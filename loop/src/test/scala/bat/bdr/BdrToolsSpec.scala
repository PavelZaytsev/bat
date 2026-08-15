package bat.bdr

import bat.controller.*
import bat.protocol.*

import java.nio.file.Path

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object BdrToolsSpec extends ZIOSpecDefault:
  private val EngineCommit =
    "0123456789abcdef0123456789abcdef01234567"
  private val EngineCanary = "PRIVATE_ENGINE_TEXT_7ac9"

  def spec =
    suite("strict BDR tool surface")(
      test("exports a closed strict inventory with separated authority") {
        for
          bdr <- RecordingBdr.make()
          tools = BdrTools.all(bdr)
          registry <- ZIO.fromEither(ToolRegistry.make(tools))
          inventory = tools.map(tool => tool.definition.name -> tool.authority)
          auditNames = registry
            .definitionsFor(RunMode.Audit)
            .map(_.name)
          writerNames = registry
            .definitionsFor(RunMode.FullWriter)
            .map(_.name)
          applySchema = tools
            .find(_.definition.name == "bdr_apply")
            .get
            .definition
            .parameters
        yield assertTrue(
          inventory == Chunk(
            "bdr_audit_summary" -> ToolAuthority.ReadOnly,
            "bdr_apply" -> ToolAuthority.Writer,
            "bdr_completion_check" -> ToolAuthority.ReadOnly
          ),
          registry.allStrict,
          tools.forall(tool => closedObject(tool.definition.parameters)),
          propertyNames(applySchema) == Set("operation_json"),
          requiredNames(applySchema) == Set("operation_json"),
          propertyType(applySchema, "operation_json").contains("string"),
          auditNames == Chunk(
            "bdr_audit_summary",
            "bdr_completion_check"
          ),
          writerNames == Chunk(
            "bdr_audit_summary",
            "bdr_apply",
            "bdr_completion_check"
          )
        )
      },
      test("parses strict operation JSON and delegates the complete object") {
        for
          bdr <- RecordingBdr.make()
          registry <- ZIO.fromEither(
            ToolRegistry.make(BdrTools.all(bdr))
          )
          operationJson =
            """{
              |  "type": "set_run_state",
              |  "state": "ready_for_review",
              |  "actor": "must-be-rejected-by-the-session"
              |}""".stripMargin
          output <- registry.execute(
            call(
              "apply-1",
              "bdr_apply",
              obj("operation_json" -> Json.Str(operationJson))
            ),
            RunMode.FullWriter
          )
          applies <- bdr.applies
          expected <- ZIO.fromEither(
            StrictJson.parseObject(operationJson, "expected operation")
          )
        yield assertTrue(
          !output.isError,
          applies == Chunk(expected),
          stringField(applies.head, "actor").contains(
            "must-be-rejected-by-the-session"
          )
        )
      },
      test("rejects malformed or non-object operation JSON before mutation") {
        for
          bdr <- RecordingBdr.make()
          registry <- ZIO.fromEither(
            ToolRegistry.make(BdrTools.all(bdr))
          )
          malformed <- registry.execute(
            call(
              "apply-malformed",
              "bdr_apply",
              obj(
                "operation_json" -> Json.Str(
                  """{"type":"one","type":"two"}"""
                )
              )
            ),
            RunMode.FullWriter
          )
          nonObject <- registry.execute(
            call(
              "apply-array",
              "bdr_apply",
              obj("operation_json" -> Json.Str("[]"))
            ),
            RunMode.FullWriter
          )
          applies <- bdr.applies
        yield assertTrue(
          malformed.isError,
          errorCode(malformed).contains("invalid_bdr_operation"),
          nonObject.isError,
          errorCode(nonObject).contains("invalid_bdr_operation"),
          applies.isEmpty
        )
      },
      test("delegates both read-only tools") {
        val completion = obj(
          "eligible" -> Json.Bool(true),
          "revision" -> Json.Num(7)
        )
        val audit = Json.Arr(Chunk(obj("revision" -> Json.Num(7))))
        for
          bdr <- RecordingBdr.make(
            auditResult = audit,
            completionResult = completion
          )
          registry <- ZIO.fromEither(
            ToolRegistry.make(BdrTools.all(bdr))
          )
          auditOutput <- registry.execute(
            call("audit-1", "bdr_audit_summary", obj()),
            RunMode.Audit
          )
          completionOutput <- registry.execute(
            call("completion-1", "bdr_completion_check", obj()),
            RunMode.Audit
          )
          auditCalls <- bdr.audits
          completionCalls <- bdr.completions
        yield assertTrue(
          auditOutput.output == audit,
          completionOutput.output == completion,
          auditCalls == 1,
          completionCalls == 1
        )
      },
      test("canonicalizes stale arguments only for zero-argument tools") {
        for
          bdr <- RecordingBdr.make()
          registry <- ZIO.fromEither(ToolRegistry.make(BdrTools.all(bdr)))
          audit <- registry.execute(
            call(
              "audit-stale",
              "bdr_audit_summary",
              obj(
                "text" -> Json.Str("stale"),
                "max_matches" -> Json.Num(10)
              )
            ),
            RunMode.FullWriter
          )
          staleCall = call(
            "apply-stale",
            "bdr_apply",
            obj(
              "operation_json" -> Json.Str("{}"),
              "text" -> Json.Str("must remain rejected")
            )
          )
          validation = registry.validate(staleCall, RunMode.FullWriter)
          nonEmpty <- registry.execute(staleCall, RunMode.FullWriter)
          audits <- bdr.audits
          applies <- bdr.applies
        yield assertTrue(
          !audit.isError,
          audits == 1,
          validation.isLeft,
          nonEmpty.isError,
          errorCode(nonEmpty).contains("invalid_tool_arguments"),
          applies.isEmpty
        )
      },
      test("collapses engine failures to stable codes without leaking text") {
        for
          bdr <- RecordingBdr.make(fail = true)
          registry <- ZIO.fromEither(
            ToolRegistry.make(BdrTools.all(bdr))
          )
          audit <- registry.execute(
            call("audit-failure", "bdr_audit_summary", obj()),
            RunMode.FullWriter
          )
          apply <- registry.execute(
            call(
              "apply-failure",
              "bdr_apply",
              obj(
                "operation_json" -> Json.Str(
                  """{"type":"set_run_state","state":"ready_for_review"}"""
                )
              )
            ),
            RunMode.FullWriter
          )
          completion <- registry.execute(
            call("completion-failure", "bdr_completion_check", obj()),
            RunMode.FullWriter
          )
          encoded = Chunk(audit, apply, completion)
            .map(_.output.toJson)
            .mkString
        yield assertTrue(
          errorCode(audit).contains("bdr_audit_failed"),
          errorCode(apply).contains("bdr_apply_failed"),
          errorCode(completion).contains("bdr_completion_check_failed"),
          Chunk(audit, apply, completion).forall(_.isError),
          !encoded.contains(EngineCanary),
          !encoded.contains("private_engine_failure")
        )
      }
    ) @@ TestAspect.timeout(10.seconds)

  private final class RecordingBdr(
      applyCalls: Ref[Chunk[Json.Obj]],
      auditCalls: Ref[Int],
      completionCalls: Ref[Int],
      auditResult: Json,
      completionResult: Json.Obj,
      fail: Boolean
  ) extends BdrSession:
    val engineCommit: String = EngineCommit
    val actor: String = "bat"

    def applies: UIO[Chunk[Json.Obj]] = applyCalls.get
    def audits: UIO[Int] = auditCalls.get
    def completions: UIO[Int] = completionCalls.get

    def current: UIO[ValidatedBdrState] = ZIO.succeed(State)
    def checkpoint: UIO[ValidatedBdrState] = ZIO.succeed(State)

    def apply(operation: Json.Obj): IO[BatError, Json.Obj] =
      applyCalls.update(_ :+ operation) *>
        failOr(
          obj(
            "revision" -> Json.Num(8),
            "result" -> obj("accepted" -> Json.Bool(true))
          )
        )

    def auditSummary: IO[BatError, Json] =
      auditCalls.update(_ + 1) *> failOr(auditResult)

    def completionCheck: IO[BatError, Json.Obj] =
      completionCalls.update(_ + 1) *> failOr(completionResult)

    private def failOr[A](value: => A): IO[BatError, A] =
      if fail then
        ZIO.fail(
          BatError.BdrFailure("private_engine_failure", EngineCanary)
        )
      else ZIO.succeed(value)

  private object RecordingBdr:
    def make(
        auditResult: Json = Json.Arr(Chunk.empty),
        completionResult: Json.Obj = obj("eligible" -> Json.Bool(false)),
        fail: Boolean = false
    ): UIO[RecordingBdr] =
      for
        applies <- Ref.make(Chunk.empty[Json.Obj])
        audits <- Ref.make(0)
        completions <- Ref.make(0)
      yield new RecordingBdr(
        applies,
        audits,
        completions,
        auditResult,
        completionResult,
        fail
      )

  private val State: ValidatedBdrState =
    val revision = unsafe(Revision.from(7))
    val view = unsafe(
      BdrStateView.make(
        revision,
        "verifying",
        obj("action" -> Json.Str("completion_check_then_mark_ready")),
        "7" * 64
      )
    )
    ValidatedBdrState(
      Path.of("/bdr-tools-test"),
      Path.of(".bdr/progress.yaml"),
      view
    )

  private def call(
      id: String,
      name: String,
      arguments: Json.Obj
  ): FunctionCall =
    unsafe(
      FunctionCall.make(
        unsafe(CallId.from(id)),
        name,
        arguments
      )
    )

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, child) => child }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(text) => text }

  private def errorCode(output: FunctionOutput): Option[String] =
    output.output match
      case value: Json.Obj => stringField(value, "error")
      case _               => None

  private def closedObject(schema: Json.Obj): Boolean =
    field(schema, "type").contains(Json.Str("object")) &&
      field(schema, "additionalProperties").contains(Json.Bool(false))

  private def propertyNames(schema: Json.Obj): Set[String] =
    field(schema, "properties") match
      case Some(value: Json.Obj) => value.fields.map(_._1).toSet
      case _                     => Set.empty

  private def requiredNames(schema: Json.Obj): Set[String] =
    field(schema, "required") match
      case Some(Json.Arr(values)) =>
        values.collect { case Json.Str(name) => name }.toSet
      case _ => Set.empty

  private def propertyType(
      schema: Json.Obj,
      property: String
  ): Option[String] =
    field(schema, "properties")
      .collect { case value: Json.Obj => value }
      .flatMap(child => field(child, property))
      .collect { case value: Json.Obj => value }
      .flatMap(child => stringField(child, "type"))

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      identity
    )
