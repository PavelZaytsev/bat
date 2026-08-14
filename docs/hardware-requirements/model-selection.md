# Model selection for BAT

BAT is intended to review and refactor difficult Java systems code: distributed state, concurrency,
resource ownership, persistence, and cross-module information flow. Model choice matters, but a
vendor benchmark is not evidence that a model can complete BAT's six-phase workflow on such a
repository.

This document records why GPT-OSS-120B is the current first target, why Gemma 4 31B is a serious
alternative, and why Muse Glimmer 30B remains an interesting watch-list model. It is a decision log,
not a permanent ranking.

## Keep the two model roles separate

There may be two AI models involved in an experiment:

1. an **implementation model** that writes or finishes BAT integration code; and
2. the **runtime inference actor** that BAT asks to inspect and refactor the Java pull request.

They need not be the same model. A local Gemma model could finish an adapter while GPT-OSS-120B is
the actor evaluated by BAT. Conversely, Gemma could later become the actor after its own native
dialect, replay, and conformance work is complete. Results must always name the runtime actor rather
than the model that happened to prepare the experiment.

## Evidence classes

Every statement below belongs to one of three classes:

- **BAT-measured** — preserved in a validated BAT evidence bundle.
- **Vendor-reported** — taken from an official model card or documentation under that vendor's
  evaluation setup.
- **Prediction** — a hypothesis about BAT performance that requires a controlled run.

Scores from different vendors are not directly comparable unless the task, harness, prompting,
reasoning budget, sampling, and number of attempts are the same.

## Current decision

Use **GPT-OSS-120B for the first live Java acceptance run** because it is the only candidate whose
complete BAT wire path has already passed on the available cluster. In parallel, make **Gemma 4 31B
the next qualification target** because it is expected to be easier to place and could be the more
practical everyday actor. Treat **Muse Glimmer 30B as an exploratory target** until its serving path
and native reasoning/tool transcript are verified on our hardware.

This is a sequencing decision, not a claim that GPT-OSS-120B will ultimately produce the best Java
patches per day.

| Candidate | BAT wire evidence | Likely deployment burden | Current role |
|---|---|---|---|
| GPT-OSS-120B | compatible three-node live probe | highest of the three | first Java acceptance actor |
| Gemma 4 31B | no BAT adapter or live probe yet | predicted lower; exo card exists, team fit unmeasured | next qualification target |
| Muse Glimmer 30B | no BAT adapter or live probe yet | potentially low, but exo support unconfirmed | watch-list experiment |

## Why start with GPT-OSS-120B

### What BAT has measured

BAT already implements GPT-OSS Responses and native Harmony Chat dialects, including strict streamed
tool calls, opaque reasoning continuation, usage accounting, and fail-closed protocol validation.
The Harmony path has passed the fixed three-turn/two-tool contract against:

- GPT-OSS-20B on one M1 Pro; and
- GPT-OSS-120B on three exo Pipeline/MLX Ring nodes.

The [120B evidence bundle](../../benchmarks/probes/gpt-oss-120b-exo-three-node-001/README.md)
therefore removes a large integration uncertainty: BAT and this pinned deployment can already speak
the required protocol. It does not establish Java repair quality.

### What OpenAI reports

[OpenAI's model release](https://openai.com/index/introducing-gpt-oss/) describes GPT-OSS-120B as a
117-billion-parameter mixture-of-experts model with 5.1 billion active parameters per token, a
128K-token context, adjustable reasoning effort, structured outputs, and native Harmony tool use.
OpenAI reports performance near o4-mini on several core reasoning evaluations and strong results on
coding and tool-use evaluations.

Those results make it a plausible actor for long causal investigations. They do not answer whether
it can understand a large Java 25 system, obey BDR for hours, find real concurrency defects, or
produce a patch the team accepts.

### Predictions to test

- The larger model may be better at preserving cross-file invariants and resisting a plausible but
  local fix.
- The established Harmony integration should make early failures easier to attribute to model
  behaviour rather than transport bugs.
- The three-node cluster's lower throughput and greater restart surface may erase a quality
  advantage in completed findings per day.
- Because GPT-OSS-120B is sparse, its nominal parameter count is not a direct compute-equivalent
  comparison with a dense 31B model.

## Why Gemma 4 31B may become the practical default

### What BAT has measured

Nothing yet. BAT has no Gemma 4 dialect, conformance artifact, Java canary, or exo capacity result.
It must not be selected through the GPT-OSS Harmony adapter merely because a server exposes an
OpenAI-shaped HTTP envelope.

### What Google reports

Google's [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4) describes the 31B
model as a dense 30.7B-parameter reasoning model with a 256K-token context, native function calling,
and coding capabilities. The same card reports 80.0 on LiveCodeBench v6, 76.9 on the Tau2 aggregate,
and 66.4 on its 128K MRCR setting. These are useful signals under Google's evaluation methodology,
not BAT comparisons with GPT-OSS.

The pinned exo source already contains a
[Gemma 4 31B 4-bit model card](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/resources/inference_model_cards/mlx-community--gemma-4-31b-it-4bit.toml).
Google's [deployment overview](https://ai.google.dev/gemma/docs/core) estimates approximately 17.5 GB
for Q4 and 34.9 GB for SFP8 weights, before KV cache and full runtime overhead. That makes a one- or
two-node experiment plausible; it does not prove that the pinned artifact will load or remain stable
on the team's machines.

The pinned exo [placement code](https://github.com/exo-explore/exo/blob/b5375f8cee4368d09e1ce96a56b9f81fb0bc81aa/src/exo/master/placement.py)
explicitly rejects multi-node Pipeline placement for Gemma 4. Qualification should therefore try a
single-node 4-bit deployment first. If that does not fit, a two-node attempt needs a supported Tensor
placement and compatible interconnect rather than the GPT-OSS Pipeline/Ring recipe.

Gemma's continuation contract is also different. Google's
[prompt-format documentation](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4)
generally excludes thoughts from earlier ordinary turns, while preserving the thought content of a
tool-call turn with its tool interaction. BAT therefore needs a Gemma-specific opaque context and
stream assembler rather than a lowest-common-denominator chat-history adapter.

### Predictions to test

- A smaller deployment should be easier to keep stable and may deliver more completed BAT turns per
  hour.
- A 256K context could provide more headroom for long histories, although BAT should still retrieve
  focused slices rather than paste a whole repository into one prompt.
- Dense 31B compute may be slower than its storage footprint suggests, while stronger throughput
  and fewer cluster nodes may still improve end-to-end wall time.
- BDR's explicit gates and durable tracker may let a smaller model compete by reducing the amount of
  strategy it must invent.

The first Gemma milestone is not a full private-repository run. It is a native dialect plus the same
fake-server and live conformance contract GPT-OSS passed, followed by the maintained Java canary.

## Where Muse Glimmer 30B fits

Muse Glimmer is attractive because Meta positions its Apache-2.0 open weights for agentic, coding,
long-context, and structured tool-use workloads at a much smaller deployment size. See the official
[model card](https://huggingface.co/meta-models/Muse-Glimmer-30B) and
[evaluation methodology](https://research.meta.ai/static/muse-glimmer-methodology).

It is not currently a BAT-ready option:

- no BAT adapter or conformance result exists;
- its native ATEM reasoning/tool transcript is not Harmony and needs its own replay rules;
- availability on the team's exo/MLX revision has not been confirmed; and
- the team has not yet reproduced the official or community deployment claims on its Macs.

If a pinned runtime can serve its complete native reasoning and tool-call state, it should enter the
same qualification ladder as Gemma. Until then, a hypothetical two-node fit is a capacity prediction,
not a reason to interrupt the working 120B acceptance path.

## What actually matters for a large systems repository

Model selection should optimize for accepted causal repairs, not chat speed or a single coding score.
For each candidate, measure:

- valid BDR slices discovered versus findings rejected by developers;
- boundary, authority, and concurrency defects found;
- false positives, duplicates, and missed known defects;
- focused tests that fail at the intended assertion;
- sibling cases saturated and counterfactuals that turn red;
- self-induced regressions and broad-suite failures;
- iterations, tool calls, prompt/output/reasoning tokens, retries, and restarts;
- time to first useful finding, time to reviewed patch, and total wall time; and
- developer minutes required to understand and accept or reject the result.

A smaller model that needs tighter prompts but completes two sound slices per day can be more useful
than a larger model that finds a deeper issue but repeatedly loses an eight-hour run. The opposite
can also be true for subtle ownership or distributed-state defects. Only blinded, developer-scored
runs can settle that trade.

## Qualification ladder

Promote each model independently:

1. **Candidate** — official weights and a viable runtime path exist.
2. **Endpoint-qualified** — one pinned deployment stays stable on the team's hardware.
3. **BAT-wire-qualified** — the native transcript contract, fragmented streaming, strict tools,
   continuation, retries, redaction, and telemetry pass both deterministic and live probes.
4. **Canary-qualified** — the maintained Java 25 canary traverses all six phases through the real
   worker and independent evaluator.
5. **PR-qualified** — the model completes a representative 1K-5K-line distributed/concurrency PR
   within the agreed wall budget and restart contract.
6. **Pilot-qualified** — several real PRs produce useful, human-accepted results without excessive
   regressions or intervention.

GPT-OSS-120B is currently at step 3. Gemma 4 31B and Muse Glimmer 30B remain at step 1 until their
exact deployments are served and measured.

Do not promote a model because it generated one impressive patch, and retain nonconformant and failed
runs rather than tuning them out of the record.

## Near-term experiment order

1. Finish restart-aware GPT-OSS-120B Java acceptance and run the maintained canary.
2. If that passes, attempt one bounded supervised Java slice before scaling to the full PR.
3. Build and qualify the Gemma 4 31B dialect as the lower-capacity fallback and potential default.
4. Revisit Muse Glimmer after a pinned exo/MLX or alternative serving path is reproducible.
5. Run the same blinded fixture and scoring rubric across candidates; never compare vendor headline
   scores as if they were BAT results.

The current prediction is that GPT-OSS-120B has the highest probability of completing the first deep
causal repair, while Gemma 4 31B may eventually win on availability, stability, and repairs per day.
Muse Glimmer could alter that trade if its agentic behaviour and Apple Silicon runtime reproduce
cleanly. All three statements remain hypotheses until the Java evidence exists.
