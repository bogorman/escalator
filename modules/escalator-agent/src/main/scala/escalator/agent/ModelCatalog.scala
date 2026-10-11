package escalator.agent

import escalator.agent.protocol.EngineModelsProtocol
import escalator.agent.protocol.EngineModelsProtocol._

/**
 * ==Which models an engine offers==
 *
 * One answer for the picker, the availability payload and the moment a session
 * opens. An engine with SAVED rows is described by them; an engine with none
 * falls back to the provider's own list (from env config, see
 * [[AgentSettings]]). Owner-only app: there is no access gate, every ENABLED
 * row is offered.
 *
 * Reads are synchronous: they happen while a turn is being set up.
 */
trait ModelCatalog {

  /** Saved rows for this engine, in position order. Empty ⇒ unmanaged. */
  def rows(engine: String): Seq[EngineModel]

  /** The engine's own list — used when nothing is saved. */
  def fallback(engine: String): Seq[ModelInfo]

  def engines: Seq[String]

  /** Enabled rows, default first; an unmanaged engine falls back to its own list. */
  def offers(engine: String): Seq[ModelOption] = {
    val live = rows(engine).filter(_.enabled)
    if (live.isEmpty) fallback(engine).map(m => ModelOption(m.id, m.label, None, m.isDefault))
    else live.sortBy(m => (!m.isDefault, m.position)).map(m => ModelOption(m.modelId, m.label, m.description, m.isDefault))
  }

  def models(engine: String): Seq[ModelInfo] = offers(engine).map(m => ModelInfo(m.id, m.label, m.isDefault))

  def defaultModel(engine: String): Option[String] = {
    val offered = models(engine)
    offered.find(_.isDefault).orElse(offered.headOption).map(_.id)
  }

  def labels(engine: String): Map[String, String] = models(engine).map(m => m.id -> m.label).toMap

  def descriptions(engine: String): Map[String, String] = offers(engine).flatMap(m => m.description.map(m.id -> _)).toMap

  /** An empty list means "anything goes" (the engine names no models of its own). */
  def knows(engine: String, modelId: String): Boolean = {
    val offered = models(engine)
    offered.isEmpty || offered.exists(_.id == modelId)
  }

  /** Check an EXPLICIT model request. `Left` names the valid ids. */
  def validate(engine: String, requested: Option[String]): Either[String, Option[String]] =
    requested.map(_.trim).filter(_.nonEmpty) match {
      case None                          => Right(None)
      case Some(id) if knows(engine, id) => Right(Some(id))
      case Some(id) =>
        Left(s"unknown model '$id' for engine '$engine'; valid ids: ${models(engine).map(_.id).mkString(", ")}")
    }

  /** An editor card: saved rows, or the fallback list seeded as unsaved `config` rows. */
  def card(engine: String): EngineModels = {
    val saved = rows(engine)
    if (saved.nonEmpty) EngineModels(engine, titleFor(engine), saved.sortBy(_.position).toList)
    else {
      val seeded = fallback(engine).zipWithIndex.map { case (m, i) =>
        EngineModel(engine, m.id, m.label, isDefault = m.isDefault, position = i, source = ModelSource.Config)
      }
      val withDefault =
        if (seeded.exists(_.isDefault) || seeded.isEmpty) seeded else seeded.head.copy(isDefault = true) +: seeded.tail
      EngineModels(engine, titleFor(engine), withDefault.toList)
    }
  }

  def cards: List[EngineModels] = engines.map(card).toList
}

object ModelCatalog {

  val empty: ModelCatalog = fixed(Map.empty, Map.empty)

  def fixed(saved: Map[String, Seq[EngineModel]], fallbacks: Map[String, Seq[ModelInfo]]): ModelCatalog =
    new ModelCatalog {
      override def rows(engine: String): Seq[EngineModel]   = saved.getOrElse(engine, Seq.empty)
      override def fallback(engine: String): Seq[ModelInfo] = fallbacks.getOrElse(engine, Seq.empty)
      override def engines: Seq[String]                     = ordered(fallbacks.keySet ++ saved.keySet)
    }

  /** The catalog of an unmanaged host: whatever the registered providers offer. */
  def fromProviders(providers: Seq[AgentProvider]): ModelCatalog =
    fixed(Map.empty, providers.map(p => p.id -> p.models).toMap)

  def ordered(ids: Iterable[String]): Seq[String] = {
    val present = ids.toSet
    val known   = EngineModelsProtocol.engines.filter(present.contains)
    known ++ ids.toList.filterNot(known.contains).distinct.sorted
  }
}
