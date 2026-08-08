package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.{MessageDigest, SecureRandom}

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import zio.{IO, ZIO}

final class TrustedReceipt private[worker] (
    val runId: RunId,
    val operationId: OperationId,
    val operationKind: WorkerOperationKind,
    val requestDigest: Sha256Digest,
    val requestIdentity: String,
    val beforeRevision: WorkspaceRevision,
    val beforeFingerprint: WorkspaceFingerprint,
    val afterRevision: WorkspaceRevision,
    val afterFingerprint: WorkspaceFingerprint,
    val policyId: String,
    val imageDigest: Option[Sha256Digest],
    val outcome: CommandOutcome,
    val stdoutDigest: Sha256Digest,
    val stderrDigest: Sha256Digest,
    val stdoutBytes: Long,
    val stderrBytes: Long,
    val stdoutPreviewDigest: Sha256Digest,
    val stderrPreviewDigest: Sha256Digest,
    val stdoutPreviewBytes: Int,
    val stderrPreviewBytes: Int,
    val durationMillis: Long,
    private[worker] val authenticationTag: String
):
  override def equals(other: Any): Boolean = other match
    case that: TrustedReceipt =>
      runId == that.runId &&
      operationId == that.operationId &&
      operationKind == that.operationKind &&
      requestDigest == that.requestDigest &&
      requestIdentity == that.requestIdentity &&
      beforeRevision == that.beforeRevision &&
      beforeFingerprint == that.beforeFingerprint &&
      afterRevision == that.afterRevision &&
      afterFingerprint == that.afterFingerprint &&
      policyId == that.policyId &&
      imageDigest == that.imageDigest &&
      outcome == that.outcome &&
      stdoutDigest == that.stdoutDigest &&
      stderrDigest == that.stderrDigest &&
      stdoutBytes == that.stdoutBytes &&
      stderrBytes == that.stderrBytes &&
      stdoutPreviewDigest == that.stdoutPreviewDigest &&
      stderrPreviewDigest == that.stderrPreviewDigest &&
      stdoutPreviewBytes == that.stdoutPreviewBytes &&
      stderrPreviewBytes == that.stderrPreviewBytes &&
      durationMillis == that.durationMillis &&
      authenticationTag == that.authenticationTag
    case _ => false

  override def hashCode(): Int =
    (
      runId,
      operationId,
      operationKind,
      requestDigest,
      requestIdentity,
      beforeRevision,
      beforeFingerprint,
      afterRevision,
      afterFingerprint,
      policyId,
      imageDigest,
      outcome,
      stdoutDigest,
      stderrDigest,
      stdoutBytes,
      stderrBytes,
      stdoutPreviewDigest,
      stderrPreviewDigest,
      stdoutPreviewBytes,
      stderrPreviewBytes,
      durationMillis,
      authenticationTag
    ).hashCode

object ReceiptAuthority:
  private val KeyFile = "receipt.key"
  private val KeyBytes = 32

  def open(
      runDirectory: Path,
      runId: RunId
  ): IO[WorkerError, ReceiptAuthority] =
    ZIO
      .attemptBlocking {
        requirePrivateDirectory(runDirectory)
        val keyPath = runDirectory.resolve(KeyFile)
        val key =
          if Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS) then
            if !Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS) ||
              Files.isSymbolicLink(keyPath)
            then throw new IllegalStateException("receipt key is not a file")
            Files.readAllBytes(keyPath)
          else
            val generated = Array.ofDim[Byte](KeyBytes)
            SecureRandom().nextBytes(generated)
            Files.write(
              keyPath,
              generated,
              StandardOpenOption.CREATE_NEW,
              StandardOpenOption.WRITE
            )
            restrictFile(keyPath)
            generated
        if key.length != KeyBytes then
          throw new IllegalStateException("receipt key has the wrong length")
        new ReceiptAuthority(runId, key)
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "invalid_receipt_authority",
          "receipt authority could not be opened safely"
        )
      )

  private def requirePrivateDirectory(path: Path): Unit =
    if path == null || Files.isSymbolicLink(path) ||
      !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    then throw new IllegalStateException("run directory is not trusted")

  private def restrictFile(path: Path): Unit =
    try
      val _ = Files.setPosixFilePermissions(
        path,
        PosixFilePermissions.fromString("rw-------")
      )
    catch case _: UnsupportedOperationException => ()

final class ReceiptAuthority private (
    runId: RunId,
    private val key: Array[Byte]
):
  def issue(
      operation: WorkerOperation,
      completed: CompletedOperation,
      afterRevision: WorkspaceRevision
  ): TrustedReceipt =
    val observation = completed.observation
    val stdoutPreviewDigest = digest(observation.stdoutPreview.toArray)
    val stderrPreviewDigest = digest(observation.stderrPreview.toArray)
    val unsigned = new TrustedReceipt(
      runId,
      operation.id,
      operation.kind,
      operation.requestDigest,
      operation.requestIdentity,
      operation.expectedWorkspace.revision,
      operation.expectedWorkspace.fingerprint,
      afterRevision,
      completed.afterFingerprint,
      operation.policyId,
      operation.imageDigest,
      observation.outcome,
      observation.stdoutDigest,
      observation.stderrDigest,
      observation.stdoutBytes,
      observation.stderrBytes,
      stdoutPreviewDigest,
      stderrPreviewDigest,
      observation.stdoutPreview.length,
      observation.stderrPreview.length,
      observation.durationMillis,
      ""
    )
    new TrustedReceipt(
      unsigned.runId,
      unsigned.operationId,
      unsigned.operationKind,
      unsigned.requestDigest,
      unsigned.requestIdentity,
      unsigned.beforeRevision,
      unsigned.beforeFingerprint,
      unsigned.afterRevision,
      unsigned.afterFingerprint,
      unsigned.policyId,
      unsigned.imageDigest,
      unsigned.outcome,
      unsigned.stdoutDigest,
      unsigned.stderrDigest,
      unsigned.stdoutBytes,
      unsigned.stderrBytes,
      unsigned.stdoutPreviewDigest,
      unsigned.stderrPreviewDigest,
      unsigned.stdoutPreviewBytes,
      unsigned.stderrPreviewBytes,
      unsigned.durationMillis,
      mac(receiptPayload(unsigned))
    )

  private[worker] def restore(
      operation: WorkerOperation,
      afterRevision: WorkspaceRevision,
      afterFingerprint: WorkspaceFingerprint,
      outcome: CommandOutcome,
      stdoutDigest: Sha256Digest,
      stderrDigest: Sha256Digest,
      stdoutBytes: Long,
      stderrBytes: Long,
      stdoutPreviewDigest: Sha256Digest,
      stderrPreviewDigest: Sha256Digest,
      stdoutPreviewBytes: Int,
      stderrPreviewBytes: Int,
      durationMillis: Long,
      tag: String
  ): Either[WorkerError, TrustedReceipt] =
    val receipt = new TrustedReceipt(
      runId,
      operation.id,
      operation.kind,
      operation.requestDigest,
      operation.requestIdentity,
      operation.expectedWorkspace.revision,
      operation.expectedWorkspace.fingerprint,
      afterRevision,
      afterFingerprint,
      operation.policyId,
      operation.imageDigest,
      outcome,
      stdoutDigest,
      stderrDigest,
      stdoutBytes,
      stderrBytes,
      stdoutPreviewDigest,
      stderrPreviewDigest,
      stdoutPreviewBytes,
      stderrPreviewBytes,
      durationMillis,
      tag
    )
    Either.cond(
      verify(receipt),
      receipt,
      WorkerError.LedgerFailure(
        "invalid_receipt_authentication",
        "ledger contains an unauthenticated receipt"
      )
    )

  def verify(receipt: TrustedReceipt): Boolean =
    receipt.runId == runId &&
      constantTimeEquals(
        receipt.authenticationTag,
        mac(receiptPayload(receipt))
      )

  private[worker] def authenticateRecord(payload: String): String =
    mac(s"ledger-v1\n$payload")

  private[worker] def verifyRecord(payload: String, tag: String): Boolean =
    constantTimeEquals(tag, authenticateRecord(payload))

  private def receiptPayload(receipt: TrustedReceipt): String =
    List(
      "receipt-v1",
      receipt.runId.value,
      receipt.operationId.value,
      receipt.operationKind.wire,
      receipt.requestDigest.value,
      receipt.requestIdentity,
      receipt.beforeRevision.value.toString,
      receipt.beforeFingerprint.value,
      receipt.afterRevision.value.toString,
      receipt.afterFingerprint.value,
      receipt.policyId,
      receipt.imageDigest.map(_.value).getOrElse("-"),
      outcomeWire(receipt.outcome),
      receipt.stdoutDigest.value,
      receipt.stderrDigest.value,
      receipt.stdoutBytes.toString,
      receipt.stderrBytes.toString,
      receipt.stdoutPreviewDigest.value,
      receipt.stderrPreviewDigest.value,
      receipt.stdoutPreviewBytes.toString,
      receipt.stderrPreviewBytes.toString,
      receipt.durationMillis.toString
    ).mkString("\n")

  private def outcomeWire(outcome: CommandOutcome): String = outcome match
    case CommandOutcome.Exited(code) => s"exit:$code"
    case CommandOutcome.TimedOut     => "timed_out"
    case CommandOutcome.OutputLimit  => "output_limit"
    case CommandOutcome.StartFailed  => "start_failed"

  private def digest(bytes: Array[Byte]): Sha256Digest =
    Sha256Digest.trusted(
      hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    )

  private def mac(payload: String): String =
    val algorithm = Mac.getInstance("HmacSHA256")
    algorithm.init(SecretKeySpec(key, "HmacSHA256"))
    hex(algorithm.doFinal(payload.getBytes(StandardCharsets.UTF_8)))

  private def constantTimeEquals(left: String, right: String): Boolean =
    MessageDigest.isEqual(
      left.getBytes(StandardCharsets.US_ASCII),
      right.getBytes(StandardCharsets.US_ASCII)
    )

  private def hex(bytes: Array[Byte]): String =
    bytes.map(byte => f"${byte & 0xff}%02x").mkString
