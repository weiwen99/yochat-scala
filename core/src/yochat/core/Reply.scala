package yochat.core

import scodec.bits.ByteVector

object Reply:

  private val SignatureDomain = Crypto.ascii("yochat-s1-reply")
  private val AadDomain       = Crypto.ascii("yochat-s1-reply-aad")
  private val HashDomain      = Crypto.ascii("yochat-s1-reply-hash")
  private val SessionDomain   = Crypto.ascii("yochat-s1-session")

  def create(
    invitation: Invitation,
    user: Ed25519KeyPair,
    device: Ed25519KeyPair,
    ephemeral: X25519KeyPair,
    ratchet0: X25519KeyPair,
    ratchet1: X25519KeyPair,
    channelLabel: String,
    channelAccount: String,
    note: String,
    nonce: Nonce,
    nowMs: Long
  ): Either[CoreError, ReplyResult] =
    if nowMs > invitation.fields.expiresAt then Left(CoreError.Expired)
    else
      for
        _          <- KeySeparation.distinct(user.publicKey.bytes, device.publicKey.bytes)
        _          <- KeySeparation.distinct(ephemeral.publicKey.bytes, ratchet0.publicKey.bytes, ratchet1.publicKey.bytes)
        _          <- KeySeparation.distinct(invitation.fields.ephPub.bytes, ephemeral.publicKey.bytes)
        _          <- KeySeparation.distinct(invitation.fields.ratchetPub.bytes, ratchet0.publicKey.bytes)
        label      <- Text.normalize(channelLabel, "channel_label")
        account    <- Text.normalize(channelAccount, "channel_account")
        clean      <- Text.normalize(note, "note")
        fields      = ReplyFields(
                        invitation.invitationHash,
                        user.publicKey,
                        device.publicKey,
                        label,
                        account,
                        ephemeral.publicKey,
                        ratchet0.publicKey,
                        clean
                      )
        fieldBytes <- ReplyFields.encode(fields)
        signature  <- Crypto.sign(user.privateKey, SignatureDomain ++ fieldBytes)
        shared     <- Crypto.agree(ephemeral.privateKey, invitation.fields.ephPub)
        replyKey   <- Crypto.hkdf32(shared.bytes, invitation.invitationHash.bytes, Crypto.InfoReply)
        aad         = replyAad(invitation.invitationHash, ephemeral.publicKey)
        ciphertext <- Crypto.aeadSeal(replyKey, nonce, aad, fieldBytes ++ signature.bytes)
        envelope    = ReplyEnvelope.create(ephemeral.publicKey, nonce, ciphertext)
        replyHash   = Crypto.sha256(HashDomain, envelope.canonical)
        keys       <- deriveKeys(invitation.invitationHash, replyHash, shared)
        state      <- SessionSetup.responder(
                        keys,
                        invitation.fields.userPub,
                        invitation.fields.devicePub,
                        ratchet0,
                        invitation.fields.ratchetPub,
                        ratchet1
                      )
      yield ReplyResult(envelope, envelope.text, fields, keys, state)

  def accept(
    invitation: Invitation,
    replyText: String,
    inviterEphemeral: X25519Private,
    inviterRatchet0: X25519KeyPair,
    nowMs: Long
  ): Either[CoreError, ReplyResult] =
    if nowMs > invitation.fields.expiresAt then Left(CoreError.Expired)
    else if inviterRatchet0.publicKey != invitation.fields.ratchetPub then Left(CoreError.InvalidInvitation)
    else
      for
        envelope  <- ReplyEnvelope.fromText(replyText)
        shared    <- Crypto.agree(inviterEphemeral, envelope.ephPub)
        replyKey  <- Crypto.hkdf32(shared.bytes, invitation.invitationHash.bytes, Crypto.InfoReply)
        aad        = replyAad(invitation.invitationHash, envelope.ephPub)
        plaintext <- Crypto.aeadOpen(replyKey, envelope.nonce, aad, envelope.ciphertext)
        _         <- if plaintext.size < Signature.Length then Left(CoreError.InvalidReply) else Right(())
        fieldBytes = plaintext.dropRight(Signature.Length)
        sigBytes   = plaintext.takeRight(Signature.Length)
        signature <- Signature.from(sigBytes)
        fields    <- ReplyFields.decode(fieldBytes)
        _         <- if fields.invitationHash == invitation.invitationHash then Right(()) else Left(CoreError.InvalidReply)
        _         <- if fields.ephPub == envelope.ephPub then Right(()) else Left(CoreError.InvalidReply)
        _         <- if Crypto.verify(fields.userPub, SignatureDomain ++ fieldBytes, signature) then Right(())
                     else Left(CoreError.InvalidSignature)
        replyHash  = Crypto.sha256(HashDomain, envelope.canonical)
        keys      <- deriveKeys(invitation.invitationHash, replyHash, shared)
        state     <- SessionSetup.initiator(
                       keys,
                       fields.userPub,
                       fields.devicePub,
                       inviterRatchet0,
                       fields.ratchetPub
                     )
      yield ReplyResult(envelope, envelope.text, fields, keys, state)

  def matchesAccount(result: ReplyResult, expected: String): Either[CoreError, Unit] =
    Text.normalize(expected, "peer_account").flatMap { normalized =>
      if normalized == result.fields.channelAccount then Right(()) else Left(CoreError.InvalidReply)
    }

  private def replyAad(invitationHash: Key32, ephemeralPub: X25519Public): ByteVector =
    CanonicalWriter()
      .fixed(AadDomain)
      .u8(ReplyEnvelope.Version)
      .fixed(invitationHash.bytes)
      .fixed(ephemeralPub.bytes)
      .result

  private def deriveKeys(invitationHash: Key32, replyHash: Key32, shared: Key32): Either[CoreError, SessionKeys] =
    for
      sessionRaw <- SessionId.from(Crypto.sha256(SessionDomain, invitationHash.bytes, replyHash.bytes).bytes)
      rootSalt    = Crypto.sha256(invitationHash.bytes, replyHash.bytes)
      rootKey    <- Crypto.hkdf32(shared.bytes, rootSalt.bytes, Crypto.InfoRoot)
      rekeyBase  <- Crypto.hkdf32(shared.bytes, sessionRaw.bytes, Crypto.InfoRekeyBase)
    yield SessionKeys(sessionRaw, rootKey, rekeyBase)
