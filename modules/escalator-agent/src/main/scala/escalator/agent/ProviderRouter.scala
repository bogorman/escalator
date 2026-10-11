package escalator.agent

import escalator.agent.protocol.EngineModelsProtocol.{ModelOption, ProviderAvailability}

/**
 * ==Which engine answers==
 *
 * A registry plus one decision. The decision is capability-first: a profile
 * declares what it NEEDS of an engine ([[AgentProfile.requires]]) and a
 * provider that cannot satisfy that is never offered, whatever the config says.
 * A provider with no credential (or no model, for OpenAI) is never offered
 * either, so a host without `ANTHROPIC_API_KEY` and without the cliproxy still
 * answers on the CLI engine.
 */
class ProviderRouter(
    providers:      Seq[AgentProvider],
    enabledIds:     Set[String],
    defaultChat:    String,
    defaultCodegen: String,
    catalog:        ModelCatalog = ModelCatalog.empty
) {

  private val byIdMap: Map[String, AgentProvider] = providers.map(p => p.id -> p).toMap

  /** Registered AND enabled, in registration order. An empty `enabledIds` enables all. */
  val enabled: Seq[AgentProvider] = providers.filter(p => enabledIds.isEmpty || enabledIds.contains(p.id))

  def byId(id: String): Option[AgentProvider] = byIdMap.get(id).filter(p => enabled.exists(_.id == p.id))

  /** True when a profile needs a real machine (workspace + shell). */
  def isCodegen(profile: AgentProfile): Boolean = profile.needsWorkspaceTools

  def usable(profile: AgentProfile, p: AgentProvider): Boolean =
    p.capabilities.satisfies(profile.requires) && p.configured && p.credentialAvailable

  /**
   * Pick an engine for one conversation:
   *  1. an explicit request wins, IF it is enabled and usable for the profile;
   *  2. else the configured default for the profile's class (chat vs codegen);
   *  3. else the first enabled usable provider.
   */
  def pick(profile: AgentProfile, requested: Option[String]): Option[AgentProvider] = {
    val wanted    = requested.map(_.trim).filter(_.nonEmpty).flatMap(byId).filter(usable(profile, _))
    val byDefault = byId(if (isCodegen(profile)) defaultCodegen else defaultChat).filter(usable(profile, _))
    wanted.orElse(byDefault).orElse(enabled.find(usable(profile, _)))
  }

  /** The admin catalog when it has rows for the engine, the provider's own list otherwise. */
  def modelsFor(provider: AgentProvider): Seq[ModelInfo] = {
    val fromCatalog = catalog.models(provider.id)
    if (fromCatalog.nonEmpty) fromCatalog else provider.models
  }

  def defaultModelFor(provider: AgentProvider): String = {
    val offered = modelsFor(provider)
    offered.find(_.isDefault).orElse(offered.headOption).map(_.id).getOrElse(provider.defaultModel)
  }

  /** The first candidate this engine offers, else its default. A stale candidate is skipped, never an error. */
  def pickModel(provider: AgentProvider, candidates: Seq[Option[String]]): String = {
    val offered = modelsFor(provider)
    candidates.iterator
      .flatMap(_.map(_.trim).filter(_.nonEmpty))
      .find(m => offered.isEmpty || offered.exists(_.id == m))
      .getOrElse(defaultModelFor(provider))
  }

  def pickModel(provider: AgentProvider, requested: Option[String]): String = pickModel(provider, Seq(requested))

  /** Validate an EXPLICIT model request before a session is opened. `Left` lists what the engine offers. */
  def validateModel(provider: AgentProvider, requested: Option[String]): Either[String, Option[String]] =
    requested.map(_.trim).filter(_.nonEmpty) match {
      case None => Right(None)
      case Some(id) =>
        val offered = modelsFor(provider)
        if (offered.isEmpty || offered.exists(_.id == id)) Right(Some(id))
        else Left(s"unknown model '$id' for engine '${provider.id}'; valid ids: ${offered.map(_.id).mkString(", ")}")
    }

  /** Every enabled engine as a picker sees it. */
  def availability: List[ProviderAvailability] =
    enabled.map { p =>
      val options = catalog.offers(p.id) match {
        case xs if xs.nonEmpty => xs.toList
        case _                 => p.models.map(m => ModelOption(m.id, m.label, None, m.isDefault)).toList
      }
      ProviderAvailability(
        id           = p.id,
        models       = options,
        capabilities = p.capabilities.caps.map(_.ident).toList.sorted,
        defaultModel = Option(defaultModelFor(p)).filter(_.nonEmpty),
        credential   = if (p.credentialAvailable) p.credentialLabel else CredentialSource.NoCredential,
        available    = p.configured && p.credentialAvailable
      )
    }.toList
}

object ProviderRouter {

  /** Chat defaults to the direct Messages API (fast, prompt-cached); codegen needs a workspace and a shell. */
  val DefaultChat: String    = "anthropic-api"
  val DefaultCodegen: String = "claude-cli"

  def apply(providers: Seq[AgentProvider], settings: AgentSettings, catalog: ModelCatalog): ProviderRouter =
    new ProviderRouter(providers, settings.enabledProviders, settings.defaultChat, settings.defaultCodegen, catalog)
}
