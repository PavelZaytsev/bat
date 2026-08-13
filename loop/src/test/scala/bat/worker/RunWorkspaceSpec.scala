package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object RunWorkspaceSpec extends ZIOSpecDefault:
  private val Pins = unsafe(
    PullRequestPins.make(
      "R_base",
      "R_head",
      "PR_12",
      "refs/heads/main",
      "1" * 40,
      "refs/pull/12/head",
      "2" * 40
    )
  )

  def spec =
    suite("persistent per-run authoring workspace")(
      test("allocates, seals, and resumes one manifest-bound workspace") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (controlRoot, workspaceRoot, _) = roots
            id = runId("run-manifest")
            allocation <- RunWorkspace.allocate(
              controlRoot,
              workspaceRoot,
              id,
              Pins
            )
            _ <- createSyntheticRepository(allocation.repository, "version-one")
            sealedWorkspace <- RunWorkspace.seal(allocation)
            resumed <- RunWorkspace.resume(controlRoot, workspaceRoot, id)
            manifestExists <- ZIO.attemptBlocking(
              Files.isRegularFile(
                controlRoot.resolve(id.value).resolve("workspace.manifest")
              )
            )
          yield assertTrue(
            allocation.repository == workspaceRoot.toAbsolutePath.normalize
              .resolve(id.value)
              .resolve("repository"),
            sealedWorkspace.runId == id,
            sealedWorkspace.pins == Pins,
            sealedWorkspace.initialFingerprint.value.matches("[0-9a-f]{64}"),
            resumed == sealedWorkspace,
            manifestExists
          )
        }
      },
      test("refuses a second allocation for the same run ID") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (controlRoot, workspaceRoot, _) = roots
            id = runId("run-duplicate")
            _ <- RunWorkspace.allocate(controlRoot, workspaceRoot, id, Pins)
            duplicate <- RunWorkspace
              .allocate(controlRoot, workspaceRoot, id, Pins)
              .either
          yield assertTrue(
            errorCode(duplicate).contains("workspace_allocation_failed")
          )
        }
      },
      test("refuses target-supplied BDR state before writing a manifest") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (controlRoot, workspaceRoot, _) = roots
            id = runId("run-target-bdr")
            allocation <- RunWorkspace.allocate(
              controlRoot,
              workspaceRoot,
              id,
              Pins
            )
            _ <- createSyntheticRepository(allocation.repository, "source")
            _ <- ZIO.attemptBlocking {
              val bdr = Files.createDirectories(
                allocation.repository.resolve(".bdr")
              )
              val _ = Files.writeString(
                bdr.resolve("progress.yaml"),
                "attacker-owned tracker"
              )
            }
            result <- RunWorkspace.seal(allocation).either
          yield assertTrue(
            errorCode(result).contains("target_supplied_bdr_state"),
            !Files.exists(
              controlRoot.resolve(id.value).resolve("workspace.manifest")
            )
          )
        }
      },
      test("seals the workspace after trusted BDR initialization") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (controlRoot, workspaceRoot, _) = roots
            id = runId("run-trusted-bdr")
            allocation <- RunWorkspace.allocate(
              controlRoot,
              workspaceRoot,
              id,
              Pins
            )
            _ <- createSyntheticRepository(allocation.repository, "source")
            _ <- RunWorkspace.rejectTargetBdr(allocation.repository)
            _ <- ZIO.attemptBlocking {
              val bdr = Files.createDirectories(
                allocation.repository.resolve(".bdr")
              )
              val _ = Files.writeString(
                bdr.resolve("progress.yaml"),
                "trusted tracker"
              )
              val _ = Files.writeString(
                allocation.repository.resolve(".git").resolve("index"),
                "trusted-engine-refreshed-index",
                StandardCharsets.US_ASCII,
                StandardOpenOption.TRUNCATE_EXISTING
              )
            }
            sealedWorkspace <- RunWorkspace.sealInitialized(allocation)
            resumed <- RunWorkspace.resume(controlRoot, workspaceRoot, id)
            current <- WorkspaceFingerprinting.compute(allocation.repository)
          yield assertTrue(
            sealedWorkspace.initialFingerprint == current,
            resumed == sealedWorkspace
          )
        }
      },
      test("detects manifest truncation on resume") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (controlRoot, workspaceRoot, _) = roots
            id = runId("run-truncated")
            allocation <- RunWorkspace.allocate(
              controlRoot,
              workspaceRoot,
              id,
              Pins
            )
            _ <- createSyntheticRepository(allocation.repository, "source")
            _ <- RunWorkspace.seal(allocation)
            manifest = controlRoot
              .resolve(id.value)
              .resolve("workspace.manifest")
            _ <- ZIO.attemptBlocking {
              val text = Files.readString(manifest, StandardCharsets.US_ASCII)
              val _ = Files.writeString(
                manifest,
                text.stripSuffix("\n"),
                StandardCharsets.US_ASCII,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            resumed <- RunWorkspace
              .resume(controlRoot, workspaceRoot, id)
              .either
          yield assertTrue(
            errorCode(resumed).contains("workspace_resume_failed")
          )
        }
      },
      test("workspace fingerprints change with content drift") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "before")
            before <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                repository.resolve("src").resolve("Main.java"),
                "after",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            after <- WorkspaceFingerprinting.compute(repository)
          yield assertTrue(before != after)
        }
      },
      test("workspace fingerprinting rejects target symlinks") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            outside <- temporaryDirectory("bat-workspace-outside-")
            _ <- createSyntheticRepository(repository, "source")
            _ <- ZIO.attemptBlocking {
              val secret = outside.resolve("secret.txt")
              val _ = Files.writeString(secret, "canary")
              val _ = Files.createSymbolicLink(
                repository.resolve("src").resolve("linked-secret"),
                secret
              )
            }
            result <- WorkspaceFingerprinting.compute(repository).either
          yield assertTrue(
            errorCode(result).contains("workspace_fingerprint_failed")
          )
        }
      },
      test("rejects poisoned workspace Git config before other host Git") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            calls <- Ref.make(Chunk.empty[GitInvocation])
            runner = new GitRunner:
              def run(
                  invocation: GitInvocation
              ): IO[WorkerError, GitResult] =
                calls
                  .update(_ :+ invocation)
                  .as(
                    GitResult(
                      0,
                      "uploadpack.packObjectsHook\u0000core.filemode\u0000"
                    )
                  )
            result <- PinnedWorkspace.verify(repository, Pins, runner).either
            observed <- calls.get
          yield assertTrue(
            errorCode(result).contains("unsafe_git_configuration"),
            observed.map(_.arguments) == Chunk(
              GitConfigurationGuard.inspectionArguments
            )
          )
        }
      },
      test("rejects workspace Git indirection before any host Git") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                repository.resolve(".git").resolve("commondir"),
                "/tmp/untrusted-common-directory\n",
                StandardCharsets.US_ASCII
              )
            }
            calls <- Ref.make(0)
            runner = new GitRunner:
              def run(
                  invocation: GitInvocation
              ): IO[WorkerError, GitResult] =
                calls.update(_ + 1).as(GitResult(0, ""))
            result <- PinnedWorkspace.verify(repository, Pins, runner).either
            observed <- calls.get
          yield assertTrue(
            errorCode(result).contains("unsafe_git_configuration"),
            observed == 0
          )
        }
      }
    ) @@ TestAspect.sequential

  private def workspaceRoots: ZIO[Scope, Throwable, (Path, Path, Path)] =
    for
      control <- temporaryDirectory("bat-run-control-")
      workspaces <- temporaryDirectory("bat-run-workspaces-")
      repository <- temporaryDirectory("bat-run-repository-")
    yield (control, workspaces, repository)

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

  private def runId(value: String): RunId = unsafe(RunId.from(value))

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
