package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object ChatPanel:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "chat-panel",
      child <-- api.activeConversation.signal.map {
        case None     => emptyChat(ui)
        case Some(id) => conversation(api, ui, id)
      }
    )

  private def conversation(api: ApiClient, ui: UiState, conversationId: String): HtmlElement =
    val draft   = Var("")
    val contact = api.selectedContact.signal
    val state   = api.state.signal

    def send(): Unit =
      val text = draft.now().trim
      if text.nonEmpty && api.canSend.now() then
        api.sendMessage(conversationId, text).onComplete {
          case Success(_)    => if draft.now().trim == text then draft.set("")
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
              p(child.text <-- contact.map(_.map(value => if value.safetyVerified then "安全码已核对" else "安全码尚未核对").getOrElse("正在恢复本机状态")))
            )
          ),
          div(
            cls        := "head-route",
            span(cls <-- state.map(value => if value.relay.online then "route-dot online" else "route-dot offline")),
            span(child.text <-- state.map(value => if value.relay.online then "中继在线" else "中继离线")),
            span(cls := "route-arrow", "本机 → 对方")
          )
        ),
        child <-- state.map(value => routeBar(value.relay.online)),
        child <-- api.messages.signal.map(messages => messageArea(messages, api.selectedContact.now())),
        child.maybe <-- state.map { value =>
          value.conversations.find(_.id == conversationId).filter(_.waiting).map(_ => waitingLine())
        },
        composer(api, draft, state, send)
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
          placeholder := "写点什么…",
          value <-- draft,
          onInput.mapToValue --> draft,
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
            if !contact.exists(_.safetyVerified) then "先在右侧核对安全码，正文才会发送"
            else if !value.relay.online then "中继离线；消息会留在本机，恢复后自动继续"
            else "Enter 发送 · Shift+Enter 换行"
          }
        ),
        span("正文只保存在本机")
      )
    )

  private def emptyChat(ui: UiState): HtmlElement =
    div(
      cls := "empty-chat",
      div(
        cls      := "empty-illustration",
        span(cls := "empty-route-line"),
        span(cls := "empty-node"),
        span(cls := "empty-node relay"),
        span(cls := "empty-node")
      ),
      h2("还没有联系人。"),
      p("一段关系从一份邀请开始。生成邀请，或粘贴朋友发给你的邀请。"),
      button(cls := "primary-button", onClick --> (_ => ui.addFriend.set(true)), "添加联系人")
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
          h3("联系人备注"),
          child <-- ui.aliasEditing.signal.map { editing =>
            if editing then aliasEditor(api, ui, contact)
            else
              div(
                cls := "alias-row",
                span(cls     := "alias-value", contact.alias.getOrElse("未设置备注")),
                button(
                  cls        := "icon-button",
                  aria.label := "编辑备注",
                  title      := "编辑备注",
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
