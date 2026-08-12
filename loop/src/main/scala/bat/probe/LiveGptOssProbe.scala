package bat.probe

import bat.backend.gptoss.GptOssBackend
import bat.backend.harmonychat.HarmonyChatBackend
import bat.conformance.GoldenScenario
import bat.protocol.{BatError, BudgetKind, RunOutcome}
import bat.telemetry.{InMemoryTelemetry, TelemetryDocument}
import bat.transport.StreamingHttp
import bat.trace.SafeTraceEvent

import zio.{Chunk, Exit, IO, ZIO}

/** Executes BAT's pinned two-tool conformance scenario against one explicitly
  * configured GPT-OSS Responses endpoint.
  *
  * Provider and controller failures are evidence: they become sanitized
  * `Incompatible` or `Blocked` artifacts. Only BAT-internal construction,
  * telemetry, or artifact invariant failures remain in the effect error
  * channel. Interruption is never converted into a successful artifact.
  */
object LiveGptOssProbe:
  private val BlockedFailures = Set(
    "gpt_oss_rate_limited",
    "gpt_oss_request_timeout",
    "gpt_oss_unauthorized",
    "gpt_oss_endpoint_unavailable",
    "gpt_oss_open_failed",
    "gpt_oss_open_timed_out",
    "gpt_oss_body_failed",
    "gpt_oss_body_timed_out",
    "gpt_oss_response_failed",
    "gpt_oss_response_incomplete",
    "gpt_oss_stream_error",
    "gpt_oss_attempt_interrupted",
    "harmony_chat_rate_limited",
    "harmony_chat_request_timeout",
    "harmony_chat_unauthorized",
    "harmony_chat_endpoint_unavailable",
    "harmony_chat_open_failed",
    "harmony_chat_open_timed_out",
    "harmony_chat_invalid_response",
    "harmony_chat_body_failed",
    "harmony_chat_body_timed_out",
    "harmony_chat_chat_error",
    "harmony_chat_content_filtered",
    "harmony_chat_output_budget_exhausted",
    "harmony_chat_attempt_interrupted"
  )

  private val ScenarioNonconformant = "probe_scenario_nonconformant"

  def run(
      config: LiveGptOssProbeConfig,
      http: StreamingHttp
  ): IO[ProbeError, ProbeResultArtifact] =
    for
      _ <- require(
        config != null && http != null,
        "invalid_probe_dependencies",
        "live probe dependencies must not be null"
      )
      telemetry <- InMemoryTelemetry.make
      // The controller is provider-neutral, so the probe only has to choose
      // which dialect to construct. It never switches after a failure.
      backend <- ZIO.fromEither(
        (config.backendConfig match
          case ProbeBackendConfig.Responses(value) =>
            GptOssBackend.make(value, http, telemetry)
          case ProbeBackendConfig.HarmonyChat(value) =>
            HarmonyChatBackend.make(value, http, telemetry)
        ).left
          .map(_ =>
            ProbeError.make(
              "probe_backend_construction_failed",
              "live probe backend could not be constructed"
            )
          )
      )
      exit <- GoldenScenario
        .executeProbeWith(
          backend,
          telemetry,
          config.batCommit.value,
          config.reasoningEffort
        )
        .exit
      result <- exit match
        case Exit.Success(completed) =>
          for
            document <- telemetryDocument(config, telemetry)
            verdict =
              if conforms(completed) then ProbeVerdict.Compatible
              else ProbeVerdict.Nonconformant
            reason <- reasonCode(
              Option.when(verdict == ProbeVerdict.Nonconformant)(
                ScenarioNonconformant
              )
            )
            artifact <- ZIO.fromEither(
              ProbeResultArtifact.make(
                verdict,
                reasonCode = reason,
                config.batCommit,
                config.deployment,
                safeTrace = Some(completed.loopResult.traceDocument),
                document
              )
            )
          yield artifact
        case Exit.Failure(cause) if cause.isInterrupted =>
          ZIO.refailCause(
            cause.map(_ =>
              ProbeError.make(
                "probe_interrupted",
                "live probe was interrupted"
              )
            )
          )
        case Exit.Failure(cause) =>
          cause.failureOption match
            case Some(error) if isInternalDefect(error) =>
              ZIO.fail(
                ProbeError.make(
                  "probe_backend_adapter_defect",
                  "live probe backend adapter failed internally"
                )
              )
            case Some(error) =>
              for
                document <- telemetryDocument(config, telemetry)
                classified = classify(error)
                reason <- reasonCode(Some(classified._2))
                artifact <- ZIO.fromEither(
                  ProbeResultArtifact.make(
                    classified._1,
                    reasonCode = reason,
                    config.batCommit,
                    config.deployment,
                    safeTrace = None,
                    document
                  )
                )
              yield artifact
            case None =>
              ZIO.fail(
                ProbeError.make(
                  "probe_controller_defect",
                  "live probe controller failed without a typed error"
                )
              )
    yield result

  private def telemetryDocument(
      config: LiveGptOssProbeConfig,
      telemetry: InMemoryTelemetry
  ): IO[ProbeError, TelemetryDocument] =
    telemetry
      .document(config.runId, config.deployment)
      .flatMap(result =>
        ZIO.fromEither(
          result.left.map(_ =>
            ProbeError.make(
              "probe_telemetry_invalid",
              "live probe telemetry failed validation"
            )
          )
        )
      )

  private def classify(error: BatError): (ProbeVerdict, String) =
    error match
      case BatError.BudgetExceeded(BudgetKind.WallTime) =>
        ProbeVerdict.Blocked -> "probe_wall_time_exhausted"
      case _: BatError.BudgetExceeded =>
        ProbeVerdict.Nonconformant -> ScenarioNonconformant
      case failure: BatError.BackendFailure
          if BlockedFailures.contains(failure.code) =>
        ProbeVerdict.Blocked -> failure.code
      case _: BatError.ProtocolViolation | _: BatError.ToolFailure |
          _: BatError.BdrFailure | _: BatError.PrematureFinal =>
        ProbeVerdict.Nonconformant -> ScenarioNonconformant
      case _: BatError.ProviderFailure =>
        ProbeVerdict.Blocked -> "probe_provider_failure"
      case failure: BatError.BackendFailure =>
        ProbeVerdict.Incompatible -> failure.code
      case _: BatError.BackendIncompatible =>
        ProbeVerdict.Incompatible -> "backend_incompatible"

  private def isInternalDefect(error: BatError): Boolean =
    error.code == "backend_adapter_defect" ||
      error.code == "gpt_oss_attempt_defect" ||
      error.code == "harmony_chat_attempt_defect"

  private def conforms(result: GoldenScenario.BackendResult): Boolean =
    val loop = result.loopResult
    val callNames = loop.trace.collect {
      case call: SafeTraceEvent.FunctionCall => call.name
    }
    val callIds = loop.trace.collect { case call: SafeTraceEvent.FunctionCall =>
      call.callId
    }
    val outputIds = loop.trace.collect {
      case output: SafeTraceEvent.FunctionOutput => output.callId
    }
    loop.outcome == RunOutcome.ReadyForReview &&
    loop.iterations == 3 &&
    loop.toolCalls == 2 &&
    loop.bdrState.view.revision.value == 42L &&
    loop.bdrState.view.runState == "ready_for_review" &&
    result.checkpointCalls == 2 &&
    result.auditCalls == 1 &&
    result.applyCalls == 1 &&
    callNames == Chunk("bdr_audit_summary", "bdr_apply") &&
    callIds == outputIds

  private def reasonCode(
      value: Option[String]
  ): IO[ProbeError, Option[ProbeReasonCode]] =
    ZIO.foreach(value)(code =>
      ZIO.fromEither(
        ProbeReasonCode
          .from(code)
          .left
          .map(_ =>
            ProbeError.make(
              "invalid_probe_failure_code",
              "live probe produced an invalid failure code"
            )
          )
      )
    )

  private def require(
      condition: Boolean,
      code: String,
      message: String
  ): IO[ProbeError, Unit] =
    ZIO.fail(ProbeError.make(code, message)).unless(condition).unit
