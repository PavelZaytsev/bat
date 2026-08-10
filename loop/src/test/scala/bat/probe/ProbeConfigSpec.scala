package bat.probe

import java.nio.file.Path

import bat.telemetry.Measurement
import bat.transport.Secret

import zio.test.*

object ProbeConfigSpec extends ZIOSpecDefault:
  private val EndpointCanary = "probe-endpoint-canary.invalid"
  private val CredentialCanary = "PROBE_CREDENTIAL_CANARY_72c1"
  private val OutputCanary = "probe-output-canary"
  private val Commit = "0123456789abcdef0123456789abcdef01234567"

  def spec: Spec[TestEnvironment, Any] =
    suite("live GPT-OSS probe configuration")(
      test("pins the Responses deployment and builds both live configs") {
        val credential = unsafe(Secret.from(CredentialCanary))
        val config = unsafe(validConfig(credential = Some(credential)))
        val rendered = List(
          config.toString,
          config.transportConfig.toString,
          config.gptOssConfig.toString,
          config.deployment.toString,
          config.outputDirectory.toString,
          config.batCommit.toString
        ).mkString("\n")

        assertTrue(
          config.dialect == ProbeDialect.Responses,
          config.dialect.path == "/v1/responses",
          config.dialect.wire == "responses_sse",
          config.gptOssConfig.identity == config.identity,
          config.gptOssConfig.credential.contains(credential),
          config.credential.contains(credential),
          config.identity.backend == "gpt-oss-responses",
          config.identity.modelId == "openai/gpt-oss-20b",
          config.identity.modelRevision == "weights-2026-08-09",
          config.deployment.runtime == Measurement.Observed("llama.cpp"),
          config.deployment.runtimeRevision == Measurement.Observed(
            "b6200"
          ),
          config.deployment.templateRevision == Measurement.Observed(
            "harmony-2026-08"
          ),
          config.deployment.quantization == Measurement.Observed("mxfp4"),
          config.deployment.topology == Measurement.Observed("exo_thunderbolt"),
          config.deployment.nodeCount == Measurement.Observed(3L),
          config.deployment.protocol == "responses_sse",
          config.runId.value == "probe-run-0001",
          config.batCommit.value == Commit,
          config.outputDirectory.path == Path.of(
            "/private/tmp",
            OutputCanary
          ),
          !rendered.contains(EndpointCanary),
          !rendered.contains(CredentialCanary),
          !rendered.contains(OutputCanary),
          !rendered.contains(Commit)
        )
      },
      test("rejects endpoints that are not a safe absolute HTTP base") {
        val values = List(
          "https://user:pass@models.invalid",
          "https://models.invalid?token=secret",
          "https://models.invalid/base/../admin",
          "file:///private/tmp/model",
          "models.invalid"
        )
        val results = values.map(endpoint =>
          validConfig(endpoint = endpoint).left.map(_.code)
        )
        assertTrue(
          results.forall(_.isLeft),
          results.forall(_.left.exists(_ == "invalid_endpoint"))
        )
      },
      test(
        "requires explicit HTTP opt-in and never sends credentials on HTTP"
      ) {
        val credential = unsafe(Secret.from(CredentialCanary))
        val defaultHttp = validConfig(endpoint = "http://127.0.0.1:8080")
        val optedIn = validConfig(
          endpoint = "http://127.0.0.1:8080",
          allowInsecureHttp = true
        )
        val credentialOnHttp = validConfig(
          endpoint = "http://127.0.0.1:8080",
          credential = Some(credential),
          allowInsecureHttp = true
        )

        assertTrue(
          defaultHttp.left.exists(_.code == "insecure_probe_endpoint"),
          optedIn.exists(_.allowInsecureHttp),
          credentialOnHttp.left.exists(
            _.code == "insecure_probe_credential"
          ),
          !credentialOnHttp.left.toOption.get.toString.contains(
            CredentialCanary
          )
        )
      },
      test("requires safe explicit deployment pins") {
        val placeholder = validConfig(weightRevision = "latest")
        val modelUrl = validConfig(modelId = "https://models.invalid/20b")
        val runtimeUrl = validConfig(runtime = "https://runtime.invalid")
        val credentialLike = validConfig(runtimeRevision = "sk-secret")
        val hostnameTopology = validConfig(topologyClass = "node.internal")
        val absentNodes = validConfig(nodeCount = 0L)
        val whitespace = validConfig(quantization = "mxfp4 production")

        assertTrue(
          placeholder.left.exists(_.code == "invalid_probe_identity"),
          modelUrl.left.exists(_.code == "invalid_probe_deployment"),
          runtimeUrl.left.exists(_.code == "invalid_probe_deployment"),
          credentialLike.left.exists(
            _.code == "invalid_probe_deployment"
          ),
          hostnameTopology.left.exists(
            _.code == "invalid_probe_deployment"
          ),
          absentNodes.left.exists(_.code == "invalid_probe_deployment"),
          whitespace.left.exists(_.code == "invalid_probe_deployment")
        )
      },
      test("validates run, commit, output directory, and credential shape") {
        val relative = validConfig(outputDirectory = Path.of("results"))
        val root = validConfig(outputDirectory = Path.of("/"))
        val nonNormalized = validConfig(
          outputDirectory = Path.of("/private/tmp/a/../probe-results")
        )
        val badRun = validConfig(runId = "bearer-secret")
        val shortCommit = validConfig(batCommit = "abc123")
        val upperCommit = validConfig(batCommit = Commit.toUpperCase)
        val longCommit = validConfig(batCommit = Commit + ("a" * 24))
        val nullCredential = validConfig(
          credential = null.asInstanceOf[Option[Secret]]
        )

        assertTrue(
          relative.left.exists(
            _.code == "invalid_probe_output_directory"
          ),
          root.left.exists(_.code == "invalid_probe_output_directory"),
          nonNormalized.left.exists(
            _.code == "invalid_probe_output_directory"
          ),
          badRun.left.exists(_.code == "invalid_probe_run_id"),
          shortCommit.left.exists(_.code == "invalid_bat_commit"),
          upperCommit.left.exists(_.code == "invalid_bat_commit"),
          longCommit.left.exists(_.code == "invalid_bat_commit"),
          nullCredential.left.exists(_.code == "invalid_probe_credential")
        )
      },
      test("never echoes rejected operator input through errors") {
        val canary = "RAW_OPERATOR_SECRET_CANARY_6f90"
        val invalid = validConfig(runtime = s"https://$canary.invalid")
        val error = invalid.left.toOption.get
        assertTrue(
          !error.toString.contains(canary),
          !error.safeMessage.contains(canary),
          error.toString.contains("payload=<redacted>")
        )
      }
    )

  private def validConfig(
      endpoint: String = s"https://$EndpointCanary",
      credential: Option[Secret] = None,
      modelId: String = "openai/gpt-oss-20b",
      weightRevision: String = "weights-2026-08-09",
      runtime: String = "llama.cpp",
      runtimeRevision: String = "b6200",
      harmonyTemplateRevision: String = "harmony-2026-08",
      quantization: String = "mxfp4",
      topologyClass: String = "exo_thunderbolt",
      nodeCount: Long = 3L,
      runId: String = "probe-run-0001",
      batCommit: String = Commit,
      outputDirectory: Path = Path.of("/private/tmp", OutputCanary),
      allowInsecureHttp: Boolean = false
  ): Either[ProbeError, LiveGptOssProbeConfig] =
    LiveGptOssProbeConfig.make(
      endpoint = endpoint,
      credential = credential,
      modelId = modelId,
      weightRevision = weightRevision,
      runtime = runtime,
      runtimeRevision = runtimeRevision,
      harmonyTemplateRevision = harmonyTemplateRevision,
      quantization = quantization,
      topologyClass = topologyClass,
      nodeCount = nodeCount,
      runId = runId,
      batCommit = batCommit,
      outputDirectory = outputDirectory,
      allowInsecureHttp = allowInsecureHttp
    )

  private def unsafe[E, A](value: Either[E, A]): A =
    value.fold(
      error => throw new IllegalArgumentException(String.valueOf(error)),
      identity
    )
