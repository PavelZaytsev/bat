# Java ownership and time

Use ownership language precisely:

- `Arena` controls the lifetime and thread-confinement characteristics of associated `MemorySegment` views. It does not enforce unique ownership, invalidate aliases on transfer, or provide Rust borrowing.
- `MemorySegment.reinterpret(..., arena, ...)` assigns a new scope to another view of the same backing region. The original arena can still deallocate that region; do not call this an ownership move.
- `scope().isAlive()` is an observation, not a reservation. Do not use check-then-access as a concurrency guarantee; rely on the enforcing access operation and design the close/access protocol.
- `asReadOnly()` restricts one view. It does not prove that no writable alias exists.
- Checker Framework `@Owning` transfers responsibility for must-call obligations such as `close()`. It is not a region or borrow checker and does not prevent later use through an alias.

For native resources, distinguish at least:

1. Lifetime of the Java view.
2. Lifetime and deallocator of the backing allocation.
3. Aliasing and mutability rights.
4. Thread accessibility and memory ordering.
5. Logical byte ownership.
6. Capacity reservation or allocator-pool accounting.

Record each third-party or platform semantic with library/JDK version, environment, evidence, consequence if wrong, and a revalidation trigger. Prefer eliminating the dependency with an invariant over a one-time measurement.

Primary references: [Java 25 `MemorySegment`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/MemorySegment.html),
[Java 25 `Arena`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html), and the
[Checker Framework Resource Leak Checker](https://checkerframework.org/manual/#resource-leak-checker).
