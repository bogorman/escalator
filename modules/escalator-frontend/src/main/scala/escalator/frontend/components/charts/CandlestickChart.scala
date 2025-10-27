package escalator.frontend.components.charts

import com.raquo.laminar.api.L._
import org.scalajs.dom
import scala.scalajs.js
import typings.lightweightCharts.mod._
import escalator.frontend.components.charts.ChartTypes._
import escalator.frontend.components.charts.ChartUtils._


/**
  * Candlestick chart component with trade markers
  */
object CandlestickChart {

  /**
    * Create a candlestick chart
    *
    * @param data CandleData signal
    * @param markers Optional trade markers
    * @param options Chart configuration
    * @param containerId HTML element ID for the chart container
    * @return Laminar HtmlElement
    */
  def apply(
    data: Signal[List[CandleData]],
    markers: Signal[List[TradeMarker]] = Var(List.empty).signal,
    options: RocketChartOptions = RocketChartOptions(),
    containerId: String = "candlestick-chart"
  ): HtmlElement = {

    // Mutable refs for cleanup
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

          // Add candlestick series (v5.0.9 API)
          val series = chart.addSeries_Candlestick(CandlestickSeries)
          seriesInstance = Some(series)

          // Enable dynamic resizing if width not specified
          if (options.width.isEmpty) {
            enableDynamicResize(chart, containerId)
          }

          // Observe candle data changes
          val dataSubscription = data.addObserver(Observer[List[CandleData]] { candles =>
            try {
              val jsData = toCandlestickData(candles)
              series.setData(jsData)
              chart.timeScale().fitContent()
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating candle data: ${e.getMessage}")
            }
          })(ctx.owner)

          // Observe trade markers
          val markersSubscription = markers.addObserver(Observer[List[TradeMarker]] { markerList =>
            try {
              if (markerList.nonEmpty) {
                val jsMarkers = toSeriesMarkers(markerList)
                series.asInstanceOf[js.Dynamic].setMarkers(jsMarkers)
              }
            } catch {
              case e: Throwable =>
                dom.console.error(s"Error updating trade markers: ${e.getMessage}")
            }
          })(ctx.owner)

          // Return cleanup function for unmount
          () => {
            dataSubscription.kill()
            markersSubscription.kill()
            chartInstance.foreach(_.remove())
            chartInstance = None
            seriesInstance = None
          }

        } catch {
          case e: Throwable =>
            dom.console.error(s"Error creating candlestick chart: ${e.getMessage}")
            e.printStackTrace()
            () => () // Return no-op cleanup on error
        }
      }
    )
  }

}
