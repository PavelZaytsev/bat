package bat.quickstart

import bat.bdr.{BdrConfig, BdrInitialization, BdrSession, BdrTools}
import bat.controller.{AgenticLoop, LoopResult, ToolRegistry}
import bat.protocol.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*

/** Validated local paths for one executable six-phase canary run. */
final case class ToyScenarioConfig private (
    projectRoot: Path,
    outputRoot: Path
)

object ToyScenarioConfig:
  /** Validate the BAT checkout and artifact roots for one canary run.
    *
    * The artifact root must be outside the checkout; `ToyScenario.execute`
    * subsequently resolves both paths canonically before writing anything.
    */
  def make(
      projectRoot: Path,
      outputRoot: Path
  ): Either[BatError, ToyScenarioConfig] =
    if projectRoot == null || outputRoot == null then
      Left(failure("invalid_toy_paths", "toy paths must be explicit"))
    else
      val project = projectRoot.toAbsolutePath.normalize
      val output = outputRoot.toAbsolutePath.normalize
      if output.startsWith(project) then
        Left(
          failure(
            "invalid_toy_output",
            "toy output must stay outside the pinned BAT checkout"
          )
        )
      else Right(ToyScenarioConfig(project, output))

  private def failure(code: String, message: String): BatError =
    BatError.BackendFailure(code, message, retryable = false)

/** Stable, sanitized artifact emitted by the scripted portability canary. */
final case class ToyCanarySummary(
    schema: String,
    @jsonField("fixture_id") fixtureId: String,
    @jsonField("bat_commit") batCommit: String,
    @jsonField("toy_revision") toyRevision: String,
    @jsonField("base_sha") baseSha: String,
    @jsonField("target_head_sha") targetHeadSha: String,
    @jsonField("final_head_sha") finalHeadSha: String,
    @jsonField("final_patch_digest") finalPatchDigest: String,
    backend: String,
    phases: List[String],
    @jsonField("actor_test_invocations") actorTestInvocations: Long,
    @jsonField("bdr_revision") bdrRevision: Long,
    @jsonField("bdr_state") bdrState: String,
    outcome: String,
    iterations: Int,
    @jsonField("tool_calls") toolCalls: Int,
    @jsonField("total_tokens") totalTokens: Long,
    evaluation: ToyEvaluation
) derives JsonCodec

/** Completed canary result plus the paths to its sanitized local artifacts. */
final case class ToyScenarioResult(
    summary: ToyCanarySummary,
    loopResult: LoopResult,
    workspace: Path,
    summaryPath: Path,
    tracePath: Path
)

/** Runs the real BAT controller and real BDR engine against the maintained Java
  * fixture with deterministic scripted inference.
  *
  * Live provider qualification reuses the fixture, BDR tools, evaluator, and
  * success contract, but must replace these trusted local toy tools with BAT's
  * OCI-isolated Java worker before executing model-authored code.
  */
object ToyScenario:
  private val Phases =
    List("EXPOSE", "REPRESENT", "ROUTE", "COLLAPSE", "SATURATE", "FALSIFY")

  /** Execute one fresh deterministic canary run.
    *
    * The run materializes the two-commit Java subject, initializes the pinned
    * BDR tracker, drives all six phases through the real agentic loop, invokes
    * the independent evaluator after the loop stops, and writes only the
    * sanitized summary and safe trace to the configured artifact directory.
    */
  def execute(config: ToyScenarioConfig): IO[BatError, ToyScenarioResult] =
    for
      project <- validateProjectRoot(config.projectRoot)
      output <- prepareOutput(config.outputRoot, project)
      fixture = project.resolve("examples").resolve("java-six-phase")
      toy <- ToyRepository.materialize(fixture, output.resolve("subject"))
      batCommit <- ToyRuntime.head(project)
      bdrConfig <- ZIO.fromEither(
        BdrConfig.make(
          engineArgv = Chunk(project.resolve("bin").resolve("bdr").toString),
          repository = toy.repository,
          commandTimeout = 30.seconds,
          actor = "bat-toy-scripted",
          engineCommit = batCommit,
          engineSourceRoot = project,
          engineEntryPoint = Path.of("bin", "bdr")
        )
      )
      initialization <- ZIO.fromEither(
        BdrInitialization.make(
          baseSha = toy.baseSha,
          headSha = toy.headSha,
          repository = "bat/java-six-phase-ingress-001",
          runId = s"BAT-TOY-${toy.toyRevision.take(12)}"
        )
      )
      bdr <- BdrSession.initialize(bdrConfig, initialization)
      workspace <- ToyWorkspace.open(toy)
      identity <- ZIO.fromEither(
        BackendIdentity.make(
          "scripted",
          "bat-java-six-phase-canary",
          toy.toyRevision
        )
      )
      backend <- ScriptedToyBackend.make(identity, fixture)
      registry <- ZIO.fromEither(
        ToolRegistry.make(BdrTools.all(bdr) ++ workspace.tools)
      )
      pins <- ZIO.fromEither(
        RunPins.make(
          identity,
          "deterministic",
          "bat-java-six-phase-v1",
          batCommit
        )
      )
      developer <- ZIO.fromEither(
        DeveloperInput.make(
          "Execute BDR economically: five verification invocations, structural middle phases, no oracle access."
        )
      )
      user <- ZIO.fromEither(
        UserInput.make(
          "Refactor the pinned Java ingress PR through all six BDR phases and stop only at ready_for_review."
        )
      )
      budgets <- ZIO.fromEither(
        BudgetLimits.make(
          maxIterations = 40,
          maxToolCalls = 40,
          maxWallTime = 2.minutes,
          maxTotalTokens = 1000L
        )
      )
      spec = RunSpec.make(
        RunMode.FullWriter,
        pins,
        developer,
        user,
        budgets
      )
      loop <- AgenticLoop.run(spec, backend, registry, bdr)
      _ <- require(
        loop.outcome == RunOutcome.ReadyForReview,
        "scripted Java canary did not reach ready_for_review"
      )
      testInvocations <- workspace.testInvocationCount
      _ <- require(
        testInvocations == 5L,
        "scripted Java canary violated the five-invocation test budget"
      )
      finalHead <- workspace.finalHead
      finalPatchDigest <- workspace.finalPatchDigest
      evaluation <- ToyEvaluator.evaluate(fixture, toy)
      _ <- require(
        loop.bdrState.revision.value == 19L &&
          loop.bdrState.runState == "ready_for_review",
        "scripted Java canary ended at an unexpected BDR checkpoint"
      )
      _ <- require(
        loop.iterations == 35 && loop.toolCalls == 34,
        "scripted Java canary executed an unexpected tool trace"
      )
      _ <- require(
        evaluation.finalHeadSha == finalHead &&
          evaluation.finalPatchDigest == finalPatchDigest,
        "workspace and evaluator disagree about the delivered patch"
      )
      summary = ToyCanarySummary(
        schema = "bat.dev/java-six-phase-canary/v1",
        fixtureId = "java-six-phase-ingress-001",
        batCommit = batCommit,
        toyRevision = toy.toyRevision,
        baseSha = toy.baseSha,
        targetHeadSha = toy.headSha,
        finalHeadSha = finalHead,
        finalPatchDigest = finalPatchDigest,
        backend = "scripted",
        phases = Phases,
        actorTestInvocations = testInvocations,
        bdrRevision = loop.bdrState.revision.value,
        bdrState = loop.bdrState.runState,
        outcome = loop.outcome.wire,
        iterations = loop.iterations,
        toolCalls = loop.toolCalls,
        totalTokens = loop.totalTokens,
        evaluation = evaluation
      )
      summaryPath = output.resolve("summary.json")
      tracePath = output.resolve("safe-trace.json")
      _ <- writeArtifact(summaryPath, summary.toJsonPretty)
      _ <- writeArtifact(tracePath, loop.traceDocument.toJsonPretty)
    yield ToyScenarioResult(
      summary,
      loop,
      toy.repository,
      summaryPath,
      tracePath
    )

  private def validateProjectRoot(path: Path): IO[BatError, Path] =
    ZIO
      .attemptBlocking {
        if Files.isSymbolicLink(path) ||
          !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        then throw IllegalArgumentException("invalid project root")
        val root = path.toRealPath()
        val required = Seq(
          root.resolve("bin").resolve("bdr"),
          root.resolve("scripts").resolve("bdr.py"),
          root.resolve("examples").resolve("java-six-phase")
        )
        if !required.forall(candidate =>
            Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
              !Files.isSymbolicLink(candidate)
          )
        then throw IllegalArgumentException("incomplete project root")
        root
      }
      .mapError(_ =>
        failure(
          "invalid_bat_checkout",
          "quickstart requires a complete BAT checkout"
        )
      )

  private[quickstart] def prepareOutput(
      path: Path,
      projectRoot: Path
  ): IO[BatError, Path] =
    ZIO
      .attemptBlocking {
        val requested = path.toAbsolutePath.normalize
        val project = projectRoot.toRealPath()
        var existing = requested
        while existing != null &&
          !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)
        do existing = existing.getParent
        if existing == null || Files.isSymbolicLink(existing) then
          throw IllegalArgumentException("unsafe output ancestor")
        val canonicalExisting = existing.toRealPath()
        val output = canonicalExisting
          .resolve(existing.relativize(requested))
          .normalize
        if output.startsWith(project) then
          throw IllegalArgumentException("output is inside the BAT checkout")
        if Files.exists(output, LinkOption.NOFOLLOW_LINKS) then
          if Files.isSymbolicLink(output) ||
            !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
          then throw IllegalArgumentException("unsafe output")
          val entries = Files.list(output)
          try
            if entries.findFirst().isPresent then
              throw IllegalArgumentException("output is not empty")
          finally entries.close()
        else
          val parent = output.getParent
          if parent == null then throw IllegalArgumentException("unsafe output")
          Files.createDirectories(parent)
          val _ = Files.createDirectory(output)
        val verified = output.toRealPath()
        if verified != output || verified.startsWith(project) then
          throw IllegalArgumentException("unsafe canonical output")
        verified
      }
      .mapError(_ =>
        failure(
          "invalid_toy_output",
          "quickstart output must be an empty safe directory outside the BAT checkout"
        )
      )

  private def writeArtifact(path: Path, value: String): IO[BatError, Unit] =
    ZIO
      .attemptBlocking {
        if Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(path)
        then throw IllegalArgumentException("artifact already exists")
        val _ = Files.writeString(
          path,
          value + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
      }
      .unit
      .mapError(_ =>
        failure(
          "toy_artifact_write_failed",
          "cannot write sanitized toy artifacts"
        )
      )

  private def require(
      condition: Boolean,
      message: String
  ): IO[BatError, Unit] =
    ZIO.fail(failure("toy_canary_failed", message)).unless(condition).unit

  private def failure(code: String, message: String): BatError =
    BatError.BackendFailure(code, message, retryable = false)

/** Copy/paste entrypoint used by the README and ordinary CI. */
object ToyQuickstart extends ZIOAppDefault:
  def run: ZIO[Any, Any, Any] =
    for
      configuredProject <- zio.System.env("BAT_PROJECT_ROOT")
      project <- ZIO.attempt(
        configuredProject
          .map(Path.of(_))
          .getOrElse(Path.of(""))
          .toAbsolutePath
          .normalize
      )
      configuredOutput <- zio.System.env("BAT_TOY_OUTPUT")
      output <- configuredOutput match
        case Some(value) => ZIO.attempt(Path.of(value).toAbsolutePath.normalize)
        case None        =>
          ZIO.attemptBlocking {
            Files
              .createTempDirectory("bat-java-six-phase-run-")
              .toRealPath()
          }
      config <- ZIO.fromEither(ToyScenarioConfig.make(project, output))
      result <- ToyScenario.execute(config)
      _ <- Console.printLine(result.summary.toJsonPretty)
      _ <- Console.printLineError(
        s"Sanitized artifacts: ${output.toAbsolutePath.normalize}"
      )
    yield ()
