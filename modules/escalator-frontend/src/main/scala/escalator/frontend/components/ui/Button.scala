package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

object Button {

  sealed trait Variant
  object Variant {
    case object Primary extends Variant
    case object Secondary extends Variant
    case object Outline extends Variant
    case object Ghost extends Variant
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
      fullWidth: Boolean = false,
      isDisabled: Boolean = false,
      onClickHandler: () => Unit = () => ()
  ): HtmlElement = {
    button(
      typ := "button",
      className := baseClasses,
      className := variantClasses(variant),
      className := sizeClasses(size),
      className := (if (fullWidth) "w-full" else ""),
      className := (if (isDisabled) "opacity-50 cursor-not-allowed" else ""),
      disabled := isDisabled,
      text,
      onClick --> Observer[dom.MouseEvent](_ => if (!isDisabled) onClickHandler())
    )
  }

  def submit(
      text: String,
      variant: Variant = Variant.Primary,
      size: Size = Size.Medium,
      fullWidth: Boolean = false,
      isDisabled: Boolean = false
  ): HtmlElement = {
    button(
      typ := "submit",
      className := baseClasses,
      className := variantClasses(variant),
      className := sizeClasses(size),
      className := (if (fullWidth) "w-full" else ""),
      className := (if (isDisabled) "opacity-50 cursor-not-allowed" else ""),
      disabled := isDisabled,
      text
    )
  }

  private def baseClasses: String =
    "inline-flex items-center justify-center font-medium rounded-lg transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-offset-2"

  private def variantClasses(variant: Variant): String = variant match {
    case Variant.Primary =>
      "bg-primary-600 text-white hover:bg-primary-700 focus:ring-primary-500 dark:bg-primary-500 dark:hover:bg-primary-600"
    case Variant.Secondary =>
      "bg-gray-600 text-white hover:bg-gray-700 focus:ring-gray-500 dark:bg-gray-500 dark:hover:bg-gray-600"
    case Variant.Outline =>
      "border-2 border-primary-600 text-primary-600 hover:bg-primary-50 focus:ring-primary-500 dark:border-primary-400 dark:text-primary-400 dark:hover:bg-gray-800"
    case Variant.Ghost =>
      "text-gray-700 hover:bg-gray-100 focus:ring-gray-500 dark:text-gray-300 dark:hover:bg-gray-800"
  }

  private def sizeClasses(size: Size): String = size match {
    case Size.Small  => "px-3 py-1.5 text-sm"
    case Size.Medium => "px-4 py-2 text-base"
    case Size.Large  => "px-6 py-3 text-lg"
  }
}
