package bat.runner

import bat.backend.harmonychat.HarmonyChatConfig
import bat.backend.wire.WireReplayPolicy
import bat.protocol.*
import bat.telemetry.*
import bat.transport.*
import bat.worker.*
import bat.worker.oci.*

import java.nio.file.{Files, LinkOption, Path}

import scala.util.Try

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
    readEnvironment.flatMap(environment =>
      executeWith(environment, runPrepared)
    )

  private[runner] def executeWith(
      environment: Map[String, String],
      runner: LiveJavaEnvironment => ZIO[
        Scope,
        BatError,
        LiveJavaPreparedAttempt
      ]
  ): ZIO[Any, BatError, String] =
    ZIO
      .fail(BatError.ProtocolViolation("live Java executor is invalid"))
      .when(environment == null || runner == null) *>
      (for
        loaded <- ZIO.fromEither(LiveJavaEnvironment.from(environment))
        verified <- LiveJavaPreflight.verify(loaded)
        prepared <- ZIO.scoped(runner(verified))
        decision <- publish(verified, prepared.store, prepared.attempt)
        _ <- prepared.attempt match
          case LiveJavaAttempt.Failed(error, _) => ZIO.fail(error)
          case _                                => ZIO.unit
      yield decision)

  private def runPrepared(
      loaded: LiveJavaEnvironment
  ): ZIO[Scope, BatError, LiveJavaPreparedAttempt] =
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
          maxOutputTokens = 8192,
          maxAttempts = 8,
          retryDelay = Duration.fromSeconds(10),
          replayPolicy = WireReplayPolicy.RetryQualifiedSelfHosted
        )
      )
      transport <- lift(
        TransportConfig.make(
          loaded.endpoint,
          Duration.fromSeconds(30),
          Duration.fromSeconds(5 * 60),
          maxRequestBytes = 16L * 1024 * 1024,
          maxResponseHeaderBytes = 64L * 1024
        )
      )
      deployment <- lift(
        DeploymentFingerprint.make(
          identity,
          Measurement.Observed(loaded.runtime),
          Measurement.Observed(loaded.runtimeRevision),
          "harmony_chat_sse",
          Measurement.Observed(loaded.templateRevision),
          Measurement.Observed(loaded.quantization),
          Measurement.Observed(loaded.topology),
          Measurement.Observed(loaded.nodeCount)
        )
      )
      runId <- lift(TelemetryRunId.from(loaded.runId))
      originalBudgets <- lift(
        BudgetLimits.make(
          maxIterations = 128,
          maxToolCalls = 384,
          maxWallTime = Duration.fromSeconds(8 * 60 * 60),
          maxTotalTokens = 8_000_000L
        )
      )
      binding <- lift(loaded.bindingSha256)
      oracleSha256 <- lift(loaded.verifiedOracleSha256)
      gitConfig <- lift(GitRunnerConfig.make(loaded.gitBinary))
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
          loaded.ociRuntime,
          image,
          loaded.batRoot,
          uid = loaded.uid,
          gid = loaded.gid
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
      seedPatch <- lift(loaded.admittedSeedPatch)
      baseRepository <- lift(
        RepositoryId.from(loaded.repositoryId, "base_repository_id")
      )
      pullRequestId <- lift(PullRequestId.from(loaded.runId))
      bdr = WorkerBdrLifecycle.live(
        Chunk(loaded.batRoot.resolve("bin/bdr").toString),
        loaded.batRoot,
        Path.of("bin", "bdr"),
        Duration.fromSeconds(2 * 60),
        "bat-live-java",
        loaded.batCommit
      )
      openSession =
        if loaded.resume then
          JavaWorkerSession.resume(
            workerRunId,
            loaded.attemptId,
            authority,
            git,
            sandbox,
            bdr,
            workerConfig
          )
        else
          JavaWorkerSession.start(
            workerRunId,
            loaded.attemptId,
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
      worker = WorkerFactory.java(openSession, seedPatch)
      evaluator <- lift(
        OciJavaEvaluator.makePinned(
          sandbox,
          loaded.sourceRepository,
          PinnedGitSource.live(git),
          git,
          pins,
          loaded.privateRoot.resolve("evaluator"),
          limits,
          loaded.profile,
          oracleSha256,
          loaded.evaluatorRevision
        )
      )
      store <- LiveJavaAttemptStore.prepare(
        loaded.outputDirectory,
        loaded.batRoot,
        runId,
        loaded.attemptId,
        binding,
        loaded.previousAttempt
      )
      budgets <- lift(store.remaining(originalBudgets))
      production <- lift(
        ProductionRunConfig.make(
          runId,
          deployment,
          loaded.reasoningEffort,
          loaded.batCommit,
          budgets,
          resumeAttempt = loaded.resume
        )
      )
      telemetry <- store.telemetry
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
    yield LiveJavaPreparedAttempt(store, attempt)

  private def publish(
      loaded: LiveJavaEnvironment,
      store: LiveJavaAttemptStore,
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
      oracleSha256 <- lift(loaded.verifiedOracleSha256)
      telemetryDigest <- lift(
        StrictJson.sha256(telemetry.json, "live Java telemetry")
      )
      resultJson <- lift(
        StrictJson.canonical(
          Json.Obj(
            Chunk(
              "schema" -> Json.Str("bat.dev/live-java-result"),
              "version" -> Json.Num(1),
              "run_id" -> Json.Str(loaded.runId),
              "attempt_id" -> Json.Str(store.attemptId.value),
              "case" -> Json.Str(loaded.caseName),
              "evaluator_revision" -> Json.Str(loaded.evaluatorRevision),
              "oracle_sha256" -> Json.Str(oracleSha256),
              "seed_patch_sha256" -> loaded.seedPatchSource
                .map(source => Json.Str(source.sha256.value))
                .getOrElse(Json.Null),
              "decision" -> Json.Str(decision),
              "reason_code" -> reasonCode.fold[Json](Json.Null)(Json.Str(_)),
              "evidence_sha256" -> evidenceDigest
                .fold[Json](Json.Null)(Json.Str(_)),
              "telemetry_sha256" -> Json.Str(telemetryDigest)
            )
          ),
          "live Java result"
        )
      )
      documents = Chunk(
        Some("result.json" -> resultJson),
        evidence.map("evidence.json" -> _),
        Some("telemetry.json" -> telemetryJson)
      ).flatten
      _ <- store.publish(
        decision,
        telemetry.records,
        documents,
        loaded.forbiddenArtifactValues
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

private[runner] enum LiveJavaAttempt:
  case Completed(result: ProductionRunResult)
  case Failed(error: BatError, telemetry: TelemetryDocument)

private[runner] final case class LiveJavaPreparedAttempt(
    store: LiveJavaAttemptStore,
    attempt: LiveJavaAttempt
)

private[runner] final case class LiveJavaEnvironment(
    endpoint: String,
    modelId: String,
    modelRevision: String,
    runtime: String,
    runtimeRevision: String,
    templateRevision: String,
    quantization: String,
    topology: String,
    nodeCount: Long,
    reasoningEffort: String,
    image: String,
    resume: Boolean,
    attemptId: AttemptId,
    previousAttempt: Option[AttemptId],
    runId: String,
    repositoryId: String,
    baseCommit: String,
    headCommit: String,
    batCommit: String,
    batRoot: Path,
    sourceRepository: Path,
    privateRoot: Path,
    outputDirectory: Path,
    gitBinary: Path,
    ociRuntime: Path,
    uid: Int,
    gid: Int,
    profile: JavaEvaluationProfile,
    oracleSha256: Option[String],
    seedPatchSource: Option[LiveJavaSeedPatchSource],
    seedPatch: Option[TrustedSeedPatch]
):
  override def toString: String =
    "LiveJavaEnvironment(endpoint=<redacted>, paths=<redacted>, payload=<redacted>)"

  def oraclePath: Path = profile match
    case JavaEvaluationProfile.Javac(path, _, _) => path
    case JavaEvaluationProfile.Maven(path, _, _) => path

  def caseName: String = profile match
    case _: JavaEvaluationProfile.Javac => "canary"
    case _: JavaEvaluationProfile.Maven => "apache"

  def evaluatorRevision: String = s"issue-25-$caseName-v1"

  def verifiedOracleSha256: Either[BatError, String] =
    oracleSha256.toRight(
      BatError.ProtocolViolation("live Java oracle was not verified")
    )

  def admittedSeedPatch: Either[BatError, Option[TrustedSeedPatch]] =
    (seedPatchSource, seedPatch) match
      case (None, None) => Right(None)
      case (Some(source), Some(value)) if source.sha256 == value.sha256 =>
        Right(Some(value))
      case _ =>
        Left(
          BatError.ProtocolViolation(
            "live Java seed patch was not verified"
          )
        )

  def forbiddenArtifactValues: Chunk[String] =
    Chunk(
      endpoint,
      batRoot.toString,
      sourceRepository.toString,
      privateRoot.toString,
      outputDirectory.toString,
      oraclePath.toString
    ) ++ seedPatchSource.map(_.path.toString) ++ seedPatch.map(_.text)

  def bindingSha256: Either[BatError, String] =
    verifiedOracleSha256.flatMap(oracle =>
      StrictJson.sha256(
        Json.Obj(
          Chunk(
            "endpoint" -> Json.Str(endpoint),
            "model_id" -> Json.Str(modelId),
            "model_revision" -> Json.Str(modelRevision),
            "runtime" -> Json.Str(runtime),
            "runtime_revision" -> Json.Str(runtimeRevision),
            "template_revision" -> Json.Str(templateRevision),
            "quantization" -> Json.Str(quantization),
            "topology" -> Json.Str(topology),
            "node_count" -> Json.Num(nodeCount),
            "reasoning_effort" -> Json.Str(reasoningEffort),
            "image" -> Json.Str(image),
            "repository_id" -> Json.Str(repositoryId),
            "base_commit" -> Json.Str(baseCommit),
            "head_commit" -> Json.Str(headCommit),
            "bat_commit" -> Json.Str(batCommit),
            "case" -> Json.Str(caseName),
            "evaluator_revision" -> Json.Str(evaluatorRevision),
            "oracle_sha256" -> Json.Str(oracle),
            "seed_patch_policy" -> Json.Str("trusted-seed-patch-v1"),
            "seed_patch_sha256" -> seedPatchSource
              .map(source => Json.Str(source.sha256.value))
              .getOrElse(Json.Null)
          )
        ),
        "live Java attempt binding"
      )
    )

private[runner] object LiveJavaEnvironment:
  val Keys = Chunk(
    "BAT_LIVE_ARM",
    "BAT_LIVE_CASE",
    "BAT_LIVE_ENDPOINT",
    "BAT_LIVE_MODEL",
    "BAT_LIVE_MODEL_REVISION",
    "BAT_LIVE_RUNTIME",
    "BAT_LIVE_RUNTIME_REVISION",
    "BAT_LIVE_TEMPLATE_REVISION",
    "BAT_LIVE_QUANTIZATION",
    "BAT_LIVE_TOPOLOGY",
    "BAT_LIVE_NODE_COUNT",
    "BAT_LIVE_REASONING_EFFORT",
    "BAT_LIVE_IMAGE",
    "BAT_LIVE_RESUME",
    "BAT_LIVE_ATTEMPT_ID",
    "BAT_LIVE_PREVIOUS_ATTEMPT_ID",
    "BAT_LIVE_RUN_ID",
    "BAT_LIVE_REPOSITORY_ID",
    "BAT_LIVE_BASE_COMMIT",
    "BAT_LIVE_HEAD_COMMIT",
    "BAT_LIVE_BAT_COMMIT",
    "BAT_LIVE_BAT_ROOT",
    "BAT_LIVE_SOURCE",
    "BAT_LIVE_PRIVATE_ROOT",
    "BAT_LIVE_OUTPUT",
    "BAT_LIVE_ORACLE",
    "BAT_LIVE_GIT",
    "BAT_LIVE_OCI_RUNTIME",
    "BAT_LIVE_UID",
    "BAT_LIVE_GID",
    "BAT_LIVE_SEED_PATCH",
    "BAT_LIVE_SEED_PATCH_SHA256"
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
    def positiveLong(key: String): Either[BatError, Long] =
      required(key).flatMap(text =>
        text.toLongOption
          .filter(_ > 0L)
          .toRight(BatError.ProtocolViolation(s"invalid live number: $key"))
      )
    def nonnegativeInt(key: String): Either[BatError, Int] =
      required(key).flatMap(text =>
        text.toIntOption
          .filter(_ >= 0)
          .toRight(BatError.ProtocolViolation(s"invalid live number: $key"))
      )
    def optionalAttempt(key: String): Either[BatError, Option[AttemptId]] =
      values.get(key).map(_.trim).filter(_.nonEmpty) match
        case None        => Right(None)
        case Some(value) =>
          AttemptId
            .from(value)
            .left
            .map(_ => BatError.ProtocolViolation("invalid live attempt ID"))
            .map(Some(_))
    def optional(key: String): Option[String] =
      values.get(key).map(_.trim).filter(_.nonEmpty)
    def optionalAbsolute(
        value: String,
        key: String
    ): Either[BatError, Path] =
      Try(Path.of(value)).toEither.left
        .map(_ => BatError.ProtocolViolation(s"invalid live path: $key"))
        .flatMap(path =>
          Either.cond(
            path.isAbsolute,
            path.normalize,
            BatError.ProtocolViolation(s"invalid live path: $key")
          )
        )
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
      runtimeName <- required("BAT_LIVE_RUNTIME")
      runtime <- required("BAT_LIVE_RUNTIME_REVISION")
      template <- required("BAT_LIVE_TEMPLATE_REVISION")
      quantization <- required("BAT_LIVE_QUANTIZATION")
      topology <- required("BAT_LIVE_TOPOLOGY")
      nodes <- positiveLong("BAT_LIVE_NODE_COUNT")
      effort <- required("BAT_LIVE_REASONING_EFFORT")
      image <- required("BAT_LIVE_IMAGE")
      resumeText <- required("BAT_LIVE_RESUME")
      resume <- resumeText match
        case "true"  => Right(true)
        case "false" => Right(false)
        case _       =>
          Left(BatError.ProtocolViolation("live resume flag is invalid"))
      attemptText <- required("BAT_LIVE_ATTEMPT_ID")
      attempt <- AttemptId
        .from(attemptText)
        .left
        .map(_ => BatError.ProtocolViolation("invalid live attempt ID"))
      previous <- optionalAttempt("BAT_LIVE_PREVIOUS_ATTEMPT_ID")
      _ <- Either.cond(
        (resume && previous.nonEmpty) || (!resume && previous.isEmpty),
        (),
        BatError.ProtocolViolation(
          "live resume requires exactly one previous attempt"
        )
      )
      seedPath = optional("BAT_LIVE_SEED_PATCH")
      seedSha256 = optional("BAT_LIVE_SEED_PATCH_SHA256")
      seedSource <- (seedPath, seedSha256) match
        case (None, None)               => Right(None)
        case (Some(path), Some(digest)) =>
          optionalAbsolute(path, "BAT_LIVE_SEED_PATCH")
            .flatMap(LiveJavaSeedPatchSource.make(_, digest))
            .map(Some(_))
        case _ =>
          Left(
            BatError.ProtocolViolation(
              "live seed patch path and SHA-256 must be supplied together"
            )
          )
      _ <- Either.cond(
        seedSource.isEmpty || (!resume && previous.isEmpty),
        (),
        BatError.ProtocolViolation(
          "live seed patch is available only to a fresh attempt lineage"
        )
      )
      runId <- required("BAT_LIVE_RUN_ID")
      repository <- required("BAT_LIVE_REPOSITORY_ID")
      base <- required("BAT_LIVE_BASE_COMMIT")
      head <- required("BAT_LIVE_HEAD_COMMIT")
      batCommit <- required("BAT_LIVE_BAT_COMMIT")
      batRoot <- absolute("BAT_LIVE_BAT_ROOT")
      source <- absolute("BAT_LIVE_SOURCE")
      privateRoot <- absolute("BAT_LIVE_PRIVATE_ROOT")
      output <- absolute("BAT_LIVE_OUTPUT")
      git <- absolute("BAT_LIVE_GIT")
      oci <- absolute("BAT_LIVE_OCI_RUNTIME")
      uid <- nonnegativeInt("BAT_LIVE_UID")
      gid <- nonnegativeInt("BAT_LIVE_GID")
    yield LiveJavaEnvironment(
      endpoint,
      model,
      revision,
      runtimeName,
      runtime,
      template,
      quantization,
      topology,
      nodes,
      effort,
      image,
      resume,
      attempt,
      previous,
      runId,
      repository,
      base,
      head,
      batCommit,
      batRoot,
      source,
      privateRoot,
      output,
      git,
      oci,
      uid,
      gid,
      profile,
      None,
      seedSource,
      None
    )

private[runner] object LiveJavaPreflight:
  def verify(
      value: LiveJavaEnvironment
  ): ZIO[Any, BatError, LiveJavaEnvironment] =
    for
      _ <- ZIO
        .attemptBlocking {
          if value == null then throw IllegalArgumentException("missing config")
          val bat = realDirectory(value.batRoot)
          val source = realDirectory(value.sourceRepository)
          val privateRoot = realDirectory(value.privateRoot)
          val output = realDirectory(value.outputDirectory)
          val oracle = value.oraclePath.toRealPath()
          val git = realExecutable(value.gitBinary)
          val oci = realExecutable(value.ociRuntime)
          if git == oci then throw IllegalArgumentException("binary alias")
          if !output.startsWith(privateRoot) || output == privateRoot then
            throw IllegalArgumentException("output boundary")
          val disjoint = Chunk(
            bat -> source,
            bat -> privateRoot,
            source -> privateRoot,
            source -> oracle,
            privateRoot -> oracle
          )
          if disjoint.exists { case (left, right) => overlaps(left, right) }
          then throw IllegalArgumentException("path overlap")
          if Files.isSymbolicLink(value.oraclePath) ||
            (!Files.isRegularFile(oracle, LinkOption.NOFOLLOW_LINKS) &&
              !Files.isDirectory(oracle, LinkOption.NOFOLLOW_LINKS))
          then throw IllegalArgumentException("oracle boundary")
          ()
        }
        .mapError(_ =>
          BatError.ProtocolViolation("live Java preflight boundary is invalid")
        )
      oracle <- JavaEvaluationOracleInspection.inspect(value.profile)
      seedPatch <- ZIO.foreach(value.seedPatchSource)(
        _.load(value.privateRoot, value.outputDirectory)
      )
      _ <- verifyBatCheckout(value)
    yield value.copy(
      oracleSha256 = Some(oracle.sha256),
      seedPatch = seedPatch
    )

  private def verifyBatCheckout(
      value: LiveJavaEnvironment
  ): ZIO[Any, BatError, Unit] =
    for
      config <- ZIO
        .fromEither(GitRunnerConfig.make(value.gitBinary))
        .mapError(_ => invalidCheckout)
      runner = GitRunner.live(config)
      status <- runner
        .run(
          GitInvocation(
            value.batRoot,
            Chunk("status", "--porcelain=v1", "--untracked-files=all")
          )
        )
        .mapError(_ => invalidCheckout)
      head <- runner
        .run(
          GitInvocation(
            value.batRoot,
            Chunk("rev-parse", "--verify", "HEAD^{commit}")
          )
        )
        .mapError(_ => invalidCheckout)
      _ <- ZIO
        .fail(invalidCheckout)
        .unless(
          status.exitCode == 0 && status.output.trim.isEmpty &&
            head.exitCode == 0 && head.output.trim == value.batCommit
        )
    yield ()

  private def invalidCheckout: BatError =
    BatError.ProtocolViolation(
      "live Java BAT checkout is not the clean pinned commit"
    )

  private def realDirectory(path: Path): Path =
    val real = path.toRealPath()
    if Files.isSymbolicLink(path) ||
      !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
    then throw IllegalArgumentException("directory boundary")
    real

  private def realExecutable(path: Path): Path =
    val real = path.toRealPath()
    if !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isExecutable(real)
    then throw IllegalArgumentException("executable boundary")
    real

  private def overlaps(left: Path, right: Path): Boolean =
    left.startsWith(right) || right.startsWith(left)
