package bat.backend.gptoss

import scala.util.Try

import bat.protocol.*

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

/** Pure Responses wire codec. HTTP, authentication, SSE framing, retry, and
  * cancellation belong to the live transport interpreter.
  */
object ResponsesProtocol:
  private val TurnContextVersion = "bat.turn.v1"
  private val SupportedReasoningEfforts = Set("low", "medium", "high")

  def encode(
      request: ModelRequest[GptOssContext]
  ): Either[BatError, EncodedResponsesRequest] =
    if request == null then violation("GPT-OSS model request must not be null")
    else
      for
        _ <- Either.cond(
          SupportedReasoningEfforts.contains(request.pins.reasoningEffort),
          (),
          protocolError(
            "GPT-OSS reasoning effort must be low, medium, or high"
          )
        )
        replayInput <- encodeInput(request)
        turnContext <- encodeTurnContext(request)
        input = replayInput :+ turnContext
        tools <- traverse(request.tools)(encodeTool)
        historicalCallIds = request.continuation.fold(Set.empty[CallId])(
          _.historicalCallIds
        )
      yield EncodedResponsesRequest(
        identity = request.pins.identity,
        body = obj(
          "model" -> Json.Str(request.pins.identity.modelId),
          "instructions" -> Json.Str(request.developer.text),
          "input" -> Json.Arr(input),
          "tools" -> Json.Arr(tools),
          "tool_choice" -> Json.Str("auto"),
          "parallel_tool_calls" -> Json.Bool(false),
          "reasoning" -> obj(
            "effort" -> Json.Str(request.pins.reasoningEffort)
          ),
          "stream" -> Json.Bool(true),
          "store" -> Json.Bool(false),
          "truncation" -> Json.Str("disabled")
        ),
        inputSeed = input,
        historicalCallIds = historicalCallIds
      )

  private[gptoss] def decodeEvent(
      payload: String,
      limits: ResponsesLimits = ResponsesLimits.default
  ): Either[BatError, ResponsesEvent] =
    if payload == null || limits == null then
      violation("Responses event payload and limits must not be null")
    else
      val text = payload.trim
      if payload.length > limits.maxEventCharacters then
        violation("Responses event exceeds the configured size limit")
      else if text == "[DONE]" then Right(ResponsesEvent.StreamEnd)
      else if text.isEmpty then violation("Responses event payload is empty")
      else
        StrictJson
          .parseObject(text, "Responses event")
          .left
          .map(_ => protocolError("Responses event is not strict JSON"))
          .flatMap(decodeEvent(_, limits))

  private[gptoss] def decodeEvent(
      value: Json,
      limits: ResponsesLimits
  ): Either[BatError, ResponsesEvent] =
    if value == null || limits == null then
      violation("Responses event value and limits must not be null")
    else
      for
        _ <- StrictJson
          .validate(value, "Responses event")
          .left
          .map(_ => protocolError("Responses event is not strict JSON"))
        _ <- Either.cond(
          value.toJson.length <= limits.maxEventCharacters,
          (),
          protocolError("Responses event exceeds the configured size limit")
        )
        event <- value match
          case event: Json.Obj => decodeObject(event, limits)
          case _ => violation("Responses event must be a JSON object")
      yield event

  private[gptoss] def decodeEvent(
      value: Json
  ): Either[BatError, ResponsesEvent] =
    decodeEvent(value, ResponsesLimits.default)

  private def encodeInput(
      request: ModelRequest[GptOssContext]
  ): Either[BatError, Chunk[Json]] =
    request.continuation match
      case None =>
        if request.inputs.exists {
            case InputEvent.ToolOutput(_) => true
            case _                        => false
          }
        then
          violation(
            "GPT-OSS request cannot submit tool output without replay context"
          )
        else traverse(request.inputs)(encodeInputEvent)
      case Some(context) =>
        val outputs = request.inputs.collect {
          case InputEvent.ToolOutput(value) => value
        }
        val hasNonOutputs = outputs.size != request.inputs.size
        val outputIds = outputs.map(_.callId)
        if hasNonOutputs then
          violation(
            "GPT-OSS continuation accepts only function-call outputs"
          )
        else if outputIds != context.pendingCallIds then
          violation(
            "GPT-OSS continuation must answer every replayed call exactly once and in order"
          )
        else
          traverse(outputs)(encodeFunctionOutput).map { encoded =>
            context.historyItems ++ encoded
          }

  private def encodeInputEvent(event: InputEvent): Either[BatError, Json] =
    event match
      case InputEvent.User(value) =>
        Right(
          obj(
            "type" -> Json.Str("message"),
            "role" -> Json.Str("user"),
            "content" -> Json.Arr(
              Chunk(
                obj(
                  "type" -> Json.Str("input_text"),
                  "text" -> Json.Str(value.text)
                )
              )
            )
          )
        )
      case InputEvent.ToolOutput(_) =>
        violation(
          "GPT-OSS request cannot submit tool output without replay context"
        )

  private def encodeFunctionOutput(
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
          "type" -> Json.Str("function_call_output"),
          "call_id" -> Json.Str(output.callId.value),
          "output" -> Json.Str(encoded)
        )
      }

  private def encodeTool(tool: ToolDefinition): Either[BatError, Json] =
    StrictJson.validate(tool.parameters, "tool parameters").map { _ =>
      obj(
        "type" -> Json.Str("function"),
        "name" -> Json.Str(tool.name),
        "description" -> Json.Str(tool.description),
        "parameters" -> tool.parameters,
        "strict" -> Json.Bool(tool.strict)
      )
    }

  /** Keep top-level instructions byte-stable for prompt-cache reuse. Mutable
    * trusted BDR state is appended after replay history as a developer input,
    * so every later request extends the previous request's exact prefix.
    */
  private def encodeTurnContext(
      request: ModelRequest[GptOssContext]
  ): Either[BatError, Json] =
    val state = obj(
      "schema" -> Json.Str(TurnContextVersion),
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
      obj(
        "type" -> Json.Str("message"),
        "role" -> Json.Str("developer"),
        "content" -> Json.Arr(
          Chunk(
            obj(
              "type" -> Json.Str("input_text"),
              "text" -> Json.Str(
                s"<bat_turn_context>\n$encoded\n</bat_turn_context>"
              )
            )
          )
        )
      )
    }

  private def decodeObject(
      event: Json.Obj,
      limits: ResponsesLimits
  ): Either[BatError, ResponsesEvent] =
    requiredString(event, "type", "Responses event").flatMap {
      case "response.created" =>
        for
          sequence <- sequenceField(event)
          response <- requiredObject(event, "response", "response.created")
          id <- requiredNonBlankString(response, "id", "response.created")
          model <- requiredNonBlankString(
            response,
            "model",
            "response.created"
          )
        yield ResponsesEvent.Created(sequence, id, model)
      case eventType @ ("response.queued" | "response.in_progress") =>
        for
          sequence <- sequenceField(event)
          response <- requiredObject(event, "response", eventType)
          id <- requiredNonBlankString(response, "id", eventType)
        yield ResponsesEvent.Progress(
          sequence,
          id,
          eventType.stripPrefix("response.")
        )
      case "response.output_item.added" =>
        for
          sequence <- sequenceField(event)
          outputIndex <- indexField(event, "output_index")
          item <- requiredObject(
            event,
            "item",
            "response.output_item.added"
          )
        yield ResponsesEvent.OutputItemAdded(sequence, outputIndex, item)
      case "response.content_part.added" =>
        for
          sequence <- sequenceField(event)
          itemId <- requiredNonBlankString(
            event,
            "item_id",
            "response.content_part.added"
          )
          outputIndex <- indexField(event, "output_index")
          contentIndex <- indexField(event, "content_index")
          part <- requiredObject(
            event,
            "part",
            "response.content_part.added"
          )
        yield ResponsesEvent.ContentPartAdded(
          sequence,
          itemId,
          outputIndex,
          contentIndex,
          part
        )
      case "response.reasoning_text.delta" =>
        decodeTextFragment(event, "delta").map {
          case (sequence, itemId, outputIndex, contentIndex, text) =>
            ResponsesEvent.ReasoningDelta(
              sequence,
              itemId,
              outputIndex,
              contentIndex,
              text
            )
        }
      case "response.reasoning_text.done" =>
        decodeTextFragment(event, "text").map {
          case (sequence, itemId, outputIndex, contentIndex, text) =>
            ResponsesEvent.ReasoningDone(
              sequence,
              itemId,
              outputIndex,
              contentIndex,
              text
            )
        }
      case "response.output_text.delta" =>
        decodeTextFragment(event, "delta").map {
          case (sequence, itemId, outputIndex, contentIndex, text) =>
            ResponsesEvent.OutputTextDelta(
              sequence,
              itemId,
              outputIndex,
              contentIndex,
              text
            )
        }
      case "response.output_text.done" =>
        decodeTextFragment(event, "text").map {
          case (sequence, itemId, outputIndex, contentIndex, text) =>
            ResponsesEvent.OutputTextDone(
              sequence,
              itemId,
              outputIndex,
              contentIndex,
              text
            )
        }
      case "response.content_part.done" =>
        for
          sequence <- sequenceField(event)
          itemId <- requiredNonBlankString(
            event,
            "item_id",
            "response.content_part.done"
          )
          outputIndex <- indexField(event, "output_index")
          contentIndex <- indexField(event, "content_index")
          part <- requiredObject(
            event,
            "part",
            "response.content_part.done"
          )
        yield ResponsesEvent.ContentPartDone(
          sequence,
          itemId,
          outputIndex,
          contentIndex,
          part
        )
      case "response.function_call_arguments.delta" =>
        for
          sequence <- sequenceField(event)
          itemId <- requiredNonBlankString(
            event,
            "item_id",
            "response.function_call_arguments.delta"
          )
          outputIndex <- indexField(event, "output_index")
          delta <- requiredString(
            event,
            "delta",
            "response.function_call_arguments.delta"
          )
        yield ResponsesEvent.FunctionArgumentsDelta(
          sequence,
          itemId,
          outputIndex,
          delta
        )
      case "response.function_call_arguments.done" =>
        for
          sequence <- sequenceField(event)
          itemId <- requiredNonBlankString(
            event,
            "item_id",
            "response.function_call_arguments.done"
          )
          outputIndex <- indexField(event, "output_index")
          name <- requiredNonBlankString(
            event,
            "name",
            "response.function_call_arguments.done"
          )
          arguments <- requiredString(
            event,
            "arguments",
            "response.function_call_arguments.done"
          )
        yield ResponsesEvent.FunctionArgumentsDone(
          sequence,
          itemId,
          outputIndex,
          name,
          arguments
        )
      case "response.output_item.done" =>
        for
          sequence <- sequenceField(event)
          outputIndex <- indexField(event, "output_index")
          item <- requiredObject(
            event,
            "item",
            "response.output_item.done"
          )
        yield ResponsesEvent.OutputItemDone(sequence, outputIndex, item)
      case "response.completed" => decodeCompleted(event, limits)
      case "response.failed"    =>
        sequenceField(event).map(
          ResponsesEvent.TerminalFailure(_, "gpt_oss_response_failed")
        )
      case "response.incomplete" =>
        sequenceField(event).map(
          ResponsesEvent.TerminalFailure(_, "gpt_oss_response_incomplete")
        )
      case "error" =>
        sequenceField(event).map(
          ResponsesEvent.TerminalFailure(_, "gpt_oss_stream_error")
        )
      case _ => violation("Responses stream contains an unsupported event type")
    }

  private def decodeTextFragment(
      event: Json.Obj,
      textField: String
  ): Either[BatError, (Long, String, Int, Int, String)] =
    for
      sequence <- sequenceField(event)
      itemId <- requiredNonBlankString(event, "item_id", "Responses text event")
      outputIndex <- indexField(event, "output_index")
      contentIndex <- indexField(event, "content_index")
      text <- requiredString(event, textField, "Responses text event")
    yield (sequence, itemId, outputIndex, contentIndex, text)

  private def decodeCompleted(
      event: Json.Obj,
      limits: ResponsesLimits
  ): Either[BatError, ResponsesEvent] =
    for
      sequence <- sequenceField(event)
      response <- requiredObject(event, "response", "response.completed")
      id <- requiredNonBlankString(response, "id", "response.completed")
      model <- requiredNonBlankString(response, "model", "response.completed")
      status <- requiredString(response, "status", "response.completed")
      _ <- Either.cond(
        status == "completed",
        (),
        protocolError("response.completed carries a non-completed status")
      )
      _ <- requireAbsentOrNull(response, "error", "response.completed")
      _ <- requireAbsentOrNull(
        response,
        "incomplete_details",
        "response.completed"
      )
      output <- requiredObjectArray(response, "output", "response.completed")
      _ <- Either.cond(
        output.size <= limits.maxOutputItems,
        (),
        protocolError("Responses output exceeds the configured item limit")
      )
      usageObject <- requiredObject(response, "usage", "response.completed")
      usage <- decodeUsage(usageObject)
    yield ResponsesEvent.Completed(sequence, id, model, output, usage)

  private def decodeUsage(value: Json.Obj): Either[BatError, Usage] =
    for
      total <- requiredNonNegativeLong(value, "total_tokens", "usage")
      input <- requiredNonNegativeLong(value, "input_tokens", "usage")
      output <- requiredNonNegativeLong(value, "output_tokens", "usage")
      _ <- Either.cond(
        input <= Long.MaxValue - output && total == input + output,
        (),
        protocolError(
          "usage.total_tokens must equal input_tokens + output_tokens"
        )
      )
      cached <- optionalNestedNonNegativeLong(
        value,
        "input_tokens_details",
        "cached_tokens",
        "usage"
      )
      reasoning <- optionalNestedNonNegativeLong(
        value,
        "output_tokens_details",
        "reasoning_tokens",
        "usage"
      )
      usage <- Usage.make(
        totalTokens = total,
        inputTokens = Some(input),
        cachedInputTokens = cached,
        outputTokens = Some(output),
        reasoningTokens = reasoning
      )
    yield usage

  private def optionalNestedNonNegativeLong(
      value: Json.Obj,
      objectName: String,
      fieldName: String,
      label: String
  ): Either[BatError, Option[Long]] =
    field(value, objectName) match
      case None | Some(Json.Null) => Right(None)
      case Some(nested: Json.Obj) =>
        field(nested, fieldName) match
          case None | Some(Json.Null) => Right(None)
          case Some(number: Json.Num) =>
            exactLong(number, s"$label.$objectName.$fieldName").flatMap {
              result =>
                Either.cond(
                  result >= 0,
                  Some(result),
                  protocolError(
                    s"$label.$objectName.$fieldName must be non-negative"
                  )
                )
            }
          case _ =>
            violation(s"$label.$objectName.$fieldName must be an integer")
      case _ => violation(s"$label.$objectName must be an object")

  private def requiredObjectArray(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Chunk[Json.Obj]] =
    field(value, name) match
      case Some(Json.Arr(values)) =>
        traverse(values.zipWithIndex) { case (entry, index) =>
          entry match
            case objectValue: Json.Obj => Right(objectValue)
            case _ => violation(s"$label.$name[$index] must be an object")
        }
      case _ => violation(s"$label.$name must be an array")

  private def requiredObject(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Json.Obj] =
    field(value, name) match
      case Some(result: Json.Obj) => Right(result)
      case _ => violation(s"$label.$name must be an object")

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

  private def sequenceField(value: Json.Obj): Either[BatError, Long] =
    requiredNonNegativeLong(value, "sequence_number", "Responses event")

  private def indexField(
      value: Json.Obj,
      name: String
  ): Either[BatError, Int] =
    requiredNonNegativeLong(value, name, "Responses event").flatMap { result =>
      Either.cond(
        result <= Int.MaxValue,
        result.toInt,
        protocolError(s"Responses event.$name is out of range")
      )
    }

  private def exactLong(
      value: Json.Num,
      label: String
  ): Either[BatError, Long] =
    Try(value.value.longValueExact()).toEither.left.map(_ =>
      protocolError(s"$label must be an integer")
    )

  private def requireAbsentOrNull(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Unit] =
    field(value, name) match
      case None | Some(Json.Null) => Right(())
      case _                      => violation(s"$label.$name must be null")

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
