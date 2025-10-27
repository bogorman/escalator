package escalator.frontend.components.forms

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Reusable form field component
  * Renders label, input, error messages, and help text
  */
object FormField {

  /**
    * Render a form field
    *
    * @param name Field name
    * @param label Field label
    * @param fieldType Type of field (text, email, etc.)
    * @param value$ Signal of current value
    * @param valueObserver Observer for value changes
    * @param errors$ Signal of current errors
    * @param touched$ Signal of touched state
    * @param disabled$ Signal of disabled state
    * @param onBlur Observer for blur events
    * @param placeholder Optional placeholder text
    * @param helpText Optional help text
    */
  def apply(
    name: String,
    labelText: String,
    fieldType: FormFieldType,
    value$: Signal[String],
    valueObserver: Observer[String],
    errors$: Signal[List[String]],
    touched$: Signal[Boolean],
    disabled$: Signal[Boolean] = Var(false).signal,
    onBlurObserver: Observer[Unit] = Observer.empty,
    placeholderText: Option[String] = None,
    helpText: Option[String] = None
  ): HtmlElement = {

    val hasError$ = errors$.combineWith(touched$).map { case (errs, touched) =>
      touched && errs.nonEmpty
    }

    div(
      className := "mb-4",

      // Label
      label(
        forId := name,
        className := "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1",
        labelText
      ),

      // Input field
      renderInput(name, fieldType, value$, valueObserver, disabled$, onBlurObserver, placeholderText, hasError$),

      // Error messages
      child <-- hasError$.map { hasError =>
        if (hasError) {
          div(
            className := "mt-1",
            children <-- errors$.map { errs =>
              errs.map { error =>
                p(
                  className := "text-sm text-red-600 dark:text-red-400",
                  error
                )
              }
            }
          )
        } else {
          emptyNode
        }
      },

      // Help text
      helpText.map { text =>
        p(
          className := "mt-1 text-sm text-gray-500 dark:text-gray-400",
          text
        )
      }
    )
  }

  /**
    * Render the appropriate input element based on field type
    */
  private def renderInput(
    name: String,
    fieldType: FormFieldType,
    value$: Signal[String],
    valueObserver: Observer[String],
    disabled$: Signal[Boolean],
    onBlurObserver: Observer[Unit],
    placeholderText: Option[String],
    hasError$: Signal[Boolean]
  ): HtmlElement = {

    val baseInputClass = "w-full px-3 py-2 border rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-offset-0 transition-colors"
    val normalBorderClass = "border-gray-300 dark:border-gray-600 focus:ring-blue-500 focus:border-blue-500"
    val errorBorderClass = "border-red-500 dark:border-red-400 focus:ring-red-500 focus:border-red-500"

    val inputClass$ = hasError$.map { hasError =>
      if (hasError) s"$baseInputClass $errorBorderClass" else s"$baseInputClass $normalBorderClass"
    }

    fieldType match {
      case FormFieldType.Text | FormFieldType.Email | FormFieldType.Password |
           FormFieldType.Number | FormFieldType.Tel | FormFieldType.Url |
           FormFieldType.Date | FormFieldType.Time | FormFieldType.DateTime =>
        input(
          idAttr := name,
          typ := fieldTypeToInputType(fieldType),
          placeholder := placeholderText.getOrElse(""),
          className <-- inputClass$,
          disabled <-- disabled$,
          controlled(
            value <-- value$,
            onInput.mapToValue --> valueObserver
          ),
          onBlur.mapTo(()) --> onBlurObserver
        )

      case FormFieldType.TextArea =>
        textArea(
          idAttr := name,
          placeholder := placeholderText.getOrElse(""),
          className <-- inputClass$,
          disabled <-- disabled$,
          rows := 4,
          controlled(
            value <-- value$,
            onInput.mapToValue --> valueObserver
          ),
          onBlur.mapTo(()) --> onBlurObserver
        )

      case FormFieldType.Select(options) =>
        select(
          idAttr := name,
          className <-- inputClass$,
          disabled <-- disabled$,
          controlled(
            value <-- value$,
            onChange.mapToValue --> valueObserver
          ),
          onBlur.mapTo(()) --> onBlurObserver,

          // Empty option
          option(value := "", "Select..."),

          // Options
          options.map { case (optValue, optLabel) =>
            option(value := optValue, optLabel)
          }
        )

      case FormFieldType.Checkbox =>
        div(
          className := "flex items-center",
          input(
            idAttr := name,
            typ := "checkbox",
            className := "h-4 w-4 text-blue-600 border-gray-300 rounded focus:ring-blue-500",
            disabled <-- disabled$,
            checked <-- value$.map(_ == "true"),
            onChange.mapToChecked.map(_.toString) --> valueObserver,
            onBlur.mapTo(()) --> onBlurObserver
          )
        )

      case FormFieldType.Radio =>
        // Radio buttons would typically need a list of options
        // For now, render as checkbox
        div(
          className := "flex items-center",
          input(
            idAttr := name,
            typ := "radio",
            className := "h-4 w-4 text-blue-600 border-gray-300 focus:ring-blue-500",
            disabled <-- disabled$,
            checked <-- value$.map(_ == "true"),
            onChange.mapToChecked.map(_.toString) --> valueObserver,
            onBlur.mapTo(()) --> onBlurObserver
          )
        )
    }
  }

  /**
    * Convert FormFieldType to HTML input type
    */
  private def fieldTypeToInputType(fieldType: FormFieldType): String = fieldType match {
    case FormFieldType.Text => "text"
    case FormFieldType.Email => "email"
    case FormFieldType.Password => "password"
    case FormFieldType.Number => "number"
    case FormFieldType.Tel => "tel"
    case FormFieldType.Url => "url"
    case FormFieldType.Date => "date"
    case FormFieldType.Time => "time"
    case FormFieldType.DateTime => "datetime-local"
    case _ => "text"
  }
}
