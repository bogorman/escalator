package escalator.util.email

import com.sendgrid._
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects._

case class SendGridConfig(
  apiKey: String,
  fromAddress: String,
  fromName: String
)

class SendGridMailer() {

  def sendEmail(toField: String, subject: String, message: String, isHtml: Boolean) = {

    // val from = new Email("dev@driveleaders.com");
    val from = new Email(EmailConf.sgConfig.fromAddress, EmailConf.sgConfig.fromName)
    // String subject = "Sending with SendGrid is Fun";
    val to = new Email(toField)

    // Content content = new Content("text/plain", "and easy to do anywhere, even with Java");

    val content = if (isHtml) {
      new Content("text/html", message)
    } else {
      new Content("text/plain", message)
    }

    val mail = new Mail(from, subject, to, content)

    // System.getenv("SENDGRID_API_KEY")
    val apiKey = EmailConf.sgConfig.apiKey;

    println("apiKey:" + apiKey)

    val sg = new SendGrid(apiKey)
    val request = new Request();
    try {
      request.setMethod(Method.POST)
      request.setEndpoint("mail/send")
      request.setBody(mail.build)
      val response = sg.api(request)
      println(response.getStatusCode())
      println(response.getBody())
      println(response.getHeaders())
      // } catch (IOException ex) {
      // System.out.println(response.getStatusCode());
      // throw ex;
      // }
    } catch {
      case ex: Exception => {
        // System.out.println(response.getStatusCode());
        println("FAIED TO SEND")
        ex.printStackTrace
      }
    }

  }

}