package escalator.agent

import scala.concurrent.Future

import escalator.agent.protocol.PromptBlocksProtocol._

/**
 * ==Where a prompt block's text comes from==
 *
 * The owner's override if there is one, the code default otherwise. [[resolve]]
 * and [[all]] are SYNCHRONOUS on purpose: they run while a turn is being set up,
 * so the DB-backed store ([[JdbcPromptStore]]) serves them from memory,
 * refreshed on write and on a TTL, and falls back to code defaults when the
 * database cannot be read.
 */
trait PromptStore {

  /** The registry this store answers for. */
  def blocks: Seq[PromptBlocks.Block]

  /** The body in force for `key` — override, else code default, else "". */
  def resolve(key: String): String

  def all(): Seq[PromptBlockSummary]

  def detail(key: String): Future[Option[PromptBlockDetail]]

  /** Save an override. `Left(message)` is a validation failure, verbatim. */
  def set(key: String, body: String, byUserId: Option[Long]): Future[Either[String, PromptBlockDetail]]

  /** Drop the override; the code default resolves afterwards. */
  def reset(key: String, byUserId: Option[Long]): Future[Option[PromptBlockDetail]]

  def default(key: String): String = blocks.find(_.key == key).map(_.default).getOrElse("")

  /** The prompt exactly as an engine receives it for one profile, with a sample context block. */
  def preview(profiles: AgentProfiles, profileId: String): Option[PromptPreview] =
    profiles.build(profileId, this).map { profile =>
      val block  = SessionContextRenderer.systemBlock(PromptStore.SampleContext)
      val prompt = if (block.trim.isEmpty) profile.systemPrompt else profile.systemPrompt + "\n\n" + block
      PromptPreview(profileId, prompt, (prompt.length + 3) / 4)
    }
}

object PromptStore {

  final case class Stored(key: String, body: String, defaultSha: String, updatedBy: Option[Long], updatedAt: Long)

  final case class Revision(body: String, updatedBy: Option[Long], updatedAt: Long)

  /** No overrides: every block resolves to the text the code ships. */
  def defaults(profiles: AgentProfiles): PromptStore =
    new StaticPromptStore(PromptBlocks.registry(profiles), () => Map.empty)

  /** An in-memory store with the real write semantics, for tests and DB-less wiring. */
  def inMemory(profiles: AgentProfiles): MutablePromptStore = new MutablePromptStore(PromptBlocks.registry(profiles))

  val SampleContext: SessionContext =
    SessionContext(name = "Owner", role = "ADMIN", tools = ToolsView(allowed = List("example_tool")))

  def summaries(blocks: Seq[PromptBlocks.Block], overrides: Map[String, Stored]): Seq[PromptBlockSummary] =
    blocks.map { b =>
      overrides.get(b.key) match {
        case Some(row) =>
          PromptBlockSummary(b.key, b.group, b.title, BlockSource.Override, row.updatedBy, Some(row.updatedAt),
            row.body.length, row.defaultSha != b.defaultSha)
        case None =>
          PromptBlockSummary(b.key, b.group, b.title, BlockSource.Default, bytes = b.default.length)
      }
    }

  def detailOf(
      blocks:    Seq[PromptBlocks.Block],
      key:       String,
      overrides: Map[String, Stored],
      history:   Seq[Revision]
  ): Option[PromptBlockDetail] =
    blocks.find(_.key == key).map { b =>
      val row = overrides.get(key)
      PromptBlockDetail(
        key          = b.key,
        title        = b.title,
        group        = b.group,
        body         = row.map(_.body).getOrElse(b.default),
        default      = b.default,
        isOverride   = row.isDefined,
        defaultDrift = row.exists(_.defaultSha != b.defaultSha),
        updatedBy    = row.flatMap(_.updatedBy),
        updatedAt    = row.map(_.updatedAt),
        history      = history.map(r => PromptBlockRevision(r.body, r.updatedBy, r.updatedAt)).toList
      )
    }
}

/** A read-only store over a snapshot of overrides. */
class StaticPromptStore(val blocks: Seq[PromptBlocks.Block], snapshot: () => Map[String, PromptStore.Stored])
    extends PromptStore {

  override def resolve(key: String): String = snapshot().get(key).map(_.body).getOrElse(default(key))

  override def all(): Seq[PromptBlockSummary] = PromptStore.summaries(blocks, snapshot())

  override def detail(key: String): Future[Option[PromptBlockDetail]] =
    Future.successful(PromptStore.detailOf(blocks, key, snapshot(), Seq.empty))

  override def set(key: String, body: String, byUserId: Option[Long]): Future[Either[String, PromptBlockDetail]] =
    Future.successful(Left("this prompt store is read-only"))

  override def reset(key: String, byUserId: Option[Long]): Future[Option[PromptBlockDetail]] =
    Future.successful(None)
}

/** The full write semantics (validation, history and its cap) without a database. */
class MutablePromptStore(val blocks: Seq[PromptBlocks.Block]) extends PromptStore {

  private var overrides: Map[String, PromptStore.Stored]           = Map.empty
  private var history:   Map[String, Vector[PromptStore.Revision]] = Map.empty

  override def resolve(key: String): String = synchronized {
    overrides.get(key).map(_.body).getOrElse(default(key))
  }

  override def all(): Seq[PromptBlockSummary] = synchronized(PromptStore.summaries(blocks, overrides))

  override def detail(key: String): Future[Option[PromptBlockDetail]] =
    Future.successful(synchronized(PromptStore.detailOf(blocks, key, overrides, history.getOrElse(key, Vector.empty))))

  override def set(key: String, body: String, byUserId: Option[Long]): Future[Either[String, PromptBlockDetail]] =
    Future.successful(synchronized {
      PromptBlocks.validate(blocks, key, body).map { text =>
        val block = blocks.find(_.key == key).get
        record(key, byUserId)
        overrides += key -> PromptStore.Stored(key, text, block.defaultSha, byUserId, System.currentTimeMillis())
        PromptStore.detailOf(blocks, key, overrides, history.getOrElse(key, Vector.empty)).get
      }
    })

  override def reset(key: String, byUserId: Option[Long]): Future[Option[PromptBlockDetail]] =
    Future.successful(synchronized {
      if (!blocks.exists(_.key == key)) None
      else {
        record(key, byUserId)
        overrides -= key
        PromptStore.detailOf(blocks, key, overrides, history.getOrElse(key, Vector.empty))
      }
    })

  private def record(key: String, byUserId: Option[Long]): Unit = {
    val current = resolve(key)
    if (current.trim.nonEmpty) {
      val rev = PromptStore.Revision(current, byUserId, System.currentTimeMillis())
      history += key -> (rev +: history.getOrElse(key, Vector.empty)).take(PromptBlocks.HistoryLimit)
    }
  }
}
