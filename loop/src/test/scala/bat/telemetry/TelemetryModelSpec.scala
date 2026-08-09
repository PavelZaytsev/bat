package bat.telemetry

import bat.protocol.*

import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object TelemetryModelSpec extends ZIOSpecDefault:
  import TelemetryTestKit.*

  def spec: Spec[TestEnvironment, Any] =
    suite("telemetry model")(
      test("represents missing values with an explicit machine reason") {
        val missing = Measurement.fromOption[Long](None)
        val observed = Measurement.fromOption(Some(0L))
        assertTrue(
          missing == Measurement.Unavailable(MissingReason.NotReported),
          observed == Measurement.Observed(0L),
          missing.toString == "Measurement.Unavailable(not_reported)",
          !Measurement
            .Observed("payload-canary")
            .toString
            .contains(
              "payload-canary"
            )
        )
      },
      test("derives only bounded checkpoint attribution") {
        val canary = "RAW-NEXT-ACTION-CANARY"
        val value = attribution(
          iteration = 3,
          phase = Some(BdrPhase.Route),
          revision = 9L,
          action = "finish_or_recover_phase",
          sliceId = Some("S-0042"),
          extraFields = Chunk(
            "reason" -> Json.Str(canary),
            "operation" -> Json.Obj(
              Chunk("untrusted" -> Json.Str(canary))
            )
          )
        )
        assertTrue(
          value.iteration == 3,
          value.revision == 9L,
          value.action == Measurement.Observed("finish_or_recover_phase"),
          value.sliceId == Measurement.Observed("S-0042"),
          value.phase == Measurement.Observed(BdrPhase.Route),
          !value.toString.contains(canary)
        )
      },
      test("drops malformed provider-controlled attribution strings") {
        val value = attribution(
          iteration = -5,
          action = "https://secret.example/token",
          sliceId = Some("secret-slice"),
          extraFields = Chunk("phase" -> Json.Str("secret-phase"))
        )
        assertTrue(
          value.iteration == 0,
          value.action == Measurement.Unavailable(MissingReason.NotApplicable),
          value.sliceId == Measurement.Unavailable(
            MissingReason.NotApplicable
          ),
          value.phase == Measurement.Unavailable(MissingReason.NotApplicable)
        )
      },
      test("validates operator deployment values without inferring them") {
        val missing = minimalDeployment
        val url = DeploymentFingerprint.make(
          identity,
          Measurement.Observed("https://secret.example"),
          Measurement.Unavailable(MissingReason.NotConfigured),
          "responses_sse",
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured)
        )
        val credentialLike = DeploymentFingerprint.make(
          identity,
          Measurement.Observed("operator@example.com"),
          Measurement.Unavailable(MissingReason.NotConfigured),
          "responses_sse",
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured)
        )
        val endpointPath = DeploymentFingerprint.make(
          identity,
          Measurement.Observed("api.example.com/v1"),
          Measurement.Unavailable(MissingReason.NotConfigured),
          "responses_sse",
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured)
        )
        val llamaCpp = DeploymentFingerprint.make(
          identity,
          Measurement.Observed("llama.cpp"),
          Measurement.Unavailable(MissingReason.NotConfigured),
          "responses_sse",
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured)
        )
        val impossibleNodes = DeploymentFingerprint.make(
          identity,
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          "responses_sse",
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Unavailable(MissingReason.NotConfigured),
          Measurement.Observed(0L)
        )
        val hostnameIdentity = unsafe(
          BackendIdentity.make("gpt_oss", "api.example.com", "weights-v1")
        )
        val credentialIdentity = unsafe(
          BackendIdentity.make("gpt_oss", "sk-secret-canary", "weights-v1")
        )
        assertTrue(
          missing.quantization == Measurement.Unavailable(
            MissingReason.NotConfigured
          ),
          missing.topology == Measurement.Unavailable(
            MissingReason.NotConfigured
          ),
          missing.nodeCount == Measurement.Unavailable(
            MissingReason.NotConfigured
          ),
          url.isLeft,
          credentialLike.isLeft,
          endpointPath.isLeft,
          llamaCpp.isRight,
          impossibleNodes.isLeft,
          DeploymentFingerprint
            .minimal(hostnameIdentity, "responses_sse")
            .isLeft,
          DeploymentFingerprint
            .minimal(credentialIdentity, "responses_sse")
            .isLeft
        )
      },
      test("digests unsafe run pins before they reach a telemetry sink") {
        val canary = "https://user:secret@example.invalid/full prompt canary"
        val hostileIdentity = unsafe(
          BackendIdentity.make("gpt_oss", canary, canary)
        )
        val hostilePins = unsafe(
          RunPins.make(hostileIdentity, canary, canary, commit)
        )
        val captured = TelemetryRunPins.capture(hostilePins)
        val deploymentResult =
          DeploymentFingerprint.minimal(hostileIdentity, "responses_sse")
        assertTrue(
          captured.identityDigest.value.matches("^sha256:[0-9a-f]{64}$"),
          captured.reasoningEffortDigest.value.matches(
            "^sha256:[0-9a-f]{64}$"
          ),
          captured.promptVersionDigest.value.matches(
            "^sha256:[0-9a-f]{64}$"
          ),
          !captured.toString.contains(canary),
          deploymentResult.isLeft,
          TelemetryCode.capture(canary).value == "invalid_external_code"
        )
      },
      test("converts provider usage without inventing optional counts") {
        val usage = unsafe(
          Usage.make(
            totalTokens = 15L,
            inputTokens = Some(10L),
            outputTokens = Some(5L)
          )
        )
        val value = TokenMeasurements.from(usage)
        assertTrue(
          value.total == Measurement.Observed(15L),
          value.input == Measurement.Observed(10L),
          value.output == Measurement.Observed(5L),
          value.cachedInput == Measurement.Unavailable(
            MissingReason.NotReported
          ),
          value.reasoning == Measurement.Unavailable(
            MissingReason.NotReported
          )
        )
      },
      test("redacts telemetry products and clamps clock skew") {
        val canary = "TELEMETRY-SECRET-CANARY"
        val error = TelemetryError.make("provider_failed", canary)
        val event = TelemetryEvent.ModelTurn(
          attribution(1),
          ModelTurnKind.BackendFailed,
          TokenMeasurements.unavailable(MissingReason.NotReported),
          ModelTimingMeasurements.logicalTurn(2L),
          Measurement.Observed(code("provider_failed"))
        )
        assertTrue(
          !error.toString.contains(canary),
          !event.toString.contains(canary),
          !deployment.toString.contains("mxfp4"),
          TelemetryValidation.durationMillis(20L, 10L) == 0L
        )
      }
    )
