package bat.worker.oci

import java.nio.file.Path
import java.security.MessageDigest

import zio.*
import zio.test.*

object OciSandboxSpec extends ZIOSpecDefault:
  private val Digest = "ab" * 32
  private val Runtime = Path.of("/opt/bat/bin/docker")
  private val LauncherCwd = Path.of("/var/empty/bat-launcher")

  def spec =
    suite("OciSandbox")(
      test(
        "builds the exact fixed-profile OCI argv and sanitized launcher environment"
      ) {
        for
          image <- fromEither(
            PinnedImage.from(s"ghcr.io/bat/java-worker@sha256:$Digest")
          )
          config <- fromEither(
            OciSandboxConfig.make(Runtime, image, LauncherCwd, 10001, 10002)
          )
          workspace <- containerPath("/workspace")
          cache <- containerPath("/cache/m2")
          workdir <- containerPath("/workspace/project")
          workspaceMount <- bind(
            "/worktrees/pr-42",
            workspace,
            MountAccess.ReadWrite
          )
          cacheMount <- bind("/opt/bat/cache/m2", cache, MountAccess.ReadOnly)
          limits <- standardLimits
          request <- fromEither(
            OciRunRequest.make(
              "pr-42-compile",
              Chunk("/opt/bat/bin/mvn", "-q", "test"),
              Chunk(workspaceMount, cacheMount),
              workdir,
              limits
            )
          )
          runner <- RecordingRunner.make
          sandbox = OciSandbox.live(config, runner)
          result <- sandbox.run(request)
          calls <- runner.calls
          invocation = calls.head
          expected = Chunk(
            Runtime.toString,
            "run",
            "--rm",
            s"--name=${OciSandbox.runtimeResourceName(config, request)}",
            "--log-driver=none",
            "--init",
            "--stop-timeout=1",
            "--pull=never",
            "--network=none",
            "--read-only",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--pids-limit=256",
            "--memory=2147483648",
            "--memory-swap=2147483648",
            "--cpus=2.5",
            "--user=10001:10002",
            "--ipc=none",
            "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=67108864,mode=1777",
            "--tmpfs=/home/bat:rw,noexec,nosuid,nodev,size=16777216,uid=10001,gid=10002,mode=0700",
            "--workdir=/workspace/project",
            "--env=HOME=/home/bat",
            "--env=TMPDIR=/tmp",
            "--env=LANG=C.UTF-8",
            "--env=LC_ALL=C.UTF-8",
            "--env=TZ=UTC",
            "--env=GIT_CONFIG_NOSYSTEM=1",
            "--env=GIT_CONFIG_GLOBAL=/dev/null",
            "--env=GIT_TERMINAL_PROMPT=0",
            "--env=GIT_OPTIONAL_LOCKS=0",
            "--env=GIT_LFS_SKIP_SMUDGE=1",
            "--env=GIT_NO_REPLACE_OBJECTS=1",
            "--entrypoint=/opt/bat/bin/mvn",
            "--label=bat.operation-id=pr-42-compile",
            "--mount",
            "type=bind,src=/worktrees/pr-42,dst=/workspace",
            "--mount",
            "type=bind,src=/opt/bat/cache/m2,dst=/cache/m2,readonly",
            s"ghcr.io/bat/java-worker@sha256:$Digest",
            "-q",
            "test"
          )
          forbiddenKeys = Set(
            "PATH",
            "SSH_AUTH_SOCK",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY",
            "AWS_SECRET_ACCESS_KEY"
          )
        yield assertTrue(
          calls.size == 1,
          invocation.operationId == "pr-42-compile",
          invocation.argv == expected,
          invocation.cwd == LauncherCwd,
          invocation.environment == Map(
            "HOME" -> "/nonexistent",
            "DOCKER_CONFIG" -> "/nonexistent",
            "LANG" -> "C",
            "LC_ALL" -> "C",
            "TZ" -> "UTC"
          ),
          invocation.cleanup.exists(cleanup =>
            cleanup.removeArgv == Chunk(
              Runtime.toString,
              "rm",
              "--force",
              OciSandbox.runtimeResourceName(config, request)
            ) && cleanup.exactName == OciSandbox.runtimeResourceName(
              config,
              request
            )
          ),
          invocation.environment.keySet.intersect(forbiddenKeys).isEmpty,
          invocation.timeout == 12.seconds,
          invocation.stdoutPreviewBytes == 1024,
          invocation.stderrPreviewBytes == 2048,
          invocation.outputLimitBytes == 8192L,
          result.operationId == "pr-42-compile",
          result.outcome == OciRunOutcome.Exited(0)
        )
      },
      test(
        "binds runtime names only to validated operation IDs across config changes"
      ) {
        for
          firstImage <- fromEither(
            PinnedImage.from(s"registry-one/bat@sha256:$Digest")
          )
          secondImage <- fromEither(
            PinnedImage.from(s"registry-two/bat@sha256:${"cd" * 32}")
          )
          first <- fromEither(
            OciSandboxConfig.make(
              Runtime,
              firstImage,
              LauncherCwd,
              10001,
              10002
            )
          )
          second <- fromEither(
            OciSandboxConfig.make(
              Path.of("/opt/bat/bin/podman"),
              secondImage,
              Path.of("/var/empty/changed-launcher"),
              20001,
              20002
            )
          )
          runner <- RecordingRunner.make
          invalid <- OciSandbox
            .live(second, runner)
            .cleanup("INVALID operation")
            .either
          nullOperationId <- OciSandbox
            .live(second, runner)
            .cleanup(null)
            .either
          firstName = OciSandbox.runtimeResourceName(
            first,
            "stable-operation"
          )
          changedConfigName = OciSandbox.runtimeResourceName(
            second,
            "stable-operation"
          )
          differentOperationName = OciSandbox.runtimeResourceName(
            second,
            "other-operation"
          )
        yield assertTrue(
          firstName == changedConfigName,
          firstName != differentOperationName,
          firstName.matches("bat-[0-9a-f]{48}"),
          invalid.left.toOption.exists(_.code == "invalid_operation_id"),
          nullOperationId.left.toOption.exists(
            _.code == "invalid_operation_id"
          )
        )
      },
      test("stages a read-only source in a bounded writable tmpfs") {
        for
          image <- fromEither(
            PinnedImage.from(s"ghcr.io/bat/java-worker@sha256:$Digest")
          )
          config <- fromEither(
            OciSandboxConfig.make(Runtime, image, LauncherCwd, 10001, 10002)
          )
          source <- containerPath("/bat/source")
          workdir <- containerPath("/bat/run")
          sourceMount <- bind(
            "/worktrees/pr-42",
            source,
            MountAccess.ReadOnly
          )
          limits <- standardLimits
          request <- fromEither(
            OciRunRequest.makeStaged(
              "pr-42-build",
              Chunk("/opt/bat/bin/mvn", "-o", "test"),
              Chunk(sourceMount),
              workdir,
              limits
            )
          )
          runner <- RecordingRunner.make
          _ <- OciSandbox.live(config, runner).run(request)
          invocation <- runner.calls.map(_.head)
          runTmpfs =
            "--tmpfs=/bat/run:rw,nosuid,nodev,size=4294967296,uid=10001,gid=10002,mode=0700"
          sourceBinding =
            "type=bind,src=/worktrees/pr-42,dst=/bat/source,readonly"
          imageIndex = invocation.argv.indexOf(image.value)
        yield assertTrue(
          invocation.argv.contains(runTmpfs),
          invocation.argv.contains(sourceBinding),
          !invocation.argv.contains(
            "type=bind,src=/worktrees/pr-42,dst=/bat/source"
          ),
          invocation.argv.contains("--workdir=/bat/run"),
          invocation.argv.contains("--entrypoint=/bin/sh"),
          imageIndex > 0,
          invocation.argv.drop(imageIndex + 1).take(4) == Chunk(
            "-eu",
            "-c",
            OciSandbox.StagedWorkspaceScript,
            "bat-stage"
          ),
          invocation.argv.takeRight(3) == Chunk(
            "/opt/bat/bin/mvn",
            "-o",
            "test"
          ),
          OciSandbox.StagedWorkspaceScript.endsWith("exec \"$@\"")
        )
      },
      test("rejects mutable policy inputs before invoking the runtime") {
        for
          pinned <- ZIO.succeed(
            PinnedImage.from(s"worker:latest@sha256:$Digest")
          )
          unpinned <- ZIO.succeed(PinnedImage.from("worker:latest"))
          uppercase <- ZIO.succeed(
            PinnedImage.from(s"worker@sha256:${Digest.toUpperCase}")
          )
          relativeRuntime <- fromEither(pinned).map(image =>
            OciSandboxConfig.make(
              Path.of("docker"),
              image,
              LauncherCwd,
              10001,
              10001
            )
          )
          rootUser <- fromEither(pinned).map(image =>
            OciSandboxConfig.make(Runtime, image, LauncherCwd, 0, 10001)
          )
          nullImage = OciSandboxConfig.make(
            Runtime,
            null.asInstanceOf[PinnedImage],
            LauncherCwd,
            10001,
            10001
          )
          relativePath = ContainerPath.from("workspace")
          traversalPath = ContainerPath.from("/workspace/../host")
          reservedPath = ContainerPath.from("/proc/build")
          nonNormalizedPath = ContainerPath.from("/workspace//project")
          destination <- containerPath("/workspace")
          relativeMount = BindMount.make(
            Path.of("worktree"),
            destination,
            MountAccess.ReadWrite
          )
          unsafeMount = BindMount.make(
            Path.of("/worktrees/bad,mount"),
            destination,
            MountAccess.ReadWrite
          )
          nullDestination = BindMount.make(
            Path.of("/worktrees/pr-42"),
            null.asInstanceOf[ContainerPath],
            MountAccess.ReadWrite
          )
          oversizedPreview = OciLimits.make(
            5.seconds,
            129,
            32,
            128L,
            32,
            1024L,
            BigDecimal(1),
            1024L,
            1024L
          )
          oversizedCpu = OciLimits.make(
            5.seconds,
            32,
            32,
            128L,
            32,
            1024L,
            BigDecimal(1025),
            1024L,
            1024L
          )
          explicitMount <- bind(
            "/worktrees/pr-42",
            destination,
            MountAccess.ReadOnly
          )
          nullLimits = OciRunRequest.make(
            "compile",
            Chunk("/bin/true"),
            Chunk(explicitMount),
            destination,
            null.asInstanceOf[OciLimits]
          )
        yield assertTrue(
          unpinned.isLeft,
          uppercase.isLeft,
          relativeRuntime.isLeft,
          rootUser.isLeft,
          nullImage.isLeft,
          relativePath.isLeft,
          traversalPath.isLeft,
          reservedPath.isLeft,
          nonNormalizedPath.isLeft,
          relativeMount.isLeft,
          unsafeMount.isLeft,
          nullDestination.isLeft,
          oversizedPreview.isLeft,
          oversizedCpu.isLeft,
          nullLimits.isLeft
        )
      },
      test(
        "requires explicit mounts and keeps the working directory inside one"
      ) {
        for
          workspace <- containerPath("/workspace")
          outside <- containerPath("/other/project")
          mount <- bind("/worktrees/pr-42", workspace, MountAccess.ReadWrite)
          limits <- standardLimits
          noMounts = OciRunRequest.make(
            "compile",
            Chunk("./mvnw", "test"),
            Chunk.empty,
            workspace,
            limits
          )
          outsideMount = OciRunRequest.make(
            "compile",
            Chunk("./mvnw", "test"),
            Chunk(mount),
            outside,
            limits
          )
          nulArgument = OciRunRequest.make(
            "compile",
            Chunk("/opt/bat/bin/mvn", "bad\u0000argument"),
            Chunk(mount),
            workspace,
            limits
          )
          relativeExecutable = OciRunRequest.make(
            "compile",
            Chunk("./mvnw", "test"),
            Chunk(mount),
            workspace,
            limits
          )
        yield assertTrue(
          noMounts.isLeft,
          outsideMount.isLeft,
          nulArgument.isLeft,
          relativeExecutable.isLeft
        )
      }
    )

  private def standardLimits: IO[OciFailure, OciLimits] =
    fromEither(
      OciLimits.make(
        timeout = 12.seconds,
        stdoutPreviewBytes = 1024,
        stderrPreviewBytes = 2048,
        outputLimitBytes = 8192L,
        pids = 256,
        memoryBytes = 2147483648L,
        cpus = BigDecimal("2.500"),
        tmpBytes = 67108864L,
        homeBytes = 16777216L
      )
    )

  private def containerPath(value: String): IO[OciFailure, ContainerPath] =
    fromEither(ContainerPath.from(value))

  private def bind(
      source: String,
      destination: ContainerPath,
      access: MountAccess
  ): IO[OciFailure, BindMount] =
    fromEither(BindMount.make(Path.of(source), destination, access))

  private def fromEither[A](value: Either[OciFailure, A]): IO[OciFailure, A] =
    ZIO.fromEither(value)

  private final class RecordingRunner(ref: Ref[Chunk[OciProcessSpec]])
      extends OciProcessRunner:
    def calls: UIO[Chunk[OciProcessSpec]] = ref.get

    def run(spec: OciProcessSpec): IO[OciFailure, OciRunResult] =
      val empty = OciStreamReceipt(
        0L,
        sha256(Array.emptyByteArray),
        Chunk.empty,
        previewTruncated = false
      )
      ref
        .update(_ :+ spec)
        .as(
          OciRunResult(
            spec.operationId,
            OciRunOutcome.Exited(0),
            empty,
            empty,
            durationMillis = 0L
          )
        )

  private object RecordingRunner:
    def make: UIO[RecordingRunner] =
      Ref.make(Chunk.empty[OciProcessSpec]).map(new RecordingRunner(_))

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
