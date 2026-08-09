package bat.backend.gptoss

import bat.protocol.*

import zio.Chunk
import zio.json.ast.Json

/** Pure immutable fold over decoded Responses events.
  *
  * A live interpreter creates one assembler per HTTP response and replaces it
  * with the value returned by [[accept]]. Calling [[finish]] after the stream
  * closes rejects every response that did not reach a validated
  * `response.completed` event.
  */
final class ResponsesAssembler private (
    private val identity: BackendIdentity,
    private val request: EncodedResponsesRequest,
    private val limits: ResponsesLimits,
    private val state: ResponsesAssembler.State
):
  import ResponsesAssembler.*

  private[gptoss] def accept(
      event: ResponsesEvent
  ): Either[BatError, ResponsesAssembler] =
    event match
      case ResponsesEvent.StreamEnd =>
        if state.completed.isEmpty then
          violation("Responses stream ended before response.completed")
        else if state.streamEnded then
          violation("Responses stream contains duplicate end markers")
        else Right(copy(state.copy(streamEnded = true)))
      case sequenced =>
        for
          sequence <- sequenced.sequenceNumber.toRight(
            protocolError("Responses event is missing a sequence number")
          )
          advanced <- advanceSequence(sequence)
          next <- advanced.acceptSequenced(sequenced)
        yield next

  def finish: Either[BatError, ModelTurn[GptOssContext]] =
    state.completed match
      case None =>
        violation("Responses stream is incomplete or truncated")
      case Some(completed) =>
        val ordered = orderedItems(state)
        val calls = ordered.flatMap(_.call.toList)
        val messages = ordered.filter(_.kind == OutputItemKind.Message)
        val reasoning = ordered.filter(_.kind == OutputItemKind.Reasoning)
        if calls.isEmpty && messages.size > 1 then
          violation("Responses turn contains multiple final messages")
        else if calls.nonEmpty then
          if reasoning.isEmpty then
            violation("GPT-OSS tool turn is missing replayable reasoning")
          else
            val replayOutput = completed.output.map(value => value: Json)
            val context = new GptOssContext(
              identity,
              request.inputSeed ++ replayOutput,
              calls.map(_.callId),
              state.historicalCallIds
            )
            ModelTurn.toolCalls(context, calls, completed.usage)
        else if messages.nonEmpty then
          val text = messages
            .flatMap(_.parts.toList.sortBy(_._1).map(_._2.text))
            .mkString
          FinalOutput
            .make(text)
            .map(output => ModelTurn.completed(output, completed.usage))
        else
          violation(
            "Responses turn has neither function calls nor final output"
          )

  override def toString: String =
    "ResponsesAssembler(state=<redacted>)"

  private def acceptSequenced(
      event: ResponsesEvent
  ): Either[BatError, ResponsesAssembler] =
    if state.completed.nonEmpty then
      violation("Responses stream contains events after response.completed")
    else
      event match
        case value: ResponsesEvent.Created          => onCreated(value)
        case value: ResponsesEvent.Progress         => onProgress(value)
        case value: ResponsesEvent.OutputItemAdded  => onItemAdded(value)
        case value: ResponsesEvent.ContentPartAdded => onPartAdded(value)
        case value: ResponsesEvent.ReasoningDelta   =>
          onTextDelta(
            value.itemId,
            value.outputIndex,
            value.contentIndex,
            value.delta,
            OutputItemKind.Reasoning,
            limits.maxReasoningCharacters
          )
        case value: ResponsesEvent.ReasoningDone =>
          onTextDone(
            value.itemId,
            value.outputIndex,
            value.contentIndex,
            value.text,
            OutputItemKind.Reasoning
          )
        case value: ResponsesEvent.OutputTextDelta =>
          onTextDelta(
            value.itemId,
            value.outputIndex,
            value.contentIndex,
            value.delta,
            OutputItemKind.Message,
            limits.maxOutputCharacters
          )
        case value: ResponsesEvent.OutputTextDone =>
          onTextDone(
            value.itemId,
            value.outputIndex,
            value.contentIndex,
            value.text,
            OutputItemKind.Message
          )
        case value: ResponsesEvent.ContentPartDone        => onPartDone(value)
        case value: ResponsesEvent.FunctionArgumentsDelta =>
          onArgumentsDelta(value)
        case value: ResponsesEvent.FunctionArgumentsDone =>
          onArgumentsDone(value)
        case value: ResponsesEvent.OutputItemDone  => onItemDone(value)
        case value: ResponsesEvent.Completed       => onCompleted(value)
        case value: ResponsesEvent.TerminalFailure =>
          Left(
            BatError.BackendFailure(
              errorCode = value.code,
              safeMessage = "GPT-OSS response failed",
              retryable = false
            )
          )
        case ResponsesEvent.StreamEnd =>
          violation("Responses stream end marker has invalid ordering")

  private def onCreated(
      event: ResponsesEvent.Created
  ): Either[BatError, ResponsesAssembler] =
    if state.responseId.nonEmpty || state.items.nonEmpty then
      violation("Responses stream contains duplicate response.created")
    else if event.model != identity.modelId then
      violation("Responses model does not match the pinned backend identity")
    else
      Right(
        copy(
          state.copy(
            responseId = Some(event.responseId),
            model = Some(event.model)
          )
        )
      )

  private def onProgress(
      event: ResponsesEvent.Progress
  ): Either[BatError, ResponsesAssembler] =
    for
      _ <- requireStarted
      _ <- requireResponseId(event.responseId)
      _ <- Either.cond(
        state.items.isEmpty,
        (),
        protocolError("Responses progress event arrived after output began")
      )
      _ <- Either.cond(
        event.status == "queued" || event.status == "in_progress",
        (),
        protocolError("Responses progress event has an invalid status")
      )
      _ <- Either.cond(
        !state.progress.contains(event.status),
        (),
        protocolError("Responses stream contains duplicate progress events")
      )
      _ <- Either.cond(
        event.status != "queued" || state.progress.isEmpty,
        (),
        protocolError("Responses progress status regressed to queued")
      )
    yield copy(state.copy(progress = state.progress + event.status))

  private def onItemAdded(
      event: ResponsesEvent.OutputItemAdded
  ): Either[BatError, ResponsesAssembler] =
    for
      _ <- requireStarted
      _ <- Either.cond(
        event.outputIndex == state.items.size,
        (),
        protocolError(
          "Responses output indexes are duplicated or non-contiguous"
        )
      )
      _ <- Either.cond(
        state.items.size < limits.maxOutputItems,
        (),
        protocolError("Responses output exceeds the configured item limit")
      )
      id <- requiredNonBlankString(event.item, "id", "output item")
      _ <- Either.cond(
        !state.itemIds.contains(id),
        (),
        protocolError("Responses output contains duplicate item IDs")
      )
      kind <- decodeItemKind(event.item)
      callCount = state.items.values.count(
        _.kind == OutputItemKind.FunctionCall
      )
      _ <- Either.cond(
        kind != OutputItemKind.FunctionCall ||
          (callCount == 0 && callCount < limits.maxFunctionCalls),
        (),
        protocolError(
          "Responses emitted multiple function calls while parallel calls are disabled"
        )
      )
      item = PartialItem(
        id = id,
        kind = kind,
        added = event.item,
        parts = Map.empty,
        argumentFragments = Chunk.empty,
        argumentsLength = 0L,
        argumentsDone = None,
        rawDone = None,
        call = None
      )
    yield copy(
      state.copy(
        items = state.items.updated(event.outputIndex, item),
        itemIds = state.itemIds + id
      )
    )

  private def onPartAdded(
      event: ResponsesEvent.ContentPartAdded
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItem(event.outputIndex, event.itemId)
      _ <- Either.cond(
        item.kind != OutputItemKind.FunctionCall,
        (),
        protocolError("Function-call output cannot contain text parts")
      )
      _ <- Either.cond(
        event.contentIndex == item.parts.size,
        (),
        protocolError(
          "Responses content indexes are duplicated or non-contiguous"
        )
      )
      partType <- requiredString(event.part, "type", "content part")
      expected = item.kind match
        case OutputItemKind.Reasoning    => "reasoning_text"
        case OutputItemKind.Message      => "output_text"
        case OutputItemKind.FunctionCall => ""
      _ <- Either.cond(
        partType == expected,
        (),
        protocolError("Responses content part does not match its output item")
      )
      initial <- requiredString(event.part, "text", "content part")
      _ <- Either.cond(
        initial.isEmpty,
        (),
        protocolError("Responses content part must begin empty before deltas")
      )
      updated = item.copy(
        parts = item.parts.updated(
          event.contentIndex,
          PartialText(
            fragments = Chunk.empty,
            length = 0L,
            streamDone = false,
            contentDone = false
          )
        )
      )
    yield updateItem(event.outputIndex, updated)

  private def onTextDelta(
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      delta: String,
      expectedKind: OutputItemKind,
      maxCharacters: Int
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItem(outputIndex, itemId)
      _ <- requireKind(item, expectedKind)
      part <- activePart(item, contentIndex)
      _ <- Either.cond(
        !part.streamDone && !part.contentDone,
        (),
        protocolError("Responses text delta arrived after text completion")
      )
      currentTotal = textTotal(expectedKind)
      _ <- boundedAppend(
        currentTotal,
        delta.length,
        maxCharacters,
        "Responses text"
      )
      updatedPart = part.copy(
        fragments = part.fragments :+ delta,
        length = part.length + delta.length.toLong
      )
      updated = item.copy(parts = item.parts.updated(contentIndex, updatedPart))
    yield updateItem(outputIndex, updated)

  private def onTextDone(
      itemId: String,
      outputIndex: Int,
      contentIndex: Int,
      text: String,
      expectedKind: OutputItemKind
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItem(outputIndex, itemId)
      _ <- requireKind(item, expectedKind)
      part <- activePart(item, contentIndex)
      _ <- Either.cond(
        !part.streamDone && !part.contentDone,
        (),
        protocolError("Responses text contains duplicate done events")
      )
      _ <- Either.cond(
        part.text == text,
        (),
        protocolError("Responses text deltas do not match the done event")
      )
      updated = item.copy(
        parts = item.parts.updated(contentIndex, part.copy(streamDone = true))
      )
    yield updateItem(outputIndex, updated)

  private def onPartDone(
      event: ResponsesEvent.ContentPartDone
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItem(event.outputIndex, event.itemId)
      part <- activePart(item, event.contentIndex)
      _ <- Either.cond(
        part.streamDone && !part.contentDone,
        (),
        protocolError("Responses content part has invalid completion ordering")
      )
      expectedType = item.kind match
        case OutputItemKind.Reasoning    => "reasoning_text"
        case OutputItemKind.Message      => "output_text"
        case OutputItemKind.FunctionCall => ""
      actualType <- requiredString(event.part, "type", "content part")
      actualText <- requiredString(event.part, "text", "content part")
      _ <- Either.cond(
        actualType == expectedType && actualText == part.text,
        (),
        protocolError("Responses content part does not match streamed text")
      )
      updated = item.copy(
        parts = item.parts.updated(
          event.contentIndex,
          part.copy(contentDone = true)
        )
      )
    yield updateItem(event.outputIndex, updated)

  private def onArgumentsDelta(
      event: ResponsesEvent.FunctionArgumentsDelta
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItem(event.outputIndex, event.itemId)
      _ <- requireKind(item, OutputItemKind.FunctionCall)
      _ <- Either.cond(
        item.argumentsDone.isEmpty,
        (),
        protocolError("Function arguments delta arrived after completion")
      )
      currentTotal = state.items.values.map(_.argumentsLength).sum
      _ <- boundedAppend(
        currentTotal,
        event.delta.length,
        limits.maxArgumentsCharacters,
        "Function arguments"
      )
      updated = item.copy(
        argumentFragments = item.argumentFragments :+ event.delta,
        argumentsLength = item.argumentsLength + event.delta.length.toLong
      )
    yield updateItem(event.outputIndex, updated)

  private def onArgumentsDone(
      event: ResponsesEvent.FunctionArgumentsDone
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItem(event.outputIndex, event.itemId)
      _ <- requireKind(item, OutputItemKind.FunctionCall)
      _ <- Either.cond(
        item.argumentsDone.isEmpty,
        (),
        protocolError("Function arguments contain duplicate done events")
      )
      _ <- Either.cond(
        item.arguments == event.arguments,
        (),
        protocolError("Function argument deltas do not match the done event")
      )
      _ <- StrictJson
        .parseObject(event.arguments, "function arguments")
        .left
        .map(_ =>
          protocolError("Function arguments are not a strict JSON object")
        )
      updated = item.copy(
        argumentsDone = Some(event.name -> event.arguments)
      )
    yield updateItem(event.outputIndex, updated)

  private def onItemDone(
      event: ResponsesEvent.OutputItemDone
  ): Either[BatError, ResponsesAssembler] =
    for
      item <- activeItemByIndex(event.outputIndex)
      _ <- Either.cond(
        item.rawDone.isEmpty,
        (),
        protocolError("Responses output item contains duplicate done events")
      )
      id <- requiredNonBlankString(event.item, "id", "output item")
      _ <- Either.cond(
        id == item.id,
        (),
        protocolError("Responses output item ID changed while streaming")
      )
      kind <- decodeItemKind(event.item)
      _ <- Either.cond(
        kind == item.kind,
        (),
        protocolError("Responses output item type changed while streaming")
      )
      _ <- validateCompletedStatus(event.item)
      validated <- item.kind match
        case OutputItemKind.Reasoning => validateReasoningItem(item, event.item)
        case OutputItemKind.FunctionCall =>
          validateFunctionCallItem(event.outputIndex, item, event.item)
        case OutputItemKind.Message => validateMessageItem(item, event.item)
      next = validated.copy(rawDone = Some(event.item))
      nextHistoricalIds = next.call.fold(state.historicalCallIds)(call =>
        state.historicalCallIds + call.callId
      )
    yield copy(
      state.copy(
        items = state.items.updated(event.outputIndex, next),
        historicalCallIds = nextHistoricalIds
      )
    )

  private def validateReasoningItem(
      item: PartialItem,
      raw: Json.Obj
  ): Either[BatError, PartialItem] =
    for
      _ <- requireAllPartsDone(item)
      _ <- Either.cond(
        item.parts.nonEmpty && item.parts.values.exists(_.text.trim.nonEmpty),
        (),
        protocolError("GPT-OSS reasoning content is missing or stripped")
      )
      content <- requiredObjectArray(raw, "content", "reasoning output item")
      _ <- validateRawContent(item, content, "reasoning_text")
    yield item

  private def validateFunctionCallItem(
      outputIndex: Int,
      item: PartialItem,
      raw: Json.Obj
  ): Either[BatError, PartialItem] =
    for
      completed <- item.argumentsDone.toRight(
        protocolError(
          "Function-call output item completed before its arguments"
        )
      )
      (doneName, doneArguments) = completed
      name <- requiredNonBlankString(raw, "name", "function-call output item")
      callIdText <- requiredNonBlankString(
        raw,
        "call_id",
        "function-call output item"
      )
      arguments <- requiredString(raw, "arguments", "function-call output item")
      _ <- Either.cond(
        name == doneName && arguments == doneArguments,
        (),
        protocolError("Function-call output item changed after arguments done")
      )
      _ <- validateOptionalStableString(item.added, raw, "name")
      _ <- validateOptionalStableString(item.added, raw, "call_id")
      _ <- validateOptionalStableString(
        item.added,
        raw,
        "arguments",
        allowEmpty = true
      )
      callId <- CallId.from(callIdText)
      _ <- Either.cond(
        !state.historicalCallIds.contains(callId),
        (),
        protocolError("Responses output contains duplicate function call IDs")
      )
      _ <- Either.cond(
        hasCompletedReasoningBefore(outputIndex),
        (),
        protocolError("GPT-OSS function call is missing preceding reasoning")
      )
      parsed <- StrictJson
        .parseObject(arguments, "function arguments")
        .left
        .map(_ =>
          protocolError("Function arguments are not a strict JSON object")
        )
      call <- FunctionCall.make(callId, name, parsed)
    yield item.copy(call = Some(call))

  private def validateMessageItem(
      item: PartialItem,
      raw: Json.Obj
  ): Either[BatError, PartialItem] =
    for
      _ <- requireAllPartsDone(item)
      role <- requiredString(raw, "role", "message output item")
      _ <- Either.cond(
        role == "assistant",
        (),
        protocolError("Responses final message must have assistant role")
      )
      content <- requiredObjectArray(raw, "content", "message output item")
      _ <- validateRawContent(item, content, "output_text")
    yield item

  private def onCompleted(
      event: ResponsesEvent.Completed
  ): Either[BatError, ResponsesAssembler] =
    for
      _ <- requireStarted
      _ <- requireResponseId(event.responseId)
      _ <- Either.cond(
        event.model == identity.modelId,
        (),
        protocolError(
          "Responses model does not match the pinned backend identity"
        )
      )
      _ <- Either.cond(
        state.items.size == event.output.size && state.items.values.forall(
          _.rawDone.nonEmpty
        ),
        (),
        protocolError(
          "response.completed arrived before every output item completed"
        )
      )
      ordered = orderedItems(state)
      _ <- traverse(ordered.zip(event.output)) { case (partial, terminal) =>
        partial.rawDone match
          case Some(done) => sameJson(done, terminal)
          case None       => violation("Responses output item is incomplete")
      }
      completed = CompletedResponse(event.output, event.usage)
    yield copy(state.copy(completed = Some(completed)))

  private def validateRawContent(
      item: PartialItem,
      content: Chunk[Json.Obj],
      expectedType: String
  ): Either[BatError, Unit] =
    val parts = item.parts.toList.sortBy(_._1)
    if parts.size != content.size then
      violation("Responses output item content does not match streamed parts")
    else
      traverse(parts.zip(content)) { case ((_, partial), rawPart) =>
        for
          partType <- requiredString(rawPart, "type", "output content")
          text <- requiredString(rawPart, "text", "output content")
          _ <- Either.cond(
            partType == expectedType && text == partial.text,
            (),
            protocolError("Responses output content changed after streaming")
          )
        yield ()
      }.map(_ => ())

  private def validateOptionalStableString(
      added: Json.Obj,
      done: Json.Obj,
      name: String,
      allowEmpty: Boolean = false
  ): Either[BatError, Unit] =
    field(added, name) match
      case None                                                     => Right(())
      case Some(Json.Str(initial)) if allowEmpty && initial.isEmpty => Right(())
      case Some(Json.Str(initial))                                  =>
        requiredString(done, name, "output item").flatMap { terminal =>
          Either.cond(
            initial == terminal,
            (),
            protocolError(
              "Responses output item metadata changed while streaming"
            )
          )
        }
      case Some(_) =>
        violation("Responses output item metadata has an invalid type")

  private def validateCompletedStatus(
      raw: Json.Obj
  ): Either[BatError, Unit] =
    field(raw, "status") match
      case None | Some(Json.Str("completed")) => Right(())
      case Some(_: Json.Str)                  =>
        violation("Responses output item has a non-completed status")
      case Some(_) => violation("Responses output item status must be a string")

  private def requireAllPartsDone(
      item: PartialItem
  ): Either[BatError, Unit] =
    Either.cond(
      item.parts.nonEmpty && item.parts.values.forall(part =>
        part.streamDone && part.contentDone
      ),
      (),
      protocolError("Responses output item completed before its content")
    )

  private def activeItem(
      outputIndex: Int,
      itemId: String
  ): Either[BatError, PartialItem] =
    activeItemByIndex(outputIndex).flatMap { item =>
      Either.cond(
        item.id == itemId,
        item,
        protocolError("Responses event item ID does not match its output index")
      )
    }

  private def activeItemByIndex(
      outputIndex: Int
  ): Either[BatError, PartialItem] =
    state.items
      .get(outputIndex)
      .toRight(
        protocolError("Responses event references an unknown output item")
      )
      .flatMap { item =>
        Either.cond(
          item.rawDone.isEmpty,
          item,
          protocolError(
            "Responses event references an already-completed output item"
          )
        )
      }

  private def activePart(
      item: PartialItem,
      contentIndex: Int
  ): Either[BatError, PartialText] =
    item.parts
      .get(contentIndex)
      .toRight(
        protocolError("Responses event references an unknown content part")
      )

  private def requireKind(
      item: PartialItem,
      expected: OutputItemKind
  ): Either[BatError, Unit] =
    Either.cond(
      item.kind == expected,
      (),
      protocolError("Responses event does not match its output item type")
    )

  private def requireStarted: Either[BatError, Unit] =
    Either.cond(
      state.responseId.nonEmpty,
      (),
      protocolError("Responses event arrived before response.created")
    )

  private def requireResponseId(value: String): Either[BatError, Unit] =
    Either.cond(
      state.responseId.contains(value),
      (),
      protocolError("Responses response ID changed while streaming")
    )

  private def hasCompletedReasoningBefore(outputIndex: Int): Boolean =
    state.items.exists { case (index, item) =>
      index < outputIndex && item.kind == OutputItemKind.Reasoning &&
      item.rawDone.nonEmpty
    }

  private def textTotal(kind: OutputItemKind): Long =
    state.items.values
      .filter(_.kind == kind)
      .flatMap(_.parts.values)
      .map(_.length)
      .sum

  private def boundedAppend(
      current: Long,
      added: Int,
      maximum: Int,
      label: String
  ): Either[BatError, Unit] =
    Either.cond(
      added >= 0 && current <= maximum.toLong - added.toLong,
      (),
      protocolError(s"$label exceeds the configured size limit")
    )

  private def updateItem(
      outputIndex: Int,
      item: PartialItem
  ): ResponsesAssembler =
    copy(state.copy(items = state.items.updated(outputIndex, item)))

  private def advanceSequence(
      sequence: Long
  ): Either[BatError, ResponsesAssembler] =
    state.lastSequence match
      case None =>
        if state.responseId.isEmpty then
          Right(copy(state.copy(lastSequence = Some(sequence))))
        else violation("Responses sequence state is inconsistent")
      case Some(previous) =>
        if previous != Long.MaxValue && sequence == previous + 1L then
          Right(copy(state.copy(lastSequence = Some(sequence))))
        else
          violation(
            "Responses sequence numbers are duplicated or non-contiguous"
          )

  private def sameJson(left: Json, right: Json): Either[BatError, Unit] =
    for
      leftCanonical <- StrictJson.canonical(left, "Responses output item")
      rightCanonical <- StrictJson.canonical(right, "Responses output item")
      _ <- Either.cond(
        leftCanonical == rightCanonical,
        (),
        protocolError("response.completed output differs from streamed output")
      )
    yield ()

  private def copy(next: State): ResponsesAssembler =
    new ResponsesAssembler(identity, request, limits, next)

object ResponsesAssembler:
  private final case class PartialText(
      fragments: Chunk[String],
      length: Long,
      streamDone: Boolean,
      contentDone: Boolean
  ):
    def text: String = fragments.mkString

  private final case class PartialItem(
      id: String,
      kind: OutputItemKind,
      added: Json.Obj,
      parts: Map[Int, PartialText],
      argumentFragments: Chunk[String],
      argumentsLength: Long,
      argumentsDone: Option[(String, String)],
      rawDone: Option[Json.Obj],
      call: Option[FunctionCall]
  ):
    def arguments: String = argumentFragments.mkString

  private final case class CompletedResponse(
      output: Chunk[Json.Obj],
      usage: Usage
  )

  private final case class State(
      responseId: Option[String],
      model: Option[String],
      progress: Set[String],
      lastSequence: Option[Long],
      items: Map[Int, PartialItem],
      itemIds: Set[String],
      historicalCallIds: Set[CallId],
      completed: Option[CompletedResponse],
      streamEnded: Boolean
  )

  def start(
      identity: BackendIdentity,
      request: EncodedResponsesRequest,
      limits: ResponsesLimits = ResponsesLimits.default
  ): Either[BatError, ResponsesAssembler] =
    if identity == null || request == null || limits == null then
      violation("Responses assembler inputs must not be null")
    else if request.identity != identity then
      violation("Encoded Responses request has a different backend identity")
    else
      Right(
        new ResponsesAssembler(
          identity,
          request,
          limits,
          State(
            responseId = None,
            model = None,
            progress = Set.empty,
            lastSequence = None,
            items = Map.empty,
            itemIds = Set.empty,
            historicalCallIds = request.historicalCallIds,
            completed = None,
            streamEnded = false
          )
        )
      )

  private def orderedItems(state: State): Chunk[PartialItem] =
    Chunk.fromIterable(state.items.toList.sortBy(_._1).map(_._2))

  private def decodeItemKind(
      value: Json.Obj
  ): Either[BatError, OutputItemKind] =
    requiredString(value, "type", "output item").flatMap {
      case "reasoning"     => Right(OutputItemKind.Reasoning)
      case "function_call" => Right(OutputItemKind.FunctionCall)
      case "message"       => Right(OutputItemKind.Message)
      case _ => violation("Responses output contains an unsupported item type")
    }

  private def requiredObjectArray(
      value: Json.Obj,
      name: String,
      label: String
  ): Either[BatError, Chunk[Json.Obj]] =
    field(value, name) match
      case Some(Json.Arr(values)) =>
        traverse(values.zipWithIndex) { case (entry, index) =>
          entry match
            case result: Json.Obj => Right(result)
            case _ => violation(s"$label.$name[$index] must be an object")
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

  private def protocolError(message: String): BatError =
    BatError.ProtocolViolation(message)

  private def violation[A](message: String): Either[BatError, A] =
    Left(protocolError(message))
