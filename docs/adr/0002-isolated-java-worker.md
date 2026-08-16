# ADR 0002: Run untrusted Java pull requests in a pinned isolated worker

> **Historical ADR.** This records the removed Scala/ZIO worker. It is retained for architectural
> evidence and is not an implementation claim for the current tree.

- Status: accepted
- Date: 2026-08-07
- Issue: [#4](https://github.com/PavelZaytsev/bat/issues/4)

## Context

BAT has to inspect, test, and refactor pull-request code. That code, its build files, tests, Git
metadata, and model-visible instructions are untrusted. Running them in the controller process or an
ordinary developer checkout would expose model credentials, SSH agents, home-directory state, the
inference endpoint, and unrelated host files. It would also make retries dangerous: after a timeout
or controller crash, BAT could unknowingly apply the same mutation twice.

The worker needs enough authority to perform BDR's focused Java loop, but no general shell and no
authority to publish a result.

## Decision

BAT provides a Scala 3/ZIO Java pull-request worker with three separate host roots:

- a private, persistent **control root** for pins, the operation ledger, receipt key, bounded output
  artifacts, and handoff artifacts;
- a private, stable-per-run **workspace root** containing the detached authoring worktree; and
- a **scratch root** for bounded, operation-scoped patch input.

The roots must be absolute and pairwise disjoint. The control root is never mounted into a target
container. A run keeps the same authoring worktree so local commits and BDR progress survive a
controller restart; scratch data is operation-scoped and removed after use. Java builds stage the
read-only authoring tree into a bounded container tmpfs, so build output never consumes an
unbounded host directory. Cleanup of a completed run is an orchestration concern outside the
worker.

### Pin the pull request before executing code

A controller-supplied `PullRequestAuthority` resolves authenticated PR metadata into one immutable
identity tuple:

```text
(base repository, head repository, PR ID, base ref, full base commit,
 head ref, full head commit)
```

The source verifier rejects abbreviated object IDs, a dirty, bare, or shallow source, replacement
objects, grafted history, refs that no longer resolve to the recorded objects, non-commit objects,
and a base that is not an ancestor of the head. It does not accept a contributor-provided clone URL
as authority.

Before any worktree-sensitive host Git command, the verifier rejects repository-local Git
configuration capable of invoking filters, helpers, drivers, includes, hooks, or host-reading
attribute/exclude files. Provisioning initializes an empty repository, fetches only the exact
authenticated base and head refs into private `refs/bat/*` names without tags, checkout hooks, LFS
smudging, or submodule recursion, then checks out the exact head commit in detached mode. The
source object store and expanded head tree are measured before persistent provisioning; path,
expanded-byte, and metadata bounds fail closed. The resulting worktree must be clean and cannot use submodules, alternate object
stores, hidden index flags, or an index tree different from `HEAD`. A new run rejects target-supplied
`.bdr` state.

The authority is queried again after provisioning, before each patch or commit, and before handoff.
Any change to the base repository, head repository, refs, or commits makes the run stale and stops
further mutation. A run is never silently rebased onto a moving PR.

### Expose a narrow tool algebra

The model receives structured tools for:

- bounded UTF-8 file reads and deterministic fixed-string search;
- validated text patch application;
- bounded Git status and diff;
- local Git commit; and
- Maven `test`/`verify` or Gradle `test`/`check`, with an optional class or `class#method` selector
  only on focused test actions.

There is no generic shell, arbitrary executable, environment inspection, network client, push,
merge, deploy, history rewrite, package publication, or user-supplied Git argument surface. Paths
cannot traverse the repository or cross symlinks. Patches are size-bounded and cannot modify
`.git`/`.bdr`, add symlinks or Git links, rename or copy paths, change modes, or carry binary diffs.
Git runs with fixed arguments, disabled hooks, signing, external diff/text conversion, global
configuration, prompts, LFS smudging, and replacement objects.

Builds receive the authoring repository only as a read-only `/bat/source` mount. A fixed wrapper in
the reviewed image copies source—excluding `.git` and `.bdr`—into an operation-local, size-capped
`/bat/run` tmpfs; Maven/Gradle output and empty cache directories live there and disappear with the
container. Maven and Gradle are forced offline and may only use the policy-configured absolute
executables. A project whose dependencies are not already available from its checked-in inputs will
fail offline. That is an environment block to report, not permission to enable ambient network or a
developer's package cache.

### Enforce one fixed OCI profile

Every mutation-capable Git operation and every Java build is explicit argv inside an OCI image
pinned by its full SHA-256 digest. Read-only source and handoff inspection uses a separate sanitized
host-side Git runner with fixed non-network commands. The OCI runtime executable is an absolute
configured path, the requested executable replaces any image entrypoint, and image pulling is
disabled at execution time. The profile always uses:

- no network, read-only container root, no capabilities, and `no-new-privileges`;
- a non-root UID/GID, no IPC namespace, and bounded PIDs, memory, CPU, wall time, and output;
- bounded `noexec,nosuid,nodev` tmpfs mounts for `/tmp` and an empty `/home/bat`, plus bounded
  writable `/bat/run` storage for staged builds;
- an exact launcher environment and explicit safe container overrides with no inherited host API
  keys, SSH agent, Docker configuration, or host home; and
- only explicit bind mounts for the operation's worktree and, when needed, validated patch input.

The runtime uses deterministic per-operation names that remain stable across worker-image changes,
disables runtime log persistence, and confirms resource absence before and after every invocation.
The trusted host runner is the sole wall-clock timeout authority: it records a timeout before
terminating the runtime process tree and removing the named container. Container exit codes are
preserved as target outcomes and are never reinterpreted as timeout signals.
Host-deadline, cancellation, output-limit, and resume paths forcibly remove any daemon-owned
container.
A pending mutating intent remains indeterminate. A pending non-mutating intent can be retried only
after cleanup, an unchanged workspace fingerprint, and a durable authenticated recovery record. Full
stdout/stderr digests and byte counts are retained even though only bounded previews are returned.
The inference service and controller remain outside the container's mounts and are unreachable
through its disabled network.

### Commit intent before effect and replay receipts

Every command-like tool call derives a stable operation ID from the run ID, provider call ID, and
tool name. It also carries the expected workspace revision and fingerprint. Before executing an
effect, the worker appends and synchronizes an authenticated intent record binding:

```text
(operation ID, kind, bounded canonical request identity and digest, before revision/fingerprint,
 policy ID, OCI image digest)
```

On completion it persists bounded output artifacts and an authenticated receipt binding the intent
to the after revision/fingerprint, typed outcome, full output digests and byte counts, preview
digests and sizes, and duration. A mutating operation advances the workspace revision only when its
fingerprint changes. A read-only operation that changes the authoring workspace is rejected.

Repeating a completed operation with identical input returns the stored receipt and preview without
executing the effect again. Reusing its ID for different input is rejected, as is any stale workspace
precondition. The control root is exclusively locked per run, and one session gate serializes model
tools, BDR calls, evidence snapshots, and complete handoff generation so a mutation cannot race an
artifact snapshot.

If the worker has durable mutating intent but no trusted completion—such as a crash after a patch
but before its completion record—it cannot prove which effect happened. The run becomes
`indeterminate_operation`; replay and all new operations fail closed. The same is true when an
interrupted read-only operation leaves any workspace drift. Only a non-mutating intent whose
container has been removed and whose workspace still matches its authenticated precondition may be
closed with an authenticated recovery record and retried. Ambiguous mutation recovery requires
explicit human or higher-level orchestration, never a guessed retry.

### Bridge trusted worker evidence into BDR

BDR remains the methodology and state authority described in
[ADR 0001](0001-bat-bdr-boundary.md). A worker lifecycle adapter initializes or resumes the existing
BDR session in the authoring worktree. On both paths it verifies that `.bdr/progress.yaml` records
the same absolute repository root and exact base/head commits as the private worker manifest.

A model cannot manufacture command evidence by quoting output. The evidence bridge resolves an
opaque receipt ID from the private ledger, requires the receipt's revision and fingerprint to match
the current healthy workspace, accepts only Java test/verification operations, and materializes the
authenticated policy, canonical full-suite/focused-selector identity, request digest, exit code,
and full output digests for BDR. Nonexistent, stale, non-verification, and non-exited receipts are
rejected.

### Hand off a local result; do not publish it

Handoff rechecks PR freshness and requires every code change to be committed locally; only BDR state
may remain outside a commit. The local head must descend from the original pinned head. The worker
writes a private, size-bounded binary-safe patch from the pinned head to the verified local head plus
a manifest binding the complete PR identity and refs, original and local commits, workspace
revision/fingerprint, fresh terminal BDR revision/state/digest, and patch digest/byte count. PR
freshness is checked again immediately before artifacts are written.

The worker has no push API. A separate trusted publisher may inspect the handoff, rerun organization
CI, and push through ordinary repository controls. Review and CI remain the merge authority.

## Trust boundary and non-goals

Trusted inputs are the controller, authenticated PR authority, configured Git and OCI runtime
binaries, selected digest-pinned image, host kernel/runtime isolation, the three configured roots,
and the verified BDR engine/lifecycle adapter. Target code and everything it can influence are
untrusted.

This design does not claim to defend against:

- a compromised host administrator, kernel, OCI runtime, trusted controller, or maliciously chosen
  worker image;
- denial of service outside the configured process, time, memory, CPU, output, source/tree, and
  workspace-scan bounds or outside the deployment's filesystem quota;
- target code that genuinely needs network services or an unmaterialized dependency cache;
- nondeterministic tests, incorrect project tests, or ambiguous intended behavior; or
- publication, merge, deployment, artifact signing, or production credential use.

Digest pinning identifies an image; it does not establish that the image is trustworthy. The
reviewed image must not bake in secrets or unsafe environment defaults. Ledger and receipt records
are HMAC-authenticated with a key stored in the private control directory, but the log is not
hash-chained or externally anchored. A local administrator who can alter or roll back the control
directory and its key is outside the threat model. Durable Git history or an external audit store
must provide rollback resistance if that becomes a requirement.

## Verification

Unit and integration specifications cover pin validation, path and patch policy, workspace
provisioning/resume, ledger replay and crash windows, Java command planning, BDR pin binding and
evidence materialization, handoff, and deterministic OCI argument construction.

The required `Live OCI containment` CI job additionally launches adversarial synthetic fixtures
with a synthetic canary. They prove that inherited environment values, an unmounted host file, host
home, SSH/Docker state, and writes to the container root are unavailable; local and public network
endpoints are unreachable; staged builds cannot write their source mount or copy `.git`/`.bdr`; and
host-runner deadline expiry or output-flood termination leaves no live daemon container. No real
secret is used as a canary.

## Consequences

- BAT can perform focused BDR refactoring on hostile Java PRs without giving target code model or
  publication authority.
- Exact pins and workspace preconditions turn PR drift and out-of-band edits into explicit stops.
- Durable replay avoids duplicate mutations when completion is known; ambiguous crash windows stop
  instead of guessing.
- Strict offline builds are safer and reproducible, but some projects will need a separately
  designed dependency-materialization step before they can run in this worker.
- Provider transports, worker-image distribution, scheduling, cleanup, and the trusted publisher
  remain separate changes.
