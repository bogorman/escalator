package escalator.frontend.components.tables

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Reusable DataTable component for Laminar
  * Following quanty-os pattern of custom HTML tables with TailwindCSS
  *
  * Features:
  * - Type-safe column definitions
  * - Reactive data binding with Signals
  * - Loading states with skeleton loaders
  * - Empty states with custom messages
  * - Row click handlers
  * - Hover effects
  * - Responsive design
  *
  * @tparam A The type of data in each row
  */
object DataTable {

  /**
    * Create a DataTable component
    *
    * @param data Signal containing the list of rows to display
    * @param columns List of column definitions
    * @param isLoading Signal indicating loading state
    * @param onRowClick Optional click handler for rows
    * @param emptyMessage Message to show when data is empty
    * @param options Table configuration options
    * @param initialSort Optional initial sort state
    * @tparam A The type of data in each row
    */
  def apply[A](
    data: Signal[List[A]],
    columns: List[Column[A]],
    isLoading: Signal[Boolean] = Var(false).signal,
    onRowClick: Option[A => Unit] = None,
    emptyMessage: String = "No data found",
    options: TableOptions = TableOptions.default,
    initialSort: Option[SortState] = None
  ): HtmlElement = {

    // Sort state management
    val sortStateVar = Var(initialSort)

    // Create sorted data signal
    val sortedData = data.combineWith(sortStateVar.signal).map { case (rows, sortStateOpt) =>
      sortStateOpt match {
        case Some(sortState) if sortState.columnIndex >= 0 && sortState.columnIndex < columns.length =>
          val column = columns(sortState.columnIndex)
          column.sortBy match {
            case Some(extractor) =>
              sortData(rows, extractor, sortState.direction)
            case None => rows
          }
        case _ => rows
      }
    }

    // Container class names
    val containerClass = if (options.containerClassName.isEmpty) {
      "flex flex-col"
    } else {
      s"flex flex-col ${options.containerClassName}"
    }

    // Border class
    val borderClass = if (options.bordered) "border border-gray-200" else ""

    // Rounded corners class
    val roundedClass = if (options.roundedCorners) "sm:rounded-md" else ""

    div(
      className := containerClass,
      div(
        className := "-my-2 overflow-x-auto sm:-mx-6 lg:-mx-8",
        div(
          className := "py-2 align-middle inline-block min-w-full sm:px-3 lg:px-8",

          // Empty state check
          child <-- sortedData.combineWith(isLoading).map { case (rows, loading) =>
            if (!loading && rows.isEmpty) {
              renderEmptyState(emptyMessage, options)
            } else {
              div(
                className := s"overflow-hidden $borderClass $roundedClass",
                table(
                  className := buildTableClassName(options),

                  // Table header
                  thead(
                    className := "bg-gray-50",
                    tr(
                      columns.zipWithIndex.map { case (column, index) =>
                        renderHeaderCell(column, index, sortStateVar)
                      }
                    )
                  ),

                  // Table body
                  tbody(
                    className := buildBodyClassName(options),

                    children <-- isLoading.map { loading =>
                      if (loading) {
                        // Show skeleton loaders
                        SkeletonLoader.rows(
                          columnCount = columns.length,
                          rowCount = 5
                        )
                      } else {
                        // Show actual data
                        rows.map { row =>
                          renderDataRow(row, columns, onRowClick, options)
                        }
                      }
                    }
                  )
                )
              )
            }
          }
        )
      )
    )
  }

  /**
    * Sort data by a column
    */
  private def sortData[A](
    data: List[A],
    extractor: A => Any,
    direction: SortDirection
  ): List[A] = {
    try {
      val sorted = data.sortWith { (a, b) =>
        val valA = extractor(a)
        val valB = extractor(b)

        val comparison = (valA, valB) match {
          case (sa: String, sb: String) =>
            sa.toLowerCase < sb.toLowerCase
          case (na: Int, nb: Int) =>
            na < nb
          case (na: Long, nb: Long) =>
            na < nb
          case (na: Double, nb: Double) =>
            na < nb
          case (na: Float, nb: Float) =>
            na < nb
          case (oa, ob) =>
            oa.toString.toLowerCase < ob.toString.toLowerCase
        }

        direction match {
          case SortDirection.Ascending => comparison
          case SortDirection.Descending => !comparison
        }
      }
      sorted
    } catch {
      case _: Throwable => data // Return unsorted if sorting fails
    }
  }

  /**
    * Handle sort click
    */
  private def handleSort(columnIndex: Int, sortStateVar: Var[Option[SortState]]): Unit = {
    val currentSort = sortStateVar.now()

    val newSort = currentSort match {
      case Some(state) if state.columnIndex == columnIndex =>
        // Toggle direction on same column
        Some(SortState(columnIndex, SortDirection.toggle(state.direction)))
      case _ =>
        // New column, default to ascending
        Some(SortState(columnIndex, SortDirection.Ascending))
    }

    sortStateVar.set(newSort)
  }

  /**
    * Render a table header cell
    */
  private def renderHeaderCell[A](
    column: Column[A],
    columnIndex: Int,
    sortStateVar: Var[Option[SortState]]
  ): HtmlElement = {
    val baseClass = "px-3 py-3 text-xs font-medium text-gray-500 uppercase tracking-wider"
    val alignClass = column.alignment.className
    val customClass = if (column.headerClassName.nonEmpty) column.headerClassName else ""
    val cursorClass = if (column.sortable) "cursor-pointer hover:bg-gray-100" else ""
    val finalClass = s"$baseClass $alignClass $customClass $cursorClass".trim

    val widthStyle = column.width.map(w => s"width: $w;").getOrElse("")

    val currentSort = sortStateVar.signal.map(_.filter(_.columnIndex == columnIndex))

    th(
      className := finalClass,
      styleAttr := widthStyle,

      // Add click handler if sortable
      if (column.sortable) {
        onClick --> Observer[dom.MouseEvent] { _ =>
          handleSort(columnIndex, sortStateVar)
        }
      } else {
        emptyMod
      },

      div(
        className := "flex items-center justify-between",
        span(column.header),

        // Sort indicator
        if (column.sortable) {
          child <-- currentSort.map {
            case Some(state) =>
              span(
                className := "ml-2 text-gray-900",
                if (state.direction == SortDirection.Ascending) "↑" else "↓"
              )
            case None =>
              span(className := "ml-2 text-gray-400", "↕")
          }
        } else {
          emptyNode
        }
      )
    )
  }

  /**
    * Render a data row
    */
  private def renderDataRow[A](
    row: A,
    columns: List[Column[A]],
    onRowClick: Option[A => Unit],
    options: TableOptions
  ): HtmlElement = {
    val baseClass = "bg-white"
    val hoverClass = if (options.hoverable && onRowClick.isDefined) "hover:bg-gray-100 cursor-pointer" else ""
    val transitionClass = if (onRowClick.isDefined) "transition-all" else ""
    val finalClass = s"$baseClass $hoverClass $transitionClass".trim

    tr(
      className := finalClass,

      // Add click handler if provided
      onRowClick.map { handler =>
        onClick.mapTo(row) --> Observer[A](handler)
      },

      // Render cells
      columns.map { column =>
        renderDataCell(row, column)
      }
    )
  }

  /**
    * Render a data cell
    */
  private def renderDataCell[A](row: A, column: Column[A]): HtmlElement = {
    val baseClass = "px-3 py-4 whitespace-nowrap text-sm text-gray-900"
    val alignClass = column.alignment.className
    val customClass = if (column.cellClassName.nonEmpty) column.cellClassName else ""
    val finalClass = s"$baseClass $alignClass $customClass".trim

    td(
      className := finalClass,
      column.render(row)
    )
  }

  /**
    * Render empty state
    */
  private def renderEmptyState(message: String, options: TableOptions): HtmlElement = {
    div(
      className := "pt-12 pb-6 w-full max-w-6xl mx-auto",
      div(
        className := "border border-gray-200 text-base text-gray-500 rounded p-6 text-center",

        // Optional icon
        options.emptyStateIcon.map { icon =>
          div(
            className := "text-4xl mb-4 text-gray-400",
            icon
          )
        },

        // Message
        p(
          className := "mb-4",
          message
        ),

        // Optional action button
        options.emptyStateAction.map { case (label, action) =>
          button(
            className := "px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700",
            label,
            onClick --> Observer[dom.MouseEvent](_ => action())
          )
        }
      )
    )
  }

  /**
    * Build table className based on options
    */
  private def buildTableClassName(options: TableOptions): String = {
    val baseClass = "min-w-full divide-y divide-gray-200"
    val customClass = if (options.tableClassName.nonEmpty) options.tableClassName else ""
    val compactClass = if (options.compact) "table-compact" else ""

    s"$baseClass $customClass $compactClass".trim
  }

  /**
    * Build table body className based on options
    */
  private def buildBodyClassName(options: TableOptions): String = {
    val baseClass = "bg-white divide-y divide-gray-200"
    val stripedClass = if (options.striped) "even:bg-gray-50" else ""

    s"$baseClass $stripedClass".trim
  }

  /**
    * Helper: Create a simple text column
    */
  def textColumn[A](
    header: String,
    accessor: A => String,
    alignment: ColumnAlignment = ColumnAlignment.Left,
    sortable: Boolean = false
  ): Column[A] = {
    Column[A](
      header = header,
      render = row => span(accessor(row)),
      alignment = alignment,
      sortable = sortable,
      sortBy = if (sortable) Some(accessor) else None
    )
  }

  /**
    * Helper: Create a numeric column (right-aligned by default)
    */
  def numberColumn[A](
    header: String,
    accessor: A => Double,
    format: Double => String = _.toString,
    sortable: Boolean = false
  ): Column[A] = {
    Column[A](
      header = header,
      render = row => span(format(accessor(row))),
      alignment = ColumnAlignment.Right,
      sortable = sortable,
      sortBy = if (sortable) Some(accessor) else None
    )
  }

  /**
    * Helper: Create a badge column
    */
  def badgeColumn[A](
    header: String,
    accessor: A => String,
    colorClass: A => String = (_: A) => "bg-blue-100 text-blue-800"
  ): Column[A] = {
    Column[A](
      header = header,
      render = row => {
        val text = accessor(row)
        val color = colorClass(row)
        span(
          className := s"px-2 py-1 text-xs font-medium rounded-full $color",
          text
        )
      }
    )
  }

  /**
    * Helper: Create an action column with buttons
    */
  def actionColumn[A](
    header: String = "Actions",
    actions: List[(String, A => Unit, A => String)]
  ): Column[A] = {
    Column[A](
      header = header,
      render = row => {
        div(
          className := "flex gap-2",
          actions.map { case (label, handler, classNameFn) =>
            button(
              className := classNameFn(row),
              label,
              onClick.stopPropagation.mapTo(row) --> Observer[A](handler)
            )
          }
        )
      },
      alignment = ColumnAlignment.Center
    )
  }
}
