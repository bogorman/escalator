package escalator.frontend.components.forms

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Declarative Form Builder
  * Build complete forms from field definitions
  *
  * Usage:
  * ```scala
  * FormBuilder(
  *   fields = List(
  *     FieldDef("email", "Email", FormFieldType.Email, validators = List(required, email)),
  *     FieldDef("password", "Password", FormFieldType.Password, validators = List(required))
  *   ),
  *   onSubmit = data => ToastService.success("Form submitted!"),
  *   submitButtonText = "Login"
  * )
  * ```
  */
object FormBuilder {

  /**
    * Create a form from field definitions
    *
    * @param fields List of field definitions
    * @param onSubmit Callback when form is successfully submitted
    * @param onError Callback when form validation fails
    * @param submitButtonText Text for submit button
    * @param showCancelButton Whether to show cancel button
    * @param onCancel Callback when cancel is clicked
    * @param formClassName Additional CSS classes for form container
    * @param layout Form layout (vertical or horizontal)
    */
  def apply(
    fields: List[FieldDef],
    onSubmitCallback: FormData => Unit,
    onError: () => Unit = () => (),
    submitButtonText: String = "Submit",
    cancelButtonText: String = "Cancel",
    showCancelButton: Boolean = false,
    onCancel: () => Unit = () => (),
    formClassName: String = "",
    layout: FormLayout = FormLayout.Vertical
  ): HtmlElement = {

    // Create field state Vars
    val fieldVars = fields.map { fieldDef =>
      fieldDef.name -> (
        Var(FieldState(
          value = fieldDef.initialValue,
          disabled = fieldDef.disabled
        )),
        fieldDef.validators
      )
    }.toMap

    // Submitting state
    val isSubmittingVar = Var(false)

    // Touch field handler
    def touchField(name: String): Unit = {
      fieldVars.get(name).foreach { case (fieldVar, _) =>
        fieldVar.update(_.markTouched)
      }
    }

    // Validate field handler
    def validateField(name: String): Unit = {
      fieldVars.get(name).foreach { case (fieldVar, validators) =>
        val currentValue = fieldVar.now().value
        val errors = FormValidation.validateAll(currentValue, validators)
        fieldVar.update(_.withErrors(errors))
      }
    }

    // Validate all fields
    def validateAll(): Boolean = {
      fields.foreach(f => validateField(f.name))
      fieldVars.values.forall { case (fieldVar, _) => fieldVar.now().isValid }
    }

    // Get form data
    def getFormData(): FormData = {
      FormData(
        fieldVars.map { case (name, (fieldVar, _)) =>
          name -> fieldVar.now().value
        }
      )
    }

    // Submit handler
    val submitHandler = Observer[dom.Event] { _ =>
      // Mark all fields as touched
      fields.foreach(f => touchField(f.name))

      // Validate all fields
      if (validateAll()) {
        isSubmittingVar.set(true)
        val formData = getFormData()
        onSubmitCallback(formData)
        isSubmittingVar.set(false)
      } else {
        onError()
      }
    }

    // Cancel handler
    val cancelHandler = Observer[dom.MouseEvent] { _ =>
      // Reset form
      fieldVars.foreach { case (_, (fieldVar, _)) =>
        fieldVar.set(FieldState(""))
      }
      onCancel()
    }

    // Render form
    div(
      className := s"max-w-md ${formClassName}",

      form(
        onSubmit.preventDefault --> submitHandler,

        // Render fields
        fields.map { fieldDef =>
          fieldVars.get(fieldDef.name).map { case (fieldVar, _) =>
            FormField(
              name = fieldDef.name,
              labelText = fieldDef.label,
              fieldType = fieldDef.fieldType,
              value$ = fieldVar.signal.map(_.value),
              valueObserver = Observer[String] { newValue =>
                fieldVar.update(_.withValue(newValue))
              },
              errors$ = fieldVar.signal.map(_.errors),
              touched$ = fieldVar.signal.map(_.touched),
              disabled$ = fieldVar.signal.map(_.disabled),
              onBlurObserver = Observer[Unit] { _ =>
                touchField(fieldDef.name)
                validateField(fieldDef.name)
              },
              placeholderText = fieldDef.placeholder,
              helpText = fieldDef.helpText
            )
          }
        },

        // Buttons
        div(
          cls := (if (showCancelButton) "flex gap-3" else ""),

          // Cancel button
          if (showCancelButton) {
            button(
              typ := "button",
              cls := "flex-1 px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors",
              cancelButtonText,
              onClick.preventDefault --> cancelHandler
            )
          } else {
            emptyNode
          },

          // Submit button
          button(
            typ := "submit",
            cls := s"${if (showCancelButton) "flex-1" else "w-full"} px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors",
            disabled <-- isSubmittingVar.signal,
            submitButtonText,
            onClick.preventDefault --> submitHandler
          )
        )
      )
    )
  }

  /**
    * Create a compact inline form (for search, filters, etc.)
    */
  def inline(
    fields: List[FieldDef],
    onSubmitCallback: FormData => Unit,
    submitButtonText: String = "Go",
    formClassName: String = ""
  ): HtmlElement = {

    // Create field state Vars
    val fieldVars = fields.map { fieldDef =>
      fieldDef.name -> Var(FieldState(fieldDef.initialValue))
    }.toMap

    // Get form data
    def getFormData(): FormData = {
      FormData(
        fieldVars.map { case (name, fieldVar) =>
          name -> fieldVar.now().value
        }
      )
    }

    // Submit handler
    val submitHandler = Observer[dom.Event] { _ =>
      val formData = getFormData()
      onSubmitCallback(formData)
    }

    form(
      className := s"flex gap-2 items-end ${formClassName}",
      onSubmit.preventDefault --> submitHandler,

      // Render fields inline
      fields.map { fieldDef =>
        fieldVars.get(fieldDef.name).map { fieldVar =>
          div(
            className := "flex-1",
            label(
              forId := fieldDef.name,
              className := "block text-xs font-medium text-gray-700 dark:text-gray-300 mb-1",
              fieldDef.label
            ),
            input(
              idAttr := fieldDef.name,
              typ := "text",
              placeholder := fieldDef.placeholder.getOrElse(""),
              className := "w-full px-3 py-2 text-sm border border-gray-300 dark:border-gray-600 rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500",
              controlled(
                value <-- fieldVar.signal.map(_.value),
                onInput.mapToValue --> Observer[String] { newValue =>
                  fieldVar.update(_.withValue(newValue))
                }
              )
            )
          )
        }
      },

      // Submit button
      button(
        typ := "submit",
        className := "px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 transition-colors",
        submitButtonText
      )
    )
  }
}

/**
  * Form layout options
  */
sealed trait FormLayout
object FormLayout {
  case object Vertical extends FormLayout
  case object Horizontal extends FormLayout
}
