package bat.worker.oci

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import zio.*

/** Executes explicit argv in a fixed OCI isolation profile.
  *
  * Authoring operations receive only their stable run worktree. Java builds
  * mount that worktree read-only and stage it into bounded ephemeral storage.
  */
trait OciSandbox:
  def image: PinnedImage
  def cleanup(operationId: String): IO[OciFailure, Unit]
  def run(request: OciRunRequest): IO[OciFailure, OciRunResult]

object OciSandbox:
  def live(config: OciSandboxConfig): OciSandbox =
    live(config, JdkOciProcessRunner)

  private[oci] def live(
      config: OciSandboxConfig,
      runner: OciProcessRunner
  ): OciSandbox =
    new Live(config, runner)

  private final class Live(
      config: OciSandboxConfig,
      runner: OciProcessRunner
  ) extends OciSandbox:
    val image: PinnedImage = config.image

    def cleanup(operationId: String): IO[OciFailure, Unit] =
      if operationId == null ||
        !operationId.matches("^[a-z0-9][a-z0-9_-]{0,63}$")
      then
        ZIO.fail(
          OciFailure.InvalidConfiguration(
            "invalid_operation_id",
            "OCI cleanup operation ID is invalid"
          )
        )
      else
        JdkOciProcessRunner.cleanup(
          cleanupSpec(config, operationId)
        )

    def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
      runner.run(processSpec(config, request))

  private[oci] def processSpec(
      config: OciSandboxConfig,
      request: OciRunRequest
  ): OciProcessSpec =
    val limits = request.limits
    val containerName = runtimeResourceName(config, request)
    val fixedArguments = Chunk(
      "run",
      s"--name=$containerName",
      "--log-driver=none",
      "--init",
      "--stop-timeout=1",
      "--pull=never",
      "--network=none",
      "--read-only",
      "--cap-drop=ALL",
      "--security-opt=no-new-privileges",
      s"--pids-limit=${limits.pids}",
      s"--memory=${limits.memoryBytes}",
      s"--memory-swap=${limits.memoryBytes}",
      s"--cpus=${canonicalDecimal(limits.cpus)}",
      s"--user=${config.uid}:${config.gid}",
      "--ipc=none",
      s"--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=${limits.tmpBytes},mode=1777",
      s"--tmpfs=/home/bat:rw,noexec,nosuid,nodev,size=${limits.homeBytes},uid=${config.uid},gid=${config.gid},mode=0700",
      s"--workdir=${request.workingDirectory.value}",
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
      s"--entrypoint=${
          if request.stagedWorkspace then "/bin/sh"
          else request.argv.head
        }",
      s"--label=bat.operation-id=${request.operationId}"
    )
    val stagedArguments =
      if request.stagedWorkspace then
        Chunk(
          s"--tmpfs=/bat/run:rw,nosuid,nodev,size=${limits.workBytes},uid=${config.uid},gid=${config.gid},mode=0700"
        )
      else Chunk.empty
    val mountArguments =
      request.mounts.flatMap(mount => Chunk("--mount", renderMount(mount)))
    val commandArguments =
      if request.stagedWorkspace then
        Chunk(
          config.image.value,
          "-eu",
          "-c",
          StagedWorkspaceScript,
          "bat-stage"
        ) ++ request.argv
      else Chunk(config.image.value) ++ request.argv.tail
    OciProcessSpec(
      operationId = request.operationId,
      argv = Chunk(config.runtimeExecutable.toString) ++ fixedArguments ++
        stagedArguments ++ mountArguments ++ commandArguments,
      cwd = config.launcherWorkingDirectory,
      environment = FixedLauncherEnvironment,
      cleanup = Some(runtimeCleanup(config, containerName)),
      timeout = limits.timeout,
      stdoutPreviewBytes = limits.stdoutPreviewBytes,
      stderrPreviewBytes = limits.stderrPreviewBytes,
      outputLimitBytes = limits.outputLimitBytes
    )

  private val FixedLauncherEnvironment = Map(
    "HOME" -> "/nonexistent",
    "DOCKER_CONFIG" -> "/nonexistent",
    "LANG" -> "C",
    "LC_ALL" -> "C",
    "TZ" -> "UTC"
  )

  private[oci] val StagedWorkspaceScript =
    "mkdir -p /bat/run/repository /bat/run/cache/maven /bat/run/cache/gradle; " +
      "if [ -d /opt/bat/cache/maven ]; then cp -R /opt/bat/cache/maven/. /bat/run/cache/maven/; fi; " +
      "if [ -d /opt/bat/cache/gradle ]; then cp -R /opt/bat/cache/gradle/. /bat/run/cache/gradle/; fi; " +
      "cd /bat/source; " +
      "/bin/tar --exclude=./.git --exclude=./.bdr -cf /bat/run/source.tar .; " +
      "cd /bat/run/repository; " +
      "/bin/tar -xf /bat/run/source.tar; " +
      "rm -f /bat/run/source.tar; " +
      "cd /bat/run/repository; " +
      "exec \"$@\""

  private def canonicalDecimal(value: BigDecimal): String =
    value.bigDecimal.stripTrailingZeros.toPlainString

  private def renderMount(mount: BindMount): String =
    val base =
      s"type=bind,src=${mount.source},dst=${mount.destination.value}"
    mount.access match
      case MountAccess.ReadOnly  => s"$base,readonly"
      case MountAccess.ReadWrite => base

  private[oci] def runtimeResourceName(
      config: OciSandboxConfig,
      request: OciRunRequest
  ): String = runtimeResourceName(config, request.operationId)

  private[oci] def runtimeResourceName(
      _config: OciSandboxConfig,
      operationId: String
  ): String =
    if operationId == null ||
      !operationId.matches("^[a-z0-9][a-z0-9_-]{0,63}$")
    then throw new IllegalArgumentException("invalid OCI operation ID")
    val binding = Chunk(
      "bat-runtime-resource-v2",
      operationId
    ).mkString("\n")
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(binding.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
    s"bat-${digest.take(48)}"

  private def runtimeCleanup(
      config: OciSandboxConfig,
      containerName: String
  ): OciRuntimeCleanup =
    OciRuntimeCleanup(
      removeArgv = Chunk(
        config.runtimeExecutable.toString,
        "rm",
        "--force",
        containerName
      ),
      listArgv = Chunk(
        config.runtimeExecutable.toString,
        "container",
        "ls",
        "--all",
        "--format",
        "{{.Names}}",
        "--filter",
        s"name=$containerName"
      ),
      exactName = containerName
    )

  private def cleanupSpec(
      config: OciSandboxConfig,
      operationId: String
  ): OciProcessSpec =
    val name = runtimeResourceName(config, operationId)
    OciProcessSpec(
      operationId,
      Chunk.empty,
      config.launcherWorkingDirectory,
      FixedLauncherEnvironment,
      Some(runtimeCleanup(config, name)),
      5.seconds,
      1,
      1,
      1L
    )

private[oci] final case class OciProcessSpec(
    operationId: String,
    argv: Chunk[String],
    cwd: Path,
    environment: Map[String, String],
    cleanup: Option[OciRuntimeCleanup],
    timeout: Duration,
    stdoutPreviewBytes: Int,
    stderrPreviewBytes: Int,
    outputLimitBytes: Long
)

private[oci] final case class OciRuntimeCleanup(
    removeArgv: Chunk[String],
    listArgv: Chunk[String],
    exactName: String
)

private[oci] trait OciProcessRunner:
  def run(spec: OciProcessSpec): IO[OciFailure, OciRunResult]

private[oci] object JdkOciProcessRunner extends OciProcessRunner:
  def cleanup(spec: OciProcessSpec): IO[OciFailure, Unit] =
    ensureRuntimeResourceAbsent(spec)

  def run(spec: OciProcessSpec): IO[OciFailure, OciRunResult] =
    val startedNanos = java.lang.System.nanoTime()
    val stdout = StreamAccumulator(spec.stdoutPreviewBytes)
    val stderr = StreamAccumulator(spec.stderrPreviewBytes)
    val combinedBytes = new AtomicLong(0L)
    val forcedOutcome = new AtomicReference[Option[ForcedOutcome]](None)
    val execution: IO[RunnerFailure, Int] = ZIO.scoped {
      ZIO
        .acquireRelease(
          start(spec).mapError(RunnerFailure.Infrastructure.apply)
        )(process => stop(process, Iterable.empty))
        .flatMap { process =>
          for
            tracked = new ConcurrentHashMap[Long, ProcessHandle]()
            trackerReady = new CountDownLatch(1)
            tracker <- trackDescendants(
              process,
              tracked,
              trackerReady
            ).forkScoped
            _ <- awaitTracker(trackerReady)
            exitCode <- Promise.make[Nothing, Int]
            command = ZIO
              .collectAllParDiscard(
                Chunk(
                  waitFor(process)
                    .mapError(RunnerFailure.Infrastructure.apply)
                    .tap(code => exitCode.succeed(code))
                    .unit,
                  drain(
                    process,
                    process.getInputStream,
                    "stdout",
                    stdout,
                    combinedBytes,
                    spec.outputLimitBytes,
                    forcedOutcome
                  ),
                  drain(
                    process,
                    process.getErrorStream,
                    "stderr",
                    stderr,
                    combinedBytes,
                    spec.outputLimitBytes,
                    forcedOutcome
                  )
                )
              )
            guarded <- command
              .tapError(_ =>
                ZIO.suspendSucceed(
                  stop(process, tracked.values().asScala.toList)
                )
              )
              .raceFirst(
                timeoutGuard(
                  process,
                  tracked,
                  spec,
                  forcedOutcome
                )
              )
              .ensuring(
                cleanup(
                  process,
                  tracked,
                  tracker,
                  spec
                )
              )
            result <- exitCode.await
          yield result
        }
    }
    (ensureRuntimeResourceAbsent(spec) *>
      execution.either.onInterrupt(ensureRuntimeResourceAbsent(spec).ignore))
      .flatMap { executionResult =>
        ensureRuntimeResourceAbsent(spec) *>
          (forcedOutcome.get() match
            case Some(ForcedOutcome.Timeout) =>
              ZIO.succeed(
                result(
                  spec,
                  OciRunOutcome.TimedOut,
                  stdout.receipt,
                  stderr.receipt,
                  startedNanos
                )
              )
            case Some(ForcedOutcome.OutputLimit) =>
              ZIO.succeed(
                result(
                  spec,
                  OciRunOutcome.OutputLimit(
                    spec.outputLimitBytes,
                    combinedBytes.get()
                  ),
                  stdout.receipt,
                  stderr.receipt,
                  startedNanos
                )
              )
            case None =>
              executionResult match
                case Right(exitCode) =>
                  val outcome =
                    if Set(125, 126, 127).contains(exitCode) then
                      OciRunOutcome.RuntimeFailed(exitCode)
                    else OciRunOutcome.Exited(exitCode)
                  ZIO.succeed(
                    result(
                      spec,
                      outcome,
                      stdout.receipt,
                      stderr.receipt,
                      startedNanos
                    )
                  )
                case Left(RunnerFailure.Timeout) =>
                  ZIO.fail(
                    OciFailure.ProcessFailure(
                      "timeout_state_missing",
                      "OCI timeout guard finished without recording a timeout"
                    )
                  )
                case Left(RunnerFailure.OutputLimit) =>
                  ZIO.succeed(
                    result(
                      spec,
                      OciRunOutcome.OutputLimit(
                        spec.outputLimitBytes,
                        combinedBytes.get()
                      ),
                      stdout.receipt,
                      stderr.receipt,
                      startedNanos
                    )
                  )
                case Left(RunnerFailure.Infrastructure(failure)) =>
                  ZIO.fail(failure))
      }

  private def result(
      spec: OciProcessSpec,
      outcome: OciRunOutcome,
      stdout: OciStreamReceipt,
      stderr: OciStreamReceipt,
      startedNanos: Long
  ): OciRunResult =
    val elapsedNanos = math.max(0L, java.lang.System.nanoTime() - startedNanos)
    OciRunResult(
      spec.operationId,
      outcome,
      stdout,
      stderr,
      elapsedNanos / 1000000L
    )

  private def start(spec: OciProcessSpec): IO[OciFailure, Process] =
    ZIO
      .attemptBlockingInterrupt {
        val builder = new ProcessBuilder(spec.argv*)
        builder.directory(spec.cwd.toFile)
        builder.redirectErrorStream(false)
        val environment = builder.environment()
        environment.clear()
        environment.putAll(spec.environment.asJava)
        val process = builder.start()
        process.getOutputStream.close()
        process
      }
      .mapError(_ =>
        OciFailure.ProcessFailure(
          "process_start_failed",
          "OCI runtime process could not start"
        )
      )

  private def waitFor(process: Process): IO[OciFailure, Int] =
    ZIO
      .attemptBlockingInterrupt(process.waitFor())
      .mapError(_ =>
        OciFailure.ProcessFailure(
          "process_wait_failed",
          "OCI runtime process could not be reaped"
        )
      )

  private def drain(
      process: Process,
      stream: InputStream,
      label: String,
      accumulator: StreamAccumulator,
      combinedBytes: AtomicLong,
      outputLimitBytes: Long,
      forcedOutcome: AtomicReference[Option[ForcedOutcome]]
  ): IO[RunnerFailure, Unit] =
    ZIO
      .attemptBlockingInterrupt {
        val buffer = new Array[Byte](8192)
        var read = stream.read(buffer)
        try
          while read >= 0 do
            if read > 0 then
              accumulator.add(buffer, read)
              val observed = combinedBytes.addAndGet(read.toLong)
              if observed > outputLimitBytes then
                val _ = forcedOutcome.compareAndSet(
                  None,
                  Some(ForcedOutcome.OutputLimit)
                )
                val _ = process.destroyForcibly()
                throw OutputLimitExceeded
            read = stream.read(buffer)
        finally accumulator.finish()
      }
      .mapError {
        case OutputLimitExceeded => RunnerFailure.OutputLimit
        case _                   =>
          RunnerFailure.Infrastructure(
            OciFailure.ProcessFailure(
              "process_output_failed",
              s"OCI $label could not be captured"
            )
          )
      }

  private def timeoutGuard(
      process: Process,
      tracked: ConcurrentHashMap[Long, ProcessHandle],
      spec: OciProcessSpec,
      forcedOutcome: AtomicReference[Option[ForcedOutcome]]
  ): IO[RunnerFailure, Nothing] =
    ZIO.sleep(spec.timeout) *> ZIO.succeed(
      forcedOutcome.compareAndSet(None, Some(ForcedOutcome.Timeout))
    ) *> ZIO.suspendSucceed(
      stop(process, tracked.values().asScala.toList)
    ) *>
      ZIO.fail(RunnerFailure.Timeout)

  private def cleanup(
      process: Process,
      tracked: ConcurrentHashMap[Long, ProcessHandle],
      tracker: Fiber[Nothing, Unit],
      spec: OciProcessSpec
  ): UIO[Unit] =
    for
      _ <- tracker.interrupt
      descendants <- ZIO.succeed(tracked.values().asScala.toList)
      _ <- stop(process, descendants)
    yield ()

  private def trackDescendants(
      process: Process,
      tracked: ConcurrentHashMap[Long, ProcessHandle],
      ready: CountDownLatch
  ): UIO[Unit] =
    ZIO.attemptBlockingInterrupt {
      try
        while !Thread.currentThread().isInterrupted do
          descendantsOf(process).foreach(handle =>
            val _ = tracked.put(handle.pid(), handle)
          )
          ready.countDown()
          Thread.sleep(2L)
      catch case _: InterruptedException => Thread.currentThread().interrupt()
      finally ready.countDown()
    }.ignore

  private def awaitTracker(ready: CountDownLatch): UIO[Unit] =
    ZIO.attemptBlockingInterrupt(ready.await()).orDie

  private def stop(
      process: Process,
      previouslyObserved: Iterable[ProcessHandle]
  ): UIO[Unit] =
    ZIO.attemptBlocking {
      closeQuietly(process.getOutputStream)
      val descendants = mutable.LinkedHashMap.empty[Long, ProcessHandle]
      def observe(): Unit =
        (previouslyObserved ++ descendantsOf(process)).foreach(handle =>
          val _ = descendants.update(handle.pid(), handle)
        )
      observe()
      val gracefulDeadline = java.lang.System.nanoTime() + 100.millis.toNanos
      while java.lang.System.nanoTime() < gracefulDeadline do
        observe()
        descendants.valuesIterator.foreach(handle =>
          if handle.isAlive then
            val _ = handle.destroy()
        )
        Thread.sleep(5L)
      val forcedDeadline = java.lang.System.nanoTime() + 2.seconds.toNanos
      var quietScans = 0
      while java.lang.System.nanoTime() < forcedDeadline && quietScans < 3 do
        observe()
        descendants.valuesIterator.foreach(handle =>
          if handle.isAlive then
            val _ = handle.destroyForcibly()
        )
        if descendants.valuesIterator.exists(_.isAlive) then quietScans = 0
        else quietScans += 1
        Thread.sleep(5L)
      observe()
      awaitProcessStopped(process, 100.millis)
      if process.isAlive then process.destroy()
      awaitProcessStopped(process, 100.millis)
      if process.isAlive then
        val _ = process.destroyForcibly()
      awaitProcessStopped(process, 500.millis)
      descendants.valuesIterator.foreach(handle =>
        if handle.isAlive then
          val _ = handle.destroyForcibly()
      )
      awaitStopped(descendants.values, 200.millis)
      closeQuietly(process.getInputStream)
      closeQuietly(process.getErrorStream)
    }.ignore

  private def descendantsOf(process: Process): List[ProcessHandle] =
    try
      val stream = process.descendants()
      try stream.iterator().asScala.toList
      finally stream.close()
    catch case _: Exception => Nil

  private def ensureRuntimeResourceAbsent(
      spec: OciProcessSpec
  ): IO[OciFailure, Unit] =
    spec.cleanup match
      case None          => ZIO.unit
      case Some(cleanup) =>
        ZIO
          .attemptBlockingInterrupt {
            var attempt = 0
            var absent = false
            while attempt < 120 && !absent do
              observeAbsent(spec, cleanup) match
                case Some(true)  => absent = true
                case Some(false) =>
                  // Docker Desktop may finish removing the container after
                  // the client-side command times out. Always re-observe the
                  // exact name before treating that timeout as a cleanup
                  // failure.
                  try
                    val _ = runCleanupCommand(
                      spec,
                      cleanup.removeArgv,
                      captureOutput = false
                    )
                  catch case NonFatal(_) => ()
                  absent = observeAbsent(spec, cleanup).contains(true)
                case None => ()
              if !absent then Thread.sleep(250L)
              attempt += 1
            if !absent then
              throw new IllegalStateException("runtime resource remains")
          }
          .mapError(_ =>
            OciFailure.ProcessFailure(
              "runtime_cleanup_failed",
              "OCI runtime resource absence could not be confirmed"
            )
          )

  private def observeAbsent(
      spec: OciProcessSpec,
      cleanup: OciRuntimeCleanup
  ): Option[Boolean] =
    try Some(!listed(spec, cleanup))
    catch case NonFatal(_) => None

  private def listed(
      spec: OciProcessSpec,
      cleanup: OciRuntimeCleanup
  ): Boolean =
    val (exitCode, output) = runCleanupCommand(
      spec,
      cleanup.listArgv,
      captureOutput = true
    )
    if exitCode != 0 then throw new IllegalStateException("runtime list failed")
    output.linesIterator.exists(_.trim == cleanup.exactName)

  private def runCleanupCommand(
      spec: OciProcessSpec,
      argv: Chunk[String],
      captureOutput: Boolean
  ): (Int, String) =
    val builder = ProcessBuilder(argv*)
    builder.directory(spec.cwd.toFile)
    if !captureOutput then
      val _ = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    val environment = builder.environment()
    environment.clear()
    environment.putAll(spec.environment.asJava)
    val process = builder.start()
    process.getOutputStream.close()
    val completed = process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS)
    if !completed then
      val _ = process.destroyForcibly()
      val _ = process.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS)
      throw new IllegalStateException("runtime cleanup command timed out")
    val output =
      if captureOutput then
        val bytes = process.getInputStream.readNBytes(65537)
        if bytes.length > 65536 then
          throw new IllegalStateException("runtime list output exceeded limit")
        String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      else ""
    closeQuietly(process.getInputStream)
    closeQuietly(process.getErrorStream)
    process.exitValue() -> output

  private def awaitStopped(
      handles: Iterable[ProcessHandle],
      timeout: Duration
  ): Unit =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    while handles.exists(_.isAlive) && java.lang.System.nanoTime() < deadline do
      Thread.sleep(5L)

  private def awaitProcessStopped(process: Process, timeout: Duration): Unit =
    val deadline = java.lang.System.nanoTime() + timeout.toNanos
    while process.isAlive && java.lang.System.nanoTime() < deadline do
      Thread.sleep(5L)

  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch case _: Exception => ()

  private enum RunnerFailure:
    case Infrastructure(failure: OciFailure)
    case Timeout
    case OutputLimit

  private enum ForcedOutcome:
    case Timeout
    case OutputLimit

  private case object OutputLimitExceeded extends RuntimeException

  private final class StreamAccumulator private (previewLimit: Int):
    private val digest = MessageDigest.getInstance("SHA-256")
    private val preview = new ByteArrayOutputStream(
      math.min(previewLimit, 8192)
    )
    private val total = new AtomicLong(0L)
    private val finished = new AtomicBoolean(false)
    private val stored = new AtomicReference[OciStreamReceipt](
      OciStreamReceipt(0L, EmptySha256, Chunk.empty, previewTruncated = false)
    )

    def add(bytes: Array[Byte], length: Int): Unit =
      digest.update(bytes, 0, length)
      val alreadyObserved = total.getAndAdd(length.toLong)
      val remaining = previewLimit.toLong - alreadyObserved
      if remaining > 0 then
        preview.write(bytes, 0, math.min(length.toLong, remaining).toInt)

    def finish(): Unit =
      if finished.compareAndSet(false, true) then
        val totalBytes = total.get()
        stored.set(
          OciStreamReceipt(
            totalBytes = totalBytes,
            sha256 = hex(digest.digest()),
            preview = Chunk.fromArray(preview.toByteArray),
            previewTruncated = totalBytes > preview.size().toLong
          )
        )

    def receipt: OciStreamReceipt = stored.get()

  private object StreamAccumulator:
    def apply(previewLimit: Int): StreamAccumulator =
      new StreamAccumulator(previewLimit)

  private val EmptySha256 =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  private def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString
