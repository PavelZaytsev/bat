#!/usr/bin/env python3
"""Exercise local target preparation and location validation for /bat:fix."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "skills" / "fix" / "scripts" / "target.py"


def run(
    *arguments: str,
    check: bool = True,
    environment: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(HELPER), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
        env=environment,
    )


def git(project: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(project), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return result.stdout.strip()


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="bat-fix-target-") as directory:
        project = Path(directory) / "project with spaces"
        project.mkdir()
        git(project, "init", "-b", "main")
        git(project, "config", "user.name", "BAT Test")
        git(project, "config", "user.email", "bat-test@example.invalid")
        (project / "tracked.txt").write_text("base\n", encoding="utf-8")
        git(project, "add", "tracked.txt")
        git(project, "commit", "-m", "base")

        created = json.loads(
            run("prepare", str(project), "main", "bdr/issue-123").stdout
        )
        if created["action"] != "created_branch":
            raise RuntimeError(f"expected a created branch, got {created!r}")
        if git(project, "branch", "--show-current") != "bdr/issue-123":
            raise RuntimeError("prepare did not switch to the BDR branch")

        literal = "$(touch should-not-exist)"
        executed = run(
            "exec",
            str(project),
            "--",
            sys.executable,
            "-c",
            "import sys; print(sys.argv[1])",
            literal,
        )
        if executed.stdout.strip() != literal or (project / "should-not-exist").exists():
            raise RuntimeError("exec did not preserve a literal argument")

        git(project, "switch", "main")
        resumed = json.loads(
            run("prepare", str(project), "main", "bdr/issue-123").stdout
        )
        if resumed["action"] != "resumed_local_branch":
            raise RuntimeError(f"expected a resumed branch, got {resumed!r}")

        (project / "untracked.txt").write_text("dirty\n", encoding="utf-8")
        dirty = run(
            "prepare", str(project), "main", "bdr/another", check=False
        )
        if dirty.returncode == 0 or "not clean" not in dirty.stderr:
            raise RuntimeError("prepare accepted a dirty worktree")

        described = json.loads(
            run("describe", "developer@192.0.2.10:/home/developer/project").stdout
        )
        if described != {
            "host": "developer@192.0.2.10",
            "kind": "ssh",
            "path": "/home/developer/project",
        }:
            raise RuntimeError(f"unexpected SSH location parse: {described!r}")
        relative = run("describe", "relative/project", check=False)
        if relative.returncode == 0:
            raise RuntimeError("relative project location was accepted")

        (project / "untracked.txt").unlink()
        git(project, "switch", "main")
        fake_bin = Path(directory) / "fake-bin"
        fake_bin.mkdir()
        fake_ssh = fake_bin / "ssh"
        fake_ssh.write_text(
            "#!/bin/sh\n"
            "test \"$1\" = -- || exit 90\n"
            "shift\n"
            "test \"$1\" = developer@host || exit 91\n"
            "shift\n"
            "exec /bin/sh -c \"$1\"\n",
            encoding="utf-8",
        )
        fake_ssh.chmod(0o755)
        environment = dict(os.environ)
        environment["PATH"] = f"{fake_bin}{os.pathsep}{environment.get('PATH', '')}"
        remote_location = f"developer@host:{project.resolve()}"
        remote_result = run(
            "prepare",
            remote_location,
            "main",
            "bdr/remote",
            check=False,
            environment=environment,
        )
        if remote_result.returncode != 0:
            raise RuntimeError(f"remote prepare failed: {remote_result.stderr.strip()}")
        remote = json.loads(remote_result.stdout)
        if remote["kind"] != "ssh" or remote["action"] != "created_branch":
            raise RuntimeError(f"unexpected remote preparation: {remote!r}")
        remote_exec = run(
            "exec",
            remote_location,
            "--",
            sys.executable,
            "-c",
            "import sys; print(sys.argv[1])",
            literal,
            environment=environment,
        )
        if remote_exec.stdout.strip() != literal:
            raise RuntimeError("remote exec did not preserve a literal argument")

    print("PASS fix target: local branch setup and target parsing")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
