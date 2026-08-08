package bat.worker

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object PrSourceSpec extends ZIOSpecDefault:
  private val BaseCommit = "1" * 40
  private val HeadCommit = "2" * 40
  private val OtherCommit = "3" * 40
  private val BaseRepository = unsafe(RepositoryId.from("R_base", "base"))
  private val PullRequest = unsafe(PullRequestId.from("PR_7"))
  private val Pins = pins(BaseCommit, HeadCommit)

  def spec =
    suite("authenticated and pinned PR source")(
      test("rejects authority metadata for a different PR identity") {
        val wrongRepository = pins(
          BaseCommit,
          HeadCommit,
          baseRepository = "R_other"
        )
        val authority = FixedAuthority(wrongRepository)

        for result <- AuthenticatedPrSource
            .resolve(authority, BaseRepository, PullRequest)
            .either
        yield assertTrue(errorCode(result).contains("pr_identity_mismatch"))
      },
      test("detects base, head, and ref changes as stale") {
        val changedHead = pins(BaseCommit, OtherCommit)
        val changedBaseRef = pins(
          BaseCommit,
          HeadCommit,
          baseRef = "refs/heads/release"
        )

        for
          headResult <- AuthenticatedPrSource
            .requireFresh(FixedAuthority(changedHead), Pins)
            .either
          refResult <- AuthenticatedPrSource
            .requireFresh(FixedAuthority(changedBaseRef), Pins)
            .either
          fresh <- AuthenticatedPrSource
            .requireFresh(FixedAuthority(Pins), Pins)
            .either
        yield assertTrue(
          errorCode(headResult).contains("stale_pr_input"),
          errorCode(refResult).contains("stale_pr_input"),
          fresh == Right(())
        )
      },
      test("uses exact literal Git arguments for a clean pinned source") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            runner <- RecordingGitRunner.make(successResponses)
            result <- PinnedGitSource
              .live(runner)
              .verify(repository, Pins)
              .either
            calls <- runner.calls
            normalized = repository.toAbsolutePath.normalize
          yield assertTrue(
            result == Right(()),
            calls.map(_.cwd).forall(_ == normalized),
            calls.map(_.arguments) == ExpectedCommands
          )
        }
      },
      test("rejects a dirty source before resolving either ref") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            responses = successResponses.updated(
              commandKey(StatusCommand),
              GitResult(0, "1 .M N... 100644 100644 100644 a b src/Main.java\n")
            )
            runner <- RecordingGitRunner.make(responses)
            result <- PinnedGitSource
              .live(runner)
              .verify(repository, Pins)
              .either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("dirty_source_repository"),
            calls.map(_.arguments) == ExpectedCommands.take(5)
          )
        }
      },
      test("rejects executable local Git configuration before status") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            responses = successResponses.updated(
              commandKey(LocalConfigCommand),
              GitResult(0, "filter.host.clean\u0000core.filemode\u0000")
            )
            runner <- RecordingGitRunner.make(responses)
            result <- PinnedGitSource
              .live(runner)
              .verify(repository, Pins)
              .either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("unsafe_git_configuration"),
            calls.map(_.arguments) == Chunk(LocalConfigCommand)
          )
        }
      },
      test("hostile clean-filter configuration is rejected without execution") {
        ZIO.scoped {
          for
            git <- existingGit
            repository <- standaloneRepository
            marker = repository.resolve("filter-executed")
            _ <- initializeRepository(git, repository)
            _ <- ZIO.attemptBlocking {
              val config =
                s"""[core]
                   |\trepositoryformatversion = 0
                   |\tbare = false
                   |[filter \"host\"]
                   |\tclean = /bin/sh -c 'printf owned > ${marker.toString}'
                   |""".stripMargin
              val _ = Files.writeString(
                repository.resolve(".git").resolve("config"),
                config
              )
            }
            config <- ZIO.fromEither(GitRunnerConfig.make(git))
            result <- PinnedGitSource
              .live(GitRunner.live(config))
              .verify(repository, Pins)
              .either
            executed <- ZIO.attemptBlocking(Files.exists(marker))
          yield assertTrue(
            errorCode(result).contains("unsafe_git_configuration"),
            !executed
          )
        }
      },
      test("rejects an upload-pack command hook before provisioning") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            responses = successResponses.updated(
              commandKey(LocalConfigCommand),
              GitResult(
                0,
                "uploadpack.packObjectsHook\u0000core.filemode\u0000"
              )
            )
            runner <- RecordingGitRunner.make(responses)
            result <- PinnedGitSource
              .live(runner)
              .verify(repository, Pins)
              .either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("unsafe_git_configuration"),
            calls.map(_.arguments) == Chunk(LocalConfigCommand)
          )
        }
      },
      test("rejects an exact ref that no longer names the pinned object") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            responses = successResponses.updated(
              commandKey(BaseRefCommand),
              GitResult(0, OtherCommit + "\n")
            )
            runner <- RecordingGitRunner.make(responses)
            result <- PinnedGitSource
              .live(runner)
              .verify(repository, Pins)
              .either
          yield assertTrue(errorCode(result).contains("stale_base_ref"))
        }
      },
      test("distinguishes non-ancestry from Git inspection failure") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            nonAncestor <- RecordingGitRunner.make(
              successResponses.updated(
                commandKey(AncestryCommand),
                GitResult(1, "")
              )
            )
            nonAncestorResult <- PinnedGitSource
              .live(nonAncestor)
              .verify(repository, Pins)
              .either
            broken <- RecordingGitRunner.make(
              successResponses.updated(
                commandKey(AncestryCommand),
                GitResult(128, "fatal: bad object")
              )
            )
            brokenResult <- PinnedGitSource
              .live(broken)
              .verify(repository, Pins)
              .either
          yield assertTrue(
            errorCode(nonAncestorResult).contains("base_not_ancestor"),
            errorCode(brokenResult).contains("git_inspection_failed")
          )
        }
      },
      test("rejects replacement refs and nonempty graft state") {
        ZIO.scoped {
          for
            replacementRepository <- standaloneRepository
            replacementRunner <- RecordingGitRunner.make(
              successResponses.updated(
                commandKey(ReplacementsCommand),
                GitResult(0, OtherCommit + "\n")
              )
            )
            replacement <- PinnedGitSource
              .live(replacementRunner)
              .verify(replacementRepository, Pins)
              .either
            graftRepository <- standaloneRepository
            _ <- ZIO.attemptBlocking {
              val info = graftRepository.resolve(".git").resolve("info")
              val _ = Files.createDirectories(info)
              val _ = Files.writeString(
                info.resolve("grafts"),
                s"$HeadCommit $BaseCommit\n"
              )
            }
            graftRunner <- RecordingGitRunner.make(successResponses)
            graft <- PinnedGitSource
              .live(graftRunner)
              .verify(graftRepository, Pins)
              .either
          yield assertTrue(
            errorCode(replacement).contains("replacement_objects"),
            errorCode(graft).contains("grafted_history")
          )
        }
      },
      test("rejects alternate object stores before invoking Git") {
        ZIO.scoped {
          for
            repository <- standaloneRepository
            _ <- ZIO.attemptBlocking {
              val info = Files.createDirectories(
                repository.resolve(".git").resolve("objects").resolve("info")
              )
              val _ = Files.writeString(
                info.resolve("alternates"),
                "/untrusted/host/object-store\n"
              )
            }
            runner <- RecordingGitRunner.make(successResponses)
            result <- PinnedGitSource
              .live(runner)
              .verify(repository, Pins)
              .either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("invalid_source_repository"),
            calls.isEmpty
          )
        }
      }
    ) @@ TestAspect.sequential

  private val BareCommand = Chunk("rev-parse", "--is-bare-repository")
  private val LocalConfigCommand = Chunk(
    "config",
    "--local",
    "--null",
    "--name-only",
    "--list",
    "--no-includes"
  )
  private val ShallowCommand = Chunk("rev-parse", "--is-shallow-repository")
  private val ReplacementsCommand =
    Chunk("for-each-ref", "--format=%(objectname)", "refs/replace")
  private val StatusCommand =
    Chunk(
      "status",
      "--porcelain=v2",
      "--untracked-files=all",
      "--ignore-submodules=all"
    )
  private val BaseRefCommand =
    Chunk("show-ref", "--verify", "--hash", Pins.baseRef.value)
  private val HeadRefCommand =
    Chunk("show-ref", "--verify", "--hash", Pins.headRef.value)
  private val BaseCommitCommand =
    Chunk("cat-file", "-e", s"${Pins.baseCommit.value}^{commit}")
  private val HeadCommitCommand =
    Chunk("cat-file", "-e", s"${Pins.headCommit.value}^{commit}")
  private val AncestryCommand = Chunk(
    "merge-base",
    "--is-ancestor",
    Pins.baseCommit.value,
    Pins.headCommit.value
  )
  private val ExpectedCommands = Chunk(
    LocalConfigCommand,
    BareCommand,
    ShallowCommand,
    ReplacementsCommand,
    StatusCommand,
    BaseRefCommand,
    HeadRefCommand,
    BaseCommitCommand,
    HeadCommitCommand,
    AncestryCommand
  )

  private val successResponses: Map[String, GitResult] = Map(
    commandKey(LocalConfigCommand) -> GitResult(
      0,
      "core.repositoryformatversion\u0000core.filemode\u0000"
    ),
    commandKey(BareCommand) -> GitResult(0, "false\n"),
    commandKey(ShallowCommand) -> GitResult(0, "false\n"),
    commandKey(ReplacementsCommand) -> GitResult(0, ""),
    commandKey(StatusCommand) -> GitResult(0, ""),
    commandKey(BaseRefCommand) -> GitResult(0, BaseCommit + "\n"),
    commandKey(HeadRefCommand) -> GitResult(0, HeadCommit + "\n"),
    commandKey(BaseCommitCommand) -> GitResult(0, ""),
    commandKey(HeadCommitCommand) -> GitResult(0, ""),
    commandKey(AncestryCommand) -> GitResult(0, "")
  )

  private def pins(
      baseCommit: String,
      headCommit: String,
      baseRepository: String = "R_base",
      headRepository: String = "R_head",
      baseRef: String = "refs/heads/main"
  ): PullRequestPins =
    unsafe(
      PullRequestPins.make(
        baseRepository,
        headRepository,
        "PR_7",
        baseRef,
        baseCommit,
        "refs/pull/7/head",
        headCommit
      )
    )

  private def standaloneRepository: ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking {
        val root = Files.createTempDirectory("bat-pr-source-")
        val git = Files.createDirectories(root.resolve(".git"))
        val _ = Files.writeString(
          git.resolve("config"),
          "[core]\n\trepositoryformatversion = 0\n\tbare = false\n"
        )
        root
      }
    )(deleteRecursively)

  private def existingGit: Task[Path] =
    ZIO.attempt {
      List("/usr/bin/git", "/opt/homebrew/bin/git")
        .map(Path.of(_))
        .find(path => Files.isRegularFile(path) && Files.isExecutable(path))
        .getOrElse(throw new IllegalStateException("Git executable not found"))
    }

  private def initializeRepository(git: Path, repository: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val process = ProcessBuilder(
        git.toString,
        "init",
        "--initial-branch=main",
        repository.toString
      ).start()
      process.getOutputStream.close()
      val code = process.waitFor()
      if code != 0 then throw new IllegalStateException("git init failed")
      ()
    }

  private def commandKey(arguments: Chunk[String]): String =
    arguments.mkString("\u0000")

  private def errorCode[A](value: Either[WorkerError, A]): Option[String] =
    value.left.toOption.map(_.code)

  private def unsafe[A](value: Either[WorkerError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def deleteRecursively(path: Path): UIO[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(path) then
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

  private final case class FixedAuthority(value: PullRequestPins)
      extends PullRequestAuthority:
    def resolve(
        baseRepository: RepositoryId,
        pullRequestId: PullRequestId
    ): IO[WorkerError, PullRequestPins] = ZIO.succeed(value)

  private final class RecordingGitRunner private (
      responses: Map[String, GitResult],
      callsRef: Ref[Chunk[GitInvocation]]
  ) extends GitRunner:
    def calls: UIO[Chunk[GitInvocation]] = callsRef.get

    def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
      callsRef.update(_ :+ invocation) *>
        ZIO
          .fromOption(responses.get(commandKey(invocation.arguments)))
          .orElseFail(
            WorkerError.SourceRejected(
              "unexpected_git_command",
              "test Git runner received an unexpected command"
            )
          )

  private object RecordingGitRunner:
    def make(
        responses: Map[String, GitResult]
    ): UIO[RecordingGitRunner] =
      Ref
        .make(Chunk.empty[GitInvocation])
        .map(
          new RecordingGitRunner(responses, _)
        )
