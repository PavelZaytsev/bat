# BAT hardware journey

> Status checked: 2026-08-13 PT. This is a record of observations, not a hardware sizing guarantee or
> a Java-refactoring benchmark.

BAT's hardware work has followed a deliberate sequence: prove the model wire on one Mac, prove the
same contract with a larger model distributed across three Macs, then move from trusted probe tools
to an isolated Java worker. Each step answers a different question.

## Start hardware-independent

The GPT-OSS adapters, streaming transport, opaque reasoning replay, telemetry, and two-tool
conformance scenario were first built against deterministic local endpoints. Ordinary CI therefore
does not need a GPU, model endpoint, credential, or paid inference call.

That ordering made the live cluster a compatibility test rather than the place where request,
streaming, tool-call, and redaction logic were debugged for the first time.

## Step 1: prove the GPT-OSS wire on one Mac

On 2026-08-11, BAT completed its fixed conformance scenario against `openai/gpt-oss-20b` served by exo
on one Apple M1 Pro with 32 GB of unified memory. **Repository-verified:** the run used Harmony Chat
SSE, completed three model turns, invoked both pinned tools in order, preserved reasoning continuity,
and reached `ready_for_review` without a retry.

| Measurement | Result |
|---|---:|
| Reasoning effort | `high` |
| Total tokens | 4,482 |
| Output / reasoning tokens | 1,026 / 948 |
| Output rate | 33.75 tokens/s |
| Wall time | 48.6 s |
| Mean time to first event | 6.0 s |

The BAT controller ran on a separate Linux machine and reached the Mac-hosted model over HTTP. The
controller and inference hardware therefore do not need to share a machine, operating system, or CPU
architecture.

Evidence: [`gpt-oss-20b-exo-single-node-001`](../../benchmarks/probes/gpt-oss-20b-exo-single-node-001/README.md).

## Step 2: fit GPT-OSS-120B across three Macs

On 2026-08-13, the same contract passed against `openai/gpt-oss-120b`. **Repository-verified:** exo
distributed the model across three nodes using Pipeline placement and MLX Ring over Thunderbolt. The
run completed three model turns, made both required tool calls in order, preserved opaque reasoning
through both continuations, reported terminal usage, and reached `ready_for_review` with zero retries.

| Measurement | Result |
|---|---:|
| Reasoning effort | `medium` |
| Total tokens | 2,270 |
| Output / reasoning tokens | 258 / 182 |
| Output rate | 21.75 tokens/s |
| Wall time | 89.0 s |
| Mean time to first event | 25.7 s |
| First-turn time | 73.6 s |
| First-event warm-up delay | 65.6 s |

The slow first turn remains in the evidence. The next two turns completed in 8.6 and 6.8 seconds, so
the record exposes cold-start behaviour instead of presenting only a warmed-up number.

**Operator-reported:** the physical proof used three work MacBooks connected over Thunderbolt,
spanning M1, M3, and M4 generations. The broader team pool is primarily newer M4/M5 hardware. The
public artifact deliberately records topology and node count without publishing device identities,
hostnames, or the private inventory.

The runtime included operator source changes beyond the pinned exo commit. The evidence records a
digest in the deployment fingerprint without publishing that patch.

Evidence: [`gpt-oss-120b-exo-three-node-001`](../../benchmarks/probes/gpt-oss-120b-exo-three-node-001/README.md)
and [PR #30](https://github.com/PavelZaytsev/bat/pull/30).

## What those runs prove

For the two pinned deployments, BAT successfully exercised:

- streamed Harmony Chat;
- strict tool calls and exact call identity;
- opaque reasoning continuation;
- terminal usage reporting; and
- the expected final BDR handoff.

The results prove that BAT and exo can speak the required wire contract in single-node 20B and
distributed 120B shapes.

They do **not** prove that GPT-OSS can diagnose or repair a Java regression. The scenario uses fixed
trusted stub tools. It does not inspect a repository, execute the six BDR phases against a live
worker, survive a multi-hour interruption, or evaluate a model-authored patch.

The 33.75 and 21.75 tokens/s results are not a controlled 20B-versus-120B comparison. Model size,
hardware, topology, reasoning effort, output mix, context, and warm-up state all changed.

## What we learned

### Capacity and speed are separate

The three-node cluster made the larger model fit. In Pipeline/MLX Ring, every generated token still
passes through every layer stage. Additional nodes can add capacity while adding communication and
synchronization to the critical path.

The expectation that fourth and fifth Pipeline nodes may reduce one-stream TPS is a **hypothesis**.
The repository has one measured three-node point, not a controlled scaling curve. See
[`scaling.md`](scaling.md).

### Cold start belongs in the result

The first 120B turn waited 65.6 seconds for its first event, while the next turns were much faster. A
useful capacity record therefore needs TTFT, prompt processing, output rate, and full-turn wall time,
not only the most flattering warm token rate.

### One model instance should serve one BAT conversation

Separate prefix-cache experiments found that BAT's replay shape can produce an exact prefix hit, but
an unrelated request between turns evicted reuse in the observed setup. Long BAT histories make
exclusive instance ownership more important than short-chat benchmarks suggest. The measurements and
caveats live in [`../exo-efficiency.md`](../exo-efficiency.md).

### Reproducibility does not require publishing the lab

The committed bundles pin model, runtime, template, quantization, topology class, node count, BAT
commit, safe trace, telemetry, and artifact digests. They omit endpoints, credentials, host
identities, private paths, prompts, reasoning, and tool payloads.

## Where live Java acceptance stands

[Draft PR #31](https://github.com/PavelZaytsev/bat/pull/31) adds an explicitly armed live Java entry
point, a Java 25 worker image, a networkless OCI evaluator, canary and Apache pilot profiles, a closed
`javac_test` action, deterministic baseline recording, and handling for strict-tool feedback and
slower container cleanup.

**Repository-verified as of 2026-08-13 PT:** all seven current GitHub checks are green.

No completed live Java result, telemetry bundle, or evidence bundle has been committed. Green CI says
the implementation and repository contracts pass; it does not say GPT-OSS-120B completed the Java
canary. Restart-aware endpoint recovery, durable attempt accounting, and the first honest canary
result remain work in progress.

## What cannot be claimed yet

There is no evidence yet for:

- Java bug-finding or patch quality from GPT-OSS-120B;
- a complete live six-phase Java canary;
- a 1,000-5,000-line Java PR completed in one to three days;
- multi-hour survival across exo or controller restarts;
- long-context throughput on realistic BAT histories;
- a controlled mixed-generation versus matched-generation cluster comparison;
- three-versus-four/five/six-node Pipeline scaling; or
- two three-node clusters running BAT concurrently.

These are measurements to collect, not assumptions to hide inside a requirement.

## Next experiments

1. Finish the restart contract in PR #31 and preserve attempt-aware telemetry.
2. Run the maintained Java canary through the real worker and evaluator, retaining success or honest
   failure evidence.
3. Run the larger Apache pilot only after the canary.
4. Compare the current mixed trio with a closely matched modern trio under identical conditions.
5. Benchmark three, four, five, and six Pipeline/Ring nodes where memory permits.
6. Compare one larger ring with two isolated three-node replicas.
7. Benchmark short, medium, and long BAT-shaped histories without interleaved requests.
8. Attempt one bounded real Java PR and have developers score every reported finding.

## Current honest summary

BAT has crossed the transport boundary. GPT-OSS-20B and GPT-OSS-120B both completed the strict
conformance contract through exo, and 120B did so through a three-node Thunderbolt Pipeline/MLX Ring
deployment.

The project is now crossing the harder boundary from “the model and controller speak correctly” to
“the system performs useful, durable Java refactoring.” PR #31 builds that path; the live Java canary
is the next proof point.
