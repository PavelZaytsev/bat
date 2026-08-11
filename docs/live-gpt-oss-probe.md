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

## Choosing a dialect

The probe qualifies exactly one wire dialect per run, selected by `BAT_GPT_OSS_DIALECT`:

| value | endpoint | when to use it |
|---|---|---|
| `responses` (default) | `POST /v1/responses` over SSE | Hosted or self-served endpoints that emit `response.reasoning_text.*` and replay reasoning items verbatim. |
| `harmony-chat` | `POST /v1/chat/completions` over SSE | Endpoints that carry the raw analysis channel in a first-class `reasoning_content` field, such as exo. |

Neither dialect is a fallback for the other. A failure on one never re-attempts the other, because
the point of a probe run is to record which wire a deployment actually satisfies. The selected
dialect becomes part of the pinned backend identity and of the recorded deployment fingerprint, so
an artifact always says which wire produced its verdict.

**For exo, select `harmony-chat`.** exo serves both endpoints, but its Responses surface is a
translation shim that flattens replayed reasoning into ordinary assistant content and emits a
`response.reasoning_summary_*` vocabulary. Only its Chat Completions surface round-trips raw
reasoning. See [`docs/adr/0005-wire-dialect-seam.md`](adr/0005-wire-dialect-seam.md) for the
measured evidence.

## What the probe requires

Both dialects require SSE streaming, function calling with exact call identifiers, replayable raw
reasoning, and terminal usage. Neither follows a redirect to another dialect or silently
reinterprets an incompatible response.

The `responses` dialect additionally requires the `response.reasoning_text.*` event vocabulary and
verbatim replay of provider output items. The `harmony-chat` dialect reconstructs the assistant
message from streamed deltas instead, so it additionally requires that reasoning be non-empty, that
tool call identity never change mid-stream, that the served model match the pin, and that the stream
reach the `[DONE]` sentinel. A turn that fails any of those is a failed turn, not a best effort.

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
| `BAT_GPT_OSS_DIALECT` | no | `responses` (default) or `harmony-chat`. An unrecognised value is rejected rather than defaulted. |

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

### Example: an exo cluster over plain HTTP

exo serves the OpenAI-compatible API on port `52415` without a credential, so this is the
credential-free HTTP case. Replace the address, and pin the deployment values from the cluster
operator rather than guessing them:

```bash
export BAT_GPT_OSS_LIVE='1'
export BAT_GPT_OSS_DIALECT='harmony-chat'
export BAT_GPT_OSS_ENDPOINT='http://10.0.0.1:52415'
export BAT_GPT_OSS_ALLOW_INSECURE_HTTP='1'
unset BAT_GPT_OSS_TOKEN
export BAT_GPT_OSS_MODEL_ID='openai/gpt-oss-20b'
export BAT_GPT_OSS_RUNTIME='exo'
export BAT_GPT_OSS_TOPOLOGY='exo_thunderbolt'
export BAT_GPT_OSS_NODE_COUNT='1'
```

`BAT_GPT_OSS_MODEL_ID` must be the served identifier exactly as `/v1/models` reports it, because the
cartridge rejects a response attributed to a different model.

#### exo placement is setup, not bootstrap

A registered model card and a placed instance are different state. `POST /models/add` creates a
card, which is what `/v1/models` reports; only `POST /place_instance` creates a runner with shard
assignments. There is no lazy placement on first request — exo's chat path fails closed with
`404 No instance found for model ...`. So a downloaded, registered, visible model still serves
nothing while `instances` is empty.

Both cards and instances are in-memory cluster state and **do not survive an exo restart**. Treat
place-then-verify as part of every run's setup rather than a one-time bootstrap: if placement was
expected and `instances` is empty, the likely cause is a restart, not a failed POST.

```bash
# 1. place (single node; multi-node still needs the topology-edge work)
curl -sS -X POST "$BAT_GPT_OSS_ENDPOINT/place_instance" \
  -H 'Content-Type: application/json' \
  -d "{\"model_id\":\"$BAT_GPT_OSS_MODEL_ID\",\"sharding\":\"Pipeline\",\"instance_meta\":\"MlxRing\",\"min_nodes\":1}"

# 2. wait until a runner exists with shards assigned, and absorb the cold load
until curl -sS "$BAT_GPT_OSS_ENDPOINT/state" \
  | python3 -c 'import sys,json; sys.exit(0 if json.load(sys.stdin).get("instances") else 1)'
do sleep 5; done
```

The readiness poll matters for a second reason: after placement the runner must load the weights —
roughly 13 GB for the 20b — before it serves anything. The first request can therefore take tens of
seconds while later ones are fast. Polling until the instance appears moves that cold load outside
the probe, so a cold start cannot be misread as a deployment failure. If you skip the poll, raise
the transport's body-idle allowance rather than the retry count; a retry would re-queue work the
server has already started.

#### Give a reasoning model room to answer

gpt-oss spends its token allowance on the analysis channel first. With a small `max_tokens` it can
return status `200`, valid framing, and `content: ''` — measured on `gpt-oss-20b` at `max_tokens: 24`:
`completion_tokens: 24`, of which `reasoning_tokens: 21`, and no visible output. At `max_tokens: 250`
the same deployment produced 237 tokens of real prose.

That is a budget fact, not a wire fault, and it is the cosmetic-looking failure most likely to waste
an afternoon. BAT reports it as `harmony_chat_output_budget_exhausted` with the observed
`reasoning_tokens`, `output_tokens`, and content length, rather than as a protocol violation. Allow at
least ~150 output tokens per probe turn; the pinned conformance scenario already budgets 32768.

A 404 from the chat path has two distinguishable causes — no instance for a downloaded model, versus
weights that are absent, which also raises a user-facing download notification. Both fail closed, so
BAT reports the endpoint's own detail string alongside the status in the operator-facing message
(`status=404, detail=No instance found for model ...`). The reason code in the artifact stays stable;
the detail is for the human reading the console.

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
