package yochat.ui

import com.raquo.laminar.api.L.*

object Shell:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls <-- ui.mobileList.signal.map(value => if value then "shell list-open" else "shell chat-open"),
      div(cls := "drag-strip"),
      navTag(
        cls   := "rail",
        div(cls := "rail-brand", span(cls := "rail-mark", "有")),
        div(
          cls   := "rail-nav",
          railButton(
            "chat",
            "通信",
            ui.view.signal.map(_ == View.Chat),
            () => {
              ui.view.set(View.Chat)
              ui.mobileList.set(true)
            }
          ),
          railButton(
            "people",
            "联系人",
            ui.view.signal.map(_ == View.Contacts),
            () => {
              ui.view.set(View.Contacts)
              ui.mobileList.set(true)
            }
          ),
          child.maybe <-- api.state.signal.map { state =>
            if state.readOnly then None
            else
              Some(
                railButton(
                  "devices",
                  "设备",
                  ui.view.signal.map(_ == View.Devices),
                  () => {
                    ui.view.set(View.Devices)
                    ui.mobileList.set(true)
                  }
                )
              )
          }
        ),
        child.maybe <-- api.state.signal.map { state =>
          if state.readOnly then None
          else
            Some(
              div(
                cls := "rail-bottom",
                button(
                  cls        := "rail-button",
                  title      := "锁定本机",
                  aria.label := "锁定本机",
                  onClick --> (_ => api.lock()),
                  Icons("lock")
                )
              )
            )
        }
      ),
      conversationList(api, ui),
      mainTag(
        cls   := "workspace",
        child <-- ui.view.signal.map {
          case View.Chat     => ChatPanel(api, ui)
          case View.Contacts => ContactsPanel(api, ui)
          case View.Devices  => DevicesPanel(api, ui)
        }
      ),
      child.maybe <-- ui.addFriend.signal.map(value => if value then Some(AddFriendDialog(api, ui)) else None),
      child.maybe <-- ui.toast.signal.map(_.map(toast))
    )

  private def railButton(name: String, label: String, active: Signal[Boolean], action: () => Unit): HtmlElement =
    button(
      cls <-- active.map(value => if value then "rail-button on" else "rail-button"),
      title      := label,
      aria.label := label,
      onClick --> (_ => action()),
      Icons(name)
    )

  private def conversationList(api: ApiClient, ui: UiState): HtmlElement =
    asideTag(
      cls := "conversation-list",
      div(
        cls := "list-head",
        div(cls := "wordmark", "有聊", span("YOCHAT / S1")),
        div(
          cls   := "list-title-row",
          h2("通信"),
          child.maybe <-- api.state.signal.map(value =>
            if value.readOnly then None
            else
              Some(
                button(cls := "icon-button", aria.label := "添加联系人", title := "添加联系人", onClick --> (_ => ui.addFriend.set(true)), Icons("plus"))
              )
          )
        )
      ),
      child <-- api.state.signal.map { state =>
        if state.conversations.isEmpty then emptyList(ui, state.readOnly)
        else
          div(
            cls := "conversation-stack",
            state.conversations.map { conversation =>
              val contact = state.contactFor(conversation)
              button(
                cls <-- api.activeConversation.signal.map(id =>
                  if id.contains(conversation.id) then "conversation-row on" else "conversation-row"
                ),
                onClick --> (_ => {
                  api.selectConversation(Some(conversation.id))
                  ui.view.set(View.Chat)
                  ui.mobileList.set(false)
                }),
                span(cls := "avatar", contact.map(_.displayName.take(1)).getOrElse("?")),
                span(
                  cls    := "conversation-copy",
                  span(cls := "conversation-name", conversation.peerName),
                  span(cls := "conversation-preview", if conversation.lastMessage.isEmpty then "还没有消息" else conversation.lastMessage)
                ),
                span(cls := "conversation-time", formatTime(conversation.lastMessageAt))
              )
            }
          )
      },
      div(
        cls := "list-foot",
        span(child.text <-- api.state.signal.map(state => s"${state.contacts.size} 位联系人")),
        child.maybe <-- api.state.signal.map(state =>
          if state.readOnly then None else Some(button(cls := "text-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人"))
        )
      )
    )

  private def emptyList(ui: UiState, readOnly: Boolean): HtmlElement =
    div(
      cls := "list-empty",
      div(cls := "empty-rule"),
      p(if readOnly then "只读设备已配对。" else "还没有联系人。"),
      span(if readOnly then "这里只显示主设备同步来的加入后内容。" else "用一份邀请开始一段只有你们两人的通信。"),
      if readOnly then div() else button(cls := "secondary-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人")
    )

  private def toast(value: Toast): HtmlElement =
    div(
      cls := s"toast ${value.kind}",
      span(cls := "toast-mark", if value.kind == "error" then "!" else "✓"),
      span(value.message)
    )

  private def formatTime(value: Long): String =
    if value <= 0L then ""
    else
      val date = new scalajs.js.Date(value.toDouble)
      val hh   = date.getHours().toInt
      val mm   = date.getMinutes().toInt
      s"${if hh < 10 then "0" else ""}$hh:${if mm < 10 then "0" else ""}$mm"
