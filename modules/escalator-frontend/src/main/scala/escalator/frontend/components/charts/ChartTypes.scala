package escalator.frontend.components.charts

import scala.scalajs.js

/**
  * Type definitions for chart data structures
  */
object ChartTypes {

  /**
    * Candlestick data point
    */
  case class CandleData(
    time: Long,      // Unix timestamp (seconds)
    open: Double,
    high: Double,
    low: Double,
    close: Double
  )

  /**
    * Line/Area data point
    */
  case class LineDataPoint(
    time: Long,      // Unix timestamp (seconds)
    value: Double
  )

  /**
    * Trade marker for buy/sell indicators on charts
    */
  case class TradeMarker(
    time: Long,
    position: MarkerPosition,
    color: String,
    shape: MarkerShape,
    text: Option[String] = None
  )

  sealed trait MarkerPosition {
    def value: String
  }
  object MarkerPosition {
    case object AboveBar extends MarkerPosition { val value = "aboveBar" }
    case object BelowBar extends MarkerPosition { val value = "belowBar" }
    case object InBar extends MarkerPosition { val value = "inBar" }
  }

  sealed trait MarkerShape {
    def value: String
  }
  object MarkerShape {
    case object Circle extends MarkerShape { val value = "circle" }
    case object Square extends MarkerShape { val value = "square" }
    case object ArrowUp extends MarkerShape { val value = "arrowUp" }
    case object ArrowDown extends MarkerShape { val value = "arrowDown" }
  }

  /**
    * Chart size configuration
    */
  case class ChartSize(
    width: Option[Int],   // None = auto width
    height: Int = 500
  )

  /**
    * Series configuration for multi-line charts
    */
  case class SeriesConfig(
    name: String,
    data: List[LineDataPoint],
    color: String
  )

  /**
    * Price scale mode (renamed to avoid conflict with lightweight-charts library)
    */
  sealed trait ChartsPriceScaleMode {
    def value: Int
  }
  object ChartsPriceScaleMode {
    case object Normal extends ChartsPriceScaleMode { val value = 0 }
    case object Logarithmic extends ChartsPriceScaleMode { val value = 1 }
    case object Percentage extends ChartsPriceScaleMode { val value = 2 }
    case object IndexedTo100 extends ChartsPriceScaleMode { val value = 3 }
  }

  /**
    * Chart options (renamed to avoid conflict with lightweight-charts ChartOptions)
    */
  case class RocketChartOptions(
    width: Option[Int] = None,
    height: Int = 500,
    timeVisible: Boolean = true,
    secondsVisible: Boolean = false,
    borderVisible: Boolean = false
  )
}
