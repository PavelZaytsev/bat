package bat.backend.harmonychat

import scala.util.Try

import bat.protocol.*

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Pure Harmony Chat Completions wire codec. HTTP, authentication, SSE framing,
  * retry, and cancellation belong to the live transport interpreter.
  *
  * This dialect exists because a GPT-OSS Chat Completions endpoint can carry
  * the model's raw analysis channel in a first-class `reasoning_content` field
  * on both the response delta and the replayed assistant message. A Responses
  * translation shim that flattens reasoning into ordinary assistant `content`
  * cannot do that, so it is not interchangeable with this dialect.
  */
object HarmonyChatProtocol:
  private val TurnContextVersion = "bat.turn.v1"
  private val SupportedReasoningEfforts = Set("low", "medium", "high")

  def encode(
      request: ModelRequest[HarmonyChatContext]
  ): Either[BatError, EncodedHarmonyChatRequest] =
    if request == null then violation("Harmony Chat request must not be null")
    else
      for
        _ <- Either.cond(
          SupportedReasoningEfforts.contains(request.pins.reasoningEffort),
          (),
          protocolError(
            "Harmony Chat reasoning effort must be low, medium, or high"
          )
        )
        replayMessages <- encodeMessages(request)
        turnContext <- encodeTurnContext(request)
        messages = replayMessages :+ turnContext
        tools <- traverse(request.tools)(encodeTool)
        historicalCallIds = request.continuation.fold(Set.empty[CallId])(
          _.historicalCallIds
        )
      yield EncodedHarmonyChatRequest(
        identity = request.pins.identity,
        body = obj(
          "model" -> Json.Str(request.pins.identity.modelId),
          "messages" -> Json.Arr(messages),
          "tools" -> Json.Arr(tools),
          "tool_choice" -> Json.Str("auto"),
          "parallel_tool_calls" -> Json.Bool(false),
          "reasoning_effort" -> Json.Str(request.pins.reasoningEffort),
          "stream" -> Json.Bool(true)
        ),
        messageSeed = messages,
        historicalCallIds = historicalCallIds
      )

  private[harmonychat] def decodeEvent(
      payload: String,
      limits: HarmonyChatLimits = HarmonyChatLimits.default
  ): Either[BatError, HarmonyChatEvent] =
    if payload == null || limits == null then
      violation("Harmony Chat event payload and limits must not be null")
    else
      val text = payload.trim
      if payload.length > limits.maxEventCharacters then
        violation("Harmony Chat event exceeds the configured size limit")
      else if text == "[DONE]" then Right(HarmonyChatEvent.StreamEnd)
      else if text.isEmpty then violation("Harmony Chat event payload is empty")
      else
        StrictJson
          .parseObject(text, "Harmony Chat event")
          .left
          .map(_ => protocolError("Harmony Chat event is not strict JSON"))
          .flatMap(decodeEvent(_, limits))

  private[harmonychat] def decodeEvent(
      value: Json,
      limits: HarmonyChatLimits
  ): Either[BatError, HarmonyChatEvent] =
    if value == null || limits == null then
      violation("Harmony Chat event value and limits must not be null")
    else
      for
        _ <- StrictJson
          .validate(value, "Harmony Chat event")
          .left
          .map(_ => protocolError("Harmony Chat event is not strict JSON"))
        _ <- Either.cond(
          value.toJson.length <= limits.maxEventCharacters,
          (),
          protocolError("Harmony Chat event exceeds the configured size limit")
        )
        event <- value match
          case event: Json.Obj => decodeObject(event, limits)
          case _ => violation("Harmony Chat event must be a JSON object")
      yield event

  private[harmonychat] def decodeEvent(
      value: Json
  ): Either[BatError, HarmonyChatEvent] =
    decodeEvent(value, HarmonyChatLimits.default)

  private def decodeObject(
      event: Json.Obj,
      limits: HarmonyChatLimits
  ): Either[BatError, HarmonyChatEvent] =
    field(event, "error") match
      case Some(_) => Right(HarmonyChatEvent.TerminalFailure("chat_error"))
      case None    => decodeChunk(event, limits)

  private def decodeChunk(
      event: Json.Obj,
      limits: HarmonyChatLimits
  ): Either[BatError, HarmonyChatEvent] =
    for
      responseId <- requiredNonBlankString(event, "id", "Harmony Chat chunk")
      model <- requiredNonBlankString(event, "model", "Harmony Chat chunk")
      choices <- requiredObjectArray(event, "choices", "Harmony Chat chunk")
      // BAT pins n=1 by never requesting alternatives. More than one choice
      // means the endpoint is not honouring the pinned request shape.
      choice <- Either.cond(
        choices.size == 1,
        choices.head,
        protocolError("Harmony Chat chunk must carry exactly one choice")
      )
      delta <- requiredObject(choice, "delta", "Harmony Chat choice")
      reasoning <- optionalNonEmptyString(
        delta,
        "reasoning_content",
        "Harmony Chat delta"
      )
      content <- optionalNonEmptyString(
        delta,
        "content",
        "Harmony Chat delta"
      )
      toolCalls <- decodeToolCalls(delta, limits)
      finishReason <- optionalNonEmptyString(
        choice,
        "finish_reason",
        "Harmony Chat choice"
      )
      usage <- decodeUsage(event)
    yield HarmonyChatEvent.Delta(
      responseId = responseId,
      model = model,
      reasoning = reasoning,
      content = content,
      toolCalls = toolCalls,
      finishReason = finishReason,
      usage = usage
    )

  private def decodeToolCalls(
      delta: Json.Obj,
      limits: HarmonyChatLimits
  ): Either[BatError, Chunk[RawToolCall]] =
    field(delta, "tool_calls") match
      case None | Some(Json.Null) => Right(Chunk.empty)
      case Some(Json.Arr(values)) =>
        for
          _ <- Either.cond(
            values.nonEmpty,
            (),
            protocolError("Harmony Chat tool_calls must not be empty")
          )
          _ <- Either.cond(
            values.size <= limits.maxToolCalls,
            (),
            protocolError("Harmony Chat tool_calls exceeds the pinned bound")
          )
          decoded <- traverse(values.zipWithIndex) { case (value, position) =>
            value match
              case call: Json.Obj => decodeToolCall(call, position, limits)
              case _              =>
                violation("Harmony Chat tool call must be a JSON object")
          }
        yield decoded
      case _ => violation("Harmony Chat tool_calls must be an array")

  private def decodeToolCall(
      call: Json.Obj,
      position: Int,
      limits: HarmonyChatLimits
  ): Either[BatError, RawToolCall] =
    for
      callType <- requiredNonBlankString(call, "type", "Harmony Chat tool call")
      _ <- Either.cond(
        callType == "function",
        (),
        protocolError("Harmony Chat tool call type must be 'function'")
      )
      id <- requiredNonBlankString(call, "id", "Harmony Chat tool call")
      // A streamed index is authoritative when present, because it orders
      // fragments; without one the array position is the only ordering.
      index <- optionalIndex(call, "index").map(_.getOrElse(position))
      function <- requiredObject(call, "function", "Harmony Chat tool call")
      name <- requiredNonBlankString(
        function,
        "name",
        "Harmony Chat tool function"
      )
      arguments <- requiredString(
        function,
        "arguments",
        "Harmony Chat tool function"
      )
      _ <- Either.cond(
        arguments.length <= limits.maxArgumentsCharacters,
        (),
        protocolError(
          "Harmony Chat tool arguments exceed the configured size limit"
        )
      )
    yield RawToolCall(index, id, name, arguments)

  /** Usage is optional per chunk and mandatory by the end of a turn. An absent
    * object stays absent here; the assembler decides that a turn without usage
    * is nonconformant rather than reporting zero.
    */
  private def decodeUsage(event: Json.Obj): Either[BatError, Option[Usage]] =
    field(event, "usage") match
      case None | Some(Json.Null) => Right(None)
      case Some(usage: Json.Obj)  =>
        for
          total <- requiredNonNegativeLong(
            usage,
            "total_tokens",
            "Harmony Chat usage"
          )
          input <- optionalNonNegativeLong(
            usage,
            "prompt_tokens",
            "Harmony Chat usage"
          )
          output <- optionalNonNegativeLong(
            usage,
            "completion_tokens",
            "Harmony Chat usage"
          )
          cached <- optionalNestedNonNegativeLong(
            usage,
            "prompt_tokens_details",
            "cached_tokens",
            "Harmony Chat usage"
          )
          reasoning <- optionalNestedNonNegativeLong(
            usage,
            "completion_tokens_details",
            "reasoning_tokens",
            "Harmony Chat usage"
          )
          built <- Usage.make(
            totalTokens = total,
            inputTokens = input,
            cachedInputTokens = cached,
            outputTokens = output,
            reasoningTokens = reasoning
          )
        yield Some(built)
      case _ => violation("Harmony Chat usage must be a JSON object")

  private def encodeMessages(
      request: ModelRequest[HarmonyChatContext]
  ): Either[BatError, Chunk[Json]] =
    request.continuation match
      case None =>
        if request.inputs.exists {
            case InputEvent.ToolOutput(_) => true
            case _                        => false
          }
        then
          violation(
            "Harmony Chat request cannot submit tool output without replay context"
          )
        else
          // The developer instructions are a message in this dialect rather
          // than a top-level field, so they enter the replay prefix once and
          // are resent verbatim on every later turn.
          traverse(request.inputs)(encodeInputEvent).map { inputs =>
            developerMessage(request.developer.text) +: inputs
          }
      case Some(context) =>
        val outputs = request.inputs.collect {
          case InputEvent.ToolOutput(value) => value
        }
        val hasNonOutputs = outputs.size != request.inputs.size
        val outputIds = outputs.map(_.callId)
        if hasNonOutputs then
          violation(
            "Harmony Chat continuation accepts only function-call outputs"
          )
        else if outputIds != context.pendingCallIds then
          violation(
            "Harmony Chat continuation must answer every replayed call exactly once and in order"
          )
        else
          traverse(outputs)(encodeToolOutput).map { encoded =>
            context.historyMessages ++ encoded
          }

  private def encodeInputEvent(event: InputEvent): Either[BatError, Json] =
    event match
      case InputEvent.User(value) =>
        Right(
          obj(
            "role" -> Json.Str("user"),
            "content" -> Json.Str(value.text)
          )
        )
      case InputEvent.ToolOutput(_) =>
        violation(
          "Harmony Chat request cannot submit tool output without replay context"
        )

  private def encodeToolOutput(
      output: FunctionOutput
  ): Either[BatError, Json] =
    StrictJson
      .canonical(
        obj(
          "is_error" -> Json.Bool(output.isError),
          "output" -> output.output
        ),
        "function output"
      )
      .map { encoded =>
        obj(
          "role" -> Json.Str("tool"),
          "tool_call_id" -> Json.Str(output.callId.value),
          "content" -> Json.Str(encoded)
        )
      }

  private def encodeTool(tool: ToolDefinition): Either[BatError, Json] =
    StrictJson.validate(tool.parameters, "tool parameters").map { _ =>
      obj(
        "type" -> Json.Str("function"),
        "function" -> obj(
          "name" -> Json.Str(tool.name),
          "description" -> Json.Str(tool.description),
          "parameters" -> tool.parameters,
          "strict" -> Json.Bool(tool.strict)
        )
      )
    }

  /** Mutable trusted BDR state is appended after replay history as a developer
    * message, so every later request extends the previous request's exact
    * prefix and remains prompt-cache friendly.
    */
  private def encodeTurnContext(
      request: ModelRequest[HarmonyChatContext]
  ): Either[BatError, Json] =
    val state = obj(
      "version" -> Json.Str(TurnContextVersion),
      "iteration" -> number(request.iteration.toLong),
      "prompt_version" -> Json.Str(request.pins.promptVersion),
      "bdr_commit" -> Json.Str(request.pins.bdrCommit),
      "bdr_state" -> obj(
        "revision" -> number(request.bdrState.revision.value),
        "run_state" -> Json.Str(request.bdrState.runState),
        "next_action" -> request.bdrState.nextAction,
        "state_digest" -> Json.Str(request.bdrState.stateDigest)
      )
    )
    StrictJson.canonical(state, "BAT turn context").map { encoded =>
      developerMessage(s"<bat_turn_context>\n$encoded\n</bat_turn_context>")
    }

  private def developerMessage(text: String): Json =
    obj(
      "role" -> Json.Str("developer"),
      "content" -> Json.Str(text)
    )

  private def requiredObject(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Json.Obj] =
    field(value, name) match
      case Some(result: Json.Obj) => Right(result)
      case _ => violation(s"$label.$name must be a JSON object")

  private def requiredObjectArray(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Chunk[Json.Obj]] =
    field(value, name) match
      case Some(Json.Arr(values)) =>
        traverse(values) {
          case result: Json.Obj => Right(result)
          case _ => violation(s"$label.$name must contain JSON objects")
        }
      case _ => violation(s"$label.$name must be an array")

  private def requiredString(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, String] =
    field(value, name) match
      case Some(Json.Str(result)) => Right(result)
      case _                      => violation(s"$label.$name must be a string")

  private def requiredNonBlankString(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, String] =
    requiredString(value, name, label).flatMap { result =>
      Either.cond(
        result.trim.nonEmpty,
        result,
        protocolError(s"$label.$name must be non-empty")
      )
    }

  /** An absent field and an explicitly null field mean the same thing on this
    * wire. An empty string is rejected so a stripped value cannot masquerade as
    * a present one.
    */
  private def optionalNonEmptyString(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Option[String]] =
    field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case Some(Json.Str(result)) =>
        Either.cond(
          result.nonEmpty,
          Some(result),
          protocolError(s"$label.$name must not be an empty string")
        )
      case _ => violation(s"$label.$name must be a string")

  private def requiredNonNegativeLong(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Long] =
    field(value, name) match
      case Some(number: Json.Num) =>
        exactLong(number, s"$label.$name").flatMap { result =>
          Either.cond(
            result >= 0,
            result,
            protocolError(s"$label.$name must be non-negative")
          )
        }
      case _ => violation(s"$label.$name must be an integer")

  private def optionalNonNegativeLong(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Option[Long]] =
    field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case _ => requiredNonNegativeLong(value, name, label).map(Some(_))

  private def optionalNestedNonNegativeLong(
      value: Json.Obj,
      outer: String,
      inner: String,
      label: String
  ): Either[BatError, Option[Long]] =
    field(value, outer) match
      case None | Some(Json.Null) => Right(None)
      case Some(nested: Json.Obj) =>
        optionalNonNegativeLong(nested, inner, s"$label.$outer")
      case _ => violation(s"$label.$outer must be a JSON object")

  private def optionalIndex(
      value: Json.Obj,
      name: String
  ): Either[BatError, Option[Int]] =
    field(value, name) match
      case None | Some(Json.Null) => Right(None)
      case _                      =>
        requiredNonNegativeLong(value, name, "Harmony Chat tool call").flatMap {
          result =>
            Either.cond(
              result <= Int.MaxValue,
              Some(result.toInt),
              protocolError("Harmony Chat tool call index is out of range")
            )
        }

  private def exactLong(
      value: Json.Num,
      label: String
  ): Either[BatError, Long] =
    Try(value.value.longValueExact()).toEither.left.map(_ =>
      protocolError(s"$label must be an integer")
    )

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def traverse[A, B](
      values: Iterable[A]
  )(
      f: A => Either[BatError, B]
  ): Either[BatError, Chunk[B]] =
    values.foldLeft[Either[BatError, Chunk[B]]](Right(Chunk.empty)) {
      case (result, value) =>
        for
          collected <- result
          next <- f(value)
        yield collected :+ next
    }

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def protocolError(message: String): BatError =
    BatError.ProtocolViolation(message)

  private def violation[A](message: String): Either[BatError, A] =
    Left(protocolError(message))
