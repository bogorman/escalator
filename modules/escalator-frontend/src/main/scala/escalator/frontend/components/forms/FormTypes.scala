package escalator.frontend.components.forms

import com.raquo.laminar.api.L._

/**
  * Form field type
  */
sealed trait FormFieldType
object FormFieldType {
  case object Text extends FormFieldType
  case object Email extends FormFieldType
  case object Password extends FormFieldType
  case object Number extends FormFieldType
  case object Tel extends FormFieldType
  case object Url extends FormFieldType
  case object Date extends FormFieldType
  case object Time extends FormFieldType
  case object DateTime extends FormFieldType
  case object TextArea extends FormFieldType
  case class Select(options: List[(String, String)]) extends FormFieldType // (value, label)
  case object Checkbox extends FormFieldType
  case object Radio extends FormFieldType
}

/**
  * Validation result
  */
sealed trait ValidationResult
object ValidationResult {
  case object Valid extends ValidationResult
  case class Invalid(error: String) extends ValidationResult
}

/**
  * Validator function
  */
case class Validator[A](validate: A => ValidationResult)

/**
  * Field state containing value, errors, and touched status
  */
case class FieldState[A](
  value: A,
  errors: List[String] = List.empty,
  touched: Boolean = false,
  disabled: Boolean = false
) {
  def isValid: Boolean = errors.isEmpty
  def hasError: Boolean = errors.nonEmpty

  def withValue(newValue: A): FieldState[A] = copy(value = newValue)
  def withErrors(newErrors: List[String]): FieldState[A] = copy(errors = newErrors)
  def markTouched: FieldState[A] = copy(touched = true)
  def markUntouched: FieldState[A] = copy(touched = false)
  def setDisabled(isDisabled: Boolean): FieldState[A] = copy(disabled = isDisabled)
}

/**
  * Pre-built validators
  */
object FormValidation {

  /**
    * Required field validator
    */
  def required[A](errorMsg: String = "This field is required"): Validator[A] = Validator { value =>
    value match {
      case s: String if s.trim.isEmpty => ValidationResult.Invalid(errorMsg)
      case None => ValidationResult.Invalid(errorMsg)
      case Some(s: String) if s.trim.isEmpty => ValidationResult.Invalid(errorMsg)
      case _ => ValidationResult.Valid
    }
  }

  /**
    * Email validator
    */
  def email(errorMsg: String = "Invalid email address"): Validator[String] = Validator { value =>
    val emailRegex = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r
    if (value.trim.isEmpty) {
      ValidationResult.Valid // Let required handle empty
    } else if (emailRegex.matches(value.trim)) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errorMsg)
    }
  }

  /**
    * Min length validator
    */
  def minLength(length: Int, errorMsg: Option[String] = None): Validator[String] = Validator { value =>
    if (value.trim.isEmpty) {
      ValidationResult.Valid // Let required handle empty
    } else if (value.length >= length) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errorMsg.getOrElse(s"Must be at least $length characters"))
    }
  }

  /**
    * Max length validator
    */
  def maxLength(length: Int, errorMsg: Option[String] = None): Validator[String] = Validator { value =>
    if (value.length <= length) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errorMsg.getOrElse(s"Must be at most $length characters"))
    }
  }

  /**
    * Pattern validator
    */
  def pattern(regex: String, errorMsg: String = "Invalid format"): Validator[String] = Validator { value =>
    if (value.trim.isEmpty) {
      ValidationResult.Valid // Let required handle empty
    } else if (value.matches(regex)) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errorMsg)
    }
  }

  /**
    * Numeric range validator
    */
  def range(min: Double, max: Double, errorMsg: Option[String] = None): Validator[String] = Validator { value =>
    try {
      val num = value.toDouble
      if (num >= min && num <= max) {
        ValidationResult.Valid
      } else {
        ValidationResult.Invalid(errorMsg.getOrElse(s"Must be between $min and $max"))
      }
    } catch {
      case _: NumberFormatException =>
        ValidationResult.Invalid("Must be a valid number")
    }
  }

  /**
    * Min value validator
    */
  def min(minValue: Double, errorMsg: Option[String] = None): Validator[String] = Validator { value =>
    if (value.trim.isEmpty) {
      ValidationResult.Valid // Let required handle empty
    } else {
      try {
        val num = value.toDouble
        if (num >= minValue) {
          ValidationResult.Valid
        } else {
          ValidationResult.Invalid(errorMsg.getOrElse(s"Must be at least $minValue"))
        }
      } catch {
        case _: NumberFormatException =>
          ValidationResult.Invalid("Must be a valid number")
      }
    }
  }

  /**
    * Max value validator
    */
  def max(maxValue: Double, errorMsg: Option[String] = None): Validator[String] = Validator { value =>
    if (value.trim.isEmpty) {
      ValidationResult.Valid // Let required handle empty
    } else {
      try {
        val num = value.toDouble
        if (num <= maxValue) {
          ValidationResult.Valid
        } else {
          ValidationResult.Invalid(errorMsg.getOrElse(s"Must be at most $maxValue"))
        }
      } catch {
        case _: NumberFormatException =>
          ValidationResult.Invalid("Must be a valid number")
      }
    }
  }

  /**
    * Match another field validator
    */
  def matches(otherValue: String, fieldName: String = "password"): Validator[String] = Validator { value =>
    if (value == otherValue) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(s"Must match $fieldName")
    }
  }

  /**
    * Custom validator
    */
  def custom[A](predicate: A => Boolean, errorMsg: String): Validator[A] = Validator { value =>
    if (predicate(value)) {
      ValidationResult.Valid
    } else {
      ValidationResult.Invalid(errorMsg)
    }
  }

  /**
    * Compose multiple validators
    */
  def compose[A](validators: List[Validator[A]]): Validator[A] = Validator { value =>
    validators.foldLeft[ValidationResult](ValidationResult.Valid) { (acc, validator) =>
      acc match {
        case ValidationResult.Valid => validator.validate(value)
        case invalid => invalid // Stop at first error
      }
    }
  }

  /**
    * Run all validators and collect all errors
    */
  def validateAll[A](value: A, validators: List[Validator[A]]): List[String] = {
    validators.flatMap { validator =>
      validator.validate(value) match {
        case ValidationResult.Invalid(error) => Some(error)
        case ValidationResult.Valid => None
      }
    }
  }
}

/**
  * Field definition for FormBuilder
  */
case class FieldDef(
  name: String,
  label: String,
  fieldType: FormFieldType,
  initialValue: String = "",
  validators: List[Validator[String]] = List.empty,
  placeholder: Option[String] = None,
  helpText: Option[String] = None,
  disabled: Boolean = false
)

/**
  * Form submission data
  */
case class FormData(values: Map[String, String]) {
  def get(fieldName: String): Option[String] = values.get(fieldName)
  def apply(fieldName: String): String = values.getOrElse(fieldName, "")
}
