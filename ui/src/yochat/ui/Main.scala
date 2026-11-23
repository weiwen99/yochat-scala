package yochat.ui

import scala.scalajs.js

import com.raquo.laminar.api.L.*
import org.scalajs.dom

@main def yochatUi(): Unit =
  val api = new ApiClient
  val ui  = new UiState
  if js.typeOf(js.Dynamic.global.yochat) != "undefined" then dom.document.body.classList.add("electron")

  val app = div(
    cls := "app-root",
    child <-- api.unlocked.signal.map { unlocked =>
      if unlocked then Shell(api, ui)
      else UnlockView(api)
    },
    child.maybe <-- api.loading.signal.map { loading =>
      if loading then
        Some(
          div(
            cls := "loading-veil",
            div(
              cls := "loading-card",
              span(cls := "loading-mark", "有"),
              span("正在打开本机…"),
              span(cls := "loading-rule")
            )
          )
        )
      else None
    }
  )

  render(dom.document.getElementById("app"), app)
  api.start()
