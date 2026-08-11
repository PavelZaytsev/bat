package bat.probe

import java.nio.file.Path

import zio.test.*

object ProbeEnvironmentSpec extends ZIOSpecDefault:
  private val Endpoint = "https://probe-environment.invalid"
  private val Token = "PROBE_ENV_TOKEN_CANARY_8e21"
  private val Output = "/private/tmp/bat-probe-environment"
  private val Commit = "0123456789abcdef0123456789abcdef01234567"

  def spec: Spec[TestEnvironment, Any] =
    suite("live GPT-OSS probe environment")(
      test("requires explicit arming before any other configuration") {
        val absent = ProbeEnvironment.from(Map.empty)
        val disabled = ProbeEnvironment.from(
          complete.updated(
            "BAT_GPT_OSS_LIVE",
            "0"
          )
        )

        assertTrue(
          absent.left.exists(_.code == "probe_not_armed"),
          disabled.left.exists(_.code == "probe_not_armed")
        )
      },
      test("loads one complete, explicitly armed environment") {
        val loaded = unsafe(ProbeEnvironment.from(complete))

        assertTrue(
          loaded.config.dialect == ProbeDialect.Responses,
          loaded.config.transportConfig != null,
          loaded.config.credential.nonEmpty,
          loaded.config.runId.value == "probe-environment-0001",
          loaded.config.batCommit.value == Commit,
          loaded.config.outputDirectory.path == Path.of(Output),
          loaded.forbiddenArtifactValues.contains(Endpoint),
          loaded.forbiddenArtifactValues.contains(Token),
          loaded.forbiddenArtifactValues.contains(Output)
        )
      },
      test("rejects a present but empty token") {
        val result = ProbeEnvironment.from(
          complete.updated("BAT_GPT_OSS_TOKEN", "")
        )

        assertTrue(
          result.left.exists(_.code == "invalid_probe_credential")
        )
      },
      test("never permits a token over an insecure HTTP endpoint") {
        val result = ProbeEnvironment.from(
          complete ++ Map(
            "BAT_GPT_OSS_ENDPOINT" -> "http://127.0.0.1:8080",
            "BAT_GPT_OSS_ALLOW_INSECURE_HTTP" -> "1"
          )
        )

        assertTrue(
          result.left.exists(_.code == "insecure_probe_credential")
        )
      },
      test("rejects malformed flags, node counts, and output paths") {
        val flag = ProbeEnvironment.from(
          complete.updated("BAT_GPT_OSS_ALLOW_INSECURE_HTTP", "true")
        )
        val zeroNodes = ProbeEnvironment.from(
          complete.updated("BAT_GPT_OSS_NODE_COUNT", "0")
        )
        val textNodes = ProbeEnvironment.from(
          complete.updated("BAT_GPT_OSS_NODE_COUNT", "many")
        )
        val relativePath = ProbeEnvironment.from(
          complete.updated("BAT_GPT_OSS_OUTPUT", "probe-output")
        )
        val nonNormalizedPath = ProbeEnvironment.from(
          complete.updated(
            "BAT_GPT_OSS_OUTPUT",
            "/private/tmp/a/../probe-output"
          )
        )

        assertTrue(
          flag.left.exists(_.code == "invalid_probe_flag"),
          zeroNodes.left.exists(_.code == "invalid_probe_node_count"),
          textNodes.left.exists(_.code == "invalid_probe_node_count"),
          relativePath.left.exists(
            _.code == "invalid_probe_output_directory"
          ),
          nonNormalizedPath.left.exists(
            _.code == "invalid_probe_output_directory"
          )
        )
      },
      test("redacts loaded configuration and rejected operator values") {
        val loaded = unsafe(ProbeEnvironment.from(complete))
        val invalidCanary = "RAW_ENVIRONMENT_CANARY_1138"
        val rejected = ProbeEnvironment
          .from(
            complete.updated(
              "BAT_GPT_OSS_RUNTIME",
              s"https://$invalidCanary.invalid"
            )
          )
          .left
          .toOption
          .get
        val renderings = List(
          loaded.toString,
          loaded.config.toString,
          loaded.config.transportConfig.toString,
          loaded.config.backendConfig.toString,
          loaded.config.outputDirectory.toString,
          loaded.config.batCommit.toString,
          rejected.toString,
          rejected.safeMessage
        ).mkString("\n")

        assertTrue(
          !renderings.contains(Endpoint),
          !renderings.contains(Token),
          !renderings.contains(Output),
          !renderings.contains(Commit),
          !renderings.contains(invalidCanary),
          loaded.toString.contains("configuration=<redacted>"),
          rejected.toString.contains("payload=<redacted>")
        )
      },
      test("keeps the documented verdict and failure exit codes stable") {
        val configuration = ProbeError.make(
          "missing_probe_configuration",
          "configuration is missing"
        )
        val internal = ProbeError.make(
          "probe_artifact_publication_failed",
          "publication failed"
        )
        val commitMismatch = ProbeError.make(
          "probe_bat_commit_mismatch",
          "commit mismatch"
        )

        assertTrue(
          LiveGptOssProbeApp.exitCode(ProbeVerdict.Compatible) == 0,
          LiveGptOssProbeApp.exitCode(ProbeVerdict.Incompatible) == 2,
          LiveGptOssProbeApp.exitCode(ProbeVerdict.Nonconformant) == 3,
          LiveGptOssProbeApp.exitCode(ProbeVerdict.Blocked) == 4,
          LiveGptOssProbeApp.exitCode(configuration) == 64,
          LiveGptOssProbeApp.exitCode(commitMismatch) == 64,
          LiveGptOssProbeApp.exitCode(internal) == 70
        )
      }
    )

  private val complete: Map[String, String] = Map(
    "BAT_GPT_OSS_LIVE" -> "1",
    "BAT_GPT_OSS_ENDPOINT" -> Endpoint,
    "BAT_GPT_OSS_TOKEN" -> Token,
    "BAT_GPT_OSS_MODEL_ID" -> "openai/gpt-oss-20b",
    "BAT_GPT_OSS_WEIGHT_REVISION" -> "weights-2026-08-09",
    "BAT_GPT_OSS_RUNTIME" -> "llama.cpp",
    "BAT_GPT_OSS_RUNTIME_REVISION" -> "b6200",
    "BAT_GPT_OSS_HARMONY_TEMPLATE_REVISION" -> "harmony-2026-08",
    "BAT_GPT_OSS_QUANTIZATION" -> "mxfp4",
    "BAT_GPT_OSS_TOPOLOGY" -> "exo_thunderbolt",
    "BAT_GPT_OSS_NODE_COUNT" -> "3",
    "BAT_GPT_OSS_RUN_ID" -> "probe-environment-0001",
    "BAT_GPT_OSS_BAT_COMMIT" -> Commit,
    "BAT_GPT_OSS_OUTPUT" -> Output,
    "BAT_GPT_OSS_ALLOW_INSECURE_HTTP" -> "0"
  )

  private def unsafe[E, A](value: Either[E, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(String.valueOf(error)),
      identity
    )
