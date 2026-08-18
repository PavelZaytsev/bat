<div align="center">
  <img
    src="docs/assets/bat-mascot.png"
    alt="BAT mascot: a muscular cartoon bat with a cigar, tattoo sleeve, patchwork arm, and smoking machine gun amid defeated software bugs"
    width="800"
  />

  <h1>BAT</h1>
  <p><strong>BugAnnihilatorThreethousand</strong></p>
  <p><strong>"It's time to kill bugs and chew bubble gum... and I'm all outta gum" - BAT</strong></p>
</div>

BAT is an autonomous refactoring runtime built around **Boundary-Driven Refactoring (BDR)**, an
evidence-backed methodology for finding missing information-flow boundaries in code, making the
required facts explicit, routing them from their authority to the decisions that need them, and
removing the obsolete inference.

The repository contains the portable BDR method, its deterministic state/evidence engine, host
adapters for Claude Code, ChatGPT, and Codex, and the supported phase-opaque direct runtime for
OpenAI-compatible local or exo inference.

## Run it

### Prerequisites

For the current `main` branch:

- Git
- Python 3.10 or newer
- the target repository's normal build/test toolchain
- a clean checkout or isolated worktree at the PR head
- for live autonomous runs, an approved OpenAI-compatible model endpoint and an isolated execution
  container

See [`INSTALLATION.md`](INSTALLATION.md) for host-specific installation and permissions.

### Smoke-test the BDR engine

From the BAT repository root:

```bash
bin/bdr rules
bin/bdr selftest
```

The BDR engine is dependency-free and maintains the repository-local `.bdr/` state used by the
methodology.

### Smoke-test the autonomous runtime

`bin/bat-direct` is the supported autonomous model-driven runtime on current `main`.

Run the deterministic, network-free rehearsal:

```bash
bin/bat-direct rehearse
```

Generate a configuration template:

```bash
bin/bat-direct example-config > /tmp/bat-direct-config.json
```

The generated file is only a template. Before a live run, replace every placeholder and pin the
task, methodology, target repository revision, model identity, context limit, server identity,
container image, mounts, and execution budgets.

Validate the configured container identity:

```bash
bin/bat-direct container-identity --config /tmp/bat-direct-config.json
```

Then start the run:

```bash
bin/bat-direct run --config /tmp/bat-direct-config.json
```

The live runtime expects an explicitly configured OpenAI-compatible Chat Completions endpoint.
Prefer loopback or an SSH tunnel and keep credentials in the configured environment variable rather
than in the JSON file.

For the full configuration contract, preregistration boundary, exo/local serving path, compaction,
cold resume, retry policy, and terminal acceptance behavior, read
[`docs/direct-runtime.md`](docs/direct-runtime.md).

### Invoke BAT through a coding host

The host adapters expose the same portable BDR workflow but do **not** automatically invoke
`bin/bat-direct`.

Check out the PR head in the target repository, start a new coding session, and invoke:

| host | invocation |
|---|---|
| Claude Code | `/bat:refactor this PR` |
| ChatGPT | `@refactor this PR` |
| Codex | `$refactor this PR` |

Claude Code may also expose `/refactor` when no command collision exists. The stable namespaced
Claude entrypoint is `/bat:refactor`.

See [`INSTALLATION.md`](INSTALLATION.md) for private marketplace installation, the Claude bare-name
compatibility shim, ChatGPT/Codex plugin packaging, workspace permissions, and upgrade notes.

## Why generic "find and fix all bugs" prompts perform poorly

A broad bug-finding prompt does not define a stable unit of work, a causal model, an evidence
contract, or a stopping condition. A capable model can produce a plausible local patch while
leaving sibling failures, duplicated state, or the underlying information-flow defect intact.

BDR instead makes the model answer concrete questions throughout the run:

- What fact does this decision require?
- Which component has authority to provide that fact?
- Where is the code currently inferring or reconstructing it?
- Which other decisions depend on the same missing information-flow edge?
- What evidence proves the repair changed the intended boundary?
- What evidence would falsify the repair?
- Has the final code reached a fixed point, or is there still live work?

The model supplies repository search, reasoning, implementation, and test-writing ability. BDR
supplies the diagnostic grammar, durable state, evidence gates, counterfactual proof, and bounded
convergence rules.

## What BDR does differently

BDR treats each finding as an information-flow boundary problem.

Every boundary finding is written in this form:

> At `<site>`, consumer **C** needs fact **K** from authority **A** to make decision **D**, but
> instead infers K from **I**.

Findings are grouped by the missing edge:

```text
(authority A, fact K, consumer decision C/D)
```

They are not grouped only because they appear in the same file, subsystem, symptom category, or
share a broad label such as "ownership."

Before execution, BDR asks whether one representation at that edge would make all grouped findings
impossible. If not, the group is split.

A finding may initially look like a value, temporal, concurrency, or direct defect. For
time-shaped problems, BDR first checks whether the missing structure is actually ownership,
borrowing, reservation, completion, generation, capability, projection, or a lease. Genuine
real-time, lifecycle, fairness, ordering, and happens-before requirements remain temporal or
concurrent; the method does not force them into an ownership model.

BDR then repairs one bounded slice at a time. Each slice carries explicit findings, evidence,
foreign facts, dependencies, decisions, and phase state in the `.bdr/` tracker. The tracker is not
proof by itself: gate transitions require code-derived or execution-derived evidence.

After all live slices are complete, the final code is rescanned against the pinned target and the
broad verification suite is run at the fixed point. New merge-blocking findings enter another
bounded pass. Exhausting the configured pass/attempt bound is non-convergence, not success.

## The six-phase kill chain

Each boundary slice moves through the same six phases:

| phase | purpose | gate |
|---|---|---|
| **EXPOSE** | Make one reachable defect observable with a focused regression test. | The test fails at the intended assertion against the recorded slice base. |
| **REPRESENT** | Introduce the missing fact and its invariants without routing behavior through it yet. | The representation is explicit and the relevant baseline behavior is preserved. |
| **ROUTE** | Enumerate producers and consumers and carry the fact from its authority to every decision that needs it. | Transfer is mechanical; routing does not invent unrelated policy. |
| **COLLAPSE** | Remove the old inference, duplicate state, and mechanisms made obsolete by the new representation. | Actual structural deaths match the predictions recorded before routing. |
| **SATURATE** | Exercise the adjacent decision algebra and any operational obligations. | The focused slice selection is green across the relevant boundary states. |
| **FALSIFY** | Remove only the repair and challenge the final code plus every sibling finding. | The focused counterfactual turns red and each sibling has a typed, evidence-backed outcome. |

The normal cadence is intentionally lean: one baseline signal, one focused red proof in EXPOSE,
structural evidence through REPRESENT/ROUTE/COLLAPSE, one focused green selection in SATURATE, one
counterfactual red proof in FALSIFY, and broad verification once at the final fixed point. Extra
execution is driven by a concrete risk or changed workspace, not by phase-number ritual.

## Read the method

Start with these documents:

| file | purpose |
|---|---|
| [`skills/refactor/SKILL.md`](skills/refactor/SKILL.md) | executable BAT/BDR workflow and terminal behavior |
| [`skills/refactor/references/protocol.md`](skills/refactor/references/protocol.md) | boundary discovery, evidence rules, six phases, rewinds, and convergence |
| [`skills/refactor/references/tracker.md`](skills/refactor/references/tracker.md) | `.bdr/` state model, operations, evidence records, phase gates, and readiness contracts |
| [`skills/refactor/references/autonomy.md`](skills/refactor/references/autonomy.md) | authority boundaries, safe execution, interruption policy, and decision quarantine |
| [`skills/refactor/references/java-ownership.md`](skills/refactor/references/java-ownership.md) | Java ownership, native-memory lifetime, concurrency, and related boundary guidance |
| [`docs/direct-runtime.md`](docs/direct-runtime.md) | current phase-opaque autonomous runtime, exo/local serving, isolation, compaction, cold resume, and acceptance |
| [`INSTALLATION.md`](INSTALLATION.md) | installation, host adapters, permissions, and live-runtime prerequisites |

For the architecture and experimental evidence behind the current runtime:

| file | purpose |
|---|---|
| [`docs/convergent-agentic-loops.md`](docs/convergent-agentic-loops.md) | design rules for long-running autonomous loops and the phase-opaque host boundary |
| [`docs/adr/0001-bat-bdr-boundary.md`](docs/adr/0001-bat-bdr-boundary.md) | runtime/methodology responsibility boundary |
| [`docs/adr/0002-isolated-java-worker.md`](docs/adr/0002-isolated-java-worker.md) | isolated Java execution design |
| [`docs/adr/0003-reasoning-backends.md`](docs/adr/0003-reasoning-backends.md) | reasoning backend decisions |
| [`docs/adr/0004-run-telemetry.md`](docs/adr/0004-run-telemetry.md) | run telemetry contract |
| [`docs/adr/0005-wire-dialect-seam.md`](docs/adr/0005-wire-dialect-seam.md) | provider/serving wire-dialect boundary |
| [`docs/exo-efficiency.md`](docs/exo-efficiency.md) | measured exo throughput, prefix-cache behavior, and distributed-inference notes |
| [`experiments/bdrv1/README.md`](experiments/bdrv1/README.md) | BDRv1 model/runtime qualification program |
| [`experiments/bdrv1/results/`](experiments/bdrv1/results/) | recorded Qwen, GPT-OSS, and Gemma qualification results |
| [`benchmarks/pilot/README.md`](benchmarks/pilot/README.md) | benchmark protocol and pilot evidence |

`docs/quickstart.md` is retained as historical documentation for the removed Scala canary. Its
commands are not the current execution path; use [`docs/direct-runtime.md`](docs/direct-runtime.md)
for current `main`.
