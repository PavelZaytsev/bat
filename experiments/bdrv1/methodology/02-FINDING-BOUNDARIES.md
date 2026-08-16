# Finding the boundary — and why 'temporal' means unfinished

*Companion to 01-METHOD. This is how to FIND a slice; the method document is how to RUN one.*

Companion to [[feedback-slice-execution-loop]], which documents how to *run* a slice. This
documents how to *find* one, and what each phase is really doing. Written 2026-07-30 answering
Pavel's questions after slices 0-4 of PR #592.

# 0. The one-sentence version

**A bug is code acting on something it does not know.** Boundary-driven refactoring finds what the
code needed to know, makes it know it explicitly, and the bug class becomes unrepresentable.

Everything below is machinery for that sentence.

# 1. Finding the boundary — the missing step

You have a pile of findings. They look unrelated. How do you partition them?

**Not by file. Not by subsystem. Not by severity.** Slice 1 spanned four classes; slice 4's
findings sat in three. Those groupings feel natural and are wrong.

## The procedure

**Step 1 — for each finding, write exactly one sentence in this shape:**

> At `<site>`, the code needs to know **K**, and instead infers K from **I**.

Force every finding into it. If a finding will not fit the shape, it is probably not a boundary
defect (a typo, a missing null check, a wrong constant) and does not belong in a slice at all.

**Step 2 — group by K, the missing fact. Not by I, not by symptom.**

**Step 3 — the boundary statement is:**

> Party A must know **K** about party B, and nothing tells it.

**Step 4 — falsify the grouping before committing to it:**

> Would ONE representation of K make all of these impossible?

Yes → one slice. No → you grouped by symptom; split and retry.

## Worked, from the real findings

Slice 1's four findings looked like four different bugs — a use-after-free, a permanent leak, a
wrong gauge, a muted log. In the shape:

| finding | K (needs to know) | I (infers from) |
|---|---|---|
| #879 | do I own this refcount? | whether the entry is still in the cache |
| #886 | how many buffers did I release? | the size of the collection |
| #883 | did this release succeed? | whether an exception was thrown |
| #878 | was that failure real or benign? | *never asked* |

All four Ks are the same fact: **what a release actually did, and who owns what.** One record,
`ReleaseOutcome`, plus recording ownership at acquisition, made all four impossible.

Slice 3's K was different: *"do I know where the retention window starts?"* — an empty index and a
failed probe were both "I don't know" rendered as "I know."

## There is no temporal K — find the ownership structure (added 2026-08-03)

**The most important addition since the method was written, and it came from Pavel.**

After fifteen findings closed cleanly and nine stalled, classifying each K showed the split: every
one that closed was a **value** fact; every survivor read as **temporal** — lifetime, lock scope,
pinned duration, scheduling, liveness, the instant a reading was taken, the staleness of a mirror.

The first conclusion was "BDR needs a temporal vocabulary — represent the validity window". That is
half-right and stops too early. Pavel's correction: **time is hard to reason about, unlike value —
so do not model time. This is why Rust and modern C++ solve what FP alone does not.**

And the mechanism is not that they model time better. Rust's `'a` is a **region** — a set of program
points — and borrow checking is a *static* analysis over a containment lattice. Rust never reasons
about *when*; it reasons about **structure that implies when**. Ownership is a tree; validity is a
*consequence* of containment.

> **`k_kind: temporal` is a TODO, not a category.** It means the ownership structure has not been
> found yet.

Eight structures dissolve it, each turning a question about time into a question about a value:

| K sounds like | structure | becomes |
|---|---|---|
| when may I release this? | **ownership** | do I own it, or was it handed on? |
| how long is this reference valid? | **borrow** | owned copy, or non-escaping borrow? |
| how long will this stay held? | **lease** | how many leases are outstanding? |
| what may I do *here*? | **capability** | which token do I hold? |
| when should this work run? | **work ownership** | does a queue own it, or a reader's stack? |
| what does the system permit *now*? | **reservation** | do I hold a permit for N? |
| what is outstanding right now? | **projection** | *(the question disappears)* |
| has this happened / will it? | **completion** | do I have the completion value? |
| what time is it? | **irreducible** | inject the clock — only this one is real |

**Two dissolutions overturned conclusions already written down as unavoidable**, which is the
evidence this is not just a nicer framing:

- *"admission depends on live memory, so a time-of-check/time-of-use gap is inherent"* — false.
  Hold a **reservation** (acquire, materialise, release) and the gap cannot exist, because the
  capacity is owned rather than observed. See #903.
- *"the outstanding ledger is fed by discipline, so it can always drift"* — false. Make it a
  **projection** of the owner set and it cannot disagree with itself. See #895.

It also retro-explains slice 4's split result: its chained `CompletableFuture` is a **completion**
value and left no residual; its byte ledger is a **mirror** and spawned both #895 and #903. Naming
an instant held; shadowing a live value did not.

**Use the platform's ownership before inventing any.** The FFM `Arena` already enforces segment
scopes at runtime — #902 exists *because* the confinement is written in a javadoc while the language
may already offer the boundary. And the Checker Framework's Resource Leak Checker offers
compile-time `@Owning`. Both are in `foreign_facts` as **assumed**, unmeasured, because "a library
solves this" is the exact class of claim this method refuses to trust — including from me.

## Why "missing fact" beats "bad practice"

Pavel looked at the toy example and saw **mutable state**. I described it as **an implicit
assumption with no explicit data transfer**. Both point at the same code. Only the second tells you
what to build.

"Mutable state is bad", "primitive obsession", "too much coupling" — these diagnose what to
**remove**. They leave the actual work undefined. "The discount is inferred by comparing prices
rather than recorded" tells you exactly what to **add**: a record of the coupon.

**BDR names the missing fact, not the bad practice.** The missing fact is the actionable half, and
naming it is most of the design.

# 2. EXPOSE — a probe, not a specification

**One test. One finding. The most cheaply reachable one, not the most severe.**

Slice 4 exposed #891 because it was the only one of five reachable with the seams that existed.
Starting where the boundary is *observable* beats starting where the severity is highest, because
an unobservable defect cannot be fixed honestly.

Its job is to prove the boundary is observable at all. It must fail **at its assertion** — a test
that fails in setup has exposed nothing, and a test that *passes* means the experiment is wrong,
not that the code is fine. Both happened; see the loop doc.

**Why not write tests for all N findings now?** Because before REPRESENT you lack the vocabulary to
express them cheaply. You would write N integration-shaped tests and throw them away. The evidence
is stark: slice 2's EXPOSE needed a cache, an LSN index, a safe-point manager and a mocked store to
observe one budget decision; the SATURATE tests for the same logic needed **nothing** and ran in
1.0s.

**The other findings get tested in SATURATE, and confirmed dead in FALSIFY.**

One thing EXPOSE *must* do beyond the single test: **write down the input space** the defect
manifests across, including the parts you are not testing. That list becomes SATURATE's checklist.
Slice 3 skipped this and shipped a fix covering one of three conditions.

# 3. REPRESENT — the inversion

Pavel's read is correct and it is the most unusual part of the method:

> Instead of writing the pipe, you find the data that goes through the pipe first, then send it
> through all the producers and consumers.

Normal FP practice builds pure functions and discovers the data types they need. **BDR runs it
backwards**: name the missing value, introduce it, and let it pull the functions into shape.

**Why the inversion is right here:** in a refactor you already *have* the functions. They are wrong
precisely because they lack an input or an output. You cannot start from functions that are
already there — you start from the fact they are missing.

REPRESENT asks: **what is the sentence this code cannot currently say?** Then makes that sentence
expressible.

Practical rules:

- **Purely additive.** Introduce the type; change no behaviour; every existing test still passes.
  Keep the old path alive behind a thin adapter if you must — its later death is COLLAPSE evidence.
- **Make illegal states unrepresentable in the compact constructor.** `ReleaseOutcome` rejects a
  failure count without a cause; `CacheAdmissionPolicy` rejects an entry ceiling above the total —
  "a bound that enforces nothing while reading like a bound" is the very defect being removed.
- **Absence must be a variant, not a sentinel.** `RetentionFloor.Unknown(Cause)` rather than -1.
  `UNKNOWN_COMMITTED_BYTES = -1, never 0` — a bound that silently disables itself is the defect
  reproduced inside its own fix.
- **You cannot represent a value whose meaning you do not know.** Measure third-party semantics
  first. `committedBytes()` was a well-formed representation of a number I had misunderstood: right
  shape, wrong meaning, and no test of the type could have caught it.

# 4. ROUTE — three ways it fails

ROUTE makes the implicit transfer explicit: producers produce the value, consumers consume it. It
should be **mechanical**. It fails in exactly three ways, all observed:

**(a) The value cannot reach where it is needed — a foreign contract blocks it.**
Slice 2 could not hoist allocation out of the callback because `populateForCache`'s
`MemorySegment`s die when `visit()` returns. Legitimate; record it and scope a different slice.

**(b) You start inventing. This is the dangerous one.**
If ROUTE reaches for a new abstraction, **REPRESENT was incomplete — go back**. An improvised
helper written mid-wiring gets none of REPRESENT's scrutiny and is not in the collapse claim, so
nothing downstream checks it. Slice 3's `resolveRetentionFloor()` collapsed two sources into one
value, discarded which one answered, and #876 survived behind code that looked correctly
refactored. COLLAPSE passed 4/4 while the bug was fully live.

**(c) The routing changes behaviour you did not intend.**
Slice 3 sourced an exception trailer from the resolved floor and created a *fresh instance of the
bug being fixed*. Caught by an existing test. Run the whole suite after ROUTE, not just the slice's.

# 5. COLLAPSE — what dies, and what "pure" actually means here

## What should die

Code that existed *because* the fact was missing. Four recurring shapes:

1. **the inference itself** — `getIfPresent(lsn) != entry`
2. **defensive guards around the inference** — try/catch cleanup, retry bounds, re-derivation
3. **reporting APIs that carried the fact out-of-band** — `recordReleaseError()` died with zero
   callers. *An API that exists to REPORT something usually means the something was not
   represented.*
4. **the exception used as a data channel** — and the catch-and-rethrow that documented it

## How you know it succeeded

**You predicted specific deletions before starting, and they happened.** That is the whole point of
writing the collapse claim in advance: a prediction that could not fail proves nothing.

Derive the claim from the **findings' stated fix directions**, not from your build plan — otherwise
unbuilt items leave no surviving code and COLLAPSE cannot notice them.

Distinguish two kinds of survivor:
- **surviving mechanism** (a ThreadLocal that exists because Caffeine forbids nested mutation) —
  fine, foreign contract, re-scope the prediction
- **surviving inference** — not fine, the representation did not reach it, return to REPRESENT

## Does the fix reduce to a pure function? What about IO, with no ZIO?

**No, and this matters.** The target is not "everything becomes pure". It is:

> **The decision becomes pure. The effect becomes thin.**

The effectful code does not disappear — it stops *deciding* and starts *applying a decision*.
`populate` still does I/O and allocation; it just no longer computes budgets while doing so.

**You do not need an IO monad for this, and Java does not have one.** An IO monad buys
*composition of effects*. BDR only needs *separation* of effects — a strictly weaker requirement,
satisfiable with:

- decisions as values (pure functions returning records/enums)
- effects as thin appliers of those decisions
- **seams** for the effects you must test through: an injectable allocator, an injectable executor,
  a `DeterministicRuntime`

That is functional core / imperative shell (Bernhardt), and it is entirely achievable in plain
Java. If a SATURATE test needs a mock, the effect was not hoisted far enough — that is the signal,
not the absence of a monad.

# 6. SATURATE — yes, property-based, in three layers

Pavel is right that this is where PBT belongs. Formalising it: **SATURATE should reach for
property-based testing whenever the pure core has an invariant that holds across a space of
inputs.** Used twice on this PR, both times productively.

Three layers, cheapest first:

1. **Algebra of the value** — illegal states rejected, identities (`nothing().plus(x) == x`),
   accumulation, factories. No I/O, no buffers, no threads.
2. **Boundaries** — one value either side of every threshold, and every precedence combination when
   several bounds can be exceeded at once.
3. **Properties over randomised sequences** — with a **fixed seed**: reproducible, not flaky. Assert
   the invariant **after every step**, not at the end, so a drift names the step it began on.

SATURATE is where the purity claim is cashed. Slice 2: 19 cases in 1.0s with zero mocks, against an
EXPOSE test that needed four collaborators to observe one decision.

**SATURATE can fail after COLLAPSE passed.** It did, in slice 3. Passing COLLAPSE means the old code
died; it says nothing about whether the new code is right.

# 7. FALSIFY — tests the partition, not the code

The cleanest way to state the difference Pavel asked about:

| phase | question | subject |
|---|---|---|
| SATURATE | is the new code correct? | **the representation** |
| FALSIFY | did the slice's claim hold? | **the grouping** |

FALSIFY is the only phase that can invalidate the **boundary hypothesis** — "these N findings share
one cause". It re-reads every sibling finding and asks:

1. Is it **unreachable** now, or merely separately patched?
2. **Is there a test that would fail if this fix were reverted?** "I read it and it looks handled"
   is not a verdict. #878 was believed fixed and had no test at all.
3. If not fixed — **mis-assigned or unfinished?**
   - same fix, another site → extend this slice (#883 was another class, same representation)
   - different fix → split it out (#886's residual became #895)
   - **different boundary → a new slice is born.** Slice 7 exists because FALSIFY found three
     remainders that shared a boundary nobody owned. #884 moved to slice 5 the same way.

So a new slice is *born* in FALSIFY, from findings that turned out to need a different missing
fact. That is the loop feeding itself.

# 8. What we are actually doing

Pavel's closing framing, which is exactly right:

> We are looking for the most truthful, explicit representation of what the functionality is trying
> to achieve, and not assuming anything.

That is the method. Everything else is procedure for getting there without fooling yourself:

- **EXPOSE** — prove you can see it
- **REPRESENT** — say the true thing
- **ROUTE** — make everyone say it
- **COLLAPSE** — delete what existed only because it was unsaid
- **SATURATE** — check the true thing is actually true
- **FALSIFY** — check you were right about which things were the same thing

The reason the resulting code reads like FP written from scratch is not coincidence. FP arrives
there by *conviction* — make things explicit and total because that is the discipline. BDR arrives
there by *pressure* — each bug proves a specific fact was missing, and adding it forces the same
shape. Same destination, and the BDR route carries its own evidence: every value in the codebase
can name the bug that demanded it.
