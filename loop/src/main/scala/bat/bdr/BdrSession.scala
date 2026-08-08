package bat.bdr

import bat.protocol.*

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json

final case class ValidatedBdrState(
    repository: Path,
    statePath: Path,
    view: BdrStateView
):
  def revision: Revision = view.revision
  def runState: String = view.runState
  def nextAction: Json.Obj = view.nextAction

final case class BdrConfig private (
    engineArgv: Chunk[String],
    engineSourceRoot: Path,
    engineEntryPoint: Path,
    repository: Path,
    statePath: Path,
    commandTimeout: Duration,
    actor: String,
    engineCommit: String
)

object BdrConfig:
  private val gitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r

  def make(
      engineArgv: Chunk[String],
      repository: Path,
      statePath: Path = Path.of(".bdr", "progress.yaml"),
      commandTimeout: Duration = 30.seconds,
      actor: String = "bat",
      engineCommit: String,
      engineSourceRoot: Path,
      engineEntryPoint: Path
  ): Either[BatError, BdrConfig] =
    if engineArgv.isEmpty || engineArgv.exists(part =>
        part == null || part.trim.isEmpty
      )
    then
      Left(
        BatError
          .BdrFailure("invalid_engine_command", "BDR command must be explicit")
      )
    else if engineSourceRoot == null then
      Left(
        BatError.BdrFailure(
          "invalid_engine_source_root",
          "BDR engine source root is required"
        )
      )
    else if engineEntryPoint == null || engineEntryPoint.isAbsolute ||
      engineEntryPoint.getNameCount == 0 ||
      engineEntryPoint
        .iterator()
        .asScala
        .exists(part => part.toString == ".." || part.toString == ".")
    then
      Left(
        BatError.BdrFailure(
          "invalid_engine_entry_point",
          "BDR engine entry point must stay inside its source repository"
        )
      )
    else if repository == null then
      Left(
        BatError.BdrFailure("invalid_repository", "BDR repository is required")
      )
    else if statePath == null || statePath.isAbsolute || statePath.getNameCount == 0 ||
      statePath.iterator().asScala.exists(_.toString == "..")
    then
      Left(
        BatError.BdrFailure(
          "invalid_state_path",
          "BDR state path must stay inside the repository"
        )
      )
    else if commandTimeout == null || commandTimeout == Duration.Infinity ||
      commandTimeout.isZero || commandTimeout.isNegative
    then
      Left(
        BatError.BdrFailure(
          "invalid_timeout",
          "BDR command timeout must be finite and positive"
        )
      )
    else if actor == null || actor.trim.isEmpty then
      Left(BatError.BdrFailure("invalid_actor", "BDR actor must be explicit"))
    else if engineCommit == null || !gitObject.matches(engineCommit) then
      Left(
        BatError.BdrFailure(
          "invalid_engine_commit",
          "BDR engine commit must be pinned"
        )
      )
    else
      Right(
        BdrConfig(
          engineArgv,
          engineSourceRoot.toAbsolutePath.normalize,
          engineEntryPoint.normalize,
          repository.toAbsolutePath.normalize,
          statePath.normalize,
          commandTimeout,
          actor,
          engineCommit
        )
      )

trait BdrSession:
  def engineCommit: String
  def actor: String
  def current: IO[BatError, ValidatedBdrState]
  def checkpoint: IO[BatError, ValidatedBdrState]
  def apply(operation: Json.Obj): IO[BatError, Json.Obj]
  def auditSummary: IO[BatError, Json]

object BdrSession:
  def resume(config: BdrConfig): IO[BatError, BdrSession] =
    resume(config, JdkProcessRunner, GitEngineIdentityVerifier)

  private[bdr] def resume(
      config: BdrConfig,
      runner: ProcessRunner,
      verifier: EngineIdentityVerifier
  ): IO[BatError, BdrSession] =
    for
      _ <- verifier.verify(config)
      _ <- ZIO
        .attemptBlocking(
          Files.isDirectory(config.repository, LinkOption.NOFOLLOW_LINKS)
        )
        .mapError(_ =>
          BatError
            .BdrFailure("invalid_repository", "cannot inspect BDR repository")
        )
        .filterOrFail(identity)(
          BatError.BdrFailure(
            "invalid_repository",
            "BDR repository is not a directory"
          )
        )
      live = new Live(config, runner)
      initial <- live.validatedSnapshot
      state <- Ref.Synchronized.make[SessionState](SessionState.Active(initial))
    yield live.withState(state)

  private enum SessionState:
    case Active(value: ValidatedBdrState)
    case Invalidated(failure: BatError)

  private final class Live(
      config: BdrConfig,
      runner: ProcessRunner,
      state: Option[Ref.Synchronized[SessionState]] = None
  ) extends BdrSession:
    def withState(ref: Ref.Synchronized[SessionState]): Live =
      new Live(config, runner, Some(ref))

    private def stateRef: Ref.Synchronized[SessionState] =
      state.getOrElse(
        throw new IllegalStateException("BDR session was not initialized")
      )

    val engineCommit: String = config.engineCommit
    val actor: String = config.actor

    def current: IO[BatError, ValidatedBdrState] =
      stateRef.get.flatMap {
        case SessionState.Active(value)       => ZIO.succeed(value)
        case SessionState.Invalidated(reason) => ZIO.fail(reason)
      }

    def checkpoint: IO[BatError, ValidatedBdrState] =
      stateRef
        .modifyZIO { _ =>
          validatedSnapshot.either.map {
            case Right(snapshot) =>
              Right(snapshot) -> SessionState.Active(snapshot)
            case Left(originalFailure) =>
              val failure = invalidatedFailure
              Left(originalFailure) -> SessionState.Invalidated(failure)
          }
        }
        .flatMap(ZIO.fromEither)

    def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
      val reserved = Set("expected_revision", "expectedRevision", "actor")
      val supplied = operation.fields.map(_._1).toSet.intersect(reserved)
      if supplied.nonEmpty then
        ZIO.fail(
          BatError.BdrFailure(
            "reserved_operation_field",
            s"BDR operation cannot control BAT-owned fields: ${supplied.toList.sorted.mkString(", ")}"
          )
        )
      else
        for
          input <- ZIO.fromEither(
            StrictJson.canonical(operation, "BDR operation")
          )
          result <- stateRef
            .modifyZIO {
              case invalid @ SessionState.Invalidated(failure) =>
                ZIO.succeed(Left(failure) -> invalid)
              case SessionState.Active(previous) =>
                applyValidated(previous, input).either.flatMap {
                  case Right((result, refreshed)) =>
                    ZIO.succeed(
                      Right(result) -> SessionState.Active(refreshed)
                    )
                  case Left(originalFailure) =>
                    // The engine may have committed before BAT observed a bad
                    // response or checkpoint. Reconcile before returning; if
                    // that is impossible, fail closed instead of retaining the
                    // pre-mutation cache.
                    validatedSnapshot.either.map {
                      case Right(refreshed) =>
                        Left(originalFailure) -> SessionState.Active(refreshed)
                      case Left(_) =>
                        val failure = invalidatedFailure
                        Left(failure) -> SessionState.Invalidated(failure)
                    }
                }
            }
            .flatMap(ZIO.fromEither)
        yield result

    private def applyValidated(
        previous: ValidatedBdrState,
        input: String
    ): IO[BatError, (Json.Obj, ValidatedBdrState)] =
      for
        response <- runJson(
          Chunk(
            "apply",
            "--state",
            stateArgument,
            "--expected-revision",
            previous.revision.value.toString,
            "--actor",
            config.actor,
            "-"
          ),
          Some(input),
          "bdr_apply"
        )
        result <- requireObject(response, "bdr apply output")
        revision <- requiredRevision(result, "bdr apply output")
        expected <- ZIO.fromEither(
          Revision.from(previous.revision.value + 1).left.map(identity)
        )
        _ <- ZIO
          .fail(
            BatError.BdrFailure(
              "invalid_revision_advance",
              "BDR mutation did not advance exactly one revision"
            )
          )
          .unless(revision == expected)
        refreshed <- validatedSnapshot
        _ <- ZIO
          .fail(
            BatError.BdrFailure(
              "stale_bdr_state",
              "BDR state changed while validating the accepted mutation"
            )
          )
          .unless(refreshed.revision == expected)
      yield result -> refreshed

    private def invalidatedFailure: BatError =
      BatError.BdrFailure(
        "bdr_session_invalidated",
        "BDR mutation outcome could not be validated; a fresh checkpoint is required"
      )

    def auditSummary: IO[BatError, Json] =
      runJson(
        Chunk("audit", "--state", stateArgument, "--summary"),
        None,
        "bdr_audit"
      )

    private[bdr] def validatedSnapshot: IO[BatError, ValidatedBdrState] =
      for
        checkedJson <- runJson(
          Chunk("check", "--state", stateArgument, "--json"),
          None,
          "bdr_check"
        )
        checked <- requireObject(checkedJson, "bdr check output")
        valid <- requiredBoolean(checked, "valid", "bdr check output")
        _ <- ZIO
          .fail(
            BatError.BdrFailure(
              "invalid_bdr_state",
              "BDR check did not return valid=true"
            )
          )
          .unless(valid)
        checkedRevision <- requiredRevision(checked, "bdr check output")
        checkedRunState <- requiredString(
          checked,
          "run_state",
          "bdr check output"
        )
        statusJson <- runJson(
          Chunk("status", "--state", stateArgument, "--next"),
          None,
          "bdr_status"
        )
        status <- requireObject(statusJson, "bdr status output")
        statusRevision <- requiredRevision(status, "bdr status output")
        statusRunState <- requiredString(
          status,
          "run_state",
          "bdr status output"
        )
        next <- requiredObject(status, "next", "bdr status output")
        _ <- ZIO
          .fail(
            BatError.BdrFailure(
              "stale_bdr_state",
              "BDR check and status revisions do not agree"
            )
          )
          .unless(checkedRevision == statusRevision)
        _ <- ZIO
          .fail(
            BatError.BdrFailure(
              "stale_bdr_state",
              "BDR check and status run states do not agree"
            )
          )
          .unless(checkedRunState == statusRunState)
        trackerBytes <- readTracker
        trackerText <- decodeUtf8(trackerBytes, "BDR tracker")
        trackerJson <- ZIO.fromEither(
          StrictJson.parseObject(trackerText, "BDR tracker")
        )
        trackerRevision <- requiredRevision(trackerJson, "BDR tracker")
        run <- requiredObject(trackerJson, "run", "BDR tracker")
        trackerRunState <- requiredString(run, "state", "BDR tracker run")
        _ <- ZIO
          .fail(
            BatError.BdrFailure(
              "stale_bdr_state",
              "BDR tracker changed after validation"
            )
          )
          .unless(
            trackerRevision == checkedRevision && trackerRunState == checkedRunState
          )
        digest = sha256(trackerBytes)
        view <- ZIO.fromEither(
          BdrStateView.make(checkedRevision, checkedRunState, next, digest)
        )
      yield ValidatedBdrState(config.repository, config.statePath, view)

    private def stateArgument: String = config.statePath.toString

    private def runJson(
        arguments: Chunk[String],
        input: Option[String],
        label: String
    ): IO[BatError, Json] =
      runner
        .run(
          config.engineArgv ++ arguments,
          config.repository,
          input,
          config.commandTimeout
        )
        .flatMap { result =>
          if result.exitCode != 0 then
            ZIO.fail(
              BatError.BdrFailure(
                s"${label}_failed",
                s"$label failed with exit code ${result.exitCode}"
              )
            )
          else
            decodeUtf8(result.stdout, s"$label output")
              .flatMap(text =>
                ZIO.fromEither(StrictJson.parse(text, s"$label output"))
              )
        }

    private def readTracker: IO[BatError, Array[Byte]] =
      val path = config.repository.resolve(config.statePath).normalize
      if !path.startsWith(config.repository) then
        ZIO.fail(
          BatError.BdrFailure(
            "invalid_state_path",
            "BDR state path escaped the repository"
          )
        )
      else
        ZIO
          .attemptBlockingInterrupt {
            var cursor = config.repository
            config.statePath.iterator().asScala.foreach { component =>
              cursor = cursor.resolve(component)
              if Files.isSymbolicLink(cursor) then
                throw new IllegalArgumentException(
                  "tracker path contains a symbolic link"
                )
            }
            if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
              throw new IllegalArgumentException(
                "tracker is not a regular file"
              )
            val channel = java.nio.channels.FileChannel.open(
              path,
              StandardOpenOption.READ,
              LinkOption.NOFOLLOW_LINKS
            )
            try
              val size = channel.size()
              if size > MaxOutputBytes then
                throw new IllegalArgumentException(
                  "tracker exceeds the read limit"
                )
              val buffer = ByteBuffer.allocate(size.toInt)
              while buffer.hasRemaining && channel.read(buffer) >= 0 do ()
              buffer.array()
            finally channel.close()
          }
          .mapError(_ =>
            BatError.BdrFailure(
              "tracker_read_failed",
              "cannot read validated BDR state"
            )
          )

  private val MaxOutputBytes = 64L * 1024L * 1024L

  private def requireObject(
      value: Json,
      label: String
  ): IO[BatError, Json.Obj] =
    value match
      case obj: Json.Obj => ZIO.succeed(obj)
      case _             =>
        ZIO.fail(
          BatError.BdrFailure("invalid_json_shape", s"$label must be an object")
        )

  private def field(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.collectFirst { case (`name`, value) => value }

  private def requiredObject(
      obj: Json.Obj,
      name: String,
      label: String
  ): IO[BatError, Json.Obj] =
    field(obj, name) match
      case Some(value: Json.Obj) => ZIO.succeed(value)
      case _                     =>
        ZIO.fail(
          BatError.BdrFailure(
            "invalid_json_shape",
            s"$label.$name must be an object"
          )
        )

  private def requiredString(
      obj: Json.Obj,
      name: String,
      label: String
  ): IO[BatError, String] =
    field(obj, name) match
      case Some(Json.Str(value)) if value.trim.nonEmpty => ZIO.succeed(value)
      case _                                            =>
        ZIO.fail(
          BatError.BdrFailure(
            "invalid_json_shape",
            s"$label.$name must be a string"
          )
        )

  private def requiredBoolean(
      obj: Json.Obj,
      name: String,
      label: String
  ): IO[BatError, Boolean] =
    field(obj, name) match
      case Some(Json.Bool(value)) => ZIO.succeed(value)
      case _                      =>
        ZIO.fail(
          BatError.BdrFailure(
            "invalid_json_shape",
            s"$label.$name must be boolean"
          )
        )

  private def requiredRevision(
      obj: Json.Obj,
      label: String
  ): IO[BatError, Revision] =
    field(obj, "revision") match
      case Some(Json.Num(value)) =>
        ZIO
          .attempt(value.longValueExact())
          .mapError(_ =>
            BatError.BdrFailure(
              "invalid_json_shape",
              s"$label.revision must be an integer"
            )
          )
          .flatMap(value => ZIO.fromEither(Revision.from(value)))
      case _ =>
        ZIO.fail(
          BatError.BdrFailure(
            "invalid_json_shape",
            s"$label.revision must be a number"
          )
        )

  private def decodeUtf8(
      bytes: Array[Byte],
      label: String
  ): IO[BatError, String] =
    ZIO
      .attempt {
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      }
      .mapError(_ =>
        BatError.BdrFailure("invalid_utf8", s"$label is not UTF-8")
      )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

private[bdr] final case class ProcessResult(
    exitCode: Int,
    stdout: Array[Byte],
    stderr: Array[Byte]
)

private[bdr] trait ProcessRunner:
  def run(
      command: Chunk[String],
      cwd: Path,
      input: Option[String],
      timeout: Duration
  ): IO[BatError, ProcessResult]

private[bdr] trait EngineIdentityVerifier:
  def verify(config: BdrConfig): IO[BatError, Unit]

private[bdr] object GitEngineIdentityVerifier extends EngineIdentityVerifier:
  def verify(config: BdrConfig): IO[BatError, Unit] =
    for
      root <- canonicalRoot(config.engineSourceRoot)
      entryPoint <- canonicalEntryPoint(
        root,
        config.engineEntryPoint
      )
      _ <- ZIO
        .fail(identityMismatch)
        .unless(argvBindsEntryPoint(config.engineArgv, entryPoint))
      gitRoot <- gitText(
        root,
        config.commandTimeout,
        Chunk("rev-parse", "--show-toplevel")
      )
      canonicalGitRoot <- ZIO
        .attemptBlockingInterrupt(Path.of(gitRoot).toRealPath())
        .mapError(_ => identityMismatch)
      _ <- ZIO.fail(identityMismatch).unless(canonicalGitRoot == root)
      head <- gitText(
        root,
        config.commandTimeout,
        Chunk("rev-parse", "--verify", "HEAD")
      )
      _ <- ZIO.fail(identityMismatch).unless(head == config.engineCommit)
      relativeEntry = root.relativize(entryPoint).toString
      _ <- gitSuccess(
        root,
        config.commandTimeout,
        Chunk("ls-files", "--error-unmatch", "--", relativeEntry)
      )
      status <- gitText(
        root,
        config.commandTimeout,
        Chunk("status", "--porcelain=v1", "--untracked-files=all")
      )
      _ <- ZIO.fail(identityMismatch).unless(status.isEmpty)
    yield ()

  private def canonicalRoot(root: Path): IO[BatError, Path] =
    ZIO
      .attemptBlockingInterrupt {
        if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) then
          throw new IllegalArgumentException("not a directory")
        // The configured directory itself may not be a symlink, but macOS
        // exposes the temporary directory through /var -> /private/var.
        // Canonicalize allowed ancestor aliases before comparing Git paths.
        root.toRealPath()
      }
      .mapError(_ => identityMismatch)

  private def canonicalEntryPoint(
      root: Path,
      relative: Path
  ): IO[BatError, Path] =
    ZIO
      .attemptBlockingInterrupt {
        var cursor = root
        relative.iterator().asScala.foreach { component =>
          cursor = cursor.resolve(component)
          if Files.isSymbolicLink(cursor) then
            throw new IllegalArgumentException("symlinked engine path")
        }
        val canonical = cursor.toRealPath(LinkOption.NOFOLLOW_LINKS)
        if !canonical.startsWith(root) ||
          !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)
        then throw new IllegalArgumentException("invalid engine entry point")
        canonical
      }
      .mapError(_ => identityMismatch)

  private def argvBindsEntryPoint(
      argv: Chunk[String],
      entryPoint: Path
  ): Boolean =
    argv.exists { argument =>
      try
        Path
          .of(argument)
          .toAbsolutePath
          .normalize
          .toRealPath() == entryPoint
      catch case _: Exception => false
    }

  private def gitSuccess(
      root: Path,
      timeout: Duration,
      arguments: Chunk[String]
  ): IO[BatError, ProcessResult] =
    JdkProcessRunner
      .run(Chunk("git", "-C", root.toString) ++ arguments, root, None, timeout)
      .flatMap(result =>
        ZIO.fail(identityMismatch).unless(result.exitCode == 0).as(result)
      )
      .mapError(_ => identityMismatch)

  private def gitText(
      root: Path,
      timeout: Duration,
      arguments: Chunk[String]
  ): IO[BatError, String] =
    gitSuccess(root, timeout, arguments).flatMap(result =>
      ZIO
        .attempt {
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(result.stdout))
            .toString
            .trim
        }
        .mapError(_ => identityMismatch)
    )

  private def identityMismatch: BatError =
    BatError.BdrFailure(
      "engine_identity_mismatch",
      "BDR engine does not match the pinned source identity"
    )

private[bdr] object JdkProcessRunner extends ProcessRunner:
  private val MaxCapturedBytes = 64 * 1024 * 1024

  def run(
      command: Chunk[String],
      cwd: Path,
      input: Option[String],
      timeout: Duration
  ): IO[BatError, ProcessResult] =
    ZIO
      .scoped {
        for
          process <- ZIO.acquireRelease(start(command, cwd))(process =>
            stop(process, Iterable.empty)
          )
          tracked <- Ref.make(Map.empty[Long, ProcessHandle])
          tracker <- trackDescendants(process, tracked).forkDaemon
          stdout <- drain(process.getInputStream, "stdout").forkDaemon
          stderr <- drain(process.getErrorStream, "stderr").forkDaemon
          writer <- write(process.getOutputStream, input).forkDaemon
          result <- (for
            exit <- ZIO
              .attemptBlockingInterrupt(process.waitFor())
              .mapError(_ =>
                BatError.BdrFailure(
                  "process_wait_failed",
                  "BDR process could not be reaped"
                )
              )
            _ <- writer.join
            out <- stdout.join
            err <- stderr.join
          yield ProcessResult(exit, out, err)).ensuring(
            cleanup(process, tracked, tracker, writer, stdout, stderr)
          )
        yield result
      }
      .timeoutFail(
        BatError
          .BdrFailure("process_timeout", "BDR command exceeded its timeout")
      )(timeout)

  private def start(command: Chunk[String], cwd: Path): IO[BatError, Process] =
    ZIO
      .attemptBlockingInterrupt {
        val builder = new ProcessBuilder(command*)
        builder.directory(cwd.toFile)
        builder.redirectErrorStream(false)
        builder.start()
      }
      .mapError(_ =>
        BatError
          .BdrFailure("process_start_failed", "BDR process could not start")
      )

  private def cleanup(
      process: Process,
      tracked: Ref[Map[Long, ProcessHandle]],
      tracker: Fiber[Nothing, Nothing],
      writer: Fiber[BatError, Unit],
      stdout: Fiber[BatError, Array[Byte]],
      stderr: Fiber[BatError, Array[Byte]]
  ): UIO[Unit] =
    for
      descendants <- tracked.get
      _ <- stop(process, descendants.values)
      _ <- tracker.interrupt
      _ <- writer.interrupt
      _ <- stdout.interrupt
      _ <- stderr.interrupt
    yield ()

  private def trackDescendants(
      process: Process,
      tracked: Ref[Map[Long, ProcessHandle]]
  ): UIO[Nothing] =
    (ZIO
      .attemptBlockingInterrupt {
        process.descendants().iterator().asScala.toList
      }
      .orElseSucceed(Nil)
      .flatMap(handles =>
        tracked.update(previous =>
          previous ++ handles.map(handle => handle.pid() -> handle)
        )
      ) *> ZIO.sleep(10.millis)).forever

  private def stop(
      process: Process,
      previouslyObserved: Iterable[ProcessHandle]
  ): UIO[Unit] =
    ZIO.attemptBlockingInterrupt {
      closeQuietly(process.getOutputStream)
      val current =
        try process.descendants().iterator().asScala.toList
        catch case _: Exception => Nil
      val descendants =
        (previouslyObserved ++ current).toList
          .groupBy(_.pid())
          .values
          .map(_.head)
      descendants.foreach(handle =>
        if handle.isAlive then
          val _ = handle.destroy()
      )
      if process.isAlive then process.destroy()
      descendants.foreach(handle =>
        if handle.isAlive then
          val _ = handle.destroyForcibly()
      )
      if process.isAlive then
        val _ = process.destroyForcibly()
      closeQuietly(process.getInputStream)
      closeQuietly(process.getErrorStream)
    }.ignore

  private def write(
      stream: OutputStream,
      input: Option[String]
  ): IO[BatError, Unit] =
    ZIO
      .attemptBlockingInterrupt {
        try
          input.foreach(text =>
            stream.write(text.getBytes(StandardCharsets.UTF_8))
          )
        finally stream.close()
      }
      .mapError(_ =>
        BatError
          .BdrFailure("process_input_failed", "cannot write BDR command input")
      )

  private def drain(
      stream: InputStream,
      label: String
  ): IO[BatError, Array[Byte]] =
    ZIO
      .attemptBlockingInterrupt {
        val output = new ByteArrayOutputStream()
        val buffer = new Array[Byte](8192)
        var total = 0L
        var overflow = false
        var read = stream.read(buffer)
        while read >= 0 do
          if read > 0 then
            if total + read <= MaxCapturedBytes then
              output.write(buffer, 0, read)
            else overflow = true
            total += read
          read = stream.read(buffer)
        if overflow then
          throw new IllegalArgumentException(s"$label exceeded capture limit")
        output.toByteArray
      }
      .mapError(_ =>
        BatError.BdrFailure(
          "process_output_failed",
          s"BDR $label could not be captured"
        )
      )

  private def closeQuietly(closeable: AutoCloseable): Unit =
    try closeable.close()
    catch case _: Exception => ()
