package bat.quickstart

import bat.protocol.BatError

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*

/** Result from the evaluator-only side of the Java portability canary.
  * Evaluator source and process output are deliberately absent.
  */
final case class ToyEvaluation(
    @jsonField("public_green") publicGreen: Boolean,
    @jsonField("hidden_green") hiddenGreen: Boolean,
    @jsonField("actor_boundary_intact") actorBoundaryIntact: Boolean,
    @jsonField("source_tree_clean") sourceTreeClean: Boolean,
    @jsonField("evaluation_digest") evaluationDigest: String,
    @jsonField("final_patch_digest") finalPatchDigest: String,
    @jsonField("final_head_sha") finalHeadSha: String
) derives JsonCodec:
  def passed: Boolean =
    publicGreen && hiddenGreen && actorBoundaryIntact && sourceTreeClean

private[quickstart] final case class ToySuiteOutcomes(
    publicCompile: ToyCommandResult,
    hiddenCompile: ToyCommandResult,
    publicRun: ToyCommandResult,
    hiddenRun: ToyCommandResult
)

/** Trusted evaluator for the Java fixture.
  *
  * The hidden suite is compiled from `fixtureRoot`, never copied into the actor
  * repository, and is invoked only after the agentic loop has stopped.
  */
object ToyEvaluator:
  private val PublicMain =
    "dev.bat.examples.ingress.IngressGatewayPublicTest"
  private val HiddenMain =
    "dev.bat.examples.ingress.IngressGatewayHiddenTest"

  def evaluate(
      fixtureRoot: Path,
      toy: MaterializedToy
  ): IO[BatError, ToyEvaluation] =
    for
      outcomes <- suiteOutcomes(fixtureRoot, toy.repository)
      head <- ToyRuntime.head(toy.repository)
      patch <- ToyRuntime.git(
        toy.repository,
        Seq(
          "diff",
          "--no-ext-diff",
          "--no-textconv",
          "--binary",
          s"${toy.headSha}..$head",
          "--",
          "src"
        )
      )
      status <- ToyRuntime.git(
        toy.repository,
        Seq("status", "--porcelain=v1", "--untracked-files=all", "--", "src")
      )
      actorBoundary <- actorBoundaryIntact(toy.repository)
      evaluationBytes =
        outcomes.publicCompile.combinedOutput ++
          outcomes.hiddenCompile.combinedOutput ++
          outcomes.publicRun.combinedOutput ++
          outcomes.hiddenRun.combinedOutput ++ patch.output
      result = ToyEvaluation(
        publicGreen = outcomes.publicRun.exitCode == 0,
        hiddenGreen = outcomes.hiddenRun.exitCode == 0,
        actorBoundaryIntact = actorBoundary,
        sourceTreeClean = decode(status.output).trim.isEmpty,
        evaluationDigest = ToyRuntime.sha256(evaluationBytes),
        finalPatchDigest = ToyRuntime.sha256(patch.output),
        finalHeadSha = head
      )
      _ <- ZIO
        .fail(
          BatError.BackendFailure(
            "toy_evaluation_failed",
            "Java six-phase evaluator rejected the final workspace",
            retryable = false
          )
        )
        .unless(result.passed)
    yield result

  private[quickstart] def suiteOutcomes(
      fixtureRoot: Path,
      repository: Path
  ): IO[BatError, ToySuiteOutcomes] =
    ZIO.scoped {
      for
        publicClasses <- temporaryDirectory("bat-toy-evaluator-public-")
        hiddenClasses <- temporaryDirectory("bat-toy-evaluator-hidden-")
        production <- javaSources(
          repository.resolve("src").resolve("main").resolve("java")
        )
        publicTests <- javaSources(
          repository.resolve("src").resolve("test").resolve("java")
        )
        hiddenTests <- javaSources(
          fixtureRoot
            .resolve("oracle")
            .resolve("src")
            .resolve("test")
            .resolve("java")
        )
        publicCompile <- compile(
          publicClasses,
          production ++ publicTests
        )
        hiddenCompile <- compile(
          hiddenClasses,
          production ++ hiddenTests
        )
        public <- runMain(publicClasses, PublicMain)
        hidden <- runMain(hiddenClasses, HiddenMain)
      yield ToySuiteOutcomes(
        publicCompile,
        hiddenCompile,
        public,
        hidden
      )
    }

  private def compile(
      classes: Path,
      sources: Seq[Path]
  ): IO[BatError, ToyCommandResult] =
    ToyRuntime
      .command(
        Seq(
          ToyRuntime.Javac.toString,
          "--release",
          "17",
          "-d",
          classes.toString
        ) ++ sources.map(_.toString),
        classes
      )
      .flatMap(result =>
        ZIO
          .fail(
            BatError.BackendFailure(
              "toy_evaluator_compile_failed",
              "trusted toy evaluator did not compile",
              retryable = false
            )
          )
          .unless(result.exitCode == 0)
          .as(result)
      )

  private def runMain(
      classes: Path,
      mainClass: String
  ): IO[BatError, ToyCommandResult] =
    ToyRuntime.command(
      Seq(
        ToyRuntime.Java.toString,
        "-cp",
        classes.toString,
        mainClass
      ),
      classes
    )

  private def actorBoundaryIntact(repository: Path): IO[BatError, Boolean] =
    ZIO
      .attemptBlocking {
        !Seq("oracle", "reference").exists(name =>
          Files.exists(repository.resolve(name), LinkOption.NOFOLLOW_LINKS)
        )
      }
      .mapError(_ =>
        BatError.BackendFailure(
          "toy_boundary_check_failed",
          "cannot inspect the toy actor boundary",
          retryable = false
        )
      )

  private def javaSources(root: Path): IO[BatError, Seq[Path]] =
    ZIO
      .attemptBlocking {
        if Files.isSymbolicLink(root) ||
          !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        then throw IllegalArgumentException("invalid Java source root")
        val stream = Files.walk(root)
        try
          stream
            .iterator()
            .asScala
            .filter(path =>
              Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) &&
                path.getFileName.toString.endsWith(".java")
            )
            .toSeq
            .sortBy(_.toString)
        finally stream.close()
      }
      .mapError(_ =>
        BatError.BackendFailure(
          "toy_evaluator_sources_failed",
          "trusted toy evaluator sources are missing or unsafe",
          retryable = false
        )
      )

  private def temporaryDirectory(
      prefix: String
  ): ZIO[Scope, BatError, Path] =
    ZIO.acquireRelease(
      ZIO
        .attemptBlocking(Files.createTempDirectory(prefix))
        .mapError(_ =>
          BatError.BackendFailure(
            "toy_evaluator_workspace_failed",
            "cannot create trusted toy evaluator workspace",
            retryable = false
          )
        )
    )(deleteRecursively)

  private def deleteRecursively(root: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(root, LinkOption.NOFOLLOW_LINKS) then
        val stream = Files.walk(root)
        try
          stream
            .iterator()
            .asScala
            .toSeq
            .sortBy(_.getNameCount)
            .reverse
            .foreach(path =>
              val _ = Files.deleteIfExists(path)
            )
        finally stream.close()
    }.ignore

  private def decode(bytes: Array[Byte]): String =
    String(bytes, StandardCharsets.UTF_8)
