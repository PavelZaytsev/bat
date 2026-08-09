package bat.telemetry

import bat.protocol.*

import zio.{Chunk, Duration}
import zio.json.ast.Json

private[telemetry] object TelemetryTestKit:
  val commit: String = "abcdef0123456789abcdef0123456789abcdef01"

  val identity: BackendIdentity = unsafe(
    BackendIdentity.make(
      backend = "gpt-oss-responses",
      modelId = "openai/gpt-oss-20b",
      modelRevision = "weights-2026-08-09"
    )
  )

  val pins: RunPins = unsafe(
    RunPins.make(
      identity,
      reasoningEffort = "high",
      promptVersion = "bdr-2.2",
      bdrCommit = commit
    )
  )

  val telemetryPins: TelemetryRunPins = TelemetryRunPins.capture(pins)

  val budgets: BudgetLimits = unsafe(
    BudgetLimits.make(
      maxIterations = 12,
      maxToolCalls = 24,
      maxWallTime = Duration.fromSeconds(600),
      maxTotalTokens = 100000L
    )
  )

  val runId: TelemetryRunId = unsafe(TelemetryRunId.from("run-0001"))

  val deployment: DeploymentFingerprint = unsafe(
    DeploymentFingerprint.make(
      identity = identity,
      runtime = Measurement.Observed("vllm"),
      runtimeRevision = Measurement.Observed("v0.10.1"),
      protocol = "responses_sse",
      templateRevision = Measurement.Observed("harmony-2026-08"),
      quantization = Measurement.Observed("mxfp4"),
      topology = Measurement.Observed("2x-h100-sxm"),
      nodeCount = Measurement.Observed(2L)
    )
  )

  val minimalDeployment: DeploymentFingerprint =
    unsafe(DeploymentFingerprint.minimal(identity, "responses_sse"))

  def attribution(
      iteration: Int,
      phase: Option[BdrPhase] = None,
      revision: Long = 1L,
      action: String = "discover_boundaries",
      sliceId: Option[String] = None,
      extraFields: Chunk[(String, Json)] = Chunk.empty
  ): BdrAttribution =
    val fields =
      Chunk("action" -> Json.Str(action)) ++
        sliceId.map(value => "slice" -> Json.Str(value)) ++
        phase.map(value => "phase" -> Json.Str(value.wire)) ++
        extraFields
    val view = unsafe(
      Revision.from(revision).flatMap { validRevision =>
        BdrStateView.make(
          revision = validRevision,
          runState = "running",
          nextAction = Json.Obj(fields),
          stateDigest = "a" * 64
        )
      }
    )
    BdrAttribution.from(iteration, view)

  def tokenMeasurements(
      total: Long,
      input: Long,
      output: Long,
      cached: Measurement[Long] = Measurement.Unavailable(
        MissingReason.NotReported
      ),
      reasoning: Measurement[Long] = Measurement.Unavailable(
        MissingReason.NotReported
      )
  ): TokenMeasurements =
    TokenMeasurements(
      total = Measurement.Observed(total),
      input = Measurement.Observed(input),
      cachedInput = cached,
      output = Measurement.Observed(output),
      reasoning = reasoning
    )

  def timing(
      total: Long,
      headers: Long,
      firstEvent: Long,
      stream: Long
  ): ModelTimingMeasurements =
    ModelTimingMeasurements(
      totalMillis = Measurement.Observed(total),
      responseHeadersMillis = Measurement.Observed(headers),
      firstEventMillis = Measurement.Observed(firstEvent),
      streamMillis = Measurement.Observed(stream)
    )

  def start: TelemetryEvent.RunStarted =
    TelemetryEvent.RunStarted(RunMode.FullWriter, telemetryPins, budgets)

  def code(value: String): TelemetryCode =
    unsafe(TelemetryCode.from(value))

  def tool(value: String): TelemetryToolName =
    unsafe(TelemetryToolName.from(value))

  def completed(
      totalTokens: Long,
      iterations: Int = 1,
      toolCalls: Int = 0,
      wallMillis: Long = 1000L,
      finalBdr: BdrAttribution = attribution(1)
  ): TelemetryEvent.RunCompleted =
    TelemetryEvent.RunCompleted(
      RunOutcome.ReadyForReview,
      iterations,
      toolCalls,
      totalTokens,
      wallMillis,
      finalBdr
    )

  def records(events: TelemetryEvent*): Chunk[TelemetryRecord] =
    Chunk.fromIterable(events.zipWithIndex.map { case (event, index) =>
      TelemetryRecord(index.toLong + 1L, event)
    })

  def unsafe[E, A](value: Either[E, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(String.valueOf(error)),
      result => result
    )
