# Gemma 4 31B BDRv1 qualification hypothesis

Date: 2026-08-16

Issue: #34

Candidate: `google/gemma-4-31B-it`

Status: preregistered hypothesis; no Gemma repository inference yet

## Decision context

Qwen3.8-27B is the completed external behavioral reference for direct BDRv1. It is not an eligible
Broadcom deployment model. GPT-OSS-120B is eligible, and its pinned vLLM 0.27.1 deployment passed
strict tool transport, forced context maintenance, clean self-pause, and new-process cold resume.
It nevertheless failed independent work acceptance after producing a correct Java change: it
declared the tracker complete while retaining template facts. Repeating that model with new prompt
wording would not test the remaining hypothesis.

Gemma 4 31B Dense is therefore the next and only active candidate in issue #34. This is a new model
qualification, not a continuation or relabeling of any GPT-OSS attempt.

## Architectural prior

Parameter count is not treated as a capability score. It supplies only a weak prior that must be
overridden by same-task evidence.

| Model | Compute topology | Depth | Active parameters per token | Role |
| --- | --- | ---: | ---: | --- |
| Qwen3.8-27B | dense hybrid sequence model | 64 layers | approximately 27B | external behavioral reference only |
| GPT-OSS-120B | sparse mixture of experts | 36 layers | 5.1B of 116.8B | transport-qualified; work-rejected |
| Gemma 4 26B A4B | sparse mixture of experts | 30 layers | 3.8B of 25.2B | deferred efficiency fallback |
| Gemma 4 31B | dense hybrid local/global attention | 60 layers | 30.7B | next Broadcom candidate |

The hypothesis is that Gemma 4 31B's dense 60-layer language backbone is a better structural prior
for sustained boundary discovery and completion judgment than GPT-OSS-120B's sparse 36-layer
backbone. It is also closer in compute topology and depth to the successful dense Qwen reference.
This does not imply behavioral equivalence: post-training, tool training, data, inference settings,
and the serving adapter may dominate the result. Anthropic does not publish enough Opus
architecture detail to make an architecture-level comparison.

## Falsifiable hypothesis

Under the same frozen PairKey task, BDR methodology, strict one-string `bash` tool, 131,072-token
served window, forced first-turn maintenance, clean process exit, new-process cold resume, limits,
and independent acceptance gates, Gemma 4 31B will:

1. emit only complete, strict, schema-conforming tool arguments through the pinned Gemma 4 parser;
2. preserve exact logical continuity without replay or human-authored handoff;
3. produce the required Java behavior and regression evidence;
4. replace template facts with repository-specific BDR evidence before declaring completion; and
5. reach an honest terminal result within one cost-bounded canary.

The hypothesis is rejected if the serving stack cannot pass the synthetic patch-shaped function
gate, if the repository run ends indeterminately, or if the candidate fails independent semantic
acceptance. A syntactically green tracker is not sufficient.

## Qualification protocol

### Zero-GPU and synthetic gates

- Pin the exact Gemma revision, vLLM image digest, CUDA runtime, precision, topology, chat template,
  reasoning parser, tool parser, and context window.
- Use vLLM's Gemma 4 chat template, `gemma4` reasoning parser, and `gemma4` tool parser.
- Inspect the pinned parser against the known Gemma 4 streaming argument-corruption fixes.
- Exercise streaming and non-streaming synthetic calls before exposing a repository.
- Require an exact multiline, patch-shaped command encoded as one JSON string; validate the final
  bytes, strict JSON, terminal usage, served identity, and continuation after a tool result.
- Do not use speculative decoding in the first qualification.

### Repository gate

- Start from a fresh PairKey candidate and tracker template, not a GPT-OSS workspace.
- Preserve the same protocol-v2 runner and R20 false-convergence rule.
- Force maintenance after the first closed work/tool turn, self-pause, and cold-resume in a new host
  process.
- Permit no prompt repair, parser patch, model swap, transport swap, or human-written continuation
  inside the run.
- Independently check repository behavior, tests, tracker specificity, commit integrity,
  counterfactual evidence, and the exact terminal marker.

### Cost stop

The first Gemma repository canary has a 30-minute paid-compute ceiling. At $4.59 per H200-hour that
is at most approximately $2.30 of node time, excluding storage and any provider billing granularity.
Fail the attempt honestly and stop the provider at the ceiling; do not spend the remaining balance
on repeated prompt variants.

## Decision rule

- If Gemma passes transport, continuity, and independent semantic acceptance, select it as the
  issue #34 primary and advance to the separately frozen CorfuDB qualification.
- If transport fails, stop before repository inference and diagnose the pinned serving layer with
  the synthetic evidence.
- If transport passes but semantic acceptance fails, preserve the result and do not keep rerunning
  the same model. Issue #34 then has no qualified Broadcom open-weight candidate from this shortlist
  and requires a new human model-selection decision.

The experiment compares task outcomes, not vendor nationality or benchmark marketing. Qwen remains
the behavioral reference dataset and is never proposed for Broadcom deployment.
