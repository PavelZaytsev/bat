package bat.worker

import bat.controller.*
import bat.protocol.*

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64

import scala.util.Try

import zio.{Chunk, IO, ZIO}
import zio.json.ast.Json

object WorkerTools:
  def all(session: JavaWorkerSession): Chunk[Tool] =
    Chunk(
      WorkspaceTool(session),
      TargetDiffTool(session),
      ReadFileTool(session),
      SearchTool(session),
      ApplyPatchTool(session),
      GitStatusTool(session),
      GitDiffTool(session),
      GitCommitTool(session),
      JavaBuildTool(session)
    )

  private final case class WorkspaceTool(session: JavaWorkerSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.Workspace

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      worker {
        session.workspaceBootstrap.map { bootstrap =>
          Json.Obj(
            Chunk(
              "base_commit" -> Json.Str(bootstrap.baseCommit.value),
              "starting_head_commit" -> Json.Str(
                bootstrap.startingHeadCommit.value
              ),
              "workspace_revision" -> number(
                bootstrap.workspace.revision.value
              ),
              "workspace_fingerprint" -> Json.Str(
                bootstrap.workspace.fingerprint.value
              )
            )
          )
        }
      }

  private final case class TargetDiffTool(session: JavaWorkerSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.TargetDiff

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      worker {
        for result <- session.targetDiff(
            operationId(invocation, session, definition.name),
            operationId(
              invocation,
              session,
              s"${definition.name}_inventory"
            )
          )
        yield receiptJson(
          result.operation,
          Chunk(
            "base_commit" -> Json.Str(result.baseCommit.value),
            "starting_head_commit" -> Json.Str(
              result.startingHeadCommit.value
            ),
            "changed_paths_complete" -> Json.Bool(true),
            "changed_path_count" -> number(result.changedPaths.length.toLong),
            "changed_paths" -> Json.Arr(
              result.changedPaths.map(path => Json.Str(path.value.toString))
            ),
            "inventory_receipt_id" -> Json.Str(
              result.inventoryOperation.receipt.operationId.value
            )
          )
        )
      }

  private final case class ReadFileTool(session: JavaWorkerSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.ReadFile

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      worker {
        for
          path <- repositoryPath(requiredString(invocation, "path"))
          maxBytes <- inputInt(invocation, "max_bytes")
          content <- session.read(path, maxBytes)
        yield Json.Obj(
          Chunk(
            "path" -> Json.Str(path.value.toString),
            "content" -> Json.Str(content)
          )
        )
      }

  private final case class SearchTool(session: JavaWorkerSession) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.Search

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      worker {
        for
          maxMatches <- inputInt(invocation, "max_matches")
          matches <- session.search(
            requiredString(invocation, "text"),
            maxMatches
          )
        yield Json.Obj(
          Chunk(
            "matches" -> Json.Arr(matches.map { result =>
              Json.Obj(
                Chunk(
                  "path" -> Json.Str(result.path),
                  "line" -> number(result.line.toLong),
                  "column" -> number(result.column.toLong),
                  "preview" -> Json.Str(result.preview)
                )
              )
            })
          )
        )
      }

  private final case class ApplyPatchTool(session: JavaWorkerSession)
      extends Tool:
    val definition: ToolDefinition = Definitions.ApplyPatch

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      executeReceipt(invocation, session, definition.name) {
        (operationId, expected) =>
          session.applyPatch(
            operationId,
            expected,
            requiredString(invocation, "patch")
          )
      }

  private final case class GitStatusTool(session: JavaWorkerSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.GitStatus

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      executeReceipt(invocation, session, definition.name)(session.gitStatus)

  private final case class GitDiffTool(session: JavaWorkerSession) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.GitDiff

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      executeReceipt(invocation, session, definition.name)(session.gitDiff)

  private final case class GitCommitTool(session: JavaWorkerSession)
      extends Tool:
    val definition: ToolDefinition = Definitions.GitCommit

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      worker {
        for
          expected <- expectedWorkspace(invocation)
          result <- session.gitCommitWithHead(
            operationId(invocation, session, definition.name),
            expected,
            requiredString(invocation, "message")
          )
        yield receiptJson(
          result.operation,
          Chunk(
            "head_commit" -> result.headCommit
              .map(value => Json.Str(value.value))
              .getOrElse(Json.Null)
          )
        )
      }

  private final case class JavaBuildTool(session: JavaWorkerSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.JavaBuild

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      worker {
        for
          expected <- expectedWorkspace(invocation)
          action <- ZIO.fromEither(
            JavaBuildAction.values
              .find(_.wire == requiredString(invocation, "action"))
              .toRight(
                WorkerError.InvalidInput(
                  "invalid_java_build_action",
                  "Java build action is not supported"
                )
              )
          )
          selector = requiredString(invocation, "test_selector")
          request <- ZIO.fromEither(
            JavaBuildRequest.make(
              action,
              Option.when(selector.nonEmpty)(selector)
            )
          )
          result <- session.buildWithEvidence(
            operationId(invocation, session, definition.name),
            expected,
            request
          )
          baseline <- session.recordBaselineIfRequired(
            result.operation.receipt
          )
        yield receiptJson(
          result.operation,
          Chunk(
            "baseline_auto_recorded" -> Json.Bool(baseline.nonEmpty),
            "baseline_transition" -> baseline.getOrElse(Json.Null),
            "command_evidence" -> result.commandEvidence
              .getOrElse(Json.Null),
            "command_evidence_unavailable_reason" -> result.commandEvidenceUnavailableReason
              .map(Json.Str(_))
              .getOrElse(Json.Null)
          )
        )
      }

  private def executeReceipt(
      invocation: ToolInvocation,
      session: JavaWorkerSession,
      toolName: String
  )(
      execute: (
          OperationId,
          WorkspacePrecondition
      ) => IO[WorkerError, OperationResult]
  ): IO[ToolError, Json] =
    worker {
      for
        expected <- expectedWorkspace(invocation)
        operationId = WorkerTools.operationId(
          invocation,
          session,
          toolName
        )
        result <- execute(operationId, expected)
      yield receiptJson(result)
    }

  private def expectedWorkspace(
      invocation: ToolInvocation
  ): IO[WorkerError, WorkspacePrecondition] =
    for
      rawRevision <- inputLong(invocation, "workspace_revision")
      revision <- fromEither(WorkspaceRevision.from(rawRevision))
      fingerprint <- fromEither(
        WorkspaceFingerprint.from(
          requiredString(invocation, "workspace_fingerprint")
        )
      )
    yield WorkspacePrecondition(revision, fingerprint)

  private def operationId(
      invocation: ToolInvocation,
      session: JavaWorkerSession,
      toolName: String
  ): OperationId =
    OperationId.derive(
      session.runId,
      session.attemptId,
      invocation.callId.value,
      toolName
    )

  private def receiptJson(
      result: OperationResult,
      additional: Chunk[(String, Json)] = Chunk.empty
  ): Json.Obj =
    val receipt = result.receipt
    val exitCode = receipt.outcome match
      case CommandOutcome.Exited(code) => number(code.toLong)
      case _                           => Json.Null
    val fields =
      Chunk(
        "receipt_id" -> Json.Str(receipt.operationId.value),
        "replayed" -> Json.Bool(result.replayed),
        "outcome" -> Json.Str(outcomeWire(receipt.outcome)),
        "exit_code" -> exitCode,
        "stdout_sha256" -> Json.Str(receipt.stdoutDigest.value),
        "stderr_sha256" -> Json.Str(receipt.stderrDigest.value),
        "stdout_bytes" -> number(receipt.stdoutBytes),
        "stderr_bytes" -> number(receipt.stderrBytes),
        "stdout_preview_truncated" -> Json.Bool(
          receipt.stdoutBytes > receipt.stdoutPreviewBytes.toLong
        ),
        "stderr_preview_truncated" -> Json.Bool(
          receipt.stderrBytes > receipt.stderrPreviewBytes.toLong
        ),
        "stdout_preview" -> utf8(result.stdout),
        "stderr_preview" -> utf8(result.stderr),
        "stdout_preview_base64" -> Json.Str(base64(result.stdout)),
        "stderr_preview_base64" -> Json.Str(base64(result.stderr)),
        "workspace_revision" -> number(receipt.afterRevision.value),
        "workspace_fingerprint" -> Json.Str(receipt.afterFingerprint.value)
      ) ++ additional
    Json.Obj(fields)

  private def outcomeWire(outcome: CommandOutcome): String = outcome match
    case CommandOutcome.Exited(_)   => "exited"
    case CommandOutcome.TimedOut    => "timed_out"
    case CommandOutcome.OutputLimit => "output_limit"
    case CommandOutcome.StartFailed => "start_failed"

  private def base64(bytes: Chunk[Byte]): String =
    Base64.getEncoder.encodeToString(bytes.toArray)

  private def utf8(bytes: Chunk[Byte]): Json =
    Try(
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toArray))
        .toString
    ).toOption.map(Json.Str(_)).getOrElse(Json.Null)

  private def worker[A](effect: IO[WorkerError, A]): IO[ToolError, A] =
    effect.mapError(error =>
      ToolError
        .make(error.code)
        .getOrElse(unsafe(ToolError.make("worker_failure")))
    )

  private def fromEither[A](
      value: Either[WorkerError, A]
  ): IO[WorkerError, A] = ZIO.fromEither(value)

  private def requiredString(
      invocation: ToolInvocation,
      name: String
  ): String =
    invocation.arguments.fields
      .collectFirst { case (`name`, Json.Str(value)) =>
        value
      }
      .getOrElse(
        throw new IllegalStateException(s"validated field missing: $name")
      )

  private def inputInt(
      invocation: ToolInvocation,
      name: String
  ): IO[WorkerError, Int] =
    inputLong(invocation, name).flatMap { value =>
      if value >= Int.MinValue && value <= Int.MaxValue then
        ZIO.succeed(value.toInt)
      else invalidArgument(name)
    }

  private def inputLong(
      invocation: ToolInvocation,
      name: String
  ): IO[WorkerError, Long] =
    invocation.arguments.fields
      .collectFirst { case (`name`, Json.Num(value)) =>
        value
      }
      .flatMap(value => Try(value.longValueExact()).toOption) match
      case Some(value) => ZIO.succeed(value)
      case None        => invalidArgument(name)

  private def repositoryPath(value: String): IO[WorkerError, RepositoryPath] =
    ZIO
      .attempt(Path.of(value))
      .mapError(_ =>
        WorkerError.InvalidInput(
          "invalid_repository_path",
          "repository path is not valid"
        )
      )
      .flatMap(path => fromEither(RepositoryPath.from(path)))

  private def invalidArgument[A](name: String): IO[WorkerError, A] =
    ZIO.fail(
      WorkerError.InvalidInput(
        "invalid_worker_argument",
        s"worker argument $name is missing or out of range"
      )
    )

  private def number(value: Long): Json.Num = Json.Num(BigDecimal(value))

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalStateException(error.safeMessage),
      identity
    )

  private object Definitions:
    val Workspace = definition(
      "worker_workspace",
      "Read trusted pinned commits and the current workspace precondition.",
      properties()
    )

    val TargetDiff = definition(
      "worker_target_diff",
      "Read the bounded immutable diff from the pinned base commit to the starting PR head.",
      properties()
    )

    val ReadFile = definition(
      "worker_read_file",
      "Read one bounded UTF-8 regular file without following symlinks.",
      properties(
        "path" -> stringSchema,
        "max_bytes" -> integerSchema
      )
    )

    val Search = definition(
      "worker_search",
      "Run a deterministic bounded fixed-string repository search.",
      properties(
        "text" -> stringSchema,
        "max_matches" -> integerSchema
      )
    )

    val ApplyPatch = definition(
      "worker_apply_patch",
      "Apply one validated text patch at an exact workspace revision.",
      writerProperties("patch" -> stringSchema)
    )

    val GitStatus = definition(
      "worker_git_status",
      "Read bounded porcelain Git status in the isolated worker.",
      writerProperties()
    )

    val GitDiff = definition(
      "worker_git_diff",
      "Read a bounded binary-safe Git diff in the isolated worker.",
      writerProperties()
    )

    val GitCommit = definition(
      "worker_git_commit",
      "Create one verified local BAT commit without hooks, signing, or push.",
      writerProperties("message" -> stringSchema)
    )

    val JavaBuild = definition(
      "worker_java_build",
      "Run one structured offline Maven, Gradle, or dependency-free javac test action in a disposable isolated copy.",
      writerProperties(
        "action" -> enumSchema(JavaBuildAction.values.map(_.wire)*),
        "test_selector" -> stringSchema
      )
    )

    private def definition(
        name: String,
        description: String,
        schema: Json.Obj
    ): ToolDefinition = unsafe(ToolDefinition.make(name, description, schema))

    private def writerProperties(
        values: (String, Json.Obj)*
    ): Json.Obj =
      properties(
        (
          Chunk.fromIterable(values) ++ Chunk(
            "workspace_revision" -> integerSchema,
            "workspace_fingerprint" -> stringSchema
          )
        )*
      )

    private def properties(values: (String, Json.Obj)*): Json.Obj =
      val fields = Chunk.fromIterable(values)
      Json.Obj(
        Chunk(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(fields),
          "required" -> Json.Arr(
            fields.map { case (name, _) => Json.Str(name) }
          ),
          "additionalProperties" -> Json.Bool(false)
        )
      )

    private def stringSchema =
      Json.Obj(Chunk("type" -> Json.Str("string")))

    private def integerSchema =
      Json.Obj(Chunk("type" -> Json.Str("integer")))

    private def enumSchema(values: String*): Json.Obj =
      Json.Obj(
        Chunk(
          "type" -> Json.Str("string"),
          "enum" -> Json.Arr(Chunk.fromIterable(values).map(Json.Str(_)))
        )
      )
