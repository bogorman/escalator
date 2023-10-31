package escalator.util

import java.util.UUID

object RichUUID {
  implicit class RichUUID(uuid: UUID) {
    def toLong: Long = uuid.getLeastSignificantBits ^ uuid.getMostSignificantBits
  }
}
