package bat.telemetry

import zio.{Chunk, Ref, Semaphore, UIO, URIO, ZIO}

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
    state: Ref[(Long, Chunk[TelemetryRecord])],
    emissionGate: Semaphore,
    observer: Chunk[TelemetryRecord] => UIO[Unit]
) extends Telemetry:
  def emit(event: TelemetryEvent): UIO[Unit] =
    emissionGate.withPermit {
      state
        .modify { case (last, records) =>
          val next = last + 1L
          val updated = records :+ TelemetryRecord(next, event)
          updated -> (next -> updated)
        }
        .flatMap(observer)
    }

  def records: UIO[Chunk[TelemetryRecord]] = state.get.map(_._2)

  def document(
      runId: TelemetryRunId,
      deployment: DeploymentFingerprint
  ): UIO[Either[TelemetryError, TelemetryDocument]] =
    records.map(TelemetryDocument.from(runId, deployment, _))

object InMemoryTelemetry:
  def make: UIO[InMemoryTelemetry] =
    makeObserved(_ => ZIO.unit)

  /** Build the ordinary in-memory collector and observe each complete ordered
    * snapshot after an event has been assigned its sequence number. The
    * observer remains in the sink's `UIO` contract, so a durable interpreter
    * may either record its own health or defect deliberately to stop a run
    * whose checkpoint cannot be persisted.
    *
    * Emission and observation are serialized. A later snapshot can therefore
    * never reach a persistence boundary before an earlier one.
    */
  def makeObserved(
      observer: Chunk[TelemetryRecord] => UIO[Unit]
  ): UIO[InMemoryTelemetry] =
    for
      state <- Ref.make(0L -> Chunk.empty[TelemetryRecord])
      gate <- Semaphore.make(1L)
    yield new InMemoryTelemetry(
      state,
      gate,
      Option(observer).getOrElse(_ => ZIO.unit)
    )
