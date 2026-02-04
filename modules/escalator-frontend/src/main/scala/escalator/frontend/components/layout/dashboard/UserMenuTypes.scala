package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._

/**
  * Configuration types for the UserMenu component.
  *
  * These types allow the UserMenu to be fully configured without
  * coupling to specific AppState or Router implementations.
  */

/**
  * Configuration for a single menu item in the dropdown.
  *
  * @param label Display label for the menu item
  * @param icon Optional icon element
  * @param onClick Callback when item is clicked
  */
case class MenuItem(
    label: String,
    icon: Option[SvgElement] = None,
    onClick: () => Unit = () => ()
)

/**
  * Complete user menu configuration.
  *
  * @param userName Signal for user's display name
  * @param userEmail Signal for user's email address
  * @param userAvatar Signal for user's avatar image URL
  * @param menuItems List of menu items to display in dropdown
  * @param onLogout Callback when logout is triggered
  */
case class UserMenuConfig(
    userName: Signal[String],
    userEmail: Signal[String],
    userAvatar: Signal[String],
    menuItems: List[MenuItem],
    onLogout: () => Unit
)
