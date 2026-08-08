package bat.conformance

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.controller.*
import bat.protocol.*

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Executable provider-neutral trace used by every future backend adapter. */
object GoldenTrace extends ZIOAppDefault:
  def run: ZIO[Any, Any, Any] =
    GoldenScenario.execute.flatMap(result =>
      Console.printLine(result.loopResult.traceDocument.toJsonPretty)
    )

/** The single source of truth for the executable golden scenario and its
  * immutable trace fixture. The scripted backend asserts continuation and BDR
  * revision semantics while the fake BDR asserts the exact mutation.
  */
object GoldenScenario:
  val ReasoningCanaryOne = "RAW_REASONING_CANARY_7d3c"
  val ReasoningCanaryTwo = "RAW_REASONING_CANARY_8e4d"

  private val Commit = "0123456789abcdef0123456789abcdef01234567"

  final case class Result(
      loopResult: LoopResult,
      backendTurns: Int,
      checkpointCalls: Int,
      auditCalls: Int,
      applyCalls: Int,
      continuationDisplays: Chunk[String]
  )

  def execute: IO[BatError, Result] =
    for
      identity <- from(
        BackendIdentity.make("fake", "fake-bat-model", "rev-2026-08-07")
      )
      pins <- from(RunPins.make(identity, "high", "bat-loop-v1", Commit))
      developer <- from(DeveloperInput.make("Follow BDR."))
      user <- from(UserInput.make("Refactor PR 42."))
      budgets <- from(BudgetLimits.make(3, 2, 60.seconds, 260))
      capabilities <- from(
        BackendCapabilities.make(
          Set(
            Capability.ReasoningContinuity,
            Capability.StrictTools,
            Capability.Streaming,
            Capability.UsageReporting
          )
        )
      )
      initial <- state(
        revision = 41,
        runState = "verifying",
        digestCharacter = '1',
        next = obj("action" -> Json.Str("completion_check_then_mark_ready"))
      )
      terminal <- state(
        revision = 42,
        runState = "ready_for_review",
        digestCharacter = '2',
        next = obj(
          "action" -> Json.Str("handoff"),
          "reason" -> Json.Str("the verified run is ready for review")
        )
      )
      bdr <- FakeBdr.make(initial, terminal)
      backend <- ScriptedBackend.make(identity, capabilities)
      registry <- ZIO.fromEither(
        ToolRegistry.make(
          Chunk(new AuditTool(bdr), new ApplyTool(bdr))
        )
      )
      spec = RunSpec.make(
        RunMode.FullWriter,
        pins,
        developer,
        user,
        budgets,
        requiredCapabilities = Set(Capability.Streaming)
      )
      loopResult <- AgenticLoop.run(spec, backend, registry, bdr)
      backendTurns <- backend.turns
      checkpointCalls <- bdr.checkpoints
      auditCalls <- bdr.audits
      applyCalls <- bdr.applies
    yield Result(
      loopResult,
      backendTurns,
      checkpointCalls,
      auditCalls,
      applyCalls,
      Chunk(backend.first.toString, backend.second.toString)
    )

  private final class FakeContext(
      identity: BackendIdentity,
      val token: String,
      private val rawReasoning: String
  ) extends OpaqueReasoningContext(identity, ContinuationMode.OpaqueReplay)

  private final class ScriptedBackend private (
      val identity: BackendIdentity,
      val capabilities: BackendCapabilities,
      turn: Ref[Int],
      val first: FakeContext,
      val second: FakeContext
  ) extends Backend:
    type Context = FakeContext

    def turns: UIO[Int] = turn.get

    protected def generate(
        request: ModelRequest[FakeContext],
        budget: TurnBudget
    ): IO[BatError, ModelTurn[FakeContext]] =
      turn.updateAndGet(_ + 1).flatMap {
        case 1 =>
          for
            _ <- require(
              request.bdrState.revision.value == 41 && request.continuation.isEmpty,
              "first request did not start from revision 41"
            )
            callId <- from(CallId.from("call-audit-0001"))
            call <- from(FunctionCall.make(callId, "bdr_audit_summary", obj()))
            usage <- from(
              Usage.make(100, Some(60), Some(20), Some(40), Some(25))
            )
            output <- from(ModelTurn.toolCalls(first, Chunk(call), usage))
          yield output
        case 2 =>
          for
            _ <- require(
              request.bdrState.revision.value == 41 &&
                request.continuation.contains(first),
              "second request lost its first continuation or BDR revision"
            )
            callId <- from(CallId.from("call-ready-0002"))
            operation = obj(
              "type" -> Json.Str("set_run_state"),
              "state" -> Json.Str("ready_for_review")
            )
            call <- from(
              FunctionCall.make(
                callId,
                "bdr_apply",
                obj("operation" -> operation)
              )
            )
            usage <- from(
              Usage.make(100, Some(70), Some(30), Some(30), Some(20))
            )
            output <- from(ModelTurn.toolCalls(second, Chunk(call), usage))
          yield output
        case 3 =>
          for
            _ <- require(
              request.bdrState.revision.value == 42 &&
                request.continuation.contains(second),
              "third request lost its second continuation or updated BDR revision"
            )
            finalOutput <- from(FinalOutput.make("Ready for review."))
            usage <- from(
              Usage.make(60, Some(40), Some(10), Some(20), Some(10))
            )
          yield ModelTurn.completed(finalOutput, usage)
        case _ =>
          ZIO.fail(
            BatError.ProtocolViolation("golden backend received an extra turn")
          )
      }

  private object ScriptedBackend:
    def make(
        identity: BackendIdentity,
        capabilities: BackendCapabilities
    ): UIO[ScriptedBackend] =
      Ref.make(0).map { turns =>
        new ScriptedBackend(
          identity,
          capabilities,
          turns,
          new FakeContext(identity, "ctx-1", ReasoningCanaryOne),
          new FakeContext(identity, "ctx-2", ReasoningCanaryTwo)
        )
      }

  private final class FakeBdr private (
      ref: Ref.Synchronized[ValidatedBdrState],
      terminal: ValidatedBdrState,
      checkpointCount: Ref[Int],
      auditCount: Ref[Int],
      applyCount: Ref[Int]
  ) extends BdrSession:
    val engineCommit: String = Commit
    val actor: String = "bat"

    def checkpoints: UIO[Int] = checkpointCount.get
    def audits: UIO[Int] = auditCount.get
    def applies: UIO[Int] = applyCount.get

    def current: UIO[ValidatedBdrState] = ref.get

    def checkpoint: IO[BatError, ValidatedBdrState] =
      checkpointCount.update(_ + 1) *> ref.get

    def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
      applyCount.update(_ + 1) *>
        {
          val operationState = stringField(operation, "state")
          val operationType = stringField(operation, "type")
          if operationType.contains("set_run_state") && operationState.contains(
              "ready_for_review"
            )
          then
            ref
              .set(terminal)
              .as(
                obj(
                  "revision" -> Json.Num(terminal.revision.value),
                  "result" -> obj("state" -> Json.Str("ready_for_review"))
                )
              )
          else
            ZIO.fail(
              BatError.BdrFailure(
                "unexpected_operation",
                "golden BDR operation was invalid"
              )
            )
        }

    def auditSummary: IO[BatError, Json] =
      auditCount.update(_ + 1) *>
        current.map(state =>
          Json.Arr(Chunk(obj("revision" -> Json.Num(state.revision.value))))
        )

  private object FakeBdr:
    def make(
        initial: ValidatedBdrState,
        terminal: ValidatedBdrState
    ): UIO[FakeBdr] =
      for
        ref <- Ref.Synchronized.make(initial)
        checkpoints <- Ref.make(0)
        audits <- Ref.make(0)
        applies <- Ref.make(0)
      yield new FakeBdr(ref, terminal, checkpoints, audits, applies)

  private final class AuditTool(bdr: BdrSession) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly

    val definition: ToolDefinition = unsafe(
      ToolDefinition.make(
        "bdr_audit_summary",
        "Read the validated BDR audit summary.",
        objectSchema()
      )
    )

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      bdr.auditSummary.mapError(_ => unsafe(ToolError.make("bdr_audit_failed")))

  private final class ApplyTool(bdr: BdrSession) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.Writer

    val definition: ToolDefinition = unsafe(
      ToolDefinition.make(
        "bdr_apply",
        "Apply one revision-checked BDR operation.",
        objectSchema(
          "operation" -> objectSchema(
            "type" -> stringSchema,
            "state" -> stringSchema
          )
        )
      )
    )

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      val arguments = invocation.arguments
      arguments.fields.collectFirst { case ("operation", value: Json.Obj) =>
        value
      } match
        case Some(operation) =>
          bdr
            .apply(operation)
            .mapError(_ => unsafe(ToolError.make("bdr_apply_failed")))
        case None => ZIO.fail(unsafe(ToolError.make("invalid_operation")))

  private def state(
      revision: Long,
      runState: String,
      digestCharacter: Char,
      next: Json.Obj
  ): IO[BatError, ValidatedBdrState] =
    for
      validRevision <- from(Revision.from(revision))
      view <- from(
        BdrStateView.make(
          validRevision,
          runState,
          next,
          digestCharacter.toString * 64
        )
      )
    yield ValidatedBdrState(
      Path.of("/conformance"),
      Path.of(".bdr/progress.yaml"),
      view
    )

  private def objectSchema(fields: (String, Json.Obj)*): Json.Obj =
    obj(
      "type" -> Json.Str("object"),
      "properties" -> Json.Obj(Chunk.fromIterable(fields)),
      "required" -> Json.Arr(
        Chunk.fromIterable(fields.map(field => Json.Str(field._1)))
      ),
      "additionalProperties" -> Json.Bool(false)
    )

  private val stringSchema: Json.Obj = obj("type" -> Json.Str("string"))

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def stringField(obj: Json.Obj, name: String): Option[String] =
    obj.fields.collectFirst { case (`name`, Json.Str(value)) => value }

  private def require(condition: Boolean, message: String): IO[BatError, Unit] =
    if condition then ZIO.unit
    else ZIO.fail(BatError.ProtocolViolation(message))

  private def from[A](value: Either[BatError, A]): IO[BatError, A] =
    ZIO.fromEither(value)

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )
