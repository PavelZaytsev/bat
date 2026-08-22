# Project and branch target

The fix invocation supplies four positional arguments:

```text
/bat:fix <project-location> <base-branch> <bdr-branch> <issue-number-or-url>
```

Treat every value as data, never as a shell fragment. Quote an argument at invocation time when it
contains spaces. The accepted project locations are:

- `.` for the session's current directory;
- an absolute local path such as `/Users/developer/project`; or
- an SSH checkout written as `user@host:/absolute/path`.

Other relative paths, SSH options, non-absolute remote paths, and embedded shell syntax are not
part of the contract. The base and BDR branch names must pass `git check-ref-format --branch` and
must differ.

## Prepare once

Resolve the bundled target helper from the directory that contains the invoked skill, but launch it
with the Claude session's original working directory unchanged so `.` keeps its documented meaning:

```text
python3 <skill-directory>/scripts/target.py prepare \
  <project-location> <base-branch> <bdr-branch>
```

Use an argv-preserving tool call rather than assembling an evaluable command string. The helper:

1. verifies that the location is the Git worktree root and the worktree is clean;
2. resumes the named local BDR branch when it exists;
3. otherwise tracks `origin/<bdr-branch>` when that exists; or
4. creates the BDR branch from the local base branch, falling back to `origin/<base-branch>`.

It never fetches, pulls, resets, deletes, or overwrites a branch. If neither base ref exists, fetch
only after the developer authorizes or requests it, then rerun preparation. Treat its JSON output
as the authoritative execution target for the rest of the run.

On resume, verify that the existing `.bdr` tracker pins the supplied issue. Stop on an issue
mismatch; never replace live state or silently reuse the branch for another objective.

## Execute on one target

For a local location, set every repository command's working directory to the prepared path. Do
not rely on the Claude session's original working directory after preparation.

For an SSH location, run every repository read, edit, Git command, BDR operation, build, and test
on the prepared host and path. Use the helper for ordinary commands:

```text
python3 <skill-directory>/scripts/target.py exec <project-location> -- <command> <arg> ...
```

The helper transports an argument vector to Python on the remote host and changes directory before
execution. It does not invoke a remote shell with user-supplied values. For file changes, create a
patch outside the local repository and pipe it to remote `git apply -`, or use another explicit,
lossless transfer. Inspect the remote diff after every edit. Never copy the remote project onto the
controller as an implicit working tree.

Use the BDR engine installed on the execution target. For SSH targets, prefer `bdr` on the remote
`PATH`; otherwise locate the complete remote BAT installation. Do not run the controller's engine
against copied remote state, and do not launch a second Claude process. The current Claude session
remains the agent while commands and files stay on the selected machine.

Keep temporary operation payloads and captured output outside the selected repository on the same
machine where the consuming command runs.
