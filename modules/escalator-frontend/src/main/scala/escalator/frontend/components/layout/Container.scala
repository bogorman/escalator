package escalator.frontend.components.layout

import com.raquo.laminar.api.L._

object Container {

  def apply(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "container mx-auto px-4 sm:px-6 lg:px-8",
      children
    )
  }

  def narrow(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "container mx-auto px-4 sm:px-6 lg:px-8 max-w-2xl",
      children
    )
  }

  def centered(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "min-h-screen flex items-center justify-center px-4 sm:px-6 lg:px-8",
      div(
        className := "w-full max-w-md",
        children
      )
    )
  }

  def fullHeight(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "min-h-screen bg-gray-50 dark:bg-gray-900",
      children
    )
  }
}
