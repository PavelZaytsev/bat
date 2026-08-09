package bat.trace

import bat.protocol.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

final case class SafePins(
    backend: String,
    @jsonField("model_id") modelId: String,
    @jsonField("model_revision") modelRevision: String,
    @jsonField("reasoning_effort") reasoningEffort: String,
    @jsonField("prompt_version") promptVersion: String,
    @jsonField("bdr_commit") bdrCommit: String
) derives JsonCodec

final case class SafeBdrCheckpoint(
    revision: Long,
    @jsonField("run_state") runState: String,
    @jsonField("state_digest") stateDigest: String,
    @jsonField("next_action") nextAction: Json.Obj
) derives JsonCodec

final case class SafeCapabilities(
    required: List[String],
    enabled: List[String]
) derives JsonCodec

final case class SafeBudgets(
    @jsonField("max_iterations") maxIterations: Int,
    @jsonField("max_tool_calls") maxToolCalls: Int,
    @jsonField("max_wall_millis") maxWallMillis: Long,
    @jsonField("max_total_tokens") maxTotalTokens: Long
) derives JsonCodec

@jsonDiscriminator("type")
sealed trait SafeTraceEvent extends Serializable

object SafeTraceEvent:
  opaque type SafeCallId = String

  object SafeCallId:
    private val Digest = "^sha256:[0-9a-f]{64}$".r

    private[trace] def fromDigest(value: String): SafeCallId = value

    given JsonCodec[SafeCallId] =
      JsonCodec.string.transformOrFail(
        value =>
          if Digest.matches(value) then Right(value)
          else Left("safe call_id must contain only a SHA-256 digest"),
        identity
      )

  /** A durable proof that a reasoning context is opaque. There is deliberately
    * no public constructor for any other value, and the codec rejects `false`.
    */
  sealed trait OpaqueTraceMarker extends Serializable

  object OpaqueTraceMarker:
    case object Opaque extends OpaqueTraceMarker

    given JsonCodec[OpaqueTraceMarker] =
      JsonCodec.boolean.transformOrFail(
        value =>
          if value then Right(Opaque)
          else Left("reasoning context must remain opaque"),
        _ => true
      )

  /** The only payload a durable reasoning event can contain. Raw provider
    * reasoning has no inhabitant in this type and its codec rejects any other
    * string.
    */
  sealed trait RedactedTracePayload extends Serializable

  object RedactedTracePayload:
    case object Redacted extends RedactedTracePayload

    private val WireValue = "<redacted>"

    given JsonCodec[RedactedTracePayload] =
      JsonCodec.string.transformOrFail(
        value =>
          if value == WireValue then Right(Redacted)
          else Left("reasoning payload must remain redacted"),
        _ => WireValue
      )

  @jsonHint("run_start")
  final case class RunStart(
      mode: String,
      pins: SafePins,
      bdr: SafeBdrCheckpoint,
      capabilities: SafeCapabilities,
      budgets: SafeBudgets
  ) extends SafeTraceEvent

  @jsonHint("developer_input")
  final case class DeveloperInput(characters: Int) extends SafeTraceEvent

  @jsonHint("user_input")
  final case class UserInput(characters: Int) extends SafeTraceEvent

  @jsonHint("reasoning_context")
  final case class ReasoningContext private[trace] (
      mode: String,
      opaque: OpaqueTraceMarker,
      payload: RedactedTracePayload
  ) extends SafeTraceEvent

  object ReasoningContext:
    def redacted(mode: String): ReasoningContext =
      ReasoningContext(
        mode,
        OpaqueTraceMarker.Opaque,
        RedactedTracePayload.Redacted
      )

  @jsonHint("function_call")
  final case class FunctionCall private[trace] (
      @jsonField("call_id_digest") callId: SafeCallId,
      name: String,
      arguments: RedactedTracePayload
  ) extends SafeTraceEvent

  object FunctionCall:
    def redacted(callId: SafeCallId, name: String): FunctionCall =
      FunctionCall(callId, name, RedactedTracePayload.Redacted)

  @jsonHint("function_output")
  final case class FunctionOutput private[trace] (
      @jsonField("call_id_digest") callId: SafeCallId,
      @jsonField("is_error") isError: Boolean,
      output: RedactedTracePayload
  ) extends SafeTraceEvent

  object FunctionOutput:
    def redacted(callId: SafeCallId, isError: Boolean): FunctionOutput =
      FunctionOutput(callId, isError, RedactedTracePayload.Redacted)

  @jsonHint("final_output")
  final case class FinalOutput(characters: Int) extends SafeTraceEvent

  @jsonHint("provider_error")
  final case class ProviderError private[trace] (
      code: String,
      retryable: Boolean,
      message: RedactedTracePayload
  ) extends SafeTraceEvent

  object ProviderError:
    def redacted(code: String, retryable: Boolean): ProviderError =
      ProviderError(code, retryable, RedactedTracePayload.Redacted)

  @jsonHint("usage")
  final case class Usage(
      @jsonField("total_tokens") totalTokens: Long,
      @jsonField("input_tokens") inputTokens: Option[Long],
      @jsonField("cached_input_tokens") cachedInputTokens: Option[Long],
      @jsonField("output_tokens") outputTokens: Option[Long],
      @jsonField("reasoning_tokens") reasoningTokens: Option[Long]
  ) extends SafeTraceEvent

  @jsonHint("iteration")
  final case class Iteration(number: Int) extends SafeTraceEvent

  @jsonHint("run_complete")
  final case class RunComplete(
      outcome: String,
      iterations: Int,
      @jsonField("tool_calls") toolCalls: Int,
      @jsonField("total_tokens") totalTokens: Long,
      bdr: SafeBdrCheckpoint
  ) extends SafeTraceEvent

  given JsonCodec[SafeTraceEvent] = DeriveJsonCodec.gen[SafeTraceEvent]

final case class SafeTraceDocument(
    schema: String,
    version: Int,
    events: Chunk[SafeTraceEvent]
) derives JsonCodec

object SafeTraceDocument:
  def make(events: Chunk[SafeTraceEvent]): SafeTraceDocument =
    SafeTraceDocument("bat.dev/conformance-trace", 2, events)

object SafeTrace:
  def runStart(
      mode: RunMode,
      pins: RunPins,
      bdr: BdrStateView,
      capabilities: NegotiatedCapabilities,
      budgets: BudgetLimits
  ): SafeTraceEvent =
    SafeTraceEvent.RunStart(
      mode = mode.wire,
      pins = SafePins(
        pins.identity.backend,
        pins.identity.modelId,
        pins.identity.modelRevision,
        pins.reasoningEffort,
        pins.promptVersion,
        pins.bdrCommit
      ),
      bdr = SafeBdrCheckpoint(
        bdr.revision.value,
        bdr.runState,
        bdr.stateDigest,
        bdr.nextAction
      ),
      capabilities = SafeCapabilities(
        capabilities.required.toList.map(_.wire).sorted,
        capabilities.available.toList.map(_.wire).sorted
      ),
      budgets = SafeBudgets(
        budgets.maxIterations,
        budgets.maxToolCalls,
        budgets.maxWallTime.toMillis,
        budgets.maxTotalTokens
      )
    )

  def developer(input: bat.protocol.DeveloperInput): SafeTraceEvent =
    SafeTraceEvent.DeveloperInput(input.text.length)

  def user(input: bat.protocol.UserInput): SafeTraceEvent =
    SafeTraceEvent.UserInput(input.text.length)

  def reasoning(context: OpaqueReasoningContext): SafeTraceEvent =
    SafeTraceEvent.ReasoningContext.redacted(context.mode.wire)

  def functionCall(call: bat.protocol.FunctionCall): SafeTraceEvent =
    SafeTraceEvent.FunctionCall.redacted(
      SafeTraceEvent.SafeCallId.fromDigest(callIdDigest(call.callId)),
      call.name
    )

  def functionOutput(output: bat.protocol.FunctionOutput): SafeTraceEvent =
    SafeTraceEvent.FunctionOutput.redacted(
      SafeTraceEvent.SafeCallId.fromDigest(callIdDigest(output.callId)),
      output.isError
    )

  def finalOutput(output: bat.protocol.FinalOutput): SafeTraceEvent =
    SafeTraceEvent.FinalOutput(output.text.length)

  def providerError(error: bat.protocol.ProviderError): SafeTraceEvent =
    SafeTraceEvent.ProviderError.redacted(error.code, error.retryable)

  def usage(usage: bat.protocol.Usage): SafeTraceEvent =
    SafeTraceEvent.Usage(
      usage.totalTokens,
      usage.inputTokens,
      usage.cachedInputTokens,
      usage.outputTokens,
      usage.reasoningTokens
    )

  def iteration(number: Int): SafeTraceEvent =
    SafeTraceEvent.Iteration(number)

  def runComplete(
      outcome: RunOutcome,
      bdr: BdrStateView,
      iterations: Int,
      toolCalls: Int,
      totalTokens: Long
  ): SafeTraceEvent =
    SafeTraceEvent.RunComplete(
      outcome = outcome.wire,
      iterations = iterations,
      toolCalls = toolCalls,
      totalTokens = totalTokens,
      bdr = SafeBdrCheckpoint(
        bdr.revision.value,
        bdr.runState,
        bdr.stateDigest,
        bdr.nextAction
      )
    )

  /** Provider call IDs are correlation material, not durable evidence. They can
    * contain provider-controlled text, so traces retain only a stable digest
    * that still links a call to its output inside one document.
    */
  private def callIdDigest(callId: CallId): String =
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(callId.value.getBytes(StandardCharsets.UTF_8))
    s"sha256:${digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString}"
