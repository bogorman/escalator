package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._
import org.scalajs.dom

object SidebarNavItem {

  def apply(
      label: String,
      icon: SvgElement,
      isActive: Boolean = false,
      onClickHandler: () => Unit = () => ()
  ): HtmlElement = {
    a(
      href := "#",
      className := "group flex gap-x-3 rounded-md p-2 text-sm/6 font-semibold",
      className := (if (isActive) "bg-white/5 text-white" else "text-gray-400 hover:bg-white/5 hover:text-white"),
      onClick.preventDefault --> Observer[dom.MouseEvent] { _ =>
        onClickHandler()
      },
      icon,
      label
    )
  }
}
