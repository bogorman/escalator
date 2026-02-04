package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._
import escalator.frontend.components.ui.Icon
import org.scalajs.dom
import scala.scalajs.js

/**
  * Generic user menu component.
  *
  * Displays user avatar and name with a dropdown menu of configurable items.
  * AppState-agnostic and Router-agnostic - all configuration comes from UserMenuConfig.
  *
  * @param config Complete user menu configuration
  */
object UserMenu {

  def apply(config: UserMenuConfig): HtmlElement = {
    val isOpen = Var(false)

    // Close dropdown when clicking outside
    val outsideClickListener: js.Function1[dom.Event, Any] = { (_: dom.Event) =>
      isOpen.set(false)
    }

    div(
      className := "relative",

      // User button
      button(
        className := "relative flex items-center",
        onClick --> Observer[dom.MouseEvent] { _ =>
          isOpen.update(!_)
        },
        span(className := "absolute -inset-1.5"),
        span(className := "sr-only", "Open user menu"),
        img(
          src <-- config.userAvatar,
          alt <-- config.userName,
          className := "size-8 rounded-full bg-gray-50 outline -outline-offset-1 outline-black/5 dark:bg-gray-800 dark:outline-white/10"
        ),
        span(
          className := "hidden lg:flex lg:items-center",
          span(
            aria.hidden := true,
            className := "ml-4 text-sm/6 font-semibold text-gray-900 dark:text-white",
            child.text <-- config.userName
          ),
          Icon.chevronDown()
        )
      ),

      // Dropdown menu
      child <-- isOpen.signal.map { open =>
        if (open) {
          div(
            className := "absolute right-0 z-10 mt-2.5 w-48 origin-top-right rounded-md bg-white py-2 shadow-lg ring-1 ring-gray-900/5 transition focus:outline-none dark:bg-gray-800 dark:shadow-none dark:-outline-offset-1 dark:ring-white/10",
            onMountCallback { _ =>
              // Add click outside listener
              dom.window.setTimeout(() => {
                dom.document.addEventListener("click", outsideClickListener)
              }, 0)
            },
            onUnmountCallback { _ =>
              dom.document.removeEventListener("click", outsideClickListener)
            },

            // Render menu items
            config.menuItems.map { item =>
              a(
                href := "#",
                className := "block px-3 py-1 text-sm/6 text-gray-900 hover:bg-gray-50 dark:text-white dark:hover:bg-white/5",
                onClick.preventDefault --> Observer[dom.MouseEvent] { _ =>
                  isOpen.set(false)
                  item.onClick()
                },
                item.icon.toList.map(icon => span(className := "mr-2", icon)),
                item.label
              )
            },

            // Logout (always at bottom)
            a(
              href := "#",
              className := "block px-3 py-1 text-sm/6 text-gray-900 hover:bg-gray-50 dark:text-white dark:hover:bg-white/5 border-t border-gray-100 dark:border-gray-700 mt-1 pt-1",
              onClick.preventDefault --> Observer[dom.MouseEvent] { _ =>
                isOpen.set(false)
                config.onLogout()
              },
              "Sign out"
            )
          )
        } else {
          emptyNode
        }
      }
    )
  }
}
