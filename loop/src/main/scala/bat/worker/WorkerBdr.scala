package bat.worker

import bat.bdr.BdrSession
import bat.bdr.ValidatedBdrState
import bat.protocol.StrictJson

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.util.Try

import zio.json.ast.Json
import zio.{IO, ZIO}

trait WorkerBdrLifecycle:
  def initialize(
      repository: Path,
      pins: PullRequestPins
  ): IO[WorkerError, BdrSession]

  def resume(
      repository: Path,
      pins: PullRequestPins
  ): IO[WorkerError, BdrSession]

object WorkerBdrLifecycle:
  def make(
      initializeSession: (Path, PullRequestPins) => IO[WorkerError, BdrSession],
      resumeSession: (Path, PullRequestPins) => IO[WorkerError, BdrSession]
  ): WorkerBdrLifecycle =
    new WorkerBdrLifecycle:
      def initialize(
          repository: Path,
          pins: PullRequestPins
      ): IO[WorkerError, BdrSession] =
        initializeSession(repository, pins).flatMap { session =>
          TrackerPinBinding.verify(repository, pins).as(session)
        }

      def resume(
          repository: Path,
          pins: PullRequestPins
      ): IO[WorkerError, BdrSession] =
        resumeSession(repository, pins).flatMap { session =>
          session.current
            .mapError(error =>
              WorkerError.LedgerFailure(error.code, error.safeMessage)
            ) *>
            TrackerPinBinding.verify(repository, pins).as(session)
        }

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
        yield base == pins.baseCommit.value &&
          head == pins.headCommit.value &&
          rootPath.isAbsolute &&
          rootPath ==
          repository.toAbsolutePath.normalize
        valid match
          case Some(true) => ZIO.unit
          case _          =>
            ZIO.fail(
              WorkerError.LedgerFailure(
                "bdr_pin_mismatch",
                "BDR tracker source pins do not match the worker run manifest"
              )
            )
      }

  private def objectField(value: Json.Obj, name: String): Option[Json.Obj] =
    value.fields.collectFirst { case (`name`, child: Json.Obj) => child }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    value.fields.collectFirst { case (`name`, Json.Str(text)) => text }

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

final class TrustedEvidenceMaterializer(session: JavaWorkerSession):
  def command(receiptId: String): IO[WorkerError, Json.Obj] =
    for
      operationId <- ZIO.fromEither(OperationId.from(receiptId))
      snapshot <- session.trustedReceiptSnapshot(operationId)
      (current, stored) = snapshot
      receipt <- ZIO
        .fromOption(stored)
        .orElseFail(
          WorkerError.LedgerFailure(
            "unknown_receipt",
            "receipt ID is not present in this worker run"
          )
        )
      evidence <- TrustedEvidenceMaterializer.materialize(receipt, current)
    yield evidence

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
    yield Json.Obj(
      zio.Chunk(
        "command" -> Json.Str(receipt.requestIdentity),
        "policy" -> Json.Str(receipt.policyId),
        "request_sha256" -> Json.Str(receipt.requestDigest.value),
        "exit_code" -> Json.Num(BigDecimal(exitCode)),
        "artifact" -> Json.Str(
          s"bat-receipt:${receipt.operationId.value};stdout-sha256:${receipt.stdoutDigest.value};stderr-sha256:${receipt.stderrDigest.value}"
        )
      )
    )
