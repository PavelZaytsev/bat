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
      // A terminal finish reason is not proof the stream completed. Every
      // terminal path on this wire emits the `[DONE]` sentinel, so a stream
      // that stops before it was cut short and must not be mistaken for a
      // finished turn.
      //
      // The observed shape travels with the failure so a cosmetic mismatch is
      // diagnosable without a second run. The stream *tail* deliberately does
      // not: on this wire the tail is where reasoning and content live.
      _ <- Either.cond(
        state.streamEnded,
        (),
        diagnostic(
          "missing_stream_end",
          s"Harmony Chat stream ended before the [DONE] sentinel (finish_reason=${safeValue(finishReason)}, chunks=${state.chunks}, usage=${
              if state.usage.isDefined then "present"
              else "absent"
            })"
        )
      )
      // A turn without reported usage cannot be attributed. BAT records an
      // unavailable measurement as absent, never as zero, so the honest
      // outcome here is to reject the turn rather than invent a total.
      usage <- state.usage.toRight(
        diagnostic(
          "usage_absent",
          "Harmony Chat turn did not report terminal usage"
        )
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
          diagnostic(
            "reasoning_stripped",
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
          budgetExhausted(finishReason, usage)
        )
        output <- FinalOutput.make(state.content)
      yield ModelTurn.completed(output, usage)
    else Left(emptyOutputFailure(finishReason, usage))

  /** A reasoning model with a small token allowance can spend the entire budget
    * in the analysis channel and return `content: ''` under a perfectly valid
    * status. That is a budget fact, not a wire-format fault, and reporting it
    * as a generic protocol violation sends the operator hunting the wrong
    * problem.
    */
  private def emptyOutputFailure(
      finishReason: String,
      usage: Usage
  ): BatError =
    val spentOnReasoning = usage.reasoningTokens.exists(reasoning =>
      usage.outputTokens.exists(output => output > 0 && reasoning * 2 >= output)
    )
    if finishReason == "length" || spentOnReasoning then
      budgetExhausted(finishReason, usage)
    else
      protocolError("Harmony Chat turn has neither tool calls nor final output")

  private def budgetExhausted(finishReason: String, usage: Usage): BatError =
    diagnostic(
      "output_budget_exhausted",
      s"Harmony Chat turn spent its token allowance without usable output (finish_reason=${safeValue(finishReason)}, reasoning_tokens=${render(usage.reasoningTokens)}, output_tokens=${render(usage.outputTokens)}, content_characters=${state.content.length}); raise the output token budget"
    )

  private def render(value: Option[Long]): String =
    value.fold("unreported")(_.toString)

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
        diagnostic(
          "duplicate_call_id",
          "Harmony Chat turn contains duplicate tool call ids"
        )
      )
      _ <- Either.cond(
        calls.forall(call =>
          !request.historicalCallIds.exists(_.value == call.id)
        ),
        (),
        diagnostic(
          "reused_call_id",
          "Harmony Chat turn reused a historical tool call id"
        )
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
      _ <- requireStable(
        state.responseId,
        event.responseId,
        "id",
        "unstable_response_id"
      )
      _ <- requireStable(state.model, event.model, "model", "model_mismatch")
      // The served model is part of the evidence pin. A response attributed to
      // another model is not the run that was requested. Both identifiers are
      // operator-facing metadata rather than model output, so reporting them is
      // safe and turns a cosmetic mismatch into a one-line diagnosis.
      _ <- Either.cond(
        event.model == identity.modelId,
        (),
        diagnostic(
          "model_mismatch",
          s"Harmony Chat response was served by a different model (expected ${safeValue(identity.modelId)}, observed ${safeValue(event.model)})"
        )
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
      field: String,
      suffix: String
  ): Either[BatError, Unit] =
    current match
      case None             => Right(())
      case Some(`observed`) => Right(())
      case Some(previous)   =>
        Left(
          diagnostic(
            suffix,
            s"Harmony Chat stream changed its $field mid-response (expected ${safeValue(previous)}, observed ${safeValue(observed)})"
          )
        )

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

  /** A fail-closed check that an operator will need to diagnose.
    *
    * The dialect boundary collapses an anonymous protocol violation into one
    * generic code, which would hide *which* check rejected the turn. A distinct
    * `BackendFailure` code survives that boundary and reaches the run's
    * telemetry and the probe's reason code, so a refusal is self-explaining
    * without a second run and without emitting provider output.
    */
  private def diagnostic(suffix: String, message: String): BatError =
    BatError.BackendFailure(
      errorCode = s"harmony_chat_$suffix",
      safeMessage = message,
      retryable = false
    )

  /** Renders an operator-facing identifier. Provider-supplied metadata is still
    * provider-supplied, so it is length-bounded and stripped of anything that
    * is not plain printable text before it can appear in a message.
    */
  private def safeValue(value: String): String =
    val bounded = Option(value).getOrElse("").take(64)
    if bounded.isEmpty then "<absent>"
    else if bounded.forall(character =>
        !character.isControl && character >= ' ' && character <= '~'
      )
    then bounded
    else "<unsafe>"

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
