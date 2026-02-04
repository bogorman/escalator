package escalator.frontend.components.layout.dashboard

import com.raquo.laminar.api.L._

/**
  * Generic sidebar navigation component.
  *
  * Fully configurable sidebar with logo, navigation sections, and optional footer.
  * Router-agnostic and state-agnostic - all configuration comes from SidebarConfig.
  *
  * @param config Complete sidebar configuration
  */
object Sidebar {

  def apply(config: SidebarConfig): HtmlElement = {
    div(
      className := "hidden bg-gray-900 ring-1 ring-white/10 lg:fixed lg:inset-y-0 lg:z-50 lg:flex lg:w-72 lg:flex-col",

      // Sidebar content
      div(
        className := "flex grow flex-col gap-y-5 overflow-y-auto bg-black/10 px-6 pb-4",

        // Logo
        div(
          className := "flex h-16 shrink-0 items-center",
          config.logoElement
        ),

        // Navigation
        nav(
          className := "flex flex-1 flex-col",
          ul(
            role := "list",
            className := "flex flex-1 flex-col gap-y-7",

            // Render each navigation section
            config.navSections.map { section =>
              li(
                // Section title (if provided)
                section.title.map { title =>
                  div(
                    className := "text-xs/6 font-semibold text-gray-400",
                    title
                  )
                },

                // Section items
                ul(
                  role := "list",
                  className := section.title.fold("-mx-2 space-y-1")(_ => "-mx-2 mt-2 space-y-1"),
                  section.items.map { item =>
                    li(
                      SidebarNavItem(
                        label = item.label,
                        icon = item.icon,
                        isActive = item.isActive,
                        onClickHandler = item.onClick
                      )
                    )
                  }
                )
              )
            },

            // Footer element at bottom (if provided)
            config.footerElement.map { footer =>
              li(
                className := "mt-auto",
                footer
              )
            }
          )
        )
      )
    )
  }
}
