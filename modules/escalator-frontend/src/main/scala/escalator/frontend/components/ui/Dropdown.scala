package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Reusable Dropdown/Menu component for Laminar
  * Following quanty-os pattern with Tailwind CSS
  *
  * Features:
  * - Click to open/close
  * - Click outside to close
  * - Position variants (bottom-left, bottom-right, top-left, top-right)
  * - Menu items with optional icons
  * - Dividers
  * - Disabled items
  * - Item variants (default, danger, success, primary)
  */
object Dropdown {

  /**
    * Create a Dropdown component
    *
    * @param trigger The button/element that triggers the dropdown
    * @param items List of dropdown items and dividers
    * @param options Dropdown configuration options
    */
  def apply(
    trigger: HtmlElement,
    items: List[DropdownContent],
    options: DropdownOptions = DropdownOptions.default
  ): HtmlElement = {

    // State for dropdown open/close
    val isOpen = Var(false)

    // Reference to dropdown container for click-outside detection
    val dropdownRef = Var[Option[dom.HTMLElement]](None)

    // Close dropdown when clicking outside
    val clickOutsideListener = documentEvents.onClick.map(_.target) --> Observer[dom.EventTarget] { target =>
      if (isOpen.now()) {
        dropdownRef.now().foreach { dropdown =>
          val clickedElement = target.asInstanceOf[dom.Node]
          if (!dropdown.contains(clickedElement)) {
            isOpen.set(false)
          }
        }
      }
    }

    div(
      className := "relative inline-block text-left",

      // Store reference to dropdown container
      onMountCallback { ctx =>
        dropdownRef.set(Some(ctx.thisNode.ref))
      },

      // Click outside listener
      clickOutsideListener,

      // Trigger element with click handler
      trigger.amend(
        onClick.stopPropagation --> Observer[dom.MouseEvent] { _ =>
          isOpen.update(!_)
        }
      ),

      // Dropdown menu
      child <-- isOpen.signal.map { open =>
        if (open) {
          renderMenu(items, isOpen, options)
        } else {
          emptyNode
        }
      }
    )
  }

  /**
    * Render the dropdown menu
    */
  private def renderMenu(
    items: List[DropdownContent],
    isOpen: Var[Boolean],
    options: DropdownOptions
  ): HtmlElement = {
    div(
      className := s"absolute ${options.position.className} ${options.minWidth} z-50",

      // Stop click propagation to prevent closing when clicking inside menu
      onClick.stopPropagation --> Observer[dom.MouseEvent](_ => ()),

      div(
        className := s"${options.maxHeight} overflow-auto rounded-md shadow-lg bg-white ring-1 ring-black ring-opacity-5 ${options.menuClassName}",
        div(
          className := "py-1",
          items.map {
            case DropdownContent.Item(item) => renderItem(item, isOpen, options.closeOnClick)
            case DropdownContent.Divider => renderDivider()
          }
        )
      )
    )
  }

  /**
    * Render a dropdown menu item
    */
  private def renderItem(
    item: DropdownItem,
    isOpen: Var[Boolean],
    closeOnClick: Boolean
  ): HtmlElement = {
    val baseClass = "flex items-center px-4 py-2 text-sm transition-colors"
    val variantClass = item.variant.className
    val disabledClass = if (item.disabled) "opacity-50 cursor-not-allowed" else "cursor-pointer"
    val finalClass = s"$baseClass $variantClass $disabledClass"

    button(
      className := finalClass,
      disabled := item.disabled,

      // Icon if provided
      item.icon.map { iconText =>
        span(
          className := "mr-3 text-base",
          iconText
        )
      },

      // Label
      span(item.label),

      // Click handler
      if (!item.disabled) {
        onClick --> Observer[dom.MouseEvent] { _ =>
          item.onClick()
          if (closeOnClick) {
            isOpen.set(false)
          }
        }
      } else {
        emptyMod
      }
    )
  }

  /**
    * Render a divider
    */
  private def renderDivider(): HtmlElement = {
    div(
      className := "my-1 border-t border-gray-200"
    )
  }

  /**
    * Helper: Create a simple dropdown with text items
    */
  def simple(
    triggerText: String,
    items: List[(String, () => Unit)],
    triggerClass: String = "px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500",
    options: DropdownOptions = DropdownOptions.default
  ): HtmlElement = {
    val trigger = button(
      className := triggerClass,
      triggerText,
      span(className := "ml-2", "▼")
    )

    val dropdownItems = items.map { case (label, handler) =>
      DropdownContent.Item(DropdownItem(label, handler))
    }

    apply(trigger, dropdownItems, options)
  }

  /**
    * Helper: Create a dropdown with icon button trigger
    */
  def iconButton(
    icon: String,
    items: List[DropdownContent],
    triggerClass: String = "p-2 text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-full transition-colors",
    options: DropdownOptions = DropdownOptions.default
  ): HtmlElement = {
    val trigger = button(
      className := triggerClass,
      span(className := "text-xl", icon)
    )

    apply(trigger, items, options)
  }

  /**
    * Helper: Create an action menu (three dots)
    */
  def actionMenu(
    items: List[DropdownContent],
    options: DropdownOptions = DropdownOptions.default
  ): HtmlElement = {
    iconButton("⋮", items, options = options)
  }

  /**
    * Helper: Create items from simple tuples
    */
  def items(items: (String, () => Unit)*): List[DropdownContent] = {
    items.map { case (label, handler) =>
      DropdownContent.Item(DropdownItem(label, handler))
    }.toList
  }

  /**
    * Helper: Create an item with icon
    */
  def item(
    label: String,
    onClick: () => Unit,
    icon: Option[String] = None,
    variant: DropdownItemVariant = DropdownItemVariant.Default,
    disabled: Boolean = false
  ): DropdownContent = {
    DropdownContent.Item(DropdownItem(label, onClick, icon, variant, disabled))
  }

  /**
    * Helper: Add a divider
    */
  def divider: DropdownContent = DropdownContent.Divider
}
