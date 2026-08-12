# `gpt-oss-20b` on single-node exo — `compatible`

The first live GPT-OSS deployment to pass BAT's conformance probe.

Recorded 2026-08-11. BAT commit `0a24783c2b985b73fb0a63ab7fe5d313d0e68d5c`.

## Deployment

| field | value |
|---|---|
| model | `openai/gpt-oss-20b` |
| weights | `artifactory-sha256-verified-2026-08-10` |
| runtime | exo `0.3.70-b5375f8c` |
| template | `exo-mlx-parse-gpt-oss-0.3.70` |
| quantization | `mxfp4` |
| dialect | `harmony_chat_sse` (`POST /v1/chat/completions`) |
| topology | single-node, 1 node, Apple M1 Pro (32 GB) |
| reasoning effort | `high` |

The controller ran on a Linux VM and reached the model over ordinary HTTP across a routed corporate
network at roughly 50 ms. BAT and the model did not share hardware, an OS, or an architecture, which
is the portability claim this run exercises.

## Result

`verdict=compatible`, `reason_code=null`, exit `0`.

Three model turns; both pinned tools invoked in the required order; terminal BDR checkpoint
`ready_for_review`.

| measurement | value |
|---|---|
| total tokens | 4482 |
| input / cached input | 3456 / 0 |
| output | 1026 |
| reasoning | 948 |
| output tokens per second | 33.75 |
| wall time | 48.6 s |
| mean time to first event | 6.0 s |
| provider attempts / retries | 3 / 0 |
| node-hours | 0.013495 |
| cost | `null` — no operator rate configured |

Reasoning is 92% of output tokens, which is ordinary for this model family at `high` effort and is the
reason a small output allowance yields empty content. See the budget guidance in
[`../../../docs/live-gpt-oss-probe.md`](../../../docs/live-gpt-oss-probe.md).

## What this is not

- Not evidence about `gpt-oss-120b`.
- Not evidence about distributed placement. Two-node placement on this cluster still fails on a
  one-way topology edge.
- Not evidence about model quality on real defects. The scenario is a fixed protocol exercise with
  trusted stub tools; it does not run the six-phase BDR loop against a repository.
- Not a throughput benchmark. Turns two and three replay a growing prompt prefix, and this deployment
  reported zero cached input tokens on every turn, so the run does not demonstrate prefix-cache reuse.
