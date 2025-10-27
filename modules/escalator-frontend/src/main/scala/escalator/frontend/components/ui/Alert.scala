package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Alert component for messages and notifications
  * Uses Tailwind CSS utility classes
  */
object Alert {

  sealed trait Variant
  object Variant {
    case object Info extends Variant
    case object Success extends Variant
    case object Warning extends Variant
    case object Error extends Variant
  }

  def apply(
      message: String,
      variant: Variant = Variant.Info,
      title: Option[String] = None,
      dismissible: Boolean = false,
      onDismiss: () => Unit = () => ()
  ): HtmlElement = {
    div(
      className := baseClasses,
      className := variantClasses(variant),
      // Icon
      div(
        className := "flex-shrink-0",
        iconForVariant(variant)
      ),
      // Content
      div(
        className := "ml-3 flex-1",
        title.map { t =>
          h3(
            className := "text-sm font-medium",
            t
          )
        },
        p(
          className := "text-sm",
          message
        )
      ),
      // Dismiss button
      if (dismissible) {
        div(
          className := "ml-auto pl-3",
          button(
            typ := "button",
            className := s"inline-flex rounded-md p-1.5 focus:outline-none focus:ring-2 focus:ring-offset-2 ${dismissButtonClasses(variant)}",
            onClick --> Observer[dom.MouseEvent](_ => onDismiss()),
            Icon.close()
          )
        )
      } else {
        emptyNode
      }
    )
  }

  private def baseClasses: String =
    "rounded-md p-4 flex items-start"

  private def variantClasses(variant: Variant): String = variant match {
    case Variant.Info =>
      "bg-blue-50 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
    case Variant.Success =>
      "bg-green-50 text-green-800 dark:bg-green-900 dark:text-green-200"
    case Variant.Warning =>
      "bg-yellow-50 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
    case Variant.Error =>
      "bg-red-50 text-red-800 dark:bg-red-900 dark:text-red-200"
  }

  private def dismissButtonClasses(variant: Variant): String = variant match {
    case Variant.Info    => "text-blue-500 hover:bg-blue-100 focus:ring-blue-600"
    case Variant.Success => "text-green-500 hover:bg-green-100 focus:ring-green-600"
    case Variant.Warning => "text-yellow-500 hover:bg-yellow-100 focus:ring-yellow-600"
    case Variant.Error   => "text-red-500 hover:bg-red-100 focus:ring-red-600"
  }

  private def iconForVariant(variant: Variant): SvgElement = {
    val iconColor = variant match {
      case Variant.Info    => "text-blue-400"
      case Variant.Success => "text-green-400"
      case Variant.Warning => "text-yellow-400"
      case Variant.Error   => "text-red-400"
    }

    svg.svg(
      svg.className := s"h-5 w-5 $iconColor",
      svg.viewBox := "0 0 20 20",
      svg.fill := "currentColor",
      variant match {
        case Variant.Info | Variant.Warning =>
          svg.path(
            svg.fillRule := "evenodd",
            svg.d := "M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z",
            svg.clipRule := "evenodd"
          )
        case Variant.Success =>
          svg.path(
            svg.fillRule := "evenodd",
            svg.d := "M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z",
            svg.clipRule := "evenodd"
          )
        case Variant.Error =>
          svg.path(
            svg.fillRule := "evenodd",
            svg.d := "M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z",
            svg.clipRule := "evenodd"
          )
      }
    )
  }
}
