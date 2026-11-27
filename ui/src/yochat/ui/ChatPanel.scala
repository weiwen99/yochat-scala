package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object ChatPanel:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "chat-panel",
      child <-- api.activeConversation.signal.map {
        case None     => emptyChat(api, ui)
        case Some(id) => conversation(api, ui, id)
      }
    )

  private def conversation(api: ApiClient, ui: UiState, conversationId: String): HtmlElement =
    val draft         = Var("")
    val draftRevision = Var(0L)
    val contact       = api.selectedContact.signal
    val state         = api.state.signal

    def send(): Unit =
      val text     = draft.now().trim
      val revision = draftRevision.now()
      if text.nonEmpty && api.canSend.now() then
        api.sendMessage(conversationId, text).onComplete {
          case Success(_)    => if draftRevision.now() == revision then draft.set("")
          case Failure(fail) => ui.failure(api.describeError(fail))
        }

    div(
      cls := "conversation-view",
      div(
        cls := "chat-column",
        div(
          cls := "chat-head",
          button(
            cls        := "back-button",
            aria.label := "返回通信列表",
            title      := "返回通信列表",
            onClick --> (_ => ui.mobileList.set(true)),
            Icons("back")
          ),
          div(
            cls        := "chat-identity",
            span(cls := "avatar large", child.text <-- contact.map(_.map(_.displayName.take(1)).getOrElse("?"))),
            div(
              h1(child.text <-- contact.map(_.map(_.displayName).getOrElse("通信"))),
              p(
                child.text <-- contact.map(
                  _.map { value =>
                    if value.blocked then "已拉黑 · 不能发送也不能接收"
                    else if value.safetyVerified then "安全码已核对"
                    else "安全码尚未核对"
                  }.getOrElse("关系已结束 · 仅保留本机历史")
                )
              )
            )
          ),
          div(
            cls        := "head-route",
            onMountCallback(context => context.thisNode.ref.setAttribute("data-route", "relay")),
            span(cls <-- state.map(value => if value.relay.online then "route-dot online" else "route-dot offline")),
            span(child.text <-- state.map(value => if value.relay.online then "经中继 · 在线" else "经中继 · 离线")),
            span(cls := "route-arrow", "本机 → 中继 → 对方")
          )
        ),
        child <-- state.map(value => routeBar(value.relay.online)),
        child.maybe <-- state.map(value => if value.readOnly then Some(readOnlyBar()) else None),
        child <-- api.messages.signal.map(messages => messageArea(messages, api.selectedContact.now())),
        child.maybe <-- state.map { value =>
          value.conversations.find(_.id == conversationId).filter(_.waiting).map(_ => waitingLine())
        },
        composer(api, draft, draftRevision, state, send)
      ),
      ChatLedger(api, ui, conversationId)
    )

  private def routeBar(online: Boolean): HtmlElement =
    div(
      cls := "route-bar",
      div(cls  := "route-stop", span(cls := "route-node source"), span(cls := "route-label", "本机")),
      div(cls  := "route-line"),
      div(cls  := "route-stop", span(cls := s"route-node relay ${if online then "on" else "off"}"), span(cls := "route-label", "中继")),
      div(cls  := "route-line"),
      div(cls  := "route-stop", span(cls := "route-node target"), span(cls := "route-label", "对方")),
      span(cls := "route-note", if online then "密文转发正常" else "消息留在本机，恢复后继续")
    )

  private def readOnlyBar(): HtmlElement =
    div(
      cls := "read-only-bar",
      span(cls := "read-only-mark", "只读"),
      span("这台设备只接收主设备同步的消息，不能发送或加好友。")
    )

  private def messageArea(messages: Vector[ChatMessage], contact: Option[Contact]): HtmlElement =
    val name = contact.map(_.displayName).getOrElse("对方")
    if messages.isEmpty then
      div(
        cls := "messages empty",
        div(
          cls := "conversation-empty",
          span(cls := "empty-line"),
          h2("你们已经连上了。"),
          p("先说点什么。消息会先保存在本机，再交给中继。"),
          span(cls := "empty-route", Icons("route"), "本机 → 中继 → 对方")
        )
      )
    else
      div(
        cls := "messages",
        onMountCallback { context =>
          context.thisNode.ref.scrollTop = context.thisNode.ref.scrollHeight
        },
        div(cls := "date-rule", span(TimeFormat.day(messages.head.createdAt))),
        messages.map(message => messageNode(message, name))
      )

  private def messageNode(message: ChatMessage, peerName: String): HtmlElement =
    val own        = message.author == "me"
    val stateClass = if own then message.state else "received"
    div(
      cls := s"message ${if own then "own" else "peer"} $stateClass",
      div(cls := "score", span(cls := "score-mark")),
      div(
        cls   := "message-content",
        div(
          cls   := "message-meta",
          span(if own then "你" else peerName),
          span(TimeFormat.clock(message.createdAt))
        ),
        div(cls := "bubble", message.body),
        div(cls := "message-state", if own then stateLabel(message.state) else "")
      )
    )

  private def stateLabel(state: String): String =
    state match
      case "delivered" => "已送达"
      case "sent"      => "已交给中继 · 等待对方确认"
      case "pending"   => "已写入本机"
      case "failed"    => "发送失败 · 已保留"
      case _           => ""

  private def waitingLine(): HtmlElement =
    div(
      cls := "waiting-line",
      span(cls := "waiting-dash"),
      "正在等待较早的一条消息 · 自动获取中",
      span(cls := "waiting-dash")
    )

  private def composer(
    api: ApiClient,
    draft: Var[String],
    draftRevision: Var[Long],
    state: Signal[AppState],
    send: () => Unit
  ): HtmlElement =
    div(
      cls := "composer",
      div(
        cls := "composer-box",
        textArea(
          cls         := "composer-input",
          aria.label  := "消息正文",
          placeholder := "只读设备不能发送消息",
          value <-- draft,
          disabled <-- state.map(_.readOnly),
          onInput.mapToValue --> { value =>
            draft.set(value)
            draftRevision.update(_ + 1L)
          },
          onKeyDown --> { event =>
            if event.key == "Enter" && !event.shiftKey then
              event.preventDefault()
              send()
          }
        ),
        button(
          cls         := "send-button",
          aria.label  := "发送",
          title       := "发送",
          disabled <-- api.canSend.signal.map(value => !value),
          onClick --> (_ => send()),
          Icons("send")
        )
      ),
      div(
        cls := "composer-foot",
        span(
          child.text <-- state.map { value =>
            val contact = api.selectedContact.now()
            contact match
              case None                                     => "关系已结束；这段历史只在本机"
              case Some(current) if current.blocked         => "已拉黑该联系人；解除后才能发送"
              case Some(current) if !current.safetyVerified => "先在右侧核对安全码，正文才会发送"
              case Some(_) if !value.relay.online           => "中继离线；消息会留在本机，恢复后自动继续"
              case Some(_)                                  => "Enter 发送 · Shift+Enter 换行"
          }
        ),
        span("正文只保存在本机")
      )
    )

  private def emptyChat(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "empty-chat",
      div(
        cls := "empty-illustration",
        span(cls := "empty-route-line"),
        span(cls := "empty-node"),
        span(cls := "empty-node relay"),
        span(cls := "empty-node")
      ),
      child <-- api.state.signal.map { state =>
        if state.readOnly then
          div(
            h2("只读设备。"),
            p("这里只显示主设备同步来的加入后内容；不能加好友、发消息或改安全码。")
          )
        else
          div(
            h2("还没有联系人。"),
            p("一段关系从一份邀请开始。生成邀请，或粘贴朋友发给你的邀请。"),
            button(cls := "primary-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人")
          )
      }
    )

  private object ChatLedger:

    def apply(api: ApiClient, ui: UiState, conversationId: String): HtmlElement =
      val contact = api.selectedContact.signal
      div(
        cls := "ledger",
        child <-- contact.map {
          case None        => div(cls := "ledger-empty", "正在加载联系人…")
          case Some(value) => content(api, ui, conversationId, value)
        }
      )

    private def content(api: ApiClient, ui: UiState, conversationId: String, contact: Contact): HtmlElement =
      div(
        cls := "ledger-inner",
        sectionTag(
          cls := "ledger-section",
          h3("这次通信"),
          div(
            cls := "route-card",
            routeStop("本机", "这台设备", "source"),
            routeConnector(),
            child <-- api.state.signal.map(state => routeStop("中继", if state.relay.online then "在线" else "离线", "relay")),
            routeConnector(),
            routeStop(contact.displayName, "设备", "target")
          ),
          p(cls := "ledger-note", "中继只转发密文与路由信息。没有中继时，两台设备无法跨网通信。")
        ),
        sectionTag(
          cls := "ledger-section",
          h3("安全码"),
          div(
            cls := "safety-code",
            span(cls := "safety-digits", if contact.safetyCode.isEmpty then "—" else contact.safetyCode),
            span(
              cls    := (if contact.safetyVerified then "safety-state verified" else "safety-state"),
              if contact.safetyVerified then "已当面核对" else "尚未核对"
            )
          ),
          if !contact.safetyVerified then
            button(
              cls       := "secondary-button small",
              onClick --> (_ =>
                api.verifySafety(contact.userPub).onComplete {
                  case Success(_)    => ui.notify("安全码已标记为已核对")
                  case Failure(fail) => ui.failure(api.describeError(fail))
                }
              ),
              "我已当面核对"
            )
          else span(cls := "hidden", "")
        ),
        sectionTag(
          cls := "ledger-section",
          h3("联系人别名"),
          child <-- ui.aliasEditing.signal.map { editing =>
            if editing then aliasEditor(api, ui, contact)
            else
              div(
                cls := "alias-row",
                span(cls     := "alias-value", contact.alias.getOrElse("未设置别名")),
                button(
                  cls        := "icon-button",
                  aria.label := "编辑别名",
                  title      := "编辑别名",
                  onClick --> (_ => {
                    ui.aliasDraft.set(contact.alias.getOrElse(""))
                    ui.aliasEditing.set(true)
                  }),
                  Icons("edit")
                )
              )
          }
        ),
        sectionTag(
          cls := "ledger-section",
          h3("联系人备注"),
          child <-- ui.noteEditing.signal.map { editing =>
            if editing then noteEditor(api, ui, contact)
            else
              div(
                cls := "alias-row",
                span(cls     := "alias-value", contact.note.filter(_.nonEmpty).getOrElse("未设置备注")),
                button(
                  cls        := "icon-button",
                  aria.label := "编辑备注",
                  title      := "编辑备注",
                  onClick --> (_ => {
                    ui.noteDraft.set(contact.note.getOrElse(""))
                    ui.noteEditing.set(true)
                  }),
                  Icons("edit")
                )
              )
          }
        ),
        sectionTag(
          cls := "ledger-section",
          h3("通知"),
          label(
            cls := "checkbox-row",
            input(
              cls := "notify-body-toggle",
              typ := "checkbox",
              checked <-- api.notifyBody.signal,
              onChange.mapToChecked --> (value => api.setNotifyBody(value))
            ),
            span("通知里显示消息正文（默认关闭；关闭时只显示联系人名）")
          ),
          label(
            cls := "checkbox-row",
            input(
              cls := "notify-mute-toggle",
              typ := "checkbox",
              checked <-- api.mutedSignal(conversationId),
              onChange.mapToChecked --> (value => api.setMuted(conversationId, value))
            ),
            span("这个会话不提醒")
          ),
          p(cls := "ledger-note", "通知由本机系统弹出，不经过中继；中继看不到通知内容。")
        ),
        sectionTag(
          cls := "ledger-section",
          h3("投递状态"),
          div(
            cls := "legend",
            div(span(cls := "legend-mark delivered"), "已送达：对方设备已确认"),
            div(span(cls := "legend-mark sent"), "已交给中继：等待确认"),
            div(span(cls := "legend-mark pending"), "已写入本机：等待发送"),
            div(span(cls := "legend-mark failed"), "失败：已保留，不静默丢弃")
          )
        ),
        sessionAction(api, ui, conversationId),
        div(
          cls := "ledger-foot",
          span(cls := "device-line", "本机设备 ", api.state.now().identity.map(_.shortDevice).getOrElse("—")),
          span("消息按序保存在本机")
        )
      )

    private def aliasEditor(api: ApiClient, ui: UiState, contact: Contact): HtmlElement =
      div(
        cls := "alias-editor",
        input(
          cls         := "text-input small",
          value <-- ui.aliasDraft,
          onInput.mapToValue --> ui.aliasDraft,
          placeholder := "例如：陈默",
          autoFocus   := true
        ),
        div(
          cls         := "alias-actions",
          button(cls := "text-button", onClick --> (_ => ui.aliasEditing.set(false)), "取消"),
          button(
            cls      := "secondary-button small",
            onClick --> (_ => {
              val value = ui.aliasDraft.now().trim
              api.setAlias(contact.userPub, if value.isEmpty then None else Some(value)).onComplete {
                case Success(_)    =>
                  ui.notify("备注已保存")
                  ui.aliasEditing.set(false)
                case Failure(fail) => ui.failure(api.describeError(fail))
              }
            }),
            "保存"
          )
        )
      )

    private def noteEditor(api: ApiClient, ui: UiState, contact: Contact): HtmlElement =
      div(
        cls := "alias-editor",
        textArea(
          cls         := "text-input small",
          value <-- ui.noteDraft,
          onInput.mapToValue --> ui.noteDraft,
          placeholder := "只在本机与本人设备上可见",
          rows        := 3,
          autoFocus   := true
        ),
        div(
          cls         := "alias-actions",
          button(cls := "text-button", onClick --> (_ => ui.noteEditing.set(false)), "取消"),
          button(
            cls      := "secondary-button small",
            onClick --> (_ => {
              val value = ui.noteDraft.now().trim
              api.setNote(contact.userPub, if value.isEmpty then None else Some(value)).onComplete {
                case Success(_)    =>
                  ui.notify("备注已保存")
                  ui.noteEditing.set(false)
                case Failure(fail) => ui.failure(api.describeError(fail))
              }
            }),
            "保存"
          )
        )
      )

    private def sessionAction(api: ApiClient, ui: UiState, conversationId: String): HtmlElement =
      div(
        cls := "session-action",
        child.maybe <-- api.state.signal.map { state =>
          state.sessions.find(_.conversationId == conversationId).map { value =>
            button(
              cls := "text-button rekey",
              onClick --> (_ =>
                api.startRekey(value.id).onComplete {
                  case Success(_)    => ui.notify("已开始通道重建")
                  case Failure(fail) => ui.failure(api.describeError(fail))
                }
              ),
              "重建加密通道"
            )
          }
        }
      )

    private def routeStop(label: String, detail: String, kind: String): HtmlElement =
      div(
        cls := s"ledger-stop $kind",
        span(cls := "route-node"),
        span(cls := "ledger-stop-copy", span(cls := "ledger-stop-label", label), span(cls := "ledger-stop-detail", detail))
      )

    private def routeConnector(): HtmlElement = div(cls := "ledger-connector")
