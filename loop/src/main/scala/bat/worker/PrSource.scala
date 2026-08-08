package bat.worker

import java.io.ByteArrayOutputStream
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, LinkOption, Path}
import java.util.concurrent.Executors

import scala.concurrent.duration.Duration as ScalaDuration

import zio.{Chunk, Duration, IO, ZIO}

trait PullRequestAuthority:
  def resolve(
      baseRepository: RepositoryId,
      pullRequestId: PullRequestId
  ): IO[WorkerError, PullRequestPins]

object AuthenticatedPrSource:
  def resolve(
      authority: PullRequestAuthority,
      baseRepository: RepositoryId,
      pullRequestId: PullRequestId
  ): IO[WorkerError, PullRequestPins] =
    authority.resolve(baseRepository, pullRequestId).flatMap { pins =>
      if pins.baseRepository != baseRepository ||
        pins.pullRequestId != pullRequestId
      then
        ZIO.fail(
          WorkerError.SourceRejected(
            "pr_identity_mismatch",
            "PR authority returned metadata for a different pull request"
          )
        )
      else ZIO.succeed(pins)
    }

  def requireFresh(
      authority: PullRequestAuthority,
      expected: PullRequestPins
  ): IO[WorkerError, Unit] =
    resolve(
      authority,
      expected.baseRepository,
      expected.pullRequestId
    ).flatMap { current =>
      if current == expected then ZIO.unit
      else
        ZIO.fail(
          WorkerError.SourceRejected(
            "stale_pr_input",
            "pull request base or head changed after this run was pinned"
          )
        )
    }

final case class GitInvocation(cwd: Path, arguments: Chunk[String])

final case class GitResult(exitCode: Int, output: String)

trait GitRunner:
  def run(invocation: GitInvocation): IO[WorkerError, GitResult]

final case class GitRunnerConfig private (
    gitBinary: Path,
    timeout: Duration,
    maxOutputBytes: Int
)

object GitRunnerConfig:
  def make(
      gitBinary: Path,
      timeout: Duration = Duration.fromSeconds(15),
      maxOutputBytes: Int = 1024 * 1024
  ): Either[WorkerError, GitRunnerConfig] =
    if gitBinary == null || !gitBinary.isAbsolute then
      Left(
        WorkerError.InvalidInput(
          "invalid_git_binary",
          "Git executable must be an absolute path"
        )
      )
    else if timeout == null || timeout == Duration.Infinity || timeout.isZero ||
      timeout.isNegative
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_git_timeout",
          "Git timeout must be finite and positive"
        )
      )
    else if maxOutputBytes <= 0 || maxOutputBytes > 4 * 1024 * 1024 then
      Left(
        WorkerError.InvalidInput(
          "invalid_git_output_limit",
          "Git output limit must be between 1 byte and 4 MiB"
        )
      )
    else
      try
        val _ = timeout.toMillis
        Right(GitRunnerConfig(gitBinary.normalize, timeout, maxOutputBytes))
      catch
        case _: ArithmeticException =>
          Left(
            WorkerError.InvalidInput(
              "invalid_git_timeout",
              "Git timeout is too large"
            )
          )

object GitRunner:
  def live(config: GitRunnerConfig): GitRunner = LiveGitRunner(config)

  private final class LiveGitRunner(config: GitRunnerConfig) extends GitRunner:
    def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
      ZIO
        .attemptBlockingInterrupt {
          val command =
            Chunk(
              config.gitBinary.toString,
              "-c",
              "core.hooksPath=/dev/null",
              "-c",
              "core.fsmonitor=false",
              "-c",
              "filter.lfs.smudge=",
              "-c",
              "filter.lfs.required=false"
            ) ++ invocation.arguments
          val builder = ProcessBuilder(command*)
          builder.directory(invocation.cwd.toFile)
          builder.redirectErrorStream(true)
          val environment = builder.environment()
          environment.clear()
          environment.put("GIT_CONFIG_NOSYSTEM", "1")
          environment.put("GIT_CONFIG_GLOBAL", "/dev/null")
          environment.put("GIT_TERMINAL_PROMPT", "0")
          environment.put("GIT_OPTIONAL_LOCKS", "0")
          environment.put("GIT_LFS_SKIP_SMUDGE", "1")
          environment.put("GIT_NO_REPLACE_OBJECTS", "1")
          environment.put("HOME", "/nonexistent")
          environment.put("LC_ALL", "C")
          val process = builder.start()
          process.getOutputStream.close()
          val executor = Executors.newVirtualThreadPerTaskExecutor()
          try
            val outputFuture = executor.submit(() =>
              readBounded(process.getInputStream, config.maxOutputBytes)
            )
            val completed = process.waitFor(
              config.timeout.toMillis,
              java.util.concurrent.TimeUnit.MILLISECONDS
            )
            if !completed then
              process.destroyForcibly()
              process.waitFor()
              throw GitProcessFailure("git command timed out")
            val (bytes, overflow) = outputFuture.get()
            if overflow then
              throw GitProcessFailure("git output exceeded limit")
            GitResult(process.exitValue(), decodeUtf8(bytes))
          finally
            if process.isAlive then
              val _ = process.destroyForcibly()
            val _ = executor.shutdownNow()
        }
        .mapError(_ =>
          WorkerError.SourceRejected(
            "git_inspection_failed",
            "trusted Git inspection could not be completed"
          )
        )

  private def readBounded(
      input: java.io.InputStream,
      limit: Int
  ): (Array[Byte], Boolean) =
    val output = ByteArrayOutputStream(math.min(limit, 8192))
    val buffer = Array.ofDim[Byte](8192)
    var overflow = false
    var read = input.read(buffer)
    while read != -1 do
      val remaining = limit - output.size()
      if remaining > 0 then output.write(buffer, 0, math.min(remaining, read))
      if read > remaining then overflow = true
      read = input.read(buffer)
    output.toByteArray -> overflow

  private def decodeUtf8(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(java.nio.ByteBuffer.wrap(bytes))
      .toString

  private final case class GitProcessFailure(message: String)
      extends RuntimeException(message)

trait PinnedGitSource:
  def verify(repository: Path, pins: PullRequestPins): IO[WorkerError, Unit]

private[worker] object GitConfigurationGuard:
  val inspectionArguments: Chunk[String] = Chunk(
    "config",
    "--local",
    "--null",
    "--name-only",
    "--list",
    "--no-includes"
  )

  def verifySource(
      repository: Path,
      runner: GitRunner
  ): IO[WorkerError, Unit] = verify(repository, runner, rejectGrafts = false)

  def verifyWorkspace(
      repository: Path,
      runner: GitRunner
  ): IO[WorkerError, Unit] = verify(repository, runner, rejectGrafts = true)

  private def verify(
      repository: Path,
      runner: GitRunner,
      rejectGrafts: Boolean
  ): IO[WorkerError, Unit] =
    for
      _ <- requireRegularLocalConfig(repository, rejectGrafts)
      result <- runner.run(GitInvocation(repository, inspectionArguments))
      _ <-
        if result.exitCode == 0 then ZIO.unit
        else
          ZIO.fail(
            WorkerError.SourceRejected(
              "git_configuration_inspection_failed",
              "Git local configuration could not be inspected safely"
            )
          )
      keys = result.output
        .split("\u0000", -1)
        .iterator
        .map(_.trim.toLowerCase(java.util.Locale.ROOT))
        .filter(_.nonEmpty)
        .toList
      _ <-
        if keys.exists(isExecutableConfiguration) then
          ZIO.fail(
            WorkerError.SourceRejected(
              "unsafe_git_configuration",
              "repository has executable or host-reading Git configuration"
            )
          )
        else ZIO.unit
    yield ()

  private def requireRegularLocalConfig(
      repository: Path,
      rejectGrafts: Boolean
  ): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val gitDirectory = repository.resolve(".git")
        val config = gitDirectory.resolve("config")
        val objectsInfo = gitDirectory.resolve("objects").resolve("info")
        val alternates = objectsInfo.resolve("alternates")
        val httpAlternates = objectsInfo.resolve("http-alternates")
        val commonDirectory = gitDirectory.resolve("commondir")
        val grafts = gitDirectory.resolve("info").resolve("grafts")
        val unsafeGrafts = rejectGrafts &&
          Files.exists(grafts, LinkOption.NOFOLLOW_LINKS) &&
          (!Files.isRegularFile(grafts, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(grafts) > 0L)
        if Files.isSymbolicLink(gitDirectory) ||
          !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(config) ||
          !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) ||
          Files.exists(alternates, LinkOption.NOFOLLOW_LINKS) ||
          Files.exists(httpAlternates, LinkOption.NOFOLLOW_LINKS) ||
          Files.exists(commonDirectory, LinkOption.NOFOLLOW_LINKS) ||
          unsafeGrafts
        then throw new IllegalStateException("unsafe local configuration")
      }
      .mapError(_ =>
        WorkerError.SourceRejected(
          "unsafe_git_configuration",
          "repository local Git metadata could invoke or read outside the workspace"
        )
      )

  private def isExecutableConfiguration(key: String): Boolean =
    key.startsWith("filter.") ||
      key.startsWith("include.") ||
      key.startsWith("includeif.") ||
      key == "core.attributesfile" ||
      key == "core.excludesfile" ||
      key == "core.fsmonitor" ||
      key == "core.worktree" ||
      key == "core.hookspath" ||
      key == "core.sshcommand" ||
      key == "core.alternaterefscommand" ||
      key == "extensions.partialclone" ||
      key == "extensions.worktreeconfig" ||
      key == "uploadpack.packobjectshook" ||
      key.endsWith(".promisor") ||
      key.endsWith(".partialclonefilter") ||
      (key.startsWith("credential.") && key.endsWith(".helper")) ||
      (key.startsWith("diff.") &&
        (key.endsWith(".command") || key.endsWith(".textconv"))) ||
      (key.startsWith("merge.") && key.endsWith(".driver"))

object PinnedGitSource:
  def live(runner: GitRunner): PinnedGitSource = LivePinnedGitSource(runner)

  private final class LivePinnedGitSource(runner: GitRunner)
      extends PinnedGitSource:
    def verify(
        repository: Path,
        pins: PullRequestPins
    ): IO[WorkerError, Unit] =
      for
        normalized <- validateRepository(repository)
        _ <- GitConfigurationGuard.verifySource(normalized, runner)
        _ <- requireOutput(
          normalized,
          Chunk("rev-parse", "--is-bare-repository"),
          "false",
          "bare_repository"
        )
        _ <- requireOutput(
          normalized,
          Chunk("rev-parse", "--is-shallow-repository"),
          "false",
          "shallow_repository"
        )
        replacements <- successful(
          normalized,
          Chunk("for-each-ref", "--format=%(objectname)", "refs/replace")
        )
        _ <- rejectIf(
          replacements.output.trim.nonEmpty,
          "replacement_objects",
          "source repository has replacement objects"
        )
        _ <- rejectGrafts(normalized)
        status <- successful(
          normalized,
          Chunk(
            "status",
            "--porcelain=v2",
            "--untracked-files=all",
            "--ignore-submodules=all"
          )
        )
        _ <- rejectIf(
          status.output.nonEmpty,
          "dirty_source_repository",
          "source repository is not clean"
        )
        _ <- requireRef(normalized, pins.baseRef, pins.baseCommit, "base")
        _ <- requireRef(normalized, pins.headRef, pins.headCommit, "head")
        _ <- requireCommit(normalized, pins.baseCommit, "base")
        _ <- requireCommit(normalized, pins.headCommit, "head")
        ancestry <- runner.run(
          GitInvocation(
            normalized,
            Chunk(
              "merge-base",
              "--is-ancestor",
              pins.baseCommit.value,
              pins.headCommit.value
            )
          )
        )
        _ <- ancestry.exitCode match
          case 0 => ZIO.unit
          case 1 =>
            ZIO.fail(
              WorkerError.SourceRejected(
                "base_not_ancestor",
                "pinned PR base is not an ancestor of its head"
              )
            )
          case _ => commandFailure("Git could not verify PR ancestry")
      yield ()

    private def validateRepository(repository: Path): IO[WorkerError, Path] =
      ZIO
        .attemptBlocking {
          if repository == null then
            throw new IllegalArgumentException("repository is required")
          val normalized = repository.toAbsolutePath.normalize
          val gitDirectory = normalized.resolve(".git")
          val gitConfig = gitDirectory.resolve("config")
          val objectsInfo = gitDirectory.resolve("objects").resolve("info")
          val alternates = objectsInfo.resolve("alternates")
          val httpAlternates = objectsInfo.resolve("http-alternates")
          val commonDirectory = gitDirectory.resolve("commondir")
          if Files.isSymbolicLink(normalized) ||
            !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(gitDirectory) ||
            !Files.isDirectory(gitDirectory, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(gitConfig) ||
            !Files.isRegularFile(gitConfig, LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(alternates, LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(httpAlternates, LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(commonDirectory, LinkOption.NOFOLLOW_LINKS)
          then throw new IllegalStateException("repository is not standalone")
          normalized
        }
        .mapError(_ =>
          WorkerError.SourceRejected(
            "invalid_source_repository",
            "source must be a standalone non-symlink Git worktree"
          )
        )

    private def rejectGrafts(repository: Path): IO[WorkerError, Unit] =
      ZIO
        .attemptBlocking {
          val grafts =
            repository.resolve(".git").resolve("info").resolve("grafts")
          Files.exists(grafts, LinkOption.NOFOLLOW_LINKS) &&
          (!Files.isRegularFile(grafts, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(grafts) > 0L)
        }
        .mapError(_ =>
          WorkerError.SourceRejected(
            "graft_inspection_failed",
            "source graft configuration could not be inspected"
          )
        )
        .flatMap(
          rejectIf(
            _,
            "grafted_history",
            "source repository has grafted history"
          )
        )

    private def requireRef(
        repository: Path,
        ref: GitRef,
        commit: GitObjectId,
        label: String
    ): IO[WorkerError, Unit] =
      successful(
        repository,
        Chunk("show-ref", "--verify", "--hash", ref.value)
      ).flatMap { result =>
        rejectIf(
          result.output.trim != commit.value,
          s"stale_${label}_ref",
          s"pinned PR $label ref no longer matches its object ID"
        )
      }

    private def requireCommit(
        repository: Path,
        commit: GitObjectId,
        label: String
    ): IO[WorkerError, Unit] =
      runner
        .run(
          GitInvocation(
            repository,
            Chunk("cat-file", "-e", s"${commit.value}^{commit}")
          )
        )
        .flatMap { result =>
          rejectIf(
            result.exitCode != 0,
            s"invalid_${label}_commit",
            s"pinned PR $label object is not a commit"
          )
        }

    private def requireOutput(
        repository: Path,
        arguments: Chunk[String],
        expected: String,
        code: String
    ): IO[WorkerError, Unit] =
      successful(repository, arguments).flatMap { result =>
        rejectIf(
          result.output.trim != expected,
          code,
          "source repository uses unsupported Git history state"
        )
      }

    private def successful(
        repository: Path,
        arguments: Chunk[String]
    ): IO[WorkerError, GitResult] =
      runner.run(GitInvocation(repository, arguments)).flatMap { result =>
        if result.exitCode == 0 then ZIO.succeed(result)
        else commandFailure("trusted Git inspection returned an error")
      }

    private def rejectIf(
        condition: Boolean,
        code: String,
        message: String
    ): IO[WorkerError, Unit] =
      if condition then ZIO.fail(WorkerError.SourceRejected(code, message))
      else ZIO.unit

    private def commandFailure(message: String): IO[WorkerError, Nothing] =
      ZIO.fail(
        WorkerError.SourceRejected("git_inspection_failed", message)
      )
