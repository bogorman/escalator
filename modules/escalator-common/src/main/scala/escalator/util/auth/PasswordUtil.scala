package escalator.util.auth

// import org.mindrot.jbcrypt.BCrypt
import escalator.util._

// import org.mindrot.jbcrypt.{BCrypt => B}

object PasswordUtil {

  def encrypt(password: String, pepper: String): String = {
    // s"$password$pepper".bcrypt
    val pepperedPass = s"$password$pepper"
    org.mindrot.jbcrypt.BCrypt.hashpw(pepperedPass, org.mindrot.jbcrypt.BCrypt.gensalt())
  }

  def matches(password: String, pepper: String, hashed: String) = {
    if (TextUtil.blank_?(hashed)) {
      false
    } else {
      // A stored value that is not a bcrypt hash (e.g. a plaintext seed) never matches.
      val pepperedPass = s"$password$pepper"
      try org.mindrot.jbcrypt.BCrypt.checkpw(pepperedPass, hashed)
      catch { case _: IllegalArgumentException => false }
    }
  }

}
