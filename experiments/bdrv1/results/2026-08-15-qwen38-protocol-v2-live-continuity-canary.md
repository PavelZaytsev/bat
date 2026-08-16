# Qwen3.8-27B protocol-v2 live continuity canary — 2026-08-15

This non-secret synthetic Java run was the first live-inference exercise of the phase-opaque
repository runner. Its purpose was mechanics and continuity, not model qualification: prove that
one model can keep doing ordinary BDRv1 repository work across automatic context maintenance while
tool execution remains offline, policy-bound, durable, and non-replayed.

## Frozen envelope

- Model: `Qwen/Qwen3.8-27B`, external reference role, `medium` reasoning
- Context: 131,072 tokens; 100,000-token proactive maintenance trigger
- Output allowances: 8,192 work, 4,096 maintenance
- Tool observations: 12,000 characters, symmetric head/tail preservation
- Tool environment: non-root, no-network Docker execution with read-only root, all capabilities
  dropped, exact mount/environment allowlists, and no provider credential
- Policy SHA-256: `06b78e61e3a54c1014d234c7ead66fc574fe6cdcf8c7c7f23155c4a21717c7d2`
- Canary-only circuit breaker: 40 logical work calls; forced maintenance after work turn 1
- Frozen methodology: the same nine original BDRv1 files, delivered by ordered path and byte hash

The tiny task required `PairKey.join("", right)` to return `right` without a leading separator,
while preserving non-empty behavior. Qwen had to read the frozen bundle, maintain the tracker,
establish red evidence, implement and saturate the repair, perform a production-only revert check,
run the validator, and create a self-contained commit.

## Continuity result

The run crossed two autonomous maintenance boundaries:

| # | Trigger | Boundary | Result |
| ---: | --- | --- | --- |
| 1 | forced canary trigger | after completed work turn 1 | resumed with policy, history, checkpoint, workspace, and closed-tool continuity verified |
| 2 | estimated context at 102,337 tokens | after completed work turn 22 | resumed at a compact context and continued through EXPOSE, implementation, falsification, tracker closure, tests, and commit |

Both numbered continuation manifests passed the independent evaluator against the same externally
recorded policy fingerprint. No tool call was lost or repeated. One zero-sample transport failure
at work call 27 retried after the frozen five-second backoff; the replacement attempt completed.
There was no partial sampled response, abandoned attempt, dangling tool intent, model swap, policy
change, or human-written handoff.

## Work product

Qwen completed the actual engineering work on its fortieth work turn:

- candidate commit `6cd548bd85fe7841cce91d876261961caeaa121c`;
- two files changed, 11 insertions, clean Git worktree;
- red EXPOSE observation: expected `beta`, received `:beta` against the pre-fix code;
- six value-level tests covering empty/non-empty sides and embedded separators;
- production-only counterfactual: retaining the new test while reverting the implementation fails
  at the empty-left assertion;
- final candidate test green in the original offline container; and
- one `done` slice, six phases complete, one fixed finding, confirmed collapse, and a tracker green
  against the frozen validator's 19 rules and evaluator-owned slice-status check.

Independent verification repeated the green candidate test, the red production-only revert, the
tracker consistency/self-test, and both continuation-manifest checks after the model stopped.

## Honest terminal classification

The runner reports `runner_limit`, not `completed`. The 40-call canary breaker fired immediately
after the final commit/status observation and before Qwen could issue the exact completion command.
The committed work product is complete and independently green, but the protocol result is
**runner-limit-censored after completion of the work product**. It is not silently reclassified as
a completed run, and it is not a model qualification score.

This earns the central mechanism: same-model context maintenance preserved real repository work
across a forced and a naturally triggered compaction. It also shows that 40 work calls is too tight
when a canary requires reading the full 202 KB methodology bundle. Monday's production breaker is
1,000 calls, so no candidate policy change is needed; future full-bundle canaries should not reuse
the 40-call diagnostic ceiling.

## Metrics

- 40 completed work responses, 2 completed maintenance responses
- 40 completed tool calls, 43 durable checkpoints, 257 hash-chained events
- 1 retry, 0 abandoned attempts, 2 validated continuations
- 998,961 prompt tokens and 20,720 completion tokens, including maintenance
- 461.436 seconds from first model attempt to the final response
- final post-maintenance work-request estimate: 56,527 tokens

## Archived safe artifacts

- [`qwen38-protocol-v2-live-canary-metrics.json`](artifacts/qwen38-protocol-v2-live-canary-metrics.json)
- [`qwen38-protocol-v2-live-canary-repair.patch`](artifacts/qwen38-protocol-v2-live-canary-repair.patch)
- [`qwen38-protocol-v2-live-canary-slices_progress.yaml`](artifacts/qwen38-protocol-v2-live-canary-slices_progress.yaml)

The raw state, transcript, model reasoning, tool observations, endpoint/configuration, container
inspection, and scratch files remain private because they contain unnecessary operational detail.
