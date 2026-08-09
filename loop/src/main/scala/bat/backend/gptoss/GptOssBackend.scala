package bat.backend.gptoss

import bat.protocol.*
import bat.transport.*

import zio.*
import zio.json.ast.Json

/** GPT-OSS interpreter for one explicit OpenAI Responses wire dialect.
  *
  * The shared transport owns sockets and byte streaming. This backend owns
  * request encoding, SSE/Responses validation, opaque reasoning replay, and
  * normalization into BAT model turns. It never silently falls back to Chat
  * Completions or another endpoint.
  */
final class GptOssBackend private (
    config: GptOssConfig,
    http: StreamingHttp,
    val capabilities: BackendCapabilities
) extends Backend:
  type Context = GptOssContext

  val identity: BackendIdentity = config.identity

  protected def generate(
      request: ModelRequest[GptOssContext],
      budget: TurnBudget
  ): IO[BatError, ModelTurn[GptOssContext]] =
    prepare(request, budget)
      .flatMap(prepared => executeWithRetries(prepared, attempt = 1))
      .timeoutFail(BatError.BudgetExceeded(BudgetKind.WallTime))(
        budget.remainingWallTime
      )

  private final class Prepared(
      val encoded: EncodedResponsesRequest,
      val request: StreamingRequest
  ):
    override def toString: String =
      "GptOssBackend.Prepared(payload=<redacted>)"

  private final class FoldState(
      val framing: SseDecoder.State,
      val assembler: ResponsesAssembler
  ):
    override def toString: String =
      "GptOssBackend.FoldState(payload=<redacted>)"

  private def prepare(
      request: ModelRequest[GptOssContext],
      budget: TurnBudget
  ): IO[BatError, Prepared] =
    for
      encoded <- ZIO.fromEither(ResponsesProtocol.encode(request))
      outputLimit = Math.min(
        config.maxOutputTokens,
        budget.remainingTotalTokens
      )
      body = Json.Obj(
        encoded.body.fields :+ (
          "max_output_tokens" -> number(outputLimit)
        )
      )
      canonical <- ZIO.fromEither(
        StrictJson.canonical(body, "GPT-OSS Responses request")
      )
      requestBody <- transport(RequestBody.utf8(canonical))
      accept <- transport(
        RequestHeader.make("accept", "text/event-stream")
      )
      contentType <- transport(
        RequestHeader.make("content-type", "application/json")
      )
      authorization <- ZIO.foreach(config.credential)(secret =>
        transport(RequestHeader.bearer(secret))
      )
      headers = Chunk(accept, contentType) ++ Chunk.fromIterable(authorization)
      wireRequest <- transport(
        StreamingRequest.make(
          HttpMethod.Post,
          config.target,
          headers,
          requestBody
        )
      )
    yield new Prepared(encoded, wireRequest)

  private def executeWithRetries(
      prepared: Prepared,
      attempt: Int
  ): IO[BatError, ModelTurn[GptOssContext]] =
    executeOnce(prepared).catchSome {
      case failure: BatError.BackendFailure
          if failure.retryable && attempt < config.maxAttempts =>
        ZIO.sleep(retryDelay(attempt)) *>
          executeWithRetries(prepared, attempt + 1)
    }

  private def executeOnce(
      prepared: Prepared
  ): IO[BatError, ModelTurn[GptOssContext]] =
    ZIO.scoped {
      for
        response <- http
          .open(prepared.request)
          .mapError(mapTransportError)
        _ <- validateStatus(response.status)
        _ <- validateContentType(response.headers)
        framing <- ZIO.fromEither(
          SseDecoder.initial(config.sseLimits).left.map(mapSseError)
        )
        assembler <- ZIO.fromEither(
          ResponsesAssembler
            .start(identity, prepared.encoded, config.responsesLimits)
            .left
            .map(mapProviderProtocolError)
        )
        folded <- response.body
          .mapError(mapTransportError)
          .chunks
          .runFoldZIO(
            new FoldState(framing, assembler)
          ) { (current, bytes) =>
            for
              framed <- ZIO.fromEither(
                SseDecoder
                  .feed(current.framing, bytes)
                  .left
                  .map(mapSseError)
              )
              (nextFraming, events) = framed
              nextAssembler <- ZIO.fromEither(
                events
                  .foldLeft[Either[BatError, ResponsesAssembler]](
                    Right(current.assembler)
                  ) { (next, event) =>
                    next.flatMap(assembler =>
                      decodeSseEvent(event).flatMap(assembler.accept)
                    )
                  }
                  .left
                  .map(mapProviderProtocolError)
              )
            yield new FoldState(nextFraming, nextAssembler)
          }
        trailing <- ZIO.fromEither(
          SseDecoder
            .finish(folded.framing)
            .left
            .map(mapSseError)
        )
        completedAssembler <- ZIO.fromEither(
          trailing
            .foldLeft[Either[BatError, ResponsesAssembler]](
              Right(folded.assembler)
            ) { (next, event) =>
              next.flatMap(assembler =>
                decodeSseEvent(event).flatMap(assembler.accept)
              )
            }
            .left
            .map(mapProviderProtocolError)
        )
        turn <- ZIO.fromEither(
          completedAssembler.finish.left.map(mapProviderProtocolError)
        )
      yield turn
    }

  private def decodeSseEvent(
      event: SseEvent
  ): Either[BatError, ResponsesEvent] =
    val payload = event.data
    val trimmed = payload.trim
    if trimmed == "[DONE]" then
      for
        _ <- validateSseEventName(event, None)
        decoded <- ResponsesProtocol.decodeEvent(
          payload,
          config.responsesLimits
        )
      yield decoded
    else
      for
        json <- StrictJson
          .parseObject(trimmed, "Responses SSE data")
          .left
          .map(_ => providerProtocolFailure)
        wireType <- json.fields
          .collectFirst { case ("type", Json.Str(value)) => value }
          .filter(_.nonEmpty)
          .toRight(providerProtocolFailure)
        _ <- validateSseEventName(event, Some(wireType))
        decoded <- ResponsesProtocol
          .decodeEvent(payload, config.responsesLimits)
          .left
          .map(mapProviderProtocolError)
      yield decoded

  private def validateSseEventName(
      event: SseEvent,
      wireType: Option[String]
  ): Either[BatError, Unit] =
    event.eventType match
      case None | Some("message")                  => Right(())
      case Some(value) if wireType.contains(value) => Right(())
      case _ => Left(providerProtocolFailure)

  private def validateStatus(status: ResponseStatus): IO[BatError, Unit] =
    if status.code == 200 then ZIO.unit
    else
      // A received 429 explicitly says this inference was not admitted. A
      // connection failure or generic 5xx can occur after the model started
      // work, so replaying that POST would duplicate unreported spend.
      val retryable = status.code == 429
      val code =
        if status.code == 429 then "gpt_oss_rate_limited"
        else if status.code >= 500 then "gpt_oss_endpoint_unavailable"
        else "gpt_oss_http_status"
      ZIO.fail(
        BatError.BackendFailure(
          errorCode = code,
          safeMessage = "GPT-OSS Responses endpoint rejected the request",
          retryable = retryable
        )
      )

  private def validateContentType(
      headers: ResponseHeaders
  ): IO[BatError, Unit] =
    val values = headers.all("content-type")
    val valid =
      values.size == 1 && mediaType(values.head).contains("text/event-stream")
    ZIO
      .fail(
        BatError.BackendFailure(
          errorCode = "gpt_oss_content_type",
          safeMessage = "GPT-OSS Responses endpoint did not return SSE",
          retryable = false
        )
      )
      .unless(valid)
      .unit

  private def mediaType(value: String): Option[String] =
    Option(value)
      .map(_.takeWhile(_ != ';').trim.toLowerCase)
      .filter(_.nonEmpty)

  private def retryDelay(attempt: Int): Duration =
    Duration.fromNanos(
      config.retryDelay.toNanos * (1L << Math.max(0, attempt - 1))
    )

  private def mapTransportError(error: TransportError): BatError =
    BatError.BackendFailure(
      errorCode = s"gpt_oss_${error.code}",
      safeMessage = "GPT-OSS HTTP transport failed",
      retryable = false
    )

  private def mapSseError(error: SseError): BatError =
    BatError.BackendFailure(
      errorCode = s"gpt_oss_${error.code}",
      safeMessage = "GPT-OSS SSE stream was invalid",
      retryable = false
    )

  private def mapProviderProtocolError(error: BatError): BatError =
    error match
      case failure: BatError.BackendFailure => failure
      case _: BatError.BudgetExceeded       => error
      case _                                => providerProtocolFailure

  private val providerProtocolFailure: BatError.BackendFailure =
    BatError.BackendFailure(
      errorCode = "gpt_oss_protocol_violation",
      safeMessage = "GPT-OSS Responses endpoint violated the pinned dialect",
      retryable = false
    )

  private def transport[A](
      result: Either[TransportError, A]
  ): IO[BatError, A] =
    ZIO.fromEither(result.left.map(mapTransportError))

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

object GptOssBackend:
  private val RequiredCapabilities = Set(
    Capability.ReasoningContinuity,
    Capability.StrictTools,
    Capability.Streaming,
    Capability.UsageReporting
  )

  def make(
      config: GptOssConfig,
      http: StreamingHttp
  ): Either[BatError, GptOssBackend] =
    if config == null || http == null then
      Left(
        BatError.ProtocolViolation(
          "GPT-OSS backend dependencies must not be null"
        )
      )
    else
      BackendCapabilities
        .make(RequiredCapabilities)
        .map(new GptOssBackend(config, http, _))

  /** Functional construction for application wiring. The same controller can
    * receive a differently typed `Backend` layer for Claude or Kimi without
    * changing its loop or tool registry.
    */
  val live: ZLayer[GptOssConfig & StreamingHttp, BatError, GptOssBackend] =
    ZLayer.fromZIO {
      for
        config <- ZIO.service[GptOssConfig]
        http <- ZIO.service[StreamingHttp]
        backend <- ZIO.fromEither(make(config, http))
      yield backend
    }
