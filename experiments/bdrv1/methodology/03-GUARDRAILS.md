# Guardrails, each with the instance that paid for it

*Every rule below came from the loop failing during execution. A rule without its concrete
instance decays into folklore, cannot be checked, and gets cargo-culted into situations it
was never about — so the instance is part of the rule.*

The approach is named **boundary-driven refactoring** (Pavel's choice, 2026-07-29); the unit of
work is a **slice**. Use these terms consistently.

How to actually work a slice, given the unit of work is a *composition boundary* rather than a
file or a bug. Derived with Pavel 2026-07-29; applies to every slice in
[[project-pr592-slice-plan]] and is core content for the eventual skill
([[project-slice-review-skill-intent]]).

## Lineage — cite this, don't claim novelty

When explaining it, lead with the borrowed parts; they make it placeable rather than invented:

| Piece | Established as | Source |
|---|---|---|
| Slice 0 — injection points for effects | Seams | Feathers, *Working Effectively with Legacy Code* |
| Pure planner + effectful executor | Functional core, imperative shell | Gary Bernhardt, "Boundaries" (2012) — closest ancestor |
| REPRESENT phase | Make illegal states unrepresentable | Yaron Minsky |
| Typed rejection over null/inference | Parse, don't validate | Alexis King |
| Slice DAG, leaves-first, 0 unblocks all | Mikado Method | Brolund & Ellnestam |
| "Untestable code is a design signal" | Listen to the tests / design pressure | Freeman & Pryce, *GOOS* |
| One boundary all the way to done | Vertical slicing | agile practice, borrowed from feature work |

**Genuinely novel (as far as we know), and worth framing as "how we keep ourselves honest" rather
than as the headline:** (1) the *collapse claim* as falsification — predict before starting which
defensive code must die, and treat "nothing died" as evidence the representation is wrong;
(2) the *FALSIFY* phase — verifying siblings became unreachable rather than patched, with
mis-assignment as an explicit outcome. Supporting signal: findings grew 7 → 19 while slices grew
6 → 7; proportional slice growth would mean the boundary abstraction is not earning its keep.

Most persuasive single artifact when explaining it: Slice 1 — four issues that read as unrelated
bugs (use-after-free #879, permanent leak #883, untrustworthy gauge #886, muted detector #878)
reduce to one sentence, *"the refcount ledger is maintained by inference rather than
representation."*

**Why not plain TDD:** at a broken boundary the thing you would assert on does not exist yet as a
value. You cannot write `assertThat(ownership)` before ownership is representable. So the loop is
**representation-first**, not test-first.

## The 6 phases

1. **EXPOSE** — write the test that demonstrates the boundary defect against current code. It must
   fail **at its assertion**. A test that fails in `setUp`, on a mock, or on a precondition has
   exposed nothing — and if you then "fix" the defect and it goes green, the green came from the
   setup error disappearing, not from the fix. (Observed: a test failed because `EventStore` is
   `sealed`, never reaching the assertion.) Equally, a *green* EXPOSE test means the experiment is
   wrong, not that the code is fine — check what it actually exercised. (Observed: evicting an
   entry *before* the drain instead of *during* it, so the code correctly re-inserted and skipped
   it; the test proved the happy path works.) Purpose is not the fix; it is proving the boundary is
   *observable*. If you cannot write the test without a live RocksDB/Netty/thread-race, that is the
   first finding: the boundary has no seam. Fix that before continuing.
2. **REPRESENT** — introduce the value that carries the assumption (`ReleaseOutcome`,
   `PopulatePlan`, `Ownership`, a cause enum). **Pure addition — no behaviour change, all existing
   tests still pass.** Do not fix anything in this phase.
3. **ROUTE** — make producers return the value and consumers accept it. The original defect's fix
   becomes mechanical, because the information now exists where the decision is made.
4. **COLLAPSE** — delete the defensive code the representation made redundant: guards, try/catch
   cleanup, retry bounds, re-derivation. **This phase falsifies the design.** If nothing became
   dead, the representation is wrong or unnecessary — go back to 2. This is the phase people skip
   and it is where the payoff lives.
5. **SATURATE** — now that decisions are values, test them densely and cheaply: boundaries,
   off-by-ones, precedence, every exception path. No mocks, no I/O, no threads. If a test still
   needs a mock, the effect was not hoisted far enough.
6. **FALSIFY** — re-read every *other* finding assigned to this slice and confirm each is now
   **unreachable**, not separately patched. This tests the slice's central claim (that N findings
   share one cause).

   For each sibling ask: **is there a test that would fail if this fix were reverted?** "I read it
   and it looks handled" is not a verdict. (Observed: #878 was believed fixed but had no test for
   the retain path at all — it was asserted, not tested.)

   Sibling disposition — the crude rule "needs its own fix → split it out" is wrong, because a
   sibling can need another *code site* while needing the *same representation*:
   - **Same fix, another site → extend this slice.** (#883 lived in a different class but was the
     identical shape; applying the same value type there was ~15 lines. Splitting would have been
     bureaucracy.)
   - **Different fix → split into its own issue**, even if it is in the same file. (#886's filed
     defect was fixed, but the residual — the ledger is still fed by caller discipline — needed a
     different design. Quietly widening the slice to cover it would have made "done"
     unfalsifiable.)
   - **A partially-fixed sibling must be split, never silently narrowed.** Close what is actually
     fixed; file the remainder with the evidence. "The ledger no longer lies" is a weaker claim
     than "the ledger cannot be wrong" — say which one you bought.
   - A sibling that is *almost* dead → representation incomplete, return to phase 2.

Phases 4 and 6 are the non-obvious ones and the only ones that can invalidate the work. Do not
drop them.

## Guardrail: the EXPOSE test may stop compiling against its own premise

A good structural fix can **delete the failure mode the test was pinned to**. This is expected, not
an anomaly — but it destroys the red-green evidence.

Observed in slice 1: EXPOSE pinned "the drain rethrows after cleanup". Making release total meant
the drain no longer throws at all, so the test failed with *"Expecting code to raise a throwable"* —
green on the property under test, red on an obsolete precondition.

**Rule: if ROUTE forces you to change the EXPOSE test, the changed test does not count as green
until you re-run it against the pre-fix code and watch it go red.** Otherwise you rewrote the
experiment to match the answer, and the fix gets credit for a defect it may not address. Restoring
one line (the defective predicate) and re-running is usually enough; do it before claiming the
phase.

Corollary: prefer EXPOSE assertions on the **property** (a reader's buffer stays alive) over the
**mechanism** (an exception is thrown). Property assertions survive the fix; mechanism assertions
are hostages to it.

## Guardrail: ROUTE fact-checks the findings — expect to correct your own issues

ROUTE is the first phase that forces you to read **every** call site rather than the one that
caught your eye during review. That is when findings written from a reading pass get validated or
refuted.

Observed in slice 1: issue #878 asserted that swallowing `IllegalReferenceCountException` in
`releaseRetains` was "defensible, because losing a race against the eviction listener is
legitimate". Migrating all five call sites showed it was not — every caller arrives having
*successfully retained*, so it owns a refcount the listener cannot take. Every throw there is a
genuine double-release. The filed rationale was wrong, and the fix is simpler and stronger than
the issue described.

**Rule: when ROUTE contradicts a filed finding, update the issue before FALSIFY.** FALSIFY checks
each sibling against what the issue claims; if the claim is stale, FALSIFY validates against the
wrong standard and passes something it should have caught.

## Guardrail: check `blast_radius` during ROUTE, not just when writing the slice

A fix that satisfies one finding can violate another's invariant. Slice 1 nearly fed retain
releases into `byteBufReleased`, which is balanced against `allocateDirect` and never sees eager
retains or `retainedSlice` parents — that would have fixed #878's silence by driving #886's gauge
negative. Two record methods with explicit names (`recordAllocationRelease` /
`recordRetainRelease`) made the distinction hard to get wrong.

Read the `blast_radius` field again before wiring consumers, not only when planning.

## Smell: an API that exists to REPORT something means the something is not represented

`recordReleaseError()` was a public channel for "tell the cache an error happened". Once release
outcomes became values, the outcome already carried the failure and the channel had nothing left
to carry — it died with zero callers, unpredicted.

Generalise: a `reportX` / `recordX` / `notifyX` method whose argument is a *fact the caller
observed* is usually a missing return value. Treat it as a candidate boundary when scanning for
slices.

## Guardrail: a foreign-API assumption is an UNREPRESENTED FACT, not a diligence failure

**Reframed 2026-07-31 after Pavel pushed on it.** The old version of this rule said "never assume,
measure it". That is advice, and advice fires only when you already remembered — the same weakness
as a validator whose escape hatch is "explain yourself" (see the FALSIFY-disposition guardrail).
Proof it is insufficient: this session followed the loop carefully and still shipped a ROUTE design
that depended on RocksDB iterator view-stability across `seek()`, unquestioned, where a wrong answer
means delivering a transaction with tables missing.

The correct framing is that it is this method's own defect shape pointed outward:

> At `<site>`, the code needs to know **K** — what this call actually returns and means — and
> instead infers K from **the method name, or from what I expected**.

`numActiveBytes` sounds byte-accurate and is chunk-granular. `ByteBuf.equals` sounds like identity
and is content. An iterator sounds like it holds still. That is `getIfPresent(lsn) != entry`
standing in for ownership, in someone else's library. So it gets **represented**, in
`slices_progress.yaml`'s `foreign_facts` ledger, and enforced by R12/R13 in `slices.py`.

**Disposition, in preference order — and the ordering is the lesson:**

    eliminated  >  measured  >  documented  >  assumed (blocks)

**Eliminating beats measuring.** A measurement is a point-in-time act that decays silently on the
next dependency upgrade; a checked invariant travels with the code. Faced with "does a RocksDB
iterator hold still across a seek?" — documented yes, unmeasurable without a live DB, wrong answer
means silent corruption — the right move was NOT to measure it. It was to compare the two passes'
row counts and throw on mismatch, so the code stops caring.

**The gate: ask at EVERY phase boundary "what did this phase newly assume about code I do not
own?"** This file used to file the rule under REPRESENT ("you cannot represent a value whose meaning
you do not know"), which is true and insufficient — the miss happened in ROUTE. R12/R13 check the
entries that exist; nothing detects a dependency never written down, so the question is asked aloud.

## Guardrail: never assume a third-party API's return semantics — MEASURE IT

Pavel, 2026-07-29: *"Assuming and not knowing the explicit value returned is how the issues show
up. Do not assume the return type when dealing with third party APIs."*

Every third-party-API assumption made this session was wrong:

- `io.netty.util.internal.OutOfDirectMemoryError` — assumed constructible; its constructor is
  package-private. Also assumed production distinguished it from `OutOfMemoryError`; it does not.
- `PooledByteBufAllocator` — assumed it does not recycle `ByteBuf` *instances*. It does, which
  silently corrupted an identity-based leak ledger.
- `ByteBuf.equals` — assumed identity. It is a **content** comparison, so every leaked buffer
  reported as `#0`.
- `EventStore` — assumed mockable. It is `sealed`.
- `PoolArenaMetric.numActiveBytes()` / `usedDirectMemory()` — assumed byte-accurate. Both are
  **chunk-granular**: a 100-byte allocation reports 4 194 304, and it does not decrement on
  release. Built an entire admission bound on this before six tests caught it.

**Rule: before depending on what a third-party call returns, write a throwaway test that prints
or asserts the actual value, and read the number.** A failing assertion with the real value in the
message costs one build. Assuming costs a design.

This applies to *semantics*, not just types: granularity, units, whether a counter decrements,
whether identity is preserved, what "empty" means. The compiler checks the type and nothing checks
the meaning.

**If you cannot measure it, read the API documentation. Never infer semantics from a method name.**
`numActiveBytes` sounds byte-accurate and is chunk-granular; `usedDirectMemory` sounds like usage
and is reservation.

**Why this belongs to REPRESENT specifically.** You cannot represent a value whose meaning you do
not know — you will encode the meaning you assumed, and the type will look correct while carrying
the wrong thing. `committedBytes()` was a well-formed representation of a number I had
misunderstood: right shape, wrong semantics, and no test of the type itself could have caught it.
Explicit beats implicit, and a value's *meaning* is the part that has to be explicit first.

## Attack the blocker. Deferral compounds; work does not.

Pavel, 2026-07-29: *"We should not be scared of challenges and attack the issue that blocks the
largest number of issues in the slice."*

**When several findings share a blocker, that blocker is the work.** Not the easy findings around
it, not the ones with obvious fixes. Rank by how many things a fix releases, and start there.

Slice 4 is the case study, and it is embarrassing in a useful way:

1. #880 needed a byte-accurate committed-memory figure.
2. Netty could not supply it. I filed **#899** as a blocker, wrote its own fix direction 1 —
   *"byte ledger at the choke point; ReleaseOutcome would need to carry bytes"* — and deferred it
   as "different work with its own design decision".
3. Two findings went to `blocked`, and I spent a full exchange arguing whether `blocked` was an
   honest label.
4. Pavel said attack it. **It was three edits and one record component.** The answer had been
   written down, by me, in the issue I filed to avoid doing it.

**The deferral cost more than the work.** It also produced a `blocked` status I then had to defend,
a tracker entry, and an argument about vocabulary — all overhead generated by not doing twenty
minutes of work.

**Rule: `blocked` is a claim that must be tested before it is used.** Before applying it, ask *"have
I actually tried, or am I estimating?"* A blocker you have not attempted is not blocked, it is
avoided. Legitimate uses are narrow: the fix requires changing a contract you do not own
(slice 7's `populateForCache` arena lifetime), or it needs a decision only someone else can make.
"It looks like a lot of work" is not on that list.

**Why fear compounds specifically.** Every deferral leaves a partial. Partials accumulate into a
plan where nothing is finished and everything is nearly done, and at that point you cannot tell
which remainders are real blockers and which are avoidance — because they all wear the same label.
That is the state Pavel was right to worry about, and the defence is not better bookkeeping, it is
doing the blocking work first so the label is rarely needed.

## Status vocabulary — `partial` is banned, it hides four different situations

`partial` conflated states that need opposite responses. Use these instead:

| status | meaning | may a slice close with it? |
|---|---|---|
| `fixed` | defect unreachable, with a test that fails if reverted | yes |
| `split` | part fixed; remainder filed under its own ID with its own fix | **yes** — the parent is done, the remainder is tracked |
| `blocked` | fix designed and implemented, cannot take effect until a *named* dependency lands | yes, if the dependency is filed and the block is stated |
| `dormant` | fix implemented but **not wired** — present in the tree, doing nothing | **NO. This is the trap.** |
| `open` | not started | no |

**`dormant` is the dangerous one.** It reads as done in every summary, passes every test it has, and
changes nothing at runtime. A validator nobody calls, a bound that always returns UNKNOWN, a policy
no caller consults. Treat it as `open` for the purpose of closing a slice.

**Rule: a slice may close with `split` and `blocked` items. It may not close with `dormant` or
`open` ones** — those are the self-inflicted deferrals the foreign-contract test already forbids,
and the vocabulary now makes them impossible to mislabel.

Corollary: when a probe result contradicts an assumption, **record the measured numbers in the
javadoc at the point of use**, so the next person does not re-derive it. See
`PooledDirectBufferAllocator.committedBytes()`.

## Phase 0 of the meta-loop: when the process surprises you, AUGMENT THE PROCESS

**The loop is expected to be wrong, and fixing it is part of the work.** Every guardrail below
came from the loop failing during execution. Do not treat a surprise as a one-off to route around.

**Triggers — stop and consider a guardrail when:**
- a test fails for a reason you did not predict (including passing when it should fail);
- a phase completes cleanly and the defect is still reachable;
- a prediction is refuted (a collapse claim, a severity, a finding's stated mechanism);
- you correct something you previously filed or asserted;
- you make the same *class* of mistake twice.

**Then ask, in order:**
1. Is this domain or process? A surprise revealing a new **boundary** produces a new slice; a
   surprise revealing a **blind spot** produces a guardrail. Slice 2's ROUTE wall produced both —
   slice 7 *and* the foreign-contract rule. Conflating them means the plan absorbs process problems
   or the process absorbs domain problems.
2. **Which phase should have caught it, and why didn't it?** A guardrail with no home phase is
   usually a restatement of "be careful" and should not be written.
3. Would the rule have **changed a decision** I actually made? If not, discard it.

**Write it immediately, while the specifics are fresh, and cite the concrete instance.** Every rule
here names what was observed ("Slice 3: ROUTE improvised `resolveRetentionFloor`..."). A rule
without its instance decays into folklore, cannot be checked, and gets cargo-culted into situations
it was never about. Writing them up at the end of a session loses the detail that makes them usable.

**Pruning is part of it.** This file is a working instrument, not a changelog. A rule that has never
fired after several slices is noise — delete it. A rule that keeps firing suggests the phase it
guards is under-specified and should be redesigned rather than patched again.

**Health signal:** the rate of new guardrails should decay across slices. Slices 0-3 produced nine;
if slices 4-7 produce nine more, the loop is under-specified rather than merely young, and the
right response is to rethink a phase rather than add rule ten.

## Guardrail: a new abstraction invented during ROUTE means REPRESENT was incomplete

**This is the highest-value rule in the file. Both of slice 3's errors were instances of it.**

REPRESENT designs the value. ROUTE is supposed to be mechanical — thread it through. When ROUTE
finds itself *inventing* something (a helper, a resolution policy, a choice of which source to
read), that invention is undesigned: it never went through the phase whose job is getting the shape
right, and nothing downstream is built to check it. The collapse claim cannot catch it — the claim
is a list of **old** code that should die, and this is new code.

Slice 3: REPRESENT designed `RetentionFloor`. ROUTE improvised a `resolveRetentionFloor()` helper
that collapsed the index and tracker floors into one value. That improvisation discarded *which
source answered*, which was the fact the consumers needed, and #876 survived behind code that
looked correctly refactored. Separately, ROUTE improvised a choice to source the exception trailer
from the resolved floor, creating a fresh instance of #892 inside the #892 fix.

**Rule: when ROUTE reaches for a new abstraction, stop and return to REPRESENT.** Design it, state
what it must preserve, and add it to the collapse claim. An improvised helper written mid-wiring
gets none of that scrutiny.

Corollary — **a resolved value must not lose a distinction its consumers depend on.** "Combine the
sources" looks like tidying and can silently re-create the bug: `Known(50)` from the tracker and
`Known(50)` from the index are the same value and mean different things.

## Guardrail: COLLAPSE is not fix-verification — it and SATURATE ask different questions

- COLLAPSE asks **"did the old code die?"** — evidence the representation displaced what it should.
- SATURATE asks **"is the new code right?"** — evidence the defect is actually gone.

Passing the first tells you nothing about the second. Slice 3's COLLAPSE passed 4/4 while #876 was
still fully reachable, because the defect had moved into the new abstraction. A slice can look
perfectly collapsed and still be broken.

**A SATURATE failure loops back to REPRESENT or ROUTE, and may produce further collapses** — in
slice 3 it deleted the improvised helper. The phases are not strictly linear; ROUTE → COLLAPSE →
SATURATE iterate until stable, and FALSIFY is the exit.

## Guardrail: EXPOSE should enumerate the defect's input space, not just hit one point

EXPOSE produces one failing test at one input. That is enough to prove the boundary is observable,
but it is **not** a specification of the defect, and treating it as one is how a partial fix passes
ROUTE.

Slice 3's defect manifested across `index empty` × `tracker floor ∈ {unknown, below cursor, above
cursor}`. EXPOSE tested one of the three. The fix handled that one; the second was still broken
and only surfaced in SATURATE.

**Rule: when EXPOSE lands, write down the conditions under which the defect manifests, even the
ones you are not testing yet.** That list becomes SATURATE's checklist and stops COLLAPSE from
being mistaken for coverage. Cheap — it is a comment or a YAML field, not more tests.

## Guardrail: derive the collapse claim from the FINDINGS, not from your plan

REPRESENT has no natural completeness criterion. "Introduce the value that carries the assumption"
is singular, but a boundary usually needs several values, and nothing stops you introducing the
first one that makes the EXPOSE test pass and calling the phase done.

Observed in slice 2: issue #882 listed five representational changes. Two were built (pure planner,
fold instead of arrays), two were blocked by foreign contracts, and **one — move the throw out of
the mapping function; return a typed rejection instead of `null` — was simply not done.** COLLAPSE
could not catch the omission, because the collapse claim had been written from what I intended to
build rather than from what the finding said should change. Unbuilt items produce no surviving
code to notice.

**Rule: write the collapse claim by walking each kill-list issue's stated fix directions and asking
"what code dies if this lands?".** Then an unbuilt item shows up as an unmet prediction instead of
as silence. If a fix direction implies no code death, say so explicitly — that is information too.

## Guardrail: distinguish a foreign-contract partial from a self-inflicted one

A slice may legitimately finish with findings partially addressed **only** when the remainder is
blocked by a contract outside its boundary. A finding left partial because the work was not done is
not a deferral, it is an unfinished slice — and labelling it "done, partial" launders one into the
other.

Test: *could I finish this without changing any contract I do not own?* If yes, the slice is not
done; **return to REPRESENT** for the missing value. No new phase is needed — the loop already
routes this correctly, the failure was in calling REPRESENT complete too early.

Slice 2 got this wrong in both directions at once: #888/#889 were correctly deferred (they need
`populateForCache`'s contract), while #882's remaining items were incorrectly deferred (they need
nothing but the work).

## Guardrail: a collapse claim must be about a contract YOU own

Slice 2 predicted five collapses and two were refuted — both for the same reason. They were not
predictions about the representation at all; they were predictions about **someone else's
contract**:

- *"the ThreadLocal read-ahead side channel disappears"* — it exists because **Caffeine** forbids
  mutating the map from inside `computeIfAbsent`. No representation of ours removes that.
- *"off-heap allocation no longer happens under `cfLock`"* — it happens there because
  **`populateForCache`'s** `MemorySegment`s are confined to the single `visit` call. Deferring the
  copy loses the source bytes. Changing that is `RocksDbEventStore`'s boundary, not `populate`'s.

**Rule: before committing a collapse claim, ask whose contract makes that code necessary.** If the
answer is a library or another component, the item belongs to a different slice — one whose
boundary is that contract. Predicting it here guarantees a refutation that says nothing about the
work actually done.

**A refuted claim of this kind does NOT mean "return to REPRESENT".** That instruction is for a
representation that failed to displace code it was supposed to displace. Distinguish:

- *predicted-dead code is still alive because the representation did not reach it* → back to
  phase 2, the representation is wrong;
- *predicted-dead code is still alive because a foreign contract requires it* → the prediction was
  mis-scoped. Record the refutation, re-scope the item, and continue. The slice can still be sound.

Tell them apart by asking whether the surviving code is doing the *inference* the slice was meant
to remove. Surviving mechanism is fine; surviving inference is not.

## Guardrail: a FALSIFY disposition is not done until the OWNERSHIP moved

**Which phase should have caught it: FALSIFY.** It decides each sibling's disposition and records
it — and then nothing verifies the decision was carried out.

Observed 2026-07-31, two instances, both found only by auditing the tracker: slice 3's FALSIFY wrote
*"#890 DEFERRED to slice 7, predicted before slice 3 even started EXPOSE"*, and slice 2's wrote
*"#889 → slice 7"*. Both notes were accurate. Neither finding moved: both still recorded `slice: 3`
/ `slice: 2`, both still sat in a **done** slice's `kill_list`, and slice 7's `kill_list` was
`[900]`. Slice 7 would have run its own FALSIFY against one finding and declared the boundary
verified while two more findings on that exact boundary stayed live. Its real kill list is
`[900, 890, 889]` — three, not one, plus the #888/#882 remainders.

**Rule: writing the disposition and moving the finding are one step, not two.** "→ slice N" in a
note is a decision, not a transfer. The transfer is three edits (the finding's `slice:`, the old
slice's `kill_list`, the new slice's `kill_list`) and until all three land, the receiving slice does
not know it owns the work.

**Why the checker did not catch it, and the fix.** The validator's rule was *"a done slice may not
own an open finding **without a note explaining why**"*. The note was the deferral itself, so the
note satisfied the check — the evidence of the unfinished transfer was accepted as the excuse for
it. The rule is now the strict one this file already states: **a done slice may not own an `open` or
`dormant` finding at all**, note or no note. Transfer it or re-file the remainder under its own ID.

Generalises past this tracker: **when a validator's escape hatch is "explain yourself", the
explanation will be the thing you were supposed to act on.** Prefer a check that requires the state
to be right over one that requires the state to be narrated.

Corollary — *deliberately* unassigned and *accidentally* unassigned are indistinguishable in the
data and need opposite responses (#895 was a considered "own item, different representation"; #898
was simply undecided). So the reason has to be a field, not a vibe: `unassigned: <why>`, and no
reason means it is an orphan. Same move as every slice — the missing fact was *why* it has no owner.

## Guardrail: COLLAPSE evidence arrives early — record it when it appears

COLLAPSE is the checkpoint where you *tally* dead code, not the only place you notice it. Slice 1
turned up an unpredicted collapse during ROUTE: making release total shrank the drain's error path
from "reachable whenever a release fails" to "reachable only if the insert fails".

Track predicted and unpredicted collapses separately. Unpredicted ones are the strongest evidence
the representation was right; a slice with only predicted collapses is merely competent, one with
unpredicted collapses found a real boundary.

## Meta-properties every slice carries (checkable, not decorative)

Paste and fill per slice:

```
Boundary:        <who holds what assumption about whom>
Kill list:       <issue numbers> — each must die structurally, not by patch
Representation:  <the value being introduced>
Collapse claim:  <specific code that must become dead>   <-- falsifies the design
  predicted:     <written BEFORE starting>
  unpredicted:   <found during the slice — the strongest signal the boundary was real>
Purity claim:    <what becomes testable with no mocks/IO/threads>
Done bar:        <falsifiable statement>
Blast radius:    <other slices whose assumptions this changes>
Expose re-check: <did the EXPOSE test change after ROUTE? if so, re-verified red against pre-fix code?>
```

## Worked example — Slice 1

- **Boundary:** every release site assumes it knows whether it owns a refcount and how many
  buffers it released. Neither is represented.
- **Kill list:** #879, #883, #886, #878.
- **Representation:** release operations return what they actually did (count released, count
  failed); ownership recorded when acquired instead of inferred from cache residency.
- **Collapse claim:** the `getIfPresent(lsn) != entry` ownership test in the drain error path
  disappears; `recordExternalRelease(int)`'s caller-supplied count parameter disappears.
- **Purity claim:** the refcount ledger becomes a value-level property test — a randomized
  sequence of populate/evict/deliver/fail operations, asserting outstanding equals true live count.
- **Blast radius:** Slice 4's memory accounting depends on this ledger being trustworthy.

## Model selection — it is the PHASE, not the slice

| Phase | Model | Why |
|---|---|---|
| EXPOSE | Sonnet/High, Opus if the defect is a race | Finding a seam is mechanical; demonstrating a concurrency defect is not |
| **REPRESENT** | **Opus** | Designing the type IS the design. Highest-judgment step; a mediocre type poisons every later phase |
| ROUTE | Sonnet | Mechanical once the type exists |
| **COLLAPSE** | **Opus** | Judging whether the claim held is falsification, not editing |
| SATURATE | Sonnet | Dense value-level tests against an existing pure type. The token-heavy phase, and the safest to delegate |
| **FALSIFY** | **Opus** | "Unreachable vs patched" is exactly where a smaller model rubber-stamps |

**Key point: the process is what makes delegation safe.** A smaller model's failure mode here is
not "cannot do it" — it is "produces a plausible fix that patches the symptom," which is the
exact thing boundary-driven refactoring exists to catch. So COLLAPSE and FALSIFY matter *more*,
not less, when Sonnet does the bulk.

Per-slice overlay for PR #592: slices 1, 2, 3 are Opus-heavy throughout (concurrent refcount
lifetimes; the purity split's type design; Invariant 7's over-claim/under-claim asymmetry where a
wrong fix means silent data loss). Slice 4 splits — Opus for the gate concurrency decision,
Sonnet for config validation (#894). Slices 5 and 6 are Sonnet end-to-end. Slice 0 is
Opus-design / Sonnet-implement, because its seams are retrofitted everywhere if designed badly.

## Anti-patterns — you are off the rails if

- You added a guard or a try/catch to fix a finding → patching, not representing.
- COLLAPSE found nothing to delete → the representation is not earning its keep.
- A test needs a mock → the effect was not hoisted far enough out.
- You are fixing findings one at a time in issue order → you abandoned the boundary as the unit.
