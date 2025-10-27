package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

object PasswordInput {

  def apply(
      id: String,
      placeholderText: String = "Enter password",
      valueSignal: Signal[String],
      onChange: Observer[String],
      isRequired: Boolean = false,
      isDisabled: Boolean = false,
      autoCompleteValue: Option[String] = Some("current-password"),
      hasError: Signal[Boolean] = Val(false)
  ): HtmlElement = {
    val showPassword = Var(false)

    div(
      className := "relative",
      input(
        typ <-- showPassword.signal.map(show => if (show) "text" else "password"),
        idAttr := id,
        className := baseClasses,
        className <-- hasError.map(err => if (err) errorClasses else normalClasses),
        className := (if (isDisabled) "opacity-50 cursor-not-allowed" else ""),
        className := "pr-10", // Make room for the toggle button
        placeholder := placeholderText,
        required := isRequired,
        disabled := isDisabled,
        autoCompleteValue.map(ac => autoComplete := ac),
        controlled(
          value <-- valueSignal,
          onInput.mapToValue --> onChange
        )
      ),
      // Toggle visibility button
      button(
        typ := "button",
        className := "absolute inset-y-0 right-0 flex items-center pr-3 text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200",
        onClick --> Observer[dom.MouseEvent](_ => showPassword.update(!_)),
        svg.svg(
          svg.className := "w-5 h-5",
          svg.fill := "none",
          svg.stroke := "currentColor",
          svg.viewBox := "0 0 24 24",
          child <-- showPassword.signal.map { show =>
            if (show) {
              // Eye slash icon (hide password)
              svg.path(
                svg.strokeLineCap := "round",
                svg.strokeLineJoin := "round",
                svg.strokeWidth := "2",
                svg.d := "M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l3.59 3.59m0 0A9.953 9.953 0 0112 5c4.478 0 8.268 2.943 9.543 7a10.025 10.025 0 01-4.132 5.411m0 0L21 21"
              )
            } else {
              // Eye icon (show password)
              svg.g(
                svg.path(
                  svg.strokeLineCap := "round",
                  svg.strokeLineJoin := "round",
                  svg.strokeWidth := "2",
                  svg.d := "M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                ),
                svg.path(
                  svg.strokeLineCap := "round",
                  svg.strokeLineJoin := "round",
                  svg.strokeWidth := "2",
                  svg.d := "M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                )
              )
            }
          }
        )
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
