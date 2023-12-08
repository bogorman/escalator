package escalator.util.email

case class EscalatorMailResult(
  success: Boolean,
  messageId: Option[String]
)

trait EscalatorMailer {
	def sendEmail(toField: String, subject: String, message: String, isHtml: Boolean): EscalatorMailResult
}