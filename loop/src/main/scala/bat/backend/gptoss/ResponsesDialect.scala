package bat.backend.gptoss

import bat.backend.wire.*
import bat.protocol.*
import bat.transport.*

import zio.json.ast.Json

/** GPT-OSS interpreter for one explicit OpenAI Responses wire dialect.
  *
  * The shared streaming backend owns sockets, framing, retry, and telemetry.
  * This dialect owns request encoding, SSE/Responses validation, opaque
  * reasoning replay, and normalization into BAT model turns. It never silently
  * falls back to Chat Completions or another endpoint.
  */
final class ResponsesDialect private (
    config: GptOssConfig,
    val retryPolicy: WireRetryPolicy,
    val capabilities: BackendCapabilities
) extends WireDialect:
  type Context = GptOssContext
  type Stream = ResponsesAssembler

  val identity: BackendIdentity = config.identity
  val errorPrefix: String = ResponsesDialect.ErrorPrefix
  val dialectLabel: String = "GPT-OSS Responses"
  val target: RequestTarget = config.target
  val credential: Option[Secret] = config.credential
  val sseLimits: SseLimits = config.sseLimits
  val maxOutputTokens: Long = config.maxOutputTokens

  def beginTurn(
      request: ModelRequest[GptOssContext],
      outputTokenLimit: Long
  ): Either[BatError, (String, ResponsesAssembler)] =
    for
      encoded <- ResponsesProtocol.encode(request)
      body = Json.Obj(
        encoded.body.fields :+ (
          "max_output_tokens" -> number(outputTokenLimit)
        )
      )
      canonical <- StrictJson.canonical(body, "GPT-OSS Responses request")
      assembler <- ResponsesAssembler.start(
        identity,
        encoded,
        config.responsesLimits
      )
    yield (canonical, assembler)

  def accept(
      stream: ResponsesAssembler,
      event: SseEvent
  ): Either[BatError, WireStep[ResponsesAssembler]] =
    for
      decoded <- decodeSseEvent(event)
      next <- stream.accept(decoded).left.map(mapDialectError)
    yield WireStep(next, decoded != ResponsesEvent.StreamEnd)

  def finish(
      stream: ResponsesAssembler
  ): Either[BatError, ModelTurn[GptOssContext]] =
    stream.finish.left.map(mapDialectError)

  /** Pinned reason codes. A 404/405 on this path means the endpoint does not
    * serve the Responses dialect at all, which is a different operator fact
    * from a transient outage and is recorded as such.
    */
  override def statusFailure(code: Int): WireStatus =
    if code == 429 then WireStatus("gpt_oss_rate_limited", true)
    else if code == 408 then WireStatus("gpt_oss_request_timeout", false)
    else if code == 401 || code == 403 then
      WireStatus("gpt_oss_unauthorized", false)
    else if code == 404 || code == 405 then
      WireStatus("gpt_oss_responses_unavailable", false)
    else if code >= 500 then WireStatus("gpt_oss_endpoint_unavailable", false)
    else WireStatus("gpt_oss_http_status", false)

  override def toString: String =
    s"ResponsesDialect(identity=$identity, dialect=responses)"

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
          .map(_ => protocolFailure)
        wireType <- json.fields
          .collectFirst { case ("type", Json.Str(value)) => value }
          .filter(_.nonEmpty)
          .toRight(protocolFailure)
        _ <- validateSseEventName(event, Some(wireType))
        decoded <- ResponsesProtocol
          .decodeEvent(payload, config.responsesLimits)
          .left
          .map(mapDialectError)
      yield decoded

  private def validateSseEventName(
      event: SseEvent,
      wireType: Option[String]
  ): Either[BatError, Unit] =
    event.eventType match
      case None | Some("message")                  => Right(())
      case Some(value) if wireType.contains(value) => Right(())
      case _                                       => Left(protocolFailure)

  private def mapDialectError(error: BatError): BatError =
    error match
      case failure: BatError.BackendFailure => failure
      case _: BatError.BudgetExceeded       => error
      case _                                => protocolFailure

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

object ResponsesDialect:
  val ErrorPrefix = "gpt_oss"

  private val RequiredCapabilities = Set(
    Capability.ReasoningContinuity,
    Capability.StrictTools,
    Capability.Streaming,
    Capability.UsageReporting
  )

  def make(config: GptOssConfig): Either[BatError, ResponsesDialect] =
    if config == null then
      Left(
        BatError.ProtocolViolation("GPT-OSS Responses config must not be null")
      )
    else
      for
        retry <- WireRetryPolicy.make(config.maxAttempts, config.retryDelay)
        capabilities <- BackendCapabilities.make(RequiredCapabilities)
      yield new ResponsesDialect(config, retry, capabilities)
