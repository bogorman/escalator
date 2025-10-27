package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

object Input {

  def apply(
      id: String,
      inputType: String = "text",
      placeholderText: String = "",
      valueSignal: Signal[String],
      onChange: Observer[String],
      isRequired: Boolean = false,
      isDisabled: Boolean = false,
      autoCompleteValue: Option[String] = None,
      hasError: Signal[Boolean] = Val(false)
  ): HtmlElement = {
    input(
      typ := inputType,
      idAttr := id,
      className := baseClasses,
      className <-- hasError.map(err => if (err) errorClasses else normalClasses),
      className := (if (isDisabled) "opacity-50 cursor-not-allowed" else ""),
      placeholder := placeholderText,
      required := isRequired,
      disabled := isDisabled,
      autoCompleteValue.map(ac => autoComplete := ac),
      controlled(
        value <-- valueSignal,
        onInput.mapToValue --> onChange
      )
    )
  }

  private def baseClasses: String =
    "block w-full px-3 py-2 border rounded-lg shadow-sm " +
      "focus:outline-none focus:ring-2 focus:ring-offset-1 " +
      "dark:bg-gray-800 dark:text-white " +
      "transition-colors duration-200"

  private def normalClasses: String =
    "border-gray-300 focus:ring-primary-500 focus:border-primary-500 " +
      "dark:border-gray-600 dark:focus:ring-primary-400 dark:focus:border-primary-400"

  private def errorClasses: String =
    "border-red-500 focus:ring-red-500 focus:border-red-500 " +
      "dark:border-red-400 dark:focus:ring-red-400 dark:focus:border-red-400"
}
