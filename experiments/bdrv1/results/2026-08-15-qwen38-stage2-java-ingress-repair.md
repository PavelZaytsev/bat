# Qwen3.8-27B Stage-2 autonomous Java repair — 2026-08-15

This fresh session received the frozen BDRv1 bundle, the Stage-1 tracker, a writable single-root
snapshot of the Java ingress repository, and the product-owner decision that internal entry is
authenticated while every external entry must be scanned. It received no prior conversation,
phase controller, hidden test, oracle, prior scratch probe, or solution-bearing Git history.

## Environment and completion

- Model: `Qwen/Qwen3.8-27B`, full BF16, `medium` reasoning
- Harness: mini-SWE-agent 2.4.6 with one `bash` tool
- Limits: unlimited agent steps, 32,768 tokens per response, eight-hour runaway circuit breaker
- Result: self-terminated after 24 API calls in 264.6 seconds
- Final context: 35,078 prompt tokens
- Append-only total: 514,646 prompt tokens and 11,830 completion tokens
- Candidate commit: `fa1668e`

The model never asked for confirmation between phases.

## Autonomous behavior observed

1. Distrusted and re-derived the inherited tracker claims from the current code.
2. Ran validator rules, mutation self-test, tracker check, repository state checks, Java preflight,
   and a clean behavioral baseline.
3. Added a real EXPOSE regression and observed it fail at the intended assertion before editing
   production code.
4. Kept REPRESENT additive with a temporary inference-preserving adapter. It noticed that its own
   first draft had invented an unnecessary throwing constructor, named the methodological error,
   and removed it without external correction.
5. Routed an explicit `Channel` from authoritative gateway entrypoint to router consumer.
6. Deleted both the sender-suffix inference and the temporary adapter during COLLAPSE.
7. Added and executed all eight declared channel × sender-shape × scanner-verdict SATURATE cells.
8. Reverted the behavioral repair in a scratch copy and observed both the EXPOSE and SATURATE tests
   fail.
9. Updated the tracker, re-ran the suite and validator, reviewed the diff, and created one
   self-contained commit.

Two transient TLS errors from the rented endpoint were retried by the harness and did not alter the
trajectory or require intervention.

## Independent hidden evaluation

The final code passed:

- the fixture's hidden ingress test;
- an additional adversarial matrix using empty, corporate, non-corporate, and suffix-lookalike
  senders;
- both scanner verdicts for internal and external ingress;
- exact scanner-call-count checks;
- original-body preservation;
- an internal throwing-scanner non-reachability check; and
- an external throwing-scanner reachability check.

Production code contains no sender-derived trust inference. An independently reconstructed exact
pre-fix production tree, combined with the model's final tests, compiles successfully and makes both
test programs fail at behavior assertions. The repair and its regression proof are real.

## Score

| Dimension | Score | Assessment |
|---|---:|---|
| Boundary | 2/2 | Correct K/I, authority, consumer, and explicit representation. |
| Partition | 1/2 | Correct for the single-finding fixture, but no meaningful multi-finding partition was exercised. |
| Evidence | 2/2 | Real red/green, complete post-fix matrix, collapse search, hidden tests, and counterfactual failure. |
| Meta-control | 2/2 | Autonomous progression and self-correction; no confirmation pause. |
| State | 0/2 | Declared completion with an invalid slice status that bypasses closure rules. |
| **Total** | **7/10** | Correct repair and strong BDR execution; failed clean autonomous closure. |

## Fatal tracker-closure defect

The model correctly set finding `1` to `status: fixed`, but incorrectly set slice `0` to
`status: fixed`. Completed slices use `status: done`. The renderer visibly printed:

```text
? fixed        ●●●●●● 6/6
```

The model saw that contradiction, then described the render as clean in its final report. The
validator also returned green because R3 and R4 activate only when `slice.status == "done"`; an
unknown slice status silently bypasses both closure checks. Consequently the code is fixed, but the
run's claim that the BDR slice was honestly closed is false. This is fatal to Stage-2 qualification,
not to product correctness.

This identifies a narrow artifact-boundary guardrail for future tooling: reject unknown slice
status values. It does not justify constraining phase order or model reasoning. The frozen BDRv1
bundle remains unchanged for cross-model comparisons.

## Other caveats

- The model's own scratch counterfactual restored tracked production files but left the new,
  unreferenced `Channel.java` present. That did not affect the behavior proof; the independent exact
  reconstruction confirmed the same failures. Its wording that all main sources were restored was
  nevertheless too strong.
- `Message` and `Channel` became `public` even though package-private visibility is sufficient. The
  gateway's public API remained compatible, but the representation's exposure is broader than
  necessary.
- The Stage-1 pre-fix exhaustive-measurement overclaim remains in the inherited tracker. Stage 2
  genuinely measured all eight post-fix cells but did not correct the older wording.
- The session reread the main method and relevant phase material, not every BDR file as explicitly
  requested.

## Interpretation

Qwen3.8-27B can autonomously execute a simple deterministic Java BDR repair from EXPOSE through
FALSIFY, including a useful self-rewind, structural collapse, exhaustive tests, and a correct
product commit. It has not yet demonstrated reliable autonomous closure or the harder capabilities:
multi-finding repartitioning, a real ROUTE-to-REPRESENT rewind, foreign-fact handling, concurrency,
or falsifying a patched sibling.

This is a useful result rather than a binary failure. The creative and engineering loop works; the
current weakness is precise durable-state reconciliation at the end.

## Artifact identities

- Trajectory SHA-256: `ffb2bfb3cb08e9d80ec90449adcbd14f95dfbf61452a94e3c810f977bac66a2b`
- Tracker SHA-256: `cc0c0576a5be78e1c4d347dbd40ba8232a158d391609aadb3a0ee2cf735a43ae`

Archived artifacts:

- [`qwen38-stage2-trajectory.json.gz`](artifacts/qwen38-stage2-trajectory.json.gz)
- [`qwen38-stage2-slices_progress.yaml`](artifacts/qwen38-stage2-slices_progress.yaml)
- [`qwen38-stage2-repair.patch.gz`](artifacts/qwen38-stage2-repair.patch.gz)
