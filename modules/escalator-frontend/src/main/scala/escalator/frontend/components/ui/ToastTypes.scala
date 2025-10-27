package escalator.frontend.components.ui

/**
  * Toast type variants
  */
sealed trait ToastType {
  def bgColor: String
  def textColor: String
  def icon: String
}

object ToastType {
  case object Success extends ToastType {
    val bgColor = "bg-green-50"
    val textColor = "text-green-800"
    val icon = "✓"
  }

  case object Error extends ToastType {
    val bgColor = "bg-red-50"
    val textColor = "text-red-800"
    val icon = "✕"
  }

  case object Warning extends ToastType {
    val bgColor = "bg-yellow-50"
    val textColor = "text-yellow-800"
    val icon = "⚠"
  }

  case object Info extends ToastType {
    val bgColor = "bg-blue-50"
    val textColor = "text-blue-800"
    val icon = "ℹ"
  }
}

/**
  * Toast position on screen
  */
sealed trait ToastPosition {
  def className: String
}

object ToastPosition {
  case object TopRight extends ToastPosition {
    val className = "top-4 right-4"
  }

  case object TopLeft extends ToastPosition {
    val className = "top-4 left-4"
  }

  case object TopCenter extends ToastPosition {
    val className = "top-4 left-1/2 transform -translate-x-1/2"
  }

  case object BottomRight extends ToastPosition {
    val className = "bottom-4 right-4"
  }

  case object BottomLeft extends ToastPosition {
    val className = "bottom-4 left-4"
  }

  case object BottomCenter extends ToastPosition {
    val className = "bottom-4 left-1/2 transform -translate-x-1/2"
  }
}

/**
  * Data for a single toast notification
  *
  * @param id Unique identifier for this toast
  * @param message The message to display
  * @param toastType Type of toast (success, error, warning, info)
  * @param duration Duration in milliseconds before auto-dismiss (0 = no auto-dismiss)
  * @param dismissible Whether the toast can be manually dismissed
  * @param action Optional action button (label, handler)
  */
case class ToastData(
  id: String,
  message: String,
  toastType: ToastType,
  duration: Int = 5000,
  dismissible: Boolean = true,
  action: Option[(String, () => Unit)] = None
)

/**
  * Toast container configuration
  *
  * @param position Position on screen
  * @param maxToasts Maximum number of toasts to show at once (older ones removed)
  * @param spacing Spacing between toasts in Tailwind units
  */
case class ToastContainerOptions(
  position: ToastPosition = ToastPosition.TopRight,
  maxToasts: Int = 5,
  spacing: String = "mb-3"
)

object ToastContainerOptions {
  val default: ToastContainerOptions = ToastContainerOptions()
  val topRight: ToastContainerOptions = ToastContainerOptions(position = ToastPosition.TopRight)
  val topLeft: ToastContainerOptions = ToastContainerOptions(position = ToastPosition.TopLeft)
  val topCenter: ToastContainerOptions = ToastContainerOptions(position = ToastPosition.TopCenter)
  val bottomRight: ToastContainerOptions = ToastContainerOptions(position = ToastPosition.BottomRight)
  val bottomLeft: ToastContainerOptions = ToastContainerOptions(position = ToastPosition.BottomLeft)
  val bottomCenter: ToastContainerOptions = ToastContainerOptions(position = ToastPosition.BottomCenter)
}
