package bat.worker

import bat.bdr.BdrSession
import bat.protocol.BatError
import bat.worker.oci.*

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

import scala.collection.mutable

import zio.{Chunk, Clock, IO, Scope, Semaphore, ZIO}

final case class WorkerStorageLimits private (
    maxSourceBytes: Long,
    maxSourcePaths: Long,
    maxCheckoutBytes: Long,
    maxCheckoutPaths: Long,
    maxTreeMetadataBytes: Int
)

object WorkerStorageLimits:
  def make(
      maxSourceBytes: Long,
      maxSourcePaths: Long,
      maxCheckoutBytes: Long,
      maxCheckoutPaths: Long,
      maxTreeMetadataBytes: Int
  ): Either[WorkerError, WorkerStorageLimits] =
    if maxSourceBytes <= 0L then
      Left(
        WorkerError.InvalidInput(
          "invalid_source_byte_limit",
          "source repository byte limit must be positive"
        )
      )
    else if maxSourcePaths <= 0L then
      Left(
        WorkerError.InvalidInput(
          "invalid_source_path_limit",
          "source repository path limit must be positive"
        )
      )
    else if maxCheckoutBytes <= 0L then
      Left(
        WorkerError.InvalidInput(
          "invalid_checkout_byte_limit",
          "checkout byte limit must be positive"
        )
      )
    else if maxCheckoutPaths <= 0L then
      Left(
        WorkerError.InvalidInput(
          "invalid_checkout_path_limit",
          "checkout path limit must be positive"
        )
      )
    else if maxTreeMetadataBytes <= 0 ||
      maxTreeMetadataBytes > 16 * 1024 * 1024
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_tree_metadata_limit",
          "tree metadata limit must be between 1 byte and 16 MiB"
        )
      )
    else
      Right(
        WorkerStorageLimits(
          maxSourceBytes,
          maxSourcePaths,
          maxCheckoutBytes,
          maxCheckoutPaths,
          maxTreeMetadataBytes
        )
      )

object SourceRepositoryPreflight:
  def verify(
      sourceRepository: Path,
      limits: WorkerStorageLimits
  ): IO[WorkerError, Unit] =
    ZIO
      .attemptBlocking {
        if sourceRepository == null || limits == null then throw UnsafeSource()
        val root = sourceRepository.toAbsolutePath.normalize
        if Files.isSymbolicLink(root) ||
          !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        then throw UnsafeSource()

        var paths = 0L
        var bytes = 0L

        def reservePath(): Unit =
          paths = Math.addExact(paths, 1L)
          if paths > limits.maxSourcePaths then throw PathLimitExceeded()

        Files.walkFileTree(
          root,
          new SimpleFileVisitor[Path]:
            override def preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              if attributes.isSymbolicLink || !attributes.isDirectory then
                throw UnsafeSource()
              if directory != root then reservePath()
              FileVisitResult.CONTINUE

            override def visitFile(
                file: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult =
              reservePath()
              if attributes.isSymbolicLink || !attributes.isRegularFile then
                throw UnsafeSource()
              bytes = Math.addExact(bytes, attributes.size())
              if bytes > limits.maxSourceBytes then throw ByteLimitExceeded()
              FileVisitResult.CONTINUE

            override def visitFileFailed(
                file: Path,
                failure: java.io.IOException
            ): FileVisitResult = throw UnsafeSource()
        )
        ()
      }
      .mapError {
        case _: PathLimitExceeded =>
          WorkerError.SourceRejected(
            "source_path_limit_exceeded",
            "source repository exceeds the configured path limit"
          )
        case _: ByteLimitExceeded =>
          WorkerError.SourceRejected(
            "source_byte_limit_exceeded",
            "source repository exceeds the configured byte limit"
          )
        case _ =>
          WorkerError.SourceRejected(
            "unsafe_source_storage",
            "source repository storage contains an unsafe entry"
          )
      }

  private final case class PathLimitExceeded() extends RuntimeException
  private final case class ByteLimitExceeded() extends RuntimeException
  private final case class UnsafeSource() extends RuntimeException

private object SourceTreePreflight:
  private val GitObject = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$".r
  private val RegularModes = Set("100644", "100755")
  private val Dot = Vector('.'.toByte)
  private val DotDot = Vector('.'.toByte, '.'.toByte)
  private val Protected = Set(
    ".git".getBytes(StandardCharsets.US_ASCII).toVector,
    ".bdr".getBytes(StandardCharsets.US_ASCII).toVector
  )

  def verify(
      result: OciRunResult,
      limits: WorkerStorageLimits
  ): Either[WorkerError, Unit] =
    result.outcome match
      case OciRunOutcome.Exited(0)      => verifySuccessful(result, limits)
      case _: OciRunOutcome.OutputLimit => truncated
      case _                            =>
        Left(
          WorkerError.SourceRejected(
            "source_tree_inspection_failed",
            "pinned source tree inspection did not complete successfully"
          )
        )

  private def verifySuccessful(
      result: OciRunResult,
      limits: WorkerStorageLimits
  ): Either[WorkerError, Unit] =
    val stdout = result.stdout
    if stdout.previewTruncated ||
      stdout.totalBytes != stdout.preview.length.toLong ||
      stdout.totalBytes > limits.maxTreeMetadataBytes.toLong
    then truncated
    else if result.stderr.totalBytes != 0L then malformed
    else if stdout.sha256 != digest(stdout.preview.toArray) then malformed
    else
      try
        val bytes = stdout.preview.toArray
        if bytes.nonEmpty && bytes.last != 0.toByte then
          throw MalformedMetadata()
        var offset = 0
        var expandedBytes = 0L
        var expandedPaths = 0L
        val directories = mutable.HashSet.empty[Vector[Byte]]

        def reservePath(): Unit =
          expandedPaths = Math.addExact(expandedPaths, 1L)
          if expandedPaths > limits.maxCheckoutPaths then
            throw CheckoutPathLimit()

        while offset < bytes.length do
          val end = indexOf(bytes, 0.toByte, offset)
          if end < 0 || end == offset then throw MalformedMetadata()
          val tab = indexOf(bytes, '\t'.toByte, offset, end)
          if tab < 0 || tab == offset || tab + 1 >= end then
            throw MalformedMetadata()
          val prefixBytes = java.util.Arrays.copyOfRange(bytes, offset, tab)
          if prefixBytes.exists(_ < 0) then throw MalformedMetadata()
          val prefix = String(prefixBytes, StandardCharsets.US_ASCII).trim
            .split("\\s+")
            .toList
          val size = prefix match
            case mode :: "blob" :: objectId :: rawSize :: Nil
                if RegularModes.contains(mode) && GitObject.matches(objectId) =>
              try rawSize.toLong
              catch case _: NumberFormatException => throw MalformedMetadata()
            case _ => throw MalformedMetadata()
          if size < 0L then throw MalformedMetadata()
          expandedBytes = Math.addExact(expandedBytes, size)
          if expandedBytes > limits.maxCheckoutBytes then
            throw CheckoutByteLimit()

          val path = java.util.Arrays.copyOfRange(bytes, tab + 1, end).toVector
          val components = splitPath(path)
          if components.isEmpty || components.exists(component =>
              component.isEmpty || component == Dot || component == DotDot
            ) || isProtected(components.head)
          then throw MalformedMetadata()
          reservePath()
          var prefixPath = Vector.empty[Byte]
          components.dropRight(1).foreach { component =>
            prefixPath =
              if prefixPath.isEmpty then component
              else prefixPath ++ Vector('/'.toByte) ++ component
            if directories.add(prefixPath) then reservePath()
          }
          offset = end + 1
        Right(())
      catch
        case _: CheckoutByteLimit =>
          Left(
            WorkerError.SourceRejected(
              "checkout_byte_limit_exceeded",
              "pinned checkout exceeds the configured expanded byte limit"
            )
          )
        case _: CheckoutPathLimit =>
          Left(
            WorkerError.SourceRejected(
              "checkout_path_limit_exceeded",
              "pinned checkout exceeds the configured expanded path limit"
            )
          )
        case _: ArithmeticException => malformed
        case _: MalformedMetadata   => malformed

  private def splitPath(path: Vector[Byte]): Vector[Vector[Byte]] =
    val result = Vector.newBuilder[Vector[Byte]]
    var current = Vector.newBuilder[Byte]
    path.foreach { byte =>
      if byte == '/'.toByte then
        result += current.result()
        current = Vector.newBuilder[Byte]
      else current += byte
    }
    result += current.result()
    result.result()

  private def isProtected(component: Vector[Byte]): Boolean =
    Protected.contains(component.map { byte =>
      if byte >= 'A'.toByte && byte <= 'Z'.toByte then
        (byte + ('a'.toByte - 'A'.toByte)).toByte
      else byte
    })

  private def indexOf(
      bytes: Array[Byte],
      target: Byte,
      start: Int,
      limit: Int = Int.MaxValue
  ): Int =
    val end = math.min(bytes.length, limit)
    var index = start
    while index < end && bytes(index) != target do index += 1
    if index < end then index else -1

  private def truncated: Left[WorkerError, Nothing] =
    Left(
      WorkerError.SourceRejected(
        "source_tree_metadata_truncated",
        "pinned source tree metadata exceeded its explicit output bound"
      )
    )

  private def malformed: Left[WorkerError, Nothing] =
    Left(
      WorkerError.SourceRejected(
        "invalid_source_tree_metadata",
        "pinned source tree metadata was malformed"
      )
    )

  private def digest(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private final case class CheckoutByteLimit() extends RuntimeException
  private final case class CheckoutPathLimit() extends RuntimeException
  private final case class MalformedMetadata() extends RuntimeException

final case class WorkerRuntimeConfig private (
    controlRoot: Path,
    workspaceRoot: Path,
    scratchRoot: Path,
    image: PinnedImage,
    ociLimits: OciLimits,
    javaPolicy: JavaBuildPolicy,
    storageLimits: WorkerStorageLimits
):
  def imageDigest: String = image.digest

object WorkerRuntimeConfig:
  def make(
      controlRoot: Path,
      workspaceRoot: Path,
      scratchRoot: Path,
      image: PinnedImage,
      ociLimits: OciLimits,
      javaPolicy: JavaBuildPolicy,
      storageLimits: WorkerStorageLimits
  ): Either[WorkerError, WorkerRuntimeConfig] =
    val roots = Chunk(controlRoot, workspaceRoot, scratchRoot)
    if image.asInstanceOf[AnyRef] == null || ociLimits == null ||
      javaPolicy == null
    then
      Left(
        WorkerError.InvalidInput(
          "invalid_worker_policy",
          "worker image, OCI limits, and Java policy must be explicit"
        )
      )
    else if storageLimits == null then
      Left(
        WorkerError.InvalidInput(
          "invalid_storage_limits",
          "worker storage limits must be explicit"
        )
      )
    else if roots.exists(path => path == null || !path.isAbsolute) then
      Left(
        WorkerError.InvalidInput(
          "invalid_worker_root",
          "worker control, workspace, and scratch roots must be absolute"
        )
      )
    else
      val normalized = roots.map(_.normalize)
      val overlap = normalized.combinations(2).exists { pair =>
        pair(0).startsWith(pair(1)) || pair(1).startsWith(pair(0))
      }
      if overlap then
        Left(
          WorkerError.InvalidInput(
            "overlapping_worker_roots",
            "worker control, workspace, and scratch roots must be disjoint"
          )
        )
      else
        Right(
          WorkerRuntimeConfig(
            normalized(0),
            normalized(1),
            normalized(2),
            image,
            ociLimits,
            javaPolicy,
            storageLimits
          )
        )

object WorkspaceProvisioner:
  private val Git = "/usr/bin/git"
  private val GitSafety = Chunk(
    Git,
    "-c",
    "core.hooksPath=/dev/null",
    "-c",
    "filter.lfs.smudge=",
    "-c",
    "filter.lfs.required=false"
  )

  def provision(
      sandbox: OciSandbox,
      sourceRepository: Path,
      allocation: RunWorkspace.Allocation,
      limits: OciLimits,
      storageLimits: WorkerStorageLimits
  ): IO[WorkerError, Unit] =
    for
      _ <- SourceRepositoryPreflight.verify(sourceRepository, storageLimits)
      source <- bind(
        sourceRepository.toAbsolutePath.normalize,
        "/bat/source",
        MountAccess.ReadOnly
      )
      sourcePath <- containerPath("/bat/source")
      metadataLimits <- treeInspectionLimits(limits, storageLimits)
      inspection <- request(
        provisionOperationId(allocation.runId, "inspect-tree"),
        GitSafety ++ Chunk(
          "-C",
          "/bat/source",
          "ls-tree",
          "-rlz",
          "--full-tree",
          allocation.pins.headCommit.value
        ),
        Chunk(source),
        sourcePath,
        metadataLimits
      )
      inspectionResult <- sandbox.run(inspection).mapError(isolationFailure)
      _ <- ZIO
        .fail(
          WorkerError.SourceRejected(
            "source_tree_receipt_mismatch",
            "source tree inspection returned a mismatched operation receipt"
          )
        )
        .unless(inspectionResult.operationId == inspection.operationId)
      _ <- ZIO.fromEither(
        SourceTreePreflight.verify(inspectionResult, storageLimits)
      )
      work <- bind(allocation.runDirectory, "/bat/work", MountAccess.ReadWrite)
      workPath <- containerPath("/bat/work")
      mounts = Chunk(source, work)
      init <- request(
        provisionOperationId(allocation.runId, "init"),
        GitSafety ++ Chunk(
          "init",
          "--initial-branch=bat-empty",
          "/bat/work/repository"
        ),
        mounts,
        workPath,
        limits
      )
      initResult <- sandbox.run(init).mapError(isolationFailure)
      _ <- requireSuccess(initResult, "init")
      fetch <- request(
        provisionOperationId(allocation.runId, "fetch"),
        GitSafety ++ Chunk(
          "-C",
          "/bat/work/repository",
          "fetch",
          "--no-tags",
          "--no-write-fetch-head",
          "/bat/source",
          s"+${allocation.pins.baseRef.value}:refs/bat/base",
          s"+${allocation.pins.headRef.value}:refs/bat/head"
        ),
        mounts,
        workPath,
        limits
      )
      fetchResult <- sandbox.run(fetch).mapError(isolationFailure)
      _ <- requireSuccess(fetchResult, "fetch")
      checkout <- request(
        provisionOperationId(allocation.runId, "checkout"),
        GitSafety ++ Chunk(
          "-C",
          "/bat/work/repository",
          "checkout",
          "--detach",
          "--no-recurse-submodules",
          allocation.pins.headCommit.value
        ),
        mounts,
        workPath,
        limits
      )
      checkoutResult <- sandbox.run(checkout).mapError(isolationFailure)
      _ <- requireSuccess(checkoutResult, "checkout")
    yield ()

  private[worker] def provisionOperationId(
      runId: RunId,
      phase: String
  ): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(
        s"bat-provision-v1\n${runId.value}\n$phase".getBytes(
          StandardCharsets.UTF_8
        )
      )
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def requireSuccess(
      result: OciRunResult,
      phase: String
  ): IO[WorkerError, Unit] = result.outcome match
    case OciRunOutcome.Exited(0) => ZIO.unit
    case _                       =>
      ZIO.fail(
        WorkerError.SourceRejected(
          s"workspace_${phase}_failed",
          s"isolated workspace $phase did not complete successfully"
        )
      )

  private def bind(
      source: Path,
      destination: String,
      access: MountAccess
  ): IO[WorkerError, BindMount] =
    for
      path <- containerPath(destination)
      mount <- ZIO.fromEither(
        BindMount.make(source, path, access).left.map(isolationFailure)
      )
    yield mount

  private def containerPath(value: String): IO[WorkerError, ContainerPath] =
    ZIO.fromEither(ContainerPath.from(value).left.map(isolationFailure))

  private def request(
      operationId: String,
      argv: Chunk[String],
      mounts: Chunk[BindMount],
      cwd: ContainerPath,
      limits: OciLimits
  ): IO[WorkerError, OciRunRequest] =
    ZIO.fromEither(
      OciRunRequest
        .make(operationId, argv, mounts, cwd, limits)
        .left
        .map(isolationFailure)
    )

  private def treeInspectionLimits(
      base: OciLimits,
      storage: WorkerStorageLimits
  ): IO[WorkerError, OciLimits] =
    val stderrBytes = math.min(base.stderrPreviewBytes, 4096)
    val outputBytes = storage.maxTreeMetadataBytes.toLong + stderrBytes.toLong
    ZIO.fromEither(
      OciLimits
        .make(
          timeout = base.timeout,
          stdoutPreviewBytes = storage.maxTreeMetadataBytes,
          stderrPreviewBytes = stderrBytes,
          outputLimitBytes = outputBytes,
          pids = base.pids,
          memoryBytes = base.memoryBytes,
          cpus = base.cpus,
          tmpBytes = base.tmpBytes,
          homeBytes = base.homeBytes,
          workBytes = base.workBytes
        )
        .left
        .map(isolationFailure)
    )

  private def isolationFailure(failure: OciFailure): WorkerError =
    WorkerError.IsolationFailure(failure.code, failure.safeMessage)

final class JavaWorkerSession private (
    val runId: RunId,
    val workspace: RunWorkspace,
    rawBdr: BdrSession,
    authority: PullRequestAuthority,
    gitRunner: GitRunner,
    sandbox: OciSandbox,
    ledger: WorkerLedger,
    config: WorkerRuntimeConfig,
    reader: RepositoryReader,
    actionMutex: Semaphore
):
  val bdr: BdrSession = guardedBdr(rawBdr)

  def read(path: RepositoryPath, maxBytes: Int): IO[WorkerError, String] =
    serialized(healthyWorkspaceUnlocked *> reader.read(path, maxBytes))

  def search(
      needle: String,
      maxMatches: Int = 200
  ): IO[WorkerError, Chunk[SearchMatch]] =
    serialized(
      healthyWorkspaceUnlocked *> reader.search(needle, maxMatches)
    )

  def build(
      operationId: OperationId,
      expected: WorkspacePrecondition,
      request: JavaBuildRequest
  ): IO[WorkerError, OperationResult] =
    serialized {
      val plan = config.javaPolicy.plan(request)
      executePlan(
        operationId,
        expected,
        plan,
        extraDigest = request.toString,
        disposable = true,
        patchInput = None
      )
    }

  def gitStatus(
      operationId: OperationId,
      expected: WorkspacePrecondition
  ): IO[WorkerError, OperationResult] =
    serialized(
      executePlan(
        operationId,
        expected,
        GitCommandPolicy.status,
        extraDigest = "",
        disposable = false,
        patchInput = None
      )
    )

  def gitDiff(
      operationId: OperationId,
      expected: WorkspacePrecondition
  ): IO[WorkerError, OperationResult] =
    serialized(
      executePlan(
        operationId,
        expected,
        GitCommandPolicy.diff,
        extraDigest = "",
        disposable = false,
        patchInput = None
      )
    )

  def applyPatch(
      operationId: OperationId,
      expected: WorkspacePrecondition,
      patch: String
  ): IO[WorkerError, OperationResult] =
    serialized {
      for
        _ <- ZIO.fromEither(PatchPolicy.validate(patch))
        _ <- AuthenticatedPrSource.requireFresh(authority, workspace.pins)
        result <- executePlan(
          operationId,
          expected,
          GitCommandPolicy.applyPatch,
          extraDigest = patch,
          disposable = false,
          patchInput = Some(patch)
        )
      yield result
    }

  def gitCommit(
      operationId: OperationId,
      expected: WorkspacePrecondition,
      message: String
  ): IO[WorkerError, OperationResult] =
    serialized {
      for
        plan <- ZIO.fromEither(GitCommandPolicy.commit(message))
        _ <- AuthenticatedPrSource.requireFresh(authority, workspace.pins)
        result <- executePlan(
          operationId,
          expected,
          plan,
          extraDigest = message,
          disposable = false,
          patchInput = None
        )
      yield result
    }

  def currentWorkspace: IO[WorkerError, WorkspacePrecondition] =
    serialized(healthyWorkspaceUnlocked)

  def receipt(
      operationId: OperationId
  ): IO[WorkerError, Option[TrustedReceipt]] =
    serialized(ledger.lookup(operationId))

  def prepareHandoff: IO[WorkerError, VerifiedWorkerResult] =
    serialized {
      for
        _ <- AuthenticatedPrSource.requireFresh(authority, workspace.pins)
        recorded <- healthyWorkspaceUnlocked
        state <- rawBdr.checkpoint.mapError(error =>
          WorkerError.LedgerFailure(error.code, error.safeMessage)
        )
        terminal <- WorkerBdrTerminal.require(state)
        result <- VerifiedWorkerResult.create(
          workspace,
          gitRunner,
          recorded,
          terminal,
          AuthenticatedPrSource.requireFresh(authority, workspace.pins)
        )
      yield result
    }

  private[worker] def trustedReceiptSnapshot(
      operationId: OperationId
  ): IO[
    WorkerError,
    (WorkspacePrecondition, Option[TrustedReceipt])
  ] =
    serialized(
      healthyWorkspaceUnlocked.zip(ledger.lookup(operationId))
    )

  private def healthyWorkspaceUnlocked: IO[WorkerError, WorkspacePrecondition] =
    for
      recorded <- ledger.currentWorkspace
      actual <- WorkspaceFingerprinting.compute(workspace.repository)
      _ <- requireFingerprint(recorded, actual)
    yield recorded

  private def guardedBdr(delegate: BdrSession): BdrSession =
    new BdrSession:
      val engineCommit: String = delegate.engineCommit
      val actor: String = delegate.actor

      def current =
        serialized(
          healthyWorkspaceUnlocked.mapError(bdrFailure) *> delegate.current
        )

      def checkpoint =
        serialized(
          healthyWorkspaceUnlocked.mapError(bdrFailure) *> delegate.checkpoint
        )

      def apply(operation: zio.json.ast.Json.Obj) =
        serialized(
          healthyWorkspaceUnlocked.mapError(bdrFailure) *>
            delegate.apply(operation)
        )

      def auditSummary =
        serialized(
          healthyWorkspaceUnlocked.mapError(bdrFailure) *>
            delegate.auditSummary
        )

      def completionCheck =
        serialized(
          healthyWorkspaceUnlocked.mapError(bdrFailure) *>
            delegate.completionCheck
        )

  private def serialized[E, A](effect: IO[E, A]): IO[E, A] =
    actionMutex.withPermit(effect)

  private def bdrFailure(error: WorkerError): BatError =
    BatError.BdrFailure(error.code, error.safeMessage)

  private def executePlan(
      operationId: OperationId,
      expected: WorkspacePrecondition,
      plan: JavaCommandPlan,
      extraDigest: String,
      disposable: Boolean,
      patchInput: Option[String]
  ): IO[WorkerError, OperationResult] =
    for
      actual <- WorkspaceFingerprinting.compute(workspace.repository)
      _ <- requireFingerprint(expected, actual)
      requestDigest = digestRequest(plan, extraDigest)
      operation <- ZIO.fromEither(
        WorkerOperation.make(
          operationId,
          plan.kind,
          requestDigest,
          plan.requestIdentity,
          expected,
          plan.policyId,
          Some(config.imageDigest)
        )
      )
      result <- ledger.execute(operation) {
        if disposable then
          runPlan(
            operationId,
            plan,
            workspace.repository,
            None,
            stagedWorkspace = true
          )
        else
          patchInput match
            case Some(patch) =>
              ZIO.scoped {
                PatchInput
                  .open(config.scratchRoot, operationId, patch)
                  .flatMap(path =>
                    runPlan(
                      operationId,
                      plan,
                      workspace.repository,
                      Some(path),
                      stagedWorkspace = false
                    )
                  )
              }
            case None =>
              runPlan(
                operationId,
                plan,
                workspace.repository,
                None,
                stagedWorkspace = false
              )
      }
    yield result

  private def runPlan(
      operationId: OperationId,
      plan: JavaCommandPlan,
      mountedRoot: Path,
      inputMount: Option[Path],
      stagedWorkspace: Boolean
  ): IO[WorkerError, CompletedOperation] =
    for
      rootDestination <- containerPath(
        if stagedWorkspace then "/bat/source" else "/bat/repository"
      )
      workingDirectory <- containerPath(
        if stagedWorkspace then "/bat/run" else "/bat/repository"
      )
      rootMount <- bind(
        mountedRoot,
        rootDestination,
        if stagedWorkspace then MountAccess.ReadOnly else MountAccess.ReadWrite
      )
      extraMounts <- inputMount match
        case Some(path) =>
          for
            destination <- containerPath("/bat/input")
            mount <- bind(path, destination, MountAccess.ReadOnly)
          yield Chunk(mount)
        case None => ZIO.succeed(Chunk.empty)
      rawRequest =
        if stagedWorkspace then
          OciRunRequest.makeStaged(
            operationId.value,
            plan.argv,
            Chunk(rootMount) ++ extraMounts,
            workingDirectory,
            config.ociLimits
          )
        else
          OciRunRequest.make(
            operationId.value,
            plan.argv,
            Chunk(rootMount) ++ extraMounts,
            workingDirectory,
            config.ociLimits
          )
      request <- ZIO.fromEither(rawRequest.left.map(isolationFailure))
      started <- Clock.nanoTime
      result <- sandbox.run(request).either
      finished <- Clock.nanoTime
      durationMillis = math.max(0L, (finished - started) / 1000000L)
      observation <- result match
        case Right(value) => observationFrom(value, durationMillis)
        case Left(failure) if failure.code == "process_start_failed" =>
          emptyObservation(CommandOutcome.StartFailed, durationMillis)
        case Left(failure) => ZIO.fail(isolationFailure(failure))
      after <- WorkspaceFingerprinting.compute(workspace.repository)
    yield CompletedOperation(observation, after)

  private def observationFrom(
      result: OciRunResult,
      durationMillis: Long
  ): IO[WorkerError, CommandObservation] =
    val outcome = result.outcome match
      case OciRunOutcome.Exited(code)     => CommandOutcome.Exited(code)
      case OciRunOutcome.TimedOut         => CommandOutcome.TimedOut
      case _: OciRunOutcome.OutputLimit   => CommandOutcome.OutputLimit
      case _: OciRunOutcome.RuntimeFailed => CommandOutcome.StartFailed
    ZIO.fromEither(
      CommandObservation.make(
        outcome,
        result.stdout.preview,
        result.stderr.preview,
        result.stdout.sha256,
        result.stderr.sha256,
        result.stdout.totalBytes,
        result.stderr.totalBytes,
        durationMillis
      )
    )

  private def emptyObservation(
      outcome: CommandOutcome,
      durationMillis: Long
  ): IO[WorkerError, CommandObservation] =
    ZIO.fromEither(
      CommandObservation.make(
        outcome,
        Chunk.empty,
        Chunk.empty,
        JavaWorkerSession.EmptyDigest,
        JavaWorkerSession.EmptyDigest,
        0L,
        0L,
        durationMillis
      )
    )

  private def requireFingerprint(
      expected: WorkspacePrecondition,
      actual: WorkspaceFingerprint
  ): IO[WorkerError, Unit] =
    if actual == expected.fingerprint then ZIO.unit
    else
      ZIO.fail(
        WorkerError.LedgerFailure(
          "workspace_fingerprint_mismatch",
          "authoring workspace changed outside its operation ledger"
        )
      )

  private def bind(
      source: Path,
      destination: ContainerPath,
      access: MountAccess
  ): IO[WorkerError, BindMount] =
    ZIO.fromEither(
      BindMount.make(source, destination, access).left.map(isolationFailure)
    )

  private def containerPath(value: String): IO[WorkerError, ContainerPath] =
    ZIO.fromEither(ContainerPath.from(value).left.map(isolationFailure))

  private def isolationFailure(failure: OciFailure): WorkerError =
    WorkerError.IsolationFailure(failure.code, failure.safeMessage)

  private def digestRequest(plan: JavaCommandPlan, extra: String): String =
    val payload =
      (Chunk("bat-worker-request-v1", plan.policyId) ++ plan.argv :+ extra)
        .mkString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(payload.getBytes(StandardCharsets.UTF_8))
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

object JavaWorkerSession:
  private val EmptyDigest =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  def start(
      runId: RunId,
      baseRepository: RepositoryId,
      pullRequestId: PullRequestId,
      sourceRepository: Path,
      authority: PullRequestAuthority,
      sourceVerifier: PinnedGitSource,
      workspaceGitRunner: GitRunner,
      sandbox: OciSandbox,
      bdrLifecycle: WorkerBdrLifecycle,
      config: WorkerRuntimeConfig
  ): ZIO[Scope, WorkerError, JavaWorkerSession] =
    for
      _ <- requireSandboxIdentity(sandbox, config)
      _ <- SourceRepositoryPreflight.verify(
        sourceRepository,
        config.storageLimits
      )
      pins <- AuthenticatedPrSource.resolve(
        authority,
        baseRepository,
        pullRequestId
      )
      _ <- sourceVerifier.verify(sourceRepository, pins)
      allocation <- RunWorkspace.allocate(
        config.controlRoot,
        config.workspaceRoot,
        runId,
        pins
      )
      _ <- WorkspaceProvisioner.provision(
        sandbox,
        sourceRepository,
        allocation,
        config.ociLimits,
        config.storageLimits
      )
      _ <- PinnedWorkspace.verify(
        allocation.repository,
        pins,
        workspaceGitRunner
      )
      _ <- AuthenticatedPrSource.requireFresh(authority, pins)
      workspace <- RunWorkspace.seal(allocation)
      bdr <- bdrLifecycle.initialize(workspace.repository, pins)
      initialRevision <- ZIO.fromEither(WorkspaceRevision.from(0L))
      initial = WorkspacePrecondition(
        initialRevision,
        workspace.initialFingerprint
      )
      ledger <- WorkerLedger.open(config.controlRoot, runId, initial)
      reader <- RepositoryReader.open(workspace.repository)
      actionMutex <- Semaphore.make(1L)
    yield JavaWorkerSession(
      runId,
      workspace,
      bdr,
      authority,
      workspaceGitRunner,
      sandbox,
      ledger,
      config,
      reader,
      actionMutex
    )

  def resume(
      runId: RunId,
      authority: PullRequestAuthority,
      workspaceGitRunner: GitRunner,
      sandbox: OciSandbox,
      bdrLifecycle: WorkerBdrLifecycle,
      config: WorkerRuntimeConfig
  ): ZIO[Scope, WorkerError, JavaWorkerSession] =
    for
      _ <- requireSandboxIdentity(sandbox, config)
      workspace <- RunWorkspace.resume(
        config.controlRoot,
        config.workspaceRoot,
        runId
      )
      initialRevision <- ZIO.fromEither(WorkspaceRevision.from(0L))
      initial = WorkspacePrecondition(
        initialRevision,
        workspace.initialFingerprint
      )
      ledger <- WorkerLedger.open(config.controlRoot, runId, initial)
      pending <- ledger.pendingOperation
      _ <- ZIO.foreachDiscard(pending)(operation =>
        sandbox
          .cleanup(operation.id.value)
          .mapError(failure =>
            WorkerError.IsolationFailure(
              failure.code,
              failure.safeMessage
            )
          )
      )
      recoveredFingerprint <- WorkspaceFingerprinting.compute(
        workspace.repository
      )
      _ <- ZIO.foreachDiscard(pending)(operation =>
        ledger.recoverInterruptedReadOnly(operation, recoveredFingerprint)
      )
      recorded <- ledger.currentWorkspace
      actual <- WorkspaceFingerprinting.compute(workspace.repository)
      _ <-
        if actual == recorded.fingerprint then ZIO.unit
        else
          ZIO.fail(
            WorkerError.LedgerFailure(
              "resume_workspace_mismatch",
              "workspace does not match the durable operation ledger"
            )
          )
      _ <- AuthenticatedPrSource.requireFresh(authority, workspace.pins)
      _ <- GitConfigurationGuard.verifyWorkspace(
        workspace.repository,
        workspaceGitRunner
      )
      bdr <- bdrLifecycle.resume(workspace.repository, workspace.pins)
      reader <- RepositoryReader.open(workspace.repository)
      actionMutex <- Semaphore.make(1L)
    yield JavaWorkerSession(
      runId,
      workspace,
      bdr,
      authority,
      workspaceGitRunner,
      sandbox,
      ledger,
      config,
      reader,
      actionMutex
    )

  private def requireSandboxIdentity(
      sandbox: OciSandbox,
      config: WorkerRuntimeConfig
  ): IO[WorkerError, Unit] =
    if sandbox.image == config.image then ZIO.unit
    else
      ZIO.fail(
        WorkerError.IsolationFailure(
          "sandbox_image_mismatch",
          "worker sandbox image does not match the receipt policy"
        )
      )

private object PatchInput:
  def open(
      scratchRoot: Path,
      operationId: OperationId,
      patch: String
  ): ZIO[Scope, WorkerError, Path] =
    ZIO.acquireRelease(create(scratchRoot, operationId, patch))(remove)

  private def create(
      scratchRoot: Path,
      operationId: OperationId,
      patch: String
  ): IO[WorkerError, Path] =
    ZIO
      .attemptBlocking {
        val root = scratchRoot.toAbsolutePath.normalize
        if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
          if Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
          then throw new IllegalStateException("scratch root is unsafe")
        else
          Files.createDirectories(root)
          restrictDirectory(root)
        val directory = root.resolve(s"${operationId.value}-input")
        createPrivateDirectory(directory)
        val file = directory.resolve("change.patch")
        Files.writeString(
          file,
          patch,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        restrictFile(file)
        directory
      }
      .mapError(_ =>
        WorkerError.IsolationFailure(
          "patch_stage_failed",
          "validated patch could not be staged for isolated execution"
        )
      )

  private def remove(path: Path): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
      if path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        val _ = Files.deleteIfExists(path.resolve("change.patch"))
        val _ = Files.deleteIfExists(path)
    }.ignore

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
