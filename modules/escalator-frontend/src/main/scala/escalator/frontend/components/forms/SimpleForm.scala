package escalator.frontend.components.forms

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * SimpleForm trait - Mix into components for instant form capabilities
  *
  * No akka-js dependencies - pure Laminar/Airstream
  *
  * Usage:
  * ```scala
  * object MyForm extends SimpleForm {
  *   val emailField = addField("email", "", List(required, email))
  *   val passwordField = addField("password", "", List(required, minLength(8)))
  *
  *   def render = div(
  *     renderField("email", "Email", FormFieldType.Email),
  *     renderField("password", "Password", FormFieldType.Password),
  *     renderSubmitButton("Login", handleSubmit)
  *   )
  *
  *   def handleSubmit(data: FormData): Unit = {
  *     // Handle form submission
  *   }
  * }
  * ```
  */
trait SimpleForm {

  // Internal state - map of field name to field state Var
  private val fields = scala.collection.mutable.Map[String, (Var[FieldState[String]], List[Validator[String]])]()

  // Form-level submission state
  private val isSubmittingVar = Var(false)
  val isSubmitting$ : Signal[Boolean] = isSubmittingVar.signal

  /**
    * Add a field to the form
    *
    * @param name Field name (unique identifier)
    * @param initialValue Initial value for the field
    * @param validators List of validators to apply to this field
    * @return The Var for this field's state
    */
  def addField(
    name: String,
    initialValue: String = "",
    validators: List[Validator[String]] = List.empty
  ): Var[FieldState[String]] = {
    val fieldVar = Var(FieldState(initialValue))
    fields(name) = (fieldVar, validators)
    fieldVar
  }

  /**
    * Get a field by name
    */
  def getField(name: String): Option[Var[FieldState[String]]] = {
    fields.get(name).map(_._1)
  }

  /**
    * Get field value
    */
  def getFieldValue(name: String): String = {
    fields.get(name).map(_._1.now().value).getOrElse("")
  }

  /**
    * Set field value
    */
  def setFieldValue(name: String, value: String): Unit = {
    fields.get(name).foreach { case (fieldVar, _) =>
      fieldVar.update(_.withValue(value))
    }
  }

  /**
    * Mark field as touched
    */
  def touchField(name: String): Unit = {
    fields.get(name).foreach { case (fieldVar, _) =>
      fieldVar.update(_.markTouched)
    }
  }

  /**
    * Validate a specific field
    */
  def validateField(name: String): Unit = {
    fields.get(name).foreach { case (fieldVar, validators) =>
      val currentValue = fieldVar.now().value
      val errors = FormValidation.validateAll(currentValue, validators)
      fieldVar.update(_.withErrors(errors))
    }
  }

  /**
    * Validate all fields
    * @return True if all fields are valid
    */
  def validateAll(): Boolean = {
    fields.foreach { case (name, _) =>
      validateField(name)
    }
    fields.values.forall { case (fieldVar, _) => fieldVar.now().isValid }
  }

  /**
    * Reset all fields to initial values
    */
  def resetForm(): Unit = {
    fields.foreach { case (_, (fieldVar, _)) =>
      fieldVar.set(FieldState(""))
    }
  }

  /**
    * Reset specific field
    */
  def resetField(name: String): Unit = {
    fields.get(name).foreach { case (fieldVar, _) =>
      fieldVar.set(FieldState(""))
    }
  }

  /**
    * Get all form values as FormData
    */
  def getFormData(): FormData = {
    FormData(
      fields.map { case (name, (fieldVar, _)) =>
        name -> fieldVar.now().value
      }.toMap
    )
  }

  /**
    * Set form submitting state
    */
  protected def setSubmitting(submitting: Boolean): Unit = {
    isSubmittingVar.set(submitting)
  }

  /**
    * Submit handler - validates and calls onSuccess if valid
    */
  def submit(onSuccess: FormData => Unit, onError: () => Unit = () => ()): Observer[dom.MouseEvent] = {
    Observer[dom.MouseEvent] { _ =>
      // Mark all fields as touched
      fields.foreach { case (name, _) => touchField(name) }

      // Validate all fields
      if (validateAll()) {
        setSubmitting(true)
        val formData = getFormData()
        onSuccess(formData)
        // Note: Caller should call setSubmitting(false) when done
      } else {
        onError()
      }
    }
  }

  /**
    * Render a form field using FormField component
    */
  def renderField(
    name: String,
    label: String,
    fieldType: FormFieldType,
    placeholder: Option[String] = None,
    helpText: Option[String] = None
  ): HtmlElement = {
    fields.get(name) match {
      case Some((fieldVar, validators)) =>
        FormField(
          name = name,
          labelText = label,
          fieldType = fieldType,
          value$ = fieldVar.signal.map(_.value),
          valueObserver = Observer[String] { newValue =>
            fieldVar.update(_.withValue(newValue))
          },
          errors$ = fieldVar.signal.map(_.errors),
          touched$ = fieldVar.signal.map(_.touched),
          disabled$ = fieldVar.signal.map(_.disabled),
          onBlurObserver = Observer[Unit] { _ =>
            touchField(name)
            validateField(name)
          },
          placeholderText = placeholder,
          helpText = helpText
        )
      case None =>
        div(
          className := "text-red-600",
          s"Field '$name' not found. Did you call addField('$name')?"
        )
    }
  }

  /**
    * Render a submit button
    */
  def renderSubmitButton(
    text: String,
    onSuccess: FormData => Unit,
    onError: () => Unit = () => (),
    className: String = "w-full px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
  ): HtmlElement = {
    button(
      typ := "button",
      cls := className,
      disabled <-- isSubmitting$,
      text,
      onClick.preventDefault --> submit(onSuccess, onError)
    )
  }

  /**
    * Render a cancel/reset button
    */
  def renderCancelButton(
    text: String = "Cancel",
    onCancel: () => Unit = () => resetForm(),
    className: String = "w-full px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors"
  ): HtmlElement = {
    button(
      typ := "button",
      cls := className,
      text,
      onClick.preventDefault --> Observer[dom.MouseEvent] { _ =>
        onCancel()
      }
    )
  }

  /**
    * Check if form is valid
    */
  def isFormValid(): Boolean = {
    fields.values.forall { case (fieldVar, _) => fieldVar.now().isValid }
  }

  /**
    * Check if form has been touched (any field touched)
    */
  def isFormTouched(): Boolean = {
    fields.values.exists { case (fieldVar, _) => fieldVar.now().touched }
  }

  /**
    * Get form valid signal
    */
  def formValid$(): Signal[Boolean] = {
    val allSignals = fields.values.map { case (fieldVar, _) => fieldVar.signal }.toList

    if (allSignals.isEmpty) {
      Val(true)
    } else {
      allSignals.tail.foldLeft[Signal[Boolean]](allSignals.head.map(_.isValid)) { (acc, signal) =>
        acc.combineWith(signal).map { case (valid, state) => valid && state.isValid }
      }
    }
  }
}
