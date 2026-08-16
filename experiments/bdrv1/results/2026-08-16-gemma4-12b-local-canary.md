# Gemma 4 12B dense local BDRv1 canary

Date: 2026-08-16

Issue: #38

Status: the MLX transport diagnostic failed at context maintenance; a corrected GGUF/native
attempt passed pause/cold-resume continuity but failed autonomous task closure.

## Why this run exists

The work-laptop model is a free, fast development proxy for the planned Gemma 4 31B Dense exo
qualification. It is a useful data point for runner, template, tool, and context-maintenance
compatibility. It is not a substitute for the 31B qualification and cannot accept or reject the
31B hypothesis.

## Frozen candidate

- model alias: `gemma4:12b-mlx-131k`
- base model digest: `117d0d84cf2ab865feb59afc2cd30ff5d55f0035e05eb8d1b814f9688e3f3671`
- derived alias digest: `2d2b3c07212257fca0e7f29cefa29f1b38b8bc9c09f63a6f7197c7915b01366a`
- architecture: dense `gemma4_unified`, 48 blocks, 12,382,568,756 parameters
- precision: MLX NVFP4
- native model context: 262,144 tokens
- served context: 131,072 tokens
- serving stack: Ollama 0.32.6 on an M1 Pro Mac with 32 GB unified memory
- endpoint: SSH loopback tunnel only; no public model port and no cloud inference
- runner: repository commit `16bf90f`, SHA-256
  `d2b333a98492f8ef05e8a7ad2059bbca7067cb966562d94547340a7613a47e26`
- candidate HEAD/tree: `1e0837c9a631c6ffe2b0eab9ac3827a492f80a6a` /
  `9ea23ba241fc486b8727c28270bb461df742edbb`

The disposable tool container had no network, a read-only root, UID:GID `501:20`, all
capabilities dropped, `no-new-privileges`, and six exact mounts. The initial `PairKeyTest` passed,
the repository was clean, the tracker equalled its template, and scratch was empty.

## Identity calibration

Two fresh attempts stopped before completing a model turn while establishing Ollama's response
identity contract:

1. a machine-specific configured fingerprint was rejected because Ollama did not return it;
2. `response-unavailable` was rejected because the completed response did return `fp_ollama`.

Both attempts completed zero turns and executed zero tools. A direct read-only response probe then
pinned `system_fingerprint=fp_ollama`. The third attempt used that exact value. These are adapter
calibration failures, not Gemma capability results.

## Result

The third fresh attempt successfully completed its first work response and one named `bash` tool
action. The response identity and usage were valid:

- work latency: 11.16 seconds;
- work usage: 1,588 prompt tokens and 239 completion tokens;
- tool actions requested/completed: 1/1;
- repository changes after the tool action: none.

The preregistered forced compaction then ran from the closed post-tool checkpoint. It returned a
complete response with valid model identity, fingerprint, usage, and `finish_reason=stop`, but the
1,220-byte content was not strict JSON:

- compaction latency: 85.94 seconds;
- compaction usage: 5,161 prompt tokens and 312 completion tokens;
- content SHA-256: `feba55030c869cc338e0851aaf30016bb3148b4960bfc115ae2bbdaddefa4335`;
- reasoning SHA-256: `024cb956c6c5026076cac267a9ba1d5513b018208eeb008ff70fb3f95095c98f`;
- terminal classification: `model_protocol_error`;
- terminal message: `context-maintenance response is not strict JSON`.

The runner failed closed before creating a continuation packet or pause point. No cold resume was
attempted. It did not repair, salvage, or replay the malformed response. Final repository HEAD and
tree remained byte-identical to the initial candidate; the worktree was clean, the tracker still
equalled its template, and scratch remained empty.

## Interpretation

This is a useful negative transport result, not evidence that Gemma 4 12B cannot perform BDR. The
model demonstrated a valid strict named-tool turn, but the Ollama Gemma template did not satisfy the
typed JSON context-maintenance contract. The next cheapest diagnostic is to inspect an isolated
maintenance response and the Ollama/Gemma response-format path without repository access. Do not
weaken the runner by extracting JSON from prose or repairing malformed output.

The 31B exo experiment remains independently necessary. It should use its pinned native Gemma tool
and reasoning parsers and must pass a one-shot synthetic maintenance gate before receiving a
repository prompt.

## Root cause and typed correction

Ollama issue [#17183](https://github.com/ollama/ollama/issues/17183) reproduces the exact failure:
MLX models, explicitly including `gemma4:12b-mlx`, silently ignore structured-output schemas while
the equivalent GGUF model honors them. The runner was therefore correct to reject the prose.

The follow-up did not parse prose or relax validation. Commit `b051ba9` added an explicit
`ollama_native_chat` transport. It uses complete native `/api/chat` responses, maps the exact
maintenance schema to native `format`, disables thinking only for maintenance, normalizes native
typed tool calls into the existing strict envelope, and preserves fail-closed retry/restart
behavior. The full suite passed 96/96. The exact previously failed maintenance transcript then
returned a valid four-field continuation packet.

## Fresh GGUF/native repository canary

The corrected attempt used dense 11.9B `gemma4:12b-131k`, GGUF Q4_K_M, with base digest
`4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c` and derived 131K alias
digest `d619e9ad5dc334919cd66c79db4832a966c04da7acb1ee9e9cc2de4f6e836c25`. Its frozen runner SHA-256
was `4d606c669ad572ba55b39986b7a5e9b1003e8d029010516ad8adea24c34675ee`; the private root is
`/private/tmp/bdrv1-v2-local-gemma12-canary-20260816d`.

The fresh attempt completed one work/tool turn, forced strict maintenance, wrote a valid
continuation, and paused with `safe_to_resume=true`. A new process cold-resumed the exact run with
no lost or replayed action.

The resumed model completed 23 work/tool turns. It found the correct source and tests, implemented
the empty-left behavior, and added both required assertions. The resulting repository test passed
when independently rerun. The implementation formatting was poor but semantically correct.

The model did not close the task:

1. it created an untracked `canary/` compilation-output directory;
2. it accidentally truncated `slices_progress.yaml` to one byte;
3. it repeatedly misread `ls -l` output and failed to understand that the tracker file was writable
   while its parent methodology directory was read-only;
4. it attempted rename-based `sed -i`, which failed on the read-only directory;
5. the next logical work call produced no required tool call on all three complete attempts,
   exhausting the frozen two-retry budget and terminating as `model_protocol_error`.

Final counters were 23 completed turns, 23 completed tool calls, one completed compaction, two
retries, and zero task-completion calls. State SHA-256 was
`6b29a0f7febdcf0a73f0d0a8a4f79dd1d9bc46276e244e6b4c0f9519053ac793`. The candidate was not
committed, the tracker was invalid, and this exact 12B deployment is rejected for autonomous BDR.

The compatibility correction exposed the model's real capability: it can implement the tiny Java
requirement and continue across forced pause/resume, but it could not reliably manage durable BDR
state or recover from its filesystem mistake. The 31B exo gate should reuse the enforced-schema
lesson and treat durable-state recovery as a first-class capability. Do not generalize this 12B
failure to 31B, and do not hide it with controller-side tracker repair.
