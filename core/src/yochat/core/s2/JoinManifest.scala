package yochat.core.s2

import scala.math.Ordered.orderingToOrdered

import scodec.bits.ByteVector

import yochat.core.{CanonicalReader, CanonicalWriter, CoreError, Crypto, Key32}

/** The authenticated enrollment cutoff: for each (conversation, author, device) stream, the maximum seq that existed when the companion was
  * authorized. It is small enough to sign directly and does not depend on local SQLite row ids.
  */
final case class JoinManifest(entries: Map[ManifestKey, Long]):

  def covers(key: ManifestKey, seq: Long): Boolean =
    entries.get(key).exists(maxSeq => seq <= maxSeq)

  def canonical: ByteVector =
    val writer = CanonicalWriter()
      .u8(JoinManifest.Version)
      .u32(entries.size.toLong)
    entries.toVector
      .sortBy { case (key, _) => ManifestKey.ordering(key) }
      .foreach { case (key, maxSeq) =>
        writer.fixed(key.conversationId)
        writer.fixed(key.authorPub.bytes)
        writer.fixed(key.devicePub.bytes)
        writer.u64(maxSeq)
      }
    writer.result

  def hash: Key32 =
    Crypto.sha256(Crypto.ascii("yochat-s2-join-manifest") ++ canonical)

  override def toString: String = s"JoinManifest(entries=${entries.size})"

object JoinManifest:

  val Version: Int    = 1
  val MaxEntries: Int = 1024

  val empty: JoinManifest = JoinManifest(Map.empty)

  def create(entries: Map[ManifestKey, Long]): Either[CoreError, JoinManifest] =
    if entries.size > MaxEntries then Left(CoreError.InvalidEncoding)
    else if entries.values.exists(seq => seq < 0L) then Left(CoreError.InvalidEncoding)
    else Right(JoinManifest(entries))

  def fromCanonical(value: ByteVector): Either[CoreError, JoinManifest] =
    val reader = CanonicalReader(value)
    for
      (version, r1) <- reader.u8
      _             <- if version == Version then Right(()) else Left(CoreError.InvalidVersion(version))
      (count, r2)   <- r1.u32
      _             <- if count <= MaxEntries.toLong then Right(()) else Left(CoreError.InvalidEncoding)
      entries       <- decodeEntries(r2, count.toInt, Map.empty, None)
      _             <- entries._2.finish
    yield JoinManifest(entries._1)

  private def decodeEntries(
    reader: CanonicalReader,
    remaining: Int,
    acc: Map[ManifestKey, Long],
    previous: Option[ManifestKey]
  ): Either[CoreError, (Map[ManifestKey, Long], CanonicalReader)] =
    if remaining == 0 then Right((acc, reader))
    else
      for
        (conversation, r1) <- reader.fixed(32)
        (author, r2)       <- r1.fixed(32)
        (device, r3)       <- r2.fixed(32)
        authorPub          <- yochat.core.Ed25519Public.from(author)
        devicePub          <- yochat.core.Ed25519Public.from(device)
        key                <- ManifestKey.create(conversation, authorPub, devicePub)
        _                  <- previous match
                                case Some(prior) if ManifestKey.ordering(prior).compare(ManifestKey.ordering(key)) >= 0 =>
                                  Left(CoreError.InvalidEncoding)
                                case _                                                                                  => Right(())
        (maxSeq, r4)       <- r3.u64
        _                  <- if maxSeq >= 0L then Right(()) else Left(CoreError.InvalidEncoding)
        next               <- decodeEntries(r4, remaining - 1, acc.updated(key, maxSeq), Some(key))
      yield next
