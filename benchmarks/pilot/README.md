# BAT pilot: BDR V2

This directory holds the first controlled execution of BDR V2 using the implementation now shipped
as BAT. The pilot is deliberately small: one authentic Java change, one sealed regression oracle,
one GPT deployment, and one writer.

The acting model receives only an isolated two-commit target repository and the installed BDR
workflow now shipped by BAT. It does not receive the upstream future history, issue, reference fix,
oracle, or hidden test. Evaluator material under `private/` must be mounted outside the actor's
readable sandbox.

The recorded runs intentionally retain BDR engine names, `.bdr/` state paths, schemas, and evidence
identifiers. Those identify the methodology and its audit protocol; they are not obsolete BAT
branding.

## First case

`java-pilot-001` reconstructs an Apache Commons Pool change that made `addObject()` respect a
positive `maxIdle` limit. The public suite at the change head is green. A later maintainer test
exposed a regression in the change. The exact regression and test remain in the private oracle.

The case has been preflighted with BDR engine 2.1.0 and an isolated Java 17/Maven 3.9.11 container:

- the synthetic target contains exactly a base commit and the target change, with no remote;
- the full public test suite passes at the target head: 389 tests, 0 failures, 0 errors, 11 skipped;
- the sealed test passes at the clean base, fails at the target head for its expected assertion,
  and passes at the durable upstream fix; and
- `bdr preflight` accepts the target checkout.

This is a directional smoke test, not a publishable model comparison. A subscription-hosted run
may not expose token or dollar usage; unavailable measurements must be recorded as `null`, never
as zero.

## Scoring

The first result reports only:

- BDR terminal state and audit validity;
- whether the known defect was detected and corrected;
- validated novel defects;
- self-induced regressions introduced, later repaired, or surviving;
- false readiness;
- wall time; and
- model/token/cost identity to the extent the host exposes it.

Do not require the acting model to reproduce the maintainer's patch. Behavioral correction and
preservation are authoritative.

## Recorded runs

- `GPT-TERRA-JAVA-PILOT-001` ended at `blocked_environment` before code review. Apache RAT
  scanned BDR's in-checkout `.bdr` journal and rejected its YAML/JSONL files as unapproved.
- `GPT-TERRA-JAVA-PILOT-002` repeated the same case from a fresh checkout with RAT skipped only
  inside the actor workspace. It reached `ready_for_review`, detected and fixed the sealed bug,
  passed its public checks, and passed independent hidden and public evaluation.

The clean evaluator checkout ran the complete Maven suite with RAT enabled and reported zero
unapproved files. This preserves the harness failure as data while showing that the delivered
source/test patch itself satisfies the project's license check.

`cases/java-pilot-001/target.bundle` preserves the exact synthetic two-commit subject. The
successful run's `artifacts/repository.bundle` preserves the subject plus the actor's delivery
commit. Both bundles contain complete reachable history and are SHA-256 pinned by adjacent JSON.
