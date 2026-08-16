# ADR 0001: Keep BAT execution separate from BDR methodology

- Status: accepted
- Date: 2026-08-07
- Issue: [#3](https://github.com/PavelZaytsev/bat/issues/3)

## Context

Boundary-Driven Refactoring (BDR) already defines the six-phase method, the revisioned tracker,
the evidence contract, legal state transitions, and the audit trail. Those rules must remain usable
from different model hosts and from independent implementations.

BugAnnihilatorThreethousand (BAT) needs an agentic loop that can drive that method with hosted or
open-weight models without making provider conversation state authoritative. Provider APIs also
have different tool, usage, streaming, and reasoning-continuation capabilities.

## Decision

**BAT is the agentic loop; BDR is the methodology.**

BDR owns:

- the boundary-driven refactoring phases and phase gates;
- `.bdr/` state, revisions, evidence, and the event journal;
- validation, legal mutations, next-action derivation, and audit output; and
- the compatibility contract exposed by the `bdr` command and `bdr.dev/*` schemas.

BAT owns:

- the provider-neutral model and tool protocol;
- capability negotiation and fail-closed run admission;
- backend/model identity, prompt, reasoning-effort, and verified BDR-commit pins;
- iteration, tool-call, wall-time, and token budgets;
- at-most-once tool-call replay within one live run; and
- ephemeral provider continuation state.

BAT's supported autonomous runtime is the dependency-free, phase-opaque direct runner exposed by
`bin/bat-direct`. It keeps the established BDR engine and readable repository artifacts behind
their versioned contracts while enforcing transport, identity, replay, compaction, and effect
boundaries outside the model's methodology decisions. The removed Scala/ZIO controller remains in
Git history as an experiment; it is not a supported runtime.

BAT resumes from a freshly validated BDR checkpoint. Provider-side conversation or response state
is only an optimization. Audit mode exposes only explicitly read-only tools and may complete only
after another validated checkpoint. A model's final text cannot complete a writer run unless a
fresh BDR checkpoint reports either `handoff` or `handoff_terminal`.

Before opening a live BDR session, BAT binds the configured engine entry point to a clean source
checkout at the exact full Git commit recorded in the run pins. A caller-supplied version label is
not sufficient engine identity.

Reasoning continuation is opaque and backend-affined. BAT may return it to the backend that created
it, but raw reasoning has no durable encoder and must not enter `.bdr/`, logs, GitHub projections,
pull-request comments, or benchmark reports.

Provider adapters normalize their wire formats into the BAT protocol. Provider SDKs and wire
formats do not enter `scripts/bdr.py` or the BDR state contract.

## Consequences

- OpenAI-compatible local and exo endpoints share one conformance suite.
- A restarted controller reconstructs work from BDR state instead of replaying hidden model state.
- Audit and writer authority are different types of tool access, enforced before execution.
- The direct runner can invoke the proven Python BDR engine without making provider conversation
  state authoritative.
- BDR mutations remain optimistic and stale-safe through BAT-supplied revision preconditions and
  actor identity; BDR remains the authority that owns and advances the revision.
- Real provider transports, isolated Java execution, routing, and cluster deployment remain separate
  changes.
