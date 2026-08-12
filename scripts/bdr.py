#!/usr/bin/env python3
"""Dependency-free state engine for Boundary-Driven Refactoring.

The tracker uses strict JSON syntax in a .yaml file. JSON is a YAML 1.2 subset, and the
restriction gives Claude Code, ChatGPT, and Codex the same duplicate-key-safe parser.
"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import errno
import hashlib
import json
import math
import os
import re
import stat
import shutil
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Callable, Iterable
from urllib.parse import urlsplit


VERSION = "2.2.0"
SCHEMA = "bdr.dev/tracker"
SCHEMA_VERSION = 2
LEAN_GATE_MINIMUM_VERSION = "2.2.0"
PHASES = ("expose", "represent", "route", "collapse", "saturate", "falsify")
STRUCTURAL_PHASES = {"represent", "route", "collapse"}
RUN_STATES = {
    "preflighting", "auditing", "executing", "verifying", "ready_for_review",
    "verification_pending", "needs_human", "blocked_environment", "stale_input",
    "non_convergent", "failed_verification",
}
INTERVENTION_RUN_STATES = {
    "verification_pending", "needs_human", "blocked_environment", "stale_input",
    "non_convergent", "failed_verification",
}
SLICE_KINDS = {"boundary"}
DELIVERY_KINDS = {"commit", "no_code_change"}
DELIVERY_EVIDENCE_KINDS = {"test", "verification"}
FIXED_POINT_EVIDENCE_KINDS = {"rescan"}
MERGE_POLICIES = {"required", "optional"}
FINDING_SHAPES = {"value", "temporal", "concurrency", "direct"}
NORMALIZED_KINDS = {
    "value", "ownership", "borrow", "lease", "capability", "work_ownership",
    "reservation", "projection", "completion", "real_time", "concurrency_order",
    "external_lifecycle", "direct", "unclassified",
}
RESOLUTION_KINDS = {"fixed", "split", "superseded", "blocked_external", "needs_human"}
ATTEMPT_RESULTS = {"passed", "failed", "blocked", "rewound"}
FACT_DISPOSITIONS = {"assumed", "measured", "documented", "enforced", "eliminated"}
RISK_COMPARISONS = {"lower", "equivalent", "higher", "unknown"}
DEPENDENCY_KINDS = {"external_contract", "human_decision", "external_issue", "environment"}
DEPENDENCY_STATUSES = {"open", "resolved"}
DECISION_STATUSES = {"open", "resolved"}
MAX_MIGRATION_DEPTH = 100
MAX_MIGRATION_NODES = 100_000


RULES = {
    "V001": "tracker uses the V2 schema and supported enum values",
    "V002": "IDs, references, and dependency graphs are valid and acyclic",
    "V003": "finding ownership is a continuous append-only chain with code-read evidence",
    "V004": "typed resolutions have evidence; splits are reciprocal and acyclic",
    "V005": "phase attempts replay in legal order and passed gates carry required evidence",
    "V006": "higher/unknown introduced risk cannot be silently accepted",
    "V007": "relied-on foreign facts are version-bound and no longer assumed",
    "V008": "slice completion and run readiness are derived from verified evidence",
    "V009": "current state agrees with the append-only hash-chained event journal",
    "V010": "only one crash-recoverable phase operation may be active",
}


class BdrError(RuntimeError):
    pass


class DuplicateKeyError(BdrError):
    pass


class NonFiniteNumberError(BdrError):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()


def canonical_bytes(value: Any) -> bytes:
    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode()
    except (TypeError, ValueError) as exc:
        raise BdrError(f"value is not finite JSON data: {exc}") from exc


def digest(value: Any) -> str:
    data = value if isinstance(value, bytes) else canonical_bytes(value)
    return hashlib.sha256(data).hexdigest()


def is_sha256(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in pairs:
        if key in out:
            raise DuplicateKeyError(f"duplicate key {key!r}")
        out[key] = value
    return out


def loads_strict(text: str, source: str = "tracker") -> dict[str, Any]:
    def reject_nonfinite(token: str) -> Any:
        raise NonFiniteNumberError(f"{source} contains non-finite JSON number {token!r}")

    try:
        value = json.loads(
            text,
            object_pairs_hook=_strict_object,
            parse_constant=reject_nonfinite,
        )
    except DuplicateKeyError:
        raise
    except json.JSONDecodeError as exc:
        raise BdrError(
            f"{source} is not strict JSON-compatible YAML at line {exc.lineno}, "
            f"column {exc.colno}: {exc.msg}"
        ) from exc
    if not isinstance(value, dict):
        raise BdrError(f"{source} must contain one top-level mapping")
    return value


def path_entry_exists(path: Path) -> bool:
    """Like exists(), but true for dangling symlinks too."""
    return os.path.lexists(path)


def regular_file_stat(path: Path, label: str) -> os.stat_result:
    try:
        value = os.lstat(path)
    except FileNotFoundError as exc:
        raise BdrError(f"{label} not found: {path}") from exc
    if stat.S_ISLNK(value.st_mode):
        raise BdrError(f"{label} may not be a symlink: {path}")
    if not stat.S_ISREG(value.st_mode):
        raise BdrError(f"{label} must be a regular file: {path}")
    return value


def same_file_identity(left: os.stat_result, right: os.stat_result) -> bool:
    if left.st_ino and right.st_ino:
        return left.st_dev == right.st_dev and left.st_ino == right.st_ino
    return (
        stat.S_IFMT(left.st_mode) == stat.S_IFMT(right.st_mode)
        and left.st_size == right.st_size
        and getattr(left, "st_mtime_ns", None) == getattr(right, "st_mtime_ns", None)
    )


def open_regular_no_follow(path: Path, flags: int, label: str, mode: int = 0o600) -> int:
    before = regular_file_stat(path, label)
    nofollow = getattr(os, "O_NOFOLLOW", 0)
    binary = getattr(os, "O_BINARY", 0)
    try:
        fd = os.open(path, flags | nofollow | binary, mode)
    except OSError as exc:
        if exc.errno in {errno.ELOOP, errno.EMLINK}:
            raise BdrError(f"{label} may not be a symlink: {path}") from exc
        raise
    opened = os.fstat(fd)
    if not stat.S_ISREG(opened.st_mode) or not same_file_identity(before, opened):
        os.close(fd)
        raise BdrError(f"{label} changed while it was being opened: {path}")
    return fd


def read_bytes_no_follow(path: Path, label: str) -> bytes:
    fd = open_regular_no_follow(path, os.O_RDONLY, label)
    try:
        with os.fdopen(fd, "rb") as handle:
            return handle.read()
    except Exception:
        # fdopen owns fd once constructed; this only covers construction failure.
        try:
            os.close(fd)
        except OSError:
            pass
        raise


def read_text_no_follow(path: Path, label: str) -> str:
    try:
        return read_bytes_no_follow(path, label).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BdrError(f"{label} is not UTF-8: {path}") from exc


def load_json_file(path: Path) -> dict[str, Any]:
    if not path_entry_exists(path):
        raise BdrError(f"tracker not found: {path}. Run `bdr init`; templates are never validated as live state")
    return loads_strict(read_text_no_follow(path, "tracker"), str(path))


def load_input(path: str | None) -> dict[str, Any]:
    if not path or path == "-":
        return loads_strict(sys.stdin.read(), "stdin operation")
    return loads_strict(read_text_no_follow(Path(path), "operation input"), path)


def atomic_write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        payload = json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n"
    except (TypeError, ValueError) as exc:
        raise BdrError(f"refusing to write non-finite or non-JSON state: {exc}") from exc
    if path_entry_exists(path) and path.is_symlink():
        raise BdrError(f"refusing to replace symlinked state: {path}")
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        try:
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        except OSError:
            pass
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def exclusive_write(path: Path, value: Any) -> None:
    """Create a JSON file without a check-then-overwrite race."""
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        payload = (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode()
    except (TypeError, ValueError) as exc:
        raise BdrError(f"refusing to write non-finite or non-JSON state: {exc}") from exc
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        fd = os.open(path, flags, 0o600)
    except FileExistsError as exc:
        raise BdrError(f"refusing to overwrite existing BDR state: {path}") from exc
    try:
        view = memoryview(payload)
        while view:
            written = os.write(fd, view)
            if written <= 0:
                raise BdrError(f"short write while creating {path}")
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)
    try:
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except OSError:
        pass


def run_command(argv: list[str], cwd: Path, required: bool = True) -> subprocess.CompletedProcess[str]:
    command = list(argv)
    if command and command[0] == "git" and "--no-replace-objects" not in command[1:]:
        command.insert(1, "--no-replace-objects")
    completed = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    if required and completed.returncode:
        message = completed.stderr.strip() or completed.stdout.strip() or f"exit {completed.returncode}"
        raise BdrError(f"{' '.join(command)} failed: {message}")
    return completed


def git_root(cwd: Path) -> Path:
    result = run_command(["git", "rev-parse", "--show-toplevel"], cwd)
    return Path(result.stdout.strip()).resolve()


def git_value(root: Path, *args: str) -> str:
    return run_command(["git", *args], root).stdout.strip()


def git_graft_errors(root: Path) -> list[str]:
    """Reject legacy graft files because Git applies them despite no-replace mode."""
    result = run_command(
        ["git", "rev-parse", "--git-path", "info/grafts"], root, required=False
    )
    if result.returncode or not result.stdout.strip():
        return ["cannot resolve the repository's legacy Git graft path"]
    raw = Path(result.stdout.strip())
    graft_path = raw if raw.is_absolute() else root / raw
    if not path_entry_exists(graft_path):
        return []
    try:
        contents = read_bytes_no_follow(graft_path, "legacy Git graft file")
    except (BdrError, OSError) as exc:
        return [f"cannot safely inspect legacy Git graft file {graft_path}: {exc}"]
    if contents:
        return [
            f"legacy Git graft file {graft_path} is nonempty and can rewrite commit ancestry; remove it before BDR"
        ]
    return []


def is_canonical_commit_oid(value: Any) -> bool:
    return (
        isinstance(value, str)
        and len(value) in {40, 64}
        and all(character in "0123456789abcdef" for character in value)
    )


def valid_pr_selector(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    if value == "current" or re.fullmatch(r"[1-9][0-9]*", value):
        return True
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        return False
    return (
        parsed.scheme == "https"
        and parsed.hostname is not None
        and parsed.username is None
        and parsed.password is None
        and (port is None or 1 <= port <= 65535)
        and not parsed.query
        and not parsed.fragment
        and re.fullmatch(r"/[^/\s]+/[^/\s]+/pull/[1-9][0-9]*/?", parsed.path) is not None
    )


def canonical_commit_oid(root: Path, reference: Any, label: str) -> str:
    """Resolve exactly one commit-ish to its immutable, full object ID."""
    if not is_nonempty_string(reference) or "\0" in reference or "\n" in reference or "\r" in reference:
        raise BdrError(f"{label} must name exactly one Git commit")
    result = run_command(
        ["git", "rev-parse", "--verify", "--end-of-options", f"{reference}^{{commit}}"],
        root,
        required=False,
    )
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if result.returncode or len(lines) != 1:
        detail = result.stderr.strip() or result.stdout.strip() or "not a commit"
        raise BdrError(f"{label} must resolve unambiguously to one commit: {detail}")
    oid = lines[0]
    if not is_canonical_commit_oid(oid):
        raise BdrError(f"{label} did not resolve to a canonical full commit object ID")
    return oid


def policy_input_snapshot(root: Path, base_sha: str, head_sha: str) -> list[dict[str, Any]]:
    def names_at(revision: str) -> set[str]:
        result = run_command(["git", "ls-tree", "-rz", "--name-only", revision], root)
        return {name for name in result.stdout.split("\0") if name}

    def relevant(path: str) -> bool:
        name = Path(path).name
        return (
            name in {"AGENTS.md", "CLAUDE.md"}
            or path in {".github/copilot-instructions.md", ".claude/settings.json", ".codex/config.toml"}
            or (path.startswith(".github/instructions/") and path.endswith(".instructions.md"))
        )

    def content_hash(revision: str, path: str) -> str | None:
        result = run_command(["git", "show", f"{revision}:{path}"], root, required=False)
        return digest(result.stdout.encode()) if result.returncode == 0 else None

    paths = sorted(path for path in names_at(base_sha) | names_at(head_sha) if relevant(path))
    records: list[dict[str, Any]] = []
    for path in paths:
        base_hash = content_hash(base_sha, path)
        head_hash = content_hash(head_sha, path)
        records.append({
            "path": path,
            "base_sha256": base_hash,
            "head_sha256": head_hash,
            "changed_by_target": base_hash != head_hash,
        })
    return records


def audit_exclusions(audit_dir: str, tracker_path: str | None = None) -> list[str]:
    """Exclude only engine-owned files, never the tracker's whole parent directory."""
    normalized_dir = audit_dir.strip("/") or ".bdr"
    normalized_tracker = tracker_path or f"{normalized_dir}/progress.yaml"
    parent = Path(normalized_tracker).parent.as_posix()
    # `literal` prevents a user-selected path from becoming Git pathspec magic
    # (for example, `audit[1]` or `:(top)elsewhere`).
    return [
        f":(exclude,literal){normalized_tracker}",
        f":(exclude,literal){parent}/events.jsonl",
        f":(exclude,literal){parent}/engine.lock",
    ]


def git_blob_oid(data: bytes, object_format: str) -> str:
    if object_format not in {"sha1", "sha256"}:
        raise BdrError(f"unsupported Git object format {object_format!r}")
    hasher = hashlib.new(object_format)
    hasher.update(f"blob {len(data)}\0".encode())
    hasher.update(data)
    return hasher.hexdigest()


def git_worktree_blob_oid(root: Path, relative: str, data: bytes, object_format: str) -> str:
    """Hash worktree bytes exactly as Git would store them for this path."""
    if object_format not in {"sha1", "sha256"}:
        raise BdrError(f"unsupported Git object format {object_format!r}")
    command = [
        "git", "--no-replace-objects", "hash-object", f"--path={relative}", "--stdin",
    ]
    completed = subprocess.run(
        command, cwd=root, input=data, capture_output=True, check=False,
    )
    if completed.returncode:
        detail = (completed.stderr or completed.stdout).decode("utf-8", errors="replace").strip()
        raise BdrError(
            f"cannot apply Git clean conversion while fingerprinting {relative!r}: "
            f"{detail or f'exit {completed.returncode}'}"
        )
    try:
        oid = completed.stdout.decode("ascii").strip()
    except UnicodeDecodeError as exc:
        raise BdrError(f"Git returned a non-ASCII object ID for {relative!r}") from exc
    expected_length = hashlib.new(object_format).digest_size * 2
    if len(oid) != expected_length or any(character not in "0123456789abcdef" for character in oid):
        raise BdrError(f"Git returned an invalid object ID for {relative!r}")
    return oid


def changed_paths(
    root: Path,
    base: str,
    target: str | None,
    audit_dir: str,
    tracker_path: str | None,
) -> list[str]:
    excluded = audit_exclusions(audit_dir, tracker_path)
    revision_args = [base] if target is None else [base, target]
    tracked = run_command(
        [
            "git", "diff", "--no-ext-diff", "--ignore-submodules=none", "--no-renames",
            "--name-only", "-z", *revision_args, "--", ".", *excluded,
        ],
        root,
    ).stdout.split("\0")
    paths = {path for path in tracked if path}
    if target is None:
        others = run_command(
            ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", ".", *excluded],
            root,
        ).stdout.split("\0")
        paths.update(path for path in others if path)
    return sorted(paths)


def tracked_worktree_modes(
    root: Path, base: str, audit_dir: str, tracker_path: str | None,
) -> dict[str, str]:
    """Return the modes Git would use for tracked paths changed from base."""
    excluded = audit_exclusions(audit_dir, tracker_path)
    raw = run_command(
        [
            "git", "diff", "--no-ext-diff", "--ignore-submodules=none", "--no-renames",
            "--raw", "--no-abbrev", "-z", base, "--", ".", *excluded,
        ],
        root,
    ).stdout.split("\0")
    if raw and raw[-1] == "":
        raw.pop()
    if len(raw) % 2:
        raise BdrError("cannot parse Git's tracked worktree mode inventory")
    modes: dict[str, str] = {}
    for index in range(0, len(raw), 2):
        metadata, relative = raw[index], raw[index + 1]
        fields = metadata.split()
        if (
            len(fields) != 5
            or not fields[0].startswith(":")
            or re.fullmatch(r"[0-7]{6}", fields[0][1:]) is None
            or re.fullmatch(r"[0-7]{6}", fields[1]) is None
            or not relative
        ):
            raise BdrError("cannot parse Git's tracked worktree mode inventory")
        if relative in modes:
            raise BdrError(f"Git reported duplicate tracked worktree entries for {relative!r}")
        modes[relative] = fields[1]
    return modes


def untracked_paths(
    root: Path, audit_dir: str, tracker_path: str | None,
) -> set[str]:
    excluded = audit_exclusions(audit_dir, tracker_path)
    raw = run_command(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", ".", *excluded],
        root,
    ).stdout.split("\0")
    paths: set[str] = set()
    for relative in raw:
        if not relative:
            continue
        # Git reports an embedded untracked repository with a trailing slash;
        # its eventual tree entry has no slash.
        normalized = relative[:-1] if relative.endswith("/") else relative
        if not normalized:
            raise BdrError("Git returned an invalid untracked path")
        paths.add(normalized)
    return paths


def git_filemode_enabled(root: Path) -> bool:
    result = run_command(
        ["git", "config", "--type=bool", "--get", "core.filemode"], root, required=False,
    )
    if result.returncode == 1 and not result.stdout and not result.stderr:
        return True
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise BdrError(f"cannot read Git core.fileMode: {detail}")
    value = result.stdout.strip().lower()
    if value not in {"true", "false"}:
        raise BdrError(f"Git returned an invalid core.fileMode value {value!r}")
    return value == "true"


def ensure_git_index_visible(
    root: Path, label: str = "repository", visited_git_dirs: set[Path] | None = None,
) -> None:
    """Reject index flags that can hide worktree bytes from Git comparisons."""
    visited = visited_git_dirs if visited_git_dirs is not None else set()
    git_dir_result = run_command(
        ["git", "rev-parse", "--absolute-git-dir"], root, required=False,
    )
    if git_dir_result.returncode or not git_dir_result.stdout.strip():
        raise BdrError(f"cannot resolve Git metadata while checking {label}")
    git_dir = Path(git_dir_result.stdout.strip()).resolve()
    if git_dir in visited:
        return
    visited.add(git_dir)

    entries = run_command(["git", "ls-files", "--stage", "-v", "-z"], root).stdout.split("\0")
    nested: set[str] = set()
    for entry in entries:
        if not entry:
            continue
        if len(entry) < 4 or entry[1] != " " or "\t" not in entry[2:]:
            raise BdrError(f"cannot parse Git index visibility for {label}")
        tag = entry[0]
        metadata, relative = entry[2:].split("\t", 1)
        fields = metadata.split()
        if len(fields) != 3 or re.fullmatch(r"[0-7]{6}", fields[0]) is None or not relative:
            raise BdrError(f"cannot parse Git index visibility for {label}")
        if tag.islower():
            raise BdrError(
                f"{label} has assume-unchanged index entry {relative!r}; "
                "Git could hide uncommitted bytes from delivery verification"
            )
        candidate = root / relative
        if tag == "S" and path_entry_exists(candidate):
            raise BdrError(
                f"{label} has a present skip-worktree entry {relative!r}; "
                "Git could hide uncommitted bytes from delivery verification"
            )
        if fields[0] == "160000":
            nested.add(relative)

    resolved_root = root.resolve()
    for relative in sorted(nested):
        candidate = root / relative
        if not path_entry_exists(candidate):
            continue
        metadata = os.lstat(candidate)
        if not stat.S_ISDIR(metadata.st_mode):
            raise BdrError(f"indexed submodule {relative!r} in {label} is not a directory")
        resolved_candidate = candidate.resolve()
        try:
            resolved_candidate.relative_to(resolved_root)
        except ValueError as exc:
            raise BdrError(f"indexed submodule {relative!r} escapes {label}") from exc
        nested_root = run_command(
            ["git", "rev-parse", "--show-toplevel"], candidate, required=False,
        )
        independent_checkout = (
            not nested_root.returncode
            and bool(nested_root.stdout.strip())
            and Path(nested_root.stdout.strip()).resolve() == resolved_candidate
        )
        if not independent_checkout:
            try:
                with os.scandir(candidate) as entries:
                    empty = next(entries, None) is None
            except OSError as exc:
                raise BdrError(
                    f"cannot inspect indexed submodule {relative!r} in {label}"
                ) from exc
            if empty:
                # A non-recursive clone commonly materializes an uninitialized
                # submodule as an empty directory. It contains no hidden bytes.
                continue
            raise BdrError(f"indexed submodule {relative!r} in {label} is not an independent checkout")
        ensure_git_index_visible(
            resolved_candidate, f"submodule {relative!r}", visited,
        )


def worktree_delta_sha256(
    root: Path, base: str, audit_dir: str = ".bdr", tracker_path: str | None = None,
) -> str:
    ensure_git_index_visible(root)
    object_format = git_value(root, "rev-parse", "--show-object-format")
    tracked_modes = tracked_worktree_modes(root, base, audit_dir, tracker_path)
    untracked = untracked_paths(root, audit_dir, tracker_path)
    paths = sorted(set(tracked_modes) | untracked)
    filemode_enabled = git_filemode_enabled(root) if untracked else True
    records: list[dict[str, str]] = []
    for relative in paths:
        candidate = root / relative
        if relative in untracked:
            effective_mode: str | None = None
        else:
            effective_mode = tracked_modes[relative]
        if effective_mode == "000000":
            if path_entry_exists(candidate):
                raise BdrError(
                    f"tracked deletion is shadowed by an ignored or ambiguous worktree path: {relative!r}"
                )
            records.append({"path": relative, "kind": "deleted"})
            continue
        try:
            metadata = os.lstat(candidate)
        except FileNotFoundError as exc:
            raise BdrError(f"changed workspace path disappeared during fingerprinting: {relative!r}") from exc
        if effective_mode is None:
            if stat.S_ISLNK(metadata.st_mode):
                effective_mode = "120000"
            elif stat.S_ISREG(metadata.st_mode):
                executable = filemode_enabled and bool(metadata.st_mode & stat.S_IXUSR)
                effective_mode = "100755" if executable else "100644"
            elif stat.S_ISDIR(metadata.st_mode):
                effective_mode = "160000"
            else:
                raise BdrError(f"cannot fingerprint special changed path {relative!r}")
        if effective_mode == "120000":
            if stat.S_ISLNK(metadata.st_mode):
                data = os.readlink(candidate).encode()
                oid = git_blob_oid(data, object_format)
            elif stat.S_ISREG(metadata.st_mode):
                # With core.symlinks=false Git materializes an indexed symlink as
                # a regular file containing its target, preserves mode 120000,
                # and still applies the path's clean conversion when it is added.
                data = read_bytes_no_follow(candidate, "changed workspace symlink")
                oid = git_worktree_blob_oid(root, relative, data, object_format)
            else:
                raise BdrError(f"changed Git symlink has incompatible worktree type: {relative!r}")
            records.append({
                "path": relative, "kind": "blob", "mode": effective_mode,
                "oid": oid,
            })
        elif effective_mode in {"100644", "100755"}:
            if not stat.S_ISREG(metadata.st_mode):
                raise BdrError(f"changed Git file has incompatible worktree type: {relative!r}")
            data = read_bytes_no_follow(candidate, "changed workspace file")
            records.append({
                "path": relative, "kind": "blob", "mode": effective_mode,
                "oid": git_worktree_blob_oid(root, relative, data, object_format),
            })
        elif effective_mode == "160000":
            if not stat.S_ISDIR(metadata.st_mode):
                raise BdrError(f"changed Git submodule has incompatible worktree type: {relative!r}")
            submodule = run_command(["git", "rev-parse", "HEAD"], candidate, required=False)
            oid = submodule.stdout.strip()
            if submodule.returncode or not is_canonical_commit_oid(oid):
                raise BdrError(f"cannot fingerprint changed directory {relative!r} as a Git submodule")
            ensure_git_index_visible(candidate, f"submodule {relative!r}")
            submodule_status = run_command(
                [
                    "git", "status", "--porcelain=v1", "--untracked-files=all",
                    "--ignore-submodules=none",
                ],
                candidate,
                required=False,
            )
            if submodule_status.returncode:
                detail = submodule_status.stderr.strip() or submodule_status.stdout.strip()
                raise BdrError(
                    f"cannot inspect changed submodule {relative!r}: "
                    f"{detail or f'exit {submodule_status.returncode}'}"
                )
            if submodule_status.stdout:
                raise BdrError(
                    f"changed submodule {relative!r} has uncommitted content that a parent delivery cannot bind"
                )
            records.append({"path": relative, "kind": "commit", "mode": effective_mode, "oid": oid})
        else:
            raise BdrError(f"cannot fingerprint changed Git mode {effective_mode!r} for {relative!r}")
    return digest(records)


def commit_delta_sha256(
    root: Path,
    base: str,
    commit: str,
    audit_dir: str = ".bdr",
    tracker_path: str | None = None,
) -> str:
    records: list[dict[str, str]] = []
    for relative in changed_paths(root, base, commit, audit_dir, tracker_path):
        raw = run_command(
            ["git", "ls-tree", "-z", commit, "--", f":(literal){relative}"], root
        ).stdout
        if not raw:
            records.append({"path": relative, "kind": "deleted"})
            continue
        line = raw[:-1] if raw.endswith("\0") else raw
        try:
            metadata, observed_path = line.split("\t", 1)
            mode, kind, oid = metadata.split(" ", 2)
        except ValueError as exc:
            raise BdrError(f"cannot parse delivered tree entry for {relative!r}") from exc
        if observed_path != relative or kind not in {"blob", "commit"}:
            raise BdrError(f"delivered tree entry for {relative!r} is ambiguous")
        records.append({"path": relative, "kind": kind, "mode": mode, "oid": oid})
    return digest(records)


def workspace_snapshot(
    root: Path,
    audit_dir: str = ".bdr",
    tracker_path: str | None = None,
    *,
    include_content_delta: bool = False,
    require_visible_index: bool = False,
) -> dict[str, Any]:
    if require_visible_index and not include_content_delta:
        ensure_git_index_visible(root)
    head = git_value(root, "rev-parse", "HEAD")
    excluded = audit_exclusions(audit_dir, tracker_path)
    diff = run_command(
        [
            "git", "diff", "--no-ext-diff", "--ignore-submodules=none", "--binary",
            "HEAD", "--", ".", *excluded,
        ], root
    ).stdout.encode()
    staged = run_command(
        [
            "git", "diff", "--no-ext-diff", "--ignore-submodules=none", "--binary",
            "--cached", "HEAD", "--", ".", *excluded,
        ], root
    ).stdout.encode()
    others = run_command(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", ".", *excluded], root
    ).stdout.split("\0")
    untracked: list[dict[str, str]] = []
    for relative in sorted(x for x in others if x):
        candidate = root / relative
        try:
            metadata = os.lstat(candidate)
        except FileNotFoundError as exc:
            raise BdrError(f"untracked path changed during workspace snapshot: {relative}") from exc
        if stat.S_ISLNK(metadata.st_mode):
            # Hash the directory entry, never the target. This handles dangling and
            # directory symlinks without reading outside the repository.
            target = os.readlink(candidate)
            untracked.append({"path": relative, "kind": "symlink", "sha256": digest(target.encode())})
        elif stat.S_ISREG(metadata.st_mode):
            untracked.append({
                "path": relative,
                "kind": "file",
                "sha256": digest(read_bytes_no_follow(candidate, "untracked workspace file")),
            })
        else:
            # Never open FIFOs, devices, or sockets while taking a snapshot.
            untracked.append({
                "path": relative,
                "kind": f"special:{stat.S_IFMT(metadata.st_mode):o}",
                "sha256": digest(str(metadata.st_mode).encode()),
            })
    snapshot = {
        "head_sha": head,
        "worktree_sha256": digest(diff + b"\0" + staged + b"\0" + canonical_bytes(untracked)),
        "dirty": bool(diff or staged or untracked),
        "captured_at": utc_now(),
    }
    if include_content_delta:
        snapshot["content_delta_sha256"] = worktree_delta_sha256(
            root, head, audit_dir, tracker_path
        )
    return snapshot


def state_path(raw: str, root: Path | None = None) -> Path:
    repository = (root or git_root(Path.cwd())).resolve()
    requested = Path(raw)
    candidate = requested if requested.is_absolute() else repository / requested
    if candidate.name in {"events.jsonl", "engine.lock"}:
        raise BdrError("state filename conflicts with an engine-owned sidecar")
    cursor = candidate
    while cursor != repository and cursor != cursor.parent:
        if cursor.is_symlink():
            raise BdrError(f"state path may not traverse a symlink: {cursor}")
        cursor = cursor.parent
    resolved = candidate.resolve()
    try:
        resolved.relative_to(repository)
    except ValueError as exc:
        raise BdrError(f"state path must stay inside repository {repository}: {resolved}") from exc
    return resolved


def events_path(path: Path) -> Path:
    return path.with_name("events.jsonl")


@contextmanager
def state_lock(path: Path):
    lock = path.with_name("engine.lock")
    lock.parent.mkdir(parents=True, exist_ok=True)
    if path_entry_exists(lock) and lock.is_symlink():
        raise BdrError(f"engine lock may not be a symlink: {lock}")
    try:
        fd = os.open(
            lock,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
            0o600,
        )
    except FileExistsError as exc:
        try:
            owner = read_bytes_no_follow(lock, "engine lock").decode("utf-8", errors="replace").strip()
        except BdrError:
            owner = "unreadable or non-regular lock"
        raise BdrError(f"another BDR state mutation is active ({owner}); do not guess recovery") from exc
    try:
        owned_stat: os.stat_result | None = None
        try:
            payload = f"pid={os.getpid()} started={utc_now()}\n".encode()
            view = memoryview(payload)
            while view:
                written = os.write(fd, view)
                if written <= 0:
                    raise BdrError(f"short write while acquiring engine lock {lock}")
                view = view[written:]
            os.fsync(fd)
            owned_stat = os.fstat(fd)
        finally:
            os.close(fd)
        yield
    finally:
        try:
            current = os.lstat(lock)
        except FileNotFoundError:
            current = None
        if current is not None and owned_stat is not None and same_file_identity(current, owned_stat):
            lock.unlink()


def next_id(mapping: dict[str, Any], prefix: str) -> str:
    used = []
    for key in mapping:
        if key.startswith(prefix + "-"):
            try:
                used.append(int(key.rsplit("-", 1)[1]))
            except ValueError:
                continue
    return f"{prefix}-{max(used, default=0) + 1:04d}"


def blank_phase_attempts() -> list[dict[str, Any]]:
    return []


def new_state(root: Path, args: argparse.Namespace) -> dict[str, Any]:
    # macOS commonly exposes the same directory through both /var and
    # /private/var.  Persist and compare the canonical repository identity.
    root = root.resolve()
    head = canonical_commit_oid(root, args.head_sha or "HEAD", "target head")
    base = args.base_sha
    if not base:
        parent = run_command(["git", "rev-parse", "HEAD^"], root, required=False)
        if parent.returncode:
            raise BdrError("--base-sha is required when HEAD has no parent")
        base = parent.stdout.strip()
    base = canonical_commit_oid(root, base, "base")
    branch = git_value(root, "branch", "--show-current") or "detached"
    created = utc_now()
    tracker_path = state_path(getattr(args, "state", ".bdr/progress.yaml"), root)
    audit_dir = tracker_path.parent.relative_to(root).as_posix()
    tracker_relative = tracker_path.relative_to(root).as_posix()
    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "minimum_validator_version": VERSION,
        "revision": 0,
        "semantic_revision": 0,
        "source": {
            "repository": args.repository or root.name,
            "pr": args.pr,
            "base_sha": base,
            "starting_head_sha": head,
            "branch": branch,
            "root": str(root),
            "audit_dir": audit_dir,
            "tracker_path": tracker_relative,
            "policy_inputs": policy_input_snapshot(root, base, head),
        },
        "policy": {
            "higher_risk": "requires_human_approval",
            "github_projection": args.github_mode,
            "push": "never",
            "max_fixed_point_passes": args.max_fixed_point_passes,
            "max_phase_attempts": args.max_phase_attempts,
        },
        "run": {
            "id": args.run_id or f"BDR-{created[:10]}-{head[:8]}",
            "state": "preflighting",
            "created_at": created,
            "updated_at": created,
            "baseline": None,
            "terminal_reason": None,
        },
        "active_operation": None,
        "checkpoints": {},
        "evidence": {},
        "dependencies": {},
        "decisions": {},
        "foreign_facts": {},
        "findings": {},
        "slices": {},
        "fixed_point": {"passes": []},
        "github": {"mappings": {}, "outbox": []},
    }


def current_owner(finding: Any) -> str | None:
    if not isinstance(finding, dict):
        return None
    ownership = finding.get("ownership", [])
    if not isinstance(ownership, list):
        return None
    owner: str | None = None
    for event in ownership:
        if not isinstance(event, dict):
            return None
        target = event.get("to")
        if target is not None and not isinstance(target, str):
            return None
        owner = target
    return owner


def owner_at_revision(finding: Any, revision: Any) -> str | None:
    """Return the last well-formed owner at or before a tracker revision."""
    if not isinstance(finding, dict) or not is_json_integer(revision, 0):
        return None
    ownership = finding.get("ownership", [])
    if not isinstance(ownership, list):
        return None
    owner: str | None = None
    for event in ownership:
        if not isinstance(event, dict) or not is_json_integer(event.get("revision"), 0):
            return None
        if event["revision"] > revision:
            break
        target = event.get("to")
        if target is not None and not isinstance(target, str):
            return None
        owner = target
    return owner


def replay_slice(slice_: dict[str, Any]) -> tuple[int, list[str]]:
    index = 0
    errors: list[str] = []
    if not isinstance(slice_, dict):
        return index, ["slice is not an object"]
    attempts = slice_.get("phase_attempts", [])
    if not isinstance(attempts, list):
        return index, ["phase_attempts is not a list"]
    for position, attempt in enumerate(attempts, 1):
        if not isinstance(attempt, dict):
            errors.append(f"attempt {position} is not an object")
            continue
        phase = attempt.get("phase")
        result = attempt.get("result")
        if not is_enum(phase, PHASES) or not is_enum(result, ATTEMPT_RESULTS):
            errors.append(f"attempt {position} has invalid phase/result {phase!r}/{result!r}")
            continue
        if result == "rewound":
            target = attempt.get("rewind_to")
            if not is_enum(target, PHASES) or PHASES.index(target) > index:
                errors.append(f"attempt {position} has illegal rewind target {target!r}")
            else:
                index = PHASES.index(target)
            continue
        if index >= len(PHASES) or phase != PHASES[index]:
            expected = "complete" if index >= len(PHASES) else PHASES[index]
            errors.append(f"attempt {position} runs {phase} while next legal phase is {expected}")
            continue
        if result == "passed":
            index += 1
    return index, errors


def slice_progress(slice_: dict[str, Any]) -> dict[str, Any]:
    index, errors = replay_slice(slice_)
    return {
        "completed": index == len(PHASES) and not errors,
        "next_phase": None if index == len(PHASES) else PHASES[index],
        "passed": index,
        "errors": errors,
    }


def live_passed_attempt_positions(slice_: Any) -> set[int]:
    """Identify passed attempts still in the active prefix after all rewinds."""
    if not isinstance(slice_, dict):
        return set()
    attempts = slice_.get("phase_attempts", [])
    if not isinstance(attempts, list):
        return set()
    index = 0
    active: list[int] = []
    for position, attempt in enumerate(attempts):
        if not isinstance(attempt, dict):
            continue
        phase = attempt.get("phase")
        result = attempt.get("result")
        if not is_enum(phase, PHASES) or not is_enum(result, ATTEMPT_RESULTS):
            continue
        if result == "rewound":
            target = attempt.get("rewind_to")
            if is_enum(target, PHASES) and PHASES.index(target) <= index:
                index = PHASES.index(target)
                active = active[:index]
            continue
        if index < len(PHASES) and phase == PHASES[index] and result == "passed":
            active.append(position)
            index += 1
    return set(active)


def finding_open(finding: Any) -> bool:
    return not isinstance(finding, dict) or finding.get("resolution") is None


def add_error(errors: list[tuple[str, str]], rule: str, message: str) -> None:
    errors.append((rule, message))


def is_nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def is_json_integer(value: Any, minimum: int | None = None) -> bool:
    """JSON booleans are not valid integer fields, despite Python's bool subclass."""
    return type(value) is int and (minimum is None or value >= minimum)


def is_enum(value: Any, allowed: set[str] | tuple[str, ...]) -> bool:
    return isinstance(value, str) and value in allowed


def reject_unknown_keys(
    errors: list[tuple[str, str]], rule: str, label: str, value: Any, allowed: set[str]
) -> None:
    if isinstance(value, dict):
        unknown = set(value) - allowed
        if unknown:
            add_error(errors, rule, f"{label} has unsupported fields: {sorted(unknown)}")


def version_tuple(value: Any) -> tuple[int, int, int] | None:
    if not isinstance(value, str):
        return None
    try:
        parts = tuple(int(part) for part in value.split("."))
    except ValueError:
        return None
    return parts if len(parts) == 3 else None


def require_validator_version(state: dict[str, Any], required: str) -> None:
    current = version_tuple(state.get("minimum_validator_version"))
    target = version_tuple(required)
    if current is None or target is None:
        raise BdrError("tracker has an invalid validator version")
    if current < target:
        state["minimum_validator_version"] = required


def evidence_exists(state: dict[str, Any], evidence_id: Any) -> bool:
    return is_nonempty_string(evidence_id) and evidence_id in state.get("evidence", {})


def evidence_has_kind(state: dict[str, Any], evidence_id: Any, kinds: set[str]) -> bool:
    if not evidence_exists(state, evidence_id):
        return False
    record = state["evidence"][evidence_id]
    return isinstance(record, dict) and is_enum(record.get("kind"), kinds)


def successful_command_records(records: Any) -> bool:
    return command_records_valid(records) and all(record.get("exit_code") == 0 for record in records)


def standalone_passing_verification_evidence(state: dict[str, Any], evidence_id: Any) -> bool:
    if not evidence_has_kind(state, evidence_id, {"test", "verification"}):
        return False
    commands = state["evidence"][evidence_id].get("commands")
    return successful_command_records(commands)


def counterfactual_verification_evidence(state: dict[str, Any], evidence_id: Any) -> bool:
    if not evidence_has_kind(state, evidence_id, {"counterfactual_test"}):
        return False
    commands = state["evidence"][evidence_id].get("commands")
    return command_records_valid(commands) and any(record.get("exit_code") != 0 for record in commands)


def live_phase_attempt_for_evidence(
    state: dict[str, Any], evidence_id: Any, phase: str, expected_slice: str | None = None,
) -> tuple[str, dict[str, Any]] | None:
    if not is_nonempty_string(evidence_id):
        return None
    for sid, slice_ in state.get("slices", {}).items():
        if expected_slice is not None and sid != expected_slice:
            continue
        attempts = slice_.get("phase_attempts", []) if isinstance(slice_, dict) else []
        if not isinstance(attempts, list):
            continue
        live_positions = live_passed_attempt_positions(slice_)
        for position, attempt in enumerate(attempts):
            if (
                position in live_positions
                and isinstance(attempt, dict)
                and attempt.get("result") == "passed"
                and attempt.get("phase") == phase
                and attempt.get("gate_evidence") == evidence_id
            ):
                return sid, attempt
    return None


def saturate_verification_attempt(
    state: dict[str, Any], evidence_id: Any, expected_slice: str | None = None,
) -> tuple[str, dict[str, Any]] | None:
    linked = live_phase_attempt_for_evidence(state, evidence_id, "saturate", expected_slice)
    if linked is None:
        return None
    gate = state.get("evidence", {}).get(evidence_id)
    if (
        not isinstance(gate, dict)
        or gate.get("kind") != "phase_gate"
        or gate.get("phase") != "saturate"
        or not successful_command_records(gate.get("commands"))
    ):
        return None
    return linked


def historical_saturate_verification_attempt(
    state: dict[str, Any], evidence_id: Any, expected_slice: str | None = None,
) -> tuple[str, dict[str, Any]] | None:
    if not is_nonempty_string(evidence_id):
        return None
    gate = state.get("evidence", {}).get(evidence_id)
    if (
        not isinstance(gate, dict)
        or gate.get("kind") != "phase_gate"
        or gate.get("phase") != "saturate"
        or not successful_command_records(gate.get("commands"))
    ):
        return None
    for sid, slice_ in state.get("slices", {}).items():
        if expected_slice is not None and sid != expected_slice:
            continue
        attempts = slice_.get("phase_attempts", []) if isinstance(slice_, dict) else []
        if not isinstance(attempts, list):
            continue
        for attempt in attempts:
            if (
                isinstance(attempt, dict)
                and attempt.get("phase") == "saturate"
                and attempt.get("result") == "passed"
                and attempt.get("gate_evidence") == evidence_id
            ):
                return sid, attempt
    return None


def saturate_reuse_is_falsified(
    state: dict[str, Any], evidence_id: Any, expected_slice: str, *, allow_active: bool,
) -> bool:
    linked = saturate_verification_attempt(state, evidence_id, expected_slice)
    if linked is None:
        return False
    _, saturate_attempt = linked
    active = state.get("active_operation")
    if (
        allow_active
        and isinstance(active, dict)
        and active.get("slice") == expected_slice
        and active.get("phase") == "falsify"
        and checkpoints_have_same_workspace(
            state, saturate_attempt.get("post_checkpoint"), active.get("pre_checkpoint")
        )
    ):
        return True
    attempt = passed_falsify_attempt_reusing_saturate(state, evidence_id, expected_slice)
    if attempt is None:
        return False
    return checkpoints_have_same_workspace(
        state, saturate_attempt.get("post_checkpoint"), attempt.get("pre_checkpoint")
    ) and checkpoints_have_same_workspace(
        state, attempt.get("pre_checkpoint"), attempt.get("post_checkpoint")
    )


def passed_falsify_attempt_reusing_saturate(
    state: dict[str, Any], evidence_id: Any, expected_slice: str,
) -> dict[str, Any] | None:
    slice_ = state.get("slices", {}).get(expected_slice)
    attempts = slice_.get("phase_attempts", []) if isinstance(slice_, dict) else []
    if not isinstance(attempts, list):
        return None
    live_positions = live_passed_attempt_positions(slice_)
    for position, attempt in enumerate(attempts):
        if (
            position not in live_positions
            or not isinstance(attempt, dict)
            or attempt.get("phase") != "falsify"
            or attempt.get("result") != "passed"
        ):
            continue
        gate = state.get("evidence", {}).get(attempt.get("gate_evidence"))
        if not isinstance(gate, dict) or gate.get("saturate_evidence") != evidence_id:
            continue
        return attempt
    return None


def passing_verification_evidence(
    state: dict[str, Any], evidence_id: Any, expected_slice: str | None = None,
    *, allow_active_falsify: bool = False,
) -> bool:
    if standalone_passing_verification_evidence(state, evidence_id):
        return True
    minimum = version_tuple(state.get("minimum_validator_version"))
    lean_minimum = version_tuple(LEAN_GATE_MINIMUM_VERSION)
    return (
        minimum is not None
        and lean_minimum is not None
        and minimum >= lean_minimum
        and isinstance(expected_slice, str)
        and saturate_reuse_is_falsified(
            state, evidence_id, expected_slice, allow_active=allow_active_falsify
        )
    )


def checkpoints_have_same_workspace(state: dict[str, Any], left: Any, right: Any) -> bool:
    if not isinstance(left, str) or not isinstance(right, str):
        return False
    left_record = state.get("checkpoints", {}).get(left)
    right_record = state.get("checkpoints", {}).get(right)
    if not isinstance(left_record, dict) or not isinstance(right_record, dict):
        return False
    left_identity = (
        left_record.get("head_sha"), left_record.get("worktree_sha256"),
        left_record.get("content_delta_sha256"), left_record.get("dirty")
    )
    right_identity = (
        right_record.get("head_sha"), right_record.get("worktree_sha256"),
        right_record.get("content_delta_sha256"), right_record.get("dirty")
    )
    if (
        not is_canonical_commit_oid(left_identity[0])
        or not is_sha256(left_identity[1])
        or not is_sha256(left_identity[2])
        or not isinstance(left_identity[3], bool)
        or not is_canonical_commit_oid(right_identity[0])
        or not is_sha256(right_identity[1])
        or not is_sha256(right_identity[2])
        or not isinstance(right_identity[3], bool)
    ):
        return False
    return left_identity == right_identity


def command_records_valid(records: Any) -> bool:
    if not isinstance(records, list) or not records:
        return False
    return all(
        isinstance(item, dict)
        and is_nonempty_string(item.get("command"))
        and is_json_integer(item.get("exit_code"))
        and (is_nonempty_string(item.get("output_digest")) or is_nonempty_string(item.get("artifact")))
        for item in records
    )


def phase_gate_errors(state: dict[str, Any], sid: str, slice_: dict[str, Any], attempt: dict[str, Any]) -> list[str]:
    phase = attempt.get("phase")
    if attempt.get("result") != "passed":
        return []
    evidence_id = attempt.get("gate_evidence")
    if not evidence_exists(state, evidence_id):
        return [f"passed {phase} attempt has no existing gate_evidence"]
    gate = state["evidence"][evidence_id]
    if not isinstance(gate, dict):
        return [f"gate evidence {evidence_id} is not an object"]
    errors: list[str] = []
    if gate.get("kind") != "phase_gate" or gate.get("phase") != phase:
        errors.append(f"gate evidence {evidence_id} is not a {phase} phase_gate")
        return errors
    if gate.get("slice") != sid:
        errors.append(f"gate evidence {evidence_id} does not belong to slice {sid}")
    commands_supplied = "commands" in gate
    commands = gate.get("commands")
    commands_required = phase in {"expose", "saturate"}
    if commands_required and not command_records_valid(commands):
        errors.append(f"{phase.upper()} commands must record command, exit_code, and output digest/artifact")
    elif commands_supplied and not command_records_valid(commands):
        errors.append("optional gate commands, when supplied, must be a non-empty valid command record list")
    elif phase == "expose":
        if not any(record.get("exit_code") != 0 for record in commands):
            errors.append("EXPOSE must record a deliberately failing command")
    elif commands_supplied and any(record.get("exit_code") != 0 for record in commands):
        errors.append(f"passed {phase} gate contains a failing verification command")

    relevant_facts = {
        ffid for ffid, fact in state.get("foreign_facts", {}).items()
        if isinstance(fact, dict)
        and isinstance(fact.get("depended_on_by", []), list)
        and sid in fact.get("depended_on_by", [])
    }
    review_supplied = "foreign_fact_review" in gate
    review = gate.get("foreign_fact_review")
    if not review_supplied:
        if phase == "falsify" and relevant_facts:
            errors.append("FALSIFY must review every foreign fact relied on by the slice")
    elif not isinstance(review, dict) or review.get("performed") is not True:
        errors.append("foreign_fact_review.performed must be true when a review record is supplied")
    else:
        reviewed = review.get("reviewed")
        if (
            not isinstance(reviewed, list)
            or any(not isinstance(item, str) for item in reviewed)
            or set(reviewed) != relevant_facts
        ):
            errors.append("foreign_fact_review.reviewed must exactly match facts relied on by this slice")

    lean_gate = (
        (phase in STRUCTURAL_PHASES and not commands_supplied)
        or (phase == "falsify" and (not commands_supplied or "saturate_evidence" in gate))
        or not review_supplied
    )
    minimum = version_tuple(state.get("minimum_validator_version"))
    lean_minimum = version_tuple(LEAN_GATE_MINIMUM_VERSION)
    if lean_gate and (minimum is None or lean_minimum is None or minimum < lean_minimum):
        errors.append(f"lean phase evidence requires validator {LEAN_GATE_MINIMUM_VERSION} or newer")
    if phase == "expose":
        for key in ("finding_id", "test", "baseline_ref"):
            if not is_nonempty_string(gate.get(key)):
                errors.append(f"EXPOSE gate is missing {key}")
        finding_id = gate.get("finding_id")
        finding = state.get("findings", {}).get(finding_id) if isinstance(finding_id, str) else None
        if not isinstance(finding, dict) or owner_at_revision(finding, attempt.get("starting_revision")) != sid:
            errors.append("EXPOSE finding_id must reference a finding owned by this slice when the attempt began")
        if gate.get("failed_at_assertion") is not True:
            errors.append("EXPOSE must fail at its intended assertion")
        if not is_nonempty_string(gate.get("assertion_fingerprint")):
            errors.append("EXPOSE must identify the intended failing assertion")
        if not isinstance(gate.get("input_space"), list) or not gate["input_space"]:
            errors.append("EXPOSE must record a non-empty input_space")
    elif phase == "represent":
        if gate.get("behavior_changed") is not False:
            errors.append("REPRESENT must explicitly record behavior_changed: false")
        if not isinstance(gate.get("artifacts"), list) or not gate["artifacts"]:
            errors.append("REPRESENT must name representation artifacts")
    elif phase == "route":
        for key in ("producers", "consumers"):
            if not isinstance(gate.get(key), list) or not gate[key]:
                errors.append(f"ROUTE must enumerate {key}")
        if gate.get("predictions_frozen") is not True:
            errors.append("ROUTE must confirm collapse predictions were frozen before routing")
        if gate.get("new_abstraction_introduced") is not False:
            errors.append("ROUTE invented an abstraction; rewind to REPRESENT")
        if not isinstance(gate.get("introduced"), list):
            errors.append("ROUTE must record introduced machinery, using [] when empty")
    elif phase == "collapse":
        raw_predictions = slice_.get("collapse_predictions", {})
        predictions = set(raw_predictions) if isinstance(raw_predictions, dict) else set()
        verdicts = gate.get("prediction_verdicts")
        if not isinstance(verdicts, dict) or set(verdicts) != predictions:
            errors.append("COLLAPSE must give exactly one verdict for every frozen prediction")
        elif any(
            not is_enum(v, {"died", "surviving_owned_mechanism", "surviving_foreign_mechanism"})
            for v in verdicts.values()
        ):
            errors.append("COLLAPSE contains a surviving inference or unknown prediction verdict")
        if not gate.get("died") and not is_nonempty_string(gate.get("no_death_expected")):
            errors.append("COLLAPSE must record structural death or an explicit no-death reason")
    elif phase == "saturate":
        if not isinstance(gate.get("structural_tests"), list) or not gate["structural_tests"]:
            errors.append("SATURATE must record structural tests")
        raw_obligations = slice_.get("operational_obligations", [])
        obligations = (
            set(raw_obligations)
            if isinstance(raw_obligations, list)
            and all(isinstance(obligation, str) for obligation in raw_obligations)
            else set()
        )
        proofs = gate.get("operational_proofs")
        if not isinstance(proofs, dict) or set(proofs) != obligations:
            errors.append("SATURATE must discharge exactly every operational obligation")
        elif any(not evidence_exists(state, proof) for proof in proofs.values()):
            errors.append("SATURATE operational proofs must reference existing evidence")
        if gate.get("input_space_covered") is not True:
            errors.append("SATURATE must cover the EXPOSE input space")
    elif phase == "falsify":
        saturate_supplied = "saturate_evidence" in gate
        saturate_evidence = gate.get("saturate_evidence")
        gate_validator = version_tuple(gate.get("validator_version"))
        if saturate_supplied:
            linked = saturate_verification_attempt(state, saturate_evidence, sid)
            if linked is None:
                errors.append("FALSIFY saturate_evidence must name this slice's current live SATURATE gate")
            else:
                _, saturate_attempt = linked
                if not checkpoints_have_same_workspace(
                    state, saturate_attempt.get("post_checkpoint"), attempt.get("pre_checkpoint")
                ):
                    errors.append("workspace changed between SATURATE verification and FALSIFY")
                if not checkpoints_have_same_workspace(
                    state, attempt.get("pre_checkpoint"), attempt.get("post_checkpoint")
                ):
                    errors.append("FALSIFY did not restore the SATURATE-verified workspace")
        if (
            gate_validator is not None
            and lean_minimum is not None
            and gate_validator >= lean_minimum
            and not saturate_supplied
        ):
            errors.append("FALSIFY under validator 2.2 requires reusable SATURATE evidence; rewind and rerun SATURATE after workspace changes")
        elif not commands_supplied and not saturate_supplied:
            errors.append("FALSIFY requires successful commands or reusable SATURATE evidence")
        owners = {
            fid for fid, finding in state.get("findings", {}).items()
            if isinstance(finding, dict)
            and isinstance(finding.get("ownership", []), list)
            and any(event.get("to") == sid for event in finding.get("ownership", []) if isinstance(event, dict))
        }
        verdicts = gate.get("finding_verdicts")
        if not isinstance(verdicts, dict) or set(verdicts) != owners:
            errors.append("FALSIFY must give exactly one verdict for every finding ever assigned to the slice")
        else:
            for fid, claim in verdicts.items():
                verdict = claim.get("verdict") if isinstance(claim, dict) else claim
                if not is_enum(verdict, {"fixed", "split", "moved", "superseded"}):
                    errors.append(f"FALSIFY includes an unfinished or unknown verdict for {fid}")
                    continue
                finding = state["findings"][fid]
                resolution_record = finding.get("resolution")
                resolution = resolution_record.get("kind") if isinstance(resolution_record, dict) else None
                if verdict in {"fixed", "split", "superseded"} and resolution != verdict:
                    errors.append(f"FALSIFY calls {fid} {verdict} but its typed resolution is {resolution!r}")
                if verdict == "moved":
                    events = finding.get("ownership", [])
                    latest_candidate = events[-1] if isinstance(events, list) and events else None
                    latest = latest_candidate if isinstance(latest_candidate, dict) else {}
                    if current_owner(finding) == sid:
                        errors.append(f"FALSIFY calls {fid} moved but {sid} still owns it")
                    if (
                        not isinstance(claim, dict)
                        or claim.get("ownership_revision") != latest.get("revision")
                        or not is_json_integer(latest.get("revision"), 0)
                        or not is_json_integer(attempt.get("starting_revision"), 0)
                        or latest.get("revision") <= attempt.get("starting_revision")
                        or latest.get("from") != sid
                        or latest.get("to") == sid
                    ):
                        errors.append(
                            f"FALSIFY moved verdict for {fid} must cite the latest ownership departure revision"
                        )
        if not isinstance(gate.get("rescan"), dict) or gate["rescan"].get("performed") is not True:
            errors.append("FALSIFY must record a performed slice rescan")
    return errors


def validate_state(state: Any) -> list[tuple[str, str]]:
    errors: list[tuple[str, str]] = []
    if not isinstance(state, dict):
        add_error(errors, "V001", "tracker root must be an object")
        return errors
    reject_unknown_keys(errors, "V001", "tracker", state, {
        "schema", "schema_version", "minimum_validator_version", "revision", "semantic_revision", "source", "policy", "run",
        "active_operation", "checkpoints", "evidence", "dependencies", "decisions", "foreign_facts", "findings",
        "slices", "fixed_point", "github", "migration",
    })
    if state.get("schema") != SCHEMA or state.get("schema_version") != SCHEMA_VERSION:
        add_error(errors, "V001", f"expected {SCHEMA} schema_version {SCHEMA_VERSION}")
        return errors
    if not is_json_integer(state.get("revision"), 0):
        add_error(errors, "V001", "revision must be a non-negative integer")
    if not is_json_integer(state.get("semantic_revision"), 0):
        add_error(errors, "V001", "semantic_revision must be a non-negative integer")
    minimum = version_tuple(state.get("minimum_validator_version"))
    if minimum is None or minimum > version_tuple(VERSION):  # type: ignore[operator]
        add_error(errors, "V001", f"tracker requires unsupported validator {state.get('minimum_validator_version')!r}")
    run = state.get("run")
    if not isinstance(run, dict) or not is_enum(run.get("state"), RUN_STATES):
        add_error(errors, "V001", f"run.state must be one of {sorted(RUN_STATES)}")
    else:
        reject_unknown_keys(errors, "V001", "run", run, {
            "id", "state", "created_at", "updated_at", "baseline", "terminal_reason",
        })
        if any(not is_nonempty_string(run.get(key)) for key in ("id", "created_at", "updated_at")):
            add_error(errors, "V001", "run must have non-empty id, created_at, and updated_at")
        baseline = run.get("baseline")
        if baseline is not None:
            if not isinstance(baseline, dict) or not isinstance(baseline.get("usable"), bool):
                add_error(errors, "V001", "run.baseline must state usable as a boolean")
            elif baseline.get("usable") is True and not successful_command_records(baseline.get("commands")):
                add_error(errors, "V001", "a usable baseline requires successful command evidence")
    source = state.get("source")
    if not isinstance(source, dict):
        add_error(errors, "V001", "source must be an object")
    else:
        reject_unknown_keys(errors, "V001", "source", source, {
            "repository", "pr", "base_sha", "starting_head_sha", "branch", "root", "audit_dir", "tracker_path",
            "policy_inputs",
        })
        if any(
            not is_nonempty_string(source.get(key))
            for key in ("repository", "base_sha", "starting_head_sha", "branch", "root", "audit_dir", "tracker_path")
        ):
            add_error(errors, "V001", "source must pin repository, base/head, branch, root, audit_dir, and tracker_path")
        for key in ("base_sha", "starting_head_sha"):
            if not is_canonical_commit_oid(source.get(key)):
                add_error(errors, "V001", f"source.{key} must be a canonical full commit object ID")
        pr = source.get("pr")
        if isinstance(pr, str):
            if not valid_pr_selector(pr):
                add_error(errors, "V001", "source.pr contains an unsafe or unsupported PR selector")
        elif pr is not None:
            if not isinstance(pr, dict):
                add_error(errors, "V001", "source.pr must be null, a safe selector, or resolved PR metadata")
            else:
                selectors = [
                    value for key, value in (("selector", pr.get("selector")), ("url", pr.get("url")))
                    if value is not None
                ]
                number = pr.get("number")
                if number is not None:
                    if not isinstance(number, int) or isinstance(number, bool) or number < 1:
                        add_error(errors, "V001", "source.pr.number must be a positive integer")
                    else:
                        selectors.append(str(number))
                if not selectors or any(not valid_pr_selector(selector) for selector in selectors):
                    add_error(errors, "V001", "source.pr metadata contains an unsafe or unsupported selector")
                for pr_key in ("baseRefOid", "headRefOid"):
                    if pr.get(pr_key) is not None and not is_canonical_commit_oid(pr.get(pr_key)):
                        add_error(errors, "V001", f"source.pr.{pr_key} must be a canonical commit object ID")
        audit_dir = source.get("audit_dir")
        if is_nonempty_string(audit_dir):
            audit_path = Path(audit_dir)
            if audit_path.is_absolute() or audit_dir in {".", ".."} or ".." in audit_path.parts:
                add_error(errors, "V001", "source.audit_dir must be a non-root relative directory")
        tracker_path = source.get("tracker_path")
        if is_nonempty_string(tracker_path):
            tracker = Path(tracker_path)
            if (
                tracker.is_absolute()
                or tracker_path in {".", ".."}
                or ".." in tracker.parts
                or tracker.parent.as_posix() != audit_dir
            ):
                add_error(errors, "V001", "source.tracker_path must be a file directly inside source.audit_dir")
        policy_inputs = source.get("policy_inputs")
        if not isinstance(policy_inputs, list):
            add_error(errors, "V001", "source.policy_inputs must be a provenance list")
        else:
            for item in policy_inputs:
                if not isinstance(item, dict) or not is_nonempty_string(item.get("path")) or not isinstance(item.get("changed_by_target"), bool):
                    add_error(errors, "V001", "source.policy_inputs contains a malformed record")
                    continue
                reject_unknown_keys(errors, "V001", f"policy input {item['path']}", item, {
                    "path", "base_sha256", "head_sha256", "changed_by_target",
                })
    policy = state.get("policy")
    if not isinstance(policy, dict):
        add_error(errors, "V001", "policy must be an object")
    else:
        reject_unknown_keys(errors, "V001", "policy", policy, {
            "higher_risk", "github_projection", "push", "max_fixed_point_passes", "max_phase_attempts",
        })
        if policy.get("higher_risk") != "requires_human_approval" or policy.get("push") != "never":
            add_error(errors, "V001", "policy may not silently broaden higher-risk or push authority")
        if not is_enum(policy.get("github_projection"), {"off", "outbox", "sync"}):
            add_error(errors, "V001", "policy.github_projection must be off, outbox, or sync")
        for key in ("max_fixed_point_passes", "max_phase_attempts"):
            if not is_json_integer(policy.get(key), 1):
                add_error(errors, "V001", f"policy.{key} must be a positive integer")
    for key in ("checkpoints", "evidence", "dependencies", "decisions", "foreign_facts", "findings", "slices"):
        if not isinstance(state.get(key), dict):
            add_error(errors, "V001", f"{key} must be a mapping keyed by stable IDs")
    fixed_point = state.get("fixed_point")
    if not isinstance(fixed_point, dict) or not isinstance(fixed_point.get("passes"), list):
        add_error(errors, "V001", "fixed_point.passes must be a list")
    else:
        reject_unknown_keys(errors, "V001", "fixed_point", fixed_point, {"passes"})
    github = state.get("github")
    if not isinstance(github, dict) or not isinstance(github.get("mappings"), dict) or not isinstance(github.get("outbox"), list):
        add_error(errors, "V001", "github must contain mappings and outbox")
    else:
        reject_unknown_keys(errors, "V001", "github", github, {"mappings", "outbox"})
        if isinstance(policy, dict) and policy.get("github_projection") == "off" and github.get("outbox"):
            add_error(errors, "V008", "GitHub projection is off but its outbox is not empty")
    if errors:
        return errors

    slices = state["slices"]
    findings = state["findings"]
    dependencies = state["dependencies"]
    decisions = state["decisions"]
    evidence = state["evidence"]

    for checkpoint_id, value in state["checkpoints"].items():
        if not checkpoint_id.startswith("CP-") or not isinstance(value, dict):
            add_error(errors, "V001", f"checkpoint {checkpoint_id!r} must be a CP-* object")
    for evidence_id, value in evidence.items():
        if not evidence_id.startswith("E-") or not isinstance(value, dict):
            add_error(errors, "V001", f"evidence {evidence_id!r} must be an E-* object")
            continue
        if value.get("kind") == "phase_gate":
            phase = value.get("phase")
            common = {
                "kind", "phase", "slice", "commands", "foreign_fact_review", "validator_version", "recorded_at",
                "notes", "blocked", "failure",
            }
            specific = {
                "expose": {"finding_id", "test", "baseline_ref", "failed_at_assertion", "assertion_fingerprint", "input_space"},
                "represent": {"behavior_changed", "artifacts"},
                "route": {"producers", "consumers", "predictions_frozen", "new_abstraction_introduced", "introduced"},
                "collapse": {"prediction_verdicts", "died", "no_death_expected"},
                "saturate": {"structural_tests", "operational_proofs", "input_space_covered"},
                "falsify": {"finding_verdicts", "rescan", "saturate_evidence"},
            }
            if not is_enum(phase, PHASES):
                add_error(errors, "V005", f"phase gate {evidence_id} has invalid phase {phase!r}")
            if not isinstance(value.get("slice"), str) or value.get("slice") not in slices:
                add_error(errors, "V005", f"phase gate {evidence_id} references no existing slice")
            if phase == "expose" and (
                not isinstance(value.get("finding_id"), str) or value.get("finding_id") not in findings
            ):
                add_error(errors, "V005", f"EXPOSE gate {evidence_id} references no existing finding")
            if (
                "saturate_evidence" in value
                and (minimum is None or minimum < version_tuple(LEAN_GATE_MINIMUM_VERSION))  # type: ignore[operator]
            ):
                add_error(
                    errors, "V005",
                    f"phase gate {evidence_id} uses SATURATE reuse below validator {LEAN_GATE_MINIMUM_VERSION}",
                )
            gate_validator = version_tuple(value.get("validator_version"))
            if "validator_version" in value and (
                gate_validator is None
                or minimum is None
                or gate_validator > version_tuple(VERSION)  # type: ignore[operator]
                or minimum < gate_validator
            ):
                add_error(errors, "V005", f"phase gate {evidence_id} has an unsupported validator_version")
            phase_fields = specific.get(phase, set()) if isinstance(phase, str) else set()
            reject_unknown_keys(errors, "V005", f"phase gate {evidence_id}", value, common | phase_fields)

    # Slice and dependency references, including cycles.
    graph: dict[str, list[str]] = {}
    for sid, slice_ in slices.items():
        if not sid.startswith("S-") or not isinstance(slice_, dict):
            add_error(errors, "V001", f"slice key {sid!r} must be S-* and map to an object")
            continue
        reject_unknown_keys(errors, "V001", f"slice {sid}", slice_, {
            "name", "kind", "merge_policy", "boundary", "depends_on", "collapse_predictions",
            "operational_obligations", "phase_attempts", "deliveries", "created_at", "legacy", "notes",
        })
        if not is_nonempty_string(slice_.get("name")):
            add_error(errors, "V001", f"slice {sid} must have a name")
        if not is_enum(slice_.get("kind"), SLICE_KINDS) or not is_enum(slice_.get("merge_policy"), MERGE_POLICIES):
            add_error(errors, "V001", f"slice {sid} has invalid kind or merge_policy")
        boundary = slice_.get("boundary")
        if not isinstance(boundary, dict) or any(not is_nonempty_string(boundary.get(k)) for k in ("authority", "fact", "consumer_decision")):
            add_error(errors, "V001", f"slice {sid} must state authority, fact, and consumer_decision")
        deps = slice_.get("depends_on", [])
        if (
            not isinstance(deps, list)
            or any(not isinstance(dep, str) for dep in deps)
            or len(deps) != len(set(deps))
        ):
            add_error(errors, "V002", f"slice {sid} dependencies must be a unique list")
            deps = []
        for dep in deps:
            if dep not in slices:
                add_error(errors, "V002", f"slice {sid} depends on missing slice {dep}")
            if dep == sid:
                add_error(errors, "V002", f"slice {sid} depends on itself")
        graph[sid] = list(deps)
        predictions = slice_.get("collapse_predictions")
        if not isinstance(predictions, dict) or any(
            not is_nonempty_string(key) or not is_nonempty_string(value) for key, value in predictions.items()
        ):
            add_error(errors, "V001", f"slice {sid}.collapse_predictions must map stable labels to claims")
        obligations = slice_.get("operational_obligations")
        if (
            not isinstance(obligations, list)
            or any(not is_nonempty_string(item) for item in obligations)
            or len(obligations) != len(set(obligations))
        ):
            add_error(errors, "V001", f"slice {sid}.operational_obligations must be a unique string list")
        progress = slice_progress(slice_)
        for message in progress["errors"]:
            add_error(errors, "V005", f"slice {sid}: {message}")
        attempts = slice_.get("phase_attempts", [])
        if not isinstance(attempts, list):
            add_error(errors, "V001", f"slice {sid}.phase_attempts must be a list")
            attempts = []
        live_attempts = live_passed_attempt_positions(slice_)
        for attempt_position, attempt in enumerate(attempts):
            if not isinstance(attempt, dict) or not is_enum(attempt.get("result"), ATTEMPT_RESULTS):
                add_error(errors, "V001", f"slice {sid} has a malformed phase attempt")
                continue
            reject_unknown_keys(errors, "V001", f"slice {sid} phase attempt", attempt, {
                "phase", "result", "pre_checkpoint", "post_checkpoint", "gate_evidence",
                "started_at", "finished_at", "starting_revision", "rewind_to", "reason",
            })
            if attempt.get("result") != "rewound" and (
                not is_json_integer(attempt.get("starting_revision"), 0)
            ):
                add_error(errors, "V005", f"slice {sid} attempt lacks a valid starting revision")
            for checkpoint_key in ("pre_checkpoint", "post_checkpoint"):
                checkpoint = attempt.get(checkpoint_key)
                if attempt.get("result") != "rewound" and (
                    not isinstance(checkpoint, str) or checkpoint not in state["checkpoints"]
                ):
                    add_error(errors, "V002", f"slice {sid} attempt references missing {checkpoint_key} {checkpoint}")
            if attempt.get("result") == "rewound":
                if not evidence_exists(state, attempt.get("gate_evidence")) or not is_nonempty_string(attempt.get("reason")):
                    add_error(errors, "V005", f"slice {sid} rewind requires evidence and a reason")
            elif not evidence_exists(state, attempt.get("gate_evidence")):
                add_error(errors, "V005", f"slice {sid} attempt has no evidence record")
            if attempt.get("result") == "blocked":
                gate_id = attempt.get("gate_evidence")
                gate = evidence.get(gate_id, {}) if isinstance(gate_id, str) else {}
                blocked = gate.get("blocked") if isinstance(gate, dict) else None
                if (
                    not isinstance(blocked, dict)
                    or not isinstance(blocked.get("dependency"), str)
                    or blocked.get("dependency") not in dependencies
                    or not is_nonempty_string(blocked.get("owner"))
                ):
                    add_error(errors, "V005", f"slice {sid} blocked attempt lacks a dependency and owner")
            if attempt.get("result") != "passed" or attempt_position in live_attempts:
                for message in phase_gate_errors(state, sid, slice_, attempt):
                    add_error(errors, "V005", f"slice {sid} {attempt.get('phase')}: {message}")
            if (
                attempt.get("result") == "passed"
                and attempt.get("phase") == "route"
                and attempt_position in live_attempts
            ):
                gate_id = attempt.get("gate_evidence")
                gate = evidence.get(gate_id, {}) if isinstance(gate_id, str) else {}
                if not isinstance(gate, dict):
                    gate = {}
                for item in gate.get("introduced", []) if isinstance(gate.get("introduced"), list) else []:
                    risk = item.get("risk") if isinstance(item, dict) else None
                    comparison = risk.get("comparison") if isinstance(risk, dict) else None
                    if not is_enum(comparison, RISK_COMPARISONS):
                        add_error(errors, "V006", f"slice {sid} introduced item has invalid risk comparison")
                    elif comparison == "unknown":
                        add_error(errors, "V006", f"slice {sid} passed ROUTE with unknown introduced risk")
                    elif comparison == "higher":
                        approval = risk.get("human_approval")
                        mitigation = risk.get("mitigation")
                        if not evidence_has_kind(state, approval, {"human_approval"}) and not (
                            isinstance(mitigation, dict)
                            and mitigation.get("residual_comparison") in {"lower", "equivalent"}
                            and evidence_exists(state, mitigation.get("evidence"))
                        ):
                            add_error(errors, "V006", f"slice {sid} passed ROUTE with unapproved higher risk")
        deliveries = slice_.get("deliveries", [])
        if not isinstance(deliveries, list):
            add_error(errors, "V001", f"slice {sid}.deliveries must be a list")
        else:
            for delivery in deliveries:
                if not isinstance(delivery, dict) or not is_enum(delivery.get("kind"), DELIVERY_KINDS):
                    add_error(errors, "V001", f"slice {sid} has malformed delivery evidence")
                    continue
                reject_unknown_keys(errors, "V001", f"slice {sid} delivery", delivery, {
                    "kind", "evidence", "recorded_at", "semantic_revision", "sha", "tree", "subject", "reason",
                    "verified_checkpoint", "content_delta_sha256",
                })
                delivery_is_fresh = delivery.get("semantic_revision") == state.get("semantic_revision")
                delivery_evidence_valid = passing_verification_evidence(
                    state, delivery.get("evidence"), sid
                )
                if not delivery_is_fresh and not delivery_evidence_valid:
                    delivery_evidence_valid = (
                        standalone_passing_verification_evidence(state, delivery.get("evidence"))
                        or (
                            minimum is not None
                            and minimum >= version_tuple(LEAN_GATE_MINIMUM_VERSION)  # type: ignore[operator]
                            and historical_saturate_verification_attempt(
                                state, delivery.get("evidence"), sid
                            ) is not None
                        )
                    )
                if not delivery_evidence_valid:
                    add_error(
                        errors,
                        "V008",
                        f"slice {sid} delivery requires passing test/verification or FALSIFY-linked SATURATE evidence",
                    )
                evidence_record = evidence.get(delivery.get("evidence"))
                if isinstance(evidence_record, dict) and evidence_record.get("phase") == "saturate":
                    if delivery.get("kind") != "commit":
                        add_error(errors, "V008", f"slice {sid} SATURATE evidence cannot attest no_code_change")
                    verified_checkpoint = delivery.get("verified_checkpoint")
                    checkpoint_record = state["checkpoints"].get(verified_checkpoint)
                    if (
                        not isinstance(checkpoint_record, dict)
                        or not is_sha256(checkpoint_record.get("content_delta_sha256"))
                        or delivery.get("content_delta_sha256")
                        != checkpoint_record.get("content_delta_sha256")
                    ):
                        add_error(errors, "V008", f"slice {sid} SATURATE delivery lacks its verified content fingerprint")
                if (
                    not is_json_integer(delivery.get("semantic_revision"), 0)
                    or delivery["semantic_revision"] > state["semantic_revision"]
                ):
                    add_error(errors, "V008", f"slice {sid} delivery lacks a valid semantic revision")
                if delivery["kind"] == "commit" and (
                    not is_canonical_commit_oid(delivery.get("sha"))
                    or not is_canonical_commit_oid(delivery.get("tree"))
                    or not is_nonempty_string(delivery.get("subject"))
                ):
                    add_error(errors, "V008", f"slice {sid} commit delivery is incomplete or non-canonical")
                if delivery["kind"] == "no_code_change" and not is_nonempty_string(delivery.get("reason")):
                    add_error(errors, "V008", f"slice {sid} no-code delivery lacks a reason")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(node: str, path: list[str]) -> None:
        if node in visiting:
            add_error(errors, "V002", "slice dependency cycle: " + " -> ".join(path + [node]))
            return
        if node in visited:
            return
        visiting.add(node)
        for child in graph.get(node, []):
            if child in graph:
                visit(child, path + [node])
        visiting.remove(node)
        visited.add(node)

    for sid in graph:
        visit(sid, [])

    # Finding ownership and resolutions.
    split_edges: dict[str, list[str]] = {}
    for fid, finding in findings.items():
        if not fid.startswith("F-") or not isinstance(finding, dict):
            add_error(errors, "V001", f"finding key {fid!r} must be F-* and map to an object")
            continue
        reject_unknown_keys(errors, "V001", f"finding {fid}", finding, {
            "title", "site", "severity", "merge_blocking", "found_by", "missing_fact",
            "fix_direction", "origin", "ownership", "unassigned_reason", "resolution",
            "resolution_history", "created_at", "legacy", "notes",
        })
        if any(not is_nonempty_string(finding.get(key)) for key in ("title", "site", "severity", "found_by", "fix_direction")):
            add_error(errors, "V001", f"finding {fid} lacks title, site, severity, found_by, or fix_direction")
        if not isinstance(finding.get("merge_blocking"), bool):
            add_error(errors, "V001", f"finding {fid}.merge_blocking must be boolean")
        missing = finding.get("missing_fact")
        if not isinstance(missing, dict) or any(
            not is_nonempty_string(missing.get(k)) for k in ("authority", "fact", "consumer_decision", "inferred_from")
        ):
            add_error(errors, "V001", f"finding {fid} has no complete information-flow edge")
        elif not is_enum(missing.get("initial_shape"), FINDING_SHAPES) or not is_enum(missing.get("normalized_as"), NORMALIZED_KINDS):
            add_error(errors, "V001", f"finding {fid} has invalid initial_shape or normalized_as")
        ownership = finding.get("ownership", [])
        if not isinstance(ownership, list):
            add_error(errors, "V003", f"finding {fid}.ownership must be a list")
            ownership = []
        expected_from = None
        previous_revision = -1
        for position, event in enumerate(ownership, 1):
            if not isinstance(event, dict) or event.get("from") != expected_from:
                add_error(errors, "V003", f"finding {fid} ownership event {position} breaks the chain")
                continue
            reject_unknown_keys(errors, "V003", f"finding {fid} ownership event {position}", event, {
                "from", "to", "at", "reason", "k_verification", "revision",
            })
            event_revision = event.get("revision")
            if (
                not is_json_integer(event_revision, 0) or event_revision > state["revision"]
                or event_revision <= previous_revision
            ):
                add_error(errors, "V003", f"finding {fid} ownership event {position} has a stale or non-monotonic revision")
            else:
                previous_revision = event_revision
            target = event.get("to")
            if target is not None and (not isinstance(target, str) or target not in slices):
                add_error(errors, "V003", f"finding {fid} ownership event {position} targets missing slice {target}")
            if target is not None and not evidence_has_kind(state, event.get("k_verification"), {"code_read"}):
                add_error(errors, "V003", f"finding {fid} ownership event {position} lacks code-read K evidence")
            expected_from = target
        if not ownership and not is_nonempty_string(finding.get("unassigned_reason")):
            add_error(errors, "V003", f"finding {fid} is unowned with no unassigned_reason")
        resolution = finding.get("resolution")
        if resolution is not None:
            if not isinstance(resolution, dict) or not is_enum(resolution.get("kind"), RESOLUTION_KINDS):
                add_error(errors, "V001", f"finding {fid} has invalid resolution")
            else:
                kind = resolution["kind"]
                allowed_resolution = {
                    "kind", "at", "passing_test", "counterfactual_test", "remainders",
                    "fixed_scope", "evidence", "blocked", "notes",
                }
                reject_unknown_keys(errors, "V004", f"finding {fid} resolution", resolution, allowed_resolution)
                if kind == "fixed":
                    if not passing_verification_evidence(
                        state, resolution.get("passing_test"), current_owner(finding),
                        allow_active_falsify=True,
                    ) or not counterfactual_verification_evidence(state, resolution.get("counterfactual_test")):
                        add_error(errors, "V004", f"finding {fid} fixed without passing and counterfactual evidence")
                elif kind == "split":
                    children = resolution.get("remainders")
                    if (
                        not is_nonempty_string(resolution.get("fixed_scope"))
                        or not passing_verification_evidence(
                            state, resolution.get("passing_test"), current_owner(finding),
                            allow_active_falsify=True,
                        )
                        or not counterfactual_verification_evidence(state, resolution.get("counterfactual_test"))
                    ):
                        add_error(errors, "V004", f"finding {fid} split lacks fixed-scope and counterfactual proof")
                    if not isinstance(children, list) or not children:
                        add_error(errors, "V004", f"finding {fid} split without remainder IDs")
                        children = []
                    elif any(not isinstance(child, str) for child in children):
                        add_error(errors, "V004", f"finding {fid} split remainder IDs must be strings")
                        children = []
                    elif len(children) != len(set(children)):
                        add_error(errors, "V004", f"finding {fid} split remainder IDs must be unique")
                    split_edges[fid] = children
                    for child in children:
                        if child == fid or child not in findings:
                            add_error(errors, "V004", f"finding {fid} split points to invalid remainder {child}")
                        elif (findings[child].get("origin") or {}).get("parent") != fid:
                            add_error(errors, "V004", f"finding {child} does not point back to split parent {fid}")
                        elif finding.get("merge_blocking") is True and findings[child].get("merge_blocking") is not True:
                            add_error(errors, "V004", f"merge-blocking finding {fid} has non-blocking split remainder {child}")
                elif kind == "superseded":
                    if not evidence_exists(state, resolution.get("evidence")):
                        add_error(errors, "V004", f"finding {fid} superseded without evidence")
                else:
                    blocked = resolution.get("blocked")
                    if (
                        not isinstance(blocked, dict)
                        or not isinstance(blocked.get("dependency"), str)
                        or blocked.get("dependency") not in dependencies
                    ):
                        add_error(errors, "V004", f"finding {fid} blocked without a named dependency")
                    elif not evidence_exists(state, blocked.get("attempt_evidence")) or not is_nonempty_string(blocked.get("owner")):
                        add_error(errors, "V004", f"finding {fid} blocked without attempted evidence and owner")

        history = finding.get("resolution_history", [])
        if not isinstance(history, list):
            add_error(errors, "V004", f"finding {fid}.resolution_history must be a list")
        else:
            for previous in history:
                if not isinstance(previous, dict) or not is_enum(previous.get("kind"), RESOLUTION_KINDS):
                    add_error(errors, "V004", f"finding {fid} has invalid reopened-resolution history")
                    continue
                if not evidence_exists(state, previous.get("reopen_evidence")) or not is_nonempty_string(previous.get("reopened_at")):
                    add_error(errors, "V004", f"finding {fid} resolution history lacks reopen evidence")
                if previous.get("kind") in {"fixed", "split"}:
                    verification_slice = previous.get("verification_slice")
                    if (
                        minimum is None
                        or minimum < version_tuple(LEAN_GATE_MINIMUM_VERSION)  # type: ignore[operator]
                        or not isinstance(verification_slice, str)
                        or verification_slice not in slices
                    ):
                        add_error(errors, "V004", f"finding {fid} historical repair requires validator 2.2 ownership metadata")
                    elif (
                        not (
                            standalone_passing_verification_evidence(state, previous.get("passing_test"))
                            or historical_saturate_verification_attempt(
                                state, previous.get("passing_test"), verification_slice
                            ) is not None
                        )
                        or not counterfactual_verification_evidence(
                            state, previous.get("counterfactual_test")
                        )
                    ):
                        add_error(errors, "V004", f"finding {fid} historical repair lacks typed proof")

        origin = finding.get("origin")
        if origin is not None:
            if (
                not isinstance(origin, dict)
                or not isinstance(origin.get("parent"), str)
                or origin.get("parent") not in findings
                or not is_nonempty_string(origin.get("scope"))
            ):
                add_error(errors, "V004", f"finding {fid} has an invalid split origin")
            else:
                parent = findings[origin["parent"]]
                parent_resolution = parent.get("resolution") if isinstance(parent, dict) else None
                parent_history = parent.get("resolution_history", []) if isinstance(parent, dict) else []
                candidates = [parent_resolution] + (
                    parent_history if isinstance(parent_history, list) else []
                )
                reciprocal = any(
                    isinstance(candidate, dict)
                    and candidate.get("kind") == "split"
                    and isinstance(candidate.get("remainders"), list)
                    and fid in candidate["remainders"]
                    for candidate in candidates
                )
                if not reciprocal:
                    add_error(errors, "V004", f"finding {fid} points to parent {origin['parent']} without reciprocal split")

    def visit_split(node: str, trail: list[str]) -> None:
        if node in trail:
            add_error(errors, "V004", "split lineage cycle: " + " -> ".join(trail + [node]))
            return
        for child in split_edges.get(node, []):
            visit_split(child, trail + [node])

    for fid in split_edges:
        visit_split(fid, [])

    # Dependency and foreign-fact evidence.
    for did, dependency in dependencies.items():
        if (
            not did.startswith("D-") or not isinstance(dependency, dict)
            or not is_enum(dependency.get("kind"), DEPENDENCY_KINDS)
            or not is_enum(dependency.get("status"), DEPENDENCY_STATUSES)
        ):
            add_error(errors, "V001", f"dependency {did} has invalid ID or kind")
            continue
        reject_unknown_keys(errors, "V001", f"dependency {did}", dependency, {
            "kind", "locator", "owner", "description", "status", "created_at", "notes",
            "resolution_evidence", "resolved_at",
        })
        if not is_nonempty_string(dependency.get("locator")):
            add_error(errors, "V002", f"dependency {did} has no durable locator")
        if dependency.get("status") == "resolved" and not evidence_exists(state, dependency.get("resolution_evidence")):
            add_error(errors, "V002", f"resolved dependency {did} lacks evidence")
    for qid, decision in decisions.items():
        if not qid.startswith("Q-") or not isinstance(decision, dict) or not is_enum(decision.get("status"), DECISION_STATUSES):
            add_error(errors, "V001", f"decision {qid} has invalid ID or status")
            continue
        reject_unknown_keys(errors, "V001", f"decision {qid}", decision, {
            "slice", "finding", "question", "alternatives", "blast_radius", "owner", "evidence",
            "quarantined_commit", "status", "resolution", "resolution_evidence", "created_at", "resolved_at",
        })
        if not isinstance(decision.get("slice"), str) or decision.get("slice") not in slices:
            add_error(errors, "V002", f"decision {qid} references missing slice {decision.get('slice')}")
        if decision.get("finding") is not None and (
            not isinstance(decision.get("finding"), str) or decision.get("finding") not in findings
        ):
            add_error(errors, "V002", f"decision {qid} references missing finding {decision.get('finding')}")
        if any(not is_nonempty_string(decision.get(key)) for key in ("question", "blast_radius", "owner")):
            add_error(errors, "V002", f"decision {qid} lacks question, blast radius, or owner")
        if not isinstance(decision.get("alternatives"), list) or len(decision["alternatives"]) < 2:
            add_error(errors, "V002", f"decision {qid} must give at least two alternatives")
        refs = decision.get("evidence")
        if not isinstance(refs, list) or not refs or any(not evidence_exists(state, ref) for ref in refs):
            add_error(errors, "V002", f"decision {qid} requires existing evidence")
        if decision.get("status") == "resolved" and (
            not is_nonempty_string(decision.get("resolution"))
            or not evidence_has_kind(state, decision.get("resolution_evidence"), {"human_approval"})
        ):
            add_error(errors, "V002", f"resolved decision {qid} lacks resolution evidence")
    for ffid, fact in state["foreign_facts"].items():
        disposition = fact.get("disposition") if isinstance(fact, dict) else None
        if not ffid.startswith("FF-") or not isinstance(disposition, dict) or not is_enum(disposition.get("kind"), FACT_DISPOSITIONS):
            add_error(errors, "V001", f"foreign fact {ffid} has invalid ID or disposition")
            continue
        reject_unknown_keys(errors, "V001", f"foreign fact {ffid}", fact, {
            "subject", "claim", "consequence_if_wrong", "disposition", "depended_on_by",
            "revalidation_trigger", "legacy_evidence", "migration_evidence", "notes",
        })
        subject = fact.get("subject")
        if not isinstance(subject, dict) or not is_nonempty_string(subject.get("symbol")) or not is_nonempty_string(subject.get("version")):
            add_error(errors, "V007", f"foreign fact {ffid} is not symbol/version bound")
        if not is_nonempty_string(fact.get("claim")) or not is_nonempty_string(fact.get("consequence_if_wrong")):
            add_error(errors, "V007", f"foreign fact {ffid} lacks claim or consequence")
        fact_evidence_kinds = {
            "measured": {"measurement", "test"},
            "documented": {"documentation", "contract"},
            "enforced": {"invariant", "test", "verification"},
            "eliminated": {"invariant", "test", "verification", "code_read"},
        }
        if disposition.get("kind") != "assumed" and not evidence_has_kind(
            state, disposition.get("evidence"), fact_evidence_kinds[disposition["kind"]]
        ):
            add_error(errors, "V007", f"foreign fact {ffid} has no correctly typed disposition evidence")
        depended = fact.get("depended_on_by", [])
        if not isinstance(depended, list) or any(not isinstance(sid, str) or sid not in slices for sid in depended):
            add_error(errors, "V007", f"foreign fact {ffid}.depended_on_by must reference existing slices")

    for position, record in enumerate(state["fixed_point"]["passes"], 1):
        if not isinstance(record, dict):
            add_error(errors, "V001", f"fixed-point pass {position} must be an object")
            continue
        reject_unknown_keys(errors, "V001", f"fixed-point pass {position}", record, {
            "number", "new_merge_blocking_findings", "evidence", "commands", "at", "notes",
            "semantic_revision", "checkpoint",
        })
        if (
            not is_json_integer(record.get("number"), 1)
            or record.get("number") != position
            or not is_json_integer(record.get("new_merge_blocking_findings"), 0)
        ):
            add_error(errors, "V008", f"fixed-point pass {position} has invalid number/count")
        if not evidence_has_kind(state, record.get("evidence"), FIXED_POINT_EVIDENCE_KINDS):
            add_error(errors, "V008", f"fixed-point pass {position} lacks typed rescan evidence")
        commands_supplied = "commands" in record
        if commands_supplied and (
            minimum is None or minimum < version_tuple(LEAN_GATE_MINIMUM_VERSION)  # type: ignore[operator]
        ):
            add_error(
                errors, "V008",
                f"fixed-point pass {position} uses final-suite commands below validator {LEAN_GATE_MINIMUM_VERSION}",
            )
        elif commands_supplied and not successful_command_records(record.get("commands")):
            add_error(errors, "V008", f"fixed-point pass {position} has invalid or failing final-suite commands")
        if not is_json_integer(record.get("semantic_revision"), 0):
            add_error(errors, "V008", f"fixed-point pass {position} lacks a semantic revision")
        if not isinstance(record.get("checkpoint"), str) or record.get("checkpoint") not in state["checkpoints"]:
            add_error(errors, "V008", f"fixed-point pass {position} lacks a workspace checkpoint")

    remote_targets: dict[tuple[Any, Any], str] = {}
    for local_id, mapping in state["github"]["mappings"].items():
        if local_id not in slices and local_id not in findings:
            add_error(errors, "V002", f"GitHub mapping references unknown local ID {local_id}")
            continue
        number = mapping.get("number") if isinstance(mapping, dict) else None
        if (
            not isinstance(mapping, dict) or not is_json_integer(number, 1)
            or not is_nonempty_string(mapping.get("url"))
        ):
            add_error(errors, "V001", f"GitHub mapping for {local_id} is malformed")
            continue
        reject_unknown_keys(errors, "V001", f"GitHub mapping for {local_id}", mapping, {
            "number", "url", "repository", "created_at", "marker_token",
        })
        repository = mapping.get("repository")
        if not is_nonempty_string(repository):
            add_error(errors, "V001", f"GitHub mapping for {local_id} has no repository")
            continue
        target = (repository, mapping.get("number"))
        if target in remote_targets and remote_targets[target] != local_id:
            add_error(errors, "V002", f"GitHub issue {target} is mapped to both {remote_targets[target]} and {local_id}")
        remote_targets[target] = local_id
    outbox_ids: set[str] = set()
    for item in state["github"]["outbox"]:
        if not isinstance(item, dict) or not is_nonempty_string(item.get("id")):
            add_error(errors, "V001", "GitHub outbox items require stable IDs")
            continue
        if not is_enum(item.get("action"), {"create", "update_managed_block", "comment"}):
            add_error(errors, "V001", f"GitHub outbox item {item['id']} has unsupported action")
        if not is_nonempty_string(item.get("local_id")) or (
            item.get("local_id") not in slices and item.get("local_id") not in findings
        ):
            add_error(errors, "V002", f"GitHub outbox item {item['id']} references no local entity")
        if item["id"] in outbox_ids:
            add_error(errors, "V002", f"duplicate GitHub outbox ID {item['id']}")
        outbox_ids.add(item["id"])

    active = state.get("active_operation")
    if active is not None:
        if (
            not isinstance(active, dict)
            or not isinstance(active.get("slice"), str) or active.get("slice") not in slices
            or not is_enum(active.get("phase"), PHASES)
        ):
            add_error(errors, "V010", "active_operation is malformed")
        else:
            reject_unknown_keys(errors, "V010", "active_operation", active, {
                "slice", "phase", "pre_checkpoint", "started_at", "starting_revision",
            })
            if not isinstance(active.get("pre_checkpoint"), str) or active.get("pre_checkpoint") not in state["checkpoints"]:
                add_error(errors, "V010", "active_operation has no pre-checkpoint")

    validate_readiness(state, errors)
    return errors


def required_findings_for_slice(state: dict[str, Any], sid: str) -> set[str]:
    findings = state.get("findings", {})
    if not isinstance(findings, dict):
        return set()
    return {fid for fid, finding in findings.items() if current_owner(finding) == sid}


def fresh_deliveries(state: dict[str, Any], sid: str) -> list[dict[str, Any]]:
    slices = state.get("slices", {})
    slice_ = slices.get(sid) if isinstance(slices, dict) else None
    deliveries = slice_.get("deliveries", []) if isinstance(slice_, dict) else []
    if not isinstance(deliveries, list):
        return []
    return [
        delivery for delivery in deliveries
        if isinstance(delivery, dict) and delivery.get("semantic_revision") == state.get("semantic_revision")
    ]


def ever_findings_for_slice(state: dict[str, Any], sid: str) -> set[str]:
    findings = state.get("findings", {})
    if not isinstance(findings, dict):
        return set()
    return {
        fid for fid, finding in findings.items()
        if isinstance(finding, dict)
        and isinstance(finding.get("ownership", []), list)
        and any(event.get("to") == sid for event in finding.get("ownership", []) if isinstance(event, dict))
    }


def slice_complete(state: dict[str, Any], sid: str) -> bool:
    slices = state.get("slices", {})
    findings = state.get("findings", {})
    if not isinstance(slices, dict) or not isinstance(findings, dict):
        return False
    slice_ = slices.get(sid)
    if not isinstance(slice_, dict):
        return False
    if not slice_progress(slice_)["completed"]:
        return False
    history = ever_findings_for_slice(state, sid)
    if not history:
        return False
    for fid in history:
        finding = findings.get(fid)
        if not isinstance(finding, dict):
            return False
        resolution = finding.get("resolution")
        resolution_kind = resolution.get("kind") if isinstance(resolution, dict) else None
        if current_owner(finding) == sid and not is_enum(resolution_kind, {"fixed", "split", "superseded"}):
            return False
    return True


def validate_readiness(state: dict[str, Any], errors: list[tuple[str, str]]) -> None:
    run = state.get("run", {})
    if not isinstance(run, dict):
        return
    if run.get("state") != "ready_for_review":
        return
    if state.get("active_operation") is not None:
        add_error(errors, "V008", "ready_for_review while a phase operation is active")
    baseline = run.get("baseline")
    if not isinstance(baseline, dict) or baseline.get("usable") is not True or not successful_command_records(baseline.get("commands")):
        add_error(errors, "V008", "ready_for_review without a usable evidence-backed baseline")
    required_slices = {
        sid for sid, slice_ in state.get("slices", {}).items()
        if isinstance(slice_, dict) and slice_.get("merge_policy") == "required"
    }
    if not required_slices:
        add_error(errors, "V008", "ready_for_review requires at least one required boundary slice")
    if not state.get("findings"):
        add_error(errors, "V008", "ready_for_review requires at least one audited finding")
    for sid, slice_ in state.get("slices", {}).items():
        if not isinstance(slice_, dict):
            continue
        if slice_.get("merge_policy") == "required" and not slice_complete(state, sid):
            add_error(errors, "V008", f"required slice {sid} is not complete")
        if slice_.get("merge_policy") == "required" and not fresh_deliveries(state, sid):
            add_error(errors, "V008", f"required slice {sid} has no delivery attested at the current semantic revision")
    for fid, finding in state.get("findings", {}).items():
        if not isinstance(finding, dict):
            continue
        owner = current_owner(finding)
        required = (
            isinstance(owner, str)
            and owner in state.get("slices", {})
            and isinstance(state["slices"][owner], dict)
            and state["slices"][owner].get("merge_policy") == "required"
        )
        if finding.get("merge_blocking", True) and owner is None:
            add_error(errors, "V008", f"merge-blocking finding {fid} is unassigned")
        if finding.get("merge_blocking", True) and isinstance(owner, str) and owner in state.get("slices", {}) and not required:
            add_error(errors, "V008", f"merge-blocking finding {fid} is owned only by optional slice {owner}")
        missing_fact = finding.get("missing_fact")
        if required and isinstance(missing_fact, dict) and missing_fact.get("normalized_as") == "unclassified":
            add_error(errors, "V008", f"required finding {fid} has not been normalized")
        resolution = finding.get("resolution")
        resolution_kind = resolution.get("kind") if isinstance(resolution, dict) else None
        if required and (finding_open(finding) or is_enum(resolution_kind, {"blocked_external", "needs_human"})):
            add_error(errors, "V008", f"required finding {fid} is unresolved or blocked")
    for ffid, fact in state.get("foreign_facts", {}).items():
        if not isinstance(fact, dict):
            continue
        disposition = fact.get("disposition")
        if isinstance(disposition, dict) and disposition.get("kind") == "assumed":
            add_error(errors, "V008", f"foreign fact {ffid} is still assumed")
    for qid, decision in state.get("decisions", {}).items():
        if not isinstance(decision, dict):
            continue
        sid = decision.get("slice")
        if (
            decision.get("status") == "open"
            and isinstance(sid, str)
            and sid in state.get("slices", {})
            and isinstance(state["slices"][sid], dict)
            and state["slices"][sid].get("merge_policy") == "required"
        ):
            add_error(errors, "V008", f"required slice {sid} has open decision {qid}")
    passes = (state.get("fixed_point") or {}).get("passes")
    if (
        not isinstance(passes, list) or not passes or not isinstance(passes[-1], dict)
        or passes[-1].get("new_merge_blocking_findings") != 0
    ):
        add_error(errors, "V008", "ready_for_review without a clean final fixed-point pass")
    elif passes[-1].get("semantic_revision") != state.get("semantic_revision"):
        add_error(errors, "V008", "final fixed-point pass predates semantic changes")
    elif (
        version_tuple(state.get("minimum_validator_version")) is not None
        and version_tuple(state.get("minimum_validator_version"))
        >= version_tuple(LEAN_GATE_MINIMUM_VERSION)  # type: ignore[operator]
        and not successful_command_records(passes[-1].get("commands"))
    ):
        add_error(errors, "V008", "final fixed-point pass lacks successful final-suite commands")
    github_mode = (state.get("policy") or {}).get("github_projection")
    if github_mode == "outbox":
        add_error(errors, "V008", "ready_for_review is unavailable in outbox-only mode; synchronize or explicitly turn projection off")
    if github_mode == "sync":
        if (state.get("github") or {}).get("outbox"):
            add_error(errors, "V008", "ready_for_review while GitHub projection has pending outbox entries")
        for sid, slice_ in state.get("slices", {}).items():
            if isinstance(slice_, dict) and slice_.get("merge_policy") == "required" and sid not in state["github"]["mappings"]:
                add_error(errors, "V008", f"required slice {sid} has no authenticated GitHub issue mapping")


def read_journal(path: Path) -> tuple[list[dict[str, Any]], list[str]]:
    journal = events_path(path)
    if not path_entry_exists(journal):
        return [], [f"event journal not found: {journal}"]
    try:
        raw_journal = read_bytes_no_follow(journal, "event journal")
    except BdrError as exc:
        return [], [str(exc)]
    try:
        text = raw_journal.decode("utf-8")
    except UnicodeDecodeError:
        return [], [f"event journal is not UTF-8: {journal}"]
    entries: list[dict[str, Any]] = []
    errors: list[str] = []
    if raw_journal and not raw_journal.endswith(b"\n"):
        errors.append("event journal has an unterminated final record")
    previous_hash: str | None = None
    previous_state_hash: str | None = None
    for line_number, raw in enumerate(text.splitlines(), 1):
        if not raw.strip():
            errors.append(f"line {line_number} is blank")
            continue
        try:
            entry = loads_strict(raw, f"{journal}:{line_number}")
        except BdrError as exc:
            errors.append(str(exc))
            continue
        claimed = entry.get("event_sha256")
        body = {key: value for key, value in entry.items() if key != "event_sha256"}
        if (
            not is_json_integer(entry.get("sequence"), 1)
            or entry.get("sequence") != len(entries) + 1
        ):
            errors.append(f"line {line_number} has non-contiguous sequence {entry.get('sequence')!r}")
        if not is_json_integer(entry.get("revision"), 0):
            errors.append(f"line {line_number} has invalid revision {entry.get('revision')!r}")
        if entry.get("previous_event_sha256") != previous_hash:
            errors.append(f"line {line_number} breaks the event hash chain")
        if entry.get("previous_state_sha256") != previous_state_hash:
            errors.append(f"line {line_number} breaks the adjacent state hash chain")
        if not is_sha256(entry.get("state_sha256")):
            errors.append(f"line {line_number} has invalid state_sha256")
        previous_state = entry.get("previous_state_sha256")
        if line_number > 1 and not is_sha256(previous_state):
            errors.append(f"line {line_number} has invalid previous_state_sha256")
        try:
            calculated = digest(body)
        except BdrError as exc:
            errors.append(f"line {line_number} cannot be hashed: {exc}")
            calculated = ""
        if not is_sha256(claimed) or claimed != calculated:
            errors.append(f"line {line_number} event digest does not match its content")
        previous_hash = claimed if isinstance(claimed, str) else calculated
        state_hash = entry.get("state_sha256")
        previous_state_hash = state_hash if isinstance(state_hash, str) else None
        entries.append(entry)
    if not entries:
        errors.append("event journal contains no events")
    return entries, errors


def journal_errors(state: dict[str, Any], path: Path) -> list[str]:
    entries, errors = read_journal(path)
    if not entries:
        return errors
    last = entries[-1]
    if last.get("revision") != state.get("revision"):
        errors.append(
            f"journal ends at revision {last.get('revision')!r}, tracker is revision {state.get('revision')!r}"
        )
    if last.get("state_sha256") != digest(state):
        errors.append("journal state digest does not match the tracker; recovery is ambiguous")
    revisions = [entry.get("revision") for entry in entries]
    if revisions != list(range(len(entries))):
        errors.append(f"journal revisions must be contiguous from 0, found {revisions!r}")
    return errors


def append_event(
    path: Path,
    old_state: dict[str, Any] | None,
    new_state: dict[str, Any],
    operation: dict[str, Any],
    actor: str,
) -> dict[str, Any]:
    journal = events_path(path)
    existing: list[dict[str, Any]] = []
    journal_exists = path_entry_exists(journal)
    if journal_exists:
        existing, errors = read_journal(path)
        if errors:
            raise BdrError("cannot append to invalid event journal: " + "; ".join(errors))
    if existing:
        if old_state is None:
            raise BdrError("cannot append a continuation event without the previous state")
        tail = existing[-1]
        old_digest = digest(old_state)
        old_revision = old_state.get("revision")
        if not is_json_integer(old_revision, 0):
            raise BdrError("cannot append: supplied old state has no integer revision")
        if tail.get("state_sha256") != old_digest or tail.get("revision") != old_revision:
            raise BdrError("cannot append: supplied old state does not match the journal tail")
        if (
            not is_json_integer(new_state.get("revision"), 0)
            or new_state.get("revision") != old_revision + 1
        ):
            raise BdrError("cannot append: new state revision is not exactly old revision + 1")
    elif old_state is not None:
        raise BdrError("cannot append a continuation event to an empty journal")
    elif not is_json_integer(new_state.get("revision"), 0) or new_state.get("revision") != 0:
        raise BdrError("the first journal event must record revision 0")
    previous_hash = existing[-1]["event_sha256"] if existing else None
    body = {
        "sequence": len(existing) + 1,
        "revision": new_state["revision"],
        "at": utc_now(),
        "actor": actor,
        "operation": operation,
        "previous_event_sha256": previous_hash,
        "previous_state_sha256": digest(old_state) if old_state is not None else None,
        "state_sha256": digest(new_state),
    }
    entry = {**body, "event_sha256": digest(body)}
    journal.parent.mkdir(parents=True, exist_ok=True)
    if journal_exists:
        before = regular_file_stat(journal, "event journal")
        fd = open_regular_no_follow(journal, os.O_WRONLY | os.O_APPEND, "event journal")
        opened = os.fstat(fd)
        if not same_file_identity(before, opened) or opened.st_size != before.st_size:
            os.close(fd)
            raise BdrError("event journal changed before append")
    else:
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        try:
            fd = os.open(journal, flags, 0o600)
        except FileExistsError as exc:
            raise BdrError("event journal appeared concurrently; refusing ambiguous append") from exc
    try:
        view = memoryview(canonical_bytes(entry) + b"\n")
        while view:
            written = os.write(fd, view)
            if written <= 0:
                raise BdrError(f"short write while appending {journal}")
            view = view[written:]
        os.fsync(fd)
    finally:
        os.close(fd)
    return entry


def checkpoint(state: dict[str, Any], root: Path, label: str) -> str:
    checkpoint_id = next_id(state["checkpoints"], "CP")
    source = state.get("source") or {}
    audit_dir = source.get("audit_dir", ".bdr")
    include_content_delta = ":saturate:post:" in label or ":falsify:" in label
    require_visible_index = include_content_delta or label.startswith("fixed-point:")
    state["checkpoints"][checkpoint_id] = {
        "label": label,
        **workspace_snapshot(
            root,
            audit_dir,
            source.get("tracker_path"),
            include_content_delta=include_content_delta,
            require_visible_index=require_visible_index,
        ),
    }
    return checkpoint_id


def add_evidence_record(state: dict[str, Any], body: dict[str, Any], requested: str | None = None) -> str:
    evidence_id = requested or next_id(state["evidence"], "E")
    if not evidence_id.startswith("E-") or evidence_id in state["evidence"]:
        raise BdrError(f"evidence ID must be a new E-* ID, got {evidence_id!r}")
    if not isinstance(body, dict):
        raise BdrError("evidence body must be an object")
    record = copy.deepcopy(body)
    record.setdefault("kind", "observation")
    record.setdefault("recorded_at", utc_now())
    state["evidence"][evidence_id] = record
    return evidence_id


def require_mapping(operation: dict[str, Any], key: str) -> dict[str, Any]:
    value = operation.get(key)
    if not isinstance(value, dict):
        raise BdrError(f"operation.{key} must be an object")
    return value


def require_list(operation: dict[str, Any], key: str) -> list[Any]:
    value = operation.get(key)
    if not isinstance(value, list):
        raise BdrError(f"operation.{key} must be a list")
    return value


def require_entity(mapping: dict[str, Any], identifier: Any, kind: str) -> dict[str, Any]:
    if not isinstance(identifier, str) or identifier not in mapping:
        raise BdrError(f"unknown {kind} {identifier!r}")
    value = mapping[identifier]
    if not isinstance(value, dict):
        raise BdrError(f"malformed {kind} {identifier}")
    return value


def phase_attempt_count(slice_: dict[str, Any], phase: str) -> int:
    return sum(
        1
        for attempt in slice_.get("phase_attempts", [])
        if isinstance(attempt, dict) and attempt.get("phase") == phase and attempt.get("result") != "rewound"
    )


def github_projection_item(state: dict[str, Any], sid: str) -> dict[str, Any]:
    slice_ = state["slices"][sid]
    progress = slice_progress(slice_)
    findings = []
    for fid in sorted(ever_findings_for_slice(state, sid)):
        finding = state["findings"][fid]
        findings.append({
            "id": fid,
            "title": finding.get("title"),
            "site": finding.get("site"),
            "owner": current_owner(finding),
            "resolution": (finding.get("resolution") or {}).get("kind"),
        })
    marker_token = digest({"run": state["run"]["id"], "local_id": sid})[:24]
    managed = {
        "schema": "bdr.dev/github-projection/v1",
        "run_id": state["run"]["id"],
        "local_id": sid,
        "marker_token": marker_token,
        "boundary": slice_["boundary"],
        "merge_policy": slice_["merge_policy"],
        "phase": progress["next_phase"] or "complete",
        "findings": findings,
    }
    managed_json = json.dumps(managed, indent=2, ensure_ascii=False)
    managed_block = (
        f"<!-- bdr-managed:start local_id={sid} token={marker_token} -->\n"
        f"```json\n{managed_json}\n```\n"
        f"<!-- bdr-managed:end local_id={sid} token={marker_token} -->"
    )
    mapping = state["github"]["mappings"].get(sid)
    if mapping:
        return {
            "id": f"GH-UPDATE-{sid}",
            "action": "update_managed_block",
            "local_id": sid,
            "issue": mapping,
            "marker_token": marker_token,
            "managed_block": managed_block,
            "desired_sha256": digest(managed),
        }
    safe_name = " ".join(str(slice_["name"]).split())[:180]
    return {
        "id": f"GH-CREATE-{sid}",
        "action": "create",
        "local_id": sid,
        "title": f"[BDR {sid}] {safe_name}",
        "body": (
            "Boundary-Driven Refactoring tracking issue. Human-authored discussion may be added "
            "outside the managed block; BDR will preserve it.\n\n" + managed_block
        ),
        "marker_token": marker_token,
        "desired_sha256": digest(managed),
    }


def apply_one(
    state: dict[str, Any],
    operation: dict[str, Any],
    root: Path,
    atomic_create_acks: dict[str, str] | None = None,
) -> dict[str, Any]:
    kind = operation.get("type")
    if kind == "batch":
        operations = require_list(operation, "operations")
        if not operations:
            raise BdrError("batch operation may not be empty")
        durable_controls = {
            child.get("type") for child in operations if isinstance(child, dict)
        } & {"begin_phase", "finish_phase", "rewind_phase", "set_run_state"}
        if durable_controls:
            raise BdrError(
                "phase and run-state transitions must be durable standalone operations, not batch children"
            )
        create_acks: dict[str, str] = {}
        for index, child in enumerate(operations):
            if not isinstance(child, dict) or child.get("type") != "map_issue":
                continue
            local_id = child.get("local_id")
            creates = [
                item for item in state["github"]["outbox"]
                if item.get("action") == "create" and item.get("local_id") == local_id
            ]
            if len(creates) != 1:
                raise BdrError("map_issue requires exactly one matching generated create item")
            create_id = creates[0].get("id")
            acknowledgements = [
                position for position, candidate in enumerate(operations)
                if isinstance(candidate, dict)
                and candidate.get("type") == "ack_github"
                and candidate.get("id") == create_id
            ]
            if len(acknowledgements) != 1 or acknowledgements[0] <= index:
                raise BdrError("map_issue and acknowledgement of its exact create item must be one ordered batch")
            if local_id in create_acks:
                raise BdrError(f"batch maps local ID {local_id!r} more than once")
            create_acks[local_id] = create_id
        result: dict[str, Any] = {"applied": []}
        for child in operations:
            if not isinstance(child, dict) or child.get("type") == "batch":
                raise BdrError("batch children must be non-batch operation objects")
            result["applied"].append(apply_one(state, child, root, create_acks))
        return result

    if kind == "add_evidence":
        evidence_id = add_evidence_record(state, require_mapping(operation, "evidence"), operation.get("id"))
        return {"evidence_id": evidence_id}

    if kind == "add_dependency":
        dependency_id = operation.get("id") or next_id(state["dependencies"], "D")
        if not isinstance(dependency_id, str) or not dependency_id.startswith("D-") or dependency_id in state["dependencies"]:
            raise BdrError(f"dependency ID must be a new D-* ID, got {dependency_id!r}")
        dependency = require_mapping(operation, "dependency")
        state["dependencies"][dependency_id] = copy.deepcopy(dependency)
        state["dependencies"][dependency_id].setdefault("status", "open")
        state["dependencies"][dependency_id].setdefault("created_at", utc_now())
        return {"dependency_id": dependency_id}

    if kind == "resolve_dependency":
        dependency_id = operation.get("id")
        dependency = require_entity(state["dependencies"], dependency_id, "dependency")
        if dependency.get("status") != "open":
            raise BdrError(f"dependency {dependency_id} is already resolved")
        evidence_id = operation.get("evidence")
        if not evidence_exists(state, evidence_id):
            raise BdrError("dependency resolution requires existing evidence")
        dependency.update({"status": "resolved", "resolution_evidence": evidence_id, "resolved_at": utc_now()})
        return {"dependency_id": dependency_id, "status": "resolved"}

    if kind == "add_decision":
        decision_id = operation.get("id") or next_id(state["decisions"], "Q")
        if not isinstance(decision_id, str) or not decision_id.startswith("Q-") or decision_id in state["decisions"]:
            raise BdrError(f"decision ID must be a new Q-* ID, got {decision_id!r}")
        decision = copy.deepcopy(require_mapping(operation, "decision"))
        decision["status"] = "open"
        decision.setdefault("created_at", utc_now())
        state["decisions"][decision_id] = decision
        return {"decision_id": decision_id}

    if kind == "resolve_decision":
        decision_id = operation.get("id")
        decision = require_entity(state["decisions"], decision_id, "decision")
        if decision.get("status") != "open":
            raise BdrError(f"decision {decision_id} is already resolved")
        if not is_nonempty_string(operation.get("resolution")) or not evidence_exists(state, operation.get("evidence")):
            raise BdrError("decision resolution requires a resolution and existing authority evidence")
        decision.update({
            "status": "resolved",
            "resolution": operation["resolution"],
            "resolution_evidence": operation["evidence"],
            "resolved_at": utc_now(),
        })
        return {"decision_id": decision_id, "status": "resolved"}

    if kind == "add_foreign_fact":
        fact_id = operation.get("id") or next_id(state["foreign_facts"], "FF")
        if not isinstance(fact_id, str) or not fact_id.startswith("FF-") or fact_id in state["foreign_facts"]:
            raise BdrError(f"foreign-fact ID must be a new FF-* ID, got {fact_id!r}")
        state["foreign_facts"][fact_id] = copy.deepcopy(require_mapping(operation, "fact"))
        return {"foreign_fact_id": fact_id}

    if kind == "update_foreign_fact":
        fact_id = operation.get("id")
        fact = require_entity(state["foreign_facts"], fact_id, "foreign fact")
        changes = require_mapping(operation, "changes")
        allowed = {"claim", "consequence_if_wrong", "disposition", "depended_on_by", "revalidation_trigger"}
        unknown = set(changes) - allowed
        if unknown:
            raise BdrError(f"foreign fact update contains unsupported keys: {sorted(unknown)}")
        fact.update(copy.deepcopy(changes))
        return {"foreign_fact_id": fact_id}

    if kind == "add_slice":
        slice_id = operation.get("id") or next_id(state["slices"], "S")
        if not isinstance(slice_id, str) or not slice_id.startswith("S-") or slice_id in state["slices"]:
            raise BdrError(f"slice ID must be a new S-* ID, got {slice_id!r}")
        boundary = require_mapping(operation, "boundary")
        state["slices"][slice_id] = {
            "name": operation.get("name") or slice_id,
            "kind": "boundary",
            "merge_policy": operation.get("merge_policy", "required"),
            "boundary": copy.deepcopy(boundary),
            "depends_on": copy.deepcopy(operation.get("depends_on", [])),
            "collapse_predictions": copy.deepcopy(operation.get("collapse_predictions", {})),
            "operational_obligations": copy.deepcopy(operation.get("operational_obligations", [])),
            "phase_attempts": [],
            "deliveries": [],
            "created_at": utc_now(),
        }
        return {"slice_id": slice_id}

    if kind == "configure_slice":
        slice_id = operation.get("id")
        slice_ = require_entity(state["slices"], slice_id, "slice")
        changes = require_mapping(operation, "changes")
        allowed = {
            "name", "merge_policy", "boundary", "depends_on", "collapse_predictions",
            "operational_obligations",
        }
        unknown = set(changes) - allowed
        if unknown:
            raise BdrError(f"slice update contains unsupported keys: {sorted(unknown)}")
        if slice_.get("merge_policy") == "required" and changes.get("merge_policy") == "optional":
            raise BdrError("a required slice cannot be downgraded to optional after creation")
        progress = slice_progress(slice_)
        if progress["passed"] >= PHASES.index("route") and set(changes) & {
            "boundary", "collapse_predictions", "operational_obligations"
        }:
            raise BdrError("structural slice claims are frozen after REPRESENT; rewind before changing them")
        slice_.update(copy.deepcopy(changes))
        return {"slice_id": slice_id}

    if kind == "add_finding":
        finding_id = operation.get("id") or next_id(state["findings"], "F")
        if not isinstance(finding_id, str) or not finding_id.startswith("F-") or finding_id in state["findings"]:
            raise BdrError(f"finding ID must be a new F-* ID, got {finding_id!r}")
        missing_fact = require_mapping(operation, "missing_fact")
        finding = {
            "title": operation.get("title") or finding_id,
            "site": operation.get("site", "unknown"),
            "severity": operation.get("severity", "unspecified"),
            "merge_blocking": operation.get("merge_blocking", True),
            "found_by": operation.get("found_by", "review"),
            "missing_fact": copy.deepcopy(missing_fact),
            "fix_direction": operation.get("fix_direction", "not_yet_derived"),
            "origin": copy.deepcopy(operation.get("origin")),
            "ownership": [],
            "unassigned_reason": operation.get("unassigned_reason", "awaiting boundary assignment"),
            "resolution": None,
            "resolution_history": [],
            "created_at": utc_now(),
        }
        state["findings"][finding_id] = finding
        return {"finding_id": finding_id}

    if kind == "update_finding":
        finding_id = operation.get("id")
        finding = require_entity(state["findings"], finding_id, "finding")
        if finding.get("resolution") is not None:
            raise BdrError(f"finding {finding_id} is resolved; create a remainder instead of rewriting history")
        changes = require_mapping(operation, "changes")
        allowed = {"title", "site", "severity", "merge_blocking", "missing_fact", "fix_direction", "unassigned_reason"}
        unknown = set(changes) - allowed
        if unknown:
            raise BdrError(f"finding update contains unsupported keys: {sorted(unknown)}")
        if finding.get("merge_blocking") is True and changes.get("merge_blocking") is False:
            raise BdrError("a merge-blocking finding cannot be downgraded after creation")
        finding.update(copy.deepcopy(changes))
        return {"finding_id": finding_id}

    if kind == "assign_finding":
        finding_id = operation.get("finding")
        slice_id = operation.get("slice")
        finding = require_entity(state["findings"], finding_id, "finding")
        require_entity(state["slices"], slice_id, "slice")
        if finding.get("resolution") is not None:
            raise BdrError(f"resolved finding {finding_id} cannot be reassigned")
        previous = current_owner(finding)
        if previous == slice_id:
            raise BdrError(f"finding {finding_id} is already assigned to {slice_id}")
        evidence_id = operation.get("k_verification")
        if not evidence_exists(state, evidence_id):
            raise BdrError("assignment requires existing code-read evidence in k_verification")
        finding["ownership"].append({
            "from": previous,
            "to": slice_id,
            "at": utc_now(),
            "revision": state["revision"] + 1,
            "reason": operation.get("reason", "boundary assignment"),
            "k_verification": evidence_id,
        })
        finding["unassigned_reason"] = None
        return {"finding_id": finding_id, "slice_id": slice_id}

    if kind == "unassign_finding":
        finding_id = operation.get("finding")
        finding = require_entity(state["findings"], finding_id, "finding")
        previous = current_owner(finding)
        if previous is None:
            raise BdrError(f"finding {finding_id} is already unassigned")
        if finding.get("resolution") is not None:
            raise BdrError(f"resolved finding {finding_id} cannot be unassigned")
        reason = operation.get("reason")
        if not is_nonempty_string(reason):
            raise BdrError("unassignment requires a reason")
        finding["ownership"].append({
            "from": previous, "to": None, "at": utc_now(),
            "revision": state["revision"] + 1, "reason": reason,
        })
        finding["unassigned_reason"] = reason
        return {"finding_id": finding_id}

    if kind == "resolve_finding":
        finding_id = operation.get("finding")
        finding = require_entity(state["findings"], finding_id, "finding")
        if finding.get("resolution") is not None:
            raise BdrError(f"finding {finding_id} is already resolved")
        if current_owner(finding) is None:
            raise BdrError(f"unassigned finding {finding_id} cannot be resolved")
        resolution = copy.deepcopy(require_mapping(operation, "resolution"))
        if (
            resolution.get("kind") in {"fixed", "split"}
            and saturate_verification_attempt(
                state, resolution.get("passing_test"), current_owner(finding)
            ) is not None
        ):
            owner = current_owner(finding)
            if not isinstance(owner, str) or not saturate_reuse_is_falsified(
                state, resolution.get("passing_test"), owner, allow_active=True
            ):
                raise BdrError(
                    "SATURATE passing evidence may resolve a finding only during its unchanged FALSIFY phase"
                )
            require_validator_version(state, LEAN_GATE_MINIMUM_VERSION)
        resolution.setdefault("at", utc_now())
        finding["resolution"] = resolution
        return {"finding_id": finding_id, "resolution": resolution.get("kind")}

    if kind == "reopen_finding":
        finding_id = operation.get("finding")
        finding = require_entity(state["findings"], finding_id, "finding")
        resolution = finding.get("resolution")
        if not isinstance(resolution, dict) or resolution.get("kind") not in {"blocked_external", "needs_human"}:
            raise BdrError("only a blocked_external or needs_human finding can be reopened")
        evidence_id = operation.get("evidence")
        if not evidence_exists(state, evidence_id):
            raise BdrError("reopening requires evidence that the block or decision changed")
        history = copy.deepcopy(resolution)
        history.update({"reopened_at": utc_now(), "reopen_evidence": evidence_id})
        finding.setdefault("resolution_history", []).append(history)
        finding["resolution"] = None
        return {"finding_id": finding_id, "reopened_from": resolution["kind"]}

    if kind == "begin_phase":
        if state.get("active_operation") is not None:
            raise BdrError("a phase operation is already active; finish or recover it first")
        baseline = state.get("run", {}).get("baseline")
        if not isinstance(baseline, dict) or baseline.get("usable") is not True:
            raise BdrError("a usable baseline is required before beginning a phase")
        if state["run"].get("state") not in {"auditing", "executing", "verifying"}:
            raise BdrError(f"run state {state['run'].get('state')!r} does not permit phase execution")
        slice_id = operation.get("slice")
        phase = operation.get("phase")
        slice_ = require_entity(state["slices"], slice_id, "slice")
        progress = slice_progress(slice_)
        if progress["errors"]:
            raise BdrError(f"slice {slice_id} has invalid attempt history")
        if phase != progress["next_phase"]:
            raise BdrError(f"slice {slice_id} next legal phase is {progress['next_phase']!r}, not {phase!r}")
        incomplete = [dep for dep in slice_.get("depends_on", []) if dep in state["slices"] and not slice_complete(state, dep)]
        if incomplete:
            raise BdrError(f"slice {slice_id} has incomplete dependencies: {', '.join(incomplete)}")
        maximum = int((state.get("policy") or {}).get("max_phase_attempts", 3))
        if phase_attempt_count(slice_, phase) >= maximum:
            raise BdrError(f"slice {slice_id} reached the configured attempt bound for {phase}")
        pre = checkpoint(state, root, f"{slice_id}:{phase}:pre")
        state["active_operation"] = {
            "slice": slice_id,
            "phase": phase,
            "pre_checkpoint": pre,
            "started_at": utc_now(),
            "starting_revision": state["revision"],
        }
        state["run"]["state"] = "executing"
        return {"slice_id": slice_id, "phase": phase, "pre_checkpoint": pre}

    if kind == "finish_phase":
        active = state.get("active_operation")
        if not isinstance(active, dict):
            raise BdrError("no phase operation is active")
        slice_id = operation.get("slice") or active.get("slice")
        phase = operation.get("phase") or active.get("phase")
        if slice_id != active.get("slice") or phase != active.get("phase"):
            raise BdrError("finish target does not match the active phase operation")
        result = operation.get("result", "passed")
        if result not in {"passed", "failed", "blocked"}:
            raise BdrError("finish result must be passed, failed, or blocked")
        gate = copy.deepcopy(require_mapping(operation, "gate"))
        if (
            "saturate_evidence" in gate
            or (
                result == "passed"
                and (
                    (phase in STRUCTURAL_PHASES and "commands" not in gate)
                    or "foreign_fact_review" not in gate
                )
            )
        ):
            require_validator_version(state, LEAN_GATE_MINIMUM_VERSION)
        if result == "passed" and phase == "falsify":
            gate["validator_version"] = VERSION
            require_validator_version(state, VERSION)
        gate["kind"] = "phase_gate"
        gate["phase"] = phase
        gate["slice"] = slice_id
        gate_id = add_evidence_record(state, gate, operation.get("evidence_id"))
        post = checkpoint(state, root, f"{slice_id}:{phase}:post:{result}")
        attempt = {
            "phase": phase,
            "result": result,
            "pre_checkpoint": active["pre_checkpoint"],
            "post_checkpoint": post,
            "gate_evidence": gate_id,
            "started_at": active.get("started_at"),
            "starting_revision": active.get("starting_revision"),
            "finished_at": utc_now(),
        }
        state["slices"][slice_id]["phase_attempts"].append(attempt)
        state["active_operation"] = None
        state["run"]["state"] = "verifying" if result == "passed" else "executing"
        return {"slice_id": slice_id, "phase": phase, "result": result, "gate_evidence": gate_id}

    if kind == "rewind_phase":
        if state.get("active_operation") is not None:
            raise BdrError("finish the active phase attempt before rewinding")
        slice_id = operation.get("slice")
        target = operation.get("rewind_to")
        slice_ = require_entity(state["slices"], slice_id, "slice")
        progress = slice_progress(slice_)
        if not is_enum(target, PHASES) or PHASES.index(target) > progress["passed"]:
            raise BdrError(f"illegal rewind target {target!r} for slice progress {progress['passed']}")
        reason = operation.get("reason")
        if not is_nonempty_string(reason):
            raise BdrError("rewind requires a reason")
        evidence_id = operation.get("evidence")
        if not evidence_exists(state, evidence_id):
            raise BdrError("rewind requires existing evidence")
        if PHASES.index(target) <= PHASES.index("falsify"):
            for finding in state["findings"].values():
                if not isinstance(finding, dict) or current_owner(finding) != slice_id:
                    continue
                resolution = finding.get("resolution")
                if (
                    not isinstance(resolution, dict)
                    or resolution.get("kind") not in {"fixed", "split"}
                    or saturate_verification_attempt(
                        state, resolution.get("passing_test"), slice_id
                    ) is None
                ):
                    continue
                history = copy.deepcopy(resolution)
                history.update({
                    "reopened_at": utc_now(),
                    "reopen_evidence": evidence_id,
                    "reopen_reason": f"phase rewind to {target}",
                    "verification_slice": slice_id,
                })
                finding.setdefault("resolution_history", []).append(history)
                finding["resolution"] = None
        slice_["phase_attempts"].append({
            "phase": progress["next_phase"] or PHASES[-1],
            "result": "rewound",
            "rewind_to": target,
            "reason": reason,
            "gate_evidence": evidence_id,
            "finished_at": utc_now(),
        })
        state["run"]["state"] = "executing"
        return {"slice_id": slice_id, "rewind_to": target}

    if kind == "record_delivery":
        slice_id = operation.get("slice")
        slice_ = require_entity(state["slices"], slice_id, "slice")
        if not slice_progress(slice_)["completed"]:
            raise BdrError(f"slice {slice_id} cannot be delivered before FALSIFY passes")
        delivery_kind = operation.get("kind")
        if not is_enum(delivery_kind, DELIVERY_KINDS):
            raise BdrError("delivery kind must be commit or no_code_change")
        evidence_id = operation.get("evidence")
        uses_saturate = saturate_verification_attempt(state, evidence_id, slice_id) is not None
        if uses_saturate:
            require_validator_version(state, LEAN_GATE_MINIMUM_VERSION)
        if not passing_verification_evidence(state, evidence_id, slice_id):
            raise BdrError(
                "delivery requires existing passing test/verification or FALSIFY-linked SATURATE evidence"
            )
        delivery: dict[str, Any] = {
            "kind": delivery_kind,
            "evidence": evidence_id,
            "recorded_at": utc_now(),
            "semantic_revision": state["semantic_revision"],
        }
        changes = working_tree_changes(
            root,
            state["source"].get("audit_dir", ".bdr"),
            state["source"].get("tracker_path"),
        )
        if changes:
            raise BdrError(f"recording delivery requires a clean code worktree: {changes[:5]!r}")
        if delivery_kind == "commit":
            sha = canonical_commit_oid(root, operation.get("sha") or "HEAD", "delivery commit")
            if sha == state["source"]["starting_head_sha"]:
                raise BdrError("the original target head is not a BDR delivery commit")
            ancestor = run_command(
                ["git", "merge-base", "--is-ancestor", state["source"]["starting_head_sha"], sha],
                root,
                required=False,
            )
            if ancestor.returncode:
                raise BdrError("delivery commit is not descended from the pinned target head")
            if uses_saturate:
                falsify_attempt = passed_falsify_attempt_reusing_saturate(
                    state, evidence_id, slice_id
                )
                checkpoint_record = (
                    state["checkpoints"].get(falsify_attempt.get("post_checkpoint"))
                    if isinstance(falsify_attempt, dict)
                    else None
                )
                if not isinstance(checkpoint_record, dict):
                    raise BdrError("SATURATE delivery has no FALSIFY workspace checkpoint")
                verified_base = checkpoint_record.get("head_sha")
                verified_delta = checkpoint_record.get("content_delta_sha256")
                if not is_canonical_commit_oid(verified_base) or not is_sha256(verified_delta):
                    raise BdrError("FALSIFY checkpoint lacks a deliverable content fingerprint")
                includes_verified_base = run_command(
                    ["git", "merge-base", "--is-ancestor", verified_base, sha],
                    root,
                    required=False,
                )
                if includes_verified_base.returncode:
                    raise BdrError("delivery commit does not descend from the FALSIFY-verified base")
                delivered_delta = commit_delta_sha256(
                    root,
                    verified_base,
                    sha,
                    state["source"].get("audit_dir", ".bdr"),
                    state["source"].get("tracker_path"),
                )
                if delivered_delta != verified_delta:
                    raise BdrError(
                        "delivery commit content differs from the SATURATE/FALSIFY-verified workspace"
                    )
                delivery.update({
                    "verified_checkpoint": falsify_attempt["post_checkpoint"],
                    "content_delta_sha256": verified_delta,
                })
            for other_id, other in state["slices"].items():
                if any(item.get("kind") == "commit" and item.get("sha") == sha for item in other.get("deliveries", [])):
                    if other_id != slice_id:
                        raise BdrError(f"delivery commit {sha} is already attributed to {other_id}")
                    if any(
                        item.get("kind") == "commit" and item.get("sha") == sha
                        and item.get("semantic_revision") == state["semantic_revision"]
                        for item in other.get("deliveries", [])
                    ):
                        raise BdrError(f"delivery commit {sha} is already current for {slice_id}")
            current = canonical_commit_oid(root, "HEAD", "current HEAD")
            frontier = run_command(
                [
                    "git", "rev-list", "--reverse", "--topo-order",
                    f"{state['source']['starting_head_sha']}..{current}",
                ],
                root,
            ).stdout.splitlines()
            attributed = {
                item.get("sha")
                for sid in state["slices"]
                for item in fresh_deliveries(state, sid)
                if item.get("kind") == "commit"
            }
            next_commit = next((candidate for candidate in frontier if candidate not in attributed), None)
            if sha != next_commit:
                raise BdrError(
                    f"delivery commit must advance the attribution frontier; next commit is {next_commit!r}, got {sha!r}"
                )
            delivery.update({
                "sha": sha,
                "tree": git_value(root, "show", "-s", "--format=%T", sha),
                "subject": git_value(root, "show", "-s", "--format=%s", sha),
            })
        else:
            if uses_saturate:
                raise BdrError("no_code_change delivery requires standalone test/verification evidence")
            reason = operation.get("reason")
            if not is_nonempty_string(reason):
                raise BdrError("no_code_change delivery requires a reason")
            delivery["reason"] = reason
        slice_.setdefault("deliveries", []).append(delivery)
        return {"slice_id": slice_id, "delivery": delivery}

    if kind == "set_baseline":
        if state.get("active_operation") is not None:
            raise BdrError("finish the active phase operation before changing the baseline")
        if state["run"].get("state") not in {
            "preflighting", "auditing", "blocked_environment", "verification_pending", "failed_verification",
        }:
            raise BdrError(f"run state {state['run'].get('state')!r} does not permit baseline replacement")
        current_run_state = state["run"].get("state")
        if current_run_state in INTERVENTION_RUN_STATES and not evidence_has_kind(
            state, operation.get("evidence"), {"resume"}
        ):
            raise BdrError("replacing a baseline while resuming an interrupted run requires resume evidence")
        baseline = copy.deepcopy(require_mapping(operation, "baseline"))
        if state["run"].get("baseline") is not None and operation.get("replace") is not True:
            raise BdrError("baseline already exists; replacement must be explicit")
        if baseline.get("usable") is True and not successful_command_records(baseline.get("commands")):
            raise BdrError("a usable baseline requires successful command evidence")
        state["run"]["baseline"] = baseline
        if baseline.get("usable") is True:
            state["run"]["state"] = "auditing"
            state["run"]["terminal_reason"] = None
        else:
            state["run"]["state"] = "blocked_environment"
            reason = baseline.get("reason")
            state["run"]["terminal_reason"] = (
                reason if is_nonempty_string(reason)
                else "baseline verification is not usable in the isolated environment"
            )
        return {"usable": baseline.get("usable")}

    if kind == "record_fixed_point":
        incomplete = [
            sid for sid, slice_ in state["slices"].items()
            if slice_.get("merge_policy") == "required"
            and (not slice_complete(state, sid) or not fresh_deliveries(state, sid))
        ]
        unresolved = [
            fid for fid, finding in state["findings"].items()
            if finding.get("merge_blocking", True) and finding_open(finding)
        ]
        if incomplete or unresolved:
            raise BdrError(f"fixed-point scan is premature; incomplete slices={incomplete}, open findings={unresolved}")
        attribution = delivery_attribution_errors(state, root, require_complete=True)
        if attribution:
            raise BdrError("fixed-point scan requires full commit attribution: " + "; ".join(attribution))
        passes = state["fixed_point"].setdefault("passes", [])
        maximum = int((state.get("policy") or {}).get("max_fixed_point_passes", 3))
        if len(passes) >= maximum:
            raise BdrError("configured fixed-point pass bound reached")
        record = copy.deepcopy(require_mapping(operation, "pass"))
        if not is_json_integer(record.get("new_merge_blocking_findings"), 0):
            raise BdrError("fixed-point pass requires a non-negative new_merge_blocking_findings count")
        if not evidence_has_kind(state, record.get("evidence"), FIXED_POINT_EVIDENCE_KINDS):
            raise BdrError("fixed-point pass requires existing typed rescan evidence")
        if not successful_command_records(record.get("commands")):
            raise BdrError("fixed-point pass requires successful final-suite command evidence")
        require_validator_version(state, LEAN_GATE_MINIMUM_VERSION)
        record.setdefault("number", len(passes) + 1)
        record.setdefault("at", utc_now())
        record["semantic_revision"] = state["semantic_revision"]
        record["checkpoint"] = checkpoint(state, root, f"fixed-point:{record['number']}")
        passes.append(record)
        if record["new_merge_blocking_findings"] and len(passes) >= maximum:
            state["run"]["state"] = "non_convergent"
        return {"pass": record["number"], "new_merge_blocking_findings": record["new_merge_blocking_findings"]}

    if kind == "configure_github":
        mode = operation.get("mode")
        if not is_enum(mode, {"off", "outbox", "sync"}):
            raise BdrError("GitHub mode must be off, outbox, or sync")
        if mode == "off" and state["github"].get("outbox"):
            raise BdrError("acknowledge every pending GitHub outbox item before turning projection off")
        state["policy"]["github_projection"] = mode
        return {"github_projection": mode}

    if kind == "project_github":
        if state["policy"].get("github_projection") == "off":
            raise BdrError("GitHub projection is off")
        include_optional = operation.get("include_optional", False)
        desired = {
            item["id"]: item
            for sid, slice_ in sorted(state["slices"].items())
            if include_optional or slice_.get("merge_policy") == "required"
            for item in [github_projection_item(state, sid)]
        }
        preserved = [
            item for item in state["github"]["outbox"]
            if not (isinstance(item, dict) and item.get("id") in desired)
        ]
        state["github"]["outbox"] = preserved + list(desired.values())
        return {"projected": sorted(desired), "outbox_size": len(state["github"]["outbox"])}

    if kind == "map_issue":
        local_id = operation.get("local_id")
        if local_id not in state["slices"] and local_id not in state["findings"]:
            raise BdrError(f"cannot map unknown local ID {local_id!r}")
        mapping = require_mapping(operation, "mapping")
        number = mapping.get("number")
        if not is_nonempty_string(mapping.get("url")) or not is_json_integer(number, 1):
            raise BdrError("issue mapping requires a positive integer number and durable URL")
        if local_id in state["github"]["mappings"]:
            raise BdrError(f"local ID {local_id} already has an issue mapping")
        creates = [
            item for item in state["github"]["outbox"]
            if item.get("action") == "create" and item.get("local_id") == local_id
        ]
        if len(creates) != 1 or mapping.get("marker_token") != creates[0].get("marker_token"):
            raise BdrError("new issue mappings require the matching locally generated create outbox item and marker token")
        if atomic_create_acks is None or atomic_create_acks.get(local_id) != creates[0].get("id"):
            raise BdrError("map_issue must atomically acknowledge its exact create item in the same batch")
        state["github"]["mappings"][local_id] = copy.deepcopy(mapping)
        return {"local_id": local_id, "issue": mapping["number"]}

    if kind == "enqueue_github":
        if state["policy"].get("github_projection") == "off":
            raise BdrError("GitHub projection is off")
        item = copy.deepcopy(require_mapping(operation, "item"))
        if not is_enum(item.get("action"), {"create", "update_managed_block", "comment"}):
            raise BdrError("unsupported GitHub outbox action")
        outbox_id = item.get("id") or f"GH-{digest(item)[:12]}"
        item["id"] = outbox_id
        matches = [existing for existing in state["github"]["outbox"] if existing.get("id") == outbox_id]
        if matches:
            comparable = lambda value: {key: field for key, field in value.items() if key != "created_at"}
            if len(matches) != 1 or canonical_bytes(comparable(matches[0])) != canonical_bytes(comparable(item)):
                raise BdrError(f"GitHub outbox ID {outbox_id!r} collides with a different payload")
            return {"outbox_id": outbox_id, "deduplicated": True}
        item.setdefault("created_at", utc_now())
        state["github"]["outbox"].append(item)
        return {"outbox_id": outbox_id, "deduplicated": False}

    if kind == "ack_github":
        outbox_id = operation.get("id")
        matched = [item for item in state["github"]["outbox"] if item.get("id") == outbox_id]
        if matched and matched[0].get("action") == "create":
            local_id = matched[0].get("local_id")
            if atomic_create_acks is None or atomic_create_acks.get(local_id) != outbox_id:
                raise BdrError("a create item may only be acknowledged atomically with its exact issue mapping")
        before = len(state["github"]["outbox"])
        state["github"]["outbox"] = [item for item in state["github"]["outbox"] if item.get("id") != outbox_id]
        if len(state["github"]["outbox"]) == before:
            raise BdrError(f"unknown GitHub outbox ID {outbox_id!r}")
        return {"outbox_id": outbox_id}

    if kind == "set_run_state":
        target = operation.get("state")
        if not is_enum(target, RUN_STATES):
            raise BdrError(f"invalid run state {target!r}")
        current = state["run"].get("state")
        if state.get("active_operation") is not None:
            raise BdrError("finish the active phase operation before changing run state")
        if target == current:
            raise BdrError(f"run is already in state {target}")
        reason = operation.get("reason")
        if target in INTERVENTION_RUN_STATES and not is_nonempty_string(reason):
            raise BdrError(f"terminal/intervention state {target} requires a reason")
        if target == "ready_for_review":
            if current != "verifying":
                raise BdrError("ready_for_review may only follow the verifying state")
        elif target == "auditing":
            if current not in INTERVENTION_RUN_STATES | {"ready_for_review"}:
                raise BdrError(f"run state {current!r} cannot be resumed into auditing")
            evidence_id = operation.get("evidence")
            if not evidence_has_kind(state, evidence_id, {"resume"}):
                raise BdrError("resuming an interrupted run requires existing evidence of kind resume")
        elif target not in INTERVENTION_RUN_STATES:
            raise BdrError(
                f"run state {target!r} is engine-controlled; use baseline, phase, or completion operations"
            )
        state["run"]["state"] = target
        state["run"]["terminal_reason"] = reason
        return {"state": target}

    raise BdrError(f"unsupported operation type {kind!r}")


def operation_affects_semantics(operation: dict[str, Any]) -> bool:
    if operation.get("type") == "batch":
        children = operation.get("operations")
        return isinstance(children, list) and any(
            isinstance(child, dict) and operation_affects_semantics(child) for child in children
        )
    return operation.get("type") not in {
        "add_evidence", "record_delivery", "record_fixed_point", "configure_github", "project_github", "map_issue",
        "enqueue_github", "ack_github", "set_run_state",
    }


def mutate_state(
    path: Path,
    operation: dict[str, Any],
    expected_revision: int,
    actor: str,
    invocation_root: Path | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    with state_lock(path):
        old = load_json_file(path)
        journal = journal_errors(old, path)
        if journal:
            raise BdrError("V009: " + "; ".join(journal))
        validation = validate_state(old)
        if validation:
            rendered = "; ".join(f"{rule}: {message}" for rule, message in validation)
            raise BdrError("current tracker is invalid: " + rendered)
        if old.get("revision") != expected_revision:
            raise BdrError(
                f"stale operation: expected revision {expected_revision}, current revision is {old.get('revision')}"
            )
        root = Path(old["source"]["root"]).resolve()
        expected_root = (invocation_root or root).resolve()
        binding = state_binding_errors(old, path, expected_root)
        if binding:
            raise BdrError("; ".join(binding))
        lineage = local_lineage_errors(old, root)
        if lineage:
            raise BdrError("stale or rewritten local input: " + "; ".join(lineage))
        state = copy.deepcopy(old)
        result = apply_one(state, operation, root)
        if operation_affects_semantics(operation):
            state["semantic_revision"] += 1
        state["revision"] = expected_revision + 1
        state["run"]["updated_at"] = utc_now()
        validation = validate_state(state)
        if validation:
            rendered = "; ".join(f"{rule}: {message}" for rule, message in validation)
            raise BdrError("operation rejected: " + rendered)
        if state["run"]["state"] == "ready_for_review":
            lineage = local_lineage_errors(state, root)
            if lineage:
                raise BdrError("operation rejected: " + "; ".join(lineage))
            workspace = fixed_point_workspace_errors(state, root)
            if workspace:
                raise BdrError("operation rejected: " + "; ".join(workspace))
        atomic_write(path, state)
        append_event(path, old, state, operation, actor)
        return state, result


def render_validation(errors: Iterable[tuple[str, str]]) -> str:
    return "\n".join(f"{rule}  {message}" for rule, message in errors)


def working_tree_changes(
    root: Path, audit_dir: str = ".bdr", tracker_path: str | None = None,
) -> list[str]:
    result = run_command(
        [
            "git", "status", "--porcelain=v1", "--untracked-files=all", "--", ".",
            *audit_exclusions(audit_dir, tracker_path),
        ],
        root,
    )
    return [line for line in result.stdout.splitlines() if line.strip()]


def state_binding_errors(state: dict[str, Any], path: Path, root: Path) -> list[str]:
    source = state.get("source") or {}
    errors: list[str] = []
    bound = Path(source.get("root", "")).resolve()
    if bound != root.resolve():
        errors.append(f"tracker is bound to repository {bound}, not {root.resolve()}")
        return errors
    try:
        actual_audit = path.resolve().parent.relative_to(root.resolve()).as_posix()
    except ValueError:
        return ["tracker path escaped its bound repository"]
    if source.get("audit_dir") != actual_audit:
        errors.append(
            f"tracker audit directory is {actual_audit!r}, state claims {source.get('audit_dir')!r}"
        )
    actual_tracker = path.resolve().relative_to(root.resolve()).as_posix()
    if source.get("tracker_path") != actual_tracker:
        errors.append(
            f"tracker path is {actual_tracker!r}, state claims {source.get('tracker_path')!r}"
        )
    return errors


def delivery_attribution_errors(
    state: dict[str, Any], root: Path, require_complete: bool = False,
) -> list[str]:
    target = (state.get("source") or {}).get("starting_head_sha")
    current = canonical_commit_oid(root, "HEAD", "current HEAD")
    if not is_canonical_commit_oid(target):
        return ["pinned target head is not a canonical commit object ID"]
    result = run_command(
        ["git", "rev-list", "--reverse", "--topo-order", f"{target}..{current}"],
        root,
        required=False,
    )
    if result.returncode:
        return ["cannot enumerate the delivery commit frontier from target head to current HEAD"]
    frontier = [line for line in result.stdout.splitlines() if line]
    frontier_set = set(frontier)
    owners: dict[str, str] = {}
    errors: list[str] = []
    for sid in state.get("slices", {}):
        for delivery in fresh_deliveries(state, sid):
            if delivery.get("kind") != "commit":
                continue
            sha = delivery.get("sha")
            if sha not in frontier_set:
                errors.append(f"slice {sid} attributes commit {sha!r} outside the current delivery frontier")
                continue
            previous = owners.get(sha)
            if previous is not None:
                errors.append(f"delivery commit {sha} is currently attributed more than once ({previous}, {sid})")
            owners[sha] = sid
            evidence_record = state.get("evidence", {}).get(delivery.get("evidence"))
            if isinstance(evidence_record, dict) and evidence_record.get("phase") == "saturate":
                checkpoint_record = state.get("checkpoints", {}).get(delivery.get("verified_checkpoint"))
                if not isinstance(checkpoint_record, dict):
                    errors.append(f"slice {sid} SATURATE delivery has no verified checkpoint")
                    continue
                base = checkpoint_record.get("head_sha")
                expected_delta = checkpoint_record.get("content_delta_sha256")
                if not is_canonical_commit_oid(base) or not is_sha256(expected_delta):
                    errors.append(f"slice {sid} SATURATE delivery checkpoint is malformed")
                    continue
                try:
                    observed_delta = commit_delta_sha256(
                        root,
                        base,
                        sha,
                        state.get("source", {}).get("audit_dir", ".bdr"),
                        state.get("source", {}).get("tracker_path"),
                    )
                except BdrError as exc:
                    errors.append(f"slice {sid} SATURATE delivery cannot be fingerprinted: {exc}")
                    continue
                if observed_delta != expected_delta:
                    errors.append(f"slice {sid} delivery commit differs from its verified workspace")
    if require_complete:
        missing = [sha for sha in frontier if sha not in owners]
        if missing:
            errors.append(
                f"delivery frontier has {len(missing)} unattributed commit(s), starting with {missing[0]}"
            )
        if frontier and frontier[-1] not in owners:
            errors.append(f"current HEAD {frontier[-1]} is not an attributed delivery commit")
    return errors


def local_lineage_errors(state: dict[str, Any], root: Path) -> list[str]:
    errors = git_graft_errors(root)
    if errors:
        return errors
    source = state.get("source") or {}
    base = source.get("base_sha")
    target = source.get("starting_head_sha")
    current = git_value(root, "rev-parse", "HEAD")
    for label, sha in (("base", base), ("target head", target)):
        if not is_nonempty_string(sha) or run_command(["git", "cat-file", "-e", f"{sha}^{{commit}}"], root, required=False).returncode:
            errors.append(f"pinned {label} commit {sha!r} is unavailable")
    if not errors:
        if run_command(["git", "merge-base", "--is-ancestor", base, target], root, required=False).returncode:
            errors.append("pinned base is no longer an ancestor of the target head")
        if run_command(["git", "merge-base", "--is-ancestor", target, current], root, required=False).returncode:
            errors.append(f"current HEAD {current} is not descended from pinned target head {target}")
    for sid, slice_ in state.get("slices", {}).items():
        for delivery in slice_.get("deliveries", []):
            if delivery.get("kind") != "commit":
                continue
            sha = delivery.get("sha")
            if run_command(["git", "merge-base", "--is-ancestor", sha, current], root, required=False).returncode:
                errors.append(f"slice {sid} delivery commit {sha} is not reachable from current HEAD {current}")
    errors.extend(delivery_attribution_errors(
        state, root, require_complete=state.get("run", {}).get("state") == "ready_for_review"
    ))
    return errors


def fixed_point_workspace_errors(state: dict[str, Any], root: Path) -> list[str]:
    passes = (state.get("fixed_point") or {}).get("passes")
    if not isinstance(passes, list) or not passes:
        return []
    checkpoint_id = passes[-1].get("checkpoint")
    expected = state.get("checkpoints", {}).get(checkpoint_id)
    if not isinstance(expected, dict):
        return ["final fixed-point workspace checkpoint is missing"]
    source = state.get("source") or {}
    observed = workspace_snapshot(
        root, source.get("audit_dir", ".bdr"), source.get("tracker_path"),
        require_visible_index=True,
    )
    errors: list[str] = []
    if observed["head_sha"] != expected.get("head_sha"):
        errors.append(
            f"HEAD changed after final fixed-point scan: expected {expected.get('head_sha')}, observed {observed['head_sha']}"
        )
    if observed["worktree_sha256"] != expected.get("worktree_sha256") or observed["dirty"] != expected.get("dirty"):
        errors.append("code worktree changed after final fixed-point scan")
    if observed["dirty"]:
        errors.append("final fixed-point workspace is not clean")
    return errors


def gh_pr_metadata(root: Path, selector: str) -> dict[str, Any]:
    if shutil.which("gh") is None:
        raise BdrError("GitHub CLI is required to resolve --pr; supply pinned --base-sha and --head-sha instead")
    if not valid_pr_selector(selector):
        raise BdrError("PR selector must be 'current', a positive issue number, or an https GitHub PR URL")
    argv = [
        "gh", "pr", "view",
        "--json", "number,url,baseRefOid,headRefOid,baseRefName,headRefName,isCrossRepository",
    ]
    if selector != "current":
        argv.extend(["--", selector])
    result = run_command(argv, root)
    try:
        metadata = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise BdrError("GitHub CLI returned malformed PR metadata") from exc
    required = ("number", "url", "baseRefOid", "headRefOid")
    if not isinstance(metadata, dict) or any(not metadata.get(key) for key in required):
        raise BdrError("GitHub CLI did not return pinned PR base/head metadata")
    if (
        not isinstance(metadata["number"], int) or isinstance(metadata["number"], bool)
        or metadata["number"] < 1 or not valid_pr_selector(metadata["url"])
        or not is_canonical_commit_oid(metadata["baseRefOid"])
        or not is_canonical_commit_oid(metadata["headRefOid"])
    ):
        raise BdrError("GitHub CLI returned unsafe or non-canonical PR metadata")
    return metadata


def preflight_report(cwd: Path, pr: str | None = None) -> dict[str, Any]:
    root = git_root(cwd)
    changes = working_tree_changes(root)
    graft_errors = git_graft_errors(root)
    report: dict[str, Any] = {
        "ok": not changes and not graft_errors,
        "repository_root": str(root),
        "head_sha": git_value(root, "rev-parse", "HEAD"),
        "branch": git_value(root, "branch", "--show-current") or "detached",
        "clean": not changes,
        "changes": changes,
        "python": sys.version.split()[0],
        "git": git_value(root, "--version"),
        "github_cli": shutil.which("gh") is not None,
        "git_graft_errors": graft_errors,
    }
    if pr:
        report["pr"] = gh_pr_metadata(root, pr)
        if report["pr"]["headRefOid"] != report["head_sha"]:
            report["ok"] = False
            report["head_mismatch"] = {
                "checked_out": report["head_sha"],
                "pr_head": report["pr"]["headRefOid"],
            }
        report["policy_inputs"] = (
            [] if graft_errors else policy_input_snapshot(
                root, report["pr"]["baseRefOid"], report["pr"]["headRefOid"]
            )
        )
    else:
        parent = run_command(["git", "rev-parse", "HEAD^"], root, required=False) if not graft_errors else None
        report["policy_inputs"] = (
            policy_input_snapshot(root, parent.stdout.strip(), report["head_sha"])
            if parent is not None and parent.returncode == 0 else []
        )
    report["target_changed_policy_inputs"] = [
        item["path"] for item in report["policy_inputs"] if item["changed_by_target"]
    ]
    return report


def derive_next_action(state: dict[str, Any]) -> dict[str, Any]:
    run_state = state["run"]["state"]
    if run_state == "ready_for_review":
        return {"action": "handoff", "reason": "the verified run is ready for review"}
    if run_state in {
        "verification_pending", "needs_human", "blocked_environment", "stale_input",
        "non_convergent", "failed_verification",
    }:
        return {
            "action": "handoff_terminal",
            "state": run_state,
            "reason": state["run"].get("terminal_reason"),
        }
    active = state.get("active_operation")
    if isinstance(active, dict):
        return {
            "action": "finish_or_recover_phase",
            "slice": active.get("slice"),
            "phase": active.get("phase"),
            "pre_checkpoint": active.get("pre_checkpoint"),
        }
    if not isinstance(state["run"].get("baseline"), dict):
        return {"action": "record_baseline", "operation": "set_baseline"}
    if state["run"]["baseline"].get("usable") is not True:
        return {"action": "audit_only", "reason": "the baseline oracle is not usable"}
    if not state["findings"] or not state["slices"]:
        return {
            "action": "discover_boundaries",
            "reason": "no executable finding/slice inventory exists",
            "hint": "run `bdr examples`, then apply one discovery batch containing evidence, slices, findings, and assignments",
        }
    unassigned = [
        fid for fid, finding in state["findings"].items()
        if finding.get("merge_blocking", True) and current_owner(finding) is None
    ]
    if unassigned:
        return {"action": "assign_or_split_findings", "findings": sorted(unassigned)}
    waiting: list[str] = []
    open_decisions = {
        qid: decision for qid, decision in state.get("decisions", {}).items()
        if decision.get("status") == "open"
    }
    stale_deliveries: list[str] = []
    for sid, slice_ in sorted(state["slices"].items()):
        if slice_.get("merge_policy") != "required":
            continue
        if slice_complete(state, sid):
            if not fresh_deliveries(state, sid):
                stale_deliveries.append(sid)
            continue
        incomplete = [dep for dep in slice_.get("depends_on", []) if not slice_complete(state, dep)]
        if incomplete:
            waiting.append(sid)
            continue
        if any(decision.get("slice") == sid for decision in open_decisions.values()):
            waiting.append(sid)
            continue
        progress = slice_progress(slice_)
        return {
            "action": "begin_phase",
            "slice": sid,
            "phase": progress["next_phase"],
            "operation": {
                "type": "begin_phase", "slice": sid, "phase": progress["next_phase"],
            },
        }
    if open_decisions:
        return {
            "action": "needs_human_decisions",
            "decisions": sorted(open_decisions),
            "waiting_slices": waiting,
        }
    unresolved = [
        fid for fid, finding in state["findings"].items()
        if finding.get("merge_blocking", True) and finding_open(finding)
    ]
    if unresolved:
        return {"action": "resolve_or_reassign_findings", "findings": sorted(unresolved), "waiting_slices": waiting}
    if stale_deliveries:
        sid = stale_deliveries[0]
        return {
            "action": "record_delivery",
            "slice": sid,
            "remaining_stale_slices": stale_deliveries,
            "operation": "record_delivery",
        }
    passes = state["fixed_point"].get("passes", [])
    if (
        not passes
        or passes[-1].get("new_merge_blocking_findings") != 0
        or passes[-1].get("semantic_revision") != state.get("semantic_revision")
    ):
        return {"action": "rescan_fixed_point", "pass": len(passes) + 1}
    if state["policy"].get("github_projection") == "sync" and state["github"].get("outbox"):
        return {"action": "synchronize_github", "outbox_items": len(state["github"]["outbox"])}
    return {"action": "completion_check_then_mark_ready"}


def status_document(state: dict[str, Any]) -> dict[str, Any]:
    slices: dict[str, Any] = {}
    for sid, slice_ in sorted(state["slices"].items()):
        progress = slice_progress(slice_)
        owners = sorted(required_findings_for_slice(state, sid))
        slices[sid] = {
            "name": slice_.get("name"),
            "merge_policy": slice_.get("merge_policy"),
            "next_phase": progress["next_phase"],
            "phases_passed": progress["passed"],
            "complete": slice_complete(state, sid),
            "findings": owners,
            "open_findings": [fid for fid in owners if finding_open(state["findings"][fid])],
        }
    return {
        "run_id": state["run"].get("id"),
        "run_state": state["run"].get("state"),
        "revision": state.get("revision"),
        "source": state.get("source"),
        "counts": {
            "slices": len(state["slices"]),
            "findings": len(state["findings"]),
            "open_findings": sum(1 for finding in state["findings"].values() if finding_open(finding)),
            "foreign_facts": len(state["foreign_facts"]),
            "open_decisions": sum(1 for decision in state["decisions"].values() if decision.get("status") == "open"),
            "github_outbox": len(state["github"].get("outbox", [])),
        },
        "active_operation": state.get("active_operation"),
        "slices": slices,
        "next": derive_next_action(state),
    }


def legacy_load(path: Path) -> dict[str, Any]:
    text = read_text_no_follow(path, "legacy tracker")
    try:
        return loads_strict(text, str(path))
    except NonFiniteNumberError:
        raise
    except BdrError as json_error:
        try:
            import yaml  # type: ignore
        except ImportError as exc:
            raise BdrError(
                "V1 migration needs PyYAML only when the legacy file is not strict JSON-compatible YAML. "
                "Run migration in the old tool's environment or install PyYAML temporarily. "
                f"Strict parser detail: {json_error}"
            ) from exc

        class UniqueSafeLoader(yaml.SafeLoader):  # type: ignore[name-defined]
            pass

        def construct_mapping(loader: Any, node: Any, deep: bool = False) -> dict[Any, Any]:
            mapping: dict[Any, Any] = {}
            for key_node, value_node in node.value:
                key = loader.construct_object(key_node, deep=deep)
                if key in mapping:
                    raise DuplicateKeyError(f"duplicate legacy YAML key {key!r} at line {key_node.start_mark.line + 1}")
                mapping[key] = loader.construct_object(value_node, deep=deep)
            return mapping

        UniqueSafeLoader.add_constructor(  # type: ignore[attr-defined]
            yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, construct_mapping  # type: ignore[name-defined]
        )
        try:
            value = yaml.load(text, Loader=UniqueSafeLoader)  # type: ignore[name-defined]
        except Exception as exc:
            raise BdrError(f"cannot parse legacy tracker {path}: {exc}") from exc
        if not isinstance(value, dict):
            raise BdrError("legacy tracker must contain a top-level mapping")
        return json_safe(value)


def json_safe(
    value: Any,
    *,
    _depth: int = 0,
    _stack: set[int] | None = None,
    _budget: list[int] | None = None,
) -> Any:
    """Convert legacy YAML values to bounded, acyclic, finite JSON data."""
    if _depth > MAX_MIGRATION_DEPTH:
        raise BdrError(f"legacy tracker exceeds migration depth limit {MAX_MIGRATION_DEPTH}")
    stack = _stack if _stack is not None else set()
    budget = _budget if _budget is not None else [MAX_MIGRATION_NODES]
    budget[0] -= 1
    if budget[0] < 0:
        raise BdrError(f"legacy tracker exceeds migration node limit {MAX_MIGRATION_NODES}")
    if value is None or isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise BdrError("legacy tracker contains NaN or Infinity")
        return value
    if isinstance(value, (dict, list, tuple)):
        identity = id(value)
        if identity in stack:
            raise BdrError("legacy tracker contains a recursive YAML alias")
        stack.add(identity)
        try:
            if isinstance(value, dict):
                converted: dict[str, Any] = {}
                for key, child in value.items():
                    safe_key_value = json_safe(
                        key, _depth=_depth + 1, _stack=stack, _budget=budget,
                    )
                    if isinstance(safe_key_value, (dict, list)):
                        raise BdrError("legacy tracker contains a non-scalar mapping key")
                    safe_key = str(safe_key_value)
                    if safe_key in converted:
                        raise DuplicateKeyError(
                            f"legacy mapping keys collide after JSON conversion: {safe_key!r}"
                        )
                    converted[safe_key] = json_safe(
                        child, _depth=_depth + 1, _stack=stack, _budget=budget,
                    )
                return converted
            return [
                json_safe(child, _depth=_depth + 1, _stack=stack, _budget=budget)
                for child in value
            ]
        finally:
            stack.remove(identity)
    rendered = str(value)
    budget[0] -= len(rendered) // 1024
    if budget[0] < 0:
        raise BdrError(f"legacy tracker exceeds migration node limit {MAX_MIGRATION_NODES}")
    return rendered


def migrate_v1_state(legacy: dict[str, Any], root: Path, args: argparse.Namespace) -> dict[str, Any]:
    state = new_state(root, args)
    state["run"]["state"] = "needs_human"
    state["run"]["terminal_reason"] = "V1 import requires code-read boundary and evidence review"
    migration_evidence = add_evidence_record(state, {
        "kind": "migration",
        "claim": "Values below were imported as historical hints, not revalidated evidence.",
        "source": str(args.from_path),
    }, "E-0001")
    unresolved: list[str] = []

    old_slices = legacy.get("slices", [])
    if isinstance(old_slices, dict):
        old_slices = [dict(value, id=key) if isinstance(value, dict) else {"id": key} for key, value in old_slices.items()]
    if not isinstance(old_slices, list):
        old_slices = []
    slice_ids: dict[str, str] = {}
    for old in old_slices:
        if not isinstance(old, dict):
            continue
        old_id = str(old.get("id", len(slice_ids)))
        sid = next_id(state["slices"], "S")
        slice_ids[old_id] = sid
        raw_boundary = old.get("boundary") or old.get("name") or "unknown migrated boundary"
        predictions = ((old.get("collapse_claim") or {}).get("predictions") if isinstance(old.get("collapse_claim"), dict) else []) or []
        if not isinstance(predictions, list):
            predictions = [str(predictions)]
        state["slices"][sid] = {
            "name": str(old.get("name") or f"Migrated slice {old_id}"),
            "kind": "boundary",
            "merge_policy": "required" if old.get("merge_blocking", True) else "optional",
            "boundary": {
                "authority": "UNKNOWN_PENDING_MIGRATION_REVIEW",
                "fact": str(raw_boundary).strip(),
                "consumer_decision": "UNKNOWN_PENDING_MIGRATION_REVIEW",
            },
            "depends_on": [],
            "collapse_predictions": {f"P-{i:04d}": str(item) for i, item in enumerate(predictions, 1)},
            "operational_obligations": [],
            "phase_attempts": [],
            "deliveries": [],
            "legacy": {"id": old.get("id"), "status": old.get("status"), "snapshot": old},
            "created_at": utc_now(),
        }
        unresolved.append(f"{sid}: verify authority, fact, consumer decision, dependencies, and phase evidence")
    for old in old_slices:
        if not isinstance(old, dict):
            continue
        sid = slice_ids.get(str(old.get("id", "")))
        if not sid:
            continue
        deps = old.get("depends_on", [])
        if isinstance(deps, list):
            state["slices"][sid]["depends_on"] = [slice_ids[str(dep)] for dep in deps if str(dep) in slice_ids]

    old_findings = legacy.get("findings", {})
    iterable = old_findings.items() if isinstance(old_findings, dict) else enumerate(old_findings, 1) if isinstance(old_findings, list) else []
    for old_id, old in iterable:
        if not isinstance(old, dict):
            continue
        fid = next_id(state["findings"], "F")
        state["findings"][fid] = {
            "title": str(old.get("what") or f"Migrated finding {old_id}"),
            "site": str(old.get("where") or "UNKNOWN_PENDING_MIGRATION_REVIEW"),
            "severity": str(old.get("severity") or "unspecified"),
            "merge_blocking": True,
            "found_by": str(old.get("found") or "legacy"),
            "missing_fact": {
                "authority": "UNKNOWN_PENDING_MIGRATION_REVIEW",
                "fact": str(old.get("k") or old.get("what") or "UNKNOWN_PENDING_MIGRATION_REVIEW"),
                "consumer_decision": "UNKNOWN_PENDING_MIGRATION_REVIEW",
                "inferred_from": "UNKNOWN_PENDING_MIGRATION_REVIEW",
                "initial_shape": "temporal" if old.get("k_kind") == "temporal" else "value",
                "normalized_as": "unclassified",
            },
            "fix_direction": str(old.get("fix") or "not_yet_derived"),
            "origin": None,
            "ownership": [],
            "unassigned_reason": "V1 ownership is untrusted until K is re-read in code",
            "resolution": None,
            "resolution_history": [],
            "legacy": {
                "id": old_id,
                "status": old.get("status"),
                "proposed_slice": slice_ids.get(str(old.get("slice"))),
                "snapshot": old,
            },
            "created_at": utc_now(),
        }
        unresolved.append(f"{fid}: re-derive information-flow edge, owner, and typed resolution")

    old_facts = legacy.get("foreign_facts", [])
    if isinstance(old_facts, dict):
        old_facts = list(old_facts.values())
    if isinstance(old_facts, list):
        for old in old_facts:
            if not isinstance(old, dict):
                continue
            ffid = next_id(state["foreign_facts"], "FF")
            state["foreign_facts"][ffid] = {
                "subject": {
                    "symbol": str(old.get("symbol") or "UNKNOWN_PENDING_MIGRATION_REVIEW"),
                    "version": str(old.get("version") or old.get("api") or "UNKNOWN_PENDING_MIGRATION_REVIEW"),
                },
                "claim": str(old.get("assumed") or old.get("claim") or "UNKNOWN_PENDING_MIGRATION_REVIEW"),
                "consequence_if_wrong": str(old.get("consequence_if_wrong") or "UNKNOWN_PENDING_MIGRATION_REVIEW"),
                "disposition": {"kind": "assumed"},
                "legacy_evidence": old.get("evidence"),
                "migration_evidence": migration_evidence,
            }
            unresolved.append(f"{ffid}: re-establish or eliminate the foreign fact")

    state["migration"] = {
        "from_schema": "bdr-v1",
        "source": str(args.from_path),
        "imported_at": utc_now(),
        "unresolved": unresolved,
        "warning": "No legacy phase or finding status was accepted as proof.",
    }
    errors = validate_state(state)
    if errors:
        raise BdrError("migrated state is invalid: " + "; ".join(f"{r}: {m}" for r, m in errors))
    return state


def fixture_state(root: Path) -> dict[str, Any]:
    args = argparse.Namespace(
        head_sha=None, base_sha=git_value(root, "rev-parse", "HEAD^"), repository="selftest",
        pr=None, github_mode="off", max_fixed_point_passes=3, max_phase_attempts=3, run_id="BDR-SELFTEST",
    )
    state = new_state(root, args)
    # This fixture intentionally models the fully command-backed 2.1 form.
    state["minimum_validator_version"] = "2.1.0"
    command = [{"command": "fixture-test", "exit_code": 0, "output_digest": "sha256:fixture"}]
    state["run"]["baseline"] = {"usable": True, "commands": command, "captured_at": utc_now()}
    snapshot = workspace_snapshot(root, include_content_delta=True)
    state["checkpoints"] = {
        "CP-0001": {"label": "fixture-pre", **snapshot},
        "CP-0002": {"label": "fixture-post", **snapshot},
    }
    state["evidence"] = {
        "E-0001": {"kind": "code_read", "claim": "K belongs to S-0001"},
        "E-0002": {
            "kind": "test", "claim": "finding passes",
            "commands": [{
                "command": "fixture-passing-test", "exit_code": 0,
                "output_digest": "sha256:passing-test",
            }],
        },
        "E-0003": {
            "kind": "counterfactual_test", "claim": "reversion fails",
            "commands": [{
                "command": "fixture-counterfactual", "exit_code": 1,
                "output_digest": "sha256:counterfactual-failure",
            }],
        },
        "E-0010": {"kind": "rescan", "claim": "no new merge blockers"},
    }
    state["findings"]["F-0001"] = {
        "title": "fixture finding", "site": "Fixture.java:1", "severity": "high", "merge_blocking": True,
        "found_by": "selftest",
        "missing_fact": {
            "authority": "producer", "fact": "ownership", "consumer_decision": "release",
            "inferred_from": "registry membership", "initial_shape": "temporal", "normalized_as": "ownership",
        },
        "fix_direction": "carry an explicit owner token", "origin": None,
        "ownership": [{
            "from": None, "to": "S-0001", "k_verification": "E-0001",
            "at": utc_now(), "revision": 0,
        }],
        "unassigned_reason": None,
        "resolution": {"kind": "fixed", "passing_test": "E-0002", "counterfactual_test": "E-0003", "at": utc_now()},
        "resolution_history": [],
    }
    state["slices"]["S-0001"] = {
        "name": "explicit release authority", "kind": "boundary", "merge_policy": "required",
        "boundary": {"authority": "producer", "fact": "ownership", "consumer_decision": "release"},
        "depends_on": [], "collapse_predictions": {}, "operational_obligations": [], "phase_attempts": [],
        "deliveries": [{
            "kind": "no_code_change", "evidence": "E-0002", "reason": "selftest fixture",
            "semantic_revision": state["semantic_revision"],
        }],
    }
    gates = {
        "expose": {
            "finding_id": "F-0001", "test": "fixtureTest", "baseline_ref": "run.baseline",
            "failed_at_assertion": True, "assertion_fingerprint": "expected explicit owner token",
            "input_space": ["owner", "borrower"],
        },
        "represent": {"behavior_changed": False, "artifacts": ["OwnerToken"]},
        "route": {
            "producers": ["producer"], "consumers": ["release"], "predictions_frozen": True,
            "new_abstraction_introduced": False, "introduced": [],
        },
        "collapse": {"prediction_verdicts": {}, "died": [], "no_death_expected": "direct boundary"},
        "saturate": {"structural_tests": ["illegal state rejected"], "operational_proofs": {}, "input_space_covered": True},
        "falsify": {"finding_verdicts": {"F-0001": "fixed"}, "rescan": {"performed": True}},
    }
    for index, phase in enumerate(PHASES, 20):
        evidence_id = f"E-{index:04d}"
        phase_commands = (
            [{"command": "fixture-expose-test", "exit_code": 1, "output_digest": "sha256:expected-failure"}]
            if phase == "expose" else command
        )
        state["evidence"][evidence_id] = {
            "kind": "phase_gate", "phase": phase, "slice": "S-0001", "commands": phase_commands,
            "foreign_fact_review": {"performed": True, "reviewed": []}, **gates[phase],
        }
        state["slices"]["S-0001"]["phase_attempts"].append({
            "phase": phase, "result": "passed", "pre_checkpoint": "CP-0001",
            "post_checkpoint": "CP-0002", "gate_evidence": evidence_id, "starting_revision": 0,
        })
    state["fixed_point"]["passes"] = [{
        "number": 1, "new_merge_blocking_findings": 0, "evidence": "E-0010",
        "semantic_revision": state["semantic_revision"], "checkpoint": "CP-0002",
    }]
    state["run"]["state"] = "ready_for_review"
    return state


def selftest() -> list[str]:
    passed: list[str] = []
    with tempfile.TemporaryDirectory(prefix="bdr-selftest-") as temporary:
        root = Path(temporary)
        run_command(["git", "init", "-q"], root)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], root)
        run_command(["git", "config", "user.name", "BDR Selftest"], root)
        (root / "Fixture.java").write_text("final class Fixture {}\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java"], root)
        run_command(["git", "commit", "-q", "-m", "fixture base"], root)
        (root / "Fixture.java").write_text("final class Fixture { int value; }\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java"], root)
        run_command(["git", "commit", "-q", "-m", "fixture head"], root)
        state = fixture_state(root)
        errors = validate_state(state)
        if errors:
            raise BdrError("selftest fixture is not valid: " + render_validation(errors))
        passed.append("valid fixture")

        legacy_v2 = copy.deepcopy(state)
        if validate_state(legacy_v2):
            raise BdrError("safe command-backed 2.1.0 tracker is no longer valid")
        passed.append("safe command-backed 2.1.0 tracker compatibility")

        upgraded_legacy_history = copy.deepcopy(state)
        upgraded_legacy_history["minimum_validator_version"] = LEAN_GATE_MINIMUM_VERSION
        upgraded_legacy_history["run"]["state"] = "auditing"
        if validate_state(upgraded_legacy_history):
            raise BdrError("raising the validator floor invalidated historical 2.1 gates or passes")
        passed.append("historical 2.1 evidence survives a later validator upgrade")

        lean_state = copy.deepcopy(state)
        lean_state["minimum_validator_version"] = LEAN_GATE_MINIMUM_VERSION
        lean_state["fixed_point"]["passes"][0]["commands"] = [{
            "command": "fixture-final-suite", "exit_code": 0,
            "output_digest": "sha256:final-suite",
        }]
        for evidence_id in ("E-0021", "E-0022", "E-0023"):
            lean_state["evidence"][evidence_id].pop("commands")
        lean_state["evidence"]["E-0025"].pop("commands")
        lean_state["evidence"]["E-0025"]["saturate_evidence"] = "E-0024"
        lean_state["evidence"]["E-0025"]["validator_version"] = LEAN_GATE_MINIMUM_VERSION
        lean_state["findings"]["F-0001"]["resolution"]["passing_test"] = "E-0024"
        lean_delivery = lean_state["slices"]["S-0001"]["deliveries"][0]
        lean_delivery.update({
            "kind": "commit", "evidence": "E-0024", "sha": "1" * 40, "tree": "2" * 40,
            "subject": "lean fixture", "verified_checkpoint": "CP-0002",
            "content_delta_sha256": lean_state["checkpoints"]["CP-0002"]["content_delta_sha256"],
        })
        lean_delivery.pop("reason", None)
        if validate_state(lean_state):
            raise BdrError("lean structural gates and SATURATE reuse were rejected")
        passed.append("lean structural gates and SATURATE reuse")

        for evidence_id in ("E-0021", "E-0022", "E-0023"):
            for label, commands in (
                ("empty", []),
                ("malformed", [{"command": "fixture", "exit_code": 0}]),
                ("failing", [{"command": "fixture", "exit_code": 1, "output_digest": "sha256:failure"}]),
            ):
                broken = copy.deepcopy(lean_state)
                broken["evidence"][evidence_id]["commands"] = commands
                if "V005" not in {rule for rule, _ in validate_state(broken)}:
                    raise BdrError(f"optional structural {label} commands were silently accepted")
        passed.append("optional structural command records remain strict")

        for label, mutation in (
            ("missing", lambda gate: gate.pop("commands")),
            ("empty", lambda gate: gate.update(commands=[])),
            ("all green", lambda gate: gate.update(commands=[{
                "command": "fixture-expose", "exit_code": 0, "output_digest": "sha256:green",
            }])),
        ):
            broken = copy.deepcopy(state)
            mutation(broken["evidence"]["E-0020"])
            if "V005" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"EXPOSE {label} command evidence was accepted")
        passed.append("EXPOSE still requires intended red evidence")

        for label, mutation in (
            ("missing", lambda gate: gate.pop("commands")),
            ("empty", lambda gate: gate.update(commands=[])),
            ("failing", lambda gate: gate.update(commands=[{
                "command": "fixture-saturate", "exit_code": 1, "output_digest": "sha256:failure",
            }])),
        ):
            broken = copy.deepcopy(state)
            mutation(broken["evidence"]["E-0024"])
            if "V005" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"SATURATE {label} command evidence was accepted")
        passed.append("SATURATE still requires focused green evidence")

        for label, commands in (
            ("missing", None),
            ("all green", [{
                "command": "fixture-counterfactual", "exit_code": 0,
                "output_digest": "sha256:unexpected-success",
            }]),
        ):
            broken = copy.deepcopy(state)
            if commands is None:
                broken["evidence"]["E-0003"].pop("commands")
            else:
                broken["evidence"]["E-0003"]["commands"] = commands
            if "V004" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"fixed resolution accepted {label} counterfactual command evidence")
        passed.append("fixed resolution requires red counterfactual command evidence")

        for label, commands in (
            ("missing", None),
            ("all red", [{
                "command": "fixture-passing-test", "exit_code": 1,
                "output_digest": "sha256:passing-test-failure",
            }]),
        ):
            broken = copy.deepcopy(state)
            if commands is None:
                broken["evidence"]["E-0002"].pop("commands")
            else:
                broken["evidence"]["E-0002"]["commands"] = commands
            if "V004" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"fixed resolution accepted {label} passing command evidence")
        passed.append("fixed resolution requires green standalone passing command evidence")

        for label, commands in (
            ("missing", None),
            ("all red", [{
                "command": "fixture-passing-test", "exit_code": 1,
                "output_digest": "sha256:passing-test-failure",
            }]),
        ):
            broken = copy.deepcopy(state)
            broken["minimum_validator_version"] = LEAN_GATE_MINIMUM_VERSION
            previous = copy.deepcopy(broken["findings"]["F-0001"]["resolution"])
            previous.update({
                "reopened_at": utc_now(),
                "reopen_evidence": "E-0001",
                "verification_slice": "S-0001",
            })
            broken["findings"]["F-0001"]["resolution_history"] = [previous]
            broken["findings"]["F-0001"]["resolution"] = {
                "kind": "superseded", "evidence": "E-0001", "at": utc_now(),
            }
            if commands is None:
                broken["evidence"]["E-0002"].pop("commands")
            else:
                broken["evidence"]["E-0002"]["commands"] = commands
            if "V004" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"historical resolution accepted {label} passing command evidence")
        passed.append("historical resolution requires green standalone passing command evidence")

        for label, commands in (
            ("missing", None),
            ("failing", [{
                "command": "fixture-baseline", "exit_code": 1,
                "output_digest": "sha256:baseline-failure",
            }]),
        ):
            broken = copy.deepcopy(state)
            if commands is None:
                broken["run"]["baseline"].pop("commands")
            else:
                broken["run"]["baseline"]["commands"] = commands
            rules = {rule for rule, _ in validate_state(broken)}
            if "V001" not in rules:
                raise BdrError(f"ready state accepted {label} baseline command evidence")
        passed.append("usable baseline requires successful command evidence")

        falsify_without_proof = copy.deepcopy(lean_state)
        falsify_without_proof["evidence"]["E-0025"].pop("saturate_evidence")
        if "V005" not in {rule for rule, _ in validate_state(falsify_without_proof)}:
            raise BdrError("commandless FALSIFY without SATURATE reuse was accepted")
        for label, evidence_id in (
            ("missing", "E-NOPE"),
            ("wrong phase", "E-0023"),
            ("unlinked clone", "E-0099"),
        ):
            broken = copy.deepcopy(lean_state)
            if evidence_id == "E-0099":
                broken["evidence"][evidence_id] = copy.deepcopy(broken["evidence"]["E-0024"])
            broken["evidence"]["E-0025"]["saturate_evidence"] = evidence_id
            if "V005" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"FALSIFY {label} SATURATE reference was accepted")
        passed.append("FALSIFY requires current live SATURATE evidence")

        for relation in ("before", "during"):
            for key, replacement in (
                ("head_sha", "0" * 40),
                ("worktree_sha256", "0" * 64),
                ("content_delta_sha256", "0" * 64),
                ("dirty", not state["checkpoints"]["CP-0002"]["dirty"]),
            ):
                broken = copy.deepcopy(lean_state)
                broken["checkpoints"]["CP-0003"] = copy.deepcopy(broken["checkpoints"]["CP-0002"])
                broken["checkpoints"]["CP-0003"][key] = replacement
                falsify_attempt = broken["slices"]["S-0001"]["phase_attempts"][-1]
                falsify_attempt["pre_checkpoint" if relation == "before" else "post_checkpoint"] = "CP-0003"
                if "V005" not in {rule for rule, _ in validate_state(broken)}:
                    raise BdrError(f"FALSIFY workspace change {relation} phase ({key}) was accepted")
        passed.append("SATURATE reuse is workspace-fingerprint bound")

        old_minimum_lean = copy.deepcopy(lean_state)
        old_minimum_lean["minimum_validator_version"] = "2.1.0"
        old_minimum_lean["fixed_point"]["passes"][0].pop("commands")
        if "V005" not in {rule for rule, _ in validate_state(old_minimum_lean)}:
            raise BdrError("lean evidence did not require the 2.2.0 validator")
        passed.append("lean evidence raises the validator floor")

        old_minimum_reuse = copy.deepcopy(state)
        old_minimum_reuse["minimum_validator_version"] = "2.1.0"
        old_minimum_reuse["findings"]["F-0001"]["resolution"]["passing_test"] = "E-0024"
        old_minimum_reuse["slices"]["S-0001"]["deliveries"][0]["evidence"] = "E-0024"
        reuse_rules = {rule for rule, _ in validate_state(old_minimum_reuse)}
        if not {"V004", "V008"}.issubset(reuse_rules):
            raise BdrError("SATURATE resolution/delivery reuse bypassed the 2.2.0 validator floor")
        passed.append("SATURATE resolution and delivery reuse require validator 2.2.0")

        old_minimum_falsify_field = copy.deepcopy(state)
        old_minimum_falsify_field["evidence"]["E-0025"]["saturate_evidence"] = "E-0024"
        if "V005" not in {rule for rule, _ in validate_state(old_minimum_falsify_field)}:
            raise BdrError("command-backed FALSIFY bypassed the SATURATE reuse validator floor")
        passed.append("SATURATE reuse field always raises the validator floor")

        command_only_falsify = copy.deepcopy(lean_state)
        command_only_falsify["evidence"]["E-0025"].pop("saturate_evidence")
        command_only_falsify["evidence"]["E-0025"]["commands"] = [{
            "command": "not-a-focused-proof", "exit_code": 0,
            "output_digest": "sha256:irrelevant-success",
        }]
        if "V005" not in {rule for rule, _ in validate_state(command_only_falsify)}:
            raise BdrError("2.2 FALSIFY accepted commands as a substitute for fresh SATURATE proof")
        passed.append("2.2 FALSIFY requires fresh SATURATE proof")

        null_review = copy.deepcopy(lean_state)
        null_review["evidence"]["E-0021"]["foreign_fact_review"] = None
        if "V005" not in {rule for rule, _ in validate_state(null_review)}:
            raise BdrError("explicit null foreign_fact_review was treated as omission")
        passed.append("explicit null review is rejected")

        for label, commands in (
            ("missing", None),
            ("empty", []),
            ("failing", [{
                "command": "fixture-final-suite", "exit_code": 1,
                "output_digest": "sha256:final-suite-failure",
            }]),
        ):
            broken = copy.deepcopy(lean_state)
            if commands is None:
                broken["fixed_point"]["passes"][0].pop("commands")
            else:
                broken["fixed_point"]["passes"][0]["commands"] = commands
            if "V008" not in {rule for rule, _ in validate_state(broken)}:
                raise BdrError(f"2.2 fixed point accepted {label} final-suite commands")
        passed.append("2.2 fixed point requires successful final-suite commands")

        cases: list[tuple[str, Callable[[dict[str, Any]], None], str]] = [
            ("schema", lambda s: s.update(schema="wrong"), "V001"),
            ("boolean revision", lambda s: s.update(revision=True), "V001"),
            ("dependency", lambda s: s["slices"]["S-0001"].update(depends_on=["S-0001"]), "V002"),
            ("ownership", lambda s: s["findings"]["F-0001"]["ownership"][0].update(k_verification="E-NOPE"), "V003"),
            ("resolution", lambda s: s["findings"]["F-0001"].update(resolution={"kind": "split", "remainders": ["F-NOPE"]}), "V004"),
            ("phase gate", lambda s: s["evidence"]["E-0020"].pop("input_space"), "V005"),
            ("phase gate slice", lambda s: s["evidence"]["E-0020"].update(slice="S-NOPE"), "V005"),
            ("EXPOSE finding", lambda s: s["evidence"]["E-0020"].update(finding_id="F-NOPE"), "V005"),
            ("introduced risk", lambda s: s["evidence"]["E-0022"].update(introduced=[{"what": "cache", "risk": {"comparison": "higher"}}]), "V006"),
            ("foreign fact", lambda s: s["foreign_facts"].update({"FF-0001": {
                "subject": {"symbol": "x", "version": "1"}, "claim": "x", "consequence_if_wrong": "bad",
                "disposition": {"kind": "measured", "evidence": "E-NOPE"},
            }}), "V007"),
            ("readiness", lambda s: s["fixed_point"].update(passes=[]), "V008"),
            ("delivery evidence kind", lambda s: s["slices"]["S-0001"]["deliveries"][0].update(evidence="E-0001"), "V008"),
            ("fixed-point evidence kind", lambda s: s["fixed_point"]["passes"][0].update(evidence="E-0001"), "V008"),
            ("malformed phase attempt", lambda s: s["slices"]["S-0001"].update(phase_attempts=[None]), "V001"),
            ("malformed ownership event", lambda s: s["findings"]["F-0001"].update(ownership=["bad"]), "V003"),
            ("scalar operational obligations", lambda s: s["slices"]["S-0001"].update(operational_obligations=1), "V001"),
            ("unhashable phase gate", lambda s: s["evidence"]["E-0020"].update(phase={}), "V005"),
            ("unhashable checkpoint", lambda s: s["slices"]["S-0001"]["phase_attempts"][0].update(post_checkpoint=[]), "V002"),
            ("active operation", lambda s: s.update(active_operation={"slice": "S-NOPE", "phase": "route"}), "V010"),
        ]
        for name, corrupt, expected in cases:
            broken = copy.deepcopy(state)
            corrupt(broken)
            codes = {rule for rule, _ in validate_state(broken)}
            if expected not in codes:
                raise BdrError(f"selftest mutation {name!r} did not trigger {expected}; got {sorted(codes)}")
            passed.append(f"{expected} mutation")

        if {rule for rule, _ in validate_state(1)} != {"V001"}:
            raise BdrError("non-object tracker root was not rejected cleanly")
        passed.append("total validator root rejection")

        empty_inventory = copy.deepcopy(state)
        empty_inventory["slices"].clear()
        empty_inventory["findings"].clear()
        empty_inventory["github"] = {"mappings": {}, "outbox": []}
        empty_codes = {rule for rule, _ in validate_state(empty_inventory)}
        if "V008" not in empty_codes:
            raise BdrError("ready state with an empty audit inventory was accepted")
        passed.append("non-empty audit inventory readiness")

        short_ref = copy.deepcopy(state)
        short_ref["source"]["base_sha"] = short_ref["source"]["base_sha"][:8]
        if "V001" not in {rule for rule, _ in validate_state(short_ref)}:
            raise BdrError("tracker with an abbreviated source commit was accepted")
        if canonical_commit_oid(root, "HEAD^", "selftest base") != git_value(root, "rev-parse", "HEAD^"):
            raise BdrError("symbolic commit reference was not canonicalized")
        try:
            canonical_commit_oid(root, "HEAD HEAD^", "ambiguous selftest")
            raise BdrError("multi-result commit syntax was accepted")
        except BdrError as exc:
            if "multi-result commit syntax" in str(exc):
                raise
        unsafe_selector = copy.deepcopy(state)
        unsafe_selector["source"]["pr"] = {"selector": "--repo"}
        if "V001" not in {rule for rule, _ in validate_state(unsafe_selector)}:
            raise BdrError("unsafe GitHub PR selector was accepted")
        if (
            not valid_pr_selector("https://github.example.invalid/team/project/pull/42")
            or valid_pr_selector("http://github.example.invalid/team/project/pull/42")
            or valid_pr_selector("https://user@github.example.invalid/team/project/pull/42")
        ):
            raise BdrError("safe GitHub Enterprise PR URL validation failed")
        passed.append("canonical single-commit source refs")

        split_state = copy.deepcopy(state)
        remainder = copy.deepcopy(split_state["findings"]["F-0001"])
        remainder.update({
            "merge_blocking": False,
            "origin": {"parent": "F-0001", "scope": "remaining path"},
            "ownership": [],
            "unassigned_reason": "awaiting split assignment",
            "resolution": None,
        })
        split_state["findings"]["F-0002"] = remainder
        split_state["findings"]["F-0001"]["resolution"] = {
            "kind": "split", "remainders": ["F-0002", "F-0002"], "fixed_scope": "original path",
            "passing_test": "E-0002", "counterfactual_test": "E-0003",
        }
        split_messages = [message for rule, message in validate_state(split_state) if rule == "V004"]
        if not any("unique" in message for message in split_messages) or not any(
            "non-blocking split remainder" in message for message in split_messages
        ):
            raise BdrError("split validation did not preserve uniqueness and merge-blocking scope")

        moved_state = copy.deepcopy(state)
        moved_state["revision"] = 1
        moved_state["slices"]["S-0002"] = copy.deepcopy(moved_state["slices"]["S-0001"])
        moved_state["slices"]["S-0002"].update({
            "name": "new owner", "merge_policy": "optional", "phase_attempts": [], "deliveries": [],
        })
        moved_state["findings"]["F-0001"]["ownership"].append({
            "from": "S-0001", "to": "S-0002", "at": utc_now(), "revision": 1,
            "reason": "boundary correction", "k_verification": "E-0001",
        })
        moved_gate = moved_state["evidence"]["E-0025"]
        moved_gate["finding_verdicts"] = {"F-0001": {"verdict": "moved", "ownership_revision": 0}}
        moved_messages = [message for rule, message in validate_state(moved_state) if rule == "V005"]
        if not any("latest ownership departure revision" in message for message in moved_messages):
            raise BdrError("stale moved verdict was accepted")
        moved_gate["finding_verdicts"]["F-0001"]["ownership_revision"] = 1
        if any(
            "latest ownership departure revision" in message
            for rule, message in validate_state(moved_state) if rule == "V005"
        ):
            raise BdrError("fresh moved verdict was rejected")
        moved_state["revision"] = 10
        moved_state["slices"]["S-0001"]["phase_attempts"][-1]["starting_revision"] = 9
        if not any(
            "latest ownership departure revision" in message
            for rule, message in validate_state(moved_state) if rule == "V005"
        ):
            raise BdrError("pre-FALSIFY ownership departure was accepted as a fresh moved verdict")
        passed.append("split preservation and revision-bound moved verdicts")

        rewound_state = copy.deepcopy(state)
        apply_one(rewound_state, {
            "type": "rewind_phase", "slice": "S-0001", "rewind_to": "represent",
            "reason": "representation claim changed", "evidence": "E-0001",
        }, root)
        apply_one(rewound_state, {
            "type": "configure_slice", "id": "S-0001",
            "changes": {"collapse_predictions": {"P-new": "new representation removes the legacy branch"}},
        }, root)
        rewound_phase_errors = [
            message for rule, message in validate_state(rewound_state)
            if rule == "V005" and "COLLAPSE" in message
        ]
        if rewound_phase_errors:
            raise BdrError(
                "inactive historical COLLAPSE gates froze current predictions: "
                + "; ".join(rewound_phase_errors)
            )
        passed.append("rewound historical gates do not freeze current claims")

        for rewind_target in ("falsify", "saturate"):
            lean_rewind = copy.deepcopy(lean_state)
            apply_one(lean_rewind, {
                "type": "rewind_phase", "slice": "S-0001", "rewind_to": rewind_target,
                "reason": "final boundary changed", "evidence": "E-0001",
            }, root)
            lean_rewind["semantic_revision"] += 1
            if validate_state(lean_rewind):
                raise BdrError(
                    f"SATURATE-backed resolution or delivery made {rewind_target} rewind impossible"
                )
            reopened = lean_rewind["findings"]["F-0001"]
            if reopened["resolution"] is not None or not reopened["resolution_history"]:
                raise BdrError(
                    f"{rewind_target} rewind did not preserve and reopen the SATURATE-backed resolution"
                )
        passed.append("rewind reopens SATURATE-backed resolutions and stales delivery")

        reassigned_history = copy.deepcopy(lean_rewind)
        reassigned_history["evidence"]["E-REASSIGN"] = {
            "kind": "code_read", "claim": "re-derived owner after rewind",
        }
        reassigned_history["slices"]["S-0002"] = {
            "name": "reassigned boundary", "kind": "boundary", "merge_policy": "optional",
            "boundary": {"authority": "producer", "fact": "ownership", "consumer_decision": "release"},
            "depends_on": [], "collapse_predictions": {}, "operational_obligations": [],
            "phase_attempts": [], "deliveries": [],
        }
        next_revision = reassigned_history["revision"] + 1
        reassigned_history["findings"]["F-0001"]["ownership"].append({
            "from": "S-0001", "to": "S-0002", "k_verification": "E-REASSIGN",
            "at": utc_now(), "revision": next_revision,
        })
        reassigned_history["revision"] = next_revision
        if validate_state(reassigned_history):
            raise BdrError("reassigning a rewound finding invalidated its historical SATURATE proof")
        passed.append("rewound SATURATE proof retains its original verification slice")

        old_floor_history = copy.deepcopy(lean_rewind)
        old_floor_history["minimum_validator_version"] = "2.1.0"
        if "V004" not in {rule for rule, _ in validate_state(old_floor_history)}:
            raise BdrError("fixed resolution history bypassed the 2.2 validator floor")
        passed.append("fixed resolution history requires validator 2.2")

        try:
            loads_strict('{"a": 1, "a": 2}', "duplicate fixture")
            raise BdrError("duplicate-key fixture was accepted")
        except DuplicateKeyError:
            passed.append("duplicate key rejection")

        for token in ("NaN", "Infinity", "-Infinity"):
            try:
                loads_strict(f'{{"value": {token}}}', "non-finite fixture")
                raise BdrError(f"non-finite token {token} was accepted")
            except NonFiniteNumberError:
                pass
        try:
            canonical_bytes({"value": float("nan")})
            raise BdrError("canonical JSON accepted NaN")
        except BdrError as exc:
            if "finite JSON" not in str(exc):
                raise
        passed.append("non-finite number rejection")

        recursive: list[Any] = []
        recursive.append(recursive)
        try:
            json_safe(recursive)
            raise BdrError("recursive legacy alias was accepted")
        except BdrError as exc:
            if "recursive YAML alias" not in str(exc):
                raise
        too_deep: list[Any] = []
        cursor = too_deep
        for _ in range(MAX_MIGRATION_DEPTH + 2):
            child: list[Any] = []
            cursor.append(child)
            cursor = child
        try:
            json_safe(too_deep)
            raise BdrError("over-depth legacy value was accepted")
        except BdrError as exc:
            if "depth limit" not in str(exc):
                raise
        try:
            json_safe([1, 2], _budget=[1])
            raise BdrError("over-budget legacy value was accepted")
        except BdrError as exc:
            if "node limit" not in str(exc):
                raise
        try:
            json_safe(float("inf"))
            raise BdrError("non-finite legacy float was accepted")
        except BdrError as exc:
            if "NaN or Infinity" not in str(exc):
                raise
        passed.append("bounded cycle-safe legacy conversion")

        malformed_progress = replay_slice({"phase_attempts": [None, "bad"]})
        if malformed_progress[0] != 0 or len(malformed_progress[1]) != 2:
            raise BdrError("malformed phase attempts did not produce total replay errors")
        passed.append("total malformed phase replay")

        for operation in (
            {
                "type": "record_delivery", "slice": "S-0001", "kind": "no_code_change",
                "reason": "wrong evidence kind", "evidence": "E-0001",
            },
            {
                "type": "record_fixed_point",
                "pass": {"new_merge_blocking_findings": 0, "evidence": "E-0001"},
            },
        ):
            try:
                apply_one(copy.deepcopy(state), operation, root)
                raise BdrError(f"writer accepted wrong evidence kind for {operation['type']}")
            except BdrError as exc:
                if "requires existing" not in str(exc):
                    raise
        passed.append("typed delivery and fixed-point evidence")

        symlink_supported = True
        # The symlink target is an engine-owned file excluded from the code
        # snapshot, so only the link entry itself may influence the digest.
        audit_target = root / ".bdr" / "progress.yaml"
        audit_target.parent.mkdir(parents=True, exist_ok=True)
        audit_target.write_text("secret-one\n", encoding="utf-8")
        workspace_link = root / "UntrackedLink"
        try:
            workspace_link.symlink_to(".bdr/progress.yaml")
        except OSError:
            symlink_supported = False
        if symlink_supported:
            first_link_snapshot = workspace_snapshot(root)
            audit_target.write_text("secret-two-with-different-bytes\n", encoding="utf-8")
            second_link_snapshot = workspace_snapshot(root)
            audit_target.unlink()
            dangling_link_snapshot = workspace_snapshot(root)
            if (
                not first_link_snapshot["dirty"]
                or first_link_snapshot["worktree_sha256"] != second_link_snapshot["worktree_sha256"]
                or second_link_snapshot["worktree_sha256"] != dangling_link_snapshot["worktree_sha256"]
                or not working_tree_changes(root)
            ):
                raise BdrError("untracked symlink target was followed or omitted from workspace identity")
            workspace_link.unlink()
            passed.append("untracked and dangling symlink workspace identity")
        elif audit_target.exists():
            audit_target.unlink()

        if symlink_supported:
            state_link = root / ".dangling-state"
            state_link.symlink_to(".bdr/no-such-state")
            try:
                state_path(str(state_link), root)
                raise BdrError("state_path accepted a dangling state symlink")
            except BdrError as exc:
                if "symlink" not in str(exc):
                    raise
            try:
                load_json_file(state_link)
                raise BdrError("state loader followed a dangling symlink")
            except BdrError as exc:
                if "symlink" not in str(exc):
                    raise
            state_link.unlink()

            dangling_audit = root / ".dangling-audit"
            dangling_audit.mkdir()
            dangling_events = dangling_audit / "events.jsonl"
            dangling_events.symlink_to("missing-events-target")
            dangling_tracker = dangling_audit / "progress.yaml"
            dangling_state = copy.deepcopy(state)
            try:
                write_initial(dangling_tracker, dangling_state, {"type": "init"}, "selftest")
                raise BdrError("initializer accepted a dangling event-journal symlink")
            except BdrError as exc:
                if "refusing to overwrite" not in str(exc):
                    raise
            dangling_events.unlink()
            dangling_audit.rmdir()
            passed.append("dangling symlink and no-follow state storage")

        race_audit = root / ".init-race"
        race_tracker = race_audit / "progress.yaml"
        race_state = copy.deepcopy(state)
        write_initial(race_tracker, race_state, {"type": "init"}, "selftest")
        original_state_bytes = read_bytes_no_follow(race_tracker, "race fixture tracker")
        original_journal_bytes = read_bytes_no_follow(events_path(race_tracker), "race fixture journal")
        try:
            write_initial(race_tracker, race_state, {"type": "init-again"}, "selftest")
            raise BdrError("second initializer overwrote existing state")
        except BdrError as exc:
            if "refusing to overwrite" not in str(exc):
                raise
        if (
            read_bytes_no_follow(race_tracker, "race fixture tracker") != original_state_bytes
            or read_bytes_no_follow(events_path(race_tracker), "race fixture journal") != original_journal_bytes
        ):
            raise BdrError("failed second initialization changed durable state")
        race_tracker.unlink()
        events_path(race_tracker).unlink()
        race_audit.rmdir()
        passed.append("race-safe no-overwrite initialization")

        current_status, _ = process_status(os.getpid())
        absent_status, _ = process_status(1 << 30)
        if current_status != "alive" or absent_status != "absent":
            raise BdrError(
                f"safe process lookup returned current={current_status}, absent={absent_status}"
            )
        passed.append("non-signalling lock-owner process lookup")

        try:
            apply_one(
                copy.deepcopy(state),
                {"type": "batch", "operations": [
                    {"type": "begin_phase", "slice": "S-0001", "phase": "expose"},
                    {"type": "finish_phase", "slice": "S-0001", "phase": "expose", "gate": {}},
                ]},
                root,
            )
            raise BdrError("phase begin/finish laundering in a batch was accepted")
        except BdrError as exc:
            if "laundering" in str(exc):
                raise
        resumable = copy.deepcopy(state)
        resumable["run"]["state"] = "needs_human"
        resumable["evidence"]["E-RESUME"] = {"kind": "resume", "claim": "decision supplied"}
        try:
            apply_one(resumable, {"type": "set_run_state", "state": "auditing"}, root)
            raise BdrError("interrupted run resumed without evidence")
        except BdrError as exc:
            if "without evidence" in str(exc):
                raise
        apply_one(
            resumable,
            {"type": "set_run_state", "state": "auditing", "evidence": "E-RESUME"},
            root,
        )
        baseline_resume = copy.deepcopy(state)
        baseline_resume["run"]["state"] = "verification_pending"
        baseline_resume["evidence"]["E-RESUME"] = {"kind": "resume", "claim": "verification may resume"}
        replacement = {
            "type": "set_baseline", "replace": True,
            "baseline": copy.deepcopy(state["run"]["baseline"]),
        }
        try:
            apply_one(baseline_resume, replacement, root)
            raise BdrError("baseline replacement bypassed evidence-backed resume")
        except BdrError as exc:
            if "bypassed evidence-backed resume" in str(exc):
                raise
        replacement["evidence"] = "E-RESUME"
        apply_one(baseline_resume, replacement, root)

        unusable_baseline = copy.deepcopy(state)
        unusable_baseline["run"]["state"] = "preflighting"
        unusable_baseline["run"]["baseline"] = None
        unusable_baseline["run"]["terminal_reason"] = None
        apply_one(
            unusable_baseline,
            {
                "type": "set_baseline",
                "baseline": {
                    "usable": False,
                    "reason": "offline dependencies are unavailable",
                },
            },
            root,
        )
        unusable_next = derive_next_action(unusable_baseline)
        if (
            unusable_baseline["run"]["state"] != "blocked_environment"
            or unusable_baseline["run"]["terminal_reason"]
            != "offline dependencies are unavailable"
            or unusable_next.get("action") != "handoff_terminal"
            or unusable_next.get("reason")
            != "offline dependencies are unavailable"
        ):
            raise BdrError("an unusable baseline did not produce a valid terminal handoff")
        passed.append("unusable baseline produces a reasoned terminal handoff")

        github_off = copy.deepcopy(state)
        try:
            apply_one(github_off, {
                "type": "enqueue_github",
                "item": {"id": "GH-OFF", "action": "comment", "local_id": "S-0001", "body": "late"},
            }, root)
            raise BdrError("GitHub outbox accepted work while projection was off")
        except BdrError as exc:
            if "accepted work while projection was off" in str(exc):
                raise
        github_off["github"]["outbox"] = [{
            "id": "GH-STRANDED", "action": "comment", "local_id": "S-0001", "body": "stranded",
        }]
        if "V008" not in {rule for rule, _ in validate_state(github_off)}:
            raise BdrError("off-mode tracker with a stranded GitHub outbox was accepted")
        downgrade_state = copy.deepcopy(state)
        downgrade_state["findings"]["F-0001"]["resolution"] = None
        try:
            apply_one(
                downgrade_state,
                {"type": "update_finding", "id": "F-0001", "changes": {"merge_blocking": False}},
                root,
            )
            raise BdrError("merge-blocking finding downgrade was accepted")
        except BdrError as exc:
            if "downgrade was accepted" in str(exc):
                raise
        try:
            apply_one(
                copy.deepcopy(state),
                {"type": "configure_slice", "id": "S-0001", "changes": {"merge_policy": "optional"}},
                root,
            )
            raise BdrError("required slice downgrade was accepted")
        except BdrError as exc:
            if "downgrade was accepted" in str(exc):
                raise
        passed.append("durable phase and evidence-backed resume transitions")
        passed.append("off-mode GitHub outbox rejection")
        passed.append("merge-blocking scope cannot be downgraded")

        tracker = root / ".bdr" / "progress.yaml"
        atomic_write(tracker, state)
        append_event(tracker, None, state, {"type": "init"}, "selftest")
        if journal_errors(state, tracker):
            raise BdrError("valid journal was rejected")
        passed.append("V009 valid journal")
        tampered = copy.deepcopy(state)
        tampered["run"]["updated_at"] = "tampered"
        if not journal_errors(tampered, tracker):
            raise BdrError("state/event mismatch was accepted")
        passed.append("V009 state tamper")

        chain_audit = root / ".journal-chain"
        chain_tracker = chain_audit / "progress.yaml"
        chain_old = copy.deepcopy(state)
        write_initial(chain_tracker, chain_old, {"type": "init"}, "selftest")
        chain_new = copy.deepcopy(chain_old)
        chain_new["revision"] = 1
        chain_new["run"]["updated_at"] = utc_now()
        append_event(chain_tracker, chain_old, chain_new, {"type": "advance"}, "selftest")

        wrong_old = copy.deepcopy(chain_new)
        wrong_old["run"]["updated_at"] = "not-the-journal-tail"
        wrong_next = copy.deepcopy(wrong_old)
        wrong_next["revision"] = 2
        try:
            append_event(chain_tracker, wrong_old, wrong_next, {"type": "wrong-tail"}, "selftest")
            raise BdrError("journal accepted an old state that did not match its tail")
        except BdrError as exc:
            if "does not match the journal tail" not in str(exc):
                raise
        skipped_revision = copy.deepcopy(chain_new)
        skipped_revision["revision"] = 3
        try:
            append_event(chain_tracker, chain_new, skipped_revision, {"type": "skip"}, "selftest")
            raise BdrError("journal accepted a non-adjacent state revision")
        except BdrError as exc:
            if "not exactly old revision + 1" not in str(exc):
                raise

        journal_lines = read_text_no_follow(events_path(chain_tracker), "chain fixture journal").splitlines()
        second_event = loads_strict(journal_lines[1], "chain fixture event")
        second_event["previous_state_sha256"] = "0" * 64
        second_body = {key: value for key, value in second_event.items() if key != "event_sha256"}
        second_event["event_sha256"] = digest(second_body)
        events_path(chain_tracker).write_text(
            journal_lines[0] + "\n" + canonical_bytes(second_event).decode() + "\n",
            encoding="utf-8",
        )
        _, adjacent_errors = read_journal(chain_tracker)
        if not any("adjacent state hash chain" in message for message in adjacent_errors):
            raise BdrError("journal accepted a broken adjacent state hash chain")
        events_path(chain_tracker).write_text(journal_lines[0], encoding="utf-8")
        _, unterminated_errors = read_journal(chain_tracker)
        if not any("unterminated final record" in message for message in unterminated_errors):
            raise BdrError("journal accepted an unterminated final record")
        chain_tracker.unlink()
        events_path(chain_tracker).unlink()
        chain_audit.rmdir()
        passed.append("V009 adjacent-state and append-tail enforcement")

        updated, result = mutate_state(
            tracker,
            {"type": "add_evidence", "evidence": {"kind": "observation", "claim": "mutation works"}},
            0,
            "selftest",
        )
        if updated["revision"] != 1 or not result.get("evidence_id") or journal_errors(updated, tracker):
            raise BdrError("atomic mutation or journal append failed")
        passed.append("atomic mutation")
        try:
            mutate_state(tracker, {"type": "add_evidence", "evidence": {"claim": "stale"}}, 0, "selftest")
            raise BdrError("stale expected revision was accepted")
        except BdrError as exc:
            if "stale operation" not in str(exc):
                raise
        passed.append("optimistic revision lock")

        migration_args = argparse.Namespace(
            head_sha=None, base_sha=git_value(root, "rev-parse", "HEAD^"), repository="migration-selftest",
            pr=None, github_mode="outbox", max_fixed_point_passes=3, max_phase_attempts=3,
            run_id="BDR-MIGRATION", from_path="legacy.yaml",
        )
        migrated = migrate_v1_state({
            "slices": [{
                "id": 0, "name": "legacy slice", "status": "done", "merge_blocking": True,
                "boundary": "consumer needs ownership", "depends_on": [],
                "collapse_claim": {"predictions": ["old inference dies"]},
            }],
            "findings": {"42": {
                "slice": 0, "status": "fixed", "what": "legacy finding", "where": "Fixture.java:1",
                "k": "ownership", "k_kind": "temporal", "fix": "explicit owner",
            }},
            "foreign_facts": [{
                "symbol": "Library.call", "api": "1", "assumed": "returns bytes", "established": "measured",
                "evidence": "old measurement", "consequence_if_wrong": "memory corruption",
            }],
        }, root, migration_args)
        if validate_state(migrated):
            raise BdrError("V1 migration produced invalid V2 state")
        migrated_finding = next(iter(migrated["findings"].values()))
        migrated_fact = next(iter(migrated["foreign_facts"].values()))
        if (
            migrated["run"]["state"] != "needs_human"
            or migrated_finding["resolution"] is not None
            or migrated_finding["ownership"]
            or migrated_fact["disposition"]["kind"] != "assumed"
        ):
            raise BdrError("V1 migration trusted an old completion claim")
        passed.append("V1 statuses imported as untrusted hints")

        integration = root / "integration"
        integration.mkdir()
        run_command(["git", "init", "-q"], integration)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], integration)
        run_command(["git", "config", "user.name", "BDR Selftest"], integration)
        (integration / "Fixture.java").write_text("final class Fixture {}\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java"], integration)
        run_command(["git", "commit", "-q", "-m", "base"], integration)
        (integration / "Fixture.java").write_text("final class Fixture { int value; }\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java"], integration)
        run_command(["git", "commit", "-q", "-m", "head"], integration)
        init_args = argparse.Namespace(
            head_sha=None, base_sha=git_value(integration, "rev-parse", "HEAD^"), repository="integration",
            pr=None, github_mode="off", max_fixed_point_passes=3, max_phase_attempts=3,
            run_id="BDR-INTEGRATION",
        )
        integration_state = new_state(integration, init_args)
        integration_tracker = integration / ".bdr" / "progress.yaml"
        write_initial(integration_tracker, integration_state, {"type": "init"}, "selftest")
        revision = 0

        def perform(operation: dict[str, Any]) -> dict[str, Any]:
            nonlocal revision
            changed, _ = mutate_state(
                integration_tracker, operation, revision, "selftest", integration,
            )
            revision = changed["revision"]
            return changed

        try:
            mutate_state(
                integration_tracker,
                {"type": "begin_phase", "slice": "S-0001", "phase": "represent"},
                revision,
                "selftest",
                integration,
            )
            raise BdrError("illegal phase transition was accepted")
        except BdrError:
            if load_json_file(integration_tracker)["revision"] != revision:
                raise BdrError("rejected transition changed state")

        perform({
            "type": "set_baseline",
            "baseline": {
                "usable": True,
                "commands": [{"command": "fixture-baseline", "exit_code": 0, "output_digest": "sha256:baseline"}],
            },
        })
        perform({
            "type": "batch",
            "operations": [
                {"type": "add_evidence", "id": "E-0001", "evidence": {"kind": "code_read", "claim": "K belongs here"}},
                {
                    "type": "add_evidence", "id": "E-0002",
                    "evidence": {
                        "kind": "test", "claim": "intermediate commit verification",
                        "commands": [{
                            "command": "fixture-passing-test", "exit_code": 0,
                            "output_digest": "sha256:passing-test",
                        }],
                    },
                },
                {
                    "type": "add_evidence", "id": "E-0003",
                    "evidence": {
                        "kind": "counterfactual_test", "claim": "reversion fails",
                        "commands": [{
                            "command": "fixture-counterfactual", "exit_code": 1,
                            "output_digest": "sha256:counterfactual-failure",
                        }],
                    },
                },
                {"type": "add_evidence", "id": "E-0004", "evidence": {"kind": "rescan", "claim": "clean rescan"}},
                {
                    "type": "add_slice", "id": "S-0001", "name": "release authority",
                    "boundary": {"authority": "owner", "fact": "release right", "consumer_decision": "close"},
                    "collapse_predictions": {}, "operational_obligations": [],
                },
                {
                    "type": "add_finding", "id": "F-0001", "title": "implicit release", "site": "Fixture.java:1",
                    "missing_fact": {
                        "authority": "owner", "fact": "release right", "consumer_decision": "close",
                        "inferred_from": "registry membership", "initial_shape": "temporal", "normalized_as": "ownership",
                    },
                    "fix_direction": "carry release capability",
                },
                {"type": "assign_finding", "finding": "F-0001", "slice": "S-0001", "k_verification": "E-0001"},
            ],
        })
        phase_payloads = {
            "expose": {
                "finding_id": "F-0001", "test": "fixtureTest", "baseline_ref": "run.baseline",
                "failed_at_assertion": True, "assertion_fingerprint": "missing release capability",
                "input_space": ["owner", "borrower"],
            },
            "represent": {"behavior_changed": False, "artifacts": ["ReleaseCapability"]},
            "route": {
                "producers": ["owner"], "consumers": ["close"], "predictions_frozen": True,
                "new_abstraction_introduced": False, "introduced": [],
            },
            "collapse": {"prediction_verdicts": {}, "died": [], "no_death_expected": "direct replacement"},
            "saturate": {"structural_tests": ["borrower cannot close"], "operational_proofs": {}, "input_space_covered": True},
            "falsify": {"finding_verdicts": {"F-0001": "fixed"}, "rescan": {"performed": True}},
        }
        saturate_evidence: str | None = None
        for phase in PHASES:
            if phase == "saturate":
                (integration / "Fixture.java").write_text(
                    "final class Fixture { int value; int first; int second; }\n",
                    encoding="utf-8",
                )
            perform({"type": "begin_phase", "slice": "S-0001", "phase": phase})
            if phase == "falsify":
                if saturate_evidence is None:
                    raise BdrError("lean phase flow reached FALSIFY without SATURATE evidence")
                perform({
                    "type": "resolve_finding", "finding": "F-0001",
                    "resolution": {
                        "kind": "fixed", "passing_test": saturate_evidence,
                        "counterfactual_test": "E-0003",
                    },
                })
            gate = {**phase_payloads[phase]}
            if phase == "expose":
                gate["commands"] = [{
                    "command": "fixture-focused-expose", "exit_code": 1,
                    "output_digest": "sha256:expose-assertion-failure",
                }]
            elif phase == "saturate":
                gate["commands"] = [{
                    "command": "fixture-focused-saturate", "exit_code": 0,
                    "output_digest": "sha256:saturate-focused-success",
                }]
            elif phase == "falsify":
                gate["saturate_evidence"] = saturate_evidence
            changed = perform({
                "type": "finish_phase", "slice": "S-0001", "phase": phase, "result": "passed",
                "gate": gate,
            })
            if phase == "saturate":
                candidate = changed["slices"]["S-0001"]["phase_attempts"][-1].get("gate_evidence")
                if not isinstance(candidate, str):
                    raise BdrError("SATURATE did not produce reusable gate evidence")
                saturate_evidence = candidate
        if saturate_evidence is None:
            raise BdrError("lean phase flow completed without SATURATE evidence")
        (integration / "Fixture.java").write_text("final class Fixture { int value; int first; }\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java"], integration)
        run_command(["git", "commit", "-q", "-m", "delivery one"], integration)
        first_delivery_sha = canonical_commit_oid(integration, "HEAD", "first delivery")
        (integration / "Fixture.java").write_text(
            "final class Fixture { int value; int first; int second; }\n", encoding="utf-8"
        )
        run_command(["git", "add", "Fixture.java"], integration)
        run_command(["git", "commit", "-q", "-m", "delivery two"], integration)
        second_delivery_sha = canonical_commit_oid(integration, "HEAD", "second delivery")
        try:
            mutate_state(
                integration_tracker,
                {
                    "type": "record_delivery", "slice": "S-0001", "kind": "commit",
                    "sha": first_delivery_sha, "evidence": saturate_evidence,
                },
                revision,
                "selftest",
                integration,
            )
            raise BdrError("partial post-FALSIFY commit reused whole-workspace SATURATE evidence")
        except BdrError as exc:
            if "content differs" not in str(exc):
                raise
        try:
            mutate_state(
                integration_tracker,
                {
                    "type": "record_delivery", "slice": "S-0001", "kind": "commit",
                    "sha": second_delivery_sha, "evidence": saturate_evidence,
                },
                revision,
                "selftest",
                integration,
            )
            raise BdrError("delivery attribution skipped the commit frontier")
        except BdrError as exc:
            if "skipped the commit frontier" in str(exc):
                raise
        perform({
            "type": "record_delivery", "slice": "S-0001", "kind": "commit",
            "sha": first_delivery_sha, "evidence": "E-0002",
        })
        perform({
            "type": "record_delivery", "slice": "S-0001", "kind": "commit",
            "sha": second_delivery_sha, "evidence": saturate_evidence,
        })
        stale_delivery_state = copy.deepcopy(load_json_file(integration_tracker))
        stale_delivery_state["semantic_revision"] += 1
        stale_delivery_state["run"]["state"] = "ready_for_review"
        stale_messages = [message for rule, message in validate_state(stale_delivery_state) if rule == "V008"]
        if not any("current semantic revision" in message for message in stale_messages):
            raise BdrError("semantic work did not invalidate earlier delivery attestations")
        phase_priority_state = copy.deepcopy(stale_delivery_state)
        phase_priority_state["run"]["state"] = "executing"
        phase_priority_state["slices"]["S-0002"] = copy.deepcopy(
            phase_priority_state["slices"]["S-0001"]
        )
        phase_priority_state["slices"]["S-0002"].update({
            "name": "later boundary", "phase_attempts": [], "deliveries": [],
        })
        next_action = derive_next_action(phase_priority_state)
        if next_action.get("action") != "begin_phase" or next_action.get("slice") != "S-0002":
            raise BdrError("stale delivery guidance masked a runnable later slice phase")
        perform({
            "type": "record_fixed_point",
            "pass": {
                "new_merge_blocking_findings": 0,
                "evidence": "E-0004",
                "commands": [{
                    "command": "fixture-final-public-suite", "exit_code": 0,
                    "output_digest": "sha256:final-public-suite-success",
                }],
            },
        })
        perform({"type": "configure_github", "mode": "sync"})
        projected = perform({"type": "project_github"})
        create_item = projected["github"]["outbox"][0]
        try:
            mutate_state(
                integration_tracker,
                {
                    "type": "map_issue", "local_id": "S-0001",
                    "mapping": {
                        "number": 1, "url": "https://example.invalid/issues/1",
                        "repository": "selftest/repo", "marker_token": create_item["marker_token"],
                    },
                },
                revision,
                "selftest",
                integration,
            )
            raise BdrError("standalone issue mapping was accepted without atomic create acknowledgement")
        except BdrError as exc:
            if "standalone issue mapping" in str(exc):
                raise
        perform({
            "type": "batch",
            "operations": [
                {
                    "type": "map_issue", "local_id": "S-0001",
                    "mapping": {
                        "number": 1, "url": "https://example.invalid/issues/1", "repository": "selftest/repo",
                        "marker_token": create_item["marker_token"],
                    },
                },
                {"type": "ack_github", "id": create_item["id"]},
            ],
        })
        projected = perform({"type": "project_github"})
        update_item = projected["github"]["outbox"][0]
        if update_item.get("action") != "update_managed_block":
            raise BdrError("mapped slice did not produce a managed-block update")
        perform({"type": "ack_github", "id": update_item["id"]})
        collision_state = copy.deepcopy(load_json_file(integration_tracker))
        apply_one(collision_state, {"type": "enqueue_github", "item": {
            "id": "GH-COLLISION", "action": "comment", "local_id": "S-0001", "body": "first",
        }}, integration)
        try:
            apply_one(collision_state, {"type": "enqueue_github", "item": {
                "id": "GH-COLLISION", "action": "comment", "local_id": "S-0001", "body": "different",
            }}, integration)
            raise BdrError("GitHub outbox ID collision with a different payload was accepted")
        except BdrError as exc:
            if "different payload was accepted" in str(exc):
                raise
        final_state = perform({"type": "set_run_state", "state": "ready_for_review"})
        if validate_state(final_state) or journal_errors(final_state, integration_tracker):
            raise BdrError("full six-phase integration run did not end valid")
        if derive_next_action(final_state).get("action") != "handoff":
            raise BdrError("completed integration run did not derive handoff")
        passed.append("full six-phase state-machine run")
        passed.append("complete ordered delivery attribution")
        passed.append("semantic work invalidates stale deliveries")
        passed.append("runnable phases precede final delivery re-attestation")
        passed.append("idempotent GitHub projection workflow")
        passed.append("atomic GitHub create mapping and payload collision rejection")
        passed.append("rejected mutation rollback")

        normalized = root / "normalized-content"
        normalized.mkdir()
        run_command(["git", "init", "-q"], normalized)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], normalized)
        run_command(["git", "config", "user.name", "BDR Selftest"], normalized)
        run_command(["git", "config", "core.autocrlf", "false"], normalized)
        run_command(["git", "config", "core.safecrlf", "false"], normalized)
        run_command(["git", "config", "core.fileMode", "false"], normalized)
        (normalized / ".gitattributes").write_text("*.txt text eol=crlf\n", encoding="utf-8")
        (normalized / "sample.txt").write_bytes(b"base\r\n")
        run_command(["git", "add", ".gitattributes", "sample.txt"], normalized)
        run_command(["git", "commit", "-q", "-m", "normalized base"], normalized)
        normalized_base = canonical_commit_oid(normalized, "HEAD", "normalized base")
        (normalized / "sample.txt").write_bytes(b"changed\r\n")
        normalized_workspace_delta = worktree_delta_sha256(normalized, normalized_base)
        run_command(["git", "add", "sample.txt"], normalized)
        run_command(["git", "commit", "-q", "-m", "normalized delivery"], normalized)
        normalized_delivery = canonical_commit_oid(normalized, "HEAD", "normalized delivery")
        normalized_blob = git_value(normalized, "rev-parse", f"{normalized_delivery}:sample.txt")
        object_format = git_value(normalized, "rev-parse", "--show-object-format")
        if normalized_blob == git_blob_oid(b"changed\r\n", object_format):
            raise BdrError("Git-normalization fixture did not actually convert CRLF worktree bytes")
        normalized_commit_delta = commit_delta_sha256(
            normalized, normalized_base, normalized_delivery,
        )
        if normalized_workspace_delta != normalized_commit_delta:
            raise BdrError("path-aware Git clean conversion changed the verified delivery fingerprint")
        passed.append("Git-normalized worktree and delivery content identity")

        normalized_base = normalized_delivery
        (normalized / "sample.txt").chmod(0o755)
        if os.lstat(normalized / "sample.txt").st_mode & 0o111:
            (normalized / "sample.txt").write_bytes(b"mode-change\r\n")
            filemode_workspace_delta = worktree_delta_sha256(normalized, normalized_base)
            run_command(["git", "add", "sample.txt"], normalized)
            run_command(["git", "commit", "-q", "-m", "filemode delivery"], normalized)
            filemode_delivery = canonical_commit_oid(normalized, "HEAD", "filemode delivery")
            delivered_mode = git_value(
                normalized, "ls-tree", filemode_delivery, "--", "sample.txt",
            ).split()[0]
            if delivered_mode != "100644":
                raise BdrError("core.fileMode=false fixture did not preserve the indexed mode")
            if filemode_workspace_delta != commit_delta_sha256(
                normalized, normalized_base, filemode_delivery,
            ):
                raise BdrError("core.fileMode=false changed the verified delivery fingerprint")
            passed.append("core.fileMode=false worktree and delivery identity")

        if symlink_supported:
            symlink_modes = root / "symlink-modes"
            symlink_modes.mkdir()
            run_command(["git", "init", "-q"], symlink_modes)
            run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], symlink_modes)
            run_command(["git", "config", "user.name", "BDR Selftest"], symlink_modes)
            (symlink_modes / ".gitattributes").write_text("link text eol=lf\n", encoding="utf-8")
            (symlink_modes / "link").symlink_to("first-target")
            run_command(["git", "add", ".gitattributes", "link"], symlink_modes)
            run_command(["git", "commit", "-q", "-m", "symlink base"], symlink_modes)
            symlink_base = canonical_commit_oid(symlink_modes, "HEAD", "symlink base")
            run_command(["git", "config", "core.symlinks", "false"], symlink_modes)
            (symlink_modes / "link").unlink()
            (symlink_modes / "link").write_bytes(b"second-target\r\n")
            symlink_workspace_delta = worktree_delta_sha256(symlink_modes, symlink_base)
            run_command(["git", "add", "link"], symlink_modes)
            run_command(["git", "commit", "-q", "-m", "symlink delivery"], symlink_modes)
            symlink_delivery = canonical_commit_oid(symlink_modes, "HEAD", "symlink delivery")
            delivered_mode = git_value(
                symlink_modes, "ls-tree", symlink_delivery, "--", "link",
            ).split()[0]
            if delivered_mode != "120000":
                raise BdrError("core.symlinks=false fixture did not preserve the indexed mode")
            object_format = git_value(symlink_modes, "rev-parse", "--show-object-format")
            delivered_blob = git_value(symlink_modes, "rev-parse", f"{symlink_delivery}:link")
            if delivered_blob == git_blob_oid(b"second-target\r\n", object_format):
                raise BdrError("core.symlinks=false fixture did not apply clean conversion")
            if symlink_workspace_delta != commit_delta_sha256(
                symlink_modes, symlink_base, symlink_delivery,
            ):
                raise BdrError("core.symlinks=false changed the verified delivery fingerprint")
            passed.append("core.symlinks=false worktree and delivery identity")

        submodule_parent = root / "submodule-modes"
        submodule_parent.mkdir()
        run_command(["git", "init", "-q"], submodule_parent)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], submodule_parent)
        run_command(["git", "config", "user.name", "BDR Selftest"], submodule_parent)
        submodule_child = submodule_parent / "child"
        submodule_child.mkdir()
        run_command(["git", "init", "-q"], submodule_child)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], submodule_child)
        run_command(["git", "config", "user.name", "BDR Selftest"], submodule_child)
        (submodule_child / "value.txt").write_text("base\n", encoding="utf-8")
        run_command(["git", "add", "value.txt"], submodule_child)
        run_command(["git", "commit", "-q", "-m", "child base"], submodule_child)
        run_command(["git", "add", "child"], submodule_parent)
        run_command(["git", "commit", "-q", "-m", "parent base"], submodule_parent)
        submodule_base = canonical_commit_oid(submodule_parent, "HEAD", "submodule parent base")
        (submodule_child / "value.txt").write_text("delivered\n", encoding="utf-8")
        run_command(["git", "add", "value.txt"], submodule_child)
        run_command(["git", "commit", "-q", "-m", "child delivery"], submodule_child)
        submodule_workspace_delta = worktree_delta_sha256(submodule_parent, submodule_base)
        run_command(["git", "add", "child"], submodule_parent)
        run_command(["git", "commit", "-q", "-m", "parent delivery"], submodule_parent)
        submodule_delivery = canonical_commit_oid(
            submodule_parent, "HEAD", "submodule parent delivery",
        )
        if submodule_workspace_delta != commit_delta_sha256(
            submodule_parent, submodule_base, submodule_delivery,
        ):
            raise BdrError("clean changed submodule did not retain delivery identity")
        (submodule_child / "value.txt").write_text("dirty\n", encoding="utf-8")
        try:
            worktree_delta_sha256(submodule_parent, submodule_delivery)
            raise BdrError("dirty changed submodule was accepted for delivery reuse")
        except BdrError as exc:
            if "uncommitted content" not in str(exc):
                raise
        (submodule_child / "value.txt").write_text("delivered\n", encoding="utf-8")
        run_command(["git", "update-index", "--assume-unchanged", "value.txt"], submodule_child)
        (submodule_child / "value.txt").write_text("hidden dirty bytes\n", encoding="utf-8")
        try:
            worktree_delta_sha256(submodule_parent, submodule_delivery)
            raise BdrError("assume-unchanged content in a submodule was accepted")
        except BdrError as exc:
            if "assume-unchanged" not in str(exc):
                raise
        passed.append("clean and dirty submodule delivery binding")

        uninitialized_submodule = root / "uninitialized-submodule"
        run_command(
            [
                "git", "clone", "-q", "--no-recurse-submodules",
                str(submodule_parent), str(uninitialized_submodule),
            ],
            root,
        )
        uninitialized_child = uninitialized_submodule / "child"
        if not path_entry_exists(uninitialized_child):
            uninitialized_child.mkdir()
        ensure_git_index_visible(uninitialized_submodule)
        passed.append("empty uninitialized submodule visibility")

        shadowed = root / "shadowed-deletion"
        shadowed.mkdir()
        run_command(["git", "init", "-q"], shadowed)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], shadowed)
        run_command(["git", "config", "user.name", "BDR Selftest"], shadowed)
        (shadowed / ".gitignore").write_text("hidden.txt\n", encoding="utf-8")
        (shadowed / "hidden.txt").write_text("verified bytes\n", encoding="utf-8")
        run_command(["git", "add", "-f", ".gitignore", "hidden.txt"], shadowed)
        run_command(["git", "commit", "-q", "-m", "shadow base"], shadowed)
        shadow_base = canonical_commit_oid(shadowed, "HEAD", "shadow base")
        run_command(["git", "rm", "--cached", "-q", "hidden.txt"], shadowed)
        try:
            worktree_delta_sha256(shadowed, shadow_base)
            raise BdrError("ignored path shadowing a tracked deletion was accepted")
        except BdrError as exc:
            if "shadowed" not in str(exc):
                raise
        passed.append("ignored tracked-deletion shadow rejection")

        hidden_index = root / "hidden-index"
        hidden_index.mkdir()
        run_command(["git", "init", "-q"], hidden_index)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], hidden_index)
        run_command(["git", "config", "user.name", "BDR Selftest"], hidden_index)
        (hidden_index / "hidden.txt").write_text("base\n", encoding="utf-8")
        run_command(["git", "add", "hidden.txt"], hidden_index)
        run_command(["git", "commit", "-q", "-m", "hidden-index base"], hidden_index)
        hidden_base = canonical_commit_oid(hidden_index, "HEAD", "hidden-index base")
        run_command(["git", "update-index", "--assume-unchanged", "hidden.txt"], hidden_index)
        (hidden_index / "hidden.txt").write_text("assumed dirty\n", encoding="utf-8")
        try:
            worktree_delta_sha256(hidden_index, hidden_base)
            raise BdrError("assume-unchanged root content was accepted")
        except BdrError as exc:
            if "assume-unchanged" not in str(exc):
                raise
        run_command(["git", "update-index", "--no-assume-unchanged", "hidden.txt"], hidden_index)
        (hidden_index / "hidden.txt").write_text("base\n", encoding="utf-8")
        run_command(["git", "update-index", "--skip-worktree", "hidden.txt"], hidden_index)
        (hidden_index / "hidden.txt").write_text("skipped dirty\n", encoding="utf-8")
        try:
            worktree_delta_sha256(hidden_index, hidden_base)
            raise BdrError("present skip-worktree root content was accepted")
        except BdrError as exc:
            if "skip-worktree" not in str(exc):
                raise
        passed.append("hidden index-entry rejection")

        custom = root / "custom-audit-dir"
        custom.mkdir()
        run_command(["git", "init", "-q"], custom)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], custom)
        run_command(["git", "config", "user.name", "BDR Selftest"], custom)
        (custom / "Fixture.java").write_text("final class Fixture {}\n", encoding="utf-8")
        custom_source_dir = custom / ".audit[1]"
        custom_source_dir.mkdir()
        custom_source = custom_source_dir / "Tracked.java"
        custom_source.write_text("final class Tracked {}\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java", ".audit[1]/Tracked.java"], custom)
        run_command(["git", "commit", "-q", "-m", "base"], custom)
        (custom / "Fixture.java").write_text("final class Fixture { int value; }\n", encoding="utf-8")
        run_command(["git", "add", "Fixture.java"], custom)
        run_command(["git", "commit", "-q", "-m", "head"], custom)
        custom_args = argparse.Namespace(
            state=".audit[1]/progress.yaml", head_sha=None,
            base_sha=git_value(custom, "rev-parse", "HEAD^"), repository="custom-audit-dir",
            pr=None, github_mode="off", max_fixed_point_passes=3, max_phase_attempts=3,
            run_id="BDR-CUSTOM-AUDIT-DIR",
        )
        custom_state = new_state(custom, custom_args)
        custom_tracker = state_path(custom_args.state, custom)
        write_initial(custom_tracker, custom_state, {"type": "init"}, "selftest")
        if (
            custom_state["source"]["audit_dir"] != ".audit[1]"
            or custom_state["source"]["tracker_path"] != ".audit[1]/progress.yaml"
            or state_binding_errors(custom_state, custom_tracker, custom)
            or workspace_snapshot(custom, ".audit[1]", ".audit[1]/progress.yaml")["dirty"]
            or working_tree_changes(custom, ".audit[1]", ".audit[1]/progress.yaml")
        ):
            raise BdrError("custom tracker files were not bound and excluded consistently")
        custom_source.write_text("final class Tracked { int changed; }\n", encoding="utf-8")
        if (
            not workspace_snapshot(custom, ".audit[1]", ".audit[1]/progress.yaml")["dirty"]
            or not working_tree_changes(custom, ".audit[1]", ".audit[1]/progress.yaml")
        ):
            raise BdrError("custom tracker parent hid a tracked source change")
        passed.append("custom tracker file-only isolation")

        replace_repo = root / "replace-refs"
        replace_repo.mkdir()
        run_command(["git", "init", "-q"], replace_repo)
        run_command(["git", "config", "user.email", "bdr-selftest@example.invalid"], replace_repo)
        run_command(["git", "config", "user.name", "BDR Selftest"], replace_repo)
        replace_file = replace_repo / "History.java"
        replace_file.write_text("final class History {}\n", encoding="utf-8")
        run_command(["git", "add", "History.java"], replace_repo)
        run_command(["git", "commit", "-q", "-m", "target"], replace_repo)
        replace_target = git_value(replace_repo, "rev-parse", "HEAD")
        replace_file.write_text("final class History { int hidden; }\n", encoding="utf-8")
        run_command(["git", "add", "History.java"], replace_repo)
        run_command(["git", "commit", "-q", "-m", "hidden"], replace_repo)
        replace_file.write_text("final class History { int hidden; int visible; }\n", encoding="utf-8")
        run_command(["git", "add", "History.java"], replace_repo)
        run_command(["git", "commit", "-q", "-m", "visible"], replace_repo)
        replace_head = git_value(replace_repo, "rev-parse", "HEAD")
        run_command(["git", "replace", "--graft", replace_head, replace_target], replace_repo)
        protected_frontier = run_command(
            ["git", "rev-list", "--reverse", f"{replace_target}..{replace_head}"], replace_repo
        ).stdout.splitlines()
        if len(protected_frontier) != 2:
            raise BdrError("Git replacement refs hid a delivery commit from protected traversal")
        passed.append("Git replacement refs cannot rewrite attribution")
        graft_raw = Path(run_command(
            ["git", "rev-parse", "--git-path", "info/grafts"], replace_repo
        ).stdout.strip())
        graft_path = graft_raw if graft_raw.is_absolute() else replace_repo / graft_raw
        graft_path.parent.mkdir(parents=True, exist_ok=True)
        graft_path.write_bytes(b" \n\t")
        if not git_graft_errors(replace_repo):
            raise BdrError("whitespace-only nonempty legacy Git graft file was not detected")
        if preflight_report(replace_repo)["ok"]:
            raise BdrError("preflight accepted a nonempty legacy Git graft file")
        init_probe = argparse.Namespace(
            pr=None, base_sha=replace_target, head_sha=replace_head,
        )
        try:
            resolve_init_args(init_probe, replace_repo)
            raise BdrError("initialization accepted a nonempty legacy Git graft file")
        except BdrError as exc:
            if "legacy Git graft file" not in str(exc):
                raise
        graft_path.write_text(f"{replace_head} {replace_target}\n", encoding="utf-8")
        if not local_lineage_errors(fixture_state(replace_repo), replace_repo):
            raise BdrError("local lineage accepted a meaningful legacy Git graft entry")
        passed.append("legacy Git graft ancestry rejection")
    return passed


def write_initial(path: Path, state: dict[str, Any], operation: dict[str, Any], actor: str) -> None:
    with state_lock(path):
        # The check belongs under the lock, and exclusive_write is the final
        # no-overwrite guard against non-cooperating creators and dangling links.
        if path_entry_exists(path) or path_entry_exists(events_path(path)):
            raise BdrError(f"refusing to overwrite existing BDR state beside {path}")
        exclusive_write(path, state)
        append_event(path, None, state, operation, actor)


def resolve_init_args(args: argparse.Namespace, root: Path) -> None:
    graft_errors = git_graft_errors(root)
    if graft_errors:
        raise BdrError("; ".join(graft_errors))
    selector = args.pr
    metadata: dict[str, Any] | None = None
    if selector and (not args.base_sha or not args.head_sha):
        metadata = gh_pr_metadata(root, selector)
        args.base_sha = args.base_sha or metadata["baseRefOid"]
        args.head_sha = args.head_sha or metadata["headRefOid"]
        args.pr = metadata
    actual_head = canonical_commit_oid(root, "HEAD", "checked-out HEAD")
    if args.head_sha:
        args.head_sha = canonical_commit_oid(root, args.head_sha, "target head")
    if args.base_sha:
        args.base_sha = canonical_commit_oid(root, args.base_sha, "base")
    if args.head_sha and args.head_sha != actual_head:
        raise BdrError(f"checked-out HEAD {actual_head} does not equal pinned target head {args.head_sha}")
    if args.base_sha:
        ancestor = run_command(["git", "merge-base", "--is-ancestor", args.base_sha, actual_head], root, required=False)
        if ancestor.returncode:
            raise BdrError(f"base SHA {args.base_sha} is not an ancestor of target head {actual_head}")
    if metadata is None and isinstance(args.pr, str):
        args.pr = {"selector": args.pr, "baseRefOid": args.base_sha, "headRefOid": args.head_sha or actual_head}


def command_preflight(args: argparse.Namespace) -> int:
    report = preflight_report(Path.cwd(), args.pr)
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 2


def command_init(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    changes = working_tree_changes(root)
    if changes:
        raise BdrError(
            "refusing to initialize in a dirty user worktree; use a clean or isolated worktree. "
            f"First changes: {changes[:5]!r}"
        )
    resolve_init_args(args, root)
    state = new_state(root, args)
    errors = validate_state(state)
    if errors:
        raise BdrError("new tracker is invalid: " + render_validation(errors))
    path = state_path(args.state, root)
    write_initial(path, state, {"type": "init", "source": state["source"]}, args.actor)
    print(json.dumps({"initialized": str(path), "revision": 0, "run_id": state["run"]["id"]}, indent=2))
    return 0


def command_migrate(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    resolve_init_args(args, root)
    legacy_path = state_path(args.from_path, root)
    legacy = legacy_load(legacy_path)
    args.from_path = str(legacy_path)
    state = migrate_v1_state(legacy, root, args)
    path = state_path(args.state, root)
    write_initial(
        path,
        state,
        {"type": "migrate_v1", "source": str(legacy_path), "trusted_statuses": False},
        args.actor,
    )
    print(json.dumps({
        "migrated": str(path),
        "run_state": state["run"]["state"],
        "unresolved": len(state["migration"]["unresolved"]),
        "warning": state["migration"]["warning"],
    }, indent=2))
    return 0


def checked_state(path: Path, invocation_root: Path | None = None) -> dict[str, Any]:
    state = load_json_file(path)
    if invocation_root is not None:
        binding = state_binding_errors(state, path, invocation_root)
        if binding:
            raise BdrError("; ".join(binding))
    errors = validate_state(state)
    errors.extend(("V009", message) for message in journal_errors(state, path))
    if invocation_root is not None:
        errors.extend(("CONTEXT", message) for message in local_lineage_errors(state, invocation_root))
        if state.get("run", {}).get("state") == "ready_for_review":
            errors.extend(("CONTEXT", message) for message in fixed_point_workspace_errors(state, invocation_root))
    if errors:
        raise BdrError("tracker validation failed:\n" + render_validation(errors))
    return state


def command_check(args: argparse.Namespace) -> int:
    try:
        root = git_root(Path.cwd())
        path = state_path(args.state, root)
    except BdrError as exc:
        if args.json:
            print(json.dumps({"valid": False, "errors": [{"rule": "CONTEXT", "message": str(exc)}]}, indent=2))
        else:
            print(f"INVALID\nCONTEXT  {exc}")
        return 1
    try:
        state = load_json_file(path)
        errors = validate_state(state)
        binding = state_binding_errors(state, path, root)
        if binding:
            errors.extend(("V001", message) for message in binding)
        else:
            errors.extend(("CONTEXT", message) for message in local_lineage_errors(state, root))
            if state.get("run", {}).get("state") == "ready_for_review":
                errors.extend(("CONTEXT", message) for message in fixed_point_workspace_errors(state, root))
        errors.extend(("V009", message) for message in journal_errors(state, path))
    except BdrError as exc:
        if args.json:
            print(json.dumps({"valid": False, "errors": [{"rule": "PARSE", "message": str(exc)}]}, indent=2))
        else:
            print(f"INVALID\nPARSE  {exc}")
        return 1
    if errors:
        if args.json:
            print(json.dumps({
                "valid": False,
                "revision": state.get("revision"),
                "errors": [{"rule": rule, "message": message} for rule, message in errors],
            }, indent=2))
        else:
            print("INVALID")
            print(render_validation(errors))
        return 1
    output = {"valid": True, "revision": state["revision"], "run_state": state["run"]["state"]}
    print(json.dumps(output, indent=2) if args.json else f"VALID revision={state['revision']} state={state['run']['state']}")
    return 0


def command_status(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    state = checked_state(state_path(args.state, root), root)
    document = status_document(state)
    if args.next:
        document = {"revision": state["revision"], "run_state": state["run"]["state"], "next": document["next"]}
    print(json.dumps(document, indent=2))
    return 0


def command_apply(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    operation = load_input(args.operation)
    state, result = mutate_state(
        state_path(args.state, root), operation, args.expected_revision, args.actor, root,
    )
    print(json.dumps({"revision": state["revision"], "result": result, "next": derive_next_action(state)}, indent=2))
    return 0


def command_transition(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    if args.transition_action == "begin":
        operation = {"type": "begin_phase", "slice": args.slice, "phase": args.phase}
    elif args.transition_action == "finish":
        gate = load_input(args.evidence)
        operation = {
            "type": "finish_phase", "slice": args.slice, "phase": args.phase,
            "result": args.result, "gate": gate,
        }
        if args.evidence_id:
            operation["evidence_id"] = args.evidence_id
    else:
        operation = {
            "type": "rewind_phase", "slice": args.slice, "rewind_to": args.rewind_to,
            "reason": args.reason, "evidence": args.evidence,
        }
    state, result = mutate_state(state_path(args.state, root), operation, args.expected_revision, args.actor, root)
    print(json.dumps({"revision": state["revision"], "result": result, "next": derive_next_action(state)}, indent=2))
    return 0


def command_completion(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    path = state_path(args.state, root)
    state = load_json_file(path)
    binding = state_binding_errors(state, path, root)
    if binding:
        raise BdrError("; ".join(binding))
    errors = validate_state(state)
    errors.extend(("V009", message) for message in journal_errors(state, path))
    errors.extend(("CONTEXT", message) for message in local_lineage_errors(state, root))
    errors.extend(("CONTEXT", message) for message in fixed_point_workspace_errors(state, root))
    candidate = copy.deepcopy(state)
    candidate["run"]["state"] = "ready_for_review"
    candidate_errors = validate_state(candidate)
    nonreadiness = [(rule, message) for rule, message in errors if rule != "V008"]
    readiness = [(rule, message) for rule, message in candidate_errors if rule == "V008"]
    readiness.extend(
        ("CONTEXT", message) for message in delivery_attribution_errors(candidate, root, require_complete=True)
    )
    if state.get("run", {}).get("state") != "verifying":
        readiness.append(("V008", "completion may only transition from the verifying run state"))
    all_errors = nonreadiness + readiness
    output = {
        "eligible": not all_errors,
        "current_state": state.get("run", {}).get("state"),
        "revision": state.get("revision"),
        "blockers": [{"rule": rule, "message": message} for rule, message in all_errors],
        "next": derive_next_action(state) if not all_errors else None,
    }
    print(json.dumps(output, indent=2))
    return 0 if not all_errors else 1


def command_audit(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    path = state_path(args.state, root)
    state = load_json_file(path)
    binding = state_binding_errors(state, path, root)
    if binding:
        raise BdrError("; ".join(binding))
    entries, errors = read_journal(path)
    if errors:
        raise BdrError("invalid journal: " + "; ".join(errors))
    if journal_errors(state, path):
        raise BdrError("journal does not agree with current state")
    lineage = local_lineage_errors(state, root)
    if lineage:
        raise BdrError("stale or rewritten local input: " + "; ".join(lineage))
    if args.summary:
        output: Any = [{
            "sequence": entry["sequence"], "revision": entry["revision"], "at": entry["at"],
            "actor": entry["actor"], "operation": entry["operation"].get("type"),
            "event_sha256": entry["event_sha256"],
        } for entry in entries]
    else:
        output = entries
    print(json.dumps(output, indent=2))
    return 0


def command_stale_check(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    state = checked_state(state_path(args.state, root), root)
    pr = state["source"].get("pr")
    if not isinstance(pr, dict):
        raise BdrError("tracker has no resolvable PR metadata; staleness must be checked by the host")
    selector: str | None = None
    if is_nonempty_string(pr.get("url")):
        selector = pr["url"]
    elif is_json_integer(pr.get("number"), 1):
        selector = str(pr["number"])
    elif is_nonempty_string(pr.get("selector")):
        selector = pr["selector"]
    if selector is None:
        raise BdrError("tracker PR metadata has no number, URL, or selector")
    current = gh_pr_metadata(root, selector)
    expected = {
        "baseRefOid": state["source"]["base_sha"],
        "headRefOid": state["source"]["starting_head_sha"],
    }
    observed = {"baseRefOid": current["baseRefOid"], "headRefOid": current["headRefOid"]}
    stale = expected != observed
    print(json.dumps({"stale": stale, "expected": expected, "observed": observed, "pr": current["url"]}, indent=2))
    return 3 if stale else 0


def command_github_outbox(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    state = checked_state(state_path(args.state, root), root)
    print(json.dumps({
        "revision": state["revision"],
        "mode": state["policy"]["github_projection"],
        "mappings": state["github"]["mappings"],
        "outbox": state["github"]["outbox"],
    }, indent=2))
    return 0


def process_status(pid: int) -> tuple[str, str]:
    """Return alive/absent/unknown without ever signalling the process on Windows."""
    if os.name == "nt":
        try:
            import ctypes
            from ctypes import wintypes

            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            open_process = kernel32.OpenProcess
            open_process.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            open_process.restype = wintypes.HANDLE
            get_exit_code = kernel32.GetExitCodeProcess
            get_exit_code.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            get_exit_code.restype = wintypes.BOOL
            close_handle = kernel32.CloseHandle
            close_handle.argtypes = [wintypes.HANDLE]
            close_handle.restype = wintypes.BOOL
            # Query only; unlike os.kill(pid, 0) on Windows, this cannot terminate.
            process = open_process(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
            if not process:
                error = ctypes.get_last_error()
                if error == 87:  # ERROR_INVALID_PARAMETER: no such PID.
                    return "absent", f"OpenProcess error {error}"
                if error == 5:  # ERROR_ACCESS_DENIED normally proves the process exists.
                    return "alive", f"OpenProcess error {error}"
                return "unknown", f"OpenProcess error {error}"
            try:
                exit_code = wintypes.DWORD()
                if not get_exit_code(process, ctypes.byref(exit_code)):
                    return "unknown", f"GetExitCodeProcess error {ctypes.get_last_error()}"
                return ("alive", "STILL_ACTIVE") if exit_code.value == 259 else (
                    "absent", f"exit code {exit_code.value}"
                )
            finally:
                close_handle(process)
        except Exception as exc:  # pragma: no cover - exercised on Windows.
            return "unknown", f"Windows process query failed: {exc}"
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return "absent", "process lookup reported no such PID"
    except PermissionError:
        return "alive", "process exists but is not signalable"
    except OSError as exc:
        return "unknown", f"process lookup failed: {exc}"
    return "alive", "process exists"


def command_recover_lock(args: argparse.Namespace) -> int:
    root = git_root(Path.cwd())
    path = state_path(args.state, root)
    lock = path.with_name("engine.lock")
    if not path_entry_exists(lock):
        print(json.dumps({"recovered": False, "reason": "no lock exists"}, indent=2))
        return 0
    lock_stat = regular_file_stat(lock, "engine lock")
    lock_bytes = read_bytes_no_follow(lock, "engine lock")
    content = lock_bytes.decode("utf-8", errors="replace").strip()
    fields = dict(
        part.split("=", 1) for part in content.split() if "=" in part
    )
    try:
        pid = int(fields["pid"])
    except (KeyError, ValueError) as exc:
        raise BdrError(f"lock ownership is ambiguous ({content!r}); do not remove it automatically") from exc
    if pid <= 0:
        raise BdrError(f"lock contains an unsafe pid {pid}; do not remove it automatically")
    status, detail = process_status(pid)
    if status == "alive":
        raise BdrError(f"lock owner pid {pid} still exists; recovery is unsafe")
    if status != "absent":
        raise BdrError(f"cannot prove lock owner pid {pid} is absent ({detail}); recovery is unsafe")
    checked_state(path, root)
    current_stat = regular_file_stat(lock, "engine lock")
    if (
        not same_file_identity(lock_stat, current_stat)
        or read_bytes_no_follow(lock, "engine lock") != lock_bytes
    ):
        raise BdrError("engine lock changed during recovery; refusing to remove it")
    lock.unlink()
    print(json.dumps({"recovered": True, "stale_pid": pid, "lock": str(lock)}, indent=2))
    return 0


def command_rules(_: argparse.Namespace) -> int:
    print(f"BDR validator {VERSION}\n")
    for rule, description in RULES.items():
        print(f"{rule}  {description}")
    print(
        "\nA valid tracker proves internal consistency and required evidence shape. It does not prove "
        "that tests are truthful, foreign facts are current, issue synchronization succeeded, or the refactor is correct."
    )
    return 0


def command_selftest(_: argparse.Namespace) -> int:
    passed = selftest()
    print(f"PASS {len(passed)} checks")
    for item in passed:
        print(f"  {item}")
    return 0


def command_examples(_: argparse.Namespace) -> int:
    examples = {
        "add_evidence": {
            "type": "add_evidence",
            "evidence": {"kind": "code_read", "claim": "K was re-derived at File.java:42"},
        },
        "add_slice": {
            "type": "add_slice", "name": "release authority", "merge_policy": "required",
            "boundary": {"authority": "allocation owner", "fact": "release right", "consumer_decision": "close or retain"},
            "depends_on": [], "collapse_predictions": {"P-0001": "membership inference dies"},
            "operational_obligations": ["backing allocation remains live for every borrower"],
        },
        "add_finding": {
            "type": "add_finding", "title": "release inferred from registry membership", "site": "Component.java:42",
            "missing_fact": {
                "authority": "allocation owner", "fact": "release right", "consumer_decision": "close or retain",
                "inferred_from": "registry membership", "initial_shape": "temporal", "normalized_as": "ownership",
            },
            "fix_direction": "route an explicit release capability",
        },
        "assign_finding": {"type": "assign_finding", "finding": "F-0001", "slice": "S-0001", "k_verification": "E-0001"},
        "finish_expose": {
            "type": "finish_phase", "slice": "S-0001", "phase": "expose", "result": "passed",
            "gate": {
                "commands": [{
                    "command": "focused regression test", "exit_code": 1,
                    "output_digest": "sha256:expected-assertion-failure",
                }],
                "finding_id": "F-0001", "test": "ComponentTest.releaseRequiresAuthority",
                "baseline_ref": "run.baseline", "failed_at_assertion": True,
                "assertion_fingerprint": "borrower cannot release owner allocation",
                "input_space": ["owner", "borrower"],
            },
        },
        "finish_represent": {
            "type": "finish_phase", "slice": "S-0001", "phase": "represent", "result": "passed",
            "gate": {
                "behavior_changed": False, "artifacts": ["ReleaseCapability"],
            },
        },
        "finish_route": {
            "type": "finish_phase", "slice": "S-0001", "phase": "route", "result": "passed",
            "gate": {
                "producers": ["allocation owner"], "consumers": ["close", "retain"],
                "predictions_frozen": True, "new_abstraction_introduced": False, "introduced": [],
            },
        },
        "finish_collapse": {
            "type": "finish_phase", "slice": "S-0001", "phase": "collapse", "result": "passed",
            "gate": {
                "prediction_verdicts": {"P-0001": "died"},
                "died": ["membership no longer decides release authority"],
            },
        },
        "finish_saturate": {
            "type": "finish_phase", "slice": "S-0001", "phase": "saturate", "result": "passed",
            "evidence_id": "E-SATURATE",
            "gate": {
                "commands": [{
                    "command": "focused owner/borrower boundary tests", "exit_code": 0,
                    "output_digest": "sha256:focused-success",
                }],
                "structural_tests": ["borrower cannot close", "owner can close exactly once"],
                "operational_proofs": {
                    "backing allocation remains live for every borrower": "E-OPERATIONAL",
                },
                "input_space_covered": True,
            },
        },
        "resolve_finding_with_saturate": {
            "type": "resolve_finding", "finding": "F-0001",
            "resolution": {
                "kind": "fixed", "passing_test": "E-SATURATE",
                "counterfactual_test": "E-COUNTERFACTUAL",
            },
        },
        "finish_falsify": {
            "type": "finish_phase", "slice": "S-0001", "phase": "falsify", "result": "passed",
            "gate": {
                "saturate_evidence": "E-SATURATE",
                "finding_verdicts": {"F-0001": "fixed"}, "rescan": {"performed": True},
            },
        },
        "record_delivery_with_saturate": {
            "type": "record_delivery", "slice": "S-0001", "kind": "commit",
            "sha": "HEAD", "evidence": "E-SATURATE",
        },
        "record_final_fixed_point": {
            "type": "record_fixed_point",
            "pass": {
                "new_merge_blocking_findings": 0,
                "evidence": "E-FINAL-RESCAN",
                "commands": [{
                    "command": "project broad integration/chaos/benchmark suite",
                    "exit_code": 0,
                    "output_digest": "sha256:final-suite-success",
                }],
            },
        },
    }
    print(json.dumps(examples, indent=2))
    return 0


def add_state_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--state", default=".bdr/progress.yaml", help="path to the V2 tracker")


def add_mutation_arguments(parser: argparse.ArgumentParser) -> None:
    add_state_argument(parser)
    parser.add_argument("--expected-revision", type=int, required=True)
    parser.add_argument("--actor", default=os.environ.get("BDR_ACTOR", "agent"))


def add_init_arguments(parser: argparse.ArgumentParser) -> None:
    add_state_argument(parser)
    parser.add_argument("--pr", help="GitHub PR number/URL, or 'current'; resolved and pinned with gh")
    parser.add_argument("--base-sha")
    parser.add_argument("--head-sha")
    parser.add_argument("--repository")
    parser.add_argument("--run-id")
    parser.add_argument("--github-mode", choices=("off", "outbox", "sync"), default="outbox")
    parser.add_argument("--max-fixed-point-passes", type=int, default=3)
    parser.add_argument("--max-phase-attempts", type=int, default=3)
    parser.add_argument("--actor", default=os.environ.get("BDR_ACTOR", "agent"))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="bdr", description="Boundary-Driven Refactoring state engine")
    parser.add_argument("--version", action="version", version=f"%(prog)s {VERSION}")
    commands = parser.add_subparsers(dest="command", required=True)

    preflight = commands.add_parser("preflight", help="verify a clean, pinned execution context")
    preflight.add_argument("--pr", help="GitHub PR number/URL, or 'current'")
    preflight.set_defaults(handler=command_preflight)

    init = commands.add_parser("init", help="initialize a new V2 tracker and journal")
    add_init_arguments(init)
    init.set_defaults(handler=command_init)

    migrate = commands.add_parser("migrate-v1", help="one-way import of a V1 YAML tracker")
    add_init_arguments(migrate)
    migrate.add_argument("--from", dest="from_path", required=True)
    migrate.set_defaults(handler=command_migrate)

    check = commands.add_parser("check", help="validate state and event journal")
    add_state_argument(check)
    check.add_argument("--json", action="store_true")
    check.set_defaults(handler=command_check)

    status = commands.add_parser("status", help="show derived progress")
    add_state_argument(status)
    status.add_argument("--next", action="store_true", help="show only the next legal action")
    status.set_defaults(handler=command_status)

    apply = commands.add_parser("apply", help="atomically apply one JSON operation or batch")
    add_mutation_arguments(apply)
    apply.add_argument("operation", nargs="?", default="-", help="operation file, or - for stdin")
    apply.set_defaults(handler=command_apply)

    transition = commands.add_parser("transition", help="begin, finish, or rewind a phase")
    transition_commands = transition.add_subparsers(dest="transition_action", required=True)
    begin = transition_commands.add_parser("begin")
    add_mutation_arguments(begin)
    begin.add_argument("slice")
    begin.add_argument("phase", choices=PHASES)
    begin.set_defaults(handler=command_transition)
    finish = transition_commands.add_parser("finish")
    add_mutation_arguments(finish)
    finish.add_argument("slice")
    finish.add_argument("phase", choices=PHASES)
    finish.add_argument("--result", choices=("passed", "failed", "blocked"), default="passed")
    finish.add_argument("--evidence", required=True, help="phase-gate JSON file, or - for stdin")
    finish.add_argument("--evidence-id")
    finish.set_defaults(handler=command_transition)
    rewind = transition_commands.add_parser("rewind")
    add_mutation_arguments(rewind)
    rewind.add_argument("slice")
    rewind.add_argument("--to", dest="rewind_to", choices=PHASES, required=True)
    rewind.add_argument("--reason", required=True)
    rewind.add_argument("--evidence", required=True, help="existing evidence ID")
    rewind.set_defaults(handler=command_transition)

    completion = commands.add_parser("completion-check", help="test eligibility for ready_for_review")
    add_state_argument(completion)
    completion.set_defaults(handler=command_completion)

    audit = commands.add_parser("audit", help="render the verified event journal")
    add_state_argument(audit)
    audit.add_argument("--summary", action="store_true")
    audit.set_defaults(handler=command_audit)

    stale = commands.add_parser("stale-check", help="compare the pinned PR base/head with GitHub")
    add_state_argument(stale)
    stale.set_defaults(handler=command_stale_check)

    outbox = commands.add_parser("github-outbox", help="render pending idempotent issue operations")
    add_state_argument(outbox)
    outbox.set_defaults(handler=command_github_outbox)

    recovery = commands.add_parser("recover-lock", help="remove a provably stale engine lock")
    add_state_argument(recovery)
    recovery.set_defaults(handler=command_recover_lock)

    rules = commands.add_parser("rules", help="print validator claims and limits")
    rules.set_defaults(handler=command_rules)
    tests = commands.add_parser("selftest", help="run adversarial engine tests")
    tests.set_defaults(handler=command_selftest)
    examples = commands.add_parser("examples", help="print example mutation payloads")
    examples.set_defaults(handler=command_examples)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return int(args.handler(args))
    except (BdrError, OSError, ValueError) as exc:
        print(f"bdr: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
