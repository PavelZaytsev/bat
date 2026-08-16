# Qwen3.8-27B protocol-v2 maintenance-schema diagnostic — 2026-08-15

This was a fresh, non-secret PairKey continuity canary after the earlier 40-call diagnostic. Its
purpose was to require the exact completion marker under a 64-call canary ceiling while exercising
the same phase-opaque context-maintenance and persistence path. It was not a CorfuDB run and is not
a model-qualification result.

## Result

The run completed 17 closed assistant/tool turns and crossed its forced first maintenance boundary.
At an estimated 102,309-token context, the second maintenance call returned strict JSON whose field
set did not match the preregistered four-field maintenance contract. The runner stopped with a
non-retriable `model_protocol_error`.

The durable state, immutable-input binding, event chain, checkpoint chain, first continuation, and
workspace fingerprint all validate. No tool call was dangling, lost, or replayed. The repository
remained clean at its synthetic base commit, the tracker remained byte-identical to its template,
and there was no candidate commit. The honest classification is therefore
**context-maintenance schema failure before a work product**, not completion, runner exhaustion, or
context-window exhaustion.

This differs from the earlier live canary, which reached a complete committed repair but was
runner-limit-censored before emitting the exact completion marker.

## Evidence-earned protocol change

The failed envelope requested JSON mode and then validated exact keys after generation. That
prevented unsafe continuation, but it did not constrain the maintenance response to the required
shape. Protocol v2 now uses the backend's standard JSON Schema response format for the same four
fields, retains independent strict parsing and type checks, binds the exact schema into the policy
fingerprint, and records only safe failure diagnostics such as response length, content hash, and
sanitized top-level key names.

This is a methodology-neutral transport/continuity repair. The same model still summarizes its own
evidence, unresolved judgments, and next plan; no BDR phase prompt, phase controller, or
Corfu-specific knowledge was added.

## Safe metrics

- 17 completed work responses and 17 completed offline tool calls
- 2 maintenance calls started; 1 completed continuation
- 19 model attempts, 0 retries, 0 abandoned attempts
- 19 durable checkpoints and 115 hash-chained events
- 416,724 prompt tokens and 9,370 completion tokens across accepted responses
- 246.097 seconds from the first attempt through the terminal response

The raw transcript, model reasoning, rejected response content, endpoint/configuration, container
inspection, and scratch files remain private. The rejected payload was not retained by this older
diagnostic path, so its specific missing or extra field cannot be reconstructed.

## Archived safe artifact

- [`qwen38-protocol-v2-maintenance-schema-diagnostic-metrics.json`](artifacts/qwen38-protocol-v2-maintenance-schema-diagnostic-metrics.json)
