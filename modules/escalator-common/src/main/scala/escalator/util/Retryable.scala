package escalator.util

object Retryable {
  @annotation.tailrec
  def retry[T](availableRetries: Int)(action: => T): T = {
    try {
      return action
    } catch {
      case e: Exception if (availableRetries > 0) => {}
    }
    retry(availableRetries - 1)(action)
  }
}