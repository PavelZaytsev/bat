package bat.backend.harmonychat

import bat.backend.wire.*
import bat.protocol.*
import bat.telemetry.*
import bat.transport.*

import zio.{Duration, ZLayer}
import zio.json.ast.Json

/** Operator-pinned configuration for the GPT-OSS Harmony Chat cartridge.
  *
  * Endpoint location and socket timeouts belong to the shared HTTP transport.
  * This value owns only Harmony Chat dialect policy, replay bounds, and retry
  * policy. A credential can be attached to the request, but its representation
  * is always redacted.
  */
final class HarmonyChatConfig private (
    val identity: BackendIdentity,
    val credential: Option[Secret],
    val maxOutputTokens: Long,
    val maxAttempts: Int,
    val retryDelay: Duration,
    val replayPolicy: WireReplayPolicy,
    val sseLimits: SseLimits,
    val chatLimits: HarmonyChatLimits,
    private[harmonychat] val target: RequestTarget
):
  override def toString: String =
    s"HarmonyChatConfig(identity=$identity, credential=<redacted>, maxOutputTokens=$maxOutputTokens, maxAttempts=$maxAttempts, retryDelay=$retryDelay, replayPolicy=$replayPolicy, sseLimits=$sseLimits, chatLimits=$chatLimits, dialect=harmony-chat)"

object HarmonyChatConfig:
  val BackendName = "gpt-oss-harmony-chat"
  val ChatCompletionsPath = "/v1/chat/completions"

  val DefaultMaxOutputTokens: Long = 32L * 1024
  val DefaultMaxAttempts: Int = 3
  val DefaultRetryDelay: Duration = Duration.fromMillis(250)

  private val MaxOutputTokens: Long = 1024L * 1024

  def identity(
      modelId: String,
      modelRevision: String
  ): Either[BatError, BackendIdentity] =
    BackendIdentity.make(BackendName, modelId, modelRevision)

  def make(
      identity: BackendIdentity,
      credential: Option[Secret],
      sseLimits: SseLimits,
      chatLimits: HarmonyChatLimits = HarmonyChatLimits.default,
      maxOutputTokens: Long = DefaultMaxOutputTokens,
      maxAttempts: Int = DefaultMaxAttempts,
      retryDelay: Duration = DefaultRetryDelay,
      replayPolicy: WireReplayPolicy = WireReplayPolicy.FailClosed
  ): Either[BatError, HarmonyChatConfig] =
    for
      _ <- Either.cond(
        identity != null && identity.backend == BackendName,
        (),
        violation(s"Harmony Chat identity backend must be '$BackendName'")
      )
      _ <- Either.cond(
        credential != null && !credential.contains(null),
        (),
        violation("Harmony Chat credential option must not be null")
      )
      _ <- Either.cond(
        sseLimits != null && chatLimits != null && replayPolicy != null,
        (),
        violation(
          "Harmony Chat stream limits and replay policy must not be null"
        )
      )
      _ <- Either.cond(
        maxOutputTokens > 0 && maxOutputTokens <= MaxOutputTokens,
        (),
        violation(
          s"Harmony Chat max_tokens must be between 1 and $MaxOutputTokens"
        )
      )
      // Validated here as well as in the retry policy so an invalid value is
      // rejected while constructing configuration, not on the first turn.
      _ <- WireRetryPolicy.make(maxAttempts, retryDelay).map(_ => ())
      target <- RequestTarget
        .from(ChatCompletionsPath)
        .left
        .map(mapTransportConfigurationError)
    yield new HarmonyChatConfig(
      identity,
      credential,
      maxOutputTokens,
      maxAttempts,
      retryDelay,
      replayPolicy,
      sseLimits,
      chatLimits,
      target
    )

  private def mapTransportConfigurationError(
      error: TransportError
  ): BatError =
    BatError.ProtocolViolation(
      s"Harmony Chat target is invalid (${error.code})"
    )

  private def violation(message: String): BatError =
    BatError.ProtocolViolation(message)

/** GPT-OSS interpreter for a native Harmony Chat Completions endpoint.
  *
  * This dialect is used where an endpoint serves Harmony over
  * `/v1/chat/completions` with a first-class `reasoning_content` field. It is a
  * peer of the Responses cartridge, not a fallback from it: the shared backend
  * is told which dialect to speak and never switches on its own.
  */
final class HarmonyChatDialect private (
    config: HarmonyChatConfig,
    val retryPolicy: WireRetryPolicy,
    val capabilities: BackendCapabilities
) extends WireDialect:
  type Context = HarmonyChatContext
  type Stream = HarmonyChatAssembler

  val identity: BackendIdentity = config.identity
  val errorPrefix: String = HarmonyChatDialect.ErrorPrefix
  val dialectLabel: String = "GPT-OSS Harmony Chat"
  val target: RequestTarget = config.target
  val credential: Option[Secret] = config.credential
  val sseLimits: SseLimits = config.sseLimits
  val maxOutputTokens: Long = config.maxOutputTokens
  override val replayPolicy: WireReplayPolicy = config.replayPolicy

  def beginTurn(
      request: ModelRequest[HarmonyChatContext],
      outputTokenLimit: Long
  ): Either[BatError, (String, HarmonyChatAssembler)] =
    for
      encoded <- HarmonyChatProtocol.encode(request)
      body = Json.Obj(
        encoded.body.fields :+ ("max_tokens" -> number(outputTokenLimit))
      )
      canonical <- StrictJson.canonical(body, "Harmony Chat request")
      assembler <- HarmonyChatAssembler.start(
        identity,
        encoded,
        config.chatLimits
      )
    yield (canonical, assembler)

  def accept(
      stream: HarmonyChatAssembler,
      event: SseEvent
  ): Either[BatError, WireStep[HarmonyChatAssembler]] =
    for
      decoded <- decodeSseEvent(event)
      next <- stream.accept(decoded).left.map(mapDialectError)
    yield WireStep(next, decoded != HarmonyChatEvent.StreamEnd)

  def finish(
      stream: HarmonyChatAssembler
  ): Either[BatError, ModelTurn[HarmonyChatContext]] =
    stream.finish.left.map(mapDialectError)

  /** `405` proves that this endpoint does not serve Chat Completions. `404` is
    * also fail-closed by default, but exo uses it for a temporarily absent
    * instance after restart; only the already-qualified self-hosted policy may
    * retry that status, and it never changes the pinned path.
    */
  override def statusFailure(code: Int): WireStatus =
    if code == 429 then WireStatus("harmony_chat_rate_limited", true)
    else if code == 408 then
      WireStatus(
        "harmony_chat_request_timeout",
        replayPolicy.retriesStatus(code)
      )
    else if code == 401 || code == 403 then
      WireStatus("harmony_chat_unauthorized", false)
    else if code == 404 then
      WireStatus(
        "harmony_chat_completions_unavailable",
        replayPolicy.retriesStatus(code)
      )
    else if code == 405 then
      WireStatus("harmony_chat_completions_unavailable", false)
    else if code >= 500 then
      WireStatus(
        "harmony_chat_endpoint_unavailable",
        replayPolicy.retriesStatus(code)
      )
    else WireStatus("harmony_chat_http_status", false)

  override def toString: String =
    s"HarmonyChatDialect(identity=$identity, dialect=harmony-chat)"

  private def decodeSseEvent(
      event: SseEvent
  ): Either[BatError, HarmonyChatEvent] =
    // Chat Completions carries no per-event name, so a named event would mean
    // this is some other dialect wearing the same media type.
    event.eventType match
      case None | Some("message") =>
        HarmonyChatProtocol
          .decodeEvent(event.data, config.chatLimits)
          .left
          .map(mapDialectError)
      case _ => Left(protocolFailure)

  private def mapDialectError(error: BatError): BatError =
    error match
      case failure: BatError.BackendFailure
          if failure.code == "harmony_chat_chat_error" &&
            config.replayPolicy.retriesInBandProviderError =>
        BatError.BackendFailure(
          errorCode = failure.code,
          safeMessage = "Harmony Chat response failed",
          retryable = true
        )
      case failure: BatError.BackendFailure => failure
      case _: BatError.BudgetExceeded       => error
      case _                                => protocolFailure

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

object HarmonyChatDialect:
  val ErrorPrefix = "harmony_chat"

  private val RequiredCapabilities = Set(
    Capability.ReasoningContinuity,
    Capability.StrictTools,
    Capability.Streaming,
    Capability.UsageReporting
  )

  def make(config: HarmonyChatConfig): Either[BatError, HarmonyChatDialect] =
    if config == null then
      Left(
        BatError.ProtocolViolation("Harmony Chat config must not be null")
      )
    else
      for
        retry <- WireRetryPolicy.make(config.maxAttempts, config.retryDelay)
        capabilities <- BackendCapabilities.make(RequiredCapabilities)
      yield new HarmonyChatDialect(config, retry, capabilities)

/** The Harmony Chat cartridge is the shared streaming backend interpreting one
  * pinned dialect, exactly like the Responses cartridge.
  */
type HarmonyChatBackend = StreamingWireBackend[HarmonyChatDialect]

object HarmonyChatBackend:
  def make(
      config: HarmonyChatConfig,
      http: StreamingHttp
  ): Either[BatError, HarmonyChatBackend] =
    make(config, http, Telemetry.noop)

  def make(
      config: HarmonyChatConfig,
      http: StreamingHttp,
      telemetry: Telemetry
  ): Either[BatError, HarmonyChatBackend] =
    for
      dialect <- HarmonyChatDialect.make(config)
      backend <- StreamingWireBackend.make(dialect, http, telemetry)
    yield backend

  val live: ZLayer[
    HarmonyChatConfig & StreamingHttp,
    BatError,
    HarmonyChatBackend
  ] =
    ZLayer.fromZIO {
      for
        config <- zio.ZIO.service[HarmonyChatConfig]
        http <- zio.ZIO.service[StreamingHttp]
        backend <- zio.ZIO.fromEither(make(config, http))
      yield backend
    }

  val observed: ZLayer[
    HarmonyChatConfig & StreamingHttp & Telemetry,
    BatError,
    HarmonyChatBackend
  ] =
    ZLayer.fromZIO {
      for
        config <- zio.ZIO.service[HarmonyChatConfig]
        http <- zio.ZIO.service[StreamingHttp]
        telemetry <- zio.ZIO.service[Telemetry]
        backend <- zio.ZIO.fromEither(make(config, http, telemetry))
      yield backend
    }
