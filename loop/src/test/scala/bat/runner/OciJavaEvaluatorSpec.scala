package bat.runner

import bat.protocol.BatError
import bat.worker.*
import bat.worker.oci.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

import zio.{Chunk, IO, Ref, Scope, UIO, ZIO}
import zio.test.*

object OciJavaEvaluatorSpec extends ZIOSpecDefault:
  private val EmptySha256 =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  private val FinalHead = "f" * 40
  private val Image = unsafe(
    PinnedImage.from(s"bat-evaluator@sha256:${"a" * 64}")
  )
  private val Limits = unsafe(
    OciLimits.make(
      zio.Duration.fromSeconds(30),
      1024,
      1024,
      4096,
      32,
      256L * 1024 * 1024,
      BigDecimal(1),
      16L * 1024 * 1024,
      16L * 1024 * 1024,
      256L * 1024 * 1024
    )
  )

  def spec =
    suite("pinned OCI Java evaluator")(
      test(
        "materializes the authenticated head when the clean source checkout is still at base"
      ) {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            observed <- Ref.make(Option.empty[EvaluationObservation])
            sandbox = InspectingSandbox(observed, OciRunOutcome.Exited(0))
            evaluator <- makePinned(
              fixture,
              sandbox,
              JavaEvaluationProfile.Javac(
                fixture.oracleDirectory,
                "example.HiddenTest",
                "example.PublicTest"
              )
            )
            sourceStateBefore <- ZIO.attemptBlocking(
              Files.readString(fixture.source.resolve("state.txt"))
            )
            report <- ZIO.scoped(evaluator.evaluate(fixture.handoff))
            observation <- observed.get.someOrFail(
              new IllegalStateException("evaluator sandbox was not called")
            )
            preparedTreeSurvived <- ZIO.attemptBlocking(
              Files.exists(
                observation.preparedRoot,
                LinkOption.NOFOLLOW_LINKS
              )
            )
          yield assertTrue(
            sourceStateBefore == "base\n",
            report.passed,
            observation.sourceState == "head\n",
            !observation.gitMetadataPresent,
            observation.deliveryPatch == fixture.patch,
            observation.oracleMarker == "sealed-oracle\n",
            observation.mountAccess == MountAccess.ReadOnly,
            observation.argv == Chunk(
              "/bin/sh",
              "-eu",
              "-c",
              OciJavaEvaluator.JavacScript,
              "bat-evaluate-javac",
              "example.HiddenTest",
              "example.PublicTest"
            ),
            !preparedTreeSurvived
          )
        }
      },
      test("re-verifies the pinned ref immediately before evaluation") {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            observed <- Ref.make(Option.empty[EvaluationObservation])
            sandbox = InspectingSandbox(observed, OciRunOutcome.Exited(0))
            evaluator <- makePinned(
              fixture,
              sandbox,
              JavaEvaluationProfile.Javac(
                fixture.oracleDirectory,
                "example.HiddenTest",
                "example.PublicTest"
              )
            )
            _ <- ZIO.attemptBlocking(
              runGit(
                fixture.git,
                fixture.source,
                "update-ref",
                "refs/heads/bat-head",
                fixture.baseCommit
              )
            )
            result <- ZIO.scoped(evaluator.evaluate(fixture.handoff)).either
            invocation <- observed.get
          yield assertTrue(
            errorCode(result).contains("stale_head_ref"),
            invocation.isEmpty
          )
        }
      },
      test("a failing sealed Maven profile remains a negative control") {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            oraclePatch <- ZIO.attemptBlocking {
              val path = fixture.root.resolve("maven-oracle.patch")
              val _ = Files.writeString(path, "sealed-maven-oracle\n")
              path
            }
            observed <- Ref.make(Option.empty[EvaluationObservation])
            sandbox = InspectingSandbox(observed, OciRunOutcome.Exited(1))
            evaluator <- makePinned(
              fixture,
              sandbox,
              JavaEvaluationProfile.Maven(
                oraclePatch,
                "example.HiddenTest",
                runPublicSuite = false
              )
            )
            report <- ZIO.scoped(evaluator.evaluate(fixture.handoff))
            observation <- observed.get.someOrFail(
              new IllegalStateException("evaluator sandbox was not called")
            )
          yield assertTrue(
            !report.passed,
            observation.oracleMarker == "sealed-maven-oracle\n",
            observation.argv == Chunk(
              "/bin/sh",
              "-eu",
              "-c",
              OciJavaEvaluator.MavenScript,
              "bat-evaluate-maven",
              "example.HiddenTest",
              "0"
            )
          )
        }
      },
      test(
        "seals the bound patch bytes when the handoff path changes during materialization"
      ) {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            observed <- Ref.make(Option.empty[EvaluationObservation])
            sandbox = InspectingSandbox(observed, OciRunOutcome.Exited(0))
            replacement = "replacement-after-validation\n"
            materializer = ReplacingPatchGitRunner(
              fixture.runner,
              fixture.handoff.patchPath,
              replacement
            )
            evaluator <- makePinned(
              fixture,
              sandbox,
              JavaEvaluationProfile.Javac(
                fixture.oracleDirectory,
                "example.HiddenTest",
                "example.PublicTest"
              ),
              materializer
            )
            report <- ZIO.scoped(evaluator.evaluate(fixture.handoff))
            observation <- observed.get.someOrFail(
              new IllegalStateException("evaluator sandbox was not called")
            )
            replacedPath <- ZIO.attemptBlocking(
              Files.readString(fixture.handoff.patchPath)
            )
          yield assertTrue(
            report.passed,
            replacedPath == replacement,
            observation.deliveryPatch == fixture.patch
          )
        }
      },
      test("re-verifies the deterministic oracle digest before evaluation") {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            profile = JavaEvaluationProfile.Javac(
              fixture.oracleDirectory,
              "example.HiddenTest",
              "example.PublicTest"
            )
            first <- JavaEvaluationOracleInspection.inspect(profile)
            second <- JavaEvaluationOracleInspection.inspect(profile)
            observed <- Ref.make(Option.empty[EvaluationObservation])
            sandbox = InspectingSandbox(observed, OciRunOutcome.Exited(0))
            evaluator <- constructPinned(
              fixture,
              sandbox,
              profile,
              fixture.runner,
              first.sha256
            )
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                fixture.oracleDirectory.resolve("marker.txt"),
                "changed-oracle\n",
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            result <- ZIO.scoped(evaluator.evaluate(fixture.handoff)).either
            invocation <- observed.get
          yield assertTrue(
            first.sha256 == second.sha256,
            first.sha256.matches("[0-9a-f]{64}"),
            errorCode(result).contains("evaluator_oracle_digest_mismatch"),
            invocation.isEmpty
          )
        }
      },
      test("oracle inspection rejects Javac and Maven symlinks") {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            _ <- ZIO.attemptBlocking {
              Files.createSymbolicLink(
                fixture.oracleDirectory.resolve("linked.java"),
                Path.of("marker.txt")
              )
            }
            javacResult <- JavaEvaluationOracleInspection
              .inspect(
                JavaEvaluationProfile.Javac(
                  fixture.oracleDirectory,
                  "example.HiddenTest",
                  "example.PublicTest"
                )
              )
              .either
            mavenOracle <- ZIO.attemptBlocking {
              val path = fixture.root.resolve("real-oracle.patch")
              val _ = Files.writeString(path, "oracle\n")
              path
            }
            mavenLink <- ZIO.attemptBlocking(
              Files.createSymbolicLink(
                fixture.root.resolve("linked-oracle.patch"),
                mavenOracle.getFileName
              )
            )
            mavenResult <- JavaEvaluationOracleInspection
              .inspect(
                JavaEvaluationProfile.Maven(
                  mavenLink,
                  "example.HiddenTest",
                  runPublicSuite = false
                )
              )
              .either
          yield assertTrue(
            errorCode(javacResult).contains(
              "evaluator_oracle_inspection_failed"
            ),
            errorCode(mavenResult).contains(
              "evaluator_oracle_inspection_failed"
            )
          )
        }
      },
      test("rejects source, oracle, and scratch path overlap") {
        ZIO.scoped {
          for
            fixture <- repositoryFixture
            observed <- Ref.make(Option.empty[EvaluationObservation])
            sandbox = InspectingSandbox(observed, OciRunOutcome.Exited(0))
            hiddenInSource = fixture.source.resolve("hidden-tests")
            scratchInSource = fixture.source.resolve("evaluator-scratch")
            hiddenResult = OciJavaEvaluator.makePinned(
              sandbox,
              fixture.source,
              fixture.verifier,
              fixture.runner,
              fixture.pins,
              fixture.scratch,
              Limits,
              JavaEvaluationProfile.Javac(
                hiddenInSource,
                "example.HiddenTest",
                "example.PublicTest"
              ),
              "b" * 64,
              "fixture-v1"
            )
            scratchResult = OciJavaEvaluator.makePinned(
              sandbox,
              fixture.source,
              fixture.verifier,
              fixture.runner,
              fixture.pins,
              scratchInSource,
              Limits,
              JavaEvaluationProfile.Javac(
                fixture.oracleDirectory,
                "example.HiddenTest",
                "example.PublicTest"
              ),
              "b" * 64,
              "fixture-v1"
            )
          yield assertTrue(
            protocolViolation(hiddenResult),
            protocolViolation(scratchResult)
          )
        }
      }
    ) @@ TestAspect.sequential

  private final case class RepositoryFixture(
      root: Path,
      source: Path,
      scratch: Path,
      oracleDirectory: Path,
      git: Path,
      runner: GitRunner,
      verifier: PinnedGitSource,
      pins: PullRequestPins,
      baseCommit: String,
      patch: String,
      handoff: ProductionHandoff
  )

  private final case class EvaluationObservation(
      preparedRoot: Path,
      sourceState: String,
      gitMetadataPresent: Boolean,
      deliveryPatch: String,
      oracleMarker: String,
      mountAccess: MountAccess,
      argv: Chunk[String]
  )

  private final class InspectingSandbox(
      observed: Ref[Option[EvaluationObservation]],
      outcome: OciRunOutcome
  ) extends OciSandbox:
    val image: PinnedImage = Image

    def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

    def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
      val capture = ZIO
        .attemptBlocking {
          val mount = request.mounts.head
          val root = mount.source
          val sealedRoot = root.resolve(".bat-evaluator")
          val javacOracle = sealedRoot.resolve("oracle").resolve("marker.txt")
          val mavenOracle = sealedRoot.resolve("oracle.patch")
          EvaluationObservation(
            root,
            Files.readString(root.resolve("state.txt")),
            Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS),
            Files.readString(sealedRoot.resolve("delivery.patch")),
            if Files.exists(javacOracle) then Files.readString(javacOracle)
            else Files.readString(mavenOracle),
            mount.access,
            request.argv
          )
        }
        .mapError(_ =>
          OciFailure.ProcessFailure(
            "fixture_inspection_failed",
            "evaluator fixture could not inspect its mount"
          )
        )
      capture.flatMap(value =>
        observed
          .set(Some(value))
          .as(
            OciRunResult(
              request.operationId,
              outcome,
              emptyReceipt,
              emptyReceipt,
              1L
            )
          )
      )

  private final class ReplacingPatchGitRunner(
      delegate: GitRunner,
      patchPath: Path,
      replacement: String
  ) extends GitRunner:
    private val replaced = new AtomicBoolean(false)

    def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
      val replace =
        if invocation.arguments.headOption.contains("clone") &&
          replaced.compareAndSet(false, true)
        then
          ZIO
            .attemptBlocking {
              val _ = Files.writeString(
                patchPath,
                replacement,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            .mapError(_ =>
              WorkerError.SourceRejected(
                "fixture_patch_replacement_failed",
                "fixture could not replace the delivery patch"
              )
            )
        else ZIO.unit
      replace *> delegate.run(invocation)

  private def repositoryFixture: ZIO[Scope, Throwable, RepositoryFixture] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val git = locateGit()
        val root = Files.createTempDirectory("bat-evaluator-spec-")
        val source = Files.createDirectory(root.resolve("source"))
        val oracle = Files.createDirectory(root.resolve("oracle"))
        val _ = Files.writeString(
          oracle.resolve("marker.txt"),
          "sealed-oracle\n"
        )
        runGit(git, source, "init", "--initial-branch=main", ".")
        runGit(git, source, "config", "user.name", "BAT Fixture")
        runGit(
          git,
          source,
          "config",
          "user.email",
          "bat-fixture@example.invalid"
        )
        val _ = Files.writeString(source.resolve("state.txt"), "base\n")
        runGit(git, source, "add", "state.txt")
        runGit(git, source, "commit", "--quiet", "-m", "base")
        val base = readGit(git, source, "rev-parse", "HEAD").trim
        runGit(
          git,
          source,
          "update-ref",
          "refs/heads/bat-base",
          base
        )
        val _ = Files.writeString(source.resolve("state.txt"), "head\n")
        runGit(git, source, "add", "state.txt")
        runGit(git, source, "commit", "--quiet", "-m", "head")
        val head = readGit(git, source, "rev-parse", "HEAD").trim
        runGit(
          git,
          source,
          "update-ref",
          "refs/heads/bat-head",
          head
        )
        runGit(git, source, "checkout", "--quiet", "--detach", base)
        val patch =
          """diff --git a/state.txt b/state.txt
            |--- a/state.txt
            |+++ b/state.txt
            |@@ -1 +1 @@
            |-head
            |+fixed
            |""".stripMargin
        val patchPath = root.resolve("delivery.patch")
        val patchBytes = patch.getBytes(StandardCharsets.UTF_8)
        val _ = Files.write(patchPath, patchBytes)
        val config = unsafe(GitRunnerConfig.make(git))
        val runner = GitRunner.live(config)
        val pins = unsafe(
          PullRequestPins.make(
            "fixture-repository",
            "fixture-repository",
            "fixture-pr",
            "refs/heads/bat-base",
            base,
            "refs/heads/bat-head",
            head
          )
        )
        val handoff = unsafe(
          ProductionHandoff.make(
            FinalHead,
            patchPath,
            sha256(patchBytes),
            patchBytes.length.toLong
          )
        )
        RepositoryFixture(
          root,
          source,
          root.resolve("scratch"),
          oracle,
          git,
          runner,
          PinnedGitSource.live(runner),
          pins,
          base,
          patch,
          handoff
        )
      }
    )(fixture => removeTree(fixture.root))

  private def makePinned(
      fixture: RepositoryFixture,
      sandbox: OciSandbox,
      profile: JavaEvaluationProfile
  ): IO[BatError, OciJavaEvaluator] =
    makePinned(fixture, sandbox, profile, fixture.runner)

  private def makePinned(
      fixture: RepositoryFixture,
      sandbox: OciSandbox,
      profile: JavaEvaluationProfile,
      materializer: GitRunner
  ): IO[BatError, OciJavaEvaluator] =
    for
      oracle <- JavaEvaluationOracleInspection.inspect(profile)
      evaluator <- constructPinned(
        fixture,
        sandbox,
        profile,
        materializer,
        oracle.sha256
      )
    yield evaluator

  private def constructPinned(
      fixture: RepositoryFixture,
      sandbox: OciSandbox,
      profile: JavaEvaluationProfile,
      materializer: GitRunner,
      expectedOracleSha256: String
  ): IO[BatError, OciJavaEvaluator] =
    ZIO.fromEither(
      OciJavaEvaluator.makePinned(
        sandbox,
        fixture.source,
        fixture.verifier,
        materializer,
        fixture.pins,
        fixture.scratch,
        Limits,
        profile,
        expectedOracleSha256,
        "fixture-v1"
      )
    )

  private def emptyReceipt: OciStreamReceipt =
    OciStreamReceipt(0L, EmptySha256, Chunk.empty, previewTruncated = false)

  private def locateGit(): Path =
    Chunk(Path.of("/usr/bin/git"), Path.of("/opt/homebrew/bin/git"))
      .find(Files.isExecutable(_))
      .getOrElse(throw new IllegalStateException("git is unavailable"))

  private def runGit(
      git: Path,
      cwd: Path,
      arguments: String*
  ): Unit =
    val _ = readGit(git, cwd, arguments*)

  private def readGit(
      git: Path,
      cwd: Path,
      arguments: String*
  ): String =
    val process = new ProcessBuilder((git.toString +: arguments.toList)*)
      .directory(cwd.toFile)
      .redirectErrorStream(true)
      .start()
    process.getOutputStream.close()
    val output = String(
      process.getInputStream.readAllBytes(),
      StandardCharsets.UTF_8
    )
    val exitCode = process.waitFor()
    if exitCode != 0 then
      throw new IllegalStateException(s"git fixture failed: $output")
    output

  private def removeTree(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        val _ = Files.walkFileTree(
          path,
          new SimpleFileVisitor[Path]:
            override def visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              Files.deleteIfExists(file)
              FileVisitResult.CONTINUE

            override def postVisitDirectory(
                directory: Path,
                error: java.io.IOException
            ): FileVisitResult =
              if error != null then throw error
              Files.deleteIfExists(directory)
              FileVisitResult.CONTINUE
        )
    }.ignore

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def errorCode[A](value: Either[BatError, A]): Option[String] =
    value.left.toOption.map(_.code)

  private def protocolViolation(
      value: Either[BatError, OciJavaEvaluator]
  ): Boolean = value.left.toOption.exists(
    _.isInstanceOf[BatError.ProtocolViolation]
  )

  private def unsafe[A](value: Either[?, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.toString),
      identity
    )
