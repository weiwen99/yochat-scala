package yochat.server

import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import cats.effect.unsafe.IORuntime
import cats.effect.IO
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import scodec.bits.ByteVector
import ujson.Value

final class HttpApi private ():

  def routes(service: AppService, hub: EventHub)(using
    materializer: Materializer,
    runtime: IORuntime
  ): Route =
    respondWithHeaders(RawHeader("Content-Security-Policy", HttpApi.SecurityPolicy)) {
      pathPrefix("api") {
        path("events") {
          get {
            handleWebSocketMessages(eventFlow(hub))
          }
        } ~
          path("session") {
            get {
              onComplete(service.sessionJson.unsafeToFuture()) {
                case Success(value) => completeJson(value)
                case Failure(_)     => completeError(StatusCodes.InternalServerError, "internal")
              }
            }
          } ~
          path("state") {
            get {
              completeResult(service.stateJson)
            }
          } ~
          path("unlock") {
            post {
              entity(as[String]) { body =>
                withObject(body) { json =>
                  string(json, "passphrase") match
                    case Right(passphrase) =>
                      service.unlock(passphrase.toCharArray).flatMap {
                        case Right(_)    => service.stateJson
                        case Left(error) => IO.pure(Left(error))
                      }
                    case Left(error)       => IO.pure(Left(error))
                }
              }
            }
          } ~
          path("lock") {
            post {
              completeResult(service.lock.as(Right(ujson.Obj("locked" -> true))))
            }
          } ~
          path("invitations") {
            post {
              entity(as[String]) { body =>
                withObject(body) { json =>
                  liftEither(
                    for
                      label    <- required(json, "channelLabel")(string)
                      account  <- required(json, "channelAccount")(string)
                      expected <- optional(json, "expectedAccount")(string)
                      note     <- optional(json, "note")(string)
                      hours    <- optional(json, "ttlHours")(long)
                    yield service.createInvitation(
                      label,
                      account,
                      expected,
                      math.max(1L, math.min(hours.getOrElse(24L), 168L)) * 3_600_000L,
                      note.getOrElse("")
                    )
                  )
                }
              }
            }
          } ~
          path("invitations" / "receive") {
            post {
              entity(as[String]) { body =>
                withObject(body) { json =>
                  liftEither(
                    for
                      text     <- required(json, "text")(string)
                      expected <- optional(json, "expectedAccount")(string)
                    yield service.receiveInvitation(text, expected)
                  )
                }
              }
            }
          } ~
          path("invitations" / Segment / "accept") { idRaw =>
            post {
              entity(as[String]) { body =>
                id(idRaw) match
                  case Left(error) => completeResult(IO.pure(Left(error)))
                  case Right(id)   =>
                    withObject(body) { json =>
                      liftEither(
                        for
                          label   <- required(json, "channelLabel")(string)
                          account <- required(json, "channelAccount")(string)
                          note    <- optional(json, "note")(string)
                        yield service.acceptInvitation(id, label, account, note.getOrElse(""))
                      )
                    }
              }
            }
          } ~
          path("invitations" / Segment / "reject") { idRaw =>
            post {
              id(idRaw) match
                case Left(error) => completeResult(IO.pure(Left(error)))
                case Right(id)   => completeResult(service.rejectInvitation(id).map(_.map(_ => ujson.Obj("ok" -> true))))
            }
          } ~
          path("contacts" / Segment / "alias") { userRaw =>
            post {
              entity(as[String]) { body =>
                id(userRaw) match
                  case Left(error) => completeResult(IO.pure(Left(error)))
                  case Right(user) =>
                    withObject(body) { json =>
                      optional(json, "alias")(string) match
                        case Left(error)  => IO.pure(Left(error))
                        case Right(alias) => service.setAlias(user, alias).map(_.map(_ => ujson.Obj("ok" -> true)))
                    }
              }
            }
          } ~
          path("contacts" / Segment / "safety") { userRaw =>
            post {
              id(userRaw) match
                case Left(error) => completeResult(IO.pure(Left(error)))
                case Right(user) => completeResult(service.verifySafety(user).map(_.map(_ => ujson.Obj("ok" -> true))))
            }
          } ~
          path("conversations" / Segment / "messages") { conversationRaw =>
            get {
              parameter("limit".as[Int].?) { requested =>
                id(conversationRaw) match
                  case Left(error) => completeResult(IO.pure(Left(error)))
                  case Right(id)   =>
                    completeResult(service.messagesJson(id, requested.fold(200)(value => math.max(1, math.min(value, 1_000)))))
              }
            } ~
              post {
                entity(as[String]) { body =>
                  id(conversationRaw) match
                    case Left(error) => completeResult(IO.pure(Left(error)))
                    case Right(id)   =>
                      withObject(body) { json =>
                        string(json, "body") match
                          case Left(error)                                              => IO.pure(Left(error))
                          case Right(text) if text.trim.isEmpty || text.length > 65_536 =>
                            IO.pure(Left(ServiceError.BadRequest("message-body")))
                          case Right(text)                                              =>
                            service.sendMessage(id, text).map(_.map(messageId => ujson.Obj("messageId" -> messageId)))
                      }
                }
              }
          } ~
          path("rekey" / Segment) { sessionRaw =>
            post {
              id(sessionRaw) match
                case Left(error)      => completeResult(IO.pure(Left(error)))
                case Right(sessionId) => completeResult(service.startRekey(sessionId).map(_.map(_ => ujson.Obj("ok" -> true))))
            }
          }
      } ~
        pathEndOrSingleSlash {
          getFromResource("public/index.html")
        } ~
        getFromResourceDirectory("public")
    }

  private def eventFlow(hub: EventHub)(using materializer: Materializer): Flow[Message, Message, Any] =
    val (queue, source) = Source.queue[String](32, OverflowStrategy.dropHead).preMaterialize()
    hub.register(queue).foreach { id =>
      queue.watchCompletion().onComplete(_ => hub.unregister(id))(using materializer.executionContext)
    }
    queue.offer("""{"type":"state"}""")
    Flow.fromSinkAndSource(Sink.ignore, source.map(TextMessage.Strict.apply))

  private def withObject(body: String)(action: Value => IO[Either[ServiceError, Value]])(using IORuntime): Route =
    readObject(body) match
      case Left(error) => completeResult(IO.pure(Left(error)))
      case Right(json) => completeResult(action(json))

  private def liftEither(value: Either[ServiceError, IO[Either[ServiceError, Value]]]): IO[Either[ServiceError, Value]] =
    value match
      case Left(error) => IO.pure(Left(error))
      case Right(io)   => io

  private def readObject(body: String): Either[ServiceError, Value] =
    try Right(ujson.read(body))
    catch case NonFatal(_) => Left(ServiceError.BadRequest("json"))

  private def completeResult(result: IO[Either[ServiceError, Value]])(using IORuntime): Route =
    onComplete(result.unsafeToFuture()) {
      case Success(Right(value)) => completeJson(value)
      case Success(Left(error))  => completeError(errorStatus(error), error.code)
      case Failure(_)            => completeError(StatusCodes.InternalServerError, "internal")
    }

  private def completeJson(value: Value): Route =
    complete(
      HttpResponse(
        StatusCodes.OK,
        headers = List(RawHeader("Cache-Control", "no-store")),
        entity = HttpEntity(ContentTypes.`application/json`, ujson.write(value))
      )
    )

  private def completeError(status: StatusCode, code: String): Route =
    complete(
      HttpResponse(
        status,
        headers = List(RawHeader("Cache-Control", "no-store")),
        entity = HttpEntity(ContentTypes.`application/json`, ujson.write(ujson.Obj("error" -> code)))
      )
    )

  private def errorStatus(error: ServiceError): StatusCode =
    error match
      case ServiceError.Locked | ServiceError.UnlockFailed | ServiceError.SessionUnavailable => StatusCodes.Unauthorized
      case ServiceError.WeakPassphrase | ServiceError.BadRequest(_)                          => StatusCodes.BadRequest
      case ServiceError.AlreadyUnlocked                                                      => StatusCodes.Conflict
      case ServiceError.NotFound                                                             => StatusCodes.NotFound
      case ServiceError.StoreFailed(_) | ServiceError.RuntimeFailed(_)                       => StatusCodes.InternalServerError

  private def id(value: String): Either[ServiceError, ByteVector] =
    ByteVector.fromHex(value) match
      case Some(bytes) if bytes.nonEmpty => Right(bytes)
      case _                             => Left(ServiceError.BadRequest("id"))

  private def required[A](json: Value, name: String)(read: (Value, String) => Either[ServiceError, A]): Either[ServiceError, A] =
    read(json, name)

  private def optional[A](json: Value, name: String)(read: (Value, String) => Either[ServiceError, A]): Either[ServiceError, Option[A]] =
    field(json, name) match
      case None | Some(ujson.Null) => Right(None)
      case Some(_)                 => read(json, name).map(Some(_))

  private def string(json: Value, name: String): Either[ServiceError, String] =
    field(json, name) match
      case Some(ujson.Str(value)) => Right(value)
      case _                      => Left(ServiceError.BadRequest(name))

  private def long(json: Value, name: String): Either[ServiceError, Long] =
    field(json, name) match
      case Some(ujson.Num(value)) if value.isWhole => Right(value.toLong)
      case _                                       => Left(ServiceError.BadRequest(name))

  private def field(json: Value, name: String): Option[Value] =
    json match
      case ujson.Obj(values) => values.collectFirst { case (key, value) if key == name => value }
      case _                 => None

object HttpApi:

  private val SecurityPolicy: String =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; font-src 'self'"

  def start(service: AppService, hub: EventHub, config: ServiceConfig)(using
    system: ActorSystem,
    materializer: Materializer,
    runtime: IORuntime
  ): IO[ServerBinding] =
    val api = new HttpApi
    IO.fromFuture(
      IO(
        Http()
          .newServerAt("127.0.0.1", config.port)
          .bind(api.routes(service, hub))
      )
    )
