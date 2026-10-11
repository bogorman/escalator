package escalator.agent.protocol

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.deriveEncoder

/**
 * ==Which models each engine offers==
 *
 * The shapes behind `escalator.agent.ModelCatalog`. The engines are
 * server-side only, so there is no access ladder: a row is either enabled (offered) or not.
 * Model ids are the engine's own (`claude-opus-5-5`, or a CLI alias such as
 * `opus`); `meta` is carried opaquely.
 */
object EngineModelsProtocol {

  /** Where a row came from. */
  object ModelSource {
    val Import: String = "import"
    val Manual: String = "manual"
    val Config: String = "config"
  }

  /** The engine ids the agent module ships, in display order. */
  val engines: List[String] = List("claude-cli", "anthropic-api", "codex-cli", "openai-api")

  /** Human name for an engine id; unknown ids are their own title. */
  def titleFor(engine: String): String = engine match {
    case "claude-cli"    => "Claude CLI"
    case "anthropic-api" => "Anthropic API"
    case "codex-cli"     => "Codex CLI"
    case "openai-api"    => "OpenAI API"
    case other           => other
  }

  /** One row of one engine's catalog. `enabled = false` is a soft delete. */
  final case class EngineModel(
      engine:      String,
      modelId:     String,
      label:       String,
      description: Option[String] = None,
      isDefault:   Boolean        = false,
      enabled:     Boolean        = true,
      position:    Int            = 0,
      source:      String         = ModelSource.Manual,
      meta:        Option[Json]   = None
  )
  object EngineModel {
    implicit val encoder: Encoder[EngineModel] = deriveEncoder[EngineModel].mapJson(_.dropNullValues)
    implicit val decoder: Decoder[EngineModel] = Decoder.instance { c =>
      for {
        engine  <- c.getOrElse[String]("engine")("")
        modelId <- c.getOrElse[String]("modelId")("")
        label   <- c.getOrElse[Option[String]]("label")(None)
        desc    <- c.getOrElse[Option[String]]("description")(None)
        isDef   <- c.getOrElse[Boolean]("isDefault")(false)
        enabled <- c.getOrElse[Boolean]("enabled")(true)
        pos     <- c.getOrElse[Int]("position")(0)
        source  <- c.getOrElse[String]("source")(ModelSource.Manual)
        meta    <- c.getOrElse[Option[Json]]("meta")(None)
      } yield EngineModel(
        engine      = engine.trim,
        modelId     = modelId.trim,
        label       = label.map(_.trim).filter(_.nonEmpty).getOrElse(modelId.trim),
        description = desc.map(_.trim).filter(_.nonEmpty),
        isDefault   = isDef,
        enabled     = enabled,
        position    = pos,
        source      = source.trim,
        meta        = meta.filterNot(_.isNull)
      )
    }
  }

  /** One engine's card: its models in display order. */
  final case class EngineModels(engine: String, title: String, models: List[EngineModel])
  object EngineModels {
    implicit val encoder: Encoder.AsObject[EngineModels] = deriveEncoder
  }

  /** One model an engine offers, as a picker shows it. */
  final case class ModelOption(
      id:          String,
      label:       String,
      description: Option[String] = None,
      isDefault:   Boolean        = false
  )
  object ModelOption {
    implicit val encoder: Encoder[ModelOption] = deriveEncoder[ModelOption].mapJson(_.dropNullValues)
  }

  /** One engine as a picker sees it: models, capabilities, and the credential it would run on. */
  final case class ProviderAvailability(
      id:           String,
      models:       List[ModelOption],
      capabilities: List[String],
      defaultModel: Option[String],
      credential:   String,
      available:    Boolean
  )
  object ProviderAvailability {
    implicit val encoder: Encoder.AsObject[ProviderAvailability] = deriveEncoder
  }

  /** Validate a saved catalog for one engine: ids present and unique, at most one default. */
  def validate(engine: String, models: Seq[EngineModel]): Either[String, List[EngineModel]] = {
    val cleaned = models.toList.zipWithIndex.map { case (m, i) =>
      m.copy(
        engine   = engine,
        modelId  = m.modelId.trim,
        label    = if (m.label.trim.isEmpty) m.modelId.trim else m.label.trim,
        position = i,
        source   = if (m.source.trim.isEmpty) ModelSource.Manual else m.source.trim
      )
    }
    val blank    = cleaned.zipWithIndex.collectFirst { case (m, i) if m.modelId.isEmpty => i }
    val dupes    = cleaned.map(_.modelId).groupBy(identity).collect { case (id, xs) if xs.size > 1 => id }.toList.sorted
    val defaults = cleaned.count(_.isDefault)
    if (blank.isDefined) Left(s"model ${blank.get + 1} has no model id")
    else if (dupes.nonEmpty) Left(s"duplicate model id: ${dupes.mkString(", ")}")
    else if (defaults > 1) Left(s"only one model can be the default ($defaults are marked)")
    else Right(cleaned)
  }
}
