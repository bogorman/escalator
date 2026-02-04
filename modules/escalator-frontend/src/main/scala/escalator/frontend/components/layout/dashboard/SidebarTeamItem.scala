package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._
import org.scalajs.dom

object SidebarTeamItem {

  def apply(
      name: String,
      initial: String,
      onClickHandler: () => Unit = () => ()
  ): HtmlElement = {
    a(
      href := "#",
      className := "group flex gap-x-3 rounded-md p-2 text-sm/6 font-semibold text-gray-400 hover:bg-white/5 hover:text-white",
      onClick.preventDefault --> Observer[dom.MouseEvent] { _ =>
        onClickHandler()
      },
      span(
        className := "flex size-6 shrink-0 items-center justify-center rounded-lg border border-white/10 bg-white/5 text-[0.625rem] font-medium text-gray-400 group-hover:border-white/20 group-hover:text-white",
        initial
      ),
      span(
        className := "truncate",
        name
      )
    )
  }
}
