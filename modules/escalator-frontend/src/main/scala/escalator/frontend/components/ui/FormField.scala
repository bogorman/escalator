package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

object FormField {

  def apply(
      labelText: String,
      htmlFor: String,
      input: HtmlElement,
      error: Signal[Option[String]] = Val(None),
      helpText: Option[String] = None
  ): HtmlElement = {
    div(
      className := "mb-4",
      // Label
      label(
        forId := htmlFor,
        className := "block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1",
        labelText
      ),
      // Input
      input,
      // Error message
      child <-- error.map {
        case Some(errorMsg) =>
          div(
            className := "mt-1 text-sm text-red-600 dark:text-red-400",
            errorMsg
          )
        case None =>
          helpText match {
            case Some(help) =>
              div(
                className := "mt-1 text-sm text-gray-500 dark:text-gray-400",
                help
              )
            case None =>
              emptyNode
          }
      }
    )
  }

  def withoutLabel(
      input: HtmlElement,
      error: Signal[Option[String]] = Val(None),
      helpText: Option[String] = None
  ): HtmlElement = {
    div(
      className := "mb-4",
      // Input
      input,
      // Error message
      child <-- error.map {
        case Some(errorMsg) =>
          div(
            className := "mt-1 text-sm text-red-600 dark:text-red-400",
            errorMsg
          )
        case None =>
          helpText match {
            case Some(help) =>
              div(
                className := "mt-1 text-sm text-gray-500 dark:text-gray-400",
                help
              )
            case None =>
              emptyNode
          }
      }
    )
  }
}
