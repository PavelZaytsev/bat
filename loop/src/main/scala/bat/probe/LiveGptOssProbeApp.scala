package bat.probe

import java.nio.file.{Files, LinkOption, Path}

import scala.util.control.NonFatal

import bat.transport.StreamingHttp
import bat.worker.{GitInvocation, GitRunner, GitRunnerConfig}

import zio.http.Client
import zio.{ExitCode, Scope, ZIO, ZIOAppDefault}

/** Explicitly armed live entry point. All configuration comes from fixed
  * environment keys so credentials and private endpoints never enter process
  * arguments. Normal CI exercises the same runner against a loopback server; it
  * does not invoke this application.
  */
object LiveGptOssProbeApp extends ZIOAppDefault:
  override def run: ZIO[Any, Any, Any] =
    execute.foldZIO(
      error =>
        zio.Console
          .printLineError(s"BAT GPT-OSS probe failed: ${error.code}")
          .orDie *> exit(ExitCode(exitCode(error))),
      artifact =>
        val reason =
          artifact.reasonCode.fold("")(value => s" reason=${value.value}")
        zio.Console
          .printLine(
            s"BAT GPT-OSS probe verdict=${artifact.verdict.wire}$reason; artifacts published"
          )
          .orDie *> exit(ExitCode(exitCode(artifact.verdict)))
    )

  private[probe] def execute: ZIO[Any, ProbeError, ProbeResultArtifact] =
    for
      environment <- readEnvironment
      loaded <- ZIO.fromEither(ProbeEnvironment.from(environment))
      projectRoot <- ZIO
        .attemptBlocking(
          Path.of("").toAbsolutePath.normalize().toRealPath()
        )
        .mapError(_ =>
          ProbeError.make(
            "probe_project_root_unavailable",
            "BAT project root could not be resolved"
          )
        )
      checkoutRoot <- resolveBatCheckout(
        projectRoot,
        Path.of("/usr/bin/git")
      )
      _ <- verifyBatCommit(
        checkoutRoot,
        loaded.config.batCommit,
        Path.of("/usr/bin/git")
      )
      _ <- verifyCleanCheckout(checkoutRoot, Path.of("/usr/bin/git"))
      artifact <- ZIO.scoped(runPrepared(loaded, checkoutRoot))
    yield artifact

  private def runPrepared(
      loaded: LoadedProbeEnvironment,
      projectRoot: Path
  ): ZIO[Scope, ProbeError, ProbeResultArtifact] =
    for
      prepared <- ProbeArtifactWriter.prepare(
        loaded.config.outputDirectory,
        projectRoot
      )
      artifact <- ZIO
        .serviceWithZIO[StreamingHttp](http =>
          LiveGptOssProbe.run(loaded.config, http)
        )
        .provideLayer(
          Client.default >>> StreamingHttp.configured(
            loaded.config.transportConfig
          )
        )
        .catchAllCause { cause =>
          if cause.isInterrupted then
            ZIO.refailCause(cause.map {
              case error: ProbeError => error
              case _                 => httpClientFailure
            })
          else
            cause.failureOption match
              case Some(error: ProbeError) => ZIO.fail(error)
              case _                       => ZIO.fail(httpClientFailure)
        }
      _ <- prepared.publish(
        artifact,
        loaded.forbiddenArtifactValues ++ zio.Chunk(projectRoot.toString)
      )
    yield artifact

  private def readEnvironment: ZIO[Any, ProbeError, Map[String, String]] =
    ZIO
      .foreach(ProbeEnvironment.Keys)(key =>
        zio.System.env(key).map(_.map(value => key -> value))
      )
      .map(_.flatten.toMap)
      .mapError(_ =>
        ProbeError.make(
          "probe_environment_unavailable",
          "live probe environment could not be read"
        )
      )

  private[probe] def verifyBatCommit(
      projectRoot: Path,
      expected: ProbeBatCommit,
      gitBinary: Path
  ): ZIO[Any, ProbeError, Unit] =
    for
      runner <- gitRunner(gitBinary)
      result <- runner
        .run(
          GitInvocation(
            projectRoot,
            zio.Chunk("rev-parse", "--verify", "HEAD^{commit}")
          )
        )
        .mapError(_ =>
          ProbeError.make(
            "probe_git_inspection_failed",
            "trusted BAT Git inspection failed"
          )
        )
      commits = result.output.linesIterator
        .map(_.trim)
        .filter("^[0-9a-f]{40}$".r.matches)
        .toList
      _ <- ZIO
        .fail(
          ProbeError.make(
            "probe_bat_commit_mismatch",
            "configured BAT commit does not match the running checkout"
          )
        )
        .unless(result.exitCode == 0 && commits == List(expected.value))
    yield ()

  private[probe] def resolveBatCheckout(
      workingDirectory: Path,
      gitBinary: Path
  ): ZIO[Any, ProbeError, Path] =
    for
      runner <- gitRunner(gitBinary)
      result <- runner
        .run(
          GitInvocation(
            workingDirectory,
            zio.Chunk("rev-parse", "--show-toplevel")
          )
        )
        .mapError(_ => gitInspectionFailure)
      roots <- ZIO
        .attemptBlocking {
          safeGitLines(result.output).flatMap { line =>
            try
              val candidate = Path.of(line)
              if candidate.isAbsolute then Some(candidate.toRealPath())
              else None
            catch case NonFatal(_) => None
          }
        }
        .mapError(_ => gitInspectionFailure)
      root <- ZIO
        .fromOption(roots.distinct match
          case value :: Nil => Some(value)
          case _            => None)
        .orElseFail(gitInspectionFailure)
      _ <- ZIO
        .fail(gitInspectionFailure)
        .unless(
          result.exitCode == 0 &&
            workingDirectory.startsWith(root) &&
            Files.exists(root.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
        )
    yield root

  private[probe] def verifyCleanCheckout(
      projectRoot: Path,
      gitBinary: Path
  ): ZIO[Any, ProbeError, Unit] =
    for
      runner <- gitRunner(gitBinary)
      result <- runner
        .run(
          GitInvocation(
            projectRoot,
            zio.Chunk(
              "status",
              "--porcelain=v1",
              "--untracked-files=all"
            )
          )
        )
        .mapError(_ => gitInspectionFailure)
      _ <- ZIO
        .fail(
          ProbeError.make(
            "probe_dirty_checkout",
            "live probe requires a clean BAT checkout"
          )
        )
        .unless(result.exitCode == 0 && safeGitLines(result.output).isEmpty)
    yield ()

  private def gitRunner(gitBinary: Path): ZIO[Any, ProbeError, GitRunner] =
    ZIO.fromEither(
      GitRunnerConfig
        .make(gitBinary)
        .map(GitRunner.live)
        .left
        .map(_ =>
          ProbeError.make(
            "probe_git_unavailable",
            "trusted BAT Git inspection could not be configured"
          )
        )
    )

  private def safeGitLines(output: String): List[String] =
    Option(output).toList
      .flatMap(_.linesIterator)
      .map(_.trim)
      .filter(_.nonEmpty)
      .filterNot(_.startsWith("git: warning: confstr() failed"))

  private def gitInspectionFailure: ProbeError =
    ProbeError.make(
      "probe_git_inspection_failed",
      "trusted BAT Git inspection failed"
    )

  private def httpClientFailure: ProbeError =
    ProbeError.make(
      "probe_http_client_failed",
      "live probe HTTP client could not be constructed"
    )

  private[probe] def exitCode(verdict: ProbeVerdict): Int = verdict match
    case ProbeVerdict.Compatible    => 0
    case ProbeVerdict.Incompatible  => 2
    case ProbeVerdict.Nonconformant => 3
    case ProbeVerdict.Blocked       => 4

  private[probe] def exitCode(error: ProbeError): Int =
    val configurationFailure =
      error.code.startsWith("invalid_") ||
        error.code.startsWith("missing_") ||
        error.code.startsWith("insecure_") ||
        error.code.startsWith("unsafe_") ||
        error.code == "probe_not_armed" ||
        error.code == "probe_bat_commit_mismatch" ||
        error.code == "probe_dirty_checkout"
    if configurationFailure then 64 else 70
