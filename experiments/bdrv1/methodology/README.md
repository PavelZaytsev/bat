# Boundary-Driven Refactoring (BDR)

**A bug is code acting on something it does not know.** BDR finds what the code needed to know,
makes it know it explicitly, and the bug class becomes unrepresentable.

Derived while refactoring the CDC value cache in vcf/datastore (PR #592). Eight slices closed,
29 findings, 15 fixed. Validated on that one codebase; **not yet validated on a second**.

## Read in this order

| file | what it is |
|---|---|
| **01-METHOD.md** | the method: find boundaries, order slices, run the six-phase loop. Start here. |
| **02-FINDING-BOUNDARIES.md** | how to FIND a slice, and why a "temporal" K means you have not found the structure yet |
| **03-GUARDRAILS.md** | ~20 guardrails, each with the concrete failure that produced it |
| **04-PHILOSOPHY.md** | the stance underneath: composition over correctness |
| **05-CASE-STUDY.md** | the evidence, the measured foreign facts, and what went wrong |

## The tooling

```bash
cp slices_progress.template.yaml slices_progress.yaml   # then fill it in
python3 slices.py              # render the slice tree
python3 slices.py --check      # validate the tracker against itself (exit 1 on problems)
python3 slices.py --rules      # what --check catches, and what it does NOT
python3 slices.py --selftest   # corrupt a fixture 18 ways, assert every rule fires
```

Requires `pyyaml`. Nothing else.

**Run `--rules` before you trust `--check`.** A green check is a claim about the rules it happens to
contain, not about the work — that distinction cost a full session once.

## The one thing to keep if you keep nothing else

Force every finding into one sentence:

> At `<site>`, the code needs to know **K**, and instead infers K from **I**.

Then group by **K** — the missing fact. Not by file, not by subsystem, not by severity. One real
boundary spanned four classes; those other groupings feel natural and are wrong.

And when K sounds like it is about *time*, you are missing an **ownership structure**, not a clock.
Rust's lifetimes are regions, not durations: it reasons about containment, never about when.

## Status

V1. The phase loop is solid and the tooling enforces 19 rules with a mutation test for each. The
domain-flavoured examples are provisional — everything here is concurrent off-heap memory
management in Java. Before presenting to a team, run it on a second, smaller codebase in a
different domain and see which guardrails survive.
