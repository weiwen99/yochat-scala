package yochat.server

import scala.util.{Failure, Success}

import cats.effect.unsafe.IORuntime
import cats.effect.IO
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import ujson.Value

/** Small browser surface for A2 before it has a device authorization. It has no access to contacts or messages. */
final class CompanionPairingHttpApi private ():

  def routes(service: CompanionPairingService)(using runtime: IORuntime): Route =
    respondWithHeaders(RawHeader("Content-Security-Policy", CompanionPairingHttpApi.SecurityPolicy)) {
      pathPrefix("api" / "pair") {
        path("status") {
          get {
            completeResult(service.status.map(Right(_)))
          }
        } ~
          path("accept") {
            post {
              entity(as[String]) { body =>
                withObject(body) { json =>
                  string(json, "authCode") match
                    case Left(error)                      => IO.pure(Left(ServiceError.BadRequest(error)))
                    case Right(code) if code.trim.isEmpty => IO.pure(Left(ServiceError.BadRequest("auth-code")))
                    case Right(code)                      =>
                      service.accept(code).map {
                        case Left(error) => Left(ServiceError.BadRequest(error))
                        case Right(_)    => Right(ujson.Obj("paired" -> true))
                      }
                }
              }
            }
          }
      } ~
        pathEndOrSingleSlash {
          getFromResource("public/pair.html")
        } ~
        getFromResourceDirectory("public")
    }

  private def withObject(body: String)(action: Value => IO[Either[ServiceError, Value]])(using IORuntime): Route =
    try completeResult(action(ujson.read(body)))
    catch case _: Exception => completeError(StatusCodes.BadRequest, "json")

  private def completeResult(result: IO[Either[ServiceError, Value]])(using IORuntime): Route =
    onComplete(result.unsafeToFuture()) {
      case Success(Right(value)) => completeJson(value)
      case Success(Left(error))  => completeError(StatusCodes.BadRequest, error.code)
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

  private def string(json: Value, name: String): Either[String, String] =
    json.obj.get(name).collect { case ujson.Str(value) => value }.toRight(name)

object CompanionPairingHttpApi:

  private val SecurityPolicy: String =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'"

  def start(service: CompanionPairingService, config: ServiceConfig)(using
    system: ActorSystem,
    runtime: IORuntime
  ): IO[ServerBinding] =
    IO.fromFuture(
      IO(Http().newServerAt("127.0.0.1", config.port).bind(new CompanionPairingHttpApi().routes(service)))
    )
