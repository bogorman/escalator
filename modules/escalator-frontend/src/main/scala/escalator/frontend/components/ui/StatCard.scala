package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

/**
  * Stat card component for displaying metrics and KPIs
  * Uses Tailwind CSS utility classes
  */
object StatCard {

  sealed trait Trend
  object Trend {
    case object Up extends Trend
    case object Down extends Trend
    case object Neutral extends Trend
  }

  def apply(
      title: String,
      value: String,
      icon: Option[HtmlElement] = None,
      change: Option[String] = None,
      trend: Trend = Trend.Neutral,
      subtitle: Option[String] = None
  ): HtmlElement = {
    div(
      className := "bg-white dark:bg-gray-800 overflow-hidden shadow rounded-lg",
      div(
        className := "p-5",
        div(
          className := "flex items-center",
          // Icon
          icon.map { iconElement =>
            div(
              className := "flex-shrink-0",
              div(
                className := "flex items-center justify-center h-12 w-12 rounded-md bg-primary-500 text-white",
                iconElement
              )
            )
          },
          // Content
          div(
            className := s"${if (icon.isDefined) "ml-5 " else ""}w-0 flex-1",
            // Title
            dt(
              className := "text-sm font-medium text-gray-500 dark:text-gray-400 truncate",
              title
            ),
            // Value
            dd(
              className := "flex items-baseline",
              div(
                className := "text-2xl font-semibold text-gray-900 dark:text-white",
                value
              ),
              // Change indicator
              change.map { changeText =>
                div(
                  className := s"ml-2 flex items-baseline text-sm font-semibold ${trendColor(trend)}",
                  trendIcon(trend),
                  span(className := "sr-only", if (trend == Trend.Up) "Increased" else "Decreased", " by"),
                  changeText
                )
              }
            ),
            // Subtitle
            subtitle.map { sub =>
              p(
                className := "mt-1 text-xs text-gray-500 dark:text-gray-400",
                sub
              )
            }
          )
        )
      )
    )
  }

  /**
    * Simple stat card without icon
    */
  def simple(
      title: String,
      value: String,
      subtitle: Option[String] = None
  ): HtmlElement = {
    apply(
      title = title,
      value = value,
      icon = None,
      subtitle = subtitle
    )
  }

  /**
    * Stat card with custom content
    */
  def withContent(
      title: String,
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "bg-white dark:bg-gray-800 overflow-hidden shadow rounded-lg",
      div(
        className := "p-5",
        dt(
          className := "text-sm font-medium text-gray-500 dark:text-gray-400 mb-2",
          title
        ),
        dd(children)
      )
    )
  }

  private def trendColor(trend: Trend): String = trend match {
    case Trend.Up      => "text-green-600 dark:text-green-400"
    case Trend.Down    => "text-red-600 dark:text-red-400"
    case Trend.Neutral => "text-gray-600 dark:text-gray-400"
  }

  private def trendIcon(trend: Trend): SvgElement = {
    trend match {
      case Trend.Up =>
        svg.svg(
          svg.className := "self-center flex-shrink-0 h-5 w-5 text-green-500",
          svg.fill := "currentColor",
          svg.viewBox := "0 0 20 20",
          svg.path(
            svg.fillRule := "evenodd",
            svg.d := "M5.293 9.707a1 1 0 010-1.414l4-4a1 1 0 011.414 0l4 4a1 1 0 01-1.414 1.414L11 7.414V15a1 1 0 11-2 0V7.414L6.707 9.707a1 1 0 01-1.414 0z",
            svg.clipRule := "evenodd"
          )
        )
      case Trend.Down =>
        svg.svg(
          svg.className := "self-center flex-shrink-0 h-5 w-5 text-red-500",
          svg.fill := "currentColor",
          svg.viewBox := "0 0 20 20",
          svg.path(
            svg.fillRule := "evenodd",
            svg.d := "M14.707 10.293a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 111.414-1.414L9 12.586V5a1 1 0 012 0v7.586l2.293-2.293a1 1 0 011.414 0z",
            svg.clipRule := "evenodd"
          )
        )
      case Trend.Neutral =>
        svg.svg(
          svg.className := "self-center flex-shrink-0 h-5 w-5 text-gray-500",
          svg.fill := "currentColor",
          svg.viewBox := "0 0 20 20",
          svg.path(
            svg.fillRule := "evenodd",
            svg.d := "M3 10a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z",
            svg.clipRule := "evenodd"
          )
        )
    }
  }
}
