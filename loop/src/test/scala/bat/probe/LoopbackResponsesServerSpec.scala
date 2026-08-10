package bat.probe

import bat.transport.*

import zio.*
import zio.http.Client
import zio.test.*

object LoopbackResponsesServerSpec extends ZIOSpecDefault:
  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("loopback Responses test server")(
      test("streams fragmented SSE through the real HTTP interpreter") {
        val payload =
          "event: response.created\r\ndata: {\"type\":\"response.created\"}\r\n\r\ndata: [DONE]\r\n\r\n"
        LoopbackResponsesServer.use(
          Chunk(
            LoopbackResponsesServer.ScriptedResponse.sse(payload)
          )
        ) { server =>
          for
            config <- ZIO.fromEither(
              TransportConfig.make(
                server.baseUrl,
                openTimeout = 5.seconds,
                bodyIdleTimeout = 5.seconds
              )
            )
            target <- ZIO.fromEither(RequestTarget.from("/v1/responses"))
            accept <- ZIO.fromEither(
              RequestHeader.make("accept", "text/event-stream")
            )
            contentType <- ZIO.fromEither(
              RequestHeader.make("content-type", "application/json")
            )
            body <- ZIO.fromEither(RequestBody.utf8("{\"stream\":true}"))
            request <- ZIO.fromEither(
              StreamingRequest.make(
                HttpMethod.Post,
                target,
                Chunk(accept, contentType),
                body
              )
            )
            responseBody <- StreamingHttp
              .use(request)(_.body.runCollect)
              .provideLayer(
                Client.default >>> StreamingHttp.configured(config)
              )
            requests <- server.requests
          yield assertTrue(
            String(
              responseBody.toArray,
              java.nio.charset.StandardCharsets.UTF_8
            ) == payload,
            requests.size == 1,
            requests.head.method == "POST",
            requests.head.path == "/v1/responses",
            requests.head.accept.contains("text/event-stream"),
            requests.head.contentType.contains("application/json"),
            requests.head.body == "{\"stream\":true}",
            !requests.head.toString.contains("stream")
          )
        }
      }
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds)
