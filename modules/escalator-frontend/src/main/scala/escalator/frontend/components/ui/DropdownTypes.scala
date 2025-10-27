package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

/**
  * Dropdown menu position relative to trigger
  */
sealed trait DropdownPosition {
  def className: String
}

object DropdownPosition {
  case object BottomLeft extends DropdownPosition {
    val className = "left-0 top-full mt-2"
  }

  case object BottomRight extends DropdownPosition {
    val className = "right-0 top-full mt-2"
  }

  case object TopLeft extends DropdownPosition {
    val className = "left-0 bottom-full mb-2"
  }

  case object TopRight extends DropdownPosition {
    val className = "right-0 bottom-full mb-2"
  }
}

/**
  * Dropdown item variant for styling
  */
sealed trait DropdownItemVariant {
  def className: String
}

object DropdownItemVariant {
  case object Default extends DropdownItemVariant {
    val className = "text-gray-700 hover:bg-gray-100 hover:text-gray-900"
  }

  case object Danger extends DropdownItemVariant {
    val className = "text-red-600 hover:bg-red-50 hover:text-red-700"
  }

  case object Success extends DropdownItemVariant {
    val className = "text-green-600 hover:bg-green-50 hover:text-green-700"
  }

  case object Primary extends DropdownItemVariant {
    val className = "text-blue-600 hover:bg-blue-50 hover:text-blue-700"
  }
}

/**
  * Single dropdown menu item
  *
  * @param label Item text
  * @param onClick Click handler
  * @param icon Optional icon (emoji or text)
  * @param variant Item styling variant
  * @param disabled Whether item is disabled
  */
case class DropdownItem(
  label: String,
  onClick: () => Unit,
  icon: Option[String] = None,
  variant: DropdownItemVariant = DropdownItemVariant.Default,
  disabled: Boolean = false
)

/**
  * Dropdown divider (separator line)
  */
case object DropdownDivider

/**
  * Dropdown menu content - either an item or divider
  */
sealed trait DropdownContent

object DropdownContent {
  case class Item(item: DropdownItem) extends DropdownContent
  case object Divider extends DropdownContent
}

/**
  * Dropdown configuration options
  *
  * @param position Menu position relative to trigger
  * @param closeOnClick Whether to close menu when item is clicked
  * @param minWidth Minimum width of dropdown menu (Tailwind class)
  * @param maxHeight Maximum height of dropdown menu (Tailwind class)
  * @param menuClassName Additional CSS classes for menu
  */
case class DropdownOptions(
  position: DropdownPosition = DropdownPosition.BottomRight,
  closeOnClick: Boolean = true,
  minWidth: String = "min-w-[200px]",
  maxHeight: String = "max-h-96",
  menuClassName: String = ""
)

object DropdownOptions {
  val default: DropdownOptions = DropdownOptions()
  val bottomLeft: DropdownOptions = DropdownOptions(position = DropdownPosition.BottomLeft)
  val bottomRight: DropdownOptions = DropdownOptions(position = DropdownPosition.BottomRight)
  val topLeft: DropdownOptions = DropdownOptions(position = DropdownPosition.TopLeft)
  val topRight: DropdownOptions = DropdownOptions(position = DropdownPosition.TopRight)
}
