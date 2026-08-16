# ADR 0003: Isolate each reasoning provider behind its native dialect

> **Historical ADR.** This records the removed Scala/ZIO backend layer. It is retained for
> architectural evidence and is not an implementation claim for the current tree.

- Status: accepted
- Date: 2026-08-09

## Context

BAT needs to drive hosted and open-weight reasoning models through one agentic loop. The providers
do not, however, expose one interchangeable conversation protocol. OpenAI Responses, Anthropic
Messages, and OpenAI-compatible Chat endpoints differ in request shape, streamed events, tool-call
assembly, usage reporting, failure states, and the material that must be replayed after a tool
turn.

Treating those APIs as cosmetic JSON variants would move provider-specific state into
`AgenticLoop`, encourage lossy reasoning continuation, and make compatibility servers appear more
interchangeable than they are. It would also invite a dangerous recovery policy: silently trying a
different API dialect after a malformed or failed response.

The inference server may run beside BAT, on a remote GPU host, or on a separately managed cluster.
The backend boundary therefore cannot assume that the controller, transport process, and model
weights share hardware or a filesystem.

## Decision

BAT uses four composed layers:

```text
AgenticLoop
    -> provider-neutral Backend
        -> provider-native dialect and response state machine
            -> shared scoped ZIO HTTP/SSE transport
```

`AgenticLoop` knows only the provider-neutral `Backend` contract: pinned backend identity,
capabilities, model requests and turns, tool calls, usage, typed safe failures, and an opaque
continuation value. It does not parse provider events or construct provider wire objects.

Each provider adapter owns its native request and response dialect as well as the state machine that
assembles one streamed turn. Provider wire DTOs remain inside that adapter. We share the scoped ZIO
HTTP client, SSE framing, byte and time bounds, interruption, budget accounting, and safe transport
error taxonomy. We do **not** share wire DTOs merely because two services use similar JSON field
names.

Dialect encoding, event decoding, and turn assembly are deterministic functional state transitions.
The scoped HTTP/SSE layer owns network and resource effects, then feeds validated provider events
into that state machine.

### Preserve complete replay context

Reasoning continuation is an opaque, backend-affined value. After a tool turn, an adapter retains
the complete provider output required by that provider for the next request—not just visible text,
a response ID, or a normalized reasoning string. BAT may hold that value and return it to the same
backend identity, but cannot inspect or durably encode its raw reasoning.

The context cannot cross to another backend, model revision, or API dialect. It remains ephemeral;
durable recovery comes from validated BDR state rather than hidden provider state.

### Implement provider dialects independently

The first implementation target is GPT-OSS through the Responses API. Its adapter owns Responses
request construction, event ordering, reasoning and function-call item assembly, terminal-state
validation, usage extraction, and exact replay of the provider output items required after tool
execution.

Future providers get separate adapters:

- Anthropic Claude Messages must preserve and replay provider-signed thinking blocks according to
  the Messages contract.
- Kimi Chat must preserve and replay its `reasoning_content` according to that endpoint's contract.

Those adapters may reuse transport mechanics, budgets, and safe errors. They must not reuse GPT-OSS
Responses DTOs or pretend that signed thinking blocks, Responses reasoning items, and
`reasoning_content` are the same wire-level object.

### Fail closed on dialect and identity

The configured API dialect is explicit. BAT does not silently fall back from Responses to Chat,
from one provider endpoint to another, or from a native API to a compatibility guess. A protocol
failure is reported as a typed backend failure.

A live run stays pinned to one backend and model identity. BAT does not switch provider, model
revision, endpoint dialect, or continuation representation mid-run. Restarting with another backend
is a new run reconstructed from validated BDR state, not a continuation of the old provider
conversation.

### Test transport without requiring inference hardware

Ordinary CI exercises request serialization, SSE fragmentation, event ordering, tool assembly,
bounds, cancellation, error mapping, and replay against a deterministic fake HTTP endpoint. This
keeps conformance repeatable and independent of GPU availability or paid inference.

Live endpoint checks are a later, explicitly configured validation layer. A model server may be on
different hardware from BAT, including a remote open-weight cluster, as long as its selected adapter
and endpoint satisfy the pinned dialect. Passing fake-endpoint CI does not claim live-model quality,
GPT-OSS deployment compatibility, or cluster validation.

## Consequences

- The functional controller and BDR tools remain provider-neutral while provider protocol rules
  stay locally testable.
- GPT-OSS Responses can be implemented and hardened without baking Responses semantics into the
  controller.
- Claude Messages and Kimi Chat can preserve their native reasoning contracts instead of passing
  through a lowest-common-denominator DTO.
- Shared scoped transport code centralizes resource safety, bounds, budgets, and safe failures.
- Adding a provider requires a dialect/state-machine conformance suite, not only a base URL and model
  name.
- Provider failover cannot occur invisibly inside a run. External orchestration may start a new run
  from durable BDR state after recording the previous run's failure.
- Fake-endpoint tests prove protocol behavior; live inference, model quality, performance, and
  multi-host deployment require separate evidence.
