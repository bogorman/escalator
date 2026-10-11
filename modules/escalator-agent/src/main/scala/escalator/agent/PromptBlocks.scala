package escalator.agent

import escalator.agent.protocol.PromptBlocksProtocol.BlockGroup

/**
 * ==The editable pieces of an agent's system prompt==
 *
 * A fixed registry of BLOCKS — each with a stable key, a title, a group and a
 * CODE DEFAULT — that the owner may override one at a time. Two kinds of key:
 *
 *   `profile.<id>`   what one agent is and how it works (its base prompt,
 *                    defaulting to the profile's own `systemPrompt`);
 *   `shared.<name>`  text appended to EVERY profile (empty by default — the
 *                    owner's to write, e.g. house style).
 *
 * The registry is derived from the registered profiles, so a new profile gets
 * an editable block without touching this file. With no overrides the composed
 * prompt is byte-for-byte the profile's own text.
 */
object PromptBlocks {

  final case class Block(
      key:            String,
      title:          String,
      group:          String,
      default:        String,
      /** Substrings an override must keep (code substitutes into them). */
      requiredTokens: Seq[String] = Nil
  ) {
    lazy val defaultSha: String = PromptBlocks.sha256(default)
  }

  val SharedGuidelines: String = "shared.guidelines"

  def profileKey(profileId: String): String = s"profile.$profileId"

  /** The biggest body a block may hold. A prompt is read on EVERY turn. */
  val MaxBytes: Int = 32 * 1024

  /** How many superseded bodies are kept per key. */
  val HistoryLimit: Int = 20

  /** Shared blocks, appended to every profile in this order. */
  val sharedBlocks: Seq[Block] = Seq(
    Block(SharedGuidelines, "Shared guidelines", BlockGroup.Shared, "")
  )

  /** Every block for these profiles: one per profile, then the shared ones. */
  def registry(profiles: AgentProfiles): Seq[Block] =
    profiles.all.map(p => Block(profileKey(p.id), titleOf(p.id), BlockGroup.Profile, p.systemPrompt)) ++ sharedBlocks

  private def titleOf(id: String): String =
    id.split('-').iterator.filter(_.nonEmpty).map(w => w.take(1).toUpperCase + w.drop(1)).mkString(" ")

  // ── validation ────────────────────────────────────────────────────────────

  /** What a saved body must satisfy. `Left(message)` names the fix. */
  def validate(blocks: Seq[Block], key: String, body: String): Either[String, String] =
    blocks.find(_.key == key) match {
      case None => Left(s"unknown prompt block '$key'")
      case Some(block) =>
        val text = body.replace("\r\n", "\n")
        if (text.trim.isEmpty) Left("the block cannot be empty — reset it to go back to the default")
        else if (text.length > MaxBytes) Left(s"too long: ${text.length} characters, the limit is $MaxBytes")
        else if (text.exists(_ == 0.toChar)) Left("the block contains a NUL character")
        else
          block.requiredTokens.find(t => !text.contains(t)) match {
            case Some(missing) => Left(s"the token $missing must appear in this block")
            case None          => Right(text)
          }
    }

  // ── composition ───────────────────────────────────────────────────────────

  /** `profile.<id>` (override, else the profile's own text), then the shared blocks; empty layers skipped. */
  def compose(profile: AgentProfile, prompts: PromptStore): String = {
    val base   = Option(prompts.resolve(profileKey(profile.id))).filter(_.trim.nonEmpty).getOrElse(profile.systemPrompt)
    val shared = sharedBlocks.map(b => prompts.resolve(b.key))
    join(base +: shared)
  }

  /** Join layers with ONE blank line between them, each normalised to one trailing newline. */
  private def join(layers: Seq[String]): String = {
    val kept = layers.filter(_.trim.nonEmpty)
    if (kept.size <= 1) kept.headOption.getOrElse("")
    else kept.map(normalise).mkString("\n")
  }

  private def normalise(layer: String): String =
    layer.reverse.dropWhile(_ == '\n').reverse + "\n"

  def sha256(s: String): String = {
    val md     = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xff}%02x").mkString
  }
}
