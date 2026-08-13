package bat.runner

import bat.protocol.BatError
import bat.worker.oci.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardCopyOption
}
import java.security.MessageDigest

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

/** A concrete evaluator that runs after the actor scope closes. It copies the
  * trusted target into an evaluator-owned directory, adds sealed material
  * there, and exposes that single directory read-only to a fresh networkless
  * OCI run. The actor never receives the oracle paths or bytes.
  */
final class OciJavaEvaluator private (
    sandbox: OciSandbox,
    sourceRepository: Path,
    scratchRoot: Path,
    limits: OciLimits,
    profile: JavaEvaluationProfile,
    evaluatorRevision: String
) extends ProductionEvaluator:
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
    ZIO
      .attemptBlocking {
        requireRegularFile(handoff.patchPath, "delivery patch")
        val actual = sha256(Files.readAllBytes(handoff.patchPath))
        if actual != handoff.patchSha256 then
          throw new IllegalStateException("delivery digest mismatch")
        Files.createDirectories(scratchRoot)
        restrictDirectory(scratchRoot)
        val root = Files.createTempDirectory(scratchRoot, "evaluation-")
        restrictDirectory(root)
        copySource(sourceRepository, root)
        val sealedRoot = Files.createDirectory(root.resolve(".bat-evaluator"))
        restrictDirectory(sealedRoot)
        Files.copy(
          handoff.patchPath,
          sealedRoot.resolve("delivery.patch"),
          StandardCopyOption.COPY_ATTRIBUTES
        )
        profile match
          case JavaEvaluationProfile.Javac(hidden, _, _) =>
            copySource(hidden, sealedRoot.resolve("oracle"))
          case JavaEvaluationProfile.Maven(oraclePatch, _, _) =>
            requireRegularFile(oraclePatch, "oracle patch")
            Files.copy(
              oraclePatch,
              sealedRoot.resolve("oracle.patch"),
              StandardCopyOption.COPY_ATTRIBUTES
            )
        root
      }
      .mapError(_ =>
        BatError.BackendFailure(
          "evaluator_prepare_failed",
          "sealed evaluator input could not be prepared",
          retryable = false
        )
      )

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

  private def copySource(source: Path, target: Path): Unit =
    if source == null || !source.isAbsolute || Files.isSymbolicLink(source) ||
      !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
    then throw new IllegalStateException("unsafe evaluator source")
    Files.createDirectories(target)
    val _ = Files.walkFileTree(
      source,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(
            directory: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): FileVisitResult =
          val relative = source.relativize(directory)
          if relative.getNameCount > 0 &&
            Set(".git", ".bdr").contains(relative.getName(0).toString)
          then FileVisitResult.SKIP_SUBTREE
          else if Files.isSymbolicLink(directory) then
            throw new IllegalStateException("evaluator source has a symlink")
          else
            Files.createDirectories(target.resolve(relative))
            FileVisitResult.CONTINUE

        override def visitFile(
            file: Path,
            attributes: java.nio.file.attribute.BasicFileAttributes
        ): FileVisitResult =
          if Files.isSymbolicLink(file) || !attributes.isRegularFile then
            throw new IllegalStateException("evaluator source is not regular")
          Files.copy(
            file,
            target.resolve(source.relativize(file)),
            StandardCopyOption.COPY_ATTRIBUTES
          )
          FileVisitResult.CONTINUE
    )

  private def requireRegularFile(path: Path, label: String): Unit =
    if path == null || !path.isAbsolute || Files.isSymbolicLink(path) ||
      !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    then throw new IllegalStateException(s"unsafe $label")

  private def restrictDirectory(path: Path): Unit =
    val _ = Files.setPosixFilePermissions(
      path,
      PosixFilePermissions.fromString("rwx------")
    )

  private def removeTree(path: Path): ZIO[Any, Nothing, Unit] =
    ZIO.attemptBlocking {
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
    }.ignore

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

  def make(
      sandbox: OciSandbox,
      sourceRepository: Path,
      scratchRoot: Path,
      limits: OciLimits,
      profile: JavaEvaluationProfile,
      evaluatorRevision: String
  ): Either[BatError, OciJavaEvaluator] =
    val validProfile = Option(profile).exists {
      case JavaEvaluationProfile.Javac(hidden, hiddenClass, publicClass) =>
        hidden != null && hidden.isAbsolute &&
        SafeClass.matches(hiddenClass) && SafeClass.matches(publicClass)
      case JavaEvaluationProfile.Maven(oracle, selector, _) =>
        oracle != null && oracle.isAbsolute && SafeClass.matches(selector)
    }
    if sandbox == null || sourceRepository == null || !sourceRepository.isAbsolute ||
      scratchRoot == null || !scratchRoot.isAbsolute || limits == null ||
      profile == null || !validProfile ||
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
          scratchRoot.normalize,
          limits,
          profile,
          evaluatorRevision
        )
      )
