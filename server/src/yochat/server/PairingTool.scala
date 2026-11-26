package yochat.server

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

import cats.effect.{ExitCode, IO}
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Public, Key32}
import yochat.store.{DeviceRecord, DeviceRole, DeviceSyncRecord, KeySource, Store, StoreConfig}

/** A2-side manual pairing. Request and accept are separate processes so the two codes can travel through any existing channel. The pending root
  * is kept in a 0600 file until the auth code is accepted; it never leaves A2.
  */
object PairingTool:

  private val PendingFile = "pairing.json"

  def run(args: List[String]): IO[Option[ExitCode]] =
    args match
      case "pair-request" :: tail => request(parse(tail)).map(Some(_))
      case "pair-accept" :: tail  => accept(parse(tail)).map(Some(_))
      case _                      => IO.pure(None)

  final private case class Options(
    dataDir: Path,
    userPub: Option[String],
    passphrase: Option[Array[Char]],
    inviteFile: Option[Path],
    authFile: Option[Path],
    name: String
  )

  private def parse(args: List[String]): Either[String, Options] =
    def loop(
      remaining: List[String],
      dataDir: Path,
      userPub: Option[String],
      passphrase: Option[Array[Char]],
      inviteFile: Option[Path],
      authFile: Option[Path],
      name: String
    ): Either[String, Options] =
      remaining match
        case Nil                             => Right(Options(dataDir, userPub, passphrase, inviteFile, authFile, name))
        case "--data-dir" :: value :: tail   => loop(tail, Path.of(value), userPub, passphrase, inviteFile, authFile, name)
        case "--user" :: value :: tail       => loop(tail, dataDir, Some(value), passphrase, inviteFile, authFile, name)
        case "--passphrase" :: value :: tail => loop(tail, dataDir, userPub, Some(value.toCharArray), inviteFile, authFile, name)
        case "--invite" :: value :: tail     => loop(tail, dataDir, userPub, passphrase, Some(Path.of(value)), authFile, name)
        case "--auth" :: value :: tail       => loop(tail, dataDir, userPub, passphrase, inviteFile, Some(Path.of(value)), name)
        case "--name" :: value :: tail       => loop(tail, dataDir, userPub, passphrase, inviteFile, authFile, value)
        case unknown :: _                    => Left(s"unknown pair argument: $unknown")
    loop(args, Path.of(System.getProperty("user.home"), ".yochat"), None, None, None, None, "companion")

  private def request(options: Either[String, Options]): IO[ExitCode] =
    options match
      case Left(error)  => IO.println(s"yochat: $error").as(ExitCode.Error)
      case Right(value) =>
        val user = value.userPub.toRight("--user is required").flatMap(parsePublic)
        (user, value.passphrase.toRight("--passphrase is required"), value.inviteFile.toRight("--invite is required")) match
          case (Right(userPub), Right(passphrase), Right(inviteFile)) =>
            readText(inviteFile).flatMap { invite =>
              Store
                .openCompanion(companionConfig(value.dataDir, passphrase), userPub)
                .use {
                  case Left(error)  => IO.println(s"yochat: ${error.code}").as(ExitCode.Error)
                  case Right(store) =>
                    DevicePairing
                      .request(invite.trim, store.identity.devicePrivate, store.identity.devicePublic, value.name)
                      .flatMap {
                        case Left(error)                              => IO.println(s"yochat: $error").as(ExitCode.Error)
                        case Right((requestCode, root, confirmation)) =>
                          val pending = ujson.Obj(
                            "userPub"      -> userPub.bytes.toHex,
                            "devicePub"    -> store.identity.devicePublic.bytes.toHex,
                            "root"         -> root.bytes.toHex,
                            "requestCode"  -> requestCode,
                            "name"         -> value.name,
                            "confirmation" -> confirmation.code,
                            "fingerprint"  -> confirmation.fingerprint
                          )
                          writePending(value.dataDir, pending) *>
                            IO.blocking(
                              Console.err.println(
                                s"A2 confirmation: ${confirmation.code}\nA2 fingerprint:  ${confirmation.fingerprint}\nPaste the JSON request code into A1."
                              )
                            ) *>
                            IO.println(requestCode).as(ExitCode.Success)
                      }
                }
            }
          case (Left(error), _, _)                                    => IO.println(s"yochat: $error").as(ExitCode.Error)
          case (_, Left(error), _)                                    => IO.println(s"yochat: $error").as(ExitCode.Error)
          case (_, _, Left(error))                                    => IO.println(s"yochat: $error").as(ExitCode.Error)

  private def accept(options: Either[String, Options]): IO[ExitCode] =
    options match
      case Left(error)  => IO.println(s"yochat: $error").as(ExitCode.Error)
      case Right(value) =>
        (value.passphrase.toRight("--passphrase is required"), value.authFile.toRight("--auth is required")) match
          case (Right(passphrase), Right(authFile)) =>
            readPending(value.dataDir).flatMap { pending =>
              readText(authFile).flatMap { authCode =>
                (pending, storeFromPending(value.dataDir, passphrase, pending)) match
                  case (Right(json), Right(pendingStore)) =>
                    val userRaw = json.obj.get("userPub").collect { case ujson.Str(v) => v }.toRight("pending-user")
                    val rootRaw = json.obj.get("root").collect { case ujson.Str(v) => v }.toRight("pending-root")
                    (userRaw, rootRaw) match
                      case (Right(userHex), Right(rootHex)) =>
                        parsePublic(userHex) match
                          case Left(error)    => IO.println(s"yochat: $error").as(ExitCode.Error)
                          case Right(userPub) =>
                            Key32.from(ByteVector.fromValidHex(rootHex)) match
                              case Left(_)     => IO.println("yochat: pending-root").as(ExitCode.Error)
                              case Right(root) =>
                                pendingStore.use {
                                  case Left(error)  => IO.println(s"yochat: ${error.code}").as(ExitCode.Error)
                                  case Right(store) =>
                                    DevicePairing.accept(authCode.trim, userPub, store.identity.devicePublic) match
                                      case Left(error)                                                         => IO.println(s"yochat: $error").as(ExitCode.Error)
                                      case Right(auth) if Crypto.sha256(root.bytes).bytes != auth.syncRootHash =>
                                        IO.println("yochat: sync-root").as(ExitCode.Error)
                                      case Right(auth)                                                         => persist(store, value.dataDir, json, authCode.trim, auth, root).as(ExitCode.Success)
                                }
                      case _                                => IO.println("yochat: pending-file").as(ExitCode.Error)
                  case (Left(error), _)                   => IO.println(s"yochat: $error").as(ExitCode.Error)
                  case (_, Left(error))                   => IO.println(s"yochat: $error").as(ExitCode.Error)
              }
            }
          case (Left(error), _)                     => IO.println(s"yochat: $error").as(ExitCode.Error)
          case (_, Left(error))                     => IO.println(s"yochat: $error").as(ExitCode.Error)

  private def persist(
    store: Store,
    dataDir: Path,
    pending: Value,
    authCode: String,
    auth: yochat.core.s2.DeviceAuthorization,
    root: Key32
  ): IO[Unit] =
    val requestHash = pending.obj.get("requestCode").collect { case ujson.Str(value) =>
      Crypto.sha256(ByteVector.view(value.getBytes(StandardCharsets.UTF_8))).bytes
    }
    val record      = DeviceRecord(
      devicePub = store.identity.devicePublic,
      userPub = auth.userPub,
      role = DeviceRole.Companion,
      name = pending.obj.get("name").collect { case ujson.Str(value) => value }.getOrElse("companion"),
      authorization = ByteVector.view(authCode.getBytes(StandardCharsets.UTF_8)),
      version = 0L,
      createdAt = 0L,
      revokedAt = None,
      joinEpoch = auth.joinEpoch,
      joinManifest = auth.joinManifest.canonical,
      joinManifestHash = auth.joinManifestHash,
      joinCursorSyncSeq = auth.joinCursorSyncSeq,
      pairingNonce = None,
      requestHash = requestHash
    )
    val sync        = DeviceSyncRecord(
      peerDevicePub = auth.primaryDevicePub,
      syncRoot = root.bytes,
      lastAckedSyncSeq = 0L,
      lastBatchSeq = 0L,
      lastBatchHash = ByteVector.fill(32)(0),
      version = 0L,
      updatedAt = 0L
    )
    for
      lock <- store.lockGeneration
      _    <- lock match
                case Left(error)  => IO.println(s"yochat: ${error.code}")
                case Right(value) =>
                  store.insertDevice(record, value).flatMap {
                    case Left(error) => IO.println(s"yochat: ${error.code}")
                    case Right(_)    =>
                      store.insertDeviceSync(sync, value).flatMap {
                        case Left(error) => IO.println(s"yochat: ${error.code}")
                        case Right(_)    =>
                          IO.blocking(Files.deleteIfExists(dataDir.resolve(PendingFile))).void *>
                            IO.println("paired")
                      }
                  }
    yield ()

  private def storeFromPending(
    dataDir: Path,
    passphrase: Array[Char],
    pending: Either[String, Value]
  ): Either[String, cats.effect.Resource[IO, Either[yochat.store.StorageError, Store]]] =
    pending.flatMap { json =>
      json.obj.get("userPub").collect { case ujson.Str(value) => value }.toRight("pending-user") match
        case Left(error) => Left(error)
        case Right(raw)  =>
          parsePublic(raw).map(user => Store.openCompanion(companionConfig(dataDir, passphrase), user)).left.map(_.toString)
    }

  private def companionConfig(directory: Path, passphrase: Array[Char]): StoreConfig =
    StoreConfig.default(directory, KeySource.KeyFile(passphrase))

  private def parsePublic(value: String): Either[String, Ed25519Public] =
    ByteVector.fromHex(value).flatMap(Ed25519Public.from(_).toOption).toRight("user-pub")

  private def readText(path: Path): IO[String] =
    IO.blocking(Files.readString(path, StandardCharsets.UTF_8))

  private def readPending(directory: Path): IO[Either[String, Value]] =
    IO.blocking {
      try Right(ujson.read(Files.readString(directory.resolve(PendingFile), StandardCharsets.UTF_8)))
      catch case _: Exception => Left("pending-file")
    }

  private def writePending(directory: Path, value: Value): IO[Unit] =
    IO.blocking {
      val target = directory.resolve(PendingFile)
      Files.writeString(target, ujson.write(value), StandardCharsets.UTF_8)
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
      ()
    }
