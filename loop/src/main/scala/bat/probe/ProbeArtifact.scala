package bat.probe

import java.math.BigDecimal as JBigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

import bat.protocol.StrictJson
import bat.telemetry.{
  DeploymentFingerprint,
  Measurement,
  TelemetryDocument,
  TelemetryEvent
}
import bat.trace.SafeTraceDocument

import zio.Chunk
import zio.json.*
import zio.json.ast.Json

enum ProbeVerdict(val wire: String):
  case Compatible extends ProbeVerdict("compatible")
  case Incompatible extends ProbeVerdict("incompatible")
  case Nonconformant extends ProbeVerdict("nonconformant")
  case Blocked extends ProbeVerdict("blocked")

  final override def toString: String = s"ProbeVerdict($wire)"

/** Bounded operator-safe explanation code. Provider text cannot be admitted. */
final class ProbeReasonCode private (val value: String):
  override def toString: String = "ProbeReasonCode(value=<redacted>)"

object ProbeReasonCode:
  private val MachineCode = "^[a-z][a-z0-9_-]{0,63}$".r

  def from(value: String): Either[ProbeError, ProbeReasonCode] =
    Option(value)
      .filter(MachineCode.matches)
      .map(new ProbeReasonCode(_))
      .toRight(
        ProbeError.make(
          "invalid_probe_reason",
          "probe reason must be a bounded machine code"
        )
      )

/** Deterministic, sanitized result written by the live probe interpreter.
  *
  * Endpoint, credential, prompts, raw reasoning, provider bodies, tool
  * payloads, and raw call IDs have no field in this model. The two embedded
  * documents are BAT's typed sanitized trace and canonical telemetry schemas.
  */
final class ProbeResultArtifact private (
    val verdict: ProbeVerdict,
    val reasonCode: Option[ProbeReasonCode],
    val batCommit: ProbeBatCommit,
    val deployment: DeploymentFingerprint,
    val safeTrace: Option[SafeTraceDocument],
    val telemetry: TelemetryDocument,
    val safeTraceJson: String,
    val telemetryJson: String,
    val safeTraceSha256: String,
    val telemetrySha256: String,
    val json: Json.Obj,
    val canonicalJson: String
):
  override def toString: String =
    s"ProbeResultArtifact(verdict=${verdict.wire}, payload=<redacted>)"

object ProbeResultArtifact:
  val Schema = "bat.dev/gpt-oss-probe-result"
  val Version = 1

  def make(
      verdict: ProbeVerdict,
      reasonCode: Option[ProbeReasonCode],
      batCommit: ProbeBatCommit,
      deployment: DeploymentFingerprint,
      safeTrace: Option[SafeTraceDocument],
      telemetry: TelemetryDocument
  ): Either[ProbeError, ProbeResultArtifact] =
    for
      _ <- require(
        verdict != null && reasonCode != null && !reasonCode.contains(null) &&
          batCommit != null && deployment != null && safeTrace != null &&
          !safeTrace.contains(null) && telemetry != null,
        "invalid_probe_result",
        "probe result fields must not be null"
      )
      _ <- validateVerdict(verdict, reasonCode, safeTrace)
      _ <- require(
        telemetry.deployment == deployment,
        "probe_deployment_mismatch",
        "probe result deployment must match canonical telemetry"
      )
      _ <- validateTerminal(verdict, telemetry)
      traceEncoding <- encodeTrace(safeTrace)
      (traceText, traceJson) = traceEncoding
      telemetryText <- telemetry.canonicalJson.left.map(_ =>
        ProbeError.make(
          "telemetry_encoding_failed",
          "probe telemetry could not be encoded"
        )
      )
      telemetryJson <- StrictJson
        .parseObject(telemetryText, "probe telemetry")
        .left
        .map(_ =>
          ProbeError.make(
            "telemetry_encoding_failed",
            "probe telemetry could not be encoded"
          )
        )
      traceDigest = sha256(traceText)
      telemetryDigest = sha256(telemetryText)
      value = obj(
        "schema" -> Json.Str(Schema),
        "version" -> number(Version.toLong),
        "run_id" -> Json.Str(telemetry.runId.value),
        "verdict" -> Json.Str(verdict.wire),
        "reason_code" -> reasonCode.fold[Json](Json.Null)(reason =>
          Json.Str(reason.value)
        ),
        "bat_commit" -> Json.Str(batCommit.value),
        "deployment" -> deploymentJson(deployment),
        "safe_trace_sha256" -> Json.Str(traceDigest),
        "telemetry_sha256" -> Json.Str(telemetryDigest),
        "safe_trace" -> traceJson,
        "telemetry" -> telemetryJson
      )
      canonical <- StrictJson
        .canonical(value, "GPT-OSS probe result")
        .left
        .map(_ =>
          ProbeError.make(
            "probe_result_encoding_failed",
            "probe result could not be encoded"
          )
        )
    yield new ProbeResultArtifact(
      verdict,
      reasonCode,
      batCommit,
      deployment,
      safeTrace,
      telemetry,
      traceText,
      telemetryText,
      traceDigest,
      telemetryDigest,
      value,
      canonical
    )

  private def validateVerdict(
      verdict: ProbeVerdict,
      reasonCode: Option[ProbeReasonCode],
      safeTrace: Option[SafeTraceDocument]
  ): Either[ProbeError, Unit] =
    verdict match
      case ProbeVerdict.Compatible =>
        require(
          reasonCode.isEmpty && safeTrace.nonEmpty,
          "invalid_compatible_result",
          "compatible probe result requires a safe trace and no reason code"
        )
      case ProbeVerdict.Incompatible | ProbeVerdict.Nonconformant |
          ProbeVerdict.Blocked =>
        require(
          reasonCode.nonEmpty,
          "invalid_non_compatible_result",
          "non-compatible probe result requires a reason code"
        )

  private def validateTerminal(
      verdict: ProbeVerdict,
      telemetry: TelemetryDocument
  ): Either[ProbeError, Unit] =
    val completed = telemetry.records.lastOption.exists(
      _.event.isInstanceOf[TelemetryEvent.RunCompleted]
    )
    val valid = verdict match
      case ProbeVerdict.Compatible                          => completed
      case ProbeVerdict.Incompatible | ProbeVerdict.Blocked => !completed
      case ProbeVerdict.Nonconformant                       => true
    require(
      valid,
      "probe_terminal_mismatch",
      "probe verdict must match the canonical telemetry terminal event"
    )

  private def encodeTrace(
      value: Option[SafeTraceDocument]
  ): Either[ProbeError, (String, Json)] =
    value match
      case None           => Right("null" -> Json.Null)
      case Some(document) =>
        for
          _ <- require(
            document.schema == "bat.dev/conformance-trace" &&
              document.version == 2,
            "invalid_safe_trace",
            "probe safe trace schema is invalid"
          )
          text = document.toJson
          encoded <- StrictJson
            .parse(text, "probe safe trace")
            .left
            .map(_ =>
              ProbeError.make(
                "safe_trace_encoding_failed",
                "probe safe trace could not be encoded"
              )
            )
        yield text -> encoded

  private def deploymentJson(value: DeploymentFingerprint): Json.Obj =
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

  /** File digests cover these exact UTF-8 bytes without a trailing newline. The
    * artifact writer may append one newline after hashing.
    */
  private def sha256(value: String): String =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def require(
      condition: Boolean,
      code: String,
      message: String
  ): Either[ProbeError, Unit] =
    Either.cond(condition, (), ProbeError.make(code, message))
