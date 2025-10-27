package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

object Card {

  def apply(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "bg-white dark:bg-gray-800 rounded-lg shadow-md p-6 border border-gray-200 dark:border-gray-700",
      children
    )
  }

  def withHeader(
      title: String,
      subtitle: Option[String],
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "bg-white dark:bg-gray-800 rounded-lg shadow-md border border-gray-200 dark:border-gray-700",
      // Header
      div(
        className := "px-6 py-4 border-b border-gray-200 dark:border-gray-700",
        h3(
          className := "text-lg font-semibold text-gray-900 dark:text-white",
          title
        ),
        subtitle.map { sub =>
          p(
            className := "mt-1 text-sm text-gray-500 dark:text-gray-400",
            sub
          )
        }
      ),
      // Body
      div(
        className := "p-6",
        children
      )
    )
  }

  def simple(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "bg-white dark:bg-gray-800 rounded-lg shadow-sm p-4",
      children
    )
  }
}
