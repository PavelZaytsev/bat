package bat.transport

import zio.{Scope, URLayer, ZIO, ZLayer}
import zio.http.{Body, Client, Header, Headers, Method, Request, URL}
import zio.stream.ZStream

/** Provider-neutral, scoped streaming HTTP. Provider adapters own wire codecs,
  * event framing, status policy, and retry policy.
  */
trait StreamingHttp:
  def open(
      request: StreamingRequest
  ): ZIO[Scope, TransportError, StreamingResponse]

  /** Preferred entry point. Acquisition, metadata inspection, body consumption,
    * and connection finalization all occur in one lexical scope.
    */
  final def use[R, A](
      request: StreamingRequest
  )(
      consume: StreamingResponse => ZIO[R, TransportError, A]
  ): ZIO[R, TransportError, A] =
    ZIO.scoped(open(request).flatMap(consume))

object StreamingHttp:
  def open(
      request: StreamingRequest
  ): ZIO[StreamingHttp & Scope, TransportError, StreamingResponse] =
    ZIO.serviceWithZIO[StreamingHttp](_.open(request))

  def use[R, A](
      request: StreamingRequest
  )(
      consume: StreamingResponse => ZIO[R, TransportError, A]
  ): ZIO[StreamingHttp & R, TransportError, A] =
    ZIO.serviceWithZIO[StreamingHttp](_.use(request)(consume))

  /** Captures the ZIO HTTP client and validated transport configuration in the
    * interpreter. The returned response remains valid only inside the caller's
    * `Scope`.
    */
  val live: URLayer[Client & TransportConfig, StreamingHttp] =
    ZLayer.fromZIO {
      for
        client <- ZIO.service[Client]
        config <- ZIO.service[TransportConfig]
      yield make(client, config)
    }

  def configured(
      config: TransportConfig
  ): URLayer[Client, StreamingHttp] =
    ZLayer.fromFunction((client: Client) => make(client, config))

  private def make(
      client: Client,
      config: TransportConfig
  ): StreamingHttp =
    if config == null then InvalidConfiguration
    else Live(client, config)

  private object InvalidConfiguration extends StreamingHttp:
    override def open(
        request: StreamingRequest
    ): ZIO[Scope, TransportError, StreamingResponse] =
      ZIO.fail(TransportError.InvalidEndpoint)

  private final class Live(client: Client, config: TransportConfig)
      extends StreamingHttp:
    override def open(
        request: StreamingRequest
    ): ZIO[Scope, TransportError, StreamingResponse] =
      for
        _ <- ZIO.fail(TransportError.InvalidRequest).when(request == null)
        _ <- ZIO
          .fail(TransportError.RequestBodyTooLarge)
          .when(request.body.sizeBytes.toLong > config.maxRequestBytes)
        wireRequest <- makeRequest(request)
        response <- openResponse(wireRequest)
        result <- makeResponse(response)
      yield result

    private def makeRequest(
        request: StreamingRequest
    ): ZIO[Any, TransportError, Request] =
      collapseDefects(TransportError.InvalidRequest) {
        for
          uri <- ZIO.fromEither(config.resolve(request.target))
          url <- ZIO.fromEither(
            URL
              .decode(uri.toASCIIString)
              .left
              .map(_ => TransportError.InvalidTarget)
          )
        yield Request(
          method = toMethod(request.method),
          url = url,
          headers = Headers.fromIterable(
            request.headers.map(header =>
              Header.Custom(header.name, header.rawValue)
            )
          ),
          body = Body.fromChunk(request.body.bytes)
        )
      }

    private def openResponse(
        request: Request
    ): ZIO[Scope, TransportError, zio.http.Response] =
      collapseDefects(TransportError.OpenFailed) {
        Client
          .streaming(request)
          .provideSomeEnvironment[Scope](_.add(client))
          .mapError(_ => TransportError.OpenFailed)
      }.timeoutFail(TransportError.OpenTimedOut)(config.openTimeout)

    private def makeResponse(
        response: zio.http.Response
    ): ZIO[Any, TransportError, StreamingResponse] =
      collapseDefects(TransportError.InvalidResponse) {
        for
          status <- ZIO.fromEither(ResponseStatus.from(response.status.code))
          headers <- ZIO.fromEither(
            ResponseHeaders.from(
              response.headers.iterator
                .map(header => header.headerName -> header.renderedValue)
                .toList,
              config.maxResponseHeaderBytes
            )
          )
          result <- ZIO.fromEither(
            StreamingResponse.make(
              status,
              headers,
              safeBody(response.body.asStream)
            )
          )
        yield result
      }

    private def safeBody(
        stream: ZStream[Any, Throwable, Byte]
    ): ZStream[Any, TransportError, Byte] =
      stream
        .catchAllCause { cause =>
          if cause.isInterrupted then ZStream.fromZIO(ZIO.interrupt)
          else ZStream.fail(TransportError.BodyFailed)
        }
        .timeoutFail(TransportError.BodyTimedOut)(config.bodyIdleTimeout)

    private def collapseDefects[R, A](
        failure: TransportError
    )(effect: => ZIO[R, TransportError, A]): ZIO[R, TransportError, A] =
      ZIO.suspendSucceed(effect).catchAllCause { cause =>
        if cause.isInterrupted then ZIO.refailCause(cause)
        else ZIO.fail(failure)
      }

    private def toMethod(method: HttpMethod): Method = method match
      case HttpMethod.Get    => Method.GET
      case HttpMethod.Head   => Method.HEAD
      case HttpMethod.Post   => Method.POST
      case HttpMethod.Put    => Method.PUT
      case HttpMethod.Patch  => Method.PATCH
      case HttpMethod.Delete => Method.DELETE
