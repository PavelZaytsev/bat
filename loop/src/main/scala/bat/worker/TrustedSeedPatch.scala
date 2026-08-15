package bat.worker

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.security.MessageDigest

import scala.util.Try

/** Operator-authenticated recovery input admitted outside the actor-visible
  * tool contract. The payload is deliberately available only inside BAT and its
  * textual representation is always redacted.
  */
final class TrustedSeedPatch private (
    private[bat] val text: String,
    val sha256: Sha256Digest,
    val byteLength: Int
):
  override def toString: String =
    s"TrustedSeedPatch(sha256=${sha256.value}, payload=<redacted>)"

object TrustedSeedPatch:
  val MaxBytes: Int = 2 * 1024 * 1024

  def fromBytes(
      bytes: Array[Byte],
      expectedSha256: String
  ): Either[WorkerError, TrustedSeedPatch] =
    for
      expected <- Sha256Digest.from(
        expectedSha256,
        "seed_patch_sha256"
      )
      input <- Option(bytes).toRight(
        invalid("seed patch bytes are required")
      )
      _ <- Either.cond(
        input.nonEmpty && input.length <= MaxBytes,
        (),
        invalid("seed patch must contain 1 byte through 2 MiB")
      )
      actual <- Sha256Digest.from(sha256(input), "seed_patch_sha256")
      _ <- Either.cond(
        MessageDigest.isEqual(
          actual.value.getBytes(StandardCharsets.US_ASCII),
          expected.value.getBytes(StandardCharsets.US_ASCII)
        ),
        (),
        WorkerError.InvalidInput(
          "seed_patch_digest_mismatch",
          "seed patch does not match the operator SHA-256 binding"
        )
      )
      decoded <- Try(
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input))
          .toString
      ).toEither.left.map(_ =>
        WorkerError.InvalidInput(
          "invalid_seed_patch_encoding",
          "seed patch must be strict UTF-8"
        )
      )
      _ <- PatchPolicy.validate(decoded)
    yield new TrustedSeedPatch(decoded, actual, input.length)

  private def invalid(message: String): WorkerError =
    WorkerError.InvalidInput("invalid_seed_patch", message)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
