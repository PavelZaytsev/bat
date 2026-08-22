<div align="center">
  <img
    src="docs/assets/bat-mascot.png"
    alt="BAT mascot"
    width="800"
  />

  <h1>BAT</h1>
  <p><strong>BugAnnihilatorThreethousand</strong></p>
  <p><strong>"It's time to kill bugs and chew bubble gum... and I'm all outta gum" - BAT</strong></p>
</div>

---

BAT is an experimental autonomous agentic refactoring loop built around **Boundary-Driven Refactoring
(BDR)**: a method for finding facts that code is inferring, representing those facts explicitly at their authority boundary, routing them to the decisions that need them, and deleting the obsolete inference.

## Run it

### Requirements

The current `main` path requires:

- Python 3.10+
- Git
- the target repository's normal build/test toolchain
- for live autonomous runs, an OpenAI-compatible Chat Completions endpoint
- an isolated/disposable execution environment for target-controlled code

See [`INSTALLATION.md`](INSTALLATION.md) for the complete installation and runtime prerequisites.

### Claude Code and Zed's Claude Agent

Install or refresh BAT for both with one command:

```bash
bin/bat-claude-install
```

Start a new Claude session or Zed Claude Agent thread, then invoke `/bat:fix` with a project
location, base branch, BDR branch, and same-repository GitHub issue URL or number:

```text
/bat:fix . main bdr/issue-123 https://github.com/ORG/REPO/issues/123
/bat:fix /Users/developer/project cache-experiments bdr/cache-fix 123
/bat:fix developer@192.0.2.10:/home/developer/project main bdr/issue-123 123
```

The first two forms run in a local checkout; the third keeps repository work on the SSH host. The
installer handles Claude's user-scope plugin registration; no Zed configuration is required.

### BDR engine

The deterministic BDR state/evidence engine is exposed through `bin/bdr`.

```bash
bin/bdr --version
bin/bdr rules
bin/bdr selftest
```

The engine maintains repository-local `.bdr/` state and enforces the methodology's tracker,
evidence, transition, delivery, and completion invariants.

For one developer-selected GitHub issue, initialize the bounded primitive with:

```bash
bin/bdr init --mode fix --issue 123
bin/bdr guide
bin/bdr status
bin/bdr status --json
```

The issue and current revision are pinned read-only. Root and causally required slices use the
existing six phases; unrelated findings remain visible without being repaired or blocking closure.
Activity is projected mechanically from durable state rather than model-authored telemetry.
`bdr guide` is the compact model-facing projection: it validates state and returns only the next
legal action, that gate's meaning, and the relevant payload skeleton. The full tracker runbook
remains available for exceptional recovery and protocol work without becoming a fixed context cost
for every ordinary run.

> The developer chooses the objective; the model chooses the repair path; BDR determines what
> evidence constitutes closure.

### Direct autonomous runtime

The supported model-driven runtime on current `main` is:

```bash
bin/bat-direct
```

First run the deterministic rehearsal. It crosses a forced context-compaction boundary without
contacting a model:

```bash
bin/bat-direct rehearse
```

Generate a live-run configuration template:

```bash
bin/bat-direct example-config > /tmp/bat-direct-config.json
```

The generated file is a template, not a ready-to-run configuration. Before inference, pin the task,
methodology, repository revision, served model identity, precision/quantization, context window,
server identity, container image, mounts, and run policy.

Validate the configured execution environment:

```bash
bin/bat-direct container-identity --config /tmp/bat-direct-config.json
```

Then start the run:

```bash
bin/bat-direct run --config /tmp/bat-direct-config.json
```

For a deliberate pause/cold-resume qualification:

```bash
bin/bat-direct run \
  --config /tmp/bat-direct-config.json \
  --pause-after-compactions 1
```

After auditing the closed checkpoint, restart the same logical run without the pause flag.

The direct runtime expects an explicitly configured OpenAI-compatible Chat Completions endpoint.
Recent live work has exercised this path with Qwen3.8-27B, GPT-OSS-120B, and Gemma-family
deployments.

See [`docs/direct-runtime.md`](docs/direct-runtime.md) for the current runtime contract and
[`experiments/bdrv1/`](experiments/bdrv1/) for the recorded qualification evidence.

## What BDR does differently

A broad "find and fix all bugs" prompt does not give an autonomous model a stable causal unit of
work, an evidence contract, or a defensible stopping condition. BDR instead organizes engineering
work around missing information-flow boundaries.

A boundary finding is written as:

> At `<site>`, consumer **C** needs fact **K** from authority **A** to make decision **D**, but
> instead infers K from **I**.

Findings are grouped by the missing edge:

```text
(authority A, fact K, consumer decision C/D)
```

not merely by file, subsystem, symptom, severity, or a broad noun such as "ownership."

Before execution, the model asks whether one representation at that edge would make every grouped
finding impossible. If not, the group is split.

The method then repairs one bounded slice at a time. Each slice carries explicit findings,
dependencies, evidence, foreign facts, decisions, and phase state in `.bdr/`. Tracker state is not
treated as semantic proof: transitions require code-derived or execution-derived evidence, and the
final repository is rescanned before completion.

BDR distinguishes a locally correct patch from an honestly completed repair.

The theory behind BAT is that if each discovered boundary is taken honestly through that complete loop, then repeated discovery/repair/rescan passes should drive the repository toward a fixed point rather than accumulating a collection of locally plausible patches.

Thus, BAT is not primarily trying to make an LLM better at fixing individual bugs. It is trying to make autonomous refactoring convergent by requiring each repair to satisfy BDR’s causal and evidentiary invariants before the model is allowed to treat that work as complete.

Recent experiments demonstrated this directly: GPT-OSS-120B produced the correct Java behavior, but still declared completion while the BDR tracker contained unresolved template facts. BAT rejected the run because passing tests proved the local patch, not that the model had actually closed the tracked boundary.

## The six-phase kill chain

Each boundary slice moves through the same six phases:

| phase | purpose | gate |
|---|---|---|
| **EXPOSE** | Make one reachable defect observable with focused evidence. | The regression test fails at the intended assertion against the recorded slice base. |
| **REPRESENT** | Introduce the missing fact and its invariants without prematurely routing behavior through it. | The representation is explicit and the relevant baseline behavior is preserved. |
| **ROUTE** | Carry the fact from its authority to every decision that requires it. | Producers and consumers are accounted for without inventing unrelated policy. |
| **COLLAPSE** | Remove obsolete inference, duplicate state, and mechanisms superseded by the representation. | The expected structural deaths are present. |
| **SATURATE** | Exercise the adjacent decision/state space and operational obligations. | The focused slice selection is green across the relevant boundary states. |
| **FALSIFY** | Remove only the repair and challenge the final code plus sibling findings. | The focused counterfactual turns red and sibling outcomes are evidence-backed. |

The normal verification cadence is intentionally lean: establish a deterministic baseline, obtain
focused red evidence in EXPOSE, use structural evidence through REPRESENT/ROUTE/COLLAPSE, run one
focused green selection in SATURATE, run the counterfactual red proof in FALSIFY, and run the broad
suite at the final fixed point. Additional execution is driven by a concrete risk or changed
workspace, not by phase-number ritual.

After all runnable slices are complete, BDR performs a bounded fixed-point rescan. New
merge-blocking findings enter another bounded pass. Exhausting the configured pass/attempt bound is
non-convergence, not success.

## Read the method

For the conceptual model and current implementation, start here:

| file | purpose |
|---|---|
| [`docs/bat-for-java-developers.md`](docs/bat-for-java-developers.md) | **Start here.** Conceptual guide for Java developers: honest function contracts, effects, composition, the FP/ZIO design lens, and how those ideas lead to current BAT/BDR |
| [`skills/fix/SKILL.md`](skills/fix/SKILL.md) | bounded `/fix` workflow for one pinned GitHub issue and root-evidenced closure |
| [`skills/refactor/SKILL.md`](skills/refactor/SKILL.md) | executable BDR workflow, autonomy contract, phase loop, stop conditions, and terminal behavior |
| [`skills/refactor/references/protocol.md`](skills/refactor/references/protocol.md) | boundary discovery, grouping, evidence rules, six phases, rewinds, and convergence |
| [`skills/refactor/references/tracker.md`](skills/refactor/references/tracker.md) | `.bdr/` state model, operations, evidence records, phase gates, and readiness contracts |
| [`skills/refactor/references/autonomy.md`](skills/refactor/references/autonomy.md) | authority boundaries, safe execution, interruption policy, and decision quarantine |
| [`skills/refactor/references/java-ownership.md`](skills/refactor/references/java-ownership.md) | Java ownership, native-memory lifetime, concurrency, and related boundary guidance |
| [`docs/direct-runtime.md`](docs/direct-runtime.md) | supported phase-opaque autonomous runtime, local/exo serving, compaction, cold resume, isolation, retries, and terminal acceptance |
| [`INSTALLATION.md`](INSTALLATION.md) | prerequisites, package layout, host adapters, permissions, and rollout verification |

For the architecture and current experimental evidence:

| file | purpose |
|---|---|
| [`docs/convergent-agentic-loops.md`](docs/convergent-agentic-loops.md) | why the current host is phase-opaque and what the runtime is allowed to own |
| [`docs/adr/0001-bat-bdr-boundary.md`](docs/adr/0001-bat-bdr-boundary.md) | BAT/BDR responsibility boundary |
| [`docs/adr/0002-isolated-java-worker.md`](docs/adr/0002-isolated-java-worker.md) | isolated Java execution design |
| [`docs/adr/0003-reasoning-backends.md`](docs/adr/0003-reasoning-backends.md) | reasoning-backend decisions |
| [`docs/adr/0004-run-telemetry.md`](docs/adr/0004-run-telemetry.md) | run telemetry contract |
| [`docs/adr/0005-wire-dialect-seam.md`](docs/adr/0005-wire-dialect-seam.md) | provider/serving wire-dialect boundary |
| [`docs/exo-efficiency.md`](docs/exo-efficiency.md) | distributed-inference and exo efficiency notes |
| [`docs/claude-token-efficiency.md`](docs/claude-token-efficiency.md) | progressive-disclosure design and live A/B plan for Claude-hosted BAT runs |
| [`experiments/bdrv1/README.md`](experiments/bdrv1/README.md) | current BDRv1 open-weight qualification program and findings |
| [`experiments/bdrv1/results/`](experiments/bdrv1/results/) | preserved Qwen, GPT-OSS, and Gemma run records and artifacts |
| [`experiments/bdrv1/MONDAY-TARGET.md`](experiments/bdrv1/MONDAY-TARGET.md) | frozen production-scale CorfuDB qualification target |
| [`experiments/bdrv1/MONDAY-EXO-RUNBOOK.md`](experiments/bdrv1/MONDAY-EXO-RUNBOOK.md) | exo/local execution plan for that target |
| [`benchmarks/pilot/README.md`](benchmarks/pilot/README.md) | benchmark protocol and pilot evidence |

`docs/quickstart.md` describes the earlier Scala canary path and is historical. It is not the
execution path for current `main`; use [`docs/direct-runtime.md`](docs/direct-runtime.md) instead.
