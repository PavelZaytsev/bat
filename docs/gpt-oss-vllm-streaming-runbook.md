# GPT-OSS on vLLM: streaming tool-call compatibility runbook

This runbook records the GPT-OSS/vLLM compatibility failures already diagnosed during BAT and
direct BDRv1 experiments. Read it before changing prompts, retry policy, controller code, or GPU
topology. The failures below look similar at the client boundary but have different causes and must
not share a speculative repair.

The durable rule is:

> Preserve the complete failed exchange, identify the failing layer, and apply a version-pinned
> upstream repair or a separately preregistered protocol change. Never repair malformed model
> arguments and execute them.

## Known-good deployment invariants

- Pin the exact GPT-OSS weight revision and vLLM image digest.
- Use the native GPT-OSS parser: `--tool-call-parser openai`.
- Use the native reasoning parser when Chat Completions requires it:
  `--reasoning-parser openai_gptoss`.
- Do not set a coarse `--stream-interval`; use vLLM's default token cadence.
- Authenticate the endpoint and verify the served model identity before inference.
- Run a synthetic tool round trip before exposing a repository.
- Treat every incomplete stream as indeterminate. Retry only a fully received response rejected
  before any tool intent or execution, from the same closed checkpoint, under a bounded policy.

## Failure 1: `prev_tool_call_arr[index]` crashes the stream

### Signature

The server raises `IndexError: list index out of range` in the Chat Completions streaming generator
while indexing `tool_parser.prev_tool_call_arr[index]`. The request may use GPT-OSS, the OpenAI tool
parser, streaming, and a non-default stream interval.

This was reproduced in the rented GPT-OSS-120B experiment on vLLM 0.15.1. It matched upstream
[vLLM issue #36849](https://github.com/vllm-project/vllm/issues/36849) and
[vLLM PR #37958](https://github.com/vllm-project/vllm/pull/37958).

### Upstream repair

The relevant guard requires a parsed tool call before indexing the parser's tool-call arrays:

```diff
 if (
     should_check
     and tool_parser
+    and auto_tools_called
 ):
```

Prefer upgrading to a pinned vLLM release containing the upstream repair. For an immutable legacy
0.15.1 environment that cannot be upgraded, the experiment used this directed patch after first
backing up the installed file:

```bash
serving_py=/workspace/vllm-0.15.1-cu129/lib/python3.12/site-packages/vllm/entrypoints/openai/chat_completion/serving.py
cp "$serving_py" "$serving_py.pre-pr37958"
sed -i '/and tool_parser$/a\                and auto_tools_called' "$serving_py"
/workspace/vllm-0.15.1-cu129/bin/python -m py_compile "$serving_py"
```

Do not run that command against an unknown version or path. Inspect the installed source first and
record the original and patched hashes. Restart only the model server, then replay a synthetic
probe—not a repository action.

### Current status

The pinned vLLM 0.20.1 source already contains the equivalent bounds condition:

```python
if should_check and tool_parser and auto_tools_called:
```

Reapplying PR #37958 to 0.20.1 is therefore wrong and cannot explain a complete response whose
argument string itself contains invalid JSON.

## Failure 2: coarse streaming produces an empty tool list

### Signature

The response ends with `finish_reason=tool_calls`, but the streamed response contains no usable tool
call. The server may remain alive. A raw wire capture shows that a large `stream_interval` grouped
tokens more coarsely than the OpenAI tool parser could reconstruct.

### Repair

Remove `--stream-interval 20` (or any other non-default coarse interval) from both the pod and its
template. Restart the model server and rerun the synthetic tool round trip. Do not compensate in the
client by inventing a missing tool call.

This was a second, independent defect in the vLLM 0.15.1 deployment. Applying PR #37958 stopped the
crash; removing the coarse stream interval fixed the empty-tool-list framing failure. Both changes
were required before the three-turn Harmony probe passed.

## Failure 3: a complete tool argument string is invalid JSON

### Signature

The stream completes normally and identifies the tool, but `function.arguments` is not strict JSON.
In the 2026-08-16 direct BDRv1 run, four rejected multiline patch calls ended with an extra array
bracket, for example `PATCH"]}` instead of `PATCH"}`. Short read commands serialized correctly.

This is not the PR #37958 crash and not the empty-tool-list failure:

- vLLM 0.20.1 already contains the `auto_tools_called` guard;
- the deployment did not configure a coarse stream interval;
- the client received a complete argument string rather than an absent tool call; and
- no rejected command was parsed or executed.

The vLLM GPT-OSS parser validates the Harmony message with `json.loads`. On `JSONDecodeError`, both
vLLM 0.20.1 and current upstream log the error and forward the raw malformed text as
`function.arguments`. The client must therefore validate it again and fail closed.

### Safe response

1. Persist the fully received rejected response privately and expose only hashes and structural
   diagnostics publicly.
2. Prove that the source checkpoint is closed and unchanged and that no tool intent or execution
   occurred.
3. Retry the same logical call only within the frozen bounded pre-tool retry policy.
4. On exhaustion, terminate honestly. Never delete the extra bracket, salvage a prefix, or execute a
   guessed command.
5. Before another full repository run, compare a minimal named-tool probe across:
   - streaming Chat Completions;
   - non-streaming Chat Completions; and
   - the GPT-OSS-native Responses/Harmony path.

Changing the endpoint dialect, streaming mode, or vLLM release is a protocol change. Freeze it as a
new canary with its own runtime identity and acceptance record; do not silently relabel an existing
attempt.

## Diagnostic decision table

| Observed failure | Likely layer | Required action |
| --- | --- | --- |
| `IndexError` at `prev_tool_call_arr[index]` | Old vLLM streaming generator | Upgrade or apply PR #37958's bounds guard to the exact pinned legacy runtime. |
| `finish_reason=tool_calls`, empty tool list, coarse interval configured | vLLM streaming cadence/parser interaction | Remove the non-default stream interval and rerun a synthetic probe. |
| Complete `function.arguments`, strict JSON parse fails | Generated Harmony payload or unconstrained parser path | Fail closed; preserve; bounded pre-tool retry; test non-streaming/Responses or a pinned newer runtime. |
| Stream ends before `[DONE]`, usage, or terminal metadata | Transport is indeterminate | Do not retry as a completed response and do not execute any partial tool payload. |
| Served model identity differs from the pin | Provider/runtime identity | Reject before inference or tool execution. |

## Pre-inference checklist

Before starting paid repository inference, record affirmative evidence for every item:

- [ ] GPU provider is stopped while preparing the canary.
- [ ] Model revision, vLLM version, image digest, CUDA runtime, context window, and topology are pinned.
- [ ] Installed `serving.py` contains the `auto_tools_called` guard or the runtime predates the bug
      and carries a hash-recorded directed patch.
- [ ] Pod and template contain no non-default coarse stream interval.
- [ ] Anonymous identity requests are rejected and an authenticated request returns the exact model.
- [ ] Synthetic named-tool arguments are strict JSON and conform to the one-string command schema.
- [ ] Tool-result continuation and context maintenance pass using the selected dialect.
- [ ] Partial-stream, malformed-response, retry-exhaustion, and restart tests fail closed locally.
- [ ] The candidate repository is clean, isolated, and still at its preregistered tree.
- [ ] A hard cost stop and provider-stop procedure are active.

## Why this is in the repository

Provider compatibility is part of the experiment, not disposable operator memory. A successful
deployment must leave behind enough sanitized, version-specific evidence that another operator can
answer three questions before spending GPU time:

1. Has this exact failure already been diagnosed?
2. Does the pinned runtime already contain its repair?
3. Is the new symptom actually the same mechanism?

If any answer is unknown, stop before the repository run and perform a minimal transport probe.
