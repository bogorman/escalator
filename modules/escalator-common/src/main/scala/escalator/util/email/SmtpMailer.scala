package escalator.util.email

import javax.mail._
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit

class SmtpMailer extends EscalatorMailer {
	
	def sendEmail(toField: String, subject: String, message: String, isHtml: Boolean): EscalatorMailResult = {
    val username = System.getenv("SMTP_EMAIL")
    val password = System.getenv("SMTP_PASSWORD")

    if (username == null || password == null){
      println("Missing SMTP_EMAIL or SMTP_PASSWORD")
      return EscalatorMailResult(false,None)
    }

    val from = username

 		val props = new Properties()
    props.put("mail.smtp.auth", "true")
    props.put("mail.smtp.starttls.enable", "true")
    props.put("mail.smtp.host", "smtp.gmail.com")
    props.put("mail.smtp.port", "587")

		val session = Session.getInstance(props, new Authenticator() {
      override protected def getPasswordAuthentication: PasswordAuthentication = {
        new PasswordAuthentication(username, password)
      }
    })

    try {
      val mimeMessage = new MimeMessage(session)
      mimeMessage.setFrom(new InternetAddress(from)) // Replace with your email
      mimeMessage.setRecipients(Message.RecipientType.TO, toField) // Replace with recipient
      mimeMessage.setSubject(subject)

      // mimeMessage.setDeliveryNotificationOptions(DeliveryNotificationOptions.OnSuccess);
      // mimeMessage.getHeaders().add("Return-Receipt-To", "tracking@minus42.com");
      // mimeMessage.getHeaders().add("Disposition-Notification-To", "tracking@minus42.com");

      // message.setText("Hello, this is a test email sent from Scala!")

      val uniqueId = "SL-" + java.util.UUID.randomUUID().toString.replace("-","")
      mimeMessage.setHeader("X-Unique-Id", uniqueId)

      if(isHtml){
          mimeMessage.setContent(message, "text/html; charset=utf-8");
      } else {
          mimeMessage.setText(message); 
      }

      Transport.send(mimeMessage)

      println("Email sent successfully:" + toField)
      EscalatorMailResult(true,Some(uniqueId))
    } catch {
      case e: MessagingException => {
      	e.printStackTrace()
  			EscalatorMailResult(false,None)
      }
    }

	}


}


// z var = null;al username = "your-email@gmail.com" // Replace with your Gmail address
//     val password = "your-password" // Replace with your Gmail password or App Password

//     val props = new Properties()
//     props.put("mail.smtp.auth", "true")
//     props.put("mail.smtp.starttls.enable", "true")
//     props.put("mail.smtp.host", "smtp.gmail.com")
//     props.put("mail.smtp.port", "587")

//     val session = Session.getInstance(props, new Authenticator() {
//       override protected def getPasswordAuthentication: PasswordAuthentication = {
//         new PasswordAuthentication(username, password)
//       }
//     })

//     try {
//       val message = new MimeMessage(session)
//       message.setFrom(new InternetAddress("your-email@gmail.com")) // Replace with your email
//       message.setRecipients(Message.RecipientType.TO, "recipient-email@gmail.com") // Replace with recipient
//       message.setSubject("Test Email from Scala")
//       message.setText("Hello, this is a test email sent from Scala!")

//       Transport.send(message)

//       println("Email sent successfully")
//     } catch {
//       case e: MessagingException => e.printStackTrace()
//     }
//   }


// {
//     private static final String MAIL_SMTP_USERNAME= "mail.smtp.user";
//     private static final String MAIL_SMTP_PASSWORD = "mail.smtp.pass";
//     private static final String MAIL_SMTP_FROM = "mail.smtp.from";

//     private static final String MAIL_SMTP_AUTH_KEY = "mail.smtp.auth";
//     private static final String MAIL_SMTP_STARTTLS_KEY = "mail.smtp.starttls.enable";
//     private static final String MAIL_SMTP_HOST_KEY = "mail.smtp.host";
//     private static final String MAIL_SMTP_PORT_KEY = "mail.smtp.port";
//     private static final String MAIL_SMTP_CHANNEL_KEY = "mail.smtp.channel";

//      static public void sendEmail(String toField,String subject,String message,boolean isHtml) throws MessagingException { 
//             GlobalLogger.debug("Sending email...");
//             com.betmonster.bmcommon.Properties bmProps = BMConfig.getProperties();
            
//             final String username = bmProps.get(MAIL_SMTP_USERNAME,null);
//             final String password = bmProps.get(MAIL_SMTP_PASSWORD,null);
//             final String fromAddress = bmProps.get(MAIL_SMTP_FROM,null);

//             Properties props = new Properties();
//             props.put(MAIL_SMTP_AUTH_KEY, bmProps.get(MAIL_SMTP_AUTH_KEY,null) );
//             props.put(MAIL_SMTP_STARTTLS_KEY, bmProps.get(MAIL_SMTP_STARTTLS_KEY,null) );
//             props.put(MAIL_SMTP_HOST_KEY, bmProps.get(MAIL_SMTP_HOST_KEY,null) );
//             props.put(MAIL_SMTP_PORT_KEY, bmProps.get(MAIL_SMTP_PORT_KEY,null) );
//             props.put(MAIL_SMTP_CHANNEL_KEY, bmProps.get(MAIL_SMTP_PORT_KEY,"plain") );

//             Session session = Session.getInstance(props,
//                     new javax.mail.Authenticator() {
//                         protected PasswordAuthentication getPasswordAuthentication() {
//                             return new PasswordAuthentication(username, password);
//                         }
//                     });

//             try {

//                 Message mimeMessage = new MimeMessage(session);
//                 mimeMessage.setFrom(new InternetAddress(fromAddress));
//                 mimeMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toField));
//                 mimeMessage.setSubject(subject);
//                 if(isHtml){
//                     mimeMessage.setContent(message, "text/html; charset=utf-8");
//                 } else {
//                     mimeMessage.setText(message); 
//                 }
//                 Transport.send(mimeMessage);

//                 GlobalLogger.debug("Email Successfully send. Check " + toField);

//             } catch (MessagingException e) {
//                 throw e;
//             }

//         }
// }