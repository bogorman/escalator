package escalator.frontend.components.tables

import com.raquo.laminar.api.L._

/**
  * Skeleton loader components for table loading states
  * Uses pure CSS animations with Tailwind classes for smooth loading effect
  */
object SkeletonLoader {

  /**
    * Single skeleton cell with animated shimmer effect
    *
    * @param width CSS width value (e.g., "60px", "100%")
    * @param height CSS height value (default: "16px")
    */
  def cell(width: String = "100%", height: String = "16px"): HtmlElement = {
    div(
      className := "bg-gray-200 rounded animate-pulse",
      styleAttr := s"width: $width; height: $height;"
    )
  }

  /**
    * Skeleton row for table loading state
    *
    * @param columnCount Number of columns to render
    * @param columnWidths Optional list of column widths (CSS values)
    */
  def row(columnCount: Int, columnWidths: List[String] = List.empty): HtmlElement = {
    val widths = if (columnWidths.isEmpty) {
      // Default widths - vary them for more realistic look
      List.fill(columnCount)("70%")
    } else {
      columnWidths
    }

    tr(
      className := "bg-white",
      (0 until columnCount).map { i =>
        val width = if (i < widths.length) widths(i) else "70%"
        td(
          className := "px-3 py-4 whitespace-nowrap",
          cell(width = width)
        )
      }
    )
  }

  /**
    * Multiple skeleton rows for table loading state
    *
    * @param columnCount Number of columns
    * @param rowCount Number of loading rows to show (default: 5)
    * @param columnWidths Optional list of column widths
    */
  def rows(
    columnCount: Int,
    rowCount: Int = 5,
    columnWidths: List[String] = List.empty
  ): List[HtmlElement] = {
    (0 until rowCount).map { _ =>
      row(columnCount, columnWidths)
    }.toList
  }

  /**
    * Skeleton table for completely loading state
    * (Useful for showing a skeleton before any data is loaded)
    *
    * @param columnCount Number of columns
    * @param rowCount Number of skeleton rows
    * @param columnHeaders Optional column header names
    */
  def skeletonTable(
    columnCount: Int,
    rowCount: Int = 5,
    columnHeaders: List[String] = List.empty
  ): HtmlElement = {
    val skeletonRows = rows(columnCount, rowCount)

    div(
      className := "flex flex-col",
      div(
        className := "-my-2 overflow-x-auto sm:-mx-6 lg:-mx-8",
        div(
          className := "py-2 align-middle inline-block min-w-full sm:px-3 lg:px-8",
          div(
            className := "overflow-hidden border border-gray-200 sm:rounded-md",
            table(
              className := "min-w-full divide-y divide-gray-200",
              thead(
                className := "bg-gray-50",
                tr(
                  (0 until columnCount).map { i =>
                    th(
                      className := "px-3 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider",
                      if (i < columnHeaders.length) columnHeaders(i) else ""
                    )
                  }
                )
              ),
              tbody(
                className := "bg-white divide-y divide-gray-200",
                skeletonRows
              )
            )
          )
        )
      )
    )
  }

  /**
    * Card-style skeleton loader for mobile responsive view
    */
  def card(): HtmlElement = {
    div(
      className := "border border-gray-200 p-4 rounded-md space-y-3",
      // Header
      cell(width = "60%", height = "20px"),
      // Subheader
      cell(width = "40%", height = "14px"),
      // Divider
      div(className := "border-t border-gray-200 my-3"),
      // Content rows
      cell(width = "80%", height = "16px"),
      cell(width = "50%", height = "16px"),
      cell(width = "70%", height = "16px")
    )
  }

  /**
    * Multiple card skeletons for mobile view
    */
  def cards(count: Int = 3): List[HtmlElement] = {
    (0 until count).map(_ => card()).toList
  }
}
