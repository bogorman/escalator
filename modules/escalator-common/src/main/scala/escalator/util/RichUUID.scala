// package escalator.util

// import java.util.UUID

// object RichUUID {
//   val BLANK_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
  
//   implicit class RichUUID(uuid: UUID) {
//     def toLong: Long = uuid.getLeastSignificantBits ^ uuid.getMostSignificantBits
  
//     def blankUuid() = {
//       BLANK_UUID
//     }

//     def isBlank_?(): Boolean = {
//       false
//     }
//   }
// }
