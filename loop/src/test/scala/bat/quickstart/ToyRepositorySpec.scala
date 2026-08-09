package bat.quickstart

import bat.protocol.BatError

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitResult,
  Files,
  LinkOption,
  Path,
  SimpleFileVisitor,
  StandardCopyOption,
  StandardOpenOption
}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object ToyRepositorySpec extends ZIOSpecDefault:
  private val RouterPath =
    "src/main/java/dev/bat/examples/ingress/MessageRouter.java"

  def spec =
    suite("Java six-phase toy materialization")(
      test(
        "creates deterministic base and target history without private assets"
      ) {
        ZIO.scoped {
          for
            firstRoot <- temporaryDirectory("bat-toy-materialize-a-")
            secondRoot <- temporaryDirectory("bat-toy-materialize-b-")
            first <- ToyRepository.materialize(
              fixtureRoot,
              firstRoot.resolve("repository")
            )
            second <- ToyRepository.materialize(
              fixtureRoot,
              secondRoot.resolve("repository")
            )
            baseRouter <- gitText(
              first.repository,
              "show",
              s"${first.baseSha}:$RouterPath"
            )
            headRouter <- gitText(
              first.repository,
              "show",
              s"${first.headSha}:$RouterPath"
            )
            ancestry <- ToyRuntime.git(
              first.repository,
              Seq("merge-base", "--is-ancestor", first.baseSha, first.headSha)
            )
            remotes <- gitText(first.repository, "remote")
            visiblePaths <- actorVisiblePaths(first.repository)
          yield assertTrue(
            first.repository == firstRoot.resolve("repository").toRealPath(),
            first.baseSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"),
            first.headSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"),
            first.baseSha != first.headSha,
            first.baseSha == second.baseSha,
            first.headSha == second.headSha,
            first.toyRevision == second.toyRevision,
            first.toyRevision.matches("[0-9a-f]{64}"),
            ancestry.exitCode == 0,
            baseRouter.contains("route(Message message, boolean scanRequired)"),
            !baseRouter.contains("endsWith(\"@corp.test\")"),
            headRouter.contains("route(Message message)"),
            headRouter.contains("endsWith(\"@corp.test\")"),
            remotes.isBlank,
            visiblePaths.contains("README.md"),
            visiblePaths.exists(_.startsWith("src/main/java/")),
            visiblePaths.exists(_.startsWith("src/test/java/")),
            !visiblePaths.exists(isPrivatePath)
          )
        }
      },
      test(
        "authenticates evaluator and reference assets without exposing them"
      ) {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-revision-")
            copiedFixture = scratch.resolve("fixture")
            _ <- copyTree(fixtureRoot, copiedFixture)
            first <- ToyRepository.materialize(
              copiedFixture,
              scratch.resolve("repository-a")
            )
            _ <- append(
              copiedFixture
                .resolve("oracle")
                .resolve(
                  "src/test/java/dev/bat/examples/ingress/IngressGatewayHiddenTest.java"
                ),
              "\n// evaluator revision canary\n"
            )
            evaluatorChanged <- ToyRepository.materialize(
              copiedFixture,
              scratch.resolve("repository-b")
            )
            _ <- append(
              copiedFixture.resolve("reference/repair.patch"),
              "\n# reference revision canary\n"
            )
            referenceChanged <- ToyRepository.materialize(
              copiedFixture,
              scratch.resolve("repository-c")
            )
          yield assertTrue(
            first.baseSha == evaluatorChanged.baseSha,
            first.headSha == evaluatorChanged.headSha,
            evaluatorChanged.baseSha == referenceChanged.baseSha,
            evaluatorChanged.headSha == referenceChanged.headSha,
            first.toyRevision != evaluatorChanged.toyRevision,
            evaluatorChanged.toyRevision != referenceChanged.toyRevision,
            !Files.exists(evaluatorChanged.repository.resolve("oracle")),
            !Files.exists(evaluatorChanged.repository.resolve("reference")),
            !Files.exists(referenceChanged.repository.resolve("oracle")),
            !Files.exists(referenceChanged.repository.resolve("reference"))
          )
        }
      },
      test("locks the base, target, and repaired evaluator matrix") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-matrix-")
            toy <- ToyRepository.materialize(
              fixtureRoot,
              scratch.resolve("repository")
            )
            _ <- ToyRuntime.git(
              toy.repository,
              Seq("checkout", "--detach", toy.baseSha)
            )
            base <- ToyEvaluator.suiteOutcomes(fixtureRoot, toy.repository)
            _ <- ToyRuntime.git(
              toy.repository,
              Seq("checkout", "--detach", toy.headSha)
            )
            target <- ToyEvaluator.suiteOutcomes(
              fixtureRoot,
              toy.repository
            )
            repair <- ZIO.attemptBlocking(
              Files.readString(
                fixtureRoot.resolve("reference/repair.patch"),
                StandardCharsets.UTF_8
              )
            )
            _ <- ToyRuntime.git(
              toy.repository,
              Seq("apply", "--index", "--whitespace=error-all", "-"),
              Some(repair)
            )
            repaired <- ToyEvaluator.suiteOutcomes(
              fixtureRoot,
              toy.repository
            )
            targetFailure = String(
              target.hiddenRun.combinedOutput,
              StandardCharsets.UTF_8
            )
          yield assertTrue(
            base.publicRun.exitCode == 0,
            base.hiddenRun.exitCode == 0,
            target.publicRun.exitCode == 0,
            target.hiddenRun.exitCode == 1,
            targetFailure.contains(
              "external corporate-looking sender: expected REJECTED but was ACCEPTED"
            ),
            repaired.publicRun.exitCode == 0,
            repaired.hiddenRun.exitCode == 0
          )
        }
      },
      test("refuses to materialize over an occupied destination") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-occupied-")
            destination = scratch.resolve("repository")
            _ <- ZIO.attemptBlocking {
              val _ = Files.createDirectory(destination)
              val _ = Files.writeString(
                destination.resolve("owner-data"),
                "preserve me",
                StandardCharsets.UTF_8
              )
            }
            result <- ToyRepository
              .materialize(fixtureRoot, destination)
              .either
            preserved <- ZIO.attemptBlocking(
              Files.readString(destination.resolve("owner-data"))
            )
          yield assertTrue(
            errorCode(result).contains("invalid_toy_destination"),
            preserved == "preserve me",
            !Files.exists(destination.resolve(".git"))
          )
        }
      },
      test("times out a child that inherits the process pipes") {
        ZIO.scoped {
          for
            cwd <- temporaryDirectory("bat-toy-process-")
            pidFile = cwd.resolve("child.pid")
            started <- Clock.nanoTime
            result <- ToyRuntime
              .command(
                Seq(
                  "/bin/sh",
                  "-c",
                  "sleep 30 & child=$!; printf '%s' \"$child\" > child.pid; sleep 0.1; exit 0"
                ),
                cwd,
                timeout = 300.millis
              )
              .either
            elapsed <- Clock.nanoTime.map(_ - started)
            childPid <- ZIO.attemptBlocking(
              Files.readString(pidFile, StandardCharsets.UTF_8).trim.toLong
            )
            childStopped <- waitUntilStopped(childPid, 2.seconds)
          yield assertTrue(
            errorCode(result).contains("toy_process_timeout"),
            elapsed < 4.seconds.toNanos,
            childStopped
          )
        }
      } @@ TestAspect.withLiveClock,
      test("rejects symlinked output ancestors and checkout descendants") {
        ZIO.scoped {
          for
            scratch <- temporaryDirectory("bat-toy-output-")
            root <- ZIO.attemptBlocking(scratch.toRealPath())
            project = root.resolve("project")
            external = root.resolve("external")
            externalAlias = root.resolve("external-alias")
            checkoutAlias = root.resolve("checkout-alias")
            checkoutParent = project.resolve("existing")
            _ <- ZIO.attemptBlocking {
              val _ = Files.createDirectory(project)
              val _ = Files.createDirectory(external)
              val _ = Files.createDirectory(checkoutParent)
              val _ = Files.createSymbolicLink(externalAlias, external)
              val _ = Files.createSymbolicLink(checkoutAlias, project)
            }
            symlinked <- ToyScenario
              .prepareOutput(externalAlias.resolve("run"), project)
              .either
            canonicalInside <- ToyScenario
              .prepareOutput(
                checkoutAlias.resolve("existing").resolve("run"),
                project
              )
              .either
            inside <- ToyScenario
              .prepareOutput(project.resolve("run"), project)
              .either
          yield assertTrue(
            errorCode(symlinked).contains("invalid_toy_output"),
            errorCode(canonicalInside).contains("invalid_toy_output"),
            errorCode(inside).contains("invalid_toy_output"),
            !Files.exists(external.resolve("run")),
            !Files.exists(checkoutParent.resolve("run")),
            !Files.exists(project.resolve("run"))
          )
        }
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)

  private def fixtureRoot: Path =
    @tailrec
    def find(directory: Path): Path =
      val candidate = directory.resolve("examples/java-six-phase")
      if Files.isRegularFile(candidate.resolve("manifest.json")) then candidate
      else
        val parent = directory.getParent
        if parent == null then
          throw new IllegalStateException("examples/java-six-phase not found")
        find(parent)

    find(Path.of("").toAbsolutePath.normalize)

  private def gitText(
      repository: Path,
      arguments: String*
  ): IO[BatError, String] =
    ToyRuntime
      .git(repository, arguments)
      .map(result => String(result.output, StandardCharsets.UTF_8))

  private def actorVisiblePaths(repository: Path): Task[Set[String]] =
    ZIO.attemptBlocking {
      val stream = Files.walk(repository)
      try
        stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .map(path => slash(repository.relativize(path)))
          .filterNot(path => path == ".git" || path.startsWith(".git/"))
          .toSet
      finally stream.close()
    }

  private def isPrivatePath(path: String): Boolean =
    path == ".bdr" || path.startsWith(".bdr/") ||
      path == "oracle" || path.startsWith("oracle/") ||
      path == "reference" || path.startsWith("reference/")

  private def slash(path: Path): String =
    path.iterator().asScala.map(_.toString).mkString("/")

  private def append(path: Path, value: String): Task[Unit] =
    ZIO.attemptBlocking {
      val _ = Files.writeString(
        path,
        value,
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND
      )
    }

  private def copyTree(source: Path, destination: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val _ = Files.walkFileTree(
        source,
        new SimpleFileVisitor[Path]:
          override def preVisitDirectory(
              directory: Path,
              attributes: BasicFileAttributes
          ): FileVisitResult =
            val relative = source.relativize(directory)
            val _ = Files.createDirectories(destination.resolve(relative))
            FileVisitResult.CONTINUE

          override def visitFile(
              file: Path,
              attributes: BasicFileAttributes
          ): FileVisitResult =
            val _ = Files.copy(
              file,
              destination.resolve(source.relativize(file)),
              StandardCopyOption.COPY_ATTRIBUTES
            )
            FileVisitResult.CONTINUE
      )
    }

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        val stream = Files.walk(path)
        try
          stream
            .iterator()
            .asScala
            .toList
            .sortBy(_.getNameCount)
            .reverse
            .foreach(candidate => {
              val _ = Files.deleteIfExists(candidate)
            })
        finally stream.close()
    }.ignore

  private def errorCode[A](result: Either[BatError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def waitUntilStopped(pid: Long, timeout: Duration): UIO[Boolean] =
    ZIO.attemptBlocking {
      val deadline = java.lang.System.nanoTime() + timeout.toNanos
      var stopped = false
      while !stopped && java.lang.System.nanoTime() < deadline do
        val handle = ProcessHandle.of(pid)
        stopped = !handle.isPresent || !handle.get().isAlive
        if !stopped then Thread.sleep(10L)
      stopped
    }.orDie
