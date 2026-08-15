package bat.runner

import bat.protocol.BatError
import bat.worker.{
  GitInvocation,
  GitRunner,
  PinnedGitSource,
  PullRequestPins,
  WorkerError
}
import bat.worker.oci.*

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

import scala.collection.mutable

import zio.{Chunk, IO, Scope, ZIO}

enum JavaEvaluationProfile:
  case Javac(
      hiddenSourceRoot: Path,
      hiddenMainClass: String,
      publicMainClass: String
  )
  case Maven(
      oraclePatch: Path,
      hiddenTestSelector: String,
      runPublicSuite: Boolean
  )

private[runner] enum JavaEvaluationOracleEntry:
  case Directory(path: Chunk[String])
  case File(path: Chunk[String], content: Chunk[Byte])

private[runner] enum JavaEvaluationOracleSnapshot:
  case Javac(entries: Chunk[JavaEvaluationOracleEntry])
  case Maven(content: Chunk[Byte])

/** A validated, immutable snapshot of the exact oracle content that will be
  * sealed into an evaluator run. The digest binds profile kind, relative path
  * structure, and file bytes; raw oracle content is deliberately redacted from
  * diagnostics.
  */
private[runner] final class JavaEvaluationOracleInspection private (
    val sha256: String,
    private[runner] val snapshot: JavaEvaluationOracleSnapshot
):
  override def toString: String =
    s"JavaEvaluationOracleInspection(sha256=$sha256, content=<redacted>)"

private[runner] object JavaEvaluationOracleInspection:
  private val Domain =
    "bat-java-evaluation-oracle-v1".getBytes(StandardCharsets.US_ASCII)

  def inspect(
      profile: JavaEvaluationProfile
  ): IO[BatError, JavaEvaluationOracleInspection] =
    ZIO
      .attemptBlocking(inspectBlocking(profile))
      .mapError(_ =>
        BatError.BackendFailure(
          "evaluator_oracle_inspection_failed",
          "sealed evaluator oracle could not be inspected safely",
          retryable = false
        )
      )

  private def inspectBlocking(
      profile: JavaEvaluationProfile
  ): JavaEvaluationOracleInspection =
    if profile == null then
      throw new IllegalStateException("evaluation profile is required")
    val snapshot = profile match
      case JavaEvaluationProfile.Javac(root, _, _) => inspectDirectory(root)
      case JavaEvaluationProfile.Maven(path, _, _) => inspectFile(path)
    new JavaEvaluationOracleInspection(digest(snapshot), snapshot)

  private def inspectDirectory(root: Path): JavaEvaluationOracleSnapshot =
    if root == null || !root.isAbsolute || Files.isSymbolicLink(root) ||
      !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
    then throw new IllegalStateException("unsafe Javac oracle root")
    val entries = mutable.ArrayBuffer.empty[JavaEvaluationOracleEntry]
    val _ = Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            directory: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if Files.isSymbolicLink(directory) || !attributes.isDirectory then
            throw new IllegalStateException(
              "Javac oracle has an unsafe directory"
            )
          val relative = relativePath(root, directory)
          if directory != root then
            entries += JavaEvaluationOracleEntry.Directory(relative)
          FileVisitResult.CONTINUE

        override def visitFile(
            file: Path,
            attributes: BasicFileAttributes
        ): FileVisitResult =
          if Files.isSymbolicLink(file) || !attributes.isRegularFile then
            throw new IllegalStateException("Javac oracle has an unsafe file")
          val content = Chunk.fromArray(Files.readAllBytes(file))
          if Files.isSymbolicLink(file) ||
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          then
            throw new IllegalStateException(
              "Javac oracle changed during inspection"
            )
          entries += JavaEvaluationOracleEntry.File(
            relativePath(root, file),
            content
          )
          FileVisitResult.CONTINUE

        override def visitFileFailed(
            file: Path,
            error: java.io.IOException
        ): FileVisitResult = throw error

        override def postVisitDirectory(
            directory: Path,
            error: java.io.IOException
        ): FileVisitResult =
          if error != null then throw error
          FileVisitResult.CONTINUE
    )
    val ordered = entries.toSeq.sortBy(entry => entryPath(entry).mkString("/"))
    JavaEvaluationOracleSnapshot.Javac(Chunk.fromIterable(ordered))

  private def inspectFile(path: Path): JavaEvaluationOracleSnapshot =
    if path == null || !path.isAbsolute || Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    then throw new IllegalStateException("unsafe Maven oracle patch")
    val content = Chunk.fromArray(Files.readAllBytes(path))
    if Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    then
      throw new IllegalStateException("Maven oracle changed during inspection")
    JavaEvaluationOracleSnapshot.Maven(content)

  private def relativePath(root: Path, path: Path): Chunk[String] =
    val relative = root.relativize(path)
    val builder = Chunk.newBuilder[String]
    val iterator = relative.iterator()
    while iterator.hasNext do
      val segment = iterator.next().toString
      if segment.nonEmpty then builder += segment
    builder.result()

  private def entryPath(entry: JavaEvaluationOracleEntry): Chunk[String] =
    entry match
      case JavaEvaluationOracleEntry.Directory(path) => path
      case JavaEvaluationOracleEntry.File(path, _)   => path

  private def digest(snapshot: JavaEvaluationOracleSnapshot): String =
    val digest = MessageDigest.getInstance("SHA-256")
    update(digest, Domain)
    snapshot match
      case JavaEvaluationOracleSnapshot.Javac(entries) =>
        update(digest, "javac".getBytes(StandardCharsets.US_ASCII))
        entries.foreach {
          case JavaEvaluationOracleEntry.Directory(path) =>
            update(digest, Array('d'.toByte))
            update(digest, path.mkString("/").getBytes(StandardCharsets.UTF_8))
          case JavaEvaluationOracleEntry.File(path, content) =>
            update(digest, Array('f'.toByte))
            update(digest, path.mkString("/").getBytes(StandardCharsets.UTF_8))
            update(digest, content.toArray)
        }
      case JavaEvaluationOracleSnapshot.Maven(content) =>
        update(digest, "maven".getBytes(StandardCharsets.US_ASCII))
        update(digest, content.toArray)
    digest
      .digest()
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def update(digest: MessageDigest, bytes: Array[Byte]): Unit =
    val length =
      ByteBuffer.allocate(java.lang.Long.BYTES).putLong(bytes.length).array()
    digest.update(length)
    digest.update(bytes)

/** A concrete evaluator that runs after the actor scope closes. It re-verifies
  * the authenticated PR pins, materializes that exact head into an
  * evaluator-owned directory, adds sealed material there, and exposes that
  * single directory read-only to a fresh networkless OCI run. The actor never
  * receives the oracle paths or bytes.
  */
final class OciJavaEvaluator private (
    sandbox: OciSandbox,
    sourceRepository: Path,
    sourceVerifier: PinnedGitSource,
    gitRunner: GitRunner,
    pins: PullRequestPins,
    scratchRoot: Path,
    limits: OciLimits,
    profile: JavaEvaluationProfile,
    expectedOracleSha256: String,
    evaluatorRevision: String
) extends ProductionEvaluator:
  private final case class PreparedEvaluator(
      root: Path,
      deliveryPatch: Chunk[Byte]
  )

  def evaluate(
      handoff: ProductionHandoff
  ): ZIO[Scope, BatError, EvaluationReport] =
    ZIO.acquireRelease(prepare(handoff))(removeTree).flatMap { prepared =>
      for
        sourcePath <- fromOci(ContainerPath.from("/bat/source"))
        mount <- fromOci(
          BindMount.make(prepared, sourcePath, MountAccess.ReadOnly)
        )
        workPath <- fromOci(ContainerPath.from("/bat/run"))
        request <- fromOci(
          OciRunRequest.makeStaged(
            operationId(handoff),
            command(profile),
            Chunk(mount),
            workPath,
            limits
          )
        )
        result <- sandbox.run(request).mapError(mapOci)
        passed = result.outcome == OciRunOutcome.Exited(0)
        digest = resultDigest(result)
        report <- ZIO.fromEither(
          EvaluationReport.make(
            "sealed-java-oci",
            evaluatorRevision,
            handoff.finalHeadCommit,
            handoff.patchSha256,
            passed,
            digest
          )
        )
      yield report
    }

  private def prepare(
      handoff: ProductionHandoff
  ): IO[BatError, Path] =
    for
      _ <- sourceVerifier
        .verify(sourceRepository, pins)
        .mapError(mapWorker)
      bound <- bindPatchAndCreateRoot(handoff)
      prepared <- (for
        _ <- materializePinnedHead(bound.root)
        oracle <- inspectExpectedOracle
        _ <- sealEvaluatorInputs(bound, oracle, handoff)
      yield bound.root).onError(_ => removeTree(bound.root))
    yield prepared

  private def bindPatchAndCreateRoot(
      handoff: ProductionHandoff
  ): IO[BatError, PreparedEvaluator] =
    ZIO
      .attemptBlocking {
        requireRegularFile(handoff.patchPath, "delivery patch")
        val patch = Chunk.fromArray(Files.readAllBytes(handoff.patchPath))
        val actual = sha256(patch.toArray)
        if actual != handoff.patchSha256 then
          throw new IllegalStateException("delivery digest mismatch")
        if patch.length.toLong != handoff.patchBytes then
          throw new IllegalStateException("delivery size mismatch")
        prepareScratchRoot()
        requireSeparatedTrustedPaths()
        val root = Files.createTempDirectory(scratchRoot, "evaluation-")
        restrictDirectory(root)
        PreparedEvaluator(root, patch)
      }
      .mapError(_ => prepareFailure)

  private def materializePinnedHead(root: Path): IO[BatError, Unit] =
    val cloneArguments = Chunk(
      "clone",
      "--quiet",
      "--no-checkout",
      "--no-hardlinks",
      "--no-tags",
      "--local",
      "--",
      sourceRepository.toString,
      root.toString
    )
    val checkoutArguments = Chunk(
      "checkout",
      "--quiet",
      "--detach",
      "--no-recurse-submodules",
      pins.headCommit.value
    )
    for
      cloned <- gitRunner
        .run(GitInvocation(scratchRoot, cloneArguments))
        .mapError(_ => materializeFailure)
      _ <- requireGitSuccess(cloned.exitCode)
      checkedOut <- gitRunner
        .run(GitInvocation(root, checkoutArguments))
        .mapError(_ => materializeFailure)
      _ <- requireGitSuccess(checkedOut.exitCode)
      resolved <- gitRunner
        .run(
          GitInvocation(
            root,
            Chunk("rev-parse", "--verify", "HEAD^{commit}")
          )
        )
        .mapError(_ => materializeFailure)
      _ <- requireGitSuccess(resolved.exitCode)
      _ <-
        if resolved.output.trim == pins.headCommit.value then ZIO.unit
        else ZIO.fail(materializeFailure)
      _ <- ZIO
        .attemptBlocking {
          removeTreeBlocking(root.resolve(".git"))
          requireSafeTree(root)
        }
        .mapError(_ => materializeFailure)
    yield ()

  private def sealEvaluatorInputs(
      prepared: PreparedEvaluator,
      oracle: JavaEvaluationOracleInspection,
      handoff: ProductionHandoff
  ): IO[BatError, Unit] =
    ZIO
      .attemptBlocking {
        val sealedRoot = Files.createDirectory(
          prepared.root.resolve(".bat-evaluator")
        )
        restrictDirectory(sealedRoot)
        val deliveryPatch = sealedRoot.resolve("delivery.patch")
        Files.write(
          deliveryPatch,
          prepared.deliveryPatch.toArray,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE
        )
        restrictFile(deliveryPatch)
        val sealedPatchBytes = Files.readAllBytes(deliveryPatch)
        if sealedPatchBytes.length.toLong != handoff.patchBytes ||
          sha256(sealedPatchBytes) != handoff.patchSha256
        then throw new IllegalStateException("sealed delivery patch changed")
        sealOracle(sealedRoot, oracle.snapshot)
        ()
      }
      .mapError(_ => prepareFailure)

  private def inspectExpectedOracle
      : IO[BatError, JavaEvaluationOracleInspection] =
    JavaEvaluationOracleInspection.inspect(profile).flatMap { inspection =>
      if inspection.sha256 == expectedOracleSha256 then ZIO.succeed(inspection)
      else
        ZIO.fail(
          BatError.BackendFailure(
            "evaluator_oracle_digest_mismatch",
            "sealed evaluator oracle changed after it was pinned",
            retryable = false
          )
        )
    }

  private def sealOracle(
      sealedRoot: Path,
      snapshot: JavaEvaluationOracleSnapshot
  ): Unit = snapshot match
    case JavaEvaluationOracleSnapshot.Javac(entries) =>
      val oracleRoot = Files.createDirectory(sealedRoot.resolve("oracle"))
      restrictDirectory(oracleRoot)
      entries.foreach {
        case JavaEvaluationOracleEntry.Directory(path) =>
          val directory = resolveOraclePath(oracleRoot, path)
          Files.createDirectory(directory)
          restrictDirectory(directory)
        case JavaEvaluationOracleEntry.File(path, content) =>
          val file = resolveOraclePath(oracleRoot, path)
          Files.write(
            file,
            content.toArray,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
          )
          restrictFile(file)
      }
    case JavaEvaluationOracleSnapshot.Maven(content) =>
      val oraclePatch = sealedRoot.resolve("oracle.patch")
      Files.write(
        oraclePatch,
        content.toArray,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      restrictFile(oraclePatch)

  private def resolveOraclePath(
      root: Path,
      segments: Chunk[String]
  ): Path =
    val resolved =
      segments.foldLeft(root)((path, segment) => path.resolve(segment))
    val normalized = resolved.normalize
    if segments.isEmpty || !normalized.startsWith(root) then
      throw new IllegalStateException("unsafe oracle snapshot path")
    normalized

  private def requireGitSuccess(exitCode: Int): IO[BatError, Unit] =
    if exitCode == 0 then ZIO.unit else ZIO.fail(materializeFailure)

  private def prepareFailure: BatError =
    BatError.BackendFailure(
      "evaluator_prepare_failed",
      "sealed evaluator input could not be prepared",
      retryable = false
    )

  private def materializeFailure: BatError =
    BatError.BackendFailure(
      "evaluator_materialize_failed",
      "authenticated evaluator source could not be materialized",
      retryable = false
    )

  private def mapWorker(error: WorkerError): BatError =
    BatError.BackendFailure(error.code, error.safeMessage, retryable = false)

  private def command(profile: JavaEvaluationProfile): Chunk[String] =
    profile match
      case JavaEvaluationProfile.Javac(_, hidden, public) =>
        Chunk(
          "/bin/sh",
          "-eu",
          "-c",
          OciJavaEvaluator.JavacScript,
          "bat-evaluate-javac",
          hidden,
          public
        )
      case JavaEvaluationProfile.Maven(_, selector, publicSuite) =>
        Chunk(
          "/bin/sh",
          "-eu",
          "-c",
          OciJavaEvaluator.MavenScript,
          "bat-evaluate-maven",
          selector,
          if publicSuite then "1" else "0"
        )

  private def operationId(handoff: ProductionHandoff): String =
    s"eval-${handoff.patchSha256.take(48)}"

  private def resultDigest(result: OciRunResult): String =
    val outcome = result.outcome match
      case OciRunOutcome.Exited(code)             => s"exited:$code"
      case OciRunOutcome.TimedOut                 => "timed_out"
      case OciRunOutcome.OutputLimit(limit, seen) =>
        s"output_limit:$limit:$seen"
      case OciRunOutcome.RuntimeFailed(code) => s"runtime_failed:$code"
    sha256(
      Chunk(
        "bat-sealed-java-evaluation-v1",
        outcome,
        result.stdout.sha256,
        result.stderr.sha256
      ).mkString("\u0000").getBytes(StandardCharsets.UTF_8)
    )

  private def prepareScratchRoot(): Unit =
    if Files.exists(scratchRoot, LinkOption.NOFOLLOW_LINKS) then
      if Files.isSymbolicLink(scratchRoot) ||
        !Files.isDirectory(scratchRoot, LinkOption.NOFOLLOW_LINKS)
      then throw new IllegalStateException("unsafe evaluator scratch root")
    else
      Files.createDirectories(scratchRoot)
      restrictDirectory(scratchRoot)

  private def requireSeparatedTrustedPaths(): Unit =
    val source = sourceRepository.toRealPath()
    val scratch = scratchRoot.toRealPath()
    val oracle = profile match
      case JavaEvaluationProfile.Javac(hidden, _, _) => hidden.toRealPath()
      case JavaEvaluationProfile.Maven(patch, _, _)  => patch.toRealPath()
    if overlaps(source, scratch) || overlaps(source, oracle) ||
      overlaps(scratch, oracle)
    then throw new IllegalStateException("evaluator trusted paths overlap")

  private def requireSafeTree(root: Path): Unit =
    val _ = Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            directory: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): FileVisitResult =
          if Files.isSymbolicLink(directory) then
            throw new IllegalStateException("evaluator source has a symlink")
          FileVisitResult.CONTINUE

        override def visitFile(
            file: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): FileVisitResult =
          if Files.isSymbolicLink(file) || !attributes.isRegularFile then
            throw new IllegalStateException("evaluator source is not regular")
          FileVisitResult.CONTINUE
    )

  private def overlaps(left: Path, right: Path): Boolean =
    left.startsWith(right) || right.startsWith(left)

  private def requireRegularFile(path: Path, label: String): Unit =
    if path == null || !path.isAbsolute || Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    then throw new IllegalStateException(s"unsafe $label")

  private def restrictDirectory(path: Path): Unit =
    val _ = Files.setPosixFilePermissions(
      path,
      PosixFilePermissions.fromString("rwx------")
    )

  private def restrictFile(path: Path): Unit =
    val _ = Files.setPosixFilePermissions(
      path,
      PosixFilePermissions.fromString("rw-------")
    )

  private def removeTree(path: Path): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
      removeTreeBlocking(path)
    }.ignore

  private def removeTreeBlocking(path: Path): Unit =
    if path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val _ = Files.walkFileTree(
        path,
        new SimpleFileVisitor[Path]:
          override def visitFile(
              file: Path,
              attributes: java.nio.file.attribute.BasicFileAttributes
          ): FileVisitResult =
            Files.deleteIfExists(file)
            FileVisitResult.CONTINUE

          override def postVisitDirectory(
              directory: Path,
              error: java.io.IOException
          ): FileVisitResult =
            if error != null then throw error
            Files.deleteIfExists(directory)
            FileVisitResult.CONTINUE
      )

  private def fromOci[A](value: Either[OciFailure, A]): IO[BatError, A] =
    ZIO.fromEither(value.left.map(mapOci))

  private def mapOci(error: OciFailure): BatError =
    BatError.BackendFailure(error.code, error.safeMessage, retryable = false)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

object OciJavaEvaluator:
  private val SafeRevision = "^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}$".r
  private val Sha256 = "^[0-9a-f]{64}$".r
  private val SafeClass =
    "^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$".r

  private[runner] val JavacScript =
    "git apply --whitespace=nowarn .bat-evaluator/delivery.patch; " +
      "classes=\"$(mktemp -d)\"; " +
      "find src/main/java src/test/java .bat-evaluator/oracle -name '*.java' -print0 | " +
      "sort -z | xargs -0 /usr/bin/javac --release 17 -d \"$classes\"; " +
      "/usr/bin/java -cp \"$classes\" \"$1\"; " +
      "exec /usr/bin/java -cp \"$classes\" \"$2\""

  private[runner] val MavenScript =
    "git apply --whitespace=nowarn .bat-evaluator/delivery.patch; " +
      "git apply --whitespace=nowarn .bat-evaluator/oracle.patch; " +
      "/usr/bin/mvn --batch-mode --offline --no-transfer-progress -Dstyle.color=never " +
      "-Drat.skip=true -Dmaven.repo.local=/bat/run/cache/maven -Dtest=\"$1\" test; " +
      "if [ \"$2\" = 1 ]; then exec /usr/bin/mvn --batch-mode --offline --no-transfer-progress " +
      "-Dstyle.color=never -Dmaven.repo.local=/bat/run/cache/maven test; fi"

  def makePinned(
      sandbox: OciSandbox,
      sourceRepository: Path,
      sourceVerifier: PinnedGitSource,
      gitRunner: GitRunner,
      pins: PullRequestPins,
      scratchRoot: Path,
      limits: OciLimits,
      profile: JavaEvaluationProfile,
      expectedOracleSha256: String,
      evaluatorRevision: String
  ): Either[BatError, OciJavaEvaluator] =
    val validProfile = Option(profile).exists {
      case JavaEvaluationProfile.Javac(hidden, hiddenClass, publicClass) =>
        hidden != null && hidden.isAbsolute &&
        Option(hiddenClass).exists(SafeClass.matches) &&
        Option(publicClass).exists(SafeClass.matches)
      case JavaEvaluationProfile.Maven(oracle, selector, _) =>
        oracle != null && oracle.isAbsolute &&
        Option(selector).exists(SafeClass.matches)
    }
    val source = Option(sourceRepository).map(_.normalize)
    val scratch = Option(scratchRoot).map(_.normalize)
    val oracle = Option(profile).flatMap {
      case JavaEvaluationProfile.Javac(hidden, _, _) =>
        Option(hidden).map(_.normalize)
      case JavaEvaluationProfile.Maven(path, _, _) =>
        Option(path).map(_.normalize)
    }
    val separated = (source, scratch, oracle) match
      case (Some(sourcePath), Some(scratchPath), Some(oraclePath)) =>
        !overlaps(sourcePath, scratchPath) &&
        !overlaps(sourcePath, oraclePath) &&
        !overlaps(scratchPath, oraclePath)
      case _ => false
    if sandbox == null || sourceRepository == null || !sourceRepository.isAbsolute ||
      sourceVerifier == null || gitRunner == null || pins == null ||
      scratchRoot == null || !scratchRoot.isAbsolute || limits == null ||
      profile == null || !validProfile || !separated ||
      expectedOracleSha256 == null ||
      !Sha256.matches(expectedOracleSha256) ||
      evaluatorRevision == null || !SafeRevision.matches(evaluatorRevision)
    then
      Left(
        BatError.ProtocolViolation("sealed evaluator configuration is invalid")
      )
    else
      Right(
        new OciJavaEvaluator(
          sandbox,
          sourceRepository.normalize,
          sourceVerifier,
          gitRunner,
          pins,
          scratchRoot.normalize,
          limits,
          profile,
          expectedOracleSha256,
          evaluatorRevision
        )
      )

  /** Kept only while callers migrate to [[makePinned]]. It fails closed rather
    * than evaluating whichever commit happens to be checked out in the source
    * repository.
    */
  private[runner] def make(
      sandbox: OciSandbox,
      sourceRepository: Path,
      scratchRoot: Path,
      limits: OciLimits,
      profile: JavaEvaluationProfile,
      evaluatorRevision: String
  ): Either[BatError, OciJavaEvaluator] =
    Left(
      BatError.ProtocolViolation(
        "sealed evaluator requires authenticated pinned source metadata"
      )
    )

  private def overlaps(left: Path, right: Path): Boolean =
    left.startsWith(right) || right.startsWith(left)
