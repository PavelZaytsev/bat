package bat.probe

import java.net.InetAddress
import java.nio.charset.StandardCharsets

import scala.annotation.tailrec

import zio.*
import zio.http.*
import zio.stream.ZStream

/** A real loopback HTTP server for the live GPT-OSS probe tests.
  *
  * Unlike the adapter's unit-test driver, this fixture exercises URI
  * resolution, request headers, request-body streaming, response metadata,
  * socket lifecycle, and SSE bytes through zio-http on both sides.
  */
private[probe] object LoopbackResponsesServer:
  private val Ipv4Loopback = InetAddress.getByAddress(
    Array[Byte](127.toByte, 0.toByte, 0.toByte, 1.toByte)
  )

  final case class ScriptedResponse(
      status: Status,
      contentType: String,
      chunks: Chunk[Chunk[Byte]],
      headers: Chunk[(String, String)] = Chunk.empty
  )

  object ScriptedResponse:
    def sse(payload: String): ScriptedResponse =
      ScriptedResponse(
        Status.Ok,
        "text/event-stream; charset=utf-8",
        hostileChunks(payload)
      )

    def text(status: Status, payload: String): ScriptedResponse =
      ScriptedResponse(
        status,
        "application/json; charset=utf-8",
        hostileChunks(payload)
      )

    def redirect(location: String): ScriptedResponse =
      ScriptedResponse(
        Status.TemporaryRedirect,
        "text/plain; charset=utf-8",
        Chunk.empty,
        Chunk("location" -> location)
      )

  final class CapturedRequest private[probe] (
      val method: String,
      val path: String,
      val authorization: Option[String],
      val accept: Option[String],
      val contentType: Option[String],
      val body: String
  ):
    override def toString: String =
      "CapturedRequest(method=<redacted>, path=<redacted>, headers=<redacted>, body=<redacted>)"

  final class Running private[probe] (
      val baseUrl: String,
      private val captured: Ref[Chunk[CapturedRequest]]
  ):
    def requests: UIO[Chunk[CapturedRequest]] = captured.get

    override def toString: String =
      "LoopbackResponsesServer.Running(endpoint=<redacted>)"

  /** Acquire a server on a kernel-selected loopback port, execute `use`, and
    * close every listener/channel when the effect completes.
    */
  def use[R, E, A](
      script: Chunk[ScriptedResponse]
  )(
      use: Running => ZIO[R, E, A]
  ): ZIO[R, E | Throwable, A] =
    for
      captured <- Ref.make(Chunk.empty[CapturedRequest])
      nextResponse <- Ref.make(0)
      routes = responsesRoute(script, captured, nextResponse)
      result <- (
        for
          _ <- Server.install(routes)
          port <- ZIO.serviceWithZIO[Server](_.port)
          running = new Running(s"http://127.0.0.1:$port", captured)
          value <- use(running)
        yield value
      ).provideSomeLayer[R](
        Server.defaultWith(
          _.binding(Ipv4Loopback, 0)
            .requestStreaming(Server.RequestStreaming.Enabled)
        )
      )
    yield result

  private def responsesRoute(
      script: Chunk[ScriptedResponse],
      captured: Ref[Chunk[CapturedRequest]],
      nextResponse: Ref[Int]
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "v1" / "responses" -> requestHandler(
        script,
        captured,
        nextResponse
      ),
      Method.POST / "v1" / "chat" / "completions" -> requestHandler(
        script,
        captured,
        nextResponse
      ),
      Method.POST / "redirect-target" -> requestHandler(
        script,
        captured,
        nextResponse
      )
    )

  private def requestHandler(
      script: Chunk[ScriptedResponse],
      captured: Ref[Chunk[CapturedRequest]],
      nextResponse: Ref[Int]
  ) =
    handler { (request: Request) =>
      for
        body <- request.body.asString.orDie
        observed = new CapturedRequest(
          request.method.render,
          request.url.path.encode,
          request.headers.get("authorization"),
          request.headers.get("accept"),
          request.headers.get("content-type"),
          body
        )
        _ <- captured.update(_ :+ observed)
        index <- nextResponse.getAndUpdate(_ + 1)
      yield script.lift(index) match
        case Some(response) => toResponse(response)
        case None           =>
          Response(
            status = Status.InternalServerError,
            headers = Headers("content-type" -> "application/json"),
            body = Body.fromString("{\"error\":\"unexpected_request\"}")
          )
    }

  private def toResponse(scripted: ScriptedResponse): Response =
    Response(
      status = scripted.status,
      headers = Headers.fromIterable(
        (Chunk("content-type" -> scripted.contentType) ++ scripted.headers)
          .map((name, value) => Header.Custom(name, value))
      ),
      body = Body.fromStreamChunked(
        ZStream
          .fromIterable(scripted.chunks)
          .flatMap(bytes => ZStream.fromChunk(bytes))
      )
    )

  private def hostileChunks(value: String): Chunk[Chunk[Byte]] =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    val sizes = Array(1, 2, 3, 5, 8, 13, 21)

    @tailrec
    def loop(
        offset: Int,
        index: Int,
        accumulated: List[Chunk[Byte]]
    ): List[Chunk[Byte]] =
      if offset >= bytes.length then accumulated.reverse
      else
        val end = Math.min(bytes.length, offset + sizes(index % sizes.length))
        loop(
          end,
          index + 1,
          Chunk.fromArray(bytes.slice(offset, end)) :: accumulated
        )

    Chunk.fromIterable(loop(0, 0, Nil))
