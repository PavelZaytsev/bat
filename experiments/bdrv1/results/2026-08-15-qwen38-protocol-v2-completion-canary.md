# Qwen3.8-27B protocol-v2 completion canary — 2026-08-15

This fresh, non-secret PairKey run is the first exact-completion exercise of the phase-opaque
protocol-v2 repository runner. Its purpose was mechanics and continuity, not model qualification:
prove that one model can complete ordinary BDRv1 repository work across both forced and natural
context maintenance while tool execution remains offline, policy-bound, durable, and non-replayed.

## Frozen envelope

- Model: `Qwen/Qwen3.8-27B`, external reference role, work reasoning `medium`
- Context: 131,072 tokens; 100,000-token proactive maintenance trigger
- Output allowances: 8,192 work, 4,096 maintenance
- Maintenance wire policy: strict JSON Schema plus policy-bound non-thinking output
- Tool observations: 12,000 characters, symmetric head/tail preservation
- Tool environment: non-root, no-network Docker execution with read-only root, all capabilities
  dropped, exact mount/environment allowlists, and no provider credential
- Policy SHA-256: `af643a723380817476b319caa1bb69c093a23df7556e368bb0d99e368907be3d`
- Canary-only circuit breaker: 64 logical work calls; forced maintenance after work turn 1
- Frozen methodology: the same nine original BDRv1 files, delivered by ordered path and byte hash

The task required `PairKey.join("", right)` to return `right` without a leading separator while
preserving non-empty behavior. Qwen had to read the frozen bundle, maintain the tracker, establish
red evidence, implement and saturate the repair, falsify it against production-only rollback, run
the validator, create a self-contained commit, and emit the exact completion command.

## Continuity result

The run crossed two autonomous maintenance boundaries:

| # | Trigger | Boundary | Result |
| ---: | --- | --- | --- |
| 1 | forced canary trigger | after completed work turn 1; 16,991 estimated tokens | schema-valid packet; policy, workspace, checkpoint, history, and closed-tool continuity verified |
| 2 | estimated context threshold | after completed work turn 20; 101,195 estimated tokens | schema-valid packet; context restored and work resumed through repair, falsification, tracker closure, commit, and exact completion |

Both continuation manifests passed the independent evaluator against the same externally recorded
policy fingerprint. No tool call was lost or repeated, and there were no retries, partial sampled
responses, abandoned attempts, dangling tool intents, model swaps, policy changes, or human-written
handoffs.

The maintenance-only non-thinking setting cleared the empty-final-content failure from the prior
diagnostic. It is bound into the immutable run policy and does not affect ordinary work turns. This
validates the generic mechanism and this Qwen deployment's wire setting; GPT-OSS and Gemma still
need their own preregistered live canaries rather than inheriting a Qwen-specific parameter.

## Work product

Qwen completed the engineering work and exact runner protocol in 29 work turns:

- candidate commit `a1fab3554de93c43b86f3bcd1afd1c9b606ed7e4`;
- two files changed, nine insertions, clean Git worktree;
- red EXPOSE evidence: expected `beta`, received `:beta` against the pre-fix code;
- a 2x2 value-level test matrix covering empty/non-empty left and right inputs;
- production-only counterfactual: retaining the new tests while reverting the implementation fails
  at the empty-left assertion;
- final candidate test green in the original offline container;
- one `done` slice, all six phases complete, one fixed finding, confirmed collapse, and a tracker
  green against the frozen validator's 19 rules; and
- exact completion command emitted as the sole final tool action.

Independent verification repeated the candidate green test, the red production-only revert, the
tracker consistency check and self-test, the evaluator-owned slice-status check, and both
continuation-manifest checks after the model stopped.

Before publication, review found that the then-current configuration loader did not itself prove
that fingerprinted host paths were the sources mounted at model-visible paths, and that the
standalone evaluator accepted less than the runner's full continuation contract. The runner now
fails closed on exact host-to-container bindings, and the evaluator now checks the complete packet,
preserved-summary digest, workspace semantic digest, self-hash, and origin-complete manifest chain.
A post-run audit against the preserved configuration and still-running container passed every
binding, and the two preserved manifests passed the hardened evaluator as one ordered chain. This
is post-run verification of the historical canary, while all future runs enforce both invariants
before work begins.

## Honest classification

The result is a **completed protocol-v2 live continuity canary**. It proves that the runner and
Qwen reference deployment can preserve and finish real repository work across forced and natural
maintenance. It is not a frozen Qwen model qualification result: the endpoint exposed a served
alias but not an immutable model revision or exact precision, and Qwen is not a Monday Broadcom
deployment candidate.

This result earns progression to the same non-secret continuity gate for GPT-OSS-120B and Gemma 4
31B. It does not select either candidate or substitute for the later model-neutral Java capability
gate.

## Metrics

- 29 completed work responses, 2 completed maintenance responses
- 29 completed tool calls, 32 durable checkpoints, 189 hash-chained events
- 0 retries, 0 abandoned attempts, 2 validated continuations
- 741,394 prompt tokens and 14,787 completion tokens, including maintenance
- 356.518 seconds from first model attempt to the terminal response
- final post-maintenance work-request estimate: 50,327 tokens
- approximately 0.099 H200 node-hours, or about $0.45 at the displayed $4.59/hour rate; exact
  incremental billing is unknown because the pod was already running before the canary

## Archived safe artifacts

- [`qwen38-protocol-v2-completion-canary-metrics.json`](artifacts/qwen38-protocol-v2-completion-canary-metrics.json)
- [`qwen38-protocol-v2-completion-canary-repair.patch`](artifacts/qwen38-protocol-v2-completion-canary-repair.patch)
- [`qwen38-protocol-v2-completion-canary-slices_progress.yaml`](artifacts/qwen38-protocol-v2-completion-canary-slices_progress.yaml)

The raw state, transcript, model reasoning, tool observations, endpoint/configuration, container
inspection, and scratch files remain private because they contain unnecessary operational detail.
