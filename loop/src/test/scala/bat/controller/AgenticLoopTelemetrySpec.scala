package bat.controller

import bat.bdr.{BdrSession, BdrTools, ValidatedBdrState}
import bat.controller.ControllerTestKit.*
import bat.protocol.*
import bat.telemetry.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object AgenticLoopTelemetrySpec extends ZIOSpecDefault:
  private val RunId = telemetryUnsafe(
    TelemetryRunId.from("controller-telemetry-test")
  )
  private val Deployment = telemetryUnsafe(
    DeploymentFingerprint.minimal(Identity, "scripted")
  )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("agentic loop telemetry")(
      test(
        "two fresh tools reconcile telemetry and observe each BDR transition"
      ) {
        val operationText =
          """{"type":"set_run_state","state":"ready_for_review"}"""
        val applyCall = call(
          "apply-telemetry-1",
          "bdr_apply",
          obj(
            s"""{"operation_json":"${operationText.replace("\"", "\\\"")}"}"""
          )
        )
        val checkCall = call(
          "check-telemetry-1",
          "bdr_completion_check",
          obj("{}")
        )
        val turns = Chunk(
          toolTurn(
            context(ReasoningCanaryOne),
            Chunk(applyCall, checkCall),
            detailedUsage(40L, 30L, 10L, 10L, 5L)
          ),
          completed(
            usage = detailedUsage(20L, 12L, 2L, 8L, 4L)
          )
        )
        val parallelCapabilities = capabilities(
          Capability.ReasoningContinuity,
          Capability.StrictTools,
          Capability.Streaming,
          Capability.ParallelCalls,
          Capability.UsageReporting
        )
        val applyOutput = obj(
          """{"revision":42,"result":{"accepted":true}}"""
        )
        for
          backend <- ScriptedBackend.make(
            turns,
            capabilities = parallelCapabilities
          )
          bdr <- FakeBdr.make(
            Verifying41,
            applies = Chunk(applyOutput -> Ready42)
          )
          tools <- ZIO.fromEither(ToolRegistry.make(BdrTools.all(bdr)))
          telemetry <- InMemoryTelemetry.make
          result <- AgenticLoop.run(
            makeRunSpec(
              budgets(
                iterations = 2,
                toolCalls = 2,
                totalTokens = 60L
              )
            ),
            backend,
            tools,
            bdr,
            telemetry
          )
          rawRecords <- telemetry.records
          documentEither <- telemetry.document(RunId, Deployment)
          document = telemetryUnsafe(documentEither)
          toolEvents = rawRecords.collect {
            case TelemetryRecord(_, event: TelemetryEvent.ToolExecution) =>
              event
          }
        yield assertTrue(
          result.outcome == RunOutcome.ReadyForReview,
          result.iterations == 2,
          result.toolCalls == 2,
          result.totalTokens == 60L,
          document.records == rawRecords,
          document.summary.modelTurns == 2,
          document.summary.toolExecutions == 2,
          document.summary.measurements.tokens.total == Measurement.Observed(
            60L
          ),
          document.summary.measurements.tokens.input == Measurement.Observed(
            42L
          ),
          document.summary.measurements.tokens.cachedInput == Measurement
            .Observed(12L),
          document.summary.measurements.tokens.output == Measurement.Observed(
            18L
          ),
          document.summary.measurements.tokens.reasoning == Measurement
            .Observed(9L),
          toolEvents.map(_.name.value) == Chunk(
            "bdr_apply",
            "bdr_completion_check"
          ),
          toolEvents.map(_.outcome) == Chunk(
            ToolExecutionOutcome.Succeeded,
            ToolExecutionOutcome.Succeeded
          ),
          toolEvents(0).before.revision == 41L,
          observedRevision(toolEvents(0).after).contains(42L),
          toolEvents(1).before.revision == 42L,
          observedRevision(toolEvents(1).after).contains(42L),
          rawRecords.head.event.isInstanceOf[TelemetryEvent.RunStarted],
          rawRecords.last.event.isInstanceOf[TelemetryEvent.RunCompleted],
          document.canonicalJson.isRight
        )
      },
      test("replayed calls are recorded but excluded from fresh tool totals") {
        val firstArguments = obj("""{"a":1,"b":"one"}""")
        val reorderedArguments = obj("""{"b":"one","a":1}""")
        val turns = Chunk(
          toolTurn(
            context("replay-reasoning-one"),
            Chunk(call("replay-telemetry-1", "echo", firstArguments)),
            usage(1L)
          ),
          toolTurn(
            context("replay-reasoning-two"),
            Chunk(call("replay-telemetry-1", "echo", reorderedArguments)),
            usage(1L)
          ),
          completed(usage = usage(1L))
        )
        val initial = activeState()
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(
            initial,
            checkpoints = Chunk(initial, Ready42)
          )
          tool <- RecordingTool.constant(
            EchoDefinition,
            parseJson("""{"ok":true}""")
          )
          telemetry <- InMemoryTelemetry.make
          result <- AgenticLoop.run(
            makeRunSpec(
              budgets(
                iterations = 3,
                toolCalls = 1,
                totalTokens = 3L
              )
            ),
            backend,
            registry(tool),
            bdr,
            telemetry
          )
          executions <- tool.executions.get
          records <- telemetry.records
          documentEither <- telemetry.document(RunId, Deployment)
          document = telemetryUnsafe(documentEither)
          toolEvents = records.collect {
            case TelemetryRecord(_, event: TelemetryEvent.ToolExecution) =>
              event
          }
        yield assertTrue(
          result.toolCalls == 1,
          executions == Chunk(firstArguments),
          toolEvents.map(_.outcome) == Chunk(
            ToolExecutionOutcome.Succeeded,
            ToolExecutionOutcome.Replayed
          ),
          toolEvents(1).durationMillis == Measurement.Unavailable(
            MissingReason.NotApplicable
          ),
          document.summary.toolExecutions == 1,
          document.summary.modelTurns == 3,
          document.summary.measurements.tokens.total == Measurement.Observed(
            3L
          )
        )
      },
      test("failed post-tool BDR observation stays explicitly unavailable") {
        val initial = activeState()
        val arguments = obj("""{"a":1,"b":"one"}""")
        val turns = Chunk(
          toolTurn(
            context("post-tool-observation"),
            Chunk(call("post-tool-1", "echo", arguments)),
            usage(1L)
          )
        )
        for
          backend <- ScriptedBackend.make(turns)
          stable <- FakeBdr.make(initial)
          currentCalls <- Ref.make(0)
          flaky = new BdrSession:
            val engineCommit: String = stable.engineCommit
            val actor: String = stable.actor

            def current: IO[BatError, ValidatedBdrState] =
              currentCalls.updateAndGet(_ + 1).flatMap {
                case 2 =>
                  ZIO.fail(
                    BatError.BdrFailure(
                      "post_tool_checkpoint_failed",
                      "trusted BDR checkpoint was unavailable"
                    )
                  )
                case _ => stable.current
              }

            def checkpoint: IO[BatError, ValidatedBdrState] =
              stable.checkpoint

            def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
              stable.apply(operation)

            def auditSummary: IO[BatError, Json] = stable.auditSummary

            def completionCheck: IO[BatError, Json.Obj] =
              stable.completionCheck
          tool <- RecordingTool.constant(
            EchoDefinition,
            parseJson("""{"ok":true}""")
          )
          telemetry <- InMemoryTelemetry.make
          result <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(iterations = 2, toolCalls = 1, totalTokens = 2L)
              ),
              backend,
              registry(tool),
              flaky,
              telemetry
            )
            .either
          records <- telemetry.records
          document <- telemetry.document(RunId, Deployment)
          toolEvents = records.collect {
            case TelemetryRecord(_, event: TelemetryEvent.ToolExecution) =>
              event
          }
        yield assertTrue(
          result.left.exists(_.code == "post_tool_checkpoint_failed"),
          toolEvents.size == 1,
          toolEvents.head.after == Measurement.Unavailable(
            MissingReason.FailedBeforeMeasurement
          ),
          records.last.event.isInstanceOf[TelemetryEvent.RunFailed],
          document.isRight
        )
      },
      test("typed controller failure terminates a valid telemetry document") {
        val initial = activeState()
        for
          backend <- ScriptedBackend.make(
            Chunk(
              completed(
                "Premature output.",
                detailedUsage(5L, 3L, 1L, 2L, 1L)
              )
            )
          )
          bdr <- FakeBdr.make(initial)
          telemetry <- InMemoryTelemetry.make
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(
                  iterations = 1,
                  toolCalls = 1,
                  totalTokens = 5L
                )
              ),
              backend,
              registry(),
              bdr,
              telemetry
            )
            .either
          records <- telemetry.records
          documentEither <- telemetry.document(RunId, Deployment)
          document = telemetryUnsafe(documentEither)
          failures = records.collect {
            case TelemetryRecord(_, event: TelemetryEvent.RunFailed) => event
          }
          completions = records.collect {
            case TelemetryRecord(_, event: TelemetryEvent.RunCompleted) => event
          }
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.PrematureFinal("executing", "begin_phase")
          ),
          failures.map(_.errorCode.value) == Chunk("premature_final"),
          completions.isEmpty,
          records.last.event == failures.head,
          document.summary.modelTurns == 1,
          document.summary.measurements.tokens.total == Measurement.Observed(
            5L
          ),
          document.canonicalJson.isRight
        )
      },
      test("canonical telemetry excludes all model-controlled canaries") {
        val developerCanary = "DEVELOPER_PROMPT_CANARY_313e"
        val userCanary = "USER_PROMPT_CANARY_417a"
        val reasoningCanary = "RAW_REASONING_CANARY_771b"
        val argumentCanary = "TOOL_ARGUMENT_CANARY_82ac"
        val toolOutputCanary = "TOOL_OUTPUT_CANARY_99db"
        val finalOutputCanary = "FINAL_OUTPUT_CANARY_a37f"
        val arguments = obj(
          s"""{"a":1,"b":"$argumentCanary"}"""
        )
        val turns = Chunk(
          toolTurn(
            context(reasoningCanary),
            Chunk(call("canary-call-1", "echo", arguments)),
            detailedUsage(3L, 2L, 0L, 1L, 0L)
          ),
          completed(
            finalOutputCanary,
            detailedUsage(3L, 2L, 0L, 1L, 0L)
          )
        )
        val spec = RunSpec.make(
          RunMode.FullWriter,
          Pins,
          unsafe(DeveloperInput.make(developerCanary)),
          unsafe(UserInput.make(userCanary)),
          budgets(iterations = 2, toolCalls = 1, totalTokens = 6L),
          Set(Capability.Streaming)
        )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(Ready42)
          tool <- RecordingTool.constant(
            EchoDefinition,
            Json.Str(toolOutputCanary)
          )
          telemetry <- InMemoryTelemetry.make
          _ <- AgenticLoop.run(
            spec,
            backend,
            registry(tool),
            bdr,
            telemetry
          )
          documentEither <- telemetry.document(RunId, Deployment)
          document = telemetryUnsafe(documentEither)
          canonical = telemetryUnsafe(document.canonicalJson)
          canaries = Chunk(
            developerCanary,
            userCanary,
            reasoningCanary,
            argumentCanary,
            toolOutputCanary,
            finalOutputCanary
          )
        yield assertTrue(
          canaries.forall(canary => !canonical.contains(canary)),
          canaries.forall(canary => !document.toString.contains(canary)),
          canonical.contains("\"schema\":\"bat.dev/run-telemetry\"")
        )
      },
      test("the source-compatible no-telemetry overload still completes") {
        for
          backend <- ScriptedBackend.make(Chunk(completed()))
          bdr <- FakeBdr.make(
            Verifying41,
            checkpoints = Chunk(Verifying41, Ready42)
          )
          result <- AgenticLoop.run(
            makeRunSpec(
              budgets(iterations = 1, toolCalls = 1, totalTokens = 1L)
            ),
            backend,
            registry(),
            bdr
          )
        yield assertTrue(
          result.outcome == RunOutcome.ReadyForReview,
          result.totalTokens == 1L,
          result.iterations == 1
        )
      }
    ) @@ TestAspect.timeout(10.seconds)

  private def telemetryUnsafe[A](
      value: Either[TelemetryError, A]
  ): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def observedRevision(
      value: Measurement[BdrAttribution]
  ): Option[Long] = value match
    case Measurement.Observed(attribution) => Some(attribution.revision)
    case Measurement.Unavailable(_)        => None
