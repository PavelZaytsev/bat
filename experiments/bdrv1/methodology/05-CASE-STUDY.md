# Case study — vcf/datastore PR #592, the CDC value cache

The only codebase BDR has been run on. Everything in the method traces to something here, so this
file is the evidence and the honest limits in one place.

## Shape of the work

| | |
|---|---|
| Diff under review | 48 files, ~6,600 insertions |
| Findings filed | 29 |
| Fixed | 15 |
| Split (parent closed, remainder filed) | 6 |
| Superseded (premise disproved) | 1 |
| Slices closed | 0, 1, 2, 3, 4, 7, 8, 9, 10 |
| Slices live and pending | 11 |
| Slices superseded | 5 (never a boundary), 6 (same K as 9) |

**Slices grew 7 → 11 while findings grew 19 → 29.** That ratio is the method's central claim: new
findings mostly land *inside* existing boundaries, so the slice count grows slowly while the issue
count grows fast. Proportional growth would mean the boundary abstraction is not earning its keep.

## The single most persuasive artifact

Slice 1. Four issues that read as four unrelated bugs —

| finding | K (needs to know) | I (infers from) |
|---|---|---|
| use-after-free | do I own this refcount? | whether it is still in the cache |
| untrustworthy gauge | how many did I release? | the size of the collection |
| permanent leak | did this release succeed? | whether an exception was thrown |
| muted detector | was that failure real? | *never asked* |

— reduce to one sentence: **"the refcount ledger is maintained by inference rather than
representation."** One record made all four impossible.

## What the method caught that reading did not

**Nine of twenty-nine findings came from DOING a slice, not from reviewing.** That asymmetry is the
actual claim, and it is what the component's own author noticed:

> *"I am reading the issues you found... they are good finds! Just curious, how did you find them?
> My AI completely missed these bugs when I pushed it out for review."*

Examples that a reading pass cannot reach:

- **Netty's arena metrics are chunk-granular.** Found by building an admission bound on them and
  watching six tests fail in one build. Measured: a 100-byte allocation reports **4,194,304 bytes**,
  and the figure does not decrement on release. An entire memory bound had been designed on it.
- **A previous fix that could never fire.** Slice 7 deleted a `maxCandidates` cap added as an
  earlier mitigation for the very finding slice 7 owned. Its javadoc described the defect
  *correctly* — and a skipped candidate never reaches the planner, so the counter it checked never
  advanced on exactly the run it was written for. Proven by replacing the branch with a throw: only
  the planner's own unit test failed. **`dormant` in the tracker's precise sense** — reads as done
  in every summary, changes nothing at runtime.
- **A finding whose premise was false.** #885 claimed zero-copy delivery depended on gRPC copying
  during `onNext`. Measured: protobuf 4.34.1's `unsafeWrap` **aliases heap** ByteBuffers and
  **copies direct** ones, so the proto owns private heap bytes and there is no lifetime dependency
  at all. The real finding is the inverse — the zero-copy hot path copies every row direct→heap —
  filed separately. Measuring the assumption before designing on it is the only reason a
  representation was not built for a defect that does not exist.

## Foreign facts, measured

Every third-party assumption made during this work was **wrong**. This is why the method treats a
foreign-API assumption as an unrepresented fact rather than a diligence problem.

| symbol | assumed | measured |
|---|---|---|
| `OutOfDirectMemoryError` | constructible; distinguished from OOM | constructor is package-private; production does not distinguish it |
| `PooledByteBufAllocator` | does not recycle `ByteBuf` instances | it does — silently corrupted an identity-based ledger |
| `ByteBuf.equals` | identity | **content** comparison; every leaked buffer reported as `#0` |
| `PoolArenaMetric.numActiveBytes` | byte-accurate | chunk-granular: 4 MiB for 100 bytes, no decrement on release |
| `unsafeWrap(ByteBuffer)` | zero-copy for direct buffers | aliases **heap**, **copies direct** |
| `nioBuffer()` on a released buffer | does not check refCnt | it **does** — throws `IllegalReferenceCountException` |
| `Arena` + `MemorySegment.scope()` | enforceable ownership | **confirmed** — access after close throws |
| `Arena` per *scan* | can enforce a callback borrow | **cannot** — the escape happens while the arena is open |
| `Arena` byte budget | might express admission | **no such API** — capacity is a different structure |
| RocksDB value pointer | valid after the iterator moves | **not** valid — forces measure-then-copy |

## The value/temporal split — the method's biggest single finding

Classifying every finding's K produced a clean result:

- **All 15 findings that closed cleanly were VALUE facts** — ownership, release outcomes, a
  retention floor, a scan budget, sentinel state, index authority.
- **All 9 survivors read as TEMPORAL** — lifetime, lock scope, pinned duration, scheduling,
  liveness, the instant a reading was taken, the staleness of a mirror.

Then eight of those nine **dissolved into ownership structures**, and two dissolutions overturned
conclusions already written down as unavoidable:

- *"admission depends on live memory, so a time-of-check/time-of-use gap is inherent"* — false.
  Hold a **reservation**; the gap cannot exist because capacity is owned rather than observed.
- *"the outstanding-buffer ledger is fed by discipline, so it can always drift"* — false. Make it a
  **projection** of the owner set and it cannot disagree with itself.

Slice 4 is the clearest evidence: it represented two temporal facts, and only one held. A chained
`CompletableFuture` (a **completion** value) left no residual. A byte ledger (a **mirror**) spawned
two follow-up findings. **Naming an instant worked; shadowing a live value did not.**

## The ownership lens, applied — the part that actually tests it

Two slices were run *after* the value/temporal split was discovered, specifically to test whether
dissolving a temporal K into an ownership structure works. Both passed the pre-registered criterion,
and the second one is the interesting result.

**Slice 9 — a BORROW.** "How long do these bytes live?" A visitor was handed segments documented as
valid for one callback while the arena backing them spanned the whole scan, so an escaped segment
stayed readable and — worse — **writable** across later visits. The fix was not to represent a
window but to make the arena's lifetime *be* the window: one confined arena per callback, closed on
return. The platform then enforces it.

The design came from measurement, not intuition. An arena per *scan* was measured **not** to catch a
callback escape (the arena is still open when the escape happens — exactly where the defect lives);
an arena per *callback* was measured to catch it. "Just use the arena we already have" would have
shipped a borrow the platform does not check.

**Slice 10 — a COMPLETION.** "Does the recovery I rely on ever actually run?" The answer was the
opposite of what the finding claimed: it ran *far too often*. The rebuild returned `void`, so a
caller could not distinguish "not attempted" from "attempted and cannot succeed" — and an
unachievable rebuild was re-attempted on **every read**, each a full column-family scan under a
lock. A liveness fix had quietly become an unbounded-work defect.

The dissolution: a completion value (`Completed | StoreEmpty | Failed`, where **completing is not
succeeding**) plus a generation counter, so **one authority-loss owns one repair attempt**. Three
cases became distinct where one had been: restoring authority needs no guard, completing *without*
authority must not retry, and *throwing* must retry because the scan never ran. Collapsing them
either repeats useless work or abandons recoverable work.

### Criterion 2 held, including where it should have broken

> **A dissolved K is testable without time** — no `sleep`, no thread, no clock.

Neither slice's tests use any of the three. Slice 10 is the real evidence: **its subject IS
scheduling.** If any slice needed a clock it was that one, and instead *"when may this run"* became
*"which generation has already been attempted"* — a value comparison. That is the clearest signal so
far that the lens converts time into structure rather than merely relabelling it.

### And the lens disproved a finding

A latent-corruption finding claimed zero-copy delivery depended on gRPC copying during `onNext`.
Measuring the assumption rather than designing on it showed `unsafeWrap` **copies** direct buffers,
so the proto owns private heap bytes and there is no lifetime dependency at all. The foreign fact
moved to `eliminated` — the best of the four dispositions, because measuring did not verify the
assumption, it removed the dependency. Had it been designed on instead, a representation would have
been built for a defect that does not exist.

## What went wrong, kept deliberately

Removing these would make the method look better and be less useful.

- **Two slices closed with an unfalsifiable collapse claim.** Their predictions were never derived
  from the findings, and they are left failing the validator on purpose. Backfilling them after
  seeing what died would be rewriting the experiment to match the answer.
- **A validator reported green while two findings had been re-assigned in prose and never moved.**
  Its rule accepted "there is a note explaining why" — and the note *was* the deferral. The
  evidence of the unfinished transfer was accepted as the excuse for it.
- **A finding was routed to a slice because it shared an unseamed REGION with that slice's
  findings.** Region is exactly what step 1 forbids grouping by. It took reading the code to move it.
- **A slice's boundary statement named two things and closed one.** Twice — slices 7 and 9. Hence
  step 5: read your own boundary statement for the word "and".
- **A recovery path was added on a read path without going through design**, because it felt
  obligatory. The tracker note literally read *"required, not optional"*. That is how ROUTE's
  "you are inventing" rule gets skipped, and it is why the loop now accounts for what it ADDS.
- **The foreign-facts ledger was built and then missed the most dangerous assumption in the
  codebase for a full day.** The mechanism worked; the gate was not run. Hence: seed the ledger
  once from every existing finding, do not only add as you go.

## Validation criteria, agreed in advance

| criterion | result |
|---|---|
| One representational fix closes multiple findings structurally | ✅ four findings, one record |
| Resulting tests are value-level — no mocks, I/O or threads | ✅ 19 cases in 1.0s, zero mocks |
| Slice count stays stable while finding count grows | ✅ findings 19→29, live slices 7→10 |
| A slice reviews cleanly as one self-contained commit | 🔶 commits clean, the PR spans many slices |
| The tracker's own defects are findable by the method | ✅ four, all fitting the same K-shape |
| A slice can delete a previous, failed attempt at its own finding | ✅ slice 7 |
| Two findings grouped by K turn out to share a CAUSE | ✅ slice 8 |
| A dissolved temporal K is testable **without time** | ✅ slices 9 AND 10 — including slice 10, whose subject is scheduling |
| A temporal K dissolves into one of the eight structures | ✅ borrow (slice 9), completion (slice 10); none needed a ninth |
| The lens can disprove a finding, not just fix one | ✅ one latent-corruption premise did not survive measurement |

**Still open:** run this on a second, smaller codebase in a different domain. Every example here is
concurrent off-heap memory management in Java, and the examples may have shaped the guardrails more
than is obvious.
