package bat.controller

import bat.bdr.{BdrSession, ValidatedBdrState}
import bat.protocol.*
import bat.trace.{SafeTrace, SafeTraceDocument, SafeTraceEvent}

import zio.*
import zio.json.ast.Json

final case class LoopResult(
    finalOutput: FinalOutput,
    outcome: RunOutcome,
    pins: RunPins,
    bdrState: ValidatedBdrState,
    iterations: Int,
    toolCalls: Int,
    totalTokens: Long,
    trace: Chunk[SafeTraceEvent]
):
  def traceDocument: SafeTraceDocument = SafeTraceDocument.make(trace)

object AgenticLoop:
  private final case class ExecutedCall(
      toolName: String,
      argumentsDigest: String,
      output: FunctionOutput
  )

  private final case class PreparedCall(
      call: FunctionCall,
      digest: String,
      cached: Option[FunctionOutput]
  )

  private final case class LoopState[C <: OpaqueReasoningContext](
      bdr: ValidatedBdrState,
      pendingInputs: Chunk[InputEvent],
      continuation: Option[C],
      iterations: Int,
      toolCalls: Int,
      totalTokens: Long,
      ledger: Map[CallId, ExecutedCall],
      trace: Chunk[SafeTraceEvent]
  )

  def run(
      spec: RunSpec,
      backend: Backend,
      tools: ToolRegistry,
      bdr: BdrSession
  ): IO[BatError, LoopResult] =
    for
      _ <- ZIO
        .fail(
          BatError.ProtocolViolation(
            "backend identity does not match the pinned run identity"
          )
        )
        .unless(backend.identity == spec.pins.identity)
      negotiated <- ZIO.fromEither(
        backend.capabilities.negotiate(spec.mode, spec.requiredCapabilities)
      )
      _ <- ZIO
        .fail(
          BatError.ProtocolViolation(
            "loaded BDR engine does not match the pinned BDR commit"
          )
        )
        .unless(bdr.engineCommit == spec.pins.bdrCommit)
      _ <- ZIO
        .fail(
          BatError.ProtocolViolation(
            "full-writer tools must all use strict schemas"
          )
        )
        .when(spec.mode == RunMode.FullWriter && !tools.allStrict)
      startedNanos <- Clock.nanoTime
      result <- runWithinBudget(
        spec,
        backend,
        tools,
        bdr,
        negotiated,
        startedNanos
      ).timeoutFail(BatError.BudgetExceeded(BudgetKind.WallTime))(
        spec.budgets.maxWallTime
      )
    yield result

  private def runWithinBudget(
      spec: RunSpec,
      backend: Backend,
      tools: ToolRegistry,
      bdr: BdrSession,
      negotiated: NegotiatedCapabilities,
      startedNanos: Long
  ): IO[BatError, LoopResult] =
    for
      initialBdr <- bdr.checkpoint
      initial = LoopState[backend.Context](
        bdr = initialBdr,
        pendingInputs = Chunk(InputEvent.User(spec.user)),
        continuation = None,
        iterations = 0,
        toolCalls = 0,
        totalTokens = 0L,
        ledger = Map.empty,
        trace = Chunk(
          SafeTrace.runStart(
            spec.mode,
            spec.pins,
            initialBdr.view,
            negotiated,
            spec.budgets
          ),
          SafeTrace.developer(spec.developer),
          SafeTrace.user(spec.user)
        )
      )
      result <- iterate(
        spec,
        backend,
        tools,
        bdr,
        negotiated,
        startedNanos,
        initial
      )
    yield result

  private def iterate(
      spec: RunSpec,
      backend: Backend,
      tools: ToolRegistry,
      bdr: BdrSession,
      negotiated: NegotiatedCapabilities,
      startedNanos: Long,
      state: LoopState[backend.Context]
  ): IO[BatError, LoopResult] =
    for
      iteration <- reserveIteration(state.iterations, spec.budgets)
      now <- Clock.nanoTime
      remainingWall <- remainingWallTime(startedNanos, now, spec.budgets)
      remainingTokens = spec.budgets.maxTotalTokens - state.totalTokens
      turnBudget <- ZIO.fromEither(
        TurnBudget.make(remainingWall, remainingTokens)
      )
      request <- ZIO.fromEither(
        ModelRequest.make[backend.Context](
          pins = spec.pins,
          developer = spec.developer,
          inputs = state.pendingInputs,
          tools = tools.definitionsFor(spec.mode),
          bdrState = state.bdr.view,
          iteration = iteration,
          continuation = state.continuation
        )
      )
      traceWithIteration = state.trace :+ SafeTrace.iteration(iteration)
      turn <- backend.complete(request, turnBudget)
      result <- turn match
        case ModelTurn.ToolCalls(context, calls, usage) =>
          handleToolTurn(
            spec,
            backend,
            tools,
            bdr,
            negotiated,
            startedNanos,
            state.copy(iterations = iteration, trace = traceWithIteration),
            context,
            calls,
            usage
          )
        case ModelTurn.Completed(output, usage) =>
          handleCompleted(
            spec,
            bdr,
            state.copy(iterations = iteration, trace = traceWithIteration),
            output,
            usage
          )
        case ModelTurn.Failed(error, usage) =>
          accountUsage(state.totalTokens, usage, spec.budgets).flatMap { _ =>
            ZIO.fail(BatError.ProviderFailure(error))
          }
    yield result

  private def handleToolTurn(
      spec: RunSpec,
      backend: Backend,
      tools: ToolRegistry,
      bdr: BdrSession,
      negotiated: NegotiatedCapabilities,
      startedNanos: Long,
      state: LoopState[backend.Context],
      context: backend.Context,
      calls: Chunk[FunctionCall],
      usage: Usage
  ): IO[BatError, LoopResult] =
    for
      totalTokens <- accountUsage(state.totalTokens, usage, spec.budgets)
      prepared <- ZIO.fromEither(
        prepareCalls(
          calls,
          tools,
          spec.mode,
          state.ledger,
          state.toolCalls,
          spec.budgets
        )
      )
      freshCount = prepared.count(_.cached.isEmpty)
      execution <- executePrepared(prepared, tools, spec.mode, state.ledger)
      (outputs, ledger) = execution
      latestBdr <- bdr.current
      nextTrace =
        state.trace ++
          Chunk(SafeTrace.reasoning(context)) ++
          calls.map(SafeTrace.functionCall) ++
          Chunk(SafeTrace.usage(usage)) ++
          outputs.map(SafeTrace.functionOutput)
      nextState = LoopState[backend.Context](
        bdr = latestBdr,
        pendingInputs = outputs.map(InputEvent.ToolOutput(_)),
        continuation = Some(context),
        iterations = state.iterations,
        toolCalls = state.toolCalls + freshCount,
        totalTokens = totalTokens,
        ledger = ledger,
        trace = nextTrace
      )
      result <- iterate(
        spec,
        backend,
        tools,
        bdr,
        negotiated,
        startedNanos,
        nextState
      )
    yield result

  private def handleCompleted[C <: OpaqueReasoningContext](
      spec: RunSpec,
      bdr: BdrSession,
      state: LoopState[C],
      output: FinalOutput,
      usage: Usage
  ): IO[BatError, LoopResult] =
    for
      totalTokens <- accountUsage(state.totalTokens, usage, spec.budgets)
      checkpoint <- bdr.checkpoint
      outcome <- ZIO.fromEither(completionOutcome(spec.mode, checkpoint))
      completedTrace =
        state.trace ++ Chunk(
          SafeTrace.finalOutput(output),
          SafeTrace.usage(usage),
          SafeTrace.runComplete(
            outcome,
            checkpoint.view,
            state.iterations,
            state.toolCalls,
            totalTokens
          )
        )
    yield LoopResult(
      finalOutput = output,
      outcome = outcome,
      pins = spec.pins,
      bdrState = checkpoint,
      iterations = state.iterations,
      toolCalls = state.toolCalls,
      totalTokens = totalTokens,
      trace = completedTrace
    )

  private def reserveIteration(
      used: Int,
      limits: BudgetLimits
  ): IO[BatError, Int] =
    if used >= limits.maxIterations then
      ZIO.fail(BatError.BudgetExceeded(BudgetKind.Iterations))
    else ZIO.succeed(used + 1)

  private def remainingWallTime(
      startedNanos: Long,
      nowNanos: Long,
      limits: BudgetLimits
  ): IO[BatError, Duration] =
    val elapsed = Math.max(0L, nowNanos - startedNanos)
    val limit = limits.maxWallTime.toNanos
    if elapsed >= limit then
      ZIO.fail(BatError.BudgetExceeded(BudgetKind.WallTime))
    else ZIO.succeed(Duration.fromNanos(limit - elapsed))

  private def accountUsage(
      used: Long,
      usage: Usage,
      limits: BudgetLimits
  ): IO[BatError, Long] =
    if usage.totalTokens > limits.maxTotalTokens - used then
      ZIO.fail(BatError.BudgetExceeded(BudgetKind.TotalTokens))
    else ZIO.succeed(used + usage.totalTokens)

  private def prepareCalls(
      calls: Chunk[FunctionCall],
      tools: ToolRegistry,
      mode: RunMode,
      ledger: Map[CallId, ExecutedCall],
      usedToolCalls: Int,
      limits: BudgetLimits
  ): Either[BatError, Chunk[PreparedCall]] =
    val duplicateIds =
      calls.map(_.callId).groupBy(identity).exists(_._2.size > 1)
    if duplicateIds then
      Left(
        BatError.ProtocolViolation(
          "tool turn contains duplicate call_id values"
        )
      )
    else
      calls
        .foldLeft[Either[BatError, Chunk[PreparedCall]]](Right(Chunk.empty)) {
          (result, call) =>
            for
              prepared <- result
              _ <- tools.validate(call, mode)
              digest <- StrictJson.sha256(call.arguments, "function arguments")
              cached <- ledger.get(call.callId) match
                case None => Right(None)
                case Some(previous)
                    if previous.toolName == call.name && previous.argumentsDigest == digest =>
                  Right(Some(previous.output))
                case Some(_) =>
                  Left(
                    BatError.ProtocolViolation(
                      s"call_id ${call.callId.value} was reused with different content"
                    )
                  )
            yield prepared :+ PreparedCall(call, digest, cached)
        }
        .flatMap { prepared =>
          val fresh = prepared.count(_.cached.isEmpty)
          if fresh > limits.maxToolCalls - usedToolCalls then
            Left(BatError.BudgetExceeded(BudgetKind.ToolCalls))
          else Right(prepared)
        }

  private def executePrepared(
      prepared: Chunk[PreparedCall],
      tools: ToolRegistry,
      mode: RunMode,
      initialLedger: Map[CallId, ExecutedCall]
  ): IO[BatError, (Chunk[FunctionOutput], Map[CallId, ExecutedCall])] =
    ZIO.foldLeft(prepared)((Chunk.empty[FunctionOutput], initialLedger)) {
      case ((outputs, ledger), item) =>
        item.cached match
          case Some(output) => ZIO.succeed((outputs :+ output, ledger))
          case None         =>
            tools.execute(item.call, mode).map { output =>
              val recorded = ExecutedCall(item.call.name, item.digest, output)
              (outputs :+ output, ledger.updated(item.call.callId, recorded))
            }
    }

  private val TerminalStates = Set(
    "verification_pending",
    "needs_human",
    "blocked_environment",
    "stale_input",
    "non_convergent",
    "failed_verification"
  )

  private def completionOutcome(
      mode: RunMode,
      state: ValidatedBdrState
  ): Either[BatError, RunOutcome] =
    mode match
      case RunMode.Audit      => Right(RunOutcome.AuditComplete)
      case RunMode.FullWriter => terminalOutcome(state)

  private def terminalOutcome(
      state: ValidatedBdrState
  ): Either[BatError, RunOutcome] =
    val action = stringField(state.nextAction, "action").getOrElse("<missing>")
    action match
      case "handoff" if state.runState == "ready_for_review" =>
        Right(RunOutcome.ReadyForReview)
      case "handoff_terminal"
          if TerminalStates.contains(state.runState) &&
            stringField(state.nextAction, "state").contains(state.runState) &&
            stringField(state.nextAction, "reason").exists(_.trim.nonEmpty) =>
        Right(RunOutcome.TerminalHandoff)
      case _ => Left(BatError.PrematureFinal(state.runState, action))

  private def stringField(obj: Json.Obj, name: String): Option[String] =
    obj.fields.collectFirst { case (`name`, Json.Str(value)) => value }
