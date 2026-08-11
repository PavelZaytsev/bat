package bat.backend.harmonychat

import bat.protocol.*

import zio.Chunk
import zio.json.ast.Json

/** Pure immutable fold over decoded Harmony Chat streaming events.
  *
  * A live interpreter creates one assembler per HTTP response and replaces it
  * with the value returned by [[accept]]. Calling [[finish]] after the stream
  * closes rejects every response that did not reach a terminal finish reason
  * with reported usage.
  *
  * Unlike the Responses dialect, this wire never hands back a complete output
  * object. The assistant message that must be replayed on the next tool turn is
  * therefore *reconstructed* from the deltas, which is why the reconstruction
  * rules here are strict: a turn that cannot be replayed exactly is a failed
  * turn, not a best effort.
  */
final class HarmonyChatAssembler private (
    private val identity: BackendIdentity,
    private val request: EncodedHarmonyChatRequest,
    private val limits: HarmonyChatLimits,
    private val state: HarmonyChatAssembler.State
):
  import HarmonyChatAssembler.*

  private[harmonychat] def accept(
      event: HarmonyChatEvent
  ): Either[BatError, HarmonyChatAssembler] =
    event match
      case HarmonyChatEvent.StreamEnd =>
        if state.finishReason.isEmpty then
          violation(
            "Harmony Chat stream ended before a terminal finish reason"
          )
        else if state.streamEnded then
          violation("Harmony Chat stream contains duplicate end markers")
        else Right(copy(state.copy(streamEnded = true)))
      case failure: HarmonyChatEvent.TerminalFailure =>
        Left(
          BatError.BackendFailure(
            errorCode = s"harmony_chat_${failure.code}",
            safeMessage = "Harmony Chat response failed",
            retryable = false
          )
        )
      case delta: HarmonyChatEvent.Delta => onDelta(delta)

  def finish: Either[BatError, ModelTurn[HarmonyChatContext]] =
    for
      finishReason <- state.finishReason.toRight(
        protocolError("Harmony Chat stream is incomplete or truncated")
      )
      // A turn without reported usage cannot be attributed. BAT records an
      // unavailable measurement as absent, never as zero, so the honest
      // outcome here is to reject the turn rather than invent a total.
      usage <- state.usage.toRight(
        protocolError("Harmony Chat turn did not report terminal usage")
      )
      turn <- assemble(finishReason, usage)
    yield turn

  override def toString: String =
    "HarmonyChatAssembler(state=<redacted>)"

  private def assemble(
      finishReason: String,
      usage: Usage
  ): Either[BatError, ModelTurn[HarmonyChatContext]] =
    val calls = state.orderedToolCalls
    if calls.nonEmpty then
      for
        _ <- Either.cond(
          finishReason == "tool_calls",
          (),
          protocolError(
            "Harmony Chat turn emitted tool calls under a non-tool finish reason"
          )
        )
        // Harmony puts the model's plan in the analysis channel. Replaying a
        // tool turn without it silently discards the reasoning the next turn
        // depends on, so an endpoint that strips it is not usable.
        _ <- Either.cond(
          state.reasoning.nonEmpty,
          (),
          protocolError(
            "Harmony Chat tool turn is missing replayable reasoning"
          )
        )
        validated <- validateCalls(calls)
        assistant <- assistantMessage(calls)
        context = new HarmonyChatContext(
          identity,
          request.messageSeed :+ assistant,
          validated.map(_.callId),
          request.historicalCallIds ++ validated.map(_.callId).toSet
        )
        turn <- ModelTurn.toolCalls(context, validated, usage)
      yield turn
    else if state.content.nonEmpty then
      for
        _ <- Either.cond(
          finishReason != "length",
          (),
          protocolError("Harmony Chat response was truncated by a token limit")
        )
        output <- FinalOutput.make(state.content)
      yield ModelTurn.completed(output, usage)
    else
      violation(
        "Harmony Chat turn has neither tool calls nor final output"
      )

  /** Every call identifier must be genuinely new. A reused identifier would let
    * a later tool result be attributed to an earlier call.
    */
  private def validateCalls(
      calls: Chunk[RawToolCall]
  ): Either[BatError, Chunk[FunctionCall]] =
    for
      _ <- Either.cond(
        calls.map(_.id).toSet.size == calls.size,
        (),
        protocolError("Harmony Chat turn contains duplicate tool call ids")
      )
      _ <- Either.cond(
        calls.forall(call =>
          !request.historicalCallIds.exists(_.value == call.id)
        ),
        (),
        protocolError("Harmony Chat turn reused a historical tool call id")
      )
      validated <- traverse(calls)(toFunctionCall)
    yield validated

  private def toFunctionCall(
      call: RawToolCall
  ): Either[BatError, FunctionCall] =
    for
      callId <- CallId.from(call.id)
      arguments <- StrictJson
        .parseObject(call.arguments, "Harmony Chat tool arguments")
        .left
        .map(_ =>
          protocolError(
            "Harmony Chat tool arguments are not a strict JSON object"
          )
        )
      function <- FunctionCall.make(callId, call.name, arguments)
    yield function

  /** The exact assistant message to replay. `reasoning_content` carries the raw
    * analysis channel; a dialect that cannot round-trip this field is a
    * different dialect, not a configuration of this one.
    */
  private def assistantMessage(
      calls: Chunk[RawToolCall]
  ): Either[BatError, Json] =
    val toolCalls = calls.map { call =>
      obj(
        "id" -> Json.Str(call.id),
        "type" -> Json.Str("function"),
        "function" -> obj(
          "name" -> Json.Str(call.name),
          "arguments" -> Json.Str(call.arguments)
        )
      ): Json
    }
    Right(
      obj(
        "role" -> Json.Str("assistant"),
        "content" -> Json.Str(state.content),
        "reasoning_content" -> Json.Str(state.reasoning),
        "tool_calls" -> Json.Arr(toolCalls)
      )
    )

  private def onDelta(
      event: HarmonyChatEvent.Delta
  ): Either[BatError, HarmonyChatAssembler] =
    for
      _ <- Either.cond(
        !state.streamEnded,
        (),
        protocolError("Harmony Chat chunk arrived after the stream end marker")
      )
      _ <- Either.cond(
        state.finishReason.isEmpty,
        (),
        protocolError(
          "Harmony Chat chunk arrived after a terminal finish reason"
        )
      )
      _ <- Either.cond(
        state.chunks < limits.maxChunks,
        (),
        protocolError("Harmony Chat stream exceeded the pinned chunk bound")
      )
      _ <- requireStable(state.responseId, event.responseId, "id")
      _ <- requireStable(state.model, event.model, "model")
      // The served model is part of the evidence pin. A response attributed to
      // another model is not the run that was requested.
      _ <- Either.cond(
        event.model == identity.modelId,
        (),
        protocolError("Harmony Chat response was served by a different model")
      )
      reasoning <- boundedAppend(
        state.reasoning,
        event.reasoning,
        limits.maxReasoningCharacters,
        "reasoning"
      )
      content <- boundedAppend(
        state.content,
        event.content,
        limits.maxOutputCharacters,
        "content"
      )
      toolCalls <- mergeToolCalls(state.toolCalls, event.toolCalls)
    yield copy(
      state.copy(
        responseId = Some(event.responseId),
        model = Some(event.model),
        reasoning = reasoning,
        content = content,
        toolCalls = toolCalls,
        finishReason = event.finishReason,
        usage = event.usage.orElse(state.usage),
        chunks = state.chunks + 1
      )
    )

  /** Tool calls may arrive whole or fragmented. Identity fields are write-once
    * so a later fragment cannot rewrite which function is being called.
    */
  private def mergeToolCalls(
      current: Map[Int, RawToolCall],
      incoming: Chunk[RawToolCall]
  ): Either[BatError, Map[Int, RawToolCall]] =
    incoming.foldLeft[Either[BatError, Map[Int, RawToolCall]]](Right(current)) {
      case (result, call) =>
        result.flatMap { merged =>
          merged.get(call.index) match
            case None =>
              Either.cond(
                merged.size < limits.maxToolCalls,
                merged.updated(call.index, call),
                protocolError(
                  "Harmony Chat stream exceeded the pinned tool call bound"
                )
              )
            case Some(existing) =>
              for
                _ <- Either.cond(
                  existing.id == call.id && existing.name == call.name,
                  (),
                  protocolError(
                    "Harmony Chat tool call fragment changed its id or name"
                  )
                )
                arguments <- boundedAppend(
                  existing.arguments,
                  Some(call.arguments),
                  limits.maxArgumentsCharacters,
                  "tool arguments"
                )
              yield merged.updated(
                call.index,
                existing.copy(arguments = arguments)
              )
        }
    }

  private def requireStable(
      current: Option[String],
      observed: String,
      field: String
  ): Either[BatError, Unit] =
    current match
      case None             => Right(())
      case Some(`observed`) => Right(())
      case Some(_)          =>
        violation(s"Harmony Chat stream changed its $field mid-response")

  private def boundedAppend(
      current: String,
      addition: Option[String],
      limit: Int,
      label: String
  ): Either[BatError, String] =
    addition match
      case None        => Right(current)
      case Some(value) =>
        val total = current.length.toLong + value.length.toLong
        Either.cond(
          total <= limit.toLong,
          current + value,
          protocolError(
            s"Harmony Chat $label exceeded the configured size limit"
          )
        )

  private def copy(next: State): HarmonyChatAssembler =
    new HarmonyChatAssembler(identity, request, limits, next)

  private def traverse[A, B](
      values: Chunk[A]
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

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def protocolError(message: String): BatError =
    BatError.ProtocolViolation(message)

  private def violation[A](message: String): Either[BatError, A] =
    Left(protocolError(message))

object HarmonyChatAssembler:
  private[harmonychat] final case class State(
      responseId: Option[String],
      model: Option[String],
      reasoning: String,
      content: String,
      toolCalls: Map[Int, RawToolCall],
      finishReason: Option[String],
      usage: Option[Usage],
      chunks: Int,
      streamEnded: Boolean
  ):
    def orderedToolCalls: Chunk[RawToolCall] =
      Chunk.fromIterable(toolCalls.toList.sortBy(_._1).map(_._2))

    override def toString: String =
      "HarmonyChatAssembler.State(payload=<redacted>)"

  def start(
      identity: BackendIdentity,
      request: EncodedHarmonyChatRequest,
      limits: HarmonyChatLimits = HarmonyChatLimits.default
  ): Either[BatError, HarmonyChatAssembler] =
    if identity == null || request == null || limits == null then
      Left(
        BatError.ProtocolViolation(
          "Harmony Chat assembler inputs must not be null"
        )
      )
    else if request.identity != identity then
      Left(
        BatError.ProtocolViolation(
          "Encoded Harmony Chat request has a different backend identity"
        )
      )
    else
      Right(
        new HarmonyChatAssembler(
          identity,
          request,
          limits,
          State(
            responseId = None,
            model = None,
            reasoning = "",
            content = "",
            toolCalls = Map.empty,
            finishReason = None,
            usage = None,
            chunks = 0,
            streamEnded = false
          )
        )
      )
