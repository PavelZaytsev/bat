package bat.controller

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.protocol.*

import java.nio.file.Path

import zio.*
import zio.json.ast.Json

object ControllerTestKit:
  val EngineCommit = "0123456789abcdef0123456789abcdef01234567"
  val ReasoningCanaryOne = "RAW_REASONING_CANARY_7d3c"
  val ReasoningCanaryTwo = "RAW_REASONING_CANARY_91ef"

  val Identity: BackendIdentity = unsafe(
    BackendIdentity.make("fake", "fake-bat-model", "rev-2026-08-07")
  )
  val Pins: RunPins = unsafe(
    RunPins.make(Identity, "high", "bat-loop-v1", EngineCommit)
  )
  val Developer: DeveloperInput = unsafe(DeveloperInput.make("Follow BDR."))
  val User: UserInput = unsafe(UserInput.make("Refactor PR 42."))

  val FullCapabilities: BackendCapabilities = capabilities(
    Capability.ReasoningContinuity,
    Capability.StrictTools,
    Capability.Streaming,
    Capability.UsageReporting
  )

  val EmptyObjectSchema: Json.Obj = obj(
    """{
      |  "type": "object",
      |  "properties": {},
      |  "required": [],
      |  "additionalProperties": false
      |}""".stripMargin
  )

  val EchoSchema: Json.Obj = obj(
    """{
      |  "type": "object",
      |  "properties": {
      |    "a": {"type": "integer"},
      |    "b": {"type": "string"}
      |  },
      |  "required": ["a", "b"],
      |  "additionalProperties": false
      |}""".stripMargin
  )

  val ApplySchema: Json.Obj = obj(
    """{
      |  "type": "object",
      |  "properties": {
      |    "operation": {
      |      "type": "object",
      |      "properties": {
      |        "type": {
      |          "type": "string",
      |          "enum": ["set_run_state"]
      |        },
      |        "state": {
      |          "type": "string",
      |          "enum": ["ready_for_review"]
      |        }
      |      },
      |      "required": ["type", "state"],
      |      "additionalProperties": false
      |    }
      |  },
      |  "required": ["operation"],
      |  "additionalProperties": false
      |}""".stripMargin
  )

  val AuditDefinition: ToolDefinition = definition(
    "bdr_audit_summary",
    EmptyObjectSchema
  )
  val ApplyDefinition: ToolDefinition = definition("bdr_apply", ApplySchema)
  val EchoDefinition: ToolDefinition = definition("echo", EchoSchema)

  final class TestContext(
      identity: BackendIdentity,
      val canary: String,
      mode: ContinuationMode = ContinuationMode.OpaqueReplay
  ) extends OpaqueReasoningContext(identity, mode)

  final class ScriptedBackend(
      val identity: BackendIdentity,
      val capabilities: BackendCapabilities,
      scripted: Ref[Chunk[ModelTurn[TestContext]]],
      val requests: Ref[Chunk[ModelRequest[TestContext]]],
      val turnBudgets: Ref[Chunk[TurnBudget]]
  ) extends Backend:
    type Context = TestContext

    protected def generate(
        request: ModelRequest[TestContext],
        budget: TurnBudget
    ): IO[BatError, ModelTurn[TestContext]] =
      requests.update(_ :+ request) *>
        turnBudgets.update(_ :+ budget) *>
        scripted
          .modify { remaining =>
            if remaining.nonEmpty then Right(remaining.head) -> remaining.tail
            else
              Left(
                BatError.ProtocolViolation(
                  "scripted backend received an unexpected turn"
                )
              ) -> remaining
          }
          .flatMap(ZIO.fromEither(_))

  object ScriptedBackend:
    def make(
        turns: Chunk[ModelTurn[TestContext]],
        capabilities: BackendCapabilities = FullCapabilities,
        identity: BackendIdentity = Identity
    ): UIO[ScriptedBackend] =
      for
        scripted <- Ref.make(turns)
        requests <- Ref.make(Chunk.empty[ModelRequest[TestContext]])
        budgets <- Ref.make(Chunk.empty[TurnBudget])
      yield ScriptedBackend(
        identity,
        capabilities,
        scripted,
        requests,
        budgets
      )

  final class FakeBdr(
      val engineCommit: String,
      val actor: String,
      state: Ref[ValidatedBdrState],
      checkpointScript: Ref[Chunk[ValidatedBdrState]],
      applyScript: Ref[Chunk[(Json.Obj, ValidatedBdrState)]],
      auditResult: Json,
      val checkpointCalls: Ref[Int],
      val applyCalls: Ref[Chunk[Json.Obj]],
      val auditCalls: Ref[Int]
  ) extends BdrSession:
    def current: UIO[ValidatedBdrState] = state.get

    def checkpoint: IO[BatError, ValidatedBdrState] =
      checkpointCalls.update(_ + 1) *>
        checkpointScript
          .modify { remaining =>
            if remaining.nonEmpty then Some(remaining.head) -> remaining.tail
            else None -> remaining
          }
          .flatMap {
            case Some(next) => state.set(next).as(next)
            case None       => state.get
          }

    def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
      applyCalls.update(_ :+ operation) *>
        applyScript
          .modify { remaining =>
            if remaining.nonEmpty then Right(remaining.head) -> remaining.tail
            else
              Left(
                BatError.BdrFailure(
                  "unexpected_apply",
                  "fake BDR received an unexpected mutation"
                )
              ) -> remaining
          }
          .flatMap(ZIO.fromEither(_))
          .flatMap { case (output, next) => state.set(next).as(output) }

    def auditSummary: IO[BatError, Json] =
      auditCalls.update(_ + 1).as(auditResult)

    def completionCheck: IO[BatError, Json.Obj] =
      state.get.map(current =>
        Json.Obj(
          Chunk(
            "eligible" -> Json.Bool(false),
            "revision" -> Json.Num(current.revision.value)
          )
        )
      )

  object FakeBdr:
    def make(
        initial: ValidatedBdrState,
        checkpoints: Chunk[ValidatedBdrState] = Chunk.empty,
        applies: Chunk[(Json.Obj, ValidatedBdrState)] = Chunk.empty,
        auditResult: Json = Json.Arr(Chunk.empty),
        engineCommit: String = EngineCommit,
        actor: String = "bat"
    ): UIO[FakeBdr] =
      for
        state <- Ref.make(initial)
        checkpointScript <- Ref.make(checkpoints)
        applyScript <- Ref.make(applies)
        checkpointCalls <- Ref.make(0)
        applyCalls <- Ref.make(Chunk.empty[Json.Obj])
        auditCalls <- Ref.make(0)
      yield FakeBdr(
        engineCommit,
        actor,
        state,
        checkpointScript,
        applyScript,
        auditResult,
        checkpointCalls,
        applyCalls,
        auditCalls
      )

  final class RecordingTool(
      val definition: ToolDefinition,
      handler: Json.Obj => IO[ToolError, Json],
      val executions: Ref[Chunk[Json.Obj]],
      override val authority: ToolAuthority
  ) extends Tool:
    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      executions.update(_ :+ invocation.arguments) *>
        handler(invocation.arguments)

  object RecordingTool:
    def make(
        definition: ToolDefinition,
        handler: Json.Obj => IO[ToolError, Json],
        authority: ToolAuthority = ToolAuthority.Writer
    ): UIO[RecordingTool] =
      Ref
        .make(Chunk.empty[Json.Obj])
        .map(RecordingTool(definition, handler, _, authority))

    def constant(
        definition: ToolDefinition,
        output: Json,
        authority: ToolAuthority = ToolAuthority.Writer
    ): UIO[RecordingTool] =
      make(definition, _ => ZIO.succeed(output), authority)

  def capabilities(values: Capability*): BackendCapabilities =
    unsafe(BackendCapabilities.make(values.toSet))

  def budgets(
      iterations: Int = 3,
      toolCalls: Int = 2,
      wallTime: Duration = 60.seconds,
      totalTokens: Long = 260L
  ): BudgetLimits =
    unsafe(BudgetLimits.make(iterations, toolCalls, wallTime, totalTokens))

  def makeRunSpec(
      limits: BudgetLimits = budgets(),
      required: Set[Capability] = Set(Capability.Streaming),
      mode: RunMode = RunMode.FullWriter
  ): RunSpec =
    RunSpec.make(mode, Pins, Developer, User, limits, required)

  def state(
      revision: Long,
      runState: String,
      nextAction: Json.Obj,
      digestCharacter: Char
  ): ValidatedBdrState =
    val view = unsafe(
      BdrStateView.make(
        unsafe(Revision.from(revision)),
        runState,
        nextAction,
        digestCharacter.toString * 64
      )
    )
    ValidatedBdrState(
      Path.of("virtual-repository").toAbsolutePath.normalize,
      Path.of(".bdr", "progress.yaml"),
      view
    )

  val Verifying41: ValidatedBdrState = state(
    41L,
    "verifying",
    obj("""{"action":"completion_check_then_mark_ready"}"""),
    '1'
  )

  val Ready42: ValidatedBdrState = state(
    42L,
    "ready_for_review",
    obj(
      """{
        |  "action": "handoff",
        |  "reason": "the verified run is ready for review"
        |}""".stripMargin
    ),
    '2'
  )

  def activeState(revision: Long = 41L): ValidatedBdrState = state(
    revision,
    "executing",
    obj("""{"action":"begin_phase"}"""),
    '3'
  )

  def context(
      canary: String,
      identity: BackendIdentity = Identity
  ): TestContext = TestContext(identity, canary)

  def call(id: String, name: String, arguments: Json.Obj): FunctionCall =
    unsafe(FunctionCall.make(unsafe(CallId.from(id)), name, arguments))

  def usage(total: Long): Usage = unsafe(Usage.make(total))

  def detailedUsage(
      total: Long,
      input: Long,
      cached: Long,
      output: Long,
      reasoning: Long
  ): Usage =
    unsafe(
      Usage.make(
        total,
        inputTokens = Some(input),
        cachedInputTokens = Some(cached),
        outputTokens = Some(output),
        reasoningTokens = Some(reasoning)
      )
    )

  def toolTurn(
      context: TestContext,
      calls: Chunk[FunctionCall],
      usage: Usage
  ): ModelTurn[TestContext] =
    unsafe(ModelTurn.toolCalls(context, calls, usage))

  def completed(
      text: String = "Ready for review.",
      usage: Usage = usage(1L)
  ): ModelTurn[TestContext] =
    ModelTurn.completed(unsafe(FinalOutput.make(text)), usage)

  def definition(
      name: String,
      schema: Json.Obj,
      strict: Boolean = true
  ): ToolDefinition =
    unsafe(ToolDefinition.make(name, s"test tool $name", schema, strict))

  def registry(tools: RecordingTool*): ToolRegistry =
    unsafe(ToolRegistry.make(Chunk.fromIterable(tools)))

  def field(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.collectFirst { case (`name`, value) => value }

  def obj(text: String): Json.Obj = unsafe(StrictJson.parseObject(text))

  def parseJson(text: String): Json = unsafe(StrictJson.parse(text))

  def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  val FakeToolError: ToolError = unsafe(ToolError.make("fake_tool_error"))
