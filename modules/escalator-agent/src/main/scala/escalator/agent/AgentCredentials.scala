package escalator.agent

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

import io.circe.parser.parse

/**
 * A credential a direct-API provider runs on. Owner-only app: there are no
 * per-user keys, only what the SERVER has. `toString` never prints the secret.
 */
sealed trait ProviderCredential {

  /** Secret-free label: `api-key` | `proxy`. */
  def source: String
}

object ProviderCredential {

  /** A real API key (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`), sent to the provider's own endpoint. */
  final case class ApiKey(key: String, baseUrl: Option[String] = None) extends ProviderCredential {
    val source: String               = "api-key"
    override def toString: String    = s"ApiKey(${CredentialSource.mask(key)}, ${baseUrl.getOrElse("default")})"
  }

  /** The shared cliproxy: `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN` from `$CLAUDE_CONFIG_DIR/settings.json`. */
  final case class Proxy(baseUrl: String, authToken: String, configDir: Path) extends ProviderCredential {
    val source: String            = "proxy"
    override def toString: String = s"Proxy($baseUrl, ${CredentialSource.mask(authToken)}, $configDir)"
  }
}

/** Secret-free credential labels, as stored on a conversation row and shown in a UI. */
object CredentialSource {
  val ApiKey: String    = "api-key"
  val Proxy: String     = "proxy"
  val CliConfig: String = "cli-config"
  val NoCredential: String = "none"

  val all: List[String] = List(ApiKey, Proxy, CliConfig, NoCredential)

  /** `sk-ant-…4f2a` → `••••4f2a`. Never more than the last four characters. */
  def mask(secret: String): String =
    if (secret.length <= 8) "••••" else "••••" + secret.takeRight(4)
}

/**
 * ==Where the Anthropic credential comes from==
 *
 * One order, shared by the API provider and the CLI (and by `LlmRater`):
 *
 *  1. `ANTHROPIC_API_KEY` in the environment — a real key, direct to
 *     api.anthropic.com (or `ANTHROPIC_API_BASE_URL` if set);
 *  2. otherwise the shared cliproxy: `$CLAUDE_CONFIG_DIR/settings.json`
 *     (default `~/.claude-cliproxy`), whose `env` holds `ANTHROPIC_BASE_URL`
 *     and `ANTHROPIC_AUTH_TOKEN` (written by cliproxy's `client-setup.sh`);
 *  3. otherwise nothing — the API provider is then unavailable.
 *
 * Pure over its inputs (environment, home dir, a file reader) so the order can
 * be tested without touching the real home directory.
 */
object AnthropicCredentials {

  val DefaultConfigDirName: String = ".claude-cliproxy"

  /** The Claude config home: `$CLAUDE_CONFIG_DIR`, else `~/.claude-cliproxy`. */
  def configDir(env: Map[String, String], home: String): Path =
    env.get("CLAUDE_CONFIG_DIR").map(_.trim).filter(_.nonEmpty) match {
      case Some(dir) => Paths.get(expandHome(dir, home))
      case None      => Paths.get(home, DefaultConfigDirName)
    }

  def resolve(
      env:      Map[String, String],
      home:     String,
      readFile: Path => Option[String] = defaultRead
  ): Option[ProviderCredential] =
    env.get("ANTHROPIC_API_KEY").map(_.trim).filter(_.nonEmpty) match {
      case Some(key) =>
        Some(ProviderCredential.ApiKey(key, env.get("ANTHROPIC_API_BASE_URL").map(_.trim).filter(_.nonEmpty)))
      case None => proxy(configDir(env, home), readFile)
    }

  /** The proxy credential from `<dir>/settings.json`, when both values are present. */
  def proxy(dir: Path, readFile: Path => Option[String] = defaultRead): Option[ProviderCredential.Proxy] =
    readFile(dir.resolve("settings.json")).flatMap(parse(_).toOption).flatMap { j =>
      val e = j.hcursor.downField("env")
      for {
        url   <- e.get[String]("ANTHROPIC_BASE_URL").toOption.map(_.trim).filter(_.nonEmpty)
        token <- e.get[String]("ANTHROPIC_AUTH_TOKEN").toOption.map(_.trim).filter(_.nonEmpty)
      } yield ProviderCredential.Proxy(url, token, dir)
    }

  /** From the real process environment. */
  def fromEnv(): Option[ProviderCredential] = resolve(sys.env, sys.props("user.home"))

  private def defaultRead(p: Path): Option[String] =
    Try(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).toOption

  private def expandHome(p: String, home: String): String =
    if (p == "~") home else if (p.startsWith("~/")) home + p.drop(1) else p
}

/**
 * ==The environment a `claude` child process runs with==
 *
 * Built explicitly per turn rather than inherited wholesale:
 *
 *  - `CLAUDE_CONFIG_DIR` is ALWAYS set (default `~/.claude-cliproxy`), so the
 *    CLI reads the proxy settings rather than whatever login `~/.claude` has;
 *  - inherited `ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` are removed, so a
 *    stray parent variable cannot override the config home;
 *  - `ANTHROPIC_API_KEY` is passed only when that is the resolved credential;
 *  - every other inherited `CLAUDE*` variable is removed (`CLAUDECODE`,
 *    `CLAUDE_CODE_*`, `CLAUDE_EFFORT`, …), so a backend started from inside a
 *    Claude Code session does not hand its session state to the child.
 */
object ClaudeCliEnv {

  /** Variables never inherited from the parent. */
  val Stripped: Set[String] = Set("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY", "CLAUDECODE")

  def build(parent: Map[String, String], configDir: Path, credential: Option[ProviderCredential]): Map[String, String] = {
    val base = parent.filter { case (k, _) => !Stripped.contains(k) && !k.startsWith("CLAUDE") }
    val withDir = base + ("CLAUDE_CONFIG_DIR" -> configDir.toString)
    credential match {
      case Some(ProviderCredential.ApiKey(key, _)) => withDir + ("ANTHROPIC_API_KEY" -> key)
      case _                                       => withDir // the proxy comes from <configDir>/settings.json
    }
  }
}
