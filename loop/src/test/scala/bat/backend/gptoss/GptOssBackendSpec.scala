package bat.backend.gptoss

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.annotation.tailrec

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.conformance.GoldenScenario
import bat.controller.*
import bat.protocol.*
import bat.telemetry.*
import bat.transport.*

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

object GptOssBackendSpec extends ZIOSpecDefault:
  private val Commit = "0123456789abcdef0123456789abcdef01234567"
  private val ModelId = "gpt-oss-120b-q4-k-m"
  private val ModelRevision = "weights-2026-08-09"
  private val FinalReasoningCanary = "RAW_FINAL_REASONING_CANARY_31a9"
  private val ProviderBodyCanary = "RAW_PROVIDER_BODY_CANARY_5fd2"
  private val CredentialCanary = "RAW_CREDENTIAL_CANARY_4c82"

  private val identity = unsafe(
    GptOssConfig.identity(ModelId, ModelRevision)
  )

  private val sseLimits = unsafeTransport(
    SseLimits.make(1024 * 1024, 8 * 1024 * 1024)
  )

  private val transportConfig = unsafeTransport(
    TransportConfig.make(
      "https://models.invalid.internal",
      openTimeout = 5.seconds,
      bodyIdleTimeout = 30.seconds
    )
  )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GPT-OSS Responses backend")(
      test(
        "runs the golden loop through three fragmented Responses streams"
      ) {
        for
          credential <- ZIO.fromEither(Secret.from(CredentialCanary))
          telemetry <- InMemoryTelemetry.make
          driver <- ProbeDriver.make { (attempt, _) =>
            attempt match
              case 1 => ZIO.succeed(okResponse(firstToolStream))
              case 2 => ZIO.succeed(okResponse(secondToolStream))
              case 3 => ZIO.succeed(okResponse(finalStream))
              case _ => ZIO.fail(new IllegalStateException("extra request"))
          }
          backend <- makeBackend(
            driver,
            credential = Some(credential),
            telemetry = telemetry
          )
          run <- GoldenScenario.executeWith(backend)
          requests <- driver.requests
          telemetryRecords <- telemetry.records
          bodies <- ZIO.foreach(requests)(request =>
            ZIO.fromEither(
              StrictJson.parseObject(request.body, "captured request")
            )
          )
        yield
          val firstInput = arrayField(bodies(0), "input").getOrElse(Chunk.empty)
          val secondInput =
            arrayField(bodies(1), "input").getOrElse(Chunk.empty)
          val thirdInput = arrayField(bodies(2), "input").getOrElse(Chunk.empty)
          val firstOutput = secondInput
            .drop(firstInput.size)
            .collectFirst {
              case value: Json.Obj
                  if stringField(value, "type").contains(
                    "function_call_output"
                  ) =>
                value
            }
          val secondOutput = thirdInput
            .drop(secondInput.size)
            .collectFirst {
              case value: Json.Obj
                  if stringField(value, "type").contains(
                    "function_call_output"
                  ) =>
                value
            }
          val trace = run.loopResult.traceDocument.toJson
          val providerAttempts = telemetryRecords.collect {
            case TelemetryRecord(_, event: TelemetryEvent.ProviderAttempt) =>
              event
          }
          val renderedConfig = unsafe(
            GptOssConfig.make(
              identity,
              credential = Some(credential),
              sseLimits = sseLimits
            )
          ).toString

          assertTrue(
            run.loopResult.outcome == RunOutcome.ReadyForReview,
            run.loopResult.finalOutput.text == "Ready for review.",
            run.loopResult.iterations == 3,
            run.loopResult.toolCalls == 2,
            run.loopResult.totalTokens == 260L,
            run.loopResult.bdrState.revision.value == 42L,
            run.checkpointCalls == 2,
            run.auditCalls == 1,
            run.applyCalls == 1,
            requests.size == 3,
            providerAttempts.size == 3,
            providerAttempts.map(_.attribution.iteration) == Chunk(1, 2, 3),
            providerAttempts.forall(attempt =>
              attempt.attempt == 1 &&
                attempt.outcome == ProviderAttemptOutcome.Completed &&
                observedNonNegative(attempt.timing.totalMillis) &&
                observedNonNegative(attempt.timing.responseHeadersMillis) &&
                observedNonNegative(attempt.timing.firstEventMillis) &&
                observedNonNegative(attempt.timing.streamMillis) &&
                attempt.errorCode == Measurement.Unavailable(
                  MissingReason.NotApplicable
                )
            ),
            !telemetryRecords.toString.contains(
              GoldenScenario.ReasoningCanaryOne
            ),
            !telemetryRecords.toString.contains(CredentialCanary),
            requests.forall(request =>
              request.method == "POST" &&
                request.url ==
                "https://models.invalid.internal/v1/responses" &&
                request.accept.contains("text/event-stream") &&
                request.contentType.contains("application/json") &&
                request.authorization.contains(s"Bearer $CredentialCanary")
            ),
            requests.forall(request =>
              !request.toString.contains("Refactor PR 42") &&
                !request.toString.contains(GoldenScenario.ReasoningCanaryOne) &&
                !request.toString.contains(CredentialCanary)
            ),
            !credential.toString.contains(CredentialCanary),
            !renderedConfig.contains(CredentialCanary),
            !backend.toString.contains(CredentialCanary),
            !trace.contains(CredentialCanary),
            firstInput.size == 2,
            secondInput.size == 6,
            thirdInput.size == 10,
            secondInput.take(firstInput.size) == firstInput,
            thirdInput.take(secondInput.size) == secondInput,
            !requests(0).body.contains(GoldenScenario.ReasoningCanaryOne),
            requests(1).body.contains(GoldenScenario.ReasoningCanaryOne),
            !requests(1).body.contains(GoldenScenario.ReasoningCanaryTwo),
            requests(2).body.contains(GoldenScenario.ReasoningCanaryOne),
            requests(2).body.contains(GoldenScenario.ReasoningCanaryTwo),
            !trace.contains(GoldenScenario.ReasoningCanaryOne),
            !trace.contains(GoldenScenario.ReasoningCanaryTwo),
            !trace.contains(FinalReasoningCanary),
            firstOutput.exists(output =>
              stringField(output, "type").contains("function_call_output") &&
                stringField(output, "call_id").contains(
                  "call-audit-0001"
                ) &&
                stringField(output, "output").contains(
                  "{\"is_error\":false,\"output\":[{\"revision\":41}]}"
                )
            ),
            secondOutput.exists(output =>
              stringField(output, "type").contains("function_call_output") &&
                stringField(output, "call_id").contains(
                  "call-ready-0002"
                ) &&
                stringField(output, "output").contains(
                  "{\"is_error\":false,\"output\":{\"result\":{\"state\":\"ready_for_review\"},\"revision\":42}}"
                )
            ),
            longField(bodies(0), "max_output_tokens").contains(260L),
            longField(bodies(1), "max_output_tokens").contains(160L),
            longField(bodies(2), "max_output_tokens").contains(60L)
          )
      },
      test("retries only 429 responses and caps attempts") {
        for
          transientTelemetry <- InMemoryTelemetry.make
          transient <- ProbeDriver.make { (attempt, _) =>
            if attempt == 1 then
              ZIO.succeed(statusResponse(Status.TooManyRequests))
            else ZIO.succeed(okResponse(firstToolStream))
          }
          transientBackend <- makeBackend(
            transient,
            maxAttempts = 3,
            retryDelay = Duration.Zero,
            telemetry = transientTelemetry
          )
          recovered <- transientBackend
            .complete(directRequest, directBudget)
            .either
          transientCalls <- transient.count
          transientRequests <- transient.requests
          transientRecords <- transientTelemetry.records
          exhaustedTelemetry <- InMemoryTelemetry.make
          exhausted <- ProbeDriver.make((_, _) =>
            ZIO.succeed(statusResponse(Status.TooManyRequests))
          )
          exhaustedBackend <- makeBackend(
            exhausted,
            maxAttempts = 3,
            retryDelay = Duration.Zero,
            telemetry = exhaustedTelemetry
          )
          failure <- exhaustedBackend
            .complete(directRequest, directBudget)
            .either
          exhaustedCalls <- exhausted.count
          exhaustedRecords <- exhaustedTelemetry.records
        yield
          val transientAttempts = providerAttempts(transientRecords)
          val transientRetries = retryEvents(transientRecords)
          val exhaustedAttempts = providerAttempts(exhaustedRecords)
          val exhaustedRetries = retryEvents(exhaustedRecords)
          assertTrue(
            recovered.exists {
              case ModelTurn.ToolCalls(_, calls, eventUsage) =>
                calls.map(_.callId.value) == Chunk("call-audit-0001") &&
                eventUsage.totalTokens == 100L
              case _ => false
            },
            transientCalls == 2,
            transientRequests.size == 2,
            transientRequests.head.body == transientRequests.last.body,
            transientRequests.head.authorization ==
              transientRequests.last.authorization,
            transientAttempts.size == 2,
            transientAttempts.map(_.attempt) == Chunk(1, 2),
            providerEventOrder(transientRecords) == Chunk(
              "attempt:1",
              "retry:1",
              "attempt:2"
            ),
            transientAttempts.head.outcome == ProviderAttemptOutcome.Rejected,
            observedCode(
              transientAttempts.head.errorCode,
              "gpt_oss_rate_limited"
            ),
            transientAttempts.last.outcome == ProviderAttemptOutcome.Completed,
            transientAttempts.last.errorCode == Measurement.Unavailable(
              MissingReason.NotApplicable
            ),
            transientAttempts.forall(attempt =>
              attempt.attribution.iteration == 1 &&
                attempt.attribution.revision == 41L &&
                observedNonNegative(attempt.timing.totalMillis) &&
                observedNonNegative(attempt.timing.responseHeadersMillis)
            ),
            transientRetries.size == 1,
            transientRetries.head.failedAttempt == 1,
            transientRetries.head.nextAttempt == 2,
            transientRetries.head.reasonCode.value == "gpt_oss_rate_limited",
            transientRetries.head.delayMillis == 0L,
            exhaustedCalls == 3,
            exhaustedAttempts.size == 3,
            exhaustedAttempts.forall(
              _.outcome == ProviderAttemptOutcome.Rejected
            ),
            providerEventOrder(exhaustedRecords) == Chunk(
              "attempt:1",
              "retry:1",
              "attempt:2",
              "retry:2",
              "attempt:3"
            ),
            exhaustedRetries.map(retry =>
              retry.failedAttempt -> retry.nextAttempt
            ) == Chunk(1 -> 2, 2 -> 3),
            failure.left.exists {
              case error: BatError.BackendFailure =>
                error.code == "gpt_oss_rate_limited" && error.retryable
              case _ => false
            },
            !transientRecords.toString.contains(
              GoldenScenario.ReasoningCanaryOne
            )
          )
      },
      test("does not retry 5xx or a failed partial response body") {
        for
          unavailableTelemetry <- InMemoryTelemetry.make
          unavailable <- ProbeDriver.make((_, _) =>
            ZIO.succeed(statusResponse(Status.ServiceUnavailable))
          )
          unavailableBackend <- makeBackend(
            unavailable,
            maxAttempts = 3,
            retryDelay = Duration.Zero,
            telemetry = unavailableTelemetry
          )
          unavailableResult <- unavailableBackend
            .complete(directRequest, directBudget)
            .either
          unavailableCalls <- unavailable.count
          unavailableRecords <- unavailableTelemetry.records
          partialTelemetry <- InMemoryTelemetry.make
          partial <- ProbeDriver.make((_, _) =>
            ZIO.succeed(partialFailureResponse)
          )
          partialBackend <- makeBackend(
            partial,
            maxAttempts = 3,
            retryDelay = Duration.Zero,
            telemetry = partialTelemetry
          )
          partialResult <- partialBackend
            .complete(directRequest, directBudget)
            .either
          partialCalls <- partial.count
          partialRecords <- partialTelemetry.records
        yield
          val unavailableAttempts = providerAttempts(unavailableRecords)
          val partialAttempts = providerAttempts(partialRecords)
          assertTrue(
            unavailableCalls == 1,
            unavailableResult.left.exists {
              case error: BatError.BackendFailure =>
                error.code == "gpt_oss_endpoint_unavailable" &&
                !error.retryable
              case _ => false
            },
            unavailableAttempts.size == 1,
            unavailableAttempts.head.outcome ==
              ProviderAttemptOutcome.Failed,
            observedCode(
              unavailableAttempts.head.errorCode,
              "gpt_oss_endpoint_unavailable"
            ),
            retryEvents(unavailableRecords).isEmpty,
            partialCalls == 1,
            partialResult.left.exists {
              case error: BatError.BackendFailure =>
                error.code == "gpt_oss_body_failed" && !error.retryable &&
                !error.safeMessage.contains(ProviderBodyCanary)
              case _ => false
            },
            partialAttempts.size == 1,
            partialAttempts.head.outcome == ProviderAttemptOutcome.Failed,
            observedCode(
              partialAttempts.head.errorCode,
              "gpt_oss_body_failed"
            ),
            observedNonNegative(partialAttempts.head.timing.firstEventMillis),
            observedNonNegative(partialAttempts.head.timing.streamMillis),
            retryEvents(partialRecords).isEmpty,
            !partialResult.toString.contains(ProviderBodyCanary),
            !partialRecords.toString.contains(ProviderBodyCanary)
          )
      },
      test("sanitizes bad content type and malformed provider bodies") {
        for
          badContentType <- ProbeDriver.make((_, _) =>
            ZIO.succeed(
              streamResponse(
                Status.Ok,
                "text/plain",
                ZStream.fromChunk(
                  Chunk.fromArray(
                    ProviderBodyCanary.getBytes(StandardCharsets.UTF_8)
                  )
                )
              )
            )
          )
          contentBackend <- makeBackend(badContentType)
          contentFailure <- contentBackend
            .complete(directRequest, directBudget)
            .either
          malformed <- ProbeDriver.make((_, _) =>
            ZIO.succeed(
              okResponse(
                s"data: {\"type\":\"$ProviderBodyCanary\",\"sequence_number\":0}\n\n"
              )
            )
          )
          malformedBackend <- makeBackend(malformed)
          malformedFailure <- malformedBackend
            .complete(directRequest, directBudget)
            .either
        yield assertTrue(
          contentFailure.left.exists {
            case error: BatError.BackendFailure =>
              error.code == "gpt_oss_content_type" &&
              !error.safeMessage.contains(ProviderBodyCanary)
            case _ => false
          },
          malformedFailure.left.exists {
            case error: BatError.BackendFailure =>
              error.code == "gpt_oss_protocol_violation" &&
              !error.safeMessage.contains(ProviderBodyCanary)
            case _ => false
          },
          !contentFailure.toString.contains(ProviderBodyCanary),
          !malformedFailure.toString.contains(ProviderBodyCanary)
        )
      },
      test("enforces the raw Responses event character limit") {
        for
          driver <- ProbeDriver.make((_, _) =>
            ZIO.succeed(okResponse(oversizedRawEventStream))
          )
          backend <- makeBackend(
            driver,
            responsesLimits = constrainedResponsesLimits
          )
          result <- backend.complete(directRequest, directBudget).either
          calls <- driver.count
        yield assertTrue(
          calls == 1,
          result.left.exists {
            case error: BatError.BackendFailure =>
              error.code == "gpt_oss_protocol_violation" &&
              !error.retryable
            case _ => false
          }
        )
      },
      test("rejects truncated and malformed streams before any tool effect") {
        for
          truncatedEffects <- Ref.make(0)
          truncatedDriver <- ProbeDriver.make((_, _) =>
            ZIO.succeed(
              okResponse(
                toSse(
                  functionTurnEvents(
                    "resp_truncated",
                    "rs_truncated",
                    "fc_truncated",
                    "truncated reasoning",
                    "call-truncated",
                    "effect",
                    "{}",
                    firstUsage
                  ).dropRight(1),
                  includeDone = false
                )
              )
            )
          )
          truncatedBackend <- makeBackend(truncatedDriver)
          truncated <- runEffectLoop(truncatedBackend, truncatedEffects).either
          truncatedCount <- truncatedEffects.get
          malformedEffects <- Ref.make(0)
          malformedDriver <- ProbeDriver.make((_, _) =>
            ZIO.succeed(
              okResponse(
                toSse(
                  functionTurnEvents(
                    "resp_malformed",
                    "rs_malformed",
                    "fc_malformed",
                    "malformed reasoning",
                    "call-malformed",
                    "effect",
                    "not-json",
                    firstUsage
                  )
                )
              )
            )
          )
          malformedBackend <- makeBackend(malformedDriver)
          malformed <- runEffectLoop(malformedBackend, malformedEffects).either
          malformedCount <- malformedEffects.get
        yield assertTrue(
          truncated.isLeft,
          malformed.isLeft,
          truncatedCount == 0,
          malformedCount == 0,
          truncated.left.exists(_.code == "gpt_oss_protocol_violation"),
          malformed.left.exists(_.code == "gpt_oss_protocol_violation")
        )
      }
    ) @@ TestAspect.timeout(30.seconds)

  private final class CapturedRequest(
      val method: String,
      val url: String,
      val authorization: Option[String],
      val accept: Option[String],
      val contentType: Option[String],
      val body: String
  ):
    override def toString: String =
      "CapturedRequest(method=<redacted>, url=<redacted>, headers=<redacted>, body=<redacted>)"

  private final class ProbeDriver private (
      captured: Ref[Chunk[CapturedRequest]],
      attempts: Ref[Int],
      respond: (Int, CapturedRequest) => ZIO[Scope, Throwable, Response]
  ) extends ZClient.Driver[Any, Scope, Throwable]:
    def requests: UIO[Chunk[CapturedRequest]] = captured.get
    def count: UIO[Int] = attempts.get

    override def request(
        version: Version,
        method: Method,
        url: URL,
        headers: Headers,
        body: Body,
        sslConfig: Option[ClientSSLConfig],
        proxy: Option[Proxy]
    )(implicit trace: Trace): ZIO[Scope, Throwable, Response] =
      for
        bodyText <- body.asString
        observed = new CapturedRequest(
          method.render,
          url.encode,
          headers.get("authorization"),
          headers.get("accept"),
          headers.get("content-type"),
          bodyText
        )
        attempt <- attempts.updateAndGet(_ + 1)
        _ <- captured.update(_ :+ observed)
        response <- respond(attempt, observed)
      yield response

    override def socket[Env1](
        version: Version,
        url: URL,
        headers: Headers,
        app: WebSocketApp[Env1]
    )(implicit
        trace: Trace,
        ev: Scope =:= Scope
    ): ZIO[Env1 & Scope, Throwable, Response] =
      ZIO.fail(new UnsupportedOperationException("websocket disabled in test"))

  private object ProbeDriver:
    def make(
        respond: (Int, CapturedRequest) => ZIO[Scope, Throwable, Response]
    ): UIO[ProbeDriver] =
      for
        captured <- Ref.make(Chunk.empty[CapturedRequest])
        attempts <- Ref.make(0)
      yield new ProbeDriver(captured, attempts, respond)

  private def makeBackend(
      driver: ProbeDriver,
      maxAttempts: Int = 1,
      retryDelay: Duration = Duration.Zero,
      credential: Option[Secret] = None,
      responsesLimits: ResponsesLimits = ResponsesLimits.default,
      telemetry: Telemetry = Telemetry.noop
  ): IO[BatError, GptOssBackend] =
    for
      http <- ZIO
        .service[StreamingHttp]
        .provideLayer(httpLayer(driver))
      config <- ZIO.fromEither(
        GptOssConfig.make(
          identity,
          credential = credential,
          sseLimits = sseLimits,
          responsesLimits = responsesLimits,
          maxOutputTokens = 1024,
          maxAttempts = maxAttempts,
          retryDelay = retryDelay
        )
      )
      backend <- ZIO.fromEither(GptOssBackend.make(config, http, telemetry))
    yield backend

  private def providerAttempts(
      records: Chunk[TelemetryRecord]
  ): Chunk[TelemetryEvent.ProviderAttempt] =
    records.collect {
      case TelemetryRecord(_, event: TelemetryEvent.ProviderAttempt) => event
    }

  private def retryEvents(
      records: Chunk[TelemetryRecord]
  ): Chunk[TelemetryEvent.Retry] =
    records.collect { case TelemetryRecord(_, event: TelemetryEvent.Retry) =>
      event
    }

  private def observedNonNegative(value: Measurement[Long]): Boolean =
    value match
      case Measurement.Observed(number) => number >= 0L
      case Measurement.Unavailable(_)   => false

  private def observedCode(
      value: Measurement[TelemetryCode],
      expected: String
  ): Boolean =
    value match
      case Measurement.Observed(code) => code.value == expected
      case Measurement.Unavailable(_) => false

  private def providerEventOrder(
      records: Chunk[TelemetryRecord]
  ): Chunk[String] =
    records.map {
      case TelemetryRecord(_, event: TelemetryEvent.ProviderAttempt) =>
        s"attempt:${event.attempt}"
      case TelemetryRecord(_, event: TelemetryEvent.Retry) =>
        s"retry:${event.failedAttempt}"
      case _ => "other"
    }

  private def httpLayer(
      driver: ZClient.Driver[Any, Scope, Throwable]
  ): ULayer[StreamingHttp] =
    val client: Client = ZClient.fromDriver(driver)
    ZLayer.succeed(client) >>> StreamingHttp.configured(transportConfig)

  private lazy val directRequest: ModelRequest[GptOssContext] =
    val pins = unsafe(
      RunPins.make(identity, "high", "backend-spec-v1", Commit)
    )
    unsafe(
      ModelRequest.make(
        pins,
        unsafe(DeveloperInput.make("Follow BDR.")),
        Chunk(InputEvent.User(unsafe(UserInput.make("Inspect the PR.")))),
        Chunk.empty,
        initialState.view,
        iteration = 1,
        continuation = None
      )
    )

  private val directBudget: TurnBudget =
    unsafe(TurnBudget.make(10.seconds, 1000L))

  private def runEffectLoop(
      backend: GptOssBackend,
      effects: Ref[Int]
  ): IO[BatError, LoopResult] =
    for
      registry <- ZIO.fromEither(
        ToolRegistry.make(Chunk(new EffectTool(effects)))
      )
      limits <- ZIO.fromEither(BudgetLimits.make(2, 1, 10.seconds, 200L))
      pins <- ZIO.fromEither(
        RunPins.make(identity, "high", "backend-spec-v1", Commit)
      )
      spec = RunSpec.make(
        RunMode.FullWriter,
        pins,
        unsafe(DeveloperInput.make("Use the effect tool.")),
        unsafe(UserInput.make("Exercise the backend boundary.")),
        limits,
        requiredCapabilities = Set(Capability.Streaming)
      )
      result <- AgenticLoop.run(spec, backend, registry, NoopBdr)
    yield result

  private final class EffectTool(effects: Ref[Int]) extends Tool:
    val definition: ToolDefinition = unsafe(
      ToolDefinition.make(
        "effect",
        "Record one test-only effect.",
        emptyObjectSchema
      )
    )

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      effects.update(_ + 1).as(obj("ok" -> Json.Bool(true)))

  private object NoopBdr extends BdrSession:
    val engineCommit: String = Commit
    val actor: String = "bat"

    def current: IO[BatError, ValidatedBdrState] = ZIO.succeed(initialState)
    def checkpoint: IO[BatError, ValidatedBdrState] = ZIO.succeed(initialState)
    def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
      ZIO.fail(BatError.BdrFailure("unused", "unused test BDR operation"))
    def auditSummary: IO[BatError, Json] =
      ZIO.fail(BatError.BdrFailure("unused", "unused test BDR audit"))
    def completionCheck: IO[BatError, Json.Obj] =
      ZIO.fail(BatError.BdrFailure("unused", "unused completion check"))

  private val initialState: ValidatedBdrState =
    ValidatedBdrState(
      Path.of("/backend-spec"),
      Path.of(".bdr/progress.yaml"),
      unsafe(
        BdrStateView.make(
          unsafe(Revision.from(41L)),
          "executing",
          obj("action" -> Json.Str("inspect")),
          "1" * 64
        )
      )
    )

  private val emptyObjectSchema: Json.Obj =
    obj(
      "type" -> Json.Str("object"),
      "properties" -> obj(),
      "required" -> Json.Arr(Chunk.empty),
      "additionalProperties" -> Json.Bool(false)
    )

  private val firstUsage = usageJson(60, 20, 40, 25)
  private val secondUsage = usageJson(70, 30, 30, 20)
  private val finalUsage = usageJson(40, 10, 20, 10)

  private val constrainedResponsesLimits = unsafe(
    ResponsesLimits.make(
      maxEventCharacters = 2048,
      maxOutputItems = 128,
      maxFunctionCalls = 64,
      maxReasoningCharacters = 4 * 1024 * 1024,
      maxArgumentsCharacters = 1024 * 1024,
      maxOutputCharacters = 2 * 1024 * 1024
    )
  )

  private val firstToolStream: String =
    toSse(
      functionTurnEvents(
        "resp_audit",
        "rs_audit",
        "fc_audit",
        GoldenScenario.ReasoningCanaryOne + " 🦇",
        "call-audit-0001",
        "bdr_audit_summary",
        "{}",
        firstUsage
      )
    )

  private val oversizedRawEventStream: String =
    firstToolStream.replaceFirst(
      "data: ",
      "data: " + (" " * (constrainedResponsesLimits.maxEventCharacters + 1))
    )

  private val secondArguments =
    "{\"operation_json\":\"{\\\"state\\\":\\\"ready_for_review\\\",\\\"type\\\":\\\"set_run_state\\\"}\"}"

  private val secondToolStream: String =
    toSse(
      functionTurnEvents(
        "resp_ready",
        "rs_ready",
        "fc_ready",
        GoldenScenario.ReasoningCanaryTwo + " 🦇",
        "call-ready-0002",
        "bdr_apply",
        secondArguments,
        secondUsage
      )
    )

  private val finalStream: String =
    toSse(
      finalTurnEvents(
        "resp_final",
        "rs_final",
        "msg_final",
        FinalReasoningCanary + " 🦇",
        "Ready for review.",
        finalUsage
      )
    )

  private def functionTurnEvents(
      responseId: String,
      reasoningId: String,
      functionId: String,
      reasoning: String,
      callId: String,
      name: String,
      arguments: String,
      usage: Json.Obj
  ): Chunk[Json.Obj] =
    val reasoningSplit = Math.min(7, reasoning.length)
    val argumentSplit = Math.min(5, arguments.length)
    val reasoningRaw = reasoningItem(reasoningId, reasoning)
    val functionRaw = functionItem(
      functionId,
      callId,
      name,
      arguments,
      "completed"
    )
    Chunk(
      created(0, responseId),
      progress(1, responseId),
      outputItemAdded(2, 0, reasoningAdded(reasoningId)),
      contentPartAdded(3, reasoningId, 0, reasoningPart("")),
      textEvent(
        "response.reasoning_text.delta",
        4,
        reasoningId,
        0,
        "delta",
        reasoning.take(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.delta",
        5,
        reasoningId,
        0,
        "delta",
        reasoning.drop(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.done",
        6,
        reasoningId,
        0,
        "text",
        reasoning
      ),
      contentPartDone(7, reasoningId, 0, reasoningPart(reasoning)),
      outputItemDone(8, 0, reasoningRaw),
      outputItemAdded(
        9,
        1,
        functionItem(functionId, callId, name, "", "in_progress")
      ),
      functionArgumentsDelta(
        10,
        functionId,
        1,
        arguments.take(argumentSplit)
      ),
      functionArgumentsDelta(
        11,
        functionId,
        1,
        arguments.drop(argumentSplit)
      ),
      functionArgumentsDone(12, functionId, 1, name, arguments),
      outputItemDone(13, 1, functionRaw),
      completed(14, responseId, Chunk(reasoningRaw, functionRaw), usage)
    )

  private def finalTurnEvents(
      responseId: String,
      reasoningId: String,
      messageId: String,
      reasoning: String,
      output: String,
      usage: Json.Obj
  ): Chunk[Json.Obj] =
    val reasoningSplit = Math.min(7, reasoning.length)
    val outputSplit = Math.min(6, output.length)
    val reasoningRaw = reasoningItem(reasoningId, reasoning)
    val messageRaw = messageItem(messageId, output, "completed")
    Chunk(
      created(0, responseId),
      progress(1, responseId),
      outputItemAdded(2, 0, reasoningAdded(reasoningId)),
      contentPartAdded(3, reasoningId, 0, reasoningPart("")),
      textEvent(
        "response.reasoning_text.delta",
        4,
        reasoningId,
        0,
        "delta",
        reasoning.take(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.delta",
        5,
        reasoningId,
        0,
        "delta",
        reasoning.drop(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.done",
        6,
        reasoningId,
        0,
        "text",
        reasoning
      ),
      contentPartDone(7, reasoningId, 0, reasoningPart(reasoning)),
      outputItemDone(8, 0, reasoningRaw),
      outputItemAdded(9, 1, messageItem(messageId, "", "in_progress")),
      contentPartAdded(10, messageId, 1, outputPart("")),
      textEvent(
        "response.output_text.delta",
        11,
        messageId,
        1,
        "delta",
        output.take(outputSplit)
      ),
      textEvent(
        "response.output_text.delta",
        12,
        messageId,
        1,
        "delta",
        output.drop(outputSplit)
      ),
      textEvent(
        "response.output_text.done",
        13,
        messageId,
        1,
        "text",
        output
      ),
      contentPartDone(14, messageId, 1, outputPart(output)),
      outputItemDone(15, 1, messageRaw),
      completed(16, responseId, Chunk(reasoningRaw, messageRaw), usage)
    )

  private def created(sequence: Long, responseId: String): Json.Obj =
    event(
      "response.created",
      sequence,
      "response" -> obj(
        "id" -> Json.Str(responseId),
        "model" -> Json.Str(ModelId),
        "status" -> Json.Str("in_progress")
      )
    )

  private def progress(sequence: Long, responseId: String): Json.Obj =
    event(
      "response.in_progress",
      sequence,
      "response" -> obj(
        "id" -> Json.Str(responseId),
        "model" -> Json.Str(ModelId),
        "status" -> Json.Str("in_progress")
      )
    )

  private def outputItemAdded(
      sequence: Long,
      outputIndex: Int,
      item: Json.Obj
  ): Json.Obj =
    event(
      "response.output_item.added",
      sequence,
      "output_index" -> number(outputIndex),
      "item" -> item
    )

  private def contentPartAdded(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      part: Json.Obj
  ): Json.Obj =
    event(
      "response.content_part.added",
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "content_index" -> number(0),
      "part" -> part
    )

  private def contentPartDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      part: Json.Obj
  ): Json.Obj =
    event(
      "response.content_part.done",
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "content_index" -> number(0),
      "part" -> part
    )

  private def textEvent(
      eventType: String,
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      valueName: String,
      value: String
  ): Json.Obj =
    event(
      eventType,
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "content_index" -> number(0),
      valueName -> Json.Str(value)
    )

  private def functionArgumentsDelta(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      delta: String
  ): Json.Obj =
    event(
      "response.function_call_arguments.delta",
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "delta" -> Json.Str(delta)
    )

  private def functionArgumentsDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      name: String,
      arguments: String
  ): Json.Obj =
    event(
      "response.function_call_arguments.done",
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "name" -> Json.Str(name),
      "arguments" -> Json.Str(arguments)
    )

  private def outputItemDone(
      sequence: Long,
      outputIndex: Int,
      item: Json.Obj
  ): Json.Obj =
    event(
      "response.output_item.done",
      sequence,
      "output_index" -> number(outputIndex),
      "item" -> item
    )

  private def completed(
      sequence: Long,
      responseId: String,
      output: Chunk[Json.Obj],
      usage: Json.Obj
  ): Json.Obj =
    event(
      "response.completed",
      sequence,
      "response" -> obj(
        "id" -> Json.Str(responseId),
        "model" -> Json.Str(ModelId),
        "status" -> Json.Str("completed"),
        "error" -> Json.Null,
        "incomplete_details" -> Json.Null,
        "output" -> Json.Arr(output.map(value => value: Json)),
        "usage" -> usage
      )
    )

  private def event(
      eventType: String,
      sequence: Long,
      fields: (String, Json)*
  ): Json.Obj =
    obj(
      (Chunk[(String, Json)](
        "type" -> Json.Str(eventType),
        "sequence_number" -> number(sequence)
      ) ++ Chunk.fromIterable(fields))*
    )

  private def reasoningAdded(id: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("reasoning"),
      "summary" -> Json.Arr(Chunk.empty)
    )

  private def reasoningItem(id: String, text: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("reasoning"),
      "summary" -> Json.Arr(Chunk.empty),
      "content" -> Json.Arr(Chunk(reasoningPart(text)))
    )

  private def functionItem(
      id: String,
      callId: String,
      name: String,
      arguments: String,
      status: String
  ): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("function_call"),
      "status" -> Json.Str(status),
      "call_id" -> Json.Str(callId),
      "name" -> Json.Str(name),
      "arguments" -> Json.Str(arguments)
    )

  private def messageItem(
      id: String,
      text: String,
      status: String
  ): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("message"),
      "status" -> Json.Str(status),
      "role" -> Json.Str("assistant"),
      "content" -> Json.Arr(
        if text.isEmpty then Chunk.empty else Chunk(outputPart(text))
      )
    )

  private def reasoningPart(text: String): Json.Obj =
    obj(
      "type" -> Json.Str("reasoning_text"),
      "text" -> Json.Str(text)
    )

  private def outputPart(text: String): Json.Obj =
    obj(
      "type" -> Json.Str("output_text"),
      "text" -> Json.Str(text),
      "annotations" -> Json.Arr(Chunk.empty)
    )

  private def usageJson(
      input: Long,
      cached: Long,
      output: Long,
      reasoning: Long
  ): Json.Obj =
    obj(
      "input_tokens" -> number(input),
      "input_tokens_details" -> obj("cached_tokens" -> number(cached)),
      "output_tokens" -> number(output),
      "output_tokens_details" -> obj(
        "reasoning_tokens" -> number(reasoning)
      ),
      "total_tokens" -> number(input + output)
    )

  private def toSse(
      events: Chunk[Json.Obj],
      includeDone: Boolean = true
  ): String =
    val semantic = events.map { value =>
      val eventType = stringField(value, "type").getOrElse("message")
      s"event: $eventType\r\ndata: ${value.toJson}\r\n\r\n"
    }.mkString
    val done = if includeDone then "data: [DONE]\r\n\r\n" else ""
    s": 🦇\r\n\r\n$semantic$done"

  private def okResponse(payload: String): Response =
    streamResponse(
      Status.Ok,
      "text/event-stream; charset=utf-8",
      hostileStream(payload)
    )

  private def statusResponse(status: Status): Response =
    Response(
      status = status,
      headers = Headers("content-type" -> "text/plain"),
      body = Body.empty
    )

  private val partialFailureResponse: Response =
    val prefix = toSse(Chunk(created(0, "resp_partial")), includeDone = false)
    val stream =
      hostileStream(prefix) ++
        ZStream.fail(
          new RuntimeException(s"$ProviderBodyCanary after partial response")
        )
    streamResponse(Status.Ok, "text/event-stream", stream)

  private def streamResponse(
      status: Status,
      contentType: String,
      chunks: ZStream[Any, Throwable, Byte]
  ): Response =
    Response(
      status = status,
      headers = Headers("content-type" -> contentType),
      body = Body.fromStreamChunked(chunks)
    )

  private def hostileStream(value: String): ZStream[Any, Throwable, Byte] =
    ZStream
      .fromIterable(hostileChunks(value))
      .flatMap(chunk => ZStream.fromChunk(chunk))

  private def hostileChunks(value: String): Chunk[Chunk[Byte]] =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    val sizes = Array(1, 2, 3, 5, 8, 13, 21)

    @tailrec
    def loop(
        offset: Int,
        index: Int,
        accumulated: List[Chunk[Byte]]
    ): List[Chunk[Byte]] =
      if offset >= bytes.length then accumulated.reverse
      else
        val end = Math.min(bytes.length, offset + sizes(index % sizes.length))
        loop(
          end,
          index + 1,
          Chunk.fromArray(bytes.slice(offset, end)) :: accumulated
        )

    Chunk.fromIterable(loop(0, 0, Nil))

  private def arrayField(
      value: Json.Obj,
      name: String
  ): Option[Chunk[Json]] =
    field(value, name).collect { case Json.Arr(result) => result }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }

  private def longField(value: Json.Obj, name: String): Option[Long] =
    field(value, name).collect { case Json.Num(result) =>
      result.longValueExact()
    }

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalStateException(error.safeMessage),
      result => result
    )

  private def unsafeTransport[A](value: Either[?, A]): A =
    value.fold(
      _ => throw new IllegalStateException("invalid test transport fixture"),
      result => result
    )
