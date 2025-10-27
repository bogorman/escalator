package escalator.frontend.components.tables

import com.raquo.laminar.api.L._

/**
  * Column alignment options
  */
sealed trait ColumnAlignment {
  def className: String
}

object ColumnAlignment {
  case object Left extends ColumnAlignment {
    val className = "text-left"
  }
  case object Center extends ColumnAlignment {
    val className = "text-center"
  }
  case object Right extends ColumnAlignment {
    val className = "text-right"
  }
}

/**
  * Column definition for DataTable
  *
  * @tparam A The type of data in each row
  * @param header Column header text
  * @param render Function to render cell content from row data
  * @param alignment Column alignment (default: Left)
  * @param width Optional column width (CSS value like "200px" or "20%")
  * @param headerClassName Additional CSS classes for header cell
  * @param cellClassName Additional CSS classes for data cells
  * @param sortable Whether this column can be sorted
  * @param sortBy Function to extract sortable value from row (required if sortable = true)
  */
case class Column[A](
  header: String,
  render: A => HtmlElement,
  alignment: ColumnAlignment = ColumnAlignment.Left,
  width: Option[String] = None,
  headerClassName: String = "",
  cellClassName: String = "",
  sortable: Boolean = false,
  sortBy: Option[A => Any] = None
)

/**
  * Table configuration options
  */
case class TableOptions(
  striped: Boolean = false,
  bordered: Boolean = true,
  hoverable: Boolean = true,
  compact: Boolean = false,
  roundedCorners: Boolean = true,
  containerClassName: String = "",
  tableClassName: String = "",
  emptyStateIcon: Option[String] = None,
  emptyStateAction: Option[(String, () => Unit)] = None
)

object TableOptions {
  val default: TableOptions = TableOptions()

  val compact: TableOptions = TableOptions(compact = true)

  val minimal: TableOptions = TableOptions(
    bordered = false,
    roundedCorners = false
  )
}

/**
  * Sorting state for sortable tables
  */
case class SortState(
  columnIndex: Int,
  direction: SortDirection
)

sealed trait SortDirection
object SortDirection {
  case object Ascending extends SortDirection
  case object Descending extends SortDirection

  def toggle(current: SortDirection): SortDirection = current match {
    case Ascending => Descending
    case Descending => Ascending
  }
}
