# Live GPT-OSS conformance probe

BAT includes an explicitly armed probe for checking one pinned GPT-OSS deployment against the
controller contract. The BAT controller can run on a developer laptop or in a small CI/container
job while the model runs on different hardware—an exo cluster, a GPU host, or a hosted inference
service. They need only an HTTP connection; BAT and the model do not need to share a machine.

The current launcher runs from source. On either a host or in a container it requires `/usr/bin/git`
and the clean BAT `.git` checkout whose commit is being recorded. A slim jar-only controller image
does not satisfy that provenance check yet; future BAT image packaging must carry an equally trusted
build-commit attestation before it can replace the checkout.

This is deployment evidence, not a general model benchmark. The probe drives BAT's fixed
three-turn, two-tool scenario and records whether that exact model/runtime/protocol combination can
complete it without violating the controller contract.

## What the probe requires

The cartridge speaks only the OpenAI **Responses API over SSE** at `/v1/responses`. It does not
fall back to Chat Completions, follow a redirect to another dialect, or silently reinterpret an
incompatible response. A server that exposes only a Chat Completions-compatible endpoint is not a
compatible endpoint for this probe.

GPT-OSS uses the Harmony response format. Across tool calls, implementations must preserve and
replay the model's reasoning items as opaque continuation state. OpenAI's
[verification guide](https://developers.openai.com/cookbook/articles/gpt-oss/verifying-implementations)
describes that requirement and recommends the Responses API. BAT performs the replay needed by the
model but never publishes raw reasoning, prompts, provider bodies, tool payloads, credentials, or
raw call IDs in probe artifacts.

## Fake CI and live evidence are different

Ordinary CI runs the same controller, GPT-OSS cartridge, streaming transport, and artifact model
against deterministic loopback servers. Those tests cover fragmented SSE, protocol rejection,
redirect rejection, sanitized telemetry, and the expected audit/apply tool sequence without a GPU,
credential, or external network call.

The live application is separate and requires `BAT_GPT_OSS_LIVE=1`. A live run checks the behavior
of the pinned weights, serving runtime, Harmony template, quantization, and hardware topology. CI
cannot accidentally turn the fake check into a paid or cluster-backed run merely by discovering an
endpoint.

**No real GPT-OSS 20B or 120B deployment has passed this probe in this repository yet.** A checked-in
fake result must not be presented as live model evidence.

## Configure a live run

Configuration is environment-only so a token or private endpoint never has to appear in process
arguments. Set every required variable before starting the application:

```bash
export BAT_GPT_OSS_LIVE='1'
export BAT_GPT_OSS_ENDPOINT='https://inference.example.internal'
export BAT_GPT_OSS_MODEL_ID='gpt-oss-20b'
export BAT_GPT_OSS_WEIGHT_REVISION='weights-revision-pinned-by-operator'
export BAT_GPT_OSS_RUNTIME='vllm'
export BAT_GPT_OSS_RUNTIME_REVISION='runtime-revision-pinned-by-operator'
export BAT_GPT_OSS_HARMONY_TEMPLATE_REVISION='harmony-template-revision'
export BAT_GPT_OSS_QUANTIZATION='mxfp4'
export BAT_GPT_OSS_TOPOLOGY='single-node'
export BAT_GPT_OSS_NODE_COUNT='1'
export BAT_GPT_OSS_RUN_ID='gpt-oss-20b-live-001'
export BAT_GPT_OSS_BAT_COMMIT="$(git rev-parse HEAD)"
export BAT_GPT_OSS_OUTPUT="$(cd .. && pwd -P)/bat-gpt-oss-20b-live-001"
```

`BAT_GPT_OSS_BAT_COMMIT` must be the full 40-character lowercase commit ID for the BAT code being
run. BAT resolves the actual Git top level, requires a clean checkout, and verifies that its `HEAD`
matches this pin before constructing the HTTP client. Deployment fields and the run ID must be
bounded, operator-safe identifiers; pin real revisions instead of labels such as `latest` when the
runtime exposes them.

The complete environment surface is:

| variable | required | meaning |
|---|---:|---|
| `BAT_GPT_OSS_LIVE` | yes | Must be exactly `1`; this is the network-call arming switch. |
| `BAT_GPT_OSS_ENDPOINT` | yes | Base HTTPS endpoint; BAT sends Responses requests to `/v1/responses`. |
| `BAT_GPT_OSS_TOKEN` | no | Bearer credential. If present, it must be non-empty and the endpoint must use HTTPS. |
| `BAT_GPT_OSS_MODEL_ID` | yes | Operator-pinned served model identifier. |
| `BAT_GPT_OSS_WEIGHT_REVISION` | yes | Exact weights revision recorded in evidence. |
| `BAT_GPT_OSS_RUNTIME` | yes | Serving runtime name. |
| `BAT_GPT_OSS_RUNTIME_REVISION` | yes | Serving runtime revision. |
| `BAT_GPT_OSS_HARMONY_TEMPLATE_REVISION` | yes | Harmony/template revision used by the deployment. |
| `BAT_GPT_OSS_QUANTIZATION` | yes | Quantization recorded in the deployment fingerprint. |
| `BAT_GPT_OSS_TOPOLOGY` | yes | Stable topology class, for example `single-node` or `exo-cluster`. |
| `BAT_GPT_OSS_NODE_COUNT` | yes | Positive integer node count. |
| `BAT_GPT_OSS_RUN_ID` | yes | Unique bounded identifier for this evidence set. |
| `BAT_GPT_OSS_BAT_COMMIT` | yes | Full lowercase 40-character BAT Git commit. |
| `BAT_GPT_OSS_OUTPUT` | yes | Absolute, normalized destination that does not exist yet. |
| `BAT_GPT_OSS_ALLOW_INSECURE_HTTP` | no | `1` permits credential-free HTTP; absent or `0` requires HTTPS. |

Provide `BAT_GPT_OSS_TOKEN` through the shell or secret manager only when the endpoint requires it.
Do not put the token on the Scala command line. Plain HTTP is intended only for a deliberately
selected credential-free local or trusted test network:

```bash
unset BAT_GPT_OSS_TOKEN
export BAT_GPT_OSS_ENDPOINT='http://127.0.0.1:8080'
export BAT_GPT_OSS_ALLOW_INSECURE_HTTP='1'
```

The insecure opt-in never permits a bearer token over HTTP. With a token present, HTTPS remains
mandatory.

### Prepare the evidence destination

The output path is a publication boundary, not a work directory. Before the run:

- create its parent directory, leave the selected output directory absent, and ensure the parent is
  owned by the BAT checkout owner and is not group- or world-writable;
- use an absolute, normalized, non-root path;
- place it outside the BAT checkout, and do not select an ancestor of the checkout; and
- use real path components rather than symbolic links.

BAT prepares a private sibling staging directory before constructing the live HTTP client. On the
supported Linux/macOS POSIX filesystems it publishes the complete evidence set with one
same-filesystem directory move, using the no-replace contract and refusing a destination that
appears during the run. Configuration or output-boundary errors therefore cause zero model
requests.

## Run it

From the BAT checkout pinned by `BAT_GPT_OSS_BAT_COMMIT`:

```bash
scala-cli run --server=false loop --main-class bat.probe.LiveGptOssProbeApp
```

The application prints only the verdict and a bounded reason code. Exit codes are stable:

| exit | meaning |
|---:|---|
| `0` | compatible |
| `2` | incompatible wire protocol or Responses dialect |
| `3` | nonconformant model behavior in the pinned scenario |
| `4` | blocked by credentials, rate limits, timeout, or endpoint availability |
| `64` | invalid, missing, unsafe, or unarmed configuration |
| `70` | internal setup or artifact-publication failure |

## Verdicts

| verdict | interpretation |
|---|---|
| `compatible` | The deployment completed the exact three-turn, two-tool audit/apply scenario and reached the expected BDR checkpoint. |
| `incompatible` | The endpoint could not satisfy BAT's Responses/SSE wire contract. This does not trigger a Chat fallback. |
| `nonconformant` | The wire protocol worked, but the model stopped early, used the wrong tools/order, exceeded a logical budget, or otherwise failed the scenario contract. |
| `blocked` | Infrastructure prevented a meaningful result—for example authorization, rate limiting, timeout, or endpoint unavailability. |

A blocked run is not evidence that the model is incompatible, and a fake compatible run is not
evidence that a live deployment is compatible.

## Published artifacts

A successful publication always contains exactly these fixed files:

| file | contents |
|---|---|
| `result.json` | Canonical verdict, reason code, BAT commit, deployment fingerprint, embedded sanitized documents, and SHA-256 digests. |
| `safe-trace.json` | Reasoning-redacted controller trace, or the JSON literal `null` when no successful trace is available. |
| `telemetry.json` | Canonical payload-free run, provider-attempt, token, tool, timing, and terminal telemetry. |

The digests in `result.json` cover the exact UTF-8 bytes of `safe-trace.json` and `telemetry.json`.
Files are owner-only, artifacts are validated and scanned in memory for the configured endpoint,
output path, and token, and the final directory appears only after the entire set is ready.

For real 20B evidence, preserve all three files together with the deployment metadata as generated.
Do not edit a result, substitute a fake trace, or separate it from the BAT commit and deployment
fingerprint that make the run reproducible.
