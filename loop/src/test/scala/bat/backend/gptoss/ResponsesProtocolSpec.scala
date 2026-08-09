package bat.backend.gptoss

import bat.protocol.*

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object ResponsesProtocolSpec extends ZIOSpecDefault:
  private val validCommit = "abcdef0123456789abcdef0123456789abcdef01"
  private val modelId = "gpt-oss-120b-q4-k-m"
  private val reasoningCanary = "RAW_REASONING_CANARY_98c2"

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalStateException(error.safeMessage),
      result => result
    )

  private val identity = unsafe(
    BackendIdentity.make("gpt-oss-responses", modelId, "weights-2026-08-09")
  )

  private val pins = unsafe(
    RunPins.make(
      identity,
      "high",
      "bdr-2.2",
      validCommit
    )
  )

  private val bdrState = unsafe(
    BdrStateView.make(
      unsafe(Revision.from(7L)),
      "executing",
      obj("action" -> Json.Str("inspect_slice")),
      "0" * 64
    )
  )

  private val developer = unsafe(DeveloperInput.make("Follow BDR exactly."))
  private val user = unsafe(UserInput.make("Find and kill the cache bug."))

  private val tool = unsafe(
    ToolDefinition.make(
      "read_file",
      "Read one repository file.",
      obj(
        "type" -> Json.Str("object"),
        "properties" -> obj("path" -> obj("type" -> Json.Str("string"))),
        "required" -> Json.Arr(Chunk(Json.Str("path"))),
        "additionalProperties" -> Json.Bool(false)
      )
    )
  )

  private val usage = unsafe(
    Usage.make(
      totalTokens = 10,
      inputTokens = Some(6),
      cachedInputTokens = Some(1),
      outputTokens = Some(4),
      reasoningTokens = Some(2)
    )
  )

  private def request(
      inputs: Chunk[InputEvent] = Chunk(InputEvent.User(user)),
      continuation: Option[GptOssContext] = None,
      iteration: Int = 1
  ): ModelRequest[GptOssContext] =
    unsafe(
      ModelRequest.make(
        pins = pins,
        developer = developer,
        inputs = inputs,
        tools = Chunk(tool),
        bdrState = bdrState,
        iteration = iteration,
        continuation = continuation
      )
    )

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GPT-OSS Responses protocol")(
      suite("request encoding")(
        test("rejects a null model request without defecting") {
          assertTrue(ResponsesProtocol.encode(null).isLeft)
        },
        test("encodes pinned stateless Responses input and BAT turn state") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val body = encoded.body
          val input = arrayField(body, "input")
          val tools = arrayField(body, "tools")
          val instructions = stringField(body, "instructions")
          val turnContext = input.flatMap(_.lastOption).collect {
            case value: Json.Obj => value
          }
          val turnContextText = turnContext
            .flatMap(arrayField(_, "content"))
            .flatMap(_.headOption)
            .collect { case value: Json.Obj => value }
            .flatMap(stringField(_, "text"))
          assertTrue(
            stringField(body, "model").contains(modelId),
            booleanField(body, "stream").contains(true),
            booleanField(body, "store").contains(false),
            booleanField(body, "parallel_tool_calls").contains(false),
            stringField(body, "truncation").contains("disabled"),
            input.exists(_.size == 2),
            input.flatMap(_.headOption).exists {
              case item: Json.Obj =>
                stringField(item, "type").contains("message") &&
                stringField(item, "role").contains("user")
              case _ => false
            },
            turnContext.exists(value =>
              stringField(value, "role").contains("developer")
            ),
            turnContextText.exists(_.contains("\"iteration\":1")),
            turnContextText.exists(_.contains("\"revision\":7")),
            turnContextText.exists(
              _.contains("\"run_state\":\"executing\"")
            ),
            tools.flatMap(_.headOption).exists {
              case definition: Json.Obj =>
                stringField(definition, "type").contains("function") &&
                stringField(definition, "name").contains("read_file") &&
                booleanField(definition, "strict").contains(true)
              case _ => false
            },
            instructions.contains("Follow BDR exactly.")
          )
        },
        test("rejects tool outputs without opaque replay context") {
          val output =
            functionOutput("call-orphan", obj("ok" -> Json.Bool(true)))
          val result = ResponsesProtocol.encode(
            request(inputs = Chunk(InputEvent.ToolOutput(output)))
          )
          assertTrue(result.isLeft)
        },
        test("rejects reasoning efforts unsupported by GPT-OSS") {
          val unsupportedPins = unsafe(
            RunPins.make(identity, "xhigh", "bdr-2.2", validCommit)
          )
          val unsupported = unsafe(
            ModelRequest.make[GptOssContext](
              pins = unsupportedPins,
              developer = developer,
              inputs = Chunk(InputEvent.User(user)),
              tools = Chunk(tool),
              bdrState = bdrState,
              iteration = 1,
              continuation = None
            )
          )
          assertTrue(ResponsesProtocol.encode(unsupported).isLeft)
        }
      ),
      suite("event decoding")(
        test("rejects null event values and limits without defecting") {
          assertTrue(
            ResponsesProtocol.decodeEvent(null: String).isLeft,
            ResponsesProtocol
              .decodeEvent(Json.Null, null)
              .isLeft
          )
        },
        test("decodes fragmented semantic payloads from both text and JSON") {
          val delta = obj(
            "type" -> Json.Str("response.function_call_arguments.delta"),
            "sequence_number" -> number(4),
            "item_id" -> Json.Str("fc_1"),
            "output_index" -> number(1),
            "delta" -> Json.Str("{\"path\":")
          )
          val fromText = ResponsesProtocol.decodeEvent(delta.toJson)
          val fromJson = ResponsesProtocol.decodeEvent(delta)
          assertTrue(
            fromText == fromJson,
            fromText.exists {
              case ResponsesEvent.FunctionArgumentsDelta(
                    4,
                    "fc_1",
                    1,
                    "{\"path\":"
                  ) =>
                true
              case _ => false
            }
          )
        },
        test(
          "rejects duplicate JSON keys, unknown events, and oversized payloads"
        ) {
          val smallLimits = unsafe(
            ResponsesLimits.make(32, 4, 2, 100, 100, 100)
          )
          val duplicate =
            """{"type":"response.created","type":"response.completed"}"""
          val unknown = obj(
            "type" -> Json.Str("response.secret_payload"),
            "sequence_number" -> number(0)
          )
          val oversized = "{" + ("x" * 64) + "}"
          val whitespaceBypass = (" " * 40) + "{}"
          assertTrue(
            ResponsesProtocol.decodeEvent(duplicate).isLeft,
            ResponsesProtocol.decodeEvent(unknown).isLeft,
            ResponsesProtocol.decodeEvent(oversized, smallLimits).isLeft,
            ResponsesProtocol.decodeEvent(whitespaceBypass, smallLimits).isLeft
          )
        },
        test("requires terminal usage") {
          val response = terminalResponse(
            Chunk(messageItem("msg_1", "done")),
            includeUsage = false
          )
          val event = obj(
            "type" -> Json.Str("response.completed"),
            "sequence_number" -> number(2),
            "response" -> response
          )
          assertTrue(ResponsesProtocol.decodeEvent(event).isLeft)
        },
        test("decodes exact terminal token accounting") {
          val event = obj(
            "type" -> Json.Str("response.completed"),
            "sequence_number" -> number(2),
            "response" -> terminalResponse(
              Chunk(messageItem("msg_1", "done")),
              includeUsage = true
            )
          )
          assertTrue(
            ResponsesProtocol.decodeEvent(event).exists {
              case ResponsesEvent.Completed(
                    2,
                    "resp_1",
                    `modelId`,
                    _,
                    eventUsage
                  ) =>
                eventUsage == usage
              case _ => false
            }
          )
        },
        test("requires exact Responses token totals") {
          val invalidUsage = Json.Obj(
            usageJson.fields.map {
              case ("total_tokens", _) => "total_tokens" -> number(11)
              case other               => other
            }
          )
          val response = Json.Obj(
            terminalResponse(
              Chunk(messageItem("msg_1", "done")),
              includeUsage = true
            ).fields.map {
              case ("usage", _) => "usage" -> invalidUsage
              case other        => other
            }
          )
          val event = obj(
            "type" -> Json.Str("response.completed"),
            "sequence_number" -> number(2),
            "response" -> response
          )
          assertTrue(ResponsesProtocol.decodeEvent(event).isLeft)
        }
      ),
      suite("immutable assembly")(
        test("assembles fragmented reasoning and function arguments") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val result = fold(encoded, toolTrace(reasoningCanary, "call-1"))
            .flatMap(_.finish)
          assertTrue(
            result.exists {
              case ModelTurn.ToolCalls(context, calls, eventUsage) =>
                calls.size == 1 &&
                calls.head.callId.value == "call-1" &&
                calls.head.name == "read_file" &&
                stringField(calls.head.arguments, "path")
                  .contains("README.md") &&
                eventUsage == usage &&
                context.historyItems.size == 4 &&
                context.pendingCallIds.map(_.value) == Chunk("call-1") &&
                context.historicalCallIds.map(_.value) == Set("call-1")
              case _ => false
            }
          )
        },
        test("accumulates complete manual history across tool turns") {
          val firstEncoded = unsafe(ResponsesProtocol.encode(request()))
          val first = unsafe(
            fold(firstEncoded, toolTrace("first reasoning", "call-1"))
              .flatMap(_.finish)
          )
          val firstContext = first match
            case ModelTurn.ToolCalls(context, _, _) => context
            case _                                  =>
              throw new IllegalStateException("expected first tool turn")
          val firstOutput = functionOutput(
            "call-1",
            obj("contents" -> Json.Str("source"))
          )
          val secondRequest = request(
            inputs = Chunk(InputEvent.ToolOutput(firstOutput)),
            continuation = Some(firstContext),
            iteration = 2
          )
          val secondEncoded = unsafe(ResponsesProtocol.encode(secondRequest))
          val second = unsafe(
            fold(
              secondEncoded,
              toolTrace("second reasoning", "call-2", responseId = "resp_2")
            ).flatMap(_.finish)
          )
          val secondContext = second match
            case ModelTurn.ToolCalls(context, _, _) => context
            case _                                  =>
              throw new IllegalStateException("expected second tool turn")
          assertTrue(
            firstContext.historyItems.size == 4,
            secondEncoded.inputSeed.size == 6,
            secondContext.historyItems.size == 8,
            secondEncoded.inputSeed
              .take(firstContext.historyItems.size)
              .map(canonical) == firstContext.historyItems.map(canonical),
            canonical(secondContext.historyItems.head) ==
              canonical(firstContext.historyItems.head),
            secondContext.historyItems.exists {
              case item: Json.Obj =>
                stringField(item, "type").contains("function_call_output") &&
                stringField(item, "call_id").contains("call-1")
              case _ => false
            },
            secondContext.pendingCallIds.map(_.value) == Chunk("call-2") &&
              secondContext.historicalCallIds.map(_.value) ==
              Set("call-1", "call-2")
          )
        },
        test("rejects missing, duplicated, or reordered continuation outputs") {
          val call1 = unsafe(CallId.from("call-1"))
          val call2 = unsafe(CallId.from("call-2"))
          val initialUser =
            unsafe(ResponsesProtocol.encode(request())).inputSeed
          val context = new GptOssContext(
            identity,
            initialUser,
            Chunk(call1, call2),
            Set(call1, call2)
          )
          val first = functionOutput("call-1", Json.Str("one"))
          val second = functionOutput("call-2", Json.Str("two"))
          val missing = ResponsesProtocol.encode(
            request(Chunk(InputEvent.ToolOutput(first)), Some(context), 2)
          )
          val reversed = ResponsesProtocol.encode(
            request(
              Chunk(
                InputEvent.ToolOutput(second),
                InputEvent.ToolOutput(first)
              ),
              Some(context),
              2
            )
          )
          val duplicated = ResponsesProtocol.encode(
            request(
              Chunk(InputEvent.ToolOutput(first), InputEvent.ToolOutput(first)),
              Some(context),
              2
            )
          )
          assertTrue(missing.isLeft, reversed.isLeft, duplicated.isLeft)
        },
        test("rejects provider call-ID reuse across separate turns") {
          val firstEncoded = unsafe(ResponsesProtocol.encode(request()))
          val first = unsafe(
            fold(firstEncoded, toolTrace("first reasoning", "call-1"))
              .flatMap(_.finish)
          )
          val firstContext = first match
            case ModelTurn.ToolCalls(context, _, _) => context
            case _                                  =>
              throw new IllegalStateException("expected first tool turn")
          val output = functionOutput("call-1", Json.Str("done"))
          val nextEncoded = unsafe(
            ResponsesProtocol.encode(
              request(
                Chunk(InputEvent.ToolOutput(output)),
                Some(firstContext),
                2
              )
            )
          )
          val reused = fold(
            nextEncoded,
            toolTrace("second reasoning", "call-1", responseId = "resp_2")
          )
          assertTrue(reused.isLeft)
        },
        test(
          "assembles fragmented final output and discards reasoning context"
        ) {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val result = fold(encoded, finalTrace("Killed ", "the bug."))
            .flatMap(_.finish)
          assertTrue(
            result.exists {
              case ModelTurn.Completed(output, eventUsage) =>
                output.text == "Killed the bug." && eventUsage == usage
              case _ => false
            }
          )
        }
      ),
      suite("fail-closed invariants")(
        test("rejects sequence gaps and item-ID substitution") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val assembler = unsafe(ResponsesAssembler.start(identity, encoded))
          val afterCreated = unsafe(
            assembler.accept(ResponsesEvent.Created(0, "resp_1", modelId))
          )
          val gap = afterCreated.accept(
            ResponsesEvent.Progress(2, "resp_1", "in_progress")
          )
          val withReasoning = unsafe(
            afterCreated.accept(
              ResponsesEvent.OutputItemAdded(
                1,
                0,
                reasoningAdded("rs_1")
              )
            )
          )
          val swappedId = withReasoning.accept(
            ResponsesEvent.ContentPartAdded(
              2,
              "rs_other",
              0,
              0,
              reasoningPart("")
            )
          )
          assertTrue(gap.isLeft, swappedId.isLeft)
        },
        test("rejects progress regression and progress after output begins") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val assembler = unsafe(ResponsesAssembler.start(identity, encoded))
          val created = unsafe(
            assembler.accept(ResponsesEvent.Created(0, "resp_1", modelId))
          )
          val running = unsafe(
            created.accept(
              ResponsesEvent.Progress(1, "resp_1", "in_progress")
            )
          )
          val regressed = running.accept(
            ResponsesEvent.Progress(2, "resp_1", "queued")
          )
          val withOutput = unsafe(
            created.accept(
              ResponsesEvent.OutputItemAdded(
                1,
                0,
                reasoningAdded("rs_1")
              )
            )
          )
          val late = withOutput.accept(
            ResponsesEvent.Progress(2, "resp_1", "in_progress")
          )
          assertTrue(regressed.isLeft, late.isLeft)
        },
        test("rejects stripped reasoning and malformed arguments") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val withoutReasoning = Chunk[ResponsesEvent](
            ResponsesEvent.Created(0, "resp_1", modelId),
            ResponsesEvent.OutputItemAdded(1, 0, callAdded("fc_1", "call-1")),
            ResponsesEvent.FunctionArgumentsDelta(2, "fc_1", 0, "{}"),
            ResponsesEvent
              .FunctionArgumentsDone(3, "fc_1", 0, "read_file", "{}"),
            ResponsesEvent.OutputItemDone(
              4,
              0,
              callItem("fc_1", "call-1", "read_file", "{}")
            )
          )
          val malformed = toolTrace("reason", "call-1").map {
            case ResponsesEvent.FunctionArgumentsDelta(10, id, index, _) =>
              ResponsesEvent.FunctionArgumentsDelta(10, id, index, "not-")
            case ResponsesEvent.FunctionArgumentsDelta(11, id, index, _) =>
              ResponsesEvent.FunctionArgumentsDelta(11, id, index, "json")
            case ResponsesEvent
                  .FunctionArgumentsDone(seq, id, index, name, _) =>
              ResponsesEvent.FunctionArgumentsDone(
                seq,
                id,
                index,
                name,
                "not-json"
              )
            case event => event
          }
          assertTrue(
            fold(encoded, withoutReasoning).isLeft,
            fold(encoded, malformed).isLeft
          )
        },
        test("rejects wrong-typed optional output metadata") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val wrongName = toolTrace("reason", "call-1").map {
            case ResponsesEvent.OutputItemAdded(seq, 1, item) =>
              ResponsesEvent.OutputItemAdded(
                seq,
                1,
                Json.Obj(
                  item.fields.map {
                    case ("name", _) => "name" -> number(1)
                    case other       => other
                  }
                )
              )
            case event => event
          }
          val wrongStatus = toolTrace("reason", "call-1").map {
            case ResponsesEvent.OutputItemDone(seq, 0, item) =>
              ResponsesEvent.OutputItemDone(
                seq,
                0,
                Json.Obj(item.fields :+ ("status" -> Json.Bool(true)))
              )
            case event => event
          }
          assertTrue(
            fold(encoded, wrongName).isLeft,
            fold(encoded, wrongStatus).isLeft
          )
        },
        test("rejects truncation and retains tool-call preambles opaquely") {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val truncated = fold(
            encoded,
            toolTrace("reason", "call-1").dropRight(1)
          ).flatMap(_.finish)
          val callOutput = callItem(
            "fc_1",
            "call-1",
            "read_file",
            "{\"path\":\"README.md\"}"
          )
          val messageOutput = messageItem("msg_1", "done")
          val mixedEvents = toolTrace("reason", "call-1").dropRight(1)
          val offset = mixedEvents.last.sequenceNumber.get + 1
          val mixed = mixedEvents ++ messageItemEvents(
            offset,
            outputIndex = 2,
            "msg_1",
            "done"
          ) ++ Chunk(
            ResponsesEvent.Completed(
              offset + 6,
              "resp_1",
              modelId,
              Chunk(reasoningItem("rs_1", "reason"), callOutput, messageOutput),
              usage
            )
          )
          val mixedResult = fold(encoded, mixed).flatMap(_.finish)
          assertTrue(
            truncated.isLeft,
            mixedResult.exists {
              case ModelTurn.ToolCalls(context, calls, _) =>
                calls.map(_.callId.value) == Chunk("call-1") &&
                context.historyItems.exists {
                  case value: Json.Obj =>
                    stringField(value, "type").contains("message") &&
                    stringField(value, "role").contains("assistant")
                  case _ => false
                }
              case _ => false
            }
          )
        },
        test("enforces cumulative reasoning and argument limits") {
          val limits = unsafe(ResponsesLimits.make(1024, 8, 2, 4, 4, 16))
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val reasoningOverflow = fold(
            encoded,
            toolTrace("12345", "call-1"),
            limits
          )
          val argumentOverflow = fold(
            encoded,
            toolTrace("ok", "call-1", arguments = "{\"x\":12345}"),
            limits
          )
          assertTrue(reasoningOverflow.isLeft, argumentOverflow.isLeft)
        },
        test(
          "redacts provider reasoning and request material from renderings"
        ) {
          val encoded = unsafe(ResponsesProtocol.encode(request()))
          val event = ResponsesEvent.ReasoningDelta(
            4,
            "rs_1",
            0,
            0,
            reasoningCanary
          )
          val assembler = unsafe(ResponsesAssembler.start(identity, encoded))
          val completed = unsafe(
            fold(encoded, toolTrace(reasoningCanary, "call-1")).flatMap(
              _.finish
            )
          )
          val context = completed match
            case ModelTurn.ToolCalls(value, _, _) => value
            case _ => throw new IllegalStateException("expected tool turn")
          val unknown = ResponsesProtocol.decodeEvent(
            obj(
              "type" -> Json.Str(reasoningCanary),
              "sequence_number" -> number(0)
            )
          )
          assertTrue(
            !encoded.toString.contains("Find and kill"),
            !event.toString.contains(reasoningCanary),
            !assembler.toString.contains(reasoningCanary),
            !context.toString.contains(reasoningCanary),
            unknown.left.exists(error =>
              !error.safeMessage.contains(reasoningCanary)
            )
          )
        }
      )
    )

  private def fold(
      encoded: EncodedResponsesRequest,
      events: Chunk[ResponsesEvent],
      limits: ResponsesLimits = ResponsesLimits.default
  ): Either[BatError, ResponsesAssembler] =
    ResponsesAssembler.start(identity, encoded, limits).flatMap { initial =>
      events.foldLeft[Either[BatError, ResponsesAssembler]](Right(initial)) {
        case (current, event) => current.flatMap(_.accept(event))
      }
    }

  private def toolTrace(
      reasoning: String,
      callId: String,
      responseId: String = "resp_1",
      arguments: String = "{\"path\":\"README.md\"}"
  ): Chunk[ResponsesEvent] =
    val splitReasoning = Math.min(2, reasoning.length)
    val splitArguments = Math.min(8, arguments.length)
    val reasoningRaw = reasoningItem("rs_1", reasoning)
    val callRaw = callItem("fc_1", callId, "read_file", arguments)
    Chunk(
      ResponsesEvent.Created(0, responseId, modelId),
      ResponsesEvent.Progress(1, responseId, "in_progress"),
      ResponsesEvent.OutputItemAdded(2, 0, reasoningAdded("rs_1")),
      ResponsesEvent.ContentPartAdded(
        3,
        "rs_1",
        0,
        0,
        reasoningPart("")
      ),
      ResponsesEvent.ReasoningDelta(
        4,
        "rs_1",
        0,
        0,
        reasoning.take(splitReasoning)
      ),
      ResponsesEvent.ReasoningDelta(
        5,
        "rs_1",
        0,
        0,
        reasoning.drop(splitReasoning)
      ),
      ResponsesEvent.ReasoningDone(6, "rs_1", 0, 0, reasoning),
      ResponsesEvent.ContentPartDone(
        7,
        "rs_1",
        0,
        0,
        reasoningPart(reasoning)
      ),
      ResponsesEvent.OutputItemDone(8, 0, reasoningRaw),
      ResponsesEvent.OutputItemAdded(9, 1, callAdded("fc_1", callId)),
      ResponsesEvent.FunctionArgumentsDelta(
        10,
        "fc_1",
        1,
        arguments.take(splitArguments)
      ),
      ResponsesEvent.FunctionArgumentsDelta(
        11,
        "fc_1",
        1,
        arguments.drop(splitArguments)
      ),
      ResponsesEvent.FunctionArgumentsDone(
        12,
        "fc_1",
        1,
        "read_file",
        arguments
      ),
      ResponsesEvent.OutputItemDone(13, 1, callRaw),
      ResponsesEvent.Completed(
        14,
        responseId,
        modelId,
        Chunk(reasoningRaw, callRaw),
        usage
      )
    )

  private def finalTrace(first: String, second: String): Chunk[ResponsesEvent] =
    val text = first + second
    val rawReasoning = reasoningItem("rs_final", "final reasoning")
    val rawMessage = messageItem("msg_1", text)
    Chunk(
      ResponsesEvent.Created(0, "resp_final", modelId),
      ResponsesEvent.OutputItemAdded(1, 0, reasoningAdded("rs_final")),
      ResponsesEvent.ContentPartAdded(
        2,
        "rs_final",
        0,
        0,
        reasoningPart("")
      ),
      ResponsesEvent.ReasoningDelta(3, "rs_final", 0, 0, "final "),
      ResponsesEvent.ReasoningDelta(4, "rs_final", 0, 0, "reasoning"),
      ResponsesEvent.ReasoningDone(5, "rs_final", 0, 0, "final reasoning"),
      ResponsesEvent.ContentPartDone(
        6,
        "rs_final",
        0,
        0,
        reasoningPart("final reasoning")
      ),
      ResponsesEvent.OutputItemDone(7, 0, rawReasoning)
    ) ++ messageItemEvents(8, 1, "msg_1", text, Chunk(first, second)) ++ Chunk(
      ResponsesEvent.Completed(
        15,
        "resp_final",
        modelId,
        Chunk(rawReasoning, rawMessage),
        usage
      )
    )

  private def messageItemEvents(
      start: Long,
      outputIndex: Int,
      id: String,
      text: String,
      deltas: Chunk[String] = Chunk.empty
  ): Chunk[ResponsesEvent] =
    val fragments = if deltas.isEmpty then Chunk(text) else deltas
    val deltaEvents = fragments.zipWithIndex.map { case (delta, index) =>
      ResponsesEvent.OutputTextDelta(
        start + 2 + index,
        id,
        outputIndex,
        0,
        delta
      )
    }
    val doneSequence = start + 2 + fragments.size
    Chunk(
      ResponsesEvent.OutputItemAdded(start, outputIndex, messageAdded(id)),
      ResponsesEvent.ContentPartAdded(
        start + 1,
        id,
        outputIndex,
        0,
        outputPart("")
      )
    ) ++ deltaEvents ++ Chunk(
      ResponsesEvent.OutputTextDone(
        doneSequence,
        id,
        outputIndex,
        0,
        text
      ),
      ResponsesEvent.ContentPartDone(
        doneSequence + 1,
        id,
        outputIndex,
        0,
        outputPart(text)
      ),
      ResponsesEvent.OutputItemDone(
        doneSequence + 2,
        outputIndex,
        messageItem(id, text)
      )
    )

  private def functionOutput(callId: String, value: Json): FunctionOutput =
    unsafe(
      CallId
        .from(callId)
        .flatMap(FunctionOutput.make(_, value))
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

  private def callAdded(id: String, callId: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("function_call"),
      "status" -> Json.Str("in_progress"),
      "call_id" -> Json.Str(callId),
      "name" -> Json.Str("read_file"),
      "arguments" -> Json.Str("")
    )

  private def callItem(
      id: String,
      callId: String,
      name: String,
      arguments: String
  ): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("function_call"),
      "status" -> Json.Str("completed"),
      "call_id" -> Json.Str(callId),
      "name" -> Json.Str(name),
      "arguments" -> Json.Str(arguments)
    )

  private def messageAdded(id: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("message"),
      "status" -> Json.Str("in_progress"),
      "role" -> Json.Str("assistant"),
      "content" -> Json.Arr(Chunk.empty)
    )

  private def messageItem(id: String, text: String): Json.Obj =
    obj(
      "id" -> Json.Str(id),
      "type" -> Json.Str("message"),
      "status" -> Json.Str("completed"),
      "role" -> Json.Str("assistant"),
      "content" -> Json.Arr(Chunk(outputPart(text)))
    )

  private def reasoningPart(text: String): Json.Obj =
    obj("type" -> Json.Str("reasoning_text"), "text" -> Json.Str(text))

  private def outputPart(text: String): Json.Obj =
    obj(
      "type" -> Json.Str("output_text"),
      "text" -> Json.Str(text),
      "annotations" -> Json.Arr(Chunk.empty)
    )

  private def terminalResponse(
      output: Chunk[Json.Obj],
      includeUsage: Boolean
  ): Json.Obj =
    val base = Chunk[(String, Json)](
      "id" -> Json.Str("resp_1"),
      "model" -> Json.Str(modelId),
      "status" -> Json.Str("completed"),
      "error" -> Json.Null,
      "incomplete_details" -> Json.Null,
      "output" -> Json.Arr(output.map(value => value: Json))
    )
    val fields =
      if includeUsage then base :+ ("usage" -> usageJson)
      else base
    Json.Obj(fields)

  private val usageJson: Json.Obj =
    obj(
      "input_tokens" -> number(6),
      "input_tokens_details" -> obj("cached_tokens" -> number(1)),
      "output_tokens" -> number(4),
      "output_tokens_details" -> obj("reasoning_tokens" -> number(2)),
      "total_tokens" -> number(10)
    )

  private def canonical(value: Json): String =
    unsafe(StrictJson.canonical(value))

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }

  private def booleanField(value: Json.Obj, name: String): Option[Boolean] =
    field(value, name).collect { case Json.Bool(result) => result }

  private def arrayField(value: Json.Obj, name: String): Option[Chunk[Json]] =
    field(value, name).collect { case Json.Arr(result) => result }

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))
