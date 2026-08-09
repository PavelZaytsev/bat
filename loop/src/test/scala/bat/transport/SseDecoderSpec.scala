package bat.transport

import java.nio.charset.StandardCharsets

import zio.*
import zio.test.*

object SseDecoderSpec extends ZIOSpecDefault:
  private def limits(
      maxEventBytes: Long = 1024,
      maxStreamBytes: Long = 4096
  ): SseLimits =
    SseLimits.make(maxEventBytes, maxStreamBytes).toOption.get

  private def bytes(value: String): Chunk[Byte] =
    Chunk.fromArray(value.getBytes(StandardCharsets.UTF_8))

  private def decode(
      fragments: Chunk[Chunk[Byte]],
      framingLimits: SseLimits = limits()
  ): Either[SseError, Chunk[SseEvent]] =
    for
      initial <- SseDecoder.initial(framingLimits)
      result <- fragments.foldLeft[
        Either[SseError, (SseDecoder.State, Chunk[SseEvent])]
      ](Right((initial, Chunk.empty))) { case (result, fragment) =>
        for
          (state, accumulated) <- result
          (next, emitted) <- SseDecoder.feed(state, fragment)
        yield (next, accumulated ++ emitted)
      }
      (state, events) = result
      trailing <- SseDecoder.finish(state)
    yield events ++ trailing

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("incremental SSE framing")(
      test("decodes arbitrary UTF-8 byte splits, CRLF, and multiline data") {
        val wire =
          "event: response.output_text.delta\r\n" +
            "data: hé\r\n" +
            "data: llo\r\n" +
            "id: provider-private-id\r\n" +
            "\r\n"
        val result = decode(bytes(wire).map(byte => Chunk(byte)))
        assertTrue(
          result.exists(events =>
            events.length == 1 &&
              events.head.data == "hé\nllo" &&
              events.head.eventType.contains("response.output_text.delta") &&
              events.head.id.contains("provider-private-id") &&
              !events.head.toString.contains("hé") &&
              !events.head.toString.contains("provider-private-id")
          )
        )
      },
      test("supports LF, comments, unknown fields, and empty data") {
        val wire =
          ": keepalive\n" +
            "provider-extension: ignored\n" +
            "retry: 1500\n" +
            "data:\n\n"
        val result = decode(Chunk(bytes(wire)))
        assertTrue(
          result.exists(events =>
            events.length == 1 &&
              events.head.data.isEmpty &&
              events.head.retryMillis.contains(1500L)
          )
        )
      },
      test("handles CRLF split across transport chunks") {
        val result = decode(
          Chunk(
            bytes("data: one\r"),
            bytes("\n\r"),
            bytes("\n")
          )
        )
        assertTrue(result.exists(events => events.map(_.data) == Chunk("one")))
      },
      test("rejects malformed UTF-8 without exposing bytes") {
        val invalid = Chunk.fromIterable(
          "data: ".getBytes(StandardCharsets.UTF_8).toSeq ++
            Seq(0xc3.toByte, 0x28.toByte, '\n'.toByte)
        )
        val result = decode(Chunk(invalid))
        assertTrue(
          result == Left(SseError.InvalidUtf8),
          !result.toString.contains("data")
        )
      },
      test("enforces per-event bytes across fragments") {
        val result = decode(
          bytes("data: this event is too large\n\n").map(byte => Chunk(byte)),
          limits(maxEventBytes = 12, maxStreamBytes = 256)
        )
        assertTrue(result == Left(SseError.EventLimitExceeded))
      },
      test("enforces total stream bytes across dispatched events") {
        val result = decode(
          Chunk(bytes("data: a\n\n"), bytes("data: b\n\n")),
          limits(maxEventBytes = 16, maxStreamBytes = 16)
        )
        assertTrue(result == Left(SseError.StreamLimitExceeded))
      },
      test("requires explicit EOF after a fully delimited event") {
        val complete = decode(Chunk(bytes("data: complete\n\n")))
        val missingBlankLine = decode(Chunk(bytes("data: truncated\n")))
        val partialLine = decode(Chunk(bytes("data: truncated")))
        val partialUtf8 = decode(
          Chunk(
            Chunk.fromIterable(
              "data: ".getBytes(StandardCharsets.UTF_8).toSeq :+ 0xc3.toByte
            )
          )
        )
        assertTrue(
          complete.exists(_.map(_.data) == Chunk("complete")),
          missingBlankLine == Left(SseError.TruncatedStream),
          partialLine == Left(SseError.TruncatedStream),
          partialUtf8 == Left(SseError.TruncatedStream)
        )
      },
      test("validates event and stream limits") {
        assertTrue(
          SseLimits.make(0, 1).isLeft,
          SseLimits.make(2, 1).isLeft,
          SseLimits.make(1, 1).isRight,
          SseDecoder.initial(null).isLeft
        )
      },
      test("decoder state does not render buffered provider data") {
        val result = for
          initial <- SseDecoder.initial(limits())
          (state, _) <- SseDecoder.feed(initial, bytes("data: secret"))
        yield state.toString
        assertTrue(result.exists(rendered => !rendered.contains("secret")))
      }
    )
