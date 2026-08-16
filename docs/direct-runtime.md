# Direct BAT runtime

The supported autonomous runtime is the phase-opaque BDRv1 runner exposed as `bin/bat-direct`.
It gives the model a familiar repository, shell, tests, Git, and the frozen BDR methodology while
the host strictly controls identity, isolation, durable intent, replay, compaction, retry budgets,
and terminal acceptance.

The earlier Scala/ZIO controller was removed after live experiments showed that its semantic phase
API added a second, model-unfamiliar representation of the work without improving convergence. Its
types were useful plumbing, but the abstraction boundary was in the wrong place. The design record
in [`convergent-agentic-loops.md`](convergent-agentic-loops.md) explains the resulting rule:
constrain effects and authority; do not encode the model's thinking in the controller.

## Local smoke test

The runtime is dependency-free on Python 3.10 or newer:

```bash
bin/bat-direct rehearse
bin/bat-direct example-config > /tmp/bat-direct-config.json
```

The rehearsal crosses a deterministic forced-compaction boundary without contacting a model. The
generated configuration is a template, not a safe live configuration: replace every placeholder,
pin the served model identity and context limit, and bind a disposable container before `run`.

## exo or work-laptop inference

Expose the approved model server through an OpenAI-compatible Chat Completions endpoint reachable
from the trusted host. Prefer a loopback endpoint or an SSH tunnel; do not expose the inference port
publicly. Set the generated configuration's `model.endpoint` to that URL and keep credentials in the
named environment variable rather than the JSON file.

Before inference:

1. pin the task, methodology, repository commit/tree, model revision, precision, context window,
   server identity, container image, mounts, and run policy;
2. run `container-identity --config CONFIG` and store its hash with the preregistration;
3. verify the candidate repository and tracker are clean and the state directory is fresh;
4. run the deterministic rehearsal with the exact runner revision; and
5. set explicit call, retry, token, wall-time, and cost circuit breakers.

Then start or resume the same logical run with:

```bash
bin/bat-direct run --config CONFIG
```

For a deliberate cold-resume gate, first run with `--pause-after-compactions 1`, audit the closed
checkpoint, terminate that process, and rerun without the pause flag. Never relabel a failed or
cost-censored attempt as a continuation.

## Current model evidence

- Qwen3.8-27B is the external reference success for protocol-v2 continuity and repository repair.
  It is research evidence, not a Broadcom deployment candidate.
- GPT-OSS-120B exercised transport and recovery but did not converge reliably on the bounded
  repository task. Preserve it as a negative model-quality result.
- Gemma 4 31B remains unscored: the rented-GPU qualification never reached inference because of
  serving-runtime failures. It must be evaluated from a fresh preregistered run on exo or the work
  laptop before any capability claim.

Rented GPU capacity is not part of the active path. Starting a paid cloud pod requires a new,
explicit authorization and budget; ordinary development and qualification use exo or local
inference only.

## What the host must never do

Do not repair malformed model JSON, salvage a forbidden tool call, replay a partial stream, retry
after a tool might have executed, or compact while an action is in flight. A retry is admissible
only from an unchanged closed checkpoint before any tool intent, within the shared frozen budget.
The private rejected response and public safe digest must remain bound to that checkpoint.
