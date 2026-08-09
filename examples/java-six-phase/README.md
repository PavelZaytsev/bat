# Java six-phase fixture

This is BAT's small, dependency-free Java 17 canary for a complete Boundary-Driven
Refactoring run. It is deliberately small enough to make every phase observable, while the
defect is a real information-boundary failure rather than a planted `TODO` or a broken build.

## Subject

The subject is a message ingress gateway. Calls entering through `acceptInternal` have already
crossed an authenticated boundary and may bypass content scanning. Calls entering through
`acceptExternal` must be scanned regardless of the sender text supplied by the caller.

The base revision carries that fact explicitly as a boolean argument. The target revision performs
a plausible API simplification: it removes the argument and has the router infer trusted ingress
from a sender ending in `@corp.test`. Its two public tests still pass, but the refactor discarded the
only fact that distinguishes authenticated ingress from an untrusted caller claiming a corporate
address.

The BDR boundary is:

> At `MessageRouter.route`, the scanner decision needs the ingress trust fact from the
> `IngressGateway` entrypoint, but the target infers that fact from the sender suffix.

The intended structural direction is to represent ingress trust explicitly, route it from both
gateway producers to the router consumer, and delete the suffix inference.

## Layout and visibility

- `subject/base/` is the correct base tree and contains the actor-visible public assertion suite.
- `subject/head.patch` creates the buggy target revision from that base.
- `oracle/` contains an evaluator-owned copy of the complete four-cell decision algebra. Never copy
  it into the actor checkout.
- `reference/repair.patch` is one possible final repair and is not a gold implementation contract.
  Never copy it into the actor checkout.
- `reference/phases/` splits that repair into deterministic scripted-inference steps for the BAT
  portability canary. These patches are also evaluator-side reference assets, not actor input and
  not an exact-patch scoring contract.

An actor subject must be materialized into a separate repository:

1. Copy only the contents of `subject/base/` into an empty directory.
2. Commit that tree as the base revision.
3. Apply `subject/head.patch` and commit it as the target revision.
4. Give BAT only that resulting repository. Do not copy this fixture directory, `oracle/`, or
   `reference/` into the actor workspace.

This preserves ordinary base/head Git history while keeping the evaluator and reference assets
outside the model-visible subject.

## Dependency-free checks

From a materialized subject checkout, compile and run the public suite with a Java 17 JDK:

```sh
classes="$(mktemp -d)"
javac --release 17 -d "$classes" $(find src/main/java src/test/java -name '*.java' -print)
java -cp "$classes" dev.bat.examples.ingress.IngressGatewayPublicTest
```

To evaluate a checkout, compile the evaluator source in `oracle/src/test/java` together with the
subject sources, then run `dev.bat.examples.ingress.IngressGatewayHiddenTest`. The expected matrix
is recorded in `manifest.json`:

- base: public green, hidden green;
- target head: public green, hidden red at an assertion; and
- repaired head: public green, hidden green.

The lean six-phase cadence is one baseline public run, one focused red EXPOSE assertion, structural
evidence for REPRESENT/ROUTE/COLLAPSE, one focused green SATURATE run, one counterfactual red
FALSIFY run followed by exact restoration, and one final broad public run.
