# Contributing to BAT

BAT is the agentic loop; BDR is the methodology. Use those names consistently in issues, pull
requests, documentation, and experiments. The canonical BAT plugin identifier is `bat`, including
Claude's `/bat:refactor` entrypoint. The `bdr` CLI and engine, `.bdr/` state paths, `BDR_ACTOR`,
`bdr.dev/*` schemas, and BDR evidence identifiers remain part of the methodology's compatibility
contract.

## Work through an issue and a branch

Create or choose a GitHub issue before starting non-trivial work. The issue should describe the
problem and observable acceptance criteria rather than prescribe a large implementation. Use the
appropriate bug, feature, or experiment form so decisions and results remain searchable.

Use one focused branch and one pull request per issue. Suggested branch names are:

- `codex/<issue>-<short-name>` for Codex-created work;
- `feat/<issue>-<short-name>` for a BAT or BDR capability;
- `fix/<issue>-<short-name>` for a defect; and
- `docs/<issue>-<short-name>` for documentation-only work.

Do not develop directly on `main`. If a discovery materially expands the issue, open a follow-up
issue instead of silently growing the pull request.

## Keep the change reviewable

A pull request should:

1. link its issue;
2. state whether it changes BAT, BDR, an adapter, or benchmark infrastructure;
3. explain the behavior or invariant that changes;
4. identify deliberate non-goals;
5. include focused verification proportional to the risk; and
6. record any expected change in token, test-run, wall-time, or hardware cost.

Do not commit credentials, proprietary target code, private prompts, model-provider tokens, or raw
logs that may contain them. Scrub machine names, user paths, and unrelated repository content from
reproduction artifacts. A private repository is not a reason to weaken this rule.

## Run the local gate

The BDR state engine and direct runtime are dependency-free and support Python 3.10 and newer.
Before opening or updating a pull request, run:

```bash
python3 -m py_compile \
  scripts/bdr.py \
  skills/refactor/scripts/bdr.py \
  scripts/check_plugin_manifests.py \
  scripts/check_benchmark_artifacts.py
python3 scripts/bdr.py examples | python3 -m json.tool >/dev/null
python3 scripts/bdr.py selftest
python3 scripts/check_plugin_manifests.py
python3 scripts/check_benchmark_artifacts.py
bin/bdr --version
bin/bat-direct rehearse
bin/bat-direct example-config | python3 -m json.tool >/dev/null
```

On Windows, use `bin\bdr.cmd --version` for the launcher check. GitHub Actions repeats the engine
suite on the minimum and current supported Python versions on Linux and on current Python on
Windows. CI also runs the direct runtime's protocol, evaluator, retry, integrity, and forced-
compaction tests on Linux. CI is read-only and does not call OpenAI, GPT-OSS, Gemma, Anthropic, or
any other model.

## Record experiments as experiments

Model and cluster trials are evidence, not anecdotes. Pin the BAT commit, case, prompt/protocol,
exact model and quantization, runtime, hardware topology, limits, and oracle isolation. Record
unavailable usage as `null` with a reason; never report an unavailable measurement as zero.

At minimum, report terminal state, known and novel defects, self-induced regressions, test
invocations, wall time, token counts when exposed, and throughput when measured. Preserve failures
and environment blocks as results rather than deleting them.

Committed benchmark runs are immutable records. Do not normalize whitespace, replace a failed run,
or edit an artifact and its adjacent digest together; add a new run. The artifact checker proves
that the recorded graph is internally bound, while Git review and repository rules provide the
historical root of trust.

## Merge discipline

Open a pull request and wait for the stable `CI / Required` check. Resolve review conversations and
keep the branch current enough for GitHub to evaluate the actual merge result. Do not bypass the
gate, force-push shared history, or merge a red build. Repository rules, not BAT's own claims, are
the final merge authority.
