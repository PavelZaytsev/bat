package bat.worker.oci

import java.net.{InetAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object LiveOciConformanceSpec extends ZIOSpecDefault:
  def spec =
    suite("live OCI containment")(
      test("denies synthetic host secrets, sockets, writes, and network") {
        liveConfiguration match
          case None              => ZIO.succeed(assertTrue(true))
          case Some(environment) => runAdversarialProbe(environment)
      } @@ TestAspect.withLiveClock,
      test("removes the daemon-owned container after host-runner timeout") {
        liveConfiguration match
          case None              => ZIO.succeed(assertTrue(true))
          case Some(environment) => runTimeoutProbe(environment)
      } @@ TestAspect.withLiveClock,
      test("removes the daemon-owned container after caller interruption") {
        liveConfiguration match
          case None              => ZIO.succeed(assertTrue(true))
          case Some(environment) => runInterruptionProbe(environment)
      } @@ TestAspect.withLiveClock,
      test("stages a read-only source into bounded ephemeral build storage") {
        liveConfiguration match
          case None              => ZIO.succeed(assertTrue(true))
          case Some(environment) => runStagedWorkspaceProbe(environment)
      } @@ TestAspect.withLiveClock,
      test("removes the daemon-owned container after an output flood") {
        liveConfiguration match
          case None              => ZIO.succeed(assertTrue(true))
          case Some(environment) => runOutputFloodProbe(environment)
      } @@ TestAspect.withLiveClock
    ) @@ TestAspect.sequential

  private final case class LiveEnvironment(
      runtime: Path,
      image: String,
      uid: Int,
      gid: Int
  )

  private def liveConfiguration: Option[LiveEnvironment] =
    for
      runtime <- Option(java.lang.System.getenv("BAT_LIVE_OCI_RUNTIME"))
      image <- Option(java.lang.System.getenv("BAT_LIVE_OCI_IMAGE"))
      uid <- Option(java.lang.System.getenv("BAT_LIVE_OCI_UID")).flatMap(
        _.toIntOption
      )
      gid <- Option(java.lang.System.getenv("BAT_LIVE_OCI_GID")).flatMap(
        _.toIntOption
      )
      _ <- Option(java.lang.System.getenv("BAT_LIVE_OCI_CANARY"))
    yield LiveEnvironment(Path.of(runtime), image, uid, gid)

  private def runAdversarialProbe(
      environment: LiveEnvironment
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        root <- temporaryDirectory
        workspace = root.resolve("workspace")
        _ <- ZIO.attemptBlocking(Files.createDirectory(workspace))
        hostCanary = root.resolve("host-canary.txt")
        _ <- ZIO.attemptBlocking(
          Files.writeString(
            hostCanary,
            "synthetic-host-secret",
            StandardCharsets.UTF_8
          )
        )
        listener <- ZIO.acquireRelease(
          ZIO.attemptBlocking(
            ServerSocket(0, 1, InetAddress.getLoopbackAddress())
          )
        )(socket => ZIO.attemptBlocking(socket.close()).ignore)
        image <- ZIO.fromEither(PinnedImage.from(environment.image))
        limits <- ZIO.fromEither(
          OciLimits.make(
            timeout = 10.seconds,
            stdoutPreviewBytes = 8192,
            stderrPreviewBytes = 8192,
            outputLimitBytes = 1024L * 1024,
            pids = 64,
            memoryBytes = 128L * 1024 * 1024,
            cpus = BigDecimal(1),
            tmpBytes = 8L * 1024 * 1024,
            homeBytes = 8L * 1024 * 1024
          )
        )
        config <- ZIO.fromEither(
          OciSandboxConfig.make(
            environment.runtime,
            image,
            root,
            environment.uid,
            environment.gid
          )
        )
        destination <- ZIO.fromEither(ContainerPath.from("/bat/work"))
        mount <- ZIO.fromEither(
          BindMount.make(workspace, destination, MountAccess.ReadWrite)
        )
        script = adversarialScript(hostCanary, listener.getLocalPort)
        request <- ZIO.fromEither(
          OciRunRequest.make(
            "live-containment",
            Chunk("/bin/sh", "-eu", "-c", script),
            Chunk(mount),
            destination,
            limits
          )
        )
        result <- OciSandbox.live(config).run(request)
        stdout = String(result.stdout.preview.toArray, StandardCharsets.UTF_8)
        stderr = String(result.stderr.preview.toArray, StandardCharsets.UTF_8)
      yield assertTrue(
        result.outcome == OciRunOutcome.Exited(0),
        stdout.contains("containment-ok"),
        !stdout.contains("synthetic-host-secret"),
        !stderr.contains("synthetic-host-secret")
      )
    }

  private def adversarialScript(hostCanary: Path, port: Int): String =
    s"""
       |fail() { printf 'containment-failed:%s\n' "$$1" >&2; exit 90; }
       |[ -z "$${BAT_LIVE_OCI_CANARY+x}" ] || fail inherited-environment
       |[ ! -e '${hostCanary.toString}' ] || fail host-file
       |[ ! -e /var/run/docker.sock ] || fail docker-socket
       |[ ! -e /run/host-services/ssh-auth.sock ] || fail ssh-agent
       |[ ! -e /home/bat/.ssh ] || fail host-home
       |[ -z "$$(find /home/bat -mindepth 1 -print -quit)" ] || fail nonempty-home
       |if touch /escape 2>/dev/null; then fail writable-root; fi
       |if wget -q -T 1 -O - http://1.1.1.1/ >/dev/null 2>&1; then fail public-network; fi
       |if wget -q -T 1 -O - http://host.docker.internal:$port/ >/dev/null 2>&1; then fail control-plane-network; fi
       |printf 'containment-ok\n'
       |""".stripMargin

  private def runTimeoutProbe(
      environment: LiveEnvironment
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        root <- temporaryDirectory
        workspace = root.resolve("workspace")
        _ <- ZIO.attemptBlocking(Files.createDirectory(workspace))
        image <- ZIO.fromEither(PinnedImage.from(environment.image))
        limits <- ZIO.fromEither(
          OciLimits.make(
            timeout = 2.seconds,
            stdoutPreviewBytes = 1024,
            stderrPreviewBytes = 1024,
            outputLimitBytes = 8192L,
            pids = 32,
            memoryBytes = 64L * 1024 * 1024,
            cpus = BigDecimal(1),
            tmpBytes = 4L * 1024 * 1024,
            homeBytes = 4L * 1024 * 1024
          )
        )
        config <- ZIO.fromEither(
          OciSandboxConfig.make(
            environment.runtime,
            image,
            root,
            environment.uid,
            environment.gid
          )
        )
        destination <- ZIO.fromEither(ContainerPath.from("/bat/work"))
        mount <- ZIO.fromEither(
          BindMount.make(workspace, destination, MountAccess.ReadWrite)
        )
        request <- ZIO.fromEither(
          OciRunRequest.make(
            "live-timeout-cleanup",
            Chunk(
              "/bin/sh",
              "-c",
              "trap '' TERM; printf started > started; while :; do printf x >> heartbeat; sleep 0.1; done"
            ),
            Chunk(mount),
            destination,
            limits
          )
        )
        name = OciSandbox.runtimeResourceName(config, request)
        result <- OciSandbox.live(config).run(request)
        started <- ZIO.attemptBlocking(
          Files.isRegularFile(workspace.resolve("started"))
        )
        heartbeat = workspace.resolve("heartbeat")
        bytesAtReturn <- ZIO.attemptBlocking(Files.size(heartbeat))
        _ <- ZIO.sleep(500.millis)
        bytesAfterWait <- ZIO.attemptBlocking(Files.size(heartbeat))
        remains <- runtimeResourceExists(environment.runtime, name)
      yield assertTrue(
        result.outcome == OciRunOutcome.TimedOut,
        started,
        bytesAtReturn > 0L,
        bytesAfterWait == bytesAtReturn,
        !remains
      )
    }

  private def runInterruptionProbe(
      environment: LiveEnvironment
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        root <- temporaryDirectory
        workspace = root.resolve("workspace")
        _ <- ZIO.attemptBlocking(Files.createDirectory(workspace))
        image <- ZIO.fromEither(PinnedImage.from(environment.image))
        limits <- ZIO.fromEither(
          OciLimits.make(
            timeout = 30.seconds,
            stdoutPreviewBytes = 1024,
            stderrPreviewBytes = 1024,
            outputLimitBytes = 8192L,
            pids = 32,
            memoryBytes = 64L * 1024 * 1024,
            cpus = BigDecimal(1),
            tmpBytes = 4L * 1024 * 1024,
            homeBytes = 4L * 1024 * 1024
          )
        )
        config <- ZIO.fromEither(
          OciSandboxConfig.make(
            environment.runtime,
            image,
            root,
            environment.uid,
            environment.gid
          )
        )
        destination <- ZIO.fromEither(ContainerPath.from("/bat/work"))
        mount <- ZIO.fromEither(
          BindMount.make(workspace, destination, MountAccess.ReadWrite)
        )
        request <- ZIO.fromEither(
          OciRunRequest.make(
            "live-interruption-cleanup",
            Chunk(
              "/bin/sh",
              "-c",
              "trap '' TERM; printf started > started; while :; do printf x >> heartbeat; sleep 0.1; done"
            ),
            Chunk(mount),
            destination,
            limits
          )
        )
        sandbox = OciSandbox.live(config)
        name = OciSandbox.runtimeResourceName(config, request)
        fiber <- sandbox.run(request).forkScoped
        heartbeat = workspace.resolve("heartbeat")
        _ <- waitForHeartbeat(heartbeat, 10.seconds)
        existedBeforeInterrupt <- runtimeResourceExists(
          environment.runtime,
          name
        )
        _ <- fiber.interrupt
        started <- ZIO.attemptBlocking(
          Files.isRegularFile(workspace.resolve("started"))
        )
        bytesAtReturn <- ZIO.attemptBlocking(Files.size(heartbeat))
        _ <- ZIO.sleep(500.millis)
        bytesAfterWait <- ZIO.attemptBlocking(Files.size(heartbeat))
        remains <- runtimeResourceExists(environment.runtime, name)
      yield assertTrue(
        existedBeforeInterrupt,
        started,
        bytesAtReturn > 0L,
        bytesAfterWait == bytesAtReturn,
        !remains
      )
    }

  private def runStagedWorkspaceProbe(
      environment: LiveEnvironment
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        root <- temporaryDirectory
        source = root.resolve("source")
        _ <- ZIO.attemptBlocking {
          val _ = Files.createDirectory(source)
          val _ = Files.writeString(source.resolve("visible.txt"), "source")
          val git = Files.createDirectory(source.resolve(".git"))
          val bdr = Files.createDirectory(source.resolve(".bdr"))
          val _ = Files.writeString(git.resolve("private"), "git-secret")
          val _ = Files.writeString(bdr.resolve("private"), "bdr-secret")
        }
        image <- ZIO.fromEither(PinnedImage.from(environment.image))
        limits <- ZIO.fromEither(
          OciLimits.make(
            timeout = 5.seconds,
            stdoutPreviewBytes = 1024,
            stderrPreviewBytes = 1024,
            outputLimitBytes = 8192L,
            pids = 32,
            memoryBytes = 64L * 1024 * 1024,
            cpus = BigDecimal(1),
            tmpBytes = 4L * 1024 * 1024,
            homeBytes = 4L * 1024 * 1024,
            workBytes = 8L * 1024 * 1024
          )
        )
        config <- ZIO.fromEither(
          OciSandboxConfig.make(
            environment.runtime,
            image,
            root,
            environment.uid,
            environment.gid
          )
        )
        sourceDestination <- ZIO.fromEither(
          ContainerPath.from("/bat/source")
        )
        workDestination <- ZIO.fromEither(ContainerPath.from("/bat/run"))
        mount <- ZIO.fromEither(
          BindMount.make(source, sourceDestination, MountAccess.ReadOnly)
        )
        request <- ZIO.fromEither(
          OciRunRequest.makeStaged(
            "live-staged-workspace",
            Chunk(
              "/bin/sh",
              "-eu",
              "-c",
              "test -f visible.txt; test ! -e .git; test ! -e .bdr; printf generated > generated.txt; printf staged-ok"
            ),
            Chunk(mount),
            workDestination,
            limits
          )
        )
        result <- OciSandbox.live(config).run(request)
        stdout = String(result.stdout.preview.toArray, StandardCharsets.UTF_8)
        sourceUntouched <- ZIO.attemptBlocking(
          Files.readString(source.resolve("visible.txt")) == "source" &&
            !Files.exists(source.resolve("generated.txt"))
        )
      yield assertTrue(
        result.outcome == OciRunOutcome.Exited(0),
        stdout == "staged-ok",
        sourceUntouched
      )
    }

  private def runOutputFloodProbe(
      environment: LiveEnvironment
  ): ZIO[Any, Any, TestResult] =
    ZIO.scoped {
      for
        root <- temporaryDirectory
        workspace = root.resolve("workspace")
        _ <- ZIO.attemptBlocking(Files.createDirectory(workspace))
        image <- ZIO.fromEither(PinnedImage.from(environment.image))
        limits <- ZIO.fromEither(
          OciLimits.make(
            timeout = 10.seconds,
            stdoutPreviewBytes = 1024,
            stderrPreviewBytes = 1024,
            outputLimitBytes = 8192L,
            pids = 32,
            memoryBytes = 64L * 1024 * 1024,
            cpus = BigDecimal(1),
            tmpBytes = 4L * 1024 * 1024,
            homeBytes = 4L * 1024 * 1024
          )
        )
        config <- ZIO.fromEither(
          OciSandboxConfig.make(
            environment.runtime,
            image,
            root,
            environment.uid,
            environment.gid
          )
        )
        destination <- ZIO.fromEither(ContainerPath.from("/bat/work"))
        mount <- ZIO.fromEither(
          BindMount.make(workspace, destination, MountAccess.ReadWrite)
        )
        request <- ZIO.fromEither(
          OciRunRequest.make(
            "live-output-cleanup",
            Chunk(
              "/bin/sh",
              "-c",
              "printf started > started; printf h > heartbeat; (while :; do printf x >> heartbeat; sleep 0.1; done) & while :; do printf flood; done"
            ),
            Chunk(mount),
            destination,
            limits
          )
        )
        name = OciSandbox.runtimeResourceName(config, request)
        result <- OciSandbox.live(config).run(request)
        started <- ZIO.attemptBlocking(
          Files.isRegularFile(workspace.resolve("started"))
        )
        heartbeat = workspace.resolve("heartbeat")
        bytesAtReturn <- ZIO.attemptBlocking(Files.size(heartbeat))
        _ <- ZIO.sleep(500.millis)
        bytesAfterWait <- ZIO.attemptBlocking(Files.size(heartbeat))
        remains <- runtimeResourceExists(environment.runtime, name)
      yield assertTrue(
        result.outcome match
          case OciRunOutcome.OutputLimit(8192L, observed) => observed > 8192L
          case _                                          => false,
        started,
        bytesAfterWait == bytesAtReturn,
        !remains
      )
    }

  private def runtimeResourceExists(
      runtime: Path,
      name: String
  ): Task[Boolean] =
    ZIO.attemptBlocking {
      val builder = ProcessBuilder(
        runtime.toString,
        "container",
        "inspect",
        name
      )
      builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      builder.redirectError(ProcessBuilder.Redirect.DISCARD)
      val process = builder.start()
      process.getOutputStream.close()
      process.waitFor() == 0
    }

  private def waitForHeartbeat(path: Path, timeout: Duration): Task[Unit] =
    def poll: Task[Unit] =
      ZIO
        .attemptBlocking(
          Files.isRegularFile(path) && Files.size(path) > 0L
        )
        .flatMap(observed =>
          if observed then ZIO.unit else ZIO.sleep(25.millis) *> poll
        )

    poll.timeoutFail(
      IllegalStateException("live OCI heartbeat did not start")
    )(timeout)

  private def temporaryDirectory: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory("bat-live-oci-"))
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
            .sortWith((left, right) => left.getNameCount > right.getNameCount)
            .foreach(candidate =>
              val _ = Files.deleteIfExists(candidate)
            )
        finally stream.close()
    }.ignore
