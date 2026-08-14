# Hardware requirements

BAT has three distinct hardware roles. They do not need to run on the same machine:

1. the trusted BAT controller;
2. the model inference endpoint; and
3. the Java build/test worker.

The first live GPT-OSS probe demonstrated this separation: BAT ran on a Linux controller while exo
served the model from an Apple Silicon Mac over HTTP. Hardware sizing should therefore begin with the
model and target build, not with the BAT controller itself.

This directory records the hardware journey and the current operating hypotheses:

- [`journey.md`](journey.md) — what has been built and measured so far;
- [`scaling.md`](scaling.md) — why Pipeline/MLX Ring and Tensor/JACCL scale differently;
- [`model-selection.md`](model-selection.md) — why GPT-OSS-120B is first, with Gemma 4 31B and Muse
  Glimmer 30B as explicit experiments; and
- [`../exo-efficiency.md`](../exo-efficiency.md) — measured prompt, generation, and prefix-cache
  behaviour plus the 120B readiness checklist.

## Evidence vocabulary

Hardware notes use three labels:

- **Repository-verified** — supported by an immutable probe bundle committed under
  [`benchmarks/probes/`](../../benchmarks/probes/).
- **Operator-reported** — observed on the physical cluster but intentionally absent from the public
  evidence bundle.
- **Hypothesis** — an engineering expectation that still needs a controlled benchmark.

This distinction matters. A topology can fit a model without being the fastest topology, and a wire
probe can pass without proving that the model can repair Java.

## Current known-working deployments

| Model | Measured topology | Result | Output rate | Evidence |
|---|---|---|---:|---|
| `gpt-oss-20b` | one Apple M1 Pro, 32 GB | compatible | 33.75 tokens/s | [single-node run](../../benchmarks/probes/gpt-oss-20b-exo-single-node-001/README.md) |
| `gpt-oss-120b` | three-node exo Pipeline/MLX Ring over Thunderbolt | compatible | 21.75 tokens/s | [three-node run](../../benchmarks/probes/gpt-oss-120b-exo-three-node-001/README.md) |

Both records are protocol results: three streamed turns, two strict tools, reasoning continuation,
terminal usage, and the expected final BDR checkpoint. Neither is a Java repair or a controlled
model-performance comparison.

## Controller requirements

The trusted controller needs:

- network reachability to the inference endpoint;
- a supported JVM/Scala toolchain for BAT;
- Python 3.10 or newer for the current reviewed BDR engine, plus Git;
- private control, workspace, scratch, and evidence locations; and
- an OCI runtime when model-authored target code must be isolated.

The controller does not need enough unified memory to hold the model. A trusted CI VM is a valid
controller while the model runs on a separate exo cluster.

## Inference requirements

There is no universal “three Macs” requirement. Select the smallest stable cluster whose live
available memory fits the pinned model plus operating-system, runtime, Metal, KV-cache, and context
headroom.

The exo model card used for the current 120B proof declares 70,652,212,224 bytes of placement storage,
about 65.80 GiB. That is a placement input, not a complete runtime-memory promise. A nominal
`3 x 24 GB` cluster can still be too tight after macOS and long-context overhead.

For the current Pipeline/MLX Ring path:

- prefer closely matched nodes;
- use the fewest nodes that fit with safe headroom;
- keep one BAT conversation exclusive to a model instance; and
- use separate model replicas, rather than a longer ring, when the goal is concurrent team work.

See [`scaling.md`](scaling.md) for the reasoning and the Tensor/JACCL exception.

## Java worker requirements

Target requirements depend on the repository rather than the inference model. The worker needs:

- the pinned JDK and reviewed Maven, Gradle, or other closed build profile;
- enough CPU, memory, disk, and time for the focused and final verification commands;
- a networkless, non-root OCI boundary for model-authored code unless a deliberately trusted-VM POC
  profile is selected; and
- pre-materialized dependencies when the worker is offline.

Inference speed cannot compensate for a build profile that takes forty minutes per focused test.
Measure target build time separately from model time.

## Current team recommendation

Until controlled topology benchmarks say otherwise:

1. Use one minimal, stable three-node Pipeline/MLX Ring deployment for the first 120B Java canary.
2. Replace the older node with a closely matched newer Mac when a modern trio independently fits the
   model and context.
3. With six suitable laptops, prefer two isolated three-node replicas for two BAT jobs instead of
   assuming one six-node ring will accelerate one job.
4. Give each cluster a distinct `EXO_LIBP2P_NAMESPACE` so automatic discovery cannot merge them.
5. Treat a four-node, all-Thunderbolt-5 Tensor/JACCL cluster as a separate performance experiment.
6. Do not add or remove nodes during a BAT attempt. A topology change starts a new pinned attempt from
   durable BDR and workspace state.

## Before calling a topology ready

- Pin the exo, MLX, model, template, and operator-patch revisions.
- Confirm the exact served model identifier.
- Check live available memory after a fresh boot, not nominal RAM from a product page.
- Confirm all expected directed links and the selected placement in exo state.
- Warm the instance, then measure both first-turn and warm-turn behaviour.
- Run BAT's live conformance probe before exposing a Java repository.
- Record prompt TPS, output TPS, time to first event, full-turn wall time, retries, node count, and
  topology.
- Preserve failure evidence instead of tuning until only a successful run remains.

## Privacy boundary

Public hardware evidence must not include internal endpoints, hostnames, device identities, serial
numbers, usernames, private paths, credentials, private repository details, raw prompts, raw
reasoning, provider bodies, tool arguments, or tool output. Deployment fingerprints and sanitized
telemetry are sufficient to audit the public claim.
