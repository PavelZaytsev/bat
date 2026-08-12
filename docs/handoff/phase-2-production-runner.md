# Handoff — the Phase 2 production runner

A self-contained brief for a coding agent working without cluster access. Advances #25 Phase 2.

**You do not need a model endpoint, a GPU, or the exo cluster to complete this.** Everything here is
verified against a scripted backend and a sealed evaluator. A live model is a later config change,
not a code change, because the backend boundary already abstracts it.

## Outcome

Build the smallest supervised runner that connects a real reasoning backend to the existing isolated
Java worker, so that one BDR run can go from a pinned base/head commit to a verified local commit or
patch without a human in the loop.

```text
Backend (any dialect)
  -> AgenticLoop + shared telemetry sink
  -> real BdrTools + isolated Java worker tools
  -> ready-for-review handoff
  -> independent OCI evaluator
```

## Why this is the critical path

Nothing composes these today. `bat.quickstart.ToyQuickstart` wires a *scripted* backend to *toy*
tools. `bat.probe.LiveGptOssProbe` wires a *real* backend to *stub* tools. The diagonal — real
backend, real tools — does not exist, and every downstream question about model quality is blocked
behind it.

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
| a scripted backend to test with | `loop/src/main/scala/bat/quickstart/ScriptedToyBackend.scala` |
| end-to-end six-phase example | `loop/src/main/scala/bat/quickstart/ToyScenario.scala` |
| telemetry sink | `loop/src/main/scala/bat/telemetry/Telemetry.scala` |

Read `ToyScenario.scala` closely. It is the shape of what you are building, with the two ends
replaced.

## What to build

A runner that:

1. **Composes rather than copies.** Use `AgenticLoop`, `BdrTools`, `WorkerTools`, and a `Backend`
   injected by the caller. If you find yourself reimplementing loop or phase logic, stop.
2. **Uses one shared telemetry collector** for provider attempts, logical turns, BDR attribution,
   tool execution, retries, tokens, timings, and terminal outcome.
3. **Gives the model a versioned, reviewed prompt/tool contract** — never reference patches, hidden
   tests, expected findings, or hard-coded operations.
4. **Supplies trusted initial workspace state and the final canonical commit SHA** rather than asking
   the model to invent either.
5. **Materialises verification evidence from worker receipts inside the trusted controller.** Literal
   model-authored command output must be rejected as evidence.
6. **Invokes handoff only after a valid `ready_for_review` outcome.**
7. **Evaluates the delivered commit in a fresh OCI sandbox after the actor is gone**, mounting sealed
   oracle material only for that phase.

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

## Verification, all offline

Build against `examples/java-six-phase/` with `ScriptedToyBackend`, then confirm the same runner
accepts a real `Backend` without source changes.

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

- [ ] A runner composes a caller-injected `Backend`, `AgenticLoop`, real `BdrTools`, the isolated
      Java worker, one telemetry sink, handoff, and an isolated evaluator.
- [ ] It drives the six-phase canary to a terminal BDR state using `ScriptedToyBackend`, with the
      sealed evaluator passing on the delivered commit, and **no provider call**.
- [ ] Swapping in a real `Backend` requires configuration only — no change to runner source.
- [ ] Strict local toy tools are unreachable from any non-scripted backend, enforced by a test.
- [ ] Verification evidence derives from worker receipts, and model-authored command output is
      rejected, enforced by a test.
- [ ] Telemetry attributes turns and tokens to BDR phases; unknown measurements are `null` with a
      reason.
- [ ] Full local gate green; ordinary CI makes no model call.

## Explicit non-goals

- No live model run, no cluster work, no 120b.
- No Bazel support. The worker currently exposes only the reviewed offline Maven/Gradle path;
  project-specific build systems, images, targets, and dependency snapshots belong to a separate
  integration task.
- No changes to the BDR protocol, state engine, or evidence schema.
- No GitHub automation, no pushing, no merging.

## Where to leave the result

A branch and a pull request per CONTRIBUTING, linked to #25, describing what the runner composes,
what it deliberately does not do, and the exact verification performed. If a discovery materially
expands the work, open a follow-up issue rather than growing the pull request.
