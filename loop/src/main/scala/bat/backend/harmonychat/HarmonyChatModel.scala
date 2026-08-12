package bat.backend.harmonychat

import bat.protocol.*

import zio.Chunk
import zio.json.ast.Json

/** Hard bounds for the provider-controlled Chat Completions stream.
  *
  * The transport may impose tighter byte limits. These character and item
  * limits protect the pure decoder and assembler even when they are exercised
  * without a live transport.
  */
final case class HarmonyChatLimits private (
    maxEventCharacters: Int,
    maxChunks: Int,
    maxToolCalls: Int,
    maxReasoningCharacters: Int,
    maxArgumentsCharacters: Int,
    maxOutputCharacters: Int
)

object HarmonyChatLimits:
  val default: HarmonyChatLimits =
    HarmonyChatLimits(
      maxEventCharacters = 1024 * 1024,
      maxChunks = 256 * 1024,
      maxToolCalls = 64,
      maxReasoningCharacters = 4 * 1024 * 1024,
      maxArgumentsCharacters = 1024 * 1024,
      maxOutputCharacters = 2 * 1024 * 1024
    )

  def make(
      maxEventCharacters: Int,
      maxChunks: Int,
      maxToolCalls: Int,
      maxReasoningCharacters: Int,
      maxArgumentsCharacters: Int,
      maxOutputCharacters: Int
  ): Either[BatError, HarmonyChatLimits] =
    val values = List(
      "max_event_characters" -> maxEventCharacters,
      "max_chunks" -> maxChunks,
      "max_tool_calls" -> maxToolCalls,
      "max_reasoning_characters" -> maxReasoningCharacters,
      "max_arguments_characters" -> maxArgumentsCharacters,
      "max_output_characters" -> maxOutputCharacters
    )
    values.collectFirst { case (name, value) if value <= 0 => name } match
      case Some(name) =>
        Left(BatError.ProtocolViolation(s"$name must be positive"))
      case None =>
        Right(
          HarmonyChatLimits(
            maxEventCharacters,
            maxChunks,
            maxToolCalls,
            maxReasoningCharacters,
            maxArgumentsCharacters,
            maxOutputCharacters
          )
        )

/** Exact provider history required to continue a Harmony Chat tool turn.
  *
  * The dialect is stateless on the wire: every turn resends the whole message
  * array. `historyMessages` is therefore the literal prefix that was sent plus
  * the assistant message reconstructed from the stream, including the model's
  * raw reasoning in `reasoning_content` so the analysis channel survives the
  * next tool turn.
  *
  * The messages have no public accessor and inherit the redacted `toString`
  * from [[OpaqueReasoningContext]]. BAT can retain this value but cannot
  * inspect model reasoning.
  */
final class HarmonyChatContext private[harmonychat] (
    identity: BackendIdentity,
    private[harmonychat] val historyMessages: Chunk[Json],
    private[harmonychat] val pendingCallIds: Chunk[CallId],
    private[harmonychat] val historicalCallIds: Set[CallId]
) extends OpaqueReasoningContext(identity, ContinuationMode.OpaqueReplay)

/** Redacted request envelope that binds the exact wire input to the assembler.
  * That seed is required for stateless replay on the next tool turn.
  */
final class EncodedHarmonyChatRequest private[harmonychat] (
    private[harmonychat] val identity: BackendIdentity,
    private[harmonychat] val body: Json.Obj,
    private[harmonychat] val messageSeed: Chunk[Json],
    private[harmonychat] val historicalCallIds: Set[CallId]
):
  override def toString: String =
    "EncodedHarmonyChatRequest(body=<redacted>, messages=<redacted>)"

/** One tool call as it arrived on the wire, before argument validation.
  *
  * `id` is the provider's own call identifier. It is replayed verbatim; BAT
  * never mints, rewrites, or reuses one.
  */
private[harmonychat] final case class RawToolCall(
    index: Int,
    id: String,
    name: String,
    arguments: String
):
  override def toString: String = "RawToolCall(payload=<redacted>)"

/** Transport-independent semantic subset of Chat Completions streaming events.
  * SSE framing is deliberately outside this algebra.
  */
private[harmonychat] sealed trait HarmonyChatEvent extends Serializable:
  final override def toString: String = "HarmonyChatEvent(payload=<redacted>)"

private[harmonychat] object HarmonyChatEvent:
  /** One `chat.completion.chunk`. Every field the dialect needs is explicit;
    * absent optional fields stay absent rather than defaulting to zero.
    */
  final case class Delta(
      responseId: String,
      model: String,
      reasoning: Option[String],
      content: Option[String],
      toolCalls: Chunk[RawToolCall],
      finishReason: Option[String],
      usage: Option[Usage]
  ) extends HarmonyChatEvent

  /** An in-band provider error object. */
  final case class TerminalFailure(code: String) extends HarmonyChatEvent

  /** The `[DONE]` sentinel. Accepted only after a terminal finish reason. */
  case object StreamEnd extends HarmonyChatEvent
