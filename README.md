# Boundary-Driven Refactoring (BDR)

**A bug is code acting on something it does not know.** BDR finds the fact a decision needs,
represents that fact explicitly at its authority boundary, and then tries to make the old inference
unreachable.

BDR is packaged as one portable refactoring skill with thin host adapters. The method, state model,
and audit contract live in [`skills/refactor/`](skills/refactor/); each host only changes how the
same skill is installed and invoked.

## Run it

Open the repository at the pull request head, start your coding agent, and invoke the skill:

| host | invocation |
|---|---|
| Claude Code | `/refactor this PR` on current collision-free installs or with the team shim; `/bdr:refactor this PR` always |
| ChatGPT | `@refactor this PR` |
| Codex | `$refactor this PR` |

`/bdr:refactor` is the canonical collision-free entrypoint. Claude Code 2.1.216 and newer also
exposes a plugin skill's bare name when it does not collide, so `/refactor` works directly there.
[`adapters/claude-bare/refactor/`](adapters/claude-bare/refactor/) is a tiny standalone
personal/managed compatibility skill that delegates `/refactor` on older clients.

See [`INSTALLATION.md`](INSTALLATION.md) for private team installation, prerequisites, permissions,
and host-specific setup.

## What a run does

The agent:

1. pins the PR base and head and creates or resumes `.bdr/progress.yaml`;
2. audits the PR diff plus only the connected code needed to reason about it;
3. records findings as missing facts at information-flow boundaries;
4. groups findings into falsifiable boundary slices;
5. executes **EXPOSE → REPRESENT → ROUTE → COLLAPSE → SATURATE → FALSIFY**;
6. verifies each slice, rescans to a bounded fixed point, and leaves an append-only audit trail;
7. projects stable BDR IDs into GitHub issues when authorized, or leaves an idempotent outbox when
   synchronization is unavailable.

The repository tracker is current state. Git, `.bdr/events.jsonl`, and digest-bound evidence
records are history. GitHub is a projection, not a second editable source of truth.

The agent drives a dependency-free state engine (Python 3.10+). For a local smoke test:

```bash
bin/bdr rules
bin/bdr selftest
```

Inside a target Git repository, the normal lifecycle is `preflight` → `init`/resume →
`status --next` plus evidence-backed operations → `completion-check`. Do not hand-edit
`.bdr/progress.yaml`; see [`skills/refactor/references/tracker.md`](skills/refactor/references/tracker.md)
for the exact operation and phase-gate schemas.

## What “unattended” means

The skill is designed to run without discretionary questions. It makes reversible implementation
choices, quarantines work that needs human authority, continues independent safe slices, and ends
in an explicit terminal state.

It does **not** bypass the host's safety model. Workspace trust, permission prompts, organization
policy, missing credentials, rate limits, unavailable build tools, stale PR input, unsafe tests, and
genuinely ambiguous product semantics can still stop a run. By default BDR does not push, merge,
deploy, rewrite PR history, weaken tests, or accept a higher-risk design on the user's behalf.

## The core diagnostic

Force every finding into one sentence:

> At `<site>`, the code needs to know **K**, and instead infers K from **I**.

Group findings by the missing information-flow edge:

> `(fact authority, missing fact K, consumer decision)`

Do not group merely by file, subsystem, symptom, severity, or a broad noun such as “ownership.”

When a finding initially looks temporal, first test whether the missing structure is ownership,
borrowing, reservation, completion, generation, or a lease. Preserve real deadlines, TTLs,
happens-before obligations, fairness, and other genuinely temporal or concurrent properties as
such; BDR does not assume every time-shaped defect dissolves into ownership.

## Read the method

| file | purpose |
|---|---|
| [`skills/refactor/SKILL.md`](skills/refactor/SKILL.md) | the executable agent workflow |
| [`skills/refactor/references/protocol.md`](skills/refactor/references/protocol.md) | boundary discovery and the six-phase loop |
| [`skills/refactor/references/tracker.md`](skills/refactor/references/tracker.md) | state, evidence, transition, and readiness contracts |
| [`skills/refactor/references/autonomy.md`](skills/refactor/references/autonomy.md) | authority, safety, and interruption policy |
| [`skills/refactor/references/java-ownership.md`](skills/refactor/references/java-ownership.md) | Java ownership, native memory, lifetime, and concurrency guidance |

## Maturity

BDR is an alpha method. The workflow is useful and the state engine is designed to fail closed, but
the method has not yet been validated independently across multiple domains or organizations.
Start with a supervised pilot, inspect the audit trail, and retain ordinary code review and CI as
the merge authority.
