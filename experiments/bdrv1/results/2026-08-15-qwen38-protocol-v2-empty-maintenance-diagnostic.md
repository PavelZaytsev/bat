# Qwen3.8-27B protocol-v2 empty-maintenance diagnostic — 2026-08-15

This was a fresh, non-secret PairKey continuity canary after the maintenance response had been
constrained by a strict JSON Schema. Its purpose was to require the exact completion marker under a
64-call canary ceiling while exercising both a forced and a natural context-maintenance boundary.
It was not a CorfuDB run and is not a model-qualification result.

## Result

The run completed 19 closed assistant/tool turns and crossed its forced first maintenance boundary.
After the nineteenth turn, the next work request was estimated at 101,689 tokens and triggered
natural context maintenance. The streamed response completed with zero final-content bytes, so the
runner could not parse the required strict JSON packet and stopped with a non-retriable
`model_protocol_error`.

The last durable checkpoint is a complete turn boundary with all 19 requested tool calls closed.
The immutable policy, first continuation, workspace fingerprint, and independent continuation
preflight validate. The repository remained clean at its synthetic base commit, the tracker
remained byte-identical to its template, and there was no candidate commit. The honest
classification is therefore **empty context-maintenance final content before a work product**, not
completion, context-window overflow, or a BDR/model-semantics failure.

This attempt is distinct from the preceding maintenance-schema diagnostic. That earlier attempt
returned a JSON object with the wrong field set after 17 work turns; this attempt used the strict
schema but produced no final-content JSON at its natural boundary.

## What the old diagnostic cannot prove

This runner revision retained the final-content length and digest but did not retain safe
finish-reason, reasoning-channel length, or terminal usage diagnostics. The response may have spent
its 4,096-token allowance in model reasoning before emitting final content, but that explanation is
an inference from the Qwen/vLLM behavior rather than preserved evidence. The attempt must not be
relabeled as a proven reasoning-budget failure.

## Evidence-earned protocol change

The next protocol revision keeps full reasoning on ordinary work turns but supports a
maintenance-only, policy-bound backend body. For the Qwen deployment, the next fresh canary
preregisters `chat_template_kwargs.enable_thinking=false`, while retaining the same strict schema,
independent post-validation, output allowance, frozen BDR bytes, and fail-closed behavior. It also
records only redacted structural diagnostics for content, reasoning, finish reason, and usage.

This changes transport for context maintenance only. The same model still authors the evidence,
unresolved judgments, summary, and next plan; no BDR phase prompt, phase scheduler, Corfu-specific
knowledge, or human handoff was added.

## Safe metrics

- 19 completed work responses and 19 completed offline tool calls
- 2 maintenance calls started; 1 completed continuation
- 22 model attempts, 1 retry, and 0 abandoned attempts
- 21 durable checkpoints and 129 hash-chained events
- 452,929 prompt tokens and 3,388 completion tokens across 20 responses with retained usage
- 194.662 seconds from the first model attempt through the terminal response
- terminal maintenance finish reason, reasoning length, and usage are unknown because this older
  runner revision did not retain them

## Archived safe artifact

- [`qwen38-protocol-v2-empty-maintenance-diagnostic-metrics.json`](artifacts/qwen38-protocol-v2-empty-maintenance-diagnostic-metrics.json)

The raw state, transcript, model reasoning, tool observations, endpoint/configuration, container
inspection, scratch files, and continuation packet remain private because they contain unnecessary
operational detail.
