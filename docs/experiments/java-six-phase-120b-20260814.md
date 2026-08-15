# Issue #33 postmortem: six-phase Java run on GPT-OSS-120B

This is the publication-safe postmortem for the 2026-08-14 supervised Java canary. The private
archive remains operator evidence; this document contains only bounded counters, stable reason
codes, SHA-256 anchors, and sanitized telemetry event sequences.

## Result

The run did not complete the six phases. It reached BDR revision 3 in `expose`, staged a regression
test, and obtained an authenticated red-test observation. BAT then stopped before the next tool
execution with `workspace_fingerprint_mismatch`.

That final stop is the before-fix baseline for issue #33. It was a BAT fingerprint false positive,
not the red test failing unexpectedly, not an exo outage, and not evidence that the model could not
continue. A read-only host-side Git inspection refreshed stat-cache bytes in `.git/index`; source
files and detached `HEAD` were unchanged, but workspace fingerprint v1 included the raw index bytes.

The preserved staged-patch SHA-256 is
`0327d2c2b6bf101e52f9d3b870bc24344a19281c2b5ee2b41148e514ec98fa3c`.
The patch is not reproduced here.

## Evidence and reconciliation

The archive checksum inventory named every one of the other 118 regular files exactly once. There
were no unlisted files, stale entries, or symbolic links, and all 118 digests matched. The SHA-256
of that inventory is
`d51942b41690bd51f558d3285dfb44c3188ab65c53c8db984e051adfc8d18633`.
For each published failed attempt, the result's telemetry digest also matched the exact telemetry
file, and the telemetry records matched the checkpoint records. Every attempt's event sequence was
contiguous from 1 through the recorded final sequence.

The headline totals reconcile in two independent ways: summing each attempt's current counters and
reading attempt 006's cumulative counters both produce **87 charged iterations, 81 tool executions,
and 890,565 total tokens**. "87 turns" means BAT's charged logical-iteration counter. There are 85
`model_turn` events: 82 valid tool-calling turns and three backend-failed turns. Two more iterations
were charged in attempts 002 and 003 but never produced a model turn because the serving path stayed
unavailable through retries. Telemetry records 97 provider attempts in all: 82 completed, 15 failed,
and 12 retry events.

<!-- issue-33-attempts:start -->
| Attempt | Archive state | Charged iterations | Model-turn events | Provider attempts | Retries | Tool executions | Tokens | Active ms | Terminal or last recorded event |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---|
| 001 | published failed | 14 | 14 | 14 | 0 | 13 | 108,678 | 390,354 | `harmony_chat_protocol_violation` at sequences 55–57 |
| 002 | in progress | 15 | 14 | 20 | 6 | 14 | 125,560 | 1,731,523 | sixth retry recorded at sequence 70 |
| 003 | in progress | 1 | 0 | 6 | 6 | 0 | 0 | 333,928 | sixth retry recorded at sequence 14 |
| 004 | published failed | 19 | 19 | 19 | 0 | 18 | 242,819 | 1,289,429 | `harmony_chat_chat_error` at sequences 75–77 |
| 005 | published failed | 12 | 12 | 12 | 0 | 11 | 75,782 | 276,251 | `harmony_chat_protocol_violation` at sequences 47–49 |
| 006 | published failed | 26 | 26 | 26 | 0 | 25 | 337,726 | 1,304,585 | `workspace_fingerprint_mismatch` at sequence 105 |
<!-- issue-33-attempts:end -->

The attempt wall counters sum to 5,326,070 ms (1h 28m 46.070s) of active attempt time. The lineage
counter is 6,758,945 ms (1h 52m 38.945s), exactly the interval from attempt 001's start through
attempt 006's final checkpoint. The 1,432,875 ms difference is the five restart/operator gaps, not
unattributed work inside an attempt.

The safe checkpoint anchors are:

| Attempt | Checkpoint SHA-256 | Event sequence |
|---:|---|---:|
| 001 | `b06161286bca6ea50bb308e120a454c5d05004681c284f6ba39b7356687be675` | 1–57 |
| 002 | `816221f8cd30c919efc08885e3028913f81f4df68a4d59f664736619da1b835a` | 1–70 |
| 003 | `86d380981bd8dc9ad9314c47380ebbd8bd39075d9d10c2c93bc3c1d49c0625fd` | 1–14 |
| 004 | `b84042b2dd3ba3e015023602fdbe37eb11c90cf32f7fa3db8543f69f8a386a7e` | 1–77 |
| 005 | `351694465722e6684240c5a447057e8d3dcce34496de22adfda0aec08e1708a3` | 1–49 |
| 006 | `11e101f682260d4797b04c30f33bd646ae451f1a303749c976aaa323a9fb1f2c` | 1–105 |

## Attempt timeline

Times are PDT on 2026-08-14 and come from each checkpoint's start time plus its current wall
counter.

| Attempt | Active interval | What happened |
|---:|---|---|
| 001 | 13:05:29.316–13:11:59.670 | Established the baseline and advanced from preflight to audit. A `bdr_apply` request was rejected at sequence 53; the next provider response then violated the pinned Harmony Chat dialect at sequence 55, and BAT failed closed at 57. |
| 002 | 13:13:35.477–13:42:27.000 | Completed 14 model/tool cycles in audit. A body failure at 59 was followed by three open timeouts and two open failures. Exponential retry events reached sequence 70; no terminal result was published. |
| 003 | 13:45:44.575–13:51:18.503 | Six opens failed at sequences 3, 5, 7, 9, 11, and 13. Their retries were recorded at 4, 6, 8, 10, 12, and 14. No model turn, tool execution, or token usage was recorded. |
| 004 | 13:53:49.578–14:15:19.007 | Finished audit, entered `expose` at revision 2, and entered execution at revision 3. The provider emitted a terminal chat error at 75; BAT recorded the backend-failed turn at 76 and stopped at 77. |
| 005 | 14:28:10.663–14:32:46.914 | Resumed `expose` for 11 model/tool cycles, then hit a second pinned-dialect violation at 47 and stopped at 49. |
| 006 | 14:36:23.676–14:58:08.261 | Produced the red regression test, then encountered the false workspace fingerprint mismatch described below. The serving path completed all 26 provider attempts in this attempt. |

Attempts 002 and 003 are crash/interruption checkpoints with status `running`, not completed failed
attempts. Their last events schedule a seventh provider attempt after a 320-second backoff, but the
archive contains neither that attempt nor a terminal event. The evidence does not identify who or
what stopped those controller processes.

## What worked

- BAT's checkpoint chain preserved cumulative budgets and BDR state across all six attempts. The
  state moved from preflight to audit in attempt 001, then to `expose` revision 2 and execution
  revision 3 at attempt-004 sequences 49–54.
- Retry safety held. Attempts 002 and 003 executed no tool after their unfinished provider turn.
  The retry schedule was bounded and observable: 10, 20, 40, 80, 160, then 320 seconds.
- The model made concrete progress. Across the lineage, 82 completed model turns requested tools;
  79 of 81 recorded tool executions succeeded at the controller/tool boundary. Attempt 006 applied
  two patches at sequences 93 and 97 and invoked the Java build at 101.
- The red observation was authenticated. The build operation itself completed successfully as a
  worker operation, while its bounded result recorded `exit:1`, which is the expected red state for
  `expose`. The staged patch was bound to the SHA-256 above.
- Fail-closed behavior held. Invalid wire responses never became model continuations, and the final
  fingerprint mismatch prevented the requested next tool from executing.

## Attempt 006: red test versus fingerprint failure

These are separate events and should not be collapsed into "the test failed the run":

1. Sequences 93 and 97 recorded two successful patch operations.
2. Sequence 101 recorded a successful `worker_java_build` operation. Its authenticated bounded
   observation recorded `exit:1`, establishing the intended red test.
3. A read-only host-side Git inspection then refreshed non-semantic stat-cache fields in the index.
   The staged entries, source bytes, and detached `HEAD` remained semantically unchanged.
4. Sequences 103 and 104 show a subsequent provider completion and a valid tool-calling model turn.
5. BAT recomputed the precondition before executing that requested tool. Fingerprint v1 hashed the
   raw index file, observed the stat-only byte change, and emitted `workspace_fingerprint_mismatch`
   at sequence 105. There is no sequence-105 `tool_execution` event.

The immediate cause is therefore BAT's representation of Git index state. The red test and its
receipt were valid inputs to the next BDR transition; the integrity check rejected an equivalent
workspace before that transition could run.

## Failure taxonomy and attribution

| Class | Evidence | Attribution | What can be concluded |
|---|---|---|---|
| Serving availability | One `harmony_chat_body_failed`, three `harmony_chat_open_timed_out`, eight `harmony_chat_open_failed`, and 12 retry events in attempts 002–003 | Serving/transport path; exact exo-versus-network split is unknown | No model-quality conclusion is possible because these failures occurred before a valid turn completed. |
| Pinned-dialect failure | `harmony_chat_protocol_violation` in attempts 001 and 005 | Model/serving/runtime boundary; exact split unknown | BAT correctly rejected responses that could not satisfy the Harmony Chat contract. The bounded code cannot distinguish malformed model tool arguments from framing, identity, usage, or stream-finalization faults. |
| Provider-declared failure | `harmony_chat_chat_error` in attempt 004 | Serving runtime/provider | The endpoint returned an error event after 18 completed turns; its private body is neither needed nor published. |
| Tool-level rejection | `bdr_apply` tool errors at attempt-001 sequence 53 and attempt-006 sequence 89 | Model/controller contract boundary | These were recoverable request-level errors. The sanitized evidence does not expose enough detail to assign a narrower model cause. |
| False integrity failure | Attempt-006 sequence 105 | BAT fingerprint v1 | Raw index stat-cache bytes changed without a semantic workspace change. This is the issue #33 defect. |
| Interrupted controllers | Nonterminal attempts 002 and 003 | Unknown | The checkpoints prove where execution stopped, not why the processes stopped. |

The model should receive credit for reaching a valid red test, but not for a completed repair: it
never advanced beyond `expose`, produced no accepted handoff, and was never evaluated against the
sealed oracle. Conversely, the open/body transport failures are not evidence that the model reasoned
poorly because BAT did not receive model output from them. The generic protocol violations remain
ambiguous between model output and the serving/runtime wire.

## Efficiency findings

Token accounting is dominated by replayed input:

<!-- issue-33-tokens:start -->
| Attempt | Input | Cached input | Output | Reasoning | Total |
|---:|---:|---:|---:|---:|---:|
| 001 | 107,301 | 42,315 | 1,377 | 790 | 108,678 |
| 002 | 124,007 | 49,140 | 1,553 | 992 | 125,560 |
| 003 | 0 | 0 | 0 | 0 | 0 |
| 004 | 230,830 | 66,885 | 11,989 | 9,961 | 242,819 |
| 005 | 74,330 | 30,030 | 1,452 | 1,100 | 75,782 |
| 006 | 325,150 | 126,945 | 12,576 | 9,883 | 337,726 |
<!-- issue-33-tokens:end -->

| Measurement | Count | Interpretation |
|---|---:|---|
| Input tokens | 861,618 | 96.7% of total tokens |
| Cached input tokens | 315,315 | 36.6% of input; 546,303 input tokens were uncached |
| Output tokens | 28,947 | Includes 22,726 reasoning tokens |
| Reasoning tokens | 22,726 | 78.5% of output tokens |
| Total tokens | 890,565 | Exactly the sum of per-turn totals and per-attempt counters |

Of the 5,326,070 ms active-attempt total, provider attempts consumed 4,621,218 ms (86.8%), completed
retry sleeps consumed 620,000 ms (11.6%), worker tools consumed 35,476 ms (0.7%), and the remaining
49,376 ms (0.9%) was controller and checkpoint overhead. Optimizing worker commands would therefore
have little effect on this run. Reducing uncached prefill, avoiding restarts, and improving serving
availability are the material levers.

<!-- issue-33-tools:start -->
| Tool | Executions |
|---|---:|
| `worker_search` | 28 |
| `worker_read_file` | 28 |
| `worker_workspace` | 5 |
| `worker_target_diff` | 5 |
| `bdr_audit_summary` | 5 |
| `worker_apply_patch` | 4 |
| `worker_java_build` | 2 |
| `bdr_apply` | 4 |
<!-- issue-33-tools:end -->

Search and read operations account for 56 of 81 tools (69.1%), while only four patch operations and
two build operations were recorded. Some of that inspection was necessary, but six fresh model
contexts repeatedly re-established state. A future comparison should report both total tools and
tools after the last resume so restart overhead is visible.

Attempt 006 alone used 337,726 tokens (37.9% of the lineage), and attempt 004 used 242,819 (27.3%).
Within attempt 006, input context grew from 4,011 tokens on the first turn to 27,814 on the last—a
6.9-fold increase even before considering earlier attempts. This is why completing a phase in fewer
tool turns matters alongside cache reuse.

Only attempt 006 had a complete enough summary to report aggregate generation throughput: 18.28
output tokens/second with a 23.13-second mean time to first event. Failed-attempt summaries mark
aggregate token/throughput fields unavailable when a final turn fails before measurement, so the
cross-attempt totals above come from the observed model-turn records and checkpoint counters.

The finalized telemetry summaries retain these deployment-cost and first-event measurements:

| Attempt | Mean first event | Node-hours |
|---:|---:|---:|
| 001 | 20.2355 s | 0.311395833 |
| 004 | 26.857889 s | 1.070760833 |
| 005 | 16.348 s | 0.226905000 |
| 006 | 23.134192 s | 1.083853333 |
| **Finalized total** | — | **2.692915000** |

Attempts 002 and 003 did not publish finalized telemetry summaries, so this table does not invent
node-hours or a mean first-event value for them. Within attempt 006, summing observed provider and
tool durations through the relevant event gives 973,949 ms (16m 13.949s) to the first accepted patch
at sequence 93 and 1,212,207 ms (20m 12.207s) to the authenticated red build at sequence 101. These
are event-accounted milestone times, not wall-clock timestamps; the final summary contains another
2,262 ms of controller/checkpoint overhead beyond all observed provider and tool durations.

## Ranked fix matrix

"Implemented" below means present in the issue #33 change with offline coverage. "Follow-up" means
not implemented here. No post-fix live run has occurred, so reliability and performance gains are
expectations from the failure mechanism and offline tests—not measured speedups.

| Rank | Change | Status | Reliability gain | Performance gain | Confidence | Risk / effort |
|---:|---|---|---|---|---|---|
| 1 | Semantic Git-index fingerprint v2 | Implemented | High: removes the observed stat-cache false positive while retaining real mutation detection | None expected | High offline; live confirmation pending | Medium: strict binary parser, bounded by fail-closed format and corruption tests |
| 2 | Digest-pinned, one-shot seed patch for a fresh run | Implemented | High: recovers the exact preserved work without copying the old ledger or workspace | Medium: avoids asking the model to rediscover and rewrite the preserved patch; fresh audit is still required | High for the offline boundary; live savings unmeasured | Medium: new mutation surface, constrained to a private file, exact digest, revision 0, active `expose`, and one use |
| 3 | Retry in-band chat errors only for the qualified self-hosted policy | Implemented | Medium: a transient instance reload like attempt 004 can remain within the immutable pre-tool retry boundary | Neutral or negative during an outage; prevents a full restart when recovery is quick | Medium: retry safety is proven offline, but the archived error's internal cause is hidden | Medium: must never broaden to unqualified endpoints or post-tool replay |
| 4 | Recover a fully durable terminal bundle interrupted before final rename | Implemented | Medium: preserves honest terminal evidence at the publication commit point | None expected | High offline | Low–medium: recovery accepts only the exact closed file set and matching binding |
| 5 | Warm and serialize the serving instance; keep one run per cache | Follow-up: operator procedure | Medium: reduces placement/reload and interleaving failures | Potentially high: 36.6% of input was cached here, leaving 546,303 uncached input tokens | Medium; no controlled post-fix run | Low operational effort; may reduce cluster concurrency |
| 6 | Resume directly from attached durable BDR/workspace facts; batch independent inspections; avoid duplicate text encodings; return stable command-failure fields | Implemented offline | Low–medium: fewer reconstruction steps and clearer recoverable tool failures | Potentially medium: read/search was 69.1% of tools and attempt-006 context grew 6.9-fold | Medium for the contract; no live speedup measured | Low–medium: guidance preserves audit/read escape hatches instead of imposing a hard search cap |
| 7 | Compare the revised contract against this trajectory on a stable serving instance | Follow-up: controlled live experiment | Establishes whether the fixes preserve repair quality | Measures actual turn, token, cache, and wall-time change | None until rerun | Medium inference cost; use the gates below and stop early on regression |

## Before-fix baseline

Workspace fingerprint v1 bound the detached `HEAD`, the raw `.git/index` bytes, and the worktree
tree. That catches real mutations, but it also treats Git's mutable stat cache as source identity.
The baseline failure is:

- valid red observation at attempt-006 sequence 101;
- unchanged semantic staged state and detached `HEAD`;
- stat-only index refresh after that observation; and
- false `workspace_fingerprint_mismatch` at sequence 105 before the requested tool executed.

The issue #33 fix replaces the raw index bytes with a validated semantic index snapshot. It binds
the repository object format, entry count, path bytes, merge stage, canonical mode, object identity,
behavior-changing entry flags, and optional extension signature and payload. It deliberately
ignores only per-entry ctime, mtime, device, inode, uid, gid, and file-size cache fields. The outer
workspace digest still binds detached `HEAD` and each direct worktree path, type, executable bit,
and content.

The parser fails closed on a bad signature, version, checksum, padding, path order, flag, mode, or
truncated extension. Required extensions are rejected, so a split or sparse index cannot silently
weaken the snapshot. The workspace manifest version advances from v1 to v2, causing legacy
raw-index lineages to reject on resume. Regression coverage proves both sides of the boundary: a
Git stat-cache refresh changes raw index bytes without changing the fingerprint, while real changes
to worktree content, staged identity, path, mode, flags, or `HEAD` still change or invalidate it.

Do not resume this archived lineage with the fix. A new BAT commit changes the attempt binding, and
the existing ledger was created with fingerprint v1. Start a new logical run and import the exact
preserved patch as an explicit ledgered mutation. The rerun passes the issue #33 boundary only when
the same read-only Git inspection leaves the fingerprint stable and the authenticated red result can
advance BDR beyond `expose`. Completing all remaining phases and evaluator acceptance are separate
requirements before claiming a successful canary.

## Exact next-run procedure and gates

1. **Freeze the inputs.** Keep the archive unchanged and reverify all 118 checksum entries. Pin a
   clean BAT commit containing the implemented fixes, the same authenticated source base/head, and
   freshly observed deployment identity. Run the complete offline suite before arming live access.
2. **Start a new lineage.** Allocate a new logical run and fresh private evidence/workspace roots.
   Do not resume an old attempt and do not copy its manifest, ledger, tracker, Git directory, or
   private authority material. Gate: the fresh workspace uses manifest v2 and independently verifies
   the authenticated source pins.
3. **Configure the seed without publishing it.** Set `BAT_LIVE_SEED_PATCH` to the private preserved
   patch file and `BAT_LIVE_SEED_PATCH_SHA256` to
   `0327d2c2b6bf101e52f9d3b870bc24344a19281c2b5ee2b41148e514ec98fa3c`.
   Gate: preflight accepts only a private-owner regular non-symlink file outside the evidence output,
   strict UTF-8, 1 byte–2 MiB, safe text-patch grammar, and the exact digest. The published result may
   retain the digest, never the path or patch text.
4. **Qualify the serving path.** Place and warm the pinned model, verify its exact identity and native
   Harmony Chat behavior, disable request/body debug logging, and give this run exclusive use of the
   serving instance. Gate: a small qualified request completes before the production attempt;
   otherwise stop without spending the canary budget. Quarantine any pre-existing verbose logs
   privately and publish only sanitized derived counters or reason codes.
5. **Re-establish BDR state, then apply once.** Let the fresh run establish its green baseline,
   rediscover boundaries, and enter active `expose` with workspace revision 0. Only then may the
   model call `worker_apply_seed_patch`, whose arguments contain only the expected workspace revision
   and fingerprint. Gate: the operation is one-shot, exact replay is non-mutating, a changed binding
   conflicts, and the observed staged-patch digest equals the preserved digest.
6. **Reproduce the red boundary.** Run the authenticated Java build and require the expected red
   observation. Then perform the same class of read-only host Git inspection that triggered the
   baseline. Gate: source bytes, staged semantics, detached `HEAD`, and fingerprint v2 remain equal;
   a deliberate semantic mutation continues to fail in offline tests.
7. **Cross the issue #33 boundary.** Submit the red receipt to BDR and require a checkpoint beyond
   `expose` with no `workspace_fingerprint_mismatch`. This proves the issue #33 fix only. Continue all
   remaining phases, broad verification, handoff validation, and sealed-oracle evaluation before
   calling the canary successful.
8. **Publish and compare honestly.** Require one terminal result with digest-matched checkpoint,
   telemetry, and evidence. Report charged iterations and `model_turn` events separately, along with
   tools, input/cache/output/reasoning tokens, provider failures/retries, first-event latency,
   generation throughput, active wall time, and lineage gaps. Compare with this baseline, but label
   any improvement as measured only after that live result exists.

At any failed gate, retain the bounded result/checkpoint and stop. Do not repair the archived state
in place, silently change a pin, substitute a different model, or treat another in-progress
checkpoint as a completed experiment.

## Limitations

- Checksum verification proves that the analyzed archive matches its inventory; it does not provide
  an external signature or independently attest the operator's narrative.
- Sanitized telemetry intentionally cannot distinguish the precise malformed frame behind a
  protocol violation, the exact internal cause of a provider error, or network failure from exo
  process failure.
- Attempts 002 and 003 have no terminal result or finalized telemetry document. Their checkpoint
  records are internally consistent but incomplete by construction.
- A staged red test is evidence of progress, not proof that the test is correct or that a production
  fix would pass. No sealed-oracle evaluation or ready-for-review evidence exists.
- This is one model deployment, one task, and one sampled trajectory. It cannot establish general
  GPT-OSS-120B quality or compare model sizes.
- No prompt, reasoning trace, provider body, source content, tool payload, endpoint, host identity,
  or verbose runtime log was used as publication evidence or reproduced here.
