package bat.conformance

import bat.protocol.*
import bat.trace.{SafeTraceDocument, SafeTraceEvent}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object GoldenConformanceSpec extends ZIOSpecDefault:
  private val GoldenResource =
    "conformance/v2/two-bdr-tools-then-final.json"
  private val SafeCallIdDigest =
    "sha256:4110d8e5e4fad9ddc20d52f65907e80a18128e9cf6452c11b3087acba297b7b6"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("provider-neutral golden conformance")(
      test("actual executable scenario matches the immutable v2 trace") {
        for
          run <- GoldenScenario.execute
          expected <- loadGolden
        yield assertTrue(
          run.loopResult.traceDocument == expected,
          run.loopResult.outcome == RunOutcome.ReadyForReview,
          run.loopResult.iterations == 3,
          run.loopResult.toolCalls == 2,
          run.loopResult.totalTokens == 260L,
          run.loopResult.bdrState.revision.value == 42L,
          run.loopResult.bdrState.runState == "ready_for_review",
          run.backendTurns == 3,
          run.checkpointCalls == 2,
          run.auditCalls == 1,
          run.applyCalls == 1
        )
      },
      test("reasoning trace types and codecs only admit opaque redaction") {
        for run <- GoldenScenario.execute
        yield
          val encoded = run.loopResult.traceDocument.toJson
          val rawReasoningEvent =
            s"""{
               |  "type": "reasoning_context",
               |  "mode": "opaque_replay",
               |  "opaque": true,
               |  "payload": "${GoldenScenario.ReasoningCanaryOne}"
               |}""".stripMargin
          val nonOpaqueEvent =
            """{
              |  "type": "reasoning_context",
              |  "mode": "opaque_replay",
              |  "opaque": false,
              |  "payload": "<redacted>"
              |}""".stripMargin
          val rawCallIdEvent =
            s"""{
               |  "type": "function_call",
               |  "call_id_digest": "${GoldenScenario.ReasoningCanaryOne}",
               |  "name": "bdr_audit_summary",
               |  "arguments": "<redacted>"
               |}""".stripMargin
          val alternateRawPayloadEvents = Chunk(
            s"""{
               |  "type": "function_call",
               |  "call_id_digest": "$SafeCallIdDigest",
               |  "name": "bdr_audit_summary",
               |  "arguments": "${GoldenScenario.ReasoningCanaryOne}"
               |}""".stripMargin,
            s"""{
               |  "type": "function_output",
               |  "call_id_digest": "$SafeCallIdDigest",
               |  "is_error": false,
               |  "output": "${GoldenScenario.ReasoningCanaryOne}"
               |}""".stripMargin,
            s"""{
               |  "type": "provider_error",
               |  "code": "provider_failure",
               |  "retryable": false,
               |  "message": "${GoldenScenario.ReasoningCanaryOne}"
               |}""".stripMargin
          )
          val safeEvent: SafeTraceEvent =
            SafeTraceEvent.ReasoningContext.redacted("opaque_replay")

          assertTrue(
            !encoded.contains(GoldenScenario.ReasoningCanaryOne),
            !encoded.contains(GoldenScenario.ReasoningCanaryTwo),
            run.continuationDisplays.forall(display =>
              !display.contains(GoldenScenario.ReasoningCanaryOne) &&
                !display.contains(GoldenScenario.ReasoningCanaryTwo)
            ),
            encoded.contains("\"opaque\":true"),
            encoded.contains("\"payload\":\"<redacted>\""),
            rawReasoningEvent.fromJson[SafeTraceEvent].isLeft,
            nonOpaqueEvent.fromJson[SafeTraceEvent].isLeft,
            rawCallIdEvent.fromJson[SafeTraceEvent].isLeft,
            alternateRawPayloadEvents.forall(
              _.fromJson[SafeTraceEvent].isLeft
            ),
            safeEvent.toJson.fromJson[SafeTraceEvent] == Right(safeEvent)
          )
      },
      test("runs the same scenario through an injected backend") {
        for
          backend <- InjectedBackend.make
          run <- GoldenScenario.executeWith(backend)
          turns <- backend.turns
          observations <- backend.observations
          encoded = run.loopResult.traceDocument.toJson
        yield assertTrue(
          run.loopResult.pins.identity == backend.identity,
          run.loopResult.outcome == RunOutcome.ReadyForReview,
          run.loopResult.iterations == 3,
          run.loopResult.toolCalls == 2,
          run.loopResult.totalTokens == 260L,
          run.loopResult.bdrState.revision.value == 42L,
          run.loopResult.bdrState.runState == "ready_for_review",
          run.checkpointCalls == 2,
          run.auditCalls == 1,
          run.applyCalls == 1,
          turns == 3,
          observations == Chunk(
            Observation(41L, hasContinuation = false),
            Observation(41L, hasContinuation = true),
            Observation(42L, hasContinuation = true)
          ),
          !encoded.contains(InjectedReasoningCanary)
        )
      }
    ) @@ TestAspect.timeout(10.seconds)

  private val InjectedReasoningCanary =
    "INJECTED_REASONING_CANARY_fa37"

  private final case class Observation(
      revision: Long,
      hasContinuation: Boolean
  )

  private final class InjectedContext(
      identity: BackendIdentity,
      val sequence: Int,
      private val reasoning: String
  ) extends OpaqueReasoningContext(identity, ContinuationMode.OpaqueReplay)

  private final class InjectedBackend private (
      val identity: BackendIdentity,
      val capabilities: BackendCapabilities,
      turnCount: Ref[Int],
      observed: Ref[Chunk[Observation]],
      first: InjectedContext,
      second: InjectedContext
  ) extends Backend:
    type Context = InjectedContext

    def turns: UIO[Int] = turnCount.get
    def observations: UIO[Chunk[Observation]] = observed.get

    protected def generate(
        request: ModelRequest[InjectedContext],
        budget: TurnBudget
    ): IO[BatError, ModelTurn[InjectedContext]] =
      observed.update(
        _ :+ Observation(
          request.bdrState.revision.value,
          request.continuation.nonEmpty
        )
      ) *>
        turnCount.updateAndGet(_ + 1).flatMap {
          case 1 =>
            for
              callId <- from(CallId.from("injected-audit-0001"))
              call <- from(
                FunctionCall.make(callId, "bdr_audit_summary", obj())
              )
              usage <- from(
                Usage.make(100, Some(60), Some(20), Some(40), Some(25))
              )
              result <- from(ModelTurn.toolCalls(first, Chunk(call), usage))
            yield result
          case 2 =>
            for
              callId <- from(CallId.from("injected-ready-0002"))
              operation = obj(
                "type" -> Json.Str("set_run_state"),
                "state" -> Json.Str("ready_for_review")
              )
              operationJson <- from(
                StrictJson.canonical(operation, "injected BDR operation")
              )
              call <- from(
                FunctionCall.make(
                  callId,
                  "bdr_apply",
                  obj("operation_json" -> Json.Str(operationJson))
                )
              )
              usage <- from(
                Usage.make(100, Some(70), Some(30), Some(30), Some(20))
              )
              result <- from(ModelTurn.toolCalls(second, Chunk(call), usage))
            yield result
          case 3 =>
            for
              output <- from(FinalOutput.make("Injected backend complete."))
              usage <- from(
                Usage.make(60, Some(40), Some(10), Some(20), Some(10))
              )
            yield ModelTurn.completed(output, usage)
          case _ =>
            ZIO.fail(
              BatError.ProtocolViolation(
                "injected conformance backend received an extra turn"
              )
            )
        }

  private object InjectedBackend:
    def make: IO[BatError, InjectedBackend] =
      for
        identity <- from(
          BackendIdentity.make(
            "injected",
            "injected-conformance-model",
            "rev-injected-1"
          )
        )
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
        turns <- Ref.make(0)
        observations <- Ref.make(Chunk.empty[Observation])
      yield new InjectedBackend(
        identity,
        capabilities,
        turns,
        observations,
        new InjectedContext(identity, 1, InjectedReasoningCanary),
        new InjectedContext(identity, 2, InjectedReasoningCanary)
      )

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def from[A](value: Either[BatError, A]): IO[BatError, A] =
    ZIO.fromEither(value)

  private def loadGolden: Task[SafeTraceDocument] =
    ZIO
      .attemptBlocking {
        val resource =
          getClass.getClassLoader.getResourceAsStream(GoldenResource)
        if resource != null then
          try String(resource.readAllBytes(), StandardCharsets.UTF_8)
          finally resource.close()
        else
          val candidates = List(
            Path.of("src", "test", "resources").resolve(GoldenResource),
            Path
              .of("loop", "src", "test", "resources")
              .resolve(GoldenResource)
          )
          candidates.find(path => Files.isRegularFile(path)) match
            case Some(path) => Files.readString(path, StandardCharsets.UTF_8)
            case None       =>
              throw new java.io.FileNotFoundException(
                s"golden trace was not found: $GoldenResource"
              )
      }
      .flatMap(text =>
        ZIO.fromEither(
          text
            .fromJson[SafeTraceDocument]
            .left
            .map(detail => new IllegalArgumentException(detail))
        )
      )
