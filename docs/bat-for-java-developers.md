# How BAT works: functions, effects, and composition for Java developers

This guide is for a Java developer who wants to understand BAT without first becoming a functional-programming specialist. It develops one idea from an ordinary function boundary to BAT's current autonomous runtime.

The central claim is:

> A function signature is a claim about what a computation needs, what can go wrong, and what it produces. Many bugs happen because the implementation's real contract is larger than the signature admits. BDR repairs those dishonest boundaries. BAT provides the runtime in which a model can perform that repair while durable state, effects, context, and completion remain independently controlled.

The functional-programming vocabulary here is a **design lens**, not BAT's implementation language. Earlier BAT versions used a Scala 3/ZIO controller. That controller has been removed. Current `main` uses the Python BDR engine plus the phase-opaque `bin/bat-direct` runtime.

## 1. A function signature is a claim

For an important method, distinguish three contracts:

| Contract | Meaning |
|---|---|
| **Intended** | The business decision the function is supposed to make. |
| **Surface** | Parameters, return type, declared failures, receiver, and documentation. |
| **Operational** | Everything it actually reads, infers, mutates, calls, and every way execution can fail. |

BDR looks for consequential places where these contracts have separated.

```java
Decision route(Message message)
```

Now imagine:

```java
Decision route(Message message) {
    if (message.sender().endsWith("@corp.test")) {
        return Decision.ACCEPTED;
    }
    return scanner.scan(message);
}
```

Its apparent contract is:

```text
Message -> Decision
```

Its real contract is closer to:

```text
(Message, authenticated ingress fact, ContentScanner) -> Decision or ScanError
```

The intended policy is narrower:

```text
(Message, authenticated ingress fact) -> scan requirement
```

The scanner should perform the selected effect; it should not also reconstruct the missing policy input.

That matters for composition. If `f : A -> B` and `g : B -> C`, then `g(f(a))` can use only the information `f` preserves in `B`. If `f` discards an authoritative fact, hides a failure, or consults undeclared state, the larger composition inherits the lie.

Composition needs more than matching types. It needs **honest contracts**.

## 2. Referential transparency makes local reasoning possible

A referentially transparent expression can be replaced by its value without changing program behavior.

```java
int total = price + tax;
```

Contrast that with:

```java
long now = System.nanoTime();
```

The second expression consults a changing external clock.

The same issue appears less visibly when code reads mutable fields, thread-local values, global configuration, process environment, caches, or state controlled elsewhere. A method can look like a function of `X` while actually depending on `X` plus a hidden world.

Referential transparency is not semantic correctness:

```java
boolean trusted(Message message) {
    return message.sender().endsWith("@corp.test");
}
```

This is deterministic but still wrong if only an authenticated gateway has authority to establish trust.

BDR's primary target is therefore an **honest information and effect boundary**. Increased referential transparency is a useful consequence and design direction, not the definition of BDR.

Make the authoritative fact explicit:

```java
boolean requiresScan(Ingress ingress) {
    return ingress == Ingress.UNTRUSTED_EXTERNAL;
}
```

The full operation can remain effectful:

```java
Decision route(Message message, Ingress ingress) {
    return requiresScan(ingress)
        ? scanner.scan(message)
        : Decision.ACCEPTED;
}
```

The useful shape is:

```text
effectful authority obtains a fact
              |
              v
pure decision consumes explicit values
              |
              v
effectful boundary performs the decision
```

BDR does not pretend that files, caches, networks, clocks, native memory, or concurrency are pure. It tries to make what *can* be locally reasoned about explicit while keeping genuine effects and authorities visible.

## 3. Effects are a useful mental model

Functional effect systems separate **describing work** from **performing work**.

A method such as:

```java
String readConfig(Path path)
```

looks like `Path -> String`, but execution reads the filesystem, may block, may fail, and may return different content for the same path.

Conceptually:

```text
Path -> Effect<ReadError, String>
```

For understanding BAT, the useful abstraction is:

```text
Effect<R, E, A>
```

where:

- `R` = services or capabilities required to run;
- `E` = failures that prevent a trustworthy result;
- `A` = the successful or domain result.

Earlier BAT documentation expressed this with ZIO's `ZIO[R, E, A]`. That remains useful notation for reasoning about composition, but **current BAT does not use ZIO as its controller**.

Java developers already see a related shape in `CompletionStage.thenCompose`: the result of one operation determines the next operation, and the larger workflow has an observable completion state.

## 4. BDR discovers dishonest requirements, failures, and results

Every BDR finding uses this sentence:

> At site S, consumer C needs fact K from authority A to make decision D, but instead infers K from I.

Through the `Effect<R, E, A>` lens:

- missing `K` may be an undeclared input or capability;
- a crash, timeout, ambiguous absence, or illegal state may be a hidden failure;
- a nullable or overly weak return type may be a dishonest result.

Consider:

```java
V get(K key);
```

If `null` can be cached, the result collapses `Hit(null)` and `Miss`.

An honest result preserves the distinction:

```java
sealed interface LookupResult<V> {
    record Hit<V>(V value) implements LookupResult<V> {}
    record Miss<V>() implements LookupResult<V> {}
}
```

The goal is not to rewrite Java in a functional language. The goal is to make computational boundaries truthful:

```text
hidden context       -> explicit input or capability
hidden failure       -> explicit error or domain outcome
ambiguous success    -> honest algebraic data type
implicit side effect -> controlled effect boundary
```

The current BDR engine is Python. It maintains repository-local `.bdr/` state and validates the methodology's operations, evidence, gates, readiness, and completion invariants. The former Scala/ZIO controller and its duplicate semantic phase machinery were removed.

## 5. One slice is one missing information-flow edge

A BDR slice groups findings by:

```text
(fact authority, missing fact, consumer decision)
```

The slice is data describing one broken information-flow edge.

Executing it is effectful. Conceptually:

```text
runSlice : (repository, slice, tools, model) -> SliceOutcome or ExecutionFailure
```

Expected conclusions such as repaired, falsified, blocked, or requiring authority are legitimate domain outcomes. Execution failure means BAT could not produce a trustworthy conclusion.

Slices may have dependencies. Completing one slice can reveal another, so BDR repeatedly discovers, executes, and rescans until it reaches a validated fixed point or its bounded pass/attempt policy declares non-convergence.

## 6. The six phases progressively repair one arrow

| Phase | Transformation |
|---|---|
| **EXPOSE** | Demonstrate that the apparent contract lacks a required input, hides a failure, or produces the wrong result. |
| **REPRESENT** | Give the missing fact or state an explicit representation and invariants. |
| **ROUTE** | Supply that fact from its authority to every computation requiring it. |
| **COLLAPSE** | Delete the proxy, fallback, duplicate state, or ambiguous representation that impersonated the fact. |
| **SATURATE** | Exercise meaningful neighboring combinations of requirements, failures, and successes. |
| **FALSIFY** | Remove only the repaired connection and prove that the old failure returns. |

For one slice:

```text
dishonest function contract
  -> observable contradiction
  -> explicit missing fact
  -> authoritative fact routing
  -> obsolete inference removed
  -> decision algebra exercised
  -> causal repair demonstrated
```

This is why BDR often increases referential transparency. Hidden context becomes explicit data and policy decisions can often be separated from effects. It does not purify the whole world; it makes the boundary between decisions and genuine effects more honest.

## 7. Current BAT: model-owned engineering, phase-opaque host

The largest architectural change since the original version of this guide is that BAT no longer encodes BDR's six semantic phases in a Scala/ZIO controller.

Live experiments showed that the host and model were maintaining two representations of the same engineering work. The important failures were elsewhere: context exhaustion, maintenance/wire failures, stale or dishonest tracker state, and incorrect model judgments of completion.

Current BAT divides responsibility differently.

### The model owns

- repository investigation;
- boundary discovery and grouping;
- BDR reasoning and phase progression;
- code and test changes;
- interpretation of repository evidence;
- engineering judgment about what remains.

### The BDR engine owns

- durable `.bdr/` state;
- legal operations and phase transitions;
- evidence records and gates;
- readiness and completion invariants;
- bounded convergence state.

### `bin/bat-direct` owns

- model transport;
- bounded tool execution;
- isolated execution environment;
- preregistration and identity checks;
- durable checkpoints and event journal;
- context accounting;
- compaction and cold resume;
- retry and budget policy;
- terminal acceptance.

The host is **phase-opaque**: it does not tell the model which BDR phase to perform or implement a second semantic state machine.

## 8. The current BAT pipe

At a high level:

```text
pinned task + methodology + repository revision + model identity
                            |
                            v
                     model work turn
                            |
                            v
                    validated tool call
                            |
                            v
                 isolated repository action
                            |
                            v
                    durable observation
                            |
                            v
                  model continues BDR
                            |
                            v
             context maintenance when needed
                            |
                            v
                 checkpoint / cold resume
                            |
                            v
                  exact completion signal
                            |
                            v
                independent acceptance
```

A work turn is intentionally simpler than the old controller:

```text
request -> model -> zero-or-one tool call -> tool result -> next request
```

The runtime does not infer semantic phase transitions from model prose.

When context crosses the configured maintenance threshold at a complete tool boundary, BAT asks the model for a strict continuation packet containing durable summary, evidence, unresolved judgments, and latest plan. The runtime validates and records it, starts a fresh provider context, and continues the same logical run.

Context maintenance is part of the runtime protocol, not one of BDR's six semantic phases.

## 9. Effects and authority in the current runtime

The functional-core/imperative-shell idea still maps cleanly onto current BAT without ZIO.

```text
model requests action
      |
      v
runtime validates request
      |
      v
isolated tool performs filesystem/Git/build effect
      |
      v
result is recorded durably
      |
      v
observation returns to model
```

The model cannot make an effect happen merely by claiming it happened.

Completion is similar. Current BAT requires an exact completion signal and then applies independent acceptance conditions. Recent GPT-OSS-120B qualification demonstrated why: the model produced correct Java behavior but was rejected because unresolved/template tracker state remained.

That is the runtime version of an honest function boundary: success must be represented by evidence the authority can verify.

## 10. Context compaction is also an effect boundary

A long autonomous engineering run accumulates methodology, source, tests, build output, searches, tracker state, evidence, decisions, and model history.

The Apache Commons experiment demonstrated that an append-only run can hit the served context boundary exactly. Current BAT treats context maintenance as a transactional runtime effect:

```text
current work state
  -> model emits structured continuation packet
  -> runtime validates schema and hashes
  -> durable checkpoint
  -> old provider context is discarded
  -> new provider context resumes from validated state
```

The continuation packet is not the source of truth for the repository or BDR tracker. It is the model's durable working memory across provider-context replacement.

Repository state, BDR state, runtime journal, and model working memory are related but deliberately not collapsed into one mutable conversation.

## 11. Why this structure helps a language model

A model can be strong at search and local implementation while remaining unreliable at inventing its own causal process, preserving durable state, surviving long context, respecting authority, or deciding when it is truly finished.

BAT supplies:

- a frozen methodology;
- a bounded tool surface;
- durable state outside provider conversation;
- isolated repository effects;
- explicit model/runtime identities;
- context accounting and transactional maintenance;
- bounded retries and budgets;
- checkpoints and replayable event history;
- a completion protocol the model cannot satisfy with persuasive prose alone.

The model remains responsible for the engineering. BAT makes the environment around that engineering reproducible and falsifiable.

This also makes model comparison meaningful. Different OpenAI-compatible deployments can receive the same frozen task, methodology, runtime contract, and acceptance criteria.

## 12. A practical reading route for current `main`

Read the current system in this order:

1. [`README.md`](../README.md) — overview and current run path.
2. [`../skills/refactor/SKILL.md`](../skills/refactor/SKILL.md) — executable BDR workflow.
3. [`../skills/refactor/references/protocol.md`](../skills/refactor/references/protocol.md) — boundary grammar, grouping, phases, evidence, rewinds, and convergence.
4. [`../skills/refactor/references/tracker.md`](../skills/refactor/references/tracker.md) — `.bdr/` state and evidence contracts.
5. [`direct-runtime.md`](direct-runtime.md) — current phase-opaque runtime, isolation, compaction, cold resume, retries, and acceptance.
6. [`convergent-agentic-loops.md`](convergent-agentic-loops.md) — rationale for the phase-opaque host.
7. [`exo-efficiency.md`](exo-efficiency.md) — distributed/open-weight inference observations.
8. [`../experiments/bdrv1/README.md`](../experiments/bdrv1/README.md) — current qualification program.
9. [`../experiments/bdrv1/results/`](../experiments/bdrv1/results/) — preserved Qwen, GPT-OSS, and Gemma run evidence.

For the production-scale target, continue with [`../experiments/bdrv1/MONDAY-TARGET.md`](../experiments/bdrv1/MONDAY-TARGET.md).

`docs/quickstart.md` and the old Scala `loop/` reading path belong to the previous controller architecture and should not be used to understand current `main`.

## 13. A light learning path

Understanding current BAT no longer requires learning Scala or ZIO.

Useful concepts are:

1. **Pure functions and immutable values** — explicit inputs make local reasoning easier.
2. **Algebraic data modeling in Java** — records, sealed interfaces, enums, and explicit result types.
3. **Composition** — small transformations and effectful operations form larger workflows.
4. **Functional core / imperative shell** — keep policy and representation understandable while isolating unavoidable effects.
5. **Authority and information flow** — ask who can actually know a fact before allowing a consumer to infer it.

Optional background:

- [Functional programming in the Scala 3 Book](https://docs.scala-lang.org/scala3/book/fp-intro.html) remains a concise explanation of pure functions and immutable values; Scala itself is not required for BAT.
- [Clojure: Functional Programming](https://clojure.org/about/functional_programming) gives a short explanation of first-class functions and immutable data.
- [Clojure: Values and Change](https://clojure.org/about/state) distinguishes stable identity from changing state.
- [Simple Made Easy](https://www.youtube.com/watch?v=SxdOUGdseq4) by Rich Hickey explains why separating concerns matters more than making operations superficially convenient.
- [Railway-oriented programming](https://fsharpforfunandprofit.com/rop/) visualizes composition of success and failure paths.

Category theory, abstract monad tutorials, optics, tagless-final encodings, Scala, and ZIO are not prerequisites for understanding current BAT.

## The complete idea

At the target-program level, BDR discovers where the Java program's arrows are lying. At the slice level, the model repairs one missing information-flow edge. At the runtime level, BAT composes inference, bounded tools, durable state, context maintenance, and independent acceptance around that engineering process.

```text
make hidden context explicit
  + represent failures and outcomes honestly
  + isolate unavoidable effects
  + compose the resulting operations
  + preserve durable evidence and completion authority
  = current BAT
```

The useful FP lesson survives the implementation change: **read boundaries as contracts and composition points**. The current runtime no longer needs Scala syntax to embody that idea.
