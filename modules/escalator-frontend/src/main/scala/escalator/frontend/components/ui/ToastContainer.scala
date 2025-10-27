package escalator.frontend.components.ui

import com.raquo.laminar.api.L._
import org.scalajs.dom

/**
  * Toast Container component
  * Manages multiple toast notifications with auto-dismiss and global API
  *
  * Usage:
  * 1. Add ToastContainer() to your main layout (only once)
  * 2. Use ToastService to show toasts from anywhere in your app
  */
object ToastContainer {

  /**
    * Create a ToastContainer component
    *
    * @param options Container configuration (position, max toasts, etc.)
    */
  def apply(
    options: ToastContainerOptions = ToastContainerOptions.default
  ): HtmlElement = {

    // Subscribe to toast events from ToastService
    val toastAddBus = ToastService.toastBus.events

    // Local state for active toasts
    val toastsVar = Var(List.empty[ToastData])

    // Add toast handler
    val addToastObserver = Observer[ToastData] { newToast =>
      val currentToasts = toastsVar.now()

      // Enforce max toasts limit - remove oldest if at limit
      val updatedToasts = if (currentToasts.length >= options.maxToasts) {
        currentToasts.drop(1) :+ newToast
      } else {
        currentToasts :+ newToast
      }

      toastsVar.set(updatedToasts)

      // Auto-dismiss if duration > 0
      if (newToast.duration > 0) {
        scheduleRemoval(newToast.id, newToast.duration, toastsVar)
      }
    }

    div(
      className := s"fixed ${options.position.className} z-50 flex flex-col ${options.spacing}",

      // Subscribe to toast events
      toastAddBus --> addToastObserver,

      // Render active toasts
      children <-- toastsVar.signal.map { toasts =>
        toasts.map { toastData =>
          Toast(
            data = toastData,
            onDismiss = () => removeToast(toastData.id, toastsVar)
          )
        }
      }
    )
  }

  /**
    * Remove a toast by ID
    */
  private def removeToast(id: String, toastsVar: Var[List[ToastData]]): Unit = {
    toastsVar.update(_.filterNot(_.id == id))
  }

  /**
    * Schedule automatic toast removal
    */
  private def scheduleRemoval(id: String, duration: Int, toastsVar: Var[List[ToastData]]): Unit = {
    dom.window.setTimeout(() => {
      removeToast(id, toastsVar)
    }, duration.toDouble)
  }
}

/**
  * Global Toast Service
  * Use this singleton to show toasts from anywhere in your app
  */
object ToastService {

  // EventBus for toast notifications
  val toastBus = new EventBus[ToastData]

  // ID counter for unique toast IDs
  private var idCounter = 0

  /**
    * Generate unique toast ID
    */
  private def nextId(): String = {
    idCounter += 1
    s"toast-$idCounter"
  }

  /**
    * Show a toast notification
    *
    * @param message Toast message
    * @param toastType Type of toast (success, error, warning, info)
    * @param duration Duration in milliseconds (0 = no auto-dismiss)
    * @param dismissible Whether toast can be manually dismissed
    * @param action Optional action button (label, handler)
    */
  def show(
    message: String,
    toastType: ToastType = ToastType.Info,
    duration: Int = 5000,
    dismissible: Boolean = true,
    action: Option[(String, () => Unit)] = None
  ): Unit = {
    val toast = ToastData(
      id = nextId(),
      message = message,
      toastType = toastType,
      duration = duration,
      dismissible = dismissible,
      action = action
    )
    toastBus.writer.onNext(toast)
  }

  /**
    * Show a success toast
    */
  def success(message: String, duration: Int = 5000): Unit = {
    show(message, ToastType.Success, duration)
  }

  /**
    * Show an error toast
    */
  def error(message: String, duration: Int = 5000): Unit = {
    show(message, ToastType.Error, duration)
  }

  /**
    * Show a warning toast
    */
  def warning(message: String, duration: Int = 5000): Unit = {
    show(message, ToastType.Warning, duration)
  }

  /**
    * Show an info toast
    */
  def info(message: String, duration: Int = 5000): Unit = {
    show(message, ToastType.Info, duration)
  }

  /**
    * Show a success toast with custom action
    */
  def successWithAction(message: String, actionLabel: String, onAction: () => Unit, duration: Int = 7000): Unit = {
    show(message, ToastType.Success, duration, action = Some((actionLabel, onAction)))
  }

  /**
    * Show an error toast with custom action
    */
  def errorWithAction(message: String, actionLabel: String, onAction: () => Unit, duration: Int = 7000): Unit = {
    show(message, ToastType.Error, duration, action = Some((actionLabel, onAction)))
  }

  /**
    * Show a persistent toast (no auto-dismiss)
    */
  def persistent(message: String, toastType: ToastType = ToastType.Info): Unit = {
    show(message, toastType, duration = 0)
  }
}
