package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.s2.{JoinManifest, LogicalKey, OrderStamp}
import yochat.core.SafetyNumber
import yochat.store.{CompanionMessageRecord, ContactRecord, ConversationRecord, MessageState, StorageError, Store}

/** Read-only A2 view. It never calls MessageRepository or reconstructs a peer session. */
object CompanionView:

  final case class ConversationView(
    conversation: ConversationRecord,
    messages: List[CompanionMessageRecord]
  )

  final case class Snapshot(
    contacts: List[ContactRecord],
    conversations: List[ConversationView]
  )

  def load(store: Store): IO[Either[StorageError, Snapshot]] =
    for
      contacts      <- store.listContacts
      conversations <- store.listConversations
      manifest      <- loadManifest(store)
      result        <- contacts match
                         case Left(error)          => IO.pure(Left(error))
                         case Right(contactValues) =>
                           conversations match
                             case Left(error)               => IO.pure(Left(error))
                             case Right(conversationValues) =>
                               loadConversations(store, contactValues, conversationValues, manifest)
    yield result

  def stateJson(
    store: Store,
    snapshot: Snapshot,
    relayHost: String,
    relayPort: Int,
    online: Boolean
  ): IO[Either[String, Value]] =
    store.listBlockedPeers.map {
      case Left(error)    => Left(error.code)
      case Right(blocked) =>
        val blockedSet    = blocked.map(_.userPub).toSet
        val contacts      = snapshot.contacts
          .sortBy(contact => displayName(contact).toLowerCase)
          .map(contact => contactJson(store, snapshot, contact, blockedSet))
        val conversations = snapshot.conversations
          .sortBy(value => value.conversation.id.toHex)
          .map(value => conversationJson(snapshot, value))
        Right(
          ujson.Obj(
            "unlocked"       -> true,
            "readOnly"       -> true,
            "instanceExists" -> true,
            "identity"       -> ujson.Obj(
              "userId"   -> store.identity.userPublic.bytes.toHex,
              "deviceId" -> store.identity.devicePublic.bytes.toHex
            ),
            "relay"          -> ujson.Obj(
              "online" -> online,
              "host"   -> relayHost,
              "port"   -> relayPort
            ),
            "fatal"          -> ujson.Null,
            "notification"   -> ujson.Null,
            "lock"           -> ujson.Obj("locked" -> false, "generation" -> 0L),
            "contacts"       -> ujson.Arr(contacts*),
            "conversations"  -> ujson.Arr(conversations*),
            "invitations"    -> ujson.Arr(),
            "sessions"       -> ujson.Arr(),
            "blockedPeers"   -> ujson.Arr(
              blockedSet.toVector.sortBy(_.bytes.toHex).map { user =>
                val name = snapshot.contacts.find(_.userPub == user).map(displayName).getOrElse(user.bytes.toHex.take(8))
                ujson.Obj("userPub" -> user.bytes.toHex, "displayName" -> name)
              }*
            ),
            "lateArrival"    -> ujson.Null
          )
        )
    }

  def messagesJson(store: Store, conversationId: ByteVector, limit: Int): IO[Either[StorageError, Value]] =
    for
      messages <- store.listCompanionMessages(conversationId, limit)
      orders   <- store.listMessageOrders(conversationId)
      manifest <- loadManifest(store)
    yield (messages, orders) match
      case (Left(error), _)                    => Left(error)
      case (_, Left(error))                    => Left(error)
      case (Right(values), Right(orderValues)) =>
        val ordered = sortMessages(values, orderValues.toMap, manifest)
        Right(
          ujson.Arr(
            ordered.map(message =>
              ujson.Obj(
                "id"             -> message.sourceId,
                "conversationId" -> message.conversationId.toHex,
                "author"         -> (if message.authorPub == store.identity.userPublic then "me" else "peer"),
                "body"           -> message.body.decodeUtf8.getOrElse(""),
                "state"          -> stateName(message.state),
                "createdAt"      -> message.createdAt,
                "seq"            -> message.seq
              )
            )*
          )
        )

  private def sortMessages(
    values: List[CompanionMessageRecord],
    orders: Map[LogicalKey, OrderStamp],
    manifest: JoinManifest
  ): List[CompanionMessageRecord] =
    val present                                                 = values.map(message => (message.devicePub, message.seq)).toSet ++
      orders.keys.map(key => (key.authorDevice, key.authorSeq)).toSet
    def satisfied(dep: yochat.core.s2.OrderDependency): Boolean =
      present.contains((dep.device, dep.seq)) ||
        manifest.entries.exists { case (key, maxSeq) => key.devicePub == dep.device && dep.seq <= maxSeq }
    values
      .filter(message => message.logicalKey.forall(key => orders.get(key).forall(_.deps.forall(satisfied))))
      .sortBy { message =>
        val lamport = message.logicalKey.flatMap(key => orders.get(key)).map(_.lamport).getOrElse(message.seq)
        (lamport, message.devicePub.bytes.toHex, message.seq, message.sourceId)
      }

  private def loadConversations(
    store: Store,
    contacts: List[ContactRecord],
    conversations: List[ConversationRecord],
    manifest: JoinManifest
  ): IO[Either[StorageError, Snapshot]] =
    conversations
      .foldLeft(IO.pure[Either[StorageError, List[ConversationView]]](Right(Nil))) { (acc, conversation) =>
        acc.flatMap {
          case Left(error)   => IO.pure(Left(error))
          case Right(values) =>
            store.listCompanionMessages(conversation.id, 1_000).flatMap {
              case Left(error)     => IO.pure(Left(error))
              case Right(messages) =>
                store.listMessageOrders(conversation.id).map {
                  case Left(error)      => Left(error)
                  case Right(orderRows) => Right(values :+ ConversationView(conversation, sortMessages(messages, orderRows.toMap, manifest)))
                }
            }
        }
      }
      .map(_.map(values => Snapshot(contacts, values)))

  private def contactJson(
    store: Store,
    snapshot: Snapshot,
    record: ContactRecord,
    blocked: Set[yochat.core.Ed25519Public]
  ): Value =
    val conversation            = snapshot.conversations.find(_.conversation.peerUserPub == record.userPub)
    val last                    = conversation.flatMap(_.messages.lastOption)
    val peerDevice              = conversation.flatMap(_.messages.find(_.authorPub == record.userPub).map(_.devicePub))
    ujson.Obj(
      "userPub"            -> record.userPub.bytes.toHex,
      "devicePub"          -> peerDevice.map(_.bytes.toHex).getOrElse(""),
      "displayName"        -> displayName(record),
      "channelLabel"       -> record.data.channelLabel,
      "channelAccount"     -> record.data.channelAccount,
      "peerChannelAccount" -> record.data.peerChannelAccount.map(ujson.Str.apply).getOrElse(ujson.Null),
      "alias"              -> record.data.alias.map(ujson.Str.apply).getOrElse(ujson.Null),
      "safetyCode"         -> SafetyNumber.derive(store.identity.userPublic, record.userPub).toOption.map(_.display).getOrElse(""),
      "safetyVerified"     -> record.data.safetyVerified,
      "confirmation"       -> confirmation(record),
      "conversationId"     -> conversation.map(_.conversation.id.toHex).map(ujson.Str.apply).getOrElse(ujson.Null),
      "lastMessage"        -> last.map(_.body.decodeUtf8.getOrElse("")).getOrElse(""),
      "lastMessageAt"      -> last.map(_.createdAt).getOrElse(0L),
      "note"               -> record.data.note.map(ujson.Str.apply).getOrElse(ujson.Null),
      "recreated"          -> record.data.recreated,
      "blocked"            -> blocked.contains(record.userPub)
    )

  private def conversationJson(snapshot: Snapshot, value: ConversationView): Value =
    val contact            = snapshot.contacts.find(_.userPub == value.conversation.peerUserPub)
    val last               = value.messages.lastOption
    ujson.Obj(
      "id"            -> value.conversation.id.toHex,
      "peerUserPub"   -> value.conversation.peerUserPub.bytes.toHex,
      "peerName"      -> contact.map(displayName).getOrElse(value.conversation.peerUserPub.bytes.toHex.take(8)),
      "lastMessage"   -> last.map(_.body.decodeUtf8.getOrElse("")).getOrElse(""),
      "lastMessageAt" -> last.map(_.createdAt).getOrElse(0L),
      "unread"        -> false,
      "waiting"       -> false,
      "buffered"      -> 0
    )

  private def loadManifest(store: Store): IO[JoinManifest] =
    store.loadDevice(store.identity.devicePublic).map {
      case Left(_)       => JoinManifest.empty
      case Right(record) => JoinManifest.fromCanonical(record.joinManifest).getOrElse(JoinManifest.empty)
    }

  private def displayName(record: ContactRecord): String =
    record.data.alias
      .orElse(record.data.peerChannelAccount)
      .getOrElse(record.userPub.bytes.toHex.take(8))

  private def confirmation(record: ContactRecord): String =
    record.data.confirmationState match
      case yochat.store.ContactConfirmation.Pending         => "pending"
      case yochat.store.ContactConfirmation.Confirmed       => "confirmed"
      case yochat.store.ContactConfirmation.AccountMismatch => "account-mismatch"

  private def stateName(state: MessageState): String =
    state match
      case MessageState.Pending   => "pending"
      case MessageState.Sent      => "sent"
      case MessageState.Received  => "received"
      case MessageState.Failed    => "failed"
      case MessageState.Delivered => "delivered"
