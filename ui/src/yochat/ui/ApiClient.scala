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
  val messages: Var[Vector[ChatMessage]]              = Var(Vector.empty)
  val activeConversation: Var[Option[String]]         = Var(None)
  val selectedConversation: Var[Option[Conversation]] = Var(None)
  val selectedContact: Var[Option[Contact]]           = Var(None)
  val canSend: Var[Boolean]                           = Var(false)

  private val socket: Var[Option[dom.WebSocket]] = Var(None)

  def start(): Unit =
    loadSession()
    connectSocket()

  def selectConversation(id: Option[String]): Unit =
    activeConversation.set(id)
    updateSelection(state.now())
    id.foreach(loadMessages)

  def loadSession(): Unit =
    request("GET", "/api/session", None).onComplete {
      case Success(value)   =>
        val next = Json.parseState(value)
        state.set(next)
        if unlocked.now() != next.unlocked then unlocked.set(next.unlocked)
        if loading.now() then loading.set(false)
        error.set(None)
        if next.unlocked then
          val selected = activeConversation.now().filter(id => next.conversations.exists(_.id == id))
          val target   = selected.orElse(next.conversations.headOption.map(_.id))
          if activeConversation.now() != target then activeConversation.set(target)
          updateSelection(next)
          target.foreach(loadMessages)
        else
          messages.set(Vector.empty)
          selectedConversation.set(None)
          selectedContact.set(None)
          canSend.set(false)
      case Failure(failure) =>
        loading.set(false)
        error.set(Some(message(failure)))
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
        error.set(None)
        loadSession()
      case Failure(fail) => error.set(Some(message(fail)))
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

  def describeError(failure: Throwable): String = message(failure)

  private def updateSelection(current: AppState): Unit =
    val id      = activeConversation.now()
    val conv    = id.flatMap(value => current.conversations.find(_.id == value))
    val contact = conv.flatMap(value => current.contactFor(value))
    if selectedConversation.now() != conv then selectedConversation.set(conv)
    if selectedContact.now() != contact then selectedContact.set(contact)
    val allowed = conv.isDefined && contact.exists(_.safetyVerified)
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
        error.set(Some(message(fail)))
    }

  private def connectSocket(): Unit =
    val protocol    = if dom.window.location.protocol == "https:" then "wss" else "ws"
    val socketValue = new dom.WebSocket(s"$protocol://${dom.window.location.host}/api/events")
    socketValue.onopen = _ =>
      error.set(None)
      loadSession()
    socketValue.onmessage = event =>
      Try(ujson.read(event.data.toString)).toOption.foreach { value =>
        if Json.string(value, "type").contains("state") then loadSession()
      }
    socketValue.onclose = _ =>
      socket.set(None)
      scheduleReconnect()
    socketValue.onerror = _ => socketValue.close()
    socket.set(Some(socketValue))

  private def scheduleReconnect(): Unit =
    js.timers.setTimeout(750) {
      if socket.now().isEmpty then connectSocket()
    }

  private def request(method: String, path: String, body: Option[String]): Future[Value] =
    val init = js.Dynamic.literal(method = method, headers = js.Dictionary("Content-Type" -> "application/json"))
    body.foreach(value => init.body = value)
    dom
      .fetch(path, init.asInstanceOf[dom.RequestInit]) // scalafix:ok DisableSyntax.asInstanceOf
      .toFuture
      .flatMap { response =>
        response.text().toFuture.flatMap { text =>
          val parsed = Try(ujson.read(text)).toOption
          if response.ok then parsed.fold(Future.failed(new ApiException("invalid-json")))(Future.successful)
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
      case api: ApiException => api.code
      case _                 => "本机服务暂时不可用"

final private[ui] class ApiException(val code: String) extends RuntimeException(code)
