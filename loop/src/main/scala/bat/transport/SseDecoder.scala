package bat.transport

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}

import scala.annotation.tailrec
import scala.util.control.NonFatal

import zio.Chunk

sealed abstract class SseError(
    val code: String,
    val safeMessage: String
) extends Serializable:
  final override def toString: String =
    s"SseError(code=$code, safeMessage=$safeMessage)"

object SseError:
  case object InvalidLimits
      extends SseError(
        "invalid_sse_limits",
        "SSE framing limits must be positive and consistent"
      )

  case object InvalidInput
      extends SseError(
        "invalid_sse_input",
        "SSE decoder input is invalid"
      )

  case object EventLimitExceeded
      extends SseError(
        "sse_event_limit_exceeded",
        "SSE event exceeded the configured byte limit"
      )

  case object StreamLimitExceeded
      extends SseError(
        "sse_stream_limit_exceeded",
        "SSE stream exceeded the configured byte limit"
      )

  case object InvalidUtf8
      extends SseError(
        "invalid_sse_utf8",
        "SSE stream contained invalid UTF-8"
      )

  case object TruncatedStream
      extends SseError(
        "truncated_sse_stream",
        "SSE stream ended before the current event was delimited"
      )

final class SseLimits private (
    val maxEventBytes: Long,
    val maxStreamBytes: Long
):
  override def toString: String =
    s"SseLimits(maxEventBytes=$maxEventBytes, maxStreamBytes=$maxStreamBytes)"

object SseLimits:
  def make(
      maxEventBytes: Long,
      maxStreamBytes: Long
  ): Either[SseError, SseLimits] =
    if maxEventBytes <= 0 || maxStreamBytes <= 0 ||
      maxEventBytes > maxStreamBytes
    then Left(SseError.InvalidLimits)
    else Right(new SseLimits(maxEventBytes, maxStreamBytes))

/** One fully delimited SSE event. Its representation deliberately omits data,
  * event IDs, and other provider-controlled values.
  */
final class SseEvent private[transport] (
    val data: String,
    val eventType: Option[String],
    val id: Option[String],
    val retryMillis: Option[Long]
):
  override def toString: String =
    s"SseEvent(data=<redacted>, eventType=${eventType.fold("<none>")(_ =>
        "<present>"
      )}, id=${id.fold("<none>")(_ => "<present>")}, retryMillis=${retryMillis
        .fold("<none>")(_ => "<present>")})"

private final case class PendingSseEvent(
    dataLines: Chunk[String],
    eventType: Option[String],
    id: Option[String],
    retryMillis: Option[Long]
):
  def hasFields: Boolean =
    dataLines.nonEmpty || eventType.nonEmpty || id.nonEmpty || retryMillis.nonEmpty

private object PendingSseEvent:
  val empty: PendingSseEvent =
    PendingSseEvent(Chunk.empty, None, None, None)

/** Pure incremental SSE framing. A state value can be fed arbitrary byte
  * fragments. `finish` must be called at EOF; it rejects partial UTF-8, a
  * partial line, or an event missing its terminating blank line.
  */
object SseDecoder:
  final class State private[SseDecoder] (
      private[SseDecoder] val limits: SseLimits,
      private[SseDecoder] val streamBytes: Long,
      private[SseDecoder] val eventBytes: Long,
      private[SseDecoder] val pendingLine: Chunk[Byte],
      private[SseDecoder] val pendingEvent: PendingSseEvent
  ):
    override def toString: String =
      s"SseDecoder.State(streamBytes=$streamBytes, eventBytes=$eventBytes, pendingLineBytes=${pendingLine.length}, payload=<redacted>)"

  def initial(limits: SseLimits): Either[SseError, State] =
    if limits == null then Left(SseError.InvalidLimits)
    else
      Right(
        new State(
          limits,
          streamBytes = 0L,
          eventBytes = 0L,
          pendingLine = Chunk.empty,
          pendingEvent = PendingSseEvent.empty
        )
      )

  def feed(
      state: State,
      bytes: Chunk[Byte]
  ): Either[SseError, (State, Chunk[SseEvent])] =
    if state == null || bytes == null then Left(SseError.InvalidInput)
    else
      checkedAdd(state.streamBytes, bytes.length.toLong)
        .filterOrElse(
          _ <= state.limits.maxStreamBytes,
          SseError.StreamLimitExceeded
        )
        .flatMap(totalBytes =>
          feedBytes(
            index = 0,
            bytes = bytes,
            state = state,
            totalStreamBytes = totalBytes,
            events = Chunk.empty
          )
        )

  /** Strict EOF: the last dispatched event must have ended with a blank line.
    * An empty, fully delimited stream is valid at the framing layer.
    */
  def finish(state: State): Either[SseError, Chunk[SseEvent]] =
    if state == null then Left(SseError.InvalidInput)
    else if state.pendingLine.nonEmpty || state.pendingEvent.hasFields ||
      state.eventBytes != 0L
    then Left(SseError.TruncatedStream)
    else Right(Chunk.empty)

  @tailrec
  private def feedBytes(
      index: Int,
      bytes: Chunk[Byte],
      state: State,
      totalStreamBytes: Long,
      events: Chunk[SseEvent]
  ): Either[SseError, (State, Chunk[SseEvent])] =
    if index >= bytes.length then
      Right(
        (
          new State(
            state.limits,
            totalStreamBytes,
            state.eventBytes,
            state.pendingLine,
            state.pendingEvent
          ),
          events
        )
      )
    else
      val byte = bytes(index)
      checkedAdd(state.eventBytes, 1L) match
        case Left(error) => Left(error)
        case Right(nextEventBytes)
            if nextEventBytes > state.limits.maxEventBytes =>
          Left(SseError.EventLimitExceeded)
        case Right(nextEventBytes) =>
          val nextLine = state.pendingLine :+ byte
          if byte != '\n'.toByte then
            feedBytes(
              index + 1,
              bytes,
              new State(
                state.limits,
                totalStreamBytes,
                nextEventBytes,
                nextLine,
                state.pendingEvent
              ),
              totalStreamBytes,
              events
            )
          else
            parseLine(nextLine.dropRight(1), state.pendingEvent) match
              case Left(error)                              => Left(error)
              case Right((nextPending, emitted, blankLine)) =>
                val nextEvents = emitted.fold(events)(events :+ _)
                feedBytes(
                  index + 1,
                  bytes,
                  new State(
                    state.limits,
                    totalStreamBytes,
                    if blankLine then 0L else nextEventBytes,
                    Chunk.empty,
                    nextPending
                  ),
                  totalStreamBytes,
                  nextEvents
                )

  private def parseLine(
      rawLine: Chunk[Byte],
      pending: PendingSseEvent
  ): Either[SseError, (PendingSseEvent, Option[SseEvent], Boolean)] =
    val withoutCarriageReturn =
      if rawLine.lastOption.contains('\r'.toByte) then rawLine.dropRight(1)
      else rawLine
    decodeUtf8(withoutCarriageReturn).flatMap { line =>
      if line.isEmpty then
        val event =
          if pending.dataLines.isEmpty then None
          else
            Some(
              new SseEvent(
                pending.dataLines.mkString("\n"),
                pending.eventType,
                pending.id,
                pending.retryMillis
              )
            )
        Right((PendingSseEvent.empty, event, true))
      else if line.startsWith(":") then Right((pending, None, false))
      else
        val separator = line.indexOf(':')
        val (field, rawValue) =
          if separator < 0 then (line, "")
          else (line.take(separator), line.drop(separator + 1))
        val value =
          if rawValue.startsWith(" ") then rawValue.drop(1) else rawValue
        val updated = field match
          case "data"  => pending.copy(dataLines = pending.dataLines :+ value)
          case "event" => pending.copy(eventType = Some(value))
          case "id" if !value.contains('\u0000') =>
            pending.copy(id = Some(value))
          case "retry" if value.nonEmpty && value.forall(_.isDigit) =>
            value.toLongOption match
              case Some(millis) => pending.copy(retryMillis = Some(millis))
              case None         => pending
          case _ => pending
        Right((updated, None, false))
    }

  private def decodeUtf8(bytes: Chunk[Byte]): Either[SseError, String] =
    try
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      Right(decoder.decode(ByteBuffer.wrap(bytes.toArray)).toString)
    catch case NonFatal(_) => Left(SseError.InvalidUtf8)

  private def checkedAdd(
      left: Long,
      right: Long
  ): Either[SseError, Long] =
    try Right(Math.addExact(left, right))
    catch case _: ArithmeticException => Left(SseError.StreamLimitExceeded)
