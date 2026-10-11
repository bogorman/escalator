package escalator.web.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, HexFormat}
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

import org.mindrot.jbcrypt.BCrypt

/** Hashing, tokens, AES-GCM secrets and password storage.
  *
  * Passwords: BCrypt(base64(HMAC-SHA256(pepper, password))). Pre-hashing keeps the full pepper and long passwords
  * inside BCrypt's 72-byte limit, and a stored hash is never accepted as a
  * password. */
final class Crypto(pepper: String, secretKey: String) {
  private val rnd = new SecureRandom()

  private def hmac(key: Array[Byte], data: String): Array[Byte] = {
    val m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); m.doFinal(data.getBytes(UTF_8))
  }

  private def prehash(password: String): String = Base64.getEncoder.encodeToString(hmac(pepper.getBytes(UTF_8), password))

  def hashPassword(password: String): String = BCrypt.hashpw(prehash(password), BCrypt.gensalt(12))

  def checkPassword(password: String, stored: String): Boolean =
    stored != null && stored.startsWith("$2") && password != stored &&
      (try BCrypt.checkpw(prehash(password), stored) catch { case _: IllegalArgumentException => false })

  def token(bytes: Int = 24): String = {
    val b = new Array[Byte](bytes); rnd.nextBytes(b); Base64.getUrlEncoder.withoutPadding.encodeToString(b)
  }

  def sixDigitCode(): String = f"${rnd.nextInt(1000000)}%06d"

  /** Keyed hash for codes/tokens stored in the DB (so a DB leak doesn't reveal them). */
  def keyedHash(value: String): String = HexFormat.of().formatHex(hmac(pepper.getBytes(UTF_8), value))

  private lazy val aesKey: SecretKeySpec = new SecretKeySpec(Crypto.sha256Bytes(secretKey), "AES")

  /** AES-256-GCM. Output: v1:<iv b64>:<ciphertext b64>. */
  def encrypt(plain: Array[Byte]): String = {
    val iv = new Array[Byte](12); rnd.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv))
    val e = Base64.getEncoder
    s"v1:${e.encodeToString(iv)}:${e.encodeToString(c.doFinal(plain))}"
  }

  def decrypt(enc: String): Array[Byte] = enc.split(":") match {
    case Array("v1", iv, ct) =>
      val d = Base64.getDecoder
      val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, d.decode(iv)))
      c.doFinal(d.decode(ct))
    case _ => throw new IllegalArgumentException("bad ciphertext")
  }

  /** Raw-bytes AES-GCM for file storage: iv(12) || ciphertext. */
  def encryptBytes(plain: Array[Byte]): Array[Byte] = {
    val iv = new Array[Byte](12); rnd.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv))
    iv ++ c.doFinal(plain)
  }
  def decryptBytes(data: Array[Byte]): Array[Byte] = {
    val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, data.take(12)))
    c.doFinal(data.drop(12))
  }
}

object Crypto {
  def sha256Bytes(s: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8))
  def sha256Hex(s: String): String = HexFormat.of().formatHex(sha256Bytes(s))
  def sha256Hex(b: Array[Byte]): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b))
}
