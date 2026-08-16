# BDRv1 model experiments

This directory treats the original Boundary-Driven Refactoring bundle as a frozen experimental artifact. The files in `methodology/` are byte-for-byte copies of the bundle used successfully in ordinary frontier-model coding sessions.

## Research question

What is the least-capable open-weight model that can use BDRv1 autonomously on bounded Java engineering work while preserving boundary discovery, evidence discipline, justified rewinds, and honest tracker state?

The first variable is model capability, not BAT architecture. BAT remains prior research and a source of fixtures, but these runs use an ordinary model session with BDRv1, `slices.py`, and `slices_progress.template.yaml` directly.

## Working decision

Direct BDRv1 with a live inference backend is the primary engineering path. The model does the
repository work; surrounding code may provide only phase-opaque transport, isolation, durable
checkpoint/compaction, integrity checks, metrics, and hidden evaluation. Add a mechanism only after
a repeated, classified live-run failure earns it.

Do not build a Scala/FP phase controller, phase scheduler, scripted phase prompts, or an
orchestration model of work in place of battle-tested repository runs. BAT's earlier controller is
prior evidence, not the runtime being prepared for the Monday CorfuDB PR.

### Development and deployment lanes

Use a work-laptop Gemma 27B or 31B deployment as the `local-dev-canary` lane for non-secret runner,
tool, transport, forced-compaction, and basic BDR checks. A laptop 27B result never enters the frozen
qualification scorecard. A laptop 31B result is also a distinct deployment unless model revision,
precision or quantization, context limit, serving stack, and host topology all match the frozen
Gemma 4 31B candidate record.

Through the Monday CorfuDB PR run, optimize for reliable live evidence rather than minimum GPU
spend. Record dollars, node-hours, and wall time, and ask for a balance replenishment if funding
would otherwise stop a useful run. Do not multiply runs without a concrete readiness question.
After Monday, prefer the internal exo cluster or the M1 work Mac for routine inference and use rented
capacity only by explicit exception. Any externally imposed budget stop is `cost-censored`, not a
model failure.

## Frozen model set

The project evaluates exactly three models, with intentionally different operational roles:

1. `Qwen/Qwen3.8-27B` — capable external reference/teacher for validating direct BDRv1 and
   discovering model-agnostic augmentations. It is not a Broadcom deployment candidate.
2. `google/gemma-4-31B-it` — smaller Broadcom/exo deployment candidate for the Monday CorfuDB
   run.
3. `openai/gpt-oss-120b` — larger Broadcom/exo deployment candidate and baseline for the Monday
   CorfuDB run.

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

### Continuity and comparability

A multi-context execution counts as one logical run only when context maintenance is preregistered
and automatic. Compaction must occur at a complete assistant/tool-observation boundary, retain the
same task, methodology, and run-policy fingerprint, verify repository/tracker/source/test/evidence
hashes, carry unresolved judgments and a safe transcript tail, and neither drop nor replay a tool
action. Record its trigger threshold and reason, packet and checkpoint hashes, count, token usage,
retries, and continuity checks.

Force at least one compaction in a deterministic rehearsal before using a protocol on a candidate
model. A manual restart prompt, human-written handoff, changed output allowance, changed observation
cap, or silent transcript truncation is a recovery diagnostic, not a comparable continuation.

## Progression

1. **Boundary probes:** compact adversarial decisions that isolate grouping, rewinding, falsification, temporal/ownership reasoning, and foreign-fact discipline.
2. **Read-only repository runs:** inspect a bounded Java repository and prepare a valid tracker through REPRESENT.
3. **Repair runs:** implement, test, falsify, and collapse one bounded Java problem end to end.
4. **Larger systems:** Apache Commons or similarly bounded real projects, followed by progressively larger PR-shaped work.

A model advances only when its failures are not fatal and its behavior is repeatable. Passing a prose probe is permission to test code, not evidence that the model can complete long engineering work.

## Monday target

The supervised Monday run targets public
[CorfuDB PR #4121](https://github.com/CorfuDB/CorfuDB/pull/4121) at a frozen PR-head snapshot. It is a
real Java snapshot-sync liveness and correctness change used in the Broadcom product context, while
remaining fully observable for live supervision and independent evaluation. The candidate receives
a history-clean PR-head tree and a blinded audit/repair task; PR discussion, known review findings,
base diff, and evaluator tests stay outside its workspace until it is irreversibly finished. See
`MONDAY-TARGET.md` and `MONDAY-EXO-RUNBOOK.md`.

CorfuDB is the target profile, not a weekend optimization fixture. Qualify GPT-OSS/Gemma continuity
and BDR capability first on model-neutral Java work. Do not specialize transport, compaction,
prompts, methodology, or qualification tests around the CorfuDB review oracle.

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
- The first Apache Commons CSV Stage-3 run proved that append-only mini-SWE-agent 2.4.6 cannot carry
  this work through a 131,072-token context: the original run and first recovery each failed at an
  exact one-token-over allocation boundary. A second manual recovery produced a real public-green
  commit, but the three segments are diagnostic and not one comparable autonomous run.
- That recovered artifact passed four of five hidden checks, but this is not a qualification
  near-pass. The model measured the shared reader's short-read behavior, then introduced a
  specialized blocking helper and three caller guards while deliberately leaving the inherited
  `peek`/bulk-read abstraction incomplete. The reader-level hidden check failed, and the tracker
  nevertheless marked the slice done, every finding fixed, and the foreign fact eliminated. This
  directly realizes the fatal helper-instead-of-rewind rule and scores 4/10.
- The Stage-3 result justifies a phase-opaque runner that provides atomic complete-turn checkpoints,
  proactive model-authored context maintenance, workspace/policy fingerprints, retry metrics, and
  fail-closed tool replay protection. It does not justify a phase controller or changing the frozen
  BDRv1 bytes before the remaining cross-model anchors.
- The first live protocol-v2 Qwen canary crossed both a forced and a naturally triggered context
  maintenance boundary without losing or replaying a tool action. Qwen then established red
  evidence, implemented and saturated the repair, performed a production-only revert check, closed
  the tracker, passed all tests, and committed a clean patch. The canary's deliberately tight
  40-call breaker fired immediately after that commit but before the exact completion marker, so
  the work product is complete while the protocol result remains honestly runner-limit-censored.
  This validates same-model continuity and shows only that full-bundle canaries need more than 40
  work calls; Monday's preregistered 1,000-call production breaker is unchanged.
- Two fresh follow-up attempts then failed closed at the second maintenance boundary: one returned
  an object with the wrong field set, and the next returned no final-content JSON despite a strict
  schema. Both stopped at complete turn boundaries without a candidate patch. They are archived as
  separate diagnostics rather than folded into the successful trajectory.
- The repaired fresh Qwen completion canary crossed a forced boundary after turn 1 and a natural
  boundary after turn 20, then finished the PairKey repair in 29 work turns. It emitted the exact
  completion command as its sole final tool action, with 29 closed tool calls, two independently
  validated continuation manifests, zero retries, no lost or replayed action, a clean candidate
  commit, an independently green test, and a production-only revert that failed as expected. This
  completes the protocol-v2 reference continuity gate; it does not qualify Qwen or select the
  Monday GPT-OSS/Gemma deployment.

## Capability scorecard

Each run is scored 0–2 on boundary formation, partition by K, evidence discipline, meta-control, and state integrity. A promising run scores at least 8/10 with no fatal error.

Fatal errors include:

- marking a patched but reachable inference as fixed;
- treating the tracker validator as proof about code;
- routing through an assumed foreign fact;
- inventing a helper or guard instead of rewinding an incomplete representation;
- claiming unexecuted evidence; or
- grouping by file or region after the code disproves a shared K.
