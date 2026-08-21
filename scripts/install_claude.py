#!/usr/bin/env python3
"""Install or refresh BAT for Claude Code and Claude-compatible editor agents."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
MARKETPLACE = "bat-local"
PLUGIN = f"bat@{MARKETPLACE}"


class InstallError(RuntimeError):
    pass


def run(claude: str, *arguments: str, capture: bool = False) -> str:
    command = [claude, "plugin", *arguments]
    try:
        result = subprocess.run(
            command,
            check=True,
            text=True,
            stdout=subprocess.PIPE if capture else None,
        )
    except subprocess.CalledProcessError as exc:
        rendered = " ".join(command)
        raise InstallError(f"command failed ({exc.returncode}): {rendered}") from exc
    return result.stdout if capture else ""


def load_list(claude: str, *arguments: str) -> list[dict[str, Any]]:
    raw = run(claude, *arguments, "--json", capture=True)
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise InstallError(f"Claude returned invalid JSON for {' '.join(arguments)}") from exc
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        raise InstallError(f"Claude returned an unexpected result for {' '.join(arguments)}")
    return value


def install(claude: str) -> None:
    marketplaces = load_list(claude, "marketplace", "list")
    local_marketplace = next(
        (item for item in marketplaces if item.get("name") == MARKETPLACE), None
    )
    configured_path = (
        Path(local_marketplace["path"]).resolve()
        if local_marketplace and isinstance(local_marketplace.get("path"), str)
        else None
    )
    if local_marketplace and configured_path != ROOT:
        print("Reconnecting BAT to this checkout...", flush=True)
        run(claude, "marketplace", "remove", MARKETPLACE)
        run(claude, "marketplace", "add", str(ROOT), "--scope", "user")
    elif local_marketplace:
        print("Refreshing BAT's local Claude source...", flush=True)
        run(claude, "marketplace", "update", MARKETPLACE)
    else:
        print("Connecting BAT to Claude...", flush=True)
        run(claude, "marketplace", "add", str(ROOT), "--scope", "user")

    plugins = load_list(claude, "list")
    installed = any(
        item.get("id") == PLUGIN
        or item.get("name") == PLUGIN
        or (item.get("name") == "bat" and item.get("marketplace") == MARKETPLACE)
        for item in plugins
    )
    if installed:
        print("Updating BAT for Claude...", flush=True)
        run(claude, "update", PLUGIN, "--scope", "user")
    else:
        print("Installing BAT for Claude...", flush=True)
        run(claude, "install", PLUGIN, "--scope", "user")

    print()
    print("BAT is ready for Claude Code and Zed's Claude Agent.")
    print("Start a new Claude session or Zed Claude Agent thread, then run:")
    print("  /bat:fix https://github.com/ORG/REPO/issues/NN")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Install or refresh BAT in Claude user scope with one command."
    )
    parser.add_argument(
        "--claude",
        help=argparse.SUPPRESS,
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_args()
    claude = arguments.claude or shutil.which("claude")
    if not claude:
        print(
            "bat-claude-install: Claude Code is required; install it and ensure `claude` is on PATH",
            file=sys.stderr,
        )
        return 127
    try:
        install(claude)
    except InstallError as exc:
        print(f"bat-claude-install: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
