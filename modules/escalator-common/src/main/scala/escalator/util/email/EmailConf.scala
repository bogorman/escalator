package escalator.util.email

object EmailConf {

  var ALLOWED: Boolean = System.getenv("EMAIL_ALLOWED") == "true"
  var EMAILER: String = System.getenv("EMAILER")

  //set to low number if using smtp.
  var MAX_EMAIL_RATE_PER_MINUTE: Int = if (System.getenv("MAX_EMAIL_RATE_PER_MINUTE") != null){
    System.getenv("MAX_EMAIL_RATE_PER_MINUTE").toInt
  } else {
    10
  }

  lazy val sgApiKey = System.getenv("SG_API_KEY")

  val fromAddress = System.getenv("SG_EMAIL")
  val fromName = System.getenv("SG_NAME")

  lazy val sgConfig = new SendGridConfig(
    sgApiKey,
    fromAddress,
    fromName)


  val sendErrorTo = ""
  val sendInfoToAdmins = ""


  def defaultMailer(): EscalatorMailer = {
    if (fromAddress == null || fromAddress == ""){
      throw new Exception("EmailConf fromAddress not set correctly")
    }

    if (ALLOWED) {
      if (EMAILER == "sendgrid"){
        new SendGridMailer  
      } else if (EMAILER == "smtp"){
        new SmtpMailer  
      } else {
        new EmptyMailer 
      }
    } else {
      new EmptyMailer
    }
  }

  def backupMailer(): EscalatorMailer = {
    if (ALLOWED) {
      new SmtpMailer
    } else {
      new EmptyMailer
    }
  }


}
