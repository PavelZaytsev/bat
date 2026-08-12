package bat.telemetry

import bat.protocol.*

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

import zio.Duration
import zio.json.ast.Json

/** Stable, payload-free telemetry validation error. */
final case class TelemetryError private (
    code: String,
    safeMessage: String
):
  override def toString: String =
    s"TelemetryError(code=$code, payload=<redacted>)"

object TelemetryError:
  private val MachineCode = "^[a-z][a-z0-9_-]{0,63}$".r

  private[telemetry] def make(
      code: String,
      safeMessage: String
  ): TelemetryError =
    val safeCode = Option(code)
      .filter(MachineCode.matches)
      .getOrElse(
        "invalid_telemetry"
      )
    val safeText = Option(safeMessage)
      .filter(_.nonEmpty)
      .map(_.take(160))
      .getOrElse("telemetry validation failed")
    TelemetryError(safeCode, safeText)

opaque type TelemetryRunId = String

object TelemetryRunId:
  private val Safe = "^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$".r

  def from(value: String): Either[TelemetryError, TelemetryRunId] =
    Option(value)
      .filter(Safe.matches)
      .filterNot { text =>
        val lower = text.toLowerCase
        List("sk-", "ghp_", "github_pat_", "xox", "akia", "bearer")
          .exists(lower.startsWith)
      }
      .toRight(
        invalid("telemetry run ID is invalid")
      )

  extension (self: TelemetryRunId) def value: String = self

  private def invalid(message: String): TelemetryError =
    TelemetryError.make("invalid_run_id", message)

/** Bounded machine code admitted to telemetry. Arbitrary provider/controller
  * strings are collapsed before they reach a sink.
  */
opaque type TelemetryCode = String

object TelemetryCode:
  private val Safe = "^[a-z][a-z0-9_-]{0,63}$".r

  def from(value: String): Either[TelemetryError, TelemetryCode] =
    Option(value)
      .filter(Safe.matches)
      .toRight(
        TelemetryError.make(
          "invalid_telemetry_code",
          "telemetry machine code is invalid"
        )
      )

  /** Total boundary for error values owned by an adapter or tool. */
  def capture(value: String): TelemetryCode =
    Option(value).filter(Safe.matches).getOrElse("invalid_external_code")

  extension (self: TelemetryCode) def value: String = self

/** Bounded tool identifier admitted to telemetry. */
opaque type TelemetryToolName = String

object TelemetryToolName:
  private val Safe = "^[A-Za-z][A-Za-z0-9_.:-]{0,127}$".r

  def from(value: String): Either[TelemetryError, TelemetryToolName] =
    Option(value)
      .filter(Safe.matches)
      .toRight(
        TelemetryError.make(
          "invalid_telemetry_tool",
          "telemetry tool name is invalid"
        )
      )

  /** Total boundary for provider-selected function names. */
  def capture(value: String): TelemetryToolName =
    Option(value).filter(Safe.matches).getOrElse("invalid_tool_name")

  extension (self: TelemetryToolName) def value: String = self

/** Domain-separated digest used when the source value is not safe to persist.
  */
opaque type TelemetryDigest = String

object TelemetryDigest:
  private val Persisted = "^sha256:[0-9a-f]{64}$".r
  private val Utf8 = StandardCharsets.UTF_8

  private[telemetry] def fromPersisted(
      value: String,
      label: String
  ): Either[TelemetryError, TelemetryDigest] =
    Option(value)
      .filter(Persisted.matches)
      .toRight(
        TelemetryError.make(
          "invalid_telemetry_digest",
          s"persisted $label digest is invalid"
        )
      )

  private[telemetry] def capture(
      domain: String,
      values: String*
  ): TelemetryDigest =
    val digest = MessageDigest.getInstance("SHA-256")
    add(digest, domain)
    values.foreach(value => add(digest, Option(value).getOrElse("<null>")))
    s"sha256:${HexFormat.of().formatHex(digest.digest())}"

  extension (self: TelemetryDigest) def value: String = self

  private def add(digest: MessageDigest, value: String): Unit =
    val bytes = value.getBytes(Utf8)
    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array())
    digest.update(bytes)

/** A safe copy of the provider identity admitted to a deployment document. */
final case class TelemetryBackendIdentity private (
    backend: String,
    modelId: String,
    modelRevision: String,
    digest: TelemetryDigest
):
  override def toString: String =
    "TelemetryBackendIdentity(payload=<redacted>)"

object TelemetryBackendIdentity:
  private val Backend = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$".r
  private val Identifier = "^[A-Za-z0-9][A-Za-z0-9._/+:-]{0,127}$".r

  def from(
      identity: BackendIdentity
  ): Either[TelemetryError, TelemetryBackendIdentity] =
    for
      _ <- require(identity != null, "deployment identity is required")
      backend <- validate(identity.backend, Backend, "backend")
      modelId <- validate(identity.modelId, Identifier, "model ID")
      modelRevision <- validate(
        identity.modelRevision,
        Identifier,
        "model revision"
      )
    yield TelemetryBackendIdentity(
      backend,
      modelId,
      modelRevision,
      identityDigest(identity)
    )

  private[telemetry] def identityDigest(
      identity: BackendIdentity
  ): TelemetryDigest =
    TelemetryDigest.capture(
      "bat.telemetry.backend-identity.v1",
      Option(identity).fold("<null>")(_.backend),
      Option(identity).fold("<null>")(_.modelId),
      Option(identity).fold("<null>")(_.modelRevision)
    )

  private[telemetry] def safeOperatorText(value: String): Boolean =
    val lower = Option(value).fold("")(_.toLowerCase)
    val hostname =
      "^(?:localhost|(?:[a-z0-9-]+\\.)+[a-z]{2,63})(?::[0-9]{1,5})?(?:/.*)?$".r
    val ipv4 =
      "^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?::[0-9]{1,5})?(?:/.*)?$".r
    val credentialPrefix =
      List("sk-", "ghp_", "github_pat_", "xox", "akia", "bearer")
        .exists(lower.startsWith)
    !lower.contains("://") &&
    !lower.contains("@") &&
    !hostname.matches(lower) &&
    !ipv4.matches(lower) &&
    lower.count(_ == ':') < 2 &&
    !credentialPrefix

  private def validate(
      value: String,
      pattern: scala.util.matching.Regex,
      label: String
  ): Either[TelemetryError, String] =
    Option(value)
      .filter(pattern.matches)
      .filter(safeOperatorText)
      .toRight(
        TelemetryError.make(
          "invalid_deployment",
          s"deployment $label is invalid"
        )
      )

  private def require(
      condition: Boolean,
      message: String
  ): Either[TelemetryError, Unit] =
    Either.cond(
      condition,
      (),
      TelemetryError.make("invalid_deployment", message)
    )

/** Run pins captured without persisting model-, prompt-, or provider-controlled
  * text. Digests keep runs correlatable with an explicit deployment document.
  */
final case class TelemetryRunPins private (
    identityDigest: TelemetryDigest,
    reasoningEffortDigest: TelemetryDigest,
    promptVersionDigest: TelemetryDigest,
    bdrCommit: String
):
  override def toString: String = "TelemetryRunPins(payload=<redacted>)"

object TelemetryRunPins:
  def capture(pins: RunPins): TelemetryRunPins =
    TelemetryRunPins(
      TelemetryBackendIdentity.identityDigest(pins.identity),
      TelemetryDigest.capture(
        "bat.telemetry.reasoning-effort.v1",
        pins.reasoningEffort
      ),
      TelemetryDigest.capture(
        "bat.telemetry.prompt-version.v1",
        pins.promptVersion
      ),
      pins.bdrCommit
    )

  private[telemetry] def fromPersisted(
      identityDigest: String,
      reasoningEffortDigest: String,
      promptVersionDigest: String,
      bdrCommit: String
  ): Either[TelemetryError, TelemetryRunPins] =
    val Commit = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
    for
      identity <- TelemetryDigest.fromPersisted(
        identityDigest,
        "backend identity"
      )
      effort <- TelemetryDigest.fromPersisted(
        reasoningEffortDigest,
        "reasoning effort"
      )
      prompt <- TelemetryDigest.fromPersisted(
        promptVersionDigest,
        "prompt version"
      )
      commit <- Option(bdrCommit)
        .filter(Commit.matches)
        .toRight(
          TelemetryError.make(
            "invalid_telemetry_commit",
            "persisted BDR commit is invalid"
          )
        )
    yield TelemetryRunPins(identity, effort, prompt, commit)

/** Why a measurement is absent. Missing values are never represented as zero.
  */
enum MissingReason(val wire: String):
  case NotReported extends MissingReason("not_reported")
  case NotObserved extends MissingReason("not_observed")
  case NotApplicable extends MissingReason("not_applicable")
  case NotConfigured extends MissingReason("not_configured")
  case Unsupported extends MissingReason("unsupported")
  case FailedBeforeMeasurement
      extends MissingReason("failed_before_measurement")

/** An observed value or an explicit reason why it is unavailable. */
enum Measurement[+A]:
  case Observed(value: A)
  case Unavailable(reason: MissingReason)

  final override def toString: String =
    this match
      case Measurement.Observed(_)         => "Measurement.Observed(<redacted>)"
      case Measurement.Unavailable(reason) =>
        s"Measurement.Unavailable(${reason.wire})"

object Measurement:
  def observed[A](value: A): Measurement[A] = Observed(value)
  def unavailable(reason: MissingReason): Measurement[Nothing] =
    Unavailable(reason)

  def fromOption[A](
      value: Option[A],
      reason: MissingReason = MissingReason.NotReported
  ): Measurement[A] =
    value.fold[Measurement[A]](Unavailable(reason))(Observed(_))

enum BdrPhase(val wire: String):
  case Expose extends BdrPhase("expose")
  case Represent extends BdrPhase("represent")
  case Route extends BdrPhase("route")
  case Collapse extends BdrPhase("collapse")
  case Saturate extends BdrPhase("saturate")
  case Falsify extends BdrPhase("falsify")

object BdrPhase:
  def from(value: String): Option[BdrPhase] =
    BdrPhase.values.find(_.wire == Option(value).fold("")(_.toLowerCase))

/** Payload-free attribution to the validated BDR checkpoint active for work. */
final case class BdrAttribution private (
    iteration: Int,
    revision: Long,
    runState: String,
    stateDigest: String,
    action: Measurement[String],
    sliceId: Measurement[String],
    phase: Measurement[BdrPhase]
):
  override def toString: String =
    s"BdrAttribution(iteration=$iteration, revision=$revision, payload=<redacted>)"

object BdrAttribution:
  private val MachineCode = "^[a-z][a-z0-9_-]{0,63}$".r
  private val Slice = "^[A-Z][A-Z0-9]*-[0-9]{1,12}$".r
  private val Digest = "^[0-9a-f]{64}$".r

  def from(iteration: Int, state: BdrStateView): BdrAttribution =
    val action = stringField(state.nextAction, "action")
      .filter(MachineCode.matches)
      .fold[Measurement[String]](
        Measurement.Unavailable(MissingReason.NotApplicable)
      )(Measurement.Observed(_))
    val slice = stringField(state.nextAction, "slice")
      .filter(Slice.matches)
      .fold[Measurement[String]](
        Measurement.Unavailable(MissingReason.NotApplicable)
      )(Measurement.Observed(_))
    val phase = stringField(state.nextAction, "phase")
      .flatMap(BdrPhase.from)
      .fold[Measurement[BdrPhase]](
        Measurement.Unavailable(MissingReason.NotApplicable)
      )(Measurement.Observed(_))
    BdrAttribution(
      Math.max(0, iteration),
      state.revision.value,
      safeMachineValue(state.runState, "unknown"),
      Option(state.stateDigest).filter(Digest.matches).getOrElse("0" * 64),
      action,
      slice,
      phase
    )

  private[telemetry] def fromPersisted(
      iteration: Int,
      revision: Long,
      runState: String,
      stateDigest: String,
      action: Measurement[String],
      sliceId: Measurement[String],
      phase: Measurement[BdrPhase]
  ): BdrAttribution =
    BdrAttribution(
      iteration,
      revision,
      runState,
      stateDigest,
      action,
      sliceId,
      phase
    )

  private def stringField(value: Json.Obj, name: String): Option[String] =
    value.fields.collectFirst { case (`name`, Json.Str(text)) => text }

  private def safeMachineValue(value: String, fallback: String): String =
    Option(value).filter(MachineCode.matches).getOrElse(fallback)

/** Operator-supplied deployment identity. BAT never infers these fields. */
final case class DeploymentFingerprint private (
    identity: TelemetryBackendIdentity,
    runtime: Measurement[String],
    runtimeRevision: Measurement[String],
    protocol: String,
    templateRevision: Measurement[String],
    quantization: Measurement[String],
    topology: Measurement[String],
    nodeCount: Measurement[Long]
):
  override def toString: String =
    "DeploymentFingerprint(payload=<redacted>)"

  /** Check the opened backend against the operator-pinned deployment without
    * exposing the sanitized deployment fields.
    */
  def matchesBackend(value: BackendIdentity): Boolean =
    value != null &&
      identity.digest == TelemetryBackendIdentity.identityDigest(value)

object DeploymentFingerprint:
  private val Identifier = "^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}$".r
  private val Protocol = "^[a-z][a-z0-9_-]{0,63}$".r

  def make(
      identity: BackendIdentity,
      runtime: Measurement[String],
      runtimeRevision: Measurement[String],
      protocol: String,
      templateRevision: Measurement[String],
      quantization: Measurement[String],
      topology: Measurement[String],
      nodeCount: Measurement[Long]
  ): Either[TelemetryError, DeploymentFingerprint] =
    for
      telemetryIdentity <- TelemetryBackendIdentity.from(identity)
      _ <- validateText(runtime, "runtime")
      _ <- validateText(runtimeRevision, "runtime revision")
      _ <- require(
        protocol != null && Protocol.matches(protocol),
        "deployment protocol is invalid"
      )
      _ <- validateText(templateRevision, "template revision")
      _ <- validateText(quantization, "quantization")
      _ <- validateText(topology, "topology")
      _ <- nodeCount match
        case Measurement.Observed(value) =>
          require(value > 0 && value <= 1024, "node count is invalid")
        case Measurement.Unavailable(_) => Right(())
    yield DeploymentFingerprint(
      telemetryIdentity,
      runtime,
      runtimeRevision,
      protocol,
      templateRevision,
      quantization,
      topology,
      nodeCount
    )

  def minimal(
      identity: BackendIdentity,
      protocol: String
  ): Either[TelemetryError, DeploymentFingerprint] =
    val missing = Measurement.Unavailable(MissingReason.NotConfigured)
    make(
      identity,
      missing,
      missing,
      protocol,
      missing,
      missing,
      missing,
      missing
    )

  private def validateText(
      value: Measurement[String],
      label: String
  ): Either[TelemetryError, Unit] =
    value match
      case Measurement.Observed(text) =>
        val explicitlyAllowedRuntime = label == "runtime" && text == "llama.cpp"
        require(
          text != null && Identifier.matches(text) &&
            !text.contains("/") &&
            (TelemetryBackendIdentity.safeOperatorText(text) ||
              explicitlyAllowedRuntime),
          s"deployment $label is invalid"
        )
      case Measurement.Unavailable(_) => Right(())

  private def require(
      condition: Boolean,
      message: String
  ): Either[TelemetryError, Unit] =
    Either.cond(
      condition,
      (),
      TelemetryError.make("invalid_deployment", message)
    )

final case class TokenMeasurements(
    total: Measurement[Long],
    input: Measurement[Long],
    cachedInput: Measurement[Long],
    output: Measurement[Long],
    reasoning: Measurement[Long]
):
  override def toString: String = "TokenMeasurements(payload=<redacted>)"

object TokenMeasurements:
  def from(usage: Usage): TokenMeasurements =
    TokenMeasurements(
      Measurement.Observed(usage.totalTokens),
      Measurement.fromOption(usage.inputTokens),
      Measurement.fromOption(usage.cachedInputTokens),
      Measurement.fromOption(usage.outputTokens),
      Measurement.fromOption(usage.reasoningTokens)
    )

  def unavailable(reason: MissingReason): TokenMeasurements =
    val missing = Measurement.Unavailable(reason)
    TokenMeasurements(missing, missing, missing, missing, missing)

final case class ModelTimingMeasurements(
    totalMillis: Measurement[Long],
    responseHeadersMillis: Measurement[Long],
    firstEventMillis: Measurement[Long],
    streamMillis: Measurement[Long]
):
  override def toString: String =
    "ModelTimingMeasurements(payload=<redacted>)"

object ModelTimingMeasurements:
  def logicalTurn(totalMillis: Long): ModelTimingMeasurements =
    val notApplicable = Measurement.Unavailable(MissingReason.NotApplicable)
    ModelTimingMeasurements(
      Measurement.Observed(Math.max(0L, totalMillis)),
      notApplicable,
      notApplicable,
      notApplicable
    )

enum ModelTurnKind(val wire: String):
  case ToolCalls extends ModelTurnKind("tool_calls")
  case Completed extends ModelTurnKind("completed")
  case ProviderFailed extends ModelTurnKind("provider_failed")
  case BackendFailed extends ModelTurnKind("backend_failed")

enum ProviderAttemptOutcome(val wire: String):
  case Completed extends ProviderAttemptOutcome("completed")
  case Rejected extends ProviderAttemptOutcome("rejected")
  case Failed extends ProviderAttemptOutcome("failed")

enum ToolExecutionOutcome(val wire: String):
  case Succeeded extends ToolExecutionOutcome("succeeded")
  case ToolError extends ToolExecutionOutcome("tool_error")
  case Failed extends ToolExecutionOutcome("failed")
  case Replayed extends ToolExecutionOutcome("replayed")

@SuppressWarnings(Array("org.wartremover.warts.Any"))
sealed trait TelemetryEvent extends Serializable:
  final override def toString: String = "TelemetryEvent(payload=<redacted>)"

object TelemetryEvent:
  final case class RunStarted(
      mode: RunMode,
      pins: TelemetryRunPins,
      budgets: BudgetLimits
  ) extends TelemetryEvent

  final case class BdrCheckpoint(attribution: BdrAttribution)
      extends TelemetryEvent

  final case class ModelTurn(
      attribution: BdrAttribution,
      kind: ModelTurnKind,
      tokens: TokenMeasurements,
      timing: ModelTimingMeasurements,
      errorCode: Measurement[TelemetryCode]
  ) extends TelemetryEvent

  final case class ProviderAttempt(
      attribution: BdrAttribution,
      attempt: Int,
      outcome: ProviderAttemptOutcome,
      timing: ModelTimingMeasurements,
      errorCode: Measurement[TelemetryCode]
  ) extends TelemetryEvent

  final case class Retry(
      attribution: BdrAttribution,
      failedAttempt: Int,
      nextAttempt: Int,
      delayMillis: Long,
      reasonCode: TelemetryCode
  ) extends TelemetryEvent

  final case class ToolExecution(
      name: TelemetryToolName,
      before: BdrAttribution,
      after: Measurement[BdrAttribution],
      outcome: ToolExecutionOutcome,
      durationMillis: Measurement[Long],
      errorCode: Measurement[TelemetryCode]
  ) extends TelemetryEvent

  final case class RunCompleted(
      outcome: RunOutcome,
      iterations: Int,
      toolCalls: Int,
      totalTokens: Long,
      wallMillis: Long,
      finalBdr: BdrAttribution
  ) extends TelemetryEvent

  final case class RunFailed(
      errorCode: TelemetryCode,
      wallMillis: Long
  ) extends TelemetryEvent

private[telemetry] object TelemetryValidation:
  def nonNegative(value: Measurement[Long]): Boolean =
    value match
      case Measurement.Observed(number) => number >= 0
      case Measurement.Unavailable(_)   => true

  def durationMillis(startNanos: Long, endNanos: Long): Long =
    val nanos = Math.max(0L, endNanos - startNanos)
    Duration.fromNanos(nanos).toMillis
