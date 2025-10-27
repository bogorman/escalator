package escalator.frontend.components.ui

import com.raquo.laminar.api.L._

/**
  * Empty state component for when there's no data to display
  * Uses Tailwind CSS utility classes
  */
object EmptyState {

  def apply(
      title: String,
      description: String,
      icon: Option[Element] = None,
      action: Option[HtmlElement] = None
  ): HtmlElement = {
    div(
      className := "text-center py-12 px-4",
      // Icon
      icon.map { iconElement =>
        div(
          className := "mx-auto flex items-center justify-center h-12 w-12 rounded-full bg-gray-100 dark:bg-gray-800",
          iconElement
        )
      },
      // Title
      h3(
        className := "mt-2 text-lg font-medium text-gray-900 dark:text-white",
        title
      ),
      // Description
      p(
        className := "mt-1 text-sm text-gray-500 dark:text-gray-400",
        description
      ),
      // Action button
      action.map { actionElement =>
        div(
          className := "mt-6",
          actionElement
        )
      }
    )
  }

  /**
    * Empty state for no search results
    */
  def noResults(query: String, onClear: () => Unit): HtmlElement = {
    apply(
      title = "No results found",
      description = s"We couldn't find anything matching \"$query\". Try adjusting your search.",
      icon = Some(searchIcon),
      action = Some(
        Button(
          text = "Clear search",
          variant = Button.Variant.Outline,
          onClickHandler = onClear
        )
      )
    )
  }

  /**
    * Empty state for no data
    */
  def noData(
      resourceName: String,
      createText: String = "Create new",
      onCreate: Option[() => Unit] = None
  ): HtmlElement = {
    apply(
      title = s"No $resourceName yet",
      description = s"Get started by creating your first $resourceName.",
      icon = Some(plusIcon),
      action = onCreate.map { handler =>
        Button(
          text = createText,
          variant = Button.Variant.Primary,
          onClickHandler = handler
        )
      }
    )
  }

  private def searchIcon: SvgElement = {
    svg.svg(
      svg.className := "h-6 w-6 text-gray-400",
      svg.fill := "none",
      svg.viewBox := "0 0 24 24",
      svg.stroke := "currentColor",
      svg.path(
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round",
        svg.strokeWidth := "2",
        svg.d := "M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
      )
    )
  }

  private def plusIcon: SvgElement = {
    svg.svg(
      svg.className := "h-6 w-6 text-gray-400",
      svg.fill := "none",
      svg.viewBox := "0 0 24 24",
      svg.stroke := "currentColor",
      svg.path(
        svg.strokeLineCap := "round",
        svg.strokeLineJoin := "round",
        svg.strokeWidth := "2",
        svg.d := "M12 4v16m8-8H4"
      )
    )
  }
}
