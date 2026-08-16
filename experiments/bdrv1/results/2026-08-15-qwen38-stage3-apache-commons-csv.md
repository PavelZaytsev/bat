# Qwen3.8-27B Stage-3 Apache Commons CSV repair — 2026-08-15

This was the first real-project, end-to-end BDRv1 repair probe. The candidate received a synthetic
single-root snapshot of Apache Commons CSV, the frozen BDRv1 bundle, a writable tracker and scratch
area, and the short-read task packet. It received no upstream history, remote, PR text, future
object, hidden evaluator, or solution.

## Environment and frozen inputs

- Model: `Qwen/Qwen3.8-27B`, full BF16, `medium` reasoning
- Server: vLLM on one H200, 131,072-token model context
- Harness: mini-SWE-agent 2.4.6 with one `bash` tool
- Product base: `bb5ac53755cf220aba2473bb3cd58e42509d190a`
- Product tree: `ea9c3554f29d2edf7ddaf582371f77886a7b569c`
- Candidate environment: offline Docker execution with a private, pre-populated Maven cache
- Frozen methodology: unchanged from the Stage-1 and Stage-2 comparisons

## Run segmentation and censorship

This did not complete as one autonomous session:

| Segment | Exit | API attempts / completed responses | Prompt-token total | Completion-token total | Output allowance | Exact terminal boundary |
|---|---|---:|---:|---:|---:|---|
| Original | context-censored | 39 / 38 | 2,274,391 | 15,017 | 32,768 | 98,305 + 32,768 = 131,073 |
| Recovery 1 | context-censored | 65 / 64 | 3,879,947 | 48,800 | 8,192 | 122,881 + 8,192 = 131,073 |
| Recovery 2 | submitted | 87 / 87 | 5,093,238 | 54,047 | 8,192 | none |

The first two failures are harness/context-allocation censorship, not model crashes: both requests
exceeded the 131,072-token context by exactly one token. mini-SWE-agent retained an append-only
transcript and had no compaction or native trajectory-resume mechanism. Recovery therefore required
two fresh model contexts with explicit handoffs and, for the second and third segments, a different
output allowance. Those segments preserve useful evidence, but together they are not a frozen
one-session result and must not enter cross-model comparisons as protocol-v2.

Several RunPod proxy/TLS failures also retried during the recovery. The old harness did not persist
HTTP-attempt counts or retry latency, so those metrics are unavailable rather than estimated.

## Durable recovery behavior

Across the recoveries, Qwen independently re-read and re-ran the handed-off evidence instead of
accepting the handoff as proof. It:

1. inspected Apache Commons IO 2.22 bytecode and measured short-read, `ready`, mark, and reset
   behavior;
2. corrected a first EXPOSE test that compared `CSVRecord` identity instead of parsed values, then
   re-established red behavior against the original product code;
3. grouped parser delimiter handling, escaped-delimiter handling, and Reader-backed escaping-print
   behavior under a shared completion K;
4. added 122 public test executions across delivery granularity, delimiter length and position,
   escaping, quoting, CRLF, round-trip, EOF, zero-length, and internal-buffer boundaries;
5. stashed the production repair and demonstrated that the three named defect tests fail at their
   assertions on the pre-fix code;
6. ran the full offline suite, the tracker check, and all 19 validator mutations; and
7. created one self-contained product commit.

This is substantial autonomous engineering behavior. The qualification failure below is about the
chosen representation and the truth of final tracker closure, not a lack of productive work.

## Final candidate

- Commit: `c1954bef145d936eeca5b086da44c31b56c6e38c`
- Diff: 5 files, 490 insertions, 11 deletions
- Public candidate report: 1,077 tests, 0 failures, 11 pre-existing skips
- New public executions: 117 in `ChunkedDelimiterTest`, 5 in `PeekBlockingTest`
- Tracker: three findings `fixed`; one slice `done`, six phases complete; consistent against the
  frozen validator's 19 rules

The candidate introduced `ExtendedBufferedReader.peekBlocking(char[])` and routed three known
consumers through it. It deliberately preserved the inherited `peek(char[])` and bulk-read
semantics.

## Independent evaluation

The isolated hidden evaluator ran only after the candidate commit, tracker, and all three
trajectories were frozen and hashed. Four of five checks passed:

- parser equivalence under one- and two-character delivery;
- escaped multi-character delimiter behavior;
- Reader-backed printing and round-trip behavior; and
- zero-length read/peek behavior.

The reader-level fill-until-EOF check failed: a requested three-character window returned one.
This is the exact abstraction Qwen had measured and then intentionally left unchanged. The hidden
source and raw hidden report remain evaluator-only and are not archived in this branch.

## Score and qualification

| Dimension | Score | Assessment |
|---|---:|---|
| Boundary | 1/2 | Found completion as K, but represented it beside rather than inside the shared reader abstraction. |
| Partition | 1/2 | Correctly grouped three consumers, but missed the reader-level sibling carrying the same K. |
| Evidence | 1/2 | Strong public matrix and counterfactual, but SATURATE encoded the mistaken narrower representation. |
| Meta-control | 1/2 | Revalidated handoffs and corrected tests, but did not rewind REPRESENT after observing the shared short read. |
| State | 0/2 | Closed the slice and all findings and called the foreign fact eliminated despite the surviving inference. |
| **Total** | **4/10** | **Fatal architectural error; not Stage-3 qualified.** |

The sole hidden failure directly realizes the frozen fatal rule: **inventing a helper or guard
instead of rewinding an incomplete representation**. The model observed that inherited bulk
`read`/`peek` returned short mid-stream, added a specialized helper and three count guards, then
recorded that keeping the inherited behavior was deliberate. Product parsing and printing were
repaired, but the shared inference remained reachable.

The tracker therefore overcloses the evidence: `done`, all three findings `fixed`, no surviving
siblings, and the bulk-read foreign fact `eliminated` are semantically false even though
`slices.py --check` is green.

## Boundary and Monday lessons

- Keep the existing fatal helper/guard rule; Stage 3 empirically validates it.
- SATURATE the existing shared abstraction's semantics, not only a new helper and selected callers.
- Preserve the reader-level fill check as an evaluator boundary without exposing its source.
- Add no phase controller. The model followed the phases autonomously; the miss was representational.
- Future Qwen/GPT-OSS/Gemma comparisons must start fresh under one fingerprinted protocol with
  proactive context maintenance. Manual recovery remains diagnostic.

## Artifact identities

- Original trajectory SHA-256: `b4a557eeaf40249a77dc1f8a05aae37892a1d6e6720667a64380a3893e6ade57`
- Recovery-1 trajectory SHA-256: `5b94e83d0ee98679319032b70590c1c4140f2a589709555da04d4d3d3449a8a7`
- Recovery-2 trajectory SHA-256: `14fb3c3d48c4680d302ff013b13e39341bbc04f7a931288b7d05cb518bbce057`
- Tracker SHA-256: `0d6d338e27f26db678e4baabc9290a40c26ac7bc3a7647a4382bac40b1617c31`
- Reconstructable Git diff SHA-256: `a0c079d864d6a10da48cb38398e3baf3d18665b65ef0b1c39cab602f4a096bf8`

Archived safe artifacts:

- [`qwen38-stage3-repair.patch.gz`](artifacts/qwen38-stage3-repair.patch.gz)
- [`qwen38-stage3-slices_progress.yaml`](artifacts/qwen38-stage3-slices_progress.yaml)
- [`qwen38-stage3-run-metrics.json`](artifacts/qwen38-stage3-run-metrics.json)

The 37 MB candidate bundle, raw trajectories, raw reasoning, endpoint configuration, hidden source,
and hidden report are intentionally not committed.
