# How BAT works: functions, effects, and composition for Java developers

This guide is for a Java developer who wants to understand BAT without first becoming a Scala,
Clojure, or functional-programming specialist. It develops one idea from an ordinary function all
the way to BAT's complete agentic loop.

The central claim is:

> A function signature is a claim about what a computation needs, what can go wrong, and what it
> produces. Many bugs happen because the implementation's real contract is larger than the
> signature admits. BDR repairs those dishonest boundaries, and BAT composes the repairs into one
> effectful program.

## 1. A function signature is a claim

It helps to distinguish three contracts for any important method:

| Contract | Meaning |
|---|---|
| **Intended** | The business decision the function is supposed to make. |
| **Surface** | The parameters, return type, declared failures, receiver, and documentation. |
| **Operational** | Every value or service it actually reads, inference and effect it performs, and way execution can fail. |

A well-designed boundary keeps these contracts aligned. BDR looks for consequential places where
they have separated.

Consider this Java method:

```java
Decision route(Message message)
```

The signature claims that:

- `message` contains everything needed to choose a `Decision`;
- every normal outcome is represented by `Decision`; and
- callers do not need to know about any other dependency or failure.

Now imagine the implementation:

```java
Decision route(Message message) {
    if (message.sender().endsWith("@corp.test")) {
        return Decision.ACCEPTED;
    }
    return scanner.scan(message);
}
```

The method says it decides whether to scan a message. What it actually does is:

1. guess whether the ingress was authenticated from a caller-controlled sender string;
2. consult a hidden `scanner` dependency for one branch; and
3. conceal any scanner failure behind whatever behavior `scanner.scan` happens to use.

Its apparent contract is:

```text
Message → Decision
```

Its real contract is closer to:

```text
(Message, authenticated ingress fact, ContentScanner) → Decision or ScanError
```

The intended policy is narrower and clearer:

```text
(Message, authenticated ingress fact) → scan requirement
```

The effectful scanner should perform the action selected by that policy; it should not be entangled
with reconstructing the policy's missing input.

The function's arrow is lying. `Message` is not sufficient input, `Decision` may not describe every
outcome, and an effectful dependency is hidden.

That is the kind of mismatch BDR is designed to find.

This matters for composition. If:

```text
f : A → B
g : B → C
```

then `g(f(a))` builds an `A → C` pipeline. But `g` can only use the information that `f` preserves
in `B`. If `f` discards an authoritative fact, hides a failure, or consults undeclared state, the
larger composition inherits that lie. The downstream function must guess, reach back into the
world, or accept an ambiguous value.

Composition therefore needs more than matching types. It needs **honest contracts**.

## 2. Referential transparency makes local reasoning possible

An expression is **referentially transparent** when it can be replaced by its value without
changing the program's behavior.

For example:

```java
int total = price + tax;
```

If `price` and `tax` are immutable values, replacing `price + tax` with the resulting integer does
not change anything. The expression's behavior is fully explained by its inputs.

This expression is not referentially transparent:

```java
long now = System.nanoTime();
```

Replacing `System.nanoTime()` with one previously observed value changes the program. The expression
consults a changing external clock.

The same problem appears less visibly when code reads a mutable field, thread-local value, global
configuration, process environment, naming convention, or object whose state is controlled
elsewhere. A method can look like a function of `X` while actually being a function of `X` plus a
hidden world.

“Same inputs produce the same returned value” is not sufficient. A method that always returns
`true` while writing to a database is still effectful: replacing the call with `true` removes the
write.

Referential transparency is also not semantic correctness. This function is pure and deterministic:

```java
boolean trusted(Message message) {
    return message.sender().endsWith("@corp.test");
}
```

But it is still wrong if only the authenticated gateway has authority to establish trust. It
consistently computes the wrong fact from an untrusted proxy. BDR's primary target is therefore an
**honest information and effect boundary**. Increased referential transparency is an important
consequence and design direction, not the complete definition of BDR.

Making the ingress fact explicit repairs the pure policy boundary:

```java
boolean requiresScan(Ingress ingress) {
    return ingress == Ingress.UNTRUSTED_EXTERNAL;
}
```

Given the same immutable `Ingress`, `requiresScan` always has the same value and performs no hidden
action. It can be understood and tested locally.

The full routing operation still needs an effectful scanner:

```java
Decision route(Message message, Ingress ingress) {
    return requiresScan(ingress)
        ? scanner.scan(message)
        : Decision.ACCEPTED;
}
```

The design has improved because the policy is now pure and the remaining effect is visible as a
separate concern. This is often called a **functional core with an imperative shell**:

```text
effectful authority obtains a fact
              ↓
pure decision consumes explicit values
              ↓
effectful boundary performs the decision
```

BDR does not try to pretend that files, caches, networks, clocks, native memory, or concurrency are
pure. It tries to make everything that *can* be pure locally understandable, while making the
remaining effects and authorities explicit.

## 3. An IO value describes an effect instead of performing it

Suppose a method has this signature:

```java
String readConfig(Path path)
```

It looks like a function from `Path` to `String`, but evaluating it reads the filesystem, may block,
may throw, and can return different content for the same path at different times.

Functional effect systems represent the operation as data. Conceptually:

```text
Path → IO<ReadError, String>
```

An `IO` value means:

> Here is an immutable description of an operation which, when interpreted, may perform an effect,
> fail with `ReadError`, or succeed with `String`.

Creating the description does not read the file. This distinction lets the program safely build a
larger description before executing anything:

```text
read configuration
  → validate configuration
  → open connection
  → perform request
  → close connection
```

Constructing and composing the description can be referentially transparent even though
interpreting it performs real effects. Running the same description twice will generally perform
those effects twice; IO makes effects explicit and controllable, not imaginary.

### Why people call IO a monad

For understanding BAT, a monad only needs two practical ideas:

1. put an ordinary value into the effect context; and
2. feed the successful value from one effectful operation into the next effectful operation.

The second operation is normally called `flatMap`:

```text
IO<E, A>.flatMap(A → IO<E, B>) = IO<E, B>
```

`map` is the simpler case where the next transformation is pure:

```text
IO<E, A>.map(A → B) = IO<E, B>
```

The operations obey consistency laws so that refactoring a composition does not unexpectedly change
its meaning. The result of composition is another immutable effect description. The runtime
interprets the final description at the edge of the application.

Java developers already use similar composition syntax:

```java
CompletionStage<Routed> prepare(Slice slice) {
    return expose(slice)
        .thenCompose(this::represent)
        .thenCompose(this::route);
}
```

`CompletionStage` is only an analogy: it is generally eager and does not provide ZIO's complete
typed-error, resource, interruption, and environment model. But `thenCompose` demonstrates the same
essential shape: the output of one operation determines the next operation.

## 4. ZIO makes requirements, failures, and success explicit

ZIO generalizes the IO idea:

```text
ZIO[R, E, A]
```

Read it as:

> An immutable description of work that requires an environment `R`, may fail with `E`, and may
> succeed with `A`.

The three positions answer three architectural questions:

| Type | Question |
|---|---|
| `R` | What services or capabilities must be supplied for this operation to run? |
| `E` | Which expected failures can this operation report? |
| `A` | What value proves successful completion? |

For example:

```scala
def route(
    message: Message,
    ingress: Ingress
): ZIO[ContentScanner, ScanError, Decision] =
  if requiresScan(ingress) then scan(message)
  else ZIO.succeed(Decision.Accepted)
```

Here:

- request-specific facts such as `message` and `ingress` are ordinary explicit parameters;
- the reusable `ContentScanner` capability is in `R`;
- an expected scanner failure is in `E`; and
- the successful decision is `A`.

Not every value belongs in ZIO's `R`. A fact about one request normally belongs in a parameter or
immutable record. `R` is most useful for services and capabilities shared by a workflow.

`E` models expected typed failures. ZIO separately preserves defects—unexpected programming faults—
and fiber interruption. Expected negative business conclusions such as `Rejected`, `Blocked`, or
`Terminal` often belong as variants of `A`, because the workflow successfully produced a legitimate
answer.

Scala's `for` syntax composes these descriptions:

```scala
def inspectAndRepair(pr: PullRequest): IO[BatError, Repair] =
  for
    source  <- inspect(pr)
    finding <- discover(source)
    repair  <- repair(finding)
    _       <- verify(repair)
  yield repair
```

Read `<-` as:

> If the previous effect succeeded, give me its value and continue. If it failed, preserve the
> typed failure and stop this path.

`IO[BatError, Repair]` is ZIO shorthand for `ZIO[Any, BatError, Repair]`: the operation's external
requirements have already been supplied or passed explicitly.

## 5. BDR discovers dishonest `R`, `E`, and `A`

Every BDR finding uses this sentence:

> At site S, consumer C needs fact K from authority A to make decision D, but instead infers K from
> I.

Through the `ZIO[R, E, A]` lens:

- missing `K` may be an undeclared input or capability—a hidden part of the real `R`;
- a crash, timeout, ambiguous absence, or illegal state may be a hidden `E`; and
- a nullable or overly weak return type may be a dishonest `A`.

Consider a cache API:

```java
V get(K key);
```

If `null` can be a cached value, the result collapses two distinct facts:

```text
Hit(null)
Miss
```

A consumer that checks `value != null` is inferring cache presence from the payload. The lookup
authority knew whether the key was present, but discarded that information.

An honest result preserves it:

```java
sealed interface LookupResult<V> {
    record Hit<V>(V value) implements LookupResult<V> {}
    record Miss<V>() implements LookupResult<V> {}
}
```

This repair primarily improves `A`, not `R`: the effectful cache lookup now returns an honest value,
and downstream decisions can be pure functions of `LookupResult<V>`.

The goal is not to rewrite target Java in ZIO. The goal is to make its computational boundaries
truthful:

```text
hidden context       → explicit input or capability
hidden failure       → explicit error or domain outcome
ambiguous success    → honest algebraic data type
implicit side effect → controlled effect boundary
```

The current BDR engine is Python and the BAT controller is Scala 3/ZIO. Composition is the design
discipline; no particular target language owns it.

## 6. One slice is one missing information-flow edge

A BDR slice groups findings by:

```text
(fact authority, missing fact, consumer decision)
```

The slice itself is data describing a broken edge. It is not literally a ZIO value. **Executing**
the slice is an effectful program that can be described conceptually as:

```scala
def runSlice(
    slice: Slice
): ZIO[
  Backend & Worker & Bdr & Telemetry,
  SliceExecutionError,
  SliceOutcome
]
```

`SliceOutcome` should contain expected domain conclusions such as repaired, falsified, blocked, or
requiring human authority. Those are legitimate answers, not necessarily program failures. `E`
represents inability to produce a trustworthy answer—for example corrupted state, a stale
workspace, or an invalid model transcript.

Three slices can therefore be thought of as three effect descriptions:

```scala
val first:  ZIO[R, E, SliceOutcome]
val second: ZIO[R, E, SliceOutcome]
val third:  ZIO[R, E, SliceOutcome]
```

They may compose sequentially:

```scala
for
  a <- first
  b <- second
  c <- third
yield Chunk(a, b, c)
```

But real slices form a dependency graph. Discovery can sometimes be parallel, while mutations of
one shared Git workspace are normally serialized. Completing one slice can also reveal another, so
BAT repeatedly discovers, executes, and rescans until BDR reaches a validated fixed point.

## 7. The six phases progressively repair one arrow

The BDR phases are transformations rather than ceremonial checkpoints:

| Phase | Transformation through the `R`, `E`, `A` lens |
|---|---|
| **EXPOSE** | Demonstrate that the apparent contract lacks a required input, hides a failure, or produces the wrong success. |
| **REPRESENT** | Give the missing fact or state an explicit type and invariants. |
| **ROUTE** | Supply that fact from its authority to every computation requiring it. |
| **COLLAPSE** | Delete the proxy, fallback, duplicate state, or ambiguous representation that impersonated the fact. |
| **SATURATE** | Exercise the meaningful neighboring combinations of requirements, failures, and successes. |
| **FALSIFY** | Remove only the repaired connection and prove that the old failure returns. |

For one slice:

```text
dishonest function contract
  → observable contradiction
  → explicit missing fact
  → authoritative fact routing
  → obsolete inference removed
  → decision algebra exercised
  → causal repair demonstrated
```

This is why BDR tends to increase referential transparency. Hidden context becomes explicit data,
and policy decisions can often be separated from effects. It does not purify the whole world; it
makes the boundary between pure decisions and genuine effects honest.

## 8. BAT composes slice repair into one large effect

At a high level, BAT connects these boundaries:

```text
pinned pull request
  → reasoning backend
  → typed model turn
  → validated tool call
  → isolated Java operation
  → authenticated evidence
  → validated BDR transition
  → next model request
  → terminal BDR decision
  → independently evaluated handoff
```

The model does not directly mutate BAT state and cannot declare itself finished. It proposes a tool
call. A bounded tool performs an action. BDR decides whether the resulting state transition is
legal. A fresh BDR checkpoint—not persuasive model prose—decides whether the run is ready.

Conceptually, the repeating step is:

```scala
def step(state: RunState): ZIO[Runtime, RunError, RunState]
```

BAT repeats that effect until BDR proves the state is terminal or a budget stops the run.

The repository's top-level production composition is
[`ProductionRunner`](../loop/src/main/scala/bat/runner/ProductionRunner.scala). Its public result is
approximately:

```scala
IO[BatError, ProductionRunResult]
```

It appears to have `R = Any` because backend, worker, evaluator, configuration, and telemetry are
supplied explicitly through arguments and factories. Before supplying them, its conceptual shape is:

```scala
ZIO[
  BackendFactory & WorkerFactory & ProductionEvaluator & Telemetry,
  BatError,
  ProductionRunResult
]
```

Providing those implementations closes the environment. At the application edge, the ZIO runtime
interprets the resulting description and performs the network, filesystem, Git, model, and process
effects.

So yes: after composition, the entire BAT run is one large `ZIO[R, E, A]`. That does not make it a
monolith. It is an immutable program description whose internal structure remains a graph of small
typed descriptions.

## 9. The real BAT pipes

The following components make the abstract story concrete.

### Reasoning backend

[`Backend.complete`](../loop/src/main/scala/bat/protocol/Backend.scala) composes:

```text
validate request → invoke provider safely → validate returned turn
```

Provider-specific encoding, streaming, tool parsing, and reasoning replay stay behind this boundary.
The same controller can therefore work with different reasoning backends without changing BDR.

### Controlled toolbox

[`WorkerTools.all`](../loop/src/main/scala/bat/worker/WorkerTools.scala) assembles small operations
for reading, searching, patching, diffing, building, and committing.
[`ToolRegistry`](../loop/src/main/scala/bat/controller/ToolRegistry.scala) validates model calls,
enforces authority, executes the chosen tool, and normalizes its result.

```scala
BdrTools.all(bdr) ++ workerTools
```

Two small collections compose into the exact capability surface given to the model.

### Receipt-bound BDR

[`BdrSession`](../loop/src/main/scala/bat/bdr/BdrSession.scala) is the small state-machine boundary.
[`ReceiptBoundBdrSession`](../loop/src/main/scala/bat/worker/WorkerBdr.scala) wraps it:

```text
proposed transition
  → replace claims with authenticated worker evidence
  → validate authority
  → apply through BDR
```

The original pipe remains focused; a safety pipe is composed in front of it.

### Isolated Java worker

[`JavaWorkerSession`](../loop/src/main/scala/bat/worker/JavaWorker.scala) is an assembly line:

```text
authenticate PR
  → allocate private workspace
  → provision exact commits
  → verify and seal workspace
  → initialize or resume BDR
  → open durable ledger
  → expose bounded tools
```

The file is large because isolation, Git identity, recovery, and cleanup require defensive code. It
is not the best first file to read.

### Telemetry side pipe

[`Telemetry`](../loop/src/main/scala/bat/telemetry/Telemetry.scala) observes sanitized inference and
orchestration events without becoming their authority. The same workflow can compose with a no-op,
in-memory, or durable interpreter.

## 10. Why this structure helps a language model

A model may be good at search and local implementation while remaining unreliable at inventing its
own causal process, remembering durable state, respecting authority, and deciding when it is truly
done.

BAT supplies:

- a bounded unit of work;
- explicit inputs, outputs, and legal transitions;
- a narrow validated tool surface;
- durable state independent of provider conversation;
- focused evidence and authenticated command receipts;
- budgets, authority boundaries, interruption, and cleanup; and
- a definition of done that the model cannot talk its way around.

Composition also makes model comparisons meaningful. A scripted backend, GPT-OSS deployment, or
future Claude adapter can drive the same loop and BDR state machine. Changing the model does not
silently change the methodology being evaluated.

## 11. A practical reading route

Read BAT in this order:

1. [`README.md`](../README.md): motivation, finding grammar, and the six phases.
2. [`docs/quickstart.md`](quickstart.md): one complete Java regression.
3. [`ToyScenario.scala`](../loop/src/main/scala/bat/quickstart/ToyScenario.scala): a small
   composition root.
4. [`Backend.scala`](../loop/src/main/scala/bat/protocol/Backend.scala): provider boundary.
5. [`ToolRegistry.scala`](../loop/src/main/scala/bat/controller/ToolRegistry.scala): schema and
   authority enforcement.
6. [`AgenticLoop.scala`](../loop/src/main/scala/bat/controller/AgenticLoop.scala): repeating model →
   tool → checkpoint composition.
7. [`BdrSession.scala`](../loop/src/main/scala/bat/bdr/BdrSession.scala): durable state authority.
8. [`WorkerTools.scala`](../loop/src/main/scala/bat/worker/WorkerTools.scala): bounded real effects.
9. [`ProductionRunner.scala`](../loop/src/main/scala/bat/runner/ProductionRunner.scala): full
   composition root.

Read the corresponding `*Spec.scala` after each production file. BAT's tests are executable
architecture documentation. Do not begin with `JavaWorker.scala`; understand the small boundaries
before reading their defensive implementation.

While tracing a component, ask:

1. What does its signature claim?
2. What does its implementation actually require or perform?
3. What immutable value enters?
4. What value proves success?
5. What typed failures can leave?
6. Who has authority to validate the result?
7. Can this component be replaced without changing BDR?

## 12. A light learning path

These resources are ordered. They are intentionally not a complete FP syllabus.

1. **[Scala for Java programmers](https://docs.scala-lang.org/tutorials/scala-for-java-programmers.html)**
   — use familiar JVM concepts to cross the syntax gap.
2. **[Functional programming in the Scala 3 Book](https://docs.scala-lang.org/scala3/book/fp-intro.html)**
   — immutable values, pure functions, functions as values, and explicit errors.
3. **[Higher-order functions in Scala](https://docs.scala-lang.org/scala3/book/fun-hofs.html)**
   — functions as inputs and outputs of other functions.
4. **[Scala 3 domain modeling](https://docs.scala-lang.org/scala3/book/taste-modeling.html)**
   — case classes, enums, sum types, and pattern matching.
5. **[The core ZIO type](https://zio.dev/reference/core/zio/)** — initially focus on
   `ZIO[R, E, A]`, `map`, `flatMap`, and `for` expressions.

Optional conceptual material:

- **[Clojure: Functional Programming](https://clojure.org/about/functional_programming)** gives a
  short explanation of first-class functions and immutable data.
- **[Clojure: Values and Change](https://clojure.org/about/state)** distinguishes stable identity
  from changing state—a useful model for repositories, runs, and BDR revisions.
- **[Simple Made Easy](https://www.youtube.com/watch?v=SxdOUGdseq4)** by Rich Hickey explains why
  separating concerns matters more than making operations superficially convenient.
- **[Railway-oriented programming](https://fsharpforfunandprofit.com/rop/)** visualizes the
  composition of success and failure paths. Its F# examples map directly to `Either` and ZIO's error
  channel.

Category theory, abstract monad tutorials, optics, tagless-final encodings, and advanced ZIO layers
are not prerequisites for understanding BAT.

## The complete idea

At the target-program level, BDR discovers where the Java program's arrows are lying. At the slice
level, BAT executes an effectful repair program that makes one arrow honest. At the runtime level,
BAT composes all inference, tools, state transitions, evidence, and evaluation into one large typed
effect.

```text
make hidden context explicit
  + represent failures and outcomes honestly
  + isolate unavoidable effects
  + compose the resulting programs
  = BAT
```

Once this clicks, the Scala syntax becomes secondary. Read types as contracts and connection points,
and read `for` expressions as assembly diagrams.
