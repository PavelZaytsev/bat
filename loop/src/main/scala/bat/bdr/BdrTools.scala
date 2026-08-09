package bat.bdr

import bat.controller.*
import bat.protocol.*

import zio.{Chunk, IO, ZIO}
import zio.json.ast.Json

/** The strict, provider-neutral tool surface for the BDR state engine.
  *
  * BDR operations cross the model boundary as strict JSON text. The outer
  * function schema therefore stays closed while the BDR engine remains the
  * authority for validating its versioned operation algebra.
  */
object BdrTools:
  def all(session: BdrSession): Chunk[Tool] =
    Chunk(
      AuditSummaryTool(session),
      ApplyTool(session),
      CompletionCheckTool(session)
    )

  private final case class AuditSummaryTool(session: BdrSession) extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.AuditSummary

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      session.auditSummary.mapError(_ => Errors.AuditFailed)

  private final case class ApplyTool(session: BdrSession) extends Tool:
    val definition: ToolDefinition = Definitions.Apply

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      operationJson(invocation)
        .mapError(_ => Errors.InvalidOperation)
        .flatMap(operation =>
          session.apply(operation).mapError(_ => Errors.ApplyFailed)
        )

  private final case class CompletionCheckTool(session: BdrSession)
      extends Tool:
    override val authority: ToolAuthority = ToolAuthority.ReadOnly
    val definition: ToolDefinition = Definitions.CompletionCheck

    def execute(invocation: ToolInvocation): IO[ToolError, Json] =
      session.completionCheck.mapError(_ => Errors.CompletionCheckFailed)

  private def operationJson(
      invocation: ToolInvocation
  ): IO[BatError, Json.Obj] =
    invocation.arguments.fields.collectFirst {
      case ("operation_json", Json.Str(value)) => value
    } match
      case Some(value) =>
        ZIO.fromEither(
          StrictJson.parseObject(value, "BDR operation_json")
        )
      case None =>
        ZIO.fail(
          BatError.ProtocolViolation(
            "BDR operation_json must be a string"
          )
        )

  private object Errors:
    val InvalidOperation: ToolError = toolError("invalid_bdr_operation")
    val AuditFailed: ToolError = toolError("bdr_audit_failed")
    val ApplyFailed: ToolError = toolError("bdr_apply_failed")
    val CompletionCheckFailed: ToolError =
      toolError("bdr_completion_check_failed")

  private object Definitions:
    val AuditSummary: ToolDefinition = definition(
      "bdr_audit_summary",
      "Read the validated BDR audit summary.",
      emptyObjectSchema
    )

    val Apply: ToolDefinition = definition(
      "bdr_apply",
      "Apply one revision-checked BDR operation encoded as strict JSON.",
      objectSchema("operation_json" -> stringSchema)
    )

    val CompletionCheck: ToolDefinition = definition(
      "bdr_completion_check",
      "Check whether the validated BDR run is eligible for completion.",
      emptyObjectSchema
    )

    private def definition(
        name: String,
        description: String,
        parameters: Json.Obj
    ): ToolDefinition =
      ToolDefinition
        .make(name, description, parameters)
        .fold(
          error => throw new IllegalStateException(error.safeMessage),
          identity
        )

    private lazy val emptyObjectSchema: Json.Obj = objectSchema()

    private def objectSchema(fields: (String, Json.Obj)*): Json.Obj =
      val properties = Chunk.fromIterable(fields)
      Json.Obj(
        Chunk(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(properties),
          "required" -> Json.Arr(
            properties.map { case (name, _) => Json.Str(name) }
          ),
          "additionalProperties" -> Json.Bool(false)
        )
      )

    private lazy val stringSchema: Json.Obj =
      Json.Obj(Chunk("type" -> Json.Str("string")))

  private def toolError(code: String): ToolError =
    ToolError
      .make(code)
      .fold(
        error => throw new IllegalStateException(error.safeMessage),
        identity
      )
