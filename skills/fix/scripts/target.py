#!/usr/bin/env python3
"""Run fix-workflow commands in a validated local or SSH Git checkout."""

from __future__ import annotations

import argparse
import base64
import json
import re
import shlex
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


REMOTE_LOCATION = re.compile(
    r"(?P<host>(?:[A-Za-z0-9._-]+@)?[A-Za-z0-9._-]+):(?P<path>/[^\x00\r\n]*)\Z"
)
REMOTE_PYTHON = (
    "import base64,json,os,sys;"
    "p=json.loads(base64.b64decode(sys.argv[1]));"
    "os.chdir(p['cwd']);"
    "os.execvp(p['argv'][0],p['argv'])"
)


class TargetError(RuntimeError):
    pass


@dataclass(frozen=True)
class Target:
    kind: str
    path: str
    host: str | None = None

    @classmethod
    def parse(cls, location: str) -> "Target":
        if not location or "\x00" in location or "\n" in location or "\r" in location:
            raise TargetError("project location must be one non-empty argument")
        remote = REMOTE_LOCATION.fullmatch(location)
        if remote:
            host = remote.group("host")
            if host.startswith("-"):
                raise TargetError("SSH destination may not begin with '-'")
            return cls("ssh", remote.group("path"), host)
        if location == ".":
            return cls("local", str(Path.cwd().resolve()))
        path = Path(location)
        if path.is_absolute():
            return cls("local", str(path.resolve()))
        raise TargetError(
            "project location must be '.', an absolute local path, or user@host:/absolute/path"
        )

    def run(
        self,
        argv: list[str],
        *,
        capture: bool = False,
        check: bool = True,
        input_bytes: bytes | None = None,
    ) -> subprocess.CompletedProcess[bytes]:
        if not argv:
            raise TargetError("target command may not be empty")
        if self.kind == "local":
            command = argv
            cwd = self.path
        else:
            payload = base64.b64encode(
                json.dumps({"cwd": self.path, "argv": argv}).encode("utf-8")
            ).decode("ascii")
            remote_command = shlex.join(["python3", "-c", REMOTE_PYTHON, payload])
            command = ["ssh", "--", self.host or "", remote_command]
            cwd = None
        try:
            return subprocess.run(
                command,
                cwd=cwd,
                input=input_bytes,
                stdout=subprocess.PIPE if capture else None,
                stderr=subprocess.PIPE if capture else None,
                check=check,
            )
        except FileNotFoundError as exc:
            raise TargetError(f"required command not found: {command[0]}") from exc
        except subprocess.CalledProcessError as exc:
            if capture and exc.stderr:
                message = exc.stderr.decode("utf-8", errors="replace").strip()
                if message:
                    raise TargetError(message) from exc
            raise TargetError(f"target command failed with exit code {exc.returncode}") from exc


def output(target: Target, *argv: str) -> str:
    result = target.run(list(argv), capture=True)
    return result.stdout.decode("utf-8", errors="strict").strip()


def ref_exists(target: Target, ref: str) -> bool:
    result = target.run(
        ["git", "show-ref", "--verify", "--quiet", ref], capture=True, check=False
    )
    return result.returncode == 0


def validate_branch(target: Target, branch: str, label: str) -> None:
    if not branch or branch.startswith("-"):
        raise TargetError(f"{label} is not a valid branch name")
    result = target.run(
        ["git", "check-ref-format", "--branch", branch], capture=True, check=False
    )
    if result.returncode != 0:
        raise TargetError(f"{label} is not a valid branch name: {branch!r}")


def prepare(target: Target, base_branch: str, bdr_branch: str) -> dict[str, str]:
    validate_branch(target, base_branch, "base branch")
    validate_branch(target, bdr_branch, "BDR branch")
    if base_branch == bdr_branch:
        raise TargetError("base branch and BDR branch must be different")

    root = output(target, "git", "rev-parse", "--show-toplevel")
    requested = target.path.rstrip("/") or "/"
    canonical_root = root.rstrip("/") or "/"
    if canonical_root != requested:
        raise TargetError(
            f"project location must name the Git worktree root ({root}), not {target.path}"
        )
    dirty = output(target, "git", "status", "--porcelain=v1", "--untracked-files=all")
    if dirty:
        raise TargetError("target worktree is not clean; preserve or remove its changes before starting")

    local_bdr = f"refs/heads/{bdr_branch}"
    remote_bdr = f"refs/remotes/origin/{bdr_branch}"
    local_base = f"refs/heads/{base_branch}"
    remote_base = f"refs/remotes/origin/{base_branch}"

    if ref_exists(target, local_bdr):
        target.run(["git", "switch", bdr_branch])
        action = "resumed_local_branch"
    elif ref_exists(target, remote_bdr):
        target.run(["git", "switch", "--track", "-c", bdr_branch, f"origin/{bdr_branch}"])
        action = "resumed_remote_branch"
    else:
        if ref_exists(target, local_base):
            base_ref = base_branch
        elif ref_exists(target, remote_base):
            base_ref = f"origin/{base_branch}"
        else:
            raise TargetError(
                f"base branch {base_branch!r} does not exist locally or at origin; fetch it explicitly"
            )
        target.run(["git", "switch", "-c", bdr_branch, base_ref])
        action = "created_branch"

    head = output(target, "git", "rev-parse", "HEAD")
    current = output(target, "git", "branch", "--show-current")
    if current != bdr_branch:
        raise TargetError(f"expected branch {bdr_branch!r}, found {current!r}")
    return {
        "kind": target.kind,
        "host": target.host or "",
        "path": root,
        "base_branch": base_branch,
        "bdr_branch": bdr_branch,
        "action": action,
        "head": head,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    describe = commands.add_parser("describe", help="validate and classify a project location")
    describe.add_argument("location")
    start = commands.add_parser("prepare", help="prepare the target BDR branch")
    start.add_argument("location")
    start.add_argument("base_branch")
    start.add_argument("bdr_branch")
    execute = commands.add_parser("exec", help="execute one argv-safe command in the target")
    execute.add_argument("location")
    execute.add_argument("arguments", nargs=argparse.REMAINDER)
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    try:
        target = Target.parse(arguments.location)
        if arguments.command == "describe":
            result = {"kind": target.kind, "host": target.host or "", "path": target.path}
        elif arguments.command == "prepare":
            result = prepare(target, arguments.base_branch, arguments.bdr_branch)
        else:
            command = arguments.arguments
            if command[:1] == ["--"]:
                command = command[1:]
            if not command:
                raise TargetError("exec requires a command after '--'")
            input_bytes = None if sys.stdin.isatty() else sys.stdin.buffer.read()
            completed = target.run(
                command, check=False, input_bytes=input_bytes
            )
            return completed.returncode
    except (OSError, UnicodeError, TargetError) as exc:
        print(f"bdr-target: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
