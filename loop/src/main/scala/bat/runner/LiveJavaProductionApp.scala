package bat.runner

import bat.backend.harmonychat.HarmonyChatConfig
import bat.protocol.*
import bat.telemetry.*
import bat.transport.*
import bat.worker.*
import bat.worker.oci.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import zio.http.Client
import zio.{Chunk, Duration, Scope, ZIO, ZIOAppDefault}
import zio.json.ast.Json

/** Explicitly armed live entry point for the issue-25 Java acceptance trials.
  * Provider and private evaluator locations are accepted only through the
  * environment, never process arguments or actor-visible tool payloads.
  */
object LiveJavaProductionApp extends ZIOAppDefault:
  override def run: ZIO[Any, Any, Any] =
    execute.foldZIO(
      error =>
        zio.Console.printLineError(
          s"BAT live Java run failed: ${error.code}: ${error.safeMessage}"
        ) *>
          ZIO.fail(error),
      decision =>
        zio.Console.printLine(s"BAT live Java decision=$decision") *>
          ZIO
            .fail(
              BatError.ProtocolViolation(
                s"live Java run ended with decision $decision"
              )
            )
            .unless(decision == "ready")
    )

  private[runner] def execute: ZIO[Any, BatError, String] =
    for
      environment <- readEnvironment
      loaded <- ZIO.fromEither(LiveJavaEnvironment.from(environment))
      attempt <- ZIO.scoped(runPrepared(loaded))
      decision <- publish(loaded.outputDirectory, loaded.runId, attempt)
      _ <- attempt match
        case LiveJavaAttempt.Failed(error, _) => ZIO.fail(error)
        case _                                => ZIO.unit
    yield decision

  private def runPrepared(
      loaded: LiveJavaEnvironment
  ): ZIO[Scope, BatError, LiveJavaAttempt] =
    for
      identity <- lift(
        HarmonyChatConfig.identity(loaded.modelId, loaded.modelRevision)
      )
      sse <- lift(SseLimits.make(1024L * 1024, 64L * 1024 * 1024))
      harmony <- lift(
        HarmonyChatConfig.make(
          identity,
          None,
          sse,
          maxOutputTokens = 2048,
          maxAttempts = 2,
          retryDelay = Duration.fromSeconds(1)
        )
      )
      transport <- lift(
        TransportConfig.make(
          loaded.endpoint,
          Duration.fromSeconds(30),
          Duration.fromSeconds(45 * 60),
          maxRequestBytes = 16L * 1024 * 1024,
          maxResponseHeaderBytes = 64L * 1024
        )
      )
      deployment <- lift(
        DeploymentFingerprint.make(
          identity,
          Measurement.Observed("exo"),
          Measurement.Observed(loaded.runtimeRevision),
          "harmony_chat_sse",
          Measurement.Observed("gpt_oss_harmony"),
          Measurement.Observed("mxfp4"),
          Measurement.Observed("mlx_ring"),
          Measurement.Observed(3L)
        )
      )
      runId <- lift(TelemetryRunId.from(loaded.runId))
      budgets <- lift(
        BudgetLimits.make(
          maxIterations = 128,
          maxToolCalls = 384,
          maxWallTime = Duration.fromSeconds(8 * 60 * 60),
          maxTotalTokens = 8_000_000L
        )
      )
      production <- lift(
        ProductionRunConfig.make(
          runId,
          deployment,
          "high",
          loaded.batCommit,
          budgets
        )
      )
      gitConfig <- lift(GitRunnerConfig.make(Path.of("/usr/bin/git")))
      git = GitRunner.live(gitConfig)
      pins <- lift(
        PullRequestPins.make(
          loaded.repositoryId,
          loaded.repositoryId,
          loaded.runId,
          "refs/heads/bat-base",
          loaded.baseCommit,
          "refs/heads/bat-head",
          loaded.headCommit
        )
      )
      authority = new PullRequestAuthority:
        def resolve(
            baseRepository: RepositoryId,
            pullRequestId: PullRequestId
        ) = ZIO.succeed(pins)
      image <- lift(PinnedImage.from(loaded.image))
      limits <- lift(
        OciLimits.make(
          Duration.fromSeconds(45 * 60),
          64 * 1024,
          64 * 1024,
          16L * 1024 * 1024,
          512,
          4L * 1024 * 1024 * 1024,
          BigDecimal(6),
          512L * 1024 * 1024,
          128L * 1024 * 1024,
          4L * 1024 * 1024 * 1024
        )
      )
      sandboxConfig <- lift(
        OciSandboxConfig.make(
          Path.of("/usr/local/bin/docker"),
          image,
          loaded.batRoot,
          uid = 1000,
          gid = 1000
        )
      )
      sandbox = OciSandbox.live(sandboxConfig)
      javaPolicy <- lift(
        JavaBuildPolicy.make(
          "java-issue-25-v1",
          "/usr/bin/mvn",
          "/usr/bin/gradle"
        )
      )
      storage <- lift(
        WorkerStorageLimits.make(
          2L * 1024 * 1024 * 1024,
          250000L,
          2L * 1024 * 1024 * 1024,
          250000L,
          16 * 1024 * 1024
        )
      )
      workerConfig <- lift(
        WorkerRuntimeConfig.make(
          loaded.privateRoot.resolve("control"),
          loaded.privateRoot.resolve("workspaces"),
          loaded.privateRoot.resolve("scratch"),
          image,
          limits,
          javaPolicy,
          storage
        )
      )
      workerRunId <- lift(RunId.from(loaded.runId))
      baseRepository <- lift(
        RepositoryId.from(loaded.repositoryId, "base_repository_id")
      )
      pullRequestId <- lift(PullRequestId.from(loaded.runId))
      bdr = WorkerBdrLifecycle.live(
        Chunk(loaded.batRoot.resolve("bin/bdr").toString),
        loaded.batRoot,
        Path.of("bin", "bdr"),
        Duration.fromSeconds(2 * 60),
        "bat-gpt-oss-120b",
        loaded.batCommit
      )
      openSession =
        if loaded.resume then
          JavaWorkerSession.resume(
            workerRunId,
            authority,
            git,
            sandbox,
            bdr,
            workerConfig
          )
        else
          JavaWorkerSession.start(
            workerRunId,
            baseRepository,
            pullRequestId,
            loaded.sourceRepository,
            authority,
            PinnedGitSource.live(git),
            git,
            sandbox,
            bdr,
            workerConfig
          )
      worker = WorkerFactory.java(openSession)
      evaluator <- lift(
        OciJavaEvaluator.make(
          sandbox,
          loaded.sourceRepository,
          loaded.privateRoot.resolve("evaluator"),
          limits,
          loaded.profile,
          "issue-25-v1"
        )
      )
      telemetry <- InMemoryTelemetry.make
      result <- ZIO
        .serviceWithZIO[StreamingHttp](http =>
          ProductionRunner.runObserved(
            production,
            BackendFactory.gptOssHarmonyChat(harmony, http),
            worker,
            evaluator,
            telemetry
          )
        )
        .provideLayer(Client.default >>> StreamingHttp.configured(transport))
        .mapError {
          case error: BatError => error
          case _               =>
            BatError.BackendFailure(
              "live_http_client_failed",
              "live HTTP client could not be constructed",
              retryable = false
            )
        }
        .either
      attempt <- result match
        case Right(value) => ZIO.succeed(LiveJavaAttempt.Completed(value))
        case Left(error)  =>
          telemetry.document(runId, deployment).flatMap {
            case Right(document) =>
              ZIO.succeed(LiveJavaAttempt.Failed(error, document))
            case Left(_) =>
              // No valid start/terminal envelope means the safe publication
              // boundary was never established. Preserve the original error.
              ZIO.fail(error)
          }
    yield attempt

  private def publish(
      outputDirectory: Path,
      runId: String,
      attempt: LiveJavaAttempt
  ): ZIO[Any, BatError, String] =
    val (decision, reasonCode, evidence, evidenceDigest, telemetry) =
      attempt match
        case LiveJavaAttempt.Completed(value: ProductionRunResult.Ready) =>
          (
            "ready",
            None,
            Some(value.evidence.canonicalJson),
            Some(value.evidence.sha256),
            value.telemetry
          )
        case LiveJavaAttempt.Completed(
              value: ProductionRunResult.Rejected
            ) =>
          (
            "rejected",
            None,
            Some(value.evidence.canonicalJson),
            Some(value.evidence.sha256),
            value.telemetry
          )
        case LiveJavaAttempt.Completed(value: ProductionRunResult.Terminal) =>
          (
            "terminal",
            None,
            Some(value.evidence.canonicalJson),
            Some(value.evidence.sha256),
            value.telemetry
          )
        case LiveJavaAttempt.Failed(error, document) =>
          ("failed", Some(error.code), None, None, document)
    for
      telemetryJson <- lift(telemetry.canonicalJson)
      resultJson <- lift(
        StrictJson.canonical(
          Json.Obj(
            Chunk(
              "schema" -> Json.Str("bat.dev/live-java-result"),
              "version" -> Json.Num(1),
              "run_id" -> Json.Str(runId),
              "decision" -> Json.Str(decision),
              "reason_code" -> reasonCode.fold[Json](Json.Null)(Json.Str(_)),
              "evidence_sha256" -> evidenceDigest
                .fold[Json](Json.Null)(Json.Str(_))
            )
          ),
          "live Java result"
        )
      )
      _ <- ZIO
        .attemptBlocking {
          Files.createDirectories(outputDirectory)
          evidence.foreach(value =>
            Files.writeString(
              outputDirectory.resolve("evidence.json"),
              value + "\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE_NEW
            )
          )
          Files.writeString(
            outputDirectory.resolve("telemetry.json"),
            telemetryJson + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW
          )
          Files.writeString(
            outputDirectory.resolve("result.json"),
            resultJson + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW
          )
        }
        .mapError(_ =>
          BatError.ProtocolViolation("live evidence publication failed")
        )
    yield decision

  private def readEnvironment: ZIO[Any, BatError, Map[String, String]] =
    ZIO
      .foreach(LiveJavaEnvironment.Keys)(key =>
        zio.System.env(key).map(_.map(value => key -> value))
      )
      .map(_.flatten.toMap)
      .mapError(_ =>
        BatError.ProtocolViolation("live environment could not be read")
      )

  private def lift[E, A](value: Either[E, A]): ZIO[Any, BatError, A] =
    ZIO.fromEither(
      value.left.map(_ =>
        BatError.ProtocolViolation("live configuration is invalid")
      )
    )

private enum LiveJavaAttempt:
  case Completed(result: ProductionRunResult)
  case Failed(error: BatError, telemetry: TelemetryDocument)

private final case class LiveJavaEnvironment(
    endpoint: String,
    modelId: String,
    modelRevision: String,
    runtimeRevision: String,
    image: String,
    resume: Boolean,
    runId: String,
    repositoryId: String,
    baseCommit: String,
    headCommit: String,
    batCommit: String,
    batRoot: Path,
    sourceRepository: Path,
    privateRoot: Path,
    outputDirectory: Path,
    profile: JavaEvaluationProfile
)

private object LiveJavaEnvironment:
  val Keys = Chunk(
    "BAT_LIVE_ARM",
    "BAT_LIVE_CASE",
    "BAT_LIVE_ENDPOINT",
    "BAT_LIVE_MODEL",
    "BAT_LIVE_MODEL_REVISION",
    "BAT_LIVE_RUNTIME_REVISION",
    "BAT_LIVE_IMAGE",
    "BAT_LIVE_RESUME",
    "BAT_LIVE_RUN_ID",
    "BAT_LIVE_REPOSITORY_ID",
    "BAT_LIVE_BASE_COMMIT",
    "BAT_LIVE_HEAD_COMMIT",
    "BAT_LIVE_BAT_COMMIT",
    "BAT_LIVE_BAT_ROOT",
    "BAT_LIVE_SOURCE",
    "BAT_LIVE_PRIVATE_ROOT",
    "BAT_LIVE_OUTPUT",
    "BAT_LIVE_ORACLE"
  )

  def from(values: Map[String, String]): Either[BatError, LiveJavaEnvironment] =
    def required(key: String): Either[BatError, String] =
      values
        .get(key)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(
          BatError.ProtocolViolation(s"missing live configuration: $key")
        )
    def absolute(key: String): Either[BatError, Path] =
      required(key).flatMap { text =>
        val path = Path.of(text)
        Either.cond(
          path.isAbsolute,
          path.normalize,
          BatError.ProtocolViolation(s"invalid live path: $key")
        )
      }
    for
      arm <- required("BAT_LIVE_ARM")
      _ <- Either.cond(
        arm == "issue-25",
        (),
        BatError.ProtocolViolation("live run is not armed")
      )
      caseName <- required("BAT_LIVE_CASE")
      oracle <- absolute("BAT_LIVE_ORACLE")
      profile <- caseName match
        case "canary" =>
          Right(
            JavaEvaluationProfile.Javac(
              oracle,
              "dev.bat.examples.ingress.IngressGatewayHiddenTest",
              "dev.bat.examples.ingress.IngressGatewayPublicTest"
            )
          )
        case "apache" =>
          Right(
            JavaEvaluationProfile.Maven(
              oracle,
              "TestGenericObjectPool",
              runPublicSuite = true
            )
          )
        case _ => Left(BatError.ProtocolViolation("live Java case is invalid"))
      endpoint <- required("BAT_LIVE_ENDPOINT")
      model <- required("BAT_LIVE_MODEL")
      revision <- required("BAT_LIVE_MODEL_REVISION")
      runtime <- required("BAT_LIVE_RUNTIME_REVISION")
      image <- required("BAT_LIVE_IMAGE")
      resumeText <- required("BAT_LIVE_RESUME")
      resume <- resumeText match
        case "true"  => Right(true)
        case "false" => Right(false)
        case _       =>
          Left(BatError.ProtocolViolation("live resume flag is invalid"))
      runId <- required("BAT_LIVE_RUN_ID")
      repository <- required("BAT_LIVE_REPOSITORY_ID")
      base <- required("BAT_LIVE_BASE_COMMIT")
      head <- required("BAT_LIVE_HEAD_COMMIT")
      batCommit <- required("BAT_LIVE_BAT_COMMIT")
      batRoot <- absolute("BAT_LIVE_BAT_ROOT")
      source <- absolute("BAT_LIVE_SOURCE")
      privateRoot <- absolute("BAT_LIVE_PRIVATE_ROOT")
      output <- absolute("BAT_LIVE_OUTPUT")
    yield LiveJavaEnvironment(
      endpoint,
      model,
      revision,
      runtime,
      image,
      resume,
      runId,
      repository,
      base,
      head,
      batCommit,
      batRoot,
      source,
      privateRoot,
      output,
      profile
    )
