# Composition over correctness — the stance underneath the method

Pavel's guiding philosophy for this project, stated 2026-07-29 and explicitly flagged as
"very important, it will guide our work":

> Don't focus on writing "correct code". Focus on writing composable, pure,
> referentially transparent code — or as close to it as possible. **The essence of
> programming is composition. Correctness appears from the right structure; it is not
> bolted on with intent.**

**Why:** correctness pursued directly produces defensive patches — guards, try/catch
cleanup, retry bounds — each of which is a local fix that leaves the structure that
generated the bug intact, so the same bug class recurs in the next code path. Correctness
pursued *through structure* makes the bug class unrepresentable. Pavel's background is FP
(Scala, Clojure), so this is his native idiom, not an aspiration.

**How to apply:**

- **Diagnose structure, don't just enumerate bugs.** A bug list is a symptom report. Find
  the structural cause and file *that* as the primary finding, with the individual defects
  as its symptoms. Concrete precedent on this project: #882 (extract a pure core from
  `populate()`) is the real finding; #879 use-after-free, #880 memory overshoot, and #881
  contract violation are all consequences of one effectful function occupying a slot whose
  contract assumes purity.
- **Prefer structural fixes over defensive ones.** Inferring buffer ownership from cache
  residency and patching the fallout is bolting correctness on; making ownership an
  explicit value is structural. Reach for the second.
- **Separate decision from effect.** Pure planner returns an immutable plan; a thin
  effectful executor performs it. The plan is then testable as plain values.
- **Return values, not out-parameters.** Prefer a fold returning an accumulator over a
  callback mutating captured state (single-element arrays, `ThreadLocal` side channels).
- **Make illegal states unrepresentable.** A typed rejection with a reason beats `null`;
  a record beats a mutable flag.
- **Untestable code is a structural signal, not a testing problem.** If a unit test is
  hard to write for a critical path, refactor the path first, then test. Do not reach for
  an integration test to compensate — Pavel dislikes ITs (bulky, slow dev cycle).

**Scope caveat, so this stays actionable rather than dogma:** on review work for code
that must merge (e.g. PR #592), use this as the diagnostic lens and the prioritization
principle for where refactor effort goes — not as a mandate to rewrite everything
functionally. Flag the structural cause, then scope the refactor to the critical paths.

See [[project-pr592-cache-review]] and [[feedback-findings-as-labeled-issues]].
