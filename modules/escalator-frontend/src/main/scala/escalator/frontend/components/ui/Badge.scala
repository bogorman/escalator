package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Badge component for status indicators and labels
  * Uses Tailwind CSS utility classes
  */
object Badge {

  sealed trait Variant
  object Variant {
    case object Primary extends Variant
    case object Secondary extends Variant
    case object Success extends Variant
    case object Warning extends Variant
    case object Error extends Variant
    case object Info extends Variant
  }

  sealed trait Size
  object Size {
    case object Small extends Size
    case object Medium extends Size
    case object Large extends Size
  }

  def apply(
      text: String,
      variant: Variant = Variant.Primary,
      size: Size = Size.Medium,
      rounded: Boolean = true
  ): HtmlElement = {
    span(
      className := baseClasses,
      className := variantClasses(variant),
      className := sizeClasses(size),
      className := (if (rounded) "rounded-full" else "rounded"),
      text
    )
  }

  /**
    * Badge with dot indicator
    */
  def withDot(
      text: String,
      variant: Variant = Variant.Primary,
      size: Size = Size.Medium
  ): HtmlElement = {
    span(
      className := "inline-flex items-center gap-x-1.5",
      className := baseClasses,
      className := variantClasses(variant),
      className := sizeClasses(size),
      className := "rounded-full",
      // Dot
      svg.svg(
        svg.className := s"h-1.5 w-1.5 fill-current",
        svg.viewBox := "0 0 6 6",
        svg.circle(svg.cx := "3", svg.cy := "3", svg.r := "3")
      ),
      text
    )
  }

  /**
    * Removable badge with close button
    */
  def removable(
      text: String,
      variant: Variant = Variant.Primary,
      onRemove: () => Unit = () => ()
  ): HtmlElement = {
    span(
      className := "inline-flex items-center gap-x-0.5",
      className := baseClasses,
      className := variantClasses(variant),
      className := sizeClasses(Size.Medium),
      className := "rounded-full",
      text,
      button(
        typ := "button",
        className := "group relative -mr-1 h-3.5 w-3.5 rounded-sm hover:bg-gray-500/20",
        onClick --> Observer[dom.MouseEvent](_ => onRemove()),
        Icon.close()
      )
    )
  }

  private def baseClasses: String =
    "inline-flex items-center font-medium"

  private def variantClasses(variant: Variant): String = variant match {
    case Variant.Primary =>
      "bg-primary-100 text-primary-800 dark:bg-primary-900 dark:text-primary-200"
    case Variant.Secondary =>
      "bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-200"
    case Variant.Success =>
      "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200"
    case Variant.Warning =>
      "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200"
    case Variant.Error =>
      "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200"
    case Variant.Info =>
      "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200"
  }

  private def sizeClasses(size: Size): String = size match {
    case Size.Small  => "px-2 py-0.5 text-xs"
    case Size.Medium => "px-2.5 py-0.5 text-sm"
    case Size.Large  => "px-3 py-1 text-base"
  }
}
