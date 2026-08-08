package bat.worker

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.{BasicFileAttributes, PosixFilePermissions}
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardOpenOption
}
import java.security.MessageDigest
import java.util.Base64

import scala.jdk.CollectionConverters.*

import zio.{IO, ZIO}

final case class RunWorkspace private (
    runId: RunId,
    controlDirectory: Path,
    runDirectory: Path,
    repository: Path,
    pins: PullRequestPins,
    initialFingerprint: WorkspaceFingerprint
)

object RunWorkspace:
  private val Manifest = "workspace.manifest"
  private val Version = "bat-workspace-v1"

  final case class Allocation private[worker] (
      runId: RunId,
      controlDirectory: Path,
      runDirectory: Path,
      repository: Path,
      pins: PullRequestPins
  )

  def allocate(
      controlRoot: Path,
      workspaceRoot: Path,
      runId: RunId,
      pins: PullRequestPins
  ): IO[WorkerError, Allocation] =
    ZIO
      .attemptBlocking {
        val controlDirectory = privateRunDirectory(controlRoot, runId)
        val root = requirePrivateRoot(workspaceRoot)
        val runDirectory = root.resolve(runId.value)
        if Files.exists(runDirectory, LinkOption.NOFOLLOW_LINKS) then
          throw WorkspaceFailure("workspace run already exists")
        createPrivateDirectory(runDirectory)
        val repository = runDirectory.resolve("repository")
        Allocation(
          runId,
          controlDirectory,
          runDirectory,
          repository,
          pins
        )
      }
      .mapError(_ =>
        WorkerError.SourceRejected(
          "workspace_allocation_failed",
          "fresh private run workspace could not be allocated"
        )
      )

  def seal(allocation: Allocation): IO[WorkerError, RunWorkspace] =
    for
      _ <- rejectTargetBdr(allocation.repository)
      fingerprint <- WorkspaceFingerprinting.compute(allocation.repository)
      workspace = RunWorkspace(
        allocation.runId,
        allocation.controlDirectory,
        allocation.runDirectory,
        allocation.repository,
        allocation.pins,
        fingerprint
      )
      _ <- writeManifest(workspace)
    yield workspace

  def resume(
      controlRoot: Path,
      workspaceRoot: Path,
      runId: RunId
  ): IO[WorkerError, RunWorkspace] =
    ZIO
      .attemptBlocking {
        val controlDirectory = existingPrivateRunDirectory(controlRoot, runId)
        val root = requirePrivateRoot(workspaceRoot)
        val runDirectory = root.resolve(runId.value)
        requirePrivateDirectory(runDirectory)
        val repository = runDirectory.resolve("repository")
        if Files.isSymbolicLink(repository) ||
          !Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)
        then throw WorkspaceFailure("repository workspace is unsafe")
        parseManifest(
          controlDirectory.resolve(Manifest),
          runId,
          controlDirectory,
          runDirectory,
          repository
        )
      }
      .mapError(_ =>
        WorkerError.SourceRejected(
          "workspace_resume_failed",
          "worker run manifest or workspace failed validation"
        )
      )

  private def rejectTargetBdr(repository: Path): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val entries = Files.list(repository)
        try
          !entries
            .iterator()
            .asScala
            .exists(path => {
              val name = path.getFileName.toString
              name.equalsIgnoreCase(".bdr") ||
              name.equalsIgnoreCase(".git") && name != ".git"
            })
        finally entries.close()
      }
      .mapError(_ =>
        WorkerError.SourceRejected(
          "target_bdr_inspection_failed",
          "target BDR state could not be inspected"
        )
      )
      .flatMap { absent =>
        if absent then ZIO.unit
        else
          ZIO.fail(
            WorkerError.SourceRejected(
              "target_supplied_bdr_state",
              "new runs cannot inherit .bdr state from target code"
            )
          )
      }

  private def writeManifest(workspace: RunWorkspace): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val manifestPath = workspace.controlDirectory.resolve(Manifest)
        val fields = List(
          Version,
          workspace.runId.value,
          workspace.pins.baseRepository.value,
          workspace.pins.headRepository.value,
          workspace.pins.pullRequestId.value,
          workspace.pins.baseRef.value,
          workspace.pins.baseCommit.value,
          workspace.pins.headRef.value,
          workspace.pins.headCommit.value,
          workspace.runDirectory.toString,
          workspace.repository.toString,
          workspace.initialFingerprint.value
        )
        val text = fields.map(encode).mkString("\n") + "\n"
        Files.writeString(
          manifestPath,
          text,
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          StandardOpenOption.SYNC
        )
        restrictFile(manifestPath)
        ()
      }
      .mapError(_ =>
        WorkerError.LedgerFailure(
          "manifest_write_failed",
          "worker run manifest could not be committed"
        )
      )

  private def parseManifest(
      manifestPath: Path,
      runId: RunId,
      controlDirectory: Path,
      runDirectory: Path,
      repository: Path
  ): RunWorkspace =
    if Files.isSymbolicLink(manifestPath) ||
      !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(manifestPath) > 8192L
    then throw WorkspaceFailure("manifest is unsafe")
    val text = Files.readString(manifestPath, StandardCharsets.US_ASCII)
    if !text.endsWith("\n") then
      throw WorkspaceFailure("manifest is incomplete")
    val fields = text.linesIterator.map(decode).toList
    fields match
      case version :: rawRunId :: baseRepository :: headRepository ::
          pullRequestId :: baseRef :: baseCommit :: headRef :: headCommit ::
          recordedRunDirectory :: recordedRepository :: fingerprint :: Nil =>
        val parsed = for
          parsedRunId <- RunId.from(rawRunId)
          pins <- PullRequestPins.make(
            baseRepository,
            headRepository,
            pullRequestId,
            baseRef,
            baseCommit,
            headRef,
            headCommit
          )
          parsedFingerprint <- WorkspaceFingerprint.from(fingerprint)
        yield (parsedRunId, pins, parsedFingerprint)
        val (parsedRunId, pins, parsedFingerprint) = parsed.fold(
          _ => throw WorkspaceFailure("manifest fields are invalid"),
          identity
        )
        if version != Version || parsedRunId != runId ||
          Path.of(recordedRunDirectory) != runDirectory ||
          Path.of(recordedRepository) != repository
        then throw WorkspaceFailure("manifest identity changed")
        RunWorkspace(
          runId,
          controlDirectory,
          runDirectory,
          repository,
          pins,
          parsedFingerprint
        )
      case _ => throw WorkspaceFailure("manifest shape is invalid")

  private def privateRunDirectory(controlRoot: Path, runId: RunId): Path =
    val root = requirePrivateRoot(controlRoot)
    val directory = root.resolve(runId.value)
    if Files.exists(directory, LinkOption.NOFOLLOW_LINKS) then
      requirePrivateDirectory(directory)
    else createPrivateDirectory(directory)
    directory

  private def existingPrivateRunDirectory(
      controlRoot: Path,
      runId: RunId
  ): Path =
    val root = requirePrivateRoot(controlRoot)
    val directory = root.resolve(runId.value)
    requirePrivateDirectory(directory)
    directory

  private def requirePrivateRoot(root: Path): Path =
    if root == null then throw WorkspaceFailure("root is required")
    val normalized = root.toAbsolutePath.normalize
    if Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
      if Files.isSymbolicLink(normalized) ||
        !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
      then throw WorkspaceFailure("root is unsafe")
    else
      Files.createDirectories(normalized)
      restrictDirectory(normalized)
    normalized

  private def requirePrivateDirectory(path: Path): Unit =
    if Files.isSymbolicLink(path) ||
      !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
      !hasPrivatePermissions(path)
    then throw WorkspaceFailure("directory is not private")

  private def createPrivateDirectory(path: Path): Unit =
    try
      val _ = Files.createDirectory(
        path,
        PosixFilePermissions.asFileAttribute(
          PosixFilePermissions.fromString("rwx------")
        )
      )
    catch
      case _: UnsupportedOperationException =>
        val _ = Files.createDirectory(path)
        restrictDirectory(path)

  private def hasPrivatePermissions(path: Path): Boolean =
    try
      val permissions = Files.getPosixFilePermissions(path)
      permissions.asScala.forall(permission =>
        !permission.name().startsWith("GROUP_") &&
          !permission.name().startsWith("OTHERS_")
      )
    catch case _: UnsupportedOperationException => true

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

  private def encode(value: String): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      value.getBytes(StandardCharsets.UTF_8)
    )

  private def decode(value: String): String =
    String(Base64.getUrlDecoder.decode(value), StandardCharsets.UTF_8)

  private final case class WorkspaceFailure(message: String)
      extends RuntimeException(message)

object WorkspaceFingerprinting:
  private val MaxFiles = 100000
  private val MaxBytes = 1024L * 1024 * 1024

  def compute(repository: Path): IO[WorkerError, WorkspaceFingerprint] =
    ZIO
      .attemptBlocking {
        val root = repository.toAbsolutePath.normalize
        if Files.isSymbolicLink(root) ||
          !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        then throw FingerprintFailure("workspace root is unsafe")
        val head = readDetachedHead(root)
        val index = readIndex(root)
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, "bat-workspace-fingerprint-v1")
        update(digest, head)
        updateBytes(digest, index)
        val paths = fingerprintPaths(root)
        var total = 0L
        paths.foreach { path =>
          val relative = root.relativize(path).toString
          if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then
            update(digest, s"d:$relative")
          else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
            val size = Files.size(path)
            total = Math.addExact(total, size)
            if total > MaxBytes then
              throw FingerprintFailure("workspace exceeds byte limit")
            update(digest, s"f:$relative:${Files.isExecutable(path)}:$size")
            val input = Files.newInputStream(path)
            try
              val buffer = Array.ofDim[Byte](8192)
              var read = input.read(buffer)
              while read != -1 do
                if read > 0 then digest.update(buffer, 0, read)
                read = input.read(buffer)
            finally input.close()
          else throw FingerprintFailure("workspace has an unsupported path")
        }
        WorkspaceFingerprint.trusted(hex(digest.digest()))
      }
      .mapError(_ =>
        WorkerError.SourceRejected(
          "workspace_fingerprint_failed",
          "authoring workspace could not be fingerprinted safely"
        )
      )

  private def readDetachedHead(root: Path): String =
    val path = root.resolve(".git").resolve("HEAD")
    if Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(path) > 128L
    then throw FingerprintFailure("workspace HEAD is unsafe")
    val value = Files.readString(path, StandardCharsets.US_ASCII).trim
    GitObjectId
      .from(value, "workspace_head")
      .fold(
        _ => throw FingerprintFailure("workspace HEAD is not detached"),
        _.value
      )

  private def readIndex(root: Path): Array[Byte] =
    val path = root.resolve(".git").resolve("index")
    if Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
      Files.size(path) > 256L * 1024 * 1024
    then throw FingerprintFailure("workspace index is unsafe")
    Files.readAllBytes(path)

  private def isInternal(root: Path, path: Path): Boolean =
    val relative = root.relativize(path)
    val first = relative.getName(0).toString
    first.equalsIgnoreCase(".git") || first.equalsIgnoreCase(".bdr")

  private def fingerprintPaths(root: Path): List[Path] =
    val paths = scala.collection.mutable.ListBuffer.empty[Path]
    var visited = 0
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if directory != root && isInternal(root, directory) then
            FileVisitResult.SKIP_SUBTREE
          else
            if directory != root then add(directory)
            FileVisitResult.CONTINUE

        override def visitFile(
            file: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if attributes.isSymbolicLink then
            throw FingerprintFailure("workspace contains a symlink")
          add(file)
          FileVisitResult.CONTINUE

        private def add(path: Path): Unit =
          visited += 1
          if visited > MaxFiles then
            throw FingerprintFailure("workspace has too many paths")
          paths += path
    )
    paths.toList.sortBy(path => root.relativize(path).toString)

  private def update(digest: MessageDigest, value: String): Unit =
    updateBytes(digest, value.getBytes(StandardCharsets.UTF_8))

  private def updateBytes(digest: MessageDigest, bytes: Array[Byte]): Unit =
    digest.update(ByteBuffer.allocate(8).putLong(bytes.length.toLong).array())
    digest.update(bytes)

  private def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(byte => f"${byte & 0xff}%02x").mkString

  private final case class FingerprintFailure(message: String)
      extends RuntimeException(message)

object PinnedWorkspace:
  def verify(
      repository: Path,
      pins: PullRequestPins,
      runner: GitRunner
  ): IO[WorkerError, Unit] =
    for
      _ <- GitConfigurationGuard.verifyWorkspace(repository, runner)
      detached <- runner.run(
        GitInvocation(repository, zio.Chunk("symbolic-ref", "-q", "HEAD"))
      )
      _ <- rejectIf(
        detached.exitCode != 1,
        "symbolic_workspace_head",
        "worker workspace HEAD must be detached"
      )
      head <- successful(
        runner,
        repository,
        zio.Chunk("rev-parse", "--verify", "HEAD")
      )
      _ <- rejectIf(
        head.output.trim != pins.headCommit.value,
        "workspace_head_mismatch",
        "worker workspace is not pinned at the PR head"
      )
      fetchedBase <- successful(
        runner,
        repository,
        zio.Chunk("show-ref", "--verify", "--hash", "refs/bat/base")
      )
      _ <- rejectIf(
        fetchedBase.output.trim != pins.baseCommit.value,
        "workspace_base_ref_mismatch",
        "worker workspace did not fetch the pinned PR base"
      )
      fetchedHead <- successful(
        runner,
        repository,
        zio.Chunk("show-ref", "--verify", "--hash", "refs/bat/head")
      )
      _ <- rejectIf(
        fetchedHead.output.trim != pins.headCommit.value,
        "workspace_head_ref_mismatch",
        "worker workspace did not fetch the pinned PR head"
      )
      status <- successful(
        runner,
        repository,
        zio.Chunk(
          "status",
          "--porcelain=v2",
          "--untracked-files=all",
          "--ignore-submodules=all"
        )
      )
      _ <- rejectIf(
        status.output.nonEmpty,
        "dirty_worker_workspace",
        "new worker workspace is not clean"
      )
      unmerged <- successful(
        runner,
        repository,
        zio.Chunk("ls-files", "--unmerged")
      )
      _ <- rejectIf(
        unmerged.output.nonEmpty,
        "unmerged_worker_workspace",
        "worker workspace index contains unmerged entries"
      )
      flags <- successful(
        runner,
        repository,
        zio.Chunk("ls-files", "-v")
      )
      _ <- rejectIf(
        flags.output.linesIterator.exists(line => !line.startsWith("H ")),
        "hidden_index_state",
        "worker workspace index contains hidden state"
      )
      stages <- successful(
        runner,
        repository,
        zio.Chunk("ls-files", "--stage")
      )
      _ <- rejectIf(
        stages.output.linesIterator.exists(_.startsWith("160000 ")),
        "submodule_workspace",
        "worker workspace cannot contain Git links"
      )
      indexTree <- successful(
        runner,
        repository,
        zio.Chunk("write-tree")
      )
      headTree <- successful(
        runner,
        repository,
        zio.Chunk("rev-parse", "--verify", "HEAD^{tree}")
      )
      _ <- rejectIf(
        indexTree.output.trim != headTree.output.trim,
        "index_tree_mismatch",
        "worker workspace index does not match its head tree"
      )
      _ <- rejectAlternates(repository)
    yield ()

  private def successful(
      runner: GitRunner,
      repository: Path,
      arguments: zio.Chunk[String]
  ): IO[WorkerError, GitResult] =
    runner.run(GitInvocation(repository, arguments)).flatMap { result =>
      if result.exitCode == 0 then ZIO.succeed(result)
      else
        ZIO.fail(
          WorkerError.SourceRejected(
            "workspace_git_failed",
            "worker workspace Git invariant could not be verified"
          )
        )
    }

  private def rejectAlternates(repository: Path): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        val alternates = repository
          .resolve(".git")
          .resolve("objects")
          .resolve("info")
          .resolve("alternates")
        !Files.exists(alternates, LinkOption.NOFOLLOW_LINKS)
      }
      .mapError(_ =>
        WorkerError.SourceRejected(
          "alternates_inspection_failed",
          "worker object store could not be inspected"
        )
      )
      .flatMap { safe =>
        rejectIf(
          !safe,
          "alternate_object_store",
          "worker workspace cannot borrow an external object store"
        )
      }

  private def rejectIf(
      condition: Boolean,
      code: String,
      message: String
  ): IO[WorkerError, Unit] =
    if condition then ZIO.fail(WorkerError.SourceRejected(code, message))
    else ZIO.unit
