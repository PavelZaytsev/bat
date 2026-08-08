package bat.worker.oci

import java.nio.file.Path

import zio.{Chunk, Duration}

sealed trait OciFailure extends Serializable:
  def code: String
  def safeMessage: String

object OciFailure:
  final case class InvalidConfiguration(code: String, safeMessage: String)
      extends OciFailure

  final case class ProcessFailure(code: String, safeMessage: String)
      extends OciFailure

opaque type PinnedImage = String

object PinnedImage:
  private val Digest = "^[0-9a-f]{64}$".r

  def from(value: String): Either[OciFailure, PinnedImage] =
    val text = Option(value).map(_.trim).getOrElse("")
    val marker = "@sha256:"
    val markerIndex = text.lastIndexOf(marker)
    val name = if markerIndex < 0 then "" else text.take(markerIndex)
    val digest =
      if markerIndex < 0 then "" else text.drop(markerIndex + marker.length)
    val hasUnsafeCharacters =
      text.exists(character => character.isWhitespace || character.isControl)
    if name.isEmpty || name.startsWith("-") || name.contains("@") ||
      !Digest.matches(digest) || hasUnsafeCharacters
    then
      Left(
        OciFailure.InvalidConfiguration(
          "invalid_image",
          "OCI image must be an explicit reference pinned by a lowercase SHA-256 digest"
        )
      )
    else Right(text)

  extension (self: PinnedImage)
    def value: String = self
    def digest: String = self.drop(self.lastIndexOf("@sha256:") + 8)

opaque type ContainerPath = String

object ContainerPath:
  private val ReservedRoots =
    Set("/dev", "/proc", "/sys", "/tmp", "/home/bat")

  def from(value: String): Either[OciFailure, ContainerPath] =
    val text = Option(value).getOrElse("")
    val segments = text.split("/", -1).toList
    val normalized = segments.filter(_.nonEmpty).mkString("/", "/", "")
    val hasUnsafeCharacters =
      text.exists(character =>
        character == '\u0000' || character == '\n' || character == '\r' || character == ','
      )
    val hasTraversal =
      segments.exists(segment => segment == "." || segment == "..")
    val reserved = ReservedRoots.exists(root =>
      normalized == root || normalized.startsWith(s"$root/")
    )
    if !text.startsWith("/") || text != normalized || normalized == "/" ||
      hasUnsafeCharacters || hasTraversal || reserved
    then
      Left(
        OciFailure.InvalidConfiguration(
          "invalid_container_path",
          "container paths must be absolute, normalized, and outside protected runtime paths"
        )
      )
    else Right(normalized)

  extension (self: ContainerPath)
    def value: String = self

    private[oci] def contains(candidate: ContainerPath): Boolean =
      candidate == self || candidate.startsWith(s"$self/")

enum MountAccess:
  case ReadOnly
  case ReadWrite

final case class BindMount private (
    source: Path,
    destination: ContainerPath,
    access: MountAccess
)

object BindMount:
  def make(
      source: Path,
      destination: ContainerPath,
      access: MountAccess
  ): Either[OciFailure, BindMount] =
    val sourceText = Option(source).map(_.toString).getOrElse("")
    val hasUnsafeCharacters =
      sourceText.exists(character =>
        character == '\u0000' || character == '\n' || character == '\r' || character == ','
      )
    if source == null || destination.asInstanceOf[AnyRef] == null ||
      !source.isAbsolute ||
      hasUnsafeCharacters
    then
      Left(
        OciFailure.InvalidConfiguration(
          "invalid_bind_mount",
          "bind-mount sources must be safe absolute host paths"
        )
      )
    else if access == null then
      Left(
        OciFailure.InvalidConfiguration(
          "invalid_bind_mount",
          "bind-mount access must be explicit"
        )
      )
    else Right(BindMount(source.normalize, destination, access))

final case class OciLimits private (
    timeout: Duration,
    stdoutPreviewBytes: Int,
    stderrPreviewBytes: Int,
    outputLimitBytes: Long,
    pids: Int,
    memoryBytes: Long,
    cpus: BigDecimal,
    tmpBytes: Long,
    homeBytes: Long,
    workBytes: Long
)

object OciLimits:
  def make(
      timeout: Duration,
      stdoutPreviewBytes: Int,
      stderrPreviewBytes: Int,
      outputLimitBytes: Long,
      pids: Int,
      memoryBytes: Long,
      cpus: BigDecimal,
      tmpBytes: Long,
      homeBytes: Long,
      workBytes: Long = 4L * 1024 * 1024 * 1024
  ): Either[OciFailure, OciLimits] =
    val safeDuration =
      timeout != null && timeout != Duration.Infinity && timeout.isPositive &&
        hasSafeClockConversions(timeout)
    if !safeDuration then
      invalid("invalid_timeout", "OCI timeout must be finite and positive")
    else if stdoutPreviewBytes <= 0 || stderrPreviewBytes <= 0 then
      invalid(
        "invalid_preview_limit",
        "OCI output preview limits must be positive"
      )
    else if outputLimitBytes <= 0 then
      invalid("invalid_output_limit", "OCI total output limit must be positive")
    else if stdoutPreviewBytes.toLong > outputLimitBytes ||
      stderrPreviewBytes.toLong > outputLimitBytes
    then
      invalid(
        "invalid_preview_limit",
        "OCI previews cannot exceed the total output limit"
      )
    else if pids <= 0 then
      invalid("invalid_pid_limit", "OCI PID limit must be positive")
    else if memoryBytes <= 0 then
      invalid("invalid_memory_limit", "OCI memory limit must be positive")
    else if cpus == null || cpus <= BigDecimal(0) || cpus > BigDecimal(1024)
    then
      invalid("invalid_cpu_limit", "OCI CPU limit must be between 0 and 1024")
    else if tmpBytes <= 0 || homeBytes <= 0 || workBytes <= 0 then
      invalid("invalid_tmpfs_limit", "OCI tmpfs limits must be positive")
    else
      Right(
        OciLimits(
          timeout,
          stdoutPreviewBytes,
          stderrPreviewBytes,
          outputLimitBytes,
          pids,
          memoryBytes,
          cpus,
          tmpBytes,
          homeBytes,
          workBytes
        )
      )

  private def hasSafeClockConversions(value: Duration): Boolean =
    try
      val nanos = value.toNanos
      val _ = value.toMillis
      nanos <= Long.MaxValue - Duration.fromSeconds(3).toNanos
    catch case _: ArithmeticException => false

  private def invalid(code: String, message: String) =
    Left(OciFailure.InvalidConfiguration(code, message))

final case class OciSandboxConfig private (
    runtimeExecutable: Path,
    image: PinnedImage,
    launcherWorkingDirectory: Path,
    uid: Int,
    gid: Int
)

object OciSandboxConfig:
  def make(
      runtimeExecutable: Path,
      image: PinnedImage,
      launcherWorkingDirectory: Path,
      uid: Int,
      gid: Int
  ): Either[OciFailure, OciSandboxConfig] =
    if runtimeExecutable == null || !runtimeExecutable.isAbsolute then
      invalid(
        "invalid_runtime",
        "OCI runtime executable must be an absolute path"
      )
    else if image.asInstanceOf[AnyRef] == null then
      invalid("invalid_image", "OCI image identity must be explicit")
    else if launcherWorkingDirectory == null || !launcherWorkingDirectory.isAbsolute
    then
      invalid(
        "invalid_launcher_cwd",
        "OCI launcher working directory must be absolute"
      )
    else if uid <= 0 || gid <= 0 then
      invalid(
        "invalid_user",
        "OCI UID and GID must both be non-root positive integers"
      )
    else
      Right(
        OciSandboxConfig(
          runtimeExecutable.normalize,
          image,
          launcherWorkingDirectory.normalize,
          uid,
          gid
        )
      )

  private def invalid(code: String, message: String) =
    Left(OciFailure.InvalidConfiguration(code, message))

final case class OciRunRequest private (
    operationId: String,
    argv: Chunk[String],
    mounts: Chunk[BindMount],
    workingDirectory: ContainerPath,
    limits: OciLimits,
    stagedWorkspace: Boolean
)

object OciRunRequest:
  private val OperationId = "^[a-z0-9][a-z0-9_-]{0,63}$".r

  def make(
      operationId: String,
      argv: Chunk[String],
      mounts: Chunk[BindMount],
      workingDirectory: ContainerPath,
      limits: OciLimits
  ): Either[OciFailure, OciRunRequest] =
    makeValidated(
      operationId,
      argv,
      mounts,
      workingDirectory,
      limits,
      stagedWorkspace = false
    )

  def makeStaged(
      operationId: String,
      argv: Chunk[String],
      mounts: Chunk[BindMount],
      workingDirectory: ContainerPath,
      limits: OciLimits
  ): Either[OciFailure, OciRunRequest] =
    makeValidated(
      operationId,
      argv,
      mounts,
      workingDirectory,
      limits,
      stagedWorkspace = true
    )

  private def makeValidated(
      operationId: String,
      argv: Chunk[String],
      mounts: Chunk[BindMount],
      workingDirectory: ContainerPath,
      limits: OciLimits,
      stagedWorkspace: Boolean
  ): Either[OciFailure, OciRunRequest] =
    if argv == null || mounts == null ||
      workingDirectory.asInstanceOf[AnyRef] == null ||
      limits == null
    then
      invalid(
        "invalid_run_request",
        "OCI argv, mounts, working directory, and limits must be explicit"
      )
    else if operationId == null || !OperationId.matches(operationId) then
      invalid(
        "invalid_operation_id",
        "OCI operation ID must be a safe machine identifier"
      )
    else
      val invalidArgument = argv.exists(argument =>
        argument == null || argument.exists(character => character == '\u0000')
      )
      val invalidMount = mounts.exists(_ == null)
      val duplicateDestinations =
        !invalidMount && mounts.map(_.destination).distinct.size != mounts.size
      val coveredWorkingDirectory =
        !invalidMount && mounts.exists(
          _.destination.contains(workingDirectory)
        )
      val validStaging =
        !stagedWorkspace ||
          (!invalidMount &&
            workingDirectory.value == "/bat/run" &&
            mounts.size == 1 &&
            mounts.head.destination.value == "/bat/source" &&
            mounts.head.access == MountAccess.ReadOnly)
      val executable = argv.headOption.flatMap(argument =>
        Option(argument).flatMap(ContainerPath.from(_).toOption)
      )
      if argv.isEmpty || invalidArgument || executable.isEmpty then
        invalid(
          "invalid_command",
          "OCI command must use a normalized absolute executable and explicit argv without NUL bytes"
        )
      else if mounts.isEmpty || invalidMount then
        invalid("invalid_mounts", "OCI runs require explicit bind mounts")
      else if duplicateDestinations then
        invalid("invalid_mounts", "OCI bind-mount destinations must be unique")
      else if stagedWorkspace && !validStaging then
        invalid(
          "invalid_staged_workspace",
          "staged runs require one read-only /bat/source mount and /bat/run work directory"
        )
      else if !stagedWorkspace && !coveredWorkingDirectory then
        invalid(
          "invalid_workdir",
          "OCI working directory must be inside an explicit bind mount"
        )
      else
        Right(
          OciRunRequest(
            operationId,
            argv,
            mounts,
            workingDirectory,
            limits,
            stagedWorkspace
          )
        )

  private def invalid(code: String, message: String) =
    Left(OciFailure.InvalidConfiguration(code, message))

enum OciRunOutcome:
  case Exited(exitCode: Int)
  case TimedOut
  case OutputLimit(limitBytes: Long, observedBytes: Long)
  case RuntimeFailed(exitCode: Int)

final case class OciStreamReceipt(
    totalBytes: Long,
    sha256: String,
    preview: Chunk[Byte],
    previewTruncated: Boolean
)

final case class OciRunResult(
    operationId: String,
    outcome: OciRunOutcome,
    stdout: OciStreamReceipt,
    stderr: OciStreamReceipt,
    durationMillis: Long
)
