package escalator.agent

import java.nio.file.{Path, Paths}

import scala.concurrent.duration._

import escalator.agent.providers._

/**
 * ==Agent engine settings, from the environment==
 *
 * Escalator apps read all runtime settings from env vars (see AppConfig); the
 * agent follows suit. `<P>` below is the app prefix passed to `fromEnv`.
 * Every value has a safe default, and nothing here is
 * required for the app to boot.
 *
 * | variable                      | default                                  |
 * |-------------------------------|------------------------------------------|
 * | <P>_AGENT_MODEL         | claude-opus-5-5                          |
 * | <P>_AGENT_MODELS        | model + sonnet-5-5, fable-5-1, haiku-4-5 |
 * | <P>_AGENT_EFFORT        | medium (low..max, or off)                |
 * | <P>_AGENT_FALLBACKS     | off (`default` = server-side refusal fallback beta) |
 * | <P>_AGENT_PRICES        | built-in list prices (`model=in:out,…`)  |
 * | <P>_AGENT_PROVIDERS     | all four (comma list of engine ids)      |
 * | <P>_AGENT_DEFAULT_CHAT  | anthropic-api                            |
 * | <P>_AGENT_DEFAULT_CODEGEN | claude-cli                             |
 * | <P>_AGENT_WORKSPACES    | <<P>_DATA_DIR or data>/agent       |
 * | <P>_CLAUDE_BIN          | claude                                   |
 * | <P>_CLAUDE_CLI_MODEL    | <P>_AGENT_MODEL                    |
 * | CLAUDE_CONFIG_DIR             | ~/.claude-cliproxy                       |
 * | ANTHROPIC_API_KEY             | (unset ⇒ the cliproxy settings)          |
 * | <P>_CODEX_BIN / _MODEL  | codex / (the CLI's own default)          |
 * | CODEX_HOME                    | ~/.codex-cliproxy if it exists           |
 * | OPENAI_API_KEY / OPENAI_BASE_URL / <P>_OPENAI_MODEL | unset ⇒ openai-api off |
 */
final case class AgentSettings(
    anthropic:        AnthropicApiConfig,
    claudeCli:        ClaudeCliConfig,
    codexCli:         CodexCliConfig,
    openAi:           OpenAiApiConfig,
    enabledProviders: Set[String],
    defaultChat:      String,
    defaultCodegen:   String,
    workspaceRoot:    Path,
    idleTimeout:      FiniteDuration = 30.minutes,
    maxRunningTotal:  Int            = 4,
    maxRunningPerUser: Int           = 2
) {

  /** All four providers, built from these settings (cheap: nothing starts until a turn runs). */
  def providers(implicit ec: scala.concurrent.ExecutionContext, logger: escalator.util.logging.Logger): Seq[AgentProvider] =
    Seq(
      new AnthropicApiProvider(anthropic),
      new ClaudeCliProvider(claudeCli),
      new CodexCliProvider(codexCli),
      new OpenAiApiProvider(openAi)
    )
}

object AgentSettings {

  val DefaultModel: String = AnthropicApiConfig.DefaultModel

  /** Other ids the cliproxy serves; Haiku 4.5 only under its dated id. */
  val DefaultExtraModels: List[String] = List("claude-sonnet-5-5", "claude-fable-5-1", "claude-haiku-4-5-20251001")

  /**
   * @param prefix the app's env-var prefix: `fromEnv("CATCHPOLL")` reads `CATCHPOLL_AGENT_MODEL`,
   *               `CATCHPOLL_CLAUDE_BIN`, … The provider-owned names (`ANTHROPIC_API_KEY`,
   *               `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `OPENAI_*`) are never prefixed.
   */
  def fromEnv(prefix: String = "APP", env: Map[String, String] = sys.env, home: String = sys.props("user.home")): AgentSettings = {
    def p(k: String): String = s"${prefix}_$k"
    def get(k: String): Option[String] = env.get(k).map(_.trim).filter(_.nonEmpty)
    def list(k: String): Option[List[String]] =
      get(k).map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toList)

    val model  = get(p("AGENT_MODEL")).getOrElse(DefaultModel)
    val models = (model :: list(p("AGENT_MODELS")).getOrElse(DefaultExtraModels)).distinct
    val credential = AnthropicCredentials.resolve(env, home)
    val configDir  = AnthropicCredentials.configDir(env, home)
    val prices     = providers.ModelPrice.defaults ++ get(p("AGENT_PRICES")).map(providers.ModelPrice.parse).getOrElse(Map.empty)

    val anthropic = AnthropicApiConfig(
      model          = model,
      models         = models,
      effort         = get(p("AGENT_EFFORT")).getOrElse("medium"),
      maxTokens      = get(p("AGENT_MAX_TOKENS")).flatMap(_.toLongOption).getOrElse(AnthropicApiConfig.DefaultMaxTokens),
      maxIterations  = get(p("AGENT_MAX_ITERATIONS")).flatMap(_.toIntOption).getOrElse(25),
      toolTimeout    = 30.seconds,
      requestTimeout = 10.minutes,
      fallbacks      = get(p("AGENT_FALLBACKS")).getOrElse("off"),
      credential     = credential,
      prices         = prices
    )

    val cliModel = get(p("CLAUDE_CLI_MODEL")).getOrElse(model)
    val claudeCli = ClaudeCliConfig(
      bin        = get(p("CLAUDE_BIN")).getOrElse("claude"),
      models     = (cliModel :: models).distinct.map(m => ModelInfo(m, AnthropicApiProvider.label(m), isDefault = m == cliModel)),
      configDir  = configDir,
      // Only a real API key is handed to the child; the proxy comes from <configDir>/settings.json.
      credential = credential.collect { case k: ProviderCredential.ApiKey => k },
      parentEnv  = env
    )

    val codexModel = get(p("CODEX_MODEL")).getOrElse("")
    val codexHome = get("CODEX_HOME").orElse {
      val proxy = Paths.get(home, ".codex-cliproxy")
      if (java.nio.file.Files.isDirectory(proxy)) Some(proxy.toString) else None
    }
    val codexCli = CodexCliConfig(
      bin         = get(p("CODEX_BIN")).getOrElse("codex"),
      model       = codexModel,
      models      = (codexModel :: list(p("CODEX_MODELS")).getOrElse(Nil)).filter(_.nonEmpty).distinct,
      sandboxChat = "read-only",
      codexHome   = codexHome,
      // Deliberately NOT OPENAI_API_KEY: that would move Codex off its login and onto per-token billing.
      apiKey      = get(p("CODEX_API_KEY")),
      prices      = prices
    )

    val openAiModel = get(p("OPENAI_MODEL")).getOrElse("")
    val openAi = OpenAiApiConfig(
      model          = openAiModel,
      models         = (openAiModel :: list(p("OPENAI_MODELS")).getOrElse(Nil)).filter(_.nonEmpty).distinct,
      effort         = get(p("OPENAI_EFFORT")).getOrElse("medium"),
      maxTokens      = OpenAiApiConfig.DefaultMaxTokens,
      maxIterations  = 25,
      toolTimeout    = 30.seconds,
      requestTimeout = 10.minutes,
      apiKey         = get("OPENAI_API_KEY"),
      baseUrl        = get("OPENAI_BASE_URL"),
      prices         = prices
    )

    val dataDir = get(p("DATA_DIR")).orElse(get(p("DATA"))).getOrElse("data")
    AgentSettings(
      anthropic        = anthropic,
      claudeCli        = claudeCli,
      codexCli         = codexCli,
      openAi           = openAi,
      enabledProviders = list(p("AGENT_PROVIDERS")).map(_.toSet).getOrElse(Set.empty),
      defaultChat      = get(p("AGENT_DEFAULT_CHAT")).getOrElse(ProviderRouter.DefaultChat),
      defaultCodegen   = get(p("AGENT_DEFAULT_CODEGEN")).getOrElse(ProviderRouter.DefaultCodegen),
      workspaceRoot    = Paths.get(get(p("AGENT_WORKSPACES")).getOrElse(dataDir + "/agent")).toAbsolutePath
    )
  }
}
