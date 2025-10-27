package escalator.frontend.components.ui

/**
  * Modal size variants
  */
sealed trait ModalSize {
  def className: String
}

object ModalSize {
  case object Small extends ModalSize {
    val className = "max-w-md"
  }

  case object Medium extends ModalSize {
    val className = "max-w-lg"
  }

  case object Large extends ModalSize {
    val className = "max-w-2xl"
  }

  case object XLarge extends ModalSize {
    val className = "max-w-4xl"
  }

  case object Full extends ModalSize {
    val className = "max-w-full mx-4"
  }
}

/**
  * Modal configuration options
  *
  * @param size Modal size variant
  * @param closeOnBackdrop Whether clicking the backdrop closes the modal
  * @param closeOnEsc Whether pressing ESC closes the modal
  * @param showCloseButton Whether to show the X close button
  * @param backdropClassName Additional CSS classes for backdrop
  * @param panelClassName Additional CSS classes for modal panel
  */
case class ModalOptions(
  size: ModalSize = ModalSize.Medium,
  closeOnBackdrop: Boolean = true,
  closeOnEsc: Boolean = true,
  showCloseButton: Boolean = true,
  backdropClassName: String = "",
  panelClassName: String = ""
)

object ModalOptions {
  val default: ModalOptions = ModalOptions()

  val noClose: ModalOptions = ModalOptions(
    closeOnBackdrop = false,
    closeOnEsc = false,
    showCloseButton = false
  )

  val small: ModalOptions = ModalOptions(size = ModalSize.Small)
  val large: ModalOptions = ModalOptions(size = ModalSize.Large)
  val xlarge: ModalOptions = ModalOptions(size = ModalSize.XLarge)
  val full: ModalOptions = ModalOptions(size = ModalSize.Full)
}
