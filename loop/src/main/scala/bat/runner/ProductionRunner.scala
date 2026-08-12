package bat.runner

import bat.bdr.{BdrSession, BdrTools}
import bat.backend.gptoss.{GptOssBackend, GptOssConfig}
import bat.backend.harmonychat.{HarmonyChatBackend, HarmonyChatConfig}
import bat.controller.{AgenticLoop, LoopResult, Tool, ToolRegistry}
import bat.protocol.*
import bat.telemetry.*
import bat.worker.*
import bat.transport.StreamingHttp

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import zio.{Chunk, IO, Scope, ZIO}
import zio.json.ast.Json

/** Constructs one reasoning backend with the runner-owned telemetry sink.
  * Provider configuration remains outside the production runner.
  */
trait BackendFactory:
  def open(telemetry: Telemetry): ZIO[Scope, BatError, Backend]

object BackendFactory:
  def gptOssResponses(
      config: GptOssConfig,
      http: StreamingHttp
  ): BackendFactory =
    new BackendFactory:
      def open(telemetry: Telemetry): ZIO[Scope, BatError, Backend] =
        ZIO.fromEither(GptOssBackend.make(config, http, telemetry))

  def gptOssHarmonyChat(
      config: HarmonyChatConfig,
      http: StreamingHttp
  ): BackendFactory =
    new BackendFactory:
      def open(telemetry: Telemetry): ZIO[Scope, BatError, Backend] =
        ZIO.fromEither(HarmonyChatBackend.make(config, http, telemetry))

/** The sealed actor-side capabilities admitted to a production run. Public
  * construction is available only through [[WorkerFactory.java]]; tests in this
  * package use the package-private constructor.
  */
sealed trait ActorWorker:
  def bdr: BdrSession
  def bootstrap: IO[BatError, WorkerBootstrap]
  def tools: Chunk[Tool]
  def prepareHandoff: IO[BatError, ProductionHandoff]

sealed trait WorkerFactory:
  def open: ZIO[Scope, BatError, ActorWorker]

object WorkerFactory:
  /** Adapt BAT's reviewed isolated Java worker without admitting caller-owned
    * tools into that trusted surface.
    */
  def java(
      openSession: ZIO[Scope, WorkerError, JavaWorkerSession]
  ): WorkerFactory =
    new WorkerFactory:
      def open: ZIO[Scope, BatError, ActorWorker] =
        openSession
          .mapError(RunnerFailure.worker)
          .map { session =>
            new ActorWorker:
              val bdr: BdrSession = ReceiptBoundBdrSession.make(session)
              val tools: Chunk[Tool] = WorkerTools.all(session)

              def bootstrap: IO[BatError, WorkerBootstrap] =
                session.workspaceBootstrap
                  .mapError(RunnerFailure.worker)
                  .flatMap(value =>
                    ZIO.fromEither(
                      WorkerBootstrap.make(
                        value.runId.value,
                        value.baseCommit.value,
                        value.startingHeadCommit.value,
                        value.sourceIdentityDigest,
                        value.workspace.revision.value,
                        value.workspace.fingerprint.value,
                        value.imageDigest,
                        value.buildPolicyId
                      )
                    )
                  )

              def prepareHandoff: IO[BatError, ProductionHandoff] =
                session.prepareHandoff
                  .mapError(RunnerFailure.worker)
                  .map(ProductionHandoff.fromWorker)
          }

  private[runner] def test(
      openWorker: ZIO[Scope, BatError, ActorWorker]
  ): WorkerFactory =
    new WorkerFactory:
      def open: ZIO[Scope, BatError, ActorWorker] = openWorker

object ActorWorker:
  private[runner] def test(
      session: BdrSession,
      initial: IO[BatError, WorkerBootstrap],
      workerTools: Chunk[Tool],
      handoff: IO[BatError, ProductionHandoff]
  ): ActorWorker =
    new ActorWorker:
      val bdr: BdrSession = session
      val tools: Chunk[Tool] = workerTools
      def bootstrap: IO[BatError, WorkerBootstrap] = initial
      def prepareHandoff: IO[BatError, ProductionHandoff] = handoff

/** Injected trusted evaluation boundary, invoked only after the actor worker
  * and backend scopes have closed. The runner does not implement evaluator
  * isolation: the supplied implementation owns any fresh OCI sandbox, sealed
  * oracle mounts, and evaluator resource policy.
  */
trait ProductionEvaluator:
  def evaluate(
      handoff: ProductionHandoff
  ): ZIO[Scope, BatError, EvaluationReport]

final case class ProductionRunConfig private (
    runId: TelemetryRunId,
    deployment: DeploymentFingerprint,
    reasoningEffort: String,
    bdrCommit: String,
    budgets: BudgetLimits,
    requiredCapabilities: Set[Capability] = Set(Capability.Streaming)
)

object ProductionRunConfig:
  private val GitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val Placeholder = Set(
    "auto",
    "default",
    "latest",
    "unknown",
    "unspecified"
  )

  def make(
      runId: TelemetryRunId,
      deployment: DeploymentFingerprint,
      reasoningEffort: String,
      bdrCommit: String,
      budgets: BudgetLimits,
      requiredCapabilities: Set[Capability] = Set(Capability.Streaming)
  ): Either[BatError, ProductionRunConfig] =
    val effort = Option(reasoningEffort).map(_.trim).getOrElse("")
    val runIdText =
      if runId.asInstanceOf[AnyRef] == null then null else runId.value
    if TelemetryRunId.from(runIdText).isLeft then
      Left(RunnerFailure.invalid("production run ID is invalid"))
    else if Option(deployment).isEmpty then
      Left(RunnerFailure.invalid("production deployment is required"))
    else if effort.isEmpty || Placeholder.contains(effort.toLowerCase) then
      Left(
        RunnerFailure.invalid("production reasoning effort must be pinned")
      )
    else if bdrCommit == null || !GitObject.matches(bdrCommit) then
      Left(RunnerFailure.invalid("production BDR commit must be pinned"))
    else if Option(budgets).isEmpty then
      Left(RunnerFailure.invalid("production budgets are required"))
    else if Option(requiredCapabilities).isEmpty ||
      requiredCapabilities.exists(value => Option(value).isEmpty)
    then
      Left(
        RunnerFailure.invalid(
          "production required capabilities must be explicit"
        )
      )
    else
      Right(
        ProductionRunConfig(
          runId,
          deployment,
          effort,
          bdrCommit,
          budgets,
          requiredCapabilities
        )
      )

final case class WorkerBootstrap private (
    workerRunId: String,
    baseCommit: String,
    startingHeadCommit: String,
    sourceIdentitySha256: String,
    workspaceRevision: Long,
    workspaceFingerprint: String,
    imageSha256: String,
    buildPolicyId: String
)

object WorkerBootstrap:
  private val GitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val Digest = "^[0-9a-f]{64}$".r
  private val SafeId = "^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}$".r

  def make(
      workerRunId: String,
      baseCommit: String,
      startingHeadCommit: String,
      sourceIdentitySha256: String,
      workspaceRevision: Long,
      workspaceFingerprint: String,
      imageSha256: String,
      buildPolicyId: String
  ): Either[BatError, WorkerBootstrap] =
    if workerRunId == null || !SafeId.matches(workerRunId) then
      Left(RunnerFailure.invalid("runner worker run identity is invalid"))
    else if baseCommit == null || !GitObject.matches(baseCommit) then
      Left(RunnerFailure.invalid("runner bootstrap base commit is invalid"))
    else if startingHeadCommit == null ||
      !GitObject.matches(startingHeadCommit)
    then
      Left(
        RunnerFailure.invalid("runner bootstrap starting head is invalid")
      )
    else if sourceIdentitySha256 == null ||
      !Digest.matches(sourceIdentitySha256)
    then Left(RunnerFailure.invalid("runner source identity is invalid"))
    else if workspaceRevision < 0L then
      Left(
        RunnerFailure.invalid("runner bootstrap revision must be non-negative")
      )
    else if workspaceFingerprint == null ||
      !Digest.matches(workspaceFingerprint)
    then
      Left(
        RunnerFailure.invalid("runner bootstrap fingerprint is invalid")
      )
    else if imageSha256 == null || !Digest.matches(imageSha256) then
      Left(RunnerFailure.invalid("runner worker image digest is invalid"))
    else if buildPolicyId == null || !SafeId.matches(buildPolicyId) then
      Left(RunnerFailure.invalid("runner build policy identity is invalid"))
    else
      Right(
        WorkerBootstrap(
          workerRunId,
          baseCommit,
          startingHeadCommit,
          sourceIdentitySha256,
          workspaceRevision,
          workspaceFingerprint,
          imageSha256,
          buildPolicyId
        )
      )

final case class ProductionHandoff private (
    finalHeadCommit: String,
    patchPath: Path,
    patchSha256: String,
    patchBytes: Long
):
  override def toString: String =
    "ProductionHandoff(identity=<redacted>, patch=<redacted>)"

object ProductionHandoff:
  private val GitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val Digest = "^[0-9a-f]{64}$".r

  def make(
      finalHeadCommit: String,
      patchPath: Path,
      patchSha256: String,
      patchBytes: Long
  ): Either[BatError, ProductionHandoff] =
    if finalHeadCommit == null || !GitObject.matches(finalHeadCommit) then
      Left(RunnerFailure.invalid("runner handoff commit is invalid"))
    else if patchPath == null || !patchPath.isAbsolute then
      Left(RunnerFailure.invalid("runner handoff patch path is invalid"))
    else if patchSha256 == null || !Digest.matches(patchSha256) then
      Left(RunnerFailure.invalid("runner handoff patch digest is invalid"))
    else if patchBytes < 0L then
      Left(RunnerFailure.invalid("runner handoff patch size is invalid"))
    else
      Right(
        ProductionHandoff(
          finalHeadCommit,
          patchPath.normalize,
          patchSha256,
          patchBytes
        )
      )

  private[runner] def fromWorker(
      value: VerifiedWorkerResult
  ): ProductionHandoff =
    ProductionHandoff(
      value.localHead.value,
      value.patchPath.toAbsolutePath.normalize,
      value.patchDigest.value,
      value.patchBytes
    )

final case class EvaluationReport private (
    evaluator: String,
    evaluatorRevision: String,
    finalHeadCommit: String,
    patchSha256: String,
    passed: Boolean,
    resultDigest: String
):
  override def toString: String =
    s"EvaluationReport(passed=$passed, payload=<redacted>)"

object EvaluationReport:
  private val Identifier = "^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}$".r
  private val GitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val Digest = "^[0-9a-f]{64}$".r

  def make(
      evaluator: String,
      evaluatorRevision: String,
      finalHeadCommit: String,
      patchSha256: String,
      passed: Boolean,
      resultDigest: String
  ): Either[BatError, EvaluationReport] =
    if evaluator == null || !Identifier.matches(evaluator) then
      Left(RunnerFailure.invalid("runner evaluator identity is invalid"))
    else if evaluatorRevision == null ||
      !Identifier.matches(evaluatorRevision)
    then Left(RunnerFailure.invalid("runner evaluator revision is invalid"))
    else if finalHeadCommit == null || !GitObject.matches(finalHeadCommit) then
      Left(RunnerFailure.invalid("runner evaluated commit is invalid"))
    else if patchSha256 == null || !Digest.matches(patchSha256) then
      Left(RunnerFailure.invalid("runner evaluated patch digest is invalid"))
    else if resultDigest == null || !Digest.matches(resultDigest) then
      Left(RunnerFailure.invalid("runner evaluation digest is invalid"))
    else
      Right(
        EvaluationReport(
          evaluator,
          evaluatorRevision,
          finalHeadCommit,
          patchSha256,
          passed,
          resultDigest
        )
      )

final case class RunnerContract(
    promptVersion: String,
    promptSha256: String,
    toolContractSha256: String,
    toolNames: Chunk[String]
)

/** Canonical, payload-free evidence for the complete production-run decision.
  * It contains digests and reviewed machine identifiers, never prompt text,
  * model output, tool payloads, local paths, or evaluator output.
  */
final case class ProductionEvidence private (
    json: Json.Obj,
    canonicalJson: String,
    sha256: String
):
  override def toString: String =
    s"ProductionEvidence(sha256=$sha256, payload=<redacted>)"

object ProductionEvidence:
  val Schema = "bat.dev/production-run-evidence"
  val Version = 1

  private[runner] def make(
      contract: RunnerContract,
      loop: LoopResult,
      bootstrap: WorkerBootstrap,
      handoff: Option[ProductionHandoff],
      evaluation: Option[EvaluationReport],
      telemetryCanonicalJson: String
  ): Either[BatError, ProductionEvidence] =
    for
      decision <- evidenceDecision(loop.outcome, handoff, evaluation)
      telemetryDigest <- digestText(
        telemetryCanonicalJson,
        "canonical telemetry"
      )
      contractJson <- contractJson(contract)
      value = Json.Obj(
        Chunk(
          "schema" -> Json.Str(Schema),
          "version" -> Json.Num(BigDecimal(Version)),
          "decision" -> Json.Str(decision),
          "contract" -> contractJson,
          "source" -> sourceJson(bootstrap),
          "worker" -> workerJson(bootstrap),
          "bdr" -> bdrJson(loop),
          "handoff" -> handoff.fold[Json](Json.Null)(handoffJson),
          "evaluation" -> evaluation.fold[Json](Json.Null)(evaluationJson),
          "telemetry_sha256" -> Json.Str(telemetryDigest)
        )
      )
      canonical <- StrictJson.canonical(value, "production run evidence")
      evidenceDigest <- digestText(canonical, "canonical production evidence")
    yield ProductionEvidence(value, canonical, evidenceDigest)

  private def sourceJson(value: WorkerBootstrap): Json.Obj =
    Json.Obj(
      Chunk(
        "base_commit" -> Json.Str(value.baseCommit),
        "starting_head_commit" -> Json.Str(value.startingHeadCommit),
        "identity_sha256" -> Json.Str(value.sourceIdentitySha256)
      )
    )

  private def workerJson(value: WorkerBootstrap): Json.Obj =
    Json.Obj(
      Chunk(
        "run_id" -> Json.Str(value.workerRunId),
        "image_sha256" -> Json.Str(value.imageSha256),
        "build_policy" -> Json.Str(value.buildPolicyId)
      )
    )

  private def bdrJson(value: LoopResult): Json.Obj =
    Json.Obj(
      Chunk(
        "engine_commit" -> Json.Str(value.pins.bdrCommit),
        "revision" -> Json.Num(BigDecimal(value.bdrState.revision.value)),
        "run_state" -> Json.Str(value.bdrState.runState),
        "state_sha256" -> Json.Str(value.bdrState.view.stateDigest)
      )
    )

  private def evidenceDecision(
      loopOutcome: RunOutcome,
      handoff: Option[ProductionHandoff],
      evaluation: Option[EvaluationReport]
  ): Either[BatError, String] =
    (loopOutcome, handoff, evaluation) match
      case (
            RunOutcome.ReadyForReview,
            Some(delivered),
            Some(report)
          )
          if report.finalHeadCommit == delivered.finalHeadCommit &&
            report.patchSha256 == delivered.patchSha256 =>
        Right(if report.passed then "ready" else "rejected")
      case (RunOutcome.TerminalHandoff, None, None) => Right("terminal")
      case _                                        =>
        Left(
          BatError.ProtocolViolation(
            "production evidence inputs are inconsistent"
          )
        )

  private def contractJson(
      value: RunnerContract
  ): Either[BatError, Json.Obj] =
    val Digest = "^[0-9a-f]{64}$".r
    if Option(value).isEmpty ||
      Option(value.promptVersion).forall(_.trim.isEmpty) ||
      Option(value.promptSha256).forall(text => !Digest.matches(text)) ||
      Option(value.toolContractSha256).forall(text => !Digest.matches(text))
    then Left(BatError.ProtocolViolation("production contract is invalid"))
    else
      Right(
        Json.Obj(
          Chunk(
            "prompt_version" -> Json.Str(value.promptVersion),
            "prompt_sha256" -> Json.Str(value.promptSha256),
            "tool_contract_sha256" -> Json.Str(value.toolContractSha256)
          )
        )
      )

  private def handoffJson(value: ProductionHandoff): Json.Obj =
    Json.Obj(
      Chunk(
        "final_head_commit" -> Json.Str(value.finalHeadCommit),
        "patch_sha256" -> Json.Str(value.patchSha256),
        "patch_bytes" -> Json.Num(BigDecimal(value.patchBytes))
      )
    )

  private def evaluationJson(value: EvaluationReport): Json.Obj =
    Json.Obj(
      Chunk(
        "evaluator" -> Json.Str(value.evaluator),
        "evaluator_revision" -> Json.Str(value.evaluatorRevision),
        "final_head_commit" -> Json.Str(value.finalHeadCommit),
        "patch_sha256" -> Json.Str(value.patchSha256),
        "passed" -> Json.Bool(value.passed),
        "result_sha256" -> Json.Str(value.resultDigest)
      )
    )

  private def digestText(
      value: String,
      label: String
  ): Either[BatError, String] =
    Option(value)
      .filter(_.nonEmpty)
      .map(text => ToolContract.sha256(text.getBytes(StandardCharsets.UTF_8)))
      .toRight(BatError.ProtocolViolation(s"$label is unavailable"))

sealed trait ProductionRunResult:
  def loop: LoopResult
  def contract: RunnerContract
  def telemetry: TelemetryDocument
  def evidence: ProductionEvidence

object ProductionRunResult:
  final case class Ready(
      loop: LoopResult,
      handoff: ProductionHandoff,
      evaluation: EvaluationReport,
      contract: RunnerContract,
      telemetry: TelemetryDocument,
      evidence: ProductionEvidence
  ) extends ProductionRunResult

  /** A delivered handoff that survived the actor gates but was rejected by the
    * independent trusted evaluator.
    */
  final case class Rejected(
      loop: LoopResult,
      handoff: ProductionHandoff,
      evaluation: EvaluationReport,
      contract: RunnerContract,
      telemetry: TelemetryDocument,
      evidence: ProductionEvidence
  ) extends ProductionRunResult

  /** A valid non-success BDR terminal. It intentionally has no source handoff
    * and no evaluator result, but retains the complete canonical run evidence.
    */
  final case class Terminal(
      loop: LoopResult,
      contract: RunnerContract,
      telemetry: TelemetryDocument,
      evidence: ProductionEvidence
  ) extends ProductionRunResult

object ProductionRunner:
  /** Compose one backend, one scoped actor worker, and one injected trusted
    * evaluator boundary. Actor resources are closed before evaluator
    * acquisition. The evaluator implementation—not this composition function—is
    * responsible for process isolation and oracle sealing.
    */
  def run(
      config: ProductionRunConfig,
      backendFactory: BackendFactory,
      workerFactory: WorkerFactory,
      evaluator: ProductionEvaluator
  ): IO[BatError, ProductionRunResult] =
    InMemoryTelemetry.make.flatMap(telemetry =>
      runObserved(
        config,
        backendFactory,
        workerFactory,
        evaluator,
        telemetry
      )
    )

  /** Run with a caller-owned collector. The exact same sink is shared by the
    * provider and controller, and remains readable when this effect fails, so
    * an embedding can retain sanitized evidence from an expensive failed
    * attempt. The collector must be freshly allocated for exactly one run and
    * must not be shared concurrently.
    */
  def runObserved(
      config: ProductionRunConfig,
      backendFactory: BackendFactory,
      workerFactory: WorkerFactory,
      evaluator: ProductionEvaluator,
      telemetry: InMemoryTelemetry
  ): IO[BatError, ProductionRunResult] =
    ZIO
      .fail(
        BatError.ProtocolViolation("production runner inputs are required")
      )
      .when(
        Option(config).isEmpty ||
          Option(backendFactory).isEmpty ||
          Option(workerFactory).isEmpty ||
          Option(evaluator).isEmpty ||
          Option(telemetry).isEmpty
      ) *>
      ZIO.scoped {
        for
          prompt <- JavaBdrPrompt.load
          actor <- RunnerBoundary.guarded(
            "actor_boundary_defect",
            "production actor boundary failed"
          )(
            ZIO.scoped {
              for
                backend <- RunnerBoundary
                  .guarded(
                    "backend_factory_defect",
                    "backend factory failed"
                  )(backendFactory.open(telemetry))
                  .flatMap(value =>
                    RunnerBoundary.required(
                      value,
                      "backend_factory_invalid",
                      "backend factory returned no backend"
                    )
                  )
                identity <- RunnerBoundary.guarded(
                  "backend_identity_defect",
                  "backend identity could not be read"
                )(ZIO.suspendSucceed(ZIO.succeed(backend.identity)))
                _ <- RunnerBoundary.required(
                  identity,
                  "backend_identity_invalid",
                  "backend returned no identity"
                )
                _ <- ZIO
                  .fail(
                    BatError.ProtocolViolation(
                      "deployment identity does not match the opened backend"
                    )
                  )
                  .unless(config.deployment.matchesBackend(identity))
                result <- runActor(
                  config,
                  backend,
                  identity,
                  workerFactory,
                  telemetry,
                  prompt
                )
              yield result
            }
          )
          evaluation <- actor.handoff match
            case Some(handoff) =>
              RunnerBoundary
                .guarded(
                  "evaluator_boundary_defect",
                  "trusted evaluator boundary failed"
                )(ZIO.scoped(evaluator.evaluate(handoff)))
                .flatMap(report => verifyEvaluationBinding(handoff, report))
                .map(Some(_))
            case None => ZIO.none
          document <- telemetry
            .document(config.runId, config.deployment)
            .flatMap(value =>
              ZIO.fromEither(value.left.map(RunnerFailure.telemetry))
            )
          telemetryCanonical <- ZIO.fromEither(
            document.canonicalJson.left.map(RunnerFailure.telemetry)
          )
          evidence <- ZIO.fromEither(
            ProductionEvidence.make(
              actor.contract,
              actor.loop,
              actor.bootstrap,
              actor.handoff,
              evaluation,
              telemetryCanonical
            )
          )
          result <- (actor.handoff, evaluation) match
            case (Some(handoff), Some(report)) if report.passed =>
              ZIO.succeed(
                ProductionRunResult.Ready(
                  actor.loop,
                  handoff,
                  report,
                  actor.contract,
                  document,
                  evidence
                )
              )
            case (Some(handoff), Some(report)) =>
              ZIO.succeed(
                ProductionRunResult.Rejected(
                  actor.loop,
                  handoff,
                  report,
                  actor.contract,
                  document,
                  evidence
                )
              )
            case (None, None) =>
              ZIO.succeed(
                ProductionRunResult.Terminal(
                  actor.loop,
                  actor.contract,
                  document,
                  evidence
                )
              )
            case _ =>
              ZIO.fail(
                BatError.ProtocolViolation(
                  "runner handoff and evaluation state disagree"
                )
              )
        yield result
      }

  private def verifyEvaluationBinding(
      handoff: ProductionHandoff,
      report: EvaluationReport
  ): IO[BatError, EvaluationReport] =
    if Option(report).exists(value =>
        value.finalHeadCommit == handoff.finalHeadCommit &&
          value.patchSha256 == handoff.patchSha256
      )
    then ZIO.succeed(report)
    else
      ZIO.fail(
        BatError.ProtocolViolation(
          "evaluation report does not match the delivered handoff"
        )
      )

  private final case class ActorResult(
      loop: LoopResult,
      handoff: Option[ProductionHandoff],
      contract: RunnerContract,
      bootstrap: WorkerBootstrap
  )

  private def runActor(
      config: ProductionRunConfig,
      backend: Backend,
      identity: BackendIdentity,
      workerFactory: WorkerFactory,
      telemetry: Telemetry,
      prompt: JavaBdrPrompt
  ): ZIO[Scope, BatError, ActorResult] =
    for
      worker <- RunnerBoundary
        .guarded("worker_factory_defect", "worker factory failed")(
          workerFactory.open
        )
        .flatMap(value =>
          RunnerBoundary.required(
            value,
            "worker_factory_invalid",
            "worker factory returned no worker"
          )
        )
      workerSurface <- RunnerBoundary.guarded(
        "worker_surface_defect",
        "worker surface could not be read"
      )(ZIO.suspendSucceed(ZIO.succeed(worker.bdr -> worker.tools)))
      (bdr, workerTools) = workerSurface
      _ <- ZIO
        .fail(
          BatError.ProtocolViolation("worker surface is incomplete")
        )
        .when(Option(bdr).isEmpty || Option(workerTools).isEmpty)
      bootstrap <- RunnerBoundary
        .guarded("worker_bootstrap_defect", "worker bootstrap failed")(
          worker.bootstrap
        )
        .flatMap(value =>
          RunnerBoundary.required(
            value,
            "worker_bootstrap_invalid",
            "worker returned no bootstrap"
          )
        )
      _ <- ZIO
        .fail(
          BatError.ProtocolViolation(
            "worker run identity does not match the production run"
          )
        )
        .unless(bootstrap.workerRunId == config.runId.value)
      allTools = BdrTools.all(bdr) ++ workerTools
      contract <- RunnerBoundary.guarded(
        "tool_contract_defect",
        "production tool contract could not be read"
      )(ZIO.fromEither(ToolContract.pin(prompt, allTools)))
      registry <- ZIO.fromEither(ToolRegistry.make(allTools))
      pins <- ZIO.fromEither(
        RunPins.make(
          identity,
          config.reasoningEffort,
          contract.promptVersion,
          config.bdrCommit
        )
      )
      developer <- ZIO.fromEither(DeveloperInput.make(prompt.text))
      user <- ZIO.fromEither(bootstrapUser(bootstrap))
      spec = RunSpec.make(
        RunMode.FullWriter,
        pins,
        developer,
        user,
        config.budgets,
        config.requiredCapabilities
      )
      loop <- AgenticLoop.run(spec, backend, registry, bdr, telemetry)
      handoff <- loop.outcome match
        case RunOutcome.ReadyForReview =>
          RunnerBoundary
            .guarded("worker_handoff_defect", "worker handoff failed")(
              worker.prepareHandoff
            )
            .flatMap(value =>
              RunnerBoundary.required(
                value,
                "worker_handoff_invalid",
                "worker returned no handoff"
              )
            )
            .map(Some(_))
        case RunOutcome.TerminalHandoff => ZIO.none
        case RunOutcome.AuditComplete   =>
          ZIO.fail(
            BatError.ProtocolViolation(
              "full-writer production run returned an audit outcome"
            )
          )
    yield ActorResult(loop, handoff, contract, bootstrap)

  private def bootstrapUser(
      value: WorkerBootstrap
  ): Either[BatError, UserInput] =
    UserInput.make(
      s"""Analyze and repair the pinned Java pull-request change through all six BDR phases.
         |Trusted starting values (refresh with worker_workspace after any mutation):
         |base_commit=${value.baseCommit}
         |starting_head_commit=${value.startingHeadCommit}
         |workspace_revision=${value.workspaceRevision}
         |workspace_fingerprint=${value.workspaceFingerprint}
         |Begin by reading worker_target_diff. Stop only at a validated BDR handoff.""".stripMargin
    )

private final case class JavaBdrPrompt(
    version: String,
    text: String,
    sha256: String
)

private object JavaBdrPrompt:
  private val Resource = "/bat/runner/java-bdr-v1.md"
  private val Version = "java-bdr-v1"
  private val MaxBytes = 64 * 1024

  def load: IO[BatError, JavaBdrPrompt] =
    ZIO
      .attemptBlocking {
        val stream = Option(getClass.getResourceAsStream(Resource))
          .getOrElse(throw new IllegalStateException("prompt resource missing"))
        try
          val bytes = stream.readNBytes(MaxBytes + 1)
          if bytes.length == 0 || bytes.length > MaxBytes then
            throw new IllegalStateException("prompt resource size invalid")
          val text = String(bytes, StandardCharsets.UTF_8)
          JavaBdrPrompt(Version, text, ToolContract.sha256(bytes))
        finally stream.close()
      }
      .mapError(_ =>
        BatError.BackendFailure(
          "runner_prompt_unavailable",
          "versioned Java BDR prompt could not be loaded",
          retryable = false
        )
      )

private object ToolContract:
  private val ToyPrefix = "toy_"

  def pin(
      prompt: JavaBdrPrompt,
      tools: Chunk[Tool]
  ): Either[BatError, RunnerContract] =
    val ordered = tools.sortBy(_.definition.name)
    val names = ordered.map(_.definition.name)
    if names.exists(_.startsWith(ToyPrefix)) then
      Left(
        BatError.ProtocolViolation(
          "host-executing toy tools are forbidden in a production run"
        )
      )
    else if names.distinct.size != names.size then
      Left(BatError.ProtocolViolation("production tool names must be unique"))
    else
      val value = Json.Arr(ordered.map { tool =>
        val definition = tool.definition
        Json.Obj(
          Chunk(
            "name" -> Json.Str(definition.name),
            "description" -> Json.Str(definition.description),
            "strict" -> Json.Bool(definition.strict),
            "parameters" -> definition.parameters
          )
        )
      })
      StrictJson
        .canonical(value, "production tool contract")
        .map(text => sha256(text.getBytes(StandardCharsets.UTF_8)))
        .map { toolDigest =>
          RunnerContract(
            promptVersion = s"${prompt.version}-p${prompt.sha256}-t$toolDigest",
            promptSha256 = prompt.sha256,
            toolContractSha256 = toolDigest,
            toolNames = names
          )
        }

  def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

private object RunnerBoundary:
  def guarded[R, A](
      defectCode: String,
      defectMessage: String
  )(
      effect: => ZIO[R, BatError, A]
  ): ZIO[R, BatError, A] =
    ZIO.suspendSucceed(effect).catchAllCause { cause =>
      if cause.isInterrupted then ZIO.refailCause(cause)
      else if cause.defects.isEmpty then
        cause.failureOption match
          case Some(error) => ZIO.fail(error)
          case None        => ZIO.refailCause(cause)
      else
        ZIO.fail(
          BatError.BackendFailure(
            defectCode,
            defectMessage,
            retryable = false
          )
        )
    }

  def required[A](
      value: A,
      code: String,
      message: String
  ): IO[BatError, A] =
    Option(value) match
      case Some(valid) => ZIO.succeed(valid)
      case None        =>
        ZIO.fail(BatError.BackendFailure(code, message, retryable = false))

private object RunnerFailure:
  def invalid(message: String): BatError =
    BatError.ProtocolViolation(message)

  def worker(error: WorkerError): BatError =
    BatError.BackendFailure(error.code, error.safeMessage, retryable = false)

  def telemetry(error: TelemetryError): BatError =
    BatError.BackendFailure(error.code, error.safeMessage, retryable = false)
