# Install BAT privately

BAT (**BugAnnihilatorThreethousand**) is an agentic loop that implements BDR (**Boundary-Driven
Refactoring**). BAT packages one portable BDR skill and three thin host adapters:

| layer | file | responsibility |
|---|---|---|
| portable BDR workflow | `skills/refactor/SKILL.md` | method routing, autonomy contract, and terminal behavior |
| Claude Code adapter | `.claude-plugin/plugin.json` | BAT plugin identity and `/bat:refactor` namespace |
| ChatGPT adapter | `skills/refactor/agents/openai.yaml` | `@refactor` presentation and invocation metadata |
| Codex adapter | `.codex-plugin/plugin.json` | BAT plugin identity and `$refactor` skill discovery |

The host invocations documented below currently execute the portable skill and established BDR
engine. `loop/` is the provider-neutral Scala controller, reasoning-backend, and production-runner
module; these host adapters still invoke the portable skill/engine rather than that Scala runtime.

Keep these BAT files in one private, versioned source repository. Within one BAT deployment, do not
copy and independently edit the method into each target or application repository; that creates
multiple protocol authorities. Independent tools may implement and version BDR under their own
authority without adopting BAT's packaging or identity.

## Upgrading from a pre-0.5 `bdr` package

BAT 0.5.0 intentionally makes an alpha-breaking package namespace change. The plugin/package ID is
now `bat`, the local marketplace ID is `bat-team`, and the Claude Code namespaced command is
`/bat:refactor`. The old `bdr`, `bdr-team`, and `/bdr:refactor` package entrypoints are not aliases.

Remove the old package from the host's plugin manager, replace any copied marketplace descriptor
with the 0.5.0 descriptor, install `bat`, and restart the host before starting a new session. Do not
leave both packages installed: they expose the same portable `refactor` skill and may collide.

This migration does not rename the BDR methodology contract. The `bdr` engine command, `.bdr/`
state directory, `BDR_ACTOR` environment variable, BDR identifiers, and `bdr.dev/*` schemas remain
stable. Existing target-repository state must not be renamed or deleted during the reinstall.

## Updating clones after the repository rename

The canonical repository is now `PavelZaytsev/bat`. Existing clones keep their history and working
tree; point `origin` at the new SSH URL:

```bash
git remote set-url origin git@github.com:PavelZaytsev/bat.git
git remote -v
```

Use `https://github.com/PavelZaytsev/bat.git` instead when the clone is configured for HTTPS.
Update bookmarks, badges, marketplace source records, CI allowlists, webhooks, and automation to
the new URL rather than depending on the former `PavelZaytsev/bdr` address.

## Preconditions

Before starting a run, the developer should have:

- a clean checkout or isolated worktree at the PR head;
- Git, Python 3.10 or newer, and the repository's normal build and test toolchain;
- read access to the PR base and head commits;
- GitHub CLI authentication and issue-write access if GitHub synchronization is enabled;
- enough local disk and runtime budget for the repository's verification suite; and
- workspace trust and host permissions appropriate for editing code and running the approved test
  commands.

GitHub synchronization is optional. Without credentials or network access, BAT records the BDR
projection in an outbox and ends `verification_pending` rather than inventing remote issue IDs or
silently losing updates.

## Isolated Java worker runtime

The Scala controller contains the isolated Java PR worker described in
[`docs/adr/0002-isolated-java-worker.md`](docs/adr/0002-isolated-java-worker.md). It is currently an
integration module, not a public `bat` launcher and not yet wired into the Claude, ChatGPT, or Codex
skill entrypoints.

An application embedding the worker must provide:

- JDK 21 for the controller and an OCI runtime through an absolute executable path;
- a reviewed OCI image selected by immutable `name@sha256:...` identity, containing `/bin/sh`,
  `/bin/tar`, `/usr/bin/git`, a JDK, and the Maven/Gradle executables configured
  as absolute container paths;
- a non-root numeric UID/GID that owns the mounted run directories and is valid for both the image
  and the rootless or otherwise isolated runtime endpoint;
- three absolute, pairwise-disjoint local roots for private persistent control state, stable run
  workspaces, and disposable scratch data;
- an authenticated PR authority plus a clean, standalone, complete local Git source whose exact
  base and head refs are available; and
- a verified BDR lifecycle adapter that initializes/resumes `.bdr/progress.yaml` at those exact
  source pins.

Configure explicit limits for source bytes/paths, expanded checkout bytes/paths, tree-metadata
output, build tmpfs, process output, PIDs, memory, CPU, and wall time. Put the workspace root on a
quota-backed filesystem: application-level preflight rejects known oversize inputs, while the
filesystem quota is the hard stop for runtime metadata overhead and host or daemon failure. The
trusted host runner is the sole wall-clock timeout authority; target commands run directly inside
the container without an image-supplied timeout supervisor. BAT records a timeout only when that
host deadline guard wins, while natural target exits—including 124, 137, and 143—remain ordinary
exit outcomes. The runtime endpoint must not expose unrelated containers to the worker account. Run
a startup janitor that removes stale containers carrying BAT's operation label; this covers a
machine or controller loss that prevents normal post-run and resume cleanup.

Do not place the control root inside a target workspace or mount it into target containers. Preserve
it across controller restarts so operation receipts and BDR state can be resumed; protect it as
sensitive integrity state even though it contains no model credential by design.

Worker builds are deliberately offline and stage a read-only source tree into bounded ephemeral
storage with empty Maven/Gradle cache directories. The target must therefore carry everything needed
by the selected command in checked-in inputs, or the build will fail as an environment block. Do not
solve that failure by mounting a developer home, SSH agent, API key, Docker configuration, or ambient
package cache. A controlled dependency materialization mechanism, worker-image distribution,
scheduling, run cleanup, and trusted pushing are separate deployment concerns.

The provider-neutral production runner is currently an embedding API, not a packaged `bat` command.
An embedding application supplies one validated Responses or Harmony Chat backend configuration,
the `JavaWorkerSession.start`/`resume` inputs above, and a trusted evaluator implementation. BAT
closes the model and actor-worker scopes before acquiring that evaluator, and requires its report to
bind the exact final commit and patch digest. BAT does not yet ship the evaluator's OCI image or
oracle-mount policy. The maintained dependency-free Java canary uses direct `javac`/`java`, whereas
the production worker exposes only the reviewed Maven/Gradle actions; a production-worker canary or
project-specific offline build cartridge remains separate deployment work.

## Claude Code

For development, load the repository directly for one session:

```bash
claude --plugin-dir /absolute/path/to/bat
```

For a team, publish BAT through a private Claude Code marketplace and install it at **user** scope
so it is available in arbitrary repositories:

```bash
claude plugin marketplace add YOUR_ORG/YOUR_MARKETPLACE
claude plugin install bat@YOUR_MARKETPLACE --scope user
```

An organization can instead register the private marketplace and enable BAT through managed
settings. A repository-level plugin declaration is not a zero-install distribution mechanism:
each collaborator must still trust the repository and consent to installing external plugin code.

Start a new session, check out the PR head, and run:

```text
/bat:refactor this PR
```

You may also pass a PR number or URL. `/bat:refactor` is the stable namespaced form. Claude Code can
also expose bare `/refactor` when no other command has that name.

For the exact short command on clients that do not expose it, deploy
`adapters/claude-bare/refactor/` as a standalone personal or managed Claude skill named `refactor`
(for a personal installation, copy that directory to `~/.claude/skills/refactor/`). The shim
contains no method copy: it delegates to the installed `bat:refactor` skill and fails closed when
the plugin is absent. Check for a collision with an existing personal/enterprise `/refactor` before
deploying it.

### Claude permissions

Installing a plugin does not enable autonomous permissions. For long runs, use your organization's
approved permission mode and narrow allow rules for the BAT entrypoint, repository reads and edits,
the project's test commands, and the specific `git` or `gh` operations you intend to permit.

Claude Code auto mode can reduce routine prompts, but it must be enabled by the developer or an
administrator; a project or plugin cannot grant it to itself. Do not use bypass-permissions mode on
an ordinary workstation. If your organization permits that mode at all, reserve it for an isolated
container or VM with an intentionally limited filesystem and network boundary.

Official references: [plugins](https://code.claude.com/docs/en/plugins),
[private marketplaces](https://code.claude.com/docs/en/plugin-marketplaces),
[skills](https://code.claude.com/docs/en/slash-commands), and
[permission modes](https://code.claude.com/docs/en/permission-modes).

## ChatGPT

Publish and install the **complete BAT plugin folder** through a private marketplace. ChatGPT and
Codex share the `.codex-plugin/plugin.json` package. Do not upload only `SKILL.md` or only
`skills/refactor/`: the workflow also needs its references and the deterministic runner under
`scripts/` (plus `bin/` launchers where the host exposes them).

For a local smoke test, use the ready-made catalog descriptor under
`adapters/openai-marketplace/`. Make a separate marketplace root, place the complete BAT folder at
`plugins/bat/`, and copy that descriptor to `.agents/plugins/marketplace.json`. Restart the ChatGPT
desktop app, select **BAT Team** in the Plugins Directory, and install **BAT**. Keeping the catalog
outside the plugin prevents a recursive plugin copy.

In ChatGPT Work or the desktop app, enable the private/local marketplace source in the Plugins
Directory, install BAT, and start a new Work chat against the authorized local project. The exact
marketplace controls depend on workspace policy; an administrator may need to distribute or allow
the private source.

After the private skill is enabled for the workspace, invoke it from a coding conversation with:

```text
@refactor this PR
```

The ChatGPT workspace and connector policies remain authoritative. BAT cannot self-grant repository
write access, shell execution, GitHub access, or permission to perform external side effects.

Official references: [plugin packaging](https://developers.openai.com/plugins/build/plugins) and
[skill authoring](https://developers.openai.com/plugins/build/skills).

## Codex

The Codex adapter discovers the same portable BDR skill through `.codex-plugin/plugin.json`.
Publish the BAT plugin from a private organization marketplace, then install it using that
marketplace's real name:

```bash
codex plugin marketplace add /path/to/private-marketplace-root
codex plugin add bat@YOUR_MARKETPLACE
```

For the local smoke-test layout above, replace `/path/to/private-marketplace-root` with its
`team-bat-marketplace` directory and install `bat@bat-team`.

The marketplace-add step is for an explicitly configured private marketplace. A locally managed
personal marketplace at `~/.agents/plugins/marketplace.json` is discovered implicitly and does not
need that command. On Windows, the personal path is under the user's profile directory.

Start a new Codex task after installation or update so the current plugin version is loaded, then
invoke:

```text
$refactor this PR
```

Codex sandbox, approval, and organization policies still apply. Grant only the repository paths,
commands, and network destinations needed by the run.

## What cannot be made interruption-free

“Unattended” describes BDR's decision policy, not a promise that infrastructure will never require
attention. Any host may still stop or pause for:

- initial plugin installation or workspace trust;
- a safety classifier or explicit approval rule;
- expired authentication or insufficient repository permissions;
- model quota, rate-limit, or service failures;
- missing dependencies or unavailable build infrastructure;
- stale base/head commits, merge conflicts, or a dirty user worktree;
- nondeterministic, destructive, or unbounded tests; or
- a decision that changes intended behavior, a public contract, security/privacy posture, or risk
  beyond the original defect.

When those conditions occur, a conforming run records an explicit terminal state and a decision
packet. It must not claim `ready_for_review` merely because the agent ran out of time or context.

## Verify a rollout

Pilot on a non-critical PR before broad deployment. Confirm that:

1. the host exposes the expected BAT invocation and loads the same BDR workflow from
   `skills/refactor/SKILL.md`;
2. `.bdr/progress.yaml` and `.bdr/events.jsonl` are created in the target repository, not in plugin
   cache or host configuration directories;
3. a stopped session resumes from repository state without relying on chat prose;
4. GitHub issue creation is idempotent, or the outbox is complete when offline;
5. protected operations such as push, merge, deploy, and history rewrite remain denied; and
6. ordinary review and CI still decide whether the PR is mergeable.
