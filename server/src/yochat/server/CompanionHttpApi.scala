package yochat.server

import scala.util.{Failure, Success}

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
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import scodec.bits.ByteVector
import ujson.Value

/** Minimal read-only HTTP surface for A2. It deliberately omits mutations, invitations, sends, and device administration. */
final class CompanionHttpApi private ():

  def routes(service: CompanionService)(using materializer: Materializer, runtime: IORuntime): Route =
    respondWithHeaders(RawHeader("Content-Security-Policy", CompanionHttpApi.SecurityPolicy)) {
      pathPrefix("api") {
        path("events") {
          get {
            handleWebSocketMessages(eventFlow)
          }
        } ~
          path("session") {
            get {
              completeResult(service.sessionJson)
            }
          } ~
          path("state") {
            get {
              completeResult(service.stateJson)
            }
          } ~
          path("conversations" / Segment / "messages") { conversationRaw =>
            get {
              parameter("limit".as[Int].?) { requested =>
                ByteVector.fromHex(conversationRaw) match
                  case Some(id) if id.nonEmpty =>
                    completeResult(
                      service.messagesJson(id, requested.fold(200)(value => math.max(1, math.min(value, 1_000))))
                    )
                  case _                       => completeError(StatusCodes.BadRequest, "id")
              }
            }
          }
      } ~
        pathEndOrSingleSlash {
          getFromResource("public/index.html")
        } ~
        getFromResourceDirectory("public")
    }

  private def eventFlow(using materializer: Materializer): Flow[Message, Message, Any] =
    val (queue, source) = Source.queue[String](8, OverflowStrategy.dropHead).preMaterialize()
    queue.offer("""{"type":"state"}""")
    Flow.fromSinkAndSource(Sink.ignore, source.map(TextMessage.Strict.apply))

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
      case ServiceError.NotFound       => StatusCodes.NotFound
      case ServiceError.StoreFailed(_) => StatusCodes.InternalServerError
      case _                           => StatusCodes.BadRequest

object CompanionHttpApi:

  private val SecurityPolicy: String =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' ws: wss:; font-src 'self'"

  def start(service: CompanionService, config: ServiceConfig)(using
    system: ActorSystem,
    materializer: Materializer,
    runtime: IORuntime
  ): IO[ServerBinding] =
    val api = new CompanionHttpApi
    IO.fromFuture(
      IO(
        Http()
          .newServerAt("127.0.0.1", config.port)
          .bind(api.routes(service))
      )
    )
