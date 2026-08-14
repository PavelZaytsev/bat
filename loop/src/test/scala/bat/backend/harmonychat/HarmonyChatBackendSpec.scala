package bat.backend.harmonychat

import java.nio.charset.StandardCharsets

import bat.backend.wire.WireReplayPolicy
import bat.protocol.*
import bat.telemetry.*
import bat.transport.*

import zio.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

/** Recovery conformance for a restart-prone, self-hosted Harmony endpoint.
  *
  * These tests operate at the shared streaming-backend boundary. In particular,
  * they prove that retries reuse the identical prepared request object and
  * immutable dialect seed; provider fragments from a failed body never escape
  * as a model turn.
  */
object HarmonyChatBackendSpec extends ZIOSpecDefault:
  private val Commit = "0123456789abcdef0123456789abcdef01234567"
  private val Digest = "1" * 64
  private val ModelId = "openai/gpt-oss-120b"
  private val ModelRevision = "weights-2026-08-13"

  private val identity = unsafe(
    HarmonyChatConfig.identity(ModelId, ModelRevision)
  )

  private val sseLimits = unsafeSse(
    SseLimits.make(1024 * 1024, 8 * 1024 * 1024)
  )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Harmony Chat restart recovery")(
      test("keeps transient replay disabled by default") {
        for
          open <- exercise(
            Chunk(Left(TransportError.OpenFailed), Right(okResponse))
          )
          unavailable <- exercise(
            Chunk(Right(statusResponse(503)), Right(okResponse))
          )
        yield assertTrue(
          open.requestCount == 1,
          open.result.left.exists(nonRetryable("harmony_chat_open_failed")),
          open.retries.isEmpty,
          unavailable.requestCount == 1,
          unavailable.result.left.exists(
            nonRetryable("harmony_chat_endpoint_unavailable")
          ),
          unavailable.retries.isEmpty
        )
      },
      test(
        "replays one exact request after transient transport failures"
      ) {
        val failures = Chunk(
          TransportError.OpenFailed,
          TransportError.OpenTimedOut,
          TransportError.BodyFailed,
          TransportError.BodyTimedOut
        )
        for observations <- ZIO.foreach(failures) { failure =>
            val first = failure match
              case TransportError.BodyFailed | TransportError.BodyTimedOut =>
                Right(failedBodyResponse(failure))
              case _ => Left(failure)
            exercise(
              Chunk(first, Right(okResponse)),
              WireReplayPolicy.RetryTransientFailures
            ).map(failure -> _)
          }
        yield assertTrue(observations.forall { case (failure, observed) =>
          observed.result.exists {
            case ModelTurn.Completed(output, usage) =>
              output.text == "Ready." && usage.totalTokens == 90L
            case _ => false
          } &&
          observed.requestCount == 2 &&
          observed.samePreparedRequest &&
          observed.attempts.map(_.attempt) == Chunk(1, 2) &&
          observed.attempts.head.outcome == ProviderAttemptOutcome.Failed &&
          observed.attempts.last.outcome == ProviderAttemptOutcome.Completed &&
          observed.retries.map(retry =>
            (
              retry.failedAttempt,
              retry.nextAttempt,
              retry.reasonCode.value
            )
          ) == Chunk((1, 2, s"harmony_chat_${failure.code}")) &&
          observed.eventOrder == Chunk("attempt:1", "retry:1", "attempt:2") &&
          !observed.records.toString.contains(PartialReasoningCanary)
        })
      },
      test("retries 408 and 5xx only under the explicit replay policy") {
        val statuses = Chunk(
          408 -> "harmony_chat_request_timeout",
          500 -> "harmony_chat_endpoint_unavailable",
          503 -> "harmony_chat_endpoint_unavailable",
          599 -> "harmony_chat_endpoint_unavailable"
        )
        for observations <- ZIO.foreach(statuses) { case (status, code) =>
            exercise(
              Chunk(Right(statusResponse(status)), Right(okResponse)),
              WireReplayPolicy.RetryTransientFailures
            ).map((code, _))
          }
        yield assertTrue(observations.forall { case (code, observed) =>
          observed.result.isRight &&
          observed.requestCount == 2 &&
          observed.samePreparedRequest &&
          observed.retries.map(_.reasonCode.value) == Chunk(code)
        })
      },
      test("keeps retry backoff inside the turn wall budget") {
        val shortBudget = unsafe(TurnBudget.make(100.millis, 1000L))
        for
          fiber <- exercise(
            Chunk(Left(TransportError.OpenFailed), Right(okResponse)),
            WireReplayPolicy.RetryTransientFailures,
            retryDelay = 1.second,
            turnBudget = shortBudget
          ).fork
          _ <- TestClock.adjust(100.millis)
          observed <- fiber.join
        yield assertTrue(
          observed.result.left.exists(
            _ == BatError.BudgetExceeded(BudgetKind.WallTime)
          ),
          observed.requestCount == 1,
          observed.attempts.map(_.attempt) == Chunk(1),
          observed.retries.map(retry =>
            retry.failedAttempt -> retry.nextAttempt
          ) == Chunk(1 -> 2)
        )
      },
      test(
        "retries 404 only for an already-qualified self-hosted Chat path"
      ) {
        for
          recovered <- exercise(
            Chunk(Right(statusResponse(404)), Right(okResponse)),
            WireReplayPolicy.RetryQualifiedSelfHosted
          )
          forbidden <- ZIO.foreach(Chunk(401, 403, 405)) { status =>
            exercise(
              Chunk(Right(statusResponse(status)), Right(okResponse)),
              WireReplayPolicy.RetryQualifiedSelfHosted
            )
          }
          malformed <- exercise(
            Chunk(Right(malformedResponse), Right(okResponse)),
            WireReplayPolicy.RetryQualifiedSelfHosted
          )
        yield assertTrue(
          recovered.result.isRight,
          recovered.requestCount == 2,
          recovered.samePreparedRequest,
          recovered.retries.map(_.reasonCode.value) == Chunk(
            "harmony_chat_completions_unavailable"
          ),
          forbidden.forall(observed =>
            observed.requestCount == 1 && observed.retries.isEmpty
          ),
          malformed.requestCount == 1,
          malformed.retries.isEmpty
        )
      },
      test("never replays auth, missing-dialect, or malformed responses") {
        val statuses = Chunk(
          401 -> "harmony_chat_unauthorized",
          403 -> "harmony_chat_unauthorized",
          404 -> "harmony_chat_completions_unavailable",
          405 -> "harmony_chat_completions_unavailable"
        )
        for
          rejected <- ZIO.foreach(statuses) { case (status, code) =>
            exercise(
              Chunk(Right(statusResponse(status)), Right(okResponse)),
              WireReplayPolicy.RetryTransientFailures
            ).map((code, _))
          }
          malformed <- exercise(
            Chunk(Right(malformedResponse), Right(okResponse)),
            WireReplayPolicy.RetryTransientFailures
          )
        yield assertTrue(
          rejected.forall { case (code, observed) =>
            observed.requestCount == 1 &&
            observed.result.left.exists(nonRetryable(code)) &&
            observed.retries.isEmpty
          },
          malformed.requestCount == 1,
          malformed.result.left.exists {
            case error: BatError.BackendFailure => !error.retryable
            case _                              => false
          },
          malformed.retries.isEmpty
        )
      }
    ) @@ TestAspect.timeout(30.seconds)

  private final case class Observation(
      result: Either[BatError, ModelTurn[HarmonyChatContext]],
      requests: Chunk[StreamingRequest],
      records: Chunk[TelemetryRecord]
  ):
    val requestCount: Int = requests.size
    val samePreparedRequest: Boolean =
      requests.size == 2 && (requests.head eq requests.last)
    val attempts: Chunk[TelemetryEvent.ProviderAttempt] =
      records.collect {
        case TelemetryRecord(_, event: TelemetryEvent.ProviderAttempt) => event
      }
    val retries: Chunk[TelemetryEvent.Retry] =
      records.collect { case TelemetryRecord(_, event: TelemetryEvent.Retry) =>
        event
      }
    val eventOrder: Chunk[String] = records.map {
      case TelemetryRecord(_, event: TelemetryEvent.ProviderAttempt) =>
        s"attempt:${event.attempt}"
      case TelemetryRecord(_, event: TelemetryEvent.Retry) =>
        s"retry:${event.failedAttempt}"
      case _ => "other"
    }

  private def exercise(
      outcomes: Chunk[Either[TransportError, StreamingResponse]],
      replayPolicy: WireReplayPolicy = WireReplayPolicy.FailClosed,
      retryDelay: Duration = Duration.Zero,
      turnBudget: TurnBudget = budget
  ): UIO[Observation] =
    (for
      telemetry <- InMemoryTelemetry.make
      http <- ScriptedHttp.make(outcomes)
      config <- ZIO.fromEither(
        HarmonyChatConfig.make(
          identity,
          credential = None,
          sseLimits = sseLimits,
          maxOutputTokens = 1024,
          maxAttempts = 2,
          retryDelay = retryDelay,
          replayPolicy = replayPolicy
        )
      )
      backend <- ZIO.fromEither(
        HarmonyChatBackend.make(config, http, telemetry)
      )
      result <- backend.complete(request, turnBudget).either
      requests <- http.requests
      records <- telemetry.records
    yield Observation(result, requests, records)).orDieWith(error =>
      new RuntimeException(error.safeMessage)
    )

  private final class ScriptedHttp private (
      outcomes: Chunk[Either[TransportError, StreamingResponse]],
      index: Ref[Int],
      captured: Ref[Chunk[StreamingRequest]]
  ) extends StreamingHttp:
    def requests: UIO[Chunk[StreamingRequest]] = captured.get

    def open(
        request: StreamingRequest
    ): ZIO[Scope, TransportError, StreamingResponse] =
      for
        current <- index.getAndUpdate(_ + 1)
        _ <- captured.update(_ :+ request)
        outcome <- ZIO
          .fromOption(outcomes.lift(current))
          .orElseFail(
            TransportError.OpenFailed
          )
        response <- ZIO.fromEither(outcome)
      yield response

  private object ScriptedHttp:
    def make(
        outcomes: Chunk[Either[TransportError, StreamingResponse]]
    ): UIO[ScriptedHttp] =
      for
        index <- Ref.make(0)
        captured <- Ref.make(Chunk.empty[StreamingRequest])
      yield new ScriptedHttp(outcomes, index, captured)

  private val request: ModelRequest[HarmonyChatContext] =
    val pins = unsafe(
      RunPins.make(identity, "high", "harmony-restart-spec-v1", Commit)
    )
    unsafe(
      ModelRequest.make(
        pins,
        unsafe(DeveloperInput.make("Follow BDR.")),
        Chunk(InputEvent.User(unsafe(UserInput.make("Inspect the PR.")))),
        Chunk.empty,
        unsafe(
          BdrStateView.make(
            unsafe(Revision.from(41L)),
            "executing",
            Json.Obj(Chunk("action" -> Json.Str("inspect"))),
            Digest
          )
        ),
        iteration = 1,
        continuation = None
      )
    )

  private val budget: TurnBudget =
    unsafe(TurnBudget.make(10.seconds, 1000L))

  private def nonRetryable(
      code: String
  )(error: BatError): Boolean = error match
    case failure: BatError.BackendFailure =>
      failure.code == code && !failure.retryable
    case _ => false

  private def okResponse: StreamingResponse =
    streamResponse(okStream)

  private val malformedResponse: StreamingResponse =
    streamResponse("data: {not-json}\n\ndata: [DONE]\n\n")

  private def failedBodyResponse(
      failure: TransportError
  ): StreamingResponse =
    val prefix =
      s"data: ${reasoningChunk(PartialReasoningCanary)}\n\n" +
        s"""data: {"id":"cmd-1","object":"chat.completion.chunk","created":2,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"call-never-exposed","index":0,"type":"function","function":{"name":"effect","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":70,"completion_tokens":20,"total_tokens":90}}\n\n"""
    streamResponse(
      ZStream.fromChunk(bytes(prefix)) ++ ZStream.fail(failure)
    )

  private def statusResponse(code: Int): StreamingResponse =
    unsafeTransport(
      StreamingResponse.make(
        unsafeTransport(ResponseStatus.from(code)),
        ResponseHeaders.empty,
        ZStream.empty
      )
    )

  private def streamResponse(payload: String): StreamingResponse =
    streamResponse(ZStream.fromChunk(bytes(payload)))

  private def streamResponse(
      body: ZStream[Any, TransportError, Byte]
  ): StreamingResponse =
    unsafeTransport(
      StreamingResponse.make(
        unsafeTransport(ResponseStatus.from(200)),
        unsafeTransport(
          ResponseHeaders.from(List("content-type" -> "text/event-stream"))
        ),
        body
      )
    )

  private def bytes(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  private def reasoningChunk(value: String): String =
    s"""{"id":"cmd-1","object":"chat.completion.chunk","created":1,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$value"}}]}"""

  private val okStream: String =
    s"data: ${reasoningChunk("checked")}\n\n" +
      s"""data: {"id":"cmd-1","object":"chat.completion.chunk","created":2,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","content":"Ready."},"finish_reason":"stop"}],"usage":{"prompt_tokens":70,"completion_tokens":20,"total_tokens":90}}\n\n""" +
      "data: [DONE]\n\n"

  private val PartialReasoningCanary = "DISCARDED_PARTIAL_REASONING_94af"

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(error => throw AssertionError(error.safeMessage), value => value)

  private def unsafeTransport[A](value: Either[TransportError, A]): A =
    value.fold(error => throw AssertionError(error.safeMessage), value => value)

  private def unsafeSse[A](value: Either[SseError, A]): A =
    value.fold(error => throw AssertionError(error.safeMessage), value => value)
