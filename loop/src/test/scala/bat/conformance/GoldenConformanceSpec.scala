package bat.conformance

import bat.protocol.RunOutcome
import bat.trace.{SafeTraceDocument, SafeTraceEvent}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import zio.*
import zio.json.*
import zio.test.*

object GoldenConformanceSpec extends ZIOSpecDefault:
  private val GoldenResource =
    "conformance/v1/two-bdr-tools-then-final.json"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("provider-neutral golden conformance")(
      test("actual executable scenario matches the immutable v1 trace") {
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
          val alternateRawPayloadEvents = Chunk(
            s"""{
               |  "type": "function_call",
               |  "call_id": "call-canary",
               |  "name": "bdr_audit_summary",
               |  "arguments": "${GoldenScenario.ReasoningCanaryOne}"
               |}""".stripMargin,
            s"""{
               |  "type": "function_output",
               |  "call_id": "call-canary",
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
            alternateRawPayloadEvents.forall(
              _.fromJson[SafeTraceEvent].isLeft
            ),
            safeEvent.toJson.fromJson[SafeTraceEvent] == Right(safeEvent)
          )
      }
    ) @@ TestAspect.timeout(10.seconds)

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
