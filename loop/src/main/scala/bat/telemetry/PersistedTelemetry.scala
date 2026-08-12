package bat.telemetry

import bat.protocol.*

import java.math.BigDecimal as JBigDecimal

import scala.util.Try

import zio.Chunk
import zio.json.ast.Json

/** Strict decoder for telemetry that has crossed the persistence boundary.
  *
  * The decoder reconstructs the validated domain model, asks
  * [[TelemetryDocument]] to re-run its causal and arithmetic checks, and then
  * requires the regenerated canonical JSON to equal the supplied document.
  * Unknown, missing, reordered, or summary-only fields therefore cannot hide
  * behind a permissive JSON decoder.
  */
object PersistedTelemetry:
  def decode(value: Json.Obj): Either[TelemetryError, TelemetryDocument] =
    for
      _ <- literalString(value, "schema", TelemetryDocument.Schema)
      _ <- literalLong(value, "version", TelemetryDocument.Version.toLong)
      runId <- string(value, "run_id").flatMap(TelemetryRunId.from)
      deployment <- obj(value, "deployment").flatMap(decodeDeployment)
      recordsJson <- array(value, "records")
      records <- traverse(recordsJson)(decodeRecord)
      document <- TelemetryDocument.from(runId, deployment, records)
      actual <- canonical(value)
      expected <- document.canonicalJson
      _ <- require(
        actual == expected,
        "persisted telemetry does not match its validated canonical document"
      )
    yield document

  private def decodeDeployment(
      value: Json.Obj
  ): Either[TelemetryError, DeploymentFingerprint] =
    for
      backend <- string(value, "backend")
      modelId <- string(value, "model_id")
      modelRevision <- string(value, "model_revision")
      identity <- BackendIdentity
        .make(backend, modelId, modelRevision)
        .left
        .map(_ => invalid("persisted deployment identity is invalid"))
      runtime <- measurement(value, "runtime")(stringValue)
      runtimeRevision <- measurement(value, "runtime_revision")(stringValue)
      protocol <- string(value, "protocol")
      template <- measurement(value, "template_revision")(stringValue)
      quantization <- measurement(value, "quantization")(stringValue)
      topology <- measurement(value, "topology")(stringValue)
      nodes <- measurement(value, "node_count")(longValue)
      deployment <- DeploymentFingerprint.make(
        identity,
        runtime,
        runtimeRevision,
        protocol,
        template,
        quantization,
        topology,
        nodes
      )
    yield deployment

  private def decodeRecord(
      value: Json
  ): Either[TelemetryError, TelemetryRecord] =
    for
      record <- asObject(value, "telemetry record")
      sequence <- long(record, "sequence")
      event <- obj(record, "event").flatMap(decodeEvent)
    yield TelemetryRecord(sequence, event)

  private def decodeEvent(
      value: Json.Obj
  ): Either[TelemetryError, TelemetryEvent] =
    string(value, "type").flatMap {
      case "run_started" =>
        for
          mode <- string(value, "mode").flatMap(enumByWire(RunMode.values, _))
          pins <- obj(value, "pins").flatMap(decodePins)
          budgets <- obj(value, "budgets").flatMap(decodeBudgets)
        yield TelemetryEvent.RunStarted(mode, pins, budgets)
      case "bdr_checkpoint" =>
        obj(value, "bdr")
          .flatMap(decodeBdr)
          .map(TelemetryEvent.BdrCheckpoint(_))
      case "model_turn" =>
        for
          bdr <- obj(value, "bdr").flatMap(decodeBdr)
          kind <- string(value, "kind").flatMap(
            enumByWire(ModelTurnKind.values, _)
          )
          tokens <- obj(value, "tokens").flatMap(decodeTokens)
          timing <- obj(value, "timing").flatMap(decodeTiming)
          error <- measurement(value, "error_code")(
            stringValue(_).flatMap(TelemetryCode.from)
          )
        yield TelemetryEvent.ModelTurn(bdr, kind, tokens, timing, error)
      case "provider_attempt" =>
        for
          bdr <- obj(value, "bdr").flatMap(decodeBdr)
          attempt <- int(value, "attempt")
          outcome <- string(value, "outcome").flatMap(
            enumByWire(ProviderAttemptOutcome.values, _)
          )
          timing <- obj(value, "timing").flatMap(decodeTiming)
          error <- measurement(value, "error_code")(
            stringValue(_).flatMap(TelemetryCode.from)
          )
        yield TelemetryEvent.ProviderAttempt(
          bdr,
          attempt,
          outcome,
          timing,
          error
        )
      case "retry" =>
        for
          bdr <- obj(value, "bdr").flatMap(decodeBdr)
          failed <- int(value, "failed_attempt")
          next <- int(value, "next_attempt")
          delay <- long(value, "delay_millis")
          reason <- string(value, "reason_code").flatMap(TelemetryCode.from)
        yield TelemetryEvent.Retry(bdr, failed, next, delay, reason)
      case "tool_execution" =>
        for
          name <- string(value, "name").flatMap(TelemetryToolName.from)
          before <- obj(value, "before").flatMap(decodeBdr)
          after <- measurement(value, "after") {
            case child: Json.Obj => decodeBdr(child)
            case _               => invalidValue("tool after checkpoint")
          }
          outcome <- string(value, "outcome").flatMap(
            enumByWire(ToolExecutionOutcome.values, _)
          )
          duration <- measurement(value, "duration_millis")(longValue)
          error <- measurement(value, "error_code")(
            stringValue(_).flatMap(TelemetryCode.from)
          )
        yield TelemetryEvent.ToolExecution(
          name,
          before,
          after,
          outcome,
          duration,
          error
        )
      case "run_completed" =>
        for
          outcome <- string(value, "outcome").flatMap(
            enumByWire(RunOutcome.values, _)
          )
          iterations <- int(value, "iterations")
          toolCalls <- int(value, "tool_calls")
          totalTokens <- long(value, "total_tokens")
          wallMillis <- long(value, "wall_millis")
          bdr <- obj(value, "final_bdr").flatMap(decodeBdr)
        yield TelemetryEvent.RunCompleted(
          outcome,
          iterations,
          toolCalls,
          totalTokens,
          wallMillis,
          bdr
        )
      case "run_failed" =>
        for
          code <- string(value, "error_code").flatMap(TelemetryCode.from)
          wallMillis <- long(value, "wall_millis")
        yield TelemetryEvent.RunFailed(code, wallMillis)
      case _ => invalidValue("telemetry event type")
    }

  private def decodePins(
      value: Json.Obj
  ): Either[TelemetryError, TelemetryRunPins] =
    for
      identity <- string(value, "identity_digest")
      effort <- string(value, "reasoning_effort_digest")
      prompt <- string(value, "prompt_version_digest")
      commit <- string(value, "bdr_commit")
      pins <- TelemetryRunPins.fromPersisted(
        identity,
        effort,
        prompt,
        commit
      )
    yield pins

  private def decodeBudgets(
      value: Json.Obj
  ): Either[TelemetryError, BudgetLimits] =
    for
      iterations <- int(value, "max_iterations")
      tools <- int(value, "max_tool_calls")
      wall <- long(value, "max_wall_millis")
      tokens <- long(value, "max_total_tokens")
      budgets <- BudgetLimits
        .make(
          iterations,
          tools,
          zio.Duration.fromMillis(wall),
          tokens
        )
        .left
        .map(_ => invalid("persisted telemetry budgets are invalid"))
    yield budgets

  private def decodeBdr(
      value: Json.Obj
  ): Either[TelemetryError, BdrAttribution] =
    for
      iteration <- int(value, "iteration")
      revision <- long(value, "revision")
      runState <- string(value, "run_state")
      digest <- string(value, "state_digest")
      action <- measurement(value, "action")(stringValue)
      slice <- measurement(value, "slice_id")(stringValue)
      phase <- measurement(value, "phase")(
        stringValue(_).flatMap(text =>
          BdrPhase
            .from(text)
            .toRight(invalid("persisted BDR phase is invalid"))
        )
      )
      _ <- validateBdrMeasurements(action, slice, phase)
    yield BdrAttribution.fromPersisted(
      iteration,
      revision,
      runState,
      digest,
      action,
      slice,
      phase
    )

  private def validateBdrMeasurements(
      action: Measurement[String],
      slice: Measurement[String],
      phase: Measurement[BdrPhase]
  ): Either[TelemetryError, Unit] =
    val machine = "^[a-z][a-z0-9_-]{0,63}$".r
    val sliceId = "^[A-Z][A-Z0-9]*-[0-9]{1,12}$".r
    def valid[A](
        value: Measurement[A],
        observed: A => Boolean
    ): Boolean = value match
      case Measurement.Observed(result)    => observed(result)
      case Measurement.Unavailable(reason) =>
        reason == MissingReason.NotApplicable
    require(
      valid(action, machine.matches) &&
        valid(slice, sliceId.matches) &&
        valid(phase, _ => true),
      "persisted BDR attribution measurements are invalid"
    )

  private def decodeTokens(
      value: Json.Obj
  ): Either[TelemetryError, TokenMeasurements] =
    for
      total <- measurement(value, "total")(longValue)
      input <- measurement(value, "input")(longValue)
      cached <- measurement(value, "cached_input")(longValue)
      output <- measurement(value, "output")(longValue)
      reasoning <- measurement(value, "reasoning")(longValue)
    yield TokenMeasurements(total, input, cached, output, reasoning)

  private def decodeTiming(
      value: Json.Obj
  ): Either[TelemetryError, ModelTimingMeasurements] =
    for
      total <- measurement(value, "total_millis")(longValue)
      headers <- measurement(value, "response_headers_millis")(longValue)
      first <- measurement(value, "first_event_millis")(longValue)
      stream <- measurement(value, "stream_millis")(longValue)
    yield ModelTimingMeasurements(total, headers, first, stream)

  private def measurement[A](
      parent: Json.Obj,
      name: String
  )(
      decode: Json => Either[TelemetryError, A]
  ): Either[TelemetryError, Measurement[A]] =
    obj(parent, name).flatMap { value =>
      (field(value, "value"), field(value, "unavailable_reason")) match
        case (Some(observed), Some(Json.Null)) if observed != Json.Null =>
          decode(observed).map(Measurement.Observed(_))
        case (Some(Json.Null), Some(Json.Str(reason))) =>
          MissingReason.values
            .find(_.wire == reason)
            .map(Measurement.Unavailable(_))
            .toRight(invalid("persisted measurement reason is invalid"))
        case _ => invalidValue("persisted measurement")
    }

  private def canonical(value: Json): Either[TelemetryError, String] =
    StrictJson
      .canonical(value, "persisted telemetry")
      .left
      .map(_ => invalid("persisted telemetry is not strict JSON"))

  private def obj(
      value: Json.Obj,
      name: String
  ): Either[TelemetryError, Json.Obj] =
    field(value, name) match
      case Some(result: Json.Obj) => Right(result)
      case _                      => invalidValue(name)

  private def array(
      value: Json.Obj,
      name: String
  ): Either[TelemetryError, Chunk[Json]] =
    field(value, name) match
      case Some(Json.Arr(result)) => Right(result)
      case _                      => invalidValue(name)

  private def string(
      value: Json.Obj,
      name: String
  ): Either[TelemetryError, String] =
    field(value, name) match
      case Some(child) => stringValue(child)
      case None        => invalidValue(name)

  private def stringValue(value: Json): Either[TelemetryError, String] =
    value match
      case Json.Str(result) => Right(result)
      case _                => invalidValue("string")

  private def long(
      value: Json.Obj,
      name: String
  ): Either[TelemetryError, Long] =
    field(value, name) match
      case Some(child) => longValue(child)
      case None        => invalidValue(name)

  private def longValue(value: Json): Either[TelemetryError, Long] =
    value match
      case number: Json.Num =>
        Try(number.value.longValueExact()).toEither.left.map(_ =>
          invalid("persisted telemetry integer is out of range")
        )
      case _ => invalidValue("integer")

  private def int(
      value: Json.Obj,
      name: String
  ): Either[TelemetryError, Int] =
    long(value, name).flatMap(result =>
      Either.cond(
        result >= Int.MinValue && result <= Int.MaxValue,
        result.toInt,
        invalid("persisted telemetry integer is out of range")
      )
    )

  private def literalString(
      value: Json.Obj,
      name: String,
      expected: String
  ): Either[TelemetryError, Unit] =
    string(value, name).flatMap(actual =>
      require(actual == expected, s"persisted telemetry $name is invalid")
    )

  private def literalLong(
      value: Json.Obj,
      name: String,
      expected: Long
  ): Either[TelemetryError, Unit] =
    long(value, name).flatMap(actual =>
      require(actual == expected, s"persisted telemetry $name is invalid")
    )

  private def asObject(
      value: Json,
      label: String
  ): Either[TelemetryError, Json.Obj] = value match
    case result: Json.Obj => Right(result)
    case _                => invalidValue(label)

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def enumByWire[A](
      values: Array[A],
      wire: String
  ): Either[TelemetryError, A] =
    values
      .find(value =>
        value match
          case item: RunMode                => item.wire == wire
          case item: RunOutcome             => item.wire == wire
          case item: ModelTurnKind          => item.wire == wire
          case item: ProviderAttemptOutcome => item.wire == wire
          case item: ToolExecutionOutcome   => item.wire == wire
          case _                            => false
      )
      .toRight(invalid("persisted telemetry enum value is invalid"))

  private def traverse[A, B](
      values: Iterable[A]
  )(
      decode: A => Either[TelemetryError, B]
  ): Either[TelemetryError, Chunk[B]] =
    values.foldLeft[Either[TelemetryError, Chunk[B]]](Right(Chunk.empty)) {
      case (result, value) =>
        for
          collected <- result
          next <- decode(value)
        yield collected :+ next
    }

  private def require(
      condition: Boolean,
      message: String
  ): Either[TelemetryError, Unit] =
    Either.cond(condition, (), invalid(message))

  private def invalidValue[A](label: String): Either[TelemetryError, A] =
    Left(invalid(s"persisted telemetry $label is invalid"))

  private def invalid(message: String): TelemetryError =
    TelemetryError.make("invalid_persisted_telemetry", message)
