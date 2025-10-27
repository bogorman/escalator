package escalator.frontend.components.charts

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import typings.lightweightCharts.mod._
import escalator.frontend.components.charts.ChartTypes._
import escalator.frontend.components.charts.ChartUtils._

/**
  * Line chart component for simple time series data
  */
object LineChart {

  /**
    * Create a line chart
    *
    * @param data Line data signal
    * @param color Line color
    * @param lineWidth Line width
    * @param options Chart configuration
    * @param containerId HTML element ID
    * @return Laminar HtmlElement
    */
  def apply(
    data: Signal[List[LineDataPoint]],
    color: String = "#2196F3",
    lineWidth: Int = 2,
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "line-chart"
  ): HtmlElement = {

    var chartInstance: Option[IChartApi] = None
    var seriesInstance: Option[ISeriesApi[_, _, _, _, _]] = None

    div(
      idAttr := containerId,
      className := "relative",
      styleAttr := s"height: ${options.height}px;",

      onMountCallback { ctx =>
        try {
          val container = ctx.thisNode.ref

          // Create chart
          val chartOptions = createChartOptions(options)
          val chart = createChart(container.asInstanceOf[dom.HTMLElement], chartOptions.asInstanceOf[typings.lightweightCharts.anon.DeepPartialChartOptions])
          chartInstance = Some(chart)

          // Add line series (v5.0.9 API)
          val lineOptions = js.Dynamic.literal(
            color = color,
            lineWidth = lineWidth
          ).asInstanceOf[LineSeriesPartialOptions]
          val series = chart.addSeries_Line(LineSeries, lineOptions)
          seriesInstance = Some(series)

          if (options.width.isEmpty) {
            enableDynamicResize(chart, containerId)
          }

          // Observe data changes
          val dataSubscription = data.addObserver(Observer[List[LineDataPoint]] { points =>
            try {
              val jsData = toLineData(points)
              series.setData(jsData)
              chart.timeScale().fitContent()
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating line data: ${e.getMessage}")
            }
          })(ctx.owner)

          // Return cleanup function for unmount
          () => {
            dataSubscription.kill()
            chartInstance.foreach(_.remove())
            chartInstance = None
            seriesInstance = None
          }

        } catch {
          case e: Throwable =>
            dom.console.error(s"Error creating line chart: ${e.getMessage}")
            e.printStackTrace()
            () => () // Return no-op cleanup on error
        }
      }
    )
  }

  /**
    * Create an area chart (filled line)
    */
  def area(
    data: Signal[List[LineDataPoint]],
    lineColor: String = "#2196F3",
    topColor: String = "rgba(33, 150, 243, 0.56)",
    bottomColor: String = "rgba(33, 150, 243, 0.04)",
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "area-chart"
  ): HtmlElement = {

    var chartInstance: Option[IChartApi] = None
    var seriesInstance: Option[ISeriesApi[_, _, _, _, _]] = None

    div(
      idAttr := containerId,
      className := "relative",
      styleAttr := s"height: ${options.height}px;",

      onMountCallback { ctx =>
        try {
          val container = ctx.thisNode.ref

          val chartOptions = createChartOptions(options)
          val chart = createChart(container.asInstanceOf[dom.HTMLElement], chartOptions.asInstanceOf[typings.lightweightCharts.anon.DeepPartialChartOptions])
          chartInstance = Some(chart)

          // Add area series (v5.0.9 API)
          val areaOptions = js.Dynamic.literal(
            lineColor = lineColor,
            topColor = topColor,
            bottomColor = bottomColor,
            lineWidth = 2
          ).asInstanceOf[AreaSeriesPartialOptions]
          val series = chart.addSeries_Area(AreaSeries, areaOptions)
          seriesInstance = Some(series)

          if (options.width.isEmpty) {
            enableDynamicResize(chart, containerId)
          }

          val dataSubscription = data.addObserver(Observer[List[LineDataPoint]] { points =>
            try {
              val jsData = toAreaData(points)
              series.setData(jsData)
              chart.timeScale().fitContent()
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating area data: ${e.getMessage}")
            }
          })(ctx.owner)

          // Return cleanup function for unmount
          () => {
            dataSubscription.kill()
            chartInstance.foreach(_.remove())
            chartInstance = None
            seriesInstance = None
          }

        } catch {
          case e: Throwable =>
            dom.console.error(s"Error creating area chart: ${e.getMessage}")
            () => () // Return no-op cleanup on error
        }
      }
    )
  }
}
