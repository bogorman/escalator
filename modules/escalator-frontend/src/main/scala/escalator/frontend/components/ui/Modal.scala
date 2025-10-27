package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Reusable Modal/Dialog component for Laminar
  * Following quanty-os pattern with Tailwind CSS
  *
  * Features:
  * - Backdrop overlay with configurable behavior
  * - ESC key to close
  * - Size variants (sm, md, lg, xl, full)
  * - Header, body, footer sections
  * - Smooth animations
  * - Helper methods for common patterns (confirm, alert)
  */
object Modal {

  /**
    * Create a Modal component
    *
    * @param isOpen Signal controlling modal visibility
    * @param onClose Callback when modal should close
    * @param title Optional modal title
    * @param content Modal body content
    * @param footer Optional footer content
    * @param options Modal configuration options
    */
  def apply(
    isOpen: Signal[Boolean],
    onClose: () => Unit,
    title: Option[String] = None,
    content: HtmlElement,
    footer: Option[HtmlElement] = None,
    options: ModalOptions = ModalOptions.default
  ): HtmlElement = {

    div(
      // Container fixed overlay
      className := "fixed inset-0 z-50 overflow-y-auto items-center justify-center",

      // Only show when open
      display <-- isOpen.map(if (_) "flex" else "none"),

      // ESC key handler
      if (options.closeOnEsc) {
        documentEvents.onKeyDown
          .filter(_.keyCode == dom.KeyCode.Escape) --> Observer[dom.KeyboardEvent] { _ =>
            onClose()
          }
      } else {
        emptyMod
      },

      // Backdrop
      div(
        className := s"fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity ${options.backdropClassName}",

        // Click backdrop to close
        if (options.closeOnBackdrop) {
          onClick --> Observer[dom.MouseEvent] { _ =>
            onClose()
          }
        } else {
          emptyMod
        }
      ),

      // Modal container
      div(
        className := "flex min-h-full items-center justify-center p-4 text-center sm:p-0",

        // Modal panel
        div(
          className := s"relative transform overflow-hidden rounded-lg bg-white text-left shadow-xl transition-all sm:my-8 sm:w-full ${options.size.className} ${options.panelClassName}",

          // Stop click propagation to prevent backdrop close
          onClick.stopPropagation --> Observer[dom.MouseEvent](_ => ()),

          // Close button
          if (options.showCloseButton) {
            button(
              className := "absolute right-4 top-4 text-gray-400 hover:text-gray-500 text-2xl font-light leading-none",
              "×",
              onClick --> Observer[dom.MouseEvent] { _ =>
                onClose()
              }
            )
          } else {
            emptyNode
          },

          // Header
          title.map { t =>
            div(
              className := "border-b border-gray-200 px-6 py-4 pr-12",
              h3(
                className := "text-lg font-medium leading-6 text-gray-900",
                t
              )
            )
          },

          // Body
          div(
            className := "px-6 py-4",
            content
          ),

          // Footer
          footer.map { f =>
            div(
              className := "border-t border-gray-200 px-6 py-4 bg-gray-50",
              f
            )
          }
        )
      )
    )
  }

  /**
    * Confirmation dialog helper
    *
    * @param isOpen Signal controlling modal visibility
    * @param title Dialog title
    * @param message Confirmation message
    * @param onConfirm Callback when user confirms
    * @param onCancel Callback when user cancels
    * @param confirmText Text for confirm button (default: "Confirm")
    * @param cancelText Text for cancel button (default: "Cancel")
    * @param confirmClass CSS classes for confirm button
    */
  def confirm(
    isOpen: Signal[Boolean],
    title: String,
    message: String,
    onConfirm: () => Unit,
    onCancel: () => Unit,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    confirmClass: String = "bg-blue-600 hover:bg-blue-700",
    size: ModalSize = ModalSize.Small
  ): HtmlElement = {
    Modal(
      isOpen = isOpen,
      onClose = onCancel,
      title = Some(title),
      content = div(
        className := "text-sm text-gray-500",
        p(message)
      ),
      footer = Some(
        div(
          className := "flex justify-end gap-3",
          button(
            className := "px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md shadow-sm hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500",
            cancelText,
            onClick --> Observer[dom.MouseEvent] { _ =>
              onCancel()
            }
          ),
          button(
            className := s"px-4 py-2 text-sm font-medium text-white rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-offset-2 $confirmClass",
            confirmText,
            onClick --> Observer[dom.MouseEvent] { _ =>
              onConfirm()
            }
          )
        )
      ),
      options = ModalOptions(size = size)
    )
  }

  /**
    * Alert dialog helper
    *
    * @param isOpen Signal controlling modal visibility
    * @param title Dialog title
    * @param message Alert message
    * @param onClose Callback when user closes
    * @param buttonText Text for button (default: "OK")
    * @param buttonClass CSS classes for button
    */
  def alert(
    isOpen: Signal[Boolean],
    title: String,
    message: String,
    onClose: () => Unit,
    buttonText: String = "OK",
    buttonClass: String = "bg-blue-600 hover:bg-blue-700",
    size: ModalSize = ModalSize.Small
  ): HtmlElement = {
    Modal(
      isOpen = isOpen,
      onClose = onClose,
      title = Some(title),
      content = div(
        className := "text-sm text-gray-500",
        p(message)
      ),
      footer = Some(
        div(
          className := "flex justify-end",
          button(
            className := s"px-4 py-2 text-sm font-medium text-white rounded-md shadow-sm focus:outline-none focus:ring-2 focus:ring-offset-2 $buttonClass",
            buttonText,
            onClick --> Observer[dom.MouseEvent] { _ =>
              onClose()
            }
          )
        )
      ),
      options = ModalOptions(size = size)
    )
  }

  /**
    * Danger confirmation dialog (for destructive actions)
    */
  def confirmDanger(
    isOpen: Signal[Boolean],
    title: String,
    message: String,
    onConfirm: () => Unit,
    onCancel: () => Unit,
    confirmText: String = "Delete",
    cancelText: String = "Cancel"
  ): HtmlElement = {
    confirm(
      isOpen = isOpen,
      title = title,
      message = message,
      onConfirm = onConfirm,
      onCancel = onCancel,
      confirmText = confirmText,
      cancelText = cancelText,
      confirmClass = "bg-red-600 hover:bg-red-700"
    )
  }

  /**
    * Success alert dialog
    */
  def success(
    isOpen: Signal[Boolean],
    title: String,
    message: String,
    onClose: () => Unit
  ): HtmlElement = {
    alert(
      isOpen = isOpen,
      title = title,
      message = message,
      onClose = onClose,
      buttonClass = "bg-green-600 hover:bg-green-700"
    )
  }

  /**
    * Error alert dialog
    */
  def error(
    isOpen: Signal[Boolean],
    title: String,
    message: String,
    onClose: () => Unit
  ): HtmlElement = {
    alert(
      isOpen = isOpen,
      title = title,
      message = message,
      onClose = onClose,
      buttonClass = "bg-red-600 hover:bg-red-700"
    )
  }
}
