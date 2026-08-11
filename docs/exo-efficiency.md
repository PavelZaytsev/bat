# Making BAT efficient on exo

Measured notes for getting usable throughput out of an exo-served GPT-OSS deployment, and for what
changes when a larger model or a third node arrives.

Everything marked *measured* came from a real run. Everything marked *hypothesis* did not, and should
be confirmed before anyone builds on it. Where a claim here was previously wrong, the correction is
kept rather than quietly replaced.

## Where the time actually goes

From the first passing run (`benchmarks/probes/gpt-oss-20b-exo-single-node-001`, `gpt-oss-20b`,
single-node M1 Pro):

| measurement | value |
|---|---|
| prompt throughput | ~104 tokens/second *(measured)* |
| generation throughput | ~33 tokens/second *(measured)* |
| reasoning share of output | 948 of 1026 tokens, **92%** *(measured)* |
| cached input tokens | **0** across all three turns *(measured)* |

Two consequences follow, and neither is about model size.

**Reasoning dominates generation.** At `high` effort this model family spends most of its output
budget in the analysis channel. That is not waste — it is where the model does the work BDR depends
on — but it means effort is the single largest cost dial available.

**Prefill dominates everything at BDR scale.** Both dialects are stateless: every turn resends the
whole history. A BDR run over a real pull request means tens of thousands of prompt tokens per turn.
At ~104 tokens/second with no cache reuse, one 40k-token turn costs roughly six minutes before the
model emits anything, and a run is hundreds of turns. Prefill reuse is therefore worth more than any
other optimisation, including a faster model.

## Prefix caching — measured

exo has a working KV prefix cache, it is enabled for ordinary chat completions, and **it works with
BAT's exact message shape**. Measured on 2026-08-11 against single-node `gpt-oss-20b` on the M4 Pro:

| sequence | prompt tokens | cached | hit |
|---|---:|---:|---|
| plain conversation, second request extends the first, back-to-back | 114 | **94** | `partial` |
| BAT shape (developer instructions, user, trailing `<bat_turn_context>` developer message), back-to-back | 116 | **114** | `exact` |
| any extension with **another request interleaved** between the two | 116–168 | **0** | `none` |

The mechanism: `KVPrefixCache.get_kv_cache` does token-level longest-prefix matching
(`worker/engines/mlx/cache.py:314`), it is gated only against bench requests
(`batch_generate.py:165`), and hits are reported as
`usage.prompt_tokens_details.cached_tokens = prefix_hit_length` (`batch_generate.py:442`). A reported
`0` is a genuine miss, not a reporting gap.

**The operational rule is that the cache is effectively single-conversation.** Retention is small and
eviction is aggressive — `add_kv_cache` evicts LRU entries when memory is high (`cache.py:260`). An
unrelated request between two turns of the same run reliably destroyed reuse in every trial. The
original observation of zero cached tokens across a whole BAT run is consistent with this: that run
executed on the M1 Pro, holding a 13.7 GB model in roughly 18 GB of available memory.

Two consequences that matter more than they look:

**Do not share a serving endpoint between concurrent BAT runs.** Two runs interleaving on one instance
will evict each other continuously, and each turn pays full prefill. At BDR scale that is the
difference between minutes and hours. Serialise runs per instance, or give each run its own.

**Prefer the roomiest node.** Headroom decides how long an entry survives.

BAT's request shape needs no change. Both dialects keep a byte-stable prefix: developer instructions
enter the replay prefix once, mutable BDR state is appended as a trailing message rather than edited
in place, and every later request extends the previous request's exact prefix. That design is
confirmed cache-friendly by the `exact` hit above.

### A correction worth recording

An earlier revision of this document claimed BAT's trailing developer message defeated the cache.
That was wrong, and the error is instructive: the variant tests were run in sequence, so each
"BAT-shaped" attempt had unrelated requests between store and reuse. The confound, not the message
shape, produced the misses. Isolating one variable at a time against a *freshly stored* prefix
reversed the conclusion.

## Levers available today

| lever | effect | how |
|---|---|---|
| reasoning effort | largest generation-side dial; 92% of output tokens at `high` | `BAT_GPT_OSS_REASONING_EFFORT=low\|medium\|high` |
| run isolation | decides whether prefill is reused at all *(measured)* | never interleave another request between a run's turns |
| memory headroom | how long a cache entry survives | prefer the node with the most `ramAvailable` |
| node choice | a roomier node both fits more and evicts less | prefer the node with the most `ramAvailable` |
| output ceiling | bounds KV growth, which the placement gate ignores | `BAT_GPT_OSS_MAX_OUTPUT_TOKENS` |

Lower effort is not free. BDR's diagnostic phases are exactly where reasoning earns its cost, so the
useful experiment is not "always low" but "which phases tolerate low". BAT pins effort per run today,
so that experiment needs per-phase effort, which does not exist yet.

## What changes with 120b and a third node

**Capacity, not speed.** Pipeline parallelism splits layers and runs stages sequentially, so every
token traverses every node. exo's own measurements say a model that fits on one machine gets *slower*
when distributed. A third node makes `gpt-oss-120b` possible; it does not make it fast.

**Memory is the gate.** `gpt-oss-120b` needs about 65.2 GB. Sharding does not split the download —
`resolve_allow_patterns` returns `["*"]` unconditionally — so every node stores the full 61 GB, and
the memory check uses `ramAvailable`, not `ramTotal`.

**Prefill gets worse before it gets better.** A larger model prefills more slowly per token, so a
cache miss hurts 120b more than it hurts 20b. Since the cache is effectively single-conversation,
serialising runs matters more on 120b than on 20b, not less.

**BAT itself needs no change.** Model, dialect, effort, topology class, and node count are all
configuration. Switching to 120b is a different `BAT_GPT_OSS_MODEL_ID` and `BAT_GPT_OSS_NODE_COUNT`,
with the served identifier matching exactly what `/v1/models` reports, because the cartridge rejects a
response attributed to a different model. Nothing in the controller, the dialects, or the evidence
schema is 20b-specific.

## The KV cache is not in the placement gate

`placement_utils.py:115` compares model **weights** against `ramAvailable`. It does not account for
the KV cache, which grows with context and generation. The `gpt-oss-120b` card advertises
`contextLength: 131072`, so a deployment can place successfully, spend eight minutes loading, and
then OOM on a long first prompt.

Two consequences for a first 120b attempt.

**Cap generation.** `BAT_GPT_OSS_MAX_OUTPUT_TOKENS` bounds tokens per turn; set it to something small
like 1024 for a first run rather than the 32768 default.

**The conformance probe is the right first exercise, precisely because it is small.** The pinned
two-tool scenario ran at 3456 input and 1026 output tokens — roughly 3.5k of context against an
advertised 131k. It is about as far from an OOM as a real request gets, so it tests weights, ring,
and wire without also testing the memory ceiling. Save long-context work for after it passes.

**An accepted request is not a served request.** A two-node instance observed on 2026-08-11 accepted
a chat completion and then emitted only SSE `: keep-alive` comments for 190 seconds with no tokens and
no error. Keep-alive comments defeat an idle timeout, because bytes keep arriving; only a wall-clock
budget ends such a run. BAT ends it correctly as `blocked` / `probe_wall_time_exhausted`, but budget
the wall clock deliberately rather than relying on the body-idle timeout.

## Readiness checklist for a 120b run

1. Weights present and registered on every participating node; cards do not survive a restart.
2. Enough `ramAvailable` on each node for its shard plus KV cache, checked via `/state` after a
   fresh boot rather than assumed.
3. Both directed topology edges present and stable in `/state`. Placement calls
   `topology.get_cycles()` (`placement.py:117`), a plain graph cycle search; **nothing in the
   placement path reads `thunderboltBridgeCycles`**, and placement has succeeded in practice with
   that field empty. On hardware where `bridge0` carries no packets it can never legitimately be
   populated, so do not treat it as a gate. What actually fails after placement is
   `mx.distributed.init`, not placement itself.
4. An instance placed and visible in `/state`, with the runner past its cold load.
5. `BAT_GPT_OSS_MODEL_ID` exactly as served, `BAT_GPT_OSS_NODE_COUNT` set to the real count, and
   `BAT_GPT_OSS_TOPOLOGY` naming the real topology class.
6. Evidence parent directory `chmod 0700`, outside the checkout.
