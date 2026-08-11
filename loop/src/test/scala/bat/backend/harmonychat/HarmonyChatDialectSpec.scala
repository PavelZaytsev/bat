package bat.backend.harmonychat

import java.nio.charset.StandardCharsets

import bat.backend.wire.WireStep
import bat.protocol.*
import bat.transport.*

import zio.{Chunk, Duration}
import zio.json.ast.Json
import zio.test.*

/** Conformance for the Harmony Chat dialect against exo-shaped payloads.
  *
  * The stream is fed through the real SSE framer one byte at a time, so
  * fragmentation, comment lines, and the `[DONE]` sentinel are exercised rather
  * than assumed. The negative cases are the ones that matter: this dialect is
  * only usable if it refuses an endpoint that cannot actually replay reasoning
  * and tool identity.
  */
object HarmonyChatDialectSpec extends ZIOSpecDefault:
  private val Commit = "0123456789abcdef0123456789abcdef01234567"
  private val Digest = "a" * 64
  private val ModelId = "openai/gpt-oss-20b"
  private val ModelRevision = "weights-2026-08-10"
  private val ReasoningCanary = "RAW_ANALYSIS_CANARY_7b31"

  private val identity = unsafe(
    HarmonyChatConfig.identity(ModelId, ModelRevision)
  )

  private val sseLimits = unsafeTransport(
    SseLimits.make(1024 * 1024, 8 * 1024 * 1024)
  )

  private val dialect = unsafe(
    HarmonyChatDialect.make(
      unsafe(
        HarmonyChatConfig.make(
          identity,
          credential = None,
          sseLimits = sseLimits,
          maxOutputTokens = 1024,
          maxAttempts = 1,
          retryDelay = Duration.Zero
        )
      )
    )
  )

  def spec: Spec[Any, Any] =
    suite("HarmonyChatDialect")(
      test(
        "encodes developer instructions and tools as Harmony Chat messages"
      ) {
        val (body, _) = unsafe(dialect.beginTurn(firstRequest, 512L))
        val parsed = unsafe(StrictJson.parseObject(body, "body"))
        val messages = arrayField(parsed, "messages")
        val roles = messages.map(message => stringField(message, "role"))
        val tools = arrayField(parsed, "tools")
        assertTrue(
          stringField(parsed, "model") == ModelId,
          // Chat Completions has no top-level instructions field, so the
          // developer prompt has to enter the replay prefix as a message.
          roles == Chunk("developer", "user", "developer"),
          stringField(messages(0), "content") == "Follow BDR.",
          stringField(messages(2), "content").contains("<bat_turn_context>"),
          stringField(parsed, "reasoning_effort") == "high",
          boolField(parsed, "stream"),
          numberField(parsed, "max_tokens") == 512L,
          tools.size == 1,
          stringField(objField(tools.head, "function"), "name") == "bdr_status"
        )
      },
      test("assembles a fragmented tool turn and preserves raw reasoning") {
        val turn = unsafe(runStream(firstRequest, toolCallStream))
        turn match
          case ModelTurn.ToolCalls(context, calls, usage) =>
            val assistant = context.historyMessages.last
            assertTrue(
              calls.size == 1,
              calls.head.callId.value == "call_audit_1",
              calls.head.name == "bdr_status",
              usage.totalTokens == 120L,
              usage.reasoningTokens.contains(8L),
              // The analysis channel must survive verbatim in the field the
              // endpoint actually consumes on the next turn.
              stringField(assistant, "reasoning_content") == ReasoningCanary,
              stringField(assistant, "role") == "assistant",
              arrayField(assistant, "tool_calls").size == 1,
              stringField(
                arrayField(assistant, "tool_calls").head,
                "id"
              ) == "call_audit_1"
            )
          case _ => assertTrue(false)
      },
      test("replays the assistant message and tool output on the next turn") {
        val turn = unsafe(runStream(firstRequest, toolCallStream))
        val context = turn match
          case ModelTurn.ToolCalls(value, _, _) => value
          case _ => throw AssertionError("expected a tool turn")
        val (body, _) = unsafe(dialect.beginTurn(secondRequest(context), 512L))
        val parsed = unsafe(StrictJson.parseObject(body, "body"))
        val messages = arrayField(parsed, "messages")
        val roles = messages.map(message => stringField(message, "role"))
        val toolMessage = messages(4)
        assertTrue(
          roles == Chunk(
            "developer",
            "user",
            "developer",
            "assistant",
            "tool",
            "developer"
          ),
          stringField(messages(3), "reasoning_content") == ReasoningCanary,
          stringField(toolMessage, "tool_call_id") == "call_audit_1",
          stringField(toolMessage, "content").contains("\"is_error\":false")
        )
      },
      test("assembles a final answer turn") {
        val turn = unsafe(runStream(firstRequest, finalAnswerStream))
        turn match
          case ModelTurn.Completed(output, usage) =>
            assertTrue(
              output.text == "Audit complete.",
              usage.totalTokens == 90L
            )
          case _ => assertTrue(false)
      },
      test("rejects a tool turn whose reasoning was stripped") {
        assertTrue(runStream(firstRequest, strippedReasoningStream).isLeft)
      },
      test("rejects a turn that never reported usage") {
        assertTrue(runStream(firstRequest, noUsageStream).isLeft)
      },
      test("rejects a truncated stream and a stream without a finish reason") {
        assertTrue(
          runStream(firstRequest, truncatedStream).isLeft,
          runStream(firstRequest, noFinishReasonStream).isLeft
        )
      },
      test("rejects duplicate and historically reused tool call ids") {
        assertTrue(
          runStream(firstRequest, duplicateCallIdStream).isLeft,
          runStream(reusedIdRequest, toolCallStream).isLeft
        )
      },
      test("rejects a response served by a different model") {
        assertTrue(runStream(firstRequest, wrongModelStream).isLeft)
      },
      test("rejects tool arguments that are not a strict JSON object") {
        assertTrue(runStream(firstRequest, badArgumentsStream).isLeft)
      },
      test("rejects a named SSE event, which belongs to another dialect") {
        val seeded = unsafe(dialect.beginTurn(firstRequest, 512L))._2
        val framed = unsafe(
          framedEvents("event: response.created\ndata: {}\n\n")
        )
        val event = framed.headOption
          .getOrElse(throw AssertionError("no framed event"))
        assertTrue(dialect.accept(seeded, event).isLeft)
      },
      test("separates a spent token budget from a broken wire") {
        // Observed on gpt-oss-20b at max_tokens 24: completion_tokens 24 of
        // which reasoning_tokens 21, content ''. Status 200, valid framing,
        // nothing wrong with the cartridge — the allowance was just too small.
        val message =
          runStream(firstRequest, budgetExhaustedStream).left.toOption
            .map(_.safeMessage)
            .getOrElse("")
        assertTrue(
          failureCode(budgetExhaustedStream)
            .contains("harmony_chat_output_budget_exhausted"),
          message.contains("reasoning_tokens=21"),
          message.contains("output_tokens=24"),
          message.contains("content_characters=0")
        )
      },
      test("names the failing check in a code that survives the boundary") {
        // Without a distinct BackendFailure code these all collapse into one
        // generic protocol violation and an operator cannot tell them apart.
        assertTrue(
          failureCode(strippedReasoningStream)
            .contains("harmony_chat_reasoning_stripped"),
          failureCode(noUsageStream).contains("harmony_chat_usage_absent"),
          failureCode(truncatedStream)
            .contains("harmony_chat_missing_stream_end"),
          failureCode(wrongModelStream).contains("harmony_chat_model_mismatch"),
          failureCode(duplicateCallIdStream)
            .contains("harmony_chat_duplicate_call_id")
        )
      },
      test("reports the observed value beside the expected one") {
        val message = runStream(firstRequest, wrongModelStream).left.toOption
          .map(_.safeMessage)
          .getOrElse("")
        val truncation = runStream(firstRequest, truncatedStream).left.toOption
          .map(_.safeMessage)
          .getOrElse("")
        assertTrue(
          message.contains("openai/gpt-oss-20b"),
          message.contains("openai/gpt-oss-120b"),
          truncation.contains("finish_reason=tool_calls"),
          truncation.contains("usage=present")
        )
      },
      test("keeps raw reasoning out of every redacted representation") {
        val turn = unsafe(runStream(firstRequest, toolCallStream))
        val context = turn match
          case ModelTurn.ToolCalls(value, _, _) => value
          case _ => throw AssertionError("expected a tool turn")
        val rendered = List(
          turn.toString,
          context.toString,
          dialect.toString,
          dialect.protocolFailure.safeMessage
        ).mkString(" ")
        assertTrue(!rendered.contains(ReasoningCanary))
      }
    )

  private def failureCode(payload: String): Option[String] =
    runStream(firstRequest, payload).left.toOption.map(_.code)

  /** Feeds the payload through the real framer one byte at a time. */
  private def runStream(
      request: ModelRequest[HarmonyChatContext],
      payload: String
  ): Either[BatError, ModelTurn[HarmonyChatContext]] =
    for
      begun <- dialect.beginTurn(request, 512L)
      events <- framedEvents(payload)
      finished <- events.foldLeft[Either[BatError, HarmonyChatAssembler]](
        Right(begun._2)
      ) { (stream, event) =>
        stream.flatMap(current =>
          dialect
            .accept(current, event)
            .map(
              (step: WireStep[
                HarmonyChatAssembler
              ]) => step.stream
            )
        )
      }
      turn <- dialect.finish(finished)
    yield turn

  private def framedEvents(payload: String): Either[BatError, Chunk[SseEvent]] =
    val bytes = Chunk.fromArray(payload.getBytes(StandardCharsets.UTF_8))
    SseDecoder
      .initial(sseLimits)
      .flatMap { initial =>
        bytes.foldLeft[Either[SseError, (SseDecoder.State, Chunk[SseEvent])]](
          Right((initial, Chunk.empty))
        ) { case (accumulated, byte) =>
          accumulated.flatMap { case (state, collected) =>
            SseDecoder
              .feed(state, Chunk.single(byte))
              .map { case (next, emitted) => (next, collected ++ emitted) }
          }
        }
      }
      .flatMap { case (state, collected) =>
        SseDecoder.finish(state).map(trailing => collected ++ trailing)
      }
      .left
      .map(error => BatError.ProtocolViolation(error.code))

  private def reasoningChunk(text: String): String =
    s"""{"id":"cmd-1","object":"chat.completion.chunk","created":1,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"$text"}}]}"""

  private def contentChunk(text: String): String =
    s"""{"id":"cmd-1","object":"chat.completion.chunk","created":2,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","content":"$text"}}]}"""

  private val usageObject =
    """"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":10},"completion_tokens_details":{"reasoning_tokens":8}}"""

  private def toolCallChunk(
      callId: String,
      arguments: String = "{}",
      usage: String = usageObject,
      model: String = ModelId
  ): String =
    s"""{"id":"cmd-1","object":"chat.completion.chunk","created":3,"model":"$model","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"$callId","index":0,"type":"function","function":{"id":"$callId","name":"bdr_status","arguments":"$arguments"}}]},"finish_reason":"tool_calls"}],$usage}"""

  private def event(payload: String): String = s"data: $payload\n\n"

  private val done = "data: [DONE]\n\n"

  /** exo emits SSE comment lines for prefill and generation stats. They must be
    * skipped by the framer rather than decoded.
    */
  private val comment = ": prefill_progress {\"progress\":0.5}\n\n"

  private val toolCallStream =
    comment +
      event(reasoningChunk(ReasoningCanary.take(10))) +
      event(reasoningChunk(ReasoningCanary.drop(10))) +
      ": generation_stats {\"tps\":47.9}\n\n" +
      event(toolCallChunk("call_audit_1")) +
      done

  private val finalAnswerStream =
    event(reasoningChunk(ReasoningCanary)) +
      event(contentChunk("Audit ")) +
      s"""data: {"id":"cmd-1","created":4,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","content":"complete."},"finish_reason":"stop"}],"usage":{"prompt_tokens":70,"completion_tokens":20,"total_tokens":90}}\n\n""" +
      done

  private val strippedReasoningStream =
    event(toolCallChunk("call_audit_1")) + done

  private val noUsageStream =
    event(reasoningChunk(ReasoningCanary)) +
      event(toolCallChunk("call_audit_1", usage = """"created_at":5""")) +
      done

  private val truncatedStream =
    event(reasoningChunk(ReasoningCanary)) +
      event(toolCallChunk("call_audit_1"))

  private val noFinishReasonStream =
    event(reasoningChunk(ReasoningCanary)) + done

  private val duplicateCallIdStream =
    comment +
      event(reasoningChunk(ReasoningCanary)) +
      s"""data: {"id":"cmd-1","created":3,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"call_same","index":0,"type":"function","function":{"name":"bdr_status","arguments":"{}"}},{"id":"call_same","index":1,"type":"function","function":{"name":"bdr_status","arguments":"{}"}}]},"finish_reason":"tool_calls"}],$usageObject}\n\n""" +
      done

  private val wrongModelStream =
    event(reasoningChunk(ReasoningCanary)) +
      event(toolCallChunk("call_audit_1", model = "openai/gpt-oss-120b")) +
      done

  /** gpt-oss-20b with a small max_tokens: the whole allowance goes to the
    * analysis channel and `content` comes back empty under status 200.
    */
  private val budgetExhaustedStream =
    event(reasoningChunk(ReasoningCanary)) +
      s"""data: {"id":"cmd-1","created":4,"model":"$ModelId","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":"length"}],"usage":{"prompt_tokens":80,"completion_tokens":24,"total_tokens":104,"completion_tokens_details":{"reasoning_tokens":21}}}\n\n""" +
      done

  private val badArgumentsStream =
    event(reasoningChunk(ReasoningCanary)) +
      event(toolCallChunk("call_audit_1", arguments = "not-json")) +
      done

  private lazy val bdrState = unsafe(
    BdrStateView.make(
      unsafe(Revision.from(3L)),
      "expose",
      Json.Obj(Chunk("kind" -> Json.Str("run_focused_test"))),
      Digest
    )
  )

  private lazy val tool = unsafe(
    ToolDefinition.make(
      "bdr_status",
      "Read the current BDR state.",
      Json.Obj(
        Chunk(
          "type" -> Json.Str("object"),
          "properties" -> Json.Obj(Chunk.empty),
          "additionalProperties" -> Json.Bool(false)
        )
      )
    )
  )

  private lazy val pins = unsafe(
    RunPins.make(identity, "high", "harmony-chat-spec-v1", Commit)
  )

  private lazy val firstRequest: ModelRequest[HarmonyChatContext] =
    unsafe(
      ModelRequest.make(
        pins,
        unsafe(DeveloperInput.make("Follow BDR.")),
        Chunk(InputEvent.User(unsafe(UserInput.make("Audit the PR.")))),
        Chunk(tool),
        bdrState,
        iteration = 1,
        continuation = None
      )
    )

  /** A continuation whose history already contains the identifier the stream is
    * about to emit again, answering its own outstanding call correctly.
    */
  private lazy val reusedIdRequest: ModelRequest[HarmonyChatContext] =
    val previousCall = unsafe(CallId.from("call_previous"))
    val replayedCall = unsafe(CallId.from("call_audit_1"))
    val seeded = new HarmonyChatContext(
      identity,
      Chunk.empty,
      Chunk(previousCall),
      Set(previousCall, replayedCall)
    )
    unsafe(
      ModelRequest.make(
        pins,
        unsafe(DeveloperInput.make("Follow BDR.")),
        Chunk(
          InputEvent.ToolOutput(
            unsafe(
              FunctionOutput.make(
                previousCall,
                Json.Obj(Chunk("run_state" -> Json.Str("expose")))
              )
            )
          )
        ),
        Chunk(tool),
        bdrState,
        iteration = 2,
        continuation = Some(seeded)
      )
    )

  private def secondRequest(
      context: HarmonyChatContext
  ): ModelRequest[HarmonyChatContext] =
    unsafe(
      ModelRequest.make(
        pins,
        unsafe(DeveloperInput.make("Follow BDR.")),
        Chunk(
          InputEvent.ToolOutput(
            unsafe(
              FunctionOutput.make(
                unsafe(CallId.from("call_audit_1")),
                Json.Obj(Chunk("run_state" -> Json.Str("expose")))
              )
            )
          )
        ),
        Chunk(tool),
        bdrState,
        iteration = 2,
        continuation = Some(context)
      )
    )

  private def field(value: Json, name: String): Json =
    value match
      case obj: Json.Obj =>
        obj.fields
          .collectFirst { case (`name`, result) => result }
          .getOrElse(throw AssertionError(s"missing field $name"))
      case _ => throw AssertionError("expected a JSON object")

  private def stringField(value: Json, name: String): String =
    field(value, name) match
      case Json.Str(result) => result
      case _                => throw AssertionError(s"$name is not a string")

  private def boolField(value: Json, name: String): Boolean =
    field(value, name) match
      case Json.Bool(result) => result
      case _                 => throw AssertionError(s"$name is not a boolean")

  private def numberField(value: Json, name: String): Long =
    field(value, name) match
      case Json.Num(result) => result.longValueExact()
      case _                => throw AssertionError(s"$name is not a number")

  private def objField(value: Json, name: String): Json.Obj =
    field(value, name) match
      case result: Json.Obj => result
      case _                => throw AssertionError(s"$name is not an object")

  private def arrayField(value: Json, name: String): Chunk[Json] =
    field(value, name) match
      case Json.Arr(result) => result
      case _                => throw AssertionError(s"$name is not an array")

  private def unsafe[A](result: Either[BatError, A]): A =
    result.fold(
      error => throw AssertionError(error.safeMessage),
      value => value
    )

  private def unsafeTransport[A](result: Either[SseError, A]): A =
    result.fold(error => throw AssertionError(error.code), value => value)
