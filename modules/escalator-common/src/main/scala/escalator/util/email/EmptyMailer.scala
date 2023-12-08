package escalator.util.email

import escalator.util.disk._
import escalator.util.logging._

class EmptyMailer extends EscalatorMailer {

	def sendEmail(toField: String, subject: String, message: String, isHtml: Boolean): EscalatorMailResult = {
		val email = s"""
		TO: ${toField}
		SUBJECT: ${subject}
		MESSAGE: ${message}
		"""

		LogUtil.append("local_emailer.log", email)
		val mailLogFileName = s"email_${toField}_${subject}".replaceAll(" ","")
											.replaceAll("@","_at_")
											// .replaceAll("@","_")

		LogUtil.append(mailLogFileName + ".log", message)

		return EscalatorMailResult(true,Some("logged"))
	}

}