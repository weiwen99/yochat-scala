package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object UnlockView:

  def apply(api: ApiClient): HtmlElement =
    val passphrase  = Var("")
    val confirmText = Var("")
    val localError  = Var(Option.empty[String])
    val busy        = Var(false)
    val remember    = Var(true)
    val keychain    = Var(false)
    val creating    = api.state.signal.map(_.instanceExists == false)
    val electron    = js.typeOf(js.Dynamic.global.yochat) != "undefined"

    def loadStoredPassphrase(): Unit =
      val loaded =
        js.Dynamic.global.yochat.applyDynamic("loadPassphrase")().asInstanceOf[js.Promise[String]] // scalafix:ok DisableSyntax.asInstanceOf
      loaded.toFuture.foreach { value =>
        Option(value).filter(_.nonEmpty).foreach { stored =>
          passphrase.set(stored)
          confirmText.set(stored)
        }
      }

    def submit(): Unit =
      val value = passphrase.now()
      if value.trim.length < 8 then localError.set(Some("口令至少 8 个字符"))
      else if api.state.now().instanceExists == false && value != confirmText.now() then localError.set(Some("两次输入的口令不一致"))
      else
        busy.set(true)
        localError.set(None)
        api.unlock(value).onComplete {
          case Success(_)    =>
            busy.set(false)
            if electron && keychain.now() && remember.now() then js.Dynamic.global.yochat.applyDynamic("savePassphrase")(value)
          case Failure(fail) =>
            busy.set(false)
            localError.set(Some(failureMessage(fail)))
        }

    div(
      cls := "unlock",
      onMountCallback { _ =>
        if electron then
          val available = js.Dynamic.global.yochat
            .applyDynamic("keychainAvailable")()
            .asInstanceOf[js.Promise[Boolean]] // scalafix:ok DisableSyntax.asInstanceOf
          available.toFuture.foreach { enabled =>
            keychain.set(enabled)
            if enabled then loadStoredPassphrase()
          }
      },
      div(
        cls := "unlock-story",
        div(
          cls   := "story-top",
          div(
            cls := "brand-lockup",
            span(cls := "brand-mark", "有"),
            div(span(cls := "brand-name", "有聊"), span(cls := "brand-sub", "YOCHAT / S1"))
          )
        ),
        div(
          cls   := "story-copy",
          p(cls := "story-kicker", "PRIVATE CORRESPONDENCE"),
          h1("只属于你们两个人的通信。"),
          p(cls := "story-lede", "没有账号服务器。你与本机保存关系、密钥和消息；一台中继只转发密文。"),
          div(
            cls := "story-route",
            routeStop("本机", "这台设备", "source"),
            routeLine(),
            routeStop("中继", "只转发密文", "relay"),
            routeLine(),
            routeStop("对方", "另一台设备", "target")
          ),
          p(cls := "story-note", "中继可以看到连接时间与帧数量；它看不到消息正文。")
        ),
        div(cls := "story-foot", span("消息默认永久保存在本机"), span("没有账号服务器，但有一个中继"))
      ),
      div(
        cls := "unlock-panel",
        div(
          cls := "panel-card",
          div(
            cls   := "panel-head",
            span(cls := "panel-label", "本机身份"),
            h2(child.text <-- creating.map(value => if value then "创建一台新设备" else "解锁这台设备")),
            p(
              child.text <-- creating.map(value =>
                if value then "口令用于保护本机数据库与身份密钥。它不会离开这台设备。"
                else "输入本机口令。解锁之前，界面不会读取联系人或消息。"
              )
            )
          ),
          form(
            cls   := "unlock-form",
            onSubmit.preventDefault --> (_ => submit()),
            label(
              cls := "field",
              span(cls       := "field-label", "本机口令"),
              input(
                tpe          := "password",
                cls          := "text-input",
                placeholder  := "至少 8 个字符",
                autoComplete := "current-password",
                autoFocus    := true,
                value <-- passphrase,
                onInput.mapToValue --> passphrase
              )
            ),
            child.maybe <-- creating.map { isCreating =>
              if isCreating then
                Some(
                  label(
                    cls := "field",
                    span(cls      := "field-label", "再次输入"),
                    input(
                      tpe         := "password",
                      cls         := "text-input",
                      placeholder := "重复口令",
                      value <-- confirmText,
                      onInput.mapToValue --> confirmText
                    )
                  )
                )
              else None
            },
            child.maybe <-- keychain.signal.map { available =>
              if available then
                Some(
                  label(
                    cls := "remember-row",
                    input(tpe := "checkbox", checked <-- remember, onChange.mapToChecked --> remember),
                    span("在系统钥匙串中记住本机口令")
                  )
                )
              else None
            },
            child.maybe <-- localError.signal.map(_.map(message => p(cls := "form-error", message))),
            button(
              tpe := "submit",
              cls := "primary-button wide",
              disabled <-- busy.signal,
              child.text <-- api.state.signal.map(state => if state.instanceExists then "解锁" else "创建并进入")
            )
          ),
          div(cls := "panel-foot", span(cls := "mini-route", Icons("route"), "端到端加密"), span(cls := "mini-route", Icons("shield"), "本机持有密钥"))
        ),
        p(cls := "panel-legal", "这是 S1：一个联系人的单聊。消息经中继转发，但只有两端设备能读取。")
      )
    )

  private def routeStop(title: String, detail: String, kind: String): HtmlElement =
    div(
      cls := s"story-stop $kind",
      span(cls := "story-node"),
      div(span(cls := "story-stop-title", title), span(cls := "story-stop-detail", detail))
    )

  private def routeLine(): HtmlElement = div(cls := "story-line")

  private def failureMessage(failure: Throwable): String =
    failure match
      case api: ApiException =>
        api.code match
          case "unlock-failed"   => "口令不正确，或这台设备的密钥无法打开。"
          case "weak-passphrase" => "新设备的本机口令至少需要 8 个字符。"
          case other             => s"无法打开本机：$other"
      case _                 => "本机服务暂时不可用。请确认 YoChat 服务已经启动。"
