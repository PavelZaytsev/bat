package bat.protocol

import zio.{IO, ZIO}

/** A provider adapter owns its concrete continuation type. The controller can
  * retain and return that value, but it cannot unwrap provider reasoning state.
  */
trait Backend:
  type Context <: OpaqueReasoningContext

  def identity: BackendIdentity
  def capabilities: BackendCapabilities

  protected def generate(
      request: ModelRequest[Context],
      budget: TurnBudget
  ): IO[BatError, ModelTurn[Context]]

  final def complete(
      request: ModelRequest[Context],
      budget: TurnBudget
  ): IO[BatError, ModelTurn[Context]] =
    ZIO
      .fromEither(validateRequest(request))
      .zipRight(safelyGenerate(request, budget))
      .flatMap(turn => ZIO.fromEither(validateTurn(turn)))

  /** Provider SDK failures must cross the adapter boundary as typed, safe
    * errors. In particular, an exception message may contain prompt or
    * reasoning material, so defects are deliberately collapsed to a stable
    * controller-owned error. Interruption remains interruption.
    */
  private def safelyGenerate(
      request: ModelRequest[Context],
      budget: TurnBudget
  ): IO[BatError, ModelTurn[Context]] =
    generate(request, budget).catchAllCause { cause =>
      if cause.isInterrupted then ZIO.refailCause(cause)
      else if cause.defects.isEmpty then
        cause.failureOption match
          case Some(error) => ZIO.fail(error)
          case None        => ZIO.refailCause(cause)
      else
        ZIO.fail(
          BatError.BackendFailure(
            errorCode = "backend_adapter_defect",
            safeMessage = "backend adapter failed",
            retryable = false
          )
        )
    }

  private def validateRequest(
      request: ModelRequest[Context]
  ): Either[BatError, Unit] =
    if request.pins.identity != identity then
      Left(
        BatError.ProtocolViolation(
          "backend identity does not match the pinned run identity"
        )
      )
    else
      request.continuation match
        case Some(context) if context.identity != identity =>
          Left(
            BatError.ProtocolViolation(
              "opaque reasoning context cannot cross backend or model identity"
            )
          )
        case Some(context)
            if context.mode == ContinuationMode.ServerState &&
              !capabilities.contains(Capability.ServerSideState) =>
          Left(
            BatError.ProtocolViolation(
              "backend received unnegotiated server-side state"
            )
          )
        case _ => Right(())

  private def validateTurn(
      turn: ModelTurn[Context]
  ): Either[BatError, ModelTurn[Context]] =
    turn match
      case ModelTurn.ToolCalls(context, calls, _) =>
        if context.identity != identity then
          Left(
            BatError.ProtocolViolation(
              "backend returned reasoning context for a different identity"
            )
          )
        else if !capabilities.contains(Capability.ReasoningContinuity) then
          Left(
            BatError.ProtocolViolation(
              "backend returned unnegotiated reasoning context"
            )
          )
        else if context.mode == ContinuationMode.ServerState &&
          !capabilities.contains(Capability.ServerSideState)
        then
          Left(
            BatError.ProtocolViolation(
              "backend returned unnegotiated server-side state"
            )
          )
        else if calls.size > 1 && !capabilities.contains(
            Capability.ParallelCalls
          )
        then
          Left(
            BatError.ProtocolViolation(
              "backend emitted parallel calls without that capability"
            )
          )
        else Right(turn)
      case _ => Right(turn)
