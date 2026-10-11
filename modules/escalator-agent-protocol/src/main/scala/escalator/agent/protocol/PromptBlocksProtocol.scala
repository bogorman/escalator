package escalator.agent.protocol

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

/**
 * ==The editable prompt blocks==
 *
 * An agent's system prompt is composed from BLOCKS (the profile's own text plus
 * any shared blocks it opts into), each with a code default and an optional
 * owner override. These are the shapes an admin page would read and write; the
 * composition itself lives in `escalator.agent.PromptBlocks`.
 *
 * `defaultDrift` flags an override written against a code default that has since
 * moved (a deploy changed the shipped text under an edit made earlier).
 */
object PromptBlocksProtocol {

  /** `profile` | `shared` — the sections of an editor's left rail. */
  object BlockGroup {
    val Profile: String = "profile"
    val Shared:  String = "shared"
  }

  /** `default` (the shipped text) | `override` (the owner edited it). */
  object BlockSource {
    val Default:  String = "default"
    val Override: String = "override"
  }

  /** One row in the list. No bodies. */
  final case class PromptBlockSummary(
      key:          String,
      group:        String,
      title:        String,
      source:       String,
      updatedBy:    Option[Long] = None,
      updatedAt:    Option[Long] = None,
      bytes:        Int          = 0,
      defaultDrift: Boolean      = false
  )
  object PromptBlockSummary {
    implicit val encoder: Encoder.AsObject[PromptBlockSummary] = deriveEncoder
    implicit val decoder: Decoder[PromptBlockSummary]          = deriveDecoder
  }

  /** One superseded body, newest first in [[PromptBlockDetail.history]]. */
  final case class PromptBlockRevision(body: String, updatedBy: Option[Long], updatedAt: Long)
  object PromptBlockRevision {
    implicit val encoder: Encoder.AsObject[PromptBlockRevision] = deriveEncoder
    implicit val decoder: Decoder[PromptBlockRevision]          = deriveDecoder
  }

  /** What is in force (`body`), what the code ships (`default`), and what it used to be (`history`). */
  final case class PromptBlockDetail(
      key:          String,
      title:        String,
      group:        String,
      body:         String,
      default:      String,
      isOverride:   Boolean,
      defaultDrift: Boolean                   = false,
      updatedBy:    Option[Long]              = None,
      updatedAt:    Option[Long]              = None,
      history:      List[PromptBlockRevision] = Nil
  )
  object PromptBlockDetail {
    implicit val encoder: Encoder.AsObject[PromptBlockDetail] = deriveEncoder
    implicit val decoder: Decoder[PromptBlockDetail]          = deriveDecoder
  }

  /** The prompt exactly as the engine receives it for one profile, plus a rough token estimate (chars / 4). */
  final case class PromptPreview(profile: String, prompt: String, approxTokens: Int)
  object PromptPreview {
    implicit val encoder: Encoder.AsObject[PromptPreview] = deriveEncoder
    implicit val decoder: Decoder[PromptPreview]          = deriveDecoder
  }
}
