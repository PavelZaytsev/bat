package bat.worker

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest

import zio.{Chunk, IO, ZIO}

final case class VerifiedWorkerResult private (
    localHead: GitObjectId,
    patchPath: Path,
    patchDigest: Sha256Digest,
    patchBytes: Long
)

final case class BdrHandoff private[worker] (
    revision: Long,
    runState: String,
    stateDigest: String
)

object VerifiedWorkerResult:
  private val PatchFile = "final.patch"
  private val ManifestFile = "handoff.manifest"
  private val MaxPatchBytes = 4L * 1024 * 1024

  def create(
      workspace: RunWorkspace,
      runner: GitRunner,
      expectedWorkspace: WorkspacePrecondition,
      bdr: BdrHandoff,
      beforePersist: IO[WorkerError, Unit]
  ): IO[WorkerError, VerifiedWorkerResult] =
    for
      actual <- WorkspaceFingerprinting.compute(workspace.repository)
      _ <-
        if actual == expectedWorkspace.fingerprint then ZIO.unit
        else
          ZIO.fail(
            WorkerError.LedgerFailure(
              "handoff_workspace_mismatch",
              "handoff workspace does not match its operation ledger"
            )
          )
      status <- successful(
        runner,
        workspace.repository,
        Chunk(
          "status",
          "--porcelain=v2",
          "--untracked-files=all",
          "--ignore-submodules=all",
          "--",
          ".",
          ":(exclude).bdr",
          ":(exclude).bdr/**"
        )
      )
      _ <- rejectDirtyCode(status.output)
      headResult <- successful(
        runner,
        workspace.repository,
        Chunk("rev-parse", "--verify", "HEAD")
      )
      localHead <- ZIO.fromEither(
        GitObjectId.from(headResult.output.trim, "handoff_head")
      )
      ancestry <- runner.run(
        GitInvocation(
          workspace.repository,
          Chunk(
            "merge-base",
            "--is-ancestor",
            workspace.pins.headCommit.value,
            localHead.value
          )
        )
      )
      _ <-
        if ancestry.exitCode == 0 then ZIO.unit
        else
          ZIO.fail(
            WorkerError.SourceRejected(
              "handoff_history_rewritten",
              "local result does not descend from the pinned PR head"
            )
          )
      diff <- successful(
        runner,
        workspace.repository,
        Chunk(
          "diff",
          "--no-ext-diff",
          "--no-textconv",
          "--binary",
          workspace.pins.headCommit.value,
          localHead.value
        )
      )
      _ <- beforePersist
      result <- persist(
        workspace,
        localHead,
        diff.output,
        expectedWorkspace,
        bdr
      )
    yield result

  private def rejectDirtyCode(status: String): IO[WorkerError, Unit] =
    if status.isEmpty then ZIO.unit
    else
      ZIO.fail(
        WorkerError.SourceRejected(
          "dirty_handoff_workspace",
          "handoff requires all code changes to be committed locally"
        )
      )

  private def persist(
      workspace: RunWorkspace,
      localHead: GitObjectId,
      patch: String,
      expectedWorkspace: WorkspacePrecondition,
      bdr: BdrHandoff
  ): IO[WorkerError, VerifiedWorkerResult] =
    val bytes = patch.getBytes(StandardCharsets.UTF_8)
    if bytes.length.toLong > MaxPatchBytes then
      ZIO.fail(
        WorkerError.SourceRejected(
          "handoff_patch_too_large",
          "verified handoff patch exceeds the 4 MiB limit"
        )
      )
    else
      ZIO
        .attemptBlocking {
          val digest = sha256(bytes)
          val patchPath = workspace.controlDirectory.resolve(PatchFile)
          val manifestPath = workspace.controlDirectory.resolve(ManifestFile)
          rejectSymlink(patchPath)
          rejectSymlink(manifestPath)
          Files.write(
            patchPath,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
          )
          restrictFile(patchPath)
          val manifest = List(
            "bat-handoff-v1",
            workspace.runId.value,
            workspace.pins.baseRepository.value,
            workspace.pins.headRepository.value,
            workspace.pins.pullRequestId.value,
            workspace.pins.baseRef.value,
            workspace.pins.baseCommit.value,
            workspace.pins.headRef.value,
            workspace.pins.headCommit.value,
            localHead.value,
            expectedWorkspace.revision.value.toString,
            expectedWorkspace.fingerprint.value,
            bdr.revision.toString,
            bdr.runState,
            bdr.stateDigest,
            digest,
            bytes.length.toString
          ).mkString("\n") + "\n"
          Files.writeString(
            manifestPath,
            manifest,
            StandardCharsets.US_ASCII,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
          )
          restrictFile(manifestPath)
          VerifiedWorkerResult(
            localHead,
            patchPath,
            Sha256Digest.trusted(digest),
            bytes.length.toLong
          )
        }
        .mapError(_ =>
          WorkerError.LedgerFailure(
            "handoff_artifact_failed",
            "verified local handoff artifact could not be persisted"
          )
        )

  private def successful(
      runner: GitRunner,
      repository: Path,
      arguments: Chunk[String]
  ): IO[WorkerError, GitResult] =
    runner.run(GitInvocation(repository, arguments)).flatMap { result =>
      if result.exitCode == 0 then ZIO.succeed(result)
      else
        ZIO.fail(
          WorkerError.SourceRejected(
            "handoff_git_failed",
            "Git could not verify the local handoff result"
          )
        )
    }

  private def rejectSymlink(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
      Files.isSymbolicLink(path)
    then throw new IllegalStateException("handoff path is a symlink")

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
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
