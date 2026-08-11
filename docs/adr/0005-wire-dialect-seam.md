# 0005 — The wire-dialect seam and the GPT-OSS Harmony Chat cartridge

- Status: accepted
- Supersedes: nothing
- Amends: [`0003-reasoning-backends.md`](0003-reasoning-backends.md)

## Context

ADR 0003 established a provider-neutral `Backend` boundary with provider-native
reasoning dialects, opaque replay, and a shared transport. It also expressed a
preference, restated in issues #6 and #25: prefer a Responses-compatible
endpoint, and do not silently switch API dialects.

Qualifying BAT against the work-owned exo cluster showed that the *preference*
and the *reason for the preference* can point in opposite directions on a real
deployment.

### What exo actually serves

Measured against exo `v0.3.70` (commit `b5375f8c`):

| fact | source |
|---|---|
| `POST /v1/responses` exists | `src/exo/api/main.py:377` |
| `POST /v1/chat/completions` exists | `src/exo/api/main.py:362` |
| Responses reasoning events are `response.reasoning_summary_*` | `api/adapters/responses.py:610-659` |
| A replayed Responses `reasoning` item becomes `{"role":"assistant","content": <raw CoT>}` | `api/adapters/responses.py:291-303` |
| Responses output reasoning items expose only `summary` | `api/types/openai_responses.py:398-403` |
| Chat messages carry a first-class `reasoning_content` field | `api/types/api.py:85-94` |
| `reasoning_content` is forwarded to the chat template on input | `api/adapters/chat_completions.py:138-139` |
| `reasoning_content` is emitted on output, streaming and not | `api/adapters/chat_completions.py:199`, `:372` |
| Tool calls carry explicit `id`, replayed via `tool_call_id` | `api/types/api.py:78-93` |
| Terminal `usage` rides the finish chunk, omitted when unreported | `api/adapters/chat_completions.py:280-296` |

Two independent consequences follow.

**exo's Responses endpoint cannot satisfy BAT's Responses cartridge.** BAT
decodes `response.reasoning_text.delta`/`.done` and rejects unknown event types
by design (`ResponsesProtocol.scala`). exo emits the `reasoning_summary_*`
vocabulary instead. Because GPT-OSS always produces reasoning, the first
reasoning token ends the stream as `incompatible`.

**exo's Responses endpoint is a translation shim whose loss is exactly the
property ADR 0003 protects.** It converts Responses items into chat-template
messages, and a replayed reasoning item is re-injected as ordinary assistant
`content` — the Harmony *final* channel — rather than as analysis. It also never
emits `content` or `encrypted_content` on output reasoning items, so there is
nothing faithful to replay even if the vocabulary matched.

So on this deployment the Responses dialect is the one that destroys raw
reasoning, and Chat Completions is the one that preserves it. The "prefer
Responses" rule was a proxy for "prefer the dialect that round-trips reasoning."
On exo the proxy and the goal disagree, and the goal wins.

## Decision

**1. Record exo's Responses endpoint as incompatible, and do not weaken the
Responses decoder to make it pass.** Teaching that decoder the
`reasoning_summary_*` vocabulary would produce a green run whose reasoning
continuity is fiction. Per ADR 0003's fail-closed policy, a knowingly lossy
replay is not a compatible endpoint.

**2. Add a native GPT-OSS Harmony Chat cartridge as a peer dialect**, admitted
by the gates issue #25 sets for a second cartridge: the endpoint exposes
replayable raw reasoning and exact tool call IDs. It is selected by explicit
configuration. The controller never switches dialects on its own, and a failure
in one dialect never falls back to the other.

**3. Extract the shared streaming orchestration into a `WireDialect` seam.**
Everything identical across SSE-framed reasoning providers — request
construction, status and media-type validation, incremental framing, bounded
replay-safe retry, attempt timing, sanitized telemetry — now lives in
`StreamingWireBackend`. Everything provider-specific lives behind `WireDialect`:
request encoding, event interpretation, opaque replay, and normalization into
BAT model turns.

Adding a provider is now adding a dialect, not transcribing the transport loop.
That is what makes the next backends — Claude Messages, a Codex-style Responses
variant, Kimi Chat — incremental rather than duplicative. Notably, exo itself
also serves `/v1/messages` and an Ollama surface, so the same cluster is
reachable through more than one future dialect.

## Consequences

### What the seam guarantees

A dialect cannot widen what BAT can inspect. Provider wire objects and reasoning
history stay inside the dialect; the controller sees only normalized turns and an
opaque continuation value. Error codes remain dialect-prefixed and pinned, so
evidence keeps saying which wire produced a verdict.

### What is different about Chat Completions

The Responses dialect replays a server-provided output object verbatim. Chat
Completions never returns one, so the assistant message that must be replayed is
**reconstructed** from streamed deltas. That reconstruction is therefore strict:
a turn whose reasoning is missing, whose tool call ids are duplicated or reused,
whose fragments rewrite a call's identity, whose served model differs from the
pin, or whose terminal usage is absent, is a failed turn rather than a best
effort. An unavailable measurement stays unavailable; it is never reported as
zero.

### Known fidelity limit

Even on the Chat dialect, `reasoning_content` fidelity is the endpoint's claim,
not BAT's proof. BAT verifies that the field is present, non-empty, and
round-tripped; it cannot verify that the serving runtime placed it on the
analysis channel of the next prompt. That belongs to a deployment fingerprint and
to live qualification, not to the cartridge.

### Non-goals

This ADR does not change the BDR protocol, the controller loop, the tool
registry, or the isolated Java worker. It does not add a Chat fallback to the
Responses cartridge. It does not claim that any live GPT-OSS deployment has
passed qualification; that claim requires preserved live evidence.
