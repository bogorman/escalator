package escalator.frontend.components.charts

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import typings.lightweightCharts.mod._
import escalator.frontend.components.charts.ChartTypes._
import escalator.frontend.components.charts.ChartUtils._

/**
  * Baseline chart component for P&L and account value visualization
  */
object BaselineChart {

  /**
    * Create a baseline chart
    *
    * @param data Line data signal
    * @param baseValue Base value (e.g., initial account value)
    * @param benchmarks Optional benchmark series for comparison
    * @param options Chart configuration
    * @param containerId HTML element ID
    * @return Laminar HtmlElement
    */
  def apply(
    data: Signal[List[LineDataPoint]],
    baseValue: Double,
    benchmarks: Signal[List[SeriesConfig]] = Var(List.empty).signal,
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "baseline-chart"
  ): HtmlElement = {

    var chartInstance: Option[IChartApi] = None
    var baselineSeriesInstance: Option[ISeriesApi[_, _, _, _, _]] = None
    var benchmarkSeriesInstances: List[ISeriesApi[_, _, _, _, _]] = List.empty

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

          // Add baseline series (v5.0.9 API)
          val baselineOptions = js.Dynamic.literal(
            baseValue = js.Dynamic.literal(
              `type` = "price",
              price = baseValue
            )
          ).asInstanceOf[BaselineSeriesPartialOptions]
          val baselineSeries = chart.addSeries_Baseline(BaselineSeries, baselineOptions)
          baselineSeriesInstance = Some(baselineSeries)

          if (options.width.isEmpty) {
            enableDynamicResize(chart, containerId)
          }

          // Observe main data changes
          val dataSubscription = data.addObserver(Observer[List[LineDataPoint]] { points =>
            try {
              val jsData = toBaselineData(points)
              baselineSeries.setData(jsData)
              chart.timeScale().fitContent()
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating baseline data: ${e.getMessage}")
            }
          })(ctx.owner)

          // Observe benchmark changes
          val benchmarksSubscription = benchmarks.addObserver(Observer[List[SeriesConfig]] { benchmarkList =>
            try {
              // Remove existing benchmark series
              benchmarkSeriesInstances.foreach { s =>
                chart.asInstanceOf[js.Dynamic].removeSeries(s)
              }
              benchmarkSeriesInstances = List.empty

              // Add new benchmark series (v5.0.9 API)
              benchmarkList.foreach { benchmark =>
                val lineOptions = js.Dynamic.literal(
                  color = benchmark.color,
                  lineWidth = 2
                ).asInstanceOf[LineSeriesPartialOptions]
                val lineSeries = chart.addSeries_Line(LineSeries, lineOptions)
                val jsData = toLineData(benchmark.data)
                lineSeries.setData(jsData)
                benchmarkSeriesInstances = lineSeries :: benchmarkSeriesInstances
              }
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating benchmarks: ${e.getMessage}")
            }
          })(ctx.owner)

          // Return cleanup function for unmount
          () => {
            dataSubscription.kill()
            benchmarksSubscription.kill()
            chartInstance.foreach(_.remove())
            chartInstance = None
            baselineSeriesInstance = None
            benchmarkSeriesInstances = List.empty
          }

        } catch {
          case e: Throwable =>
            dom.console.error(s"Error creating baseline chart: ${e.getMessage}")
            e.printStackTrace()
            () => () // Return no-op cleanup on error
        }
      }
    )
  }

  /**
    * Create a simple baseline chart without benchmarks
    */
  def simple(
    data: Signal[List[LineDataPoint]],
    baseValue: Double,
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "baseline-chart-simple"
  ): HtmlElement = {
    apply(
      data = data,
      baseValue = baseValue,
      benchmarks = Var(List.empty).signal,
      options = options,
      containerId = containerId
    )
  }
}
