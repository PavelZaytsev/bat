package bat.probe

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest
import java.util.HexFormat

import scala.jdk.CollectionConverters.*

import bat.protocol.{BudgetLimits, RunMode, RunPins}
import bat.telemetry.*
import bat.transport.Secret

import zio.{Chunk, Duration, Scope, UIO, ZIO}
import zio.test.*

object ProbeArtifactWriterSpec extends ZIOSpecDefault:
  private val Commit = "0123456789abcdef0123456789abcdef01234567"
  private val Endpoint = "https://writer-endpoint-canary.invalid"
  private val Token = "WRITER_TOKEN_CANARY_29fd"

  def spec: Spec[TestEnvironment, Any] =
    suite("probe artifact writer")(
      test(
        "atomically publishes only the fixed exact-byte owner-only files"
      ) {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-success-")
            project = root.resolve("project")
            parent = root.resolve("evidence")
            _ <- makeDirectories(project, parent)
            destination = parent.resolve("run-0001")
            fixture = artifact(destination)
            absentBefore <- exists(destination)
            prepared <- ProbeArtifactWriter.prepare(
              fixture.config.outputDirectory,
              project
            )
            stagingMode <- permissions(prepared.staging)
            _ <- prepared.publish(
              fixture.result,
              Chunk(Endpoint, Token)
            )
            presentAfter <- exists(destination)
            stagingAfter <- exists(prepared.staging)
            names <- children(destination)
            resultBytes <- bytes(destination.resolve("result.json"))
            traceBytes <- bytes(destination.resolve("safe-trace.json"))
            telemetryBytes <- bytes(destination.resolve("telemetry.json"))
            destinationMode <- permissions(destination)
            fileModes <- ZIO.foreach(names)(name =>
              permissions(destination.resolve(name))
            )
            duplicate <- prepared
              .publish(fixture.result, Chunk(Endpoint, Token))
              .either
          yield assertTrue(
            !absentBefore,
            presentAfter,
            !stagingAfter,
            names == Set(
              "result.json",
              "safe-trace.json",
              "telemetry.json"
            ),
            utf8(resultBytes) == fixture.result.canonicalJson,
            utf8(traceBytes) == fixture.result.safeTraceJson,
            utf8(telemetryBytes) == fixture.result.telemetryJson,
            sha256(traceBytes) == fixture.result.safeTraceSha256,
            sha256(telemetryBytes) == fixture.result.telemetrySha256,
            stagingMode == "rwx------",
            destinationMode == "rwx------",
            fileModes.forall(_ == "rw-------"),
            duplicate.left.exists(
              _.code == "probe_artifacts_already_published"
            )
          )
        }
      },
      test("never overwrites an existing destination") {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-existing-")
            project = root.resolve("project")
            parent = root.resolve("evidence")
            destination = parent.resolve("run-0001")
            _ <- makeDirectories(project, parent, destination)
            marker = destination.resolve("operator-owned.txt")
            _ <- ZIO.attemptBlocking(
              Files.writeString(marker, "do-not-replace")
            )
            fixture = artifact(destination)
            result <- ProbeArtifactWriter
              .prepare(fixture.config.outputDirectory, project)
              .either
            markerText <- ZIO.attemptBlocking(Files.readString(marker))
          yield assertTrue(
            result.left.exists(
              _.code == "unsafe_probe_output_directory"
            ),
            markerText == "do-not-replace"
          )
        }
      },
      test("loses a destination race without replacing operator data") {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-race-")
            project = root.resolve("project")
            parent = root.resolve("evidence")
            destination = parent.resolve("run-0001")
            _ <- makeDirectories(project, parent)
            fixture = artifact(destination)
            publication <- ZIO.scoped {
              for
                prepared <- ProbeArtifactWriter.prepare(
                  fixture.config.outputDirectory,
                  project
                )
                _ <- makeDirectories(destination)
                _ <- ZIO.attemptBlocking(
                  Files.writeString(
                    destination.resolve("operator-owned.txt"),
                    "do-not-replace"
                  )
                )
                result <- prepared
                  .publish(fixture.result, Chunk(Endpoint, Token))
                  .either
              yield result
            }
            markerText <- ZIO.attemptBlocking(
              Files.readString(destination.resolve("operator-owned.txt"))
            )
            remaining <- children(parent)
          yield assertTrue(
            publication.left.exists(
              _.code == "probe_artifact_publication_failed"
            ),
            markerText == "do-not-replace",
            !remaining.exists(_.startsWith(".bat-probe-staging-"))
          )
        }
      },
      test("rejects any destination contained by the BAT checkout") {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-project-")
            project = root.resolve("project")
            parent = project.resolve("evidence")
            _ <- makeDirectories(project, parent)
            fixture = artifact(parent.resolve("run-0001"))
            result <- ProbeArtifactWriter
              .prepare(fixture.config.outputDirectory, project)
              .either
          yield assertTrue(
            result.left.exists(
              _.code == "unsafe_probe_output_directory"
            )
          )
        }
      },
      test("rejects a symbolic-link component in the output ancestry") {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-symlink-")
            project = root.resolve("project")
            real = root.resolve("real-evidence")
            nested = real.resolve("nested")
            linked = root.resolve("linked-evidence")
            _ <- makeDirectories(project, nested)
            _ <- ZIO.attemptBlocking(Files.createSymbolicLink(linked, real))
            fixture = artifact(linked.resolve("nested/run-0001"))
            result <- ProbeArtifactWriter
              .prepare(fixture.config.outputDirectory, project)
              .either
          yield assertTrue(
            result.left.exists(
              _.code == "unsafe_probe_output_directory"
            )
          )
        }
      },
      test("rejects a group- or world-writable evidence parent") {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-permissions-")
            project = root.resolve("project")
            parent = root.resolve("evidence")
            _ <- makeDirectories(project, parent)
            _ <- ZIO.attemptBlocking {
              val _ = Files.setPosixFilePermissions(
                parent,
                PosixFilePermissions.fromString("rwxrwxrwx")
              )
            }
            fixture = artifact(parent.resolve("run-0001"))
            result <- ProbeArtifactWriter
              .prepare(fixture.config.outputDirectory, project)
              .either
          yield assertTrue(
            result.left.exists(
              _.code == "unsafe_probe_output_directory"
            )
          )
        }
      },
      test("rejects sensitive-value leaks and removes private staging") {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-writer-leak-")
            project = root.resolve("project")
            parent = root.resolve("evidence")
            _ <- makeDirectories(project, parent)
            destination = parent.resolve("run-0001")
            fixture = artifact(destination)
            leakResult <- ZIO.scoped {
              for
                prepared <- ProbeArtifactWriter.prepare(
                  fixture.config.outputDirectory,
                  project
                )
                result <- prepared
                  .publish(
                    fixture.result,
                    Chunk(
                      Endpoint,
                      Token,
                      fixture.config.identity.modelId,
                      fixture.config.batCommit.value
                    )
                  )
                  .either
              yield result
            }
            destinationExists <- exists(destination)
            remaining <- children(parent)
          yield assertTrue(
            leakResult.left.exists(
              _.code == "probe_artifact_leak_detected"
            ),
            !destinationExists,
            !remaining.exists(_.startsWith(".bat-probe-staging-"))
          )
        }
      }
    ) @@ TestAspect.sequential

  private final case class Fixture(
      config: LiveGptOssProbeConfig,
      result: ProbeResultArtifact
  )

  private def artifact(output: Path): Fixture =
    val config = unsafe(
      LiveGptOssProbeConfig.make(
        endpoint = Endpoint,
        credential = Some(unsafe(Secret.from(Token))),
        modelId = "openai/gpt-oss-20b",
        weightRevision = "weights-2026-08-09",
        runtime = "llama.cpp",
        runtimeRevision = "b6200",
        harmonyTemplateRevision = "harmony-2026-08",
        quantization = "mxfp4",
        topologyClass = "exo_thunderbolt",
        nodeCount = 3L,
        runId = "probe-writer-0001",
        batCommit = Commit,
        outputDirectory = output
      )
    )
    val start = TelemetryEvent.RunStarted(
      RunMode.FullWriter,
      TelemetryRunPins.capture(
        unsafe(
          RunPins.make(
            config.identity,
            reasoningEffort = "high",
            promptVersion = "bdr-probe-v1",
            bdrCommit = config.batCommit.value
          )
        )
      ),
      unsafe(
        BudgetLimits.make(
          maxIterations = 3,
          maxToolCalls = 2,
          maxWallTime = Duration.fromSeconds(600),
          maxTotalTokens = 100000L
        )
      )
    )
    val terminal = TelemetryEvent.RunFailed(
      unsafe(TelemetryCode.from("probe_transport_failed")),
      wallMillis = 25L
    )
    val telemetry = unsafe(
      TelemetryDocument.from(
        config.runId,
        config.deployment,
        Chunk(TelemetryRecord(1L, start), TelemetryRecord(2L, terminal))
      )
    )
    val reason = unsafe(ProbeReasonCode.from("transport_unavailable"))
    val result = unsafe(
      ProbeResultArtifact.make(
        ProbeVerdict.Blocked,
        Some(reason),
        config.batCommit,
        config.deployment,
        None,
        telemetry
      )
    )
    Fixture(config, result)

  private def temporaryDirectory(
      prefix: String
  ): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix).toRealPath())
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        val stream = Files.walk(path)
        try
          stream
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)(using Ordering.Int.reverse)
            .foreach(candidate => {
              val _ = Files.deleteIfExists(candidate)
            })
        finally stream.close()
    }.ignore

  /** Creates an output parent that satisfies the writer's publication boundary.
    * `createDirectories` honours the process umask, and a developer or CI host
    * with `umask 002` would otherwise produce a group-writable parent that the
    * writer correctly refuses. The permissions are therefore set explicitly
    * rather than inherited.
    */
  private def makeDirectories(paths: Path*): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      paths.foreach(path => {
        val _ = Files.createDirectories(path)
        val _ = Files.setPosixFilePermissions(
          path,
          PosixFilePermissions.fromString("rwx------")
        )
      })
    }

  private def exists(path: Path): ZIO[Any, Throwable, Boolean] =
    ZIO.attemptBlocking(Files.exists(path, LinkOption.NOFOLLOW_LINKS))

  private def permissions(path: Path): ZIO[Any, Throwable, String] =
    ZIO.attemptBlocking(
      PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
    )

  private def bytes(path: Path): ZIO[Any, Throwable, Array[Byte]] =
    ZIO.attemptBlocking(Files.readAllBytes(path))

  private def children(path: Path): ZIO[Any, Throwable, Set[String]] =
    ZIO.attemptBlocking {
      val stream = Files.list(path)
      try stream.iterator().asScala.map(_.getFileName.toString).toSet
      finally stream.close()
    }

  private def utf8(bytes: Array[Byte]): String =
    String(bytes, StandardCharsets.UTF_8)

  private def sha256(bytes: Array[Byte]): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes)
      )

  private def unsafe[E, A](value: Either[E, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(String.valueOf(error)),
      identity
    )
