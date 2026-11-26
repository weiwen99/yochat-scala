package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object DevicesPanel:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "devices-panel",
      div(
        cls := "contacts-head",
        div(
          h1("设备"),
          p("主设备持有用户私钥；只读设备只接收加入后的同步副本。")
        ),
        button(
          cls := "primary-button",
          onClick --> (_ => {
            ui.deviceInvite.set("")
            ui.deviceRequest.set("")
            ui.deviceAuth.set("")
            ui.deviceConfirmation.set("")
            ui.deviceFingerprint.set("")
            ui.deviceName.set("")
            api.createPairingInvite().onComplete {
              case Success(code) => ui.deviceInvite.set(code)
              case Failure(fail) => ui.failure(api.describeError(fail))
            }
          }),
          Icons("plus"),
          "添加只读设备"
        )
      ),
      div(
        cls := "device-stack",
        child <-- api.state.signal.combineWith(api.devices.signal).map { case (state, devices) =>
          val primary    =
            deviceCard(
              name = "本机",
              role = "主设备",
              deviceId = state.identity.map(_.deviceId).getOrElse("—"),
              revoked = false,
              revoke = None,
              sync = None
            )
          val companions = devices.filter(_.role == "companion").map { device =>
            deviceCard(
              name = device.name,
              role = if device.revokedAt.isDefined then "已移除" else "只读设备",
              deviceId = device.devicePub,
              revoked = device.revokedAt.isDefined,
              sync =
                if device.revokedAt.isDefined then None
                else
                  Some(() =>
                    api.syncDevice(device.devicePub).onComplete {
                      case Success(_)    => ui.notify("已发送同步快照")
                      case Failure(fail) => ui.failure(api.describeError(fail))
                    }
                  )
              ,
              revoke =
                if device.revokedAt.isDefined then None
                else
                  Some(() =>
                    api.revokeDevice(device.devicePub).onComplete {
                      case Success(_)    => ui.notify("已移除只读设备")
                      case Failure(fail) => ui.failure(api.describeError(fail))
                    }
                  )
            )
          }
          div(primary +: companions*)
        }
      ),
      pairingSection(api, ui),
      div(
        cls := "contacts-foot",
        span("配对码只用于一次配对；移除设备不会远程擦除旧副本"),
        span("同步仍需主设备在线")
      )
    )

  private def deviceCard(
    name: String,
    role: String,
    deviceId: String,
    revoked: Boolean,
    revoke: Option[() => Unit],
    sync: Option[() => Unit]
  ): HtmlElement =
    articleTag(
      cls := (if revoked then "contact-card device-card revoked" else "contact-card device-card"),
      div(
        cls := "contact-card-head",
        span(cls := "avatar large", if name.isEmpty then "设" else name.take(1)),
        div(
          cls    := "contact-title",
          h2(name),
          p(s"$role · ${deviceId.take(12)}")
        ),
        span(cls := "status-chip", if revoked then "已移除" else role)
      ),
      (sync, revoke) match
        case (None, None) => div()
        case _            =>
          div(
            cls := "contact-actions",
            sync.map(action => button(cls := "secondary-button", onClick --> (_ => action()), "同步")).getOrElse(span()),
            revoke.map(action => button(cls := "secondary-button danger", onClick --> (_ => action()), "移除设备")).getOrElse(span())
          )
    )

  private def pairingSection(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "pairing-section",
      h2("配对只读设备"),
      p("1. 生成邀请码，交给只读设备。"),
      textArea(
        cls         := "pairing-output",
        readOnly    := true,
        rows        := 3,
        placeholder := "点击“添加只读设备”生成邀请码",
        value <-- ui.deviceInvite
      ),
      p("2. 粘贴只读设备生成的请求码，先检查设备指纹与确认码。"),
      textArea(
        cls         := "pairing-input",
        rows        := 3,
        placeholder := "粘贴请求码",
        value <-- ui.deviceRequest,
        onInput.mapToValue --> (value => {
          ui.deviceRequest.set(value)
          ui.deviceConfirmation.set("")
          ui.deviceFingerprint.set("")
          ui.deviceName.set("")
        })
      ),
      button(
        cls         := "secondary-button",
        disabled <-- ui.deviceRequest.signal.map(_.trim.isEmpty),
        onClick --> (_ =>
          api.inspectPairing(ui.deviceRequest.now()).onComplete {
            case Success(inspection) =>
              ui.deviceName.set(inspection.name)
              ui.deviceFingerprint.set(inspection.fingerprint)
              ui.deviceConfirmation.set(inspection.confirmation)
              ui.notify("请与只读设备屏幕核对确认码")
            case Failure(fail)       => ui.failure(api.describeError(fail))
          }
        ),
        "检查请求"
      ),
      child.maybe <-- ui.deviceConfirmation.signal.map {
        case ""           => None
        case confirmation =>
          Some(
            div(
              cls := "pairing-inspect",
              div(span(cls := "code-label", "确认码"), span(cls := "code-value", confirmation)),
              div(span(cls := "code-label", "设备指纹"), span(cls := "code-value plain", ui.deviceFingerprint.now())),
              div(span(cls := "code-label", "设备名"), span(cls := "code-value plain", ui.deviceName.now())),
              p("两边屏幕显示同一个确认码后，才能授权。")
            )
          )
      },
      button(
        cls         := "primary-button",
        disabled <-- ui.deviceConfirmation.signal.map(_.isEmpty),
        onClick --> (_ =>
          api.confirmPairing(ui.deviceRequest.now(), ui.deviceConfirmation.now()).onComplete {
            case Success(code) =>
              ui.deviceAuth.set(code)
              ui.deviceConfirmation.set("")
              ui.notify("授权码已生成")
            case Failure(fail) => ui.failure(api.describeError(fail))
          }
        ),
        "已核对，生成授权码"
      ),
      p("3. 把授权码交回只读设备完成配对。"),
      textArea(
        cls         := "pairing-output",
        readOnly    := true,
        rows        := 4,
        placeholder := "核对确认码后显示授权码",
        value <-- ui.deviceAuth
      )
    )
