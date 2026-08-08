package bat.worker

import bat.controller.*
import bat.protocol.*

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64

import scala.util.Try

import zio.{Chunk, IO, ZIO}
import zio.json.ast.Json

object WorkerTools:
  def all(session: JavaWorkerSession): Chunk[Tool] =
    Chunk(
      ReadFileTool(session),
      SearchTool(session),
      ApplyPatchTool(session),
      GitStatusTool(session),
      GitDiffTool(session),
      GitCommitTool(session),
      JavaBuildTool(session)
    )

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
      executeReceipt(invocation, session, definition.name) {
        (operationId, expected) =>
          session.gitCommit(
            operationId,
            expected,
            requiredString(invocation, "message")
          )
      }

  private final case class JavaBuildTool(session: JavaWorkerSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.JavaBuild

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      executeReceipt(invocation, session, definition.name) {
        (operationId, expected) =>
          for
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
            result <- session.build(operationId, expected, request)
          yield result
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
        operationId = OperationId.derive(
          session.runId,
          invocation.callId.value,
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

  private def receiptJson(result: OperationResult): Json.Obj =
    val receipt = result.receipt
    val exitCode = receipt.outcome match
      case CommandOutcome.Exited(code) => number(code.toLong)
      case _                           => Json.Null
    Json.Obj(
      Chunk(
        "receipt_id" -> Json.Str(receipt.operationId.value),
        "replayed" -> Json.Bool(result.replayed),
        "outcome" -> Json.Str(outcomeWire(receipt.outcome)),
        "exit_code" -> exitCode,
        "stdout_sha256" -> Json.Str(receipt.stdoutDigest.value),
        "stderr_sha256" -> Json.Str(receipt.stderrDigest.value),
        "stdout_bytes" -> number(receipt.stdoutBytes),
        "stderr_bytes" -> number(receipt.stderrBytes),
        "stdout_preview_base64" -> Json.Str(base64(result.stdout)),
        "stderr_preview_base64" -> Json.Str(base64(result.stderr)),
        "workspace_revision" -> number(receipt.afterRevision.value),
        "workspace_fingerprint" -> Json.Str(receipt.afterFingerprint.value)
      )
    )

  private def outcomeWire(outcome: CommandOutcome): String = outcome match
    case CommandOutcome.Exited(_)   => "exited"
    case CommandOutcome.TimedOut    => "timed_out"
    case CommandOutcome.OutputLimit => "output_limit"
    case CommandOutcome.StartFailed => "start_failed"

  private def base64(bytes: Chunk[Byte]): String =
    Base64.getEncoder.encodeToString(bytes.toArray)

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
      "Run one structured offline Maven or Gradle action in a disposable isolated copy.",
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
