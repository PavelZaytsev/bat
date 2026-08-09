package bat.telemetry

import bat.protocol.*

import java.math.{BigDecimal as JBigDecimal, RoundingMode}

import zio.Chunk
import zio.json.ast.Json

final case class TelemetryRecord(sequence: Long, event: TelemetryEvent):
  override def toString: String =
    s"TelemetryRecord(sequence=$sequence, event=<redacted>)"

final case class PhaseSummary(
    phase: Measurement[BdrPhase],
    modelTurns: Int,
    toolExecutions: Int,
    tokens: TokenMeasurements
):
  override def toString: String = "PhaseSummary(payload=<redacted>)"

final case class RunMeasurements(
    tokens: TokenMeasurements,
    wallMillis: Measurement[Long],
    meanFirstEventMillis: Measurement[JBigDecimal],
    outputTokensPerSecond: Measurement[JBigDecimal],
    nodeHours: Measurement[JBigDecimal],
    costUsd: Measurement[JBigDecimal]
):
  override def toString: String = "RunMeasurements(payload=<redacted>)"

final case class TelemetrySummary(
    modelTurns: Int,
    providerAttempts: Int,
    retries: Int,
    toolExecutions: Int,
    measurements: RunMeasurements,
    phases: Chunk[PhaseSummary]
):
  override def toString: String = "TelemetrySummary(payload=<redacted>)"

/** Validated, versioned, payload-free run telemetry. */
final case class TelemetryDocument private (
    runId: TelemetryRunId,
    deployment: DeploymentFingerprint,
    records: Chunk[TelemetryRecord],
    summary: TelemetrySummary
):
  override def toString: String =
    s"TelemetryDocument(runId=<redacted>, records=${records.size}, payload=<redacted>)"

  def json: Json.Obj = TelemetryJson.document(this)

  def canonicalJson: Either[TelemetryError, String] =
    StrictJson
      .canonical(json, "BAT telemetry document")
      .left
      .map(_ =>
        TelemetryError.make(
          "telemetry_encoding_failed",
          "telemetry document could not be encoded"
        )
      )

object TelemetryDocument:
  val Schema = "bat.dev/run-telemetry"
  val Version = 1

  def from(
      runId: TelemetryRunId,
      deployment: DeploymentFingerprint,
      records: Chunk[TelemetryRecord]
  ): Either[TelemetryError, TelemetryDocument] =
    for
      _ <- validateEnvelope(deployment, records)
      _ <- validateRecords(records)
      _ <- validateFlow(records)
      summary <- summarize(deployment, records)
      _ <- validateCompletion(records, summary)
    yield TelemetryDocument(runId, deployment, records, summary)

  private def validateEnvelope(
      deployment: DeploymentFingerprint,
      records: Chunk[TelemetryRecord]
  ): Either[TelemetryError, Unit] =
    val starts = records.collect {
      case TelemetryRecord(_, event: TelemetryEvent.RunStarted) => event
    }
    val terminals = records.collect {
      case TelemetryRecord(_, event: TelemetryEvent.RunCompleted) => event
      case TelemetryRecord(_, event: TelemetryEvent.RunFailed)    => event
    }
    for
      _ <- require(records.nonEmpty, "telemetry record is empty")
      _ <- require(
        records.map(_.sequence) == Chunk.fromIterable(
          1L to records.size.toLong
        ),
        "telemetry sequences must be contiguous from one"
      )
      _ <- require(starts.size == 1, "telemetry requires exactly one run start")
      _ <- require(
        records.head.event.isInstanceOf[TelemetryEvent.RunStarted],
        "run start must be the first telemetry event"
      )
      _ <- require(
        terminals.size == 1,
        "telemetry requires exactly one terminal event"
      )
      _ <- require(
        records.last.event.isInstanceOf[TelemetryEvent.RunCompleted] ||
          records.last.event.isInstanceOf[TelemetryEvent.RunFailed],
        "the terminal telemetry event must be last"
      )
      _ <- require(
        starts.head.pins.identityDigest == deployment.identity.digest,
        "deployment identity does not match run pins"
      )
    yield ()

  private def validateRecords(
      records: Chunk[TelemetryRecord]
  ): Either[TelemetryError, Unit] =
    records.foldLeft[Either[TelemetryError, Unit]](Right(())) {
      case (result, record) => result.flatMap(_ => validate(record.event))
    }

  private final case class FlowState(
      lastTurnIteration: Int = 0,
      lastTurnKind: Option[ModelTurnKind] = None,
      providerIteration: Option[Int] = None,
      lastAttempt: Int = 0,
      lastAttemptOutcome: Option[ProviderAttemptOutcome] = None,
      lastAttemptError: Option[TelemetryCode] = None,
      expectedAttempt: Option[Int] = None,
      providerAttribution: Option[BdrAttribution] = None,
      lastRevision: Long = -1L,
      mustTerminate: Boolean = false
  )

  /** Validate the causal order independently of event construction. This makes
    * a persisted document evidence, rather than just well-shaped JSON.
    */
  private def validateFlow(
      records: Chunk[TelemetryRecord]
  ): Either[TelemetryError, Unit] =
    records
      .foldLeft[Either[TelemetryError, FlowState]](
        Right(FlowState())
      ) { (result, record) =>
        result.flatMap { state =>
          if state.mustTerminate &&
            !record.event.isInstanceOf[TelemetryEvent.RunFailed]
          then
            Left(
              TelemetryError.make(
                "invalid_telemetry_document",
                "telemetry continues after a failed state observation"
              )
            )
          else advanceFlow(state, record.event)
        }
      }
      .map(_ => ())

  private def advanceFlow(
      state: FlowState,
      event: TelemetryEvent
  ): Either[TelemetryError, FlowState] =
    event match
      case _: TelemetryEvent.RunStarted              => Right(state)
      case TelemetryEvent.BdrCheckpoint(attribution) =>
        for
          _ <- require(
            attribution.iteration == state.lastTurnIteration,
            "BDR checkpoint does not belong to the active iteration"
          )
          next <- advanceRevisions(state, Chunk(attribution))
        yield next
      case TelemetryEvent.ProviderAttempt(
            attribution,
            attempt,
            outcome,
            _,
            errorCode
          ) =>
        val expectedIteration = state.lastTurnIteration + 1
        val allowedAttempt = state.providerIteration match
          case None            => attempt == 1
          case Some(iteration) =>
            iteration == expectedIteration &&
            state.expectedAttempt.contains(attempt) &&
            state.providerAttribution.contains(attribution)
        for
          _ <- require(
            state.lastTurnKind.forall(_ == ModelTurnKind.ToolCalls),
            "provider attempt follows a terminal model turn"
          )
          _ <- require(
            attribution.iteration == expectedIteration,
            "provider attempt does not belong to the next model iteration"
          )
          _ <- require(
            allowedAttempt,
            "provider attempts are not contiguous from one"
          )
          next <- advanceRevisions(state, Chunk(attribution))
        yield next.copy(
          providerIteration = Some(expectedIteration),
          lastAttempt = attempt,
          lastAttemptOutcome = Some(outcome),
          lastAttemptError = observed(errorCode),
          expectedAttempt = None,
          providerAttribution = Some(attribution)
        )
      case TelemetryEvent.Retry(
            attribution,
            failedAttempt,
            nextAttempt,
            _,
            reasonCode
          ) =>
        for
          _ <- require(
            state.providerIteration.contains(attribution.iteration) &&
              state.providerAttribution.contains(attribution),
            "retry does not belong to the failed provider attempt"
          )
          _ <- require(
            state.lastAttempt == failedAttempt &&
              state.lastAttemptOutcome.contains(
                ProviderAttemptOutcome.Rejected
              ) && state.expectedAttempt.isEmpty,
            "retry is not preceded by a rejected provider attempt"
          )
          _ <- require(
            state.lastAttemptError.contains(reasonCode),
            "retry reason does not match the rejected attempt"
          )
          next <- advanceRevisions(state, Chunk(attribution))
        yield next.copy(expectedAttempt = Some(nextAttempt))
      case TelemetryEvent.ModelTurn(attribution, kind, _, _, _) =>
        val expectedIteration = state.lastTurnIteration + 1
        val providerConsistent = state.providerIteration match
          case None            => true
          case Some(iteration) =>
            val outcomeMatches = kind match
              case ModelTurnKind.BackendFailed =>
                state.lastAttemptOutcome.exists(
                  _ != ProviderAttemptOutcome.Completed
                )
              case _ =>
                state.lastAttemptOutcome.contains(
                  ProviderAttemptOutcome.Completed
                )
            val retryStateMatches = kind match
              case ModelTurnKind.BackendFailed => true
              case _                           => state.expectedAttempt.isEmpty
            iteration == expectedIteration &&
            retryStateMatches &&
            state.providerAttribution.contains(attribution) &&
            outcomeMatches
        for
          _ <- require(
            state.lastTurnKind.forall(_ == ModelTurnKind.ToolCalls),
            "model turn follows a terminal model turn"
          )
          _ <- require(
            attribution.iteration == expectedIteration,
            "model-turn iterations must be contiguous from one"
          )
          _ <- require(
            providerConsistent,
            "model turn contradicts its provider-attempt history"
          )
          next <- advanceRevisions(state, Chunk(attribution))
        yield next.copy(
          lastTurnIteration = expectedIteration,
          lastTurnKind = Some(kind),
          providerIteration = None,
          lastAttempt = 0,
          lastAttemptOutcome = None,
          lastAttemptError = None,
          expectedAttempt = None,
          providerAttribution = None
        )
      case TelemetryEvent.ToolExecution(_, before, after, _, _, _) =>
        val measuredAfter = observed(after)
        for
          _ <- require(
            state.lastTurnKind.contains(ModelTurnKind.ToolCalls) &&
              before.iteration == state.lastTurnIteration &&
              measuredAfter.forall(_.iteration == state.lastTurnIteration),
            "tool execution does not belong to a tool-call turn"
          )
          _ <- measuredAfter match
            case Some(value) =>
              require(
                value.revision >= before.revision,
                "tool execution moves the BDR revision backwards"
              )
            case None => Right(())
          next <- advanceRevisions(
            state,
            Chunk(before) ++ Chunk.fromIterable(measuredAfter)
          )
        yield next.copy(mustTerminate = measuredAfter.isEmpty)
      case completed: TelemetryEvent.RunCompleted =>
        for
          _ <- require(
            state.lastTurnKind.contains(ModelTurnKind.Completed) &&
              completed.iterations == state.lastTurnIteration &&
              completed.finalBdr.iteration == state.lastTurnIteration &&
              state.providerIteration.isEmpty,
            "completed run does not follow its final model turn"
          )
          next <- advanceRevisions(state, Chunk(completed.finalBdr))
        yield next
      case _: TelemetryEvent.RunFailed => Right(state)

  private def advanceRevisions(
      state: FlowState,
      attributions: Chunk[BdrAttribution]
  ): Either[TelemetryError, FlowState] =
    attributions
      .foldLeft[Either[TelemetryError, Long]](
        Right(state.lastRevision)
      ) { (result, attribution) =>
        result.flatMap(previous =>
          require(
            attribution.revision >= previous,
            "BDR revisions move backwards in telemetry"
          ).map(_ => attribution.revision)
        )
      }
      .map(revision => state.copy(lastRevision = revision))

  private def validate(event: TelemetryEvent): Either[TelemetryError, Unit] =
    event match
      case TelemetryEvent.RunStarted(_, pins, _) =>
        validateRunPins(pins)
      case TelemetryEvent.BdrCheckpoint(attribution) =>
        validateAttribution(attribution, allowZeroIteration = true)
      case TelemetryEvent.ModelTurn(
            attribution,
            kind,
            tokens,
            timing,
            errorCode
          ) =>
        for
          _ <- validateAttribution(attribution, allowZeroIteration = false)
          _ <- validateTokens(tokens)
          _ <- validateTiming(timing)
          _ <- validateCode(errorCode)
          _ <- validateModelTurnError(kind, errorCode)
        yield ()
      case TelemetryEvent.ProviderAttempt(
            attribution,
            attempt,
            outcome,
            timing,
            errorCode
          ) =>
        for
          _ <- validateAttribution(attribution, allowZeroIteration = false)
          _ <- require(attempt > 0, "provider attempt must be positive")
          _ <- validateTiming(timing)
          _ <- validateCode(errorCode)
          _ <- validateProviderAttemptError(outcome, errorCode)
        yield ()
      case TelemetryEvent.Retry(
            attribution,
            failedAttempt,
            nextAttempt,
            delayMillis,
            reasonCode
          ) =>
        for
          _ <- validateAttribution(attribution, allowZeroIteration = false)
          _ <- require(
            failedAttempt > 0 && nextAttempt == failedAttempt + 1,
            "retry attempt sequence is invalid"
          )
          _ <- require(delayMillis >= 0, "retry delay cannot be negative")
          _ <- validateTelemetryCode(reasonCode)
        yield ()
      case TelemetryEvent.ToolExecution(
            name,
            before,
            after,
            outcome,
            duration,
            errorCode
          ) =>
        for
          _ <- validateToolName(name)
          _ <- validateAttribution(before, allowZeroIteration = false)
          _ <- validateMeasuredAttribution(after)
          _ <- require(
            TelemetryValidation.nonNegative(duration),
            "tool duration cannot be negative"
          )
          _ <- validateCode(errorCode)
          _ <- validateToolError(outcome, errorCode)
          _ <- validateToolShape(outcome, before, after, duration)
        yield ()
      case TelemetryEvent.RunCompleted(
            _,
            iterations,
            toolCalls,
            totalTokens,
            wallMillis,
            finalBdr
          ) =>
        for
          _ <- require(
            iterations >= 0 && toolCalls >= 0 && totalTokens >= 0 && wallMillis >= 0,
            "completed run counters cannot be negative"
          )
          _ <- validateAttribution(finalBdr, allowZeroIteration = true)
        yield ()
      case TelemetryEvent.RunFailed(errorCode, wallMillis) =>
        for
          _ <- validateTelemetryCode(errorCode)
          _ <- require(
            wallMillis >= 0,
            "failed run duration cannot be negative"
          )
        yield ()

  private def validateAttribution(
      value: BdrAttribution,
      allowZeroIteration: Boolean
  ): Either[TelemetryError, Unit] =
    val iterationValid =
      if allowZeroIteration then value.iteration >= 0 else value.iteration > 0
    for
      _ <- require(iterationValid, "BDR telemetry iteration is invalid")
      _ <- require(value.revision >= 0, "BDR telemetry revision is invalid")
      _ <- require(
        TelemetryCode.from(value.runState).isRight,
        "BDR run state is invalid"
      )
      _ <- require(
        value.stateDigest.matches("^[0-9a-f]{64}$"),
        "BDR state digest is invalid"
      )
    yield ()

  private def validateRunPins(
      pins: TelemetryRunPins
  ): Either[TelemetryError, Unit] =
    val digestPattern = "^sha256:[0-9a-f]{64}$"
    val commitPattern = "^(?:[0-9a-f]{40}|[0-9a-f]{64})$"
    for
      _ <- require(pins != null, "run pins are required")
      _ <- require(
        pins.identityDigest.value.matches(digestPattern),
        "run identity digest is invalid"
      )
      _ <- require(
        pins.reasoningEffortDigest.value.matches(digestPattern),
        "reasoning effort digest is invalid"
      )
      _ <- require(
        pins.promptVersionDigest.value.matches(digestPattern),
        "prompt version digest is invalid"
      )
      _ <- require(
        pins.bdrCommit != null && pins.bdrCommit.matches(commitPattern),
        "BDR commit pin is invalid"
      )
    yield ()

  private def validateMeasuredAttribution(
      value: Measurement[BdrAttribution]
  ): Either[TelemetryError, Unit] =
    value match
      case Measurement.Observed(attribution) =>
        validateAttribution(attribution, allowZeroIteration = false)
      case Measurement.Unavailable(_) => Right(())

  private def validateTelemetryCode(
      value: TelemetryCode
  ): Either[TelemetryError, Unit] =
    require(
      TelemetryCode.from(value.value).isRight,
      "telemetry error code is invalid"
    )

  private def validateToolName(
      value: TelemetryToolName
  ): Either[TelemetryError, Unit] =
    require(
      TelemetryToolName.from(value.value).isRight,
      "tool telemetry name is invalid"
    )

  private def validateModelTurnError(
      kind: ModelTurnKind,
      error: Measurement[TelemetryCode]
  ): Either[TelemetryError, Unit] =
    val shouldHaveError =
      kind == ModelTurnKind.ProviderFailed || kind == ModelTurnKind.BackendFailed
    validateErrorPresence(shouldHaveError, error, "model turn")

  private def validateProviderAttemptError(
      outcome: ProviderAttemptOutcome,
      error: Measurement[TelemetryCode]
  ): Either[TelemetryError, Unit] =
    validateErrorPresence(
      outcome != ProviderAttemptOutcome.Completed,
      error,
      "provider attempt"
    )

  private def validateToolError(
      outcome: ToolExecutionOutcome,
      error: Measurement[TelemetryCode]
  ): Either[TelemetryError, Unit] =
    validateErrorPresence(
      outcome == ToolExecutionOutcome.ToolError ||
        outcome == ToolExecutionOutcome.Failed,
      error,
      "tool execution"
    )

  private def validateToolShape(
      outcome: ToolExecutionOutcome,
      before: BdrAttribution,
      after: Measurement[BdrAttribution],
      duration: Measurement[Long]
  ): Either[TelemetryError, Unit] =
    outcome match
      case ToolExecutionOutcome.Replayed =>
        require(
          after == Measurement.Observed(before) &&
            duration == Measurement.Unavailable(MissingReason.NotApplicable),
          "replayed tools must preserve state and have no execution duration"
        )
      case _ =>
        val durationObserved = duration match
          case Measurement.Observed(_)    => true
          case Measurement.Unavailable(_) => false
        val afterValid = after match
          case Measurement.Observed(_)         => true
          case Measurement.Unavailable(reason) =>
            reason == MissingReason.FailedBeforeMeasurement
        require(
          durationObserved && afterValid,
          "fresh tool execution has invalid duration or post-state evidence"
        )

  private def validateErrorPresence(
      required: Boolean,
      value: Measurement[TelemetryCode],
      label: String
  ): Either[TelemetryError, Unit] =
    val observed = value match
      case Measurement.Observed(_)    => true
      case Measurement.Unavailable(_) => false
    require(
      observed == required,
      s"$label error-code presence contradicts its outcome"
    )

  private def validateCode(
      value: Measurement[TelemetryCode]
  ): Either[TelemetryError, Unit] =
    value match
      case Measurement.Observed(code) =>
        validateTelemetryCode(code)
      case Measurement.Unavailable(_) => Right(())

  private def validateTiming(
      value: ModelTimingMeasurements
  ): Either[TelemetryError, Unit] =
    val all = List(
      value.totalMillis,
      value.responseHeadersMillis,
      value.firstEventMillis,
      value.streamMillis
    )
    for
      _ <- require(
        all.forall(TelemetryValidation.nonNegative),
        "model timing cannot be negative"
      )
      _ <- requireMeasuredAtMost(
        value.responseHeadersMillis,
        value.totalMillis,
        "response headers exceed total model time"
      )
      _ <- requireMeasuredAtMost(
        value.firstEventMillis,
        value.totalMillis,
        "first event exceeds total model time"
      )
      _ <- requireMeasuredAtMost(
        value.streamMillis,
        value.totalMillis,
        "stream time exceeds total model time"
      )
      _ <- requireMeasuredAtMost(
        value.responseHeadersMillis,
        value.firstEventMillis,
        "response headers occur after the first event"
      )
      _ <- (value.firstEventMillis, value.streamMillis, value.totalMillis) match
        case (
              Measurement.Observed(firstEvent),
              Measurement.Observed(stream),
              Measurement.Observed(total)
            ) =>
          require(
            firstEvent <= total && stream <= total - firstEvent,
            "first-event and stream time exceed total model time"
          )
        case _ => Right(())
    yield ()

  private def requireMeasuredAtMost(
      left: Measurement[Long],
      right: Measurement[Long],
      message: String
  ): Either[TelemetryError, Unit] =
    observedPair(left, right) match
      case Some((a, b)) => require(a <= b, message)
      case None         => Right(())

  private def validateTokens(
      value: TokenMeasurements
  ): Either[TelemetryError, Unit] =
    val all = List(
      value.total,
      value.input,
      value.cachedInput,
      value.output,
      value.reasoning
    )
    for
      _ <- require(
        all.forall(TelemetryValidation.nonNegative),
        "token measurements cannot be negative"
      )
      _ <- observedPair(value.cachedInput, value.input) match
        case Some((cached, input)) =>
          require(cached <= input, "cached input tokens exceed input tokens")
        case None => Right(())
      _ <- observedPair(value.reasoning, value.output) match
        case Some((reasoning, output)) =>
          require(reasoning <= output, "reasoning tokens exceed output tokens")
        case None => Right(())
      _ <- (value.total, value.input, value.output) match
        case (
              Measurement.Observed(total),
              Measurement.Observed(input),
              Measurement.Observed(output)
            ) =>
          require(
            total >= input && total - input >= output,
            "total tokens are smaller than input plus output tokens"
          )
        case _ => Right(())
    yield ()

  private def observedPair(
      left: Measurement[Long],
      right: Measurement[Long]
  ): Option[(Long, Long)] =
    (left, right) match
      case (Measurement.Observed(a), Measurement.Observed(b)) => Some(a -> b)
      case _                                                  => None

  private def summarize(
      deployment: DeploymentFingerprint,
      records: Chunk[TelemetryRecord]
  ): Either[TelemetryError, TelemetrySummary] =
    val turns = records.collect {
      case TelemetryRecord(_, event: TelemetryEvent.ModelTurn) => event
    }
    val attempts = records.collect {
      case TelemetryRecord(_, event: TelemetryEvent.ProviderAttempt) => event
    }
    val retries = records.count(_.event.isInstanceOf[TelemetryEvent.Retry])
    val tools = records.collect {
      case TelemetryRecord(_, event: TelemetryEvent.ToolExecution) => event
    }
    val freshTools = tools.filter(_.outcome != ToolExecutionOutcome.Replayed)
    val terminal = records.last.event
    val wall = terminal match
      case value: TelemetryEvent.RunCompleted =>
        Measurement.Observed(value.wallMillis)
      case value: TelemetryEvent.RunFailed =>
        Measurement.Observed(value.wallMillis)
      case _ => Measurement.Unavailable(MissingReason.NotObserved)
    val tokens = sumTokens(turns.map(_.tokens))
    val ttftValues =
      attempts.flatMap(attempt => observed(attempt.timing.firstEventMillis))
    val meanTtft = mean(ttftValues)
    val throughput = outputThroughput(turns, attempts)
    val nodeHours = (deployment.nodeCount, wall) match
      case (Measurement.Observed(nodes), Measurement.Observed(millis)) =>
        Measurement.Observed(
          JBigDecimal
            .valueOf(nodes)
            .multiply(JBigDecimal.valueOf(millis))
            .divide(JBigDecimal.valueOf(3600000L), 9, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        )
      case (Measurement.Unavailable(reason), _) =>
        Measurement.Unavailable(reason)
      case _ => Measurement.Unavailable(MissingReason.NotObserved)
    val phases = phaseSummaries(turns, tools)
    Right(
      TelemetrySummary(
        turns.size,
        attempts.size,
        retries,
        freshTools.size,
        RunMeasurements(
          tokens,
          wall,
          meanTtft,
          throughput,
          nodeHours,
          Measurement.Unavailable(MissingReason.NotConfigured)
        ),
        phases
      )
    )

  private def sumTokens(values: Chunk[TokenMeasurements]): TokenMeasurements =
    if values.isEmpty then
      TokenMeasurements.unavailable(MissingReason.NotObserved)
    else
      TokenMeasurements(
        sum(values.map(_.total)),
        sum(values.map(_.input)),
        sum(values.map(_.cachedInput)),
        sum(values.map(_.output)),
        sum(values.map(_.reasoning))
      )

  private def sum(values: Chunk[Measurement[Long]]): Measurement[Long] =
    values.collectFirst { case Measurement.Unavailable(reason) => reason } match
      case Some(reason) => Measurement.Unavailable(reason)
      case None         =>
        Measurement.Observed(
          values.collect { case Measurement.Observed(value) => value }.sum
        )

  private def phaseSummaries(
      turns: Chunk[TelemetryEvent.ModelTurn],
      tools: Chunk[TelemetryEvent.ToolExecution]
  ): Chunk[PhaseSummary] =
    val keys = (
      turns.map(event => phaseKey(event.attribution.phase)) ++
        tools.map(event => phaseKey(event.before.phase))
    ).distinct.sorted
    Chunk.fromIterable(keys.map { key =>
      val matchingTurns =
        turns.filter(event => phaseKey(event.attribution.phase) == key)
      val matchingTools =
        tools.filter(event => phaseKey(event.before.phase) == key)
      val phase = matchingTurns.headOption
        .map(_.attribution.phase)
        .orElse(matchingTools.headOption.map(_.before.phase))
        .getOrElse(Measurement.Unavailable(MissingReason.NotApplicable))
      PhaseSummary(
        phase,
        matchingTurns.size,
        matchingTools.count(_.outcome != ToolExecutionOutcome.Replayed),
        sumTokens(matchingTurns.map(_.tokens))
      )
    })

  private def phaseKey(value: Measurement[BdrPhase]): String =
    value match
      case Measurement.Observed(phase)     => f"0-${phase.ordinal}%02d"
      case Measurement.Unavailable(reason) => s"1-${reason.wire}"

  private def mean(values: Chunk[Long]): Measurement[JBigDecimal] =
    if values.isEmpty then Measurement.Unavailable(MissingReason.NotObserved)
    else
      Measurement.Observed(
        JBigDecimal
          .valueOf(values.sum)
          .divide(
            JBigDecimal.valueOf(values.size.toLong),
            6,
            RoundingMode.HALF_UP
          )
          .stripTrailingZeros()
      )

  private def outputThroughput(
      turns: Chunk[TelemetryEvent.ModelTurn],
      attempts: Chunk[TelemetryEvent.ProviderAttempt]
  ): Measurement[JBigDecimal] =
    val byIteration = attempts
      .filter(_.outcome == ProviderAttemptOutcome.Completed)
      .groupBy(_.attribution.iteration)
      .view
      .mapValues(_.last)
      .toMap
    val pairs = turns.flatMap { turn =>
      for
        output <- observed(turn.tokens.output)
        attempt <- byIteration.get(turn.attribution.iteration)
        stream <- observed(attempt.timing.streamMillis)
        if stream > 0
      yield output -> stream
    }
    if pairs.size != turns.size || pairs.isEmpty then
      Measurement.Unavailable(MissingReason.NotObserved)
    else
      val output = pairs.map(_._1).sum
      val millis = pairs.map(_._2).sum
      Measurement.Observed(
        JBigDecimal
          .valueOf(output)
          .multiply(JBigDecimal.valueOf(1000L))
          .divide(JBigDecimal.valueOf(millis), 6, RoundingMode.HALF_UP)
          .stripTrailingZeros()
      )

  private def observed[A](value: Measurement[A]): Option[A] = value match
    case Measurement.Observed(result) => Some(result)
    case Measurement.Unavailable(_)   => None

  private def validateCompletion(
      records: Chunk[TelemetryRecord],
      summary: TelemetrySummary
  ): Either[TelemetryError, Unit] =
    records.last.event match
      case completed: TelemetryEvent.RunCompleted =>
        for
          _ <- require(
            summary.modelTurns == completed.iterations,
            "model turns do not reconcile with completed iterations"
          )
          _ <- require(
            summary.toolExecutions == completed.toolCalls,
            "fresh tool executions do not reconcile with completed tool calls"
          )
          _ <- summary.measurements.tokens.total match
            case Measurement.Observed(total) =>
              require(
                total == completed.totalTokens,
                "model-turn tokens do not reconcile with completed run"
              )
            case Measurement.Unavailable(_) =>
              Left(
                TelemetryError.make(
                  "token_reconciliation_failed",
                  "completed runs require reported model-turn token totals"
                )
              )
        yield ()
      case _: TelemetryEvent.RunFailed => Right(())
      case _ => require(false, "terminal telemetry event is missing")

  private def require(
      condition: Boolean,
      message: String
  ): Either[TelemetryError, Unit] =
    Either.cond(
      condition,
      (),
      TelemetryError.make("invalid_telemetry_document", message)
    )

private object TelemetryJson:
  def document(value: TelemetryDocument): Json.Obj =
    obj(
      "schema" -> Json.Str(TelemetryDocument.Schema),
      "version" -> number(TelemetryDocument.Version.toLong),
      "run_id" -> Json.Str(value.runId.value),
      "deployment" -> deployment(value.deployment),
      "summary" -> summary(value.summary),
      "records" -> Json.Arr(value.records.map(record))
    )

  private def deployment(value: DeploymentFingerprint): Json.Obj =
    obj(
      "backend" -> Json.Str(value.identity.backend),
      "model_id" -> Json.Str(value.identity.modelId),
      "model_revision" -> Json.Str(value.identity.modelRevision),
      "runtime" -> measurement(value.runtime)(Json.Str(_)),
      "runtime_revision" -> measurement(value.runtimeRevision)(Json.Str(_)),
      "protocol" -> Json.Str(value.protocol),
      "template_revision" -> measurement(value.templateRevision)(Json.Str(_)),
      "quantization" -> measurement(value.quantization)(Json.Str(_)),
      "topology" -> measurement(value.topology)(Json.Str(_)),
      "node_count" -> measurement(value.nodeCount)(number)
    )

  private def summary(value: TelemetrySummary): Json.Obj =
    obj(
      "model_turns" -> number(value.modelTurns.toLong),
      "provider_attempts" -> number(value.providerAttempts.toLong),
      "retries" -> number(value.retries.toLong),
      "tool_executions" -> number(value.toolExecutions.toLong),
      "measurements" -> runMeasurements(value.measurements),
      "phases" -> Json.Arr(value.phases.map(phaseSummary))
    )

  private def runMeasurements(value: RunMeasurements): Json.Obj =
    obj(
      "tokens" -> tokens(value.tokens),
      "wall_millis" -> measurement(value.wallMillis)(number),
      "mean_first_event_millis" -> measurement(value.meanFirstEventMillis)(
        decimal
      ),
      "output_tokens_per_second" -> measurement(value.outputTokensPerSecond)(
        decimal
      ),
      "node_hours" -> measurement(value.nodeHours)(decimal),
      "cost_usd" -> measurement(value.costUsd)(decimal)
    )

  private def phaseSummary(value: PhaseSummary): Json.Obj =
    obj(
      "phase" -> measurement(value.phase)(phase => Json.Str(phase.wire)),
      "model_turns" -> number(value.modelTurns.toLong),
      "tool_executions" -> number(value.toolExecutions.toLong),
      "tokens" -> tokens(value.tokens)
    )

  private def record(value: TelemetryRecord): Json.Obj =
    obj(
      "sequence" -> number(value.sequence),
      "event" -> event(value.event)
    )

  private def event(value: TelemetryEvent): Json.Obj = value match
    case TelemetryEvent.RunStarted(mode, pins, budgets) =>
      obj(
        "type" -> Json.Str("run_started"),
        "mode" -> Json.Str(mode.wire),
        "pins" -> runPins(pins),
        "budgets" -> budgetLimits(budgets)
      )
    case TelemetryEvent.BdrCheckpoint(attribution) =>
      obj(
        "type" -> Json.Str("bdr_checkpoint"),
        "bdr" -> bdr(attribution)
      )
    case TelemetryEvent.ModelTurn(
          attribution,
          kind,
          tokenValues,
          timing,
          error
        ) =>
      obj(
        "type" -> Json.Str("model_turn"),
        "bdr" -> bdr(attribution),
        "kind" -> Json.Str(kind.wire),
        "tokens" -> tokens(tokenValues),
        "timing" -> timings(timing),
        "error_code" -> measurement(error)(value => Json.Str(value.value))
      )
    case TelemetryEvent.ProviderAttempt(
          attribution,
          attempt,
          outcome,
          timing,
          error
        ) =>
      obj(
        "type" -> Json.Str("provider_attempt"),
        "bdr" -> bdr(attribution),
        "attempt" -> number(attempt.toLong),
        "outcome" -> Json.Str(outcome.wire),
        "timing" -> timings(timing),
        "error_code" -> measurement(error)(value => Json.Str(value.value))
      )
    case TelemetryEvent.Retry(
          attribution,
          failedAttempt,
          nextAttempt,
          delayMillis,
          reasonCode
        ) =>
      obj(
        "type" -> Json.Str("retry"),
        "bdr" -> bdr(attribution),
        "failed_attempt" -> number(failedAttempt.toLong),
        "next_attempt" -> number(nextAttempt.toLong),
        "delay_millis" -> number(delayMillis),
        "reason_code" -> Json.Str(reasonCode.value)
      )
    case TelemetryEvent.ToolExecution(
          name,
          before,
          after,
          outcome,
          duration,
          error
        ) =>
      obj(
        "type" -> Json.Str("tool_execution"),
        "name" -> Json.Str(name.value),
        "before" -> bdr(before),
        "after" -> measurement(after)(bdr),
        "outcome" -> Json.Str(outcome.wire),
        "duration_millis" -> measurement(duration)(number),
        "error_code" -> measurement(error)(value => Json.Str(value.value))
      )
    case TelemetryEvent.RunCompleted(
          outcome,
          iterations,
          toolCalls,
          totalTokens,
          wallMillis,
          finalBdr
        ) =>
      obj(
        "type" -> Json.Str("run_completed"),
        "outcome" -> Json.Str(outcome.wire),
        "iterations" -> number(iterations.toLong),
        "tool_calls" -> number(toolCalls.toLong),
        "total_tokens" -> number(totalTokens),
        "wall_millis" -> number(wallMillis),
        "final_bdr" -> bdr(finalBdr)
      )
    case TelemetryEvent.RunFailed(errorCode, wallMillis) =>
      obj(
        "type" -> Json.Str("run_failed"),
        "error_code" -> Json.Str(errorCode.value),
        "wall_millis" -> number(wallMillis)
      )

  private def runPins(value: TelemetryRunPins): Json.Obj =
    obj(
      "identity_digest" -> Json.Str(value.identityDigest.value),
      "reasoning_effort_digest" -> Json.Str(
        value.reasoningEffortDigest.value
      ),
      "prompt_version_digest" -> Json.Str(value.promptVersionDigest.value),
      "bdr_commit" -> Json.Str(value.bdrCommit)
    )

  private def budgetLimits(value: BudgetLimits): Json.Obj =
    obj(
      "max_iterations" -> number(value.maxIterations.toLong),
      "max_tool_calls" -> number(value.maxToolCalls.toLong),
      "max_wall_millis" -> number(value.maxWallTime.toMillis),
      "max_total_tokens" -> number(value.maxTotalTokens)
    )

  private def bdr(value: BdrAttribution): Json.Obj =
    obj(
      "iteration" -> number(value.iteration.toLong),
      "revision" -> number(value.revision),
      "run_state" -> Json.Str(value.runState),
      "state_digest" -> Json.Str(value.stateDigest),
      "action" -> measurement(value.action)(Json.Str(_)),
      "slice_id" -> measurement(value.sliceId)(Json.Str(_)),
      "phase" -> measurement(value.phase)(phase => Json.Str(phase.wire))
    )

  private def tokens(value: TokenMeasurements): Json.Obj =
    obj(
      "total" -> measurement(value.total)(number),
      "input" -> measurement(value.input)(number),
      "cached_input" -> measurement(value.cachedInput)(number),
      "output" -> measurement(value.output)(number),
      "reasoning" -> measurement(value.reasoning)(number)
    )

  private def timings(value: ModelTimingMeasurements): Json.Obj =
    obj(
      "total_millis" -> measurement(value.totalMillis)(number),
      "response_headers_millis" -> measurement(value.responseHeadersMillis)(
        number
      ),
      "first_event_millis" -> measurement(value.firstEventMillis)(number),
      "stream_millis" -> measurement(value.streamMillis)(number)
    )

  private def measurement[A](
      value: Measurement[A]
  )(encode: A => Json): Json.Obj =
    value match
      case Measurement.Observed(result) =>
        obj(
          "value" -> encode(result),
          "unavailable_reason" -> Json.Null
        )
      case Measurement.Unavailable(reason) =>
        obj(
          "value" -> Json.Null,
          "unavailable_reason" -> Json.Str(reason.wire)
        )

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def number(value: Long): Json.Num =
    Json.Num(JBigDecimal.valueOf(value))

  private def decimal(value: JBigDecimal): Json.Num =
    Json.Num(value)
