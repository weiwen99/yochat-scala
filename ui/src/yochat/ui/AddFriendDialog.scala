package yochat.ui

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

import com.raquo.laminar.api.L.*

object AddFriendDialog:

  def apply(api: ApiClient, ui: UiState): HtmlElement =
    div(
      cls := "scrim",
      onClick --> (_ => ui.addFriend.set(false)),
      div(
        cls := "modal",
        onClick.stopPropagation --> (_ => ()),
        div(
          cls   := "modal-head",
          div(
            h2("添加联系人"),
            p("邀请与应答都只通过你选择的渠道传递；YoChat 不接受公开目录或陌生请求。")
          ),
          button(cls := "icon-button", aria.label := "关闭", title := "关闭", onClick --> (_ => ui.addFriend.set(false)), Icons("close"))
        ),
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
