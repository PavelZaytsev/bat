package bat.worker

import java.nio.file.Path

import scala.jdk.CollectionConverters.*

import zio.{Chunk, Duration}

sealed trait WorkerError extends Serializable:
  def code: String
  def safeMessage: String

object WorkerError:
  final case class InvalidInput(code: String, safeMessage: String)
      extends WorkerError

  final case class SourceRejected(code: String, safeMessage: String)
      extends WorkerError

  final case class IsolationFailure(code: String, safeMessage: String)
      extends WorkerError

  final case class LedgerFailure(code: String, safeMessage: String)
      extends WorkerError

  final case class ToolFailure(code: String, safeMessage: String)
      extends WorkerError

private object WorkerValidation:
  private val identifier = "^[a-z0-9][a-z0-9_-]{0,63}$".r
  private val providerIdentifier = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$".r
  private val gitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val digest = "^[0-9a-f]{64}$".r
  private val forbiddenRefCharacters =
    Set(' ', '~', '^', ':', '?', '*', '[', '\\')

  def id(value: String, label: String): Either[WorkerError, String] =
    Option(value)
      .filter(identifier.matches)
      .toRight(
        WorkerError.InvalidInput(
          s"invalid_$label",
          s"$label must be a bounded lowercase identifier"
        )
      )

  def providerId(value: String, label: String): Either[WorkerError, String] =
    Option(value)
      .filter(providerIdentifier.matches)
      .toRight(
        WorkerError.InvalidInput(
          s"invalid_$label",
          s"$label must be a bounded provider identifier"
        )
      )

  def objectId(value: String, label: String): Either[WorkerError, String] =
    Option(value)
      .filter(gitObject.matches)
      .toRight(
        WorkerError.InvalidInput(
          s"invalid_$label",
          s"$label must be a full lowercase Git object ID"
        )
      )

  def sha256(value: String, label: String): Either[WorkerError, String] =
    Option(value)
      .filter(digest.matches)
      .toRight(
        WorkerError.InvalidInput(
          s"invalid_$label",
          s"$label must be a lowercase SHA-256 digest"
        )
      )

  def requestIdentity(value: String): Either[WorkerError, String] =
    Option(value)
      .filter(candidate =>
        candidate.nonEmpty &&
          candidate.length <= 1024 &&
          candidate.forall(character =>
            character >= '!' && character <= '~' && character != '|'
          )
      )
      .toRight(
        WorkerError.InvalidInput(
          "invalid_request_identity",
          "request identity must be 1-1024 printable ASCII characters without separators"
        )
      )

  def ref(value: String, label: String): Either[WorkerError, String] =
    Option(value)
      .filter { candidate =>
        candidate.startsWith("refs/") &&
        candidate.length <= 255 &&
        !candidate.endsWith("/") &&
        !candidate.endsWith(".") &&
        !candidate.contains("..") &&
        !candidate.contains("@{") &&
        !candidate.contains("//") &&
        !candidate
          .split('/')
          .exists(part =>
            part.isEmpty || part.startsWith(".") || part.endsWith(".lock")
          ) &&
        !candidate.exists(character =>
          character.isControl || forbiddenRefCharacters.contains(character)
        )
      }
      .toRight(
        WorkerError.InvalidInput(
          s"invalid_$label",
          s"$label must be an exact, canonical refs/... Git ref"
        )
      )

opaque type RunId = String

object RunId:
  def from(value: String): Either[WorkerError, RunId] =
    WorkerValidation.id(value, "run_id")

  extension (self: RunId) def value: String = self

opaque type RepositoryId = String

object RepositoryId:
  def from(value: String, label: String): Either[WorkerError, RepositoryId] =
    WorkerValidation.providerId(value, label)

  extension (self: RepositoryId) def value: String = self

opaque type PullRequestId = String

object PullRequestId:
  def from(value: String): Either[WorkerError, PullRequestId] =
    WorkerValidation.providerId(value, "pull_request_id")

  extension (self: PullRequestId) def value: String = self

opaque type OperationId = String

object OperationId:
  def from(value: String): Either[WorkerError, OperationId] =
    WorkerValidation.id(value, "operation_id")

  extension (self: OperationId) def value: String = self

  def derive(runId: RunId, callId: String, toolName: String): OperationId =
    val payload =
      s"bat-worker-operation-v1\n${runId.value}\n$callId\n$toolName"
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
    digest

opaque type GitObjectId = String

object GitObjectId:
  def from(value: String, label: String): Either[WorkerError, GitObjectId] =
    WorkerValidation.objectId(value, label)

  extension (self: GitObjectId) def value: String = self

opaque type GitRef = String

object GitRef:
  def from(value: String, label: String): Either[WorkerError, GitRef] =
    WorkerValidation.ref(value, label)

  extension (self: GitRef) def value: String = self

opaque type Sha256Digest = String

object Sha256Digest:
  def from(value: String, label: String): Either[WorkerError, Sha256Digest] =
    WorkerValidation.sha256(value, label)

  private[worker] def trusted(value: String): Sha256Digest = value

  extension (self: Sha256Digest) def value: String = self

final case class PullRequestPins private (
    baseRepository: RepositoryId,
    headRepository: RepositoryId,
    pullRequestId: PullRequestId,
    baseRef: GitRef,
    baseCommit: GitObjectId,
    headRef: GitRef,
    headCommit: GitObjectId
)

object PullRequestPins:
  def make(
      baseRepository: String,
      headRepository: String,
      pullRequestId: String,
      baseRef: String,
      baseCommit: String,
      headRef: String,
      headCommit: String
  ): Either[WorkerError, PullRequestPins] =
    for
      validBaseRepository <- RepositoryId.from(
        baseRepository,
        "base_repository_id"
      )
      validHeadRepository <- RepositoryId.from(
        headRepository,
        "head_repository_id"
      )
      validPullRequestId <- PullRequestId.from(pullRequestId)
      validBaseRef <- GitRef.from(baseRef, "base_ref")
      validBaseCommit <- GitObjectId.from(baseCommit, "base_commit")
      validHeadRef <- GitRef.from(headRef, "head_ref")
      validHeadCommit <- GitObjectId.from(headCommit, "head_commit")
    yield PullRequestPins(
      validBaseRepository,
      validHeadRepository,
      validPullRequestId,
      validBaseRef,
      validBaseCommit,
      validHeadRef,
      validHeadCommit
    )

enum WorkerOperationKind(val wire: String, val mutating: Boolean):
  case Read extends WorkerOperationKind("read", false)
  case Search extends WorkerOperationKind("search", false)
  case Patch extends WorkerOperationKind("patch", true)
  case GitStatus extends WorkerOperationKind("git_status", false)
  case GitDiff extends WorkerOperationKind("git_diff", false)
  case GitCommit extends WorkerOperationKind("git_commit", true)
  case MavenTest extends WorkerOperationKind("maven_test", false)
  case MavenVerify extends WorkerOperationKind("maven_verify", false)
  case GradleTest extends WorkerOperationKind("gradle_test", false)
  case GradleCheck extends WorkerOperationKind("gradle_check", false)
  case Checkout extends WorkerOperationKind("checkout", true)

object WorkerOperationKind:
  def fromWire(value: String): Either[WorkerError, WorkerOperationKind] =
    WorkerOperationKind.values
      .find(_.wire == value)
      .toRight(
        WorkerError.LedgerFailure(
          "invalid_ledger_operation",
          "ledger contains an unknown operation kind"
        )
      )

opaque type WorkspaceRevision = Long

object WorkspaceRevision:
  def from(value: Long): Either[WorkerError, WorkspaceRevision] =
    Either.cond(
      value >= 0,
      value,
      WorkerError.InvalidInput(
        "invalid_workspace_revision",
        "workspace revision must be non-negative"
      )
    )

  extension (self: WorkspaceRevision)
    def value: Long = self
    private[worker] def next: Either[WorkerError, WorkspaceRevision] =
      if self == Long.MaxValue then
        Left(
          WorkerError.LedgerFailure(
            "workspace_revision_exhausted",
            "workspace revision cannot advance"
          )
        )
      else Right(self + 1L)

opaque type WorkspaceFingerprint = String

object WorkspaceFingerprint:
  def from(value: String): Either[WorkerError, WorkspaceFingerprint] =
    WorkerValidation.sha256(value, "workspace_fingerprint")

  private[worker] def trusted(value: String): WorkspaceFingerprint = value

  extension (self: WorkspaceFingerprint) def value: String = self

final case class WorkspacePrecondition(
    revision: WorkspaceRevision,
    fingerprint: WorkspaceFingerprint
)

final case class WorkerOperation private (
    id: OperationId,
    kind: WorkerOperationKind,
    requestDigest: Sha256Digest,
    requestIdentity: String,
    expectedWorkspace: WorkspacePrecondition,
    policyId: String,
    imageDigest: Option[Sha256Digest]
)

object WorkerOperation:
  def make(
      id: OperationId,
      kind: WorkerOperationKind,
      requestDigest: String,
      requestIdentity: String,
      expectedWorkspace: WorkspacePrecondition,
      policyId: String,
      imageDigest: Option[String] = None
  ): Either[WorkerError, WorkerOperation] =
    for
      digest <- Sha256Digest.from(requestDigest, "request_digest")
      identity <- WorkerValidation.requestIdentity(requestIdentity)
      policy <- WorkerValidation.id(policyId, "policy_id")
      image <- imageDigest match
        case Some(value) =>
          Sha256Digest.from(value, "image_digest").map(Some(_))
        case None => Right(None)
    yield WorkerOperation(
      id,
      kind,
      digest,
      identity,
      expectedWorkspace,
      policy,
      image
    )

enum CommandOutcome:
  case Exited(exitCode: Int)
  case TimedOut
  case OutputLimit
  case StartFailed

final case class CommandObservation private[worker] (
    outcome: CommandOutcome,
    stdoutPreview: Chunk[Byte],
    stderrPreview: Chunk[Byte],
    stdoutDigest: Sha256Digest,
    stderrDigest: Sha256Digest,
    stdoutBytes: Long,
    stderrBytes: Long,
    durationMillis: Long
)

object CommandObservation:
  def make(
      outcome: CommandOutcome,
      stdoutPreview: Chunk[Byte],
      stderrPreview: Chunk[Byte],
      stdoutDigest: String,
      stderrDigest: String,
      stdoutBytes: Long,
      stderrBytes: Long,
      durationMillis: Long
  ): Either[WorkerError, CommandObservation] =
    if outcome == null then
      Left(
        WorkerError.InvalidInput(
          "invalid_command_outcome",
          "process outcome must be explicit"
        )
      )
    else if outcome match
        case CommandOutcome.Exited(code) => code < 0 || code > 255
        case _                           => false
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_exit_code",
          "process exit code must be between 0 and 255"
        )
      )
    else if stdoutBytes < stdoutPreview.length ||
      stderrBytes < stderrPreview.length || durationMillis < 0
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_command_measurement",
          "process byte counts and duration must be non-negative and consistent"
        )
      )
    else
      for
        validStdoutDigest <- Sha256Digest.from(
          stdoutDigest,
          "stdout_digest"
        )
        validStderrDigest <- Sha256Digest.from(
          stderrDigest,
          "stderr_digest"
        )
      yield CommandObservation(
        outcome,
        stdoutPreview,
        stderrPreview,
        validStdoutDigest,
        validStderrDigest,
        stdoutBytes,
        stderrBytes,
        durationMillis
      )

final case class CompletedOperation(
    observation: CommandObservation,
    afterFingerprint: WorkspaceFingerprint
)

final case class WorkerLimits private (
    timeout: Duration,
    maxOutputBytes: Int,
    maxProcesses: Int,
    memoryBytes: Long,
    cpuCount: Double
)

object WorkerLimits:
  def make(
      timeout: Duration,
      maxOutputBytes: Int,
      maxProcesses: Int,
      memoryBytes: Long,
      cpuCount: Double
  ): Either[WorkerError, WorkerLimits] =
    if timeout == null || timeout == Duration.Infinity || timeout.isZero ||
      timeout.isNegative
    then invalidLimits("timeout must be finite and positive")
    else if maxOutputBytes <= 0 || maxOutputBytes > 16 * 1024 * 1024 then
      invalidLimits("output limit must be between 1 byte and 16 MiB")
    else if maxProcesses <= 0 || maxProcesses > 4096 then
      invalidLimits("process limit must be between 1 and 4096")
    else if memoryBytes < 64L * 1024 * 1024 then
      invalidLimits("memory limit must be at least 64 MiB")
    else if !cpuCount.isFinite || cpuCount <= 0.0 || cpuCount > 64.0 then
      invalidLimits("CPU limit must be finite and between 0 and 64")
    else
      try
        val _ = timeout.toMillis
        Right(
          WorkerLimits(
            timeout,
            maxOutputBytes,
            maxProcesses,
            memoryBytes,
            cpuCount
          )
        )
      catch case _: ArithmeticException => invalidLimits("timeout is too large")

  private def invalidLimits(message: String): Left[WorkerError, Nothing] =
    Left(WorkerError.InvalidInput("invalid_worker_limits", message))

final case class RepositoryPath private (value: Path)

object RepositoryPath:
  def from(value: Path): Either[WorkerError, RepositoryPath] =
    if value == null || value.toString.isEmpty || value.isAbsolute ||
      value.getNameCount == 0 ||
      value
        .iterator()
        .asScala
        .exists(part => part.toString == "." || part.toString == "..")
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_repository_path",
          "repository path must be relative and cannot traverse"
        )
      )
    else Right(RepositoryPath(value.normalize))
