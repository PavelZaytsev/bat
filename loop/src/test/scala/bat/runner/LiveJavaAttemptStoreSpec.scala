package bat.runner

import bat.protocol.*
import bat.telemetry.*
import bat.worker.AttemptId

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, LinkOption, Path}

import zio.json.ast.Json
import zio.{Chunk, Duration, ZIO}
import zio.test.*

object LiveJavaAttemptStoreSpec extends ZIOSpecDefault:
  private val Binding = "b" * 64

  def spec =
    suite("live Java attempt store")(
      test("writes an initial running checkpoint before returning") {
        for
          fixture <- fixtureDirectory("bat-live-attempt-initial-")
          runId = unsafe(TelemetryRunId.from("live-initial-run"))
          attemptId = unsafe(AttemptId.from("attempt-001"))
          _ <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            attemptId,
            Binding,
            None
          )
          checkpoint <- read(
            fixture.output.resolve(".attempt-001.in-progress/checkpoint.json")
          )
          lineage <- read(
            fixture.output.resolve(".live-initial-run.lineage.json")
          )
        yield assertTrue(
          checkpoint.contains("\"status\":\"running\""),
          checkpoint.contains("\"parent_attempt_id\":null"),
          checkpoint.contains("\"telemetry_record_count\":0"),
          checkpoint.contains("\"telemetry_records\":[]"),
          checkpoint.contains("\"attempt_started_epoch_millis\":"),
          lineage.contains("\"tip_attempt_id\":\"attempt-001\"")
        )
      },
      test("rejects rewinding a logical run behind its durable tip") {
        for
          fixture <- fixtureDirectory("bat-live-attempt-lineage-")
          runId = unsafe(TelemetryRunId.from("live-lineage-run"))
          firstId = unsafe(AttemptId.from("attempt-001"))
          secondId = unsafe(AttemptId.from("attempt-002"))
          rewindId = unsafe(AttemptId.from("attempt-003"))
          _ <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            firstId,
            Binding,
            None
          )
          _ <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            secondId,
            Binding,
            Some(firstId)
          )
          secondCheckpoint <- read(
            fixture.output.resolve(".attempt-002.in-progress/checkpoint.json")
          )
          rewind <- LiveJavaAttemptStore
            .prepare(
              fixture.output,
              fixture.project,
              runId,
              rewindId,
              Binding,
              Some(firstId)
            )
            .either
          lineage <- read(
            fixture.output.resolve(".live-lineage-run.lineage.json")
          )
          rewindStaging <- exists(
            fixture.output.resolve(".attempt-003.in-progress")
          )
        yield assertTrue(
          secondCheckpoint.contains(
            "\"parent_attempt_id\":\"attempt-001\""
          ),
          rewind.left.exists(_.code == "protocol_violation"),
          lineage.contains("\"tip_attempt_id\":\"attempt-002\""),
          !rewindStaging
        )
      },
      test("charges downtime since the prior attempt began") {
        for
          fixture <- fixtureDirectory("bat-live-attempt-wall-")
          runId = unsafe(TelemetryRunId.from("live-wall-run"))
          firstId = unsafe(AttemptId.from("attempt-001"))
          secondId = unsafe(AttemptId.from("attempt-002"))
          _ <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            firstId,
            Binding,
            None
          )
          _ <- TestClock.adjust(Duration.fromSeconds(90))
          second <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            secondId,
            Binding,
            Some(firstId)
          )
        yield assertTrue(second.priorUsage.wallMillis >= 90000L)
      },
      test("checkpoints each tool turn and carries cumulative budgets") {
        for
          fixture <- fixtureDirectory("bat-live-attempt-resume-")
          runId = unsafe(TelemetryRunId.from("live-logical-run"))
          firstId = unsafe(AttemptId.from("attempt-001"))
          first <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            firstId,
            Binding,
            None
          )
          telemetry <- first.telemetry
          bdr = attribution(iteration = 1, revision = 7L)
          _ <- telemetry.emit(
            TelemetryEvent.ModelTurn(
              bdr,
              ModelTurnKind.ToolCalls,
              TokenMeasurements(
                Measurement.Observed(10L),
                Measurement.Observed(7L),
                Measurement.Observed(0L),
                Measurement.Observed(3L),
                Measurement.Observed(2L)
              ),
              ModelTimingMeasurements.logicalTurn(5L),
              Measurement.Unavailable(MissingReason.NotApplicable)
            )
          )
          _ <- telemetry.emit(
            TelemetryEvent.ToolExecution(
              TelemetryToolName.capture("worker_java_build"),
              bdr,
              Measurement.Observed(bdr),
              ToolExecutionOutcome.Succeeded,
              Measurement.Observed(8L),
              Measurement.Unavailable(MissingReason.NotApplicable)
            )
          )
          checkpoint <- read(
            fixture.output.resolve(".attempt-001.in-progress/checkpoint.json")
          )
          secondId = unsafe(AttemptId.from("attempt-002"))
          second <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            secondId,
            Binding,
            Some(firstId)
          )
          original = unsafe(
            BudgetLimits.make(4, 5, Duration.fromSeconds(60 * 60), 100L)
          )
          remaining = second.remaining(original)
          duplicate <- LiveJavaAttemptStore
            .prepare(
              fixture.output,
              fixture.project,
              runId,
              secondId,
              Binding,
              Some(firstId)
            )
            .either
          mismatched <- LiveJavaAttemptStore
            .prepare(
              fixture.output,
              fixture.project,
              runId,
              unsafe(AttemptId.from("attempt-003")),
              "c" * 64,
              Some(firstId)
            )
            .either
        yield assertTrue(
          checkpoint.contains("\"status\":\"running\""),
          checkpoint.contains("\"iterations\":1"),
          checkpoint.contains("\"tool_calls\":1"),
          checkpoint.contains("\"total_tokens\":10"),
          checkpoint.contains("\"telemetry_record_count\":2"),
          checkpoint.contains("\"type\":\"tool_execution\""),
          second.priorUsage.iterations == 1,
          second.priorUsage.toolCalls == 1,
          second.priorUsage.totalTokens == 10L,
          remaining.exists(_.maxIterations == 3),
          remaining.exists(_.maxToolCalls == 4),
          remaining.exists(_.maxTotalTokens == 90L),
          duplicate.left.exists(_.code == "protocol_violation"),
          mismatched.left.exists(_.code == "protocol_violation")
        )
      },
      test("publishes one attempt directory without overwriting it") {
        for
          fixture <- fixtureDirectory("bat-live-attempt-publish-")
          runId = unsafe(TelemetryRunId.from("live-published-run"))
          attemptId = unsafe(AttemptId.from("attempt-001"))
          store <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            attemptId,
            Binding,
            None
          )
          telemetry <- store.telemetry
          bdr = attribution(1, 3L)
          _ <- telemetry.emit(TelemetryEvent.BdrCheckpoint(bdr))
          records <- telemetry.records
          _ <- store.publish(
            "failed",
            records,
            Chunk(
              "result.json" -> "{\"decision\":\"failed\"}",
              "telemetry.json" -> "{\"records\":1}"
            ),
            Chunk("ENDPOINT_CANARY", "TOKEN_CANARY")
          )
          finalDirectory = fixture.output.resolve("attempt-001")
          present <- exists(finalDirectory)
          staging <- exists(
            fixture.output.resolve(".attempt-001.in-progress")
          )
          checkpoint <- read(finalDirectory.resolve("checkpoint.json"))
          duplicate <- store
            .publish(
              "failed",
              records,
              Chunk("result.json" -> "{}"),
              Chunk.empty
            )
            .either
        yield assertTrue(
          present,
          !staging,
          checkpoint.contains("\"status\":\"failed\""),
          duplicate.left.exists(_.code == "protocol_violation")
        )
      },
      test("refuses to resume a completed attempt or publish a leak") {
        for
          fixture <- fixtureDirectory("bat-live-attempt-guard-")
          runId = unsafe(TelemetryRunId.from("live-guard-run"))
          firstId = unsafe(AttemptId.from("attempt-001"))
          first <- LiveJavaAttemptStore.prepare(
            fixture.output,
            fixture.project,
            runId,
            firstId,
            Binding,
            None
          )
          telemetry <- first.telemetry
          _ <- telemetry.emit(
            TelemetryEvent.BdrCheckpoint(attribution(1, 1L))
          )
          records <- telemetry.records
          leak <- first
            .publish(
              "ready",
              records,
              Chunk("result.json" -> "{\"value\":\"TOKEN_CANARY\"}"),
              Chunk("TOKEN_CANARY")
            )
            .either
          _ <- first.publish(
            "ready",
            records,
            Chunk("result.json" -> "{\"decision\":\"ready\"}"),
            Chunk("TOKEN_CANARY")
          )
          secondId = unsafe(AttemptId.from("attempt-002"))
          resume <- LiveJavaAttemptStore
            .prepare(
              fixture.output,
              fixture.project,
              runId,
              secondId,
              Binding,
              Some(firstId)
            )
            .either
        yield assertTrue(
          leak.left.exists(_.code == "protocol_violation"),
          resume.left.exists(_.code == "protocol_violation")
        )
      }
    ) @@ TestAspect.sequential

  private final case class Fixture(project: Path, output: Path)

  private def fixtureDirectory(prefix: String) =
    ZIO.attemptBlocking {
      val root = Files.createTempDirectory(prefix).toRealPath()
      val project = Files.createDirectory(root.resolve("project"))
      val output = Files.createDirectory(root.resolve("output"))
      val privateMode = PosixFilePermissions.fromString("rwx------")
      val _ = Files.setPosixFilePermissions(project, privateMode)
      val _ = Files.setPosixFilePermissions(output, privateMode)
      Fixture(project, output)
    }

  private def attribution(
      iteration: Int,
      revision: Long
  ): BdrAttribution =
    val state = unsafe(
      Revision
        .from(revision)
        .flatMap(value =>
          BdrStateView.make(
            value,
            "active",
            Json.Obj(
              Chunk(
                "action" -> Json.Str("continue_phase"),
                "phase" -> Json.Str("expose"),
                "slice" -> Json.Str("S-1")
              )
            ),
            "a" * 64
          )
        )
    )
    BdrAttribution.from(iteration, state)

  private def exists(path: Path) =
    ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS))

  private def read(path: Path) =
    ZIO.attemptBlocking(Files.readString(path))

  private def unsafe[E, A](value: Either[E, A]): A =
    value.fold(error => throw AssertionError(String.valueOf(error)), identity)
