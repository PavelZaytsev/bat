package bat.backend.wire

import bat.protocol.*
import bat.telemetry.*
import bat.transport.*

import zio.*

/** Provider-neutral streaming interpreter.
  *
  * This value owns everything that is the same for every SSE-framed reasoning
  * provider: request construction, status and media-type validation,
  * incremental framing, bounded replay-safe retry, attempt timing, and
  * sanitized telemetry. Everything provider-specific lives behind
  * [[WireDialect]].
  *
  * Splitting those responsibilities is what lets a second provider — a GPT-OSS
  * Harmony Chat endpoint, Claude Messages, or a Codex-style Responses variant —
  * arrive as one dialect rather than as another transcription of this loop.
  */
final class StreamingWireBackend[D <: WireDialect] private (
    val dialect: D,
    http: StreamingHttp,
    telemetry: Telemetry
) extends Backend:
  type Context = dialect.Context

  val identity: BackendIdentity = dialect.identity

  val capabilities: BackendCapabilities = dialect.capabilities

  protected def generate(
      request: ModelRequest[Context],
      budget: TurnBudget
  ): IO[BatError, ModelTurn[Context]] =
    val attribution = BdrAttribution.from(request.iteration, request.bdrState)
    prepare(request, budget)
      .flatMap(prepared =>
        executeWithRetries(prepared, attribution, attempt = 1)
      )
      .timeoutFail(BatError.BudgetExceeded(BudgetKind.WallTime))(
        budget.remainingWallTime
      )

  private final class Prepared(
      val seed: dialect.Stream,
      val request: StreamingRequest
  ):
    override def toString: String =
      "StreamingWireBackend.Prepared(payload=<redacted>)"

  private final class FoldState(
      val framing: SseDecoder.State,
      val stream: dialect.Stream
  ):
    override def toString: String =
      "StreamingWireBackend.FoldState(payload=<redacted>)"

  private final case class AttemptProgress(
      startedNanos: Long,
      responseHeadersNanos: Option[Long],
      firstEventNanos: Option[Long]
  ):
    override def toString: String =
      "StreamingWireBackend.AttemptProgress(payload=<redacted>)"

  private def prepare(
      request: ModelRequest[Context],
      budget: TurnBudget
  ): IO[BatError, Prepared] =
    for
      outputLimit <- ZIO.succeed(
        Math.min(dialect.maxOutputTokens, budget.remainingTotalTokens)
      )
      begun <- ZIO.fromEither(dialect.beginTurn(request, outputLimit))
      (canonical, seed) = begun
      requestBody <- transport(RequestBody.utf8(canonical))
      accept <- transport(
        RequestHeader.make("accept", dialect.acceptMediaType)
      )
      contentType <- transport(
        RequestHeader.make("content-type", "application/json")
      )
      authorization <- ZIO.foreach(dialect.credential)(secret =>
        transport(RequestHeader.bearer(secret))
      )
      headers = Chunk(accept, contentType) ++ Chunk.fromIterable(authorization)
      wireRequest <- transport(
        StreamingRequest.make(
          HttpMethod.Post,
          dialect.target,
          headers,
          requestBody
        )
      )
    yield new Prepared(seed, wireRequest)

  private def executeWithRetries(
      prepared: Prepared,
      attribution: BdrAttribution,
      attempt: Int
  ): IO[BatError, ModelTurn[Context]] =
    executeAttempt(prepared, attribution, attempt).catchSome {
      case failure: BatError.BackendFailure
          if failure.retryable && attempt < dialect.retryPolicy.maxAttempts =>
        val delay = dialect.retryPolicy.delayAfter(attempt)
        telemetry.emit(
          TelemetryEvent.Retry(
            attribution,
            failedAttempt = attempt,
            nextAttempt = attempt + 1,
            delayMillis = delay.toMillis,
            reasonCode = TelemetryCode.capture(failure.code)
          )
        ) *>
          ZIO.sleep(delay) *>
          executeWithRetries(prepared, attribution, attempt + 1)
    }

  private def executeAttempt(
      prepared: Prepared,
      attribution: BdrAttribution,
      attempt: Int
  ): IO[BatError, ModelTurn[Context]] =
    for
      started <- Clock.nanoTime
      progress <- Ref.make(AttemptProgress(started, None, None))
      exit <- executeOnce(prepared, progress).exit
      finished <- Clock.nanoTime
      observed <- progress.get
      _ <- telemetry.emit(
        providerAttempt(attribution, attempt, observed, finished, exit)
      )
      result <- restoreExit(exit)
    yield result

  private def executeOnce(
      prepared: Prepared,
      progress: Ref[AttemptProgress]
  ): IO[BatError, ModelTurn[Context]] =
    ZIO.scoped {
      for
        response <- http
          .open(prepared.request)
          .mapError(mapTransportError)
        headersAt <- Clock.nanoTime
        _ <- progress.update(_.copy(responseHeadersNanos = Some(headersAt)))
        _ <- validateStatus(response)
        _ <- validateContentType(response.headers)
        framing <- ZIO.fromEither(
          SseDecoder.initial(dialect.sseLimits).left.map(mapSseError)
        )
        folded <- response.body
          .mapError(mapTransportError)
          .chunks
          .runFoldZIO(new FoldState(framing, prepared.seed)) {
            (current, bytes) =>
              for
                framed <- ZIO.fromEither(
                  SseDecoder
                    .feed(current.framing, bytes)
                    .left
                    .map(mapSseError)
                )
                (nextFraming, events) = framed
                nextStream <- acceptEvents(current.stream, events, progress)
              yield new FoldState(nextFraming, nextStream)
          }
        trailing <- ZIO.fromEither(
          SseDecoder.finish(folded.framing).left.map(mapSseError)
        )
        completed <- acceptEvents(folded.stream, trailing, progress)
        turn <- ZIO.fromEither(
          dialect.finish(completed).left.map(mapDialectError)
        )
      yield turn
    }

  private def acceptEvents(
      initial: dialect.Stream,
      events: Chunk[SseEvent],
      progress: Ref[AttemptProgress]
  ): IO[BatError, dialect.Stream] =
    ZIO.foldLeft(events)(initial) { (stream, event) =>
      for
        step <- ZIO.fromEither(
          dialect.accept(stream, event).left.map(mapDialectError)
        )
        _ <- markFirstSemanticEvent(progress).when(step.semantic)
      yield step.stream
    }

  private def markFirstSemanticEvent(
      progress: Ref[AttemptProgress]
  ): UIO[Unit] =
    Clock.nanoTime.flatMap(now =>
      progress.update(current =>
        if current.firstEventNanos.nonEmpty then current
        else current.copy(firstEventNanos = Some(now))
      )
    )

  private def providerAttempt(
      attribution: BdrAttribution,
      attempt: Int,
      progress: AttemptProgress,
      finishedNanos: Long,
      exit: Exit[BatError, ModelTurn[Context]]
  ): TelemetryEvent.ProviderAttempt =
    val (outcome, errorCode) = exit match
      case Exit.Success(_) =>
        ProviderAttemptOutcome.Completed -> Measurement.Unavailable(
          MissingReason.NotApplicable
        )
      case Exit.Failure(cause) =>
        val code = cause.failureOption
          .map(_.code)
          .getOrElse(
            if cause.isInterrupted then
              s"${dialect.errorPrefix}_attempt_interrupted"
            else s"${dialect.errorPrefix}_attempt_defect"
          )
        // Only 429 proves this request was not admitted. A generic status or
        // 5xx can arrive after inference began, so classifying it as rejected
        // would overstate replay safety.
        val rejected = code == s"${dialect.errorPrefix}_rate_limited"
        val classified =
          if rejected then ProviderAttemptOutcome.Rejected
          else ProviderAttemptOutcome.Failed
        classified -> Measurement.Observed(TelemetryCode.capture(code))

    TelemetryEvent.ProviderAttempt(
      attribution,
      attempt,
      outcome,
      ModelTimingMeasurements(
        totalMillis = Measurement.Observed(
          elapsedMillis(progress.startedNanos, finishedNanos)
        ),
        responseHeadersMillis =
          elapsedFromStart(progress, progress.responseHeadersNanos),
        firstEventMillis = elapsedFromStart(progress, progress.firstEventNanos),
        streamMillis = progress.firstEventNanos
          .fold[Measurement[Long]](
            Measurement.Unavailable(MissingReason.FailedBeforeMeasurement)
          )(started =>
            Measurement.Observed(elapsedMillis(started, finishedNanos))
          )
      ),
      errorCode
    )

  private def elapsedFromStart(
      progress: AttemptProgress,
      observedNanos: Option[Long]
  ): Measurement[Long] =
    observedNanos.fold[Measurement[Long]](
      Measurement.Unavailable(MissingReason.FailedBeforeMeasurement)
    )(observed =>
      Measurement.Observed(elapsedMillis(progress.startedNanos, observed))
    )

  private def elapsedMillis(startedNanos: Long, finishedNanos: Long): Long =
    Duration
      .fromNanos(Math.max(0L, finishedNanos - startedNanos))
      .toMillis

  private def restoreExit[E, A](exit: Exit[E, A]): IO[E, A] =
    exit match
      case Exit.Success(value) => ZIO.succeed(value)
      case Exit.Failure(cause) => ZIO.refailCause(cause)

  /** A rejected request exposes only controller-owned metadata.
    *
    * Provider error bodies are untrusted output and can echo prompts, source,
    * credentials, or arbitrary printable text. They therefore never enter a
    * value named `safeMessage`; deployment logs remain the place to distinguish
    * two operator mistakes that share one HTTP status.
    */
  private def validateStatus(
      response: StreamingResponse
  ): IO[BatError, Unit] =
    if response.status.code == 200 then ZIO.unit
    else
      val classified = dialect.statusFailure(response.status.code)
      ZIO.fail(
        BatError.BackendFailure(
          errorCode = classified.code,
          safeMessage =
            s"${dialect.dialectLabel} endpoint rejected the request (status=${response.status.code})",
          retryable = classified.retryable
        )
      )

  private def validateContentType(
      headers: ResponseHeaders
  ): IO[BatError, Unit] =
    val values = headers.all("content-type")
    val valid =
      values.size == 1 &&
        mediaType(values.head).contains(dialect.expectedMediaType)
    ZIO
      .fail(
        BatError.BackendFailure(
          errorCode = s"${dialect.errorPrefix}_content_type",
          safeMessage =
            s"${dialect.dialectLabel} endpoint did not return the pinned media type",
          retryable = false
        )
      )
      .unless(valid)
      .unit

  private def mediaType(value: String): Option[String] =
    Option(value)
      .map(_.takeWhile(_ != ';').trim.toLowerCase)
      .filter(_.nonEmpty)

  private def mapTransportError(error: TransportError): BatError =
    BatError.BackendFailure(
      errorCode = s"${dialect.errorPrefix}_${error.code}",
      safeMessage = s"${dialect.dialectLabel} HTTP transport failed",
      retryable = false
    )

  private def mapSseError(error: SseError): BatError =
    BatError.BackendFailure(
      errorCode = s"${dialect.errorPrefix}_${error.code}",
      safeMessage = s"${dialect.dialectLabel} SSE stream was invalid",
      retryable = false
    )

  /** A dialect may return a precise backend failure or budget error. Anything
    * else is collapsed to the dialect's stable protocol violation so provider
    * text can never reach evidence.
    */
  private def mapDialectError(error: BatError): BatError =
    error match
      case failure: BatError.BackendFailure => failure
      case _: BatError.BudgetExceeded       => error
      case _                                => dialect.protocolFailure

  private def transport[A](
      result: Either[TransportError, A]
  ): IO[BatError, A] =
    ZIO.fromEither(result.left.map(mapTransportError))

object StreamingWireBackend:
  def make[D <: WireDialect](
      dialect: D,
      http: StreamingHttp,
      telemetry: Telemetry
  ): Either[BatError, StreamingWireBackend[D]] =
    if dialect == null || http == null || telemetry == null then
      Left(
        BatError.ProtocolViolation(
          "streaming backend dependencies must not be null"
        )
      )
    else Right(new StreamingWireBackend(dialect, http, telemetry))
