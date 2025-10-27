package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Individual Toast component
  * Renders a single toast notification with icon, message, and dismiss button
  */
object Toast {

  /**
    * Create a Toast component
    *
    * @param data Toast data (message, type, etc.)
    * @param onDismiss Callback when toast is dismissed
    */
  def apply(
    data: ToastData,
    onDismiss: () => Unit
  ): HtmlElement = {

    val toastType = data.toastType

    div(
      className := s"flex items-center ${toastType.bgColor} ${toastType.textColor} rounded-lg shadow-lg p-4 min-w-[300px] max-w-md transition-all duration-300 ease-in-out",

      // Icon
      div(
        className := "flex-shrink-0 w-8 h-8 flex items-center justify-center rounded-full bg-white bg-opacity-20 mr-3",
        span(
          className := "text-xl font-bold",
          toastType.icon
        )
      ),

      // Message content
      div(
        className := "flex-1 mr-2",
        p(
          className := "text-sm font-medium",
          data.message
        ),

        // Action button if provided
        data.action.map { case (label, handler) =>
          button(
            className := s"mt-2 text-xs font-semibold underline hover:no-underline",
            label,
            onClick.stopPropagation --> Observer[dom.MouseEvent] { _ =>
              handler()
            }
          )
        }
      ),

      // Dismiss button
      if (data.dismissible) {
        button(
          className := "flex-shrink-0 text-xl font-light leading-none opacity-70 hover:opacity-100 transition-opacity",
          "×",
          onClick --> Observer[dom.MouseEvent] { _ =>
            onDismiss()
          }
        )
      } else {
        emptyNode
      }
    )
  }
}
