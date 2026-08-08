package bat.worker.oci

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object JdkOciProcessRunnerSpec extends ZIOSpecDefault:
  private val SafeEnvironment = Map(
    "HOME" -> "/nonexistent",
    "DOCKER_CONFIG" -> "/nonexistent",
    "LANG" -> "C",
    "LC_ALL" -> "C",
    "TZ" -> "UTC"
  )

  def spec =
    suite("JdkOciProcessRunner")(
      test(
        "clears the inherited host environment instead of forwarding secrets"
      ) {
        for
          executable <- existingExecutable("/usr/bin/env", "/bin/env")
          result <- JdkOciProcessRunner.run(
            processSpec(
              "environment-proof",
              Chunk(executable.toString),
              timeout = 5.seconds,
              previewBytes = 8192,
              outputLimitBytes = 16384L
            )
          )
          output = new String(
            result.stdout.preview.toArray,
            StandardCharsets.UTF_8
          )
          lines = output.linesIterator.filter(_.nonEmpty).toSet
          inheritedSecretValues = Set(
            "HOME",
            "SSH_AUTH_SOCK",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "AWS_SECRET_ACCESS_KEY"
          ).flatMap(sys.env.get)
            .filter(value => value.nonEmpty && value != "/nonexistent")
        yield assertTrue(
          result.outcome == OciRunOutcome.Exited(0),
          lines == SafeEnvironment.map((key, value) => s"$key=$value").toSet,
          inheritedSecretValues.forall(value => !output.contains(value)),
          !result.stdout.previewTruncated,
          result.stdout.totalBytes == output
            .getBytes(StandardCharsets.UTF_8)
            .length
            .toLong,
          result.stdout.sha256 == sha256(
            output.getBytes(StandardCharsets.UTF_8)
          )
        )
      },
      test(
        "retains a bounded preview while hashing and counting the full stream"
      ) {
        val payload = "0123456789abcdef"
        for
          executable <- existingExecutable("/usr/bin/printf", "/bin/printf")
          result <- JdkOciProcessRunner.run(
            processSpec(
              "stream-receipt",
              Chunk(executable.toString, payload),
              timeout = 5.seconds,
              previewBytes = 5,
              outputLimitBytes = 1024L
            )
          )
          bytes = payload.getBytes(StandardCharsets.UTF_8)
        yield assertTrue(
          result.outcome == OciRunOutcome.Exited(0),
          result.stdout.totalBytes == bytes.length.toLong,
          result.stdout.sha256 == sha256(bytes),
          new String(
            result.stdout.preview.toArray,
            StandardCharsets.UTF_8
          ) == "01234",
          result.stdout.previewTruncated,
          result.stderr.totalBytes == 0L,
          !result.stderr.previewTruncated
        )
      },
      test(
        "terminates output floods with a typed outcome and bounded preview"
      ) {
        for
          executable <- existingExecutable("/usr/bin/yes", "/bin/yes")
          started <- Clock.nanoTime
          result <- JdkOciProcessRunner.run(
            processSpec(
              "output-limit",
              Chunk(executable.toString, "canary"),
              timeout = 5.seconds,
              previewBytes = 32,
              outputLimitBytes = 128L
            )
          )
          finished <- Clock.nanoTime
        yield assertTrue(
          result.outcome match
            case OciRunOutcome.OutputLimit(128L, observed) => observed > 128L
            case _                                         => false,
          result.stdout.preview.size <= 32,
          result.stdout.totalBytes <= 128L + 16384L,
          result.stdout.sha256.matches("[0-9a-f]{64}"),
          result.stdout.previewTruncated,
          finished - started < 5.seconds.toNanos
        )
      } @@ TestAspect.withLiveClock,
      test("distinguishes OCI launcher failures from target exit codes") {
        for
          shell <- existingExecutable("/bin/sh", "/usr/bin/sh")
          outcomes <- ZIO.foreach(Chunk(125, 126, 127)) { exitCode =>
            JdkOciProcessRunner
              .run(
                processSpec(
                  s"runtime-failure-$exitCode",
                  Chunk(shell.toString, "-c", s"exit $exitCode"),
                  timeout = 5.seconds,
                  previewBytes = 128,
                  outputLimitBytes = 1024L
                )
              )
              .map(_.outcome)
          }
        yield assertTrue(
          outcomes == Chunk(
            OciRunOutcome.RuntimeFailed(125),
            OciRunOutcome.RuntimeFailed(126),
            OciRunOutcome.RuntimeFailed(127)
          )
        )
      } @@ TestAspect.withLiveClock,
      test("treats natural 124, 137, and 143 exits as ordinary outcomes") {
        for
          shell <- existingExecutable("/bin/sh", "/usr/bin/sh")
          outcomes <- ZIO.foreach(Chunk(124, 137, 143)) { exitCode =>
            JdkOciProcessRunner
              .run(
                processSpec(
                  s"supervised-exit-$exitCode",
                  Chunk(shell.toString, "-c", s"exit $exitCode"),
                  timeout = 5.seconds,
                  previewBytes = 128,
                  outputLimitBytes = 1024L
                )
              )
              .map(_.outcome)
          }
        yield assertTrue(
          outcomes == Chunk(
            OciRunOutcome.Exited(124),
            OciRunOutcome.Exited(137),
            OciRunOutcome.Exited(143)
          )
        )
      } @@ TestAspect.withLiveClock,
      test(
        "records a real deadline timeout independently of process exit code"
      ) {
        ZIO.scoped {
          for
            shell <- existingExecutable("/bin/sh", "/usr/bin/sh")
            temporary <- temporaryDirectory
            pidFile = temporary.resolve("child.pid")
            script =
              s"sleep 30 & child=$$!; printf '%s' \"$$child\" > '${pidFile.toString}'; wait"
            result <- JdkOciProcessRunner.run(
              processSpec(
                "timeout-tree",
                Chunk(shell.toString, "-c", script),
                timeout = 300.millis,
                previewBytes = 128,
                outputLimitBytes = 1024L
              )
            )
            childPid <- readPid(pidFile)
            dead <- waitUntilDead(childPid, 2.seconds)
          yield assertTrue(
            result.outcome == OciRunOutcome.TimedOut,
            dead
          )
        }
      } @@ TestAspect.withLiveClock
    ) @@ TestAspect.sequential

  private def processSpec(
      operationId: String,
      argv: Chunk[String],
      timeout: Duration,
      previewBytes: Int,
      outputLimitBytes: Long
  ): OciProcessSpec =
    OciProcessSpec(
      operationId,
      argv,
      Path.of("/").toAbsolutePath.normalize,
      SafeEnvironment,
      None,
      timeout,
      previewBytes,
      previewBytes,
      outputLimitBytes
    )

  private def existingExecutable(candidates: String*): Task[Path] =
    ZIO.attempt {
      candidates
        .map(Path.of(_))
        .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
        .getOrElse(
          throw new IllegalStateException(
            s"none of the test executables exist: ${candidates.mkString(", ")}"
          )
        )
    }

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory("bat-oci-tree-"))
    )(
      deleteRecursively
    )

  private def readPid(path: Path): Task[Long] =
    ZIO.attemptBlocking(
      Files.readString(path, StandardCharsets.UTF_8).trim.toLong
    )

  private def waitUntilDead(pid: Long, timeout: Duration): UIO[Boolean] =
    val dead = ZIO
      .attempt {
        val handle = ProcessHandle.of(pid)
        handle.isEmpty || !handle.get().isAlive
      }
      .orElseSucceed(false)
    def poll: UIO[Boolean] =
      dead.flatMap(isDead =>
        if isDead then ZIO.succeed(true) else ZIO.sleep(20.millis) *> poll
      )
    poll.timeout(timeout).map(_.getOrElse(false))

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
            .foreach(candidate =>
              val _ = Files.deleteIfExists(candidate)
            )
        finally stream.close()
    }.ignore

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
