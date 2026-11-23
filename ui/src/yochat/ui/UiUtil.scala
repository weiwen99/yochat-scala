package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import org.scalajs.dom

object UiUtil:

  def copy(text: String, ui: UiState): Unit =
    val clipboard = dom.window.navigator.clipboard
    clipboard.writeText(text).toFuture.onComplete {
      case Success(_)    => ui.notify("已复制")
      case Failure(fail) => ui.failure(s"复制失败：${fail.getMessage}")
    }
