package yochat.core

final case class Ed25519KeyPair(privateKey: Ed25519Private, publicKey: Ed25519Public)

final case class X25519KeyPair(privateKey: X25519Private, publicKey: X25519Public)

object X25519KeyPair:

  /** Deterministic key pair for vectors and tests; X25519 clamps the scalar inside JCE. */
  def fromSeed(seed: X25519Private): Either[CoreError, X25519KeyPair] = Jce.x25519FromSeed(seed)
