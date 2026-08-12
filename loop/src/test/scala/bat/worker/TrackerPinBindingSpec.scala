package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.test.*

object TrackerPinBindingSpec extends ZIOSpecDefault:
  private val Pins = unsafe(
    PullRequestPins.make(
      "R_base",
      "R_head",
      "PR_5",
      "refs/heads/main",
      "1" * 40,
      "refs/pull/5/head",
      "2" * 40
    )
  )

  def spec =
    suite("BDR tracker pin binding")(
      test("accepts only the exact manifest base, head, and repository root") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-tracker-exact-")
            _ <- writeTracker(
              repository,
              Pins.baseCommit.value,
              Pins.headCommit.value,
              repository.toAbsolutePath.normalize.toString
            )
            result <- TrackerPinBinding.verify(repository, Pins).either
          yield assertTrue(result == Right(()))
        }
      },
      test("rejects base and head substitutions independently") {
        ZIO.scoped {
          for
            baseRepository <- temporaryDirectory("bat-tracker-base-")
            _ <- writeTracker(
              baseRepository,
              "3" * 40,
              Pins.headCommit.value,
              baseRepository.toAbsolutePath.normalize.toString
            )
            wrongBase <- TrackerPinBinding
              .verify(baseRepository, Pins)
              .either
            headRepository <- temporaryDirectory("bat-tracker-head-")
            _ <- writeTracker(
              headRepository,
              Pins.baseCommit.value,
              "4" * 40,
              headRepository.toAbsolutePath.normalize.toString
            )
            wrongHead <- TrackerPinBinding
              .verify(headRepository, Pins)
              .either
          yield assertTrue(
            errorCode(wrongBase).contains("bdr_pin_mismatch"),
            errorCode(wrongHead).contains("bdr_pin_mismatch")
          )
        }
      },
      test("rejects a tracker bound to a different absolute root") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-tracker-root-")
            other <- temporaryDirectory("bat-tracker-other-")
            _ <- writeTracker(
              repository,
              Pins.baseCommit.value,
              Pins.headCommit.value,
              other.toAbsolutePath.normalize.toString
            )
            result <- TrackerPinBinding.verify(repository, Pins).either
          yield assertTrue(errorCode(result).contains("bdr_pin_mismatch"))
        }
      },
      test("rejects a relative spelling even if it resolves to the same root") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-tracker-relative-")
            cwd <- ZIO.attempt(Path.of("").toAbsolutePath.normalize)
            relative = cwd
              .relativize(repository.toAbsolutePath.normalize)
              .toString
            _ <- writeTracker(
              repository,
              Pins.baseCommit.value,
              Pins.headCommit.value,
              relative
            )
            result <- TrackerPinBinding.verify(repository, Pins).either
          yield assertTrue(errorCode(result).contains("bdr_pin_mismatch"))
        }
      },
      test("rejects a symlinked tracker file") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-tracker-link-file-")
            outside <- temporaryDirectory("bat-tracker-link-target-")
            target = outside.resolve("progress.yaml")
            _ <- writeTrackerFile(
              target,
              Pins.baseCommit.value,
              Pins.headCommit.value,
              repository.toAbsolutePath.normalize.toString
            )
            _ <- ZIO.attemptBlocking {
              val bdr = Files.createDirectories(repository.resolve(".bdr"))
              val _ =
                Files.createSymbolicLink(bdr.resolve("progress.yaml"), target)
            }
            result <- TrackerPinBinding.verify(repository, Pins).either
          yield assertTrue(errorCode(result).contains("invalid_bdr_tracker"))
        }
      },
      test("rejects an intermediate .bdr symlink escaping the repository") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-tracker-link-parent-")
            outside <- temporaryDirectory("bat-tracker-parent-target-")
            _ <- writeTrackerFile(
              outside.resolve("progress.yaml"),
              Pins.baseCommit.value,
              Pins.headCommit.value,
              repository.toAbsolutePath.normalize.toString
            )
            _ <- ZIO.attemptBlocking {
              val _ =
                Files.createSymbolicLink(repository.resolve(".bdr"), outside)
            }
            result <- TrackerPinBinding.verify(repository, Pins).either
          yield assertTrue(errorCode(result).contains("invalid_bdr_tracker"))
        }
      },
      test("rejects malformed or incomplete source metadata") {
        ZIO.scoped {
          for
            repository <- temporaryDirectory("bat-tracker-malformed-")
            bdr <- ZIO.attemptBlocking(
              Files.createDirectories(repository.resolve(".bdr"))
            )
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                bdr.resolve("progress.yaml"),
                """{"source":{"base_sha":"missing-head"}}""",
                StandardCharsets.UTF_8
              )
            }
            result <- TrackerPinBinding.verify(repository, Pins).either
          yield assertTrue(errorCode(result).contains("bdr_pin_mismatch"))
        }
      }
    ) @@ TestAspect.sequential

  private def writeTracker(
      repository: Path,
      base: String,
      head: String,
      root: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val bdr = Files.createDirectories(repository.resolve(".bdr"))
      writeTrackerFileUnsafe(bdr.resolve("progress.yaml"), base, head, root)
    }

  private def writeTrackerFile(
      path: Path,
      base: String,
      head: String,
      root: String
  ): Task[Unit] =
    ZIO.attemptBlocking(writeTrackerFileUnsafe(path, base, head, root))

  private def writeTrackerFileUnsafe(
      path: Path,
      base: String,
      head: String,
      root: String
  ): Unit =
    val json =
      s"""{"source":{"base_sha":${base.toJson},"starting_head_sha":${head.toJson},"root":${root.toJson}},"policy":{"github_projection":"off"},"evidence":{}}"""
    val _ = Files.writeString(path, json, StandardCharsets.UTF_8)

  private def errorCode[A](result: Either[WorkerError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def unsafe[A](result: Either[WorkerError, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
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
    }.ignore
