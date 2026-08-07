## Linked issue

Closes #

## Summary

<!-- What changed and why? Keep this focused on the linked issue. -->

## Change surface

- [ ] BAT runtime, controller, or product behavior
- [ ] BDR state engine, methodology, or evidence contract
- [ ] OpenAI adapter or hosted-model integration
- [ ] gpt-oss or local-cluster integration
- [ ] Benchmark or evaluation infrastructure
- [ ] Packaging, CI, or documentation only

Behavior or invariant changed:

Non-goals:

## Evidence

<!-- Link findings, benchmark runs, or sanitized reproduction evidence. -->

| Check | Command or evidence | Result |
|---|---|---|
| Focused verification | | |
| Engine self-test | `python scripts/bdr.py selftest` | |
| Broader or platform verification | | |

## Execution economics

<!-- Record expected or measured token, test-run, wall-time, cost, or hardware impact. Write "no material change" when appropriate. -->

## Risks and rollback

<!-- What could fail, how would we detect it, and how can this change be reverted safely? -->

## Checklist

- [ ] This pull request addresses one focused issue and links it above.
- [ ] BAT and BDR are named consistently: BAT is the product; BDR is the methodology.
- [ ] Acceptance criteria from the issue are satisfied or explicitly deferred.
- [ ] New behavior has focused verification; changed engine behavior has adversarial coverage.
- [ ] Documentation and examples match the implemented contract.
- [ ] I recorded material token or execution-economics changes.
- [ ] I removed credentials, proprietary target code, private prompts, and sensitive logs.
- [ ] I did not weaken tests or bypass repository policy to make the change pass.
