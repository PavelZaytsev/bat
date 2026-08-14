# Live Java acceptance runbook

This is the supervised proof-of-concept path for issue #25. It connects BAT's
trusted controller to an already-qualified exo GPT-OSS endpoint, gives the model
only the isolated Java worker tools, and evaluates the delivered patch in a
separate networkless OCI run.

It is deliberately not a cluster manager. An operator starts or replaces the
model in exo; BAT waits for the fixed, previously qualified Harmony Chat path to
recover. Ordinary CI never opens a model endpoint.

## What survives a restart

There are two different restart contracts:

- **exo restarts while BAT remains alive.** BAT retains the opaque Harmony
  continuation in memory and retries the exact immutable unfinished request.
  The live Java profile opts into retrying transport open/body failures, 408,
  5xx, and a temporary 404 from an already-qualified self-hosted path. It never
  falls back to another API. A response is fully framed and validated before
  any Java tool runs, so inference may be duplicated but a tool mutation cannot
  be duplicated by this retry.
- **the BAT controller restarts.** This is a new attempt, not continuation of
  hidden model reasoning. The logical run ID, pinned source, workspace, BDR
  journal, and completed worker receipts remain durable. The new attempt gets a
  distinct attempt ID and a fresh model context. Its reviewed resume prompt
  tells the model to read `worker_workspace` and `bdr_audit_summary`, then
  continue the persisted next action without recreating prior work.

Every telemetry event rewrites one canonical private checkpoint atomically.
The checkpoint includes the sanitized event records, latest BDR identity,
current-attempt counters, and cumulative iteration/tool/token/wall counters.
The next attempt receives only the remaining cumulative budget. A configuration,
source, model, image, or deployment-pin change breaks the checkpoint binding.

An interrupted patch or Git commit still fails closed as an indeterminate
mutation. An operator must reconcile it; BAT never guesses whether to repeat it.

## Prerequisites

1. A clean BAT checkout at the exact commit supplied as `BAT_LIVE_BAT_COMMIT`.
2. A separate clean source repository containing the exact authenticated refs
   `refs/heads/bat-base` and `refs/heads/bat-head` at the supplied commits.
   The maintained fixture's exact two-commit materialization contract is in
   [`examples/java-six-phase/README.md`](../examples/java-six-phase/README.md);
   never copy its `oracle/` or `reference/` trees into that source repository.
3. A digest-pinned worker image. The maintained dependency-free canary uses
   [`images/java-worker/Dockerfile.canary`](../images/java-worker/Dockerfile.canary),
   whose build context is this BAT checkout and does not execute target code.
   The Maven dependency-seed
   [`images/java-worker/Dockerfile`](../images/java-worker/Dockerfile) is only
   for a separately reviewed pilot context that contains `pom.xml` and `src/`;
   it is not the canary image.
4. A pre-existing private root and a pre-existing evidence root beneath it,
   both owned by the operator and mode `0700`.
5. The evaluator oracle outside the source repository and outside the private
   worker root. The actor never receives this path or its contents.
6. An exo endpoint serving the explicitly named model through native Harmony
   Chat SSE. Use a stable DNS or `.local` hostname rather than a link-local
   numeric address, because a macOS link-local address can change on restart.
7. Exact operator observations for model revision, exo/runtime revision,
   Harmony template revision, quantization, topology, and active node count.
   Do not infer or copy values from a previous run.

The trusted controller needs Docker or Podman, Git, a JDK-capable worker image,
and HTTP reachability to exo. It does not need SSH access to the inference Macs.

Build and pin the canary image before filling the environment below:

```bash
BAT_ROOT='<absolute-clean-bat-checkout>'
docker build \
  --file "$BAT_ROOT/images/java-worker/Dockerfile.canary" \
  --tag bat-java-canary:local \
  "$BAT_ROOT/images/java-worker"
BAT_IMAGE_ID="$(docker image inspect bat-java-canary:local --format '{{.Id}}')"
case "$BAT_IMAGE_ID" in sha256:*) ;; *) exit 1 ;; esac
export BAT_LIVE_IMAGE="bat-java-canary@${BAT_IMAGE_ID}"
```

The image ID pins the exact local image content. Do not use a mutable tag in
`BAT_LIVE_IMAGE`. For a registry-delivered image, use its immutable manifest
digest instead.

## First attempt

Use private paths outside the BAT checkout. Values below are placeholders; do
not copy endpoint names, private repositories, or credentials into this repo.

```bash
export BAT_LIVE_PRIVATE_ROOT='<absolute-private-root>'
export BAT_LIVE_OUTPUT='<absolute-private-root>/evidence'
umask 077
mkdir -p -- "$BAT_LIVE_PRIVATE_ROOT" "$BAT_LIVE_OUTPUT"
chmod 0700 "$BAT_LIVE_PRIVATE_ROOT" "$BAT_LIVE_OUTPUT"

export BAT_LIVE_ARM=issue-25
export BAT_LIVE_CASE=canary
export BAT_LIVE_ENDPOINT='http://model-host.local:52415'
export BAT_LIVE_MODEL='openai/gpt-oss-120b'
export BAT_LIVE_MODEL_REVISION='<exact-weights-revision>'
export BAT_LIVE_RUNTIME='exo'
export BAT_LIVE_RUNTIME_REVISION='<exact-exo-runtime-revision>'
export BAT_LIVE_TEMPLATE_REVISION='<exact-harmony-template-revision>'
export BAT_LIVE_QUANTIZATION='<exact-quantization>'
export BAT_LIVE_TOPOLOGY='<exact-topology>'
export BAT_LIVE_NODE_COUNT='<observed-positive-node-count>'
export BAT_LIVE_REASONING_EFFORT=high

export BAT_LIVE_IMAGE="${BAT_LIVE_IMAGE:?build-and-pin-the-worker-first}"
export BAT_LIVE_GIT="$(command -v git)"
export BAT_LIVE_OCI_RUNTIME="$(command -v docker)"
export BAT_LIVE_UID="$(id -u)"
export BAT_LIVE_GID="$(id -g)"

export BAT_LIVE_RUN_ID='<stable-logical-run-id>'
export BAT_LIVE_ATTEMPT_ID=attempt-001
export BAT_LIVE_RESUME=false
unset BAT_LIVE_PREVIOUS_ATTEMPT_ID

export BAT_LIVE_REPOSITORY_ID='<privacy-safe-repository-id>'
export BAT_LIVE_BASE_COMMIT='<40-hex-base-commit>'
export BAT_LIVE_HEAD_COMMIT='<40-hex-target-commit>'
export BAT_LIVE_BAT_COMMIT='<clean-40-hex-bat-commit>'
export BAT_LIVE_BAT_ROOT='<absolute-clean-bat-checkout>'
export BAT_LIVE_SOURCE='<absolute-pinned-source-repository>'
export BAT_LIVE_ORACLE='<absolute-sealed-oracle-directory>'

cd "$BAT_LIVE_BAT_ROOT"
scala-cli run --server=false loop \
  --main-class bat.runner.LiveJavaProductionApp
```

For the maintained canary, `BAT_LIVE_ORACLE` is the hidden Java source root.
For the Apache pilot, set `BAT_LIVE_CASE=apache` and supply its sealed oracle
patch instead. Run the Apache case only after retaining the canary result.

## Controller restart

Keep the same logical run and all semantic pins. Choose a new attempt ID and
name the immediately preceding attempt:

```bash
export BAT_LIVE_RESUME=true
export BAT_LIVE_ATTEMPT_ID=attempt-002
export BAT_LIVE_PREVIOUS_ATTEMPT_ID=attempt-001

cd "$BAT_LIVE_BAT_ROOT"
scala-cli run --server=false loop \
  --main-class bat.runner.LiveJavaProductionApp
```

The previous checkpoint may be in either `attempt-001/checkpoint.json` after a
graceful publication or `.attempt-001.in-progress/checkpoint.json` after a
controller crash. Only `running` or `failed` attempts are resumable. A ready,
rejected, or terminal result cannot be extended as if it were interrupted.

## Evidence layout

During execution the private evidence root contains:

```text
.<run-id>.lineage.json
.attempt-001.in-progress/
  checkpoint.json
```

On graceful completion the whole directory is renamed without replacement:

```text
.<run-id>.lineage.json
attempt-001/
  checkpoint.json
  result.json
  telemetry.json
  evidence.json       # ready/rejected/terminal only
```

`result.json` binds the logical run, attempt, telemetry digest, decision, and
optional production-evidence digest. The final production evidence binds the
authenticated source, worker image/policy, BDR state, delivered commit/patch,
and evaluator report. A failed attempt has no fabricated handoff evidence.

Retain the complete evidence root, including the hidden lineage file. It binds
the logical run to its current attempt tip and is required for a controller
resume; retaining only an individual attempt directory is insufficient. The
lock file is transient coordination state and need not be archived.

The process exits successfully only for `ready`. `rejected`, `terminal`, and
`failed` attempts first publish their honest artifacts and then exit nonzero so
that CI or an operator cannot mistake them for an accepted repair.

Never publish the private in-progress directory directly. Before retaining a
bundle, confirm it contains no endpoint, hostname, path, prompt, reasoning,
provider body, raw call ID, tool input/output, test process output, credential,
private source, or oracle material.

## Stop conditions

Stop and retain the honest checkpoint/result instead of improvising when:

- the configured model cannot be restored within the attempt's wall budget;
- exo returns a different model identity;
- the source refs or workspace no longer match their authenticated pins;
- the controller finds an indeterminate mutating worker operation;
- a resume changes any semantic attempt binding;
- the worker would need network access or an unpinned dependency;
- source, oracle, BAT, private, and output path boundaries overlap; or
- the evaluator cannot reconstruct the exact pinned head independently.

The first useful field result is one maintained six-phase canary—success or
honest failure—with its complete attempt directory. It is evidence about that
pinned model/deployment/run, not a general model-quality claim.
