package escalator.web.auth

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CryptoSpec extends AnyFunSuite with Matchers {

  private val crypto = new Crypto(pepper = "pepper-1", secretKey = "secret-1")

  test("a password checks against its own hash only") {
    val stored = crypto.hashPassword("correct horse")
    crypto.checkPassword("correct horse", stored) shouldBe true
    crypto.checkPassword("wrong horse", stored) shouldBe false
  }

  test("neither the stored hash nor a plaintext seed row works as a password") {
    val stored = crypto.hashPassword("correct horse")
    crypto.checkPassword(stored, stored) shouldBe false
    crypto.checkPassword("password", "password") shouldBe false
    crypto.checkPassword("password", null) shouldBe false
  }

  test("the pepper is part of the hash") {
    val stored = crypto.hashPassword("correct horse")
    new Crypto("pepper-2", "secret-1").checkPassword("correct horse", stored) shouldBe false
  }

  test("keyed hashes are stable per pepper and differ across peppers") {
    crypto.keyedHash("code") shouldBe crypto.keyedHash("code")
    crypto.keyedHash("code") should not be new Crypto("pepper-2", "secret-1").keyedHash("code")
  }

  test("AES-GCM round-trips strings and bytes, with a fresh IV each time") {
    val a = crypto.encrypt("hello".getBytes("UTF-8"))
    a should not be crypto.encrypt("hello".getBytes("UTF-8"))
    new String(crypto.decrypt(a), "UTF-8") shouldBe "hello"
    crypto.decryptBytes(crypto.encryptBytes(Array[Byte](1, 2, 3))).toSeq shouldBe Seq[Byte](1, 2, 3)
    an[Exception] should be thrownBy new Crypto("pepper-1", "other-secret").decrypt(a)
  }

  test("codes are six digits and tokens are url-safe") {
    all((1 to 50).map(_ => crypto.sixDigitCode())) should fullyMatch regex "\\d{6}"
    crypto.token(32) should fullyMatch regex "[A-Za-z0-9_-]+"
  }
}
