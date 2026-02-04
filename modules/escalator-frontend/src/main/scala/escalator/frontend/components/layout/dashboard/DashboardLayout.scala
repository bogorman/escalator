package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._

/**
  * Generic dashboard layout component.
  *
  * Provides a flexible dashboard layout with sidebar and header.
  * Accepts pre-built sidebar and header components as parameters,
  * making it router-agnostic and state-agnostic.
  *
  * @param sidebar The sidebar component (typically a navigation sidebar)
  * @param header The header component (typically with search, user menu, etc.)
  * @param sidebarWidthClass Tailwind class for sidebar width offset (default: "lg:pl-72")
  * @param children Content elements to render in the main area
  */
object DashboardLayout {

  def apply(
      sidebar: HtmlElement,
      header: HtmlElement,
      sidebarWidthClass: String = "lg:pl-72"
  )(
      children: HtmlElement*
  ): HtmlElement = {
    div(
      className := "h-full",

      // Sidebar
      sidebar,

      // Main content area
      div(
        className := sidebarWidthClass,

        // Header
        header,

        // Main content
        main(
          className := "py-10",
          div(
            className := "px-4 sm:px-6 lg:px-8",
            children
          )
        )
      )
    )
  }
}
