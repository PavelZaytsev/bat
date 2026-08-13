package bat.controller

import bat.controller.ControllerTestKit.*
import bat.protocol.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object AgenticLoopSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("agentic loop controller")(
      test("tool error codes are bounded safe machine codes") {
        assertTrue(
          ToolError.make("read_failed-2").isRight,
          ToolError.make("UPPERCASE").isLeft,
          ToolError.make("contains spaces").isLeft,
          ToolError.make("a" * 65).isLeft
        )
      },
      test("capability failure occurs before BDR, backend, or tool effects") {
        val missingContinuity = capabilities(
          Capability.StrictTools,
          Capability.Streaming,
          Capability.UsageReporting
        )
        for
          backend <- ScriptedBackend.make(
            Chunk(completed()),
            capabilities = missingContinuity
          )
          bdr <- FakeBdr.make(Verifying41)
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          exit <- AgenticLoop
            .run(makeRunSpec(), backend, registry(tool), bdr)
            .either
          requests <- backend.requests.get
          checkpoints <- bdr.checkpointCalls.get
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.BackendIncompatible(Set(Capability.ReasoningContinuity))
          ),
          requests.isEmpty,
          checkpoints == 0,
          executions.isEmpty
        )
      },
      test("audit also requires reasoning continuity before any effects") {
        val missingContinuity = capabilities(
          Capability.Streaming,
          Capability.UsageReporting
        )
        for
          backend <- ScriptedBackend.make(
            Chunk(completed()),
            capabilities = missingContinuity
          )
          bdr <- FakeBdr.make(activeState())
          readOnly <- RecordingTool.constant(
            AuditDefinition,
            Json.Bool(true),
            ToolAuthority.ReadOnly
          )
          exit <- AgenticLoop
            .run(
              makeRunSpec(mode = RunMode.Audit),
              backend,
              registry(readOnly),
              bdr
            )
            .either
          requests <- backend.requests.get
          checkpoints <- bdr.checkpointCalls.get
          executions <- readOnly.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.BackendIncompatible(
              Set(Capability.ReasoningContinuity)
            )
          ),
          requests.isEmpty,
          checkpoints == 0,
          executions.isEmpty
        )
      },
      test(
        "audit exposes and executes only read-only tools then completes active state"
      ) {
        val auditCall = call("audit-1", "bdr_audit_summary", obj("{}"))
        val turns = Chunk(
          toolTurn(context("audit-context"), Chunk(auditCall), usage(1L)),
          completed("Audit complete.", usage(1L))
        )
        val initial = activeState(41L)
        val fresh = activeState(42L)
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(
            initial,
            checkpoints = Chunk(initial, fresh)
          )
          readOnly <- RecordingTool.constant(
            AuditDefinition,
            parseJson("""{"findings":0}"""),
            ToolAuthority.ReadOnly
          )
          writer <- RecordingTool.constant(
            ApplyDefinition,
            Json.Bool(true)
          )
          result <- AgenticLoop.run(
            makeRunSpec(
              limits = budgets(
                iterations = 2,
                toolCalls = 1,
                totalTokens = 2L
              ),
              mode = RunMode.Audit
            ),
            backend,
            registry(readOnly, writer),
            bdr
          )
          requests <- backend.requests.get
          readExecutions <- readOnly.executions.get
          writeExecutions <- writer.executions.get
          checkpoints <- bdr.checkpointCalls.get
        yield assertTrue(
          result.outcome == RunOutcome.AuditComplete,
          result.bdrState.runState == "executing",
          result.bdrState.revision.value == 42L,
          requests.size == 2,
          requests.forall(
            _.tools.map(_.name) == Chunk("bdr_audit_summary")
          ),
          readExecutions == Chunk(obj("{}")),
          writeExecutions.isEmpty,
          checkpoints == 2
        )
      },
      test("audit rejects a hidden writer call before execution") {
        val operation = obj(
          """{
            |  "operation": {
            |    "type": "set_run_state",
            |    "state": "ready_for_review"
            |  }
            |}""".stripMargin
        )
        val turns = Chunk(
          toolTurn(
            context("hostile-audit-context"),
            Chunk(call("audit-write-1", "bdr_apply", operation)),
            usage(1L)
          )
        )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(activeState())
          writer <- RecordingTool.constant(
            ApplyDefinition,
            Json.Bool(true)
          )
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                limits = budgets(
                  iterations = 1,
                  toolCalls = 1,
                  totalTokens = 1L
                ),
                mode = RunMode.Audit
              ),
              backend,
              registry(writer),
              bdr
            )
            .either
          requests <- backend.requests.get
          executions <- writer.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.ProtocolViolation(
              "backend requested a tool unavailable in audit mode"
            )
          ),
          requests.size == 1,
          requests.head.tools.isEmpty,
          executions.isEmpty
        )
      },
      test("invalid strict arguments are recoverable tool feedback") {
        val invalid = obj("""{"a":1}""")
        val valid = obj("""{"a":1,"b":"repaired"}""")
        val turns = Chunk(
          toolTurn(
            context("invalid-argument-context"),
            Chunk(call("invalid-argument-1", "echo", invalid)),
            usage(1L)
          ),
          toolTurn(
            context("repaired-argument-context"),
            Chunk(call("invalid-argument-2", "echo", valid)),
            usage(1L)
          ),
          completed(usage = usage(1L))
        )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(
            activeState(),
            checkpoints = Chunk(Ready42)
          )
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          result <- AgenticLoop.run(
            makeRunSpec(
              budgets(iterations = 3, toolCalls = 2, totalTokens = 3L)
            ),
            backend,
            registry(tool),
            bdr
          )
          requests <- backend.requests.get
          executions <- tool.executions.get
          feedback = requests(1).inputs.collectFirst {
            case InputEvent.ToolOutput(output) => output
          }
          error = feedback.flatMap(output =>
            output.output match
              case value: Json.Obj => field(value, "error")
              case _               => None
          )
          message = feedback.flatMap(output =>
            output.output match
              case value: Json.Obj => field(value, "message")
              case _               => None
          )
        yield assertTrue(
          result.outcome == RunOutcome.ReadyForReview,
          result.toolCalls == 2,
          executions == Chunk(valid),
          feedback.exists(_.isError),
          error.contains(Json.Str("invalid_tool_arguments")),
          message.contains(
            Json.Str("tool echo: tool argument $ is missing: b")
          )
        )
      },
      test("same call ID and canonical arguments replay without re-execution") {
        val firstArguments = obj("""{"a":1,"b":"one"}""")
        val reorderedArguments = obj("""{"b":"one","a":1}""")
        val firstCall = call("replay-1", "echo", firstArguments)
        val replayedCall = call("replay-1", "echo", reorderedArguments)
        val firstContext = context("replay-context-one")
        val secondContext = context("replay-context-two")
        val turns = Chunk(
          toolTurn(firstContext, Chunk(firstCall), usage(1L)),
          toolTurn(secondContext, Chunk(replayedCall), usage(1L)),
          completed(usage = usage(1L))
        )
        val limits = budgets(toolCalls = 1, totalTokens = 3L)
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(
            activeState(),
            checkpoints = Chunk(activeState(), Ready42)
          )
          tool <- RecordingTool.constant(
            EchoDefinition,
            parseJson("""{"ok":true}""")
          )
          result <- AgenticLoop.run(
            makeRunSpec(limits),
            backend,
            registry(tool),
            bdr
          )
          executions <- tool.executions.get
          requests <- backend.requests.get
          replayOutputs = requests
            .drop(1)
            .flatMap(_.inputs)
            .collect { case InputEvent.ToolOutput(output) => output }
        yield assertTrue(
          result.toolCalls == 1,
          executions == Chunk(firstArguments),
          replayOutputs.size == 2,
          replayOutputs(0) == replayOutputs(1),
          replayOutputs.forall(_.callId.value == "replay-1")
        )
      },
      test("same call ID with changed arguments fails before re-execution") {
        val firstArguments = obj("""{"a":1,"b":"one"}""")
        val changedArguments = obj("""{"a":2,"b":"one"}""")
        val turns = Chunk(
          toolTurn(
            context("conflict-context-one"),
            Chunk(call("conflict-1", "echo", firstArguments)),
            usage(1L)
          ),
          toolTurn(
            context("conflict-context-two"),
            Chunk(call("conflict-1", "echo", changedArguments)),
            usage(1L)
          )
        )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          exit <- AgenticLoop
            .run(
              makeRunSpec(budgets(toolCalls = 2, totalTokens = 10L)),
              backend,
              registry(tool),
              bdr
            )
            .either
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.ProtocolViolation(
              "backend reused a call_id with different content"
            )
          ),
          executions == Chunk(firstArguments)
        )
      },
      test("a mixed batch with a replay conflict executes no fresh call") {
        val firstArguments = obj("""{"a":1,"b":"one"}""")
        val freshArguments = obj("""{"a":2,"b":"two"}""")
        val conflictingArguments = obj("""{"a":3,"b":"one"}""")
        val turns =
          Chunk(
            toolTurn(
              context("batch-context-one"),
              Chunk(call("batch-1", "echo", firstArguments)),
              usage(1L)
            ),
            toolTurn(
              context("batch-context-two"),
              Chunk(
                call("batch-2", "echo", freshArguments),
                call("batch-1", "echo", conflictingArguments)
              ),
              usage(1L)
            )
          )
        val parallelCapabilities = capabilities(
          Capability.ReasoningContinuity,
          Capability.StrictTools,
          Capability.Streaming,
          Capability.ParallelCalls,
          Capability.UsageReporting
        )
        for
          backend <- ScriptedBackend.make(
            turns,
            capabilities = parallelCapabilities
          )
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          exit <- AgenticLoop
            .run(
              makeRunSpec(budgets(toolCalls = 3, totalTokens = 10L)),
              backend,
              registry(tool),
              bdr
            )
            .either
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.ProtocolViolation(
              "backend reused a call_id with different content"
            )
          ),
          executions == Chunk(firstArguments),
          !executions.contains(freshArguments)
        )
      },
      test("token budget fails before executing an over-budget tool turn") {
        val arguments = obj("""{"a":1,"b":"one"}""")
        val turns = Chunk(
          toolTurn(
            context("token-context"),
            Chunk(call("token-1", "echo", arguments)),
            usage(2L)
          )
        )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(iterations = 1, toolCalls = 1, totalTokens = 1L)
              ),
              backend,
              registry(tool),
              bdr
            )
            .either
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.BudgetExceeded(BudgetKind.TotalTokens)
          ),
          executions.isEmpty
        )
      },
      test("iteration budget stops before an unbudgeted backend turn") {
        val turns =
          Chunk(
            toolTurn(
              context("iteration-context-one"),
              Chunk(call("iteration-1", "echo", obj("""{"a":1,"b":"one"}"""))),
              usage(1L)
            ),
            toolTurn(
              context("iteration-context-two"),
              Chunk(call("iteration-2", "echo", obj("""{"a":2,"b":"two"}"""))),
              usage(1L)
            )
          )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(iterations = 2, toolCalls = 2, totalTokens = 2L)
              ),
              backend,
              registry(tool),
              bdr
            )
            .either
          requests <- backend.requests.get
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.BudgetExceeded(BudgetKind.Iterations)
          ),
          requests.size == 2,
          executions.size == 2
        )
      },
      test("tool budget reserves a complete batch before executing it") {
        val firstArguments = obj("""{"a":1,"b":"one"}""")
        val secondArguments = obj("""{"a":2,"b":"two"}""")
        val turns = Chunk(
          toolTurn(
            context("tool-budget-context"),
            Chunk(
              call("tool-budget-1", "echo", firstArguments),
              call("tool-budget-2", "echo", secondArguments)
            ),
            usage(1L)
          )
        )
        val parallelCapabilities = capabilities(
          Capability.ReasoningContinuity,
          Capability.StrictTools,
          Capability.Streaming,
          Capability.ParallelCalls,
          Capability.UsageReporting
        )
        for
          backend <- ScriptedBackend.make(
            turns,
            capabilities = parallelCapabilities
          )
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.constant(EchoDefinition, Json.Bool(true))
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(iterations = 1, toolCalls = 1, totalTokens = 1L)
              ),
              backend,
              registry(tool),
              bdr
            )
            .either
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.BudgetExceeded(BudgetKind.ToolCalls)
          ),
          executions.isEmpty
        )
      },
      test("tool defects collapse to a typed error without leaking details") {
        val canary = "RAW_TOOL_DEFECT_CANARY_67bd"
        val arguments = obj("""{"a":1,"b":"one"}""")
        val turns = Chunk(
          toolTurn(
            context("tool-defect-context"),
            Chunk(call("tool-defect-1", "echo", arguments)),
            usage(1L)
          )
        )
        for
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.make(
            EchoDefinition,
            _ => ZIO.die(new RuntimeException(canary))
          )
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(
                  iterations = 1,
                  toolCalls = 1,
                  totalTokens = 1L
                )
              ),
              backend,
              registry(tool),
              bdr
            )
            .either
          executions <- tool.executions.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.ToolFailure("echo", "tool_execution_defect")
          ),
          !exit.toString.contains(canary),
          executions == Chunk(arguments)
        )
      },
      test("wall-budget interruption reaches a running tool") {
        val arguments = obj("""{"a":1,"b":"one"}""")
        val turns = Chunk(
          toolTurn(
            context("tool-interrupt-context"),
            Chunk(call("tool-interrupt-1", "echo", arguments)),
            usage(1L)
          )
        )
        for
          started <- Promise.make[Nothing, Unit]
          finalized <- Ref.make(false)
          backend <- ScriptedBackend.make(turns)
          bdr <- FakeBdr.make(activeState())
          tool <- RecordingTool.make(
            EchoDefinition,
            _ =>
              started.succeed(()).unit *>
                ZIO.never.onInterrupt(finalized.set(true))
          )
          fiber <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(
                  iterations = 1,
                  toolCalls = 1,
                  wallTime = 5.seconds,
                  totalTokens = 1L
                )
              ),
              backend,
              registry(tool),
              bdr
            )
            .fork
          _ <- started.await
          _ <- TestClock.adjust(5.seconds)
          result <- fiber.join.either
          wasFinalized <- finalized.get
        yield assertTrue(
          result.left.toOption.contains(
            BatError.BudgetExceeded(BudgetKind.WallTime)
          ),
          wasFinalized
        )
      },
      test("wall budget deterministically interrupts a blocked backend") {
        for
          started <- Promise.make[Nothing, Unit]
          finalized <- Ref.make(false)
          requests <- Ref.make(Chunk.empty[ModelRequest[TestContext]])
          backend = new Backend:
            type Context = TestContext
            val identity: BackendIdentity = Identity
            val capabilities: BackendCapabilities = FullCapabilities

            protected def generate(
                request: ModelRequest[TestContext],
                budget: TurnBudget
            ): IO[BatError, ModelTurn[TestContext]] =
              val record = requests.update(_ :+ request)
              val block = ZIO.never.onInterrupt(finalized.set(true))
              record *> started.succeed(()).unit *> block
          bdr <- FakeBdr.make(activeState())
          fiber <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(
                  iterations = 1,
                  toolCalls = 1,
                  wallTime = 5.seconds,
                  totalTokens = 1L
                )
              ),
              backend,
              registry(),
              bdr
            )
            .fork
          _ <- started.await
          _ <- TestClock.adjust(5.seconds)
          result <- fiber.join.either
          wasFinalized <- finalized.get
          captured <- requests.get
          checkpoints <- bdr.checkpointCalls.get
        yield assertTrue(
          result.left.toOption.contains(
            BatError.BudgetExceeded(BudgetKind.WallTime)
          ),
          wasFinalized,
          captured.size == 1,
          checkpoints == 1
        )
      },
      test("model final is premature while the fresh BDR state is active") {
        for
          backend <- ScriptedBackend.make(Chunk(completed()))
          bdr <- FakeBdr.make(activeState())
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(iterations = 1, toolCalls = 1, totalTokens = 1L)
              ),
              backend,
              registry(),
              bdr
            )
            .either
          checkpoints <- bdr.checkpointCalls.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.PrematureFinal("executing", "begin_phase")
          ),
          checkpoints == 2
        )
      },
      test("a fresh terminal checkpoint authorizes final completion") {
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
          checkpoints <- bdr.checkpointCalls.get
        yield assertTrue(
          result.outcome == RunOutcome.ReadyForReview,
          result.bdrState.revision.value == 42L,
          result.bdrState.runState == "ready_for_review",
          checkpoints == 2
        )
      },
      test(
        "a cached terminal state cannot override a fresh active checkpoint"
      ) {
        val newlyActive = activeState(43L)
        for
          backend <- ScriptedBackend.make(Chunk(completed()))
          bdr <- FakeBdr.make(
            Ready42,
            checkpoints = Chunk(Ready42, newlyActive)
          )
          exit <- AgenticLoop
            .run(
              makeRunSpec(
                budgets(iterations = 1, toolCalls = 1, totalTokens = 1L)
              ),
              backend,
              registry(),
              bdr
            )
            .either
          checkpoints <- bdr.checkpointCalls.get
        yield assertTrue(
          exit.left.toOption.contains(
            BatError.PrematureFinal("executing", "begin_phase")
          ),
          checkpoints == 2
        )
      }
    ) @@ TestAspect.timeout(10.seconds)
