# Scaling exo for BAT

“Scaling” describes three different outcomes:

1. **Capacity** — whether the cluster can load the model and its runtime state.
2. **Single-stream speed** — how quickly one BAT conversation produces a result.
3. **Fleet throughput** — how many independent BAT jobs the team can run at once.

More laptops can improve capacity or fleet throughput without making one conversation faster.

## Current measured baseline

On 2026-08-13, BAT passed its live conformance probe against this pinned deployment:

| Field | Value |
|---|---|
| Model | `openai/gpt-oss-120b` |
| exo | `0.3.70-b5375f8c` plus the recorded operator patch |
| Nodes | 3 |
| Sharding | `Pipeline` |
| Communication | `MlxRing`, TCP/IP over Thunderbolt |
| RDMA / JACCL | off |
| Reasoning effort | `medium` |
| Output throughput | **21.75 tokens/s** |
| Wall time | 89.0 seconds |
| Mean time to first event | 25.7 seconds |

The first turn included a 65.6-second warm-up delay; the next turns completed in 8.6 and 6.8
seconds. See the immutable
[`gpt-oss-120b` result](../../benchmarks/probes/gpt-oss-120b-exo-three-node-001/README.md).

This is one wire-conformance point. It does not measure a Java repair, an M4/M5-only trio, four to six
Pipeline nodes, two concurrent clusters, or Tensor/JACCL.

## Capacity is not speed

The pinned exo GPT-OSS-120B model card declares:

| Property | Value |
|---|---:|
| Placement storage size | 70,652,212,224 bytes |
| Binary equivalent | about 65.80 GiB |
| Layers | 36 |
| Context length | 131,072 tokens |
| KV heads | 8 |

Source: [pinned exo model card](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/resources/inference_model_cards/mlx-community--gpt-oss-120b-MXFP4-Q8.toml).

The cluster needs more than 65.80 GiB of nominal combined memory. macOS, exo, model-loading overhead,
Metal allocations, and KV cache also consume unified memory. KV usage grows with prompt and generated
context.

At the pinned revision, exo checks whether the sum of participating nodes' live available memory
covers the model-card storage size. It assigns Pipeline layers in proportion to available memory.
That gate does not reserve the full long-context KV cache. See the
[pinned placement implementation](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/src/exo/master/placement_utils.py#L18-L125).

Adding a node can therefore be useful when it makes the model fit, creates context headroom, or
reduces out-of-memory risk. Once a smaller cluster fits comfortably, capacity is no longer a reason
to expect higher token rate.

## Pipeline/MLX Ring mental model

Pipeline parallelism assigns each node a consecutive group of model layers:

```text
prompt or token
      |
      v
node A layers -> node B layers -> node C layers -> logits and sample
```

For autoregressive generation, a token must cross every layer group before it is finalized. A useful
first-order approximation is:

```text
time per token ~=
  sum of stage compute time
  + inter-node transfers
  + synchronization overhead
```

Adding a node reduces layers on some existing stages, but it also adds another stage, transfer, and
failure participant. Whether the trade is positive depends on chip speed, layer allocation, network,
prompt shape, and runtime implementation.

MLX documents Ring as a TCP-socket backend. Thunderbolt can carry that IP traffic, but it is not the
JACCL RDMA path. See the
[MLX distributed guide](https://ml-explore.github.io/mlx/build/html/usage/distributed.html).

For the current Pipeline/Ring configuration, the operating hypothesis is:

- use the smallest cluster that safely fits the model;
- expect extra nodes to help capacity;
- expect fourth, fifth, and sixth nodes often to hurt one-stream generation once capacity is met;
  and
- benchmark this instead of treating it as a universal law.

## Heterogeneous Macs

The pinned placement logic allocates Pipeline layers using available memory, not measured GPU or
memory-bandwidth throughput. A roomier but slower node can therefore receive a meaningful layer
share.

For the operator-reported M1/M3/M4 cluster:

- every generated token crosses the M1's assigned layers;
- newer nodes cannot finalize the token while that stage is incomplete; and
- the M1 may reduce single-stream speed, depending on its assigned layers.

The measured 21.75 tokens/s belongs to the entire deployment. It does not isolate the M1 as the
cause. The correct test is an A/B comparison between the mixed trio and a closely matched modern
trio, holding model, runtime, prompts, output lengths, macOS, cables, and warm-up constant.

If a modern trio fits with enough context headroom, the M1 is likely more useful as the BAT
controller, monitoring host, smaller-model host, or spare than as a default 120B stage. That remains
a hypothesis until measured.

## Two trios versus one six-node ring

Assume each trio independently fits GPT-OSS-120B plus safe context headroom:

| Topology | Model copies | One BAT job | Concurrent jobs | Primary benefit |
|---|---:|---|---:|---|
| one three-node Pipeline/Ring cluster | 1 | baseline for that trio | 1 | one PR |
| two isolated three-node clusters | 2 | baseline for each trio | 2 | team throughput |
| one six-node Pipeline/Ring cluster | 1 | more stages and transfers | normally 1 serial run | capacity |

Two trios do not make one sequential BAT conversation twice as fast. They allow two independent BAT
conversations to run at the same time.

If both trios reproduced 21.75 output tokens/s, the ideal projection would be 43.5 tokens/s across
the fleet. That is arithmetic, not a measurement. Hardware, thermals, cache behaviour, and prompts
will change it.

Use separate discovery namespaces so the two clusters cannot merge:

```text
cluster A: EXO_LIBP2P_NAMESPACE=bat-cluster-a
cluster B: EXO_LIBP2P_NAMESPACE=bat-cluster-b
```

exo documents namespaces for multiple isolated clusters on one network in its
[pinned README](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/README.md#custom-namespace-for-cluster-isolation).

## Tensor/JACCL is a different experiment

Tensor parallelism divides work inside each layer, allowing machines to calculate parts of the same
layer concurrently:

```text
Pipeline: node A layers -> node B layers -> node C layers

Tensor:   node A + node B + node C + node D
          compute each layer together
```

exo advertises up to 1.8x scaling on two devices and 3.2x on four devices for Tensor/RDMA. Those are
upstream results, not promises for GPT-OSS-120B or BAT. See the
[exo feature summary](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/README.md#features)
and [MLX tensor-parallel guide](https://ml-explore.github.io/mlx/build/html/examples/tensor_parallelism.html).

JACCL requires compatible Thunderbolt 5 hardware, RDMA enabled, direct connectivity between every
pair of participating Macs, supported cables, and matching macOS versions. A four-node full mesh
requires six direct links. The current M1 cannot participate in this TB5 experiment.

The pinned GPT-OSS-120B card declares hidden size 2,880 and eight KV heads. exo requires Tensor node
count to divide both. Under the pinned placement logic, 2, 4, and 8 are candidates; 3, 5, and 6 are
not. Hardware, memory, topology, and runtime checks must still pass. See the
[tensor placement rules](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/src/exo/master/placement.py#L125-L156).

A four-node, closely matched M4/M5 Tensor/JACCL cluster is therefore the most interesting future
single-stream experiment. It should not replace the proven Pipeline/Ring path until it wins a
BAT-shaped benchmark and remains stable over a long run.

## Benchmark plan

Keep these variables fixed across trials:

- exact exo, MLX, model, template, and operator-patch revisions;
- macOS version and power mode;
- cable topology;
- prompt and generation sizes;
- reasoning effort;
- warm-up and repeat count; and
- exclusive endpoint ownership.

Run this matrix where available memory permits:

| ID | Composition | Sharding / transport | Purpose |
|---|---|---|---|
| `P3-mixed` | current three-node mixed cluster | Pipeline / Ring | preserve baseline |
| `P3-modern` | closest-matched modern trio | Pipeline / Ring | isolate older-node effect |
| `P4-modern` | four modern Macs | Pipeline / Ring | measure first extra stage |
| `P5-modern` | five modern Macs | Pipeline / Ring | establish scaling curve |
| `P6-modern` | six modern Macs | Pipeline / Ring | compare one larger ring |
| `R3x2` | two isolated modern trios | two Pipeline / Ring replicas | fleet throughput |
| `T2-modern` | two matched TB5 Macs with enough memory | Tensor / JACCL | optional baseline |
| `T4-modern` | four matched TB5 Macs | Tensor / JACCL | single-stream experiment |

exo ships `exo-bench` to measure prompt and generation throughput across placement configurations. A
verified starting shape is:

```bash
uv run bench/exo_bench.py \
  --model '<served-model-id>' \
  --pp 128,8192,32768,65536 \
  --tg 512 \
  --max-nodes 6 \
  --instance-meta ring \
  --sharding pipeline \
  --warmup 1 \
  --repeat 3 \
  --json-out bench/results-pipeline.json
```

Use the placement records in the output to compare exact node counts. The pinned
[exo benchmark documentation](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/README.md#benchmarking)
documents the supported filters and measurements.

`exo-bench` is not the complete BAT workload. Repeat the best placements with uninterrupted
BAT-shaped conversations and record:

- prompt TPS and output TPS;
- time to first event and full-turn wall time;
- cache hits and misses;
- peak and remaining memory;
- thermal state and power mode;
- retries and cluster restarts; and
- successful BAT turns or jobs per day.

For two replicas, run the same workload simultaneously against two isolated endpoints. Report
combined throughput as measured generated tokens divided by the common wall interval; do not infer a
two-times result from a single run.

## Operating recommendation

Until the matrix says otherwise:

1. Use the smallest stable Pipeline/Ring cluster that fits GPT-OSS-120B with long-context headroom.
2. Prefer a closely matched modern trio when it independently fits.
3. With six suitable laptops, use two isolated three-node replicas for two concurrent BAT jobs.
4. Add Pipeline nodes for capacity, not on the assumption that they improve token rate.
5. Test four matched TB5 machines with Tensor/JACCL separately.
6. Keep the M1 available as controller, monitor, smaller-model host, or spare unless an A/B benchmark
   shows that it improves the desired metric.
