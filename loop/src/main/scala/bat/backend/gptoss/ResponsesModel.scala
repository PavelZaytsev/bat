package bat.backend.gptoss

import bat.protocol.*

import zio.Chunk
import zio.json.ast.Json

/** Hard bounds for the provider-controlled Responses stream.
  *
  * The transport may impose tighter byte limits. These character and item
  * limits protect the pure decoder and assembler even when they are exercised
  * without a live transport.
  */
final case class ResponsesLimits private (
    maxEventCharacters: Int,
    maxOutputItems: Int,
    maxFunctionCalls: Int,
    maxReasoningCharacters: Int,
    maxArgumentsCharacters: Int,
    maxOutputCharacters: Int
)

object ResponsesLimits:
  val default: ResponsesLimits =
    ResponsesLimits(
      maxEventCharacters = 1024 * 1024,
      maxOutputItems = 128,
      maxFunctionCalls = 64,
      maxReasoningCharacters = 4 * 1024 * 1024,
      maxArgumentsCharacters = 1024 * 1024,
      maxOutputCharacters = 2 * 1024 * 1024
    )

  def make(
      maxEventCharacters: Int,
      maxOutputItems: Int,
      maxFunctionCalls: Int,
      maxReasoningCharacters: Int,
      maxArgumentsCharacters: Int,
      maxOutputCharacters: Int
  ): Either[BatError, ResponsesLimits] =
    val values = List(
      "max_event_characters" -> maxEventCharacters,
      "max_output_items" -> maxOutputItems,
      "max_function_calls" -> maxFunctionCalls,
      "max_reasoning_characters" -> maxReasoningCharacters,
      "max_arguments_characters" -> maxArgumentsCharacters,
      "max_output_characters" -> maxOutputCharacters
    )
    values.collectFirst { case (name, value) if value <= 0 => name } match
      case Some(name) =>
        Left(BatError.ProtocolViolation(s"$name must be positive"))
      case None =>
        Right(
          ResponsesLimits(
            maxEventCharacters,
            maxOutputItems,
            maxFunctionCalls,
            maxReasoningCharacters,
            maxArgumentsCharacters,
            maxOutputCharacters
          )
        )

/** Exact provider output required to continue a GPT-OSS tool turn.
  *
  * The raw reasoning and function-call items intentionally have no public
  * accessor and inherit the redacted `toString` from
  * [[OpaqueReasoningContext]]. Only the GPT-OSS adapter package can replay
  * them. BAT itself can retain this value but cannot inspect model reasoning.
  */
final class GptOssContext private[gptoss] (
    identity: BackendIdentity,
    private[gptoss] val historyItems: Chunk[Json],
    private[gptoss] val pendingCallIds: Chunk[CallId],
    private[gptoss] val historicalCallIds: Set[CallId]
) extends OpaqueReasoningContext(identity, ContinuationMode.OpaqueReplay)

/** Redacted request envelope that binds the exact wire input to the assembler.
  * That seed is required for stateless manual Responses replay on the next tool
  * turn.
  */
final class EncodedResponsesRequest private[gptoss] (
    private[gptoss] val identity: BackendIdentity,
    private[gptoss] val body: Json.Obj,
    private[gptoss] val inputSeed: Chunk[Json],
    private[gptoss] val historicalCallIds: Set[CallId]
):
  override def toString: String =
    "EncodedResponsesRequest(body=<redacted>, input=<redacted>)"

private[gptoss] enum OutputItemKind:
  case Reasoning
  case FunctionCall
  case Message

/** Transport-independent semantic subset of Responses streaming events used by
  * BAT. SSE framing is deliberately outside this algebra.
  */
private[gptoss] sealed trait ResponsesEvent extends Serializable:
  def sequenceNumber: Option[Long]

  final override def toString: String =
    s"ResponsesEvent(sequence_number=${sequenceNumber.fold("none")(_.toString)}, payload=<redacted>)"

private[gptoss] object ResponsesEvent:
  final case class Created(
      sequence: Long,
      responseId: String,
      model: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class Progress(
      sequence: Long,
      responseId: String,
      status: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class OutputItemAdded(
      sequence: Long,
      outputIndex: Int,
      item: Json.Obj
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class ContentPartAdded(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      part: Json.Obj
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class ReasoningDelta(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      delta: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class ReasoningDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      text: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class OutputTextDelta(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      delta: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class OutputTextDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      text: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class ContentPartDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      part: Json.Obj
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class FunctionArgumentsDelta(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      delta: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class FunctionArgumentsDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      name: String,
      arguments: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class OutputItemDone(
      sequence: Long,
      outputIndex: Int,
      item: Json.Obj
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class Completed(
      sequence: Long,
      responseId: String,
      model: String,
      output: Chunk[Json.Obj],
      usage: Usage
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  final case class TerminalFailure(
      sequence: Long,
      code: String
  ) extends ResponsesEvent:
    val sequenceNumber: Option[Long] = Some(sequence)

  /** Optional SSE sentinel used by some compatible servers. The assembler
    * accepts it only after `response.completed`.
    */
  case object StreamEnd extends ResponsesEvent:
    val sequenceNumber: Option[Long] = None
