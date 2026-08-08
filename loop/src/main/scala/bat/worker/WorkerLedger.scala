package bat.worker

import java.nio.ByteBuffer
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{
  Files,
  LinkOption,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.security.MessageDigest

import scala.util.Try

import zio.{Chunk, IO, Ref, Scope, UIO, ZIO}

final case class OperationResult(
    receipt: TrustedReceipt,
    stdout: Chunk[Byte],
    stderr: Chunk[Byte],
    replayed: Boolean
)

final class WorkerLedger private (
    runDirectory: Path,
    ledgerPath: Path,
    authority: ReceiptAuthority,
    state: Ref.Synchronized[WorkerLedger.LedgerState]
):
  import WorkerLedger.*

  def execute(
      operation: WorkerOperation
  )(
      effect: IO[WorkerError, CompletedOperation]
  ): IO[WorkerError, OperationResult] =
    reserve(operation).flatMap {
      case Reservation.Replay(receipt) =>
        readArtifacts(receipt).map { case (stdout, stderr) =>
          OperationResult(receipt, stdout, stderr, replayed = true)
        }
      case Reservation.Execute =>
        effect.flatMap { completed =>
          for
            afterRevision <- ZIO.fromEither(
              nextRevision(operation, completed)
            )
            receipt = authority.issue(operation, completed, afterRevision)
            _ <- persistArtifacts(receipt, completed.observation)
            _ <- complete(operation, receipt)
          yield OperationResult(
            receipt,
            completed.observation.stdoutPreview,
            completed.observation.stderrPreview,
            replayed = false
          )
        }
    }

  def lookup(
      operationId: OperationId
  ): IO[WorkerError, Option[TrustedReceipt]] =
    state.get.map(_.entries.get(operationId).flatMap(_.receipt))

  def currentWorkspace: IO[WorkerError, WorkspacePrecondition] =
    state.get.flatMap { ledgerState =>
      ledgerState.pending match
        case Some(_) => ZIO.fail(indeterminateFailure)
        case None    => ZIO.succeed(ledgerState.workspace)
    }

  def pendingOperationId: UIO[Option[OperationId]] =
    state.get.map(_.pending)

  private[worker] def pendingOperation: UIO[Option[WorkerOperation]] =
    state.get.map(ledgerState =>
      ledgerState.pending.flatMap(operationId =>
        ledgerState.entries.get(operationId).map(_.operation)
      )
    )

  private[worker] def recoverInterruptedReadOnly(
      operation: WorkerOperation,
      observedFingerprint: WorkspaceFingerprint
  ): IO[WorkerError, Unit] =
    state.modifyZIO { ledgerState =>
      ledgerState.entries.get(operation.id) match
        case Some(Entry(existing, None))
            if ledgerState.pending.contains(operation.id) &&
              existing == operation &&
              !operation.kind.mutating &&
              operation.expectedWorkspace == ledgerState.workspace &&
              observedFingerprint == ledgerState.workspace.fingerprint =>
          append(recoveryPayload(operation, observedFingerprint)).as(
            () -> ledgerState.copy(pending = None)
          )
        case _ => ZIO.fail(indeterminateFailure)
    }

  private def reserve(
      operation: WorkerOperation
  ): IO[WorkerError, Reservation] =
    state.modifyZIO { ledgerState =>
      val entries = ledgerState.entries
      entries.get(operation.id) match
        case None =>
          if ledgerState.pending.nonEmpty then ZIO.fail(indeterminateFailure)
          else if operation.expectedWorkspace != ledgerState.workspace then
            ZIO.fail(
              WorkerError.LedgerFailure(
                "stale_workspace_revision",
                "operation workspace precondition is no longer current"
              )
            )
          else
            append(intentPayload(operation)).as(
              Reservation.Execute -> ledgerState.copy(
                entries = entries.updated(
                  operation.id,
                  Entry(operation, None)
                ),
                pending = Some(operation.id)
              )
            )
        case Some(existing) if existing.operation != operation =>
          ZIO.fail(
            WorkerError.LedgerFailure(
              "operation_id_conflict",
              "operation ID was already bound to different input"
            )
          )
        case Some(Entry(_, None)) =>
          if ledgerState.pending.nonEmpty then ZIO.fail(indeterminateFailure)
          else if operation.expectedWorkspace != ledgerState.workspace then
            ZIO.fail(
              WorkerError.LedgerFailure(
                "stale_workspace_revision",
                "operation workspace precondition is no longer current"
              )
            )
          else
            append(intentPayload(operation)).as(
              Reservation.Execute -> ledgerState.copy(
                pending = Some(operation.id)
              )
            )
        case Some(Entry(_, Some(receipt))) =>
          ZIO.succeed(Reservation.Replay(receipt) -> ledgerState)
    }

  private def complete(
      operation: WorkerOperation,
      receipt: TrustedReceipt
  ): IO[WorkerError, Unit] =
    state.modifyZIO { ledgerState =>
      val entries = ledgerState.entries
      entries.get(operation.id) match
        case Some(Entry(existing, None))
            if existing == operation && authority.verify(receipt) =>
          append(completionPayload(receipt)).as(
            () -> ledgerState.copy(
              entries = entries.updated(
                operation.id,
                Entry(operation, Some(receipt))
              ),
              workspace = WorkspacePrecondition(
                receipt.afterRevision,
                receipt.afterFingerprint
              ),
              pending = None
            )
          )
        case Some(Entry(_, Some(_))) =>
          ZIO.fail(
            WorkerError.LedgerFailure(
              "duplicate_completion",
              "operation already has a completion receipt"
            )
          )
        case _ =>
          ZIO.fail(
            WorkerError.LedgerFailure(
              "invalid_completion",
              "completion did not match a pending operation"
            )
          )
    }

  private def append(payload: String): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val tag = authority.authenticateRecord(payload)
        val bytes = s"$payload|$tag\n".getBytes(StandardCharsets.US_ASCII)
        val channel = FileChannel.open(
          ledgerPath,
          StandardOpenOption.WRITE,
          StandardOpenOption.APPEND
        )
        try
          val buffer = ByteBuffer.wrap(bytes)
          while buffer.hasRemaining do
            val _ = channel.write(buffer)
          channel.force(true)
        finally channel.close()
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "ledger_write_failed",
          "operation intent could not be committed durably"
        )
      )

  private def persistArtifacts(
      receipt: TrustedReceipt,
      observation: CommandObservation
  ): IO[WorkerError, Unit] =
    writeArtifact(receipt.operationId, "stdout", observation.stdoutPreview) *>
      writeArtifact(receipt.operationId, "stderr", observation.stderrPreview)

  private def writeArtifact(
      operationId: OperationId,
      stream: String,
      bytes: Chunk[Byte]
  ): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val finalPath = artifactPath(operationId, stream)
        val temporary = runDirectory.resolve(
          s".${operationId.value}.$stream.${java.util.UUID.randomUUID()}.tmp"
        )
        Files.write(
          temporary,
          bytes.toArray,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        restrictFile(temporary)
        try
          val _ = Files.move(
            temporary,
            finalPath,
            StandardCopyOption.ATOMIC_MOVE
          )
          ()
        catch
          case error: Throwable =>
            Files.deleteIfExists(temporary)
            throw error
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "artifact_write_failed",
          "bounded command output could not be stored safely"
        )
      )

  private def readArtifacts(
      receipt: TrustedReceipt
  ): IO[WorkerError, (Chunk[Byte], Chunk[Byte])] =
    for
      stdout <- readArtifact(
        receipt.operationId,
        "stdout",
        receipt.stdoutPreviewBytes,
        receipt.stdoutPreviewDigest
      )
      stderr <- readArtifact(
        receipt.operationId,
        "stderr",
        receipt.stderrPreviewBytes,
        receipt.stderrPreviewDigest
      )
    yield stdout -> stderr

  private def readArtifact(
      operationId: OperationId,
      stream: String,
      expectedBytes: Int,
      expectedDigest: Sha256Digest
  ): IO[WorkerError, Chunk[Byte]] =
    ZIO
      .attemptBlocking {
        val path = artifactPath(operationId, stream)
        if Files.isSymbolicLink(path) ||
          !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        then throw new IllegalStateException("artifact is not a regular file")
        val bytes = Files.readAllBytes(path)
        val digest = sha256(bytes)
        if bytes.length != expectedBytes || digest != expectedDigest.value then
          throw new IllegalStateException("artifact does not match receipt")
        Chunk.fromArray(bytes)
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "invalid_output_artifact",
          "stored command output does not match its trusted receipt"
        )
      )

  private def artifactPath(operationId: OperationId, stream: String): Path =
    runDirectory.resolve(s"${operationId.value}.$stream")

  private def nextRevision(
      operation: WorkerOperation,
      completed: CompletedOperation
  ): Either[WorkerError, WorkspaceRevision] =
    if operation.kind.mutating then
      if completed.afterFingerprint == operation.expectedWorkspace.fingerprint
      then Right(operation.expectedWorkspace.revision)
      else operation.expectedWorkspace.revision.next
    else if completed.afterFingerprint != operation.expectedWorkspace.fingerprint
    then
      Left(
        WorkerError.LedgerFailure(
          "unexpected_workspace_mutation",
          "read-only operation changed the authoring workspace"
        )
      )
    else Right(operation.expectedWorkspace.revision)

  private def indeterminateFailure: WorkerError =
    WorkerError.LedgerFailure(
      "indeterminate_operation",
      "run has durable intent without a trusted completion receipt"
    )

object WorkerLedger:
  private val LedgerFile = "operations.log"
  private val LockFile = "run.lock"
  private val MaxLedgerBytes = 8L * 1024 * 1024
  private val DigestPattern = "^[0-9a-f]{64}$".r

  private final case class Entry(
      operation: WorkerOperation,
      receipt: Option[TrustedReceipt]
  )

  private final case class LedgerState(
      entries: Map[OperationId, Entry],
      workspace: WorkspacePrecondition,
      pending: Option[OperationId]
  )

  private enum Reservation:
    case Execute
    case Replay(receipt: TrustedReceipt)

  def open(
      controlRoot: Path,
      runId: RunId,
      initialWorkspace: WorkspacePrecondition
  ): ZIO[Scope, WorkerError, WorkerLedger] =
    for
      runDirectory <- prepareRunDirectory(controlRoot, runId)
      authority <- ReceiptAuthority.open(runDirectory, runId)
      lock <- acquireLock(runDirectory.resolve(LockFile))
      _ <- ZIO.addFinalizer(releaseLock(lock))
      ledgerPath <- prepareLedger(runDirectory.resolve(LedgerFile))
      ledgerState <- load(ledgerPath, authority, initialWorkspace)
      state <- Ref.Synchronized.make(ledgerState)
    yield new WorkerLedger(runDirectory, ledgerPath, authority, state)

  private def prepareRunDirectory(
      controlRoot: Path,
      runId: RunId
  ): IO[WorkerError, Path] =
    ZIO
      .attemptBlocking {
        if controlRoot == null then
          throw new IllegalArgumentException("control root is required")
        val normalizedRoot = controlRoot.toAbsolutePath.normalize
        if Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS) then
          if Files.isSymbolicLink(normalizedRoot) ||
            !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
          then
            throw new IllegalStateException("control root is not a directory")
        else
          Files.createDirectories(normalizedRoot)
          restrictDirectory(normalizedRoot)
        val runDirectory = normalizedRoot.resolve(runId.value)
        if Files.exists(runDirectory, LinkOption.NOFOLLOW_LINKS) then
          if Files.isSymbolicLink(runDirectory) ||
            !Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)
          then throw new IllegalStateException("run directory is unsafe")
        else
          Files.createDirectory(runDirectory)
          restrictDirectory(runDirectory)
        runDirectory
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "invalid_control_directory",
          "worker control directory could not be opened safely"
        )
      )

  private def acquireLock(
      path: Path
  ): IO[WorkerError, (FileChannel, FileLock)] =
    ZIO
      .attemptBlocking {
        if Files.isSymbolicLink(path) then
          throw new IllegalStateException("run lock is a symlink")
        val channel = FileChannel.open(
          path,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE
        )
        restrictFile(path)
        try
          val lock = channel.tryLock()
          if lock == null then throw new IllegalStateException("run is locked")
          channel -> lock
        catch
          case error: Throwable =>
            channel.close()
            throw error
      }
      .mapError {
        case _: OverlappingFileLockException =>
          WorkerError.LedgerFailure(
            "run_already_active",
            "worker run already has an active controller"
          )
        case _ =>
          WorkerError.LedgerFailure(
            "run_lock_failed",
            "exclusive worker run lock could not be acquired"
          )
      }

  private def releaseLock(
      value: (FileChannel, FileLock)
  ): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
      val (channel, lock) = value
      try lock.release()
      finally channel.close()
    }.orDie

  private def prepareLedger(path: Path): IO[WorkerError, Path] =
    ZIO
      .attemptBlocking {
        if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
          if Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          then throw new IllegalStateException("ledger is unsafe")
        else
          Files.createFile(path)
          restrictFile(path)
        path
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "invalid_ledger",
          "operation ledger could not be opened safely"
        )
      )

  private def load(
      path: Path,
      authority: ReceiptAuthority,
      initialWorkspace: WorkspacePrecondition
  ): IO[WorkerError, LedgerState] =
    ZIO
      .attemptBlocking {
        val size = Files.size(path)
        if size > MaxLedgerBytes then
          throw ParseFailure("ledger exceeds its size limit")
        val bytes = Files.readAllBytes(path)
        if bytes.nonEmpty && bytes.last != '\n'.toByte then
          throw ParseFailure("ledger has an incomplete tail")
        val text = String(bytes, StandardCharsets.US_ASCII)
        text.linesIterator
          .filter(_.nonEmpty)
          .foldLeft(
            LedgerState(Map.empty, initialWorkspace, None)
          ) { (ledgerState, line) =>
            parseLine(line, ledgerState, authority)
          }
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "invalid_ledger",
          "operation ledger failed integrity validation"
        )
      )

  private def parseLine(
      line: String,
      ledgerState: LedgerState,
      authority: ReceiptAuthority
  ): LedgerState =
    if line.length > 4096 then throw ParseFailure("ledger line is too long")
    val parts = line.split("\\|", -1).toList
    val payloadParts = parts.dropRight(1)
    val recordTag =
      parts.lastOption.getOrElse(throw ParseFailure("missing tag"))
    val payload = payloadParts.mkString("|")
    if !DigestPattern.matches(recordTag) ||
      !authority.verifyRecord(payload, recordTag)
    then throw ParseFailure("invalid record authentication")
    payloadParts match
      case "I" :: id :: kind :: requestDigest :: requestIdentity ::
          beforeRevision :: beforeFingerprint :: policyId :: imageDigest :: Nil =>
        val operation = parseOperation(
          id,
          kind,
          requestDigest,
          requestIdentity,
          beforeRevision,
          beforeFingerprint,
          policyId,
          imageDigest
        )
        val existing = ledgerState.entries.get(operation.id)
        val validFirstIntent = existing.isEmpty
        val validRetryIntent = existing.exists(entry =>
          entry.operation == operation && entry.receipt.isEmpty
        )
        if ledgerState.pending.nonEmpty ||
          operation.expectedWorkspace != ledgerState.workspace ||
          (!validFirstIntent && !validRetryIntent)
        then throw ParseFailure("invalid operation intent")
        ledgerState.copy(
          entries = ledgerState.entries.updated(
            operation.id,
            Entry(operation, None)
          ),
          pending = Some(operation.id)
        )
      case "R" :: id :: kind :: requestDigest :: requestIdentity ::
          beforeRevision :: beforeFingerprint :: policyId :: imageDigest ::
          observedFingerprint :: Nil =>
        val operation = parseOperation(
          id,
          kind,
          requestDigest,
          requestIdentity,
          beforeRevision,
          beforeFingerprint,
          policyId,
          imageDigest
        )
        val existing = ledgerState.entries.getOrElse(
          operation.id,
          throw ParseFailure("recovery has no intent")
        )
        val observed = parseFingerprint(observedFingerprint)
        if ledgerState.pending != Some(operation.id) ||
          existing.operation != operation ||
          existing.receipt.nonEmpty ||
          operation.kind.mutating ||
          operation.expectedWorkspace != ledgerState.workspace ||
          observed != ledgerState.workspace.fingerprint
        then throw ParseFailure("recovery does not match pending intent")
        ledgerState.copy(pending = None)
      case "C" :: id :: kind :: requestDigest :: requestIdentity ::
          beforeRevision :: beforeFingerprint :: policyId :: imageDigest ::
          afterRevision :: afterFingerprint :: outcome :: stdoutDigest ::
          stderrDigest :: stdoutBytes :: stderrBytes :: stdoutPreviewDigest ::
          stderrPreviewDigest :: stdoutPreviewBytes :: stderrPreviewBytes ::
          durationMillis :: receiptTag :: Nil =>
        val operation = parseOperation(
          id,
          kind,
          requestDigest,
          requestIdentity,
          beforeRevision,
          beforeFingerprint,
          policyId,
          imageDigest
        )
        val existing = ledgerState.entries.getOrElse(
          operation.id,
          throw ParseFailure("completion has no intent")
        )
        if existing.operation != operation || existing.receipt.nonEmpty then
          throw ParseFailure("completion does not match pending intent")
        val receipt = authority
          .restore(
            operation,
            parseRevision(afterRevision),
            parseFingerprint(afterFingerprint),
            parseOutcome(outcome),
            parseDigest(stdoutDigest, "stdout_digest"),
            parseDigest(stderrDigest, "stderr_digest"),
            parseLong(stdoutBytes),
            parseLong(stderrBytes),
            parseDigest(stdoutPreviewDigest, "stdout_preview_digest"),
            parseDigest(stderrPreviewDigest, "stderr_preview_digest"),
            parseInt(stdoutPreviewBytes, 0, 16 * 1024 * 1024),
            parseInt(stderrPreviewBytes, 0, 16 * 1024 * 1024),
            parseLong(durationMillis),
            receiptTag
          )
          .fold(_ => throw ParseFailure("invalid receipt"), identity)
        if receipt.beforeRevision != ledgerState.workspace.revision ||
          receipt.beforeFingerprint != ledgerState.workspace.fingerprint ||
          (operation.kind.mutating &&
            (receipt.afterFingerprint == receipt.beforeFingerprint &&
              receipt.afterRevision != receipt.beforeRevision)) ||
          (operation.kind.mutating &&
            receipt.afterFingerprint != receipt.beforeFingerprint &&
            receipt.afterRevision.value != receipt.beforeRevision.value + 1L) ||
          (!operation.kind.mutating &&
            (receipt.afterRevision != receipt.beforeRevision ||
              receipt.afterFingerprint != receipt.beforeFingerprint))
        then throw ParseFailure("receipt workspace transition is invalid")
        ledgerState.copy(
          entries = ledgerState.entries.updated(
            operation.id,
            Entry(operation, Some(receipt))
          ),
          workspace = WorkspacePrecondition(
            receipt.afterRevision,
            receipt.afterFingerprint
          ),
          pending = None
        )
      case _ => throw ParseFailure("unknown ledger record")

  private def parseOperation(
      rawId: String,
      rawKind: String,
      rawDigest: String,
      rawRequestIdentity: String,
      rawRevision: String,
      rawFingerprint: String,
      rawPolicyId: String,
      rawImageDigest: String
  ): WorkerOperation =
    (for
      id <- OperationId.from(rawId)
      kind <- WorkerOperationKind.fromWire(rawKind)
      revision <- WorkspaceRevision.from(parseLong(rawRevision))
      fingerprint <- WorkspaceFingerprint.from(rawFingerprint)
      operation <- WorkerOperation.make(
        id,
        kind,
        rawDigest,
        rawRequestIdentity,
        WorkspacePrecondition(revision, fingerprint),
        rawPolicyId,
        Option.when(rawImageDigest != "-")(rawImageDigest)
      )
    yield operation)
      .fold(_ => throw ParseFailure("invalid operation"), identity)

  private def parseDigest(value: String, label: String): Sha256Digest =
    Sha256Digest
      .from(value, label)
      .fold(_ => throw ParseFailure("invalid digest"), identity)

  private def parseInt(value: String, minimum: Int, maximum: Int): Int =
    Try(value.toInt)
      .filter(number => number >= minimum && number <= maximum)
      .getOrElse(throw ParseFailure("invalid integer"))

  private def parseLong(value: String): Long =
    Try(value.toLong)
      .filter(_ >= 0L)
      .getOrElse(throw ParseFailure("invalid long"))

  private def parseRevision(value: String): WorkspaceRevision =
    WorkspaceRevision
      .from(parseLong(value))
      .fold(_ => throw ParseFailure("invalid revision"), identity)

  private def parseFingerprint(value: String): WorkspaceFingerprint =
    WorkspaceFingerprint
      .from(value)
      .fold(_ => throw ParseFailure("invalid fingerprint"), identity)

  private def parseOutcome(value: String): CommandOutcome = value match
    case "timed_out"                      => CommandOutcome.TimedOut
    case "output_limit"                   => CommandOutcome.OutputLimit
    case "start_failed"                   => CommandOutcome.StartFailed
    case exit if exit.startsWith("exit:") =>
      CommandOutcome.Exited(parseInt(exit.drop(5), 0, 255))
    case _ => throw ParseFailure("invalid command outcome")

  private def intentPayload(operation: WorkerOperation): String =
    List(
      "I",
      operation.id.value,
      operation.kind.wire,
      operation.requestDigest.value,
      operation.requestIdentity,
      operation.expectedWorkspace.revision.value.toString,
      operation.expectedWorkspace.fingerprint.value,
      operation.policyId,
      operation.imageDigest.map(_.value).getOrElse("-")
    ).mkString("|")

  private def completionPayload(receipt: TrustedReceipt): String =
    List(
      "C",
      receipt.operationId.value,
      receipt.operationKind.wire,
      receipt.requestDigest.value,
      receipt.requestIdentity,
      receipt.beforeRevision.value.toString,
      receipt.beforeFingerprint.value,
      receipt.policyId,
      receipt.imageDigest.map(_.value).getOrElse("-"),
      receipt.afterRevision.value.toString,
      receipt.afterFingerprint.value,
      outcomeWire(receipt.outcome),
      receipt.stdoutDigest.value,
      receipt.stderrDigest.value,
      receipt.stdoutBytes.toString,
      receipt.stderrBytes.toString,
      receipt.stdoutPreviewDigest.value,
      receipt.stderrPreviewDigest.value,
      receipt.stdoutPreviewBytes.toString,
      receipt.stderrPreviewBytes.toString,
      receipt.durationMillis.toString,
      receipt.authenticationTag
    ).mkString("|")

  private def recoveryPayload(
      operation: WorkerOperation,
      observedFingerprint: WorkspaceFingerprint
  ): String =
    List(
      "R",
      operation.id.value,
      operation.kind.wire,
      operation.requestDigest.value,
      operation.requestIdentity,
      operation.expectedWorkspace.revision.value.toString,
      operation.expectedWorkspace.fingerprint.value,
      operation.policyId,
      operation.imageDigest.map(_.value).getOrElse("-"),
      observedFingerprint.value
    ).mkString("|")

  private def outcomeWire(outcome: CommandOutcome): String = outcome match
    case CommandOutcome.Exited(code) => s"exit:$code"
    case CommandOutcome.TimedOut     => "timed_out"
    case CommandOutcome.OutputLimit  => "output_limit"
    case CommandOutcome.StartFailed  => "start_failed"

  private def restrictDirectory(path: Path): Unit =
    try
      val _ = Files.setPosixFilePermissions(
        path,
        PosixFilePermissions.fromString("rwx------")
      )
    catch case _: UnsupportedOperationException => ()

  private def restrictFile(path: Path): Unit =
    try
      val _ = Files.setPosixFilePermissions(
        path,
        PosixFilePermissions.fromString("rw-------")
      )
    catch case _: UnsupportedOperationException => ()

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private final case class ParseFailure(message: String)
      extends RuntimeException(message)
