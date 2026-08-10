package bat.probe

import java.nio.file.Path

import bat.protocol.StrictJson
import bat.telemetry.{Measurement, TelemetryEvent}
import bat.transport.StreamingHttp

import zio.*
import zio.http.Client
import zio.test.*

object LiveGptOssProbeSpec extends ZIOSpecDefault:
  private val BatCommit = "fedcba9876543210fedcba9876543210fedcba98"

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("live GPT-OSS Responses probe")(
      test(
        "executes the three-turn golden trace through loopback and emits only sanitized evidence"
      ) {
        run(ProbeResponsesFixtures.golden, "compatible").map {
          case (artifact, requests, endpoint) =>
            val encoded = artifact.canonicalJson
            val firstBody = requests(0).body
            val secondBody = requests(1).body
            val thirdBody = requests(2).body
            val telemetryCanonical = artifact.telemetry.canonicalJson

            assertTrue(
              artifact.verdict == ProbeVerdict.Compatible,
              artifact.reasonCode.isEmpty,
              artifact.safeTrace.nonEmpty,
              artifact.telemetry.summary.modelTurns == 3,
              artifact.telemetry.summary.providerAttempts == 3,
              artifact.telemetry.summary.retries == 0,
              artifact.telemetry.summary.toolExecutions == 2,
              encoded.contains(BatCommit),
              artifact.telemetry.summary.measurements.tokens.total ==
                Measurement.Observed(260L),
              artifact.telemetry.records.size == 14,
              requests.size == 3,
              requests.forall(request =>
                request.method == "POST" &&
                  request.path == "/v1/responses" &&
                  request.accept.contains("text/event-stream") &&
                  request.contentType.exists(
                    _.startsWith("application/json")
                  ) &&
                  request.authorization.isEmpty
              ),
              !firstBody.contains(
                ProbeResponsesFixtures.ReasoningCanaryOne
              ),
              secondBody.contains(
                ProbeResponsesFixtures.ReasoningCanaryOne
              ),
              !secondBody.contains(
                ProbeResponsesFixtures.ReasoningCanaryTwo
              ),
              thirdBody.contains(
                ProbeResponsesFixtures.ReasoningCanaryOne
              ),
              thirdBody.contains(
                ProbeResponsesFixtures.ReasoningCanaryTwo
              ),
              ProbeResponsesFixtures.sensitiveCanaries.forall(canary =>
                !encoded.contains(canary)
              ),
              !encoded.contains(endpoint),
              !encoded.contains("Run exactly one BAT protocol-conformance"),
              !encoded.contains("Run the pinned two-tool BAT conformance"),
              !encoded.contains("operation_json"),
              !artifact.toString.contains(endpoint),
              ProbeResponsesFixtures.sensitiveCanaries.forall(canary =>
                !artifact.toString.contains(canary)
              ),
              telemetryCanonical.exists(telemetry =>
                encoded.contains(telemetry)
              ),
              StrictJson.canonical(
                artifact.json,
                "probe test artifact"
              ) == Right(encoded)
            )
        }
      },
      test(
        "reports a Chat Completions body as incompatible without falling back"
      ) {
        run(ProbeResponsesFixtures.wrongDialect, "wrong-dialect").map {
          case (artifact, requests, endpoint) =>
            assertTrue(
              artifact.verdict == ProbeVerdict.Incompatible,
              artifact.reasonCode.exists(
                _.value == "gpt_oss_content_type"
              ),
              artifact.safeTrace.isEmpty,
              requests.size == 1,
              requests.head.path == "/v1/responses",
              !artifact.canonicalJson.contains(
                ProbeResponsesFixtures.ProviderBodyCanary
              ),
              !artifact.canonicalJson.contains(
                ProbeResponsesFixtures.CredentialCanary
              ),
              !artifact.canonicalJson.contains(endpoint)
            )
        }
      },
      test(
        "reports a truncated Responses stream as incompatible without executing a tool"
      ) {
        run(ProbeResponsesFixtures.truncated, "truncated").map {
          case (artifact, requests, endpoint) =>
            assertTrue(
              artifact.verdict == ProbeVerdict.Incompatible,
              artifact.reasonCode.exists(
                _.value == "gpt_oss_protocol_violation"
              ),
              artifact.safeTrace.isEmpty,
              artifact.telemetry.summary.modelTurns == 1,
              artifact.telemetry.summary.providerAttempts == 1,
              artifact.telemetry.summary.toolExecutions == 0,
              requests.size == 1,
              requests.head.path == "/v1/responses",
              ProbeResponsesFixtures.sensitiveCanaries.forall(canary =>
                !artifact.canonicalJson.contains(canary)
              ),
              !artifact.canonicalJson.contains(endpoint)
            )
        }
      },
      test("does not follow a redirect away from the Responses endpoint") {
        run(ProbeResponsesFixtures.redirect, "redirect").map {
          case (artifact, requests, endpoint) =>
            assertTrue(
              artifact.verdict == ProbeVerdict.Incompatible,
              artifact.reasonCode.exists(
                _.value == "gpt_oss_http_status"
              ),
              artifact.safeTrace.isEmpty,
              requests.size == 1,
              requests.head.path == "/v1/responses",
              !artifact.canonicalJson.contains(endpoint)
            )
        }
      },
      test(
        "classifies a valid premature final as scenario nonconformance"
      ) {
        run(ProbeResponsesFixtures.prematureFinal, "premature-final").map {
          case (artifact, requests, endpoint) =>
            assertTrue(
              artifact.verdict == ProbeVerdict.Nonconformant,
              artifact.reasonCode.exists(
                _.value == "probe_scenario_nonconformant"
              ),
              artifact.safeTrace.isEmpty,
              artifact.telemetry.records.lastOption.exists(
                _.event.isInstanceOf[TelemetryEvent.RunFailed]
              ),
              artifact.telemetry.summary.modelTurns == 1,
              artifact.telemetry.summary.providerAttempts == 1,
              artifact.telemetry.summary.toolExecutions == 0,
              requests.size == 1,
              requests.head.path == "/v1/responses",
              ProbeResponsesFixtures.sensitiveCanaries.forall(canary =>
                !artifact.canonicalJson.contains(canary)
              ),
              !artifact.canonicalJson.contains(endpoint)
            )
        }
      },
      test(
        "classifies authentication and request timeout as blocked, and a missing dialect as incompatible"
      ) {
        for
          unauthorized <- run(
            ProbeResponsesFixtures.unauthorized,
            "unauthorized"
          )
          timedOut <- run(
            ProbeResponsesFixtures.requestTimeout,
            "request-timeout"
          )
          unavailable <- run(
            ProbeResponsesFixtures.responsesUnavailable,
            "responses-unavailable"
          )
        yield
          val (blocked, blockedRequests, blockedEndpoint) = unauthorized
          val (timeout, timeoutRequests, timeoutEndpoint) = timedOut
          val (incompatible, incompatibleRequests, incompatibleEndpoint) =
            unavailable
          assertTrue(
            blocked.verdict == ProbeVerdict.Blocked,
            blocked.reasonCode.exists(_.value == "gpt_oss_unauthorized"),
            blocked.safeTrace.isEmpty,
            blockedRequests.size == 1,
            blockedRequests.head.path == "/v1/responses",
            !blocked.canonicalJson.contains(
              ProbeResponsesFixtures.ProviderBodyCanary
            ),
            !blocked.canonicalJson.contains(blockedEndpoint),
            timeout.verdict == ProbeVerdict.Blocked,
            timeout.reasonCode.exists(
              _.value == "gpt_oss_request_timeout"
            ),
            timeoutRequests.size == 1,
            !timeout.canonicalJson.contains(timeoutEndpoint),
            incompatible.verdict == ProbeVerdict.Incompatible,
            incompatible.reasonCode.exists(
              _.value == "gpt_oss_responses_unavailable"
            ),
            incompatible.safeTrace.isEmpty,
            incompatibleRequests.size == 1,
            incompatibleRequests.head.path == "/v1/responses",
            !incompatible.canonicalJson.contains(
              ProbeResponsesFixtures.ProviderBodyCanary
            ),
            !incompatible.canonicalJson.contains(incompatibleEndpoint)
          )
      }
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)

  private def run(
      script: Chunk[LoopbackResponsesServer.ScriptedResponse],
      runSuffix: String
  ) =
    LoopbackResponsesServer.use(script) { server =>
      for
        config <- ZIO.fromEither(
          LiveGptOssProbeConfig.make(
            endpoint = server.baseUrl,
            credential = None,
            modelId = ProbeResponsesFixtures.ModelId,
            weightRevision = ProbeResponsesFixtures.ModelRevision,
            runtime = ProbeResponsesFixtures.Runtime,
            runtimeRevision = ProbeResponsesFixtures.RuntimeRevision,
            harmonyTemplateRevision = ProbeResponsesFixtures.TemplateRevision,
            quantization = ProbeResponsesFixtures.Quantization,
            topologyClass = ProbeResponsesFixtures.Topology,
            nodeCount = 1L,
            runId = s"probe-$runSuffix-001",
            batCommit = BatCommit,
            outputDirectory = Path.of("/tmp/bat-probe-tests"),
            allowInsecureHttp = true,
            maxAttempts = 1,
            retryDelay = Duration.Zero
          )
        )
        artifact <- ZIO
          .serviceWithZIO[StreamingHttp](http =>
            LiveGptOssProbe.run(config, http)
          )
          .provideLayer(
            Client.default >>> StreamingHttp.configured(
              config.transportConfig
            )
          )
        requests <- server.requests
      yield (artifact, requests, server.baseUrl)
    }
