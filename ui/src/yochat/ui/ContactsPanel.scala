package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object ContactsPanel:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "contacts-panel",
      div(
        cls := "contacts-head",
        div(
          h1("联系人"),
          p("只保留你主动建立的关系。没有目录，也没有公开资料。")
        ),
        child.maybe <-- api.state.signal.map(state =>
          if state.readOnly then None
          else Some(button(cls := "primary-button", onClick --> (_ => ui.addFriend.set(true)), Icons("plus"), "添加联系人"))
        )
      ),
      child <-- api.state.signal.map { state =>
        if state.contacts.isEmpty then empty(ui, state.readOnly) else contactList(api, ui, state)
      },
      child <-- api.state.signal.map(state => invitationSection(ui, state)),
      div(
        cls := "contacts-foot",
        span("本机设备 ", child.text <-- api.state.signal.map(_.identity.map(_.shortDevice).getOrElse("—"))),
        span("安全码由双方用户公钥导出，不经过中继")
      )
    )

  private def empty(ui: UiState, readOnly: Boolean): HtmlElement =
    div(
      cls := "contacts-empty",
      div(
        cls := "empty-illustration",
        span(cls := "empty-route-line"),
        span(cls := "empty-node"),
        span(cls := "empty-node relay"),
        span(cls := "empty-node")
      ),
      h2(if readOnly then "只读设备已配对。" else "通讯录是空的。"),
      p(if readOnly then "这里只显示主设备同步来的加入后内容。" else "生成一份邀请，或粘贴朋友发给你的邀请。你们各自确认之后，联系人才会出现。"),
      if readOnly then div() else button(cls := "primary-button", onClick --> (_ => ui.addFriend.set(true)), "开始加好友")
    )

  private def contactList(api: ApiClient, ui: UiState, state: AppState): HtmlElement =
    div(
      cls := "contact-stack",
      state.contacts.map { contact =>
        val conversation = contact.conversationId.flatMap(id => state.conversations.find(_.id == id))
        articleTag(
          cls := "contact-card",
          div(
            cls := "contact-card-head",
            span(cls := "avatar large", contact.displayName.take(1)),
            div(
              cls    := "contact-title",
              h2(contact.displayName),
              p(s"${contact.channelLabel} · ${contact.channelAccount}")
            ),
            span(
              cls    := (if contact.safetyVerified then "status-chip verified" else "status-chip"),
              if contact.safetyVerified then "安全码已核对" else "待核对安全码"
            )
          ),
          div(
            cls := "contact-codes",
            div(span(cls := "code-label", "安全码"), span(cls := "code-value", if contact.safetyCode.isEmpty then "—" else contact.safetyCode)),
            div(span(cls := "code-label", "对方账号"), span(cls := "code-value plain", contact.peerChannelAccount.getOrElse("未记录")))
          ),
          div(
            cls := "contact-actions",
            conversation
              .map { value =>
                button(
                  cls := "secondary-button",
                  onClick --> (_ => {
                    api.selectConversation(Some(value.id))
                    ui.view.set(View.Chat)
                    ui.mobileList.set(false)
                  }),
                  "打开聊天"
                )
              }
              .getOrElse(span(cls := "hidden", "")),
            if !contact.safetyVerified then
              button(
                cls       := "secondary-button",
                onClick --> (_ =>
                  api.verifySafety(contact.userPub).onComplete {
                    case Success(_)    => ui.notify("安全码已标记为已核对")
                    case Failure(fail) => ui.failure(api.describeError(fail))
                  }
                ),
                "我已当面核对"
              )
            else span(cls := "hidden", ""),
            button(
              cls := "text-button",
              onClick --> (_ => {
                ui.aliasDraft.set(contact.alias.getOrElse(""))
                ui.aliasEditing.set(true)
                ui.view.set(View.Chat)
                ui.mobileList.set(false)
              }),
              "编辑备注"
            )
          )
        )
      }
    )

  private def lifecycleLabel(value: String): String =
    value match
      case "issued"         => "待应答"
      case "awaiting-reply" => "待接受"
      case "accepted"       => "已接受"
      case "expired"        => "已过期"
      case other            => other

  private def invitationSection(ui: UiState, state: AppState): HtmlElement =
    div(
      cls := "invitation-section",
      div(
        cls := "section-title",
        h2("邀请"),
        p("邀请是可复制的文本；应答会通过中继回到邀请方。")
      ),
      if state.invitations.isEmpty then
        if state.readOnly then div(cls := "invitation-empty", "只读设备不处理邀请。")
        else div(cls                   := "invitation-empty", "没有待处理的邀请。", button(cls := "text-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人"))
      else
        div(
          cls := "invitation-list",
          state.invitations.map { invitation =>
            div(
              cls := "invitation-row",
              div(
                cls := "invitation-main",
                span(cls := s"direction-mark ${invitation.direction}"),
                div(
                  span(cls := "invitation-title", if invitation.direction == "issued" then "我发出的邀请" else "收到的邀请"),
                  span(
                    cls    := "invitation-sub",
                    s"${invitation.channelLabel} · ${invitation.channelAccount} · 有效至 ${TimeFormat.timestamp(invitation.expiresAt)}"
                  )
                )
              ),
              div(
                cls := "invitation-actions",
                span(cls := "lifecycle", lifecycleLabel(invitation.lifecycle)),
                if invitation.direction == "issued" then button(cls := "text-button", onClick --> (_ => UiUtil.copy(invitation.text, ui)), "复制")
                else
                  button(
                    cls                                             := "text-button",
                    onClick --> (_ => {
                      ui.inviteTab.set(InviteTab.Received)
                      ui.inviteText.set(invitation.text)
                      ui.addFriend.set(true)
                    }),
                    "打开"
                  )
              )
            )
          }
        )
    )
