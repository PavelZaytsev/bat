package bat.transport

import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.test.*

object StreamingHttpSpec extends ZIOSpecDefault:
  private val token = "provider-token-that-must-not-leak"
  private val payload = "{\"input\":\"private prompt\"}"

  private final case class CapturedRequest(
      method: String,
      url: String,
      authorization: Option[String],
      contentType: Option[String],
      body: String
  )

  private final class StubDriver(
      handler: (Method, URL, Headers, Body) => ZIO[Scope, Throwable, Response]
  ) extends ZClient.Driver[Any, Scope, Throwable]:
    override def request(
        version: Version,
        method: Method,
        url: URL,
        headers: Headers,
        body: Body,
        sslConfig: Option[ClientSSLConfig],
        proxy: Option[Proxy]
    )(implicit trace: Trace): ZIO[Scope, Throwable, Response] =
      handler(method, url, headers, body)

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

  private def config(
      openTimeout: Duration = 5.seconds,
      bodyIdleTimeout: Duration = 30.seconds,
      maxRequestBytes: Long = TransportConfig.DefaultMaxRequestBytes,
      maxResponseHeaderBytes: Long =
        TransportConfig.DefaultMaxResponseHeaderBytes
  ): TransportConfig =
    TransportConfig
      .make(
        "https://models.internal.example/v1",
        openTimeout,
        bodyIdleTimeout,
        maxRequestBytes,
        maxResponseHeaderBytes
      )
      .toOption
      .get

  private val request: StreamingRequest =
    (for
      target <- RequestTarget.from("/responses")
      secret <- Secret.from(token)
      authorization <- RequestHeader.bearer(secret)
      accept <- RequestHeader.make("accept", "text/event-stream")
      contentType <- RequestHeader.make("content-type", "application/json")
      body <- RequestBody.utf8(payload)
      result <- StreamingRequest.make(
        HttpMethod.Post,
        target,
        Chunk(authorization, accept, contentType),
        body
      )
    yield result).toOption.get

  private def layer(
      driver: ZClient.Driver[Any, Scope, Throwable],
      transportConfig: TransportConfig
  ): ULayer[StreamingHttp] =
    val client: Client = ZClient.fromDriver(driver)
    ZLayer.succeed(client) >>> StreamingHttp.configured(transportConfig)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("live scoped streaming HTTP interpreter")(
      test("opens metadata before consuming a scoped response body") {
        for
          captured <- Ref.make(Option.empty[CapturedRequest])
          released <- Ref.make(false)
          responseBody = Chunk.fromArray("data: ok\n\n".getBytes)
          driver = new StubDriver((method, url, headers, body) =>
            ZIO.acquireRelease(
              for
                bodyText <- body.asString
                _ <- captured.set(
                  Some(
                    CapturedRequest(
                      method.render,
                      url.encode,
                      headers.get("authorization"),
                      headers.get("content-type"),
                      bodyText
                    )
                  )
                )
              yield Response(
                status = Status.Accepted,
                headers = Headers(
                  "content-type" -> "text/event-stream",
                  "x-request-id" -> "request-123"
                ),
                body = Body.fromChunk(responseBody)
              )
            )(_ => released.set(true))
          )
          result <- StreamingHttp
            .use(request)(response =>
              response.body.runCollect.map(body =>
                (
                  response.status.code,
                  response.headers.first("Content-Type"),
                  response.headers.first("x-request-id"),
                  body
                )
              )
            )
            .provideLayer(layer(driver, config()))
          observed <- captured.get
          wasReleased <- released.get
        yield assertTrue(
          result == (
            202,
            Some("text/event-stream"),
            Some("request-123"),
            responseBody
          ),
          observed.exists(value =>
            value.method == "POST" &&
              value.url == "https://models.internal.example/v1/responses" &&
              value.authorization.contains(s"Bearer $token") &&
              value.contentType.contains("application/json") &&
              value.body == payload
          ),
          wasReleased
        )
      },
      test("collapses open failures and defects without authorizing a retry") {
        val throwableDriver = new StubDriver((_, _, _, _) =>
          ZIO.fail(new RuntimeException(s"failed with $token and $payload"))
        )
        val defectDriver = new StubDriver((_, _, _, _) =>
          ZIO.die(new RuntimeException(s"defect with $token and $payload"))
        )
        for
          failure <- ZIO
            .scoped(StreamingHttp.open(request))
            .provideLayer(layer(throwableDriver, config()))
            .either
          defect <- ZIO
            .scoped(StreamingHttp.open(request))
            .provideLayer(layer(defectDriver, config()))
            .either
        yield assertTrue(
          failure == Left(TransportError.OpenFailed),
          defect == Left(TransportError.OpenFailed),
          !failure.toString.contains(token),
          !failure.toString.contains(payload),
          !TransportError.OpenFailed.retryable
        )
      },
      test("does not classify a body failure as safe to retry") {
        val driver = new StubDriver((_, _, _, _) =>
          ZIO.succeed(
            Response(
              headers = Headers("content-type", "text/event-stream"),
              body = Body.fromStreamChunked(
                ZStream.fail(
                  new RuntimeException(
                    s"partial response had $token and $payload"
                  )
                )
              )
            )
          )
        )
        for result <- ZIO
            .scoped {
              StreamingHttp
                .open(request)
                .flatMap(_.body.runDrain)
            }
            .provideLayer(layer(driver, config()))
            .either
        yield assertTrue(
          result == Left(TransportError.BodyFailed),
          !result.toString.contains(token),
          !result.toString.contains(payload),
          !TransportError.BodyFailed.retryable
        )
      },
      test("distinguishes an open timeout from other open failures") {
        val driver = new StubDriver((_, _, _, _) => ZIO.never)
        val operation = ZIO
          .scoped(StreamingHttp.open(request))
          .provideLayer(layer(driver, config(openTimeout = 1.second)))
        for
          fiber <- operation.fork
          _ <- TestClock.adjust(2.seconds)
          result <- fiber.join.either
        yield assertTrue(result == Left(TransportError.OpenTimedOut))
      },
      test("distinguishes a stalled response body") {
        val driver = new StubDriver((_, _, _, _) =>
          ZIO.succeed(
            Response(
              headers = Headers("content-type", "text/event-stream"),
              body = Body.fromStreamChunked(ZStream.never)
            )
          )
        )
        val operation = ZIO
          .scoped(
            StreamingHttp.open(request).flatMap(_.body.runDrain)
          )
          .provideLayer(layer(driver, config(bodyIdleTimeout = 1.second)))
        for
          fiber <- operation.fork
          _ <- TestClock.adjust(2.seconds)
          result <- fiber.join.either
        yield assertTrue(result == Left(TransportError.BodyTimedOut))
      },
      test("closes the response scope when body consumption is interrupted") {
        for
          acquired <- Promise.make[Nothing, Unit]
          released <- Promise.make[Nothing, Unit]
          driver = new StubDriver((_, _, _, _) =>
            ZIO.acquireRelease(
              acquired
                .succeed(())
                .as(
                  Response(
                    body = Body.fromStreamChunked(ZStream.never)
                  )
                )
            )(_ => released.succeed(()).unit)
          )
          operation = ZIO
            .scoped(
              StreamingHttp.open(request).flatMap(_.body.runDrain)
            )
            .provideLayer(layer(driver, config()))
          fiber <- operation.fork
          _ <- acquired.await
          _ <- fiber.interrupt
          wasReleased <- released.isDone
        yield assertTrue(wasReleased)
      },
      test("rejects an oversized body before opening a connection") {
        for
          invoked <- Ref.make(false)
          driver = new StubDriver((_, _, _, _) =>
            invoked.set(true) *> ZIO.succeed(Response.ok)
          )
          result <- ZIO
            .scoped(StreamingHttp.open(request))
            .provideLayer(
              layer(driver, config(maxRequestBytes = payload.length - 1L))
            )
            .either
          wasInvoked <- invoked.get
        yield assertTrue(
          result == Left(TransportError.RequestBodyTooLarge),
          !wasInvoked
        )
      },
      test("turns a null configured value into a typed failure") {
        val driver = new StubDriver((_, _, _, _) => ZIO.succeed(Response.ok))
        for result <- StreamingHttp
            .use(request)(_.body.runDrain)
            .provideLayer(layer(driver, null))
            .either
        yield assertTrue(result == Left(TransportError.InvalidEndpoint))
      }
    ) @@ TestAspect.timeout(15.seconds)
