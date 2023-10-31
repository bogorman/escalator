package escalator.errors

final case class FrontendError(errorKey: String, message: String) extends Throwable(message)
