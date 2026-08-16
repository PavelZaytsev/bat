---
name: refactor
description: |
  Boundary-driven refactoring — a reproducible loop for turning a pile of bug findings into
  structural fixes. Groups findings by the MISSING FACT each one needs, then runs a six-phase
  loop per boundary (Expose → Represent → Route → Collapse → Saturate → Falsify) where each
  phase can falsify the previous one. Use when a codebase has accumulated several defects that
  look unrelated, when a review has produced many findings and you need to decide what to fix
  first, when a component is untestable and the tests would be integration-shaped, or when the
  user asks to refactor rather than to patch. Also use to plan and track multi-session refactor
  work: the skill owns a local YAML tracker and a renderer, and defines what "done" means for a
  slice so partial work cannot be mislabelled as finished.
---

# Boundary-driven refactoring

> **STATUS: derived from one codebase (vcf/datastore PR #592 — seven slices closed, 28 findings,
> 15 fixed). Validated there against pre-agreed criteria; NOT yet validated on a second codebase.**
> Treat the phase loop as solid and the domain-flavoured examples as provisional. See
> "Validation" at the bottom before presenting this to a team.
>
> External signal worth recording, because it is the only outside evidence so far: the component's
> own author, reviewing the filed findings, said *"these are good finds — how did you find them? My
> AI completely missed these bugs when I pushed it out for review."* The findings that landed
> hardest were not from reading. **Nine of twenty-eight surfaced from DOING a slice** — one was
> found by building an admission bound on Netty's arena metrics and watching six tests fail at
> once, which revealed the metrics are chunk-granular (4 MiB reported for a 100-byte allocation).
> A review pass cannot reach those. That asymmetry is the method's actual claim.

## The one-sentence version

**A bug is code acting on something it does not know.** This method finds what the code needed to
know, makes it know it explicitly, and the bug class becomes unrepresentable.

Everything below is machinery for that sentence, plus guardrails against fooling yourself.

---

## Step 1 — Find the boundaries (the part everyone skips)

You have N findings. They look unrelated. Partition them.

**Not by file. Not by subsystem. Not by severity.** Those groupings feel natural and are wrong —
one real boundary spanned four classes.

### Procedure

**1. Force every finding into one sentence:**

> At `<site>`, the code needs to know **K**, and instead infers K from **I**.

If a finding will not fit the shape it is probably not a boundary defect (a typo, an off-by-one, a
wrong constant). Fix it directly; it does not belong in a slice.

**2. Group by K — the missing fact.** Not by I, not by symptom.

**2b. Classify K as a VALUE fact or a TEMPORAL fact — and treat "temporal" as UNFINISHED.**

There is no such thing as a temporal K. There are value Ks, and Ks whose **structure you have not
found yet**. When K sounds like it is about time, you are missing an **ownership structure**, not a
clock.

This is why Rust and modern C++ solve what FP alone does not. Rust's `'a` is not a duration — it is
a **region**, a set of program points, and borrow checking is a *static* analysis over a
containment lattice. Rust never reasons about *when*; it reasons about **structure that implies
when**. Ownership is a tree, and validity is a *consequence* of containment. Time is hard to reason
about; containment is not.

So when a finding reads as temporal, do not reach for an epoch or a timestamp. Ask which of these
structures is missing:

| K sounds like | missing structure | K restated as a value question |
|---|---|---|
| when may I release / free this? | **ownership** — exactly one owner, release is the owner's privilege | do I own it, or was it handed to someone? |
| how long is this reference valid? | **borrow** — cannot outlive its owner, enforced by not letting it escape | owned copy, or non-escaping borrow? |
| how long will this stay held? | **lease** — a holder registers, and releases | how many leases are outstanding? |
| what am I allowed to do *here*? | **capability** — a token whose type grants the operations | which token do I hold? |
| when should this work run? | **ownership of work** — a queue owns pending work | does a queue own this, or the caller's stack? |
| what does the system permit *now*? | **reservation** — hold a permit, do not read a live figure | do I hold a permit for N? |
| what is outstanding right now? | **projection** — derive the count from the owner set | (the question disappears) |
| has this happened / will it complete? | **completion** — a value meaning "this occurred" | do I have the completion value? |
| what time is it? | **irreducible** | inject the clock; this one really is temporal |

Measured on the source codebase: of nine findings that read as temporal, **eight dissolved into one
of these structures and only the clock was irreducible.** Two dissolutions overturned conclusions
that had already been written down as unavoidable:

- "the admission check depends on live memory, so a time-of-check/time-of-use gap is inherent"
  — false. Hold a **reservation** instead of reading a figure, and the gap does not exist.
- "the outstanding-buffer ledger is fed by discipline, so it can always drift" — false. Make it a
  **projection** of the owner set and it cannot disagree with itself.

And it retro-explains the same codebase's clearest split. One slice represented two temporal facts:
a chained `CompletableFuture` (a **completion** value — dissolved, no residual) and a byte ledger
(a **mirror** shadowing a live quantity — not dissolved, spawned two follow-up findings). Naming an
instant held; shadowing a live value did not.

> **Rule: `k_kind: temporal` is a TODO, not a category.** Record which structure it dissolves to
> before designing. A slice that ships a temporal K still labelled temporal has patched it.

### Use the language's own ownership before inventing any

Check what the platform already enforces before building a token:

- **Java's FFM `Arena` / `MemorySegment` is runtime-enforced ownership.** A segment carries its
  `scope()`, and access after the arena closes throws. On the source codebase, the finding about
  segment lifetime existed *because the confinement was written in a javadoc* while the language
  offered the actual boundary. That is not a missing library; it is bypassing one already in use.
- **Compile-time ownership exists for Java**: the Checker Framework's Resource Leak Checker
  (`@Owning`, `@NotOwning`, `@MustCall`, `@EnsuresCalledMethods`) is the closest thing to a borrow
  checker, as a pluggable type system rather than a runtime cost.

Both belong in `foreign_facts` as `assumed` until you have measured them on your build. "A library
solves this" is exactly the class of claim this method refuses to take on trust.

### Proving the ownership lens works — pre-register criteria that can FAIL

"Ownership dissolves temporal Ks" is a claim, and this method does not accept claims. Commit to
criteria before the slices, then check. The sharpest one is #2.

| # | criterion | pass condition |
|---|---|---|
| 1 | The primitive is real | **measured**, not recalled — a throwaway test with the numbers recorded |
| 2 | **A dissolved K is testable without time** | no test for it needs a `sleep`, a thread, or a clock |
| 3 | The check can fire **in production** | not test-only. One slice's "runtime check" was a regression test asserting a call is never made — that guards the known call site, not the invariant |
| 4 | No dissolution costs more than its defect | `riskier_than_the_defect: no` in ROUTE's `introduced` |
| 5 | The eight structures suffice | or every gap is **named and measured** |
| 6 | Slice count grows slower than finding count | the method's existing criterion |

**Criterion 2 is the proof.** If you genuinely turned time into structure, the test should not need
time. It is cheap to check and it cannot be argued around. Note that it already holds for every
*value* K on the source codebase — those SATURATE suites use no mocks, no I/O and no threads — so
the open question is only whether it survives the dissolved temporal ones.

**Falsification condition, stated in advance:** if two or more remaining slices need a structure
that is not one of the eight, the taxonomy is wrong rather than merely incomplete.

### Measure the platform before designing on it — a worked example

On the source codebase (Java 25), eight assertions measured what `Arena` actually enforces. The
two NEGATIVE results were worth more than the five positive ones:

- **Confirmed:** access after `close()` throws `IllegalStateException` (ownership is enforced, not
  documented); `scope().isAlive()` is observable without touching the memory (a holder can *ask*);
  a confined arena from another thread throws `WrongThreadException`;
  `reinterpret(size, newOwner, cleanup)` **moves** ownership — the segment outlives its original
  arena and cleanup runs when the *new* owner closes, which is the transfer primitive;
  `Arena.ofShared()` is multi-thread readable and still dies on close.
- **Negative, and it changed the design:** an arena per *scan* does **not** enforce a
  callback-scoped borrow. A callee that stashes a segment and reads it after the callback returns
  sees no error, because the arena is still open — which is exactly the defect's shape, unenforced
  where the finding lives. An arena per *callback* does enforce it. So the design is **"make the
  arena's lifetime BE the borrow window"**, not "use the arena already there".
- **Negative:** an `Arena` carries no byte budget and reports no used total. Ownership-of-memory and
  ownership-of-**capacity** are different structures, so a reservation wants a permit, not an arena.

Had that first negative gone unmeasured, the slice would have shipped a borrow the platform does not
actually check — a comment wearing a type's clothing.

### Distinguish a fact you DEPEND on from one you are CONSIDERING

`depended_on_by` blocks a slice until the fact is measured (R12). A library you are merely evaluating
must not sit there, or the rule manufactures work that may never be needed. Use a separate
`considered_for`, and move it only when something actually depends on it.

Related trap, observed: when a finding moves between slices, **the foreign fact it depends on does
not move with it.** One re-cut left an `assumed` latent-corruption assumption pointing at a
superseded slice, so R12 had quietly stopped guarding the very thing it existed for. Re-point
`depended_on_by` whenever a finding is re-homed.

### The honest limit, restated

Java's *core* type system cannot express a region, so a dissolved structure is usually enforced at
**runtime** — a scope that refuses access when closed, a permit that cannot be acquired twice, a
lease register that knows its holders. That check is not a crutch; it is where the guarantee lives.
A comment asserting the same thing is the crutch it replaces.

Two things narrow that limit, and both should be checked before accepting it: the FFM `Arena`
already enforces segment scopes at runtime, and a pluggable checker can enforce `@Owning` at
compile time. The gap versus Rust is real but smaller than "Java cannot do this".

**3. State the boundary:**

> Party A must know **K** about party B, and nothing tells it.

**4. Falsify the grouping before committing:**

> Would ONE representation of K make all of these impossible?

Yes → one slice. No → you grouped by symptom. Split and retry.

**5. Read your own boundary statement for the word "and".**

If it names two things the party cannot see, you have probably written two slices. One boundary
statement read: *"populateForCache owns two things its visitor can neither see nor influence: the
LIFETIME of the segments it hands over, **and** WHETHER the visitor is consulted at all."* Both
clauses were true. They are different Ks, the slice closed the second, and the first had to be
split out at FALSIFY — which meant narrowing a boundary statement after the fact, the exact move
that lets a slice launder unfinished work into done.

Cheap to catch up front: for each conjunct, ask whether one representation covers both. Here it
could not — a budget cannot fix a lifetime. Splitting at step 5 costs nothing; splitting at
FALSIFY costs a public correction.

### Worked example

Four findings that read as four different bugs — a use-after-free, a permanent leak, a wrong
gauge, a muted log:

| finding | K (needs to know) | I (infers from) |
|---|---|---|
| use-after-free | do I own this refcount? | whether it is still in the cache |
| wrong gauge | how many did I release? | the size of the collection |
| permanent leak | did this release succeed? | whether an exception was thrown |
| muted log | was that failure real? | *never asked* |

Same K: **what a release actually did, and who owns what.** One record made all four impossible.

### Why "missing fact" and not "bad practice"

"Mutable state", "primitive obsession", "too much coupling" diagnose what to **remove** and leave
the work undefined. "The discount is inferred by comparing prices rather than recorded" tells you
what to **add**. The missing fact is the actionable half, and naming it is most of the design.

---

## Step 2 — Order the slices

1. **Depth of representation, weighted by severity** — not severity alone. Fix the deepest
   representational hole first; its symptoms disappear for free.
2. **Attack the blocker that unblocks the most.** If several findings share a blocker, the blocker
   IS the work. Deferring it costs more than doing it. (Observed: a "blocker" deferred with a
   filed rationale turned out to be three edits and one record component — and the fix was already
   written in the issue filed to avoid it.)
3. **An enabler slice first if the effects have no seams.** You cannot test what you cannot
   inject.
4. Record hard ordering constraints — sometimes fixing A first re-opens B.

---

## Step 3 — Run the loop, per slice

### A distinction worth keeping: seam ≠ representation

A **seam** is an injection point for an effect (Feathers). A **representation** is the value
carrying the missing fact. They often arrive together and are not the same thing, and blurring
them lets seam-building pass for design work: "the seam and the representation are the same
object here" was written into a tracker and had to be corrected, because a cursor interface that
makes a loop drivable carries no missing fact at all.

Build the seam when EXPOSE cannot otherwise reach the defect. Then design the representation
separately, and ask of each: *which one carries K?*

### 1. EXPOSE — a probe, not a specification

**One test, one finding — the most cheaply reachable, not the most severe.** Its job is proving
the boundary is observable at all.

- It must fail **at its assertion**. Failing in `setUp` or on a mock has exposed nothing.
- A **passing** EXPOSE test means the experiment is wrong, not that the code is fine.
- If you cannot write it without live infrastructure or a thread race, **that is the first
  finding: the boundary has no seam.** Build the seam first.
- **Write down the defect's input space**, including the parts you are not testing. That list
  becomes SATURATE's checklist. Skipping this ships fixes covering one of three conditions.

Do not write tests for all N findings here — before REPRESENT you lack the vocabulary and would
write N integration-shaped tests and discard them.

### 2. REPRESENT — the inversion

Normal practice writes pure functions and discovers the types they need. **This runs backwards:
name the missing value, introduce it, and let it pull the functions into shape.** In a refactor
the functions already exist and are wrong precisely because they lack an input or output.

Asks: **what is the sentence this code cannot currently say?**

- **Purely additive.** No behaviour change; every existing test still passes. Keep the old path
  behind a thin adapter — its later death is COLLAPSE evidence.
- **Make illegal states unrepresentable in the compact constructor.**
- **Absence must be a variant, not a sentinel.** `Unknown(Cause)` over `-1`. If you must use a
  sentinel at a boundary, make it `-1`, never `0` — a bound that silently disables itself is the
  defect reproduced inside its own fix.
- **You cannot represent a value whose meaning you do not know.** Measure third-party semantics
  before encoding them.
- **If you introduce an "I don't know" state, represent how it becomes known again.** Otherwise
  you have traded a wrong answer for *no* answer, which is usually worse. Making a cleared index
  admit it was not authoritative stopped it reporting live data as deleted — and, on its own,
  would have made every subsequent read yield forever. A spurious error is bad; a permanent stall
  is worse. The honest `Unknown` and its recovery path are one piece of work, not two.
  Ask it as: **what makes this value knowable again, and who runs it?**

### 3. ROUTE — make the transfer explicit

Producers produce it, consumers consume it. Should be **mechanical**. Three failure modes:

- **(a) A foreign contract blocks the value.** Legitimate — record it, scope a different slice.
- **(b) You start inventing.** THE DANGEROUS ONE. If ROUTE reaches for a new abstraction,
  **REPRESENT was incomplete — go back.** An improvised helper skips REPRESENT's scrutiny and is
  not in the collapse claim, so nothing checks it. (Observed: an improvised "resolve" helper
  collapsed two sources into one value, discarded which one answered, and the bug survived behind
  code that looked correctly refactored. COLLAPSE passed 4/4 while it was fully live.)

  The same failure has a quieter form: **inventing a CONCERN rather than an abstraction.** A
  coverage flag was defaulted to "unauthoritative until rebuilt" — defensible in the abstract,
  asked for by no finding, and contradicting the previous behaviour, which had always trusted that
  value. It made every read yield and broke four test suites. Test: **which finding asked for
  this?** If none, it is not in scope, and its absence is not a bug you are fixing.
- **(c) The routing changes behaviour you did not intend.** Run the WHOLE suite after ROUTE.

#### ROUTE's second output: account for what you ADDED

*(This was briefly a phase called "4b", which was itself the kind of bolted-on exception this
method is supposed to remove. It is not a phase — it is ROUTE's second output, produced at the
same time as the wiring.)*

**Every other phase in this loop measures removal.** COLLAPSE asks what died. SATURATE tests the
new value's boundaries. FALSIFY asks whether siblings became unreachable. And the strongest signal
the method recognises — an *unpredicted* collapse — rewards deletion.

**Nothing asks what you built, or what its blast radius is.** That is a real hole, because a
refactor's risk does not come from what it deletes: deletion is validated by the suite still
passing. It comes from what it adds.

How it went wrong: a slice needed a recovery path (an index that admits it is not authoritative
must have a way back, or you have traded a wrong answer for a permanent stall). The recovery built
was **a full column-family scan on a read path that also overwrote two shared tracker fields.** It
passed through COLLAPSE, SATURATE and FALSIFY unremarked — all three working correctly, none of
them pointed at it — and the defect it serves causes only an unnecessary client re-snapshot.
Plausibly a worse trade than the bug.

So at ROUTE, record alongside the collapse claim:

```
introduced:
  - what:                      <the new machinery>
    blast_radius:              <what it touches, what state it mutates, what is untested>
    riskier_than_the_defect:   <compare against the defect's OWN blast radius, and say yes if yes>
    deviates_from_stated_fix:  <false, or the departure and why>
```

`riskier_than_the_defect` is the load-bearing field. **Trading a loud cheap bug for a quiet
expensive one is a net loss that every other phase scores as a win.** R14 enforces the record;
only you can make the comparison honest.

`deviates_from_stated_fix` exists because R9 makes you *derive* predictions from a finding's stated
fix direction, and nothing made you record *departing* from one. The same slice's issue recommended
rebuilding **in the background**; what got built was synchronous, on the reader's thread — neither
of the options the issue offered — and that went unrecorded for a week. R15 enforces it.

**Why the violation was easy.** The rule already existed: ROUTE failure mode (b) says an
improvised abstraction means REPRESENT was incomplete, go back. It was skipped because the work
felt *obligatory* rather than designed — the tracker note literally reads *"required, not
optional."* Generalise:

> **The fix you feel forced into is the one that skips scrutiny.** "I had no choice" is the state
> in which design review does not happen. If a recovery path feels mandatory, that is exactly when
> to take it back to REPRESENT and choose between the options deliberately.

Corollary for the REPRESENT rule about "I don't know" states: mandating that a recovery path exist
is not enough. **The recovery path is itself a representation and needs the same scrutiny** — the
risky version above fully satisfies "represent how it becomes known again."

### 4. COLLAPSE — delete what existed only because the fact was missing

Four recurring shapes die: the inference itself; defensive guards around it; **reporting APIs that
carried the fact out-of-band**; and the exception used as a data channel.

> An API that exists to REPORT something usually means the something was not represented.

**How you know it worked: you predicted specific deletions BEFORE starting and they happened.**
Derive the claim from the findings' stated fix directions, not from your build plan — otherwise
unbuilt items leave no surviving code and COLLAPSE cannot notice them.

Not all confirmations weigh the same. In descending order of evidence:

| | strength |
|---|---|
| **Unpredicted** collapse | strongest — the representation displaced code you did not know was displaceable. One slice deleted a *previous, dormant attempt at its own finding* and had not predicted it |
| Predicted from a finding's stated fix, confirmed | solid |
| Predicted for scaffolding **you introduced yourself**, confirmed | **weakest — do not over-read it.** A deprecated adapter added during REPRESENT so that phase changed no behaviour is *designed* to die. Its death confirms you followed your own plan, not that the boundary was real |

Record which kind you got. A slice whose every collapse is the third kind has demonstrated
tidiness, not a boundary.

Distinguish **surviving mechanism** (fine — foreign contract, re-scope the prediction) from
**surviving inference** (not fine — back to REPRESENT).

**COLLAPSE is not fix-verification.** It asks "did the old code die?", not "is the new code
right?". Both can diverge.

### 5. SATURATE — cash the purity claim

Three layers, cheapest first:

1. **Algebra of the value** — illegal states, identities, accumulation. No I/O, no threads.
2. **Boundaries** — one value either side of every threshold; every precedence combination.
3. **Properties over randomised sequences** — fixed seed, and assert the invariant **after every
   step**, not at the end, so a drift names the step it began on.

Reach for property-based testing whenever the pure core has an invariant over a space of inputs.

**If a test needs a mock, the effect was not hoisted far enough.**

**Expect SATURATE to correct your TEST as often as your code, and treat that as a result rather
than a detour.** Twice in two consecutive slices a property assertion failed and the code was
right: once asserting "the requested transaction is always served" when exceeding a per-item
ceiling is a legitimate second fate, once asserting three negative answers must be distinct when
two of them were both *known* negatives that a caller acts on identically. Both times the
corrected assertion was **sharper** than the one intended — the first became "exactly two
admissible fates, and absence is not one", the second became "the line is between known and
unknown, not between the two known cases".

When a dense test fails, ask **"is the code wrong, or have I just discovered what the invariant
actually is?"** before touching the code.

### 6. FALSIFY — test the partition, not the code

| phase | question | subject |
|---|---|---|
| SATURATE | is the new code correct? | the representation |
| FALSIFY | did the slice's claim hold? | **the grouping** |

The only phase that can invalidate the boundary hypothesis. For each sibling finding:

1. Unreachable now, or separately patched?
2. **Is there a test that would fail if this fix were reverted?** "It looks handled" is not a
   verdict.
3. If not fixed: **mis-assigned or unfinished?**
   - same fix, another site → extend this slice
   - different fix → split it out under its own ID
   - **different boundary → a new slice is born.** This is how the loop feeds itself.

**Three reasons a sibling survives, and only one of them lets the slice close.** The doc used to
name two, and the missing third caused a real mislabel:

| survivor because | verdict | what to do |
|---|---|---|
| a contract **you do not own** requires it | foreign — does not indict the representation | re-scope the prediction, close the slice |
| a **different boundary** requires it, in code you *do* own | legitimate — different K | file the remainder, new slice, close this one |
| nothing requires it; the work was not done | **self-inflicted** | the slice is NOT done — return to REPRESENT |

The mislabel: a lock-nesting remainder was recorded as blocked by a *foreign* contract, when the
contract was `populateForCache`'s own — the very component the slice owned. Under the honest test
(*"could I finish without changing a contract I do not own?"* → yes) that phrasing would have made
the slice unfinished. It was genuinely the middle row, a different K in the same component. Right
disposition, wrong reason, and only the reason distinguishes "close it" from "go back to phase 2".

---

## What "pure" means here (and IO without an IO monad)

The target is **not** "everything becomes pure". It is:

> **The decision becomes pure. The effect becomes thin.**

Effectful code stops *deciding* and starts *applying a decision*. An IO monad buys *composition*
of effects; this method only needs *separation* — strictly weaker, and achievable in plain Java
with: decisions as values, effects as thin appliers, and **seams** for the effects you must test
through (injectable allocator, injectable executor, controllable clock).

---

## Status vocabulary — never write "partial"

| status | meaning | may a slice close with it? |
|---|---|---|
| `fixed` | unreachable, with a test that fails if reverted | yes |
| `split` | part fixed; remainder filed under its own ID | **yes** — parent is done |
| `blocked` | implemented, inert until a **named** dependency lands | yes, if the dependency is filed |
| `dormant` | implemented but **not wired** | **NO — the trap** |
| `open` | not started | no |

`dormant` reads as done in every summary, passes its own tests, and changes nothing at runtime.

**`blocked` is a claim that must be tested before use.** Ask *"have I actually tried, or am I
estimating?"* A blocker you have not attempted is not blocked, it is avoided.

---

## Tracking

`slices.py` renders the slice tree and validates the tracker **against itself** — the tracker is
the source of truth for status, so the useful question is whether it contradicts itself.

```
python3 slices.py             # render the tree
python3 slices.py --check     # validate (exit 1 on any problem)
python3 slices.py --rules     # what --check catches, and what it does NOT
python3 slices.py --selftest  # corrupt a fixture 11 ways, assert each rule fires
```

Copy `slices_progress.template.yaml` next to it and fill it in. **Run `--check` after EVERY edit**
— a YAML parse error makes the tracker silently unreadable, and it went unnoticed for several
turns the first time. Note the schema constraints documented in the template: a `{...}` flow
mapping cannot contain a `>` block scalar, and a `>` block scalar cannot contain comment lines
(they become content at the wrong indent).

### A green check is a claim about the RULES, not about the work

**Read `--rules` before you believe `--check`.** This is not pedantry; it cost a full session.

A validator reported `consistent` while two findings had been re-assigned to a later slice **in
prose** and never actually moved — so the receiving slice would have run its FALSIFY against one
finding and declared its boundary verified with two more live on it. The check passed because its
rule was *"a done slice may not own an open finding **without a note explaining why**"*, and the
note **was** the deferral. The evidence of the unfinished transfer was accepted as the excuse for
it. Separately, the renderer was reading a stale planning field, so slices whose collapse claim
was confirmed displayed as `unverified`.

Both were found only by reading the validator's source. Neither was visible in its output.

Generalises past this tool: **when a validator's escape hatch is "explain yourself", the
explanation will be the thing you were supposed to act on.** Prefer a check that requires the
state to be right over one that requires the state to be narrated.

`--selftest` exists so coverage is falsifiable rather than asserted: it corrupts a fixture in
known ways and fails if a rule does not fire. A rule that cannot fail is not a rule. If you add a
rule, add its mutation.

### Apply the method to your own bookkeeping

The tracker is part of the system. Run the boundary procedure on it and the same shapes appear —
these are real defects that were found in it, in the method's own vocabulary:

| tracker defect | K (needed to know) | I (inferred from) | fix |
|---|---|---|---|
| deferral recorded, never carried out | was this transfer executed? | a note exists | R4: a done slice owns no open finding, full stop |
| confirmed claims displayed as unverified | what did COLLAPSE conclude? | a stale planning stub | R11: one home — `phases.collapse.outcome` |
| an orphan finding | is this unassigned on purpose? | *never asked* | R6: `unassigned: <why>` is required |
| a field silently overwritten | are these two keys the same key? | YAML kept the last, no error | R7: duplicate-key detection |

If your bookkeeping needs a note to be trustworthy, the note is a missing field.

### Issue tracker role — write-once

File one issue per finding for a **stable ID and a durable long-form write-up**. Then never touch
it again: no closing, no re-labelling, no follow-up comments. All status lives in the YAML.

Maintaining two stores produced exactly one confirmed error (reporting issues closed when nothing
had merged) and no benefit. But inventing local IDs means a counter and collision risk. Filing is
cheap and write-once; reconciling is neither.

---

## Running this autonomously

The loop is mechanical enough to run unsupervised. What breaks autonomy is not the phases — it is
**acting on the tracker's prose instead of on the code.** Every autonomous failure observed so far
is that one mistake wearing a different hat.

### Preflight — before touching any code, every session

```
python3 slices.py --rules      # 1. learn what the validator covers
python3 slices.py --selftest   # 2. prove it covers it
python3 slices.py --check      # 3. now a green result means something
```

Then, for the slice you are about to start:

4. **Re-derive its kill list from the CODE.** Open every finding's `where:` and confirm the K you
   are about to represent is the K that site is actually missing.
5. **Any finding with `routed_from` gets its K re-derived independently** and `k_verified_at` set
   to the receiving slice. R8 enforces that the field is set; only you can make it honest.
6. **Write the collapse predictions from each finding's stated fix directions** before ROUTE. R9
   blocks you from routing with a stub.

Step 4 is not ceremony. A finding was once routed to a slice because it lived in the same unseamed
**region** as that slice's findings — region being exactly what Step 1 forbids grouping by. The
note said `-> slice N` and was written by someone competent; it was still wrong. Reading the code
took ten minutes and moved it to a different slice, whose boundary it matched exactly.

> **A note is a decision. A decision is not a verification.**

### Per-phase exit gate

Do not advance until the gate is met. Each is checkable, not a feeling:

| phase | gate |
|---|---|
| EXPOSE | the test fails **at its assertion**, and `input_space` is written down |
| REPRESENT | every pre-existing test still passes — the phase added, it did not change |
| ROUTE | whole suite green; no new abstraction was invented (if one was, return to REPRESENT) |
| COLLAPSE | `died` is non-empty, **or** `no_death_expected` states why nothing should die |
| SATURATE | the new tests use no mocks, no I/O, no threads |
| FALSIFY | every sibling has a verdict, and each `fixed` one names a test that fails if reverted |

### What is NOT mechanical — surface these, do not absorb them

Autonomy means making the judgment calls **visible and reversible**, not pretending they are
derivable. Four recur, and each must be written down as an explicit decision with a reason:

1. **Is a finding merge-blocking?** A severity label does not answer it. State the consequence to a
   user and decide from that.
2. **Is a boundary statement true of the code?** No validator can check this. Only reading can.
3. **Was the ORIGINAL grouping by K, or by region/file/symptom?** R8 catches transfers; nothing
   catches a first-pass mis-grouping. Re-run Step 1's falsification question at the start of each
   slice, not only when planning.
4. **Is a `blocked` real?** Ask *"have I tried, or am I estimating?"* A blocker you have not
   attempted is avoided, not blocked.

If one of these is decided silently, the process is being followed in name only — the same failure
mode as leaving `collapse_claim.outcome: unverified` on a slice you called done.

### Where the honest limits are

The loop transfers. The **cost model** may not: the source session's phases were sized for
concurrent off-heap memory in Java, where EXPOSE is expensive and SATURATE is cheap. In a domain
where the defect is trivially reproducible, EXPOSE collapses to minutes and the ordering advice in
Step 2 matters less. Re-measure rather than assuming.

---

## Model and effort selection

It is the **phase**, not the slice:

| phase | model / effort | why |
|---|---|---|
| EXPOSE | mid-tier; top-tier if the defect is a race | finding a seam is mechanical |
| **REPRESENT** | **top-tier / high** | type design IS the design; a mediocre type poisons every later phase |
| ROUTE | mid-tier | mechanical once the type exists |
| **COLLAPSE** | **top-tier / medium** | verdict is empirically checkable — delete it, run the tests |
| SATURATE | mid-tier | dense value tests; the token-heavy phase, safest to delegate |
| **FALSIFY** | **top-tier / high** | "unreachable vs patched" is where a smaller model rubber-stamps |

**High effort where the output is a decision that constrains later work; medium where the output
is verifiable by tests.** The process is what makes delegation safe: a smaller model's failure
mode is a plausible fix that patches the symptom, which is exactly what COLLAPSE and FALSIFY
catch. Delegate the volume, never the falsification.

---

## Foreign facts — the one guardrail that must not be prose

**A third-party assumption is not a lapse in diligence. It is an unrepresented fact — this
method's own defect shape, pointed outward:**

> At `<site>`, the code needs to know **K** — what this call actually returns and means — and
> instead infers K from **the method name, or from what I expected it to do**.

`numActiveBytes` *sounds* byte-accurate and is chunk-granular. `ByteBuf.equals` *sounds* like
identity and is content. An iterator *sounds* like it holds still. That is
`getIfPresent(lsn) != entry` standing in for ownership, in someone else's library.

So it cannot be handled by remembering to be careful. **"Be meticulous about third-party APIs" is
the same weak shape as a validator whose escape hatch is "explain yourself"** — it only fires once
you have already remembered, which is exactly when you did not need it. Represent it instead: the
tracker carries a `foreign_facts` ledger, and R12/R13 make a missing disposition a red check.

### Disposition, in preference order

| | meaning |
|---|---|
| **`eliminated`** | the code no longer depends on it being true; `evidence` names the check that enforces the invariant instead |
| **`measured`** | a throwaway test read the **actual value**; `evidence` records the number |
| **`documented`** | the vendor says so **and** `checked_by` names what enforces it in our code |
| **`assumed`** | blocks any slice that depends on it (R12) |

**Eliminating beats measuring**, which inverts the obvious ordering and is the lesson worth
keeping: a measurement is a point-in-time act that decays silently on the next upgrade, while a
checked invariant travels with the code. Faced with "does a RocksDB iterator hold still across a
`seek()`?" — documented yes, unmeasurable without a live database, and a wrong answer means
delivering a transaction with tables missing — the right move was not to go measure it. It was to
compare the two passes' row counts and throw on mismatch, so the code stops caring.

Record the observed numbers **at the point of use**, in the javadoc, so nobody re-derives them.

### The gate that catches it

Ask at **every phase boundary**, and write the answer in the ledger:

> **What did this phase newly assume about code I do not own?**

REPRESENT is where the doc used to put this, on the grounds that you cannot represent a value
whose meaning you do not know. That is true and insufficient — the miss that produced this section
happened in **ROUTE**, where a two-pass design newly depended on iterator semantics nothing had
questioned. Any phase can acquire a foreign dependency. R12/R13 check the entries that exist;
nothing detects one you never wrote down, so the question has to be asked out loud.

Every such assumption in the source session was wrong: a package-private constructor, instance
recycling, content-based `equals`, a sealed interface, chunk-granular metrics reporting 4 MiB for
a 100-byte allocation, and an iterator assumed to hold still across a seek. Not one was caught by
intending to be careful.

---

## Guardrails learned the hard way
- **A changed EXPOSE test does not count as green until re-verified against the pre-fix code.** A
  good structural fix can delete the failure mode the test was pinned to.
- **Assert the property, not the mechanism.** "The reader's buffer stays alive" survives the fix;
  "an exception is thrown" is a hostage to it.
- **A resolved value must not lose a distinction its consumers depend on.** Combining sources looks
  like tidying and can silently re-create the bug.
- **Check the blast radius during ROUTE, not only when planning.** A fix satisfying one finding can
  violate another's invariant.
- **When the process surprises you, augment the process.** Triggers: a test fails for an
  unpredicted reason (including passing when it should fail); a phase completes cleanly and the
  defect is still reachable; a prediction is refuted; you correct something you filed; you make
  the same class of mistake twice. Then ask: domain or process? Which phase should have caught it?
  Would the rule have changed a decision? Write it immediately with the concrete instance
  attached, and prune rules that never fire.

---

## Validation

Criteria agreed before the source session, and their outcome:

| criterion | result |
|---|---|
| One representational fix closes multiple findings structurally | ✅ four findings, one record |
| Resulting tests are value-level — no mocks, I/O or threads | ✅ 19 cases, 1.0s, zero mocks |
| Slice count stays stable while finding count grows | ✅ findings 19→28, slices 7→10 |
| The tracker's own defects are findable by the method | ✅ four, see "Apply the method to your own bookkeeping" |
| A slice can delete a previous, failed attempt at its own finding | ✅ slice 7 removed a dormant bound that read like a bound and enforced nothing |
| Two findings grouped by K turn out to share a CAUSE | ✅ slice 8 — a corrupt sentinel during rebuild was what made the index under-claim |
| A slice reviews cleanly as one self-contained commit | 🔶 partially — commits were clean, the PR spanned five slices |

**Before sharing with a team, run this on a second, smaller codebase in a different domain.** The
loop should transfer; the examples are all concurrent off-heap memory management in Java and may
have shaped the guardrails more than is obvious.
