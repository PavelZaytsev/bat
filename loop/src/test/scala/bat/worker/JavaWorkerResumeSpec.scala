package bat.worker

import bat.bdr.BdrSession
import bat.worker.oci.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object JavaWorkerResumeSpec extends ZIOSpecDefault:
  private val Pins = unsafe(
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

  def spec =
    suite("Java worker resume")(
      test("rejects authoring workspace drift before opening BDR") {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-resume-control-")
            workspaces <- temporaryDirectory("bat-resume-workspaces-")
            scratch <- temporaryDirectory("bat-resume-scratch-")
            id = runId("run-resume-drift")
            allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
            _ <- createSyntheticRepository(allocation.repository, "before")
            _ <- RunWorkspace.seal(allocation)
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                allocation.repository.resolve("src").resolve("Main.java"),
                "after",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            bdrCalls <- Ref.make(0)
            sandboxCalls <- Ref.make(0)
            gitCalls <- Ref.make(0)
            lifecycle = unexpectedBdr(bdrCalls)
            sandbox = unexpectedSandbox(sandboxCalls)
            config = runtimeConfig(control, workspaces, scratch)
            result <- JavaWorkerSession
              .resume(
                id,
                FixedAuthority(Pins),
                unexpectedGit(gitCalls),
                sandbox,
                lifecycle,
                config
              )
              .either
            observedBdrCalls <- bdrCalls.get
            observedSandboxCalls <- sandboxCalls.get
            observedGitCalls <- gitCalls.get
          yield assertTrue(
            errorCode(result).contains("resume_workspace_mismatch"),
            observedBdrCalls == 0,
            observedSandboxCalls == 0,
            observedGitCalls == 0
          )
        }
      },
      test("rejects a remotely changed pin after local recovery checks") {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-resume-stale-control-")
            workspaces <- temporaryDirectory("bat-resume-stale-workspaces-")
            scratch <- temporaryDirectory("bat-resume-stale-scratch-")
            id = runId("run-resume-stale")
            allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
            _ <- createSyntheticRepository(allocation.repository, "source")
            _ <- RunWorkspace.seal(allocation)
            changedPins = unsafe(
              PullRequestPins.make(
                Pins.baseRepository.value,
                Pins.headRepository.value,
                Pins.pullRequestId.value,
                Pins.baseRef.value,
                Pins.baseCommit.value,
                Pins.headRef.value,
                "3" * 40
              )
            )
            bdrCalls <- Ref.make(0)
            sandboxCalls <- Ref.make(0)
            gitCalls <- Ref.make(0)
            result <- JavaWorkerSession
              .resume(
                id,
                FixedAuthority(changedPins),
                unexpectedGit(gitCalls),
                unexpectedSandbox(sandboxCalls),
                unexpectedBdr(bdrCalls),
                runtimeConfig(control, workspaces, scratch)
              )
              .either
            observedBdrCalls <- bdrCalls.get
          yield assertTrue(
            errorCode(result).contains("stale_pr_input"),
            observedBdrCalls == 0
          )
        }
      },
      test(
        "cleans and durably recovers a read-only intent before checking remote freshness"
      ) {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-resume-read-control-")
            workspaces <- temporaryDirectory("bat-resume-read-workspaces-")
            scratch <- temporaryDirectory("bat-resume-read-scratch-")
            id = runId("run-resume-read-recovery")
            allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
            _ <- createSyntheticRepository(allocation.repository, "source")
            workspace <- RunWorkspace.seal(allocation)
            initial <- initialWorkspace(workspace.initialFingerprint)
            config = runtimeConfig(control, workspaces, scratch)
            operation = workerOperation(
              "interrupted-read",
              WorkerOperationKind.Read,
              initial,
              config
            )
            _ <- seedPending(control, id, initial, operation)
            changedPins = unsafe(
              PullRequestPins.make(
                Pins.baseRepository.value,
                Pins.headRepository.value,
                Pins.pullRequestId.value,
                Pins.baseRef.value,
                Pins.baseCommit.value,
                Pins.headRef.value,
                "3" * 40
              )
            )
            cleanupIds <- Ref.make(Chunk.empty[String])
            runCalls <- Ref.make(0)
            gitCalls <- Ref.make(0)
            bdrCalls <- Ref.make(0)
            result <- ZIO.scoped(
              JavaWorkerSession
                .resume(
                  id,
                  FixedAuthority(changedPins),
                  unexpectedGit(gitCalls),
                  recordingSandbox(config.image, cleanupIds, runCalls),
                  unexpectedBdr(bdrCalls),
                  config
                )
                .either
            )
            recovered <- ZIO.scoped {
              WorkerLedger
                .open(control, id, initial)
                .flatMap(ledger =>
                  ledger.pendingOperation.zip(ledger.currentWorkspace.either)
                )
            }
            observedCleanup <- cleanupIds.get
            observedRuns <- runCalls.get
            observedGit <- gitCalls.get
            observedBdr <- bdrCalls.get
          yield assertTrue(
            errorCode(result).contains("stale_pr_input"),
            observedCleanup == Chunk(operation.id.value),
            observedRuns == 0,
            observedGit == 0,
            observedBdr == 0,
            recovered._1.isEmpty,
            recovered._2 == Right(initial)
          )
        }
      },
      test("cleans but never recovers an interrupted mutation") {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-resume-mutation-control-")
            workspaces <- temporaryDirectory(
              "bat-resume-mutation-workspaces-"
            )
            scratch <- temporaryDirectory("bat-resume-mutation-scratch-")
            id = runId("run-resume-mutation")
            allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
            _ <- createSyntheticRepository(allocation.repository, "source")
            workspace <- RunWorkspace.seal(allocation)
            initial <- initialWorkspace(workspace.initialFingerprint)
            config = runtimeConfig(control, workspaces, scratch)
            operation = workerOperation(
              "interrupted-patch",
              WorkerOperationKind.Patch,
              initial,
              config
            )
            _ <- seedPending(control, id, initial, operation)
            cleanupIds <- Ref.make(Chunk.empty[String])
            runCalls <- Ref.make(0)
            gitCalls <- Ref.make(0)
            bdrCalls <- Ref.make(0)
            result <- ZIO.scoped(
              JavaWorkerSession
                .resume(
                  id,
                  FixedAuthority(Pins),
                  unexpectedGit(gitCalls),
                  recordingSandbox(config.image, cleanupIds, runCalls),
                  unexpectedBdr(bdrCalls),
                  config
                )
                .either
            )
            stillPending <- ZIO.scoped {
              WorkerLedger
                .open(control, id, initial)
                .flatMap(ledger =>
                  ledger.pendingOperation.zip(ledger.currentWorkspace.either)
                )
            }
            observedCleanup <- cleanupIds.get
            observedRuns <- runCalls.get
            observedGit <- gitCalls.get
            observedBdr <- bdrCalls.get
          yield assertTrue(
            errorCode(result).contains("indeterminate_operation"),
            observedCleanup == Chunk(operation.id.value),
            observedRuns == 0,
            observedGit == 0,
            observedBdr == 0,
            stillPending._1.contains(operation),
            errorCode(stillPending._2).contains("indeterminate_operation")
          )
        }
      },
      test("revalidates local Git configuration before opening BDR") {
        ZIO.scoped {
          for
            control <- temporaryDirectory("bat-resume-config-control-")
            workspaces <- temporaryDirectory("bat-resume-config-workspaces-")
            scratch <- temporaryDirectory("bat-resume-config-scratch-")
            id = runId("run-resume-config")
            allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
            _ <- createSyntheticRepository(allocation.repository, "source")
            _ <- RunWorkspace.seal(allocation)
            config = runtimeConfig(control, workspaces, scratch)
            cleanupIds <- Ref.make(Chunk.empty[String])
            runCalls <- Ref.make(0)
            gitCalls <- Ref.make(0)
            bdrCalls <- Ref.make(0)
            unsafeGit = new GitRunner:
              def run(
                  invocation: GitInvocation
              ): IO[WorkerError, GitResult] =
                gitCalls
                  .update(_ + 1)
                  .as(GitResult(0, "filter.host.clean\u0000"))
            result <- ZIO.scoped(
              JavaWorkerSession
                .resume(
                  id,
                  FixedAuthority(Pins),
                  unsafeGit,
                  recordingSandbox(config.image, cleanupIds, runCalls),
                  unexpectedBdr(bdrCalls),
                  config
                )
                .either
            )
            observedCleanup <- cleanupIds.get
            observedRuns <- runCalls.get
            observedGit <- gitCalls.get
            observedBdr <- bdrCalls.get
          yield assertTrue(
            errorCode(result).contains("unsafe_git_configuration"),
            observedCleanup.isEmpty,
            observedRuns == 0,
            observedGit == 1,
            observedBdr == 0
          )
        }
      }
    ) @@ TestAspect.sequential

  private def runtimeConfig(
      control: Path,
      workspaces: Path,
      scratch: Path
  ): WorkerRuntimeConfig =
    val image = unsafeOci(
      PinnedImage.from(
        "ghcr.io/bat/java-worker@sha256:" + ("a" * 64)
      )
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
    val policy = unsafe(
      JavaBuildPolicy.make(
        "java-v1",
        "/opt/bat/bin/mvn",
        "/opt/bat/bin/gradle"
      )
    )
    unsafe(
      WorkerRuntimeConfig.make(
        control.toAbsolutePath.normalize,
        workspaces.toAbsolutePath.normalize,
        scratch.toAbsolutePath.normalize,
        image,
        limits,
        policy,
        unsafe(
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

  private def unexpectedBdr(
      calls: Ref[Int]
  ): WorkerBdrLifecycle =
    val failure = WorkerError.ToolFailure(
      "unexpected_bdr_call",
      "BDR must not open after failed resume validation"
    )
    WorkerBdrLifecycle.make(
      (_, _, _) => calls.update(_ + 1) *> ZIO.fail(failure),
      (_, _, _) => calls.update(_ + 1) *> ZIO.fail(failure)
    )

  private def unexpectedSandbox(calls: Ref[Int]): OciSandbox =
    new OciSandbox:
      val image: PinnedImage = unsafeOci(
        PinnedImage.from(
          "ghcr.io/bat/java-worker@sha256:" + ("a" * 64)
        )
      )

      def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

      def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
        calls.update(_ + 1) *> ZIO.fail(
          OciFailure.ProcessFailure(
            "unexpected_sandbox_call",
            "sandbox must not run while resuming"
          )
        )

  private def recordingSandbox(
      workerImage: PinnedImage,
      cleanupIds: Ref[Chunk[String]],
      runCalls: Ref[Int]
  ): OciSandbox =
    new OciSandbox:
      val image: PinnedImage = workerImage

      def cleanup(operationId: String): IO[OciFailure, Unit] =
        cleanupIds.update(_ :+ operationId)

      def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
        runCalls.update(_ + 1) *> ZIO.fail(
          OciFailure.ProcessFailure(
            "unexpected_sandbox_call",
            "sandbox must not run while resuming"
          )
        )

  private def unexpectedGit(calls: Ref[Int]): GitRunner =
    new GitRunner:
      def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
        calls.update(_ + 1) *> ZIO.fail(
          WorkerError.SourceRejected(
            "unexpected_git_call",
            "Git must not run while validating resume state"
          )
        )

  private def createSyntheticRepository(
      repository: Path,
      source: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val git = Files.createDirectories(repository.resolve(".git"))
      val src = Files.createDirectories(repository.resolve("src"))
      val _ = Files.writeString(
        git.resolve("HEAD"),
        Pins.headCommit.value + "\n",
        StandardCharsets.US_ASCII
      )
      val _ = Files.writeString(
        git.resolve("config"),
        "[core]\n\trepositoryformatversion = 0\n",
        StandardCharsets.US_ASCII
      )
      val _ = Files.write(
        git.resolve("index"),
        "synthetic-index-v1".getBytes(StandardCharsets.US_ASCII)
      )
      val _ = Files.writeString(
        src.resolve("Main.java"),
        source,
        StandardCharsets.UTF_8
      )
    }

  private def initialWorkspace(
      fingerprint: WorkspaceFingerprint
  ): IO[WorkerError, WorkspacePrecondition] =
    ZIO
      .fromEither(WorkspaceRevision.from(0L))
      .map(revision => WorkspacePrecondition(revision, fingerprint))

  private def workerOperation(
      id: String,
      kind: WorkerOperationKind,
      workspace: WorkspacePrecondition,
      config: WorkerRuntimeConfig
  ): WorkerOperation =
    unsafe(
      WorkerOperation.make(
        unsafe(OperationId.from(id)),
        kind,
        "9" * 64,
        s"resume-test-v1:${kind.wire}",
        workspace,
        "java-v1",
        Some(config.imageDigest)
      )
    )

  private def seedPending(
      control: Path,
      runId: RunId,
      initial: WorkspacePrecondition,
      operation: WorkerOperation
  ): ZIO[Any, WorkerError, Unit] =
    ZIO.scoped {
      WorkerLedger
        .open(control, runId, initial)
        .flatMap(
          _.execute(operation)(
            ZIO.fail(
              WorkerError.ToolFailure(
                "simulated_interruption",
                "simulated interrupted worker operation"
              )
            )
          ).either.unit
        )
    }

  private def runId(value: String): RunId = unsafe(RunId.from(value))

  private def errorCode[A](result: Either[WorkerError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def unsafe[A](result: Either[WorkerError, A]): A =
    result.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def unsafeOci[A](result: Either[OciFailure, A]): A =
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

  private final case class FixedAuthority(value: PullRequestPins)
      extends PullRequestAuthority:
    def resolve(
        baseRepository: RepositoryId,
        pullRequestId: PullRequestId
    ): IO[WorkerError, PullRequestPins] = ZIO.succeed(value)
