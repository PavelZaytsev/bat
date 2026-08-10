package bat.probe

import zio.Chunk
import zio.http.Status
import zio.json.*
import zio.json.ast.Json

/** Strict OpenAI Responses fixtures used through a real loopback transport.
  * Every secret/provider-controlled canary must be absent from probe artifacts.
  */
private[probe] object ProbeResponsesFixtures:
  import LoopbackResponsesServer.ScriptedResponse

  val ModelId = "gpt-oss-20b"
  val ModelRevision = "weights-sha256-20b-test"
  val Runtime = "llama.cpp"
  val RuntimeRevision = "llama-cpp-test-revision"
  val TemplateRevision = "harmony-test-revision"
  val Quantization = "mxfp4"
  val Topology = "single-node"
  val CredentialCanary = "PROBE_CREDENTIAL_CANARY_b087"
  val EndpointCanary = "endpoint-canary.invalid"
  val ReasoningCanaryOne = "PROBE_REASONING_CANARY_1_8fb2"
  val ReasoningCanaryTwo = "PROBE_REASONING_CANARY_2_174d"
  val FinalReasoningCanary = "PROBE_REASONING_CANARY_3_c61e"
  val PrematureReasoningCanary = "PROBE_REASONING_CANARY_PREMATURE_f143"
  val RawCallIdOne = "raw-call-audit-canary-001"
  val RawCallIdTwo = "raw-call-ready-canary-002"
  val ProviderBodyCanary = "PROBE_PROVIDER_BODY_CANARY_e030"

  val golden: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.sse(
        toSse(
          functionTurnEvents(
            "resp_audit",
            "rs_audit",
            "fc_audit",
            ReasoningCanaryOne,
            RawCallIdOne,
            "bdr_audit_summary",
            "{}",
            usageJson(60, 20, 40, 25)
          )
        )
      ),
      ScriptedResponse.sse(
        toSse(
          functionTurnEvents(
            "resp_ready",
            "rs_ready",
            "fc_ready",
            ReasoningCanaryTwo,
            RawCallIdTwo,
            "bdr_apply",
            "{\"operation_json\":\"{\\\"state\\\":\\\"ready_for_review\\\",\\\"type\\\":\\\"set_run_state\\\"}\"}",
            usageJson(70, 30, 30, 20)
          )
        )
      ),
      ScriptedResponse.sse(
        toSse(
          finalTurnEvents(
            "resp_final",
            "rs_final",
            "msg_final",
            FinalReasoningCanary,
            "Ready for review.",
            usageJson(40, 10, 20, 10)
          )
        )
      )
    )

  /** A Chat Completions-shaped response at the Responses path. The probe must
    * report dialect incompatibility and must not attempt a fallback endpoint.
    */
  val wrongDialect: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.text(
        Status.Ok,
        s"""{"id":"chatcmpl-canary","choices":[{"message":{"role":"assistant","content":"$ProviderBodyCanary"}}]}"""
      )
    )

  /** A syntactically valid partial Responses stream with no completion or SSE
    * sentinel. No emitted function call is safe to execute.
    */
  val truncated: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.sse(
        toSse(
          functionTurnEvents(
            "resp_truncated",
            "rs_truncated",
            "fc_truncated",
            ReasoningCanaryOne,
            RawCallIdOne,
            "bdr_audit_summary",
            "{}",
            usageJson(60, 20, 40, 25)
          ).dropRight(1),
          includeDone = false
        )
      )
    )

  /** A compatible client must treat redirects as evidence, never follow them to
    * a different path or accidentally disclose a future credential.
    */
  val redirect: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.redirect("/redirect-target"),
      golden.head
    )

  /** Fully valid Responses output that violates the pinned golden scenario by
    * returning a final answer before either required tool call.
    */
  val prematureFinal: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.sse(
        toSse(
          finalTurnEvents(
            "resp_premature",
            "rs_premature",
            "msg_premature",
            PrematureReasoningCanary,
            "Finished early.",
            usageJson(40, 10, 20, 10)
          )
        )
      )
    )

  val unauthorized: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.text(Status.Unauthorized, ProviderBodyCanary)
    )

  val requestTimeout: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.text(Status.RequestTimeout, ProviderBodyCanary)
    )

  val responsesUnavailable: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.text(Status.NotFound, ProviderBodyCanary)
    )

  val sensitiveCanaries: Chunk[String] =
    Chunk(
      CredentialCanary,
      ReasoningCanaryOne,
      ReasoningCanaryTwo,
      FinalReasoningCanary,
      PrematureReasoningCanary,
      RawCallIdOne,
      RawCallIdTwo,
      ProviderBodyCanary
    )

  private def functionTurnEvents(
      responseId: String,
      reasoningId: String,
      functionId: String,
      reasoning: String,
      callId: String,
      name: String,
      arguments: String,
      usage: Json.Obj
  ): Chunk[Json.Obj] =
    val reasoningSplit = Math.min(7, reasoning.length)
    val argumentSplit = Math.min(5, arguments.length)
    val reasoningRaw = reasoningItem(reasoningId, reasoning)
    val functionRaw = functionItem(
      functionId,
      callId,
      name,
      arguments,
      "completed"
    )
    Chunk(
      created(0, responseId),
      progress(1, responseId),
      outputItemAdded(2, 0, reasoningAdded(reasoningId)),
      contentPartAdded(3, reasoningId, 0, reasoningPart("")),
      textEvent(
        "response.reasoning_text.delta",
        4,
        reasoningId,
        0,
        "delta",
        reasoning.take(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.delta",
        5,
        reasoningId,
        0,
        "delta",
        reasoning.drop(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.done",
        6,
        reasoningId,
        0,
        "text",
        reasoning
      ),
      contentPartDone(7, reasoningId, 0, reasoningPart(reasoning)),
      outputItemDone(8, 0, reasoningRaw),
      outputItemAdded(
        9,
        1,
        functionItem(functionId, callId, name, "", "in_progress")
      ),
      functionArgumentsDelta(
        10,
        functionId,
        1,
        arguments.take(argumentSplit)
      ),
      functionArgumentsDelta(
        11,
        functionId,
        1,
        arguments.drop(argumentSplit)
      ),
      functionArgumentsDone(12, functionId, 1, name, arguments),
      outputItemDone(13, 1, functionRaw),
      completed(14, responseId, Chunk(reasoningRaw, functionRaw), usage)
    )

  private def finalTurnEvents(
      responseId: String,
      reasoningId: String,
      messageId: String,
      reasoning: String,
      output: String,
      usage: Json.Obj
  ): Chunk[Json.Obj] =
    val reasoningSplit = Math.min(7, reasoning.length)
    val outputSplit = Math.min(6, output.length)
    val reasoningRaw = reasoningItem(reasoningId, reasoning)
    val messageRaw = messageItem(messageId, output, "completed")
    Chunk(
      created(0, responseId),
      progress(1, responseId),
      outputItemAdded(2, 0, reasoningAdded(reasoningId)),
      contentPartAdded(3, reasoningId, 0, reasoningPart("")),
      textEvent(
        "response.reasoning_text.delta",
        4,
        reasoningId,
        0,
        "delta",
        reasoning.take(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.delta",
        5,
        reasoningId,
        0,
        "delta",
        reasoning.drop(reasoningSplit)
      ),
      textEvent(
        "response.reasoning_text.done",
        6,
        reasoningId,
        0,
        "text",
        reasoning
      ),
      contentPartDone(7, reasoningId, 0, reasoningPart(reasoning)),
      outputItemDone(8, 0, reasoningRaw),
      outputItemAdded(9, 1, messageItem(messageId, "", "in_progress")),
      contentPartAdded(10, messageId, 1, outputPart("")),
      textEvent(
        "response.output_text.delta",
        11,
        messageId,
        1,
        "delta",
        output.take(outputSplit)
      ),
      textEvent(
        "response.output_text.delta",
        12,
        messageId,
        1,
        "delta",
        output.drop(outputSplit)
      ),
      textEvent(
        "response.output_text.done",
        13,
        messageId,
        1,
        "text",
        output
      ),
      contentPartDone(14, messageId, 1, outputPart(output)),
      outputItemDone(15, 1, messageRaw),
      completed(16, responseId, Chunk(reasoningRaw, messageRaw), usage)
    )

  private def created(sequence: Long, responseId: String): Json.Obj =
    event(
      "response.created",
      sequence,
      "response" -> obj(
        "id" -> Json.Str(responseId),
        "model" -> Json.Str(ModelId),
        "status" -> Json.Str("in_progress")
      )
    )

  private def progress(sequence: Long, responseId: String): Json.Obj =
    event(
      "response.in_progress",
      sequence,
      "response" -> obj(
        "id" -> Json.Str(responseId),
        "model" -> Json.Str(ModelId),
        "status" -> Json.Str("in_progress")
      )
    )

  private def outputItemAdded(
      sequence: Long,
      outputIndex: Int,
      item: Json.Obj
  ): Json.Obj =
    event(
      "response.output_item.added",
      sequence,
      "output_index" -> number(outputIndex),
      "item" -> item
    )

  private def contentPartAdded(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      part: Json.Obj
  ): Json.Obj =
    contentPartEvent(
      "response.content_part.added",
      sequence,
      itemId,
      outputIndex,
      part
    )

  private def contentPartDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      part: Json.Obj
  ): Json.Obj =
    contentPartEvent(
      "response.content_part.done",
      sequence,
      itemId,
      outputIndex,
      part
    )

  private def contentPartEvent(
      eventType: String,
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      part: Json.Obj
  ): Json.Obj =
    event(
      eventType,
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "content_index" -> number(0),
      "part" -> part
    )

  private def textEvent(
      eventType: String,
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      valueName: String,
      value: String
  ): Json.Obj =
    event(
      eventType,
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "content_index" -> number(0),
      valueName -> Json.Str(value)
    )

  private def functionArgumentsDelta(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      delta: String
  ): Json.Obj =
    event(
      "response.function_call_arguments.delta",
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "delta" -> Json.Str(delta)
    )

  private def functionArgumentsDone(
      sequence: Long,
      itemId: String,
      outputIndex: Int,
      name: String,
      arguments: String
  ): Json.Obj =
    event(
      "response.function_call_arguments.done",
      sequence,
      "item_id" -> Json.Str(itemId),
      "output_index" -> number(outputIndex),
      "name" -> Json.Str(name),
      "arguments" -> Json.Str(arguments)
    )

  private def outputItemDone(
      sequence: Long,
      outputIndex: Int,
      item: Json.Obj
  ): Json.Obj =
    event(
      "response.output_item.done",
      sequence,
      "output_index" -> number(outputIndex),
      "item" -> item
    )

  private def completed(
      sequence: Long,
      responseId: String,
      output: Chunk[Json.Obj],
      usage: Json.Obj
  ): Json.Obj =
    event(
      "response.completed",
      sequence,
      "response" -> obj(
        "id" -> Json.Str(responseId),
        "model" -> Json.Str(ModelId),
        "status" -> Json.Str("completed"),
        "error" -> Json.Null,
        "incomplete_details" -> Json.Null,
        "output" -> Json.Arr(output.map(value => value: Json)),
        "usage" -> usage
      )
    )

  private def event(
      eventType: String,
      sequence: Long,
      fields: (String, Json)*
  ): Json.Obj =
    obj(
      (Chunk[(String, Json)](
        "type" -> Json.Str(eventType),
        "sequence_number" -> number(sequence)
      ) ++ Chunk.fromIterable(fields))*
    )

  private def reasoningAdded(id: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("reasoning"),
      "summary" -> Json.Arr(Chunk.empty)
    )

  private def reasoningItem(id: String, text: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("reasoning"),
      "summary" -> Json.Arr(Chunk.empty),
      "content" -> Json.Arr(Chunk(reasoningPart(text)))
    )

  private def functionItem(
      id: String,
      callId: String,
      name: String,
      arguments: String,
      status: String
  ): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("function_call"),
      "status" -> Json.Str(status),
      "call_id" -> Json.Str(callId),
      "name" -> Json.Str(name),
      "arguments" -> Json.Str(arguments)
    )

  private def messageItem(
      id: String,
      text: String,
      status: String
  ): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("message"),
      "status" -> Json.Str(status),
      "role" -> Json.Str("assistant"),
      "content" -> Json.Arr(
        if text.isEmpty then Chunk.empty else Chunk(outputPart(text))
      )
    )

  private def reasoningPart(text: String): Json.Obj =
    obj(
      "type" -> Json.Str("reasoning_text"),
      "text" -> Json.Str(text)
    )

  private def outputPart(text: String): Json.Obj =
    obj(
      "type" -> Json.Str("output_text"),
      "text" -> Json.Str(text),
      "annotations" -> Json.Arr(Chunk.empty)
    )

  private def usageJson(
      input: Long,
      cached: Long,
      output: Long,
      reasoning: Long
  ): Json.Obj =
    obj(
      "input_tokens" -> number(input),
      "input_tokens_details" -> obj("cached_tokens" -> number(cached)),
      "output_tokens" -> number(output),
      "output_tokens_details" -> obj(
        "reasoning_tokens" -> number(reasoning)
      ),
      "total_tokens" -> number(input + output)
    )

  private def toSse(
      events: Chunk[Json.Obj],
      includeDone: Boolean = true
  ): String =
    val semantic = events.map { value =>
      val eventType = stringField(value, "type").getOrElse("message")
      s"event: $eventType\r\ndata: ${value.toJson}\r\n\r\n"
    }.mkString
    val done = if includeDone then "data: [DONE]\r\n\r\n" else ""
    s": $ProviderBodyCanary\r\n\r\n$semantic$done"

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def number(value: Long): Json = Json.Num(value)

  private def stringField(value: Json.Obj, name: String): Option[String] =
    value.fields.collectFirst { case (`name`, Json.Str(text)) => text }
