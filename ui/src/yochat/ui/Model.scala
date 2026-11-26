package yochat.ui

import ujson.Value

final case class Identity(userId: String, deviceId: String):
  def shortUser: String   = userId.take(8)
  def shortDevice: String = deviceId.take(8)

final case class Relay(online: Boolean, host: String, port: Int)

final case class PeerDevice(
  devicePub: String,
  role: String,
  name: String,
  revokedAt: Option[Long]
)

final case class Contact(
  userPub: String,
  devicePub: String,
  displayName: String,
  channelLabel: String,
  channelAccount: String,
  peerChannelAccount: Option[String],
  alias: Option[String],
  safetyCode: String,
  safetyVerified: Boolean,
  confirmation: String,
  conversationId: Option[String],
  lastMessage: String,
  lastMessageAt: Long,
  devices: Vector[PeerDevice] = Vector.empty
)

final case class Conversation(
  id: String,
  peerUserPub: String,
  peerName: String,
  lastMessage: String,
  lastMessageAt: Long,
  unread: Boolean,
  waiting: Boolean,
  buffered: Int
)

final case class Invitation(
  id: String,
  direction: String,
  lifecycle: String,
  text: String,
  channelLabel: String,
  channelAccount: String,
  peerAccount: String,
  expectedAccount: Option[String],
  note: String,
  expiresAt: Long
)

final case class Session(id: String, conversationId: String, peerDevicePub: String, phase: String, nextSeq: Long)

final case class Device(
  devicePub: String,
  role: String,
  name: String,
  version: Long,
  revokedAt: Option[Long],
  joinEpoch: Long,
  lastSyncSeq: Long
)

final case class PairingInspection(
  devicePub: String,
  name: String,
  fingerprint: String,
  confirmation: String,
  expiresAt: Long
)

final case class Lock(locked: Boolean, generation: Long)

final case class AppState(
  unlocked: Boolean,
  readOnly: Boolean,
  instanceExists: Boolean,
  identity: Option[Identity],
  relay: Relay,
  fatal: Option[String],
  notification: Option[String],
  lock: Lock,
  contacts: Vector[Contact],
  conversations: Vector[Conversation],
  invitations: Vector[Invitation],
  sessions: Vector[Session]
):

  def contactFor(conversation: Conversation): Option[Contact] =
    contacts.find(_.userPub == conversation.peerUserPub)

  def conversationFor(contact: Contact): Option[Conversation] =
    contact.conversationId.flatMap(id => conversations.find(_.id == id))

  def activeConversation: Option[Conversation] = conversations.headOption

object AppState:

  val empty: AppState = AppState(
    unlocked = false,
    readOnly = false,
    instanceExists = false,
    identity = None,
    relay = Relay(online = false, host = "127.0.0.1", port = 7654),
    fatal = None,
    notification = None,
    lock = Lock(locked = true, generation = 0L),
    contacts = Vector.empty,
    conversations = Vector.empty,
    invitations = Vector.empty,
    sessions = Vector.empty
  )

final case class ChatMessage(
  id: Long,
  conversationId: String,
  author: String,
  body: String,
  state: String,
  createdAt: Long,
  seq: Long
)

object Json:

  def parseState(value: Value): AppState =
    AppState(
      unlocked = bool(value, "unlocked").getOrElse(false),
      readOnly = bool(value, "readOnly").getOrElse(false),
      instanceExists = bool(value, "instanceExists").getOrElse(false),
      identity = field(value, "identity").flatMap(parseIdentity),
      relay = field(value, "relay").map(parseRelay).getOrElse(Relay(false, "127.0.0.1", 7654)),
      fatal = string(value, "fatal"),
      notification = string(value, "notification"),
      lock = field(value, "lock").map(parseLock).getOrElse(Lock(locked = true, generation = 0L)),
      contacts = array(value, "contacts").flatMap(parseContact),
      conversations = array(value, "conversations").flatMap(parseConversation),
      invitations = array(value, "invitations").flatMap(parseInvitation),
      sessions = array(value, "sessions").flatMap(parseSession)
    )

  def parseDevices(value: Value): Vector[Device] =
    array(value, "devices").flatMap { item =>
      for
        device <- string(item, "devicePub")
        role   <- string(item, "role")
        name   <- string(item, "name")
      yield Device(
        device,
        role,
        name,
        long(item, "version").getOrElse(0L),
        long(item, "revokedAt"),
        long(item, "joinEpoch").getOrElse(0L),
        long(item, "lastSyncSeq").getOrElse(0L)
      )
    }

  def parsePairingInspection(value: Value): Option[PairingInspection] =
    for
      device       <- string(value, "devicePub")
      name         <- string(value, "name")
      fingerprint  <- string(value, "fingerprint")
      confirmation <- string(value, "confirmation")
    yield PairingInspection(device, name, fingerprint, confirmation, long(value, "expiresAt").getOrElse(0L))

  def parseMessages(value: Value): Vector[ChatMessage] =
    array(value, "messages") match
      case values if values.nonEmpty => values.flatMap(parseMessage)
      case _                         => arrayValue(value).flatMap(parseMessage)

  def errorCode(value: Value): Option[String] =
    string(value, "error")

  private def parseIdentity(value: Value): Option[Identity] =
    for
      user   <- string(value, "userId")
      device <- string(value, "deviceId")
    yield Identity(user, device)

  private def parseRelay(value: Value): Relay =
    Relay(
      online = bool(value, "online").getOrElse(false),
      host = string(value, "host").getOrElse("127.0.0.1"),
      port = int(value, "port").getOrElse(7654)
    )

  private def parseLock(value: Value): Lock =
    Lock(bool(value, "locked").getOrElse(true), long(value, "generation").getOrElse(0L))

  private def parseContact(value: Value): Option[Contact] =
    for
      user   <- string(value, "userPub")
      device <- string(value, "devicePub")
      name   <- string(value, "displayName")
    yield Contact(
      user,
      device,
      name,
      string(value, "channelLabel").getOrElse(""),
      string(value, "channelAccount").getOrElse(""),
      string(value, "peerChannelAccount"),
      string(value, "alias"),
      string(value, "safetyCode").getOrElse(""),
      bool(value, "safetyVerified").getOrElse(false),
      string(value, "confirmation").getOrElse("pending"),
      string(value, "conversationId"),
      string(value, "lastMessage").getOrElse(""),
      long(value, "lastMessageAt").getOrElse(0L),
      value.obj.get("devices").toVector.flatMap(_.arr).flatMap(parsePeerDevice)
    )

  private def parsePeerDevice(value: Value): Option[PeerDevice] =
    string(value, "devicePub").map(device =>
      PeerDevice(
        device,
        string(value, "role").getOrElse("companion"),
        string(value, "name").getOrElse(""),
        long(value, "revokedAt")
      )
    )

  private def parseConversation(value: Value): Option[Conversation] =
    for
      id   <- string(value, "id")
      peer <- string(value, "peerUserPub")
      name <- string(value, "peerName")
    yield Conversation(
      id,
      peer,
      name,
      string(value, "lastMessage").getOrElse(""),
      long(value, "lastMessageAt").getOrElse(0L),
      bool(value, "unread").getOrElse(false),
      bool(value, "waiting").getOrElse(false),
      int(value, "buffered").getOrElse(0)
    )

  private def parseInvitation(value: Value): Option[Invitation] =
    for
      id   <- string(value, "id")
      text <- string(value, "text")
    yield Invitation(
      id,
      string(value, "direction").getOrElse("issued"),
      string(value, "lifecycle").getOrElse("issued"),
      text,
      string(value, "channelLabel").getOrElse(""),
      string(value, "channelAccount").getOrElse(""),
      string(value, "peerAccount").getOrElse(""),
      string(value, "expectedAccount"),
      string(value, "note").getOrElse(""),
      long(value, "expiresAt").getOrElse(0L)
    )

  private def parseSession(value: Value): Option[Session] =
    for
      id   <- string(value, "id")
      conv <- string(value, "conversationId")
    yield Session(
      id,
      conv,
      string(value, "peerDevicePub").getOrElse(""),
      string(value, "phase").getOrElse("read-only"),
      long(value, "nextSeq").getOrElse(1L)
    )

  private def parseMessage(value: Value): Option[ChatMessage] =
    for
      id   <- long(value, "id")
      conv <- string(value, "conversationId")
      body <- string(value, "body")
    yield ChatMessage(
      id,
      conv,
      string(value, "author").getOrElse("peer"),
      body,
      string(value, "state").getOrElse("received"),
      long(value, "createdAt").getOrElse(0L),
      long(value, "seq").getOrElse(0L)
    )

  def field(value: Value, name: String): Option[Value] =
    value match
      case ujson.Obj(values) => values.collectFirst { case (key, child) if key == name => child }
      case _                 => None

  def string(value: Value, name: String): Option[String] =
    field(value, name) match
      case Some(ujson.Str(text)) => Some(text)
      case _                     => None

  def bool(value: Value, name: String): Option[Boolean] =
    field(value, name) match
      case Some(ujson.Bool(flag)) => Some(flag)
      case Some(ujson.Str(text))  => text.toBooleanOption
      case _                      => None

  def long(value: Value, name: String): Option[Long] =
    field(value, name) match
      case Some(ujson.Num(number)) if number.isWhole => Some(number.toLong)
      case Some(ujson.Str(text))                     => text.toLongOption
      case _                                         => None

  def int(value: Value, name: String): Option[Int] =
    long(value, name).map(_.toInt)

  def array(value: Value, name: String): Vector[Value] =
    field(value, name).map(arrayValue).getOrElse(Vector.empty)

  def arrayValue(value: Value): Vector[Value] =
    value match
      case ujson.Arr(values) => values.toVector
      case _                 => Vector.empty
