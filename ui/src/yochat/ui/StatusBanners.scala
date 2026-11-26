package yochat.ui

import com.raquo.laminar.api.L.*

object StatusBanners:

  def apply(api: ApiClient): HtmlElement =
    div(
      cls := "status-stack",
      child.maybe <-- api.error.signal.map(_.map(message => banner("error", "本机服务", message, None))),
      child.maybe <-- api.notice.signal
        .map(_.filterNot(_ == "waiting").map(code => banner("notice", "状态", noticeText(code), Some(() => api.dismissNotice())))),
      child.maybe <-- api.state.signal.map { state =>
        if state.conversations.exists(_.waiting) then Some(banner("notice", "状态", "有消息正在等待较早的消息，服务会自动继续。", None))
        else None
      },
      child.maybe <-- api.state.signal.map(_.fatal.map(code => banner("fatal", "本机服务错误", fatalText(code), None)))
    )

  private def banner(kind: String, title: String, body: String, dismiss: Option[() => Unit]): HtmlElement =
    div(
      cls  := s"status-banner $kind",
      role := (if kind == "notice" then "status" else "alert"),
      span(cls := "status-mark", if kind == "notice" then "i" else "!"),
      div(cls  := "status-copy", span(cls := "status-title", title), span(cls := "status-text", body)),
      dismiss
        .map(action =>
          button(
            cls        := "status-close",
            aria.label := "关闭提示",
            onClick --> (_ => action()),
            Icons("close")
          )
        )
        .getOrElse(span(cls := "hidden", ""))
    )

  private def noticeText(code: String): String =
    code match
      case "queue-full"       => "待发消息已满。中继恢复前，新消息不会继续发送。"
      case "inbound-overflow" => "收到的消息暂时超出本机缓冲，正在请求补发。"
      case "message-failed"   => "有消息重试失败，正文仍保留在本机。"
      case "rekey-failed"     => "加密通道重建失败，可在聊天中重试。"
      case "rekey-required"   => "对方要求重建加密通道。"
      case other              => s"本机状态需要处理（$other）。"

  private def fatalText(code: String): String =
    if code.isEmpty then "本机服务遇到无法恢复的错误。请重启 YoChat。"
    else s"本机服务遇到无法恢复的错误（$code）。请重启 YoChat。"
