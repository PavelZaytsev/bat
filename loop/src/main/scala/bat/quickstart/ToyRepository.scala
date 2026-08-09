package bat.quickstart

import bat.protocol.BatError

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardCopyOption
}
import java.security.MessageDigest
import java.util.concurrent.{ConcurrentHashMap, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import zio.{Duration, Fiber, IO, Promise, UIO, ZIO}

/** A deterministic actor-visible checkout of the Java six-phase fixture.
  *
  * `toyRevision` authenticates the complete fixture, including evaluator and
  * reference assets. Only `subject/base` plus `subject/head.patch` contribute
  * files to `repository`; the evaluator and reference repair never cross the
  * actor boundary.
  */
final case class MaterializedToy(
    repository: Path,
    baseSha: String,
    headSha: String,
    toyRevision: String
)

object ToyRepository:
  private val SubjectBase = Path.of("subject", "base")
  private val SubjectPatch = Path.of("subject", "head.patch")
  private val MaxFixtureFiles = 10000
  private val MaxFixtureBytes = 64L * 1024 * 1024
  private val MaxPatchBytes = 4L * 1024 * 1024

  /** Materialize the trusted fixture as deterministic base and target commits.
    * The destination must be absent or an empty, non-symlink directory.
    */
  def materialize(
      fixtureRoot: Path,
      destination: Path
  ): IO[BatError, MaterializedToy] =
    for
      fixture <- validateFixtureRoot(fixtureRoot)
      revision <- fixtureRevision(fixture)
      repository <- prepareDestination(destination)
      _ <- copySubject(fixture.resolve(SubjectBase), repository)
      _ <- rejectActorLeaks(repository)
      _ <- ToyRuntime.git(repository, Seq("init", "--initial-branch=main"))
      _ <- ToyRuntime.git(repository, Seq("add", "--all", "--", "."))
      _ <- commit(repository, "toy base", "2000-01-01T00:00:00Z")
      base <- ToyRuntime.head(repository)
      patch <- readBoundedPatch(fixture.resolve(SubjectPatch))
      _ <- ToyRuntime.git(
        repository,
        Seq("apply", "--index", "--whitespace=error-all", "-"),
        Some(patch)
      )
      _ <- commit(repository, "toy target", "2000-01-01T00:00:01Z")
      head <- ToyRuntime.head(repository)
      _ <- ZIO
        .fail(failure("invalid_toy_history", "toy commits were not distinct"))
        .when(base == head)
      remotes <- ToyRuntime.git(repository, Seq("remote"))
      _ <- ZIO
        .fail(
          failure(
            "unexpected_toy_remote",
            "materialized toy repository must not have a remote"
          )
        )
        .unless(
          String(remotes.output, StandardCharsets.US_ASCII).trim.isEmpty
        )
      _ <- rejectActorLeaks(repository)
    yield MaterializedToy(repository, base, head, revision)

  private def validateFixtureRoot(path: Path): IO[BatError, Path] =
    ZIO
      .attemptBlocking {
        if path == null then throw ToyFailure()
        val root = path.toAbsolutePath.normalize
        if Files.isSymbolicLink(root) ||
          !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        then throw ToyFailure()
        val required = Seq(
          root.resolve("manifest.json"),
          root.resolve(SubjectPatch),
          root.resolve(SubjectBase)
        )
        if !required.forall(candidate =>
            !Files.isSymbolicLink(candidate) &&
              (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
          )
        then throw ToyFailure()
        root
      }
      .mapError(_ =>
        failure(
          "invalid_toy_fixture",
          "Java six-phase fixture is missing or unsafe"
        )
      )

  private def fixtureRevision(root: Path): IO[BatError, String] =
    ZIO
      .attemptBlocking {
        val digest = MessageDigest.getInstance("SHA-256")
        ToyRuntime.updateDigest(digest, "bat-java-six-phase-fixture-v1")
        val stream = Files.walk(root)
        val files =
          try
            stream
              .iterator()
              .asScala
              .filter(_ != root)
              .toList
              .sortBy(path => relativeName(root, path))
          finally stream.close()
        if files.size > MaxFixtureFiles then throw ToyFailure()
        var totalBytes = 0L
        files.foreach { path =>
          if Files.isSymbolicLink(path) then throw ToyFailure()
          val relative = relativeName(root, path)
          if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
            ToyRuntime.updateDigest(digest, s"directory:$relative")
          else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
            val size = Files.size(path)
            totalBytes = Math.addExact(totalBytes, size)
            if totalBytes > MaxFixtureBytes then throw ToyFailure()
            ToyRuntime.updateDigest(digest, s"file:$relative")
            ToyRuntime.updateDigest(digest, size)
            val input = Files.newInputStream(path)
            try transferDigest(input, digest)
            finally input.close()
          else throw ToyFailure()
        }
        ToyRuntime.hex(digest.digest())
      }
      .mapError(_ =>
        failure(
          "invalid_toy_fixture",
          "Java six-phase fixture could not be authenticated"
        )
      )

  private def prepareDestination(path: Path): IO[BatError, Path] =
    ZIO
      .attemptBlocking {
        if path == null then throw ToyFailure()
        val requested = path.toAbsolutePath.normalize
        if Files.isSymbolicLink(requested) then throw ToyFailure()
        val requestedParent = requested.getParent
        if requestedParent == null then throw ToyFailure()
        Files.createDirectories(requestedParent)
        val canonicalParent = requestedParent.toRealPath()
        if Files.isSymbolicLink(canonicalParent) ||
          !Files.isDirectory(canonicalParent, LinkOption.NOFOLLOW_LINKS)
        then throw ToyFailure()
        val destination = canonicalParent.resolve(requested.getFileName)
        if Files.exists(destination, LinkOption.NOFOLLOW_LINKS) then
          if Files.isSymbolicLink(destination) ||
            !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
          then throw ToyFailure()
          val entries = Files.list(destination)
          try
            if entries.findFirst().isPresent then throw ToyFailure()
          finally entries.close()
        else
          val _ = Files.createDirectory(destination)
        destination
      }
      .mapError(_ =>
        failure(
          "invalid_toy_destination",
          "toy destination must be an empty safe directory"
        )
      )

  private def copySubject(source: Path, destination: Path): IO[BatError, Unit] =
    ZIO
      .attemptBlocking {
        Files.walkFileTree(
          source,
          new SimpleFileVisitor[Path]:
            override def preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              if attributes.isSymbolicLink || !attributes.isDirectory then
                throw ToyFailure()
              val relative = source.relativize(directory)
              rejectProtected(relative)
              if relative.toString.nonEmpty then
                val _ = Files.createDirectory(destination.resolve(relative))
              FileVisitResult.CONTINUE

            override def visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              if attributes.isSymbolicLink || !attributes.isRegularFile then
                throw ToyFailure()
              val relative = source.relativize(file)
              rejectProtected(relative)
              val target = destination.resolve(relative).normalize
              if !target.startsWith(destination) then throw ToyFailure()
              val _ = Files.copy(
                file,
                target,
                StandardCopyOption.COPY_ATTRIBUTES
              )
              FileVisitResult.CONTINUE
        )
      }
      .unit
      .mapError(_ =>
        failure(
          "toy_materialization_failed",
          "actor-visible toy sources could not be materialized"
        )
      )

  private def rejectProtected(relative: Path): Unit =
    if relative.getNameCount > 0 then
      relative.getName(0).toString.toLowerCase match
        case ".git" | ".bdr" | "oracle" | "reference" =>
          throw ToyFailure()
        case _ => ()

  private def rejectActorLeaks(repository: Path): IO[BatError, Unit] =
    ZIO
      .attemptBlocking {
        Seq("oracle", "reference", ".bdr").foreach { name =>
          if Files.exists(
              repository.resolve(name),
              LinkOption.NOFOLLOW_LINKS
            )
          then throw ToyFailure()
        }
      }
      .mapError(_ =>
        failure(
          "toy_actor_boundary_failed",
          "evaluator or reference assets crossed the actor boundary"
        )
      )

  private def readBoundedPatch(path: Path): IO[BatError, String] =
    ZIO
      .attemptBlocking {
        if Files.isSymbolicLink(path) ||
          !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
          Files.size(path) > MaxPatchBytes
        then throw ToyFailure()
        Files.readString(path, StandardCharsets.UTF_8)
      }
      .mapError(_ =>
        failure(
          "invalid_toy_target_patch",
          "toy target patch is missing or unsafe"
        )
      )

  private def commit(
      repository: Path,
      message: String,
      date: String
  ): IO[BatError, Unit] =
    ToyRuntime
      .git(
        repository,
        Seq("commit", "--no-verify", "--no-gpg-sign", "-m", message),
        environment = Map(
          "GIT_AUTHOR_NAME" -> "BAT Quickstart",
          "GIT_AUTHOR_EMAIL" -> "bat@invalid.local",
          "GIT_AUTHOR_DATE" -> date,
          "GIT_COMMITTER_NAME" -> "BAT Quickstart",
          "GIT_COMMITTER_EMAIL" -> "bat@invalid.local",
          "GIT_COMMITTER_DATE" -> date
        )
      )
      .unit

  private def relativeName(root: Path, path: Path): String =
    root.relativize(path).iterator().asScala.map(_.toString).mkString("/")

  private def transferDigest(
      input: InputStream,
      digest: MessageDigest
  ): Unit =
    val buffer = Array.ofDim[Byte](8192)
    var read = input.read(buffer)
    while read != -1 do
      if read > 0 then digest.update(buffer, 0, read)
      read = input.read(buffer)

  private final case class ToyFailure() extends RuntimeException

  private def failure(code: String, message: String): BatError =
    BatError.BackendFailure(code, message, retryable = false)

private[quickstart] final case class ToyCommandResult(
    exitCode: Int,
    stdout: Array[Byte],
    stderr: Array[Byte] = Array.emptyByteArray
):
  def output: Array[Byte] = stdout
  def combinedOutput: Array[Byte] = stdout ++ stderr

/** Small process and hashing substrate shared by the trusted quickstart fixture
  * materializer and its local tool algebra.
  */
private[quickstart] object ToyRuntime:
  private val MaxOutputBytes = 1024 * 1024
  private val CommandTimeout = Duration.fromSeconds(20)
  private val StartGate = "BAT_TOY_START\n".getBytes(StandardCharsets.US_ASCII)
  private val ProcessTemp =
    Path
      .of(java.lang.System.getProperty("java.io.tmpdir"))
      .toAbsolutePath
      .normalize
  private val Git = locate(
    "git",
    Seq("/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git")
  )

  val Javac: Path = locate(
    "javac",
    Seq(
      Path
        .of(java.lang.System.getProperty("java.home"), "bin", "javac")
        .toString
    )
  )
  val Java: Path = locate(
    "java",
    Seq(
      Path.of(java.lang.System.getProperty("java.home"), "bin", "java").toString
    )
  )

  def git(
      repository: Path,
      arguments: Seq[String],
      input: Option[String] = None,
      environment: Map[String, String] = Map.empty
  ): IO[BatError, ToyCommandResult] =
    command(
      Seq(
        Git.toString,
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "core.fsmonitor=false",
        "-c",
        "commit.gpgSign=false"
      ) ++ arguments,
      repository,
      input,
      CommandTimeout,
      environment ++ Map(
        "GIT_CONFIG_NOSYSTEM" -> "1",
        "GIT_CONFIG_GLOBAL" -> "/dev/null",
        "GIT_TERMINAL_PROMPT" -> "0",
        "GIT_OPTIONAL_LOCKS" -> "0",
        "GIT_NO_REPLACE_OBJECTS" -> "1"
      )
    ).flatMap { result =>
      if result.exitCode == 0 then ZIO.succeed(result)
      else
        ZIO.fail(
          BatError.BackendFailure(
            "toy_git_failed",
            "trusted toy Git operation failed",
            retryable = false
          )
        )
    }

  def head(repository: Path): IO[BatError, String] =
    git(repository, Seq("rev-parse", "--verify", "HEAD")).flatMap { result =>
      val value = String(result.output, StandardCharsets.US_ASCII).trim
      if value.matches("^(?:[0-9a-f]{40}|[0-9a-f]{64})$") then
        ZIO.succeed(value)
      else
        ZIO.fail(
          BatError.BackendFailure(
            "invalid_toy_head",
            "toy HEAD was not a canonical Git object ID",
            retryable = false
          )
        )
    }

  def command(
      argv: Seq[String],
      cwd: Path,
      input: Option[String] = None,
      timeout: Duration = CommandTimeout,
      environment: Map[String, String] = Map.empty
  ): IO[BatError, ToyCommandResult] =
    ZIO.scoped {
      ZIO
        .acquireRelease(start(argv, cwd, environment))(process =>
          stop(process, Iterable.empty)
        )
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
            stopAll = cleanup(process, tracked, tracker)
            timeoutExpired <- Promise.make[Nothing, Unit]
            _ <- (realSleep(timeout) *>
              timeoutExpired.succeed(()).unit *>
              stopAll).forkScoped
            combinedBytes = new AtomicLong(0L)
            exitCode <- Promise.make[Nothing, Int]
            stdout <- Promise.make[Nothing, Array[Byte]]
            stderr <- Promise.make[Nothing, Array[Byte]]
            execution =
              for
                _ <- ZIO.collectAllParDiscard(
                  List(
                    waitFor(process)
                      .tap(exitCode.succeed)
                      .unit
                      .tapError(_ => stopAll),
                    write(process.getOutputStream, input)
                      .tapError(_ => stopAll),
                    drain(
                      process.getInputStream,
                      "stdout",
                      combinedBytes
                    ).tap(stdout.succeed).unit.tapError(_ => stopAll),
                    drain(
                      process.getErrorStream,
                      "stderr",
                      combinedBytes
                    ).tap(stderr.succeed).unit.tapError(_ => stopAll)
                  )
                )
                exit <- exitCode.await
                out <- stdout.await
                err <- stderr.await
              yield ToyCommandResult(exit, out, err)
            result <- execution
              .raceFirst(
                timeoutExpired.await *>
                  ZIO.fail(
                    processFailure(
                      "toy_process_timeout",
                      "trusted toy process exceeded its timeout"
                    )
                  )
              )
              .ensuring(stopAll)
          yield result
        }
    }

  private def realSleep(timeout: Duration): UIO[Unit] =
    ZIO
      .attemptBlockingInterrupt(
        TimeUnit.NANOSECONDS.sleep(timeout.toNanos)
      )
      .orDie

  private def start(
      argv: Seq[String],
      cwd: Path,
      environment: Map[String, String]
  ): IO[BatError, Process] =
    ZIO
      .attemptBlockingInterrupt {
        val gatedArgv = Seq(
          "/bin/sh",
          "-c",
          "IFS= read -r _bat_gate; exec \"$@\"",
          "bat-toy-gate"
        ) ++ argv
        val builder = new ProcessBuilder(gatedArgv*)
        builder.directory(cwd.toFile)
        builder.redirectErrorStream(false)
        val processEnvironment = builder.environment()
        processEnvironment.clear()
        processEnvironment.put("LANG", "C")
        processEnvironment.put("LC_ALL", "C")
        processEnvironment.put("TMPDIR", ProcessTemp.toString)
        environment.foreach { case (key, value) =>
          processEnvironment.put(key, value)
        }
        builder.start()
      }
      .mapError(_ =>
        processFailure(
          "toy_process_failed",
          "trusted toy process could not start"
        )
      )

  private def waitFor(process: Process): IO[BatError, Int] =
    ZIO
      .attemptBlockingInterrupt(process.waitFor())
      .mapError(_ =>
        processFailure(
          "toy_process_failed",
          "trusted toy process could not be reaped"
        )
      )

  private def write(
      stream: OutputStream,
      input: Option[String]
  ): IO[BatError, Unit] =
    ZIO
      .attemptBlockingInterrupt {
        try
          stream.write(StartGate)
          input.foreach(text =>
            stream.write(text.getBytes(StandardCharsets.UTF_8))
          )
        finally closeQuietly(stream)
      }
      .mapError(_ =>
        processFailure(
          "toy_process_failed",
          "trusted toy process input could not be written"
        )
      )

  private def drain(
      stream: InputStream,
      label: String,
      combinedBytes: AtomicLong
  ): IO[BatError, Array[Byte]] =
    ZIO
      .attemptBlockingInterrupt {
        val output = ByteArrayOutputStream(math.min(MaxOutputBytes, 8192))
        val buffer = Array.ofDim[Byte](8192)
        try
          var read = stream.read(buffer)
          while read >= 0 do
            if read > 0 then
              val observed = combinedBytes.addAndGet(read.toLong)
              if observed > MaxOutputBytes then throw OutputLimitExceeded()
              output.write(buffer, 0, read)
            read = stream.read(buffer)
          output.toByteArray
        finally closeQuietly(stream)
      }
      .mapError {
        case _: OutputLimitExceeded =>
          processFailure(
            "toy_process_output_limit",
            "trusted toy process exceeded its output limit"
          )
        case _ =>
          processFailure(
            "toy_process_failed",
            s"trusted toy process $label could not be captured"
          )
      }

  private def cleanup(
      process: Process,
      tracked: ConcurrentHashMap[Long, ProcessHandle],
      tracker: Fiber[Nothing, Unit]
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
      val gracefulDeadline =
        java.lang.System.nanoTime() + Duration.fromMillis(100).toNanos
      while java.lang.System.nanoTime() < gracefulDeadline do
        observe()
        descendants.valuesIterator.foreach(handle =>
          if handle.isAlive then
            val _ = handle.destroy()
        )
        Thread.sleep(5L)
      val forcedDeadline =
        java.lang.System.nanoTime() + Duration.fromSeconds(2).toNanos
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
      awaitProcessStopped(process, Duration.fromMillis(100))
      if process.isAlive then process.destroy()
      awaitProcessStopped(process, Duration.fromMillis(100))
      if process.isAlive then
        val _ = process.destroyForcibly()
      awaitProcessStopped(process, Duration.fromMillis(500))
      descendants.valuesIterator.foreach(handle =>
        if handle.isAlive then
          val _ = handle.destroyForcibly()
      )
      awaitStopped(descendants.values, Duration.fromMillis(200))
      closeQuietly(process.getInputStream)
      closeQuietly(process.getErrorStream)
    }.ignore

  private def descendantsOf(process: Process): List[ProcessHandle] =
    val descendants = mutable.LinkedHashMap.empty[Long, ProcessHandle]
    try
      val direct = process.descendants()
      try
        direct
          .iterator()
          .asScala
          .foreach(handle =>
            val _ = descendants.update(handle.pid(), handle)
          )
      finally direct.close()
    catch case _: Exception => ()
    try
      val all = ProcessHandle.allProcesses()
      try
        all.iterator().asScala.foreach { handle =>
          if descendsFrom(handle, process.pid()) then
            val _ = descendants.update(handle.pid(), handle)
        }
      finally all.close()
    catch case _: Exception => ()
    descendants.values.toList

  private def descendsFrom(handle: ProcessHandle, rootPid: Long): Boolean =
    var cursor = handle.parent()
    var depth = 0
    while cursor.isPresent && depth < 1024 do
      val parent = cursor.get()
      if parent.pid() == rootPid then return true
      cursor = parent.parent()
      depth += 1
    false

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

  private def processFailure(code: String, message: String): BatError =
    BatError.BackendFailure(code, message, retryable = false)

  def sha256(bytes: Array[Byte]): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

  def updateDigest(digest: MessageDigest, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    updateDigest(digest, bytes.length.toLong)
    digest.update(bytes)

  def updateDigest(digest: MessageDigest, value: Long): Unit =
    digest.update(
      ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
    )

  def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString

  private def locate(name: String, preferred: Seq[String]): Path =
    val fromPreferred = preferred.iterator
      .map(Path.of(_))
      .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
    val fromPath = Option(java.lang.System.getenv("PATH")).toSeq
      .flatMap(_.split(java.io.File.pathSeparator).toSeq)
      .map(directory => Path.of(directory).resolve(name))
      .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
    fromPreferred
      .orElse(fromPath)
      .getOrElse(throw new IllegalStateException(s"$name executable not found"))

  private final case class OutputLimitExceeded() extends RuntimeException
