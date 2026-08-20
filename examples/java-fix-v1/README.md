# Java `/fix` V1 qualification fixture

This dependency-free Java 25 fixture qualifies one bounded BAT `/fix` objective. It is deliberately
small, but its root bug crosses two information-flow boundaries and cannot be closed honestly by a
single local exception or cleanup refactor.

The issue claim is:

> A completion from an older streaming-cache load can overwrite the newest load, and an ordinary
> backend miss can escape as an exception instead of remaining a cache outcome.

The root proof starts two generations, completes the older one with a value, then completes the
newest one with `Missing`. The buggy tree fails because `RetryCoordinator` loses the attempt token,
`StreamingCache` infers that every callback belongs to the active generation, and `Missing` is
thrown as `NoSuchElementException`.

## Expected bounded scope

- `S-0001 completion authority` is a root slice. The cache's active generation is the authority;
  the commit/drop decision needs the callback generation instead of inferring it from callback
  arrival.
- `S-0002 retry generation` is required. `RetryCoordinator` must route the `LoadAttempt` from the
  load producer to the cache completion consumer. The root proof cannot pass without this edge.
- The overwrite and exception are multiple symptoms of the missing completion edge; they are not
  license for repository-wide refactoring.
- `F-0017 debugEntryCount naming` is an intentionally unrelated observation. It must remain
  out-of-scope, unassigned, and unchanged.

`CacheOutcome.Missing` is an expected domain result and must remain value-based. The reference
repair uses the smallest local sealed outcome and a `CompletionDisposition`; it adds no universal
`Result`/`Either`, FP dependency, or broadened exception path. The reference is one valid repair,
not an exact-patch contract.

## Deterministic runs

With a Java 25 JDK, run the buggy proof (expected assertion failure), repaired proof (expected
success), and the aggregate counterfactual (expected the original assertion failure):

```sh
classes="$(mktemp -d)"
javac --release 25 -d "$classes" $(find subject/buggy/src -name '*.java' -print)
java -cp "$classes" dev.bat.examples.cache.StreamingCacheRootProof

classes="$(mktemp -d)"
javac --release 25 -d "$classes" $(find reference/repaired/src -name '*.java' -print)
java -cp "$classes" dev.bat.examples.cache.StreamingCacheRootProof
```

For the aggregate counterfactual, retain
`reference/repaired/src/test/java/.../StreamingCacheRootProof.java` as the proof harness but compile
it against `subject/buggy/src/main/java`. It must restore the assertion fingerprint recorded in
`manifest.json`. Then restore the repaired production files and verify the exact workspace digest
before recording objective closure.

The first private Sonnet run should use the same observable sequence: root red, required slices
through all six phases, focused green, aggregate repair removed to root red, exact restoration,
root-focused rescan, and closure. Unrelated findings do not block that closure.
