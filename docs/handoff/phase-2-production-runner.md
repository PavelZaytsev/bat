# Handoff — production-runner integration

A self-contained brief for the production-runner boundary. PR #31 now supplies the live launcher,
closed canary action, restart-aware attempts, and pinned OCI evaluator described here, but does not
claim the six-phase live acceptance until a retained field run exists. Operators should use
[`docs/live-java-acceptance.md`](../live-java-acceptance.md).

**You do not need a model endpoint, a GPU, or the exo cluster to verify the offline composition.**
The runner, worker discovery surface, receipt-bound BDR bridge, typed GPT-OSS backend factories,
closed dependency-free canary action, and pinned evaluator are covered by ordinary tests. The
remaining proof is a real maintained canary attempt against an operator-pinned deployment.

## Outcome

Build the smallest supervised runner that connects a real reasoning backend to the existing isolated
Java worker, so that one BDR run can go from a pinned base/head commit to a verified local commit or
patch without a human in the loop.

```text
Backend (any dialect)
  -> AgenticLoop + shared telemetry sink
  -> real BdrTools + isolated Java worker tools
  -> ready-for-review handoff
  -> pinned networkless OCI evaluator
```

## Why this is the critical path

`bat.quickstart.ToyQuickstart` wires a *scripted* backend to *toy* tools.
`bat.probe.LiveGptOssProbe` wires a *real* backend to *stub* tools. `bat.runner.ProductionRunner`
supplies the diagonal composition for a real backend and real Java-worker tools. The issue-25 live
profile adds one reviewed worker image/configuration and evaluator; project-specific build
cartridges remain separate trusted integrations.

## Read first

- `docs/adr/0001-bat-bdr-boundary.md` — what belongs to BAT and what belongs to BDR
- `docs/adr/0002-isolated-java-worker.md` — the worker's trust boundary; do not weaken it
- `docs/adr/0005-wire-dialect-seam.md` — the backend seam you will inject through
- `skills/refactor/references/tracker.md` — BDR state, evidence, and readiness contracts
- `examples/java-six-phase/README.md` — the fixture you will verify against
- issue #25, Phase 2 and Phase 3 Case A

## What already exists — use it, do not reimplement it

| piece | location |
|---|---|
| provider-neutral loop | `loop/src/main/scala/bat/controller/AgenticLoop.scala` |
| tool registry with audit/write authority | `loop/src/main/scala/bat/controller/ToolRegistry.scala` |
| BDR session and validated state | `loop/src/main/scala/bat/bdr/BdrSession.scala` |
| real BDR tools | `loop/src/main/scala/bat/bdr/BdrTools.scala` |
| isolated Java worker | `loop/src/main/scala/bat/worker/JavaWorker.scala` |
| worker tool surface | `loop/src/main/scala/bat/worker/WorkerTools.scala` |
| worker BDR bridge, receipts, ledger | `loop/src/main/scala/bat/worker/{WorkerBdr,Receipt,WorkerLedger}.scala` |
| handoff | `loop/src/main/scala/bat/worker/Handoff.scala` |
| OCI sandbox | `loop/src/main/scala/bat/worker/oci/OciSandbox.scala` |
| backend seam | `loop/src/main/scala/bat/backend/wire/WireDialect.scala` |
| production composition | `loop/src/main/scala/bat/runner/ProductionRunner.scala` |
| restart-aware live launcher | `loop/src/main/scala/bat/runner/LiveJavaProductionApp.scala` |
| durable attempt checkpoint/publication | `loop/src/main/scala/bat/runner/LiveJavaAttemptStore.scala` |
| pinned networkless evaluator | `loop/src/main/scala/bat/runner/OciJavaEvaluator.scala` |
| versioned actor contract | `loop/src/main/resources/bat/runner/java-bdr-v1.md` |
| end-to-end six-phase example | `loop/src/main/scala/bat/quickstart/ToyScenario.scala` |
| telemetry sink | `loop/src/main/scala/bat/telemetry/Telemetry.scala` |

Read `ProductionRunner.scala` first. `ToyScenario.scala` remains useful methodology evidence, but its
`toy_*` tools and direct host `javac` execution are intentionally impossible to select in a
production run.

## Production integration contract

The embedding application must preserve these runner guarantees:

1. **Composes rather than copies.** Use `AgenticLoop`, `BdrTools`, `WorkerTools`, and a `Backend`
   injected by the caller. If you find yourself reimplementing loop or phase logic, stop.
2. **Uses one shared telemetry collector** for provider attempts, logical turns, BDR attribution,
   tool execution, retries, tokens, timings, and terminal outcome. An embedding must allocate a
   fresh collector per attempt and call `ProductionRunner.runObserved` when it needs to retain
   telemetry after a typed run failure.
3. **Gives the model a versioned, reviewed prompt/tool contract** — never reference patches, hidden
   tests, expected findings, or hard-coded operations.
4. **Supplies trusted initial workspace state and the final canonical commit SHA** rather than asking
   the model to invent either.
5. **Materialises verification evidence from worker receipts inside the trusted controller.** Literal
   model-authored command output must be rejected as evidence.
6. **Invokes handoff only after a valid `ready_for_review` outcome.**
7. **Invokes the injected trusted evaluator only after the actor is gone.** Its concrete production
   implementation must use a fresh OCI sandbox and mount sealed oracle material only for that phase.

## Hard rules — violating any of these makes the result worthless

- **Never expose `ToyTools` to a non-scripted backend.** It is a trusted local executor. A live model
  must never execute model-authored Java, build scripts, or tests on the controller host.
- **Never weaken the worker's networkless, non-root, resource-bounded profile.** An offline
  dependency failure is an environment block, not a reason to relax isolation.
- **Never publish raw reasoning, prompts, provider bodies, tool payloads, credentials, or raw call
  IDs** into any artifact. Unavailable measurements are `null` with a reason, never zero.
- **Fail closed.** A turn that cannot be verified is a failed turn, not a best effort. Prefer a
  distinct stable error code over a generic one; see the `harmony_chat_*` codes for the pattern.
- **Do not edit anything under `benchmarks/`.** Committed runs are immutable records.

A receipt authenticates the reviewed command identity, worker image, exit status, and output
digests. It does not yet parse Java test output or independently prove that a nonzero EXPOSE or
counterfactual result reached the actor-named assertion. Treat that semantic attribution as an
actor claim until a build cartridge supplies a signed structured test outcome; the sealed evaluator
and human review remain independent authority.

## Verification, all offline

Do not pass `ScriptedToyBackend` to the production runner: it calls `toy_*` tools by design. Add a
production-worker scripted actor that speaks `worker_*`, and give the fixture a reviewed structured
Java 17 action inside the OCI worker (or an equivalent checked-in offline build). Then confirm a
real backend is a configuration swap only.

The fixture's contract: materialise only `subject/base/` plus `subject/head.patch`; keep `oracle/` and
`reference/` outside every actor-visible mount; require real EXPOSE → REPRESENT → ROUTE → COLLAPSE →
SATURATE → FALSIFY progress; preserve the five-invocation cadence (baseline, EXPOSE red, SATURATE
green, counterfactual FALSIFY red, final broad green); score behaviour and BDR validity, not patch
identity.

Local gate before any pull request:

```bash
python3 -m py_compile scripts/bdr.py skills/refactor/scripts/bdr.py \
  scripts/check_plugin_manifests.py scripts/check_benchmark_artifacts.py
python3 scripts/bdr.py examples | python3 -m json.tool >/dev/null
python3 scripts/bdr.py selftest
python3 scripts/check_plugin_manifests.py
python3 scripts/check_benchmark_artifacts.py
bin/bdr --version
scala-cli fmt --check loop
scala-cli test --server=false loop
```

## Traps that already cost time

- **`scala-cli` may not be installed.** Static binary from the VirtusLab releases page into
  `~/.local/bin`. First run takes ~15 minutes to resolve dependencies; later full suites ~3 minutes.
- **`umask 002` breaks the probe artifact writer.** The production rule is correct — it refuses a
  group-writable evidence parent. Set permissions explicitly in tests; `chmod 0700` real output
  parents.
- **`scala-cli test ... | tail` masks the exit code.** Read the `N tests failed` line, not `$?`.
- **Derive wire fixtures from captured bytes, not from your own assumptions.** Fifteen green
  hand-written tests missed a field an endpoint actually sends; one real stream found it in a minute.
  This applies equally to worker and build output.

## Acceptance criteria

- [x] A runner composes a typed GPT-OSS backend factory, `AgenticLoop`, real `BdrTools`, the isolated
      Java worker, one telemetry sink, receipt-bound handoff evidence, and a trusted evaluator seam.
- [x] A concrete sealed OCI evaluator implementation binds its report to the handed-off commit and
      patch digest.
- [ ] A production-worker scripted actor drives the six-phase canary to a terminal BDR state, with
      the sealed evaluator passing on the delivered commit and **no provider call**. It must not use
      `ScriptedToyBackend` or any `toy_*` tool.
- [x] Swapping between GPT-OSS Responses and Harmony Chat requires configuration only — no change
      to runner source.
- [x] Strict local toy tools are unreachable from the sealed production worker factory, enforced by
      a test.
- [x] Verification evidence derives from worker receipts, and model-authored command output is
      rejected, enforced by a test.
- [x] Telemetry attributes turns and tokens to BDR phases; unknown measurements are `null` with a
      reason.
- [ ] Full local gate green; ordinary CI makes no model call.

## Explicit non-goals

- No claim that the live canary or 120B Java quality is proven before a retained attempt bundle.
- No Bazel support. The worker currently exposes only the reviewed offline Maven/Gradle path;
  project-specific build systems, images, targets, and dependency snapshots belong to a separate
  integration task.
- No changes to the BDR protocol, state engine, or evidence schema.
- No GitHub automation, no pushing, no merging.

## Where to leave the result

A branch and a pull request per CONTRIBUTING, linked to #25, describing what the runner composes,
what it deliberately does not do, and the exact verification performed. If a discovery materially
expands the work, open a follow-up issue rather than growing the pull request.
