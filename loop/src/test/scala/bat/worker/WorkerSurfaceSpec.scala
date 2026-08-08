package bat.worker

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.controller.*
import bat.protocol.*
import bat.worker.oci.*

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.ast.Json
import zio.test.*

object WorkerSurfaceSpec extends ZIOSpecDefault:
  private val Head = "2" * 40
  private val LocalHead = "3" * 40
  private val EmptyDigest = sha256(Array.emptyByteArray)
  private val Pins = pins(Head)
  private val FinalPatch =
    """diff --git a/src/Main.java b/src/Main.java
      |index 1111111..2222222 100644
      |--- a/src/Main.java
      |+++ b/src/Main.java
      |@@ -1 +1 @@
      |-before
      |+after
      |""".stripMargin

  def spec =
    suite("bounded worker tool surface and verified handoff")(
      test("exports only the exact structured tool inventory and authority") {
        ZIO.scoped {
          for
            fixture <- openSession(emptyGit)
            tools = WorkerTools.all(fixture.session)
            inventory = tools.map(tool =>
              tool.definition.name -> tool.authority
            )
            expectedInventory = Chunk(
              "worker_read_file" -> ToolAuthority.ReadOnly,
              "worker_search" -> ToolAuthority.ReadOnly,
              "worker_apply_patch" -> ToolAuthority.Writer,
              "worker_git_status" -> ToolAuthority.ReadOnly,
              "worker_git_diff" -> ToolAuthority.ReadOnly,
              "worker_git_commit" -> ToolAuthority.Writer,
              "worker_java_build" -> ToolAuthority.ReadOnly
            )
            expectedFields = Map(
              "worker_read_file" -> Set("path", "max_bytes"),
              "worker_search" -> Set("text", "max_matches"),
              "worker_apply_patch" -> Set(
                "patch",
                "workspace_revision",
                "workspace_fingerprint"
              ),
              "worker_git_status" -> Set(
                "workspace_revision",
                "workspace_fingerprint"
              ),
              "worker_git_diff" -> Set(
                "workspace_revision",
                "workspace_fingerprint"
              ),
              "worker_git_commit" -> Set(
                "message",
                "workspace_revision",
                "workspace_fingerprint"
              ),
              "worker_java_build" -> Set(
                "action",
                "test_selector",
                "workspace_revision",
                "workspace_fingerprint"
              )
            )
            schemasAreClosed = tools.forall { tool =>
              val schema = tool.definition.parameters
              propertyNames(schema) == expectedFields(tool.definition.name) &&
              requiredNames(schema) == expectedFields(tool.definition.name) &&
              field(schema, "additionalProperties").contains(Json.Bool(false))
            }
            declaredTypes = tools.flatMap { tool =>
              properties(tool.definition.parameters).fields.map {
                case (name, schema: Json.Obj) =>
                  (tool.definition.name -> name) -> stringField(schema, "type")
                case (name, _) =>
                  (tool.definition.name -> name) -> None
              }
            }.toMap
            expectedTypes = expectedFields.flatMap { case (tool, names) =>
              names.map { name =>
                val expected =
                  if name == "max_bytes" || name == "max_matches" ||
                    name == "workspace_revision"
                  then "integer"
                  else "string"
                (tool -> name) -> Some(expected)
              }
            }
            buildActionSchema = properties(
              tools
                .find(_.definition.name == "worker_java_build")
                .get
                .definition
                .parameters
            ).fields.collectFirst { case ("action", schema: Json.Obj) =>
              schema
            }.get
          yield assertTrue(
            inventory == expectedInventory,
            tools.forall(_.definition.strict),
            schemasAreClosed,
            declaredTypes == expectedTypes,
            enumStrings(buildActionSchema) == JavaBuildAction.values
              .map(_.wire)
              .toSet,
            inventory
              .map(_._1)
              .toSet
              .intersect(
                Set(
                  "shell",
                  "exec",
                  "network",
                  "http",
                  "worker_git_push",
                  "git_push",
                  "push"
                )
              )
              .isEmpty
          )
        }
      },
      test("binds operation IDs to call IDs and replays without rerunning") {
        ZIO.scoped {
          for
            sandbox <- RecordingSandbox.make
            fixture <- openSession(emptyGit, sandbox)
            current <- fixture.session.currentWorkspace
            arguments = workspaceArguments(current)
            invocation = functionCall(
              "provider-call-42",
              "worker_git_status",
              arguments
            )
            registry <- fromBat(
              ToolRegistry.make(WorkerTools.all(fixture.session))
            )
            first <- registry.execute(invocation, RunMode.FullWriter)
            replay <- registry.execute(invocation, RunMode.FullWriter)
            requests <- sandbox.requests
            firstJson = objectOutput(first)
            replayJson = objectOutput(replay)
            expectedId = OperationId
              .derive(
                fixture.session.runId,
                "provider-call-42",
                "worker_git_status"
              )
              .value
          yield assertTrue(
            stringField(firstJson, "receipt_id").contains(expectedId),
            boolField(firstJson, "replayed").contains(false),
            stringField(replayJson, "receipt_id").contains(expectedId),
            boolField(replayJson, "replayed").contains(true),
            requests.size == 1,
            requests.head.operationId == expectedId
          )
        }
      },
      test("rejects malformed and traversal inputs without worker effects") {
        ZIO.scoped {
          for
            sandbox <- RecordingSandbox.make
            fixture <- openSession(emptyGit, sandbox)
            registry <- fromBat(
              ToolRegistry.make(WorkerTools.all(fixture.session))
            )
            malformed = functionCall(
              "malformed-status",
              "worker_git_status",
              Json.Obj(
                Chunk("workspace_revision" -> Json.Num(BigDecimal(0)))
              )
            )
            malformedResult <- registry
              .execute(malformed, RunMode.FullWriter)
              .either
            traversal = functionCall(
              "traversal-read",
              "worker_read_file",
              obj(
                "path" -> Json.Str("../outside"),
                "max_bytes" -> Json.Num(BigDecimal(32))
              )
            )
            traversalResult <- registry.execute(
              traversal,
              RunMode.FullWriter
            )
            requests <- sandbox.requests
            traversalJson = objectOutput(traversalResult)
          yield assertTrue(
            malformedResult.left.toOption.exists(
              _.code == "protocol_violation"
            ),
            traversalResult.isError,
            stringField(traversalJson, "error").contains(
              "invalid_repository_path"
            ),
            requests.isEmpty
          )
        }
      },
      test(
        "accepts only clean committed descendant history and persists privately"
      ) {
        ZIO.scoped {
          for
            workspace <- sealedWorkspace("handoff-success")
            runner <- RecordingGit.make(successResponse(FinalPatch))
            result <- createHandoff(workspace, runner)
            calls <- runner.calls
            manifestPath = workspace.controlDirectory.resolve(
              "handoff.manifest"
            )
            artifacts <- ZIO.attemptBlocking {
              val patch = Files.readString(
                result.patchPath,
                StandardCharsets.UTF_8
              )
              val manifest = Files.readString(
                manifestPath,
                StandardCharsets.US_ASCII
              )
              (
                patch,
                manifest,
                Files.size(manifestPath),
                privateFile(result.patchPath),
                privateFile(manifestPath)
              )
            }
            (
              patch,
              manifest,
              manifestBytes,
              patchIsPrivate,
              manifestIsPrivate
            ) =
              artifacts
            manifestLines = manifest.linesIterator.toList
          yield assertTrue(
            result.localHead.value == LocalHead,
            result.patchPath.startsWith(workspace.controlDirectory),
            patch == FinalPatch,
            result.patchBytes == FinalPatch
              .getBytes(StandardCharsets.UTF_8)
              .length,
            result.patchDigest.value == sha256(
              FinalPatch.getBytes(StandardCharsets.UTF_8)
            ),
            manifestLines == List(
              "bat-handoff-v1",
              workspace.runId.value,
              Pins.baseRepository.value,
              Pins.headRepository.value,
              Pins.pullRequestId.value,
              Pins.baseRef.value,
              Pins.baseCommit.value,
              Pins.headRef.value,
              Head,
              LocalHead,
              "0",
              workspace.initialFingerprint.value,
              "7",
              "ready_for_review",
              "f" * 64,
              result.patchDigest.value,
              result.patchBytes.toString
            ),
            manifestBytes < 1024L,
            patchIsPrivate,
            manifestIsPrivate,
            calls.map(_.arguments.head) == Chunk(
              "status",
              "rev-parse",
              "merge-base",
              "diff"
            ),
            calls.forall(!_.arguments.contains("push"))
          )
        }
      },
      test("serializes a started mutation through handoff generation") {
        ZIO.scoped {
          for
            sandbox <- BlockingMutationSandbox.make
            runner <- RecordingGit.make(successResponse(FinalPatch))
            fixture <- openSession(
              runner,
              sandbox,
              terminalBdr
            )
            current <- fixture.session.currentWorkspace
            operationId <- ZIO.fromEither(
              OperationId.from("handoff-race-mutation")
            )
            mutation <- fixture.session
              .applyPatch(operationId, current, FinalPatch)
              .forkScoped
            _ <- sandbox.started.await
            handoffAttempted <- Promise.make[Nothing, Unit]
            blockedHandoff <- (handoffAttempted.succeed(()) *>
              fixture.session.prepareHandoff.either)
              .timeout(1.second)
              .forkScoped
            _ <- handoffAttempted.await
            _ <- TestClock.adjust(1.second)
            handoffWhileMutation <- blockedHandoff.join
            callsWhileMutation <- runner.calls
            artifactsWhileMutation <- ZIO.attemptBlocking {
              !Files.exists(
                fixture.session.workspace.controlDirectory.resolve(
                  "final.patch"
                )
              ) &&
              !Files.exists(
                fixture.session.workspace.controlDirectory.resolve(
                  "handoff.manifest"
                )
              )
            }
            _ <- sandbox.release.succeed(())
            mutationResult <- mutation.join
            currentAfterMutation <- fixture.session.currentWorkspace
            handoff <- fixture.session.prepareHandoff
            finalCalls <- runner.calls
            artifactsAfterHandoff <- ZIO.attemptBlocking {
              Files.isRegularFile(handoff.patchPath) &&
              Files.isRegularFile(
                fixture.session.workspace.controlDirectory.resolve(
                  "handoff.manifest"
                )
              )
            }
          yield assertTrue(
            handoffWhileMutation.isEmpty,
            callsWhileMutation.isEmpty,
            artifactsWhileMutation,
            mutationResult.receipt.afterRevision.value == 1L,
            currentAfterMutation == WorkspacePrecondition(
              mutationResult.receipt.afterRevision,
              mutationResult.receipt.afterFingerprint
            ),
            finalCalls.map(_.arguments.head) == Chunk(
              "status",
              "rev-parse",
              "merge-base",
              "diff"
            ),
            artifactsAfterHandoff
          )
        }
      },
      test("rejects dirty code before reading or exporting history") {
        ZIO.scoped {
          for
            workspace <- sealedWorkspace("handoff-dirty")
            runner <- RecordingGit.make {
              case invocation if invocation.arguments.head == "status" =>
                GitResult(
                  0,
                  "1 .M N... 100644 100644 100644 a b src/Main.java\n"
                )
              case _ => GitResult(0, LocalHead + "\n")
            }
            result <- createHandoff(workspace, runner).either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("dirty_handoff_workspace"),
            calls.size == 1,
            !Files.exists(workspace.controlDirectory.resolve("final.patch")),
            !Files.exists(
              workspace.controlDirectory.resolve("handoff.manifest")
            )
          )
        }
      },
      test("rejects rewritten local history and creates no artifacts") {
        ZIO.scoped {
          for
            workspace <- sealedWorkspace("handoff-rewritten")
            runner <- RecordingGit.make {
              case invocation if invocation.arguments.head == "status" =>
                GitResult(0, "")
              case invocation if invocation.arguments.head == "rev-parse" =>
                GitResult(0, LocalHead + "\n")
              case invocation if invocation.arguments.head == "merge-base" =>
                GitResult(1, "")
              case _ => GitResult(0, FinalPatch)
            }
            result <- createHandoff(workspace, runner).either
          yield assertTrue(
            errorCode(result).contains("handoff_history_rewritten"),
            !Files.exists(workspace.controlDirectory.resolve("final.patch")),
            !Files.exists(
              workspace.controlDirectory.resolve("handoff.manifest")
            )
          )
        }
      },
      test("rejects stale PR pins before invoking Git handoff") {
        ZIO.scoped {
          for
            runner <- RecordingGit.make(successResponse(FinalPatch))
            fixture <- openSession(runner)
            _ <- fixture.authority.current.set(pins("4" * 40))
            result <- fixture.session.prepareHandoff.either
            calls <- runner.calls
          yield assertTrue(
            errorCode(result).contains("stale_pr_input"),
            calls.isEmpty,
            !Files.exists(
              fixture.session.workspace.controlDirectory.resolve("final.patch")
            )
          )
        }
      },
      test("rejects an oversized final patch before creating artifacts") {
        ZIO.scoped {
          for
            workspace <- sealedWorkspace("handoff-oversized")
            oversized = "x" * (4 * 1024 * 1024 + 1)
            runner <- RecordingGit.make(successResponse(oversized))
            result <- createHandoff(workspace, runner).either
          yield assertTrue(
            errorCode(result).contains("handoff_patch_too_large"),
            !Files.exists(workspace.controlDirectory.resolve("final.patch")),
            !Files.exists(
              workspace.controlDirectory.resolve("handoff.manifest")
            )
          )
        }
      }
    ) @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)

  private final case class SessionFixture(
      session: JavaWorkerSession,
      authority: RefAuthority
  )

  private def openSession(
      git: GitRunner,
      sandbox: OciSandbox = RecordingSandbox.discarding,
      bdrFor: Path => BdrSession = fakeBdr
  ): ZIO[Scope, Throwable | WorkerError, SessionFixture] =
    for
      control <- temporaryDirectory("bat-surface-control-")
      workspaces <- temporaryDirectory("bat-surface-workspaces-")
      scratch <- temporaryDirectory("bat-surface-scratch-")
      id <- ZIO.fromEither(
        RunId.from(s"surface-${java.util.UUID.randomUUID()}".take(48))
      )
      allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
      _ <- createSyntheticRepository(allocation.repository)
      workspace <- RunWorkspace.seal(allocation)
      pinRef <- Ref.make(Pins)
      authority = RefAuthority(pinRef)
      bdr = bdrFor(workspace.repository)
      lifecycle = fixedBdr(bdr)
      session <- JavaWorkerSession.resume(
        id,
        authority,
        resumeSafeGit(git),
        sandbox,
        lifecycle,
        runtimeConfig(control, workspaces, scratch)
      )
    yield SessionFixture(session, authority)

  private def sealedWorkspace(
      label: String
  ): ZIO[Scope, Throwable | WorkerError, RunWorkspace] =
    for
      control <- temporaryDirectory(s"bat-$label-control-")
      workspaces <- temporaryDirectory(s"bat-$label-workspaces-")
      id <- ZIO.fromEither(RunId.from(s"$label-run".take(64)))
      allocation <- RunWorkspace.allocate(control, workspaces, id, Pins)
      _ <- createSyntheticRepository(allocation.repository)
      workspace <- RunWorkspace.seal(allocation)
    yield workspace

  private def createHandoff(
      workspace: RunWorkspace,
      runner: GitRunner
  ): IO[WorkerError, VerifiedWorkerResult] =
    VerifiedWorkerResult.create(
      workspace,
      runner,
      WorkspacePrecondition(
        unsafeWorker(WorkspaceRevision.from(0L)),
        workspace.initialFingerprint
      ),
      BdrHandoff(7L, "ready_for_review", "f" * 64),
      ZIO.unit
    )

  private def runtimeConfig(
      control: Path,
      workspaces: Path,
      scratch: Path
  ): WorkerRuntimeConfig =
    val image = unsafeOci(
      PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
    )
    val limits = unsafeOci(
      OciLimits.make(
        10.seconds,
        1024,
        1024,
        8192L,
        128,
        1024L * 1024 * 1024,
        BigDecimal(2),
        64L * 1024 * 1024,
        16L * 1024 * 1024
      )
    )
    val policy = unsafeWorker(
      JavaBuildPolicy.make(
        "java-v1",
        "/opt/bat/bin/mvn",
        "/opt/bat/bin/gradle"
      )
    )
    unsafeWorker(
      WorkerRuntimeConfig.make(
        control.toAbsolutePath.normalize,
        workspaces.toAbsolutePath.normalize,
        scratch.toAbsolutePath.normalize,
        image,
        limits,
        policy,
        unsafeWorker(
          WorkerStorageLimits.make(
            maxSourceBytes = 1024L * 1024 * 1024,
            maxSourcePaths = 100000L,
            maxCheckoutBytes = 1024L * 1024 * 1024,
            maxCheckoutPaths = 100000L,
            maxTreeMetadataBytes = 4 * 1024 * 1024
          )
        )
      )
    )

  private def fixedBdr(session: BdrSession): WorkerBdrLifecycle =
    new WorkerBdrLifecycle:
      def initialize(
          repository: Path,
          pins: PullRequestPins
      ): IO[WorkerError, BdrSession] = ZIO.succeed(session)

      def resume(
          repository: Path,
          pins: PullRequestPins
      ): IO[WorkerError, BdrSession] = ZIO.succeed(session)

  private def fakeBdr(repository: Path): BdrSession =
    fixedStateBdr(
      repository,
      "executing",
      obj("action" -> Json.Str("begin_phase"))
    )

  private def terminalBdr(repository: Path): BdrSession =
    fixedStateBdr(
      repository,
      "ready_for_review",
      obj("action" -> Json.Str("handoff"))
    )

  private def fixedStateBdr(
      repository: Path,
      runState: String,
      nextAction: Json.Obj
  ): BdrSession =
    val revision = unsafeBat(Revision.from(0L))
    val view = unsafeBat(
      BdrStateView.make(
        revision,
        runState,
        nextAction,
        "f" * 64
      )
    )
    val state = ValidatedBdrState(
      repository,
      Path.of(".bdr", "progress.yaml"),
      view
    )
    new BdrSession:
      val engineCommit: String = "e" * 40
      val actor: String = "bat"
      def current: IO[BatError, ValidatedBdrState] = ZIO.succeed(state)
      def checkpoint: IO[BatError, ValidatedBdrState] = ZIO.succeed(state)
      def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
        ZIO.fail(BatError.BdrFailure("unexpected_apply", "unexpected apply"))
      def auditSummary: IO[BatError, Json] =
        ZIO.succeed(Json.Arr(Chunk.empty))

  private final case class RefAuthority(current: Ref[PullRequestPins])
      extends PullRequestAuthority:
    def resolve(
        baseRepository: RepositoryId,
        pullRequestId: PullRequestId
    ): IO[WorkerError, PullRequestPins] = current.get

  private final class BlockingMutationSandbox private (
      val started: Promise[Nothing, Unit],
      val release: Promise[Nothing, Unit]
  ) extends OciSandbox:
    val image: PinnedImage = unsafeOci(
      PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
    )

    def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

    def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
      val empty = OciStreamReceipt(
        0L,
        EmptyDigest,
        Chunk.empty,
        previewTruncated = false
      )
      for
        _ <- started.succeed(())
        _ <- release.await
        _ <- ZIO
          .attemptBlocking {
            val repository = request.mounts
              .find(_.destination.value == "/bat/repository")
              .map(_.source)
              .getOrElse(
                throw new IllegalStateException("repository mount missing")
              )
            val _ = Files.writeString(
              repository.resolve("src").resolve("Main.java"),
              "after\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE
            )
          }
          .mapError(_ =>
            OciFailure.ProcessFailure(
              "synthetic_mutation_failed",
              "synthetic mutation could not update its workspace"
            )
          )
      yield OciRunResult(
        request.operationId,
        OciRunOutcome.Exited(0),
        empty,
        empty,
        0L
      )

  private object BlockingMutationSandbox:
    def make: UIO[BlockingMutationSandbox] =
      for
        started <- Promise.make[Nothing, Unit]
        release <- Promise.make[Nothing, Unit]
      yield BlockingMutationSandbox(started, release)

  private final class RecordingSandbox private (
      ref: Ref[Chunk[OciRunRequest]]
  ) extends OciSandbox:
    val image: PinnedImage = unsafeOci(
      PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
    )

    def requests: UIO[Chunk[OciRunRequest]] = ref.get

    def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

    def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
      val empty = OciStreamReceipt(
        0L,
        EmptyDigest,
        Chunk.empty,
        previewTruncated = false
      )
      ref
        .update(_ :+ request)
        .as(
          OciRunResult(
            request.operationId,
            OciRunOutcome.Exited(0),
            empty,
            empty,
            0L
          )
        )

  private object RecordingSandbox:
    def make: UIO[RecordingSandbox] =
      Ref.make(Chunk.empty[OciRunRequest]).map(new RecordingSandbox(_))

    val discarding: OciSandbox = new OciSandbox:
      val image: PinnedImage = unsafeOci(
        PinnedImage.from("ghcr.io/bat/java-worker@sha256:" + ("a" * 64))
      )

      def cleanup(operationId: String): IO[OciFailure, Unit] = ZIO.unit

      def run(request: OciRunRequest): IO[OciFailure, OciRunResult] =
        ZIO.fail(
          OciFailure.ProcessFailure(
            "unexpected_sandbox",
            "sandbox execution was not expected"
          )
        )

  private final class RecordingGit private (
      ref: Ref[Chunk[GitInvocation]],
      response: GitInvocation => GitResult
  ) extends GitRunner:
    def calls: UIO[Chunk[GitInvocation]] = ref.get

    def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
      ref.update(_ :+ invocation).as(response(invocation))

  private object RecordingGit:
    def make(
        response: GitInvocation => GitResult
    ): UIO[RecordingGit] =
      Ref
        .make(Chunk.empty[GitInvocation])
        .map(
          new RecordingGit(_, response)
        )

  private val emptyGit: GitRunner = new GitRunner:
    def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
      ZIO.fail(
        WorkerError.SourceRejected(
          "unexpected_git",
          "Git execution was not expected"
        )
      )

  private def resumeSafeGit(delegate: GitRunner): GitRunner =
    new GitRunner:
      def run(invocation: GitInvocation): IO[WorkerError, GitResult] =
        if invocation.arguments == GitConfigurationGuard.inspectionArguments
        then ZIO.succeed(GitResult(0, "core.filemode\u0000"))
        else delegate.run(invocation)

  private def successResponse(patch: String): GitInvocation => GitResult =
    invocation =>
      invocation.arguments.head match
        case "status"     => GitResult(0, "")
        case "rev-parse"  => GitResult(0, LocalHead + "\n")
        case "merge-base" => GitResult(0, "")
        case "diff"       => GitResult(0, patch)
        case _            => GitResult(1, "")

  private def workspaceArguments(value: WorkspacePrecondition): Json.Obj =
    obj(
      "workspace_revision" -> Json.Num(BigDecimal(value.revision.value)),
      "workspace_fingerprint" -> Json.Str(value.fingerprint.value)
    )

  private def functionCall(
      id: String,
      name: String,
      arguments: Json.Obj
  ): FunctionCall =
    unsafeBat(
      FunctionCall.make(unsafeBat(CallId.from(id)), name, arguments)
    )

  private def objectOutput(output: FunctionOutput): Json.Obj =
    output.output match
      case value: Json.Obj => value
      case _ => throw new IllegalStateException("expected object output")

  private def propertyNames(schema: Json.Obj): Set[String] =
    properties(schema).fields.map(_._1).toSet

  private def properties(schema: Json.Obj): Json.Obj =
    field(schema, "properties") match
      case Some(value: Json.Obj) => value
      case _                     => Json.Obj(Chunk.empty)

  private def requiredNames(schema: Json.Obj): Set[String] =
    field(schema, "required") match
      case Some(Json.Arr(values)) =>
        values.collect { case Json.Str(name) => name }.toSet
      case _ => Set.empty

  private def enumStrings(schema: Json.Obj): Set[String] =
    field(schema, "enum") match
      case Some(Json.Arr(values)) =>
        values.collect { case Json.Str(value) => value }.toSet
      case _ => Set.empty

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }

  private def boolField(value: Json.Obj, name: String): Option[Boolean] =
    field(value, name).collect { case Json.Bool(result) => result }

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def pins(head: String): PullRequestPins =
    unsafeWorker(
      PullRequestPins.make(
        "R_base",
        "R_head",
        "PR_42",
        "refs/heads/main",
        "1" * 40,
        "refs/pull/42/head",
        head
      )
    )

  private def createSyntheticRepository(repository: Path): Task[Unit] =
    ZIO.attemptBlocking {
      val git = Files.createDirectories(repository.resolve(".git"))
      val src = Files.createDirectories(repository.resolve("src"))
      val _ = Files.writeString(
        git.resolve("HEAD"),
        Head + "\n",
        StandardCharsets.US_ASCII
      )
      val _ = Files.writeString(
        git.resolve("config"),
        "[core]\n\trepositoryformatversion = 0\n",
        StandardCharsets.US_ASCII
      )
      val _ = Files.write(
        git.resolve("index"),
        "synthetic-index-v1".getBytes(StandardCharsets.US_ASCII)
      )
      val _ = Files.writeString(
        src.resolve("Main.java"),
        "before\n",
        StandardCharsets.UTF_8
      )
    }

  private def privateFile(path: Path): Boolean =
    if !Files.isRegularFile(path) then false
    else
      try
        val permissions = Files.getPosixFilePermissions(path).asScala
        permissions
          .intersect(
            Set(
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_WRITE,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_WRITE,
              PosixFilePermission.OTHERS_EXECUTE
            )
          )
          .isEmpty
      catch case _: UnsupportedOperationException => true

  private def errorCode[A](result: Either[WorkerError, A]): Option[String] =
    result.left.toOption.map(_.code)

  private def fromBat[A](value: Either[BatError, A]): IO[BatError, A] =
    ZIO.fromEither(value)

  private def unsafeBat[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def unsafeWorker[A](value: Either[WorkerError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def unsafeOci[A](value: Either[OciFailure, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .iterator
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def temporaryDirectory(prefix: String): ZIO[Scope, Throwable, Path] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(deleteRecursively)

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
