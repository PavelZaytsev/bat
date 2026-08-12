package bat.worker

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.protocol.BatError

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object WorkerBdrLifecycleSpec extends ZIOSpecDefault:
  def spec =
    suite("worker BDR lifecycle")(
      test("derives BDR identity only from typed worker provenance") {
        for
          runId <- ZIO.fromEither(RunId.from("bat-run"))
          initialization <- WorkerBdrLifecycle.liveInitialization(pins, runId)
        yield assertTrue(
          initialization.repository == pins.baseRepository.value,
          initialization.runId == runId.value,
          initialization.baseSha == pins.baseCommit.value,
          initialization.headSha == pins.headCommit.value,
          initialization.maxFixedPointPasses == 3,
          initialization.maxPhaseAttempts == 3
        )
      },
      test("factory retains tracker-pin verification on initialize") {
        val session = inertSession
        val lifecycle = WorkerBdrLifecycle.make(
          (_, _, _) => ZIO.succeed(session),
          (_, _, _) => ZIO.succeed(session)
        )
        for
          runId <- ZIO.fromEither(RunId.from("bat-run"))
          result <- lifecycle
            .initialize(runId, Path.of("/private/worker/missing"), pins)
            .either
        yield assertTrue(
          result.left.toOption.exists(_.code == "invalid_bdr_tracker")
        )
      },
      test(
        "resume rejects legacy controller authority and GitHub policy drift"
      ) {
        ZIO.scoped {
          for
            repository <- temporaryDirectory
            bdr <- ZIO.attemptBlocking(
              Files.createDirectory(repository.resolve(".bdr"))
            )
            tracker = bdr.resolve("progress.yaml")
            _ <- writeTracker(
              tracker,
              repository,
              githubProjection = "off",
              evidence = "\"E-OLD\":{\"kind\":\"human_approval\"}"
            )
            approval <- TrackerPinBinding.verify(repository, pins).either
            _ <- writeTracker(
              tracker,
              repository,
              githubProjection = "sync",
              evidence = ""
            )
            projection <- TrackerPinBinding.verify(repository, pins).either
          yield assertTrue(
            approval.left.toOption.exists(_.code == "bdr_pin_mismatch"),
            projection.left.toOption.exists(_.code == "bdr_pin_mismatch")
          )
        }
      }
    )

  private val pins: PullRequestPins =
    PullRequestPins
      .make(
        "base-repository",
        "head-repository",
        "42",
        "refs/heads/main",
        "b" * 40,
        "refs/heads/feature",
        "c" * 40
      )
      .fold(
        error => throw IllegalArgumentException(error.safeMessage),
        identity
      )

  private val inertSession: BdrSession = new BdrSession:
    val engineCommit = "a" * 40
    val actor = "test"
    def current: IO[BatError, ValidatedBdrState] = ZIO.dieMessage("unused")
    def checkpoint: IO[BatError, ValidatedBdrState] = ZIO.dieMessage("unused")
    def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
      ZIO.dieMessage("unused")
    def auditSummary: IO[BatError, Json] = ZIO.dieMessage("unused")
    def completionCheck: IO[BatError, Json.Obj] = ZIO.dieMessage("unused")

  private def writeTracker(
      path: Path,
      repository: Path,
      githubProjection: String,
      evidence: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val text =
        s"""{"source":{"base_sha":"${pins.baseCommit.value}","starting_head_sha":"${pins.headCommit.value}","root":"${repository.toAbsolutePath.normalize}"},"policy":{"github_projection":"$githubProjection"},"evidence":{$evidence}}"""
      val _ = Files.writeString(path, text, StandardCharsets.UTF_8)
    }

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory("bat-worker-bdr-policy-"))
    )(path =>
      ZIO.attemptBlocking {
        if Files.exists(path) then
          val stream = Files.walk(path)
          try
            stream
              .iterator()
              .asScala
              .toList
              .sortBy(_.getNameCount)
              .reverse
              .foreach(candidate => {
                val _ = Files.deleteIfExists(candidate)
              })
          finally stream.close()
      }.orDie
    )
