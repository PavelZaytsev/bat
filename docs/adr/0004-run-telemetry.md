# ADR 0004: Payload-free run telemetry

- Status: accepted
- Date: 2026-08-09
- Issue: [#21](https://github.com/PavelZaytsev/bat/issues/21)
- Parent: [#7](https://github.com/PavelZaytsev/bat/issues/7)

## Context

BAT must compare hosted inference, rented `gpt-oss`, and an exo cluster without turning one-off logs
into benchmark evidence. The existing safe trace proves controller ordering and redaction, but it is
not a performance record: it does not attribute work to BDR phases or distinguish one logical model
turn from the provider attempts and retries used to complete it.

Telemetry also crosses a sensitive boundary. Prompts, raw reasoning, provider bodies, tool
arguments and outputs, target process output, endpoint credentials, URLs, and hostnames must not
become durable metrics accidentally.

## Decision

BAT defines a separate provider-neutral telemetry contract under `bat.telemetry`.

- `Telemetry` is a functional sink whose emission is `UIO[Unit]`. A no-op interpreter preserves the
  existing controller API; an in-memory interpreter builds a validated document. Durable storage is
  an application concern and is not part of this slice.
- A controller model turn owns token accounting. Provider attempts own response-header latency,
  time to first semantic SSE event, stream duration, retry outcome, and attempt duration. Retries
  therefore cannot double-count the logical turn's usage.
- Every fresh tool execution records the validated BDR checkpoint immediately before the effect and
  either an observed checkpoint or `failed_before_measurement` afterward. A failed observation is
  never represented as unchanged state. Replayed call IDs are recorded as replays and are not
  counted as new tool executions.
- Phase attribution contains only controller iteration, BDR revision, run state, state digest, and
  bounded action, slice, and phase identifiers. The BDR `next_action` object itself is not copied.
- Durations use the injected monotonic ZIO clock and are stored as elapsed milliseconds. The record
  does not invent wall-clock timestamps.
- Optional numbers use a uniform measurement object containing either a value or `null` plus a
  machine-readable unavailability reason. Missing cached tokens, TTFT, topology, node-hours, or cost
  are never represented as zero.
- Deployment metadata is supplied explicitly by the operator. BAT does not infer model revision,
  runtime, Harmony template, quantization, topology, or node count from an endpoint response.
  Operator labels pass bounded identifier validation; endpoint, hostname, and credential-shaped
  values are rejected.
- Run-start pins do not copy arbitrary protocol strings. Provider identity, reasoning effort, and
  prompt version are stored as domain-separated SHA-256 digests and correlated with the separately
  validated deployment fingerprint. External error codes and tool names are collapsed into bounded
  telemetry-local types before emission.
- The canonical document is `bat.dev/run-telemetry`, version `1`. It validates event ordering,
  provider attempt/retry causality, model/tool iteration ownership, monotonic BDR revisions,
  outcome/error consistency, timing and token bounds, terminal state, deployment/run identity,
  fresh tool counts, iteration counts, and completed-run token reconciliation before encoding.
- Phase and run summaries are derived from the immutable event sequence. Pricing configuration,
  billing APIs, dashboards, and benchmark scoring remain outside this contract.

The schema contains no raw prompts, reasoning, call IDs, arguments, outputs, provider bodies, target
commands, endpoint URLs, credentials, or hostnames. Digested pins, safe machine error codes, and
trusted tool names are sufficient for correlation.

## Consequences

- The fake endpoint and scripted controller can exercise the complete telemetry contract in normal
  CI without network, credentials, Docker, GPUs, or paid inference.
- A future live runner can give the same sink to `AgenticLoop` and its provider adapter, then emit one
  comparable JSON record for hosted, rented, and exo runs.
- Provider cartridges remain responsible for the timing they can actually observe. Unsupported
  measurements remain explicitly unavailable.
- Safe trace and telemetry remain distinct evidence products: the trace explains controller
  protocol ordering, while telemetry measures resource and time economics.
- Node-hours can be derived only when the operator supplies node count. Dollar cost remains
  unavailable until a separately pinned pricing policy is implemented.
