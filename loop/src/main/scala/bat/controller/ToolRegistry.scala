package bat.controller

import bat.protocol.*

import zio.{Chunk, IO, ZIO}
import zio.json.ast.Json

final case class ToolError private (code: String)

object ToolError:
  private val SafeCode = "^[a-z][a-z0-9_-]{0,63}$".r

  def make(code: String): Either[BatError, ToolError] =
    Option(code).filter(SafeCode.matches) match
      case Some(value) => Right(ToolError(value))
      case None        =>
        Left(
          BatError.ProtocolViolation(
            "tool error code must be 1-64 lowercase machine-code characters"
          )
        )

enum ToolAuthority:
  case ReadOnly
  case Writer

final case class ToolInvocation(callId: CallId, arguments: Json.Obj):
  override def toString: String =
    "ToolInvocation(call_id=<redacted>, arguments=<redacted>)"

trait Tool:
  def definition: ToolDefinition
  def authority: ToolAuthority = ToolAuthority.Writer
  def execute(invocation: ToolInvocation): IO[ToolError, Json]

final class ToolRegistry private (
    ordered: Chunk[Tool],
    byName: Map[String, Tool]
):
  private val allDefinitions: Chunk[ToolDefinition] = ordered.map(_.definition)

  def allStrict: Boolean = allDefinitions.forall(_.strict)

  def definitionsFor(mode: RunMode): Chunk[ToolDefinition] =
    ordered.filter(permitted(_, mode)).map(_.definition)

  def validate(call: FunctionCall, mode: RunMode): Either[BatError, Unit] =
    resolve(call, mode).flatMap {
      case tool if tool.definition.strict =>
        StrictToolSchema.validateValue(
          tool.definition.parameters,
          call.arguments
        )
      case _ => Right(())
    }

  def execute(
      call: FunctionCall,
      mode: RunMode
  ): IO[BatError, FunctionOutput] =
    ZIO.fromEither(validate(call, mode)) *>
      ZIO.fromEither(resolve(call, mode)).flatMap { tool =>
        safelyExecute(
          tool,
          ToolInvocation(call.callId, call.arguments)
        ).flatMap {
          case Left(error) =>
            val safeOutput = Json.Obj(Chunk("error" -> Json.Str(error.code)))
            ZIO.fromEither(
              FunctionOutput.make(call.callId, safeOutput, isError = true)
            )
          case Right(output) =>
            ZIO.fromEither(FunctionOutput.make(call.callId, output))
        }
      }

  private def safelyExecute(
      tool: Tool,
      invocation: ToolInvocation
  ): IO[BatError, Either[ToolError, Json]] =
    ZIO
      .suspendSucceed(tool.execute(invocation))
      .foldCauseZIO(
        cause =>
          if cause.isInterrupted then ZIO.interrupt
          else if cause.defects.nonEmpty then
            ZIO.fail(
              BatError.ToolFailure(
                tool.definition.name,
                "tool_execution_defect"
              )
            )
          else
            cause.failureOption match
              case Some(error) => ZIO.succeed(Left(error))
              case None        =>
                ZIO.fail(
                  BatError.ToolFailure(
                    tool.definition.name,
                    "tool_execution_failed"
                  )
                ),
        output => ZIO.succeed(Right(output))
      )

  private def resolve(
      call: FunctionCall,
      mode: RunMode
  ): Either[BatError, Tool] =
    byName.get(call.name) match
      case None =>
        Left(
          BatError.ProtocolViolation(
            "backend requested an unknown tool"
          )
        )
      case Some(tool) if !permitted(tool, mode) =>
        Left(
          BatError.ProtocolViolation(
            s"backend requested a tool unavailable in ${mode.wire} mode"
          )
        )
      case Some(tool) => Right(tool)

  private def permitted(tool: Tool, mode: RunMode): Boolean =
    mode match
      case RunMode.Audit      => tool.authority == ToolAuthority.ReadOnly
      case RunMode.FullWriter => true

object ToolRegistry:
  def make(tools: Chunk[Tool]): Either[BatError, ToolRegistry] =
    val duplicates = tools
      .groupBy(_.definition.name)
      .collect { case (name, values) if values.size > 1 => name }
      .toList
      .sorted
    if duplicates.nonEmpty then
      Left(
        BatError.ProtocolViolation(
          s"tools are registered more than once: ${duplicates.mkString(", ")}"
        )
      )
    else
      tools
        .foldLeft[Either[BatError, Unit]](Right(())) { (result, tool) =>
          result.flatMap { _ =>
            if tool.definition.strict then
              StrictToolSchema.validateDefinition(tool.definition.parameters)
            else Right(())
          }
        }
        .map(_ =>
          new ToolRegistry(
            tools,
            tools.map(tool => tool.definition.name -> tool).toMap
          )
        )

private object StrictToolSchema:
  private val Allowed = Set(
    "type",
    "description",
    "enum",
    "properties",
    "required",
    "additionalProperties",
    "items"
  )
  private val Types =
    Set("object", "array", "string", "integer", "number", "boolean", "null")

  def validateDefinition(schema: Json.Obj): Either[BatError, Unit] =
    validateShape(schema, "$").flatMap { _ =>
      requiredString(schema, "type", "$").flatMap { kind =>
        if kind == "object" then Right(())
        else schemaError("$", "tool parameters must have object type")
      }
    }

  def validateValue(schema: Json.Obj, value: Json.Obj): Either[BatError, Unit] =
    validateShape(schema, "$").flatMap(_ => validateAt(schema, value, "$"))

  private def validateShape(
      schema: Json.Obj,
      path: String
  ): Either[BatError, Unit] =
    val names = schema.fields.map(_._1).toSet
    val unsupported = names -- Allowed
    if unsupported.nonEmpty then
      schemaError(
        path,
        s"unsupported keywords: ${unsupported.toList.sorted.mkString(", ")}"
      )
    else
      for
        kind <- requiredString(schema, "type", path)
        _ <-
          if Types.contains(kind) then Right(())
          else schemaError(path, "type must be one supported JSON type")
        _ <- optionalEnum(schema, path)
        _ <- kind match
          case "object" => validateObjectShape(schema, path)
          case "array"  => validateArrayShape(schema, path)
          case _        =>
            val illegal = names.intersect(
              Set("properties", "required", "additionalProperties", "items")
            )
            if illegal.isEmpty then Right(())
            else
              schemaError(
                path,
                s"$kind cannot declare ${illegal.toList.sorted.mkString(", ")}"
              )
      yield ()

  private def validateObjectShape(
      schema: Json.Obj,
      path: String
  ): Either[BatError, Unit] =
    for
      properties <- requiredObject(schema, "properties", path)
      required <- requiredNames(schema, path)
      _ <-
        field(schema, "additionalProperties") match
          case Some(Json.Bool(false)) => Right(())
          case _                      =>
            schemaError(path, "objects must set additionalProperties to false")
      declared = properties.fields.map(_._1).toSet
      _ <-
        if required.toSet.subsetOf(declared) then Right(())
        else schemaError(path, "required keys must be declared properties")
      _ <- properties.fields.foldLeft[Either[BatError, Unit]](Right(())) {
        case (result, (name, child: Json.Obj)) =>
          result.flatMap(_ => validateShape(child, s"$path.${escape(name)}"))
        case (_, (name, _)) =>
          schemaError(path, s"property $name must contain an object schema")
      }
    yield ()

  private def validateArrayShape(
      schema: Json.Obj,
      path: String
  ): Either[BatError, Unit] =
    field(schema, "items") match
      case Some(items: Json.Obj) => validateShape(items, s"$path[]")
      case _ => schemaError(path, "array requires an items object")

  private def optionalEnum(
      schema: Json.Obj,
      path: String
  ): Either[BatError, Unit] =
    field(schema, "enum") match
      case None                                      => Right(())
      case Some(Json.Arr(values)) if values.nonEmpty =>
        values.foldLeft[Either[BatError, Unit]](Right(())) { (result, value) =>
          result.flatMap(_ =>
            StrictJson.validate(value, s"strict tool enum at $path")
          )
        }
      case Some(_) => schemaError(path, "enum must be a non-empty array")

  private def validateAt(
      schema: Json.Obj,
      value: Json,
      path: String
  ): Either[BatError, Unit] =
    for
      kind <- requiredString(schema, "type", path)
      _ <- field(schema, "enum") match
        case Some(Json.Arr(values)) if !values.contains(value) =>
          Left(
            BatError.ProtocolViolation(
              s"tool argument $path is not an allowed enum value"
            )
          )
        case _ => Right(())
      _ <- kind match
        case "null" if value == Json.Null               => Right(())
        case "boolean" if value.isInstanceOf[Json.Bool] => Right(())
        case "string" if value.isInstanceOf[Json.Str]   => Right(())
        case "number" if value.isInstanceOf[Json.Num]   => Right(())
        case "integer"                                  =>
          value match
            case Json.Num(number) if number.stripTrailingZeros.scale <= 0 =>
              Right(())
            case _ => argumentTypeError(path, kind)
        case "array" =>
          value match
            case Json.Arr(values) =>
              requiredObject(schema, "items", path).flatMap { itemSchema =>
                values.zipWithIndex.foldLeft[Either[BatError, Unit]](
                  Right(())
                ) { case (result, (item, index)) =>
                  result.flatMap(_ =>
                    validateAt(itemSchema, item, s"$path[$index]")
                  )
                }
              }
            case _ => argumentTypeError(path, kind)
        case "object" =>
          value match
            case obj: Json.Obj => validateObject(schema, obj, path)
            case _             => argumentTypeError(path, kind)
        case _ => argumentTypeError(path, kind)
    yield ()

  private def validateObject(
      schema: Json.Obj,
      value: Json.Obj,
      path: String
  ): Either[BatError, Unit] =
    for
      properties <- requiredObject(schema, "properties", path)
      required <- requiredNames(schema, path)
      supplied = value.fields.map(_._1).toSet
      declared = properties.fields.map(_._1).toSet
      missing = required.toSet -- supplied
      extra = supplied -- declared
      _ <-
        if missing.isEmpty then Right(())
        else
          Left(
            BatError.ProtocolViolation(
              s"tool argument $path is missing: ${missing.toList.sorted.mkString(", ")}"
            )
          )
      _ <-
        if extra.isEmpty then Right(())
        else
          Left(
            BatError.ProtocolViolation(
              s"tool argument $path contains ${extra.size} unexpected key(s)"
            )
          )
      _ <- value.fields.foldLeft[Either[BatError, Unit]](Right(())) {
        case (result, (name, child)) =>
          result.flatMap { _ =>
            field(properties, name) match
              case Some(childSchema: Json.Obj) =>
                validateAt(childSchema, child, s"$path.${escape(name)}")
              case _ =>
                schemaError(path, s"property $name has no object schema")
          }
      }
    yield ()

  private def requiredNames(
      schema: Json.Obj,
      path: String
  ): Either[BatError, List[String]] =
    field(schema, "required") match
      case Some(Json.Arr(values)) =>
        values.toList
          .foldLeft[Either[BatError, List[String]]](Right(Nil)) {
            case (result, Json.Str(name)) if name.nonEmpty =>
              result.map(name :: _)
            case _ =>
              schemaError(path, "required must contain non-empty strings")
          }
          .flatMap { names =>
            if names.distinct.size == names.size then Right(names.reverse)
            else schemaError(path, "required keys must be unique")
          }
      case _ => schemaError(path, "object requires a required array")

  private def requiredObject(
      obj: Json.Obj,
      name: String,
      path: String
  ): Either[BatError, Json.Obj] =
    field(obj, name) match
      case Some(value: Json.Obj) => Right(value)
      case _ => schemaError(path, s"$name must be an object")

  private def requiredString(
      obj: Json.Obj,
      name: String,
      path: String
  ): Either[BatError, String] =
    field(obj, name) match
      case Some(Json.Str(value)) if value.nonEmpty => Right(value)
      case _ => schemaError(path, s"$name must be a string")

  private def field(obj: Json.Obj, name: String): Option[Json] =
    obj.fields.collectFirst { case (`name`, value) => value }

  private def schemaError(
      path: String,
      message: String
  ): Left[BatError, Nothing] =
    Left(BatError.ProtocolViolation(s"strict tool schema at $path: $message"))

  private def argumentTypeError(
      path: String,
      kind: String
  ): Left[BatError, Nothing] =
    Left(
      BatError.ProtocolViolation(
        s"tool argument $path must have JSON type $kind"
      )
    )

  private def escape(name: String): String =
    if name.matches("[A-Za-z_][A-Za-z0-9_]*") then name else s"['$name']"
