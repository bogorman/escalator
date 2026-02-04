package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._
import escalator.frontend.components.ui.Icon
import org.scalajs.dom

object SearchBar {

  def apply(
      placeholderText: String = "Search",
      onSearch: String => Unit = _ => ()
  ): HtmlElement = {
    val searchInput = Var("")

    div(
      className := "flex flex-1 gap-x-4 self-stretch lg:gap-x-6",
      form(
        action := "#",
        method := "GET",
        className := "grid flex-1 grid-cols-1",
        onSubmit.preventDefault --> Observer[dom.Event] { _ =>
          onSearch(searchInput.now())
        },
        input(
          name := "search",
          placeholder := placeholderText,
          aria.label := placeholderText,
          className := "col-start-1 row-start-1 block size-full bg-white pl-8 text-base text-gray-900 outline-hidden placeholder:text-gray-400 sm:text-sm/6 dark:bg-gray-900 dark:text-white dark:placeholder:text-gray-500",
          onInput.mapToValue --> searchInput
        ),
        Icon.magnifyingGlass()
      )
    )
  }
}
