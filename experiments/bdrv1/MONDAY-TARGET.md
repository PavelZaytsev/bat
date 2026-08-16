# Monday target: CorfuDB PR #4121

The supervised Monday direct-BDRv1 target is public
[CorfuDB PR #4121](https://github.com/CorfuDB/CorfuDB/pull/4121), authored by `annym`:
`fix: break snapshot-sync checkpoint-freeze starvation via busy signal…`.

This replaces the previously contemplated closed-source PR. CorfuDB is a real Java system used and
supported in the Broadcom product context, while the public repository, pull request, CI, review
discussion, and resulting candidate patch remain observable to the supervising evaluator. The run
can therefore produce useful fixes for Anny and high-quality evidence for improving BDRv1.

This document freezes the **kind of Monday work and its eventual target**. It is not the weekend
model-qualification fixture. Before touching this source with a model, prove on representative,
model-neutral Java repairs that GPT-OSS-120B or Gemma can use BDRv1, cross automatic context
maintenance, preserve state, and finish cleanly. Do not tune the runner, prompt, or methodology to
the five CorfuDB oracle findings during that qualification work.

## Frozen target snapshot

Recorded on 2026-08-15:

| Field | Value |
| --- | --- |
| PR | `CorfuDB/CorfuDB#4121` |
| State | open, non-draft, GitHub merge state `clean` |
| Author | `annym` |
| Base | `master` at `ccc3774ad87875655a6bb4726f5045eed4d68350` |
| Head | `fix/snapshot-sync-backpressure-and-freeze-lifecycle` at `2a5c02804b44e47b547882f97a9b449f7b02d26b` |
| Surface | 19 files, 1,937 additions, 44 deletions, 4 commits |
| Current checks | 10/10 reported GitHub checks successful at the recorded head |

The change spans snapshot-sync source and sink state machines, sender/receiver buffering,
checkpoint freeze lifecycle, apply-failure recovery, control-plane isolation, receiver-directed
retransmission, protobuf wire compatibility, unit tests, and integration tests.

## Run shape

Start from a history-clean synthetic root containing the exact PR-head tree, not from the base tree.
The Monday task is an independent BDR audit and repair of the already substantial PR, not a blind
reimplementation of its 1,937-line design. This better serves both goals: find concrete fixes that
can be returned to Anny and observe whether the model can form and test the right boundaries in a
real concurrent distributed-system change.

Candidate-visible inputs:

- the exact PR-head source tree at the frozen SHA, with no remote or upstream history;
- the original byte-frozen BDRv1 bundle and a fresh tracker;
- a task packet describing the intended snapshot-sync safety and liveness outcomes; and
- offline build/test dependencies and normal repository documentation.

Evaluator-only inputs until the candidate is irreversibly finished:

- the base-to-head diff and commit history;
- the PR page, review discussion, and known review findings;
- GitHub CI/check evidence;
- evaluator-authored adversarial tests and acceptance matrix; and
- any human-authored repair or expected patch.

Do not show the candidate the PR URL, discussion, reviewer comments, base diff, or known solution.
After snapshotting and hashing the completed candidate, compare its findings and repair against the
public review oracle and run evaluator-only checks in a separate copy. Never resume the candidate
after revealing evaluator evidence.

## Candidate-visible blinded task packet

Only the text inside the following block is delivered as the repository task. It intentionally
contains no PR number, commit identity, base-to-head diff, reviewed symbol, reviewer finding, or
suggested repair:

```text
Title: Audit and repair snapshot-sync resilience under slow and failing sinks

You are working on a history-clean snapshot of a Java distributed-systems repository.

The snapshot replication path has recently been extended to prevent restart storms,
checkpoint-freeze starvation, and permanent stalls when a sink is slow, stops acknowledging
progress, or fails during snapshot application.

Treat the current implementation as untrusted. Audit it end to end and repair every defect you can
prove.

Required behavior:

- A slow but genuinely progressing sink must not cause needless transfer cancellation.
- A truly stalled transfer or apply must have bounded, paced recovery behavior.
- Checkpoint freeze/unfreeze must remain safe and deterministic across completion, failure,
  cancellation, retry, leadership changes, and concurrency.
- Snapshot correctness must survive message loss, duplication, reordering, delayed delivery,
  retry, and overlapping attempt lifecycles.
- Recovery from an apply failure must not permanently wedge replication or allow stale work to
  interfere with newer work.
- Receiver feedback must improve retransmission without amplifying normal healthy traffic, and
  mixed-version peers must retain safe fallback behavior.
- Control-plane health probes must remain responsive while the data plane is slow.
- Cross-thread state used for liveness or safety decisions must obey the Java Memory Model.

Work from evidence. Read the relevant source and existing tests, identify the actual ownership and
concurrency boundaries, write focused regression tests, implement the smallest coherent repairs,
and run the strongest offline test suite available.

Do not merely describe possible defects. Leave a compiling patch, regression tests, and a concise
evidence record showing what failed before the repair and what passes after it. Preserve wire
compatibility and avoid unrelated refactoring or new dependencies.
```

The task packet is kept separate from the build notes and evaluator material below. The candidate
may discover source and test roots normally from the repository, but it is never given evaluator
test names, acceptance cases, review links, or a map from requirements to existing defects.

## Existing evaluator oracle

The public review at the frozen snapshot identifies five high-value boundaries. Keep their details
out of the candidate task packet:

1. receiver-directed retransmission may turn every acknowledgement into a full-window resend;
2. apply-failure cleanup may race the pre-existing auto-resume path against a fresh attempt;
3. liveness reads introduce Java Memory Model visibility requirements for receive/apply phase state;
4. apply failure may leave checkpoint unfreeze timing dependent on the exact throwing line; and
5. sequence-number-only buffering may mix stale data across snapshot attempts.

These are evaluator seeds, not an exhaustive answer key. Credit independently discovered additional
issues, and reject a patch that merely silences a symptom without repairing the responsible shared
representation or temporal/ownership boundary.

## Evaluator-only acceptance matrix

This section and every hidden test derived from it remain outside the candidate container until the
candidate result is irreversibly frozen and hashed. Evaluation happens in a separate copy. A stale
attempt writing or committing into a newer attempt, a permanent replication wedge, or a missed,
duplicated, or stale checkpoint-unfreeze callback is a fatal result even if the public suite passes.

| Boundary | Evaluator-only acceptance check |
| --- | --- |
| Healthy-ack retransmission amplification | Queue several snapshot entries. An advancing acknowledgement whose reported next sequence is simply normal progress must not expedite or resend the remaining window. A repeated non-advancing acknowledgement may expedite the actually missing entry, but not every later entry. An acknowledgement from an older peer without the optional field retains legacy cadence. |
| Failed-apply auto-resume race | Inject an apply failure and issue several immediate metadata polls. They must not restart the same doomed apply on every poll. A fresh snapshot start must be accepted and fenced from every delayed retry of the failed attempt. The stale attempt must never mark itself applied, drop the fresh start, or displace newer state. |
| Java Memory Model visibility | Cross-thread reads of receive state and snapshot-writer phase must have a provable happens-before relationship. If the existing fields remain, reflection verifies that both are `volatile`; an alternative atomic representation must eliminate the direct unsynchronized reads rather than merely adding timing. |
| Deterministic unfreeze after apply failure | Inject failures before and after transition to apply phase. The end callback occurs promptly and exactly once for the abandoned attempt, without waiting for a transfer-idle timer or a later attempt. A latch-controlled race must forbid the callback order `start(A), start(B), end(A)`. |
| Cross-attempt snapshot isolation | Start attempt B with the same topology and snapshot timestamp as A but a different request identity. Deliver an in-flight A data message after B starts, then advance B through the colliding sequence. Neither stale A data nor a stale A end marker may enter B's buffer/writer or trigger B's apply. Legitimate out-of-order B traffic must still drain correctly. |
| Regression and compatibility | Existing focused infrastructure tests, the full public unit suite, and `LogReplicationIT` remain green. Generated protobuf behavior remains wire-compatible with an older peer, no new dependency is introduced, and reverting the substantive repair makes at least one focused or hidden regression test fail. |

Suggested evaluator test roots, never copied into the candidate snapshot:

- `infrastructure/src/test/java/org/corfudb/infrastructure/logreplication/replication/send/`
- `infrastructure/src/test/java/org/corfudb/infrastructure/logreplication/replication/receive/`
- `infrastructure/src/test/java/org/corfudb/infrastructure/`
- `test/src/test/java/org/corfudb/integration/`

## Focused source and public test roots

Primary implementation surface:

- `infrastructure/src/main/java/org/corfudb/infrastructure/LogReplicationServer.java`
- `infrastructure/src/main/java/org/corfudb/infrastructure/logreplication/`
- `runtime/proto/service/log_replication.proto`

Focused public tests:

- `infrastructure/src/test/java/org/corfudb/infrastructure/LogReplicationServerTest.java`
- `infrastructure/src/test/java/org/corfudb/infrastructure/logreplication/replication/fsm/`
- `infrastructure/src/test/java/org/corfudb/infrastructure/logreplication/replication/send/SnapshotSenderTest.java`
- `test/src/test/java/org/corfudb/integration/LogReplicationIT.java`
- `test/src/test/java/org/corfudb/integration/SourceForwardingDataSender.java`

The candidate can run the existing focused unit tests offline with:

```bash
./mvnw -o -B -V \
  -pl :infrastructure -am \
  -Dtest='SnapshotSenderTest,LogReplicationServerTest,InSnapshotSyncStateTest,WaitSnapshotApplyStateTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.javadoc.skip=true \
  -T 2 test
```

After candidate freeze, the evaluator copies its hidden tests only into the separate evaluator copy
and uses the same module command with its private test selector. Evaluator test filenames and output
are not added to the candidate transcript or tracker.

Full public unit-test parity:

```bash
./mvnw -o clean test -pl '!samples' -am \
  -Dmaven.javadoc.skip=true -T 1C
```

Integration-test parity:

```bash
./mvnw -o install -DskipTests=true \
  -Dmaven.javadoc.skip=true -B -V -T 1C -q

./mvnw -o -pl :test clean verify -Pit -DskipTests
```

Use the bounded `-T 2` command for ordinary model iterations. Reserve the CI-shaped unit and
integration commands for evidence checkpoints and final evaluation; at the recorded head, the
public unit and integration jobs took approximately 22 and 61 minutes respectively.

## Offline JDK 25 and Maven image/cache contract

- Compile and test with Java 25. The root POM sets Maven compiler source and target to `25`.
- Use the repository's Maven wrapper `3.9.6`; preserve both `~/.m2/repository` and
  `~/.m2/wrapper/` in the prepared image.
- Build the Monday qualification image for Linux `amd64`. Maven resolves architecture-specific
  Protobuf, gRPC, and Netty artifacts, so an Apple Silicon/ARM cache is not interchangeable with the
  exo qualification cache.
- Match public CI with Ubuntu 22.04 plus Amazon Corretto 25, or use the repository's product base
  `eclipse-temurin:25-jdk-noble`. Pin the final image digest and record `java -version`, wrapper
  version, OS, and architecture in the run manifest.
- Include only ordinary build tools: Git, Bash, CA certificates, curl/unzip, coreutils/findutils,
  and process utilities. Do not expose a Docker socket, provider credential, review artifact, or
  hidden evaluator path to the tool container.
- The exact dependency surface includes Protobuf `3.25.8`, gRPC code generation `1.80.0`, Netty
  tcnative `2.0.77.Final`, and Lombok `1.18.46`. Warm native classifiers for the exact qualification
  architecture.
- Give each run a private disposable writable Maven cache cloned from an immutable seed. Never let
  candidates share or mutate the seed cache, and reject candidate-added dependencies that were not
  present during preflight.
- Budget at least 16 GiB RAM and 10--20 GiB writable disk for the Maven cache, generated sources,
  compiled modules, test reports, and candidate evidence.

Prepare the immutable dependency seed once with network enabled against the exact frozen tree:

```bash
./mvnw -B -V -DskipTests=true \
  -Dmaven.javadoc.skip=true -Dcheckstyle.skip -T 2 install

./mvnw -B -V -Pit -DskipTests=true \
  -Dmaven.javadoc.skip=true -Dcheckstyle.skip -T 2 test-compile
```

Then run the focused, full-unit, and integration commands above with `-o` before accepting the image
for Monday. Once preflight succeeds, create the candidate container with networking disabled. The
live inference endpoint remains outside that container; only the protocol runner talks to it.

## Supervision and handoff

Use the protocol-v2 runner and the exact-deployment gate in `MONDAY-EXO-RUNBOOK.md`. Preserve live
continuation, tool, retry, usage, timing, tracker, source/test, and evaluator evidence. The desired
handoff is:

- one self-contained candidate commit or a precise blocked result;
- an honest BDR tracker and score;
- independently reproducible baseline, targeted, full-suite, and counterfactual evidence;
- a redacted experiment result for BAT issue #34; and
- a reviewable CorfuDB patch/finding report for Anny.

No upstream CorfuDB branch, PR, review, or comment is modified without a separate explicit human
decision after the supervised result is evaluated.
