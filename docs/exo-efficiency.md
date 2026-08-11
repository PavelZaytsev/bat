# Making BAT efficient on exo

Measured notes for getting usable throughput out of an exo-served GPT-OSS deployment, and for what
changes when a larger model or a third node arrives.

Everything marked *measured* came from a real run. Everything marked *hypothesis* did not, and should
be confirmed before anyone builds on it.

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

## Prefix caching

exo has a working KV prefix cache and it is enabled for ordinary chat completions:

- `KVPrefixCache.get_kv_cache` does token-level longest-prefix matching over stored prompts
  (`worker/engines/mlx/cache.py:314`).
- It is gated only against bench requests — `not is_bench or task_params.use_prefix_cache`
  (`worker/engines/mlx/generator/batch_generate.py:165`).
- Hits are reported honestly as `usage.prompt_tokens_details.cached_tokens = prefix_hit_length`
  (`batch_generate.py:442`) and as `prefix_cache_hit` in the `generation_stats` SSE comment.

So a reported `cached_tokens: 0` is a genuine miss, not a reporting gap.

BAT is already built to be cacheable. Both dialects keep a byte-stable prefix: developer instructions
enter the replay prefix once, mutable BDR state is appended as a trailing developer message rather
than edited in place, and every later request extends the previous request's exact prefix. Nothing in
the request shape rotates.

**Why the observed run still missed is unresolved.** The leading hypothesis is LRU eviction under
memory pressure — `add_kv_cache` "evicts LRU entries if memory is high" (`cache.py:260`), and that run
held a 13.7 GB model on a node with roughly 18 GB available. That predicts the same run on a node with
more headroom would hit. **This is a hypothesis and has not been tested.**

To test it, run any two-turn exchange where the second request extends the first, on a node with
comfortable headroom, and read `cached_tokens` on the second response. If it is non-zero, the cache
works and memory headroom is the variable. If it is zero on a roomy node, the miss is in prompt
rendering and is worth chasing hard, because it is the difference between hours and minutes.

## Levers available today

| lever | effect | how |
|---|---|---|
| reasoning effort | largest generation-side dial; 92% of output tokens at `high` | `BAT_GPT_OSS_REASONING_EFFORT=low\|medium\|high` |
| memory headroom | may decide whether prefill is reused at all *(hypothesis)* | keep the serving node otherwise idle |
| node choice | a roomier node both fits more and evicts less | prefer the node with the most `ramAvailable` |

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

**Prefill gets worse before it gets better.** A larger model prefills more slowly per token, so an
unresolved prefix-cache miss hurts 120b more than it hurts 20b. Confirming the cache is the highest
-value work available before the third node lands.

**BAT itself needs no change.** Model, dialect, effort, topology class, and node count are all
configuration. Switching to 120b is a different `BAT_GPT_OSS_MODEL_ID` and `BAT_GPT_OSS_NODE_COUNT`,
with the served identifier matching exactly what `/v1/models` reports, because the cartridge rejects a
response attributed to a different model. Nothing in the controller, the dialects, or the evidence
schema is 20b-specific.

## Readiness checklist for a 120b run

1. Weights present and registered on every participating node; cards do not survive a restart.
2. Enough `ramAvailable` on each node for its shard plus KV cache, checked via `/state` after a
   fresh boot rather than assumed.
3. A cycle in `/state` topology — both directed edges *and* a non-empty `thunderboltBridgeCycles`.
   Bidirectional edges alone are not sufficient for placement.
4. An instance placed and visible in `/state`, with the runner past its cold load.
5. `BAT_GPT_OSS_MODEL_ID` exactly as served, `BAT_GPT_OSS_NODE_COUNT` set to the real count, and
   `BAT_GPT_OSS_TOPOLOGY` naming the real topology class.
6. Evidence parent directory `chmod 0700`, outside the checkout.
