#!/usr/bin/env python3
"""Validate the repository's Claude, Codex, and marketplace package contracts."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")


class ContractError(ValueError):
    pass


def object_without_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_object(relative: str) -> dict[str, Any]:
    path = ROOT / relative
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=object_without_duplicates,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ContractError) as exc:
        raise ContractError(f"{relative}: {exc}") from exc
    if not isinstance(value, dict):
        raise ContractError(f"{relative}: top-level value must be an object")
    return value


def required_text(value: dict[str, Any], field: str, source: str) -> str:
    item = value.get(field)
    if not isinstance(item, str) or not item.strip():
        raise ContractError(f"{source}: {field} must be a non-empty string")
    return item


def require_safe_relative(path_text: str, source: str) -> None:
    path = PurePosixPath(path_text)
    if path.is_absolute() or ".." in path.parts:
        raise ContractError(f"{source}: path must remain relative and cannot contain '..'")


def validate() -> None:
    codex_path = ".codex-plugin/plugin.json"
    claude_path = ".claude-plugin/plugin.json"
    claude_marketplace_path = ".claude-plugin/marketplace.json"
    openai_marketplace_path = "adapters/openai-marketplace/marketplace.json"
    codex = load_object(codex_path)
    claude = load_object(claude_path)
    claude_marketplace = load_object(claude_marketplace_path)
    openai_marketplace = load_object(openai_marketplace_path)

    codex_name = required_text(codex, "name", codex_path)
    codex_version = required_text(codex, "version", codex_path)
    required_text(codex, "description", codex_path)
    if not SEMVER.fullmatch(codex_version):
        raise ContractError(f"{codex_path}: version is not semantic versioning: {codex_version}")

    skills = required_text(codex, "skills", codex_path)
    require_safe_relative(skills, f"{codex_path}: skills")
    if not (ROOT / skills).is_dir():
        raise ContractError(f"{codex_path}: skills directory does not exist: {skills}")
    interface = codex.get("interface")
    if not isinstance(interface, dict):
        raise ContractError(f"{codex_path}: interface must be an object")
    for field in ("displayName", "shortDescription", "longDescription", "developerName"):
        required_text(interface, field, f"{codex_path}: interface")

    for field, expected in (("name", codex_name), ("version", codex_version)):
        actual = required_text(claude, field, claude_path)
        if actual != expected:
            raise ContractError(
                f"{claude_path}: {field} {actual!r} does not match {codex_path} {expected!r}"
            )
    required_text(claude, "description", claude_path)

    claude_marketplace_name = required_text(
        claude_marketplace, "name", claude_marketplace_path
    )
    if claude_marketplace_name != "bat-local":
        raise ContractError(
            f"{claude_marketplace_path}: name must remain 'bat-local' for the installer"
        )
    required_text(claude_marketplace, "description", claude_marketplace_path)
    claude_plugins = claude_marketplace.get("plugins")
    if not isinstance(claude_plugins, list) or not claude_plugins:
        raise ContractError(f"{claude_marketplace_path}: plugins must be a non-empty array")
    claude_matches = [
        plugin
        for plugin in claude_plugins
        if isinstance(plugin, dict) and plugin.get("name") == codex_name
    ]
    if len(claude_matches) != 1:
        raise ContractError(
            f"{claude_marketplace_path}: expected exactly one plugin named "
            f"{codex_name!r}, found {len(claude_matches)}"
        )
    claude_source = required_text(
        claude_matches[0], "source", f"{claude_marketplace_path}: plugin source"
    )
    if claude_source != "./":
        raise ContractError(f"{claude_marketplace_path}: bat plugin source must be './'")
    claude_marketplace_version = required_text(
        claude_matches[0], "version", f"{claude_marketplace_path}: bat plugin"
    )
    if claude_marketplace_version != codex_version:
        raise ContractError(
            f"{claude_marketplace_path}: version {claude_marketplace_version!r} does not "
            f"match {codex_path} {codex_version!r}"
        )

    required_text(openai_marketplace, "name", openai_marketplace_path)
    plugins = openai_marketplace.get("plugins")
    if not isinstance(plugins, list) or not plugins:
        raise ContractError(f"{openai_marketplace_path}: plugins must be a non-empty array")
    matches = [plugin for plugin in plugins if isinstance(plugin, dict) and plugin.get("name") == codex_name]
    if len(matches) != 1:
        raise ContractError(
            f"{openai_marketplace_path}: expected exactly one plugin named {codex_name!r}, found {len(matches)}"
        )
    source = matches[0].get("source")
    if not isinstance(source, dict) or source.get("source") != "local":
        raise ContractError(
            f"{openai_marketplace_path}: {codex_name} must use a local source object"
        )
    marketplace_plugin_path = required_text(
        source, "path", f"{openai_marketplace_path}: plugin source"
    )
    require_safe_relative(
        marketplace_plugin_path, f"{openai_marketplace_path}: plugin source"
    )


def main() -> int:
    try:
        validate()
    except ContractError as exc:
        print(f"FAIL plugin manifests: {exc}", file=sys.stderr)
        return 1
    print("PASS plugin manifests: Claude, Codex, and marketplace contracts agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
