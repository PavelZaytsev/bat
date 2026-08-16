# GPT-OSS-120B protocol-v2 completion canary

Date: 2026-08-16  
Issue: #34  
Model: `openai/gpt-oss-120b` at revision
`b5c939de8f754692c1647ca79fbf85e8c1e70f8a`  
Outcome: transport and cold-resume qualification passed; autonomous work acceptance failed

## Frozen runtime

- one H200 SXM, tensor parallelism 1;
- native MXFP4 checkpoint with BF16 activations;
- 131,072-token served context;
- vLLM 0.27.1, pinned image digest
  `sha256:c2f3b1b964e47809b722b5e75b61b1e7b39a50f70388cf2bf2418f16a9f31da2`;
- exact custom server fingerprint `vllm-0.27.1-gptoss-b5c939-h200-tp1`;
- strict, named, one-string `bash` function schema; and
- runner commit `16bf90ff88b8d2643985557b3e40ffaf3029bef6`, implementation SHA-256
  `d2b333a98492f8ef05e8a7ad2059bbca7067cb966562d94547340a7613a47e26`.

The release pin matters. vLLM PR #45560 added constrained GPT-OSS/Harmony tool-call decoding after
the previously deployed v0.20.1 runtime. Under v0.20.1, fully received multiline patch calls could
end in malformed arguments such as `PATCH"]}`. Under v0.27.1 with a strict function declaration,
the synthetic patch-shaped streaming gate produced the exact 2,347-byte command, terminal usage,
and `[DONE]`, and the real repository runs emitted valid tool arguments throughout.

## Protocol result

Attempt Q performed the required sequence:

1. authenticate and verify the exact served model;
2. start from the pristine synthetic repository and tracker template;
3. complete one real work/tool turn;
4. force context compaction at the resulting closed checkpoint;
5. exit the first host process with `safe_to_resume: true`; and
6. cold-resume from a new host process with the identical runner, policy, inputs, workspace, and
   checkpoint.

The resumed logical run reached a normal terminal completion with:

- 33 completed work turns;
- 33 closed tool actions;
- one completed compaction;
- zero retries;
- zero abandoned model attempts; and
- no malformed, partial, replayed, or indeterminate action.

This qualifies the pinned GPT-OSS/vLLM transport and the protocol-v2 pause/cold-resume mechanism.
It does **not** qualify the model as an autonomous BDR worker.

## Independent work acceptance

The model's committed Java change was narrow and correct:

- empty left: `PairKey.join("", "beta") == "beta"`;
- null left remained `"null:beta"`;
- non-empty behavior remained `"alpha:beta"`; and
- non-empty left with null right remained `"alpha:null"`.

Repository tests passed, the candidate was clean, and the model created a self-contained commit.
The original 19-rule tracker validator and its mutation self-test also passed. The model nevertheless
failed the task's requirement that implementation, tests, tracker, and commit honestly agree.

It declared the only slice and all six phases `done`, marked the finding `fixed`, and reported a
confirmed collapse while retaining literal template facts, including:

- `<name>` and `<host/org/repo>`;
- `<the one missing fact, as a question the code cannot answer>`;
- `File.java:123`;
- `<boundary stated as an assumption someone holds>`; and
- `<axis> x <axis> x <axis>`.

This is false convergence: the statuses changed, but the durable explanation never became about the
repository. A green structural validator amplified the false claim instead of rejecting it.

## Evidence-earned repair

The methodology validator now includes R20. It allows literal placeholders in an untouched pending
template, but rejects them once any slice claims completion. For each done slice it checks:

- completion metadata;
- the slice record and all six phase records;
- findings in its kill list; and
- foreign facts on which the slice says it depends.

R20 has its own mutation self-test. Applied retrospectively to Q's submitted tracker, it reports 17
concrete violations. This is intentionally narrower than semantic truth: plausible but false prose
still requires independent code and test evidence. The rule prevents the exact observed failure
without pretending that a schema can prove engineering judgment.

## Decision

Do not spend another GPT-OSS-120B/H200 run on prompt wording for this canary. The serving and
continuity defects are resolved; the remaining repeated failure is model completion judgment. A
replacement-model comparison should reuse the same base repository, strict tool schema, forced
pause/cold-resume sequence, R20 validator, semantic regression checks, and independent acceptance
gate. Report transport qualification and work qualification separately.

The provider was stopped immediately at Q's terminal state. Issue #34 must not be closed as a
successful GPT-OSS completion gate on this evidence.
