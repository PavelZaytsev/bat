package bat.telemetry

import zio.{Chunk, Ref, UIO, URIO, ZIO}

/** Functional sink for sanitized telemetry events.
  *
  * Emission cannot fail the BAT run. Durable persistence is deliberately a
  * later interpreter; this slice supplies a no-op sink and an immutable
  * in-memory collector.
  */
trait Telemetry:
  def emit(event: TelemetryEvent): UIO[Unit]

object Telemetry:
  val noop: Telemetry = new Telemetry:
    def emit(event: TelemetryEvent): UIO[Unit] = ZIO.unit

  def emit(event: TelemetryEvent): URIO[Telemetry, Unit] =
    ZIO.serviceWithZIO[Telemetry](_.emit(event))

final class InMemoryTelemetry private (
    state: Ref[(Long, Chunk[TelemetryRecord])]
) extends Telemetry:
  def emit(event: TelemetryEvent): UIO[Unit] =
    state.update { case (last, records) =>
      val next = last + 1L
      next -> (records :+ TelemetryRecord(next, event))
    }

  def records: UIO[Chunk[TelemetryRecord]] = state.get.map(_._2)

  def document(
      runId: TelemetryRunId,
      deployment: DeploymentFingerprint
  ): UIO[Either[TelemetryError, TelemetryDocument]] =
    records.map(TelemetryDocument.from(runId, deployment, _))

object InMemoryTelemetry:
  def make: UIO[InMemoryTelemetry] =
    Ref
      .make(0L -> Chunk.empty[TelemetryRecord])
      .map(new InMemoryTelemetry(_))
