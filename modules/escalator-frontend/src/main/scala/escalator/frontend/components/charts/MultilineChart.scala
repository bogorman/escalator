package escalator.frontend.components.charts

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import typings.lightweightCharts.mod._
import escalator.frontend.components.charts.ChartTypes._
import escalator.frontend.components.charts.ChartUtils._

/**
  * Multi-line chart component for comparing multiple time series
  */
object MultilineChart {

  /**
    * Create a multi-line chart
    *
    * @param data Multiple series data signal
    * @param mode Price scale mode (Normal, Percentage, etc.)
    * @param options Chart configuration
    * @param containerId HTML element ID
    * @return Laminar HtmlElement
    */
  def apply(
    data: Signal[List[SeriesConfig]],
    mode: ChartsPriceScaleMode = ChartsPriceScaleMode.Percentage,
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "multiline-chart"
  ): HtmlElement = {

    var chartInstance: Option[IChartApi] = None
    var seriesInstances: List[ISeriesApi[_, _, _, _, _]] = List.empty

    div(
      idAttr := containerId,
      className := "relative",
      styleAttr := s"height: ${options.height}px;",

      onMountCallback { ctx =>
        try {
          val container = ctx.thisNode.ref

          // Create chart with price scale mode
          val chartOptions = js.Dynamic.literal(
            height = options.height,
            rightPriceScale = js.Dynamic.literal(
              borderVisible = options.borderVisible,
              mode = mode.value
            ),
            timeScale = js.Dynamic.literal(
              borderVisible = options.borderVisible,
              timeVisible = options.timeVisible,
              secondsVisible = options.secondsVisible
            ),
            grid = js.Dynamic.literal(
              horzLines = js.Dynamic.literal(color = "#eee"),
              vertLines = js.Dynamic.literal(color = "#eee")
            )
          )

          options.width.foreach { w =>
            chartOptions.updateDynamic("width")(w)
          }

          val chart = createChart(container.asInstanceOf[dom.HTMLElement], chartOptions.asInstanceOf[typings.lightweightCharts.anon.DeepPartialChartOptions])
          chartInstance = Some(chart)

          if (options.width.isEmpty) {
            enableDynamicResize(chart, containerId)
          }

          // Observe series changes
          val dataSubscription = data.addObserver(Observer[List[SeriesConfig]] { seriesList =>
            try {
              // Remove existing series
              seriesInstances.foreach { s =>
                chart.asInstanceOf[js.Dynamic].removeSeries(s)
              }
              seriesInstances = List.empty

              // Add new series (v5.0.9 API)
              seriesList.zipWithIndex.foreach { case (seriesConfig, index) =>
                val color = if (seriesConfig.color.nonEmpty) seriesConfig.color else getPrimaryColor(index)

                val lineOptions = js.Dynamic.literal(
                  color = color,
                  lineWidth = 3
                ).asInstanceOf[LineSeriesPartialOptions]

                val series = chart.addSeries_Line(LineSeries, lineOptions)
                val jsData = toLineData(seriesConfig.data)
                series.setData(jsData)
                seriesInstances = series :: seriesInstances
              }

              if (seriesList.nonEmpty) {
                chart.timeScale().fitContent()
              }
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating multi-line data: ${e.getMessage}")
            }
          })(ctx.owner)

          // Return cleanup function for unmount
          () => {
            dataSubscription.kill()
            chartInstance.foreach(_.remove())
            chartInstance = None
            seriesInstances = List.empty
          }

        } catch {
          case e: Throwable =>
            dom.console.error(s"Error creating multi-line chart: ${e.getMessage}")
            e.printStackTrace()
            () => () // Return no-op cleanup on error
        }
      }
    )
  }

  /**
    * Create a percentage comparison chart
    */
  def percentage(
    data: Signal[List[SeriesConfig]],
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "multiline-percentage-chart"
  ): HtmlElement = {
    apply(
      data = data,
      mode = ChartsPriceScaleMode.Percentage,
      options = options,
      containerId = containerId
    )
  }

  /**
    * Create a normal (price) comparison chart
    */
  def normal(
    data: Signal[List[SeriesConfig]],
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "multiline-normal-chart"
  ): HtmlElement = {
    apply(
      data = data,
      mode = ChartsPriceScaleMode.Normal,
      options = options,
      containerId = containerId
    )
  }
}
