package bat.quickstart

import bat.protocol.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import zio.*
import zio.json.ast.Json

/** Deterministic provider substitute for the six-phase portability canary.
  *
  * It uses the same model/tool boundary as a hosted or GPT-OSS adapter. The
  * only scripted part is inference: every workspace change, verification, BDR
  * transition, completion check, and commit still crosses the strict tool
  * surface. This makes the canary useful for transport and orchestration while
  * making no claim about model intelligence.
  */
final class ScriptedToyBackend private (
    val identity: BackendIdentity,
    val capabilities: BackendCapabilities,
    patches: Map[String, String],
    state: Ref.Synchronized[ScriptedToyBackend.State]
) extends Backend:
  import ScriptedToyBackend.*

  type Context = ScriptedContext

  protected def generate(
      request: ModelRequest[ScriptedContext],
      budget: TurnBudget
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    state.modifyZIO { previous =>
      val observed = observe(previous, request)
      turn(observed.step, observed, request).map { result =>
        result -> observed.copy(step = observed.step + 1)
      }
    }

  private def turn(
      step: Int,
      current: State,
      request: ModelRequest[ScriptedContext]
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    for
      _ <- require(
        request.bdrState.revision.value == expectedBdrRevision(step),
        s"script step $step observed the wrong BDR revision"
      )
      result <- step match
        case 0 => toolTurn(step, "toy_workspace_state", obj())
        case 1 => testTurn(step, current, "baseline")
        case 2 =>
          baseline(lastResult(request)).flatMap(operation =>
            bdrTurn(step, operation)
          )
        case 3 => bdrTurn(step, discovery)
        case 4 => bdrTurn(step, begin("expose"))
        case 5 => patchTurn(step, current, "expose", reverse = false)
        case 6 => testTurn(step, current, "expose")
        case 7 =>
          finishExpose(lastResult(request)).flatMap(operation =>
            bdrTurn(step, operation)
          )
        case 8  => bdrTurn(step, begin("represent"))
        case 9  => patchTurn(step, current, "represent", reverse = false)
        case 10 => bdrTurn(step, finishRepresent)
        case 11 => bdrTurn(step, begin("route"))
        case 12 => patchTurn(step, current, "route", reverse = false)
        case 13 => bdrTurn(step, finishRoute)
        case 14 => bdrTurn(step, begin("collapse"))
        case 15 => patchTurn(step, current, "collapse", reverse = false)
        case 16 => bdrTurn(step, finishCollapse)
        case 17 => bdrTurn(step, begin("saturate"))
        case 18 => patchTurn(step, current, "saturate", reverse = false)
        case 19 => testTurn(step, current, "saturate")
        case 20 =>
          finishSaturate(lastResult(request)).flatMap(operation =>
            bdrTurn(step, operation)
          )
        case 21 => bdrTurn(step, begin("falsify"))
        case 22 => patchTurn(step, current, "falsify", reverse = false)
        case 23 => testTurn(step, current, "falsify")
        case 24 =>
          counterfactual(lastResult(request)).flatMap(operation =>
            bdrTurn(step, operation)
          )
        case 25 => patchTurn(step, current, "falsify", reverse = true)
        case 26 => bdrTurn(step, resolveFinding)
        case 27 => bdrTurn(step, finishFalsify)
        case 28 => commitTurn(step, current)
        case 29 =>
          delivery(lastResult(request)).flatMap(operation =>
            bdrTurn(step, operation)
          )
        case 30 => testTurn(step, current, "final")
        case 31 =>
          fixedPoint(lastResult(request)).flatMap(operation =>
            bdrTurn(step, operation)
          )
        case 32 => toolTurn(step, "bdr_completion_check", obj())
        case 33 =>
          for
            completion <- lastResult(request)
            _ <- require(
              booleanField(completion, "eligible").contains(true),
              "BDR completion check did not declare the canary eligible"
            )
            result <- bdrTurn(step, ready)
          yield result
        case 34 =>
          for output <- from(
              FinalOutput.make("Six-phase Java canary ready for review.")
            )
          yield ModelTurn.completed(output, usage)
        case _ =>
          ZIO.fail(
            BatError.ProtocolViolation(
              "scripted toy backend received an extra turn"
            )
          )
    yield result

  private def patchTurn(
      step: Int,
      current: State,
      name: String,
      reverse: Boolean
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    for
      workspace <- requireWorkspace(current)
      patch <- ZIO
        .fromOption(patches.get(name))
        .orElseFail(
          BatError.ProtocolViolation(s"missing scripted patch: $name")
        )
      result <- toolTurn(
        step,
        "toy_apply_patch",
        obj(
          "patch" -> Json.Str(patch),
          "reverse" -> Json.Bool(reverse),
          "expected_revision" -> number(workspace.revision),
          "expected_fingerprint" -> Json.Str(workspace.fingerprint)
        )
      )
    yield result

  private def testTurn(
      step: Int,
      current: State,
      suite: String
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    for
      workspace <- requireWorkspace(current)
      result <- toolTurn(
        step,
        "toy_run_tests",
        obj(
          "suite" -> Json.Str(suite),
          "expected_revision" -> number(workspace.revision),
          "expected_fingerprint" -> Json.Str(workspace.fingerprint)
        )
      )
    yield result

  private def commitTurn(
      step: Int,
      current: State
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    for
      workspace <- requireWorkspace(current)
      result <- toolTurn(
        step,
        "toy_git_commit",
        obj(
          "message" -> Json.Str(
            "Route explicit ingress authority to the scanner decision"
          ),
          "expected_revision" -> number(workspace.revision),
          "expected_fingerprint" -> Json.Str(workspace.fingerprint)
        )
      )
    yield result

  private def bdrTurn(
      step: Int,
      operation: Json.Obj
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    for
      canonical <- ZIO.fromEither(
        StrictJson.canonical(operation, "scripted BDR operation")
      )
      result <- toolTurn(
        step,
        "bdr_apply",
        obj("operation_json" -> Json.Str(canonical))
      )
    yield result

  private def toolTurn(
      step: Int,
      name: String,
      arguments: Json.Obj
  ): IO[BatError, ModelTurn[ScriptedContext]] =
    for
      callId <- from(CallId.from(f"toy-call-${step + 1}%03d"))
      call <- from(FunctionCall.make(callId, name, arguments))
      result <- from(
        ModelTurn.toolCalls(
          ScriptedContext(identity, step),
          Chunk(call),
          usage
        )
      )
    yield result

  private def observe(
      previous: State,
      request: ModelRequest[ScriptedContext]
  ): State =
    request.inputs.foldLeft(previous) {
      case (current, InputEvent.ToolOutput(output)) =>
        output.output match
          case value: Json.Obj =>
            workspaceField(value).fold(current)(workspace =>
              current.copy(workspace = Some(workspace))
            )
          case _ => current
      case (current, _) => current
    }

  private def requireWorkspace(state: State): IO[BatError, WorkspaceToken] =
    ZIO
      .fromOption(state.workspace)
      .orElseFail(
        BatError.ProtocolViolation(
          "scripted backend has not observed a workspace precondition"
        )
      )

  private def expectedBdrRevision(step: Int): Long =
    step match
      case 0 | 1 | 2    => 0L
      case 3            => 1L
      case 4            => 2L
      case 5 | 6 | 7    => 3L
      case 8            => 4L
      case 9 | 10       => 5L
      case 11           => 6L
      case 12 | 13      => 7L
      case 14           => 8L
      case 15 | 16      => 9L
      case 17           => 10L
      case 18 | 19 | 20 => 11L
      case 21           => 12L
      case 22 | 23 | 24 => 13L
      case 25 | 26      => 14L
      case 27           => 15L
      case 28 | 29      => 16L
      case 30 | 31      => 17L
      case 32 | 33      => 18L
      case 34           => 19L
      case _            => -1L

  private def baseline(
      result: IO[BatError, Json.Obj]
  ): IO[BatError, Json.Obj] =
    result.flatMap(value =>
      requireTestResult(
        value,
        expectedExit = 0,
        expectedAssertion = false,
        expectedFingerprint = None
      )
        .as(
          obj(
            "type" -> Json.Str("set_baseline"),
            "baseline" -> obj(
              "usable" -> Json.Bool(true),
              "commands" -> Json.Arr(Chunk(command(value, "baseline")))
            )
          )
        )
    )

  private def finishExpose(
      result: IO[BatError, Json.Obj]
  ): IO[BatError, Json.Obj] =
    result.flatMap(value =>
      requireTestResult(
        value,
        expectedExit = 1,
        expectedAssertion = true,
        expectedFingerprint = Some(ExpectedRedFingerprint)
      ).as(
        obj(
          "type" -> Json.Str("finish_phase"),
          "slice" -> Json.Str("S-0001"),
          "phase" -> Json.Str("expose"),
          "result" -> Json.Str("passed"),
          "gate" -> obj(
            "commands" -> Json.Arr(Chunk(command(value, "expose"))),
            "finding_id" -> Json.Str("F-0001"),
            "test" -> Json.Str(
              "IngressGatewayPublicTest.externalCorporateLookingSenderIsStillScanned"
            ),
            "baseline_ref" -> Json.Str("run.baseline"),
            "failed_at_assertion" -> Json.Bool(true),
            "assertion_fingerprint" -> Json.Str(
              "external corporate-looking sender: expected REJECTED but was ACCEPTED"
            ),
            "input_space" -> strings(
              "internal/corporate",
              "external/noncorporate",
              "external/corporate"
            )
          )
        )
      )
    )

  private def finishSaturate(
      result: IO[BatError, Json.Obj]
  ): IO[BatError, Json.Obj] =
    result.flatMap(value =>
      requireTestResult(
        value,
        expectedExit = 0,
        expectedAssertion = false,
        expectedFingerprint = None
      ).as(
        obj(
          "type" -> Json.Str("finish_phase"),
          "slice" -> Json.Str("S-0001"),
          "phase" -> Json.Str("saturate"),
          "result" -> Json.Str("passed"),
          "evidence_id" -> Json.Str("E-SATURATE"),
          "gate" -> obj(
            "commands" -> Json.Arr(Chunk(command(value, "saturate"))),
            "structural_tests" -> strings(
              "authenticated internal with corporate sender bypasses",
              "authenticated internal with service alias bypasses",
              "untrusted external with corporate-looking sender scans",
              "untrusted external with internet sender scans"
            ),
            "operational_proofs" -> obj(),
            "input_space_covered" -> Json.Bool(true)
          )
        )
      )
    )

  private def counterfactual(
      result: IO[BatError, Json.Obj]
  ): IO[BatError, Json.Obj] =
    result.flatMap(value =>
      requireTestResult(
        value,
        expectedExit = 1,
        expectedAssertion = true,
        expectedFingerprint = Some(ExpectedRedFingerprint)
      ).as(
        obj(
          "type" -> Json.Str("add_evidence"),
          "id" -> Json.Str("E-COUNTERFACTUAL"),
          "evidence" -> obj(
            "kind" -> Json.Str("counterfactual_test"),
            "claim" -> Json.Str(
              "Restoring only sender-suffix inference makes the exposed boundary assertion fail."
            ),
            "commands" -> Json.Arr(Chunk(command(value, "falsify")))
          )
        )
      )
    )

  private def delivery(
      result: IO[BatError, Json.Obj]
  ): IO[BatError, Json.Obj] =
    result.flatMap { value =>
      stringField(value, "head_sha") match
        case Some(head) if head.matches("[0-9a-f]{40}|[0-9a-f]{64}") =>
          ZIO.succeed(
            obj(
              "type" -> Json.Str("record_delivery"),
              "slice" -> Json.Str("S-0001"),
              "kind" -> Json.Str("commit"),
              "sha" -> Json.Str(head),
              "evidence" -> Json.Str("E-SATURATE")
            )
          )
        case _ =>
          ZIO.fail(
            BatError.ProtocolViolation(
              "toy commit did not return a full delivery SHA"
            )
          )
    }

  private def fixedPoint(
      result: IO[BatError, Json.Obj]
  ): IO[BatError, Json.Obj] =
    result.flatMap(value =>
      requireTestResult(
        value,
        expectedExit = 0,
        expectedAssertion = false,
        expectedFingerprint = None
      ).as {
        val finalCommand = command(value, "final")
        obj(
          "type" -> Json.Str("batch"),
          "operations" -> Json.Arr(
            Chunk(
              obj(
                "type" -> Json.Str("add_evidence"),
                "id" -> Json.Str("E-FINAL-RESCAN"),
                "evidence" -> obj(
                  "kind" -> Json.Str("rescan"),
                  "claim" -> Json.Str(
                    "Final actor-visible diff and public suite contain no additional merge-blocking boundary finding."
                  ),
                  "commands" -> Json.Arr(Chunk(finalCommand))
                )
              ),
              obj(
                "type" -> Json.Str("record_fixed_point"),
                "pass" -> obj(
                  "number" -> number(1L),
                  "new_merge_blocking_findings" -> number(0L),
                  "evidence" -> Json.Str("E-FINAL-RESCAN"),
                  "commands" -> Json.Arr(Chunk(finalCommand))
                )
              )
            )
          )
        )
      }
    )

  private def lastResult(
      request: ModelRequest[ScriptedContext]
  ): IO[BatError, Json.Obj] =
    request.inputs.reverse.collectFirst { case InputEvent.ToolOutput(output) =>
      output
    } match
      case Some(output) if output.isError =>
        ZIO.fail(
          BatError.ProtocolViolation(
            "scripted canary observed an unexpected tool error"
          )
        )
      case Some(output) =>
        output.output match
          case value: Json.Obj => ZIO.succeed(value)
          case _               =>
            ZIO.fail(
              BatError.ProtocolViolation(
                "scripted canary expected an object tool output"
              )
            )
      case None =>
        ZIO.fail(
          BatError.ProtocolViolation(
            "scripted canary expected a prior tool output"
          )
        )

  private def requireTestResult(
      value: Json.Obj,
      expectedExit: Long,
      expectedAssertion: Boolean,
      expectedFingerprint: Option[String]
  ): IO[BatError, Unit] =
    val actualFingerprint = field(value, "failure_fingerprint") match
      case Some(Json.Str(fingerprint)) => Some(fingerprint)
      case Some(Json.Null)             => None
      case _                           => Some("invalid")
    require(
      longField(value, "exit_code").contains(expectedExit) &&
        booleanField(value, "assertion_failed").contains(expectedAssertion) &&
        actualFingerprint == expectedFingerprint,
      "toy verification result did not match the expected evidence shape"
    )

  private def command(value: Json.Obj, suite: String): Json.Obj =
    obj(
      "command" -> Json.Str(s"toy javac/public-suite $suite"),
      "exit_code" -> number(longField(value, "exit_code").getOrElse(-1L)),
      "output_digest" -> Json.Str(
        stringField(value, "output_digest").getOrElse("sha256:missing")
      )
    )

  private val discovery: Json.Obj =
    obj(
      "type" -> Json.Str("batch"),
      "operations" -> Json.Arr(
        Chunk(
          obj(
            "type" -> Json.Str("add_evidence"),
            "id" -> Json.Str("E-0001"),
            "evidence" -> obj(
              "kind" -> Json.Str("code_read"),
              "claim" -> Json.Str(
                "IngressGateway entrypoints own ingress trust; MessageRouter needs it to decide whether scanning is mandatory."
              )
            )
          ),
          obj(
            "type" -> Json.Str("add_slice"),
            "id" -> Json.Str("S-0001"),
            "name" -> Json.Str("Route ingress trust to scanner policy"),
            "merge_policy" -> Json.Str("required"),
            "boundary" -> obj(
              "authority" -> Json.Str("IngressGateway entrypoint"),
              "fact" -> Json.Str(
                "authenticated internal or untrusted external ingress"
              ),
              "consumer_decision" -> Json.Str(
                "bypass or require ContentScanner"
              )
            ),
            "depends_on" -> Json.Arr(Chunk.empty),
            "collapse_predictions" -> obj(
              "P-0001" -> Json.Str(
                "sender-suffix scan-authority inference dies"
              )
            ),
            "operational_obligations" -> Json.Arr(Chunk.empty)
          ),
          obj(
            "type" -> Json.Str("add_finding"),
            "id" -> Json.Str("F-0001"),
            "title" -> Json.Str(
              "Router infers ingress trust from caller-controlled sender text"
            ),
            "site" -> Json.Str(
              "src/main/java/dev/bat/examples/ingress/MessageRouter.java:12"
            ),
            "severity" -> Json.Str("major"),
            "merge_blocking" -> Json.Bool(true),
            "found_by" -> Json.Str("review"),
            "missing_fact" -> obj(
              "authority" -> Json.Str("IngressGateway entrypoint"),
              "fact" -> Json.Str(
                "authenticated internal or untrusted external ingress"
              ),
              "consumer_decision" -> Json.Str(
                "bypass or require ContentScanner"
              ),
              "inferred_from" -> Json.Str("sender suffix @corp.test"),
              "initial_shape" -> Json.Str("value"),
              "normalized_as" -> Json.Str("capability")
            ),
            "fix_direction" -> Json.Str(
              "Represent ingress explicitly, route it from both gateway producers, and delete suffix inference."
            )
          ),
          obj(
            "type" -> Json.Str("assign_finding"),
            "finding" -> Json.Str("F-0001"),
            "slice" -> Json.Str("S-0001"),
            "k_verification" -> Json.Str("E-0001")
          )
        )
      )
    )

  private def begin(phase: String): Json.Obj =
    obj(
      "type" -> Json.Str("begin_phase"),
      "slice" -> Json.Str("S-0001"),
      "phase" -> Json.Str(phase)
    )

  private val finishRepresent: Json.Obj =
    finish(
      "represent",
      obj(
        "behavior_changed" -> Json.Bool(false),
        "artifacts" -> strings("Ingress")
      )
    )

  private val finishRoute: Json.Obj =
    finish(
      "route",
      obj(
        "producers" -> strings(
          "IngressGateway.acceptInternal",
          "IngressGateway.acceptExternal"
        ),
        "consumers" -> strings("MessageRouter.route"),
        "predictions_frozen" -> Json.Bool(true),
        "new_abstraction_introduced" -> Json.Bool(false),
        "introduced" -> Json.Arr(Chunk.empty)
      )
    )

  private val finishCollapse: Json.Obj =
    finish(
      "collapse",
      obj(
        "prediction_verdicts" -> obj("P-0001" -> Json.Str("died")),
        "died" -> strings(
          "MessageRouter no longer derives scan authority from sender text"
        )
      )
    )

  private val resolveFinding: Json.Obj =
    obj(
      "type" -> Json.Str("resolve_finding"),
      "finding" -> Json.Str("F-0001"),
      "resolution" -> obj(
        "kind" -> Json.Str("fixed"),
        "passing_test" -> Json.Str("E-SATURATE"),
        "counterfactual_test" -> Json.Str("E-COUNTERFACTUAL")
      )
    )

  private val finishFalsify: Json.Obj =
    finish(
      "falsify",
      obj(
        "saturate_evidence" -> Json.Str("E-SATURATE"),
        "finding_verdicts" -> obj("F-0001" -> Json.Str("fixed")),
        "rescan" -> obj("performed" -> Json.Bool(true))
      )
    )

  private val ready: Json.Obj =
    obj(
      "type" -> Json.Str("set_run_state"),
      "state" -> Json.Str("ready_for_review")
    )

  private def finish(phase: String, gate: Json.Obj): Json.Obj =
    obj(
      "type" -> Json.Str("finish_phase"),
      "slice" -> Json.Str("S-0001"),
      "phase" -> Json.Str(phase),
      "result" -> Json.Str("passed"),
      "gate" -> gate
    )

object ScriptedToyBackend:
  private val ExpectedRedFingerprint =
    s"sha256:${ToyRuntime.sha256("external corporate-looking sender: expected REJECTED but was ACCEPTED".getBytes(StandardCharsets.UTF_8))}"

  private final case class WorkspaceToken(revision: Long, fingerprint: String)
  private final case class State(
      step: Int,
      workspace: Option[WorkspaceToken]
  )

  final class ScriptedContext private[quickstart] (
      identity: BackendIdentity,
      val step: Int,
      private val reasoningCanary: String
  ) extends OpaqueReasoningContext(identity, ContinuationMode.OpaqueReplay)

  private object ScriptedContext:
    def apply(identity: BackendIdentity, step: Int): ScriptedContext =
      new ScriptedContext(
        identity,
        step,
        s"SCRIPTED_TOY_REASONING_CANARY_$step"
      )

  def make(
      identity: BackendIdentity,
      fixtureRoot: Path
  ): IO[BatError, ScriptedToyBackend] =
    for
      capabilities <- from(
        BackendCapabilities.make(
          Set(
            Capability.ReasoningContinuity,
            Capability.StrictTools,
            Capability.UsageReporting
          )
        )
      )
      patches <- ZIO.foreach(
        Chunk("expose", "represent", "route", "collapse", "saturate", "falsify")
      ) { name =>
        ZIO
          .attemptBlockingInterrupt(
            Files.readString(
              fixtureRoot
                .resolve("reference")
                .resolve("phases")
                .resolve(s"$name.patch"),
              StandardCharsets.UTF_8
            )
          )
          .mapError(_ =>
            BatError.BdrFailure(
              "toy_fixture_read_failed",
              "cannot read scripted toy phase patch"
            )
          )
          .map(name -> _)
      }
      state <- Ref.Synchronized.make(State(0, None))
    yield new ScriptedToyBackend(identity, capabilities, patches.toMap, state)

  private val usage: Usage =
    Usage
      .make(10L, Some(6L), Some(0L), Some(4L), Some(2L))
      .fold(
        error => throw new IllegalStateException(error.safeMessage),
        identity
      )

  private def workspaceField(value: Json.Obj): Option[WorkspaceToken] =
    field(value, "workspace") match
      case Some(workspace: Json.Obj) =>
        for
          revision <- longField(workspace, "revision")
          fingerprint <- stringField(workspace, "fingerprint")
        yield WorkspaceToken(revision, fingerprint)
      case _ => None

  private def field(value: Json.Obj, name: String): Option[Json] =
    value.fields.collectFirst { case (`name`, result) => result }

  private def stringField(value: Json.Obj, name: String): Option[String] =
    field(value, name).collect { case Json.Str(result) => result }

  private def booleanField(value: Json.Obj, name: String): Option[Boolean] =
    field(value, name).collect { case Json.Bool(result) => result }

  private def longField(value: Json.Obj, name: String): Option[Long] =
    field(value, name).collect { case Json.Num(result) =>
      result.longValueExact()
    }

  private def number(value: Long): Json.Num =
    Json.Num(java.math.BigDecimal.valueOf(value))

  private def strings(values: String*): Json.Arr =
    Json.Arr(Chunk.fromIterable(values.map(Json.Str(_))))

  private def obj(fields: (String, Json)*): Json.Obj =
    Json.Obj(Chunk.fromIterable(fields))

  private def require(
      condition: Boolean,
      message: String
  ): IO[BatError, Unit] =
    ZIO
      .fail(BatError.ProtocolViolation(message))
      .unless(condition)
      .unit

  private def from[A](value: Either[BatError, A]): IO[BatError, A] =
    ZIO.fromEither(value)
