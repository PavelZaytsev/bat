package bat.worker

import bat.bdr.{BdrConfig, BdrInitialization, BdrSession, ValidatedBdrState}
import bat.protocol.StrictJson

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.util.Try

import zio.json.ast.Json
import zio.{IO, ZIO}

trait WorkerBdrLifecycle:
  def initialize(
      runId: RunId,
      repository: Path,
      pins: PullRequestPins
  ): IO[WorkerError, BdrSession]

  def resume(
      runId: RunId,
      repository: Path,
      pins: PullRequestPins
  ): IO[WorkerError, BdrSession]

object WorkerBdrLifecycle:
  def make(
      initializeSession: (
          RunId,
          Path,
          PullRequestPins
      ) => IO[WorkerError, BdrSession],
      resumeSession: (
          RunId,
          Path,
          PullRequestPins
      ) => IO[WorkerError, BdrSession]
  ): WorkerBdrLifecycle =
    new WorkerBdrLifecycle:
      def initialize(
          runId: RunId,
          repository: Path,
          pins: PullRequestPins
      ): IO[WorkerError, BdrSession] =
        initializeSession(runId, repository, pins).flatMap { session =>
          TrackerPinBinding.verify(repository, pins).as(session)
        }

      def resume(
          runId: RunId,
          repository: Path,
          pins: PullRequestPins
      ): IO[WorkerError, BdrSession] =
        resumeSession(runId, repository, pins).flatMap { session =>
          session.current
            .mapError(error =>
              WorkerError.LedgerFailure(error.code, error.safeMessage)
            ) *>
            TrackerPinBinding.verify(repository, pins).as(session)
        }

  /** Construct the production lifecycle around the pinned BAT BDR engine.
    * Initialization identity is derived only from the typed worker run ID and
    * authenticated PR pins; resume reopens that exact repository-bound tracker.
    */
  def live(
      engineArgv: zio.Chunk[String],
      engineSourceRoot: Path,
      engineEntryPoint: Path,
      commandTimeout: zio.Duration,
      actor: String,
      engineCommit: String
  ): WorkerBdrLifecycle =
    make(
      (runId, repository, pins) =>
        for
          config <- liveConfig(
            engineArgv,
            engineSourceRoot,
            engineEntryPoint,
            repository,
            commandTimeout,
            actor,
            engineCommit
          )
          initialization <- liveInitialization(
            pins,
            runId
          )
          session <- BdrSession
            .initialize(config, initialization)
            .mapError(bdrFailure)
        yield session,
      (runId, repository, pins) =>
        for
          config <- liveConfig(
            engineArgv,
            engineSourceRoot,
            engineEntryPoint,
            repository,
            commandTimeout,
            actor,
            engineCommit
          )
          initialization <- liveInitialization(
            pins,
            runId
          )
          session <- BdrSession
            .resume(config, initialization)
            .mapError(bdrFailure)
        yield session
    )

  private[worker] def liveInitialization(
      pins: PullRequestPins,
      runId: RunId
  ): IO[WorkerError, BdrInitialization] =
    ZIO.fromEither(
      BdrInitialization
        .make(
          pins.baseCommit.value,
          pins.headCommit.value,
          pins.baseRepository.value,
          runId.value
        )
        .left
        .map(bdrFailure)
    )

  private def liveConfig(
      engineArgv: zio.Chunk[String],
      engineSourceRoot: Path,
      engineEntryPoint: Path,
      repository: Path,
      commandTimeout: zio.Duration,
      actor: String,
      engineCommit: String
  ): IO[WorkerError, BdrConfig] =
    ZIO.fromEither(
      BdrConfig
        .make(
          engineArgv = engineArgv,
          engineSourceRoot = engineSourceRoot,
          engineEntryPoint = engineEntryPoint,
          repository = repository,
          commandTimeout = commandTimeout,
          actor = actor,
          engineCommit = engineCommit
        )
        .left
        .map(bdrFailure)
    )

  private def bdrFailure(error: bat.protocol.BatError): WorkerError =
    WorkerError.LedgerFailure(error.code, error.safeMessage)

object TrackerPinBinding:
  private val MaxTrackerBytes = 32L * 1024 * 1024

  def verify(
      repository: Path,
      pins: PullRequestPins
  ): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val normalized = repository.toAbsolutePath.normalize
        val bdr = normalized.resolve(".bdr")
        val path = bdr.resolve("progress.yaml")
        if Files.isSymbolicLink(normalized) ||
          !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(bdr) ||
          !Files.isDirectory(bdr, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(path) ||
          !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
          Files.size(path) > MaxTrackerBytes
        then throw TrackerFailure("tracker path is unsafe")
        Files.readString(path, StandardCharsets.UTF_8)
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "invalid_bdr_tracker",
          "BDR tracker could not be read safely"
        )
      )
      .flatMap(text =>
        ZIO.fromEither(
          StrictJson
            .parseObject(text, "worker BDR tracker")
            .left
            .map(error =>
              WorkerError.LedgerFailure(error.code, error.safeMessage)
            )
        )
      )
      .flatMap { tracker =>
        val valid = for
          source <- objectField(tracker, "source")
          base <- stringField(source, "base_sha")
          head <- stringField(source, "starting_head_sha")
          root <- stringField(source, "root")
          rootPath <- Try(Path.of(root)).toOption
          policy <- objectField(tracker, "policy")
          githubProjection <- stringField(policy, "github_projection")
          evidence <- objectField(tracker, "evidence")
        yield base == pins.baseCommit.value &&
          head == pins.headCommit.value &&
          rootPath.isAbsolute &&
          rootPath ==
          repository.toAbsolutePath.normalize &&
          githubProjection == "off" &&
          !evidence.fields.exists { case (_, value) =>
            value match
              case record: Json.Obj =>
                stringField(record, "kind").exists(
                  ControllerOnlyEvidenceKinds.contains
                )
              case _ => false
          }
        valid match
          case Some(true) => ZIO.unit
          case _          =>
            ZIO.fail(
              WorkerError.LedgerFailure(
                "bdr_pin_mismatch",
                "BDR tracker identity or controller-owned policy does not match the worker run manifest"
              )
            )
      }

  private def objectField(value: Json.Obj, name: String): Option[Json.Obj] =
    value.fields.collectFirst { case (`name`, child: Json.Obj) => child }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    value.fields.collectFirst { case (`name`, Json.Str(text)) => text }

  private val ControllerOnlyEvidenceKinds = Set("human_approval", "resume")

  private final case class TrackerFailure(message: String)
      extends RuntimeException(message)

object WorkerBdrTerminal:
  private val TerminalStates = Set(
    "verification_pending",
    "needs_human",
    "blocked_environment",
    "stale_input",
    "non_convergent",
    "failed_verification"
  )

  def require(state: ValidatedBdrState): IO[WorkerError, BdrHandoff] =
    val action = stringField(state.nextAction, "action")
    val valid =
      (state.runState == "ready_for_review" && action.contains("handoff")) ||
        (TerminalStates.contains(state.runState) &&
          action.contains("handoff_terminal") &&
          stringField(state.nextAction, "state").contains(state.runState) &&
          stringField(state.nextAction, "reason").exists(_.trim.nonEmpty))
    if valid then
      ZIO.succeed(
        BdrHandoff(
          state.revision.value,
          state.runState,
          state.view.stateDigest
        )
      )
    else
      ZIO.fail(
        WorkerError.LedgerFailure(
          "bdr_not_terminal",
          "verified handoff requires a terminal BDR checkpoint"
        )
      )

  private def stringField(value: Json.Obj, name: String): Option[String] =
    value.fields.collectFirst { case (`name`, Json.Str(text)) => text }

/** Binds every model-authored BDR command record to a receipt from the worker's
  * private ledger before it crosses the BDR state boundary.
  *
  * The model may name a command only as `{ "receipt_id": "..." }`. BAT resolves
  * that opaque reference and substitutes the canonical command record
  * authenticated by [[TrustedEvidenceMaterializer]]. Literal command records
  * and receipt references mixed with model-authored fields fail closed.
  */
object ReceiptBoundBdrSession:
  def make(worker: JavaWorkerSession): BdrSession =
    new BdrSession:
      val engineCommit: String = worker.bdr.engineCommit
      val actor: String = worker.bdr.actor

      def current = worker.bdr.current
      def checkpoint = worker.bdr.checkpoint
      def apply(operation: Json.Obj) = worker.applyReceiptBound(operation)
      def auditSummary = worker.bdr.auditSummary
      def completionCheck = worker.bdr.completionCheck

  private[worker] def wrap(
      delegate: BdrSession,
      resolve: String => IO[WorkerError, Json.Obj]
  ): BdrSession =
    new BdrSession:
      val engineCommit: String = delegate.engineCommit
      val actor: String = delegate.actor

      def current = delegate.current
      def checkpoint = delegate.checkpoint

      def apply(operation: Json.Obj) =
        ReceiptBoundOperation
          .materialize(operation, resolve)
          .mapError(error =>
            bat.protocol.BatError.BdrFailure(error.code, error.safeMessage)
          )
          .flatMap(delegate.apply)

      def auditSummary = delegate.auditSummary
      def completionCheck = delegate.completionCheck

private[worker] object ReceiptBoundOperation:
  private val ProtectedOperations = Map(
    "set_baseline" -> "baseline",
    "finish_phase" -> "gate",
    "add_evidence" -> "evidence",
    "record_fixed_point" -> "pass"
  )

  def materialize(
      operation: Json.Obj,
      resolve: String => IO[WorkerError, Json.Obj]
  ): IO[WorkerError, Json.Obj] =
    stringField(operation, "type") match
      case Some("batch") => materializeBatch(operation, resolve)
      case Some(operationType)
          if DisabledProductionOperations.contains(operationType) =>
        ZIO.fail(disabledProductionOperation)
      case Some("add_evidence")
          if objectField(operation, "evidence")
            .flatMap(stringField(_, "kind"))
            .exists(ControllerOnlyEvidenceKinds.contains) =>
        ZIO.fail(untrustedControllerEvidence)
      case Some(operationType) =>
        ProtectedOperations.get(operationType) match
          case Some(containerName) =>
            objectField(operation, containerName) match
              case Some(container) =>
                rejectCommandsOutsideContainer(operation, containerName) *>
                  materializeCommands(container, resolve)
                    .map(updated => replace(operation, containerName, updated))
              // Let the BDR engine retain authority over the remainder of the
              // operation schema. No command evidence crossed the boundary.
              case None => rejectAnyCommands(operation).as(operation)
          case None => rejectAnyCommands(operation).as(operation)
      case None => rejectAnyCommands(operation).as(operation)

  private def materializeBatch(
      operation: Json.Obj,
      resolve: String => IO[WorkerError, Json.Obj]
  ): IO[WorkerError, Json.Obj] =
    arrayField(operation, "operations") match
      case Some(children) =>
        rejectBatchEnvelope(operation) *>
          ZIO
            .foreach(children) {
              case child: Json.Obj =>
                materialize(child, resolve).map(identity[Json])
              case other => rejectAnyCommands(other).as(other)
            }
            .map(updated => replace(operation, "operations", Json.Arr(updated)))
      case None => rejectAnyCommands(operation).as(operation)

  private def materializeCommands(
      container: Json.Obj,
      resolve: String => IO[WorkerError, Json.Obj]
  ): IO[WorkerError, Json.Obj] =
    field(container, "commands") match
      case None                     => ZIO.succeed(container)
      case Some(Json.Arr(commands)) =>
        ZIO
          .foreach(commands)(command => materializeCommand(command, resolve))
          .map(updated => replace(container, "commands", Json.Arr(updated)))
      case Some(_) => ZIO.fail(invalidCommandEvidence)

  private def materializeCommand(
      value: Json,
      resolve: String => IO[WorkerError, Json.Obj]
  ): IO[WorkerError, Json] =
    value match
      case command: Json.Obj
          if command.fields.length == 1 &&
            command.fields.headOption.exists(_._1 == "receipt_id") =>
        command.fields.headOption.map(_._2) match
          case Some(Json.Str(receiptId)) =>
            resolve(receiptId).map(identity[Json])
          case _ => ZIO.fail(invalidCommandEvidence)
      case _ => ZIO.fail(invalidCommandEvidence)

  private def rejectCommandsOutsideContainer(
      operation: Json.Obj,
      containerName: String
  ): IO[WorkerError, Unit] =
    val unsafe = operation.fields.exists {
      case (`containerName`, container: Json.Obj) =>
        container.fields.exists {
          case ("commands", _) => false
          case (_, child)      => containsCommands(child)
        }
      case (name, child) => name == "commands" || containsCommands(child)
    }
    rejectWhen(unsafe)

  private def rejectBatchEnvelope(
      operation: Json.Obj
  ): IO[WorkerError, Unit] =
    rejectWhen(
      operation.fields.exists {
        case ("operations", _) => false
        case (name, child)     =>
          name == "commands" || containsCommands(child)
      }
    )

  private def rejectAnyCommands(value: Json): IO[WorkerError, Unit] =
    rejectWhen(containsCommands(value))

  private def rejectWhen(condition: Boolean): IO[WorkerError, Unit] =
    if condition then ZIO.fail(invalidCommandEvidence) else ZIO.unit

  private def containsCommands(value: Json): Boolean =
    value match
      case Json.Obj(fields) =>
        fields.exists { case (name, child) =>
          name == "commands" || containsCommands(child)
        }
      case Json.Arr(children) => children.exists(containsCommands)
      case _                  => false

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, child) => child }

  private def objectField(value: Json.Obj, name: String): Option[Json.Obj] =
    field(value, name).collect { case child: Json.Obj => child }

  private def arrayField(
      value: Json.Obj,
      name: String
  ): Option[zio.Chunk[Json]] =
    field(value, name).collect { case Json.Arr(children) => children }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(text) => text }

  private def replace(value: Json.Obj, name: String, child: Json): Json.Obj =
    Json.Obj(value.fields.map {
      case (`name`, _) => name -> child
      case other       => other
    })

  private val invalidCommandEvidence: WorkerError =
    WorkerError.LedgerFailure(
      "untrusted_command_evidence",
      "BDR command evidence must contain only a worker receipt ID"
    )

  private val ControllerOnlyEvidenceKinds = Set("human_approval", "resume")

  private val DisabledProductionOperations = Set(
    "configure_github",
    "project_github",
    "enqueue_github",
    "map_issue",
    "ack_github"
  )

  private val untrustedControllerEvidence: WorkerError =
    WorkerError.LedgerFailure(
      "untrusted_controller_evidence",
      "model-authored BDR evidence cannot assert controller authority"
    )

  private val disabledProductionOperation: WorkerError =
    WorkerError.LedgerFailure(
      "disabled_production_operation",
      "the isolated production worker does not admit GitHub projection operations"
    )

object TrustedEvidenceMaterializer:
  private val VerificationKinds = Set(
    WorkerOperationKind.MavenTest,
    WorkerOperationKind.MavenVerify,
    WorkerOperationKind.GradleTest,
    WorkerOperationKind.GradleCheck
  )

  private[worker] def materialize(
      receipt: TrustedReceipt,
      current: WorkspacePrecondition
  ): IO[WorkerError, Json.Obj] =
    for
      _ <-
        if receipt.afterRevision == current.revision &&
          receipt.afterFingerprint == current.fingerprint
        then ZIO.unit
        else
          ZIO.fail(
            WorkerError.LedgerFailure(
              "receipt_workspace_mismatch",
              "receipt was produced for a different workspace fingerprint"
            )
          )
      _ <-
        if VerificationKinds.contains(receipt.operationKind) then ZIO.unit
        else
          ZIO.fail(
            WorkerError.LedgerFailure(
              "receipt_not_verification",
              "only a Java test or verification receipt can become BDR command evidence"
            )
          )
      exitCode <- receipt.outcome match
        case CommandOutcome.Exited(code) => ZIO.succeed(code)
        case _                           =>
          ZIO.fail(
            WorkerError.LedgerFailure(
              "receipt_not_successful_process",
              "only an exited command can become BDR command evidence"
            )
          )
      imageDigest <- ZIO
        .fromOption(receipt.imageDigest)
        .orElseFail(
          WorkerError.LedgerFailure(
            "receipt_image_unpinned",
            "verification evidence requires a pinned worker image"
          )
        )
    yield Json.Obj(
      zio.Chunk(
        "command" -> Json.Str(receipt.requestIdentity),
        "policy" -> Json.Str(receipt.policyId),
        "image_sha256" -> Json.Str(imageDigest.value),
        "request_sha256" -> Json.Str(receipt.requestDigest.value),
        "exit_code" -> Json.Num(BigDecimal(exitCode)),
        "artifact" -> Json.Str(
          s"bat-receipt:${receipt.operationId.value};stdout-sha256:${receipt.stdoutDigest.value};stderr-sha256:${receipt.stderrDigest.value}"
        )
      )
    )
