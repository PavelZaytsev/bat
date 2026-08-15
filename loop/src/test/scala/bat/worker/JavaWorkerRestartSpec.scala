package bat.worker

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.protocol.{BatError, BdrStateView, Revision}
import bat.worker.oci.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object JavaWorkerRestartSpec extends ZIOSpecDefault:
  private val Pins = unsafeWorker(
    PullRequestPins.make(
      "R_base",
      "R_head",
      "PR_81",
      "refs/heads/main",
      "1" * 40,
      "refs/pull/81/head",
      "2" * 40
    )
  )

  private val FirstPatch =
    """diff --git a/src/Main.java b/src/Main.java
      |index 1111111..2222222 100644
      |--- a/src/Main.java
      |+++ b/src/Main.java
      |@@ -1 +1 @@
      |-before
      |+after
      |""".stripMargin

  private val SecondPatch =
    """diff --git a/src/Main.java b/src/Main.java
      |index 2222222..3333333 100644
      |--- a/src/Main.java
      |+++ b/src/Main.java
      |@@ -1 +1 @@
      |-after
      |+final
      |""".stripMargin

  def spec =
    suite("Java worker controller restart")(
      test(
        "reopens a completed authenticated read and isolates the next attempt namespace"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-restart-read-control-")
            workspaces <- temporaryDirectory("bat-restart-read-workspaces-")
            scratch <- temporaryDirectory("bat-restart-read-scratch-")
            run = runId("run-completed-read-restart")
            firstAttempt = attemptId("attempt-001")
            secondAttempt = attemptId("attempt-002")
            firstOperation = OperationId.derive(
              run,
              firstAttempt,
              "provider-call-1",
              "worker_git_status"
            )
            secondOperation = OperationId.derive(
              run,
              secondAttempt,
              "provider-call-1",
              "worker_git_status"
            )
            _ <- prepareWorkspace(control, workspaces, run)
            sandbox <- RestartSandbox.make
            lifecycle <- PersistentBdrLifecycle.make
            config = runtimeConfig(control, workspaces, scratch)
            first <- ZIO.scoped {
              for
                session <- resume(
                  run,
                  firstAttempt,
                  sandbox,
                  lifecycle,
                  config
                )
                initial <- session.currentWorkspace
                result <- session.gitStatus(firstOperation, initial)
                _ <- session.bdr.apply(obj("type" -> Json.Str("advance")))
                bdr <- session.bdr.current
              yield (initial, result, bdr)
            }
            second <- ZIO.scoped {
              for
                session <- resume(
                  run,
                  secondAttempt,
                  sandbox,
                  lifecycle,
                  config
                )
                current <- session.currentWorkspace
                stored <- session.receipt(firstOperation)
                replay <- session.gitStatus(firstOperation, current)
                fresh <- session.gitStatus(secondOperation, current)
                bdr <- session.bdr.current
              yield (current, stored, replay, fresh, bdr)
            }
            executions <- sandbox.executions
            resumeCount <- lifecycle.resumeCount
          yield assertTrue(
            firstOperation != secondOperation,
            !first._2.replayed,
            first._2.receipt.authenticationTag.length == 64,
            first._1 == second._1,
            second._2.contains(first._2.receipt),
            second._3.replayed,
            second._3.receipt == first._2.receipt,
            !second._4.replayed,
            second._4.receipt.operationId == secondOperation,
            executions == Chunk(firstOperation.value, secondOperation.value),
            first._3.revision.value == 1L,
            second._5.revision == first._3.revision,
            second._5.view.stateDigest == first._3.view.stateDigest,
            resumeCount == 2
          )
        }
      },
      test(
        "reopens a completed authenticated mutation without executing it twice"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-restart-patch-control-")
            workspaces <- temporaryDirectory("bat-restart-patch-workspaces-")
            scratch <- temporaryDirectory("bat-restart-patch-scratch-")
            run = runId("run-completed-patch-restart")
            firstAttempt = attemptId("attempt-001")
            secondAttempt = attemptId("attempt-002")
            firstOperation = OperationId.derive(
              run,
              firstAttempt,
              "provider-call-1",
              "worker_apply_patch"
            )
            secondOperation = OperationId.derive(
              run,
              secondAttempt,
              "provider-call-1",
              "worker_apply_patch"
            )
            workspace <- prepareWorkspace(control, workspaces, run)
            sandbox <- RestartSandbox.make
            lifecycle <- PersistentBdrLifecycle.make
            config = runtimeConfig(control, workspaces, scratch)
            first <- ZIO.scoped {
              for
                session <- resume(
                  run,
                  firstAttempt,
                  sandbox,
                  lifecycle,
                  config
                )
                before <- session.currentWorkspace
                result <- session.applyPatch(
                  firstOperation,
                  before,
                  FirstPatch
                )
                after <- session.currentWorkspace
                _ <- session.bdr.apply(obj("type" -> Json.Str("advance")))
                bdr <- session.bdr.current
              yield (before, result, after, bdr)
            }
            second <- ZIO.scoped {
              for
                session <- resume(
                  run,
                  secondAttempt,
                  sandbox,
                  lifecycle,
                  config
                )
                reopened <- session.currentWorkspace
                stored <- session.receipt(firstOperation)
                bdr <- session.bdr.current
                staleReplay <- session
                  .applyPatch(firstOperation, first._1, FirstPatch)
                  .either
                reboundReplay <- session
                  .applyPatch(firstOperation, reopened, FirstPatch)
                  .either
                executionsAfterDuplicates <- sandbox.executions
                distinct <- session.applyPatch(
                  secondOperation,
                  reopened,
                  SecondPatch
                )
                finalWorkspace <- session.currentWorkspace
              yield (
                reopened,
                stored,
                bdr,
                staleReplay,
                reboundReplay,
                executionsAfterDuplicates,
                distinct,
                finalWorkspace
              )
            }
            executions <- sandbox.executions
            source <- ZIO.attemptBlocking(
              Files.readString(
                workspace.repository.resolve("src").resolve("Main.java"),
                StandardCharsets.UTF_8
              )
            )
            resumeCount <- lifecycle.resumeCount
          yield assertTrue(
            firstOperation != secondOperation,
            !first._2.replayed,
            first._2.receipt.authenticationTag.length == 64,
            first._2.receipt.afterRevision.value == 1L,
            first._3 == second._1,
            second._2.contains(first._2.receipt),
            second._3.revision == first._4.revision,
            second._3.view.stateDigest == first._4.view.stateDigest,
            errorCode(second._4).contains("workspace_fingerprint_mismatch"),
            errorCode(second._5).contains("operation_id_conflict"),
            second._6 == Chunk(firstOperation.value),
            !second._7.replayed,
            second._7.receipt.operationId == secondOperation,
            second._8.revision.value == 2L,
            executions == Chunk(firstOperation.value, secondOperation.value),
            source == "final\n",
            resumeCount == 2
          )
        }
      }
    ) @@ TestAspect.sequential

  private def prepareWorkspace(
      control: Path,
      workspaces: Path,
      runId: RunId
  ): ZIO[Any, Throwable | WorkerError, RunWorkspace] =
    for
      allocation <- RunWorkspace.allocate(control, workspaces, runId, Pins)
      _ <- createSyntheticRepository(allocation.repository)
      workspace <- RunWorkspace.seal(allocation)
    yield workspace

  private def resume(
      runId: RunId,
      attemptId: AttemptId,
      sandbox: OciSandbox,
      lifecycle: WorkerBdrLifecycle,
      config: WorkerRuntimeConfig
  ): ZIO[Scope, WorkerError, JavaWorkerSession] =
    JavaWorkerSession.resume(
      runId,
      attemptId,
      FixedAuthority(Pins),
      SafeResumeGit,
      sandbox,
      lifecycle,
      config
    )

  private def createSyntheticRepository(repository: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val src = Files.createDirectories(repository.resolve("src"))
      val _ = Files.writeString(
        src.resolve("Main.java"),
        "before\n",
        StandardCharsets.UTF_8
      )
      val _ = runGit(repository, "init")
      val _ = runGit(repository, "add", "src/Main.java")
      val _ = Files.writeString(
        repository.resolve(".git").resolve("HEAD"),
        Pins.headCommit.value + "\n",
        StandardCharsets.US_ASCII,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
      )
    }

  private def runGit(repository: Path, arguments: String*): String =
    val command = existingGit.toString +: arguments.toList
    val builder = ProcessBuilder(command*)
      .directory(repository.toFile)
      .redirectErrorStream(true)
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1")
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null")
    val process = builder.start()
    process.getOutputStream.close()
    val output = String(
      process.getInputStream.readAllBytes(),
      StandardCharsets.UTF_8
    )
    if process.waitFor() != 0 then
      throw new IllegalStateException(s"Git fixture failed: $output")
    output

  private def existingGit: Path =
    List("/usr/bin/git", "/opt/homebrew/bin/git")
      .map(Path.of(_))
      .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
      .getOrElse(throw new IllegalStateException("Git executable not found"))

  private def runtimeConfig(
      control: Path,
      workspaces: Path,
      scratch: Path
  ): WorkerRuntimeConfig =
    val image = unsafeOci(
      PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
    )
    val limits = unsafeOci(
      OciLimits.make(
        10.seconds,
        1024,
        1024,
        8192L,
        128,
        1024L * 1024 * 1024,
        BigDecimal(2),
        64L * 1024 * 1024,
        16L * 1024 * 1024
      )
    )
    val policy = unsafeWorker(
      JavaBuildPolicy.make(
        "java-v1",
        "/opt/bat/bin/mvn",
        "/opt/bat/bin/gradle"
      )
    )
    unsafeWorker(
      WorkerRuntimeConfig.make(
        control.toAbsolutePath.normalize,
        workspaces.toAbsolutePath.normalize,
        scratch.toAbsolutePath.normalize,
        image,
        limits,
        policy,
        unsafeWorker(
          WorkerStorageLimits.make(
            maxSourceBytes = 1024L * 1024 * 1024,
            maxSourcePaths = 100000L,
            maxCheckoutBytes = 1024L * 1024 * 1024,
            maxCheckoutPaths = 100000L,
            maxTreeMetadataBytes = 4 * 1024 * 1024
          )
        )
      )
    )

  private final case class FixedAuthority(value: PullRequestPins)
      extends PullRequestAuthority:
    def resolve(
        baseRepository: RepositoryId,
        pullRequestId: PullRequestId
    ): IO[WorkerError, PullRequestPins] = ZIO.succeed(value)

  private object SafeResumeGit extends GitRunner:
    def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
      if invocation.arguments == GitConfigurationGuard.inspectionArguments then
        ZIO.succeed(GitResult(0, "core.filemode\u0000"))
      else
        ZIO.fail(
          WorkerError.SourceRejected(
            "unexpected_git_call",
            "restart test expected only Git configuration inspection"
          )
        )

  private final class RestartSandbox private (
      runs: Ref[Chunk[String]]
  ) extends OciSandbox:
    val image: PinnedImage = unsafeOci(
      PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
    )

    def executions: UIO[Chunk[String]] = runs.get

    def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

    def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
      for
        _ <- runs.update(_ :+ request.operationId)
        _ <- ZIO.foreachDiscard(
          request.mounts.find(_.destination.value == "/bat/input")
        )(inputMount =>
          ZIO
            .attemptBlocking {
              val patch = Files.readString(
                inputMount.source.resolve("change.patch"),
                StandardCharsets.UTF_8
              )
              val repository = request.mounts
                .find(_.destination.value == "/bat/repository")
                .map(_.source)
                .getOrElse(
                  throw new IllegalStateException(
                    "authoring repository mount missing"
                  )
                )
              val replacement =
                if patch.contains("+final\n") then "final\n"
                else if patch.contains("+after\n") then "after\n"
                else throw new IllegalStateException("unexpected test patch")
              val _ = Files.writeString(
                repository.resolve("src").resolve("Main.java"),
                replacement,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            .mapError(_ =>
              OciFailure.ProcessFailure(
                "synthetic_patch_failed",
                "synthetic patch execution failed"
              )
            )
        )
      yield successfulResult(request)

  private object RestartSandbox:
    def make: UIO[RestartSandbox] =
      Ref.make(Chunk.empty[String]).map(new RestartSandbox(_))

  private final class PersistentBdrLifecycle private (
      resumes: Ref[Int]
  ) extends WorkerBdrLifecycle:
    def resumeCount: UIO[Int] = resumes.get

    def initialize(
        runId: RunId,
        repository: Path,
        pins: PullRequestPins
    ): IO[WorkerError, BdrSession] =
      ZIO.fail(
        WorkerError.LedgerFailure(
          "unexpected_bdr_initialize",
          "restart test must only resume an existing run"
        )
      )

    def resume(
        runId: RunId,
        repository: Path,
        pins: PullRequestPins
    ): IO[WorkerError, BdrSession] =
      prepareState(repository) *>
        resumes.update(_ + 1).as(session(repository))

    private def session(repository: Path): BdrSession = new BdrSession:
      val engineCommit: String = "e" * 40
      val actor: String = "restart-test"

      def current: IO[BatError, ValidatedBdrState] =
        readRevision(repository).map(value => bdrState(repository, value))

      def checkpoint: IO[BatError, ValidatedBdrState] = current

      def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
        for
          previous <- readRevision(repository)
          value = previous + 1L
          _ <- writeRevision(repository, value)
        yield obj(
          "accepted" -> Json.Bool(true),
          "revision" -> Json.Num(BigDecimal(value))
        )

      def auditSummary: IO[BatError, Json] =
        ZIO.succeed(Json.Arr(Chunk.empty))

      def completionCheck: IO[BatError, Json.Obj] =
        readRevision(repository).map(value =>
          obj(
            "eligible" -> Json.Bool(false),
            "revision" -> Json.Num(BigDecimal(value))
          )
        )

  private object PersistentBdrLifecycle:
    def make: UIO[PersistentBdrLifecycle] =
      Ref.make(0).map(new PersistentBdrLifecycle(_))

  private def prepareState(repository: Path): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val directory = repository.resolve(".bdr")
        val _ = Files.createDirectories(directory)
        val path = directory.resolve("restart-state")
        if !Files.exists(path) then
          val _ = Files.writeString(
            path,
            "0\n",
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          )
        ()
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "test_bdr_open_failed",
          "restart test BDR state could not be opened"
        )
      )

  private def readRevision(repository: Path): IO[BatError, Long] =
    ZIO
      .attemptBlocking(
        Files
          .readString(
            repository.resolve(".bdr").resolve("restart-state"),
            StandardCharsets.US_ASCII
          )
          .trim
          .toLong
      )
      .mapError(_ =>
        BatError.BdrFailure(
          "test_bdr_read_failed",
          "restart test BDR state could not be read"
        )
      )

  private def writeRevision(
      repository: Path,
      value: Long
  ): IO[BatError, Unit] =
    ZIO
      .attemptBlocking {
        val _ = Files.writeString(
          repository.resolve(".bdr").resolve("restart-state"),
          s"$value\n",
          StandardCharsets.US_ASCII,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
      }
      .unit
      .mapError(_ =>
        BatError.BdrFailure(
          "test_bdr_write_failed",
          "restart test BDR state could not be written"
        )
      )

  private def bdrState(repository: Path, value: Long): ValidatedBdrState =
    val revision = unsafeBat(Revision.from(value))
    val view = unsafeBat(
      BdrStateView.make(
        revision,
        "executing",
        obj("action" -> Json.Str("begin_phase")),
        sha256(s"bdr-state-$value".getBytes(StandardCharsets.UTF_8))
      )
    )
    ValidatedBdrState(
      repository,
      Path.of(".bdr", "restart-state"),
      view
    )

  private def successfulResult(request: OciRunRequest): OciRunResult =
    val empty = Chunk.empty[Byte]
    OciRunResult(
      request.operationId,
      OciRunOutcome.Exited(0),
      stream(empty),
      stream(empty),
      0L
    )

  private def stream(bytes: Chunk[Byte]): OciStreamReceipt =
    OciStreamReceipt(
      bytes.length.toLong,
      sha256(bytes.toArray),
      bytes,
      previewTruncated = false
    )

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def runId(value: String): RunId =
    unsafeWorker(RunId.from(value))

  private def attemptId(value: String): AttemptId =
    unsafeWorker(AttemptId.from(value))

  private def errorCode[A](value: Either[WorkerError, A]): Option[String] =
    value.left.toOption.map(_.code)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def unsafeWorker[A](result: Either[WorkerError, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def unsafeOci[A](result: Either[OciFailure, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def unsafeBat[A](result: Either[BatError, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def temporaryDirectory(
      prefix: String
  ): ZIO[Scope, Throwable, Path] =
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
