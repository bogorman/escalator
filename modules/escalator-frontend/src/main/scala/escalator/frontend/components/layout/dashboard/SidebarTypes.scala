package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._

/**
  * Configuration types for the Sidebar component.
  *
  * These types allow the Sidebar to be fully configured without
  * coupling to specific Router or AppState implementations.
  */

/**
  * Configuration for a single navigation item.
  *
  * @param label Display label for the nav item
  * @param icon SVG icon element
  * @param isActive Whether this item is currently active
  * @param onClick Callback when item is clicked
  * @param badge Optional badge text (e.g., notification count)
  */
case class NavItemConfig(
    label: String,
    icon: SvgElement,
    isActive: Boolean = false,
    onClick: () => Unit = () => (),
    badge: Option[String] = None
)

/**
  * Configuration for a navigation section.
  *
  * @param title Optional section title (None for no title)
  * @param items List of navigation items in this section
  */
case class NavSection(
    title: Option[String] = None,
    items: List[NavItemConfig]
)

/**
  * Complete sidebar configuration.
  *
  * @param logoElement Logo element displayed at the top
  * @param navSections List of navigation sections
  * @param footerElement Optional footer element (e.g., settings link at bottom)
  */
case class SidebarConfig(
    logoElement: HtmlElement,
    navSections: List[NavSection],
    footerElement: Option[HtmlElement] = None
)
