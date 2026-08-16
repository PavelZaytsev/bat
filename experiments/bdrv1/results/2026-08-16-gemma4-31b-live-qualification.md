# Gemma 4 31B direct BDRv1 live qualification

Date: 2026-08-16

Status: provider-blocked before inference; qualification remains pending.

This result belongs to issue #34 and draft PR #35. It records the exact
deployment contract, pre-inference evidence, cost boundary, and current
provider failure so a later restart does not repeat deployment discovery.

## Candidate

- model: `google/gemma-4-31B-it`
- revision: `842da3794eaa0b77d5f08bae87a17459d91ff475`
- weights/activations: BF16
- context window: 131,072 tokens
- runtime: `vllm/vllm-openai:v0.27.1`
- amd64 image digest:
  `sha256:c2f3b1b964e47809b722b5e75b61b1e7b39a50f70388cf2bf2418f16a9f31da2`
- hardware: one H200 SXM in RunPod Secure Cloud EUR-IS-4
- persistent cache: 200 GB network volume mounted at `/workspace`

## Exact RunPod/vLLM entrypoint contract

The vLLM image entrypoint is already `vllm serve`. The RunPod “Container
start command” must therefore contain the model tag and options only:

```text
google/gemma-4-31B-it --revision 842da3794eaa0b77d5f08bae87a17459d91ff475 --served-model-name google/gemma-4-31B-it --dtype bfloat16 --tensor-parallel-size 1 --max-model-len 131072 --max-num-seqs 1 --max-num-batched-tokens 8192 --gpu-memory-utilization 0.90 --download-dir /workspace/huggingface --enable-prefix-caching --enable-auto-tool-choice --tool-call-parser gemma4 --reasoning-parser gemma4 --chat-template /vllm-workspace/examples/tool_chat_template_gemma4.jinja --generation-config vllm --enforce-eager --host 0.0.0.0 --port 8000
```

Do not prepend `bash -lc`, `vllm`, or `serve`.

Do not pass JSON through RunPod's start-command tokenizer. In particular,
`--default-chat-template-kwargs '{"enable_thinking":true}'` lost its quotes.
The BDR transport instead sends
`chat_template_kwargs.enable_thinking=true` in every work and
context-maintenance request.

`--disable-log-requests` is intentionally absent. New vLLM defaults request
logging off and exposes the positive `--enable-log-requests` option.

## Evidence-earned deployment corrections

All failed pods were stopped before replacement; their disposable container
disks were removed and the independent network volume was preserved.

1. `js68lk97kou3xb`: `bash -lc` was appended to the image entrypoint and
   rejected as vLLM arguments.
2. `0y1xdhwtflyuht`: RunPod stripped JSON quoting from the redundant
   default-thinking CLI argument.
3. `xogj9i2n23os4d`: the command still prepended `serve`, even though the
   image entrypoint already supplies it.
4. `xre8ndqwd5p8pr`: the corrected command reached vLLM's API server and
   printed the exact expected non-default argument set.

No attempt reached model loading far enough to accept a prompt. No synthetic
transport request, context-maintenance request, repository prompt, or tool
execution occurred.

## Current provider blocker

The corrected pod failed while resolving Hugging Face from EUR-IS-4:

```text
Error retrieving file list: [Errno -3] Temporary failure in name resolution
httpx.ConnectError: [Errno -3] Temporary failure in name resolution
```

The failure repeated after the pod was stopped, left idle, and restarted.
The pod is now stopped at `$0.00/hr`; its ID is `xre8ndqwd5p8pr`.

This is a provider/network gate failure, not evidence for or against the
Gemma semantic hypothesis. The next authorized action is to restart this same
pod after provider DNS recovery, require an authenticated `/v1/models`
identity response, and then run the one-attempt synthetic tool/compaction
probe. Do not create another deployment revision unless the frozen command
itself is contradicted by new evidence.

## Frozen offline canary

The private live root is:
`/private/tmp/bdrv1-v2-live-gemma4-canary-20260816a.5UwhLF`.

Before any inference:

- candidate HEAD/tree:
  `1e0837c9a631c6ffe2b0eab9ac3827a492f80a6a` /
  `9ea23ba241fc486b8727c28270bb461df742edbb`;
- repository clean, no remotes;
- tracker byte-equal to its template;
- disposable tool container offline, read-only root, unprivileged, all
  capabilities dropped, six exact mounts;
- baseline `PairKeyTest` passed;
- runner state empty;
- synthetic transport artifact directory empty.

The transport probe is intentionally one-shot. It exercises one named
`bash` call with strict single-string command arguments, a tool-result
continuation, and JSON-schema context maintenance without reading the
repository. It must not be run until authenticated model identity succeeds.

## Cost boundary

- initial balance: `$10.89`;
- stopped balance: `$9.90`;
- observed spend: approximately `$0.99`;
- preregistered maximum: 30 H200 minutes / `$2.30`;
- current provider cost: `$0.00/hr`.

The unused budget is preserved for the model identity gate and actual
qualification, rather than being spent on a DNS restart loop.

## Decision rule

Gemma remains the sole active Broadcom-eligible candidate. Its semantic
hypothesis is neither accepted nor rejected. Acceptance still requires:

1. exact served identity and 131,072-token context;
2. synthetic named-tool and compaction transport success;
3. forced pause after the first compaction;
4. new-process cold resume;
5. autonomous repository completion with real source/test changes;
6. tracker evidence consistent with the repository diff and test results.

Any transport failure is archived without repairing model output. Any semantic
false convergence rejects the model for the Monday exo run.
