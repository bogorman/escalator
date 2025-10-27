package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Tabs component for tabbed interfaces
  * Uses Tailwind CSS utility classes
  */
object Tabs {

  case class Tab(
      id: String,
      label: String,
      content: HtmlElement,
      disabled: Boolean = false
  )

  def apply(
      tabs: List[Tab],
      activeTabId: Var[String],
      fullWidth: Boolean = false
  ): HtmlElement = {
    div(
      className := "w-full",
      // Tab headers
      div(
        className := "border-b border-gray-200 dark:border-gray-700",
        div(
          className := s"${if (fullWidth) "grid grid-cols-" + tabs.length else "-mb-px flex space-x-8"}",
          tabs.map { tab =>
            button(
              typ := "button",
              className := "group inline-flex items-center px-1 py-4 border-b-2 font-medium text-sm",
              className <-- activeTabId.signal.map { activeId =>
                if (activeId == tab.id) {
                  "border-primary-500 text-primary-600 dark:text-primary-400"
                } else if (tab.disabled) {
                  "border-transparent text-gray-400 cursor-not-allowed"
                } else {
                  "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 dark:text-gray-400 dark:hover:text-gray-300"
                }
              },
              disabled := tab.disabled,
              onClick --> Observer[dom.MouseEvent](_ => if (!tab.disabled) activeTabId.set(tab.id)),
              tab.label
            )
          }
        )
      ),
      // Tab content
      div(
        className := "mt-4",
        tabs.map { tab =>
          div(
            display <-- activeTabId.signal.map(activeId => if (activeId == tab.id) "block" else "none"),
            tab.content
          )
        }
      )
    )
  }

  /**
    * Tabs with pills style
    */
  def pills(
      tabs: List[Tab],
      activeTabId: Var[String]
  ): HtmlElement = {
    div(
      className := "w-full",
      // Tab headers (pills style)
      div(
        className := "flex space-x-2 p-1 bg-gray-100 dark:bg-gray-800 rounded-lg",
        tabs.map { tab =>
          button(
            typ := "button",
            className := "flex-1 px-4 py-2 text-sm font-medium rounded-md transition-colors",
            className <-- activeTabId.signal.map { activeId =>
              if (activeId == tab.id) {
                "bg-white dark:bg-gray-700 text-primary-600 dark:text-primary-400 shadow-sm"
              } else if (tab.disabled) {
                "text-gray-400 cursor-not-allowed"
              } else {
                "text-gray-700 dark:text-gray-300 hover:bg-white/50 dark:hover:bg-gray-700/50"
              }
            },
            disabled := tab.disabled,
            onClick --> Observer[dom.MouseEvent](_ => if (!tab.disabled) activeTabId.set(tab.id)),
            tab.label
          )
        }
      ),
      // Tab content
      div(
        className := "mt-4",
        tabs.map { tab =>
          div(
            display <-- activeTabId.signal.map(activeId => if (activeId == tab.id) "block" else "none"),
            tab.content
          )
        }
      )
    )
  }
}
