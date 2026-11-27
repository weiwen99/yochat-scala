package yochat.server

import java.nio.charset.StandardCharsets

import cats.effect.IO
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Invitation, SafetyNumber, SessionPhase}
import yochat.core.app.{AppContact, AppLimits, AppState, ContactConfirmation, Conversation, InvitationLifecycle, Notification}
import yochat.store.{MessageRecord, MessageState, Store}

object ApiJson:

  def locked(instanceExists: Boolean, config: ServiceConfig): Value =
    ujson.Obj(
      "unlocked"       -> false,
      "instanceExists" -> instanceExists,
      "relay"          -> relayJson(config, online = false),
      "fatal"          -> ujson.Null
    )

  def state(
    config: ServiceConfig,
    store: Store,
    state: AppState,
    online: Boolean,
    fatal: Option[AppExecutionError],
    lastNotification: Option[Notification]
  ): IO[Value] =
    val identity      = state.identity.map { value =>
      ujson.Obj(
        "userId"   -> value.user.publicKey.bytes.toHex,
        "deviceId" -> value.device.publicKey.bytes.toHex
      )
    }
    val contacts      = state.contacts.values.toVector.sortBy(contact => displayName(contact).toLowerCase)
    val conversations = state.conversations.values.toVector.sortBy(_.id.toHex)
    for
      contactValues      <- sequence(contacts.map(contact => contactJson(store, state, contact)))
      conversationValues <- sequence(conversations.map(conversation => conversationJson(store, state, conversation)))
    yield ujson.Obj(
      "unlocked"      -> state.lock.isUnlocked,
      "readOnly"      -> false,
      "identity"      -> identity.map(value => value).getOrElse(ujson.Null),
      "relay"         -> relayJson(config, online),
      "fatal"         -> fatal.map(error => ujson.Str(error.code)).getOrElse(ujson.Null),
      "notification"  -> (if state.lock.isUnlocked then lastNotification.map(value => ujson.Str(notificationCode(value))).getOrElse(ujson.Null)
                         else ujson.Null),
      "lock"          -> ujson.Obj(
        "locked"     -> !state.lock.isUnlocked,
        "generation" -> state.lock.value
      ),
      "contacts"      -> ujson.Arr(contactValues*),
      "conversations" -> ujson.Arr(conversationValues*),
      "invitations"   -> ujson.Arr(invitations(state)*),
      "sessions"      -> ujson.Arr(sessions(state)*)
    )

  def visible(record: MessageRecord, conversation: Conversation, localUser: ByteVector): Boolean =
    record.authorPub.bytes == localUser ||
      record.seq < conversation.nextSeq.getOrElse(record.authorPub, 1L)

  def ordered(records: List[MessageRecord]): List[MessageRecord] =
    records.sortBy(record => (record.seq, record.id))

  def ready(records: List[MessageRecord], orders: Map[yochat.core.s2.LogicalKey, yochat.core.s2.OrderStamp]): List[MessageRecord] =
    val present = records.map(record => (record.devicePub, record.seq)).toSet ++
      orders.keys.map(key => (key.authorDevice, key.authorSeq)).toSet
    records.filter { record =>
      orders.get(yochat.core.s2.LogicalKey(record.conversationId, record.devicePub, record.seq)).forall { stamp =>
        stamp.deps.forall(dep => present.contains((dep.device, dep.seq)))
      }
    }

  def ordered(records: List[MessageRecord], orders: Map[yochat.core.s2.LogicalKey, yochat.core.s2.OrderStamp]): List[MessageRecord] =
    records.sortBy { record =>
      val lamport = orders
        .get(yochat.core.s2.LogicalKey(record.conversationId, record.devicePub, record.seq))
        .map(_.lamport)
        .getOrElse(record.seq)
      (lamport, record.devicePub.bytes.toHex, record.seq, record.id)
    }

  def messages(records: List[MessageRecord], localUser: ByteVector): Value =
    ujson.Arr(records.map(messageJson(_, localUser))*)

  def messageJson(record: MessageRecord, localUser: ByteVector): Value =
    ujson.Obj(
      "id"             -> record.id,
      "conversationId" -> record.conversationId.toHex,
      "author"         -> (if record.authorPub.bytes == localUser then "me" else "peer"),
      "body"           -> decodeBody(record.body),
      "state"          -> messageState(record.state),
      "createdAt"      -> record.createdAt,
      "seq"            -> record.seq
    )

  def invitation(invitation: Invitation): Value =
    ujson.Obj(
      "id"             -> invitation.fields.ephPub.bytes.toHex,
      "text"           -> invitation.text,
      "expiresAt"      -> invitation.fields.expiresAt,
      "channelLabel"   -> invitation.fields.channelLabel,
      "channelAccount" -> invitation.fields.channelAccount,
      "note"           -> invitation.fields.note
    )

  def reply(text: String, invitationId: ByteVector): Value =
    ujson.Obj(
      "replyText"    -> text,
      "invitationId" -> invitationId.toHex
    )

  def error(error: ServiceError): Value =
    ujson.Obj("error" -> error.code)

  private def lastVisible(store: Store, conversation: Conversation, localUser: ByteVector): IO[Option[MessageRecord]] =
    for
      records <- store.listRecentMessages(conversation.id, AppLimits.MaxBuffered + 1)
      orders  <- store.listMessageOrders(conversation.id)
    yield (records, orders) match
      case (Right(values), Right(orderValues)) =>
        ordered(ready(values.filter(record => visible(record, conversation, localUser)), orderValues.toMap), orderValues.toMap).lastOption
      case _                                   => None

  private def contactJson(store: Store, state: AppState, contact: AppContact): IO[Value] =
    PeerDeviceListService.devicesFor(store, contact.userPub).flatMap { devices =>
      val deviceJson = ujson.Arr(
        devices.getOrElse(Vector.empty).map { entry =>
          ujson.Obj(
            "devicePub" -> entry.devicePub.bytes.toHex,
            "role"      -> (if entry.role == yochat.core.s2.PeerDeviceRole.Primary then "primary" else "companion"),
            "name"      -> entry.name,
            "revokedAt" -> entry.revokedAt.map(value => ujson.Num(value.toDouble)).getOrElse(ujson.Null)
          )
        }*
      )
      contactJsonWithDevices(store, state, contact, deviceJson)
    }

  private def contactJsonWithDevices(store: Store, state: AppState, contact: AppContact, devices: Value): IO[Value] =
    val conversation = state.conversations.values.find(_.peerUserPub == contact.userPub)
    val safety       = state.identity
      .flatMap(identity => SafetyNumber.derive(identity.user.publicKey, contact.userPub).toOption)
      .map(_.display)
      .getOrElse("")
    conversation match
      case None               =>
        IO.pure(
          ujson.Obj(
            "userPub"            -> contact.userPub.bytes.toHex,
            "devicePub"          -> contact.devicePub.bytes.toHex,
            "displayName"        -> displayName(contact),
            "channelLabel"       -> contact.channelLabel,
            "channelAccount"     -> contact.channelAccount,
            "peerChannelAccount" -> contact.peerChannelAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null),
            "alias"              -> contact.alias.map(value => ujson.Str(value)).getOrElse(ujson.Null),
            "safetyCode"         -> safety,
            "safetyVerified"     -> contact.safetyVerified,
            "confirmation"       -> confirmation(contact.confirmation),
            "conversationId"     -> ujson.Null,
            "lastMessage"        -> "",
            "lastMessageAt"      -> 0L,
            "devices"            -> devices
          )
        )
      case Some(conversation) =>
        lastVisible(store, conversation, state.identity.map(_.user.publicKey.bytes).getOrElse(ByteVector.empty)).map { record =>
          ujson.Obj(
            "userPub"            -> contact.userPub.bytes.toHex,
            "devicePub"          -> contact.devicePub.bytes.toHex,
            "displayName"        -> displayName(contact),
            "channelLabel"       -> contact.channelLabel,
            "channelAccount"     -> contact.channelAccount,
            "peerChannelAccount" -> contact.peerChannelAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null),
            "alias"              -> contact.alias.map(value => ujson.Str(value)).getOrElse(ujson.Null),
            "safetyCode"         -> safety,
            "safetyVerified"     -> contact.safetyVerified,
            "confirmation"       -> confirmation(contact.confirmation),
            "conversationId"     -> conversation.id.toHex,
            "lastMessage"        -> record.map(value => decodeBody(value.body)).getOrElse(""),
            "lastMessageAt"      -> record.map(_.createdAt).getOrElse(0L),
            "devices"            -> devices
          )
        }

  private def conversationJson(store: Store, state: AppState, conversation: Conversation): IO[Value] =
    lastVisible(store, conversation, state.identity.map(_.user.publicKey.bytes).getOrElse(ByteVector.empty)).map { last =>
      val contact            = state.contacts.get(conversation.peerUserPub)
      ujson.Obj(
        "id"            -> conversation.id.toHex,
        "peerUserPub"   -> conversation.peerUserPub.bytes.toHex,
        "peerName"      -> contact.map(displayName).getOrElse(short(conversation.peerUserPub.bytes)),
        "lastMessage"   -> last.map(value => decodeBody(value.body)).getOrElse(""),
        "lastMessageAt" -> last.map(_.createdAt).getOrElse(0L),
        "unread"        -> false,
        "waiting"       -> conversation.gapRequested.isDefined,
        "buffered"      -> conversation.buffered.size
      )
    }

  private def invitations(state: AppState): Vector[Value] =
    state.invitations.values.toVector.sortBy(_.expiresAt).map { invitation =>
      ujson.Obj(
        "id"              -> invitation.id.toHex,
        "direction"       -> (if invitation.ephemeralPrivate.isDefined then "issued" else "received"),
        "lifecycle"       -> lifecycle(invitation.lifecycle),
        "text"            -> invitation.invitation.text,
        "channelLabel"    -> invitation.invitation.fields.channelLabel,
        "channelAccount"  -> invitation.invitation.fields.channelAccount,
        "peerAccount"     -> invitation.invitation.fields.channelAccount,
        "expectedAccount" -> invitation.expectedAccount.map(value => ujson.Str(value)).getOrElse(ujson.Null),
        "note"            -> invitation.invitation.fields.note,
        "expiresAt"       -> invitation.expiresAt
      )
    }

  private def sessions(state: AppState): Vector[Value] =
    state.sessions.values.toVector
      .sortBy(_.state.sessionId.bytes.toHex)
      .map(session =>
        ujson.Obj(
          "id"             -> session.state.sessionId.bytes.toHex,
          "conversationId" -> session.conversationId.toHex,
          "peerDevicePub"  -> session.state.peerDevicePub.bytes.toHex,
          "phase"          -> (if session.state.phase == SessionPhase.Active then "active" else "read-only"),
          "nextSeq"        -> session.nextSeq
        )
      )

  private def relayJson(config: ServiceConfig, online: Boolean): Value =
    ujson.Obj(
      "online" -> online,
      "host"   -> config.relayHost,
      "port"   -> config.relayPort
    )

  private def sequence(values: Vector[IO[Value]]): IO[Vector[Value]] =
    values.foldLeft(IO.pure(Vector.empty[Value])) { (acc, value) =>
      acc.flatMap(current => value.map(current :+ _))
    }

  private def displayName(contact: AppContact): String =
    contact.alias.orElse(contact.peerChannelAccount).getOrElse(short(contact.userPub.bytes))

  private def short(value: ByteVector): String =
    value.toHex.take(8)

  private def confirmation(value: ContactConfirmation): String =
    value match
      case ContactConfirmation.Pending         => "pending"
      case ContactConfirmation.Confirmed       => "confirmed"
      case ContactConfirmation.AccountMismatch => "account-mismatch"

  private def lifecycle(value: InvitationLifecycle): String =
    value match
      case InvitationLifecycle.Issued        => "issued"
      case InvitationLifecycle.AwaitingReply => "awaiting-reply"
      case InvitationLifecycle.PendingReply  => "pending-reply"
      case InvitationLifecycle.Accepted      => "accepted"
      case InvitationLifecycle.Expired       => "expired"

  private def messageState(value: MessageState): String =
    value match
      case MessageState.Pending   => "pending"
      case MessageState.Sent      => "sent"
      case MessageState.Received  => "received"
      case MessageState.Failed    => "failed"
      case MessageState.Delivered => "delivered"

  private def notificationCode(notification: Notification): String =
    notification match
      case Notification.Waiting         => "waiting"
      case Notification.RekeyRequired   => "rekey-required"
      case Notification.RekeyFailed     => "rekey-failed"
      case Notification.MessageFailed   => "message-failed"
      case Notification.QueueFull       => "queue-full"
      case Notification.InboundOverflow => "inbound-overflow"
      case Notification.ChannelRebuild  => "channel-rebuild"

  private def decodeBody(value: ByteVector): String =
    new String(value.toArray, StandardCharsets.UTF_8)
