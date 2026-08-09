package bat.protocol

import zio.*
import zio.json.ast.Json
import zio.test.*

object BackendBoundarySpec extends ZIOSpecDefault:
  private val defectCanary = "RAW_PROVIDER_DEFECT_7c1199"
  private val payloadCanary = "RAW_PROVIDER_PAYLOAD_92d8c1"

  private def unsafe[A](value: Either[BatError, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(error.safeMessage),
      result => result
    )

  private val identity =
    unsafe(BackendIdentity.make("openai", "gpt-5", "2026-08-07"))

  private val pins =
    unsafe(
      RunPins.make(
        identity,
        "high",
        "bdr-2.2",
        "abcdef0123456789abcdef0123456789abcdef01"
      )
    )

  private val usage = unsafe(Usage.make(totalTokens = 1))

  private val bdrState = unsafe(
    BdrStateView.make(
      unsafe(Revision.from(1)),
      "active",
      Json.Obj(Chunk("action" -> Json.Str("inspect"))),
      "0" * 64
    )
  )

  private final class Context
      extends OpaqueReasoningContext(
        identity,
        ContinuationMode.OpaqueReplay
      )

  private val request = unsafe(
    ModelRequest.make[Context](
      pins = pins,
      developer = unsafe(DeveloperInput.make("Follow BDR.")),
      inputs =
        Chunk(InputEvent.User(unsafe(UserInput.make("Audit this slice.")))),
      tools = Chunk.empty,
      bdrState = bdrState,
      iteration = 1,
      continuation = None
    )
  )

  private val turnBudget = unsafe(TurnBudget.make(1.second, 10))

  private val defectingBackend = new Backend:
    type Context = BackendBoundarySpec.Context

    val identity: BackendIdentity = BackendBoundarySpec.identity
    val capabilities: BackendCapabilities = unsafe(
      BackendCapabilities.make(
        Set(Capability.UsageReporting, Capability.ReasoningContinuity)
      )
    )

    protected def generate(
        request: ModelRequest[Context],
        budget: TurnBudget
    ): IO[BatError, ModelTurn[Context]] =
      ZIO.die(new RuntimeException(defectCanary))

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("backend safety boundary")(
      test("collapses adapter defects without exposing exception material") {
        defectingBackend.complete(request, turnBudget).either.map { result =>
          assertTrue(
            result match
              case Left(error: BatError.BackendFailure) =>
                error.code == "backend_adapter_defect" &&
                error.safeMessage == "backend adapter failed" &&
                !error.safeMessage.contains(defectCanary)
              case _ => false
          )
        }
      },
      test("does not collapse typed adapter failures") {
        val typed = new Backend:
          type Context = BackendBoundarySpec.Context

          val identity: BackendIdentity = BackendBoundarySpec.identity
          val capabilities: BackendCapabilities =
            defectingBackend.capabilities

          protected def generate(
              request: ModelRequest[Context],
              budget: TurnBudget
          ): IO[BatError, ModelTurn[Context]] =
            ZIO.fail(
              BatError.BackendFailure(
                "provider_unavailable",
                "provider unavailable",
                retryable = true
              )
            )

        typed.complete(request, turnBudget).either.map { result =>
          assertTrue(
            result.left.exists(_.code == "provider_unavailable")
          )
        }
      },
      test("provider protocol values render without provider payloads") {
        val callId = unsafe(CallId.from(payloadCanary))
        val call = unsafe(
          FunctionCall.make(
            callId,
            payloadCanary,
            Json.Obj(Chunk("secret" -> Json.Str(payloadCanary)))
          )
        )
        val output = unsafe(
          FunctionOutput.make(callId, Json.Str(payloadCanary))
        )
        val finalOutput = unsafe(FinalOutput.make(payloadCanary))
        val toolTurn = unsafe(
          ModelTurn.toolCalls(new Context, Chunk(call), usage)
        )
        val finalTurn = ModelTurn.completed(finalOutput, usage)
        val renderings = Chunk(
          unsafe(DeveloperInput.make(payloadCanary)).toString,
          unsafe(UserInput.make(payloadCanary)).toString,
          call.toString,
          output.toString,
          finalOutput.toString,
          request.toString,
          toolTurn.toString,
          finalTurn.toString
        )

        assertTrue(renderings.forall(!_.contains(payloadCanary)))
      }
    )
