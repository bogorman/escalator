package escalator.validators

import escalator.errors.BackendError
import escalator.validators.Validator._

object StringValidators {

  type E = BackendError

  private val sv = simpleValidator[String, E] _

  final val alphabet = "abcdefghijklmnopqrstuvwxyz"

  final val nonEmptyString = sv(_.nonEmpty, BackendError("validator.nonEmptyString", _))

  final val onlyLowercaseLetters =
    sv(_.forall(c => alphabet.exists(_ == c)), BackendError("validator.stringOnlyLowerLetters", _))

  def atLeastLength(n: Int): Validator[String, BackendError] =
    sv(_.length >= n, BackendError("validator.nonLengthString", _))

  def stringContains(substr: String): Validator[String, BackendError] =
    sv(_.contains(substr), (s: String) => BackendError("validator.shouldContain", (s, substr).toString))

  def stringDoesNotContains(
      substr: String,
      errorKey: String = "validator.shouldNotContain"
  ): Validator[String, BackendError] =
    sv(!_.contains(substr), (s: String) => BackendError(errorKey, (s, substr).toString))

  def doesNotContainAnyOf(
      substrings: List[String],
      errorKey: String = "validator.shouldNotContain"
  ): Validator[String, BackendError] =
    substrings
      .map(stringDoesNotContains(_, errorKey))
      .foldLeft[Validator[String, BackendError]](allowAllValidator)(_ ++ _)

  final val containsUppercase = sv(_.exists(_.isUpper), BackendError("validator.noUppercase", _))

  final val containsLowercase = sv(_.exists(_.isLower), BackendError("validator.noLowercase", _))

  final val containsDigit = sv(t => """\d""".r.findFirstIn(t).isDefined, BackendError("validator.noDigit", _))

  final val noSpace = stringDoesNotContains(" ", "noSpace")

  final val correctPassword = nonEmptyString

  final val emailValidator = stringContains("@") ++ noSpace

}
