package bat.probe

import java.net.URI
import java.nio.file.Path
import java.util.Locale

import scala.util.control.NonFatal

import bat.backend.gptoss.{GptOssConfig, ResponsesLimits}
import bat.backend.harmonychat.{HarmonyChatConfig, HarmonyChatLimits}
import bat.protocol.BackendIdentity
import bat.telemetry.{DeploymentFingerprint, Measurement, TelemetryRunId}
import bat.transport.{Secret, SseLimits, TransportConfig}

import zio.Duration

/** Stable validation failure for the opt-in live probe. Rejected operator input
  * is deliberately absent from both fields and the value rendering.
  */
final class ProbeError private (
    val code: String,
    val safeMessage: String
):
  override def toString: String =
    s"ProbeError(code=$code, payload=<redacted>)"

object ProbeError:
  private val MachineCode = "^[a-z][a-z0-9_-]{0,63}$".r

  private[probe] def make(code: String, safeMessage: String): ProbeError =
    val safeCode = Option(code)
      .filter(MachineCode.matches)
      .getOrElse("invalid_probe_configuration")
    val safeText = Option(safeMessage)
      .filter(_.nonEmpty)
      .map(_.take(160))
      .getOrElse("probe configuration is invalid")
    new ProbeError(safeCode, safeText)

/** The wire dialect a probe run qualifies.
  *
  * A dialect is selected explicitly by the operator and recorded in the
  * deployment fingerprint. Nothing here is a fallback for anything else: a
  * failure on one dialect never re-attempts another, because the point of the
  * probe is to say which wire a deployment actually satisfies.
  */
enum ProbeDialect(val wire: String, val path: String):
  case Responses
      extends ProbeDialect("responses_sse", GptOssConfig.ResponsesPath)

  /** Native Harmony over Chat Completions, for endpoints that carry raw
    * reasoning in `reasoning_content`. See ADR 0005.
    */
  case HarmonyChat
      extends ProbeDialect(
        "harmony_chat_sse",
        HarmonyChatConfig.ChatCompletionsPath
      )

  final override def toString: String = s"ProbeDialect($wire)"

object ProbeDialect:
  /** Operator-facing selector values, kept stable for scripts and evidence. */
  def fromWire(value: String): Option[ProbeDialect] =
    value match
      case "responses" | "responses_sse"       => Some(ProbeDialect.Responses)
      case "harmony-chat" | "harmony_chat_sse" => Some(ProbeDialect.HarmonyChat)
      case _                                   => None

/** The cartridge configuration for the selected dialect. */
enum ProbeBackendConfig(val dialect: ProbeDialect):
  case Responses(value: GptOssConfig)
      extends ProbeBackendConfig(ProbeDialect.Responses)
  case HarmonyChat(value: HarmonyChatConfig)
      extends ProbeBackendConfig(ProbeDialect.HarmonyChat)

  def identity: BackendIdentity =
    this match
      case Responses(value)   => value.identity
      case HarmonyChat(value) => value.identity

  def credential: Option[Secret] =
    this match
      case Responses(value)   => value.credential
      case HarmonyChat(value) => value.credential

  final override def toString: String =
    s"ProbeBackendConfig(dialect=${dialect.wire}, payload=<redacted>)"

/** Absolute lexical destination selected by the operator.
  *
  * Existence, ownership, symlinks, and writability require effects and belong
  * to the artifact writer. This type rejects ambiguous or dangerously broad
  * path syntax before that boundary is reached.
  */
final class ProbeOutputDirectory private (val path: Path):
  override def toString: String =
    "ProbeOutputDirectory(path=<redacted>)"

object ProbeOutputDirectory:
  private val MaxPathCharacters = 4096

  def from(path: Path): Either[ProbeError, ProbeOutputDirectory] =
    try
      val rendered = Option(path).fold("")(_.toString)
      val normalized = Option(path).map(_.normalize())
      val valid =
        path != null &&
          rendered.nonEmpty &&
          rendered.length <= MaxPathCharacters &&
          !rendered.exists(character =>
            character == '\u0000' || character.isControl
          ) &&
          path.isAbsolute &&
          path.getNameCount > 0 &&
          normalized.contains(path)
      Either.cond(
        valid,
        new ProbeOutputDirectory(path),
        ProbeError.make(
          "invalid_probe_output_directory",
          "probe output directory must be an absolute normalized non-root path"
        )
      )
    catch
      case NonFatal(_) =>
        Left(
          ProbeError.make(
            "invalid_probe_output_directory",
            "probe output directory is invalid"
          )
        )

/** Canonical BAT source pin recorded with every probe artifact. */
final class ProbeBatCommit private (val value: String):
  override def toString: String = "ProbeBatCommit(value=<redacted>)"

object ProbeBatCommit:
  private val FullGitCommit = "^[0-9a-f]{40}$".r

  def from(value: String): Either[ProbeError, ProbeBatCommit] =
    Option(value)
      .filter(FullGitCommit.matches)
      .map(new ProbeBatCommit(_))
      .toRight(
        ProbeError.make(
          "invalid_bat_commit",
          "BAT commit must be a full 40-character lowercase Git commit"
        )
      )

/** Fully validated, operator-pinned input to the live GPT-OSS probe.
  *
  * This value is intentionally not JSON encodable. Endpoint and credential
  * exist only long enough to construct the transport/backend interpreters;
  * neither can enter a result artifact through this API.
  */
final class LiveGptOssProbeConfig private (
    val transportConfig: TransportConfig,
    val backendConfig: ProbeBackendConfig,
    val deployment: DeploymentFingerprint,
    val runId: TelemetryRunId,
    val batCommit: ProbeBatCommit,
    val outputDirectory: ProbeOutputDirectory,
    val allowInsecureHttp: Boolean
):
  val dialect: ProbeDialect = backendConfig.dialect
  val identity: BackendIdentity = backendConfig.identity
  val credential: Option[Secret] = backendConfig.credential

  override def toString: String =
    s"LiveGptOssProbeConfig(dialect=${dialect.wire}, endpoint=<redacted>, credential=<redacted>, deployment=<redacted>, runId=<redacted>, batCommit=<redacted>, outputDirectory=<redacted>, allowInsecureHttp=$allowInsecureHttp)"

object LiveGptOssProbeConfig:
  val DefaultOpenTimeout: Duration = Duration.fromMillis(10_000L)
  val DefaultBodyIdleTimeout: Duration = Duration.fromMillis(120_000L)
  val DefaultMaxSseEventBytes: Long = 1024L * 1024L
  val DefaultMaxSseStreamBytes: Long = 16L * 1024L * 1024L

  def make(
      endpoint: String,
      credential: Option[Secret],
      modelId: String,
      weightRevision: String,
      runtime: String,
      runtimeRevision: String,
      harmonyTemplateRevision: String,
      quantization: String,
      topologyClass: String,
      nodeCount: Long,
      runId: String,
      batCommit: String,
      outputDirectory: Path,
      allowInsecureHttp: Boolean = false,
      openTimeout: Duration = DefaultOpenTimeout,
      bodyIdleTimeout: Duration = DefaultBodyIdleTimeout,
      maxRequestBytes: Long = TransportConfig.DefaultMaxRequestBytes,
      maxResponseHeaderBytes: Long =
        TransportConfig.DefaultMaxResponseHeaderBytes,
      maxSseEventBytes: Long = DefaultMaxSseEventBytes,
      maxSseStreamBytes: Long = DefaultMaxSseStreamBytes,
      responsesLimits: ResponsesLimits = ResponsesLimits.default,
      chatLimits: HarmonyChatLimits = HarmonyChatLimits.default,
      maxOutputTokens: Long = GptOssConfig.DefaultMaxOutputTokens,
      maxAttempts: Int = GptOssConfig.DefaultMaxAttempts,
      retryDelay: Duration = GptOssConfig.DefaultRetryDelay,
      dialect: ProbeDialect = ProbeDialect.Responses
  ): Either[ProbeError, LiveGptOssProbeConfig] =
    for
      _ <- require(
        credential != null && !credential.contains(null),
        "invalid_probe_credential",
        "probe credential must be an optional Secret"
      )
      _ <- validateEndpointPolicy(endpoint, credential, allowInsecureHttp)
      // The backend name is part of the pinned identity, so the dialect a run
      // qualifies cannot be confused with another one after the fact.
      identity <- (dialect match
        case ProbeDialect.Responses =>
          GptOssConfig.identity(
            modelId,
            weightRevision
          )
        case ProbeDialect.HarmonyChat =>
          HarmonyChatConfig.identity(modelId, weightRevision)
      ).left
        .map(_ =>
          ProbeError.make(
            "invalid_probe_identity",
            "probe model identity must be explicitly pinned"
          )
        )
      deployment <- DeploymentFingerprint
        .make(
          identity = identity,
          runtime = Measurement.Observed(runtime),
          runtimeRevision = Measurement.Observed(runtimeRevision),
          protocol = dialect.wire,
          templateRevision = Measurement.Observed(harmonyTemplateRevision),
          quantization = Measurement.Observed(quantization),
          topology = Measurement.Observed(topologyClass),
          nodeCount = Measurement.Observed(nodeCount)
        )
        .left
        .map(_ =>
          ProbeError.make(
            "invalid_probe_deployment",
            "probe deployment fields must be safe explicit identifiers"
          )
        )
      transport <- TransportConfig
        .make(
          endpoint,
          openTimeout,
          bodyIdleTimeout,
          maxRequestBytes,
          maxResponseHeaderBytes
        )
        .left
        .map(error =>
          ProbeError.make(
            error.code,
            "probe transport configuration is invalid"
          )
        )
      sseLimits <- SseLimits
        .make(maxSseEventBytes, maxSseStreamBytes)
        .left
        .map(error =>
          ProbeError.make(error.code, "probe SSE limits are invalid")
        )
      backend <- (dialect match
        case ProbeDialect.Responses =>
          GptOssConfig
            .make(
              identity = identity,
              credential = credential,
              sseLimits = sseLimits,
              responsesLimits = responsesLimits,
              maxOutputTokens = maxOutputTokens,
              maxAttempts = maxAttempts,
              retryDelay = retryDelay
            )
            .map(ProbeBackendConfig.Responses(_))
        case ProbeDialect.HarmonyChat =>
          HarmonyChatConfig
            .make(
              identity = identity,
              credential = credential,
              sseLimits = sseLimits,
              chatLimits = chatLimits,
              maxOutputTokens = maxOutputTokens,
              maxAttempts = maxAttempts,
              retryDelay = retryDelay
            )
            .map(ProbeBackendConfig.HarmonyChat(_))
      ).left
        .map(_ =>
          ProbeError.make(
            "invalid_probe_backend",
            "probe cartridge configuration is invalid"
          )
        )
      safeRunId <- TelemetryRunId
        .from(runId)
        .left
        .map(_ =>
          ProbeError.make(
            "invalid_probe_run_id",
            "probe run ID must be a bounded safe identifier"
          )
        )
      safeCommit <- ProbeBatCommit.from(batCommit)
      safeOutput <- ProbeOutputDirectory.from(outputDirectory)
    yield new LiveGptOssProbeConfig(
      transport,
      backend,
      deployment,
      safeRunId,
      safeCommit,
      safeOutput,
      allowInsecureHttp
    )

  private def validateEndpointPolicy(
      endpoint: String,
      credential: Option[Secret],
      allowInsecureHttp: Boolean
  ): Either[ProbeError, Unit] =
    try
      val scheme = Option(endpoint)
        .flatMap(value => Option(new URI(value).getScheme))
        .map(_.toLowerCase(Locale.ROOT))
      scheme match
        case Some("http") if credential.nonEmpty =>
          Left(
            ProbeError.make(
              "insecure_probe_credential",
              "probe credentials require an HTTPS endpoint"
            )
          )
        case Some("http") if !allowInsecureHttp =>
          Left(
            ProbeError.make(
              "insecure_probe_endpoint",
              "plain HTTP probe endpoints require explicit opt-in"
            )
          )
        case _ => Right(())
    catch case NonFatal(_) => Right(())

  private def require(
      condition: Boolean,
      code: String,
      message: String
  ): Either[ProbeError, Unit] =
    Either.cond(condition, (), ProbeError.make(code, message))
