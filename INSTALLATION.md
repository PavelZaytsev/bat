# Install BAT privately

BAT (**BugAnnihilatorThreethousand**) is an agentic loop that implements BDR (**Boundary-Driven
Refactoring**). BAT packages two portable BDR workflows and thin host adapters:

| layer | file | responsibility |
|---|---|---|
| bounded issue workflow | `skills/fix/SKILL.md` | one pinned GitHub issue, root-reachable slices, and closure evidence |
| broad BDR workflow | `skills/refactor/SKILL.md` | method routing, autonomy contract, and terminal behavior |
| Claude Code adapter | `.claude-plugin/plugin.json` | BAT plugin identity and `/bat:fix` plus `/bat:refactor` namespaces |
| ChatGPT adapter | `skills/refactor/agents/openai.yaml` | existing `@refactor` presentation metadata |
| Codex adapter | `.codex-plugin/plugin.json` | BAT plugin identity and skill discovery |

The host invocations documented below execute the portable skill and established BDR engine. The
optional autonomous execution path is the dependency-free `bin/bat-direct` runtime documented in
[`docs/direct-runtime.md`](docs/direct-runtime.md); it is not automatically invoked by the host
adapters.

Keep these BAT files in one private, versioned source repository. Within one BAT deployment, do not
copy and independently edit the method into each target or application repository; that creates
multiple protocol authorities. Independent tools may implement and version BDR under their own
authority without adopting BAT's packaging or identity.

## Upgrading from a pre-0.5 `bdr` package

BAT 0.5.0 intentionally makes an alpha-breaking package namespace change. The plugin/package ID is
now `bat`, the local marketplace ID is `bat-team`, and the Claude Code namespaced command is
`/bat:refactor`. The old `bdr`, `bdr-team`, and `/bdr:refactor` package entrypoints are not aliases.

Remove the old package from the host's plugin manager, replace any copied marketplace descriptor
with the current descriptor, install `bat`, and restart the host before starting a new session. Do not
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

## Direct autonomous runtime

`bin/bat-direct` is the supported model-driven runtime. It targets an explicitly configured
OpenAI-compatible endpoint on exo or the work laptop and executes admitted repository actions in a
pre-created, identity-bound disposable container. It is deliberately not a one-command deployment:
the operator must pin the task, methodology, repository, model and server identity, container image
and mounts, limits, and hidden acceptance boundary before inference.

Run the network-free smoke test with:

```bash
bin/bat-direct rehearse
```

Then follow [`docs/direct-runtime.md`](docs/direct-runtime.md) for configuration, container identity,
preregistration, cold resume, and fail-closed recovery. Do not place model credentials in the JSON
configuration or expose the model endpoint publicly. Rented GPU capacity is outside the active
workflow and requires a separate explicit authorization.

## Claude Code

For a personal installation—including Zed's Claude Agent—run the one-command installer from the
BAT checkout:

```bash
bin/bat-claude-install
```

The command installs or refreshes BAT at Claude user scope. Start a new Claude Code session or Zed
Claude Agent thread after it completes. No Zed setting or manual marketplace setup is required.

For development of BAT itself, load the working tree directly for one session:

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

Start a new session and run:

```text
/bat:fix . main bdr/issue-123 123
/bat:fix /absolute/path/to/project cache-experiments bdr/cache-fix 123
/bat:fix developer@192.0.2.10:/home/developer/project main bdr/issue-123 123
/bat:refactor this PR
```

Pass four positional arguments to `/bat:fix`: the project location, base branch, new or resumable
BDR branch, and same-repository GitHub issue number or URL. A project location may be `.`, an
absolute local path, or `user@host:/absolute/path`. BAT refuses a dirty target, creates the BDR
branch from the local base (or `origin/<base>`), and keeps SSH-targeted reads, edits, tests, Git, and
BDR operations on the remote machine. It does not fetch, pull, reset, delete, or overwrite a branch
as part of setup. The fix workflow performs no GitHub writes.

You may pass a PR number or URL to `/bat:refactor`. These are the stable namespaced forms. Claude
Code can also expose bare names when there is no collision.

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
   `skills/fix/SKILL.md` or `skills/refactor/SKILL.md`, as invoked;
2. `.bdr/progress.yaml` and `.bdr/events.jsonl` are created in the target repository, not in plugin
   cache or host configuration directories;
3. a stopped session resumes from repository state without relying on chat prose;
4. GitHub issue creation is idempotent, or the outbox is complete when offline;
5. protected operations such as push, merge, deploy, and history rewrite remain denied; and
6. ordinary review and CI still decide whether the PR is mergeable.
