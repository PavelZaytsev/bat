# Designing autonomous agentic loops that converge

An autonomous coding loop is useful only when it can make evidence-backed progress across a long
run, survive context loss, and stop with a result that can be independently checked. More
controller machinery does not automatically improve those properties. A controller can be locally
correct while presenting the model with an action language that is expensive, brittle, or alien to
the way it learned to work.

This note describes the architecture supported by the BAT and direct BDRv1 experiments. It is a
blueprint rather than a claim that one model, language, or implementation wins universally. The
important variable is the boundary between model cognition and host control.

## The central design rule

**Constrain effects and authority; do not encode the model's thinking in the controller.**

The model should own:

- exploration, hypothesis formation, and planning;
- selection and ordering of safe repository actions;
- methodology phase decisions and evidence-backed rewinds;
- implementation strategy and interpretation of test results; and
- deciding when the candidate result is ready to submit.

The host should own:

- model, endpoint, task, methodology, and policy identity;
- sandboxing, credentials, tool authority, and external side effects;
- durable intent, at-most-once execution, and replay protection;
- context-window monitoring, compaction, and cold-process recovery;
- cost, time, token, and runaway limits;
- tamper-evident checkpoints and evidence provenance; and
- independent acceptance, publishing, and terminal-state rules.

The useful analogy is a microkernel. The model is the planner and researcher. The methodology is an
executable constitution. The controller is the kernel that controls resources and effects; it is
not the executive that decides every cognitive step.

## Why a smaller loop can outperform a typed phase controller

The direct BDRv1 runner gives the model a frozen methodology, a readable tracker, an ordinary
repository, and a familiar shell. It remains phase-opaque: it records and validates effects without
deciding what EXPOSE, REPRESENT, ROUTE, COLLAPSE, SATURATE, or FALSIFY should mean for the current
defect.

A semantic phase controller instead asks the model to translate each intention through additional
representations: a controller state projection, a phase-specific next action, a custom tool
vocabulary, revisions and fingerprints, receipt identifiers, and sometimes JSON encoded inside
another tool's JSON arguments. Each representation can be type-correct and still be stale, lossy,
hard for the model to use, or semantically wrong.

| Property | Phase-opaque loop | Semantic phase controller |
|---|---|---|
| Model action language | Shell, files, Git, tests | Bespoke RPC and transition algebra |
| Method state | Readable repository artifact | Controller projection plus engine state |
| Planning authority | Model | Split between model and controller |
| Invalid action handling | Validate effects at the authority boundary | Reject before expression or execution |
| Recovery source | Repository, tracker, checkpoint, compacted plan | Multiple synchronized state projections |
| Primary optimization | Model legibility and task progress | Host auditability and local correctness |

Models have strong learned priors for shells, source trees, diffs, tests, and Markdown work logs.
They have no such prior for a repository-specific nested operation grammar. A narrow familiar
interface therefore reduces protocol translation and leaves more of the model's attention for the
actual engineering judgment.

This is not an argument against types. Types are excellent for the host's authority boundary. They
can make an unregistered side effect, invalid budget transition, or replayed call unrepresentable.
They cannot prove that the model-facing abstraction is cognitively usable, that a fingerprint
captures semantic workspace identity, or that the controller-selected next step is the right
investigation.

## The thin-waist architecture

A convergent loop benefits from a small interface between a flexible model and a strict host:

```text
  task + methodology + repository state
                  |
                  v
        model-owned cognition
       plan / inspect / edit / test
                  |
        small familiar tool surface
          bash | checkpoint | submit
                  |
                  v
          typed control kernel
   identity | intent | isolation | budget
    replay | compaction | audit | policy
                  |
                  v
       independent acceptance gates
```

`bash` above is a capability, not ambient host authority. It can be implemented by a typed,
networkless, non-root worker with bounded output and explicitly mounted paths. A small model-facing
surface and a strongly controlled execution substrate are compatible.

The interface should expose the repository's native semantics whenever possible. If a file, test,
diff, or tracker can be inspected directly, avoid introducing a second lossy representation merely
so the controller can reason about it. Derive controller-owned summaries for validation and
telemetry, not as replacements for the model's world.

## A reference loop

The host loop can remain mechanically simple:

```text
verify pinned inputs and workspace
load the latest complete checkpoint

until a terminal state is reached:
    if the next model request would approach the context limit:
        compact only from the latest complete turn boundary

    durably record model-call intent
    request the next model action
    validate the complete response and served identity

    if the response is safely retryable before any tool intent:
        retry from the exact same checkpoint within the shared budget
    else if the response is indeterminate:
        stop without replay

    durably record tool intent before execution
    execute each admitted action at most once
    record its complete observation and new workspace identity
    checkpoint only after the assistant/tool turn is closed

    if the model submits:
        run independent acceptance gates
        accept, reject with bounded evidence, or stop honestly
```

The model may use many internal steps; the controller needs only a few durable states. Avoid
mirroring the model's plan as an elaborate host state machine unless an independently observed,
repeated failure proves that a new mechanism is necessary.

## Context compaction is transactional state transfer

The context window is working memory, not durable run state. Before the window fills, ask the same
model for a structured continuation packet containing:

- a concise summary of completed work;
- concrete evidence, paths, symbols, commands, and outcomes;
- unresolved judgments and failed hypotheses; and
- the latest intended plan.

Compaction must occur only after a complete assistant/tool-observation boundary. The host binds the
packet to the exact source checkpoint, task, methodology, policy, workspace, and previous
continuation. It then rebuilds active context from the stable instructions, the packet, and a safe
recent transcript tail.

Treat the packet as a fallible cache. The repository, tests, tracker, and controller-owned hashes
remain authoritative. The continuation should explicitly tell the model to verify remembered claims
against those artifacts and not to replay a completed action merely because older chat text is no
longer visible.

Never compact while a tool action is in flight. After a crash, the host could no longer distinguish
"not executed" from "executed but observation lost," so replay might duplicate a mutation. A run
with an indeterminate side effect must stop for reconciliation rather than manufacture continuity.

## Retry only when the boundary proves it is safe

A model or serving stack can violate a requested schema even when tools are disabled or strict JSON
is requested. Strict schemas are admission contracts, not guarantees about generated tokens. A
safe retry policy is based on effect state, not on optimism about the error.

A response may be resampled only when all of these are true:

1. the complete response stream was received;
2. endpoint and served-model identity were validated;
3. no tool intent was admitted or executed;
4. the source checkpoint, workspace, task, and policy are unchanged;
5. the rejected response is preserved privately and represented publicly by safe diagnostics; and
6. the shared, preregistered retry budget is not exhausted.

Do not repair malformed JSON, salvage a forbidden tool call, retry a partial stream, or retry after
a tool may have executed. Those shortcuts erase the distinction between a new sample and a replay
and make the resulting run impossible to audit honestly.

## Design for convergence, not mere continuation

A loop can run indefinitely without converging. Useful convergence requires four complementary
properties:

1. **A meaningful unit of work.** The methodology must tell the model what causal object it is
   resolving, not merely ask it to find bugs.
2. **Monotonic evidence.** Tests, observations, and tracker claims must accumulate or be explicitly
   invalidated; they must not silently disappear during context turnover.
3. **Reversible search.** The model must be able to reject a hypothesis and rewind a representation
   without fighting a controller that has mistaken phase advancement for progress.
4. **An independent stopping condition.** Submission is a request for validation, not proof of
   completion. The host checks tests, repository cleanliness, tracker invariants, counterfactuals,
   and any sealed evaluator before accepting it.

Budget limits are circuit breakers, not definitions of progress. A run that exhausts its allowance
is censored or failed; it is not successful because it remained active until the limit.

### Treat submission as untrusted input

The model's declaration of completion is another proposed action, not an authoritative terminal
fact. A model can pass tests, make a clean commit, and satisfy every structural state transition
while leaving the durable explanation unrelated to the repository.

The GPT-OSS-120B completion canary produced exactly that failure. It changed the tracker statuses to
`done`, made all six phases `done`, and marked the finding fixed while retaining template values such
as `<name>`, `File.java:123`, and `<the one missing fact>`. The validator correctly reported that
those mutually consistent fields did not contradict one another; it had no rule saying that a done
record must cease to be a template.

This distinguishes three terminal layers that should never be collapsed:

1. **Protocol completion:** the model and tool loop reached its completion command without an
   indeterminate effect.
2. **Structural acceptance:** repository, tests, commit, and tracker satisfy their machine-checkable
   invariants.
3. **Semantic acceptance:** the evidence actually establishes the requested behavior and the
   tracker describes this codebase rather than a coherent fiction.

Only the third is task success. The first two are necessary evidence, not substitutes for it.

The evidence-earned R20 tracker rule now rejects literal template residue when a slice claims done,
while still allowing a pristine pending template. Its self-test mutates a valid done slice back to a
template boundary and proves that the rule fires. This is the correct role for types and validators:
make an observed invalid state unrepresentable after it has occurred, while leaving semantic truth
to independent tests and code-derived evidence.

## Observable invariants

Keep controller guarantees few, strong, and testable:

- every model and tool attempt has a stable logical identity;
- tool intent is durable before execution;
- a completed observation is never silently replayed;
- checkpoints exist only at complete boundaries;
- task, policy, methodology, and workspace drift are detected before continuation;
- model-authored summaries cannot override controller-owned facts;
- retries share one bounded budget and retain rejected evidence;
- credentials and publishing authority never enter the model workspace;
- a final model message cannot bypass the acceptance gate; and
- terminal failures remain terminal and are never relabeled as successful runs.

These invariants are suitable for typed implementation, property tests, deterministic rehearsals,
and independent audit. They constrain what the run may do without prescribing what the model must
think.

## Failure-driven mechanism growth

Add a controller mechanism only after repeated, classified evidence identifies a missing invariant.
The smallest repair should cover the observed failure while leaving unrelated behavior fail-closed.

Examples:

- Context overflow earns proactive compaction at closed boundaries; it does not earn a host-authored
  reconstruction of the model's plan.
- A fully received malformed pre-tool response may earn bounded resampling; it does not earn JSON
  repair.
- A false workspace mismatch earns a semantic fingerprint; it does not justify removing integrity
  checks.
- Repeated phase confusion may earn a clearer methodology artifact; it does not immediately earn a
  phase scheduler.
- Path ambiguity earns an explicit model-visible working-directory sentence; changing an already
  correct executor does not address the ambiguity.

This discipline prevents the controller from accumulating speculative machinery whose complexity
creates more failure modes than it removes.

## What to measure

Task completion alone is insufficient, but controller activity is not progress. Record at least:

- independently accepted task outcome;
- completed model turns and closed tool actions;
- input, cached input, output, and reasoning tokens;
- time in provider inference, retry backoff, tools, and controller work;
- repeated reads and searches after each restart or compaction;
- compaction count, trigger, packet identity, and continuity checks;
- rejected responses, retry reasons, and budget consumption;
- workspace mutations, rewinds, tests, and counterfactual evidence; and
- terminal reason, including honest censored and indeterminate outcomes.

Compare architectures on equivalent frozen tasks and acceptance gates. A successful small canary is
evidence about that task and protocol, not universal model qualification.

## Evidence from this repository

The six-attempt BAT Java run demonstrated strong fail-closed behavior and durable controller state,
but stopped in EXPOSE after 890,565 tokens. Input represented 96.7% of all tokens, six contexts
repeatedly reconstructed state, and 69.1% of tools were searches or reads. Its final stop was a false
workspace mismatch caused by hashing non-semantic Git index stat-cache bytes. These results show
that controller correctness and task convergence are related but distinct properties. See
[`experiments/java-six-phase-120b-20260814.md`](experiments/java-six-phase-120b-20260814.md).

Direct BDRv1 runs subsequently showed substantially better understanding of the method on bounded
repository work. A Qwen protocol-v2 canary completed the PairKey repair in 29 work turns while
crossing a forced and a natural compaction, with no lost or replayed action. Those runs also exposed
real protocol defects—context overflow, malformed complete responses, invalid maintenance output,
and overly tight circuit breakers—which earned narrow runner mechanisms without introducing a
phase controller. See [`experiments/bdrv1/README.md`](../experiments/bdrv1/README.md).

These are not controlled proof that a phase-opaque loop always wins. They support a more precise
conclusion: on the observed tasks, the model-facing semantic protocol imposed more friction than its
host-side guarantees repaid. The next architecture should preserve those guarantees while returning
planning and methodology interpretation to the model.

## Review checklist

Before adding or changing an autonomous loop, ask:

- Is this rule constraining authority, or trying to prescribe cognition?
- Is the model-facing action natural and locally understandable?
- Does the controller duplicate an authoritative artifact into another state representation?
- Can a crash cause an action to be lost or replayed?
- Is context maintenance bound to a complete checkpoint?
- Is every retry demonstrably pre-effect and bounded?
- Can the model rewind a bad hypothesis without fighting the host state machine?
- Does submission trigger independent validation rather than automatic acceptance?
- Are budget exhaustion and indeterminate effects reported honestly?
- Was each new mechanism earned by a reproduced failure?

If the loop cannot answer these questions clearly, more autonomy will usually amplify uncertainty
rather than produce convergence.
