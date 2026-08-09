package bat.transport

import zio.*
import zio.test.*

object TransportModelSpec extends ZIOSpecDefault:
  private val token = "ultra-secret-token"
  private val prompt = "{\"prompt\":\"private-repository-code\"}"

  private def validConfig =
    TransportConfig.make(
      "https://models.internal.example/v1",
      5.seconds,
      30.seconds
    )

  private def validRequest =
    for
      target <- RequestTarget.from("/responses")
      secret <- Secret.from(token)
      authorization <- RequestHeader.bearer(secret)
      contentType <- RequestHeader.make("content-type", "application/json")
      body <- RequestBody.utf8(prompt)
      request <- StreamingRequest.make(
        HttpMethod.Post,
        target,
        Chunk(authorization, contentType),
        body
      )
    yield (secret, authorization, body, request)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("provider-neutral streaming HTTP model")(
      test("accepts a bounded absolute HTTP endpoint") {
        assertTrue(validConfig.isRight)
      },
      test("rejects endpoint credentials, queries, fragments, and traversal") {
        assertTrue(
          TransportConfig
            .make("https://user:pass@example.com/v1", 1.second, 1.second)
            .isLeft,
          TransportConfig
            .make("https://example.com/v1?key=secret", 1.second, 1.second)
            .isLeft,
          TransportConfig
            .make("https://example.com/v1#fragment", 1.second, 1.second)
            .isLeft,
          TransportConfig
            .make("https://example.com/a/../v1", 1.second, 1.second)
            .isLeft,
          TransportConfig
            .make("https://example.com:0/v1", 1.second, 1.second)
            .isLeft,
          TransportConfig
            .make("https://example.com:99999/v1", 1.second, 1.second)
            .isLeft
        )
      },
      test("rejects invalid operational bounds") {
        assertTrue(
          TransportConfig
            .make("http://localhost:8080", Duration.Zero, 1.second)
            .isLeft,
          TransportConfig
            .make("http://localhost:8080", 1.second, Duration.Infinity)
            .isLeft,
          TransportConfig
            .make("http://localhost:8080", 1.second, 1.second, 0)
            .isLeft,
          TransportConfig
            .make(
              "http://localhost:8080",
              1.second,
              1.second,
              maxResponseHeaderBytes = 0
            )
            .isLeft
        )
      },
      test("keeps request targets relative to the configured endpoint") {
        val config = validConfig.toOption.get
        val result = for
          target <- RequestTarget.from("/responses")
          resolved <- config.resolve(target)
        yield resolved.toASCIIString
        assertTrue(
          result == Right("https://models.internal.example/v1/responses"),
          RequestTarget.from("//attacker.example/responses").isLeft,
          RequestTarget.from("/../admin").isLeft,
          RequestTarget.from("/responses?key=secret").isLeft
        )
      },
      test("builds bearer authorization without exposing secret text") {
        val result = for
          secret <- Secret.from(token)
          header <- RequestHeader.bearer(secret)
        yield (secret, header)
        assertTrue(
          result.exists { case (secret, header) =>
            header.name == "authorization" &&
            header.rawValue == s"Bearer $token" &&
            !secret.toString.contains(token) &&
            !header.toString.contains(token)
          }
        )
      },
      test("rejects control characters and transport-owned headers") {
        assertTrue(
          Secret.from("token\twith-tab").isLeft,
          RequestHeader.make("authorization", "value\r\ninjected: yes").isLeft,
          RequestHeader.make("host", "attacker.example").isLeft,
          RequestHeader.make("content-length", "1").isLeft,
          RequestHeader.make("transfer-encoding", "chunked").isLeft
        )
      },
      test("rejects duplicate headers and bodies on GET or HEAD") {
        val result = for
          target <- RequestTarget.from("/probe")
          first <- RequestHeader.make("accept", "application/json")
          second <- RequestHeader.make("Accept", "text/plain")
          body <- RequestBody.utf8("body")
        yield (
          StreamingRequest.make(
            HttpMethod.Post,
            target,
            Chunk(first, second),
            RequestBody.empty
          ),
          StreamingRequest.make(
            HttpMethod.Get,
            target,
            Chunk.empty,
            body
          )
        )
        assertTrue(
          result.exists((duplicate, getBody) =>
            duplicate.isLeft && getBody.isLeft
          )
        )
      },
      test("redacts endpoint, credentials, headers, and request bodies") {
        val result = for
          config <- validConfig
          (secret, header, body, request) <- validRequest
        yield List(
          config.toString,
          secret.toString,
          header.toString,
          body.toString,
          request.toString
        ).mkString("\n")
        assertTrue(
          result.exists(rendered =>
            !rendered.contains(token) &&
              !rendered.contains(prompt) &&
              !rendered.contains("models.internal.example") &&
              !rendered.contains("/responses")
          )
        )
      },
      test("preserves response header values without rendering them") {
        val responseSecret = "private-response-cookie"
        val result = ResponseHeaders.from(
          List(
            "content-type" -> "text/event-stream",
            "set-cookie" -> responseSecret,
            "x-value" -> "one",
            "X-Value" -> "two"
          )
        )
        assertTrue(
          result.exists(headers =>
            headers.first("Content-Type").contains("text/event-stream") &&
              headers.all("x-value") == Chunk("one", "two") &&
              !headers.toString.contains(responseSecret) &&
              !headers.toString.contains("set-cookie")
          )
        )
      },
      test("bounds aggregate response header bytes") {
        assertTrue(
          ResponseHeaders.from(List("x-value" -> "1234"), 4).isLeft,
          ResponseHeaders.from(List("x-value" -> "1234"), 64).isRight
        )
      },
      test("uses stable safe errors") {
        val rendered = List(
          TransportError.InvalidEndpoint,
          TransportError.OpenFailed,
          TransportError.BodyFailed
        ).mkString("\n")
        assertTrue(
          !rendered.contains(token),
          !TransportError.OpenFailed.retryable,
          !TransportError.BodyFailed.retryable
        )
      }
    )
