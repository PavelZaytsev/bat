package bat.telemetry

import java.math.{BigDecimal as JBigDecimal}

import bat.protocol.*

import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object TelemetryDocumentSpec extends ZIOSpecDefault:
  import TelemetryTestKit.*

  private val noError: Measurement[TelemetryCode] =
    Measurement.Unavailable(MissingReason.NotApplicable)

  def spec: Spec[TestEnvironment, Any] =
    suite("telemetry document")(
      test("renders deterministic canonical JSON with a pinned schema") {
        val bdr =
          attribution(1, Some(BdrPhase.Expose), sliceId = Some("S-0001"))
        val events = records(
          start,
          TelemetryEvent.BdrCheckpoint(attribution(0)),
          TelemetryEvent.ProviderAttempt(
            bdr,
            1,
            ProviderAttemptOutcome.Rejected,
            timing(100L, 10L, 20L, 80L),
            Measurement.Observed(code("gpt_oss_rate_limited"))
          ),
          TelemetryEvent.Retry(
            bdr,
            1,
            2,
            5L,
            code("gpt_oss_rate_limited")
          ),
          TelemetryEvent.ProviderAttempt(
            bdr,
            2,
            ProviderAttemptOutcome.Completed,
            timing(800L, 100L, 150L, 600L),
            noError
          ),
          TelemetryEvent.ModelTurn(
            bdr,
            ModelTurnKind.Completed,
            tokenMeasurements(10L, 6L, 4L),
            ModelTimingMeasurements.logicalTurn(850L),
            noError
          ),
          completed(10L, finalBdr = bdr)
        )
        val first = unsafe(TelemetryDocument.from(runId, deployment, events))
        val second = unsafe(TelemetryDocument.from(runId, deployment, events))
        val encoded1 = unsafe(first.canonicalJson)
        val encoded2 = unsafe(second.canonicalJson)
        assertTrue(
          TelemetryDocument.Schema == "bat.dev/run-telemetry",
          TelemetryDocument.Version == 1,
          encoded1 == encoded2,
          encoded1.contains("\"schema\":\"bat.dev/run-telemetry\""),
          encoded1.contains("\"version\":1"),
          first.summary.providerAttempts == 2,
          first.summary.retries == 1,
          encoded1 == unsafe(StrictJson.canonical(first.json, "test telemetry"))
        )
      },
      test("encodes unavailable measurements as null plus an explicit reason") {
        val bdr = attribution(1)
        val events = records(
          start,
          TelemetryEvent.ModelTurn(
            bdr,
            ModelTurnKind.Completed,
            tokenMeasurements(10L, 6L, 4L),
            ModelTimingMeasurements.logicalTurn(10L),
            noError
          ),
          completed(10L, finalBdr = bdr)
        )
        val document = unsafe(
          TelemetryDocument.from(runId, minimalDeployment, events)
        )
        val encoded = unsafe(document.canonicalJson)
        assertTrue(
          encoded.contains(
            "\"runtime\":{\"unavailable_reason\":\"not_configured\",\"value\":null}"
          ),
          encoded.contains(
            "\"cached_input\":{\"unavailable_reason\":\"not_reported\",\"value\":null}"
          ),
          encoded.contains(
            "\"response_headers_millis\":{\"unavailable_reason\":\"not_applicable\",\"value\":null}"
          ),
          !encoded.contains(
            "\"cached_input\":{\"unavailable_reason\":null,\"value\":0}"
          )
        )
      },
      test("requires completed model-turn totals to reconcile") {
        val first = attribution(1, Some(BdrPhase.Expose))
        val second = attribution(2, Some(BdrPhase.Represent), revision = 2L)
        val turn1 = TelemetryEvent.ModelTurn(
          first,
          ModelTurnKind.ToolCalls,
          tokenMeasurements(10L, 7L, 3L),
          ModelTimingMeasurements.logicalTurn(10L),
          noError
        )
        val turn2 = TelemetryEvent.ModelTurn(
          second,
          ModelTurnKind.Completed,
          tokenMeasurements(20L, 12L, 8L),
          ModelTimingMeasurements.logicalTurn(20L),
          noError
        )
        val valid = TelemetryDocument.from(
          runId,
          deployment,
          records(start, turn1, turn2, completed(30L, 2, finalBdr = second))
        )
        val invalid = TelemetryDocument.from(
          runId,
          deployment,
          records(start, turn1, turn2, completed(31L, 2, finalBdr = second))
        )
        assertTrue(
          valid.exists(
            _.summary.measurements.tokens.total == Measurement.Observed(30L)
          ),
          invalid.left.exists(_.code == "invalid_telemetry_document")
        )
      },
      test("keeps failed runs even when token reconciliation is unavailable") {
        val failed = TelemetryDocument.from(
          runId,
          deployment,
          records(
            start,
            TelemetryEvent.ModelTurn(
              attribution(1),
              ModelTurnKind.BackendFailed,
              TokenMeasurements.unavailable(
                MissingReason.FailedBeforeMeasurement
              ),
              ModelTimingMeasurements.logicalTurn(20L),
              Measurement.Observed(code("gpt_oss_protocol_violation"))
            ),
            TelemetryEvent.RunFailed(code("gpt_oss_protocol_violation"), 25L)
          )
        )
        assertTrue(
          failed.exists(
            _.summary.measurements.tokens.total == Measurement.Unavailable(
              MissingReason.FailedBeforeMeasurement
            )
          ),
          failed.exists(
            _.summary.measurements.wallMillis == Measurement.Observed(25L)
          )
        )
      },
      test("rejects broken sequence, start, and terminal envelopes") {
        val finalEvent =
          completed(0L, iterations = 0, finalBdr = attribution(0))
        val wrongSequence = Chunk(
          TelemetryRecord(2L, start),
          TelemetryRecord(3L, finalEvent)
        )
        val missingStart = records(finalEvent)
        val startNotFirst = records(
          TelemetryEvent.BdrCheckpoint(attribution(0)),
          start,
          finalEvent
        )
        val duplicateStart = records(start, start, finalEvent)
        val missingTerminal =
          records(start, TelemetryEvent.BdrCheckpoint(attribution(0)))
        val terminalNotLast = records(
          start,
          TelemetryEvent.RunFailed(code("provider_failed"), 1L),
          TelemetryEvent.BdrCheckpoint(attribution(0))
        )
        val duplicateTerminal = records(
          start,
          TelemetryEvent.RunFailed(code("provider_failed"), 1L),
          finalEvent
        )
        assertTrue(
          TelemetryDocument.from(runId, deployment, wrongSequence).isLeft,
          TelemetryDocument.from(runId, deployment, missingStart).isLeft,
          TelemetryDocument.from(runId, deployment, startNotFirst).isLeft,
          TelemetryDocument.from(runId, deployment, duplicateStart).isLeft,
          TelemetryDocument.from(runId, deployment, missingTerminal).isLeft,
          TelemetryDocument.from(runId, deployment, terminalNotLast).isLeft,
          TelemetryDocument.from(runId, deployment, duplicateTerminal).isLeft
        )
      },
      test("rejects causally impossible event histories and measurements") {
        val first = attribution(1, revision = 2L)
        val older = attribution(2, revision = 1L)
        val providerError = Measurement.Observed(code("provider_failed"))
        val retryWithoutAttempt = records(
          start,
          TelemetryEvent.Retry(
            first,
            1,
            2,
            0L,
            code("provider_failed")
          ),
          TelemetryEvent.RunFailed(code("provider_failed"), 1L)
        )
        val attemptStartingAtTwo = records(
          start,
          TelemetryEvent.ProviderAttempt(
            first,
            2,
            ProviderAttemptOutcome.Rejected,
            timing(10L, 1L, 2L, 8L),
            providerError
          ),
          TelemetryEvent.RunFailed(code("provider_failed"), 10L)
        )
        val changedRetryAttribution = records(
          start,
          TelemetryEvent.ProviderAttempt(
            first,
            1,
            ProviderAttemptOutcome.Rejected,
            timing(10L, 1L, 2L, 8L),
            providerError
          ),
          TelemetryEvent.Retry(
            first,
            1,
            2,
            0L,
            code("provider_failed")
          ),
          TelemetryEvent.ProviderAttempt(
            attribution(1, revision = 3L),
            2,
            ProviderAttemptOutcome.Completed,
            timing(10L, 1L, 2L, 8L),
            noError
          ),
          TelemetryEvent.RunFailed(code("provider_failed"), 10L)
        )
        val toolAfterFinal = records(
          start,
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.Completed,
            tokenMeasurements(2L, 1L, 1L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          TelemetryEvent.ToolExecution(
            tool("read_file"),
            first,
            Measurement.Observed(first),
            ToolExecutionOutcome.Succeeded,
            Measurement.Observed(1L),
            noError
          ),
          completed(2L, finalBdr = first)
        )
        val stateChangingReplay = records(
          start,
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.ToolCalls,
            tokenMeasurements(2L, 1L, 1L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          TelemetryEvent.ToolExecution(
            tool("read_file"),
            first,
            Measurement.Observed(attribution(1, revision = 3L)),
            ToolExecutionOutcome.Replayed,
            Measurement.Observed(1L),
            noError
          ),
          TelemetryEvent.RunFailed(code("provider_failed"), 1L)
        )
        val decreasingRevision = records(
          start,
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.ToolCalls,
            tokenMeasurements(2L, 1L, 1L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          TelemetryEvent.ModelTurn(
            older,
            ModelTurnKind.Completed,
            tokenMeasurements(2L, 1L, 1L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          completed(4L, iterations = 2, finalBdr = older)
        )
        val contradictoryOutcome = records(
          start,
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.Completed,
            tokenMeasurements(2L, 1L, 1L),
            ModelTimingMeasurements.logicalTurn(1L),
            providerError
          ),
          completed(2L, finalBdr = first)
        )
        val impossibleTokens = records(
          start,
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.Completed,
            tokenMeasurements(5L, 4L, 2L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          completed(5L, finalBdr = first)
        )
        val impossibleTiming = records(
          start,
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.Completed,
            tokenMeasurements(2L, 1L, 1L),
            timing(10L, 2L, 6L, 6L),
            noError
          ),
          completed(2L, finalBdr = first)
        )
        val candidates = Chunk(
          retryWithoutAttempt,
          attemptStartingAtTwo,
          changedRetryAttribution,
          toolAfterFinal,
          stateChangingReplay,
          decreasingRevision,
          contradictoryOutcome,
          impossibleTokens,
          impossibleTiming
        )
        assertTrue(
          candidates.forall(events =>
            TelemetryDocument.from(runId, deployment, events).isLeft
          )
        )
      },
      test("allows a backend timeout while a retry is pending") {
        val bdr = attribution(1)
        val error = Measurement.Observed(code("gpt_oss_rate_limited"))
        val events = records(
          start,
          TelemetryEvent.ProviderAttempt(
            bdr,
            1,
            ProviderAttemptOutcome.Rejected,
            timing(10L, 1L, 2L, 8L),
            error
          ),
          TelemetryEvent.Retry(
            bdr,
            1,
            2,
            5L,
            code("gpt_oss_rate_limited")
          ),
          TelemetryEvent.ModelTurn(
            bdr,
            ModelTurnKind.BackendFailed,
            TokenMeasurements.unavailable(
              MissingReason.FailedBeforeMeasurement
            ),
            ModelTimingMeasurements.logicalTurn(10L),
            Measurement.Observed(code("budget_exceeded"))
          ),
          TelemetryEvent.RunFailed(code("budget_exceeded"), 10L)
        )
        assertTrue(TelemetryDocument.from(runId, deployment, events).isRight)
      },
      test("aggregates phases in BDR order with tool attribution") {
        val expose =
          attribution(
            2,
            Some(BdrPhase.Expose),
            revision = 2L,
            sliceId = Some("S-0002")
          )
        val route = attribution(
          1,
          Some(BdrPhase.Route),
          revision = 1L,
          sliceId = Some("S-0001")
        )
        val noPhase = attribution(3, None, revision = 3L)
        val events = records(
          start,
          TelemetryEvent.ModelTurn(
            route,
            ModelTurnKind.ToolCalls,
            tokenMeasurements(10L, 7L, 3L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          TelemetryEvent.ToolExecution(
            tool("bdr_apply"),
            route,
            Measurement.Observed(route),
            ToolExecutionOutcome.Succeeded,
            Measurement.Observed(2L),
            noError
          ),
          TelemetryEvent.ModelTurn(
            expose,
            ModelTurnKind.ToolCalls,
            tokenMeasurements(5L, 3L, 2L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          TelemetryEvent.ToolExecution(
            tool("read_file"),
            expose,
            Measurement.Observed(expose),
            ToolExecutionOutcome.Succeeded,
            Measurement.Observed(1L),
            noError
          ),
          TelemetryEvent.ToolExecution(
            tool("git_status"),
            expose,
            Measurement.Observed(expose),
            ToolExecutionOutcome.Replayed,
            Measurement.Unavailable(MissingReason.NotApplicable),
            noError
          ),
          TelemetryEvent.ModelTurn(
            noPhase,
            ModelTurnKind.Completed,
            tokenMeasurements(7L, 5L, 2L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          completed(22L, iterations = 3, toolCalls = 2, finalBdr = noPhase)
        )
        val document = unsafe(
          TelemetryDocument.from(runId, deployment, events)
        )
        val phases = document.summary.phases
        assertTrue(
          document.summary.toolExecutions == 2,
          phases.map(_.phase) == Chunk(
            Measurement.Observed(BdrPhase.Expose),
            Measurement.Observed(BdrPhase.Route),
            Measurement.Unavailable(MissingReason.NotApplicable)
          ),
          phases.map(_.modelTurns) == Chunk(1, 1, 1),
          phases.map(_.toolExecutions) == Chunk(1, 1, 0),
          phases.map(_.tokens.total) == Chunk(
            Measurement.Observed(5L),
            Measurement.Observed(10L),
            Measurement.Observed(7L)
          )
        )
      },
      test("derives throughput, mean first event, and node-hours") {
        val first = attribution(1, Some(BdrPhase.Expose))
        val second = attribution(2, Some(BdrPhase.Represent), revision = 2L)
        val events = records(
          start,
          TelemetryEvent.ProviderAttempt(
            first,
            1,
            ProviderAttemptOutcome.Completed,
            timing(700L, 50L, 100L, 500L),
            noError
          ),
          TelemetryEvent.ModelTurn(
            first,
            ModelTurnKind.ToolCalls,
            tokenMeasurements(70L, 20L, 50L),
            ModelTimingMeasurements.logicalTurn(700L),
            noError
          ),
          TelemetryEvent.ProviderAttempt(
            second,
            1,
            ProviderAttemptOutcome.Completed,
            timing(800L, 60L, 300L, 500L),
            noError
          ),
          TelemetryEvent.ModelTurn(
            second,
            ModelTurnKind.Completed,
            tokenMeasurements(50L, 20L, 30L),
            ModelTimingMeasurements.logicalTurn(800L),
            noError
          ),
          completed(
            120L,
            iterations = 2,
            wallMillis = 1800000L,
            finalBdr = second
          )
        )
        val measurements = unsafe(
          TelemetryDocument.from(runId, deployment, events)
        ).summary.measurements
        assertTrue(
          decimalEquals(measurements.meanFirstEventMillis, "200"),
          decimalEquals(measurements.outputTokensPerSecond, "80"),
          decimalEquals(measurements.nodeHours, "1"),
          measurements.costUsd == Measurement.Unavailable(
            MissingReason.NotConfigured
          )
        )
      },
      test("does not invent throughput or node-hours") {
        val bdr = attribution(1)
        val events = records(
          start,
          TelemetryEvent.ModelTurn(
            bdr,
            ModelTurnKind.Completed,
            tokenMeasurements(10L, 6L, 4L),
            ModelTimingMeasurements.logicalTurn(10L),
            noError
          ),
          completed(10L, finalBdr = bdr)
        )
        val measurements = unsafe(
          TelemetryDocument.from(runId, minimalDeployment, events)
        ).summary.measurements
        assertTrue(
          measurements.outputTokensPerSecond == Measurement.Unavailable(
            MissingReason.NotObserved
          ),
          measurements.nodeHours == Measurement.Unavailable(
            MissingReason.NotConfigured
          )
        )
      },
      test("never serializes unselected BDR payload canaries") {
        val canary = "DO-NOT-PERSIST-RAW-BDR-PAYLOAD"
        val bdr = attribution(
          1,
          extraFields = Chunk(
            "reason" -> Json.Str(canary),
            "operation" -> Json.Obj(
              Chunk("secret" -> Json.Str(canary))
            )
          )
        )
        val events = records(
          start,
          TelemetryEvent.ModelTurn(
            bdr,
            ModelTurnKind.Completed,
            tokenMeasurements(1L, 1L, 0L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          completed(1L, finalBdr = bdr)
        )
        val document = unsafe(
          TelemetryDocument.from(runId, minimalDeployment, events)
        )
        val encoded = unsafe(document.canonicalJson)
        assertTrue(
          !encoded.contains(canary),
          !document.toString.contains(canary),
          !events.mkString.contains(canary)
        )
      },
      test("never serializes reasoning-effort or prompt-version pins") {
        val reasoningCanary = "REASONING EFFORT SECRET CANARY"
        val promptCanary = "PROMPT VERSION SECRET CANARY"
        val privatePins = unsafe(
          RunPins.make(identity, reasoningCanary, promptCanary, commit)
        )
        val bdr = attribution(1)
        val events = records(
          TelemetryEvent.RunStarted(
            RunMode.FullWriter,
            TelemetryRunPins.capture(privatePins),
            budgets
          ),
          TelemetryEvent.ModelTurn(
            bdr,
            ModelTurnKind.Completed,
            tokenMeasurements(1L, 1L, 0L),
            ModelTimingMeasurements.logicalTurn(1L),
            noError
          ),
          completed(1L, finalBdr = bdr)
        )
        val encoded = unsafe(
          unsafe(
            TelemetryDocument.from(runId, deployment, events)
          ).canonicalJson
        )
        assertTrue(
          !encoded.contains(reasoningCanary),
          !encoded.contains(promptCanary),
          encoded.contains("\"reasoning_effort_digest\":\"sha256:"),
          encoded.contains("\"prompt_version_digest\":\"sha256:")
        )
      }
    )

  private def decimalEquals(
      value: Measurement[JBigDecimal],
      expected: String
  ): Boolean =
    value match
      case Measurement.Observed(number) =>
        number.compareTo(new JBigDecimal(expected)) == 0
      case Measurement.Unavailable(_) => false
