# How BAT works: composition for Java developers

This guide is for a Java developer who wants to understand BAT without first becoming a Scala,
Clojure, or functional-programming specialist. The goal is to make the architecture readable in
about 30–45 minutes.

The short version is:

> BAT is built by connecting small components with explicit inputs, outputs, failures, and
> authority. Each component does one job; their composition produces the complete agentic loop.

Functional composition is not foreign to Java. `Stream.map`, `Optional.flatMap`,
`CompletionStage.thenCompose`, immutable records, and sealed interfaces already use the same basic
idea. Scala 3 and ZIO make that idea a consistent architectural vocabulary.

## One mental model: typed pipes

A simple function is a pipe:

```text
A => B
```

It accepts an `A` and produces a `B`. If another function accepts that `B`:

```text
B => C
```

the two functions compose into:

```text
A => C
```

Real programs also use files, networks, subprocesses, clocks, concurrency, and failures. BAT's
effectful pipes usually have this shape:

```text
A => ZIO[R, E, B]
```

Read `ZIO[R, E, B]` as:

> A description of work that requires `R`, may fail with `E`, and may succeed with `B`.

For example:

```scala
def inspect(pr: PullRequest): IO[BatError, Finding]
```

`IO[BatError, Finding]` is shorthand for a ZIO workflow with no additional environment
requirement, a typed `BatError` failure, or a successful `Finding` value.

A ZIO value is a **description** of work. Constructing one does not immediately open a file, call a
model, or start a process. Because work is represented as a value, BAT can uniformly compose error
handling, interruption, timeouts, cleanup, telemetry, and budgets around it.

People may call this *monadic composition*. To read BAT, no category theory is required. Translate
`flatMap` or a Scala `for` expression as:

> If the previous step succeeded, feed its value into the next step. If it failed, preserve the
> typed failure and stop this path.

## The same pipe in Java and Scala

Here is a deliberately simplified Java workflow:

```java
CompletionStage<Routed> prepare(Slice slice) {
    return expose(slice)
        .thenCompose(this::represent)
        .thenCompose(this::route);
}
```

The Scala/ZIO shape is the same:

```scala
def expose(slice: Slice): IO[BatError, Exposed]
def represent(exposed: Exposed): IO[BatError, Represented]
def route(represented: Represented): IO[BatError, Routed]

def prepare(slice: Slice): IO[BatError, Routed] =
  for
    exposed     <- expose(slice)
    represented <- represent(exposed)
    routed      <- route(represented)
  yield routed
```

In the Scala version:

- `<-` obtains the successful value and feeds it into the next step;
- a typed failure short-circuits the remaining steps;
- immutable intermediate values make boundaries visible; and
- different input and output types can make an invalid ordering impossible to express.

This example is only a teaching model. BAT's actual BDR phases are durable, validated state
transitions rather than six Scala functions with six different return types.

## BAT is a network of boundaries

At a high level, a BAT run is this composition:

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

The model does not directly mutate BAT's state and cannot declare itself finished. It proposes a
tool call. A bounded tool performs an action. BDR decides whether the resulting state transition is
legal. A fresh BDR checkpoint—not persuasive model prose—decides whether the run is ready.

### 1. Provider backends are replaceable cartridges

[`Backend.complete`](../loop/src/main/scala/bat/protocol/Backend.scala) composes three operations:

```text
validate request → invoke provider safely → validate returned turn
```

The controller sees a normalized `ModelTurn`; it does not need to understand GPT-OSS Responses,
Harmony Chat, Claude Messages, or another provider's native transcript. Provider-specific encoding,
stream decoding, and reasoning replay live behind the `Backend` boundary.

This is why a new reasoning model does not require a new BDR methodology or agent loop. It requires
a small adapter that satisfies the same typed contract.

### 2. Small tools become one controlled toolbox

[`WorkerTools.all`](../loop/src/main/scala/bat/worker/WorkerTools.scala) assembles focused operations
such as reading, searching, applying a patch, inspecting a diff, running an admitted Java build,
and committing. Each tool has the same general shape:

```text
validated invocation → typed effect → structured result
```

[`ToolRegistry`](../loop/src/main/scala/bat/controller/ToolRegistry.scala) validates a model call,
checks whether its authority is allowed in the current run mode, executes the selected tool, and
normalizes its result. The agent loop does not need a special branch for every tool.

Composition here is also visible in ordinary collection syntax:

```scala
BdrTools.all(bdr) ++ workerTools
```

Two small tool collections become the exact surface exposed to the model.

### 3. Safety can wrap an existing pipe

[`BdrSession`](../loop/src/main/scala/bat/bdr/BdrSession.scala) is a small interface over the
deterministic BDR state engine. [`ReceiptBoundBdrSession`](../loop/src/main/scala/bat/worker/WorkerBdr.scala)
wraps it.

Reads pass through. Before a write, the wrapper replaces model-authored claims about tests and
commands with authenticated receipts from the isolated worker. Only then does it call the original
BDR session.

Conceptually:

```text
proposed BDR operation
  → materialize trusted worker evidence
  → validate authority
  → apply through original BDR session
```

The original state-machine pipe remains small. A safety pipe is snapped onto its front.

### 4. The Java worker is an assembly line

[`JavaWorkerSession`](../loop/src/main/scala/bat/worker/JavaWorker.scala) composes many narrow steps:

```text
authenticate PR pins
  → allocate a private workspace
  → provision the exact commits
  → verify and seal the workspace
  → initialize or resume BDR
  → open the durable operation ledger
  → expose bounded worker tools
```

The file is large because process isolation, Git identity, cleanup, replay, and crash recovery need
defensive code. It is not the best first file to read. Its architecture is still the same: every
successful stage feeds the next, while a typed failure stops the assembly line.

### 5. `ProductionRunner` is the composition root

[`ProductionRunner`](../loop/src/main/scala/bat/runner/ProductionRunner.scala) is where the larger
machine is assembled. It joins:

- a reasoning `Backend`;
- an isolated `ActorWorker`;
- the receipt-bound BDR session;
- the BDR and worker tool collections;
- the versioned model instructions and budgets;
- one telemetry collector; and
- a separately supplied trusted evaluator.

The runner does not know how Harmony tokens are parsed, how Maven executes, or how a BDR finding is
validated. Those responsibilities belong to smaller components. The runner's job is composition,
ordering, and boundary enforcement.

### 6. Telemetry is a side pipe

[`Telemetry`](../loop/src/main/scala/bat/telemetry/Telemetry.scala) has one operation: emit a
sanitized event. BAT can compose the same main workflow with a no-op collector, an in-memory
collector, or eventually a durable collector.

Telemetry observes inference and orchestration without becoming their authority. It is connected
alongside the main pipe rather than scattered through the business logic.

## BAT and BDR are different layers

- **BDR** is the deterministic methodology and state machine: findings, slices, evidence, legal
  transitions, the six phases, and the definition of done.
- **BAT** is the agentic runtime: reasoning backends, tools, worker isolation, orchestration,
  telemetry, and handoff.

Scala and ZIO compose BAT's execution. BDR gives that execution its diagnostic grammar. Provider
conversation state is temporary; validated `.bdr/` state and its journal are durable truth.

The current BDR engine is implemented in Python while the BAT controller is Scala 3/ZIO. That is a
useful reminder that composition is a design discipline, not a feature owned by one language.

For one slice, BDR itself can be read as a dataflow:

```text
symptom
  → reproducible evidence
  → explicit missing fact
  → fact routed from its authority
  → obsolete inference deleted
  → adjacent decision algebra exercised
  → causal repair challenged counterfactually
```

The six phases are transformations, not ceremonial checkpoints:

- **EXPOSE:** reachable defect → focused failing evidence;
- **REPRESENT:** missing knowledge → explicit data and invariants;
- **ROUTE:** authoritative data → every consumer that needs it;
- **COLLAPSE:** explicit fact → obsolete guessing becomes deletable;
- **SATURATE:** repaired decision → neighboring cases are exercised; and
- **FALSIFY:** plausible repair → causal repair is demonstrated.

## Why this structure helps an LLM

A language model can be good at search and local implementation while remaining unreliable at
inventing its own causal process, remembering durable state, respecting authority, and deciding
when it is actually done.

BAT supplies the missing structure:

- a bounded unit of work;
- explicit inputs, outputs, and legal transitions;
- a narrow, validated tool surface;
- durable state independent of the provider transcript;
- focused evidence and authenticated command receipts;
- budgets, authority boundaries, and interruption behavior; and
- a definition of done that the model cannot talk its way around.

Composition also makes experiments meaningful. A scripted backend, GPT-OSS deployment, or future
Claude adapter can drive the same loop and BDR state machine. Changing the model does not silently
change the methodology being evaluated.

## A practical reading route

Read BAT in this order:

1. [`README.md`](../README.md): the motivation, finding grammar, and six phases.
2. [`docs/quickstart.md`](quickstart.md): one complete Java regression from buggy commit to
   independent evaluation.
3. [`ToyScenario.scala`](../loop/src/main/scala/bat/quickstart/ToyScenario.scala): a small
   composition root.
4. [`Backend.scala`](../loop/src/main/scala/bat/protocol/Backend.scala): the reasoning-provider
   boundary.
5. [`ToolRegistry.scala`](../loop/src/main/scala/bat/controller/ToolRegistry.scala): schema and
   authority enforcement.
6. [`AgenticLoop.scala`](../loop/src/main/scala/bat/controller/AgenticLoop.scala): the repeating
   model → tool → checkpoint composition.
7. [`BdrSession.scala`](../loop/src/main/scala/bat/bdr/BdrSession.scala): the durable state
   authority.
8. [`WorkerTools.scala`](../loop/src/main/scala/bat/worker/WorkerTools.scala): bounded real-world
   effects.
9. [`ProductionRunner.scala`](../loop/src/main/scala/bat/runner/ProductionRunner.scala): the full
   composition root.

Read each corresponding `*Spec.scala` after the production file. BAT's tests are executable
architecture documentation. Do not begin with `JavaWorker.scala`; understand the small boundaries
before reading the defensive machinery that implements them.

While tracing one turn, ask:

1. What immutable value enters this boundary?
2. What value can leave successfully?
3. What typed failure can leave?
4. What real-world effect is being described?
5. Which component has authority to validate the result?
6. Could this component be replaced without changing BDR?

## A light learning path

These resources are enough to understand BAT. They are ordered; this is intentionally not a giant
functional-programming syllabus.

1. **[Scala for Java programmers](https://docs.scala-lang.org/tutorials/scala-for-java-programmers.html)**
   — skim the syntax bridge; do not memorize everything.
2. **[Functional programming in the Scala 3 Book](https://docs.scala-lang.org/scala3/book/fp-intro.html)**
   — immutable values, pure functions, functions as values, and explicit error handling.
3. **[Higher-order functions in Scala](https://docs.scala-lang.org/scala3/book/fun-hofs.html)**
   — how functions become inputs and outputs of other functions.
4. **[Scala 3 domain modeling](https://docs.scala-lang.org/scala3/book/taste-modeling.html)**
   — case classes, enums, sum types, and pattern matching. This explains much of BAT's protocol
   model.
5. **[The core ZIO type](https://zio.dev/reference/core/zio/)** — focus only on `ZIO[R, E, A]`,
   `map`, `flatMap`, and `for` expressions on the first pass.

Optional conceptual material:

- **[Clojure: Functional Programming](https://clojure.org/about/functional_programming)** is a
  short, language-light explanation of first-class functions and immutable data.
- **[Clojure: Values and Change](https://clojure.org/about/state)** separates a stable identity
  from its changing states—a useful model for repositories, runs, and BDR revisions.
- **[Simple Made Easy](https://www.youtube.com/watch?v=SxdOUGdseq4)** by Rich Hickey explains why
  separating concerns matters more than making each individual operation superficially convenient.
- **[Railway-oriented programming](https://fsharpforfunandprofit.com/rop/)** uses a visual railway
  analogy for composing success and failure paths. The examples use F#, but the idea maps directly
  to `Either` and ZIO's typed error channel.

Category theory, abstract monad tutorials, optics, tagless-final encodings, and advanced ZIO layers
are not prerequisites for understanding BAT. They can come later if they become useful.

## The takeaway

BAT is not one giant clever algorithm. It is a set of small, typed pieces whose contracts make them
safe to connect:

```text
small pipes + explicit authority + durable evidence + composition = BAT
```

Once that clicks, the Scala syntax becomes secondary. Read the types as connection points and the
`for` expressions as assembly diagrams.
