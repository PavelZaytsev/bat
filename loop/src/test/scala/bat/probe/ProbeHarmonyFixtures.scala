package bat.probe

import zio.Chunk
import zio.http.Status
import zio.json.*
import zio.json.ast.Json

/** Native Harmony Chat fixtures exercised through the real loopback HTTP and
  * SSE transport. Provider-controlled values are canaries: none may enter a
  * probe artifact or safe failure.
  */
private[probe] object ProbeHarmonyFixtures:
  import LoopbackResponsesServer.ScriptedResponse

  val ModelId = "gpt-oss-20b"
  val ReasoningCanaryOne = "HARMONY_REASONING_CANARY_1_d4b8"
  val ReasoningCanaryTwo = "HARMONY_REASONING_CANARY_2_0f73"
  val FinalReasoningCanary = "HARMONY_REASONING_CANARY_3_91ce"
  val ProviderBodyCanary = "HARMONY_PROVIDER_BODY_CANARY_2be7"
  val RawCallIdOne = "harmony-raw-call-audit-001"
  val RawCallIdTwo = "harmony-raw-call-apply-002"

  private val ApplyArguments =
    """{"operation_json":"{\"state\":\"ready_for_review\",\"type\":\"set_run_state\"}"}"""

  val golden: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.sse(
        toolTurn(
          "chat-audit",
          ReasoningCanaryOne,
          RawCallIdOne,
          "bdr_audit_summary",
          "{}",
          usage(100L, 20L, 8L)
        )
      ),
      ScriptedResponse.sse(
        toolTurn(
          "chat-apply",
          ReasoningCanaryTwo,
          RawCallIdTwo,
          "bdr_apply",
          ApplyArguments,
          usage(200L, 30L, 12L)
        )
      ),
      ScriptedResponse.sse(
        finalTurn(
          "chat-final",
          FinalReasoningCanary,
          "Ready for review.",
          usage(300L, 10L, 4L)
        )
      )
    )

  val unauthorized: Chunk[ScriptedResponse] =
    Chunk(ScriptedResponse.text(Status.Unauthorized, ProviderBodyCanary))

  val requestTimeout: Chunk[ScriptedResponse] =
    Chunk(ScriptedResponse.text(Status.RequestTimeout, ProviderBodyCanary))

  val rateLimited: Chunk[ScriptedResponse] =
    Chunk(ScriptedResponse.text(Status.TooManyRequests, ProviderBodyCanary))

  val endpointUnavailable: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.text(Status.InternalServerError, ProviderBodyCanary)
    )

  val completionsUnavailable: Chunk[ScriptedResponse] =
    Chunk(ScriptedResponse.text(Status.NotFound, ProviderBodyCanary))

  val contentFiltered: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.sse(
        finalTurn(
          "chat-filtered",
          FinalReasoningCanary,
          ProviderBodyCanary,
          usage(50L, 10L, 5L),
          finishReason = "content_filter"
        )
      )
    )

  val unsupportedFinishReason: Chunk[ScriptedResponse] =
    Chunk(
      ScriptedResponse.sse(
        finalTurn(
          "chat-future",
          FinalReasoningCanary,
          ProviderBodyCanary,
          usage(50L, 10L, 5L),
          finishReason = "future_finish_reason"
        )
      )
    )

  val sensitiveCanaries: Chunk[String] =
    Chunk(
      ReasoningCanaryOne,
      ReasoningCanaryTwo,
      FinalReasoningCanary,
      ProviderBodyCanary,
      RawCallIdOne,
      RawCallIdTwo
    )

  private def toolTurn(
      responseId: String,
      reasoning: String,
      callId: String,
      name: String,
      arguments: String,
      reportedUsage: Json.Obj
  ): String =
    event(
      chunk(
        responseId,
        obj("reasoning_content" -> Json.Str(reasoning)),
        None,
        None
      )
    ) +
      event(
        chunk(
          responseId,
          obj(
            "tool_calls" -> Json.Arr(
              Chunk(
                obj(
                  "id" -> Json.Str(callId),
                  "index" -> number(0L),
                  "type" -> Json.Str("function"),
                  "function" -> obj(
                    "name" -> Json.Str(name),
                    "arguments" -> Json.Str(arguments)
                  )
                )
              )
            )
          ),
          Some("tool_calls"),
          Some(reportedUsage)
        )
      ) + done

  private def finalTurn(
      responseId: String,
      reasoning: String,
      content: String,
      reportedUsage: Json.Obj,
      finishReason: String = "stop"
  ): String =
    event(
      chunk(
        responseId,
        obj("reasoning_content" -> Json.Str(reasoning)),
        None,
        None
      )
    ) +
      event(
        chunk(
          responseId,
          obj("content" -> Json.Str(content)),
          Some(finishReason),
          Some(reportedUsage)
        )
      ) + done

  private def chunk(
      responseId: String,
      delta: Json.Obj,
      finishReason: Option[String],
      reportedUsage: Option[Json.Obj]
  ): Json.Obj =
    obj(
      "id" -> Json.Str(responseId),
      "object" -> Json.Str("chat.completion.chunk"),
      "created" -> number(1L),
      "model" -> Json.Str(ModelId),
      "choices" -> Json.Arr(
        Chunk(
          obj(
            "index" -> number(0L),
            "delta" -> delta,
            "finish_reason" -> finishReason.fold[Json](Json.Null)(Json.Str(_))
          )
        )
      ),
      "usage" -> reportedUsage.fold[Json](Json.Null)(identity)
    )

  private def usage(
      input: Long,
      output: Long,
      reasoning: Long
  ): Json.Obj =
    obj(
      "prompt_tokens" -> number(input),
      "completion_tokens" -> number(output),
      "total_tokens" -> number(input + output),
      "completion_tokens_details" -> obj(
        "reasoning_tokens" -> number(reasoning)
      )
    )

  private def event(value: Json.Obj): String =
    s"data: ${value.toJson}\r\n\r\n"

  private def done: String = "data: [DONE]\r\n\r\n"

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))
