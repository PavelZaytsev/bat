package bat.runner

import bat.protocol.BatError

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

import zio.ZIO
import zio.Ref
import zio.test.*

object LiveJavaEnvironmentSpec extends ZIOSpecDefault:
  def spec =
    suite("live Java environment")(
      test("requires explicit deployment, attempt, runtime, and host pins") {
        for
          fixture <- paths("bat-live-env-valid-")
          parsed = LiveJavaEnvironment.from(values(fixture))
          verified <- ZIO
            .fromEither(parsed)
            .flatMap(LiveJavaPreflight.verify)
            .either
          otherVerified <- ZIO
            .fromEither(
              LiveJavaEnvironment.from(
                values(fixture).updated("BAT_LIVE_NODE_COUNT", "2")
              )
            )
            .flatMap(LiveJavaPreflight.verify)
            .either
          endpointVerified <- ZIO
            .fromEither(
              LiveJavaEnvironment.from(
                values(fixture).updated(
                  "BAT_LIVE_ENDPOINT",
                  "http://other-endpoint.invalid:52415"
                )
              )
            )
            .flatMap(LiveJavaPreflight.verify)
            .either
          apacheVerified <- ZIO
            .fromEither(
              LiveJavaEnvironment.from(
                values(fixture)
                  .updated("BAT_LIVE_CASE", "apache")
                  .updated("BAT_LIVE_ORACLE", fixture.oraclePatch.toString)
              )
            )
            .flatMap(LiveJavaPreflight.verify)
            .either
        yield assertTrue(
          parsed.exists(_.attemptId.value == "attempt-001"),
          parsed.exists(_.previousAttempt.isEmpty),
          parsed.exists(_.nodeCount == 3L),
          parsed.exists(_.uid == 501),
          parsed.exists(_.gid == 20),
          parsed.exists(_.runtime == "exo"),
          parsed.exists(_.topology == "mlx_ring"),
          verified.flatMap(_.bindingSha256).exists(_.matches("[0-9a-f]{64}")),
          verified.flatMap(_.bindingSha256) != otherVerified.flatMap(
            _.bindingSha256
          ),
          verified.flatMap(_.bindingSha256) != endpointVerified.flatMap(
            _.bindingSha256
          ),
          verified.flatMap(_.bindingSha256) != apacheVerified.flatMap(
            _.bindingSha256
          ),
          verified.exists(_.caseName == "canary"),
          apacheVerified.exists(_.evaluatorRevision.contains("apache")),
          verified.exists(_.oracleSha256.exists(_.matches("[0-9a-f]{64}"))),
          parsed.exists(_.toString.contains("endpoint=<redacted>")),
          !parsed.exists(_.toString.contains("ENDPOINT_CANARY")),
          verified.isRight
        )
      },
      test("binds every resume to a distinct previous controller attempt") {
        for
          fixture <- paths("bat-live-env-resume-")
          base = values(fixture)
          missing = LiveJavaEnvironment.from(
            base.updated("BAT_LIVE_RESUME", "true")
          )
          unexpected = LiveJavaEnvironment.from(
            base.updated("BAT_LIVE_PREVIOUS_ATTEMPT_ID", "attempt-000")
          )
          valid = LiveJavaEnvironment.from(
            base
              .updated("BAT_LIVE_RESUME", "true")
              .updated("BAT_LIVE_ATTEMPT_ID", "attempt-002")
              .updated("BAT_LIVE_PREVIOUS_ATTEMPT_ID", "attempt-001")
          )
        yield assertTrue(
          missing.isLeft,
          unexpected.isLeft,
          valid.exists(_.resume),
          valid.toOption
            .flatMap(_.previousAttempt)
            .exists(_.value == "attempt-001")
        )
      },
      test("rejects actor-visible oracle and output path overlap") {
        for
          fixture <- paths("bat-live-env-overlap-")
          sourceOracle = fixture.source.resolve("oracle")
          _ <- ZIO.attemptBlocking(Files.createDirectory(sourceOracle))
          oracleConfig = LiveJavaEnvironment.from(
            values(fixture).updated(
              "BAT_LIVE_ORACLE",
              sourceOracle.toString
            )
          )
          oracleResult <- ZIO
            .fromEither(oracleConfig)
            .flatMap(LiveJavaPreflight.verify)
            .either
          externalOutput = fixture.root.resolve("external-output")
          _ <- ZIO.attemptBlocking(Files.createDirectory(externalOutput))
          outputConfig = LiveJavaEnvironment.from(
            values(fixture).updated(
              "BAT_LIVE_OUTPUT",
              externalOutput.toString
            )
          )
          outputResult <- ZIO
            .fromEither(outputConfig)
            .flatMap(LiveJavaPreflight.verify)
            .either
        yield assertTrue(
          oracleResult.isLeft,
          outputResult.isLeft
        )
      },
      test("rejects an oracle whose filesystem type does not match the case") {
        for
          fixture <- paths("bat-live-env-oracle-type-")
          canaryFile <- ZIO
            .fromEither(
              LiveJavaEnvironment.from(
                values(fixture).updated(
                  "BAT_LIVE_ORACLE",
                  fixture.oraclePatch.toString
                )
              )
            )
            .flatMap(LiveJavaPreflight.verify)
            .either
          apacheDirectory <- ZIO
            .fromEither(
              LiveJavaEnvironment.from(
                values(fixture)
                  .updated("BAT_LIVE_CASE", "apache")
                  .updated("BAT_LIVE_ORACLE", fixture.oracle.toString)
              )
            )
            .flatMap(LiveJavaPreflight.verify)
            .either
        yield assertTrue(canaryFile.isLeft, apacheDirectory.isLeft)
      },
      test("rejects implicit or malformed operator measurements") {
        for
          fixture <- paths("bat-live-env-invalid-")
          base = values(fixture)
          missingTopology = LiveJavaEnvironment.from(
            base.removed("BAT_LIVE_TOPOLOGY")
          )
          zeroNodes = LiveJavaEnvironment.from(
            base.updated("BAT_LIVE_NODE_COUNT", "0")
          )
          negativeUid = LiveJavaEnvironment.from(
            base.updated("BAT_LIVE_UID", "-1")
          )
        yield assertTrue(
          missingTopology.isLeft,
          zeroNodes.isLeft,
          negativeUid.isLeft
        )
      },
      test("accepts a trusted CLI reached through a symlink") {
        for
          fixture <- paths("bat-live-env-cli-link-")
          linkedCli <- ZIO.attemptBlocking {
            val path = fixture.root.resolve("oci-cli")
            Files.createSymbolicLink(path, Path.of("/usr/bin/true"))
            path
          }
          parsed = LiveJavaEnvironment.from(
            values(fixture).updated(
              "BAT_LIVE_OCI_RUNTIME",
              linkedCli.toString
            )
          )
          verified <- ZIO
            .fromEither(parsed)
            .flatMap(LiveJavaPreflight.verify)
            .either
        yield assertTrue(verified.isRight)
      },
      test("binds the live launcher to one clean BAT commit") {
        for
          fixture <- paths("bat-live-env-bat-pin-")
          mismatched <- ZIO
            .fromEither(
              LiveJavaEnvironment.from(
                values(fixture).updated("BAT_LIVE_BAT_COMMIT", "f" * 40)
              )
            )
            .flatMap(LiveJavaPreflight.verify)
            .either
          _ <- ZIO.attemptBlocking(
            Files.writeString(fixture.bat.resolve("README.md"), "dirty\n")
          )
          dirty <- ZIO
            .fromEither(LiveJavaEnvironment.from(values(fixture)))
            .flatMap(LiveJavaPreflight.verify)
            .either
        yield assertTrue(
          mismatched.left.exists(_.safeMessage.contains("clean pinned")),
          dirty.left.exists(_.safeMessage.contains("clean pinned"))
        )
      },
      test("live app validates and preflights before opening its runtime") {
        for
          fixture <- paths("bat-live-app-boundary-")
          opened <- Ref.make(false)
          invalid <- LiveJavaProductionApp
            .executeWith(
              values(fixture).removed("BAT_LIVE_ARM"),
              _ =>
                opened.set(true) *> ZIO.fail(
                  BatError.ProtocolViolation("runner should not open")
                )
            )
            .either
          openedAfterInvalid <- opened.get
          valid <- LiveJavaProductionApp
            .executeWith(
              values(fixture),
              _ =>
                opened.set(true) *> ZIO.fail(
                  BatError.ProtocolViolation("fake runtime stopped")
                )
            )
            .either
          openedAfterValid <- opened.get
        yield assertTrue(
          invalid.left.exists(_.safeMessage.contains("missing live")),
          !openedAfterInvalid,
          valid.left.exists(_.safeMessage == "fake runtime stopped"),
          openedAfterValid
        )
      }
    ) @@ TestAspect.sequential

  private final case class Paths(
      root: Path,
      bat: Path,
      source: Path,
      privateRoot: Path,
      output: Path,
      oracle: Path,
      oraclePatch: Path,
      batCommit: String
  )

  private def paths(prefix: String) =
    ZIO.attemptBlocking {
      val root = Files.createTempDirectory(prefix).toRealPath()
      val bat = Files.createDirectory(root.resolve("bat"))
      val source = Files.createDirectory(root.resolve("source"))
      val privateRoot = Files.createDirectory(root.resolve("private"))
      val output = Files.createDirectory(privateRoot.resolve("evidence"))
      val oracle = Files.createDirectory(bat.resolve("oracle"))
      val _ = Files.writeString(
        oracle.resolve("HiddenTest.java"),
        "final class HiddenTest {}\n"
      )
      val oraclePatch = bat.resolve("oracle.patch")
      val _ = Files.writeString(oraclePatch, "diff --git a/a b/a\n")
      val _ = Files.writeString(bat.resolve("README.md"), "fixture\n")
      val mode = PosixFilePermissions.fromString("rwx------")
      Seq(bat, source, privateRoot, output, oracle).foreach { path =>
        val _ = Files.setPosixFilePermissions(path, mode)
      }
      val _ = runGit(bat, "init", "--quiet")
      val _ = runGit(bat, "add", "--all")
      val _ = runGit(
        bat,
        "-c",
        "user.name=BAT Test",
        "-c",
        "user.email=bat-test@example.invalid",
        "commit",
        "--quiet",
        "--message=fixture"
      )
      val commit = runGit(bat, "rev-parse", "--verify", "HEAD^{commit}")
      Paths(
        root,
        bat,
        source,
        privateRoot,
        output,
        oracle,
        oraclePatch,
        commit.trim
      )
    }

  private def runGit(directory: Path, arguments: String*): String =
    val process = ProcessBuilder(
      (Seq("/usr/bin/git", "-C", directory.toString) ++ arguments)*
    ).redirectErrorStream(true).start()
    val output = new String(
      process.getInputStream.readAllBytes(),
      java.nio.charset.StandardCharsets.UTF_8
    )
    if process.waitFor() != 0 then throw IllegalStateException("git failed")
    output

  private def values(paths: Paths): Map[String, String] =
    Map(
      "BAT_LIVE_ARM" -> "issue-25",
      "BAT_LIVE_CASE" -> "canary",
      "BAT_LIVE_ENDPOINT" -> "http://ENDPOINT_CANARY.invalid:52415",
      "BAT_LIVE_MODEL" -> "openai/gpt-oss-120b",
      "BAT_LIVE_MODEL_REVISION" -> "weights-revision-v1",
      "BAT_LIVE_RUNTIME" -> "exo",
      "BAT_LIVE_RUNTIME_REVISION" -> "exo-revision-v1",
      "BAT_LIVE_TEMPLATE_REVISION" -> "harmony-template-v1",
      "BAT_LIVE_QUANTIZATION" -> "mxfp4",
      "BAT_LIVE_TOPOLOGY" -> "mlx_ring",
      "BAT_LIVE_NODE_COUNT" -> "3",
      "BAT_LIVE_REASONING_EFFORT" -> "high",
      "BAT_LIVE_IMAGE" -> s"bat-java@sha256:${"a" * 64}",
      "BAT_LIVE_RESUME" -> "false",
      "BAT_LIVE_ATTEMPT_ID" -> "attempt-001",
      "BAT_LIVE_RUN_ID" -> "live-java-run",
      "BAT_LIVE_REPOSITORY_ID" -> "fixture-repository",
      "BAT_LIVE_BASE_COMMIT" -> ("1" * 40),
      "BAT_LIVE_HEAD_COMMIT" -> ("2" * 40),
      "BAT_LIVE_BAT_COMMIT" -> paths.batCommit,
      "BAT_LIVE_BAT_ROOT" -> paths.bat.toString,
      "BAT_LIVE_SOURCE" -> paths.source.toString,
      "BAT_LIVE_PRIVATE_ROOT" -> paths.privateRoot.toString,
      "BAT_LIVE_OUTPUT" -> paths.output.toString,
      "BAT_LIVE_ORACLE" -> paths.oracle.toString,
      "BAT_LIVE_GIT" -> "/usr/bin/git",
      "BAT_LIVE_OCI_RUNTIME" -> "/usr/bin/true",
      "BAT_LIVE_UID" -> "501",
      "BAT_LIVE_GID" -> "20"
    )
