# Six-phase Java quickstart

This quickstart executes BAT's complete controller/BDR contract against a tiny Java 17 regression.
It is the portability canary that a real provider backend must pass before BAT spends time and
tokens on a larger benchmark case.

The subject is intentionally small, but the bug is not a planted `TODO`. A correct base revision
passes an explicit ingress-trust fact into a message router. A plausible target refactor removes
that parameter and starts guessing trust from a caller-controlled sender suffix. The public tests
cover the passing diagonal; an evaluator-owned suite independently covers all four cells, including
the two off-diagonal cases that expose the regression.

## Prerequisites

- a clean BAT checkout (the BDR bridge verifies the exact engine commit and rejects local drift);
- Git;
- Python 3.10 or newer;
- a JDK 21 runtime for the Scala controller; and
- Scala CLI 1.16 or newer.

The Java subject itself is dependency-free and compiles with `javac --release 17`. Once the Scala
controller dependencies are provisioned, the canary makes no provider, target-dependency, Docker,
or GPU calls. On a cold machine, Scala CLI may first resolve BAT's own Scala/ZIO dependencies into
its Coursier cache; ordinary CI runs the canary offline after the Scala test step has provisioned
them.

The local Java harness is intentionally a canary runner, not an OS sandbox. It uses absolute
`javac`/`java` executables, a sanitized environment, disposable class output, a non-repository
working directory, and post-run fingerprint validation, but the fixed fixture code still executes
as a local process. Do not point these toy tools at untrusted Java. Real pull-request code must keep
using BAT's networkless, non-root OCI Java worker.

## Run it

From the BAT repository root:

```bash
scala-cli run --server=false loop --main-class bat.quickstart.ToyQuickstart
```

BAT writes a sanitized JSON summary to standard output and prints the temporary artifact directory
to standard error. That directory contains:

- `subject/`: the standalone actor-visible Git repository;
- `subject/.bdr/`: real BDR state and its append-only event journal;
- `summary.json`: stable pins, cadence, terminal state, and evaluator result; and
- `safe-trace.json`: the reasoning- and tool-payload-redacted controller trace.

## What the run proves

The scripted backend crosses the same strict `Backend`, `ModelRequest`, function-call, and
continuation boundary a hosted OpenAI or GPT-OSS adapter uses. It does not edit files or mutate BDR
state directly. Every action goes through the real provider-neutral agentic loop, strict toy tools,
and commit-verified BDR engine.

The run must:

1. materialize a correct base commit and the buggy target commit with no remote;
2. expose only the subject checkout to actor tools—never the oracle or reference assets;
3. execute **EXPOSE → REPRESENT → ROUTE → COLLAPSE → SATURATE → FALSIFY**;
4. finish at BDR revision 19 in `ready_for_review`;
5. create one local delivery commit and never push it; and
6. pass the independent evaluator after the loop stops.

The actor-side verification budget is exactly five invocations:

| invocation | expected result |
|---|---|
| baseline public suite | green |
| EXPOSE focused public assertion | red at the pinned assertion fingerprint |
| SATURATE four-cell public decision algebra | green |
| FALSIFY with only the collapsed inference restored | red at the same pinned fingerprint |
| final public suite at the fixed point | green |

REPRESENT, ROUTE, and COLLAPSE use code/diff structure rather than ritual test reruns. The hidden
evaluator runs separately and is not counted as actor verification.

## What the run does not prove

Scripted success proves that BAT can transport tool calls, preserve workspace preconditions, drive
real BDR transitions, enforce the economical cadence, commit the repair, stop correctly, and keep
the evaluator outside the actor boundary. It does **not** prove that a model can discover or repair
the bug.

The GPT-OSS adapter will reuse this exact subject, BDR contract, cadence, and evaluator next, but it
must execute model-authored Java through BAT's OCI-isolated Java worker—not through the trusted local
toy harness. A 20b deployment is sufficient to kill wire/Harmony/tool-continuation defects cheaply;
120b then measures inference quality. Any 120b deployment, including the measured
Thunderbolt-connected exo cluster, must record the same BAT commit, toy revision, base/head pins,
deployment fingerprint, trace, verification cadence, and evaluator outcome. Only then should a run
be promoted into benchmark evidence.

The maintained canary lives under [`examples/java-six-phase/`](../examples/java-six-phase/). It is
separate from the immutable records under `benchmarks/pilot/` by design.
