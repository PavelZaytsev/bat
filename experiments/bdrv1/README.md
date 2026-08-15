# BDRv1 model experiments

This directory treats the original Boundary-Driven Refactoring bundle as a frozen experimental artifact. The files in `methodology/` are byte-for-byte copies of the bundle used successfully in ordinary frontier-model coding sessions.

## Research question

What is the least-capable open-weight model that can use BDRv1 autonomously on bounded Java engineering work while preserving boundary discovery, evidence discipline, justified rewinds, and honest tracker state?

The first variable is model capability, not BAT architecture. BAT remains prior research and a source of fixtures, but these runs use an ordinary model session with BDRv1, `slices.py`, and `slices_progress.template.yaml` directly.

## Frozen model set

The project evaluates exactly three work-policy-approved architecture candidates:

1. `Qwen/Qwen3.8-27B` — current architecture-fit frontier candidate.
2. `google/gemma-4-31B-it` — smaller approved dense candidate.
3. `openai/gpt-oss-120b` — larger approved baseline.

Do not add another model family without an explicit change to the work-policy constraint. Model
search is no longer an experimental axis; Java problem diversity and BDR failure modes are.

All three candidates share at least one frozen anchor problem so capability comparisons are valid.
After that anchor, rotate them through different bounded Java problems to discover and augment BDRv1
more efficiently. Replay a newly discovered hard case across all three only when the cross-model
comparison answers a concrete capability question.

## Experimental discipline

- Freeze the methodology and task packet for cross-model comparisons.
- Start every probe in a fresh session.
- Preserve model, revision, precision, serving configuration, effort, token counts, elapsed time, raw response, and evaluator notes.
- Treat private reasoning as diagnostic data, not as a successful answer.
- Do not count a green `slices.py --check` as semantic proof.
- Prefer bounded Java repositories with hidden semantic checks over synthetic prose once a model passes the boundary probes.
- Explore first. Add orchestration only when a repeated failure identifies a narrow missing mechanism.
- Treat token, step, and wall-clock limits as runaway circuit breakers, not progress quotas. Give repository runs enough room to reread, rewind, and finish naturally.

## Progression

1. **Boundary probes:** compact adversarial decisions that isolate grouping, rewinding, falsification, temporal/ownership reasoning, and foreign-fact discipline.
2. **Read-only repository runs:** inspect a bounded Java repository and prepare a valid tracker through REPRESENT.
3. **Repair runs:** implement, test, falsify, and collapse one bounded Java problem end to end.
4. **Larger systems:** Apache Commons or similarly bounded real projects, followed by progressively larger PR-shaped work.

A model advances only when its failures are not fatal and its behavior is repeatable. Passing a prose probe is permission to test code, not evidence that the model can complete long engineering work.

## Findings so far

- Direct BDRv1 probes show that both GPT-OSS-120B and Qwen3.8-27B understand the core semantic
  method substantially better than their earlier BAT behavior suggested.
- Qwen3.8-27B autonomously produced a correct Java repair through all six phases, including red
  evidence, additive representation, collapse, a complete eight-cell matrix, and counterfactual
  failure, in under five minutes with no step limit.
- The same run failed clean qualification at durable-state closure: it used a finding status for a
  slice, the validator failed to reject the unknown value, and the model ignored the renderer's
  visible `?`. This points to a narrow artifact invariant, not a need for a phase controller.
- The next meaningful capability test must contain multiple plausible boundaries or a real-world
  Java change. The single-finding ingress toy cannot establish repartitioning, ROUTE rewinds,
  foreign-fact discipline, concurrency reasoning, or sibling falsification.

## Capability scorecard

Each run is scored 0–2 on boundary formation, partition by K, evidence discipline, meta-control, and state integrity. A promising run scores at least 8/10 with no fatal error.

Fatal errors include:

- marking a patched but reachable inference as fixed;
- treating the tracker validator as proof about code;
- routing through an assumed foreign fact;
- inventing a helper or guard instead of rewinding an incomplete representation;
- claiming unexecuted evidence; or
- grouping by file or region after the code disproves a shared K.
