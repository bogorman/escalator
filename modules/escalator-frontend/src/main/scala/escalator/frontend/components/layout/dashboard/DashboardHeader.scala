package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._

/**
  * Generic dashboard header component.
  *
  * Provides a flexible header bar with configurable left, center, and right content sections.
  * Typically used for mobile menu button (left), search (center), and user menu (right).
  *
  * @param leftContent Optional left section content (e.g., mobile menu button)
  * @param centerContent Optional center section content (e.g., search bar)
  * @param rightContent Optional right section content (e.g., user menu)
  */
object DashboardHeader {

  def apply(
      leftContent: Option[HtmlElement] = None,
      centerContent: Option[HtmlElement] = None,
      rightContent: Option[HtmlElement] = None
  ): HtmlElement = {
    div(
      className := "sticky top-0 z-40 flex h-16 shrink-0 items-center gap-x-4 border-b border-gray-200 bg-white px-4 shadow-xs sm:gap-x-6 sm:px-6 lg:px-8 dark:border-white/10 dark:bg-gray-900",

      // Left content (if provided)
      leftContent.toList.map(content => content),

      // Center content (if provided)
      centerContent.toList.map(content => content),

      // Right content (if provided)
      rightContent.toList.map(content => content)
    )
  }
}
