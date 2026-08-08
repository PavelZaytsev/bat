package bat.protocol

import zio.{Chunk, Duration}
import zio.json.ast.Json

sealed trait BatError extends Serializable:
  def code: String
  def safeMessage: String

object BatError:
  final case class ProtocolViolation(safeMessage: String) extends BatError:
    val code: String = "protocol_violation"

  final case class BackendIncompatible(missing: Set[Capability])
      extends BatError:
    val code: String = "backend_incompatible"
    val safeMessage: String =
      s"backend is missing required capabilities: ${missing.toList.map(_.wire).sorted.mkString(", ")}"

  final case class BudgetExceeded(kind: BudgetKind) extends BatError:
    val code: String = "budget_exceeded"
    val safeMessage: String = s"${kind.wire} budget exhausted"

  final case class ProviderFailure(error: ProviderError) extends BatError:
    val code: String = error.code
    val safeMessage: String = error.safeMessage

  final case class BackendFailure(
      errorCode: String,
      safeMessage: String,
      retryable: Boolean
  ) extends BatError:
    val code: String = errorCode

  final case class ToolFailure(tool: String, errorCode: String)
      extends BatError:
    val code: String = errorCode
    val safeMessage: String = s"tool $tool failed"

  final case class BdrFailure(errorCode: String, safeMessage: String)
      extends BatError:
    val code: String = errorCode

  final case class PrematureFinal(runState: String, nextAction: String)
      extends BatError:
    val code: String = "premature_final"
    val safeMessage: String =
      s"model returned final output while BDR state was $runState with next action $nextAction"

enum BudgetKind(val wire: String):
  case Iterations extends BudgetKind("iterations")
  case ToolCalls extends BudgetKind("tool_calls")
  case WallTime extends BudgetKind("wall_time")
  case TotalTokens extends BudgetKind("total_tokens")

enum RunMode(val wire: String):
  case Audit extends RunMode("audit")
  case FullWriter extends RunMode("full_writer")

enum RunOutcome(val wire: String):
  case AuditComplete extends RunOutcome("audit_complete")
  case ReadyForReview extends RunOutcome("ready_for_review")
  case TerminalHandoff extends RunOutcome("terminal_handoff")

enum Capability(val wire: String):
  case ReasoningContinuity extends Capability("reasoning_continuity")
  case StrictTools extends Capability("strict_tools")
  case Streaming extends Capability("streaming")
  case ParallelCalls extends Capability("parallel_calls")
  case UsageReporting extends Capability("usage_reporting")
  case ServerSideState extends Capability("server_side_state")

enum ContinuationMode(val wire: String):
  case OpaqueReplay extends ContinuationMode("opaque_replay")
  case ServerState extends ContinuationMode("server_state")

private object Validation:
  private val placeholders =
    Set("auto", "default", "latest", "unknown", "unspecified")
  private val gitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val sha256 = "^[0-9a-f]{64}$".r
  private val safeMachineCode = "^[a-z][a-z0-9_-]{0,63}$".r

  def nonBlank(value: String, label: String): Either[BatError, String] =
    Option(value)
      .filter(_.trim.nonEmpty)
      .toRight(
        BatError.ProtocolViolation(s"$label must be a non-empty string")
      )

  def pinned(value: String, label: String): Either[BatError, String] =
    nonBlank(value, label).flatMap { text =>
      if placeholders.contains(text.trim.toLowerCase) then
        Left(BatError.ProtocolViolation(s"$label must be pinned, not '$text'"))
      else Right(text)
    }

  def commit(value: String): Either[BatError, String] =
    pinned(value, "bdr_commit").flatMap { text =>
      if gitObject.matches(text) then Right(text)
      else
        Left(
          BatError.ProtocolViolation(
            "bdr_commit must be a full 40- or 64-character lowercase Git object ID"
          )
        )
    }

  def digest(value: String): Either[BatError, String] =
    Option(value)
      .filter(sha256.matches)
      .toRight(
        BatError.ProtocolViolation(
          "state_digest must be a lowercase SHA-256 digest"
        )
      )

  def machineCode(value: String, label: String): Either[BatError, String] =
    Option(value)
      .filter(safeMachineCode.matches)
      .toRight(
        BatError.ProtocolViolation(
          s"$label must be 1-64 lowercase machine-code characters"
        )
      )

  def hasSafeClockConversions(value: Duration): Boolean =
    try
      val _ = value.toNanos
      val _ = value.toMillis
      true
    catch case _: ArithmeticException => false

opaque type CallId = String

object CallId:
  def from(value: String): Either[BatError, CallId] =
    Validation.nonBlank(value, "call_id")

  extension (self: CallId) def value: String = self

opaque type Revision = Long

object Revision:
  def from(value: Long): Either[BatError, Revision] =
    if value >= 0 then Right(value)
    else Left(BatError.ProtocolViolation("BDR revision must be non-negative"))

  extension (self: Revision) def value: Long = self

final case class BackendIdentity private (
    backend: String,
    modelId: String,
    modelRevision: String
)

object BackendIdentity:
  def make(
      backend: String,
      modelId: String,
      modelRevision: String
  ): Either[BatError, BackendIdentity] =
    for
      validBackend <- Validation.pinned(backend, "backend")
      validModelId <- Validation.pinned(modelId, "model_id")
      validRevision <- Validation.pinned(modelRevision, "model_revision")
    yield BackendIdentity(validBackend, validModelId, validRevision)

final case class RunPins private (
    identity: BackendIdentity,
    reasoningEffort: String,
    promptVersion: String,
    bdrCommit: String
)

object RunPins:
  def make(
      identity: BackendIdentity,
      reasoningEffort: String,
      promptVersion: String,
      bdrCommit: String
  ): Either[BatError, RunPins] =
    for
      effort <- Validation.pinned(reasoningEffort, "reasoning_effort")
      prompt <- Validation.pinned(promptVersion, "prompt_version")
      commit <- Validation.commit(bdrCommit)
    yield RunPins(identity, effort, prompt, commit)

  def make(
      backend: String,
      modelId: String,
      modelRevision: String,
      reasoningEffort: String,
      promptVersion: String,
      bdrCommit: String
  ): Either[BatError, RunPins] =
    BackendIdentity.make(backend, modelId, modelRevision).flatMap { identity =>
      make(identity, reasoningEffort, promptVersion, bdrCommit)
    }

final case class BackendCapabilities private (supported: Set[Capability]):
  def contains(capability: Capability): Boolean = supported.contains(capability)

  def negotiate(
      mode: RunMode,
      explicitlyRequired: Set[Capability] = Set.empty
  ): Either[BatError, NegotiatedCapabilities] =
    val modeRequired = mode match
      case RunMode.Audit =>
        Set(
          Capability.UsageReporting,
          Capability.ReasoningContinuity
        )
      case RunMode.FullWriter =>
        Set(
          Capability.UsageReporting,
          Capability.ReasoningContinuity,
          Capability.StrictTools
        )
    val required = modeRequired ++ explicitlyRequired
    val missing = required -- supported
    if missing.nonEmpty then Left(BatError.BackendIncompatible(missing))
    else Right(NegotiatedCapabilities(required, supported))

object BackendCapabilities:
  def make(supported: Set[Capability]): Either[BatError, BackendCapabilities] =
    if supported.contains(Capability.ServerSideState) &&
      !supported.contains(Capability.ReasoningContinuity)
    then
      Left(
        BatError.ProtocolViolation(
          "server_side_state requires reasoning_continuity"
        )
      )
    else Right(BackendCapabilities(supported))

final case class NegotiatedCapabilities(
    required: Set[Capability],
    available: Set[Capability]
):
  def contains(capability: Capability): Boolean = available.contains(capability)

final case class BudgetLimits private (
    maxIterations: Int,
    maxToolCalls: Int,
    maxWallTime: Duration,
    maxTotalTokens: Long
)

object BudgetLimits:
  def make(
      maxIterations: Int,
      maxToolCalls: Int,
      maxWallTime: Duration,
      maxTotalTokens: Long
  ): Either[BatError, BudgetLimits] =
    if maxIterations <= 0 then
      Left(BatError.ProtocolViolation("max_iterations must be positive"))
    else if maxToolCalls <= 0 then
      Left(BatError.ProtocolViolation("max_tool_calls must be positive"))
    else if maxWallTime == null || maxWallTime == Duration.Infinity ||
      maxWallTime.isZero || maxWallTime.isNegative
    then
      Left(
        BatError.ProtocolViolation("max_wall_time must be positive and finite")
      )
    else if !Validation.hasSafeClockConversions(maxWallTime) then
      Left(
        BatError.ProtocolViolation(
          "max_wall_time is too large for clock accounting"
        )
      )
    else if maxTotalTokens <= 0 then
      Left(BatError.ProtocolViolation("max_total_tokens must be positive"))
    else
      Right(
        BudgetLimits(maxIterations, maxToolCalls, maxWallTime, maxTotalTokens)
      )

final case class TurnBudget private (
    remainingWallTime: Duration,
    remainingTotalTokens: Long
)

object TurnBudget:
  def make(
      remainingWallTime: Duration,
      remainingTotalTokens: Long
  ): Either[BatError, TurnBudget] =
    if remainingWallTime == null || remainingWallTime == Duration.Infinity ||
      remainingWallTime.isZero || remainingWallTime.isNegative
    then Left(BatError.BudgetExceeded(BudgetKind.WallTime))
    else if !Validation.hasSafeClockConversions(remainingWallTime) then
      Left(BatError.BudgetExceeded(BudgetKind.WallTime))
    else if remainingTotalTokens <= 0 then
      Left(BatError.BudgetExceeded(BudgetKind.TotalTokens))
    else Right(TurnBudget(remainingWallTime, remainingTotalTokens))

final case class DeveloperInput private (text: String)

object DeveloperInput:
  def make(text: String): Either[BatError, DeveloperInput] =
    Validation.nonBlank(text, "developer input").map(DeveloperInput(_))

final case class UserInput private (text: String)

object UserInput:
  def make(text: String): Either[BatError, UserInput] =
    Validation.nonBlank(text, "user input").map(UserInput(_))

/** Adapter-owned continuation state. BAT can inspect only its affinity metadata
  * and can pass the same concrete value back to the backend that produced it.
  */
abstract class OpaqueReasoningContext protected (
    val identity: BackendIdentity,
    val mode: ContinuationMode
):
  final override def toString: String =
    s"OpaqueReasoningContext(backend=${identity.backend}, mode=${mode.wire}, payload=<redacted>)"

final case class FunctionCall private (
    callId: CallId,
    name: String,
    arguments: Json.Obj
)

object FunctionCall:
  def make(
      callId: CallId,
      name: String,
      arguments: Json.Obj
  ): Either[BatError, FunctionCall] =
    for
      validName <- Validation.nonBlank(name, "function name")
      _ <- StrictJson.validate(arguments, "function arguments")
    yield FunctionCall(callId, validName, arguments)

final case class FunctionOutput private (
    callId: CallId,
    output: Json,
    isError: Boolean
)

object FunctionOutput:
  def make(
      callId: CallId,
      output: Json,
      isError: Boolean = false
  ): Either[BatError, FunctionOutput] =
    StrictJson.validate(output, "function output").map { _ =>
      FunctionOutput(callId, output, isError)
    }

final case class FinalOutput private (text: String)

object FinalOutput:
  def make(text: String): Either[BatError, FinalOutput] =
    Validation.nonBlank(text, "final output").map(FinalOutput(_))

final case class ProviderError private (
    code: String,
    safeMessage: String,
    retryable: Boolean
)

object ProviderError:
  def make(
      code: String,
      safeMessage: String,
      retryable: Boolean = false
  ): Either[BatError, ProviderError] =
    for
      validCode <- Validation.machineCode(code, "provider error code")
      validMessage <- Validation.nonBlank(safeMessage, "provider safe message")
    yield ProviderError(validCode, validMessage, retryable)

final case class Usage private (
    totalTokens: Long,
    inputTokens: Option[Long],
    cachedInputTokens: Option[Long],
    outputTokens: Option[Long],
    reasoningTokens: Option[Long]
)

object Usage:
  def make(
      totalTokens: Long,
      inputTokens: Option[Long] = None,
      cachedInputTokens: Option[Long] = None,
      outputTokens: Option[Long] = None,
      reasoningTokens: Option[Long] = None
  ): Either[BatError, Usage] =
    val fields = List(
      "total_tokens" -> Some(totalTokens),
      "input_tokens" -> inputTokens,
      "cached_input_tokens" -> cachedInputTokens,
      "output_tokens" -> outputTokens,
      "reasoning_tokens" -> reasoningTokens
    )
    fields.collectFirst { case (name, Some(value)) if value < 0 => name } match
      case Some(name) =>
        Left(BatError.ProtocolViolation(s"$name must be non-negative"))
      case None
          if cachedInputTokens
            .exists(cached => inputTokens.exists(cached > _)) =>
        Left(
          BatError.ProtocolViolation(
            "cached_input_tokens cannot exceed input_tokens"
          )
        )
      case None
          if reasoningTokens
            .exists(reasoning => outputTokens.exists(reasoning > _)) =>
        Left(
          BatError.ProtocolViolation(
            "reasoning_tokens cannot exceed output_tokens"
          )
        )
      case None
          if inputTokens.exists(input =>
            outputTokens.exists(output =>
              output > totalTokens || input > totalTokens - output
            )
          ) =>
        Left(
          BatError.ProtocolViolation(
            "total_tokens cannot be less than input_tokens + output_tokens"
          )
        )
      case None =>
        Right(
          Usage(
            totalTokens,
            inputTokens,
            cachedInputTokens,
            outputTokens,
            reasoningTokens
          )
        )

final case class ToolDefinition private (
    name: String,
    description: String,
    parameters: Json.Obj,
    strict: Boolean
)

object ToolDefinition:
  def make(
      name: String,
      description: String,
      parameters: Json.Obj,
      strict: Boolean = true
  ): Either[BatError, ToolDefinition] =
    for
      validName <- Validation.nonBlank(name, "tool name")
      validDescription <- Validation.nonBlank(description, "tool description")
      _ <- StrictJson.validate(parameters, "tool parameters")
    yield ToolDefinition(validName, validDescription, parameters, strict)

final case class BdrStateView private (
    revision: Revision,
    runState: String,
    nextAction: Json.Obj,
    stateDigest: String
)

object BdrStateView:
  def make(
      revision: Revision,
      runState: String,
      nextAction: Json.Obj,
      stateDigest: String
  ): Either[BatError, BdrStateView] =
    for
      validState <- Validation.nonBlank(runState, "BDR run state")
      _ <- StrictJson.validate(nextAction, "BDR next action")
      validDigest <- Validation.digest(stateDigest)
    yield BdrStateView(revision, validState, nextAction, validDigest)

sealed trait InputEvent extends Serializable

object InputEvent:
  final case class User(value: UserInput) extends InputEvent
  final case class ToolOutput(value: FunctionOutput) extends InputEvent

final case class ModelRequest[C <: OpaqueReasoningContext] private (
    pins: RunPins,
    developer: DeveloperInput,
    inputs: Chunk[InputEvent],
    tools: Chunk[ToolDefinition],
    bdrState: BdrStateView,
    iteration: Int,
    continuation: Option[C]
)

object ModelRequest:
  def make[C <: OpaqueReasoningContext](
      pins: RunPins,
      developer: DeveloperInput,
      inputs: Chunk[InputEvent],
      tools: Chunk[ToolDefinition],
      bdrState: BdrStateView,
      iteration: Int,
      continuation: Option[C]
  ): Either[BatError, ModelRequest[C]] =
    if inputs.isEmpty then
      Left(
        BatError.ProtocolViolation(
          "model request requires at least one input event"
        )
      )
    else if iteration <= 0 then
      Left(
        BatError.ProtocolViolation("model request iteration must be positive")
      )
    else
      continuation match
        case Some(context) if context.identity != pins.identity =>
          Left(
            BatError.ProtocolViolation(
              "opaque reasoning context cannot cross backend or model identity"
            )
          )
        case _ =>
          Right(
            ModelRequest(
              pins,
              developer,
              inputs,
              tools,
              bdrState,
              iteration,
              continuation
            )
          )

sealed trait ModelTurn[+C <: OpaqueReasoningContext] extends Serializable:
  def usage: Usage

object ModelTurn:
  final case class ToolCalls[C <: OpaqueReasoningContext] private[protocol] (
      context: C,
      calls: Chunk[FunctionCall],
      usage: Usage
  ) extends ModelTurn[C]

  final case class Completed private[protocol] (
      output: FinalOutput,
      usage: Usage
  ) extends ModelTurn[Nothing]

  final case class Failed private[protocol] (
      error: ProviderError,
      usage: Usage
  ) extends ModelTurn[Nothing]

  def toolCalls[C <: OpaqueReasoningContext](
      context: C,
      calls: Chunk[FunctionCall],
      usage: Usage
  ): Either[BatError, ModelTurn[C]] =
    if context == null then
      Left(
        BatError.ProtocolViolation(
          "tool turn requires opaque reasoning context"
        )
      )
    else if calls.isEmpty then
      Left(
        BatError.ProtocolViolation(
          "tool turn requires at least one function call"
        )
      )
    else if calls.map(_.callId).toSet.size != calls.size then
      Left(
        BatError.ProtocolViolation(
          "tool turn contains duplicate call_id values"
        )
      )
    else Right(ToolCalls(context, calls, usage))

  def completed(output: FinalOutput, usage: Usage): ModelTurn[Nothing] =
    Completed(output, usage)

  def failed(error: ProviderError, usage: Usage): ModelTurn[Nothing] =
    Failed(error, usage)

final case class RunSpec private (
    mode: RunMode,
    pins: RunPins,
    developer: DeveloperInput,
    user: UserInput,
    budgets: BudgetLimits,
    requiredCapabilities: Set[Capability]
)

object RunSpec:
  def make(
      mode: RunMode,
      pins: RunPins,
      developer: DeveloperInput,
      user: UserInput,
      budgets: BudgetLimits,
      requiredCapabilities: Set[Capability] = Set.empty
  ): RunSpec =
    RunSpec(mode, pins, developer, user, budgets, requiredCapabilities)
