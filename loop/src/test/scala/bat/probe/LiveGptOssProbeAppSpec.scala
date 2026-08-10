package bat.probe

import java.nio.file.{Files, LinkOption, Path}

import scala.jdk.CollectionConverters.*

import bat.worker.{GitInvocation, GitRunner, GitRunnerConfig}

import zio.{Chunk, Scope, UIO, ZIO}
import zio.test.*

object LiveGptOssProbeAppSpec extends ZIOSpecDefault:
  private val GitBinary = Path.of("/usr/bin/git")

  def spec: Spec[TestEnvironment, Any] =
    suite("live GPT-OSS probe checkout provenance")(
      test(
        "resolves the true Git root and rejects a wrong pin or dirty checkout"
      ) {
        ZIO.scoped {
          for
            root <- temporaryDirectory("bat-probe-app-git-")
            nested = root.resolve("nested/directory")
            _ <- ZIO.attemptBlocking {
              val _ = Files.createDirectories(nested)
              val _ = Files.writeString(root.resolve("tracked.txt"), "clean\n")
            }
            runner <- ZIO.fromEither(
              GitRunnerConfig.make(GitBinary).map(GitRunner.live)
            )
            _ <- git(runner, root, Chunk("init", "--quiet"))
            _ <- git(runner, root, Chunk("add", "tracked.txt"))
            _ <- git(
              runner,
              root,
              Chunk(
                "-c",
                "user.name=BAT",
                "-c",
                "user.email=bat@example.invalid",
                "commit",
                "--quiet",
                "-m",
                "fixture"
              )
            )
            headResult <- runner.run(
              GitInvocation(root, Chunk("rev-parse", "--verify", "HEAD"))
            )
            head = headResult.output.linesIterator
              .map(_.trim)
              .find("^[0-9a-f]{40}$".r.matches)
              .get
            expected = unsafe(ProbeBatCommit.from(head))
            resolved <- LiveGptOssProbeApp.resolveBatCheckout(
              nested,
              GitBinary
            )
            matching <- LiveGptOssProbeApp
              .verifyBatCommit(resolved, expected, GitBinary)
              .either
            clean <- LiveGptOssProbeApp
              .verifyCleanCheckout(resolved, GitBinary)
              .either
            wrong = unsafe(
              ProbeBatCommit.from("f" * 40)
            )
            mismatched <- LiveGptOssProbeApp
              .verifyBatCommit(resolved, wrong, GitBinary)
              .either
            _ <- ZIO.attemptBlocking {
              val _ = Files.writeString(root.resolve("tracked.txt"), "dirty\n")
            }
            dirty <- LiveGptOssProbeApp
              .verifyCleanCheckout(resolved, GitBinary)
              .either
          yield assertTrue(
            resolved == root.toRealPath(),
            matching.isRight,
            clean.isRight,
            mismatched.left.exists(
              _.code == "probe_bat_commit_mismatch"
            ),
            dirty.left.exists(_.code == "probe_dirty_checkout")
          )
        }
      }
    ) @@ TestAspect.sequential

  private def git(
      runner: GitRunner,
      cwd: Path,
      arguments: Chunk[String]
  ) =
    runner
      .run(GitInvocation(cwd, arguments))
      .flatMap(result =>
        ZIO
          .fail(new IllegalStateException("trusted Git fixture failed"))
          .unless(result.exitCode == 0)
      )

  private def temporaryDirectory(
      prefix: String
  ): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix).toRealPath())
    )(deleteRecursively)

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
        val stream = Files.walk(path)
        try
          stream
            .iterator()
            .asScala
            .toVector
            .sortBy(_.getNameCount)(using Ordering.Int.reverse)
            .foreach(candidate => {
              val _ = Files.deleteIfExists(candidate)
            })
        finally stream.close()
    }.ignore

  private def unsafe[E, A](value: Either[E, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(String.valueOf(error)),
      identity
    )
