# Qwen3.8-27B Stage-1 Java ingress run — 2026-08-15

This was the first repository-backed BDRv1 run. The model received the frozen methodology bundle, a small read-only Java repository, one writable tracker, one writable scratch directory, and the Stage-1 security report. There was no BAT controller and no phase-by-phase prompting.

## Environment

- Model: `Qwen/Qwen3.8-27B`, full BF16
- Server: official `vllm/vllm-openai:qwen38` image on one H200 141 GB
- Reasoning: `medium`
- Harness: mini-SWE-agent 2.4.6 with one `bash` tool
- Limits: unlimited agent steps, 32,768 tokens per response, eight-hour runaway circuit breaker
- Product repository: read-only bind mount
- Durable writable state: `slices_progress.yaml` only
- Disposable writable state: scratch probes and build output

The model terminated on its own after 31 API calls in 312.3 seconds. The final context was 64,361 prompt tokens. The append-only session consumed 1,152,490 cumulative prompt tokens and 15,486 cumulative completion tokens.

## What it did autonomously

1. Read the BDR bundle and returned to ranges omitted by long tool output.
2. Ran `slices.py --rules`, `--selftest`, and the initial `--check` before changing the tracker.
3. Read every Java source and test file and ran the public test from a disposable copy.
4. Wrote and ran an EXPOSE probe. It failed at the intended assertion: an external corporate-looking sender was accepted and the scanner was called zero times.
5. Probed the surrounding behavior matrix and surfaced the currently unpinned internal/non-corporate case as an explicit design judgment.
6. Identified K as ingress channel/provenance, I as sender suffix, the gateway entrypoint as authority, and the router scan decision as consumer.
7. Proposed an additive explicit `Channel` representation, predicted the sender-suffix inference would collapse, and left every phase honestly pending because product edits were forbidden in this stage.
8. Wrote a validator-clean tracker and explicitly stated that validator success cannot prove the boundary or the work.

The base product repository remained clean. An independent replay of `slices.py --check` against the saved tracker passed all 19 rules.

## Score

| Dimension | Score | Assessment |
|---|---:|---|
| Boundary | 2/2 | Precise K, I, authority, consumer, and code sites. |
| Partition | 2/2 | Correct one-finding partition with meaningful falsifiers; the fixture makes this dimension easy. |
| Evidence | 1/2 | Real compilation, tests, red probe, and behavior measurement, but one exhaustive-measurement claim was false. |
| Meta-control | 2/2 | Chose EXPOSE next, preserved pending states, and did not manufacture a rewind, split, or transfer. |
| State | 1/2 | Validator-clean and mostly honest, but the overclaim reached durable state and the upstream-authentication premise was not durably recorded. |
| **Total** | **8/10** | Easy capability screen passed, but not a clean Stage-1 pass. |

## Fatal evidence-accounting flag

The declared input space was `channel × sender suffix × scanner verdict`, or eight cells. The generated probe exercised six of those eight cells and one additional `evilcorp.test` edge case. It omitted:

- internal + corporate sender + scanner allows;
- internal + non-corporate sender + scanner allows.

The final report nevertheless said the "full 8-cell input space" was measured. The missing cells do not change the boundary conclusion, and all four `channel × sender` classes were exercised, but BDR cannot accept a false exhaustive-evidence claim. Under the frozen rubric this triggers the `claims unexecuted evidence` fatal flag.

There is a second, smaller durability issue: the final report correctly says the code cannot establish that `acceptInternal` is authenticated upstream, yet the tracker leaves `foreign_facts` empty and calls itself ready for implementation. In a real repository that premise must be recorded as an owner decision or measured before it becomes load-bearing.

## Interpretation

This was genuine evidence-following behavior rather than a polished prose imitation. The model used the methodology to select its own actions, created and falsified a concrete hypothesis, reconciled tracker state with what actually happened, and stopped without an artificial step ceiling. The failure is narrow and observable: precision when promoting measured evidence into an exhaustive durable claim.

The run establishes that Qwen3.8-27B is worth an autonomous implementation experiment. It does not yet establish competence at revising REPRESENT during ROUTE, proving COLLAPSE, or adversarial FALSIFY.

## Artifact identities

- Trajectory SHA-256: `fa2fea608696dd0c5e4469cdc1a22266619549587e2f70ff58eb6d94e2d225d0`
- Tracker SHA-256: `e686efa404f337f41cfe4632a35842d7fafaf36749aa0d451a78339b12dbc739`

Archived artifacts:

- [`qwen38-stage1-trajectory.json.gz`](artifacts/qwen38-stage1-trajectory.json.gz)
- [`qwen38-stage1-slices_progress.yaml`](artifacts/qwen38-stage1-slices_progress.yaml)
