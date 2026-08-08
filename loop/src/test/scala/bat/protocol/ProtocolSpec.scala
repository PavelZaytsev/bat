package bat.protocol

import scala.compiletime.testing.typeCheckErrors

import zio.*
import zio.json.ast.Json
import zio.test.*

object ProtocolSpec extends ZIOSpecDefault:
  private val validCommit = "abcdef0123456789abcdef0123456789abcdef01"

  private def identity(
      backend: String = "openai",
      modelId: String = "gpt-5",
      revision: String = "2026-08-07"
  ): Either[BatError, BackendIdentity] =
    BackendIdentity.make(backend, modelId, revision)

  private def pins(
      reasoningEffort: String = "high",
      promptVersion: String = "bdr-2.2",
      bdrCommit: String = validCommit
  ): Either[BatError, RunPins] =
    identity().flatMap(
      RunPins.make(_, reasoningEffort, promptVersion, bdrCommit)
    )

  private def usage(total: Long = 3): Either[BatError, Usage] =
    Usage.make(
      totalTokens = total,
      inputTokens = Some(1),
      outputTokens = Some(2)
    )

  private def call(id: String): Either[BatError, FunctionCall] =
    for
      callId <- CallId.from(id)
      arguments <- StrictJson.parseObject("""{"path":"README.md"}""")
      result <- FunctionCall.make(callId, "read_file", arguments)
    yield result

  private final class TestContext(
      identity: BackendIdentity,
      mode: ContinuationMode = ContinuationMode.OpaqueReplay
  ) extends OpaqueReasoningContext(identity, mode)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("provider-neutral protocol")(
      suite("pins")(
        test("accepts fully pinned run metadata") {
          assertTrue(pins().isRight)
        },
        test("rejects placeholder backend identity values") {
          val result = identity(revision = "latest")
          assertTrue(result.isLeft)
        },
        test("rejects placeholder reasoning effort and malformed BDR commit") {
          assertTrue(
            pins(reasoningEffort = "default").isLeft,
            pins(bdrCommit = "NOT-A-COMMIT").isLeft
          )
        }
      ),
      suite("budgets")(
        test("accepts positive finite limits") {
          val result = BudgetLimits.make(
            maxIterations = 4,
            maxToolCalls = 8,
            maxWallTime = 30.seconds,
            maxTotalTokens = 1000
          )
          assertTrue(result.isRight)
        },
        test("rejects every non-positive limit") {
          assertTrue(
            BudgetLimits.make(0, 1, 1.second, 1).isLeft,
            BudgetLimits.make(1, 0, 1.second, 1).isLeft,
            BudgetLimits.make(1, 1, Duration.Zero, 1).isLeft,
            BudgetLimits.make(1, 1, 1.second, 0).isLeft
          )
        },
        test("rejects durations that overflow clock unit conversions") {
          val enormous = Duration.fromSeconds(Long.MaxValue)
          assertTrue(
            BudgetLimits.make(1, 1, enormous, 1).isLeft,
            TurnBudget
              .make(enormous, 1)
              .left
              .toOption
              .contains(
                BatError.BudgetExceeded(BudgetKind.WallTime)
              )
          )
        }
      ),
      suite("usage")(
        test("accepts a consistent token breakdown") {
          val result = Usage.make(
            totalTokens = 15,
            inputTokens = Some(10),
            cachedInputTokens = Some(4),
            outputTokens = Some(5),
            reasoningTokens = Some(3)
          )
          assertTrue(result.isRight)
        },
        test("rejects negative and internally inconsistent counts") {
          assertTrue(
            Usage.make(totalTokens = -1).isLeft,
            Usage
              .make(
                totalTokens = 10,
                inputTokens = Some(4),
                cachedInputTokens = Some(5)
              )
              .isLeft,
            Usage
              .make(
                totalTokens = 10,
                outputTokens = Some(4),
                reasoningTokens = Some(5)
              )
              .isLeft,
            Usage
              .make(
                totalTokens = 8,
                inputTokens = Some(5),
                outputTokens = Some(4)
              )
              .isLeft
          )
        }
      ),
      suite("capabilities")(
        test("server-side state depends on reasoning continuity") {
          val invalid =
            BackendCapabilities.make(Set(Capability.ServerSideState))
          val valid = BackendCapabilities.make(
            Set(Capability.ServerSideState, Capability.ReasoningContinuity)
          )
          assertTrue(invalid.isLeft, valid.isRight)
        },
        test("full-writer negotiation fails closed") {
          val result = BackendCapabilities
            .make(Set(Capability.UsageReporting))
            .flatMap(_.negotiate(RunMode.FullWriter))
          assertTrue(
            result match
              case Left(BatError.BackendIncompatible(missing)) =>
                missing == Set(
                  Capability.ReasoningContinuity,
                  Capability.StrictTools
                )
              case _ => false
          )
        },
        test("audit negotiation also requires reasoning continuity") {
          val result = BackendCapabilities
            .make(Set(Capability.UsageReporting))
            .flatMap(_.negotiate(RunMode.Audit))
          assertTrue(
            result == Left(
              BatError.BackendIncompatible(
                Set(Capability.ReasoningContinuity)
              )
            )
          )
        },
        test(
          "full-writer negotiation requires usage, continuity, and strict tools"
        ) {
          val required = Set(
            Capability.UsageReporting,
            Capability.ReasoningContinuity,
            Capability.StrictTools
          )
          val result = BackendCapabilities
            .make(required + Capability.Streaming)
            .flatMap(_.negotiate(RunMode.FullWriter))
          assertTrue(result.exists(_.required == required))
        }
      ),
      suite("strict JSON")(
        test("rejects duplicate keys at any object depth") {
          val topLevel = StrictJson.parse("""{"x":1,"x":2}""")
          val nested = StrictJson.parse("""{"outer":{"x":1,"x":2}}""")
          assertTrue(topLevel.isLeft, nested.isLeft)
        },
        test(
          "canonicalizes object keys recursively without reordering arrays"
        ) {
          val result = StrictJson
            .parse("""{"z":{"b":1,"a":2},"a":[{"d":4,"c":3},0]}""")
            .flatMap(StrictJson.canonical(_))
          assertTrue(
            result == Right("""{"a":[{"c":3,"d":4},0],"z":{"a":2,"b":1}}""")
          )
        },
        test("equivalent object order produces the same replay digest") {
          val result = for
            left <- StrictJson.parse("""{"b":2,"a":{"y":1,"x":0}}""")
            right <- StrictJson.parse("""{"a":{"x":0,"y":1},"b":2}""")
            leftDigest <- StrictJson.sha256(left)
            rightDigest <- StrictJson.sha256(right)
          yield leftDigest == rightDigest
          assertTrue(result == Right(true))
        }
      ),
      suite("normalized model turns")(
        test("requires a non-null context and at least one call") {
          val result = for
            backendIdentity <- identity()
            eventUsage <- usage()
            eventCall <- call("call-1")
          yield (
            ModelTurn.toolCalls(
              null.asInstanceOf[TestContext],
              Chunk(eventCall),
              eventUsage
            ),
            ModelTurn.toolCalls(
              new TestContext(backendIdentity),
              Chunk.empty,
              eventUsage
            )
          )
          assertTrue(result.exists { case (withoutContext, withoutCalls) =>
            withoutContext.isLeft && withoutCalls.isLeft
          })
        },
        test("rejects duplicate call IDs in one turn") {
          val result = for
            backendIdentity <- identity()
            eventUsage <- usage()
            eventCall <- call("call-1")
            turn <- ModelTurn.toolCalls(
              new TestContext(backendIdentity),
              Chunk(eventCall, eventCall),
              eventUsage
            )
          yield turn
          assertTrue(result.isLeft)
        },
        test("accepts a context with distinct calls") {
          val result = for
            backendIdentity <- identity()
            eventUsage <- usage()
            first <- call("call-1")
            second <- call("call-2")
            turn <- ModelTurn.toolCalls(
              new TestContext(backendIdentity),
              Chunk(first, second),
              eventUsage
            )
          yield turn
          assertTrue(result.isRight)
        }
      ),
      test("provider error codes are bounded safe machine codes") {
        assertTrue(
          ProviderError.make("rate_limited-2", "retry later").isRight,
          ProviderError.make("INVALID CODE", "hidden").isLeft,
          ProviderError.make("a" * 65, "hidden").isLeft
        )
      },
      test("opaque reasoning context has no JSON encoder") {
        val errors = typeCheckErrors(
          "summon[zio.json.JsonEncoder[bat.protocol.OpaqueReasoningContext]]"
        )
        assertTrue(errors.nonEmpty)
      }
    )
