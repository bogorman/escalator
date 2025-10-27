package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

/**
  * Loading spinner component
  * Uses Tailwind CSS animation classes
  */
object Spinner {

  sealed trait Size
  object Size {
    case object Small extends Size
    case object Medium extends Size
    case object Large extends Size
  }

  def apply(
      size: Size = Size.Medium,
      color: String = "text-primary-600"
  ): HtmlElement = {
    div(
      className := "inline-block",
      svg.svg(
        svg.className := s"animate-spin ${sizeClasses(size)} $color",
        svg.xmlns := "http://www.w3.org/2000/svg",
        svg.fill := "none",
        svg.viewBox := "0 0 24 24",
        svg.circle(
          svg.className := "opacity-25",
          svg.cx := "12",
          svg.cy := "12",
          svg.r := "10",
          svg.stroke := "currentColor",
          svg.strokeWidth := "4"
        ),
        svg.path(
          svg.className := "opacity-75",
          svg.fill := "currentColor",
          svg.d := "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
        )
      )
    )
  }

  /**
    * Full-page loading spinner
    */
  def fullPage(message: String = "Loading..."): HtmlElement = {
    div(
      className := "fixed inset-0 bg-gray-900 bg-opacity-50 flex items-center justify-center z-50",
      div(
        className := "bg-white dark:bg-gray-800 rounded-lg p-8 flex flex-col items-center space-y-4",
        apply(Size.Large),
        div(
          className := "text-lg font-medium text-gray-900 dark:text-white",
          message
        )
      )
    )
  }

  private def sizeClasses(size: Size): String = size match {
    case Size.Small  => "w-4 h-4"
    case Size.Medium => "w-8 h-8"
    case Size.Large  => "w-12 h-12"
  }
}
