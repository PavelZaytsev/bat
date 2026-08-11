package bat.probe

import java.nio.file.Path

import scala.util.control.NonFatal

import bat.transport.Secret

import zio.Chunk

/** Environment-only live configuration. The raw endpoint and optional token
  * remain available solely for the final in-memory leak scan.
  */
final class LoadedProbeEnvironment private[probe] (
    val config: LiveGptOssProbeConfig,
    private[probe] val forbiddenArtifactValues: Chunk[String]
):
  override def toString: String =
    "LoadedProbeEnvironment(configuration=<redacted>)"

object ProbeEnvironment:
  val Keys: Chunk[String] = Chunk(
    "BAT_GPT_OSS_LIVE",
    "BAT_GPT_OSS_ENDPOINT",
    "BAT_GPT_OSS_TOKEN",
    "BAT_GPT_OSS_MODEL_ID",
    "BAT_GPT_OSS_WEIGHT_REVISION",
    "BAT_GPT_OSS_RUNTIME",
    "BAT_GPT_OSS_RUNTIME_REVISION",
    "BAT_GPT_OSS_HARMONY_TEMPLATE_REVISION",
    "BAT_GPT_OSS_QUANTIZATION",
    "BAT_GPT_OSS_TOPOLOGY",
    "BAT_GPT_OSS_NODE_COUNT",
    "BAT_GPT_OSS_RUN_ID",
    "BAT_GPT_OSS_BAT_COMMIT",
    "BAT_GPT_OSS_OUTPUT",
    "BAT_GPT_OSS_ALLOW_INSECURE_HTTP",
    "BAT_GPT_OSS_DIALECT"
  )

  def from(
      values: Map[String, String]
  ): Either[ProbeError, LoadedProbeEnvironment] =
    for
      _ <- require(
        values != null && !values.exists { case (key, value) =>
          key == null || value == null
        },
        "invalid_probe_environment",
        "live probe environment is invalid"
      )
      _ <- require(
        values.get("BAT_GPT_OSS_LIVE").contains("1"),
        "probe_not_armed",
        "live probe requires explicit arming"
      )
      endpoint <- required(values, "BAT_GPT_OSS_ENDPOINT")
      modelId <- required(values, "BAT_GPT_OSS_MODEL_ID")
      weightRevision <- required(values, "BAT_GPT_OSS_WEIGHT_REVISION")
      runtime <- required(values, "BAT_GPT_OSS_RUNTIME")
      runtimeRevision <- required(values, "BAT_GPT_OSS_RUNTIME_REVISION")
      template <- required(values, "BAT_GPT_OSS_HARMONY_TEMPLATE_REVISION")
      quantization <- required(values, "BAT_GPT_OSS_QUANTIZATION")
      topology <- required(values, "BAT_GPT_OSS_TOPOLOGY")
      nodeCountText <- required(values, "BAT_GPT_OSS_NODE_COUNT")
      nodeCount <- parseNodeCount(nodeCountText)
      runId <- required(values, "BAT_GPT_OSS_RUN_ID")
      batCommit <- required(values, "BAT_GPT_OSS_BAT_COMMIT")
      outputText <- required(values, "BAT_GPT_OSS_OUTPUT")
      output <- parsePath(outputText)
      credential <- parseCredential(values.get("BAT_GPT_OSS_TOKEN"))
      allowInsecure <- parseFlag(
        values.get("BAT_GPT_OSS_ALLOW_INSECURE_HTTP")
      )
      dialect <- parseDialect(values.get("BAT_GPT_OSS_DIALECT"))
      config <- LiveGptOssProbeConfig.make(
        endpoint = endpoint,
        credential = credential,
        modelId = modelId,
        weightRevision = weightRevision,
        runtime = runtime,
        runtimeRevision = runtimeRevision,
        harmonyTemplateRevision = template,
        quantization = quantization,
        topologyClass = topology,
        nodeCount = nodeCount,
        runId = runId,
        batCommit = batCommit,
        outputDirectory = output,
        allowInsecureHttp = allowInsecure,
        dialect = dialect
      )
      forbidden = Chunk(endpoint, outputText) ++
        values.get("BAT_GPT_OSS_TOKEN").toList
    yield new LoadedProbeEnvironment(config, forbidden)

  private def required(
      values: Map[String, String],
      key: String
  ): Either[ProbeError, String] =
    values
      .get(key)
      .filter(_.nonEmpty)
      .toRight(
        ProbeError.make(
          "missing_probe_configuration",
          "required live probe configuration is missing"
        )
      )

  private def parseCredential(
      value: Option[String]
  ): Either[ProbeError, Option[Secret]] =
    value match
      case None      => Right(None)
      case Some(raw) =>
        Secret
          .from(raw)
          .map(Some(_))
          .left
          .map(_ =>
            ProbeError.make(
              "invalid_probe_credential",
              "live probe credential is invalid"
            )
          )

  /** Absent means the Responses dialect, so an existing operator script keeps
    * qualifying the same wire it qualified before. An unrecognised value is
    * rejected rather than defaulted, because silently probing a different
    * dialect than the operator named would corrupt the evidence.
    */
  private def parseDialect(
      value: Option[String]
  ): Either[ProbeError, ProbeDialect] =
    value match
      case None       => Right(ProbeDialect.Responses)
      case Some(text) =>
        ProbeDialect
          .fromWire(text.trim)
          .toRight(
            ProbeError.make(
              "invalid_probe_dialect",
              "probe dialect must be 'responses' or 'harmony-chat'"
            )
          )

  private def parseNodeCount(value: String): Either[ProbeError, Long] =
    try
      val parsed = value.toLong
      Either.cond(
        parsed > 0L,
        parsed,
        ProbeError.make(
          "invalid_probe_node_count",
          "live probe node count must be positive"
        )
      )
    catch
      case NonFatal(_) =>
        Left(
          ProbeError.make(
            "invalid_probe_node_count",
            "live probe node count must be positive"
          )
        )

  private def parsePath(value: String): Either[ProbeError, Path] =
    try Right(Path.of(value))
    catch
      case NonFatal(_) =>
        Left(
          ProbeError.make(
            "invalid_probe_output_directory",
            "live probe output directory is invalid"
          )
        )

  private def parseFlag(value: Option[String]): Either[ProbeError, Boolean] =
    value match
      case None | Some("0") => Right(false)
      case Some("1")        => Right(true)
      case Some(_)          =>
        Left(
          ProbeError.make(
            "invalid_probe_flag",
            "live probe boolean flags must be 0 or 1"
          )
        )

  private def require(
      condition: Boolean,
      code: String,
      message: String
  ): Either[ProbeError, Unit] =
    Either.cond(condition, (), ProbeError.make(code, message))
