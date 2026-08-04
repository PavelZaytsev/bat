# Install BDR privately

BDR has one portable skill and three thin adapters:

| layer | file | responsibility |
|---|---|---|
| portable workflow | `skills/refactor/SKILL.md` | method routing, autonomy contract, and terminal behavior |
| Claude Code adapter | `.claude-plugin/plugin.json` | plugin identity and `/bdr:refactor` namespace |
| ChatGPT adapter | `skills/refactor/agents/openai.yaml` | `@refactor` presentation and invocation metadata |
| Codex adapter | `.codex-plugin/plugin.json` | plugin identity and `$refactor` skill discovery |

Keep these files in one private, versioned source repository. Do not copy and independently edit the
method into each product or application repository; that creates multiple protocol authorities.

## Preconditions

Before starting a run, the developer should have:

- a clean checkout or isolated worktree at the PR head;
- Git, Python 3.10 or newer, and the repository's normal build and test toolchain;
- read access to the PR base and head commits;
- GitHub CLI authentication and issue-write access if GitHub synchronization is enabled;
- enough local disk and runtime budget for the repository's verification suite; and
- workspace trust and host permissions appropriate for editing code and running the approved test
  commands.

GitHub synchronization is optional. Without credentials or network access, BDR records an outbox
and ends `verification_pending` rather than inventing remote issue IDs or silently losing updates.

## Claude Code

For development, load the repository directly for one session:

```bash
claude --plugin-dir /absolute/path/to/bdr
```

For a team, publish BDR through a private Claude Code marketplace and install it at **user** scope
so it is available in arbitrary repositories:

```bash
claude plugin marketplace add YOUR_ORG/YOUR_MARKETPLACE
claude plugin install bdr@YOUR_MARKETPLACE --scope user
```

An organization can instead register the private marketplace and enable BDR through managed
settings. A repository-level plugin declaration is not a zero-install distribution mechanism:
each collaborator must still trust the repository and consent to installing external plugin code.

Start a new session, check out the PR head, and run:

```text
/bdr:refactor this PR
```

You may also pass a PR number or URL. `/bdr:refactor` is the stable namespaced form. Claude Code
2.1.216 and newer also exposes bare `/refactor` when no other command has that name.

For the exact short command on older clients, deploy `adapters/claude-bare/refactor/` as a
standalone personal or managed Claude skill named `refactor` (for a personal installation, copy
that directory to `~/.claude/skills/refactor/`). The shim contains no method copy: it delegates to
the installed `bdr:refactor` skill and fails closed when the plugin is absent. Check for a collision
with an existing personal/enterprise `/refactor` before deploying it.

### Claude permissions

Installing a plugin does not enable autonomous permissions. For long runs, use your organization's
approved permission mode and narrow allow rules for the BDR entrypoint, repository reads and edits,
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

Publish and install the **complete BDR plugin folder** through a private marketplace. ChatGPT and
Codex share the `.codex-plugin/plugin.json` package. Do not upload only `SKILL.md` or only
`skills/refactor/`: the workflow also needs its references and the deterministic runner under
`scripts/` (plus `bin/` launchers where the host exposes them).

For a local smoke test, use the ready-made catalog descriptor under
`adapters/openai-marketplace/`. Make a separate marketplace root, place the complete BDR folder at
`plugins/bdr/`, and copy that descriptor to `.agents/plugins/marketplace.json`. Restart the ChatGPT
desktop app, select **BDR Team** in the Plugins Directory, and install **BDR**. Keeping the catalog
outside the plugin prevents a recursive plugin copy.

In ChatGPT Work or the desktop app, enable the private/local marketplace source in the Plugins
Directory, install BDR, and start a new Work chat against the authorized local project. The exact
marketplace controls depend on workspace policy; an administrator may need to distribute or allow
the private source.

After the private skill is enabled for the workspace, invoke it from a coding conversation with:

```text
@refactor this PR
```

The ChatGPT workspace and connector policies remain authoritative. BDR cannot self-grant repository
write access, shell execution, GitHub access, or permission to perform external side effects.

Official references: [plugin packaging](https://developers.openai.com/plugins/build/plugins) and
[skill authoring](https://developers.openai.com/plugins/build/skills).

## Codex

The Codex adapter discovers the same portable skill through `.codex-plugin/plugin.json`. Publish
the plugin from a private organization marketplace, then install it using that marketplace's real
name:

```bash
codex plugin marketplace add /path/to/private-marketplace-root
codex plugin add bdr@YOUR_MARKETPLACE
```

For the local smoke-test layout above, replace `/path/to/private-marketplace-root` with its
`team-bdr-marketplace` directory and install `bdr@bdr-team`.

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
- a decision that changes product semantics, a public contract, security/privacy posture, or risk
  beyond the original defect.

When those conditions occur, a conforming run records an explicit terminal state and a decision
packet. It must not claim `ready_for_review` merely because the agent ran out of time or context.

## Verify a rollout

Pilot on a non-critical PR before broad deployment. Confirm that:

1. the host exposes the expected invocation and loads the same `skills/refactor/SKILL.md`;
2. `.bdr/progress.yaml` and `.bdr/events.jsonl` are created in the target repository, not in plugin
   cache or host configuration directories;
3. a stopped session resumes from repository state without relying on chat prose;
4. GitHub issue creation is idempotent, or the outbox is complete when offline;
5. protected operations such as push, merge, deploy, and history rewrite remain denied; and
6. ordinary review and CI still decide whether the PR is mergeable.
