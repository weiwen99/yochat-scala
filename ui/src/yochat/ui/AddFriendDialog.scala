package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*
import org.scalajs.dom

object AddFriendDialog:

  private val FocusableSelector = "button:not([disabled]), input:not([disabled]), textarea:not([disabled]), a[href]"

  /** The disclosure text is versioned; bumping this value shows it open again on the next add-contact. */
  val DisclosureVersion: Int = 1

  def readDisclosureVersion(): Int =
    try Option(dom.window.localStorage.getItem("yochat.disclosureVersion")).flatMap(_.toIntOption).getOrElse(0)
    catch case _: Exception => 0

  def writeDisclosureVersion(value: Int): Unit =
    try dom.window.localStorage.setItem("yochat.disclosureVersion", value.toString)
    catch case _: Exception => ()

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    val previousFocus: Var[Option[dom.HTMLElement]]                    = Var(None)
    val keyHandler: Var[Option[js.Function1[dom.KeyboardEvent, Unit]]] = Var(None)

    def close(): Unit = ui.addFriend.set(false)

    def focusFirst(container: dom.html.Element): Unit =
      val target =
        Option(container.querySelector(".dialog-body input"))
          .orElse(Option(container.querySelector(".dialog-body textarea")))
          .orElse(Option(container.querySelector(".dialog-body button")))
          .orElse(Option(container.querySelector("button")))
      target.foreach {
        case element: dom.HTMLElement => element.focus()
        case _                        => ()
      }

    def trapFocus(event: dom.KeyboardEvent): Unit =
      if event.key == "Escape" then
        event.preventDefault()
        close()
      else if event.key == "Tab" then
        dom.document.querySelector(".modal") match
          case modal: dom.html.Element =>
            val nodes      = modal.querySelectorAll(FocusableSelector)
            val focusables = (0 until nodes.length).flatMap { index =>
              nodes.item(index) match
                case element: dom.HTMLElement => Some(element)
                case _                        => None
            }.toVector
            if focusables.nonEmpty then
              val first  = focusables.head
              val last   = focusables.last
              val active = dom.document.activeElement
              if !focusables.exists(_ == active) then
                event.preventDefault()
                first.focus()
              else if event.shiftKey && active == first then
                event.preventDefault()
                last.focus()
              else if !event.shiftKey && active == last then
                event.preventDefault()
                first.focus()
          case _                       => ()

    div(
      cls := "scrim",
      onClick --> (_ => close()),
      div(
        cls        := "modal",
        role       := "dialog",
        aria.label := "添加联系人",
        tabIndex   := -1,
        onClick.stopPropagation --> (_ => ()),
        onMountCallback { context =>
          context.thisNode.ref.setAttribute("aria-modal", "true")
          previousFocus.set(dom.document.activeElement match
            case element: dom.HTMLElement => Some(element)
            case _                        => None)
          val handler: js.Function1[dom.KeyboardEvent, Unit] = event => trapFocus(event)
          keyHandler.set(Some(handler))
          dom.document.addEventListener("keydown", handler)
          focusFirst(context.thisNode.ref)
        },
        onUnmountCallback { _ =>
          keyHandler.now().foreach(handler => dom.document.removeEventListener("keydown", handler))
          val previous = previousFocus.now().filter(_.isConnected)
          val fallback = Option(dom.document.querySelector("button[aria-label=\"添加联系人\"]")) match
            case Some(element: dom.HTMLElement) => Some(element)
            case _                              => None
          previous.orElse(fallback).foreach(_.focus())
        },
        div(
          cls   := "modal-head",
          div(
            h2("添加联系人"),
            p("邀请与应答都只通过你选择的渠道传递；YoChat 不接受公开目录或陌生请求。")
          ),
          button(cls := "icon-button", aria.label := "关闭", title := "关闭", onClick --> (_ => ui.addFriend.set(false)), Icons("close"))
        ),
        disclosure(api),
        div(
          cls   := "tabs",
          tabButton("我发出邀请", InviteTab.Issued, ui),
          tabButton("我收到邀请", InviteTab.Received, ui)
        ),
        child <-- ui.inviteTab.signal.map {
          case InviteTab.Issued   => issuedForm(api, ui)
          case InviteTab.Received => receivedForm(api, ui)
        },
        div(cls := "modal-foot", span("没有账号服务器，但有一个中继。"), span("安全码仍需当面核对"))
      )
    )

  /** Pre-use disclosure. Shown in the add-contact flow (both tabs) so it appears before the first invitation and before the first reply. The
    * read version is a local preference only.
    */
  private def disclosure(api: ApiClient): HtmlElement =
    val version = Var(AddFriendDialog.readDisclosureVersion())
    val open    = Var(version.now() < AddFriendDialog.DisclosureVersion)
    div(
      cls := "disclosure",
      onMountCallback(context => context.thisNode.ref.setAttribute("data-disclosure-version", AddFriendDialog.DisclosureVersion.toString)),
      div(
        cls := "disclosure-head",
        span(cls     := "disclosure-title", "使用前须知"),
        span(
          cls        := "disclosure-hint",
          if version.now() < AddFriendDialog.DisclosureVersion then "首次使用请先阅读" else "隐私与可见性"
        ),
        button(
          cls        := "text-button disclosure-toggle",
          aria.label := "展开或收起使用前须知",
          onClick --> (_ => open.update(!_)),
          child.text <-- open.signal.map(value => if value then "收起" else "展开")
        )
      ),
      child.maybe <-- open.signal.map { expanded =>
        if !expanded then None
        else
          Some(
            div(
              cls := "disclosure-body",
              ul(
                cls := "disclosure-list",
                li("中继能看到：连接端点、时间、帧数与大小，以及密文；看不到正文，也看不到你的用户公钥，也不知道这些设备属于谁。"),
                li("对方能看到你的公网地址（直连时）与全部设备公钥、设备别名。"),
                li(s"本机中转地址：${relayAddress(api)}；换一台中继会改变谁能看到这些元数据。"),
                li("拉黑可以被对方推断：消息会一直发不出去。我们不提供在线状态，也没有隐藏开关；单靠用户公钥不能查询在线状态。"),
                li("没有公开目录或账号服务器；邀请只通过你自己选择的渠道发出，不要贴到公开场合。")
              ),
              div(
                cls := "dialog-actions",
                button(
                  cls := "secondary-button",
                  onClick --> (_ => {
                    AddFriendDialog.writeDisclosureVersion(AddFriendDialog.DisclosureVersion)
                    version.set(AddFriendDialog.DisclosureVersion)
                    open.set(false)
                  }),
                  "我已了解"
                )
              )
            )
          )
      }
    )

  private def relayAddress(api: ApiClient): String =
    val relay = api.state.now().relay
    s"${relay.host}:${relay.port}"

  private def tabButton(label: String, tab: InviteTab, ui: UiState): HtmlElement =
    button(
      cls <-- ui.inviteTab.signal.map(value => if value == tab then "tab on" else "tab"),
      onClick --> (_ => ui.inviteTab.set(tab)),
      label
    )

  private def issuedForm(api: ApiClient, ui: UiState): HtmlElement =
    val label    = Var("微信")
    val account  = Var("")
    val expected = Var("")
    val note     = Var("")
    val ttl      = Var("24")
    val busy     = Var(false)
    val error    = Var(Option.empty[String])
    val created  = Var(Option.empty[Invitation])

    def submit(): Unit =
      if label.now().trim.isEmpty || account.now().trim.isEmpty then error.set(Some("渠道标签与本方账号都不能为空"))
      else
        busy.set(true)
        error.set(None)
        api
          .createInvitation(
            label.now().trim,
            account.now().trim,
            Option(expected.now().trim).filter(_.nonEmpty),
            note.now().trim,
            ttl.now().toIntOption.getOrElse(24)
          )
          .onComplete {
            case Success(invitation) =>
              busy.set(false)
              created.set(Some(invitation))
              ui.notify("邀请已生成")
            case Failure(fail)       =>
              busy.set(false)
              error.set(Some(api.describeError(fail)))
          }

    div(
      cls := "dialog-body",
      p(cls := "dialog-lede", "生成一段邀请文本，发给你已经认识的这个人。不要把邀请贴到公开场合。"),
      div(
        cls := "form-grid",
        field("渠道标签", input(cls := "text-input", value <-- label, onInput.mapToValue --> label, placeholder := "微信 / 当面")),
        field("本方账号", input(cls := "text-input", value <-- account, onInput.mapToValue --> account, placeholder := "对方能核对的账号")),
        field("指定对方账号（可选）", input(cls := "text-input", value <-- expected, onInput.mapToValue --> expected, placeholder := "定向邀请时填写")),
        field("有效期（小时）", input(cls := "text-input", tpe := "number", value <-- ttl, onInput.mapToValue --> ttl))
      ),
      field("备注（可选）", textArea(cls := "text-input textarea", value <-- note, onInput.mapToValue --> note, placeholder := "例如：周六见")),
      child.maybe <-- error.signal.map(_.map(message => p(cls := "form-error", message))),
      div(
        cls := "dialog-actions",
        button(
          cls := "primary-button",
          disabled <-- busy.signal,
          onClick --> (_ => submit()),
          child.text <-- busy.signal.map(value => if value then "正在生成…" else "生成邀请")
        )
      ),
      child.maybe <-- created.signal.map(_.map(invitation => invitationReceipt(ui, invitation)))
    )

  private def receivedForm(api: ApiClient, ui: UiState): HtmlElement =
    val expected  = Var("")
    val received  = Var(Option.empty[Invitation])
    val busy      = Var(false)
    val error     = Var(Option.empty[String])
    val myLabel   = Var("微信")
    val myAccount = Var("")
    val note      = Var("")
    val reply     = Var(Option.empty[String])

    def read(): Unit =
      if ui.inviteText.now().trim.isEmpty then error.set(Some("先粘贴一份邀请文本"))
      else
        busy.set(true)
        error.set(None)
        api.receiveInvitation(ui.inviteText.now().trim, Option(expected.now().trim).filter(_.nonEmpty)).onComplete {
          case Success(invitation) =>
            busy.set(false)
            received.set(Some(invitation))
            myLabel.set(if invitation.channelLabel.isEmpty then "微信" else invitation.channelLabel)
            ui.notify("邀请已读取")
          case Failure(fail)       =>
            busy.set(false)
            error.set(Some(api.describeError(fail)))
        }

    def accept(invitation: Invitation): Unit =
      if myAccount.now().trim.isEmpty then error.set(Some("填写用于本次应答的本方账号"))
      else
        busy.set(true)
        error.set(None)
        api.acceptInvitation(invitation.id, myLabel.now().trim, myAccount.now().trim, note.now().trim).onComplete {
          case Success(text) =>
            busy.set(false)
            reply.set(Some(text))
            ui.notify("应答已生成")
          case Failure(fail) =>
            busy.set(false)
            error.set(Some(api.describeError(fail)))
        }

    div(
      cls := "dialog-body",
      p(cls := "dialog-lede", "粘贴朋友发给你的邀请。读取后先核对渠道账号，再生成应答。"),
      field(
        "邀请文本",
        textArea(cls := "text-input textarea tall", value <-- ui.inviteText, onInput.mapToValue --> ui.inviteText, placeholder := "粘贴邀请文本")
      ),
      field("指定我的账号（可选）", input(cls := "text-input", value <-- expected, onInput.mapToValue --> expected, placeholder := "定向邀请会显示对方指定的账号")),
      div(
        cls := "dialog-actions",
        button(
          cls := "secondary-button",
          disabled <-- busy.signal,
          onClick --> (_ => read()),
          child.text <-- busy.signal.map(value => if value then "正在读取…" else "读取邀请")
        )
      ),
      child.maybe <-- received.signal.map(_.map(invitation => invitationPreview(invitation, myLabel, myAccount, note, busy, accept))),
      child.maybe <-- error.signal.map(_.map(message => p(cls := "form-error", message))),
      child.maybe <-- reply.signal.map(_.map(text => replyReceipt(ui, text)))
    )

  private def invitationReceipt(ui: UiState, invitation: Invitation): HtmlElement =
    div(
      cls := "receipt",
      div(cls      := "receipt-head", span("邀请已生成"), span(TimeFormat.timestamp(invitation.expiresAt))),
      textArea(cls := "text-input textarea code-text", readOnly := true, value := invitation.text),
      div(cls      := "receipt-actions", button(cls := "secondary-button", onClick --> (_ => UiUtil.copy(invitation.text, ui)), "复制邀请")),
      p(cls        := "receipt-note", "发送给朋友后，等待对方在自己的设备上确认。邀请方需要在这台设备上接收应答。")
    )

  private def invitationPreview(
    invitation: Invitation,
    myLabel: Var[String],
    myAccount: Var[String],
    note: Var[String],
    busy: Var[Boolean],
    accept: Invitation => Unit
  ): HtmlElement =
    div(
      cls := "receipt preview",
      div(cls := "receipt-head", span("收到的邀请"), span(TimeFormat.timestamp(invitation.expiresAt))),
      div(
        cls   := "preview-facts",
        fact("渠道", s"${invitation.channelLabel} · ${invitation.channelAccount}"),
        fact("备注", if invitation.note.isEmpty then "无" else invitation.note),
        fact("定向账号", invitation.expectedAccount.getOrElse("未指定"))
      ),
      div(
        cls   := "form-grid",
        field("我的渠道标签", input(cls := "text-input", value <-- myLabel, onInput.mapToValue --> myLabel)),
        field("我的账号", input(cls := "text-input", value <-- myAccount, onInput.mapToValue --> myAccount, placeholder := "对方能核对的账号")),
        field("备注（可选）", input(cls := "text-input", value <-- note, onInput.mapToValue --> note))
      ),
      div(
        cls   := "dialog-actions",
        button(
          cls := "primary-button",
          disabled <-- busy.signal,
          onClick --> (_ => accept(invitation)),
          child.text <-- busy.signal.map(value => if value then "正在生成…" else "接受并生成应答")
        )
      ),
      p(cls   := "receipt-note", "接受后，应答会通过中继送回邀请方。你也可以复制应答文本，通过原来的渠道发回去。")
    )

  private def replyReceipt(ui: UiState, text: String): HtmlElement =
    div(
      cls := "receipt",
      div(cls      := "receipt-head", span("应答已生成"), span("通过中继送回")),
      textArea(cls := "text-input textarea code-text", readOnly := true, value := text),
      div(cls      := "receipt-actions", button(cls := "secondary-button", onClick --> (_ => UiUtil.copy(text, ui)), "复制应答")),
      p(cls        := "receipt-note", "对方需要在签发邀请的那台设备上确认。确认后，你们各自核对自己的安全码。")
    )

  private def field(caption: String, control: HtmlElement): HtmlElement =
    label(cls := "field", span(cls := "field-label", caption), control)

  private def fact(key: String, value: String): HtmlElement =
    div(cls := "fact", span(cls := "fact-key", key), span(cls := "fact-value", value))
