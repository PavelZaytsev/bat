package bat.telemetry

import zio.*
import zio.test.*

object InMemoryTelemetrySpec extends ZIOSpecDefault:
  import TelemetryTestKit.*

  def spec: Spec[TestEnvironment, Any] =
    suite("in-memory telemetry")(
      test(
        "assigns sequence numbers from one and builds a validated snapshot"
      ) {
        val bdr = attribution(1)
        for
          collector <- InMemoryTelemetry.make
          _ <- collector.emit(start)
          _ <- collector.emit(
            TelemetryEvent.ModelTurn(
              bdr,
              ModelTurnKind.Completed,
              tokenMeasurements(8L, 5L, 3L),
              ModelTimingMeasurements.logicalTurn(12L),
              Measurement.Unavailable(MissingReason.NotApplicable)
            )
          )
          _ <- collector.emit(completed(8L, finalBdr = bdr))
          records <- collector.records
          document <- collector.document(runId, deployment)
        yield assertTrue(
          records.map(_.sequence) == Chunk(1L, 2L, 3L),
          document.exists(_.records == records),
          document.exists(_.summary.modelTurns == 1)
        )
      },
      test("returned record snapshots remain immutable") {
        for
          collector <- InMemoryTelemetry.make
          _ <- collector.emit(start)
          before <- collector.records
          _ <- collector.emit(
            TelemetryEvent.RunFailed(code("provider_failed"), 1L)
          )
          after <- collector.records
        yield assertTrue(
          before.size == 1,
          before.map(_.sequence) == Chunk(1L),
          after.size == 2,
          after.map(_.sequence) == Chunk(1L, 2L)
        )
      },
      test("serializes concurrent emissions into unique contiguous sequence") {
        val count = 256
        for
          collector <- InMemoryTelemetry.make
          _ <- ZIO.foreachParDiscard(1 to count) { iteration =>
            collector.emit(
              TelemetryEvent.BdrCheckpoint(
                attribution(iteration, revision = iteration.toLong)
              )
            )
          }
          records <- collector.records
          iterations = records.collect {
            case TelemetryRecord(
                  _,
                  TelemetryEvent.BdrCheckpoint(value)
                ) =>
              value.iteration
          }
        yield assertTrue(
          records.size == count,
          records.map(_.sequence) == Chunk.fromIterable(1L to count.toLong),
          records.map(_.sequence).toSet.size == count,
          iterations.toSet == (1 to count).toSet
        )
      },
      test("no-op telemetry accepts sanitized events without state") {
        Telemetry.noop.emit(start).as(assertTrue(true))
      }
    )
