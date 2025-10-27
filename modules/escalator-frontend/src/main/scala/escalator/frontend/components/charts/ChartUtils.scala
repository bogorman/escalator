package escalator.frontend.components.charts

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.`|`
import scala.scalajs.js.JSConverters._
import typings.lightweightCharts.mod._
import escalator.frontend.components.charts.ChartTypes._

/**
  * Generic utility functions for chart operations
  */
object ChartUtils {

  /**
    * Convert Scala CandleData to JS CandlestickData
    */
  def toCandlestickData(candles: List[CandleData]): js.Array[CandlestickData[Time] | WhitespaceData[Time]] = {
    candles.map { c =>
      js.Dynamic.literal(
        time = c.time,
        open = c.open,
        high = c.high,
        low = c.low,
        close = c.close
      ).asInstanceOf[CandlestickData[Time]]
    }.toJSArray.asInstanceOf[js.Array[CandlestickData[Time] | WhitespaceData[Time]]]
  }

  /**
    * Convert Scala LineDataPoint to JS LineData
    */
  def toLineData(data: List[LineDataPoint]): js.Array[LineData[Time] | WhitespaceData[Time]] = {
    data.map { d =>
      js.Dynamic.literal(
        time = d.time,
        value = d.value
      ).asInstanceOf[LineData[Time]]
    }.toJSArray.asInstanceOf[js.Array[LineData[Time] | WhitespaceData[Time]]]
  }

  /**
    * Convert Scala LineDataPoint to JS BaselineData
    */
  def toBaselineData(data: List[LineDataPoint]): js.Array[BaselineData[Time] | WhitespaceData[Time]] = {
    data.map { d =>
      js.Dynamic.literal(
        time = d.time,
        value = d.value
      ).asInstanceOf[BaselineData[Time]]
    }.toJSArray.asInstanceOf[js.Array[BaselineData[Time] | WhitespaceData[Time]]]
  }

  /**
    * Convert Scala LineDataPoint to JS AreaData
    */
  def toAreaData(data: List[LineDataPoint]): js.Array[AreaData[Time] | WhitespaceData[Time]] = {
    data.map { d =>
      js.Dynamic.literal(
        time = d.time,
        value = d.value
      ).asInstanceOf[AreaData[Time]]
    }.toJSArray.asInstanceOf[js.Array[AreaData[Time] | WhitespaceData[Time]]]
  }

  /**
    * Create custom markers from TradeMarker list
    */
  def toSeriesMarkers(markers: List[TradeMarker]): js.Array[SeriesMarker[UTCTimestamp]] = {
    markers.map { m =>
      val literal = js.Dynamic.literal(
        time = m.time,
        position = m.position.value,
        color = m.color,
        shape = m.shape.value
      )

      // Add text if present
      m.text.foreach { text =>
        literal.updateDynamic("text")(text)
      }

      literal.asInstanceOf[SeriesMarker[UTCTimestamp]]
    }.toJSArray
  }

  /**
    * Enable dynamic chart resizing on window resize
    */
  def enableDynamicResize(chart: IChartApi, containerId: String): Unit = {
    val resizeHandler: js.Function1[dom.Event, Unit] = { _: dom.Event =>
      val container = dom.document.getElementById(containerId)
      if (container != null) {
        val width = container.clientWidth
        val height = container.clientHeight
        if (width > 0 && height > 0) {
          chart.resize(width, height)
        }
      }
    }

    dom.window.addEventListener("resize", resizeHandler)
  }

  /**
    * Create chart options object
    */
  def createChartOptions(opts: RocketChartOptions): js.Object = {
    val options = js.Dynamic.literal(
      height = opts.height,
      rightPriceScale = js.Dynamic.literal(
        borderVisible = opts.borderVisible
      ),
      timeScale = js.Dynamic.literal(
        borderVisible = opts.borderVisible,
        timeVisible = opts.timeVisible,
        secondsVisible = opts.secondsVisible
      ),
      grid = js.Dynamic.literal(
        horzLines = js.Dynamic.literal(color = "#eee"),
        vertLines = js.Dynamic.literal(color = "#eee")
      )
    )

    // Add width if specified
    opts.width.foreach { w =>
      options.updateDynamic("width")(w)
    }

    options.asInstanceOf[js.Object]
  }

  /**
    * Format price for display
    */
  def formatPrice(price: Double, decimals: Int = 2): String = {
    val formatStr = s"%.${decimals}f"
    formatStr.format(price)
  }

  /**
    * Convert epoch seconds to UTCTimestamp
    */
  def toUTCTimestamp(epochSeconds: Long): UTCTimestamp = {
    epochSeconds.toDouble.asInstanceOf[UTCTimestamp]
  }

  /**
    * Primary color palette for multi-series charts
    */
  val primaryColors: List[String] = List(
    "#2196F3",  // Blue
    "#4CAF50",  // Green
    "#FF9800",  // Orange
    "#9C27B0",  // Purple
    "#F44336",  // Red
    "#00BCD4",  // Cyan
    "#FFEB3B",  // Yellow
    "#795548",  // Brown
    "#607D8B",  // Blue Grey
    "#E91E63"   // Pink
  )

  /**
    * Get color from palette by index
    */
  def getPrimaryColor(index: Int): String = {
    primaryColors(index % primaryColors.length)
  }
}
