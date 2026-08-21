#!/usr/bin/env python3
"""Exercise the one-command Claude installer without changing real user state."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

state_path = Path(os.environ["BAT_FAKE_CLAUDE_STATE"])
log_path = Path(os.environ["BAT_FAKE_CLAUDE_LOG"])
state = json.loads(state_path.read_text())
args = sys.argv[1:]
with log_path.open("a") as log:
    log.write(json.dumps(args) + "\n")

if args == ["plugin", "marketplace", "list", "--json"]:
    print(json.dumps(state["marketplaces"]))
elif args == ["plugin", "list", "--json"]:
    print(json.dumps(state["plugins"]))
elif args[:3] == ["plugin", "marketplace", "add"]:
    state["marketplaces"] = [{"name": "bat-local", "path": args[3]}]
elif args == ["plugin", "marketplace", "update", "bat-local"]:
    pass
elif args == ["plugin", "marketplace", "remove", "bat-local"]:
    state["marketplaces"] = []
    state["plugins"] = []
elif args == ["plugin", "install", "bat@bat-local", "--scope", "user"]:
    state["plugins"] = [{"id": "bat@bat-local"}]
elif args == ["plugin", "update", "bat@bat-local", "--scope", "user"]:
    pass
else:
    raise SystemExit(f"unexpected arguments: {args!r}")
state_path.write_text(json.dumps(state))
'''


def run_installer(fake_claude: Path, environment: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(ROOT / "scripts/install_claude.py"), "--claude", str(fake_claude)],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="bat-claude-installer-") as directory:
        temporary = Path(directory)
        fake_claude = temporary / "claude"
        state_path = temporary / "state.json"
        log_path = temporary / "commands.jsonl"
        fake_claude.write_text(FAKE_CLAUDE)
        fake_claude.chmod(0o755)
        state_path.write_text(json.dumps({"marketplaces": [], "plugins": []}))

        environment = dict(os.environ)
        environment["BAT_FAKE_CLAUDE_STATE"] = str(state_path)
        environment["BAT_FAKE_CLAUDE_LOG"] = str(log_path)

        first = run_installer(fake_claude, environment)
        if first.returncode != 0:
            print(first.stderr, file=sys.stderr)
            return 1
        second = run_installer(fake_claude, environment)
        if second.returncode != 0:
            print(second.stderr, file=sys.stderr)
            return 1

        state_path.write_text(
            json.dumps(
                {
                    "marketplaces": [{"name": "bat-local", "path": "/moved/bat"}],
                    "plugins": [{"id": "bat@bat-local"}],
                }
            )
        )
        moved = run_installer(fake_claude, environment)
        if moved.returncode != 0:
            print(moved.stderr, file=sys.stderr)
            return 1

        empty_path = temporary / "empty-path"
        empty_path.mkdir()
        missing = subprocess.run(
            [sys.executable, str(ROOT / "scripts/install_claude.py")],
            cwd=ROOT,
            env={"PATH": str(empty_path)},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if missing.returncode != 127 or "Claude Code is required" not in missing.stderr:
            print("FAIL Claude installer: missing-Claude error is not actionable", file=sys.stderr)
            return 1

        commands = [json.loads(line) for line in log_path.read_text().splitlines()]
        expected_fragments = [
            ["plugin", "marketplace", "add"],
            ["plugin", "install", "bat@bat-local", "--scope", "user"],
            ["plugin", "marketplace", "update", "bat-local"],
            ["plugin", "update", "bat@bat-local", "--scope", "user"],
            ["plugin", "marketplace", "remove", "bat-local"],
        ]
        for fragment in expected_fragments:
            if not any(command[: len(fragment)] == fragment for command in commands):
                print(f"FAIL Claude installer: missing command {fragment!r}", file=sys.stderr)
                return 1

    print("PASS Claude installer: first install and refresh are idempotent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
