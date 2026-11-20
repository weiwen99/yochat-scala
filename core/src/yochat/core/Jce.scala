package yochat.core

import java.security.{GeneralSecurityException, KeyFactory, MessageDigest, PrivateKey, PublicKey, Signature as JcaSignature}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import javax.crypto.{Cipher, KeyAgreement, Mac}
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import scodec.bits.ByteVector

/** JDK JCE boundary. Wire and state formats use raw 32/64-byte values; JCA wants X.509/PKCS#8 DER. */
private[core] object Jce:

  private val EdPublicPrefix  = ByteVector.fromValidHex("302a300506032b6570032100")
  private val XPublicPrefix   = ByteVector.fromValidHex("302a300506032b656e032100")
  private val EdPrivatePrefix = ByteVector.fromValidHex("302e020100300506032b657004220420")
  private val XPrivatePrefix  = ByteVector.fromValidHex("302e020100300506032b656e04220420")
  private val XBasePoint      = ByteVector.fromValidHex("0900000000000000000000000000000000000000000000000000000000000000")

  def edPublic(value: Ed25519Public): Either[CoreError, PublicKey] =
    buildPublicKey("Ed25519", EdPublicPrefix, value.bytes)

  def edPrivate(value: Ed25519Private): Either[CoreError, PrivateKey] =
    buildPrivateKey("Ed25519", EdPrivatePrefix, value.bytes)

  def xPublic(value: X25519Public): Either[CoreError, PublicKey] =
    buildPublicKey("X25519", XPublicPrefix, value.bytes)

  def xPrivate(value: X25519Private): Either[CoreError, PrivateKey] =
    buildPrivateKey("X25519", XPrivatePrefix, value.bytes)

  def x25519FromSeed(seed: X25519Private): Either[CoreError, X25519KeyPair] =
    for
      privateKey <- xPrivate(seed)
      basePoint  <- xPublic(X25519Public.trust(XBasePoint))
      publicRaw  <- agreeJca(privateKey, basePoint)
      publicKey  <- X25519Public.from(publicRaw.bytes)
    yield X25519KeyPair(seed, publicKey)

  private def buildPublicKey(algorithm: String, prefix: ByteVector, raw: ByteVector): Either[CoreError, PublicKey] =
    try Right(KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec((prefix ++ raw).toArray)))
    catch case _: GeneralSecurityException => Left(CoreError.InvalidKey)

  private def buildPrivateKey(algorithm: String, prefix: ByteVector, raw: ByteVector): Either[CoreError, PrivateKey] =
    try Right(KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec((prefix ++ raw).toArray)))
    catch case _: GeneralSecurityException => Left(CoreError.InvalidKey)

  def sign(privateKey: Ed25519Private, data: ByteVector): Either[CoreError, Signature] =
    for
      key    <- edPrivate(privateKey)
      raw    <- signRaw(key, data)
      result <- Signature.from(ByteVector.view(raw))
    yield result

  private def signRaw(key: PrivateKey, data: ByteVector): Either[CoreError, Array[Byte]] =
    try
      val signer = JcaSignature.getInstance("Ed25519")
      signer.initSign(key)
      signer.update(data.toArray)
      Right(signer.sign())
    catch case _: GeneralSecurityException => Left(CoreError.InvalidSignature)

  def verify(publicKey: Ed25519Public, data: ByteVector, signature: Signature): Boolean =
    edPublic(publicKey).fold(
      _ => false,
      key =>
        try
          val verifier = JcaSignature.getInstance("Ed25519")
          verifier.initVerify(key)
          verifier.update(data.toArray)
          verifier.verify(signature.bytes.toArray)
        catch case _: GeneralSecurityException => false
    )

  def agree(privateKey: X25519Private, publicKey: X25519Public): Either[CoreError, Key32] =
    for
      priv <- xPrivate(privateKey)
      pub  <- xPublic(publicKey)
      key  <- agreeJca(priv, pub)
    yield key

  private def agreeJca(privateKey: PrivateKey, publicKey: PublicKey): Either[CoreError, Key32] =
    try
      val agreement = KeyAgreement.getInstance("X25519")
      agreement.init(privateKey)
      agreement.doPhase(publicKey, true)
      Key32.from(ByteVector.view(agreement.generateSecret())).flatMap { key =>
        if isAllZero(key.bytes) then Left(CoreError.InvalidKey) else Right(key)
      }
    catch case _: GeneralSecurityException => Left(CoreError.InvalidKey)

  def sha256(data: ByteVector*): Key32 =
    val digest = MessageDigest.getInstance("SHA-256")
    data.foreach(part => digest.update(part.toArray))
    Key32.trust(ByteVector.view(digest.digest()))

  def hmacSha256(key: ByteVector, data: ByteVector): Key32 =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key.toArray, "HmacSHA256"))
    Key32.trust(ByteVector.view(mac.doFinal(data.toArray)))

  def aeadSeal(key: Key32, nonce: Nonce, aad: ByteVector, plaintext: ByteVector): Either[CoreError, ByteVector] =
    try
      val cipher = Cipher.getInstance("ChaCha20-Poly1305")
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.bytes.toArray, "ChaCha20"), new IvParameterSpec(nonce.bytes.toArray))
      cipher.updateAAD(aad.toArray)
      Right(ByteVector.view(cipher.doFinal(plaintext.toArray)))
    catch case _: GeneralSecurityException => Left(CoreError.InvalidKey)

  def aeadOpen(key: Key32, nonce: Nonce, aad: ByteVector, ciphertext: ByteVector): Either[CoreError, ByteVector] =
    try
      val cipher = Cipher.getInstance("ChaCha20-Poly1305")
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.bytes.toArray, "ChaCha20"), new IvParameterSpec(nonce.bytes.toArray))
      cipher.updateAAD(aad.toArray)
      Right(ByteVector.view(cipher.doFinal(ciphertext.toArray)))
    catch
      case _: javax.crypto.AEADBadTagException => Left(CoreError.InvalidCiphertext)
      case _: GeneralSecurityException         => Left(CoreError.InvalidState)

  private def isAllZero(value: ByteVector): Boolean =
    value.toArray.forall(_ == 0.toByte)
