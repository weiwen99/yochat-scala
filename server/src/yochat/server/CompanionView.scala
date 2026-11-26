package yochat.server

import cats.effect.IO
import scodec.bits.ByteVector
import ujson.Value

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
      result        <- contacts match
                         case Left(error)          => IO.pure(Left(error))
                         case Right(contactValues) =>
                           conversations match
                             case Left(error)               => IO.pure(Left(error))
                             case Right(conversationValues) =>
                               loadConversations(store, contactValues, conversationValues)
    yield result

  def stateJson(
    store: Store,
    snapshot: Snapshot,
    relayHost: String,
    relayPort: Int,
    online: Boolean
  ): Value =
    val contacts      = snapshot.contacts
      .sortBy(contact => displayName(contact).toLowerCase)
      .map(contact => contactJson(store, snapshot, contact))
    val conversations = snapshot.conversations
      .sortBy(value => value.conversation.id.toHex)
      .map(value => conversationJson(snapshot, value))

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
      "sessions"       -> ujson.Arr()
    )

  def messagesJson(store: Store, conversationId: ByteVector, limit: Int): IO[Either[StorageError, Value]] =
    store.listCompanionMessages(conversationId, limit).map {
      case Left(error)   => Left(error)
      case Right(values) =>
        Right(
          ujson.Arr(
            values.map(message =>
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
    }

  private def loadConversations(
    store: Store,
    contacts: List[ContactRecord],
    conversations: List[ConversationRecord]
  ): IO[Either[StorageError, Snapshot]] =
    conversations
      .foldLeft(IO.pure[Either[StorageError, List[ConversationView]]](Right(Nil))) { (acc, conversation) =>
        acc.flatMap {
          case Left(error)   => IO.pure(Left(error))
          case Right(values) =>
            store.listCompanionMessages(conversation.id, 1_000).map {
              case Left(error)     => Left(error)
              case Right(messages) => Right(values :+ ConversationView(conversation, messages))
            }
        }
      }
      .map(_.map(values => Snapshot(contacts, values)))

  private def contactJson(store: Store, snapshot: Snapshot, record: ContactRecord): Value =
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
      "lastMessageAt"      -> last.map(_.createdAt).getOrElse(0L)
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
