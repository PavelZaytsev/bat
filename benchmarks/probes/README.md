# Deployment conformance probes

> **Historical evidence.** These immutable records were produced by the removed Scala/ZIO probe.
> They remain useful transport evidence but are not an active runtime or model-quality claim.

These are **deployment** records, not model benchmarks. Each directory holds one armed run of
`bat.probe.LiveGptOssProbeApp` against one pinned endpoint, answering a single question: can that
exact model, runtime, template, quantization, and topology complete BAT's fixed three-turn, two-tool
scenario without violating the controller contract?

They say nothing about model quality on real defects. Model-quality evidence lives in
[`../pilot/`](../pilot/README.md), which has its own protocol, oracle isolation, and artifact checker.

## Inventory and contents

`index.json` is the machine-readable authority for the immutable run
directories. Its directory and run-ID inventory must match the filesystem
exactly; unlisted directories, repeated IDs, symbolic links, and extra files
fail validation.

Each indexed run contains exactly:

| file | contents |
|---|---|
| `result.json` | verdict, reason code, BAT commit, deployment fingerprint, embedded sanitized documents, SHA-256 digests |
| `safe-trace.json` | reasoning-redacted controller trace |
| `telemetry.json` | payload-free run, attempt, token, tool, timing, and terminal telemetry |

The digests in `result.json` cover the exact UTF-8 bytes of the other two files, so a copied record
stays internally bound. Validate the complete committed inventory with the repository artifact
checker used by ordinary CI:

```bash
python3 scripts/check_benchmark_artifacts.py
```

The checker validates the committed artifact graph, exact hashes, bundle completeness, and journal
integrity. These records are immutable. Do not normalize whitespace, replace a run, or edit an
artifact—add a new indexed run instead. A failed, blocked, incompatible, or nonconformant run is a
result and is preserved as one.

## Recorded runs

| run | model | dialect | topology | verdict |
|---|---|---|---|---|
| [`gpt-oss-20b-exo-single-node-001`](gpt-oss-20b-exo-single-node-001/) | `openai/gpt-oss-20b` | `harmony_chat_sse` | single-node exo, Apple M1 Pro | `compatible` |
| [`gpt-oss-120b-exo-three-node-001`](gpt-oss-120b-exo-three-node-001/) | `openai/gpt-oss-120b` | `harmony_chat_sse` | three-node exo Pipeline/MLX Ring over Thunderbolt | `compatible` |

An earlier attempt against the same endpoint returned `incompatible` with
`harmony_chat_protocol_violation`. That verdict was a defect in BAT's own decoder, not in the
deployment — the cartridge rejected the endpoint's empty `content` field on the terminal chunk — so it
is not preserved here as a deployment result. The fix is commit `0a24783`, and the episode is recorded
in [`../../docs/adr/0005-wire-dialect-seam.md`](../../docs/adr/0005-wire-dialect-seam.md) because it is
the strongest available argument for deriving wire fixtures from captured bytes.
