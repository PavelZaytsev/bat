package bat.backend.wire

import scala.util.control.NonFatal

import bat.protocol.*
import bat.transport.*

import zio.Duration

/** Classification of a provider status that is not `200`.
  *
  * `retryable` is a statement about replay safety, not about hope. Only a
  * status that proves the request was never admitted may be retried, because
  * replaying an admitted POST duplicates unreported inference spend.
  */
final case class WireStatus(code: String, retryable: Boolean)

/** Result of feeding one framed event to a dialect stream.
  *
  * `semantic` marks events that carry provider meaning, so the shared backend
  * can measure time-to-first-event without knowing the dialect's vocabulary.
  * Framing sentinels are not semantic.
  */
final case class WireStep[S](stream: S, semantic: Boolean)

/** Bounded retry policy shared by every streaming cartridge. */
final class WireRetryPolicy private (
    val maxAttempts: Int,
    val baseDelay: Duration
):
  /** Exponential backoff for a completed attempt. */
  def delayAfter(attempt: Int): Duration =
    Duration.fromNanos(baseDelay.toNanos * (1L << Math.max(0, attempt - 1)))

  override def toString: String =
    s"WireRetryPolicy(maxAttempts=$maxAttempts, baseDelay=$baseDelay)"

object WireRetryPolicy:
  val MaxAttemptsBound: Int = 8

  def make(
      maxAttempts: Int,
      baseDelay: Duration
  ): Either[BatError, WireRetryPolicy] =
    if maxAttempts <= 0 || maxAttempts > MaxAttemptsBound then
      Left(
        BatError.ProtocolViolation(
          s"max_attempts must be between 1 and $MaxAttemptsBound"
        )
      )
    else if !clockSafe(baseDelay, maxAttempts) then
      Left(
        BatError.ProtocolViolation(
          "retry_delay must be non-negative, finite, and clock-safe"
        )
      )
    else Right(new WireRetryPolicy(maxAttempts, baseDelay))

  private def clockSafe(value: Duration, maxAttempts: Int): Boolean =
    try
      val multiplier = 1L << Math.max(0, maxAttempts - 1)
      value != null && value != Duration.Infinity && !value.isNegative &&
      value.toNanos >= 0 && value.toMillis >= 0 &&
      value.toNanos <= Long.MaxValue / multiplier
    catch case NonFatal(_) => false

/** One provider wire dialect.
  *
  * [[StreamingWireBackend]] owns sockets, SSE framing, retry, timing, and
  * telemetry. A dialect owns request encoding, event interpretation, opaque
  * reasoning replay, and normalization into BAT model turns. Adding a provider
  * therefore means adding a dialect, not another copy of the transport loop.
  *
  * Provider wire objects and reasoning history stay inside the dialect. The
  * controller sees only normalized turns and an opaque continuation value, so a
  * dialect may not widen what BAT can inspect.
  *
  * A dialect must fail closed. It may not reinterpret an incompatible response,
  * follow a redirect to a different dialect, or emulate a capability the
  * endpoint did not actually provide.
  */
trait WireDialect:
  /** Adapter-owned continuation state for this dialect. */
  type Context <: OpaqueReasoningContext

  /** Immutable per-response assembly state. */
  type Stream

  def identity: BackendIdentity

  def capabilities: BackendCapabilities

  /** Stable machine-code prefix for this dialect's error codes. Error codes are
    * part of the evidence contract, so this value is pinned, not cosmetic.
    */
  def errorPrefix: String

  def target: RequestTarget

  def credential: Option[Secret]

  def sseLimits: SseLimits

  def retryPolicy: WireRetryPolicy

  /** Dialect ceiling on generated tokens for one turn. The backend narrows this
    * with the remaining run budget before calling [[beginTurn]].
    */
  def maxOutputTokens: Long

  def acceptMediaType: String = "text/event-stream"

  def expectedMediaType: String = "text/event-stream"

  /** Canonical UTF-8 request body plus the stream state seeded by it.
    *
    * The seed matters: a stateless dialect must be able to reconstruct the
    * exact continuation history from what it sent plus what it received.
    */
  def beginTurn(
      request: ModelRequest[Context],
      outputTokenLimit: Long
  ): Either[BatError, (String, Stream)]

  def accept(
      stream: Stream,
      event: SseEvent
  ): Either[BatError, WireStep[Stream]]

  def finish(stream: Stream): Either[BatError, ModelTurn[Context]]

  /** Classification of a non-200 status. The default covers ordinary HTTP
    * semantics; a dialect overrides it only to pin more precise reason codes.
    */
  def statusFailure(code: Int): WireStatus =
    if code == 429 then WireStatus(s"${errorPrefix}_rate_limited", true)
    else if code == 408 then
      WireStatus(s"${errorPrefix}_request_timeout", false)
    else if code == 401 || code == 403 then
      WireStatus(s"${errorPrefix}_unauthorized", false)
    else if code == 404 || code == 405 then
      WireStatus(s"${errorPrefix}_endpoint_unavailable", false)
    else if code >= 500 then
      WireStatus(s"${errorPrefix}_endpoint_unavailable", false)
    else WireStatus(s"${errorPrefix}_http_status", false)

  /** Stable failure used whenever the endpoint violated the pinned dialect. */
  final def protocolFailure: BatError.BackendFailure =
    BatError.BackendFailure(
      errorCode = s"${errorPrefix}_protocol_violation",
      safeMessage = s"$dialectLabel endpoint violated the pinned dialect",
      retryable = false
    )

  /** Human-safe label used in redacted messages. */
  def dialectLabel: String
