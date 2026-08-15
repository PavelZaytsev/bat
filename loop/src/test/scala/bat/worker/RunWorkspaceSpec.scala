package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import java.util.{Arrays, Base64}

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
              val _ = Files.setLastModifiedTime(
                allocation.repository.resolve("src").resolve("Main.java"),
                futureFileTime
              )
              runGit(allocation.repository, "status", "--porcelain=v2")
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
      test("external Git stat refresh leaves semantic fingerprint stable") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "unchanged")
            before <- WorkspaceFingerprinting.compute(repository)
            rawBefore <- ZIO.attemptBlocking(
              Files.readAllBytes(repository.resolve(".git").resolve("index"))
            )
            _ <- ZIO.attemptBlocking {
              val _ = Files.setLastModifiedTime(
                repository.resolve("src").resolve("Main.java"),
                futureFileTime
              )
              runGit(
                repository,
                "status",
                "--porcelain=v2",
                "--untracked-files=all"
              )
            }
            rawAfter <- ZIO.attemptBlocking(
              Files.readAllBytes(repository.resolve(".git").resolve("index"))
            )
            after <- WorkspaceFingerprinting.compute(repository)
          yield assertTrue(
            !Arrays.equals(rawBefore, rawAfter),
            before == after
          )
        }
      },
      test("detects staged, unstaged, path, mode, flag, and HEAD changes") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            source = repository.resolve("src").resolve("Main.java")
            _ <- createSyntheticRepository(repository, "initial")
            initial <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              Files.writeString(
                source,
                "changed but unstaged",
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            )
            unstaged <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(runGit(repository, "add", "src/Main.java"))
            staged <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(repository, "mv", "src/Main.java", "src/Renamed.java")
            )
            renamed <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(
                repository,
                "update-index",
                "--chmod=+x",
                "src/Renamed.java"
              )
            )
            indexMode <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(
                repository,
                "update-index",
                "--assume-unchanged",
                "src/Renamed.java"
              )
            )
            assumedValid <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(
                repository,
                "update-index",
                "--no-assume-unchanged",
                "src/Renamed.java"
              )
            )
            flagsReset <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(
                repository,
                "update-index",
                "--skip-worktree",
                "src/Renamed.java"
              )
            )
            skipped <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(
                repository,
                "update-index",
                "--no-skip-worktree",
                "src/Renamed.java"
              )
            )
            beforeHead <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking(
              runGit(
                repository,
                "-c",
                "user.name=BAT Test",
                "-c",
                "user.email=bat@example.invalid",
                "commit",
                "--allow-empty",
                "-m",
                "new head"
              )
            )
            newHead <- WorkspaceFingerprinting.compute(repository)
          yield assertTrue(
            initial != unstaged,
            unstaged != staged,
            staged != renamed,
            renamed != indexMode,
            indexMode != assumedValid,
            assumedValid != flagsReset,
            flagsReset != skipped,
            skipped != beforeHead,
            beforeHead != newHead
          )
        }
      },
      test("merge stage changes semantic index identity") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            before <- WorkspaceFingerprinting.compute(repository)
            _ <- ZIO.attemptBlocking {
              val index = repository.resolve(".git").resolve("index")
              val original = Files.readAllBytes(index)
              val content = Arrays.copyOf(original, original.length - 20)
              val flagsOffset = 12 + 40 + 20
              content(flagsOffset) = (content(flagsOffset) | 0x10).toByte
              val checksum = MessageDigest
                .getInstance("SHA-1")
                .digest(content)
              val _ = Files.write(index, content ++ checksum)
            }
            after <- WorkspaceFingerprinting.compute(repository)
          yield assertTrue(before != after)
        }
      },
      test("index serialization v2 and v4 have the same semantic identity") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(
                repository.resolve("src").resolve("More.java"),
                "more",
                StandardCharsets.UTF_8
              )
              runGit(repository, "add", "src/More.java")
            }
            versionTwo <- WorkspaceFingerprinting.compute(repository)
            rawVersionTwo <- ZIO.attemptBlocking(
              Files.readAllBytes(repository.resolve(".git").resolve("index"))
            )
            _ <- ZIO.attemptBlocking(
              runGit(repository, "update-index", "--index-version", "4")
            )
            rawVersionFour <- ZIO.attemptBlocking(
              Files.readAllBytes(repository.resolve(".git").resolve("index"))
            )
            versionFour <- WorkspaceFingerprinting.compute(repository)
          yield assertTrue(
            !Arrays.equals(rawVersionTwo, rawVersionFour),
            versionTwo == versionFour
          )
        }
      },
      test("supports validated SHA-256 repository indexes") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- initializeRepository(repository, "sha256 source", "sha256")
            result <- WorkspaceFingerprinting.compute(repository)
          yield assertTrue(result.value.matches("[0-9a-f]{64}"))
        }
      },
      test("rejects a repository format that does not identify its hash") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            _ <- ZIO.attemptBlocking {
              val config = repository.resolve(".git").resolve("config")
              val text = Files.readString(config, StandardCharsets.UTF_8)
              val _ = Files.writeString(
                config,
                text.replace(
                  "repositoryformatversion = 0",
                  "repositoryformatversion = 1"
                ),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
              )
            }
            result <- WorkspaceFingerprinting.compute(repository).either
          yield assertTrue(
            errorCode(result).contains("workspace_fingerprint_failed")
          )
        }
      },
      test("rejects corrupt checksums and unsupported required extensions") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            index = repository.resolve(".git").resolve("index")
            original <- ZIO.attemptBlocking(Files.readAllBytes(index))
            _ <- ZIO.attemptBlocking {
              val corrupt = original.clone()
              corrupt(12) = (corrupt(12) ^ 1).toByte
              val _ = Files.write(index, corrupt)
            }
            corrupt <- WorkspaceFingerprinting.compute(repository).either
            _ <- ZIO.attemptBlocking {
              val content = Arrays.copyOf(original, original.length - 20)
              val extension = Array[Byte](
                'l'.toByte,
                'i'.toByte,
                'n'.toByte,
                'k'.toByte,
                0,
                0,
                0,
                0
              )
              val withExtension = content ++ extension
              val checksum = MessageDigest
                .getInstance("SHA-1")
                .digest(withExtension)
              val _ = Files.write(index, withExtension ++ checksum)
            }
            required <- WorkspaceFingerprinting.compute(repository).either
          yield assertTrue(
            errorCode(corrupt).contains("workspace_fingerprint_failed"),
            errorCode(required).contains("workspace_fingerprint_failed")
          )
        }
      },
      test("rejects unsupported optional index extension state") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (_, _, repository) = roots
            _ <- createSyntheticRepository(repository, "source")
            _ <- ZIO.attemptBlocking {
              val index = repository.resolve(".git").resolve("index")
              val original = Files.readAllBytes(index)
              val content = Arrays.copyOf(original, original.length - 20)
              val extension = Array[Byte](
                'X'.toByte,
                'B'.toByte,
                'A'.toByte,
                'T'.toByte,
                0,
                0,
                0,
                1,
                1
              )
              val withExtension = content ++ extension
              val checksum = MessageDigest
                .getInstance("SHA-1")
                .digest(withExtension)
              val _ = Files.write(index, withExtension ++ checksum)
            }
            result <- WorkspaceFingerprinting.compute(repository).either
          yield assertTrue(
            errorCode(result).contains("workspace_fingerprint_failed")
          )
        }
      },
      test("old raw-index fingerprint manifests cannot resume silently") {
        ZIO.scoped {
          for
            roots <- workspaceRoots
            (controlRoot, workspaceRoot, _) = roots
            id = runId("run-v1-migration")
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
              val lines = Files
                .readString(manifest, StandardCharsets.US_ASCII)
                .linesIterator
                .toList
              val oldVersion =
                Base64.getUrlEncoder.withoutPadding.encodeToString(
                  "bat-workspace-v1".getBytes(StandardCharsets.UTF_8)
                )
              val _ = Files.writeString(
                manifest,
                (oldVersion :: lines.tail).mkString("\n") + "\n",
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
    initializeRepository(repository, source, "sha1")

  private def initializeRepository(
      repository: Path,
      source: String,
      objectFormat: String
  ): Task[Unit] =
    ZIO.attemptBlocking {
      val src = Files.createDirectories(repository.resolve("src"))
      val _ = Files.writeString(
        src.resolve("Main.java"),
        source,
        StandardCharsets.UTF_8
      )
      val _ = runGit(repository, "init", s"--object-format=$objectFormat")
      val _ = runGit(repository, "add", "src/Main.java")
      val _ = runGit(
        repository,
        "-c",
        "user.name=BAT Test",
        "-c",
        "user.email=bat@example.invalid",
        "commit",
        "-m",
        "initial"
      )
      val _ = runGit(repository, "checkout", "--detach", "HEAD")
    }

  private def runGit(repository: Path, arguments: String*): String =
    val command = existingGit.toString +: arguments.toList
    val builder = ProcessBuilder(command*)
      .directory(repository.toFile)
      .redirectErrorStream(true)
    val environment = builder.environment()
    environment.put("GIT_CONFIG_NOSYSTEM", "1")
    environment.put("GIT_CONFIG_GLOBAL", "/dev/null")
    val _ = environment.remove("GIT_OPTIONAL_LOCKS")
    val process = builder.start()
    process.getOutputStream.close()
    val output = String(
      process.getInputStream.readAllBytes(),
      StandardCharsets.UTF_8
    )
    val exitCode = process.waitFor()
    if exitCode != 0 then
      throw new IllegalStateException(s"Git fixture failed: $output")
    output

  private def existingGit: Path =
    List("/usr/bin/git", "/opt/homebrew/bin/git")
      .map(Path.of(_))
      .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
      .getOrElse(throw new IllegalStateException("Git executable not found"))

  private def futureFileTime: FileTime =
    FileTime.fromMillis(
      java.lang.System.currentTimeMillis() + 24L * 60 * 60 * 1000
    )

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
