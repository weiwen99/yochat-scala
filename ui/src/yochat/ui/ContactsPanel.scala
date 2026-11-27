package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object ContactsPanel:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "contacts-panel",
      div(
        cls         := "contacts-head",
        div(
          h1("联系人"),
          p("只保留你主动建立的关系。没有目录，也没有公开资料。")
        ),
        child.maybe <-- api.state.signal.map(state =>
          if state.readOnly then None
          else Some(button(cls := "primary-button", onClick --> (_ => ui.addFriend.set(true)), Icons("plus"), "添加联系人"))
        )
      ),
      input(
        cls         := "text-input small contact-filter",
        aria.label  := "筛选联系人",
        placeholder := "按名称、账号、备注筛选",
        display <-- api.state.signal.map(state => if state.readOnly || state.contacts.isEmpty then "none" else "block"),
        value <-- ui.contactFilter,
        onInput.mapToValue --> ui.contactFilter
      ),
      child <-- api.state.signal.combineWith(ui.contactFilter.signal).map { case (state, filter) =>
        val visible = state.contacts.filter(contact => matches(contact, filter))
        if state.contacts.isEmpty then empty(ui, state.readOnly)
        else if visible.isEmpty then div(cls := "contacts-empty", p("没有匹配的联系人。"))
        else contactList(api, ui, state, visible)
      },
      blockedSection(api, ui),
      child <-- api.state.signal.map(state => invitationSection(ui, state)),
      div(
        cls         := "contacts-foot",
        span("本机设备 ", child.text <-- api.state.signal.map(_.identity.map(_.shortDevice).getOrElse("—"))),
        span("安全码由双方用户公钥导出，不经过中继")
      )
    )

  private def matches(contact: Contact, filter: String): Boolean =
    val needle = filter.trim.toLowerCase
    needle.isEmpty ||
    Vector(
      contact.displayName,
      contact.alias.getOrElse(""),
      contact.note.getOrElse(""),
      contact.channelAccount,
      contact.peerChannelAccount.getOrElse("")
    ).exists(_.toLowerCase.contains(needle))

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

  private def contactList(api: ApiClient, ui: UiState, state: AppState, contacts: Vector[Contact]): HtmlElement =
    div(
      cls := "contact-stack",
      contacts.map { contact =>
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
              cls    :=
                (if contact.blocked then "status-chip blocked"
                 else if contact.safetyVerified then "status-chip verified"
                 else "status-chip"),
              if contact.blocked then "已拉黑"
              else if contact.safetyVerified then "安全码已核对"
              else "待核对安全码"
            )
          ),
          if contact.recreated then
            p(
              cls      := "ledger-note",
              "这是删除后重新建立的关系：安全码需要重新核对，旧记录不会自动继承。"
            )
          else div(cls := "hidden", ""),
          div(
            cls := "contact-codes",
            div(span(cls := "code-label", "安全码"), span(cls := "code-value", if contact.safetyCode.isEmpty then "—" else contact.safetyCode)),
            div(span(cls := "code-label", "对方账号"), span(cls := "code-value plain", contact.peerChannelAccount.getOrElse("未记录"))),
            div(
              span(cls := "code-label", "备注"),
              span(cls := "code-value plain", contact.note.filter(_.nonEmpty).getOrElse("未设置"))
            )
          ),
          if contact.devices.nonEmpty then
            div(
              cls      := "contact-codes",
              div(
                span(cls := "code-label", "对方设备"),
                span(
                  cls    := "code-value plain",
                  contact.devices
                    .map(device =>
                      (if device.name.nonEmpty then device.name else device.devicePub.take(8)) +
                        (if device.revokedAt.isDefined then "（已吊销）" else "")
                    )
                    .mkString(" · ")
                )
              )
            )
          else div(cls := "hidden", ""),
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
            if state.readOnly then span(cls := "hidden", "")
            else if !contact.safetyVerified && !contact.blocked then
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
            if state.readOnly then span(cls := "ledger-note", "关系变更只在主力设备进行")
            else if contact.blocked then
              button(
                cls := "secondary-button",
                onClick --> (_ =>
                  api.unblockContact(contact.userPub).onComplete {
                    case Success(_)    => ui.notify("已解除拉黑；拉黑期间对方放弃重试的消息取不回")
                    case Failure(fail) => ui.failure(api.describeError(fail))
                  }
                ),
                "解除拉黑"
              )
            else
              button(
                cls := "text-button",
                onClick --> (_ => ui.confirmBlock.set(Some(contact.userPub))),
                "拉黑"
              )
            ,
            if state.readOnly then span(cls := "hidden", "")
            else
              button(
                cls                         := "text-button danger",
                onClick --> (_ => {
                  ui.deleteHistory.set(false)
                  ui.confirmDelete.set(Some(contact.userPub))
                }),
                "删除联系人"
              )
            ,
            if state.readOnly then span(cls := "hidden", "")
            else
              button(
                cls                         := "text-button",
                onClick --> (_ => {
                  ui.aliasDraft.set(contact.alias.getOrElse(""))
                  ui.aliasEditing.set(true)
                  ui.view.set(View.Chat)
                  ui.mobileList.set(false)
                }),
                "编辑别名"
              )
          ),
          child.maybe <-- ui.confirmBlock.signal.map { confirm =>
            if confirm.contains(contact.userPub) then Some(blockConfirm(api, ui, contact)) else None
          },
          child.maybe <-- ui.confirmDelete.signal.map { confirm =>
            if confirm.contains(contact.userPub) then Some(deleteConfirm(api, ui, contact)) else None
          }
        )
      }
    )

  private def blockConfirm(api: ApiClient, ui: UiState, contact: Contact): HtmlElement =
    div(
      cls := "confirm-box",
      p(cls := "ledger-note", "对方无法从协议上证明自己被拉黑，但会察觉到消息一直发不出去。"),
      p(cls := "ledger-note", "拉黑只绑定这一把用户公钥；对方换一份邀请重加时，那是新建立的关系。"),
      div(
        cls := "alias-actions",
        button(cls := "text-button", onClick --> (_ => ui.confirmBlock.set(None)), "取消"),
        button(
          cls      := "secondary-button",
          onClick --> (_ =>
            api.blockContact(contact.userPub).onComplete {
              case Success(_)    =>
                ui.confirmBlock.set(None)
                ui.notify("已拉黑；不再接收也不再发送")
              case Failure(fail) => ui.failure(api.describeError(fail))
            }
          ),
          "确认拉黑"
        )
      )
    )

  private def deleteConfirm(api: ApiClient, ui: UiState, contact: Contact): HtmlElement =
    div(
      cls := "confirm-box",
      p(
        cls := "ledger-note",
        if contact.blocked then "删除后关系结束，拉黑记录会保留，直到你在已拉黑列表里解除。"
        else "删除后关系结束；对方再发消息不会进来。"
      ),
      label(
        cls := "checkbox-row",
        input(
          typ := "checkbox",
          checked <-- ui.deleteHistory,
          onChange.mapToChecked --> ui.deleteHistory
        ),
        span("同时删除本机这段会话的历史（其他设备各自保留）")
      ),
      div(
        cls := "alias-actions",
        button(cls := "text-button", onClick --> (_ => ui.confirmDelete.set(None)), "取消"),
        button(
          cls      := "secondary-button danger",
          onClick --> (_ => {
            val history = ui.deleteHistory.now()
            api.deleteContact(contact.userPub, history).onComplete {
              case Success(kept) =>
                ui.confirmDelete.set(None)
                api.selectConversation(None)
                ui.notify(if kept then "联系人已删除；拉黑记录保留" else "联系人已删除")
              case Failure(fail) => ui.failure(api.describeError(fail))
            }
          }),
          "确认删除"
        )
      )
    )

  private def blockedSection(api: ApiClient, ui: UiState): HtmlElement =
    div(child <-- api.state.signal.map { state =>
      if state.blockedPeers.isEmpty then div(cls := "hidden", "")
      else
        div(
          cls                                    := "invitation-section",
          div(
            cls := "section-title",
            h2("已拉黑"),
            p("这里的人不能给你发消息，你也不能给他们发。解除拉黑只补齐对方仍在重试的消息；更早的取不回。")
          ),
          div(
            cls := "invitation-list",
            state.blockedPeers.map { peer =>
              div(
                cls := "invitation-row",
                div(
                  cls := "invitation-main",
                  span(cls := "direction-mark received"),
                  div(
                    span(cls := "invitation-title", peer.displayName),
                    span(
                      cls    := "invitation-sub",
                      if state.contacts.exists(_.userPub == peer.userPub) then "关系仍在，仅停止通信" else "关系已删除，拉黑记录保留"
                    )
                  )
                ),
                div(
                  cls := "invitation-actions",
                  if state.readOnly then span(cls := "ledger-note", "只在主力设备解除")
                  else
                    button(
                      cls                         := "text-button",
                      onClick --> (_ =>
                        api.unblockContact(peer.userPub).onComplete {
                          case Success(_)    => ui.notify("已解除拉黑")
                          case Failure(fail) => ui.failure(api.describeError(fail))
                        }
                      ),
                      "解除拉黑"
                    )
                )
              )
            }
          )
        )
    })

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
        else
          div(
            cls                        := "invitation-empty",
            "没有待处理的邀请。",
            button(cls := "text-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人")
          )
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
