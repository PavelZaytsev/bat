---
name: refactor
description: Compatibility entrypoint for the installed BAT plugin. Use only when the user explicitly invokes /refactor to run Boundary-Driven Refactoring on a pull request.
disable-model-invocation: true
---

Invoke the installed Claude Code plugin skill `bat:refactor` once, passing through `$ARGUMENTS`
unchanged. Do not imitate or summarize that skill from memory. If `bat:refactor` is unavailable,
stop and report that the BAT plugin is not installed; do not fall back to a repository-supplied
lookalike.
