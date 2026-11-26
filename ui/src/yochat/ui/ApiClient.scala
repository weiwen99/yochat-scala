package yochat.ui

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import ujson.Value

final class ApiClient:

  private given ExecutionContext = ExecutionContext.global

  val state: Var[AppState]                            = Var(AppState.empty)
  val unlocked: Var[Boolean]                          = Var(false)
  val loading: Var[Boolean]                           = Var(true)
  val error: Var[Option[String]]                      = Var(None)
  val notice: Var[Option[String]]                     = Var(None)
  val messages: Var[Vector[ChatMessage]]              = Var(Vector.empty)
  val activeConversation: Var[Option[String]]         = Var(None)
  val selectedConversation: Var[Option[Conversation]] = Var(None)
  val selectedContact: Var[Option[Contact]]           = Var(None)
  val canSend: Var[Boolean]                           = Var(false)
  val devices: Var[Vector[Device]]                    = Var(Vector.empty)

  private val socket: Var[Option[dom.WebSocket]]   = Var(None)
  private val dismissedNotice: Var[Option[String]] = Var(None)
  private val serverExited: Var[Boolean]           = Var(false)
  private val sessionRequestId: Var[Long]          = Var(0L)

  def start(): Unit =
    listenForServerExit()
    loadSession()
    connectSocket()

  def dismissNotice(): Unit =
    dismissedNotice.set(notice.now())
    notice.set(None)

  def selectConversation(id: Option[String]): Unit =
    activeConversation.set(id)
    updateSelection(state.now())
    id.foreach(loadMessages)

  def loadSession(): Unit =
    val requestId = sessionRequestId.now() + 1L
    sessionRequestId.set(requestId)
    request("GET", "/api/session", None).onComplete {
      case Success(value) if sessionRequestId.now() == requestId   =>
        val next = Json.parseState(value)
        state.set(next)
        updateNotice(next.notification)
        if unlocked.now() != next.unlocked then unlocked.set(next.unlocked)
        if loading.now() then loading.set(false)
        if !serverExited.now() then error.set(None)
        if next.unlocked then
          val selected = activeConversation.now().filter(id => next.conversations.exists(_.id == id))
          val target   = selected.orElse(next.conversations.headOption.map(_.id))
          if activeConversation.now() != target then activeConversation.set(target)
          updateSelection(next)
          target.foreach(loadMessages)
          loadDevices()
        else
          messages.set(Vector.empty)
          selectedConversation.set(None)
          selectedContact.set(None)
          canSend.set(false)
      case Failure(failure) if sessionRequestId.now() == requestId =>
        loading.set(false)
        if !serverExited.now() then error.set(Some(message(failure)))
      case _                                                       => ()
    }

  def unlock(passphrase: String): Future[Unit] =
    post("/api/unlock", ujson.Obj("passphrase" -> passphrase)).map { _ =>
      error.set(None)
      loadSession()
    }

  def lock(): Unit =
    post("/api/lock", ujson.Obj()).onComplete {
      case Success(_)    =>
        unlocked.set(false)
        messages.set(Vector.empty)
        state.set(AppState.empty)
        selectedConversation.set(None)
        selectedContact.set(None)
        canSend.set(false)
        devices.set(Vector.empty)
        if !serverExited.now() then error.set(None)
        notice.set(None)
        dismissedNotice.set(None)
        loadSession()
      case Failure(fail) => if !serverExited.now() then error.set(Some(message(fail)))
    }

  def createInvitation(
    channelLabel: String,
    channelAccount: String,
    expectedAccount: Option[String],
    note: String,
    ttlHours: Int
  ): Future[Invitation] =
    post(
      "/api/invitations",
      ujson.Obj(
        "channelLabel"    -> channelLabel,
        "channelAccount"  -> channelAccount,
        "expectedAccount" -> expectedAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null),
        "note"            -> note,
        "ttlHours"        -> ttlHours
      )
    ).map(value =>
      Invitation(
        id = Json.string(value, "id").getOrElse(""),
        direction = "issued",
        lifecycle = "issued",
        text = Json.string(value, "text").getOrElse(""),
        channelLabel = Json.string(value, "channelLabel").getOrElse(channelLabel),
        channelAccount = Json.string(value, "channelAccount").getOrElse(channelAccount),
        peerAccount = "",
        expectedAccount = expectedAccount,
        note = Json.string(value, "note").getOrElse(note),
        expiresAt = Json.long(value, "expiresAt").getOrElse(0L)
      )
    )

  def receiveInvitation(text: String, expectedAccount: Option[String]): Future[Invitation] =
    post(
      "/api/invitations/receive",
      ujson.Obj(
        "text"            -> text,
        "expectedAccount" -> expectedAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null)
      )
    ).map(value =>
      Invitation(
        id = Json.string(value, "id").getOrElse(""),
        direction = "received",
        lifecycle = "awaiting-reply",
        text = Json.string(value, "text").getOrElse(text),
        channelLabel = Json.string(value, "channelLabel").getOrElse(""),
        channelAccount = Json.string(value, "channelAccount").getOrElse(""),
        peerAccount = Json.string(value, "channelAccount").getOrElse(""),
        expectedAccount = expectedAccount,
        note = Json.string(value, "note").getOrElse(""),
        expiresAt = Json.long(value, "expiresAt").getOrElse(0L)
      )
    )

  def acceptInvitation(
    invitationId: String,
    channelLabel: String,
    channelAccount: String,
    note: String
  ): Future[String] =
    post(
      s"/api/invitations/$invitationId/accept",
      ujson.Obj("channelLabel" -> channelLabel, "channelAccount" -> channelAccount, "note" -> note)
    ).map(value => Json.string(value, "replyText").getOrElse(""))

  def rejectInvitation(invitationId: String): Future[Unit] =
    post(s"/api/invitations/$invitationId/reject", ujson.Obj()).map(_ => ())

  def setAlias(userPub: String, alias: Option[String]): Future[Unit] =
    post(
      s"/api/contacts/$userPub/alias",
      ujson.Obj("alias" -> alias.map(value => ujson.Str(value)).getOrElse(ujson.Null))
    ).map(_ => ())

  def verifySafety(userPub: String): Future[Unit] =
    post(s"/api/contacts/$userPub/safety", ujson.Obj()).map(_ => ())

  def sendMessage(conversationId: String, body: String): Future[Unit] =
    post(s"/api/conversations/$conversationId/messages", ujson.Obj("body" -> body)).map { _ =>
      loadMessages(conversationId)
      ()
    }

  def startRekey(sessionId: String): Future[Unit] =
    post(s"/api/rekey/$sessionId", ujson.Obj()).map(_ => ())

  def refresh(): Unit = loadSession()

  def createPairingInvite(): Future[String] =
    post("/api/devices/pairing", ujson.Obj()).map(value => Json.string(value, "code").getOrElse(""))

  def inspectPairing(requestCode: String): Future[PairingInspection] =
    post("/api/devices/pairing/inspect", ujson.Obj("requestCode" -> requestCode)).map { value =>
      Json.parsePairingInspection(value).getOrElse(throw new IllegalStateException("pairing-inspection"))
    }

  def confirmPairing(requestCode: String, confirmation: String): Future[String] =
    post("/api/devices/pairing/confirm", ujson.Obj("requestCode" -> requestCode, "confirmation" -> confirmation))
      .map { value =>
        loadDevices()
        Json.string(value, "authCode").getOrElse("")
      }

  def syncDevice(devicePub: String): Future[Unit] =
    post(s"/api/devices/$devicePub/sync", ujson.Obj()).map(_ => ())

  def revokeDevice(devicePub: String): Future[Unit] =
    post(s"/api/devices/$devicePub/revoke", ujson.Obj()).map(_ => loadDevices())

  def describeError(failure: Throwable): String = message(failure)

  private def loadDevices(): Unit =
    request("GET", "/api/devices", None).onComplete {
      case Success(value)   => devices.set(Json.parseDevices(value))
      case Failure(failure) => error.set(Some(message(failure)))
    }

  private def updateSelection(current: AppState): Unit =
    val id      = activeConversation.now()
    val conv    = id.flatMap(value => current.conversations.find(_.id == value))
    val contact = conv.flatMap(value => current.contactFor(value))
    if selectedConversation.now() != conv then selectedConversation.set(conv)
    if selectedContact.now() != contact then selectedContact.set(contact)
    val allowed = !current.readOnly && conv.isDefined && contact.exists(_.safetyVerified)
    if canSend.now() != allowed then canSend.set(allowed)

  private def loadMessages(conversationId: String): Unit =
    request("GET", s"/api/conversations/$conversationId/messages?limit=300", None).onComplete {
      case Success(value) =>
        val next = Json.parseMessages(value)
        val old  = messages.now()
        messages.set(next)
        if next.exists(message => message.author == "peer" && !old.exists(_.id == message.id)) then
          val title = state.now().contacts.find(_.conversationId.contains(conversationId)).map(_.displayName).getOrElse("YoChat")
          notifyNative(title)
      case Failure(fail)  =>
        if !serverExited.now() then error.set(Some(message(fail)))
    }

  private def connectSocket(): Unit =
    val protocol    = if dom.window.location.protocol == "https:" then "wss" else "ws"
    val socketValue = new dom.WebSocket(s"$protocol://${dom.window.location.host}/api/events")
    socketValue.onopen = _ =>
      if !serverExited.now() then error.set(None)
      loadSession()
    socketValue.onmessage = event =>
      Try(ujson.read(event.data.toString)).toOption.foreach { value =>
        if Json.string(value, "type").contains("state") then loadSession()
      }
    socketValue.onclose = _ =>
      socket.set(None)
      if !serverExited.now() then error.set(Some("本机服务连接已断开，正在重连。"))
      scheduleReconnect()
    socketValue.onerror = _ => socketValue.close()
    socket.set(Some(socketValue))

  private def scheduleReconnect(): Unit =
    js.timers.setTimeout(750) {
      if socket.now().isEmpty then connectSocket()
    }

  private def updateNotice(value: Option[String]): Unit =
    if value.isEmpty then
      dismissedNotice.set(None)
      notice.set(None)
    else if value != dismissedNotice.now() then
      dismissedNotice.set(None)
      notice.set(value)

  private def listenForServerExit(): Unit =
    if js.typeOf(js.Dynamic.global.yochat) != "undefined" then
      val bridge = js.Dynamic.global.yochat
      if js.typeOf(bridge.onServerExited) == "function" then
        bridge.applyDynamic("onServerExited")((() => {
          serverExited.set(true)
          loading.set(false)
          error.set(Some("本机服务已退出。请重启 YoChat。"))
        }): js.Function0[Unit])

  private def request(method: String, path: String, body: Option[String]): Future[Value] =
    val init = js.Dynamic.literal(method = method, headers = js.Dictionary("Content-Type" -> "application/json"))
    body.foreach(value => init.body = value)
    dom
      .fetch(path, init.asInstanceOf[dom.RequestInit]) // scalafix:ok DisableSyntax.asInstanceOf
      .toFuture
      .flatMap { response =>
        response.text().toFuture.flatMap { text =>
          val parsed = Try(ujson.read(text)).toOption
          if response.ok then
            parsed match
              case Some(value) =>
                Json.errorCode(value) match
                  case Some(code) => Future.failed(new ApiException(code))
                  case None       => Future.successful(value)
              case None        => Future.failed(new ApiException("invalid-json"))
          else
            val code = parsed.flatMap(Json.errorCode).getOrElse(s"http-${response.status}")
            Future.failed(new ApiException(code))
        }
      }

  private def post(path: String, body: Value): Future[Value] =
    request("POST", path, Some(ujson.write(body)))

  private def notifyNative(title: String): Unit =
    if dom.document.hidden && js.typeOf(js.Dynamic.global.yochat) != "undefined" then
      js.Dynamic.global.yochat.applyDynamic("notify")(title, "有一条新消息")

  private def message(failure: Throwable): String =
    failure match
      case api: ApiException => codeMessage(api.code)
      case _                 => "本机服务暂时不可用。请确认 YoChat 服务已经启动。"

  private def codeMessage(code: String): String =
    code match
      case "queue-full" | "bad-request:queue-full" | "send-refused:queue-full"                =>
        "待发消息已满，请等中继恢复后再发送。"
      case "bad-request:send-rejected" | "send-refused:send-rejected"                         =>
        "现在不能发送：安全码或关系状态不允许。"
      case "send-refused:send-timeout"                                                        =>
        "发送状态未确认，请先查看聊天记录后再决定是否重发。"
      case "bad-request:event-queue-full"                                                     => "本机处理队列已满，请稍后重试。"
      case "locked"                                                                           => "本机已锁定，请重新解锁。"
      case "unlock-failed"                                                                    => "口令不正确，或这台设备的密钥无法打开。"
      case "weak-passphrase"                                                                  => "本机口令至少需要 8 个字符。"
      case "session-unavailable"                                                              => "本机会话已不可用，请重新解锁。"
      case value if value.startsWith("runtime-fatal:") || value.startsWith("runtime-failed:") =>
        "本机服务遇到无法恢复的错误。请重启 YoChat。"
      case value if value.startsWith("send-refused:")                                         => s"发送被拒绝：${value.stripPrefix("send-refused:")}"
      case value if value.startsWith("bad-request:")                                          => s"操作被拒绝：${value.stripPrefix("bad-request:")}"
      case value                                                                              => s"操作失败（$value）"

final private[ui] class ApiException(val code: String) extends RuntimeException(code)
