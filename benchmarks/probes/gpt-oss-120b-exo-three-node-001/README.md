# `gpt-oss-120b` on three-node exo — `compatible`

The first live distributed GPT-OSS deployment to pass BAT's conformance probe.

Recorded 2026-08-13. BAT commit `3452c3f0c1544e4f88d404949d0deb104feb81ef`.

## Deployment

| field | value |
|---|---|
| model | `openai/gpt-oss-120b` |
| weights | SHA-256 manifest `242ce8ad4690204f…` |
| runtime | exo `0.3.70-b5375f8c-dirty-caae13ca8197` |
| template | `exo-mlx-parse-gpt-oss-b5375f8c` |
| quantization | `mxfp4` |
| dialect | `harmony_chat_sse` (`POST /v1/chat/completions`) |
| topology | three-node exo Pipeline/MLX Ring over Thunderbolt |
| reasoning effort | `medium` |

The runtime pin includes a digest of the operator's uncommitted exo patch because the live cluster
used source changes beyond commit `b5375f8c`. The artifact identifies the deployment without
publishing the patch, endpoint, node identities, hostnames, or private paths.

## Result

`verdict=compatible`, `reason_code=null`, exit `0`.

Three model turns; both pinned tools invoked in the required order; terminal BDR checkpoint
`ready_for_review`.

| measurement | value |
|---|---|
| total tokens | 2270 |
| input / cached input | 2012 / 0 |
| output | 258 |
| reasoning | 182 |
| output tokens per second | 21.75 |
| wall time | 89.0 s |
| mean time to first event | 25.7 s |
| provider attempts / retries | 3 / 0 |
| node-hours | 0.074206 |
| cost | `null` — no operator rate configured |

The first turn accounted for 73.6 seconds, including a 65.6-second first-event delay. The next two
turns completed in 8.6 and 6.8 seconds. The record preserves that warm-up behavior instead of
discarding the first attempt.

## What this proves

For this pinned deployment, BAT can use distributed GPT-OSS-120B as its reasoning backend over
exo's native Harmony Chat wire. Raw reasoning survived both tool continuations, exact tool-call
identity round-tripped, terminal usage was reported, and the controller reached its expected BDR
handoff state without a retry.

## What this is not

- Not a model-quality result on a Java regression. The probe uses BAT's fixed trusted stub tools.
- Not a complete issue #25 Phase 2 or Phase 3 run through the isolated Java worker and evaluator.
- Not a 20B-versus-120B ranking. The deployments used different hardware, topology, reasoning
  effort, and warm-up state, and each has only one preserved compatible run.
- Not evidence that an unpinned exo deployment or another topology will behave identically.
