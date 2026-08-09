package bat.backend.gptoss

import scala.util.control.NonFatal

import bat.protocol.{BackendIdentity, BatError}
import bat.transport.{RequestTarget, Secret, SseLimits, TransportError}

import zio.Duration

/** Operator-pinned configuration for the GPT-OSS Responses cartridge.
  *
  * Endpoint location and socket timeouts belong to the shared HTTP transport.
  * This value owns only GPT-OSS dialect policy, replay bounds, and retry
  * policy. A credential can be attached to the request, but its representation
  * is always redacted.
  */
final class GptOssConfig private (
    val identity: BackendIdentity,
    val credential: Option[Secret],
    val maxOutputTokens: Long,
    val maxAttempts: Int,
    val retryDelay: Duration,
    val sseLimits: SseLimits,
    val responsesLimits: ResponsesLimits,
    private[gptoss] val target: RequestTarget
):
  override def toString: String =
    s"GptOssConfig(identity=$identity, credential=<redacted>, maxOutputTokens=$maxOutputTokens, maxAttempts=$maxAttempts, retryDelay=$retryDelay, sseLimits=$sseLimits, responsesLimits=$responsesLimits, dialect=responses)"

object GptOssConfig:
  val BackendName = "gpt-oss-responses"
  val ResponsesPath = "/v1/responses"

  val DefaultMaxOutputTokens: Long = 32L * 1024
  val DefaultMaxAttempts: Int = 3
  val DefaultRetryDelay: Duration = Duration.fromMillis(250)

  private val MaxOutputTokens: Long = 1024L * 1024
  private val MaxAttempts: Int = 8

  def identity(
      modelId: String,
      modelRevision: String
  ): Either[BatError, BackendIdentity] =
    BackendIdentity.make(BackendName, modelId, modelRevision)

  def make(
      identity: BackendIdentity,
      credential: Option[Secret],
      sseLimits: SseLimits,
      responsesLimits: ResponsesLimits = ResponsesLimits.default,
      maxOutputTokens: Long = DefaultMaxOutputTokens,
      maxAttempts: Int = DefaultMaxAttempts,
      retryDelay: Duration = DefaultRetryDelay
  ): Either[BatError, GptOssConfig] =
    for
      _ <- Either.cond(
        identity != null && identity.backend == BackendName,
        (),
        violation(
          s"GPT-OSS identity backend must be '$BackendName'"
        )
      )
      _ <- Either.cond(
        credential != null && !credential.contains(null),
        (),
        violation("GPT-OSS credential option must not be null")
      )
      _ <- Either.cond(
        sseLimits != null && responsesLimits != null,
        (),
        violation("GPT-OSS stream limits must not be null")
      )
      _ <- Either.cond(
        maxOutputTokens > 0 && maxOutputTokens <= MaxOutputTokens,
        (),
        violation(
          s"GPT-OSS max_output_tokens must be between 1 and $MaxOutputTokens"
        )
      )
      _ <- Either.cond(
        maxAttempts > 0 && maxAttempts <= MaxAttempts,
        (),
        violation(
          s"GPT-OSS max_attempts must be between 1 and $MaxAttempts"
        )
      )
      _ <- Either.cond(
        validRetryDelay(retryDelay, maxAttempts),
        (),
        violation(
          "GPT-OSS retry_delay must be non-negative, finite, and clock-safe"
        )
      )
      target <- RequestTarget
        .from(ResponsesPath)
        .left
        .map(mapTransportConfigurationError)
    yield new GptOssConfig(
      identity,
      credential,
      maxOutputTokens,
      maxAttempts,
      retryDelay,
      sseLimits,
      responsesLimits,
      target
    )

  private def validRetryDelay(
      value: Duration,
      maxAttempts: Int
  ): Boolean =
    try
      val multiplier = 1L << Math.max(0, maxAttempts - 1)
      value != null && value != Duration.Infinity && !value.isNegative &&
      value.toNanos >= 0 && value.toMillis >= 0 &&
      value.toNanos <= Long.MaxValue / multiplier
    catch case NonFatal(_) => false

  private def mapTransportConfigurationError(
      error: TransportError
  ): BatError =
    BatError.ProtocolViolation(
      s"GPT-OSS Responses target is invalid (${error.code})"
    )

  private def violation(message: String): BatError =
    BatError.ProtocolViolation(message)
