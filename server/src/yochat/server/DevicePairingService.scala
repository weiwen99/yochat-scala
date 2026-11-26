package yochat.server

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

import cats.effect.{IO, Ref}
import scodec.bits.ByteVector
import ujson.Value

import yochat.core.{Crypto, Ed25519Private, Ed25519Public, Key32, X25519KeyPair, X25519Private, X25519Public}
import yochat.core.s2.{DeviceAuthorization, JoinManifest, PairingInvite, PairingRequest}
import yochat.store.{DeviceRecord, DeviceRole, DeviceSyncRecord, Store}

/** Two-code manual pairing for S2-A: A1 emits an invite, A2 emits a request, A1 emits an auth code. The request/auth codes can be copied
  * through any existing channel; no pairing traffic uses the relay.
  */
final class DevicePairingService(
  store: Store,
  pending: Ref[IO, Map[String, DevicePairingService.Pending]]
):

  def createInvite(nowMs: Long, ttlMs: Long): IO[Either[String, String]] =
    store.identity.userPrivate match
      case None          => IO.pure(Left("primary-user-key"))
      case Some(userKey) =>
        for
          ephemeral <- DevicePairing.randomX25519
          nonce     <- DevicePairing.randomBytes(16)
          expires    = nowMs + ttlMs
          invite    <- IO.fromEither(
                         PairingInvite
                           .create(
                             userKey,
                             store.identity.userPublic,
                             store.identity.devicePublic,
                             ephemeral.publicKey,
                             nonce,
                             expires
                           )
                           .left
                           .map(_ => new IllegalArgumentException("pairing-invite"))
                       )
          _         <- pending.update(values =>
                         values.updated(
                           nonce.toHex,
                           DevicePairingService.Pending(invite, ephemeral.privateKey, expires)
                         )
                       )
        yield Right(DevicePairing.encodeInvite(invite))

  def inspect(requestCode: String, nowMs: Long): IO[Either[String, Value]] =
    DevicePairing.decodeRequest(requestCode) match
      case Left(error)    => IO.pure(Left(error))
      case Right(request) =>
        pending.get.flatMap { values => // scalafix:ok DisableSyntax.no-unsafe-get
          values.get(request.pairingNonce.toHex) match
            case None                                    => IO.pure(Left("pairing-expired"))
            case Some(value) if nowMs >= value.expiresAt => IO.pure(Left("pairing-expired"))
            case Some(value)                             =>
              val confirmation = DevicePairing.confirmation(request)
              IO.pure(
                Right(
                  ujson.Obj(
                    "devicePub"    -> request.companionDevicePub.bytes.toHex,
                    "name"         -> new String(request.encryptedName.toArray, StandardCharsets.UTF_8),
                    "fingerprint"  -> confirmation.fingerprint,
                    "confirmation" -> confirmation.code,
                    "expiresAt"    -> ujson.Num(value.expiresAt.toDouble)
                  )
                )
              )
        }

  def confirm(requestCode: String, expectedConfirmation: String, nowMs: Long): IO[Either[String, String]] =
    store.identity.userPrivate match
      case None          => IO.pure(Left("primary-user-key"))
      case Some(userKey) =>
        DevicePairing.decodeRequest(requestCode) match
          case Left(error)                                                                        => IO.pure(Left(error))
          case Right(request) if DevicePairing.confirmation(request).code != expectedConfirmation =>
            IO.pure(Left("confirmation"))
          case Right(request)                                                                     =>
            pending.get.flatMap { values => // scalafix:ok DisableSyntax.no-unsafe-get
              values.get(request.pairingNonce.toHex) match
                case None                                    => IO.pure(Left("pairing-expired"))
                case Some(value) if nowMs >= value.expiresAt => IO.pure(Left("pairing-expired"))
                case Some(value)                             =>
                  for
                    cursor   <- store.maxMessageSyncSeq
                    manifest <- store.joinManifest
                    result   <- (cursor, manifest) match
                                  case (Left(error), _)             => IO.pure(Left(error.code))
                                  case (_, Left(error))             => IO.pure(Left(error.code))
                                  case (Right(maxId), Right(entry)) =>
                                    DevicePairing
                                      .authorize(
                                        value.invite,
                                        value.ephemeralPrivate,
                                        request,
                                        userKey,
                                        store.identity.userPublic,
                                        store.identity.devicePublic,
                                        maxId,
                                        entry,
                                        nowMs
                                      ) match
                                      case Left(error)                   => IO.pure(Left(error))
                                      case Right((authCode, auth, root)) =>
                                        for
                                          lock  <- store.lockGeneration
                                          saved <- lock match
                                                     case Left(error)      => IO.pure(Left(error.code))
                                                     case Right(lockValue) =>
                                                       persist(request, authCode, auth, root, lockValue)
                                          _     <- saved match
                                                     case Left(_)  => IO.unit
                                                     case Right(_) =>
                                                       pending.update(values => values - request.pairingNonce.toHex)
                                        yield saved
                  yield result
            }

  def listDevicesJson: IO[Either[String, Value]] =
    store.listDevices.map {
      case Left(error)   => Left(error.code)
      case Right(values) =>
        Right(
          ujson.Arr(
            values.map { value =>
              ujson.Obj(
                "devicePub"   -> value.devicePub.bytes.toHex,
                "role"        -> (if value.role == DeviceRole.Primary then "primary" else "companion"),
                "name"        -> value.name,
                "version"     -> ujson.Num(value.version.toDouble),
                "revokedAt"   -> value.revokedAt.map(number => ujson.Num(number.toDouble)).getOrElse(ujson.Null),
                "joinEpoch"   -> ujson.Num(value.joinEpoch.toDouble),
                "lastSyncSeq" -> ujson.Num(value.joinCursorSyncSeq.toDouble)
              )
            }*
          )
        )
    }

  def revoke(deviceRaw: String): IO[Either[String, Unit]] =
    ByteVector.fromHex(deviceRaw).flatMap(Ed25519Public.from(_).toOption) match
      case None         => IO.pure(Left("device"))
      case Some(device) =>
        for
          loaded <- store.loadDevice(device)
          result <- loaded match
                      case Left(error)                                      => IO.pure(Left(error.code))
                      case Right(value) if value.role == DeviceRole.Primary => IO.pure(Left("primary-device"))
                      case Right(value)                                     =>
                        for
                          lock    <- store.lockGeneration
                          revoked <- lock match
                                       case Left(error)      => IO.pure(Left(error.code))
                                       case Right(lockValue) =>
                                         store.revokeDevice(device, value.version, lockValue).map(_.left.map(_.code))
                          _       <- store.loadDeviceSync(device).flatMap {
                                       case Right(sync) =>
                                         lock match
                                           case Left(_)          => IO.unit
                                           case Right(lockValue) =>
                                             store.deleteDeviceSync(device, sync.version, lockValue).void
                                       case Left(_)     => IO.unit
                                     }
                        yield revoked.map(_ => ())
        yield result

  private def persist(
    request: PairingRequest,
    authCode: String,
    auth: DeviceAuthorization,
    root: Key32,
    lock: Long
  ): IO[Either[String, String]] =
    val name   = new String(request.encryptedName.toArray, StandardCharsets.UTF_8)
    val record = DeviceRecord(
      devicePub = request.companionDevicePub,
      userPub = store.identity.userPublic,
      role = DeviceRole.Companion,
      name = name,
      authorization = ByteVector.view(authCode.getBytes(StandardCharsets.UTF_8)),
      version = 0L,
      createdAt = 0L,
      revokedAt = None,
      joinEpoch = auth.joinEpoch,
      joinManifest = auth.joinManifest.canonical,
      joinManifestHash = auth.joinManifestHash,
      joinCursorSyncSeq = auth.joinCursorSyncSeq,
      pairingNonce = Some(request.pairingNonce),
      requestHash = Some(request.requestHash),
      fanoutPub = Some(request.companionEphemeralPub)
    )
    for
      created <- store.insertDevice(record, lock)
      result  <- created match
                   case Left(error) => IO.pure(Left(error.code))
                   case Right(_)    =>
                     val sync = DeviceSyncRecord(
                       peerDevicePub = request.companionDevicePub,
                       syncRoot = root.bytes,
                       lastAckedSyncSeq = auth.joinCursorSyncSeq,
                       lastBatchSeq = 0L,
                       lastBatchHash = ByteVector.fill(32)(0),
                       version = 0L,
                       updatedAt = 0L
                     )
                     store.insertDeviceSync(sync, lock).map(_.map(_ => authCode).left.map(_.code))
    yield result

object DevicePairingService:

  final case class Pending(
    invite: PairingInvite,
    ephemeralPrivate: X25519Private,
    expiresAt: Long
  )

  def create(store: Store): IO[DevicePairingService] =
    Ref.of[IO, Map[String, Pending]](Map.empty).map(values => new DevicePairingService(store, values))

private object DevicePairing:

  final case class Confirmation(code: String, fingerprint: String)

  private val Random             = new SecureRandom()
  private val ConfirmationDomain = Crypto.ascii("yochat-s2-confirmation")

  def confirmation(request: PairingRequest): Confirmation =
    val codeBytes = Crypto.sha256(ConfirmationDomain ++ request.pairingNonce ++ request.companionDevicePub.bytes).bytes.take(4)
    Confirmation(grouped(codeBytes), fingerprint(request.companionDevicePub))

  def fingerprint(device: Ed25519Public): String =
    grouped(Crypto.sha256(device.bytes).bytes.take(8))

  private def grouped(value: ByteVector): String = value.toHex.toUpperCase.grouped(4).mkString("-")

  def encodeInvite(invite: PairingInvite): String =
    ujson
      .Obj(
        "kind"             -> "s2-invite",
        "version"          -> 1,
        "userPub"          -> invite.userPub.bytes.toHex,
        "primaryDevicePub" -> invite.primaryDevicePub.bytes.toHex,
        "ephemeralPub"     -> invite.ephemeralPub.bytes.toHex,
        "nonce"            -> invite.nonce.toHex,
        "expiresAt"        -> ujson.Num(invite.expiresAt.toDouble),
        "signature"        -> invite.signature.bytes.toHex
      )
      .render()

  def decodeInvite(value: String): Either[String, PairingInvite] =
    parse(value).flatMap { json =>
      for
        _         <- kind(json, "s2-invite")
        user      <- hex(json, "userPub", 32).flatMap(Ed25519Public.from(_).left.map(_ => "userPub"))
        primary   <- hex(json, "primaryDevicePub", 32).flatMap(Ed25519Public.from(_).left.map(_ => "primaryDevicePub"))
        ephemeral <- hex(json, "ephemeralPub", 32).flatMap(X25519Public.from(_).left.map(_ => "ephemeralPub"))
        nonce     <- hex(json, "nonce", 16)
        expires   <- number(json, "expiresAt")
        signature <- hex(json, "signature", 64).flatMap(yochat.core.Signature.from(_).left.map(_ => "signature"))
        invite     = PairingInvite(user, primary, ephemeral, nonce, expires, signature)
        _         <- if invite.verify then Right(()) else Left("invite-signature")
      yield invite
    }

  def encodeRequest(request: PairingRequest): String =
    ujson
      .Obj(
        "kind"                  -> "s2-request",
        "version"               -> 1,
        "pairingNonce"          -> request.pairingNonce.toHex,
        "companionDevicePub"    -> request.companionDevicePub.bytes.toHex,
        "companionEphemeralPub" -> request.companionEphemeralPub.bytes.toHex,
        "encryptedName"         -> request.encryptedName.toHex,
        "proof"                 -> request.proof.bytes.toHex
      )
      .render()

  def decodeRequest(value: String): Either[String, PairingRequest] =
    parse(value).flatMap { json =>
      for
        _         <- kind(json, "s2-request")
        nonce     <- hex(json, "pairingNonce", 16)
        device    <- hex(json, "companionDevicePub", 32).flatMap(Ed25519Public.from(_).left.map(_ => "companionDevicePub"))
        ephemeral <- hex(json, "companionEphemeralPub", 32).flatMap(X25519Public.from(_).left.map(_ => "companionEphemeralPub"))
        name      <- hexAny(json, "encryptedName")
        proof     <- hex(json, "proof", 64).flatMap(yochat.core.Signature.from(_).left.map(_ => "proof"))
        request    = PairingRequest(nonce, device, ephemeral, name, proof)
        _         <- if request.verify then Right(()) else Left("request-proof")
      yield request
    }

  def encodeAuth(auth: DeviceAuthorization): String =
    ujson
      .Obj(
        "kind"               -> "s2-auth",
        "version"            -> 1,
        "userPub"            -> auth.userPub.bytes.toHex,
        "primaryDevicePub"   -> auth.primaryDevicePub.bytes.toHex,
        "companionDevicePub" -> auth.companionDevicePub.bytes.toHex,
        "joinEpoch"          -> ujson.Num(auth.joinEpoch.toDouble),
        "joinManifest"       -> auth.joinManifest.canonical.toHex,
        "joinCursorSyncSeq"  -> ujson.Num(auth.joinCursorSyncSeq.toDouble),
        "syncRootHash"       -> auth.syncRootHash.toHex,
        "issuedAt"           -> ujson.Num(auth.issuedAt.toDouble),
        "signature"          -> auth.signature.bytes.toHex
      )
      .render()

  def decodeAuth(value: String): Either[String, DeviceAuthorization] =
    parse(value).flatMap { json =>
      for
        _         <- kind(json, "s2-auth")
        user      <- hex(json, "userPub", 32).flatMap(Ed25519Public.from(_).left.map(_ => "userPub"))
        primary   <- hex(json, "primaryDevicePub", 32).flatMap(Ed25519Public.from(_).left.map(_ => "primaryDevicePub"))
        companion <- hex(json, "companionDevicePub", 32).flatMap(Ed25519Public.from(_).left.map(_ => "companionDevicePub"))
        epoch     <- number(json, "joinEpoch")
        manifest  <- hexAny(json, "joinManifest").flatMap(JoinManifest.fromCanonical(_).left.map(_ => "joinManifest"))
        cursor    <- number(json, "joinCursorSyncSeq")
        rootHash  <- hex(json, "syncRootHash", 32)
        issued    <- number(json, "issuedAt")
        signature <- hex(json, "signature", 64).flatMap(yochat.core.Signature.from(_).left.map(_ => "signature"))
        auth       = DeviceAuthorization(user, primary, companion, epoch, manifest, cursor, rootHash, issued, signature)
        _         <- if auth.verify then Right(()) else Left("auth-signature")
      yield auth
    }

  def authorize(
    invite: PairingInvite,
    inviteEphemeralPrivate: X25519Private,
    request: PairingRequest,
    userPrivate: Ed25519Private,
    userPub: Ed25519Public,
    primaryDevicePub: Ed25519Public,
    cursor: Long,
    joinManifest: JoinManifest,
    nowMs: Long
  ): Either[String, (String, DeviceAuthorization, Key32)] =
    for
      shared <- Crypto.agree(inviteEphemeralPrivate, request.companionEphemeralPub).left.map(_ => "shared")
      root   <- deriveRoot(shared, invite.nonce)
      auth   <- DeviceAuthorization
                  .create(
                    userPrivate,
                    userPub,
                    primaryDevicePub,
                    request.companionDevicePub,
                    1L,
                    joinManifest,
                    cursor,
                    Crypto.sha256(root.bytes).bytes,
                    nowMs
                  )
                  .left
                  .map(_ => "authorization")
    yield (encodeAuth(auth), auth, root)

  def accept(
    authCode: String,
    userPub: Ed25519Public,
    companionDevicePub: Ed25519Public
  ): Either[String, DeviceAuthorization] =
    decodeAuth(authCode).flatMap { auth =>
      if auth.userPub == userPub && auth.companionDevicePub == companionDevicePub then Right(auth)
      else Left("auth-binding")
    }

  def request(
    inviteCode: String,
    companionDevicePrivate: Ed25519Private,
    companionDevicePub: Ed25519Public,
    name: String
  ): IO[Either[String, (String, Key32, Confirmation, X25519Private)]] =
    randomX25519.flatMap { ephemeral =>
      val result =
        for
          invite  <- decodeInvite(inviteCode)
          _       <- if invite.expiresAt <= 0L then Left("invite-expired") else Right(())
          shared  <- Crypto.agree(ephemeral.privateKey, invite.ephemeralPub).left.map(_ => "shared")
          root    <- deriveRoot(shared, invite.nonce)
          request <- PairingRequest
                       .create(
                         companionDevicePrivate,
                         invite.nonce,
                         companionDevicePub,
                         ephemeral.publicKey,
                         ByteVector.view(name.getBytes(StandardCharsets.UTF_8))
                       )
                       .left
                       .map(_ => "request")
        yield (encodeRequest(request), root, confirmation(request), ephemeral.privateKey)
      result.fold(error => IO.pure(Left(error)), value => IO.pure(Right(value)))
    }

  private def deriveRoot(shared: Key32, nonce: ByteVector): Either[String, Key32] =
    Crypto.hkdf32(shared.bytes, nonce, Crypto.ascii("yochat-s2-root")).left.map(_ => "root")

  def randomX25519: IO[X25519KeyPair] =
    IO.delay {
      val bytes = new Array[Byte](32)
      Random.nextBytes(bytes)
      val seed  = X25519Private.from(ByteVector.view(bytes)).fold(_ => throw new IllegalStateException("seed"), identity)
      X25519KeyPair.fromSeed(seed).fold(_ => throw new IllegalStateException("x25519"), identity)
    }

  def randomBytes(length: Int): IO[ByteVector] =
    IO.delay {
      val bytes = new Array[Byte](length)
      Random.nextBytes(bytes)
      ByteVector.view(bytes)
    }

  private def parse(value: String): Either[String, Value] =
    try Right(ujson.read(value))
    catch case _: Exception => Left("json")

  private def kind(json: Value, expected: String): Either[String, Unit] =
    json.obj.get("kind").collect { case ujson.Str(value) => value } match
      case Some(value) if value == expected => Right(())
      case _                                => Left("kind")

  private def hex(json: Value, name: String, length: Int): Either[String, ByteVector] =
    hexAny(json, name).flatMap(value => if value.size == length.toLong then Right(value) else Left(name))

  private def hexAny(json: Value, name: String): Either[String, ByteVector] =
    json.obj.get(name).collect { case ujson.Str(value) => value }.flatMap(value => ByteVector.fromHex(value)) match
      case Some(value) => Right(value)
      case None        => Left(name)

  private def number(json: Value, name: String): Either[String, Long] =
    json.obj.get(name) match
      case Some(ujson.Num(value)) => Right(value.toLong)
      case Some(ujson.Str(value)) => value.toLongOption.toRight(name)
      case _                      => Left(name)
