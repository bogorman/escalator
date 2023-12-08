package escalator.util.email

import com.sendgrid._
import com.sendgrid.helpers.mail.Mail
import com.sendgrid.helpers.mail.objects._
import escalator.util.disk._
import escalator.util.logging._
import scala.jdk.CollectionConverters._

case class SendGridConfig(
  apiKey: String,
  fromAddress: String,
  fromName: String
)

// case class SendGridResult(
//   success: Boolean,
//   messageId: Option[String]
// )

class SendGridMailer extends EscalatorMailer {

  def sendEmail(toField: String, subject: String, message: String, isHtml: Boolean): EscalatorMailResult = {
    if (message == ""){
      println("Empty mail message")
      return EscalatorMailResult(false,None)
    }

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
    val apiKey = EmailConf.sgConfig.apiKey

    if (apiKey == ""){
      println("NO SENDGRID KEY")
      LogUtil.append("email.log", s"---- START ${toField} ----")
      LogUtil.append("email.log", message)
      LogUtil.append("email.log", s"---- END ${toField} ----")
      // false
      EscalatorMailResult(false,None)
    } else {
      println("apiKey:" + apiKey)

      val sg = new SendGrid(apiKey)
      val request = new com.sendgrid.Request();
      try {
        request.setMethod(Method.POST)
        request.setEndpoint("mail/send")
        request.setBody(mail.build)
        val response = sg.api(request)
        println("SENDING MAIL to " + toField)
        println("STATUSCODE:" + response.getStatusCode())
        println("BODY:" + response.getBody())
        println("HEADER:" + response.getHeaders())
        // } catch (IOException ex) {
        // System.out.println(response.getStatusCode());
        // throw ex;
        // }

        val uniqueMessageIdOpt = getUniqueMessageId(response)
        println(s"Unique Message ID: $uniqueMessageIdOpt")

        // true
        EscalatorMailResult(true,uniqueMessageIdOpt)
      } catch {
        case ex: Exception => {
          // System.out.println(response.getStatusCode());
          println("FAIED TO SEND")
          ex.printStackTrace
        }
        // false
        EscalatorMailResult(false,None)
      }
    }
  }

  def getUniqueMessageId(response: Response): Option[String] = {
    val headers = response.getHeaders.asScala
    headers.get("X-Message-Id")
    // OrElse("X-Message-Id", "No Message ID Found")
  }

// object SendGridBounces {
//   def main(args: Array[String]): Unit = {
//     val apiKey = "YOUR_SENDGRID_API_KEY" // Replace with your SendGrid API key
//     val request = basicRequest
//       .header("Authorization", s"Bearer $apiKey")
//       .header("Content-Type", MediaType.ApplicationJson.toString)
//       .header("Accept", MediaType.ApplicationJson.toString)
//       .get(uri"https://api.sendgrid.com/v3/suppression/bounces")

//     val backend = HttpURLConnectionBackend()
//     val response = request.send(backend)

//     response.body match {
//       case Right(content) => println(s"Response content: $content")
//       case Left(error) => println(s"Error: $error")
//     }
//   }
// }


  def retrieveBouneEmails() = {
    // val apiKey = EmailConf.sgConfig.apiKey

    // import sttp.client3._
    // import sttp.model.MediaType
    // import scala.util.parsing.json._
    
  //   val backend = HttpURLConnectionBackend()
  //   var offset = 0
  //   val limit = 100 // Adjust the page size as needed
  //   var hasMore = true

  //   while (hasMore) {
  //     val request = basicRequest
  //       .header("Authorization", s"Bearer $apiKey")
  //       .header("Content-Type", MediaType.ApplicationJson.toString)
  //       .header("Accept", MediaType.ApplicationJson.toString)
  //       .get(uri"https://api.sendgrid.com/v3/suppression/bounces?limit=$limit&offset=$offset")

  //     val response = request.send(backend)

  //     response.body match {
  //       case Right(content) =>
  //         val json: Any = JSON.parseFull(content).getOrElse("")
  //         json match {
  //           case data: Map[String, Any] =>
  //             val bounces = data("bounces").asInstanceOf[List[Map[String, Any]]]
  //             bounces.foreach(bounce => println(bounce))
  //             if (bounces.length < limit) hasMore = false
  //             offset += limit
  //           case _ => println("Error parsing JSON")
  //         }

  //       case Left(error) =>
  //         println(s"Error: $error")
  //         hasMore = false
  //     }
  //   }
  // }

  

//   import com.sendgrid._
//   import scala.collection.JavaConverters._

//       val apiKey = EmailConf.sgConfig.apiKey
// val client = new SendGrid(apiKey)



//     val request = new Request()

//     try {
//       request.setMethod(Method.GET)
//       request.setEndpoint("suppression/bounces")
//       // You can add query parameters to filter results, e.g., start_time and end_time
//       // request.addQueryParam("start_time", "1")

//       val response = client.api(request)
//       println("Status Code: " + response.getStatusCode)
//       println("Body: " + response.getBody)
//       println("Headers: " + response.getHeaders.asScala.mkString(", "))
//     } catch {
//       case e: Exception => e.printStackTrace()
//     }





  }
}