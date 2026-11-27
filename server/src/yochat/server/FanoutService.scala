package yochat.server

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

import cats.effect.IO
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, Nonce, X25519KeyPair, X25519Private}
import yochat.core.s2.{FanoutAck, FanoutEnvelope, LogicalKey, PeerDeviceEntry, PeerDeviceList, PeerDeviceRole}
import yochat.store.{DeliveryReceipt, MessageRecord, Store}
import yochat.transport.{RelayClient, RelayPayload}

/** B1 side of direct companion fanout. All sends are best-effort effects after the S1 message is durable; pending rows are retried on relay
  * reconnect. A2's `FanoutAck` settles only its own recipient row.
  */
object FanoutService:

  private val Random           = new SecureRandom()
  private val TargetCache      = new ConcurrentHashMap[String, (Long, Vector[PeerDeviceEntry])]()
  private val CapabilityCache  = new ConcurrentHashMap[String, (Long, Boolean)]()
  val PendingState: Int        = 1
  val AckedState: Int          = 2
  private val MaxAttempts: Int = 32

  def sendForMessage(store: Store, relay: RelayClient, messageId: Long): IO[Unit] =
    loadMessageRetry(store, messageId, 50).flatMap {
      case None          => IO.unit
      case Some(message) => sendForMessageRecord(store, relay, message)
    }

  def sendForMessageRecord(store: Store, relay: RelayClient, message: MessageRecord): IO[Unit] =
    store.loadConversation(message.conversationId).flatMap {
      case Left(_)             => IO.unit
      case Right(conversation) =>
        store.isBlocked(conversation.peerUserPub).flatMap {
          case true  => IO.unit
          case false =>
            targets(store, conversation.peerUserPub).flatMap { entries =>
              entries.foldLeft(IO.unit) { (acc, entry) =>
                acc *> sendTo(store, relay, message, entry).attempt.void
              }
            }
        }
    }

  def resendPending(store: Store, relay: RelayClient): IO[Unit] =
    store.listPendingDeliveryReceipts(100).flatMap {
      case Left(_)         => IO.unit
      case Right(receipts) =>
        receipts.foldLeft(IO.unit) { (acc, receipt) =>
          acc *> resendOne(store, relay, receipt).attempt.void
        }
    }

  def handleAck(store: Store, from: Ed25519Public, bytes: ByteVector): IO[Unit] =
    FanoutAck.fromBytes(bytes) match
      case Left(_)    => IO.unit
      case Right(ack) =>
        if ack.recipientDevice != store.identity.devicePublic || ack.senderDevice != from then IO.unit
        else
          authorizedCompanion(store, ack.senderDevice).flatMap {
            case None          => IO.unit
            case Some(userPub) =>
              store.isBlocked(userPub).flatMap {
                case true  => IO.unit
                case false =>
                  store.lockGeneration.flatMap {
                    case Left(_)     => IO.unit
                    case Right(lock) =>
                      IO.realTime.flatMap(time => store.markDeliveryAcked(ack.logicalKey, ack.senderDevice, time.toMillis, lock).void)
                  }
              }
          }

  private[server] def targets(store: Store, peerUser: Ed25519Public): IO[Vector[PeerDeviceEntry]] =
    val cacheKey = s"${store.identity.userPublic.bytes.toHex}:${peerUser.bytes.toHex}"
    Option(TargetCache.get(cacheKey)) match
      case Some((expires, entries)) if expires > System.nanoTime() => IO.pure(entries)
      case _                                                       =>
        store.loadPeerDeviceList(peerUser).map {
          case Left(_)     => Vector.empty
          case Right(list) =>
            val entries =
              if (list.features & PeerDeviceList.FeatureS2b) == 0L then Vector.empty
              else list.entries.filter(entry => entry.role == PeerDeviceRole.Companion && entry.revokedAt.isEmpty && entry.fanoutPub.isDefined)
            TargetCache.put(cacheKey, (System.nanoTime() + 10_000_000_000L, entries))
            entries
        }

  def invalidateTargets(store: Store, peerUser: Ed25519Public): Unit =
    val key = s"${store.identity.userPublic.bytes.toHex}:${peerUser.bytes.toHex}"
    TargetCache.remove(key)
    CapabilityCache.remove(key)

  def peerSupportsS2b(store: Store, peerUser: Ed25519Public): IO[Boolean] =
    val key    = s"${store.identity.userPublic.bytes.toHex}:${peerUser.bytes.toHex}"
    val cached = Option(CapabilityCache.get(key))
    cached match
      case Some((expires, value)) if expires > System.nanoTime() => IO.pure(value)
      case _                                                     =>
        store.s2bSeen.flatMap {
          case Left(_)      => IO.pure(false)
          case Right(false) => IO.pure(false)
          case Right(true)  =>
            store.loadPeerDeviceList(peerUser).map {
              case Right(list) =>
                val value = (list.features & PeerDeviceList.FeatureS2b) != 0L
                CapabilityCache.put(key, (System.nanoTime() + 10_000_000_000L, value))
                value
              case Left(_)     => false
            }
        }

  private def authorizedCompanion(store: Store, device: Ed25519Public): IO[Option[Ed25519Public]] =
    store.listContacts.flatMap {
      case Left(_)         => IO.pure(None)
      case Right(contacts) =>
        contacts.foldLeft(IO.pure(Option.empty[Ed25519Public])) { (acc, contact) =>
          acc.flatMap {
            case Some(value) => IO.pure(Some(value))
            case None        =>
              store.loadPeerDeviceList(contact.userPub).map {
                case Right(list) if (list.features & PeerDeviceList.FeatureS2b) != 0L =>
                  list.entries
                    .find(entry => entry.devicePub == device && entry.role == PeerDeviceRole.Companion && entry.revokedAt.isEmpty)
                    .map(_ => contact.userPub)
                case _                                                                => None
              }
          }
        }
    }

  private def sendTo(
    store: Store,
    relay: RelayClient,
    message: MessageRecord,
    entry: PeerDeviceEntry
  ): IO[Unit] =
    entry.fanoutPub match
      case None         => IO.unit
      case Some(fanout) =>
        val key = LogicalKey(message.conversationId, message.devicePub, message.seq)
        store.loadDeliveryReceipt(key, entry.devicePub).flatMap {
          case Right(receipt) if receipt.state == AckedState     => IO.unit
          case Right(receipt) if receipt.attempts >= MaxAttempts => IO.unit
          case existing                                          =>
            for
              attempts   = existing.toOption.map(_.attempts + 1).getOrElse(1)
              ephemeral <- randomX25519
              nonce     <- randomNonce
              order     <- message.order match
                             case Some(value) => IO.pure(Some(value))
                             case None        =>
                               store.listMessageOrders(message.conversationId).map(_.toOption.flatMap(_.find(_._1 == key).map(_._2)))
              envelope  <- IO.fromEither(
                             FanoutEnvelope
                               .create(
                                 store.identity.devicePrivate,
                                 store.identity.userPublic,
                                 store.identity.devicePublic,
                                 entry.devicePub,
                                 fanout,
                                 message.conversationId,
                                 message.seq,
                                 message.body,
                                 ephemeral.privateKey,
                                 ephemeral.publicKey,
                                 nonce,
                                 order
                               )
                               .left
                               .map(error => new IllegalStateException(error.toString))
                           )
              now       <- IO.realTime.map(_.toMillis)
              lock      <- store.lockGeneration.flatMap {
                             case Left(error)  => IO.raiseError(new IllegalStateException(error.code))
                             case Right(value) => IO.pure(value)
                           }
              receipt    = DeliveryReceipt(
                             conversationId = message.conversationId,
                             authorDevice = message.devicePub,
                             authorSeq = message.seq,
                             recipientDevice = entry.devicePub,
                             logicalKey = key,
                             state = PendingState,
                             createdAt = now,
                             updatedAt = now,
                             attempts = attempts
                           )
              _         <- store.upsertDeliveryReceipt(receipt, lock).flatMap {
                             case Left(error) => IO.raiseError(new IllegalStateException(error.code))
                             case Right(_)    => IO.unit
                           }
              _         <- relay.send(entry.devicePub, RelayPayload.Fanout(envelope.bytes))
            yield ()
        }

  private def loadMessageRetry(store: Store, messageId: Long, attempts: Int): IO[Option[MessageRecord]] =
    store.loadMessageById(messageId).flatMap {
      case Right(message)           => IO.pure(Some(message))
      case Left(_) if attempts <= 0 => IO.pure(None)
      case Left(_)                  => IO.sleep(scala.concurrent.duration.DurationInt(100).millis) *> loadMessageRetry(store, messageId, attempts - 1)
    }

  private def resendOne(store: Store, relay: RelayClient, receipt: DeliveryReceipt): IO[Unit] =
    store.loadMessageByLogicalKey(receipt.logicalKey).flatMap {
      case Left(_)        => IO.unit
      case Right(message) =>
        store.loadConversation(message.conversationId).flatMap {
          case Left(_)             => IO.unit
          case Right(conversation) =>
            store.isBlocked(conversation.peerUserPub).flatMap {
              case true  => IO.unit
              case false =>
                store.loadPeerDeviceList(conversation.peerUserPub).flatMap {
                  case Left(_)     => IO.unit
                  case Right(list) =>
                    list.entries.find(_.devicePub == receipt.recipientDevice) match
                      case None                                     => IO.unit
                      case Some(entry) if entry.revokedAt.isDefined => IO.unit
                      case Some(entry)                              => sendTo(store, relay, message, entry)
                }
            }
        }
    }

  private def randomX25519: IO[X25519KeyPair] =
    IO.delay {
      val bytes = new Array[Byte](32)
      Random.nextBytes(bytes)
      X25519KeyPair
        .fromSeed(X25519Private.from(ByteVector.view(bytes)).fold(_ => throw new IllegalStateException("seed"), identity))
        .fold(_ => throw new IllegalStateException("x25519"), identity)
    }

  private def randomNonce: IO[Nonce] =
    IO.delay {
      val bytes = new Array[Byte](Nonce.Length)
      Random.nextBytes(bytes)
      Nonce.from(ByteVector.view(bytes)).fold(_ => throw new IllegalStateException("nonce"), identity)
    }
