# Claude token-efficiency experiment

## Problem

Fast successful Claude runs can still spend substantial input tokens. Without a private execution
trail, the strongest local signal is BAT's instruction and tool-output surface. The previous skills
required every ordinary run to load the complete 663-line tracker runbook before mutation. During
discovery, the next-action hint also sent the model to unfiltered `bdr examples`, which printed
every operation and gate sample. When a gate remained unclear, reading the engine source was often
the shortest available explanation.

That design front-loaded schemas for recovery, migration, GitHub projection, every phase, and both
run modes even when the current state could legally use only one of them.

## Change

`bdr guide` is a validated progressive-disclosure interface. It returns:

- the current tracker revision and next legal action;
- the current phase's causal claim, pass condition, and rewind route;
- only the operation or gate skeleton relevant to that action; and
- state-derived IDs, predictions, obligations, and fix-mode FALSIFY reviews where available.

The skills keep a six-line gate compass in their always-loaded instructions. They load the complete
tracker section only for recovery, migration, GitHub projection, an unsupported path, or a validator
error that focused guidance cannot explain. `bdr examples NAME...` supports focused lookup; the
unfiltered form remains compatible but is not part of the normal model workflow.

The full tracker remains the normative human and implementation specification. The guide does not
weaken validation or turn placeholders into evidence.

## Local size proxy

These are UTF-8 bytes and whitespace-delimited words, not provider token counts. They measure the
repository-controlled context surface on this branch against `main` before the change.

| normal instruction path | before | after | byte reduction |
|---|---:|---:|---:|
| bounded `/fix`: skill + autonomy + protocol + full tracker | 68,040 bytes / 8,926 words | 27,637 bytes / 3,954 words | 59.4% |
| broad `/refactor`: skill + autonomy + protocol + full tracker | 70,758 bytes / 9,357 words | 30,252 bytes / 4,370 words | 57.3% |
| discovery schema lookup | 8,869 bytes / 696 words | 2,129 bytes / 181 words | 76.0% |
| one named FALSIFY example | 8,869 bytes / 696 words for all examples | 305 bytes / 28 words | 96.6% |

State-aware active-phase guides measured 1,160-1,961 bytes for the refactor fixture after adding
their causal claim and focused FALSIFY evidence templates. Discovery plus all six gate guides total
11,236 bytes. The guides
are intentionally requested over time; they do not front-load every schema before discovery. Exact
Claude usage will also depend on repository size, tool output, host prompt caching, source searches,
and the number of model turns.

## Live A/B

Run at least three comparable cases per variant in fresh Claude sessions. Pin the BAT commit, Claude
model, issue/repository revision, permissions, and acceptance oracle. Do not coach one variant more
than the other.

Record, when the host exposes them:

- terminal state and whether closure evidence passed;
- wall time and model/tool turn counts;
- input, cache-read/cache-write, output, and total tokens;
- number of reads of `tracker.md`, `scripts/bdr.py`, and named examples; and
- validation retries or incorrect gate submissions.

Compare medians and preserve failures. The change is successful only if token use or source-search
turns fall without worse closure, more validation retries, or loss of evidence quality.
