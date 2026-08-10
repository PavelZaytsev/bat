package bat.probe

import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

import bat.protocol.*
import bat.telemetry.*
import bat.trace.{SafeTraceDocument, SafeTraceEvent}
import bat.transport.Secret

import zio.{Chunk, Duration}
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ProbeArtifactSpec extends ZIOSpecDefault:
  private val Commit = "0123456789abcdef0123456789abcdef01234567"
  private val EndpointCanary = "artifact-endpoint-canary.invalid"
  private val CredentialCanary = "ARTIFACT_CREDENTIAL_CANARY_447a"
  private val ReasoningCanary = "RAW_REASONING_CANARY_118e"

  private val config = unsafe(
    LiveGptOssProbeConfig.make(
      endpoint = s"https://$EndpointCanary",
      credential = Some(unsafe(Secret.from(CredentialCanary))),
      modelId = "openai/gpt-oss-20b",
      weightRevision = "weights-2026-08-09",
      runtime = "llama.cpp",
      runtimeRevision = "b6200",
      harmonyTemplateRevision = "harmony-2026-08",
      quantization = "mxfp4",
      topologyClass = "exo_thunderbolt",
      nodeCount = 3L,
      runId = "probe-run-0001",
      batCommit = Commit,
      outputDirectory = Path.of("/private/tmp/probe-results")
    )
  )

  def spec: Spec[TestEnvironment, Any] =
    suite("GPT-OSS probe artifact")(
      test("renders one deterministic sanitized compatible envelope") {
        val telemetry = completedTelemetry(config)
        val trace = safeTrace
        val first = unsafe(
          ProbeResultArtifact.make(
            ProbeVerdict.Compatible,
            None,
            config.batCommit,
            config.deployment,
            Some(trace),
            telemetry
          )
        )
        val second = unsafe(
          ProbeResultArtifact.make(
            ProbeVerdict.Compatible,
            None,
            config.batCommit,
            config.deployment,
            Some(trace),
            telemetry
          )
        )
        val encoded = first.canonicalJson
        val forbidden = List(
          EndpointCanary,
          CredentialCanary,
          ReasoningCanary,
          "function_call_output",
          "call-raw-0001"
        )

        assertTrue(
          ProbeResultArtifact.Schema == "bat.dev/gpt-oss-probe-result",
          ProbeResultArtifact.Version == 1,
          first.verdict == ProbeVerdict.Compatible,
          first.reasonCode.isEmpty,
          first.safeTrace.contains(trace),
          first.telemetry == telemetry,
          first.canonicalJson == second.canonicalJson,
          first.canonicalJson == unsafe(
            StrictJson.canonical(first.json, "probe test")
          ),
          encoded.contains(
            "\"schema\":\"bat.dev/gpt-oss-probe-result\""
          ),
          encoded.contains("\"version\":1"),
          encoded.contains("\"verdict\":\"compatible\""),
          encoded.contains(s"\"bat_commit\":\"$Commit\""),
          encoded.contains("\"protocol\":\"responses_sse\""),
          encoded.contains("\"safe_trace\":{"),
          encoded.contains("\"telemetry\":{"),
          encoded.contains(
            s"\"safe_trace_sha256\":\"${first.safeTraceSha256}\""
          ),
          encoded.contains(
            s"\"telemetry_sha256\":\"${first.telemetrySha256}\""
          ),
          encoded.contains("\"run_id\":\"probe-run-0001\""),
          encoded.contains("\"payload\":\"<redacted>\""),
          forbidden.forall(value => !encoded.contains(value)),
          first.safeTraceJson == trace.toJson,
          first.telemetryJson == unsafe(telemetry.canonicalJson),
          first.safeTraceSha256 == sha256(first.safeTraceJson),
          first.telemetrySha256 == sha256(first.telemetryJson),
          !first.toString.contains(Commit),
          !first.toString.contains("gpt-oss-20b"),
          first.toString.contains("payload=<redacted>")
        )
      },
      test("represents blocked and incompatible failures without a trace") {
        val telemetry = failedTelemetry(config, "probe_transport_failed")
        val blockedReason =
          unsafe(ProbeReasonCode.from("transport_unavailable"))
        val incompatibleReason = unsafe(
          ProbeReasonCode.from("responses_continuation_invalid")
        )
        val blocked = ProbeResultArtifact.make(
          ProbeVerdict.Blocked,
          Some(blockedReason),
          config.batCommit,
          config.deployment,
          None,
          telemetry
        )
        val incompatible = ProbeResultArtifact.make(
          ProbeVerdict.Incompatible,
          Some(incompatibleReason),
          config.batCommit,
          config.deployment,
          None,
          telemetry
        )

        assertTrue(
          blocked.exists(_.canonicalJson.contains("\"verdict\":\"blocked\"")),
          blocked.exists(
            _.canonicalJson.contains(
              "\"reason_code\":\"transport_unavailable\""
            )
          ),
          blocked.exists(_.canonicalJson.contains("\"safe_trace\":null")),
          blocked.exists(_.safeTraceJson == "null"),
          blocked.exists(value => value.safeTraceSha256 == sha256("null")),
          incompatible.exists(
            _.canonicalJson.contains("\"verdict\":\"incompatible\"")
          ),
          incompatible.exists(
            _.canonicalJson.contains(
              "\"reason_code\":\"responses_continuation_invalid\""
            )
          )
        )
      },
      test("allows nonconformance after either terminal outcome") {
        val reason = unsafe(ProbeReasonCode.from("golden_contract_failed"))
        val afterCompletion = ProbeResultArtifact.make(
          ProbeVerdict.Nonconformant,
          Some(reason),
          config.batCommit,
          config.deployment,
          Some(safeTrace),
          completedTelemetry(config)
        )
        val afterFailure = ProbeResultArtifact.make(
          ProbeVerdict.Nonconformant,
          Some(reason),
          config.batCommit,
          config.deployment,
          None,
          failedTelemetry(config, "probe_contract_failed")
        )

        assertTrue(
          afterCompletion.exists(
            _.canonicalJson.contains("\"verdict\":\"nonconformant\"")
          ),
          afterCompletion.exists(_.safeTrace.nonEmpty),
          afterFailure.exists(_.safeTrace.isEmpty)
        )
      },
      test("requires verdict, trace, reason, and telemetry terminal to agree") {
        val complete = completedTelemetry(config)
        val failed = failedTelemetry(config, "probe_transport_failed")
        val reason = unsafe(ProbeReasonCode.from("transport_unavailable"))
        val compatibleWithoutTrace = ProbeResultArtifact.make(
          ProbeVerdict.Compatible,
          None,
          config.batCommit,
          config.deployment,
          None,
          complete
        )
        val compatibleWithReason = ProbeResultArtifact.make(
          ProbeVerdict.Compatible,
          Some(reason),
          config.batCommit,
          config.deployment,
          Some(safeTrace),
          complete
        )
        val blockedWithoutReason = ProbeResultArtifact.make(
          ProbeVerdict.Blocked,
          None,
          config.batCommit,
          config.deployment,
          None,
          failed
        )
        val compatibleFailure = ProbeResultArtifact.make(
          ProbeVerdict.Compatible,
          None,
          config.batCommit,
          config.deployment,
          Some(safeTrace),
          failed
        )
        val blockedCompletion = ProbeResultArtifact.make(
          ProbeVerdict.Blocked,
          Some(reason),
          config.batCommit,
          config.deployment,
          None,
          complete
        )

        assertTrue(
          compatibleWithoutTrace.left.exists(
            _.code == "invalid_compatible_result"
          ),
          compatibleWithReason.left.exists(
            _.code == "invalid_compatible_result"
          ),
          blockedWithoutReason.left.exists(
            _.code == "invalid_non_compatible_result"
          ),
          compatibleFailure.left.exists(
            _.code == "probe_terminal_mismatch"
          ),
          blockedCompletion.left.exists(
            _.code == "probe_terminal_mismatch"
          )
        )
      },
      test("rejects deployment mismatch and a forged trace envelope") {
        val other = unsafe(
          LiveGptOssProbeConfig.make(
            endpoint = "https://other.invalid",
            credential = None,
            modelId = "openai/gpt-oss-20b",
            weightRevision = "weights-2026-08-10",
            runtime = "llama.cpp",
            runtimeRevision = "b6200",
            harmonyTemplateRevision = "harmony-2026-08",
            quantization = "mxfp4",
            topologyClass = "exo_thunderbolt",
            nodeCount = 3L,
            runId = "probe-run-0002",
            batCommit = Commit,
            outputDirectory = Path.of("/private/tmp/probe-results-2")
          )
        )
        val mismatched = ProbeResultArtifact.make(
          ProbeVerdict.Compatible,
          None,
          config.batCommit,
          other.deployment,
          Some(safeTrace),
          completedTelemetry(config)
        )
        val forged = ProbeResultArtifact.make(
          ProbeVerdict.Compatible,
          None,
          config.batCommit,
          config.deployment,
          Some(SafeTraceDocument("forged", 99, Chunk.empty)),
          completedTelemetry(config)
        )

        assertTrue(
          mismatched.left.exists(_.code == "probe_deployment_mismatch"),
          forged.left.exists(_.code == "invalid_safe_trace")
        )
      },
      test("admits only bounded reason codes and redacts rejected values") {
        val canary = s"provider said $ReasoningCanary"
        val invalid = ProbeReasonCode.from(canary)
        val tooLong = ProbeReasonCode.from("a" * 65)
        val valid = ProbeReasonCode.from("responses_sse_invalid")
        val rendered = invalid.left.toOption.get.toString

        assertTrue(
          invalid.isLeft,
          tooLong.isLeft,
          valid.isRight,
          !rendered.contains(ReasoningCanary),
          rendered.contains("payload=<redacted>")
        )
      }
    )

  private def safeTrace: SafeTraceDocument =
    SafeTraceDocument.make(
      Chunk(
        SafeTraceEvent.Iteration(1),
        SafeTraceEvent.ReasoningContext.redacted("opaque_replay")
      )
    )

  private def completedTelemetry(
      value: LiveGptOssProbeConfig
  ): TelemetryDocument =
    val attribution = bdrAttribution(1)
    val tokens = TokenMeasurements(
      total = Measurement.Observed(30L),
      input = Measurement.Observed(20L),
      cachedInput = Measurement.Observed(5L),
      output = Measurement.Observed(10L),
      reasoning = Measurement.Observed(8L)
    )
    document(
      value,
      Chunk(
        start(value),
        TelemetryEvent.ModelTurn(
          attribution,
          ModelTurnKind.Completed,
          tokens,
          ModelTimingMeasurements.logicalTurn(100L),
          Measurement.Unavailable(MissingReason.NotApplicable)
        ),
        TelemetryEvent.RunCompleted(
          RunOutcome.ReadyForReview,
          iterations = 1,
          toolCalls = 0,
          totalTokens = 30L,
          wallMillis = 120L,
          finalBdr = attribution
        )
      )
    )

  private def failedTelemetry(
      value: LiveGptOssProbeConfig,
      error: String
  ): TelemetryDocument =
    document(
      value,
      Chunk(
        start(value),
        TelemetryEvent.RunFailed(
          unsafe(TelemetryCode.from(error)),
          wallMillis = 25L
        )
      )
    )

  private def start(
      value: LiveGptOssProbeConfig
  ): TelemetryEvent.RunStarted =
    val pins = unsafe(
      RunPins.make(
        value.identity,
        reasoningEffort = "high",
        promptVersion = "bdr-probe-v1",
        bdrCommit = value.batCommit.value
      )
    )
    val budgets = unsafe(
      BudgetLimits.make(
        maxIterations = 3,
        maxToolCalls = 2,
        maxWallTime = Duration.fromSeconds(600),
        maxTotalTokens = 100000L
      )
    )
    TelemetryEvent.RunStarted(
      RunMode.FullWriter,
      TelemetryRunPins.capture(pins),
      budgets
    )

  private def bdrAttribution(iteration: Int): BdrAttribution =
    val state = unsafe(
      Revision
        .from(0L)
        .flatMap(revision =>
          BdrStateView.make(
            revision = revision,
            runState = "running",
            nextAction = Json.Obj(
              Chunk(
                "action" -> Json.Str("probe_conformance"),
                "phase" -> Json.Str("falsify")
              )
            ),
            stateDigest = "a" * 64
          )
        )
    )
    BdrAttribution.from(iteration, state)

  private def document(
      value: LiveGptOssProbeConfig,
      events: Chunk[TelemetryEvent]
  ): TelemetryDocument =
    val records = events.zipWithIndex.map { case (event, index) =>
      TelemetryRecord(index.toLong + 1L, event)
    }
    unsafe(TelemetryDocument.from(value.runId, value.deployment, records))

  private def unsafe[E, A](value: Either[E, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(String.valueOf(error)),
      identity
    )

  private def sha256(value: String): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest
          .getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8))
      )
