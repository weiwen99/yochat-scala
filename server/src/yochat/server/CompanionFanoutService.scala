package yochat.server

import cats.effect.{IO, Ref}
import scodec.bits.ByteVector

import yochat.core.{Ed25519Public, X25519Private}
import yochat.core.s2.{FanoutAck, FanoutEnvelope, LogicalKey, OrderStamp, PeerDeviceList, PeerDeviceRole}
import yochat.store.{CompanionMessageRecord, MessageState, Store}
import yochat.transport.{RelayClient, RelayPayload}

/** A2's direct receive path. It is strictly read-only: a verified envelope produces one display row and one device-signed ACK, never a business
  * send or a peer session.
  */
final class CompanionFanoutService(
  store: Store,
  relay: RelayClient,
  fanoutPrivate: X25519Private,
  authorization: Ref[IO, Set[Ed25519Public]],
  conversations: Ref[IO, Map[Ed25519Public, ByteVector]]
):

  def receive(bytes: ByteVector): IO[Either[String, CompanionMessageRecord]] =
    FanoutEnvelope.fromBytes(bytes) match
      case Left(_)      => IO.pure(Left("envelope"))
      case Right(value) =>
        if value.recipientDevice != store.identity.devicePublic then IO.pure(Left("recipient"))
        else
          store.isBlocked(value.senderUser).flatMap {
            case true  => IO.pure(Left("blocked"))
            case false =>
              authorize(value.senderUser, value.senderDevice).flatMap {
                case Left(error) => IO.pure(Left(error))
                case Right(_)    =>
                  FanoutEnvelope.open(fanoutPrivate, store.identity.devicePublic, value) match
                    case Left(_)     => IO.pure(Left("open"))
                    case Right(body) =>
                      localConversation(value.senderUser).flatMap {
                        case Left(error)           => IO.pure(Left(error))
                        case Right(conversationId) =>
                          val key = LogicalKey(conversationId, value.senderDevice, value.authorSeq)
                          IO.realTime.flatMap { time =>
                            val record = CompanionMessageRecord(
                              sourceId = 0L,
                              conversationId = conversationId,
                              authorPub = value.senderUser,
                              devicePub = value.senderDevice,
                              seq = value.authorSeq,
                              body = body,
                              state = MessageState.Received,
                              createdAt = time.toMillis,
                              syncSeq = 0L,
                              logicalKey = Some(key)
                            )
                            store.upsertCompanionMessage(record).flatMap {
                              case Left(error)   => IO.pure(Left(error.code))
                              case Right(stored) =>
                                val stamp = value.order.getOrElse(OrderStamp.legacy(value.authorSeq))
                                store.lockGeneration.flatMap {
                                  case Left(error) => IO.pure(Left(error.code))
                                  case Right(lock) =>
                                    store.upsertMessageOrder(key, stamp, time.toMillis, lock).flatMap {
                                      case Left(error) => IO.pure(Left(error.code))
                                      case Right(_)    => acknowledge(value, time.toMillis).attempt.as(Right(stored.record))
                                    }
                                }
                            }
                          }
                      }
              }
          }

  /** B1 uses its own local conversation id. A2's visible conversation is the one A1 already synchronized for the same peer. */
  private def localConversation(senderUser: Ed25519Public): IO[Either[String, ByteVector]] =
    conversations.get.flatMap { cached => // scalafix:ok DisableSyntax.no-unsafe-get
      cached.get(senderUser) match
        case Some(value) => IO.pure(Right(value))
        case None        =>
          store.listConversations
            .map {
              case Left(_)       => Left("conversation")
              case Right(values) =>
                values.find(_.peerUserPub == senderUser).map(_.id).toRight("conversation")
            }
            .flatMap {
              case Right(value) => conversations.update(_ + (senderUser -> value)).as(Right(value))
              case Left(error)  => IO.pure(Left(error))
            }
    }

  def clearAuthorization: IO[Unit] = authorization.set(Set.empty)

  private def authorize(
    senderUser: Ed25519Public,
    senderDevice: Ed25519Public
  ): IO[Either[String, Unit]] =
    authorization.get.flatMap { cached => // scalafix:ok DisableSyntax.no-unsafe-get
      if cached.contains(senderDevice) then IO.pure(Right(()))
      else
        store.loadContact(senderUser).flatMap {
          case Left(_)  => IO.pure(Left("not-a-contact"))
          case Right(_) =>
            store
              .loadPeerDeviceList(senderUser)
              .map {
                case Left(_)     => Left("device-list")
                case Right(list) =>
                  if (list.features & PeerDeviceList.FeatureS2b) == 0L then Left("s2b-disabled")
                  else
                    list.entries.find(entry => entry.devicePub == senderDevice) match
                      case Some(entry) if entry.role == PeerDeviceRole.Primary && entry.revokedAt.isEmpty => Right(())
                      case _                                                                              => Left("not-authorized")
              }
              .flatMap {
                case Right(_)    => authorization.update(_ + senderDevice).as(Right(()))
                case Left(error) => IO.pure(Left(error))
              }
        }
    }

  private def acknowledge(value: FanoutEnvelope, now: Long): IO[Unit] =
    FanoutAck
      .create(
        store.identity.devicePrivate,
        store.identity.devicePublic,
        value.senderDevice,
        value.conversationId,
        value.senderDevice,
        value.authorSeq,
        now
      ) match
      case Left(_)    => IO.unit
      case Right(ack) => relay.send(value.senderDevice, RelayPayload.FanoutAck(ack.bytes)).attempt.void
