package bat.backend.gptoss

import bat.backend.wire.{StreamingWireBackend, WireDialect}
import bat.protocol.*
import bat.telemetry.*
import bat.transport.*

import zio.ZLayer

/** The GPT-OSS Responses cartridge is the shared streaming backend interpreting
  * one pinned dialect. Transport mechanics are shared; the dialect is not.
  */
type GptOssBackend = StreamingWireBackend[ResponsesDialect]

object GptOssBackend:
  def make(
      config: GptOssConfig,
      http: StreamingHttp
  ): Either[BatError, GptOssBackend] =
    make(config, http, Telemetry.noop)

  def make(
      config: GptOssConfig,
      http: StreamingHttp,
      telemetry: Telemetry
  ): Either[BatError, GptOssBackend] =
    for
      dialect <- ResponsesDialect.make(config)
      backend <- StreamingWireBackend.make(dialect, http, telemetry)
    yield backend

  /** Functional construction for application wiring. The same controller can
    * receive a differently typed `Backend` layer for another GPT-OSS dialect,
    * Claude, or Kimi without changing its loop or tool registry.
    */
  val live: ZLayer[GptOssConfig & StreamingHttp, BatError, GptOssBackend] =
    ZLayer.fromZIO {
      for
        config <- zio.ZIO.service[GptOssConfig]
        http <- zio.ZIO.service[StreamingHttp]
        backend <- zio.ZIO.fromEither(make(config, http))
      yield backend
    }

  /** Application wiring that records sanitized provider-attempt telemetry in
    * the same sink used by the controller.
    */
  val observed: ZLayer[
    GptOssConfig & StreamingHttp & Telemetry,
    BatError,
    GptOssBackend
  ] =
    ZLayer.fromZIO {
      for
        config <- zio.ZIO.service[GptOssConfig]
        http <- zio.ZIO.service[StreamingHttp]
        telemetry <- zio.ZIO.service[Telemetry]
        backend <- zio.ZIO.fromEither(make(config, http, telemetry))
      yield backend
    }
