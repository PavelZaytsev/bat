# Monday direct-BDRv1 runbook

This runbook prepares one bounded audit/repair of public CorfuDB PR #4121 using live inference and
the original byte-frozen BDRv1 bundle. It is an operating procedure, not another model of the
methodology.

Execute the CorfuDB-specific gates only on Monday, after a model-neutral Java repair has shown that
the selected GPT-OSS or Gemma deployment can finish through automatic context maintenance and
durable continuation. Weekend qualification must not be shaped around CorfuDB symbols, review
findings, tests, or expected repairs.

## Invariant

The model owns boundary discovery, evidence gathering, representation, routing, collapse,
falsification, and tracker judgment. The runner may provide only phase-opaque transport, tool
isolation, complete-turn checkpoints, automatic context maintenance, integrity checks, metrics, and
independent evaluation.

Do not add a Scala/FP phase controller, phase scheduling, phase prompts, human `continue?` gates, or
manual recovery summaries. Add a mechanism only after a repeated, classified live-run failure under
a frozen policy earns it.

## Monday target

Use the exact CorfuDB PR-head SHA recorded in `MONDAY-TARGET.md`. Start from a history-clean synthetic
root of that tree and ask the candidate to audit and repair the snapshot-sync liveness/correctness
change. Keep the PR page, base-to-head diff, public review findings, evaluator tests, and human fixes
outside the candidate workspace until it is irreversibly finished.

## Model and run classes

| Model or deployment | Run class and role |
| --- | --- |
| `Qwen/Qwen3.8-27B` | External reference/teacher only; never the Monday deployment. |
| `openai/gpt-oss-120b` | Primary Monday candidate. |
| `google/gemma-4-31B-it` | Monday alternative if it passes the same gates. |
| Laptop Gemma 27B or another Gemma variant | `local-dev-canary` only; never a frozen qualification result. |
| Laptop Gemma 4 31B | Still a distinct deployment unless revision, precision or quantization, context, serving stack, and topology match the frozen candidate. |

Never fail over between models inside a run. A model or deployment switch requires a new run ID,
fresh candidate workspace, fresh state directory, and separately preregistered configuration.

## Frozen protocol-v2 envelope

Freeze these values before either Monday candidate runs. If an exact deployment cannot prove the
context limit or wire parameters, abort and re-freeze before running either candidate; do not tune
after seeing one model's result.

| Setting | Value |
| --- | ---: |
| Context window | `131072` tokens |
| Proactive context-maintenance trigger | `100000` estimated tokens |
| Work output allowance | `8192` tokens |
| Context-maintenance output allowance | `4096` tokens |
| Conservative estimate | `2.0` bytes/token plus `256` tokens |
| Logical-call circuit breaker | `1000` |
| Model retries | `2` retries, `3` attempts total |
| Retry backoff | `5.0` seconds |
| Tool calls per model turn | `1` |
| Safe transcript tail | `8` messages |
| Tool timeout | `900` seconds |
| Transport socket timeout | `3600` seconds; not a total-wall limit |
| Tool observation cap | `12000` characters, split evenly head/tail |
| Streaming | required |
| Temperature | `0.2` |
| Production forced compactions | none |
| Live canary forced compactions | after completed turn `1` |

GPT-OSS may use preregistered `reasoning_effort: "medium"`. Gemma must use only wire parameters
accepted by its live canary. Record that model-specific wire difference before either scored run.

## Cost policy

Through the Monday CorfuDB run, optimize for useful live evidence and readiness rather than
minimum GPU spend. Record dollars, node-hours, model usage, and wall time for every paid run. Ask for
a balance replenishment when funding would otherwise stop a concrete rehearsal or candidate run;
do not silently change provider or deployment.

Build images, warm a sanitized dependency cache, prepare the task, and run deterministic mechanics
checks before paid inference because they are faster to debug locally. Paid follow-up runs are
allowed when a classified result leaves a specific readiness question. Do not multiply runs without
such a question. An externally imposed funding stop is `cost-censored`, not a model failure.

After Monday, use the internal exo cluster or the M1 work Mac as the normal inference lane. Treat
rented capacity as an explicit exception while preserving the same deployment and comparability
labels.

## Gate 1: freeze the implementation

Set `BDR_BAT_CHECKOUT` to the BAT checkout containing this directory, then verify the exact branch
and commit:

```bash
export BDR_BAT_CHECKOUT='<ABSOLUTE_BAT_CHECKOUT>'

git -C "$BDR_BAT_CHECKOUT" branch --show-current
git -C "$BDR_BAT_CHECKOUT" status --short
git -C "$BDR_BAT_CHECKOUT" rev-parse HEAD

python3 -c "import yaml; assert yaml.__version__ == '6.0.3'"

python3 -m py_compile \
  "$BDR_BAT_CHECKOUT/experiments/bdrv1/run_repository_probe.py" \
  "$BDR_BAT_CHECKOUT/experiments/bdrv1/evaluate_run.py"

python3 -m unittest discover \
  -s "$BDR_BAT_CHECKOUT/experiments/bdrv1/tests" \
  -p 'test_*.py'

python3 "$BDR_BAT_CHECKOUT/experiments/bdrv1/run_repository_probe.py" rehearse
python3 "$BDR_BAT_CHECKOUT/experiments/bdrv1/run_repository_probe.py" \
  example-config | python3 -m json.tool > /dev/null
```

The gate passes only when the branch is `codex/bdrv1-model-evals`, the checkout is clean, all tests
pass, and the rehearsal reports one compaction, two work calls, two tool calls, no duplicated action,
and continuation with a packet.

## Gate 2: prepare a history-clean PR-head candidate

The PR page, base-to-head diff, upstream history, review discussion, evaluator tests, and evaluator
remain outside every candidate mount. Use them only as post-candidate evaluation evidence.

```bash
umask 077

export BDR_RUN_ID='<UNIQUE_RUN_ID>'
export BDR_RUN_ROOT='<ABSOLUTE_PRIVATE_RUN_ROOT>'
export BDR_SOURCE='<ABSOLUTE_CORFUDB_SOURCE_REPOSITORY>'
export BDR_TARGET_SHA='2a5c02804b44e47b547882f97a9b449f7b02d26b'

test -n "$BDR_RUN_ID"
test -d "$BDR_SOURCE"
git -C "$BDR_SOURCE" rev-parse --verify "${BDR_TARGET_SHA}^{commit}"
case "$BDR_RUN_ROOT" in /*) ;; *) exit 1 ;; esac
test "$BDR_RUN_ROOT" != '/'
test ! -e "$BDR_RUN_ROOT"
test ! -L "$BDR_RUN_ROOT"

install -d -m 0700 "$BDR_RUN_ROOT"
install -d -m 0700 "$BDR_RUN_ROOT/candidate/repo"
install -d -m 0700 "$BDR_RUN_ROOT/candidate/bdr"
install -d -m 0700 "$BDR_RUN_ROOT/candidate/scratch"
install -d -m 0700 "$BDR_RUN_ROOT/candidate/tmp"
install -d -m 0700 "$BDR_RUN_ROOT/candidate/dependency-cache"
install -d -m 0700 "$BDR_RUN_ROOT/state"
install -d -m 0700 "$BDR_RUN_ROOT/preflight"
install -d -m 0700 "$BDR_RUN_ROOT/artifacts"

git -C "$BDR_SOURCE" archive --format=tar \
  --output="$BDR_RUN_ROOT/preflight/target.tar" "$BDR_TARGET_SHA"
tar -xf "$BDR_RUN_ROOT/preflight/target.tar" -C "$BDR_RUN_ROOT/candidate/repo"

git -C "$BDR_RUN_ROOT/candidate/repo" init
git -C "$BDR_RUN_ROOT/candidate/repo" add -A
git -C "$BDR_RUN_ROOT/candidate/repo" \
  -c user.name='BDRv1 experiment' \
  -c user.email='bdrv1.invalid' \
  commit -m 'Synthetic internal PR base'
git -C "$BDR_RUN_ROOT/candidate/repo" remote -v
```

The last command must print nothing. Abort if `git archive` does not faithfully materialize required
submodules or LFS objects.

Prepare a blinded task packet containing the intended behavior and acceptance requirements, but no
PR diff, solution commit, evaluator test, PR URL, review finding, remote, or upstream history. Copy the nine files listed
by `example-config` from `experiments/bdrv1/methodology/` into `candidate/bdr/`, preserving names and
bytes. Initialize `candidate/bdr/slices_progress.yaml` from the template.

## Gate 3: create the offline tool container

The host runner talks to the model. Model-issued shell commands execute only in a pre-created,
offline, disposable container. Use an immutable image reference and a non-root UID/GID supported by
that image. Make the repository, tracker, and scratch paths writable by that UID/GID before launch.

```bash
export BDR_CONTAINER='<UNIQUE_CONTAINER_NAME>'
export BDR_IMAGE='<PINNED_IMAGE_DIGEST>'
export BDR_CONTAINER_UID_GID='<NONROOT_UID:GID>'
export BDR_DEP_CACHE="$BDR_RUN_ROOT/candidate/dependency-cache"
```

Before container launch, populate the run-specific dependency cache from a sanitized offline source;
never mount a personal or credential-bearing cache. Ensure the numeric non-root UID/GID can write
the repository, tracker file, scratch, dependency-cache, and temporary-directory bind sources, and
verify the tracker already exists. The cache is writable only so offline build tools can maintain
local metadata without touching the original cache.

```bash
test -d "$BDR_RUN_ROOT/candidate/repo"
test -f "$BDR_RUN_ROOT/candidate/bdr/slices_progress.yaml"
test -d "$BDR_RUN_ROOT/candidate/scratch"
test -d "$BDR_DEP_CACHE"
test -d "$BDR_RUN_ROOT/candidate/tmp"

docker run --detach \
  --name "$BDR_CONTAINER" \
  --network none \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --pids-limit 1024 \
  --user "$BDR_CONTAINER_UID_GID" \
  --mount "type=bind,src=$BDR_RUN_ROOT/candidate/repo,dst=/workspace/repo" \
  --mount "type=bind,src=$BDR_RUN_ROOT/candidate/bdr,dst=/workspace/bdr,readonly" \
  --mount "type=bind,src=$BDR_RUN_ROOT/candidate/bdr/slices_progress.yaml,dst=/workspace/bdr/slices_progress.yaml" \
  --mount "type=bind,src=$BDR_RUN_ROOT/candidate/scratch,dst=/workspace/scratch" \
  --mount "type=bind,src=$BDR_DEP_CACHE,dst=/workspace/dependency-cache" \
  --mount "type=bind,src=$BDR_RUN_ROOT/candidate/tmp,dst=/tmp" \
  "$BDR_IMAGE" sleep infinity

docker inspect "$BDR_CONTAINER" \
  > "$BDR_RUN_ROOT/preflight/container-inspect.json"
```

Abort unless inspection proves all of the following:

- network mode is `none` and there is no runtime network attachment;
- root filesystem is read-only, the user is non-root, and the container is running;
- it is nonprivileged, drops all capabilities, has no added capability or device, uses no host PID or
  IPC namespace, and enforces `no-new-privileges`;
- there is no Docker socket, host-root, credential, human-PR, or hidden-evaluator exposure; and
- environment and mounts exactly match the preregistered allowlists.

The BDR directory mount is read-only. The exact tracker regular-file mount overlays only
`/workspace/bdr/slices_progress.yaml` as writable. Do not replace this with a second mutable BDR
copy.

## Gate 4: create and bind the run configuration

Generate the current full schema rather than hand-authoring an old one:

```bash
python3 "$BDR_BAT_CHECKOUT/experiments/bdrv1/run_repository_probe.py" \
  example-config > "$BDR_RUN_ROOT/config.json"
```

Replace every placeholder and make these changes:

- `state_dir` is `$BDR_RUN_ROOT/state`, outside the candidate Git worktree.
- Host workspace and input paths point only into the private run root or frozen BAT checkout.
- Every workspace-manifest and methodology host `path` has an explicit `model_path` that maps it
  through the matching preregistered bind source and container destination. Source and test paths
  map below `/workspace/repo`, the tracker maps to the exact writable overlay at
  `/workspace/bdr/slices_progress.yaml`, evidence maps to `/workspace/scratch`, and every frozen
  methodology byte maps to its matching `/workspace/bdr/...` path. Configuration loading fails
  closed if any host fingerprint can describe bytes other than those exposed to the model.
- The tracker host path is the exact overlaid `slices_progress.yaml` file.
- `model.name` and the sole accepted response model identify exactly one candidate.
- Served identity records model revision, precision or quantization, and verified context as
  operator-declared policy fields. `server_fingerprint` is only the exact streamed response
  `system_fingerprint`; use `response-unavailable` when the server consistently omits it. Archive a
  separate serving/deployment manifest that supports all operator declarations.
- Keep the fixed `model.user_agent` in the policy fingerprint. For an HTTPS endpoint, set
  `model.tls_ca_file` to an absolute CA bundle and `model.tls_ca_sha256` to its exact byte hash; the
  runner verifies both before sampling. Never disable certificate verification. Omit those fields
  only for an explicitly trusted plain-HTTP loopback/private path.
- Use `model.api_key_env`, never a literal credential. The named variable exists only in the host
  runner environment and is excluded from tool and container environments.
- `tool.backend` is `docker_exec`, its user is the exact non-root UID/GID, and its allowed mounts
  include `source_kind: "directory"` or `source_kind: "regular_file"` as applicable.
- `tool.cwd` is `/workspace/repo`, not the container's `/workspace` parent.
- The dependency cache is mounted at `/workspace/dependency-cache`; `/tmp` is an exact dedicated
  host-directory bind. Do not use a Docker `tmpfs` until the runner explicitly fingerprints it.
- `workspace.respect_git_ignore` remains `false`. Exclude only preregistered generated dependency or
  build outputs; never source, tests, tracker, scratch evidence, or arbitrary untracked files.
- Enumerate all real source and test roots. The runner additionally fingerprints the full Git index,
  staged and unstaged differences, modes, and non-excluded untracked bytes.
- Use `force_compaction_after_turns: [1]` only in a canary. Production uses `[]`.

Before launch, compute and record the exact container ID, image fingerprint, path-string hashes for
the mount allowlist, and canonical container security-configuration digest. `source_sha256` is the
SHA-256 of Docker inspect's exact `Source` path string, not a content digest. The configuration
digest is the SHA-256 fingerprint of the runner's normalized `security_identity_from_inspect`
record, not a hash of raw Docker JSON. The runner and Docker daemon must be on the same host because
the runner independently inspects each bind source; do not substitute a remote-Docker shell wrapper.
The runner re-inspects and fails closed before every tool action. Never weaken an allowlist merely
to accept what happens to be present in a container.

Docker Desktop may report `--network none` as one opaque runtime record named `none`. The runner
accepts that representation only when it is the sole network record and contains no assigned IP,
gateway, prefix, route, or DNS metadata; opaque network/endpoint IDs remain part of the security
identity. Any assigned address or second network is a preflight failure.

After the mount path-string hashes and all other allowlists are filled, derive the normalized
identity without mutating the container:

```bash
python3 "$BDR_BAT_CHECKOUT/experiments/bdrv1/run_repository_probe.py" \
  container-identity --config "$BDR_RUN_ROOT/config.json" \
  > "$BDR_RUN_ROOT/preflight/container-identity.json"
```

Copy its `container_id`, `image_fingerprint`, and `expected_container_config_sha256` into the config,
then retain the full `security_identity` as preflight evidence. This helper validates the declared
network, user, mount, environment, and containment policy; it does not create, restart, or relax the
container.

## Gate 5: prove transport and continuation cheaply

Run these in order:

1. The deterministic rehearsal from Gate 1.
2. A laptop Gemma `local-dev-canary` against a non-secret synthetic repository, with forced
   compaction after turn 1.
3. An exact rental/exo rehearsal using the selected deployment and another non-secret repository,
   still with forced compaction after turn 1. Repeat only to answer a classified readiness question.
4. The bounded CorfuDB PR-head audit/repair, without forced compaction.

The laptop canary validates mechanics and basic BDR behavior; it does not substitute for the exact
Monday deployment.

For remote exo inference, prefer a private direct tunnel over a proxy with a fixed response timeout.
The exact SSH target and exo API port are environment-specific:

```bash
export BDR_EXO_LOCAL_PORT='18000'
export BDR_EXO_API_HOST='<HOST_REACHABLE_FROM_SSH_TARGET>'
export BDR_EXO_REMOTE_PORT='<EXO_API_PORT>'

ssh -N -T \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=15 \
  -o ServerAliveCountMax=4 \
  -L "127.0.0.1:${BDR_EXO_LOCAL_PORT}:${BDR_EXO_API_HOST}:${BDR_EXO_REMOTE_PORT}" \
  '<EXO_SSH_TARGET>'
```

In another terminal, archive `/v1/models` and the exo deployment state before and after the run.
Verify an actually placed, exclusive instance with its assigned shards, not merely a registered
model card. Use `127.0.0.1` for `BDR_EXO_API_HOST` only when the model server really runs on the SSH
target's loopback. If a private LAN endpoint is directly reachable, a tunnel is optional and must be
authorized by the environment owner. The runner endpoint is the runner host's tunnel loopback Chat
Completions URL. Send a bearer credential only over HTTPS or a trusted encrypted loopback tunnel;
credential-free HTTP is acceptable only on an explicitly trusted private path. Never expose an
unauthenticated model port publicly.

A live forced-compaction canary passes only if:

- the exact response model identity and token usage are accepted;
- a bash action completes inside the offline container and its observation is checkpointed;
- context maintenance occurs at a complete tool-observation boundary;
- policy, methodology, task, workspace, tracker, source, tests, and evidence retain their bindings;
- the numbered continuation manifest and event hash chain validate;
- no tool action is repeated; and
- the model continues autonomously and emits the exact completion command.

## Selection gate

Choose the highest comparable protocol-v2 weekend result with no fatal error. If comparable evidence
is absent or tied, try GPT-OSS-120B first. Use Gemma 4 31B if GPT cannot prove the frozen context or
fails the exact-deployment rehearsal. Qwen is reference-only, and laptop Gemma 27B is mechanics-only.

## Launch and safe continuation

Set the host-only credential variable named in the config, then launch:

```bash
export BDR_EXO_API_KEY='<RUNTIME_ONLY_OR_EMPTY_VALUE>'

python3 "$BDR_BAT_CHECKOUT/experiments/bdrv1/run_repository_probe.py" \
  run --config "$BDR_RUN_ROOT/config.json" \
  > "$BDR_RUN_ROOT/artifacts/runner-result.json" \
  2> "$BDR_RUN_ROOT/artifacts/runner-error.json"
```

Use the runner's hashed timing events for portable wall-time accounting; do not depend on GNU-only
`time` flags on the M1/macOS lane.

During the run: no phase coaching, human continuation prompt, config change, output-limit change,
endpoint/model swap, or manual tool replay. After an ordinary host interruption, rerun the exact
same command and state directory. The runner resumes only a provably complete boundary. If sampling
began without a complete response, or a tool intent lacks a durable observation, accept the
fail-closed result rather than guessing or replaying.

## Acceptance and classification

A qualification pass requires:

- runner status `completed` under one policy and deployment identity;
- valid policy, checkpoint, event, and continuation chains with no manual intervention;
- reproducible baseline and candidate suites, allowing only frozen pre-existing failures;
- the frozen tracker checks plus the evaluator-side slice-status check;
- independent evaluator-only acceptance and adversarial checks;
- at least 8/10 on the BDR rubric with no fatal semantic error; and
- tracker closure that matches surviving uncertainty.

Fatal semantic errors include invented evidence, validator-as-proof, helper/guard instead of a
required representation rewind, reachable inference marked fixed, an assumed foreign fact routed
into the solution, or an illegally closed slice.

Abort and classify without blaming the model when there is model/deployment identity drift, a
smaller context, container or policy drift, hidden-data exposure, indeterminate streamed response or
tool execution, tunnel loss, retry exhaustion, server replacement, or the cost stop-loss. Preserve
the exact terminal classification.

## Artifacts and privacy

Before hidden evaluation, snapshot and hash the finished candidate. Run hidden checks only in a
separate evaluator copy/container and never resume the candidate after hidden results are visible.

Keep privately: config and preregistration; task packet; state and every continuation; complete
events/transcript/reasoning/tool observations; container inspection and security digest; model and
exo state before/after; exact revision/precision/context/runtime/topology; base and final Git state;
tracker and scratch evidence; baseline/targeted/full-suite/counterfactual/hidden logs; usage,
compaction, retry, timing, and cost data; and a SHA-256 inventory of all artifacts.

Validate the frozen tracker and evaluator-owned status invariant after the candidate stops. If the
run compacted, validate every numbered `state/continuations/continuation-*.json` individually against
the same externally recorded policy fingerprint; do not validate only the latest continuation or
pass a wrapper artifact where the evaluator expects the policy object. Also verify the continuation
numbers and `previous_continuation_manifest_sha256` links form one unbroken chain.

Publish to BAT only redacted aggregate status, counters, score, classification, and lessons. Raw
state may contain private source, task text, tool output, and model reasoning.
