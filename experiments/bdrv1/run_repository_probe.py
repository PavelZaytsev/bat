#!/usr/bin/env python3
"""Durable, model-neutral repository probe runner.

The runner owns transport, tool execution, persistence, and context maintenance.  It does not
interpret the methodology, tracker vocabulary, or repository task.  A completed model/tool turn
is the only checkpoint boundary.  Context maintenance produces a neutral continuation packet and
then invokes the same work model with that packet, so one logical run can cross multiple model
context windows without pretending that an interrupted mini-SWE-agent process can be resumed.

The module has no third-party dependencies.  Its built-in OpenAI-compatible adapter and local
shell process make the live path executable; deterministic fake adapters exercise that same path
without a network, container, or model server.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import fnmatch
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import ssl
import stat
import subprocess
import sys
import tempfile
import time
from typing import Any, Callable, Mapping, Protocol, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid


STATE_SCHEMA_VERSION = "bdrv1-repository-probe/v2"
RUN_MANIFEST_SCHEMA_VERSION = "bdrv1-repository-run/v1"
WORKSPACE_FINGERPRINT_ALGORITHM = "bdr-workspace-v2"
POLICY_SCHEMA_VERSION = "bdrv1-repository-probe-policy/v2"
DEFAULT_COMPLETION_COMMAND = "echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT"

WORK_SYSTEM_INSTRUCTION = """You are an autonomous coding agent working on one repository task.
Use the supplied methodology artifacts as written, the repository, and the bash tool as evidence.
Continue the same logical task after a repository-probe continuation packet. Do not ask whether to
proceed while safe work remains. Do not claim a command ran unless its tool observation is present.
For every bash call, arguments are exactly one object whose command field is one JSON string. This
remains true for multiline scripts and patches: command is never an array and its arguments object
closes with the string's quote followed by the object brace, without an array bracket.
When the task is genuinely complete, give the final report and make the exact completion command
described in the task context as your only tool call.
"""

COMPACTION_INSTRUCTION = """This is context maintenance for the same repository task, not a new
task and not a completion decision. You have no tools in this call. Return one JSON object with
exactly these keys: summary, evidence, unresolved_judgments, latest_model_plan. summary and
latest_model_plan must be strings. evidence and unresolved_judgments must be arrays of strings.
Preserve concrete paths, symbols, commands and outcomes, failed hypotheses, open risks, and the
next intended actions. Distinguish observed facts from inference. Do not invent work or claim that
a command ran. Keep enough detail for the same model to continue autonomously.
"""

COMPACTION_RESPONSE_KEYS = (
    "summary",
    "evidence",
    "unresolved_judgments",
    "latest_model_plan",
)
COMPACTION_RESPONSE_FORMAT = {
    "type": "json_schema",
    "json_schema": {
        "name": "repository_probe_context_maintenance",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "evidence": {"type": "array", "items": {"type": "string"}},
                "unresolved_judgments": {
                    "type": "array",
                    "items": {"type": "string"},
                },
                "latest_model_plan": {"type": "string"},
            },
            "required": list(COMPACTION_RESPONSE_KEYS),
            "additionalProperties": False,
        },
    },
}
_UNPARSED_PROTOCOL_VALUE = object()

COMPLETED_RESPONSE_RECOVERY_CONTRACT = {
    "scope": "fully-received-normalized-work-response-adapter-rejection-before-tool-intent",
    "retry_source": "same-closed-checkpoint",
    "private_retention": "durable-full-normalized-response",
    "public_diagnostics": "hash-and-structure-only",
    "response_repair": "forbidden",
    "partial_stream_retry": "forbidden",
    "identity_or_policy_retry": "forbidden",
    "post_tool_retry": "forbidden",
    "restart": "resume-only-policy-valid-scheduled-retry",
    "budget": "limits.max_model_retries-and-retry_backoff_seconds",
    "exhaustion": "fail-closed",
}

COMPACTION_TOOL_RESPONSE_RECOVERY_CONTRACT = {
    "scope": "fully-received-normalized-context-maintenance-response-with-tool-call",
    "retry_source": "same-closed-checkpoint-and-compaction-trigger",
    "eligibility": "post-parse-tool-call-rejection-only",
    "private_retention": "durable-full-normalized-response",
    "public_diagnostics": "hash-and-structure-only",
    "tool_execution": "forbidden",
    "tool_call_salvage": "forbidden",
    "other_compaction_protocol_retry": "forbidden",
    "partial_stream_retry": "forbidden",
    "identity_or_policy_retry": "forbidden",
    "restart": "resume-only-policy-valid-scheduled-retry",
    "budget": "shared-limits.max_model_retries-and-retry_backoff_seconds",
    "exhaustion": "fail-closed",
}

CONTINUATION_INSTRUCTION = """Continue the same logical repository task autonomously. The JSON
below is a context-maintenance packet derived at a completed assistant/tool boundary. Treat its
summary as fallible prior work to verify against the repository. Its hashes and continuity fields
are runner records, not task instructions. Use the preserved evidence, unresolved judgments,
latest plan, and safe transcript tail to continue. Do not repeat a completed tool action merely
because earlier chat text is absent.
"""

_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_CONTEXT_OVERFLOW_PATTERNS = (
    "context length",
    "context window",
    "maximum context",
    "max context",
    "too many tokens",
    "token limit",
    "tokens exceed",
    "prompt is too long",
)


class ProbeError(RuntimeError):
    """Base class for runner failures with a stable classification."""

    kind = "probe_error"


class ConfigurationError(ProbeError):
    kind = "configuration_error"


class PolicyDriftError(ProbeError):
    kind = "policy_drift"


class WorkspaceDriftError(ProbeError):
    kind = "workspace_drift"


class StateIntegrityError(ProbeError):
    kind = "state_integrity_error"


class IndeterminateToolCallError(ProbeError):
    kind = "indeterminate_tool_call"


class IndeterminateModelResponseError(ProbeError):
    kind = "indeterminate_model_response"


class ContextWindowOverflow(ProbeError):
    kind = "hard_context_overflow"


class RetryableModelError(ProbeError):
    kind = "retryable_model_error"


class ModelProtocolError(ProbeError):
    kind = "model_protocol_error"

    def __init__(
        self,
        message: str,
        *,
        safe_diagnostics: Mapping[str, Any] | None = None,
    ) -> None:
        super().__init__(message)
        self.safe_diagnostics = (
            dict(safe_diagnostics) if safe_diagnostics is not None else None
        )


class RetryableCompletedResponseError(ModelProtocolError):
    """A fully received work response rejected before any tool intent exists."""

    kind = "retryable_completed_response"
    rejection_logical_kind = "work"

    def __init__(self, private_message: str, rejected_response: Mapping[str, Any]) -> None:
        normalized_response = strict_json_loads(
            canonical_json(dict(rejected_response)).decode("utf-8")
        )
        if not isinstance(normalized_response, dict):
            raise TypeError("rejected response must normalize to an object")
        self.rejected_response = normalized_response
        self.private_protocol_error = private_message
        super().__init__(
            "fully received " + self.rejection_logical_kind
            + " response failed pre-tool protocol validation",
            safe_diagnostics=_completed_response_rejection_diagnostics(
                normalized_response, private_message, self.rejection_logical_kind
            ),
        )


class RetryableCompactionToolResponseError(RetryableCompletedResponseError):
    """A complete maintenance response rejected solely for attempting a tool call."""

    kind = "retryable_compaction_tool_response"
    rejection_logical_kind = "compaction"

    def __init__(self, private_message: str, rejected_response: Mapping[str, Any]) -> None:
        if private_message != "context-maintenance response attempted a tool call":
            raise ValueError("compaction retry is limited to the exact tool-call rejection")
        super().__init__(private_message, rejected_response)
        try:
            parsed_turn = OpenAICompatibleAdapter._assistant_turn(
                self.rejected_response
            )
        except ModelProtocolError as error:
            raise ValueError(
                "compaction retry requires a successfully parsed response"
            ) from error
        if not parsed_turn.tool_calls:
            raise ValueError(
                "compaction retry requires at least one successfully parsed tool call"
            )


def _completed_response_retry_class(logical_kind: str) -> str:
    if logical_kind == "work":
        return RetryableCompletedResponseError.kind
    if logical_kind == "compaction":
        return RetryableCompactionToolResponseError.kind
    raise StateIntegrityError("completed-response retry has an invalid logical kind")


class RunnerLimitError(ProbeError):
    kind = "runner_limit"


def canonical_json(value: Any) -> bytes:
    """Return the canonical JSON bytes used for all durable hashes."""

    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def fingerprint(value: Any) -> str:
    return sha256_bytes(canonical_json(value))


def _json_value_type(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, str):
        return "string"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, list):
        return "array"
    if isinstance(value, Mapping):
        return "object"
    return "unsupported"


def _hashed_text_diagnostics(value: Any) -> dict[str, Any]:
    diagnostics: dict[str, Any] = {"type": _json_value_type(value)}
    if isinstance(value, str):
        encoded = value.encode("utf-8")
        diagnostics.update({
            "length_bytes": len(encoded),
            "sha256": sha256_bytes(encoded),
        })
    return diagnostics


def _completed_response_rejection_diagnostics(
    response: Mapping[str, Any], private_message: str, logical_kind: str = "work",
) -> dict[str, Any]:
    """Describe a rejected response without exposing its private content."""

    payload = canonical_json(dict(response))
    private_error_bytes = private_message.encode("utf-8")
    choices = response.get("choices")
    first_choice = (
        choices[0]
        if isinstance(choices, list) and choices and isinstance(choices[0], Mapping)
        else None
    )
    message = first_choice.get("message") if isinstance(first_choice, Mapping) else None
    tool_calls = message.get("tool_calls") if isinstance(message, Mapping) else None
    tool_diagnostics: list[dict[str, Any]] = []
    if isinstance(tool_calls, list):
        for index, raw_call in enumerate(tool_calls):
            function = raw_call.get("function") if isinstance(raw_call, Mapping) else None
            tool_diagnostics.append({
                "index": index,
                "call_type": _json_value_type(raw_call),
                "id": _hashed_text_diagnostics(
                    raw_call.get("id") if isinstance(raw_call, Mapping) else None
                ),
                "name": _hashed_text_diagnostics(
                    function.get("name") if isinstance(function, Mapping) else None
                ),
                "arguments": _hashed_text_diagnostics(
                    function.get("arguments") if isinstance(function, Mapping) else None
                ),
            })
    return {
        "schema": (
            "completed-work-response-rejection/v1"
            if logical_kind == "work"
            else "completed-compaction-tool-response-rejection/v1"
        ),
        "logical_kind": logical_kind,
        "response_sha256": sha256_bytes(payload),
        "response_length_bytes": len(payload),
        "response_fields_sha256": fingerprint(sorted(str(key) for key in response)),
        "protocol_error_sha256": sha256_bytes(private_error_bytes),
        "protocol_error_length_bytes": len(private_error_bytes),
        "choices_type": _json_value_type(choices),
        "choice_count": len(choices) if isinstance(choices, list) else None,
        "finish_reason": _hashed_text_diagnostics(
            first_choice.get("finish_reason") if isinstance(first_choice, Mapping) else None
        ),
        "content": _hashed_text_diagnostics(
            message.get("content") if isinstance(message, Mapping) else None
        ),
        "reasoning": _hashed_text_diagnostics(
            message.get("reasoning_content", message.get("reasoning"))
            if isinstance(message, Mapping) else None
        ),
        "tool_calls_type": _json_value_type(tool_calls),
        "tool_call_count": len(tool_calls) if isinstance(tool_calls, list) else None,
        "tool_calls": tool_diagnostics,
    }


def environment_policy(environment: Mapping[str, str] | None) -> dict[str, Any]:
    values = environment or {}
    return {
        "names": sorted(values),
        "value_sha256": {
            name: sha256_bytes(value.encode("utf-8")) for name, value in sorted(values.items())
        },
    }


def strict_json_loads(text: str) -> Any:
    """Parse strict JSON, rejecting duplicate keys and non-finite numbers."""

    def object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key: " + key)
            result[key] = value
        return result

    def bad_constant(value: str) -> None:
        raise ValueError("non-finite JSON number: " + value)

    return json.loads(text, object_pairs_hook=object_pairs, parse_constant=bad_constant)


def _validate_sha256(value: Any, field_name: str) -> str:
    if not isinstance(value, str) or _SHA256_RE.fullmatch(value) is None:
        raise StateIntegrityError(field_name + " must be a lowercase SHA-256")
    return value


def _nonnegative_int(value: Any, field_name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise StateIntegrityError(field_name + " must be a non-negative integer")
    return value


def atomic_write_json(path: Path, value: Any) -> None:
    """Atomically replace one JSON artifact and sync both file and parent directory."""

    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(
        value,
        indent=2,
        sort_keys=True,
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8") + b"\n"
    temporary_name: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix="." + path.name + ".", suffix=".tmp",
            dir=path.parent, delete=False,
        ) as handle:
            temporary_name = handle.name
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
        temporary_name = None
        try:
            directory_fd = os.open(path.parent, os.O_RDONLY)
        except OSError:
            directory_fd = None
        if directory_fd is not None:
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    finally:
        if temporary_name is not None:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def read_json(path: Path) -> Any:
    try:
        return strict_json_loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as error:
        raise StateIntegrityError(f"cannot read {path}: {error}") from error


def _path_is_within(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
    except ValueError:
        return False
    return True


def _normalized_container_path(value: Any, field_name: str) -> PurePosixPath:
    if not isinstance(value, str) or not value:
        raise ConfigurationError(field_name + " must be a non-empty container path")
    path = PurePosixPath(value)
    if not path.is_absolute() or ".." in path.parts or str(path) != value:
        raise ConfigurationError(field_name + " must be an absolute normalized POSIX path")
    return path


def _host_path_identity_sha256(path: Path) -> str:
    return sha256_bytes(str(path.resolve(strict=False)).encode("utf-8"))


@dataclass(frozen=True)
class ArtifactInput:
    """An immutable prompt input whose bytes are checked throughout a run."""

    label: str
    path: Path
    role: str = "methodology"
    model_path: str | None = None

    def declaration(self) -> dict[str, Any]:
        data = self.path.read_bytes()
        data.decode("utf-8", errors="strict")
        return {
            "label": self.label,
            "path": str(self.path.resolve()),
            "model_path": self.model_path or str(self.path.resolve()),
            "role": self.role,
            "size": len(data),
            "sha256": sha256_bytes(data),
        }


@dataclass(frozen=True)
class MonitoredPath:
    """A byte-only repository surface; contents are hashed and never interpreted."""

    category: str
    path: Path
    label: str
    model_path: str | None = None

    def policy_value(self) -> dict[str, Any]:
        return {
            "category": self.category,
            "label": self.label,
            "path": str(self.path.resolve()),
            "model_path": self.model_path,
        }


@dataclass(frozen=True)
class WorkspaceLayout:
    root: Path
    monitored_paths: tuple[MonitoredPath, ...]
    git_semantic_required: bool = True
    git_untracked_exclude_patterns: tuple[str, ...] = ()
    respect_git_ignore: bool = False

    def __post_init__(self) -> None:
        allowed = {"tracker", "source", "test", "evidence"}
        labels: set[tuple[str, str]] = set()
        for item in self.monitored_paths:
            if item.category not in allowed:
                raise ConfigurationError("unknown manifest category: " + item.category)
            key = (item.category, item.label)
            if key in labels:
                raise ConfigurationError("duplicate monitored path label: " + repr(key))
            labels.add(key)
        if not isinstance(self.git_semantic_required, bool) or not isinstance(self.respect_git_ignore, bool):
            raise ConfigurationError("Git semantic settings must be booleans")
        if self.respect_git_ignore:
            raise ConfigurationError(
                "protocol-v2 forbids mutable Git ignore sources; use policy-bound untracked exclusions"
            )
        for pattern in self.git_untracked_exclude_patterns:
            if not isinstance(pattern, str) or not pattern:
                raise ConfigurationError("Git untracked exclusions must be non-empty patterns")

    def policy_value(self) -> dict[str, Any]:
        return {
            "root": str(self.root.resolve()),
            "monitored_paths": [item.policy_value() for item in self.monitored_paths],
            "git_semantic_required": self.git_semantic_required,
            "git_untracked_exclude_patterns": list(self.git_untracked_exclude_patterns),
            "respect_git_ignore": self.respect_git_ignore,
        }

    def capture(self) -> dict[str, Any]:
        categories: dict[str, list[dict[str, Any]]] = {
            "tracker": [], "source": [], "test": [], "evidence": [],
        }
        for item in sorted(
            self.monitored_paths,
            key=lambda value: (value.category, value.label, str(value.path)),
        ):
            categories[item.category].append(self._capture_path(item))
        category_sha256 = {
            category: fingerprint(records) for category, records in categories.items()
        }
        manifest = {
            "categories": categories,
            "category_sha256": category_sha256,
        }
        manifest["manifest_sha256"] = fingerprint(manifest)
        return manifest

    def _capture_path(self, item: MonitoredPath) -> dict[str, Any]:
        path = item.path
        record: dict[str, Any] = {
            "label": item.label,
            "configured_path": str(path.resolve(strict=False)),
        }
        if not path.exists() and not path.is_symlink():
            record["kind"] = "missing"
            return record
        if path.is_symlink():
            target = os.readlink(path)
            record.update({
                "kind": "symlink",
                "mode": stat.S_IMODE(path.lstat().st_mode),
                "target": target,
                "sha256": sha256_bytes(os.fsencode(target)),
            })
            return record
        if path.is_file():
            data = path.read_bytes()
            record.update({
                "kind": "file",
                "mode": stat.S_IMODE(path.lstat().st_mode),
                "size": len(data),
                "sha256": sha256_bytes(data),
            })
            return record
        if not path.is_dir():
            record["kind"] = "other"
            return record

        files: list[dict[str, Any]] = []
        for child in sorted(path.rglob("*"), key=lambda candidate: candidate.as_posix()):
            relative = child.relative_to(path).as_posix()
            if ".git" in child.relative_to(path).parts:
                continue
            if child.is_symlink():
                target = os.readlink(child)
                files.append({
                    "path": relative,
                    "kind": "symlink",
                    "mode": stat.S_IMODE(child.lstat().st_mode),
                    "target": target,
                    "sha256": sha256_bytes(os.fsencode(target)),
                })
            elif child.is_file():
                data = child.read_bytes()
                files.append({
                    "path": relative,
                    "kind": "file",
                    "mode": stat.S_IMODE(child.lstat().st_mode),
                    "size": len(data),
                    "sha256": sha256_bytes(data),
                })
            elif child.is_dir():
                files.append({
                    "path": relative,
                    "kind": "directory",
                    "mode": stat.S_IMODE(child.lstat().st_mode),
                })
        record.update({
            "kind": "directory",
            "mode": stat.S_IMODE(path.lstat().st_mode),
            "entries": files,
            "entries_sha256": fingerprint(files),
        })
        return record

    def fingerprint(self, initial_manifest_sha256: str | None = None) -> tuple[dict[str, Any], dict[str, Any]]:
        manifest = self.capture()
        git_semantic = self._git_semantic(manifest["manifest_sha256"])
        manifest["git_semantic"] = git_semantic
        manifest["manifest_sha256"] = fingerprint({
            "categories": manifest["categories"],
            "category_sha256": manifest["category_sha256"],
            "git_semantic": git_semantic,
        })
        head_sha = git_semantic["head_sha"]
        dirty = git_semantic["dirty"]
        if initial_manifest_sha256 is not None and manifest["manifest_sha256"] != initial_manifest_sha256:
            dirty = True
        value = {
            "algorithm": WORKSPACE_FINGERPRINT_ALGORITHM,
            "head_sha": head_sha,
            "worktree_sha256": manifest["manifest_sha256"],
            "content_delta_sha256": fingerprint(manifest["category_sha256"]),
            "git_semantic_sha256": git_semantic["semantic_sha256"],
            "dirty": dirty,
        }
        return value, manifest

    def _git_semantic(self, fallback: str) -> dict[str, Any]:
        try:
            top_result = subprocess.run(
                ["git", "-C", str(self.root), "rev-parse", "--show-toplevel"],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=10,
            )
        except (OSError, subprocess.SubprocessError):
            if self.git_semantic_required:
                raise ConfigurationError("workspace root is not inside the required Git worktree")
            value = {
                "available": False,
                "head_sha": fallback[:40],
                "dirty": False,
                "index_sha256": sha256_bytes(b""),
                "index_flags_sha256": sha256_bytes(b""),
                "tracked_worktree_modes_sha256": sha256_bytes(b""),
                "staged_diff_sha256": sha256_bytes(b""),
                "worktree_diff_sha256": sha256_bytes(b""),
                "untracked_sha256": sha256_bytes(b""),
                "untracked_count": 0,
            }
            value["semantic_sha256"] = fingerprint(value)
            return value
        top = Path(os.fsdecode(top_result.stdout.strip())).resolve()

        def git_bytes(arguments: list[str]) -> bytes:
            result = subprocess.run(
                ["git", "-C", str(top), "-c", "core.quotePath=true", *arguments],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=30,
            )
            return result.stdout

        try:
            head = git_bytes(["rev-parse", "--verify", "HEAD"]).strip().decode("ascii").lower()
            if re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", head) is None:
                raise ConfigurationError("Git returned an invalid HEAD object id")
            index = git_bytes(["ls-files", "--stage", "-z"])
            index_flags = git_bytes(["ls-files", "-v", "-z"])
            hidden_index_paths = []
            for record in (item for item in index_flags.split(b"\0") if item):
                tag = record[:1]
                if tag == b"S" or (tag and tag.decode("ascii", errors="ignore").islower()):
                    hidden_index_paths.append(record[2:].hex())
            if hidden_index_paths:
                raise WorkspaceDriftError(
                    "Git index contains skip-worktree or assume-unchanged paths; semantic drift is concealed"
                )
            tracked_names = [name for name in git_bytes(["ls-files", "-z"]).split(b"\0") if name]
            tracked_worktree_modes = []
            for raw_name in sorted(tracked_names):
                candidate = top / os.fsdecode(raw_name)
                if candidate.exists() or candidate.is_symlink():
                    tracked_worktree_modes.append({
                        "path_bytes_hex": raw_name.hex(),
                        "mode": stat.S_IMODE(candidate.lstat().st_mode),
                    })
                else:
                    tracked_worktree_modes.append({
                        "path_bytes_hex": raw_name.hex(),
                        "mode": None,
                    })
            staged = git_bytes([
                "diff", "--cached", "--binary", "--no-ext-diff", "--no-renames",
                "--ignore-submodules=none",
            ])
            worktree = git_bytes([
                "diff", "--binary", "--no-ext-diff", "--no-renames",
                "--ignore-submodules=none",
            ])
            untracked_arguments = ["ls-files", "--others", "-z"]
            if self.respect_git_ignore:
                untracked_arguments.append("--exclude-standard")
            untracked_names = [name for name in git_bytes(untracked_arguments).split(b"\0") if name]
        except (OSError, subprocess.SubprocessError):
            raise ConfigurationError("cannot compute the semantic Git worktree fingerprint")

        untracked_records: list[dict[str, Any]] = []
        for raw_name in sorted(untracked_names):
            display_name = os.fsdecode(raw_name)
            posix_name = display_name.replace(os.sep, "/")
            if any(fnmatch.fnmatchcase(posix_name, pattern) for pattern in self.git_untracked_exclude_patterns):
                continue
            candidate = top / display_name
            if candidate.is_symlink():
                target = os.readlink(candidate)
                content_sha256 = sha256_bytes(os.fsencode(target))
                kind = "symlink"
            elif candidate.is_file():
                content_sha256 = sha256_bytes(candidate.read_bytes())
                kind = "file"
            else:
                content_sha256 = sha256_bytes(b"")
                kind = "other"
            untracked_records.append({
                "path_bytes_hex": raw_name.hex(),
                "kind": kind,
                "mode": stat.S_IMODE(candidate.lstat().st_mode) if candidate.exists() or candidate.is_symlink() else None,
                "sha256": content_sha256,
            })
        value = {
            "available": True,
            "repository_root": str(top),
            "head_sha": head,
            "index_sha256": sha256_bytes(index),
            "index_flags_sha256": sha256_bytes(index_flags),
            "tracked_worktree_modes_sha256": fingerprint(tracked_worktree_modes),
            "staged_diff_sha256": sha256_bytes(staged),
            "worktree_diff_sha256": sha256_bytes(worktree),
            "untracked_sha256": fingerprint(untracked_records),
            "untracked_count": len(untracked_records),
            "respect_git_ignore": self.respect_git_ignore,
            "untracked_exclude_patterns": list(self.git_untracked_exclude_patterns),
            "dirty": bool(staged or worktree or untracked_records),
        }
        value["semantic_sha256"] = fingerprint(value)
        return value


@dataclass(frozen=True)
class AdapterCapabilities:
    normalized_complete_turns: bool = True
    durable_replay: bool = True
    no_tools_compaction: bool = True
    streaming: bool = False

    def policy_value(self) -> dict[str, bool]:
        return {
            "normalized_complete_turns": self.normalized_complete_turns,
            "durable_replay": self.durable_replay,
            "no_tools_compaction": self.no_tools_compaction,
            "streaming": self.streaming,
        }


@dataclass(frozen=True)
class ToolAction:
    call_id: str
    name: str
    arguments: dict[str, Any]

    def to_json(self) -> dict[str, Any]:
        return {"call_id": self.call_id, "name": self.name, "arguments": self.arguments}


@dataclass(frozen=True)
class ToolObservation:
    call_id: str
    returncode: int
    output: str | None = None
    output_head: str | None = None
    output_tail: str | None = None
    elided_chars: int = 0
    exception_info: str | None = None

    def to_json(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "call_id": self.call_id,
            "returncode": self.returncode,
            "elided_chars": self.elided_chars,
        }
        if self.output is not None:
            result["output"] = self.output
        if self.output_head is not None:
            result["output_head"] = self.output_head
        if self.output_tail is not None:
            result["output_tail"] = self.output_tail
        if self.exception_info is not None:
            result["exception_info"] = self.exception_info
        return result


@dataclass(frozen=True)
class AssistantTurn:
    content: str
    tool_calls: tuple[ToolAction, ...] = ()
    reasoning: str | None = None
    provider_metadata: dict[str, Any] = field(default_factory=dict)

    def to_message(self) -> dict[str, Any]:
        result: dict[str, Any] = {"role": "assistant", "content": self.content}
        if self.reasoning:
            result["reasoning"] = self.reasoning
        if self.tool_calls:
            result["tool_calls"] = [call.to_json() for call in self.tool_calls]
        if self.provider_metadata:
            result["provider_metadata"] = self.provider_metadata
        return result


@dataclass(frozen=True)
class CompactionSummary:
    summary: str
    evidence: tuple[str, ...]
    unresolved_judgments: tuple[str, ...]
    latest_model_plan: str
    provider_metadata: dict[str, Any] = field(default_factory=dict)
    reasoning: str | None = None

    def to_json(self) -> dict[str, Any]:
        return {
            "summary": self.summary,
            "evidence": list(self.evidence),
            "unresolved_judgments": list(self.unresolved_judgments),
            "latest_model_plan": self.latest_model_plan,
        }

    def to_stored_json(self) -> dict[str, Any]:
        result = self.to_json()
        if self.provider_metadata:
            result["provider_metadata"] = self.provider_metadata
        if self.reasoning is not None:
            result["reasoning"] = self.reasoning
        return result


class ProbeModelAdapter(Protocol):
    capabilities: AdapterCapabilities

    def policy_value(self) -> dict[str, Any]: ...

    def estimate_tokens(self, messages: Sequence[Mapping[str, Any]], *, mode: str) -> int: ...

    def generate(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        max_tokens: int,
        on_sample_started: Callable[[], None] | None = None,
    ) -> AssistantTurn: ...

    def compact(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        instruction: str,
        max_tokens: int,
        on_sample_started: Callable[[], None] | None = None,
    ) -> CompactionSummary: ...


class ToolProcess(Protocol):
    def policy_value(self) -> dict[str, Any]: ...

    def preflight(self) -> None: ...

    def execute(self, action: ToolAction) -> ToolObservation: ...


@dataclass(frozen=True)
class RunnerLimits:
    context_window_tokens: int
    proactive_trigger_tokens: int
    work_output_tokens: int
    compaction_output_tokens: int = 4096
    max_logical_calls: int = 1000
    max_model_retries: int = 2
    retry_backoff_seconds: float = 1.0
    max_tool_calls_per_turn: int = 1
    safe_tail_messages: int = 8
    force_compaction_after_turns: tuple[int, ...] = ()

    def __post_init__(self) -> None:
        integer_fields = {
            "context_window_tokens": self.context_window_tokens,
            "proactive_trigger_tokens": self.proactive_trigger_tokens,
            "work_output_tokens": self.work_output_tokens,
            "compaction_output_tokens": self.compaction_output_tokens,
            "max_logical_calls": self.max_logical_calls,
            "max_model_retries": self.max_model_retries,
            "max_tool_calls_per_turn": self.max_tool_calls_per_turn,
            "safe_tail_messages": self.safe_tail_messages,
        }
        for name, value in integer_fields.items():
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ConfigurationError(name + " must be a non-negative integer")
        if self.context_window_tokens <= 0:
            raise ConfigurationError("context_window_tokens must be positive")
        if not 0 < self.proactive_trigger_tokens < self.context_window_tokens:
            raise ConfigurationError("proactive trigger must be inside the hard context window")
        if self.work_output_tokens <= 0 or self.compaction_output_tokens <= 0:
            raise ConfigurationError("output allowances must be positive")
        if self.max_logical_calls <= 0 or self.max_tool_calls_per_turn <= 0:
            raise ConfigurationError("call limits must be positive")
        if self.safe_tail_messages < 0:
            raise ConfigurationError("safe_tail_messages cannot be negative")
        if self.retry_backoff_seconds < 0 or not math.isfinite(self.retry_backoff_seconds):
            raise ConfigurationError("retry_backoff_seconds must be finite and non-negative")
        previous = -1
        for turn in self.force_compaction_after_turns:
            if isinstance(turn, bool) or not isinstance(turn, int) or turn < 0 or turn <= previous:
                raise ConfigurationError("forced compaction turns must be increasing integers")
            previous = turn

    def policy_value(self) -> dict[str, Any]:
        return {
            "context_window_tokens": self.context_window_tokens,
            "proactive_trigger_tokens": self.proactive_trigger_tokens,
            "work_output_tokens": self.work_output_tokens,
            "compaction_output_tokens": self.compaction_output_tokens,
            "max_logical_calls": self.max_logical_calls,
            "max_model_retries": self.max_model_retries,
            "retry_backoff_seconds": self.retry_backoff_seconds,
            "max_tool_calls_per_turn": self.max_tool_calls_per_turn,
            "safe_tail_messages": self.safe_tail_messages,
            "force_compaction_after_turns": list(self.force_compaction_after_turns),
        }


@dataclass(frozen=True)
class ProbeConfiguration:
    state_dir: Path
    workspace: WorkspaceLayout
    methodology: tuple[ArtifactInput, ...]
    task: ArtifactInput
    limits: RunnerLimits
    completion_command: str = DEFAULT_COMPLETION_COMMAND
    sleep: Callable[[float], None] = field(default=time.sleep, compare=False, repr=False)

    def __post_init__(self) -> None:
        if not self.completion_command or "\n" in self.completion_command:
            raise ConfigurationError("completion command must be one non-empty line")
        state_dir = self.state_dir.resolve(strict=False)
        try:
            result = subprocess.run(
                ["git", "-C", str(self.workspace.root), "rev-parse", "--show-toplevel"],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=10,
            )
            git_top = Path(os.fsdecode(result.stdout.strip())).resolve()
        except (OSError, subprocess.SubprocessError):
            git_top = None
        if git_top is not None and _path_is_within(state_dir, git_top):
            raise ConfigurationError("state directory must be outside the semantic Git worktree")
        for item in self.workspace.monitored_paths:
            candidate = item.path.resolve(strict=False)
            if candidate.is_dir() and _path_is_within(state_dir, candidate):
                raise ConfigurationError("state directory cannot be inside a monitored directory")


BASH_TOOL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "bash",
        "description": "Run one shell command in the configured repository workspace.",
        # vLLM 0.27+ uses this opt-in for schema-constrained structural-tag
        # decoding when tool_choice is auto. Named choice is constrained as
        # well, but keeping the intent explicit prevents a later policy change
        # from silently returning to unconstrained GPT-OSS tool arguments.
        "strict": True,
        "parameters": {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": (
                        "One shell command encoded as a JSON string, including for multiline "
                        "scripts or patches. This value is never an array."
                    ),
                }
            },
            "required": ["command"],
            "additionalProperties": False,
        },
    },
}


@dataclass
class OpenAICompatibleAdapter:
    """Executable OpenAI-compatible or Ollama-native chat adapter."""

    endpoint: str
    model: str
    accepted_response_models: tuple[str, ...]
    served_model_revision: str
    served_model_precision: str
    served_context_window_tokens: int
    server_fingerprint: str
    transport: str = "openai_chat_completions"
    api_key: str = "EMPTY"
    api_key_source: str = "literal-empty-or-runtime"
    tls_ca_file: str | None = None
    tls_ca_sha256: str | None = None
    user_agent: str = "bdrv1-repository-probe/2"
    temperature: float = 0.2
    reasoning_effort: str | None = None
    stream: bool = True
    request_timeout_seconds: float = 3600.0
    estimated_bytes_per_token: float = 2.0
    token_estimate_fixed_overhead: int = 256
    json_response_format: bool = True
    replay_reasoning_field: str | None = "reasoning_content"
    work_tool_choice: str = "auto"
    extra_body: dict[str, Any] = field(default_factory=dict)
    context_maintenance_extra_body: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.endpoint or not self.model:
            raise ConfigurationError("endpoint and model are required")
        parsed_endpoint = urlsplit(self.endpoint)
        if (
            parsed_endpoint.scheme not in {"http", "https"}
            or not parsed_endpoint.hostname
            or parsed_endpoint.username is not None
            or parsed_endpoint.password is not None
            or parsed_endpoint.query
            or parsed_endpoint.fragment
        ):
            raise ConfigurationError("endpoint must be a credential-free HTTP URL without query or fragment")
        if (self.tls_ca_file is None) != (self.tls_ca_sha256 is None):
            raise ConfigurationError("tls_ca_file and tls_ca_sha256 must be supplied together")
        if self.tls_ca_file is not None:
            if not isinstance(self.tls_ca_file, str) or not self.tls_ca_file:
                raise ConfigurationError("tls_ca_file must be a non-empty path string")
            if parsed_endpoint.scheme != "https":
                raise ConfigurationError("a custom TLS CA bundle is valid only for an HTTPS endpoint")
            ca_path = Path(self.tls_ca_file)
            if not ca_path.is_absolute():
                raise ConfigurationError("tls_ca_file must be an absolute path")
            _validate_sha256(self.tls_ca_sha256, "tls_ca_sha256")
            try:
                ca_bytes = ca_path.read_bytes()
            except OSError as error:
                raise ConfigurationError("cannot read the configured TLS CA bundle") from error
            if sha256_bytes(ca_bytes) != self.tls_ca_sha256:
                raise ConfigurationError("configured TLS CA bundle digest does not match")
            try:
                ssl.create_default_context(cafile=str(ca_path))
            except (OSError, ssl.SSLError) as error:
                raise ConfigurationError("configured TLS CA bundle cannot create a verified context") from error
        if (
            not isinstance(self.user_agent, str)
            or not self.user_agent
            or any(ord(character) < 0x20 or ord(character) == 0x7F for character in self.user_agent)
        ):
            raise ConfigurationError("user_agent must be a non-empty HTTP header value")
        if not isinstance(self.stream, bool):
            raise ConfigurationError("stream must be a boolean")
        if self.transport not in {"openai_chat_completions", "ollama_native_chat"}:
            raise ConfigurationError(
                "transport must be openai_chat_completions or ollama_native_chat"
            )
        if self.transport == "ollama_native_chat" and self.stream:
            raise ConfigurationError(
                "ollama_native_chat is currently a complete-response transport and requires stream=false"
            )
        if self.json_response_format is not True:
            raise ConfigurationError(
                "protocol-v2 context maintenance requires strict JSON Schema responses"
            )
        if not self.accepted_response_models or not all(
            isinstance(value, str) and value for value in self.accepted_response_models
        ):
            raise ConfigurationError("accepted_response_models must contain non-empty identities")
        for name, value in (
            ("served_model_revision", self.served_model_revision),
            ("served_model_precision", self.served_model_precision),
            ("server_fingerprint", self.server_fingerprint),
        ):
            if not isinstance(value, str) or not value:
                raise ConfigurationError(name + " is required")
        if isinstance(self.served_context_window_tokens, bool) or self.served_context_window_tokens <= 0:
            raise ConfigurationError("served_context_window_tokens must be positive")
        if not math.isfinite(self.temperature):
            raise ConfigurationError("temperature must be finite")
        if self.request_timeout_seconds <= 0 or not math.isfinite(self.request_timeout_seconds):
            raise ConfigurationError("request timeout must be finite and positive")
        if self.estimated_bytes_per_token <= 0 or not math.isfinite(self.estimated_bytes_per_token):
            raise ConfigurationError("estimated_bytes_per_token must be finite and positive")
        if isinstance(self.token_estimate_fixed_overhead, bool) or self.token_estimate_fixed_overhead < 0:
            raise ConfigurationError("token estimate overhead must be non-negative")
        if self.replay_reasoning_field not in {"reasoning_content", "reasoning"}:
            raise ConfigurationError("protocol-v2 requires a replayable reasoning field")
        if (
            not isinstance(self.work_tool_choice, str)
            or self.work_tool_choice not in {"auto", "bash"}
        ):
            raise ConfigurationError(
                "work_tool_choice must be auto or the named bash function"
            )
        if not isinstance(self.extra_body, dict):
            raise ConfigurationError("extra_body must be an object")
        if not isinstance(self.context_maintenance_extra_body, dict):
            raise ConfigurationError(
                "context_maintenance_extra_body must be an object"
            )
        reserved = {
            "model", "messages", "tools", "tool_choice", "parallel_tool_calls",
            "stream", "stream_options", "max_tokens", "temperature", "response_format",
            "reasoning_effort", "format", "think", "options", "num_predict",
        }
        overlap = reserved.intersection(self.extra_body)
        if overlap:
            raise ConfigurationError("extra_body overrides reserved fields: " + ", ".join(sorted(overlap)))
        maintenance_overlap = reserved.intersection(
            self.context_maintenance_extra_body
        )
        if maintenance_overlap:
            raise ConfigurationError(
                "context_maintenance_extra_body overrides reserved fields: "
                + ", ".join(sorted(maintenance_overlap))
            )
        shared_extra_keys = set(self.extra_body).intersection(
            self.context_maintenance_extra_body
        )
        if shared_extra_keys:
            raise ConfigurationError(
                "extra_body and context_maintenance_extra_body must be disjoint: "
                + ", ".join(sorted(shared_extra_keys))
            )

    @property
    def capabilities(self) -> AdapterCapabilities:
        return AdapterCapabilities(streaming=self.stream)

    def policy_value(self) -> dict[str, Any]:
        endpoint_scheme = urlsplit(self.endpoint).scheme
        return {
            "adapter": (
                "ollama-native-chat/v1"
                if self.transport == "ollama_native_chat"
                else "openai-compatible-chat-completions/v2"
            ),
            "transport": self.transport,
            "endpoint": self.endpoint,
            "model": self.model,
            "served_identity": {
                "accepted_response_models": list(self.accepted_response_models),
                "revision": self.served_model_revision,
                "precision": self.served_model_precision,
                "context_window_tokens": self.served_context_window_tokens,
                "server_fingerprint": self.server_fingerprint,
            },
            "api_key_source": self.api_key_source,
            "tls": {
                "scheme": endpoint_scheme,
                "verification": "required" if endpoint_scheme == "https" else "trusted-private-http",
                "ca_file": self.tls_ca_file,
                "ca_sha256": self.tls_ca_sha256,
            },
            "user_agent": self.user_agent,
            "temperature": self.temperature,
            "work_reasoning_effort": self.reasoning_effort,
            "context_maintenance_reasoning_effort": None,
            "stream": self.stream,
            "stream_options": {"include_usage": True} if self.stream else None,
            "transport_failure_policy": (
                "stream-progress-aware"
                if self.stream else "nonstream-dispatch-fail-closed"
            ),
            "request_timeout_seconds": self.request_timeout_seconds,
            "estimated_bytes_per_token": self.estimated_bytes_per_token,
            "token_estimate_fixed_overhead": self.token_estimate_fixed_overhead,
            "json_response_format": self.json_response_format,
            "work_tool_choice": self._work_tool_choice_value(),
            "work_tool_schema": {
                "protocol": (
                    "ollama-native-function-tool/v1"
                    if self.transport == "ollama_native_chat"
                    else "openai-compatible-function-tool/v1"
                ),
                "name": BASH_TOOL_SCHEMA["function"]["name"],
                "strict": BASH_TOOL_SCHEMA["function"]["strict"],
                "schema_sha256": fingerprint(BASH_TOOL_SCHEMA),
            },
            "context_maintenance_response_format": {
                "protocol": (
                    "ollama-native-json-schema/v1"
                    if self.transport == "ollama_native_chat"
                    else "openai-compatible-json-schema/v1"
                ),
                "name": COMPACTION_RESPONSE_FORMAT["json_schema"]["name"],
                "strict": True,
                "response_format_sha256": fingerprint(COMPACTION_RESPONSE_FORMAT),
            },
            "replay_reasoning_field": self.replay_reasoning_field,
            "extra_body_keys": sorted(self.extra_body),
            "extra_body_sha256": fingerprint(self.extra_body),
            "context_maintenance_extra_body_keys": sorted(
                self.context_maintenance_extra_body
            ),
            "context_maintenance_extra_body_sha256": fingerprint(
                self.context_maintenance_extra_body
            ),
            "capabilities": self.capabilities.policy_value(),
        }

    def estimate_tokens(self, messages: Sequence[Mapping[str, Any]], *, mode: str) -> int:
        if mode not in {"work", "compaction"}:
            raise ConfigurationError("unknown token-estimation mode: " + mode)
        material: dict[str, Any] = {"messages": list(messages)}
        if mode == "work":
            material["tools"] = [BASH_TOOL_SCHEMA]
        byte_count = len(canonical_json(material))
        return math.ceil(byte_count / self.estimated_bytes_per_token) + self.token_estimate_fixed_overhead

    def _work_tool_choice_value(self) -> str | dict[str, Any]:
        if self.work_tool_choice == "bash":
            return {"type": "function", "function": {"name": "bash"}}
        return self.work_tool_choice

    def generate(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        max_tokens: int,
        on_sample_started: Callable[[], None] | None = None,
    ) -> AssistantTurn:
        result = self._request(
            messages,
            max_tokens=max_tokens,
            with_tools=True,
            json_mode=False,
            context_maintenance=False,
            on_sample_started=on_sample_started,
        )
        try:
            turn = self._assistant_turn(result)
            if self.work_tool_choice == "bash" and (
                len(turn.tool_calls) != 1 or turn.tool_calls[0].name != "bash"
            ):
                raise ModelProtocolError(
                    "named bash tool choice response did not contain exactly one bash call"
                )
        except ModelProtocolError as error:
            # _request has already observed a complete stream and validated the served identity.
            # Preserve the complete normalized response without repairing it; the runner decides
            # whether its current
            # durable checkpoint is still eligible for a bounded pre-tool retry.
            raise RetryableCompletedResponseError(str(error), result) from error
        return turn

    def compact(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        instruction: str,
        max_tokens: int,
        on_sample_started: Callable[[], None] | None = None,
    ) -> CompactionSummary:
        compact_messages = list(messages) + [{"role": "user", "content": instruction}]
        result = self._request(
            compact_messages, max_tokens=max_tokens, with_tools=False,
            json_mode=True,
            context_maintenance=True,
            on_sample_started=on_sample_started,
        )
        turn = self._assistant_turn(result)
        if turn.tool_calls:
            # This is the one retryable maintenance rejection: the stream is complete, identity
            # and usage were validated by _request, and parsing proved a tool call was attempted.
            # No tool is registered for maintenance, and the response is never salvaged/executed.
            raise RetryableCompactionToolResponseError(
                "context-maintenance response attempted a tool call", result
            )
        diagnostics = self._compaction_protocol_diagnostics(turn)
        try:
            value = strict_json_loads(turn.content)
        except ValueError:
            raise ModelProtocolError(
                "context-maintenance response is not strict JSON",
                safe_diagnostics=diagnostics,
            ) from None
        diagnostics = self._compaction_protocol_diagnostics(turn, value)
        if not isinstance(value, dict) or set(value) != set(COMPACTION_RESPONSE_KEYS):
            raise ModelProtocolError(
                "context-maintenance response has the wrong fields",
                safe_diagnostics=diagnostics,
            )
        for name in ("summary", "latest_model_plan"):
            if not isinstance(value[name], str):
                raise ModelProtocolError(
                    name + " must be a string",
                    safe_diagnostics=diagnostics,
                )
        for name in ("evidence", "unresolved_judgments"):
            if not isinstance(value[name], list) or not all(isinstance(item, str) for item in value[name]):
                raise ModelProtocolError(
                    name + " must be an array of strings",
                    safe_diagnostics=diagnostics,
                )
        return CompactionSummary(
            summary=value["summary"],
            evidence=tuple(value["evidence"]),
            unresolved_judgments=tuple(value["unresolved_judgments"]),
            latest_model_plan=value["latest_model_plan"],
            provider_metadata=turn.provider_metadata,
            reasoning=turn.reasoning,
        )

    def _compaction_protocol_diagnostics(
        self, turn: AssistantTurn, parsed_value: Any = _UNPARSED_PROTOCOL_VALUE,
    ) -> dict[str, Any]:
        content_bytes = turn.content.encode("utf-8")
        reasoning_present = turn.reasoning is not None
        reasoning_bytes = (turn.reasoning or "").encode("utf-8")
        diagnostics: dict[str, Any] = {
            "response_content_sha256": sha256_bytes(content_bytes),
            "response_content_length_bytes": len(content_bytes),
            "response_reasoning_present": reasoning_present,
            "response_reasoning_sha256": sha256_bytes(reasoning_bytes),
            "response_reasoning_length_bytes": len(reasoning_bytes),
        }
        metadata = turn.provider_metadata
        finish_reason = metadata.get("finish_reason")
        safe_finish_reasons = {
            "stop", "length", "tool_calls", "content_filter", "function_call",
        }
        if finish_reason is None or (
            isinstance(finish_reason, str) and finish_reason in safe_finish_reasons
        ):
            diagnostics["finish_reason"] = finish_reason
        else:
            encoded_finish_reason = canonical_json(finish_reason)
            diagnostics.update({
                "finish_reason": "other",
                "finish_reason_sha256": sha256_bytes(encoded_finish_reason),
                "finish_reason_length_bytes": len(encoded_finish_reason),
            })
        response_model = metadata.get("model")
        if response_model in self.accepted_response_models:
            diagnostics["response_model"] = response_model
        response_system_fingerprint = metadata.get("system_fingerprint")
        if (
            self.server_fingerprint != "response-unavailable"
            and response_system_fingerprint == self.server_fingerprint
        ):
            diagnostics["response_system_fingerprint"] = response_system_fingerprint
        usage = metadata.get("usage")
        if isinstance(usage, Mapping):
            safe_usage: dict[str, int] = {}
            for name in ("prompt_tokens", "completion_tokens", "total_tokens"):
                value = usage.get(name)
                if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                    safe_usage = {}
                    break
                safe_usage[name] = value
            if safe_usage:
                diagnostics["usage"] = safe_usage
        if parsed_value is _UNPARSED_PROTOCOL_VALUE:
            diagnostics["response_top_level_type"] = "unparsed"
            return diagnostics
        if isinstance(parsed_value, Mapping):
            diagnostics["response_top_level_type"] = "object"
            expected_fields = set(COMPACTION_RESPONSE_KEYS)
            actual_fields = set(parsed_value)
            unexpected_fields = sorted(actual_fields - expected_fields)
            diagnostics.update({
                "observed_expected_fields": sorted(actual_fields & expected_fields),
                "missing_expected_fields": sorted(expected_fields - actual_fields),
                "observed_field_count": len(actual_fields),
                "unexpected_field_count": len(unexpected_fields),
                "unexpected_fields_sha256": fingerprint(unexpected_fields),
            })
        elif isinstance(parsed_value, list):
            diagnostics["response_top_level_type"] = "array"
        elif isinstance(parsed_value, bool):
            diagnostics["response_top_level_type"] = "boolean"
        elif isinstance(parsed_value, (int, float)):
            diagnostics["response_top_level_type"] = "number"
        elif isinstance(parsed_value, str):
            diagnostics["response_top_level_type"] = "string"
        else:
            diagnostics["response_top_level_type"] = "null"
        return diagnostics

    def _request(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        max_tokens: int,
        with_tools: bool,
        json_mode: bool,
        context_maintenance: bool,
        on_sample_started: Callable[[], None] | None,
    ) -> dict[str, Any]:
        if self.transport == "ollama_native_chat":
            body: dict[str, Any] = {
                "model": self.model,
                "messages": [self._ollama_message(message) for message in messages],
                "options": {
                    "temperature": self.temperature,
                    "num_predict": max_tokens,
                },
                "stream": False,
                # Ollama's OpenAI compatibility path cannot reliably disable Gemma thinking.
                # Native chat can, and maintenance needs the constrained content channel only.
                "think": not context_maintenance,
            }
        else:
            body = {
                "model": self.model,
                "messages": [self._openai_message(message) for message in messages],
                "temperature": self.temperature,
                "max_tokens": max_tokens,
                "stream": self.stream,
            }
        if self.stream:
            body["stream_options"] = {"include_usage": True}
        if (
            self.transport == "openai_chat_completions"
            and not context_maintenance
            and self.reasoning_effort is not None
        ):
            body["reasoning_effort"] = self.reasoning_effort
        if with_tools:
            body["tools"] = [BASH_TOOL_SCHEMA]
            if self.transport == "openai_chat_completions":
                body.update({
                    "tool_choice": self._work_tool_choice_value(),
                    "parallel_tool_calls": False,
                })
        if json_mode:
            if self.transport == "ollama_native_chat":
                body["format"] = COMPACTION_RESPONSE_FORMAT["json_schema"]["schema"]
            else:
                body["response_format"] = COMPACTION_RESPONSE_FORMAT
        body.update(self.extra_body)
        if context_maintenance:
            body.update(self.context_maintenance_extra_body)

        headers = {
            "Content-Type": "application/json",
            "Accept": "text/event-stream" if self.stream else "application/json",
            "User-Agent": self.user_agent,
        }
        if self.api_key:
            headers["Authorization"] = "Bearer " + self.api_key
        request = Request(
            self.endpoint,
            data=json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        nonstream_request_dispatched = False
        try:
            open_keywords: dict[str, Any] = {"timeout": self.request_timeout_seconds}
            if urlsplit(self.endpoint).scheme == "https":
                open_keywords["context"] = ssl.create_default_context(cafile=self.tls_ca_file)
            nonstream_request_dispatched = not self.stream
            with urlopen(request, **open_keywords) as response:
                if self.stream:
                    result = self._read_stream(response, on_sample_started=on_sample_started)
                    self._validate_response_identity(result)
                    return result
                # Receiving successful response headers proves that the request was accepted.
                # Mark sampling before reading the opaque JSON body so any subsequent I/O loss
                # is terminal and cannot replay a possibly completed remote generation.
                if on_sample_started is not None:
                    on_sample_started()
                raw_bytes = response.read()
                raw = raw_bytes.decode("utf-8", errors="replace")
        except HTTPError as error:
            try:
                detail = error.read().decode("utf-8", errors="replace")
            except OSError:
                detail = str(error)
            self._raise_transport(error.code, detail)
            raise AssertionError("unreachable")
        except (URLError, TimeoutError, OSError) as error:
            if nonstream_request_dispatched:
                raise IndeterminateModelResponseError(
                    "non-streaming transport failed after request dispatch"
                ) from error
            raise RetryableModelError("model transport failed: " + str(error)) from error
        try:
            result = strict_json_loads(raw)
        except ValueError:
            raise ModelProtocolError(
                "non-streaming model response is not strict JSON",
                safe_diagnostics={
                    "response_sha256": sha256_bytes(raw_bytes),
                    "response_length_bytes": len(raw_bytes),
                    "transport": "non-streaming",
                },
            ) from None
        if not isinstance(result, dict):
            raise ModelProtocolError("model response must be a JSON object")
        if "error" in result:
            detail = json.dumps(result["error"], ensure_ascii=False)
            self._raise_transport(400, detail)
        if self.transport == "ollama_native_chat":
            result = self._normalize_ollama_response(result)
        self._validate_response_identity(result)
        return result

    def _normalize_ollama_response(self, result: Mapping[str, Any]) -> dict[str, Any]:
        """Convert one complete Ollama native response into the runner's OpenAI-shaped envelope."""
        message = result.get("message")
        if not isinstance(message, Mapping):
            raise ModelProtocolError("Ollama response has no assistant message")
        content = message.get("content", "")
        thinking = message.get("thinking")
        if not isinstance(content, str):
            raise ModelProtocolError("Ollama assistant content must be a string")
        if thinking is not None and not isinstance(thinking, str):
            raise ModelProtocolError("Ollama assistant thinking must be a string")
        normalized_calls: list[dict[str, Any]] = []
        for index, raw_call in enumerate(message.get("tool_calls") or []):
            if not isinstance(raw_call, Mapping):
                raise ModelProtocolError("Ollama tool call must be an object")
            function = raw_call.get("function")
            if not isinstance(function, Mapping):
                raise ModelProtocolError("Ollama tool call is missing its function")
            name = function.get("name")
            arguments = function.get("arguments")
            if not isinstance(name, str) or not name:
                raise ModelProtocolError("Ollama tool call function name is malformed")
            if isinstance(arguments, Mapping):
                arguments_text = canonical_json(dict(arguments)).decode("utf-8")
            elif isinstance(arguments, str):
                arguments_text = arguments
            else:
                raise ModelProtocolError("Ollama tool call arguments are malformed")
            call_id = raw_call.get("id")
            if not isinstance(call_id, str) or not call_id:
                call_id = "ollama-" + fingerprint({
                    "index": index,
                    "name": name,
                    "arguments": arguments_text,
                })[:24]
            normalized_calls.append({
                "id": call_id,
                "type": "function",
                "function": {"name": name, "arguments": arguments_text},
            })
        prompt_tokens = result.get("prompt_eval_count")
        completion_tokens = result.get("eval_count")
        for name, value in (
            ("prompt_eval_count", prompt_tokens),
            ("eval_count", completion_tokens),
        ):
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ModelProtocolError("Ollama response " + name + " is malformed")
        normalized_message: dict[str, Any] = {
            "role": "assistant",
            "content": content,
        }
        if thinking is not None:
            normalized_message["reasoning"] = thinking
        if normalized_calls:
            normalized_message["tool_calls"] = normalized_calls
        done_reason = result.get("done_reason")
        if done_reason is not None and not isinstance(done_reason, str):
            raise ModelProtocolError("Ollama response done_reason is malformed")
        return {
            "model": result.get("model"),
            "choices": [{
                "message": normalized_message,
                "finish_reason": "tool_calls" if normalized_calls else done_reason,
            }],
            "usage": {
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
                "total_tokens": prompt_tokens + completion_tokens,
            },
        }

    def _validate_response_identity(self, result: Mapping[str, Any]) -> None:
        response_model = result.get("model")
        if not isinstance(response_model, str) or response_model not in self.accepted_response_models:
            raise ModelProtocolError(
                "response model identity is missing or not preregistered: " + repr(response_model)
            )
        response_fingerprint = result.get("system_fingerprint")
        if self.server_fingerprint == "response-unavailable":
            if response_fingerprint is not None:
                raise ModelProtocolError(
                    "server began returning a fingerprint but policy declares it unavailable"
                )
        elif response_fingerprint != self.server_fingerprint:
            raise ModelProtocolError("response server fingerprint is missing or has drifted")
        usage = result.get("usage")
        if not isinstance(usage, Mapping):
            raise ModelProtocolError("model response lacks required token usage")
        for name in ("prompt_tokens", "completion_tokens", "total_tokens"):
            value = usage.get(name)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ModelProtocolError("model response token usage is malformed")

    def _openai_message(self, message: Mapping[str, Any]) -> dict[str, Any]:
        role = message.get("role")
        if role in {"system", "user"}:
            return {"role": role, "content": message.get("content", "")}
        if role == "assistant":
            result: dict[str, Any] = {"role": "assistant", "content": message.get("content", "")}
            reasoning = message.get("reasoning")
            if self.replay_reasoning_field is not None and isinstance(reasoning, str):
                result[self.replay_reasoning_field] = reasoning
            calls = message.get("tool_calls", [])
            if calls:
                result["tool_calls"] = [
                    {
                        "id": call["call_id"],
                        "type": "function",
                        "function": {
                            "name": call["name"],
                            "arguments": canonical_json(call["arguments"]).decode("utf-8"),
                        },
                    }
                    for call in calls
                ]
            return result
        if role == "tool":
            return {
                "role": "tool",
                "tool_call_id": message["tool_call_id"],
                "content": message.get("content", ""),
            }
        raise ModelProtocolError("unsupported normalized message role: " + repr(role))

    def _ollama_message(self, message: Mapping[str, Any]) -> dict[str, Any]:
        role = message.get("role")
        if role in {"system", "user"}:
            return {"role": role, "content": message.get("content", "")}
        if role == "assistant":
            result: dict[str, Any] = {
                "role": "assistant",
                "content": message.get("content", ""),
            }
            reasoning = message.get("reasoning")
            if isinstance(reasoning, str):
                result["thinking"] = reasoning
            calls = message.get("tool_calls", [])
            if calls:
                result["tool_calls"] = [{
                    "function": {
                        "name": call["name"],
                        "arguments": call["arguments"],
                    }
                } for call in calls]
            return result
        if role == "tool":
            return {
                "role": "tool",
                "content": message.get("content", ""),
            }
        raise ModelProtocolError("unsupported normalized message role: " + repr(role))

    def _read_stream(
        self, response: Any, *, on_sample_started: Callable[[], None] | None = None,
    ) -> dict[str, Any]:
        content_parts: list[str] = []
        reasoning_parts: list[str] = []
        tool_parts: dict[int, dict[str, str]] = {}
        usage: Any = None
        response_model: Any = None
        system_fingerprint: Any = None
        response_id: Any = None
        finish_reason: Any = None
        saw_event = False
        saw_done = False
        saw_terminal_finish = False
        try:
            for raw_line in response:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line or line.startswith(":"):
                    continue
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    saw_done = True
                    break
                if not saw_event and on_sample_started is not None:
                    on_sample_started()
                saw_event = True
                event = strict_json_loads(payload)
                if not isinstance(event, dict):
                    raise ValueError("SSE event is not an object")
                if event.get("usage") is not None:
                    usage = event["usage"]
                if event.get("model") is not None:
                    response_model = event["model"]
                if event.get("system_fingerprint") is not None:
                    system_fingerprint = event["system_fingerprint"]
                if event.get("id") is not None:
                    response_id = event["id"]
                choices = event.get("choices") or []
                if not choices:
                    continue
                choice = choices[0]
                finish_reason = choice.get("finish_reason") or finish_reason
                if choice.get("finish_reason") is not None:
                    saw_terminal_finish = True
                delta = choice.get("delta") or {}
                content = delta.get("content")
                if isinstance(content, str):
                    content_parts.append(content)
                reasoning = delta.get("reasoning_content", delta.get("reasoning"))
                if isinstance(reasoning, str):
                    reasoning_parts.append(reasoning)
                for fragment in delta.get("tool_calls") or []:
                    index = fragment.get("index", 0)
                    if isinstance(index, bool) or not isinstance(index, int) or index < 0:
                        raise ValueError("invalid streamed tool-call index")
                    aggregate = tool_parts.setdefault(index, {"id": "", "name": "", "arguments": ""})
                    fragment_id = fragment.get("id")
                    if isinstance(fragment_id, str):
                        if not aggregate["id"]:
                            aggregate["id"] = fragment_id
                        elif fragment_id != aggregate["id"]:
                            aggregate["id"] += fragment_id
                    function = fragment.get("function") or {}
                    if isinstance(function.get("name"), str):
                        aggregate["name"] += function["name"]
                    if isinstance(function.get("arguments"), str):
                        aggregate["arguments"] += function["arguments"]
        except (OSError, UnicodeError, ValueError) as error:
            if saw_event:
                raise IndeterminateModelResponseError(
                    "stream failed after sampled response data; partial output will not be retried: " + str(error)
                ) from error
            raise RetryableModelError("stream failed before sampled response data: " + str(error)) from error
        if not saw_event:
            raise RetryableModelError("stream contained no response events")
        if not saw_done and not saw_terminal_finish:
            raise IndeterminateModelResponseError(
                "stream ended after partial sampling without DONE or a terminal finish reason"
            )
        message: dict[str, Any] = {
            "content": "".join(content_parts),
            "reasoning_content": "".join(reasoning_parts),
        }
        if tool_parts:
            message["tool_calls"] = [
                {
                    "id": value["id"],
                    "type": "function",
                    "function": {"name": value["name"], "arguments": value["arguments"]},
                }
                for _, value in sorted(tool_parts.items())
            ]
        return {
            "choices": [{"message": message, "finish_reason": finish_reason}],
            "usage": usage,
            "model": response_model,
            "system_fingerprint": system_fingerprint,
            "id": response_id,
        }

    @staticmethod
    def _raise_transport(status: int, detail: str) -> None:
        lowered = detail.lower()
        body_sha256 = sha256_bytes(detail.encode("utf-8", errors="replace"))
        if status in {400, 413, 422} and any(pattern in lowered for pattern in _CONTEXT_OVERFLOW_PATTERNS):
            raise ContextWindowOverflow(
                f"provider rejected the request context: HTTP {status}, body_sha256={body_sha256}"
            )
        if status in {408, 409, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524}:
            raise RetryableModelError(
                f"model endpoint returned HTTP {status}, body_sha256={body_sha256}"
            )
        raise ModelProtocolError(
            f"model endpoint returned HTTP {status}, body_sha256={body_sha256}"
        )

    @staticmethod
    def _assistant_turn(result: Mapping[str, Any]) -> AssistantTurn:
        choices = result.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], Mapping):
            raise ModelProtocolError("model response has no first choice")
        choice = choices[0]
        message = choice.get("message")
        if not isinstance(message, Mapping):
            raise ModelProtocolError("model response has no assistant message")
        raw_content = message.get("content")
        if raw_content is None:
            content = ""
        elif isinstance(raw_content, str):
            content = raw_content
        else:
            raise ModelProtocolError("assistant content must be a string or null")
        raw_reasoning = message.get("reasoning_content", message.get("reasoning"))
        reasoning = raw_reasoning if isinstance(raw_reasoning, str) else None
        actions: list[ToolAction] = []
        for raw_call in message.get("tool_calls") or []:
            if not isinstance(raw_call, Mapping):
                raise ModelProtocolError("tool call must be an object")
            call_id = raw_call.get("id")
            function = raw_call.get("function")
            if not isinstance(call_id, str) or not call_id or not isinstance(function, Mapping):
                raise ModelProtocolError("tool call is missing its id or function")
            name = function.get("name")
            arguments_text = function.get("arguments")
            if not isinstance(name, str) or not isinstance(arguments_text, str):
                raise ModelProtocolError("tool call function is malformed")
            try:
                arguments = strict_json_loads(arguments_text)
            except ValueError as error:
                raise ModelProtocolError("tool arguments are not strict JSON: " + str(error)) from error
            if not isinstance(arguments, dict):
                raise ModelProtocolError("tool arguments must be an object")
            actions.append(ToolAction(call_id=call_id, name=name, arguments=arguments))
        metadata: dict[str, Any] = {"finish_reason": choice.get("finish_reason")}
        for name in ("model", "system_fingerprint", "id"):
            if result.get(name) is not None:
                metadata[name] = result[name]
        usage = result.get("usage")
        if isinstance(usage, Mapping):
            metadata["usage"] = dict(usage)
        return AssistantTurn(content=content, reasoning=reasoning, tool_calls=tuple(actions), provider_metadata=metadata)


@dataclass
class LocalShellProcess:
    """Execute the single preregistered bash tool locally without shell replay logic."""

    cwd: Path
    timeout_seconds: float = 900.0
    max_observation_chars: int = 30000
    shell_path: str = "/bin/sh"
    environment: dict[str, str] | None = None
    inherit_environment: bool = False
    scrub_environment_names: tuple[str, ...] = ()
    trusted_disposable_host: bool = False

    def __post_init__(self) -> None:
        if self.timeout_seconds <= 0 or not math.isfinite(self.timeout_seconds):
            raise ConfigurationError("tool timeout must be finite and positive")
        if self.trusted_disposable_host is not True:
            raise ConfigurationError(
                "local shell requires explicit trusted_disposable_host acknowledgement"
            )
        if self.inherit_environment:
            raise ConfigurationError("protocol-v2 local tools may not inherit the runner environment")
        if isinstance(self.max_observation_chars, bool) or self.max_observation_chars < 2:
            raise ConfigurationError("max_observation_chars must be at least two")
        for name in self.scrub_environment_names:
            if not isinstance(name, str) or not name or "=" in name:
                raise ConfigurationError("scrubbed environment names must be non-empty variable names")
        if self.environment and set(self.environment).intersection(self.scrub_environment_names):
            raise ConfigurationError("explicit tool environment contains a scrubbed credential name")

    def policy_value(self) -> dict[str, Any]:
        return {
            "process": "local-shell/v2",
            "cwd": str(self.cwd.resolve()),
            "timeout_seconds": self.timeout_seconds,
            "max_observation_chars": self.max_observation_chars,
            "shell_path": self.shell_path,
            "inherit_environment": self.inherit_environment,
            "environment": environment_policy(self.environment),
            "scrub_environment_names": list(self.scrub_environment_names),
            "trusted_disposable_host": self.trusted_disposable_host,
        }

    def preflight(self) -> None:
        return None

    def execute(self, action: ToolAction) -> ToolObservation:
        if action.name != "bash":
            raise ModelProtocolError("only the bash tool is supported")
        if set(action.arguments) != {"command"} or not isinstance(action.arguments.get("command"), str):
            raise ModelProtocolError("bash arguments must be exactly one string field named command")
        environment: dict[str, str] = {}
        if self.inherit_environment:
            environment.update(os.environ)
        if self.environment:
            environment.update(self.environment)
        for name in self.scrub_environment_names:
            environment.pop(name, None)
        try:
            result = subprocess.run(
                [self.shell_path, "-lc", action.arguments["command"]],
                cwd=self.cwd,
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=self.timeout_seconds,
            )
            output = result.stdout.decode("utf-8", errors="replace")
            return self._observation(action.call_id, result.returncode, output, None)
        except subprocess.TimeoutExpired as error:
            del error
            raise IndeterminateToolCallError(
                "local tool timed out; descendant process quiescence cannot be proven"
            )
        except OSError as error:
            return self._observation(action.call_id, 126, "", str(error))

    def _observation(
        self, call_id: str, returncode: int, output: str, exception_info: str | None,
    ) -> ToolObservation:
        if len(output) <= self.max_observation_chars:
            return ToolObservation(
                call_id=call_id, returncode=returncode, output=output,
                exception_info=exception_info,
            )
        half = self.max_observation_chars // 2
        return ToolObservation(
            call_id=call_id,
            returncode=returncode,
            output_head=output[:half],
            output_tail=output[-half:],
            elided_chars=len(output) - (2 * half),
            exception_info=exception_info,
        )


def _docker_user_is_effective_root(value: Any) -> bool:
    """Return whether Docker's user string selects UID/root as its effective user."""

    if not isinstance(value, str):
        return True
    user = value.strip().split(":", 1)[0].strip()
    return user in {"", "0", "root"}


@dataclass
class DockerExecProcess:
    """Execute tools inside one already-running, identity-bound disposable container."""

    container: str
    expected_container_id: str
    expected_image_fingerprint: str
    expected_container_config_sha256: str
    cwd: str
    expected_user: str
    expected_network_mode: str = "none"
    allowed_mounts: tuple[dict[str, Any], ...] = ()
    allowed_container_environment_names: tuple[str, ...] = ()
    timeout_seconds: float = 900.0
    max_observation_chars: int = 12000
    shell_path: str = "/bin/sh"
    docker_binary: str = "docker"
    environment: dict[str, str] | None = None
    executor: Callable[..., Any] = field(default=subprocess.run, compare=False, repr=False)

    def __post_init__(self) -> None:
        for name, value in (
            ("container", self.container),
            ("expected_container_id", self.expected_container_id),
            ("expected_image_fingerprint", self.expected_image_fingerprint),
            ("expected_container_config_sha256", self.expected_container_config_sha256),
            ("cwd", self.cwd),
            ("expected_network_mode", self.expected_network_mode),
            ("shell_path", self.shell_path),
            ("docker_binary", self.docker_binary),
            ("expected_user", self.expected_user),
        ):
            if not isinstance(value, str) or not value:
                raise ConfigurationError(name + " is required for docker_exec")
        _validate_sha256(
            self.expected_container_config_sha256,
            "expected_container_config_sha256",
        )
        if _docker_user_is_effective_root(self.expected_user):
            raise ConfigurationError(
                "docker_exec expected_user must explicitly select a non-root user"
            )
        if self.timeout_seconds <= 0 or not math.isfinite(self.timeout_seconds):
            raise ConfigurationError("Docker tool timeout must be finite and positive")
        if isinstance(self.max_observation_chars, bool) or self.max_observation_chars < 2:
            raise ConfigurationError("Docker max_observation_chars must be at least two")
        if not all(isinstance(value, dict) for value in self.allowed_mounts):
            raise ConfigurationError("allowed_mounts must contain objects")
        required_mount_fields = {
            "type", "destination", "source_sha256", "source_kind", "rw", "propagation",
        }
        mount_destinations: set[str] = set()
        for record in self.allowed_mounts:
            if set(record) != required_mount_fields:
                raise ConfigurationError("each allowed mount must have the exact identity fields")
            if record["type"] not in {"bind", "volume"}:
                raise ConfigurationError("allowed mount type must be bind or volume")
            if record["source_kind"] not in {"directory", "regular_file", "docker_volume"}:
                raise ConfigurationError("allowed mount source_kind is invalid")
            _validate_sha256(record["source_sha256"], "allowed mount source_sha256")
            destination = str(_normalized_container_path(
                record["destination"], "allowed mount destination",
            ))
            if destination in mount_destinations:
                raise ConfigurationError("allowed mount destinations must be unique")
            mount_destinations.add(destination)
            if not isinstance(record["rw"], bool) or not isinstance(record["propagation"], str):
                raise ConfigurationError("allowed mount rw or propagation is malformed")
        _normalized_container_path(self.cwd, "docker_exec cwd")
        if not all(
            isinstance(value, str) and value
            for value in self.allowed_container_environment_names
        ):
            raise ConfigurationError(
                "allowed_container_environment_names must contain non-empty strings"
            )

    def policy_value(self) -> dict[str, Any]:
        return {
            "process": "docker-exec/v2",
            "container": self.container,
            "expected_container_id": self.expected_container_id,
            "expected_image_fingerprint": self.expected_image_fingerprint,
            "expected_container_config_sha256": self.expected_container_config_sha256,
            "expected_network_mode": self.expected_network_mode,
            "expected_user": self.expected_user,
            "allowed_mounts": list(self.allowed_mounts),
            "allowed_container_environment_names": list(
                self.allowed_container_environment_names
            ),
            "cwd": self.cwd,
            "timeout_seconds": self.timeout_seconds,
            "max_observation_chars": self.max_observation_chars,
            "shell_path": self.shell_path,
            "docker_binary": self.docker_binary,
            "environment": environment_policy(self.environment),
            "host_shell": False,
        }

    def execute(self, action: ToolAction) -> ToolObservation:
        if action.name != "bash":
            raise ModelProtocolError("only the bash tool is supported")
        if set(action.arguments) != {"command"} or not isinstance(action.arguments.get("command"), str):
            raise ModelProtocolError("bash arguments must be exactly one string field named command")
        self._verify_container()
        arguments = [self.docker_binary, "exec", "--workdir", self.cwd]
        for name, value in sorted((self.environment or {}).items()):
            arguments.extend(["--env", name + "=" + value])
        arguments.extend([
            self.container,
            self.shell_path,
            "-lc",
            action.arguments["command"],
        ])
        try:
            result = self.executor(
                arguments,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=self.timeout_seconds,
            )
            if result.returncode == 125:
                raise IndeterminateToolCallError(
                    "docker exec returned its transport failure status; command state is unknown"
                )
            output_bytes = result.stdout or b""
            output = output_bytes.decode("utf-8", errors="replace") if isinstance(output_bytes, bytes) else str(output_bytes)
            return self._observation(action.call_id, result.returncode, output, None)
        except subprocess.TimeoutExpired as error:
            del error
            raise IndeterminateToolCallError(
                "docker exec timed out; container process quiescence cannot be proven"
            )
        except OSError as error:
            raise IndeterminateToolCallError(
                "docker exec transport failed; command dispatch state is unknown"
            ) from error

    def preflight(self) -> None:
        self._verify_container()

    def _verify_container(self) -> None:
        identity = self.inspect_security_identity()
        if identity["container_id"] != self.expected_container_id:
            raise ModelProtocolError("tool container id drifted")
        if identity["image"] != self.expected_image_fingerprint:
            raise ModelProtocolError("tool container image drifted")
        if fingerprint(identity) != self.expected_container_config_sha256:
            raise ModelProtocolError("tool container security configuration drifted")

    def inspect_security_identity(self) -> dict[str, Any]:
        try:
            result = self.executor(
                [
                    self.docker_binary,
                    "inspect",
                    self.container,
                ],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=30,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise ModelProtocolError("cannot verify preregistered tool container: " + str(error)) from error
        raw_output = result.stdout or b""
        output = raw_output.decode("utf-8", errors="replace") if isinstance(raw_output, bytes) else str(raw_output)
        try:
            inspected = strict_json_loads(output)
        except ValueError as error:
            raise ModelProtocolError("Docker inspect returned invalid JSON") from error
        if not isinstance(inspected, list) or len(inspected) != 1 or not isinstance(inspected[0], Mapping):
            raise ModelProtocolError("Docker inspect returned an invalid identity record")
        return self.security_identity_from_inspect(
            inspected[0],
            expected_network_mode=self.expected_network_mode,
            expected_user=self.expected_user,
            allowed_mounts=self.allowed_mounts,
            allowed_environment_names=self.allowed_container_environment_names,
        )

    @staticmethod
    def security_identity_from_inspect(
        inspected: Mapping[str, Any],
        *,
        expected_network_mode: str,
        expected_user: str,
        allowed_mounts: Sequence[Mapping[str, Any]],
        allowed_environment_names: Sequence[str],
    ) -> dict[str, Any]:
        config = inspected.get("Config")
        host_config = inspected.get("HostConfig")
        mounts = inspected.get("Mounts", [])
        state = inspected.get("State")
        network_settings = inspected.get("NetworkSettings")
        if (
            not isinstance(config, Mapping)
            or not isinstance(host_config, Mapping)
            or not isinstance(mounts, list)
            or not isinstance(state, Mapping)
            or not isinstance(network_settings, Mapping)
        ):
            raise ModelProtocolError("Docker inspect lacks Config, HostConfig, or Mounts")
        container_id = inspected.get("Id")
        image = inspected.get("Image")
        network_mode = host_config.get("NetworkMode")
        raw_user = config.get("User")
        user = raw_user if isinstance(raw_user, str) else ""
        if not isinstance(container_id, str) or not container_id:
            raise ModelProtocolError("Docker inspect lacks immutable container id")
        if not isinstance(image, str) or not image:
            raise ModelProtocolError("Docker inspect lacks image fingerprint")
        if network_mode != expected_network_mode:
            raise ModelProtocolError("tool container network mode drifted")
        raw_runtime_networks = network_settings.get("Networks")
        runtime_networks: dict[str, Any]
        if raw_runtime_networks in ({}, None):
            runtime_networks = {}
        elif (
            expected_network_mode == "none"
            and isinstance(raw_runtime_networks, Mapping)
            and set(raw_runtime_networks) == {"none"}
            and isinstance(raw_runtime_networks.get("none"), Mapping)
        ):
            # Docker Desktop reports the built-in `none` network as an attachment even though the
            # container has no routable address.  Accept only that exact address-free shape and bind
            # its opaque daemon identifiers into the normalized identity.  Any named/bridge network
            # or assigned address remains a hard failure.
            none_network = raw_runtime_networks["none"]
            address_fields = (
                "IPAddress",
                "GlobalIPv6Address",
                "Gateway",
                "IPv6Gateway",
                "MacAddress",
            )
            if any(none_network.get(name) not in (None, "") for name in address_fields):
                raise ModelProtocolError("tool container none network has an assigned address")
            if any(
                none_network.get(name) not in (None, 0)
                for name in ("IPPrefixLen", "GlobalIPv6PrefixLen", "GwPriority")
            ):
                raise ModelProtocolError("tool container none network has an assigned prefix")
            if any(
                none_network.get(name) not in (None, [], {})
                for name in ("Aliases", "DNSNames", "DriverOpts", "IPAMConfig", "Links")
            ):
                raise ModelProtocolError("tool container none network exposes routing metadata")
            network_id = none_network.get("NetworkID")
            endpoint_id = none_network.get("EndpointID")
            if not isinstance(network_id, str) or not network_id:
                raise ModelProtocolError("tool container none network lacks an immutable id")
            if not isinstance(endpoint_id, str) or not endpoint_id:
                raise ModelProtocolError("tool container none network lacks an endpoint id")
            runtime_networks = {
                "none": {
                    "network_id_sha256": sha256_bytes(network_id.encode("utf-8")),
                    "endpoint_id_sha256": sha256_bytes(endpoint_id.encode("utf-8")),
                    "addressed": False,
                }
            }
        else:
            raise ModelProtocolError("tool container has a runtime network attachment")
        if state.get("Running") is not True:
            raise ModelProtocolError("tool container is not running")
        if _docker_user_is_effective_root(user):
            raise ModelProtocolError("tool container effective user must be non-root")
        if user != expected_user:
            raise ModelProtocolError("tool container user drifted")
        if host_config.get("Privileged") is not False:
            raise ModelProtocolError("privileged tool containers are forbidden")
        if host_config.get("CapAdd") not in (None, []):
            raise ModelProtocolError("added container capabilities are forbidden")
        cap_drop = host_config.get("CapDrop") or []
        if not isinstance(cap_drop, list) or "ALL" not in cap_drop:
            raise ModelProtocolError("tool container must drop all Linux capabilities")
        if host_config.get("Devices") not in (None, []):
            raise ModelProtocolError("container device passthrough is forbidden")
        if host_config.get("PidMode") == "host" or host_config.get("IpcMode") == "host":
            raise ModelProtocolError("host PID or IPC namespace is forbidden")
        security_options = host_config.get("SecurityOpt") or []
        if not any(str(option).startswith("no-new-privileges") for option in security_options):
            raise ModelProtocolError("tool container must enforce no-new-privileges")
        if host_config.get("ReadonlyRootfs") is not True:
            raise ModelProtocolError("tool container root filesystem must be read-only")

        allowed_environments = set(allowed_environment_names)
        environment_hashes: dict[str, str] = {}
        raw_environment = config.get("Env") or []
        if not isinstance(raw_environment, list):
            raise ModelProtocolError("container environment is malformed")
        for entry in raw_environment:
            if not isinstance(entry, str) or "=" not in entry:
                raise ModelProtocolError("container environment entry is malformed")
            name, value = entry.split("=", 1)
            if name not in allowed_environments:
                raise ModelProtocolError("container environment contains an unapproved variable: " + name)
            environment_hashes[name] = sha256_bytes(value.encode("utf-8"))

        allowed_mount_records = {canonical_json(dict(item)) for item in allowed_mounts}
        mount_records: list[dict[str, Any]] = []
        for mount in mounts:
            if not isinstance(mount, Mapping):
                raise ModelProtocolError("container mount record is malformed")
            source = mount.get("Source")
            destination = mount.get("Destination")
            if not isinstance(source, str) or not isinstance(destination, str):
                raise ModelProtocolError("container mount lacks source or destination")
            if source == "/" or "docker.sock" in source or "docker.sock" in destination:
                raise ModelProtocolError("host root and Docker socket mounts are forbidden")
            mount_type = mount.get("Type")
            if mount_type == "bind":
                try:
                    source_mode = os.lstat(source).st_mode
                except OSError as error:
                    raise ModelProtocolError("container bind source cannot be inspected") from error
                if stat.S_ISDIR(source_mode):
                    source_kind = "directory"
                elif stat.S_ISREG(source_mode):
                    source_kind = "regular_file"
                else:
                    raise ModelProtocolError(
                        "container bind sources must be exact directories or regular files"
                    )
            elif mount_type == "volume":
                source_kind = "docker_volume"
            else:
                raise ModelProtocolError("container mount type is not approved")
            record = {
                "type": mount_type,
                "destination": destination,
                # Bind this to Docker inspect's exact Source path string.  It is an
                # identity/allowlist value, deliberately not a hash of mutable source
                # contents (which the repository workspace fingerprint covers).
                "source_sha256": sha256_bytes(source.encode("utf-8")),
                "source_kind": source_kind,
                "rw": mount.get("RW"),
                "propagation": mount.get("Propagation"),
            }
            if canonical_json(record) not in allowed_mount_records:
                raise ModelProtocolError("container has an unapproved exact mount mapping")
            mount_records.append(record)
        return {
            "container_id": container_id,
            "image": image,
            "network_mode": network_mode,
            "user": user,
            "environment_value_sha256": dict(sorted(environment_hashes.items())),
            "mounts": sorted(mount_records, key=lambda value: value["destination"]),
            "privileged": False,
            "cap_add": [],
            "cap_drop": sorted(cap_drop),
            "devices": [],
            "pid_mode": host_config.get("PidMode") or "",
            "ipc_mode": host_config.get("IpcMode") or "",
            "read_only_rootfs": True,
            "security_opt": sorted(host_config.get("SecurityOpt") or []),
            "running": True,
            "runtime_networks": runtime_networks,
        }

    def _observation(
        self, call_id: str, returncode: int, output: str, exception_info: str | None,
    ) -> ToolObservation:
        if len(output) <= self.max_observation_chars:
            return ToolObservation(
                call_id=call_id,
                returncode=returncode,
                output=output,
                exception_info=exception_info,
            )
        half = self.max_observation_chars // 2
        return ToolObservation(
            call_id=call_id,
            returncode=returncode,
            output_head=output[:half],
            output_tail=output[-half:],
            elided_chars=len(output) - (2 * half),
            exception_info=exception_info,
        )


@dataclass
class ScriptedModelAdapter:
    """Deterministic adapter for integration rehearsals and crash tests."""

    turns: list[AssistantTurn]
    summaries: list[CompactionSummary]
    estimated_tokens: int = 128
    work_requests: list[list[dict[str, Any]]] = field(default_factory=list)
    compaction_requests: list[list[dict[str, Any]]] = field(default_factory=list)
    generate_calls: int = 0
    compact_calls: int = 0

    capabilities = AdapterCapabilities()

    def policy_value(self) -> dict[str, Any]:
        return {
            "adapter": "deterministic-script/v2",
            "script_sha256": fingerprint({
                "turns": [turn.to_message() for turn in self.turns],
                "summaries": [summary.to_stored_json() for summary in self.summaries],
                "estimated_tokens": self.estimated_tokens,
            }),
            "capabilities": self.capabilities.policy_value(),
        }

    def estimate_tokens(self, messages: Sequence[Mapping[str, Any]], *, mode: str) -> int:
        del messages, mode
        return self.estimated_tokens

    def generate(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        max_tokens: int,
        on_sample_started: Callable[[], None] | None = None,
    ) -> AssistantTurn:
        del max_tokens
        if on_sample_started is not None:
            on_sample_started()
        self.work_requests.append([dict(message) for message in messages])
        if self.generate_calls >= len(self.turns):
            raise ModelProtocolError("deterministic work script exhausted")
        result = self.turns[self.generate_calls]
        self.generate_calls += 1
        return result

    def compact(
        self,
        messages: Sequence[Mapping[str, Any]],
        *,
        instruction: str,
        max_tokens: int,
        on_sample_started: Callable[[], None] | None = None,
    ) -> CompactionSummary:
        del instruction, max_tokens
        if on_sample_started is not None:
            on_sample_started()
        self.compaction_requests.append([dict(message) for message in messages])
        if self.compact_calls >= len(self.summaries):
            raise ModelProtocolError("deterministic context-maintenance script exhausted")
        result = self.summaries[self.compact_calls]
        self.compact_calls += 1
        return result


@dataclass
class ScriptedToolProcess:
    """Deterministic no-shell process; optional callbacks can change a fake workspace."""

    observations: list[ToolObservation]
    effects: list[Callable[[ToolAction], None] | None] = field(default_factory=list)
    calls: list[ToolAction] = field(default_factory=list)

    def policy_value(self) -> dict[str, Any]:
        return {
            "process": "deterministic-script/v2",
            "script_sha256": fingerprint({
                "observations": [observation.to_json() for observation in self.observations],
                "effect_slots": len(self.effects),
            }),
        }

    def preflight(self) -> None:
        return None

    def execute(self, action: ToolAction) -> ToolObservation:
        index = len(self.calls)
        if index >= len(self.observations):
            raise ModelProtocolError("deterministic tool script exhausted")
        self.calls.append(action)
        if index < len(self.effects) and self.effects[index] is not None:
            self.effects[index](action)
        result = self.observations[index]
        if result.call_id != action.call_id:
            raise ModelProtocolError("deterministic observation id does not match tool intent")
        return result


def _initial_counters() -> dict[str, int]:
    return {
        "logical_calls_started": 0,
        "logical_calls_completed": 0,
        "compaction_calls_started": 0,
        "compaction_calls_completed": 0,
        "model_attempts_started": 0,
        "model_attempts_completed": 0,
        "model_attempts_abandoned": 0,
        "retries": 0,
        "tool_calls_requested": 0,
        "tool_calls_completed": 0,
        "completed_turns": 0,
        "compactions_completed": 0,
    }


def _tool_message(observation: ToolObservation) -> dict[str, Any]:
    payload = observation.to_json()
    payload.pop("call_id", None)
    return {
        "role": "tool",
        "tool_call_id": observation.call_id,
        "content": canonical_json(payload).decode("utf-8"),
    }


def _turn_from_json(value: Any) -> AssistantTurn:
    if not isinstance(value, Mapping):
        raise StateIntegrityError("stored assistant response must be an object")
    content = value.get("content")
    reasoning = value.get("reasoning")
    if not isinstance(content, str) or (reasoning is not None and not isinstance(reasoning, str)):
        raise StateIntegrityError("stored assistant response text is malformed")
    raw_calls = value.get("tool_calls", [])
    if not isinstance(raw_calls, list):
        raise StateIntegrityError("stored assistant tool calls must be an array")
    calls: list[ToolAction] = []
    for raw_call in raw_calls:
        if not isinstance(raw_call, Mapping):
            raise StateIntegrityError("stored tool call must be an object")
        call_id = raw_call.get("call_id")
        name = raw_call.get("name")
        arguments = raw_call.get("arguments")
        if not isinstance(call_id, str) or not isinstance(name, str) or not isinstance(arguments, dict):
            raise StateIntegrityError("stored tool call is malformed")
        calls.append(ToolAction(call_id=call_id, name=name, arguments=arguments))
    metadata = value.get("provider_metadata", {})
    if not isinstance(metadata, dict):
        raise StateIntegrityError("stored provider metadata must be an object")
    return AssistantTurn(
        content=content,
        reasoning=reasoning,
        tool_calls=tuple(calls),
        provider_metadata=metadata,
    )


def _summary_from_json(value: Any) -> CompactionSummary:
    required = {"summary", "evidence", "unresolved_judgments", "latest_model_plan"}
    optional = {"provider_metadata", "reasoning"}
    if not isinstance(value, Mapping) or not required.issubset(value) or set(value) - required - optional:
        raise StateIntegrityError("stored context-maintenance response is malformed")
    if not isinstance(value["summary"], str) or not isinstance(value["latest_model_plan"], str):
        raise StateIntegrityError("stored context-maintenance text is malformed")
    if not isinstance(value["evidence"], list) or not all(isinstance(item, str) for item in value["evidence"]):
        raise StateIntegrityError("stored evidence must be an array of strings")
    if not isinstance(value["unresolved_judgments"], list) or not all(
        isinstance(item, str) for item in value["unresolved_judgments"]
    ):
        raise StateIntegrityError("stored unresolved judgments must be an array of strings")
    provider_metadata = value.get("provider_metadata", {})
    if not isinstance(provider_metadata, dict):
        raise StateIntegrityError("stored context-maintenance provider metadata must be an object")
    reasoning = value.get("reasoning")
    if reasoning is not None and not isinstance(reasoning, str):
        raise StateIntegrityError("stored context-maintenance reasoning must be text")
    return CompactionSummary(
        summary=value["summary"],
        evidence=tuple(value["evidence"]),
        unresolved_judgments=tuple(value["unresolved_judgments"]),
        latest_model_plan=value["latest_model_plan"],
        provider_metadata=provider_metadata,
        reasoning=reasoning,
    )


class RepositoryProbeRunner:
    """One durable logical run across zero or more proactive context compactions."""

    def __init__(
        self,
        configuration: ProbeConfiguration,
        model: ProbeModelAdapter,
        process: ToolProcess,
    ) -> None:
        self.configuration = configuration
        self.model = model
        self.process = process
        capabilities = model.capabilities
        if not (
            capabilities.normalized_complete_turns
            and capabilities.durable_replay
            and capabilities.no_tools_compaction
        ):
            raise ConfigurationError("model adapter lacks required continuation capabilities")
        model_policy = model.policy_value()
        served_identity = model_policy.get("served_identity")
        if isinstance(served_identity, Mapping) and (
            served_identity.get("context_window_tokens")
            != configuration.limits.context_window_tokens
        ):
            raise ConfigurationError(
                "runner context window must equal the policy-bound served-model context window"
            )
        self.state_path = configuration.state_dir / "state.json"
        self.preregistration_path = configuration.state_dir / "preregistration.json"
        self.continuations_dir = configuration.state_dir / "continuations"
        self.policy = self._build_policy()
        self.policy_sha256 = fingerprint(self.policy)

    def run(self, *, pause_after_compactions: int | None = None) -> dict[str, Any]:
        if (
            pause_after_compactions is not None
            and (
                isinstance(pause_after_compactions, bool)
                or not isinstance(pause_after_compactions, int)
                or pause_after_compactions <= 0
            )
        ):
            raise ConfigurationError("pause_after_compactions must be a positive integer")
        state = self._load_or_initialize()
        if state["status"] == "completed":
            return self._result(state)
        if state["status"] == "failed":
            failure = state.get("failure") or {}
            raise ProbeError("run is already failed: " + str(failure.get("kind", "unknown")))

        try:
            self._recover_transient_state(state)
            if self._pause_checkpoint_reached(state, pause_after_compactions):
                self._verify_inputs()
                self._verify_workspace(state)
                return self._paused_result(state)
            self.process.preflight()
            while state["status"] == "running":
                counters = state["counters"]
                if counters["logical_calls_completed"] >= self.configuration.limits.max_logical_calls:
                    raise RunnerLimitError("maximum logical model calls reached")
                self._verify_inputs()
                self._verify_workspace(state)
                if self._should_compact(state):
                    self._compact(state)
                    if self._pause_checkpoint_reached(state, pause_after_compactions):
                        return self._paused_result(state)
                    continue
                self._work_once(state)
            return self._result(state)
        except ProbeError as error:
            if not isinstance(error, (PolicyDriftError, StateIntegrityError)):
                self._record_failure(state, error)
            raise

    @staticmethod
    def _pause_checkpoint_reached(
        state: Mapping[str, Any], pause_after_compactions: int | None,
    ) -> bool:
        return bool(
            pause_after_compactions is not None
            and state["counters"]["compactions_completed"] >= pause_after_compactions
            and state["checkpoints"][-1]["kind"] == "compaction"
            and state.get("model_inflight") is None
            and state.get("tool_inflight") is None
        )

    def _paused_result(self, state: Mapping[str, Any]) -> dict[str, Any]:
        result = self._result(state)
        result["pause"] = {
            "kind": "compaction_checkpoint",
            "safe_to_resume": True,
            "compactions_completed": state["counters"]["compactions_completed"],
            "checkpoint_id": state["checkpoints"][-1]["checkpoint_id"],
            "checkpoint_sha256": state["checkpoints"][-1]["checkpoint_sha256"],
        }
        return result

    def _build_policy(self) -> dict[str, Any]:
        inputs = [artifact.declaration() for artifact in self.configuration.methodology]
        task = self.configuration.task.declaration()
        runner_source_sha256 = sha256_bytes(Path(__file__).read_bytes())
        return {
            "schema_version": POLICY_SCHEMA_VERSION,
            "runner": {
                "protocol": "repository-probe/v2",
                "implementation_sha256": runner_source_sha256,
                "checkpoint_boundary": "complete-assistant-and-tool-observations",
                "pending_tool_resume": "fail-without-replay",
                "completed_response_recovery": COMPLETED_RESPONSE_RECOVERY_CONTRACT,
                "compaction_tool_response_recovery": (
                    COMPACTION_TOOL_RESPONSE_RECOVERY_CONTRACT
                ),
                "context_maintenance": "same-model-no-tools-structured-packet",
                "methodology_delivery": "ordered-model-path-and-hash-manifest",
                "methodology_bytes_embedded": False,
                "work_system_instruction_sha256": sha256_bytes(WORK_SYSTEM_INSTRUCTION.encode("utf-8")),
                "compaction_instruction_sha256": sha256_bytes(COMPACTION_INSTRUCTION.encode("utf-8")),
                "continuation_instruction_sha256": sha256_bytes(CONTINUATION_INSTRUCTION.encode("utf-8")),
            },
            "model": self.model.policy_value(),
            "process": self.process.policy_value(),
            "limits": self.configuration.limits.policy_value(),
            "workspace": self.configuration.workspace.policy_value(),
            "immutable_methodology": inputs,
            "immutable_task": task,
            "completion_command": self.configuration.completion_command,
        }

    def _base_messages(self) -> list[dict[str, Any]]:
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": WORK_SYSTEM_INSTRUCTION},
        ]
        methodology_manifest = []
        for index, artifact in enumerate(self.configuration.methodology, start=1):
            declaration = artifact.declaration()
            methodology_manifest.append({
                "ordinal": index,
                "label": declaration["label"],
                "model_path": declaration["model_path"],
                "size": declaration["size"],
                "sha256": declaration["sha256"],
            })
        messages.append({
            "role": "user",
            "content": (
                "Before applying the methodology, use bash to read every immutable artifact below "
                "completely in ordinal order. The runner verifies their host bytes before every model "
                "or tool action. The manifest is a delivery record, not a substitute or summary.\n"
                + canonical_json(methodology_manifest).decode("utf-8")
            ),
        })
        task_text = self.configuration.task.path.read_bytes().decode("utf-8", errors="strict")
        messages.append({
            "role": "user",
            "content": (
                "Immutable repository task:\n"
                "----- BEGIN TASK -----\n"
                + task_text
                + "\n----- END TASK -----\n"
                "When genuinely complete, make this exact command your only tool call:\n"
                + self.configuration.completion_command
            ),
        })
        return messages

    def _load_or_initialize(self) -> dict[str, Any]:
        self.configuration.state_dir.mkdir(parents=True, exist_ok=True)
        preregistration = {
            "schema_version": RUN_MANIFEST_SCHEMA_VERSION,
            "run_policy": self.policy,
            "run_policy_sha256": self.policy_sha256,
        }
        if self.preregistration_path.exists():
            existing = read_json(self.preregistration_path)
            if existing != preregistration:
                raise PolicyDriftError("current model or runner policy differs from preregistration")
        else:
            atomic_write_json(self.preregistration_path, preregistration)

        if not self.state_path.exists():
            workspace_fingerprint, workspace_manifest = self.configuration.workspace.fingerprint()
            base_messages = self._base_messages()
            state: dict[str, Any] = {
                "schema_version": STATE_SCHEMA_VERSION,
                "run_id": str(uuid.uuid4()),
                "policy_sha256": self.policy_sha256,
                "status": "running",
                "failure": None,
                "base_messages": base_messages,
                "transcript": list(base_messages),
                "active_messages": list(base_messages),
                "history_sha256": "",
                "active_context_sha256": "",
                "initial_manifest_sha256": workspace_manifest["manifest_sha256"],
                "expected_workspace_fingerprint": workspace_fingerprint,
                "workspace_manifest": workspace_manifest,
                "counters": _initial_counters(),
                "events": [],
                "checkpoints": [],
                "continuations": [],
                "private_rejected_model_responses": [],
                "forced_compactions_consumed": [],
                "model_inflight": None,
                "tool_inflight": None,
                "pending_compaction_trigger": None,
            }
            self._checkpoint(state, "initial")
            return state

        state = read_json(self.state_path)
        self._validate_state(state)
        self._verify_inputs()
        self._ensure_continuation_artifacts(state)
        return state

    def _verify_inputs(self) -> None:
        current_methodology = [artifact.declaration() for artifact in self.configuration.methodology]
        current_task = self.configuration.task.declaration()
        if current_methodology != self.policy["immutable_methodology"]:
            raise PolicyDriftError("immutable methodology bytes or paths changed")
        if current_task != self.policy["immutable_task"]:
            raise PolicyDriftError("immutable task bytes or path changed")
        if fingerprint(self.policy) != self.policy_sha256:
            raise PolicyDriftError("in-memory policy fingerprint changed")

    def _current_workspace(self, state: Mapping[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        return self.configuration.workspace.fingerprint(state["initial_manifest_sha256"])

    def _verify_workspace(self, state: Mapping[str, Any]) -> None:
        current, _ = self._current_workspace(state)
        if current != state["expected_workspace_fingerprint"]:
            raise WorkspaceDriftError(
                "monitored repository bytes or revision changed outside a recorded tool observation"
            )

    def _recover_transient_state(self, state: dict[str, Any]) -> None:
        tool_inflight = state.get("tool_inflight")
        if tool_inflight is not None:
            if not isinstance(tool_inflight, Mapping):
                raise StateIntegrityError("tool_inflight must be an object or null")
            if tool_inflight.get("status") != "observations_recorded":
                raise IndeterminateToolCallError(
                    "a tool intent lacks a complete durable observation; it will not be replayed"
                )
            post_fingerprint = tool_inflight.get("post_workspace_fingerprint")
            post_manifest = tool_inflight.get("post_workspace_manifest")
            current, _ = self._current_workspace(state)
            if current != post_fingerprint:
                raise WorkspaceDriftError("workspace changed after the recorded tool observation")
            state["expected_workspace_fingerprint"] = post_fingerprint
            state["workspace_manifest"] = post_manifest
            recovered_completion = self._stored_tool_completion(tool_inflight)
            if recovered_completion:
                state["status"] = "completed"
            state["tool_inflight"] = None
            self._checkpoint(
                state, "recovered-completion" if recovered_completion else "recovered-observation"
            )

        model_inflight = state.get("model_inflight")
        if model_inflight is not None and model_inflight.get("response") is None:
            counters = state["counters"]
            outstanding = (
                counters["model_attempts_started"]
                - counters["model_attempts_completed"]
                - counters["model_attempts_abandoned"]
            )
            if outstanding > 0:
                counters["model_attempts_abandoned"] += 1
                self._append_event(state, "model_attempt_abandoned", {
                    "logical_kind": model_inflight["kind"],
                    "logical_index": model_inflight["logical_index"],
                    "attempt_index": model_inflight.get("attempt_index"),
                    "reason": "runner_restart_during_dispatched_attempt",
                })
                raise IndeterminateModelResponseError(
                    "the runner stopped during a dispatched model attempt; it will not retry"
                )
            if model_inflight.get("attempt_index") is not None:
                attempt_event = self._persisted_terminal_attempt_event(
                    state, model_inflight
                )
                if attempt_event is None:
                    raise StateIntegrityError(
                        "finished in-flight model attempt lacks its durable terminal event"
                    )
                event_kind, attempt_data = attempt_event
                if event_kind == "model_attempt_abandoned":
                    raise IndeterminateModelResponseError(
                        "a prior restart abandoned this model attempt; it will not retry"
                    )
                outcome = attempt_data.get("outcome")
                if outcome == "completed_response_retry_scheduled":
                    rejection = self._rejection_for_attempt(
                        state, model_inflight, attempt_data
                    )
                    retry_backoff_seconds = attempt_data.get("retry_backoff_seconds")
                    ended_unix_seconds = attempt_data.get("ended_unix_seconds")
                    if (
                        model_inflight.get("sample_started") is not True
                        or attempt_data.get("retry_class")
                        != _completed_response_retry_class(model_inflight.get("kind"))
                        or model_inflight.get("failures", 0) < 1
                        or model_inflight.get("failures", 0)
                        > self.configuration.limits.max_model_retries
                        or isinstance(retry_backoff_seconds, bool)
                        or not isinstance(retry_backoff_seconds, (int, float))
                        or not math.isfinite(retry_backoff_seconds)
                        or retry_backoff_seconds
                        != self.configuration.limits.retry_backoff_seconds
                        or isinstance(ended_unix_seconds, bool)
                        or not isinstance(ended_unix_seconds, (int, float))
                        or not math.isfinite(ended_unix_seconds)
                        or rejection.get("protocol_diagnostics")
                        != attempt_data.get("protocol_diagnostics")
                    ):
                        raise StateIntegrityError(
                            "persisted completed-response retry is not policy-valid"
                        )
                    self._assert_completed_response_retry_boundary(
                        state, model_inflight
                    )
                    remaining_backoff = max(
                        0.0,
                        ended_unix_seconds + retry_backoff_seconds - time.time(),
                    )
                    if remaining_backoff > 0:
                        self.configuration.sleep(remaining_backoff)
                    return
                if outcome == "retry_scheduled":
                    retry_backoff_seconds = attempt_data.get("retry_backoff_seconds")
                    ended_unix_seconds = attempt_data.get("ended_unix_seconds")
                    if (
                        model_inflight.get("sample_started") is not False
                        or attempt_data.get("retry_class") != RetryableModelError.kind
                        or model_inflight.get("failures", 0) < 1
                        or model_inflight.get("failures", 0)
                        > self.configuration.limits.max_model_retries
                        or isinstance(retry_backoff_seconds, bool)
                        or not isinstance(retry_backoff_seconds, (int, float))
                        or not math.isfinite(retry_backoff_seconds)
                        or retry_backoff_seconds
                        != self.configuration.limits.retry_backoff_seconds
                        or isinstance(ended_unix_seconds, bool)
                        or not isinstance(ended_unix_seconds, (int, float))
                        or not math.isfinite(ended_unix_seconds)
                    ):
                        raise StateIntegrityError(
                            "persisted model retry is not a policy-valid zero-sample retry"
                        )
                    remaining_backoff = max(
                        0.0,
                        ended_unix_seconds + retry_backoff_seconds - time.time(),
                    )
                    if remaining_backoff > 0:
                        self.configuration.sleep(remaining_backoff)
                    return
                if outcome == "indeterminate":
                    raise IndeterminateModelResponseError(
                        "a durably terminal model attempt was indeterminate; it will not retry"
                    )
                if outcome == "hard_context_overflow":
                    raise ContextWindowOverflow(
                        "a durably terminal model attempt exceeded the context window"
                    )
                if outcome == "protocol_failure":
                    safe_diagnostics = attempt_data.get("protocol_diagnostics")
                    if safe_diagnostics is not None and not isinstance(
                        safe_diagnostics, Mapping
                    ):
                        raise StateIntegrityError(
                            "persisted protocol diagnostics are malformed"
                        )
                    raise ModelProtocolError(
                        "a durably terminal model attempt failed protocol validation",
                        safe_diagnostics=safe_diagnostics,
                    )
                if outcome == "retry_exhausted":
                    raise RetryableModelError(
                        "a durably terminal model attempt exhausted its retry policy"
                    )
                if outcome == "completed_response_retry_exhausted":
                    rejection = self._rejection_for_attempt(
                        state, model_inflight, attempt_data
                    )
                    if (
                        model_inflight.get("sample_started") is not True
                        or attempt_data.get("retry_class")
                        != _completed_response_retry_class(model_inflight.get("kind"))
                        or model_inflight.get("failures", 0)
                        != self.configuration.limits.max_model_retries + 1
                        or rejection.get("protocol_diagnostics")
                        != attempt_data.get("protocol_diagnostics")
                    ):
                        raise StateIntegrityError(
                            "persisted completed-response exhaustion is not policy-valid"
                        )
                    self._assert_completed_response_retry_boundary(
                        state, model_inflight
                    )
                    raise ModelProtocolError(
                        "fully received " + str(model_inflight.get("kind"))
                        + " response exhausted pre-tool retry policy",
                        safe_diagnostics=rejection["protocol_diagnostics"],
                    )
                if outcome == "completed":
                    raise StateIntegrityError(
                        "completed model attempt is missing its durable response"
                    )
                raise StateIntegrityError(
                    "in-flight model attempt has an unknown durable terminal outcome"
                )

    @staticmethod
    def _persisted_terminal_attempt_event(
        state: Mapping[str, Any], inflight: Mapping[str, Any],
    ) -> tuple[str, Mapping[str, Any]] | None:
        """Find the durable terminal event for the current in-flight attempt."""

        attempt_index = inflight.get("attempt_index")
        if not state["events"]:
            return None
        event = state["events"][-1]
        if event.get("kind") not in {
            "model_attempt_finished", "model_attempt_abandoned",
        }:
            return None
        data = event.get("data")
        if not isinstance(data, Mapping):
            return None
        if (
            data.get("logical_kind") != inflight.get("kind")
            or data.get("logical_index") != inflight.get("logical_index")
            or data.get("attempt_index") != attempt_index
        ):
            return None
        return event["kind"], data

    @staticmethod
    def _rejection_for_attempt(
        state: Mapping[str, Any],
        inflight: Mapping[str, Any],
        attempt_data: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        records = state.get("private_rejected_model_responses")
        if not isinstance(records, list) or not records:
            raise StateIntegrityError(
                "completed-response terminal event lacks its private rejection"
            )
        rejection = records[-1]
        if (
            not isinstance(rejection, Mapping)
            or attempt_data.get("rejection_id") != rejection.get("rejection_id")
            or attempt_data.get("rejection_sha256")
            != rejection.get("rejection_sha256")
            or rejection.get("logical_kind") != inflight.get("kind")
            or rejection.get("logical_index") != inflight.get("logical_index")
            or rejection.get("attempt_index") != inflight.get("attempt_index")
            or rejection.get("failure_count") != inflight.get("failures")
            or rejection.get("retry_class") != attempt_data.get("retry_class")
            or rejection.get("source_compaction_trigger_sha256")
            != inflight.get("source_compaction_trigger_sha256")
            or rejection.get("source_checkpoint_sha256")
            != inflight.get("source_checkpoint_sha256")
        ):
            raise StateIntegrityError(
                "completed-response terminal event does not match its private rejection"
            )
        return rejection

    def _should_compact(self, state: dict[str, Any]) -> bool:
        inflight = state.get("model_inflight")
        if inflight is not None:
            kind = inflight.get("kind")
            if kind == "compaction":
                return True
            if kind == "work":
                return False
            raise StateIntegrityError("unknown in-flight model call kind")
        if state.get("pending_compaction_trigger") is not None:
            return True
        completed_turns = state["counters"]["completed_turns"]
        forced = (
            completed_turns in self.configuration.limits.force_compaction_after_turns
            and completed_turns not in state["forced_compactions_consumed"]
        )
        estimated = self.model.estimate_tokens(state["active_messages"], mode="work")
        estimated += self.configuration.limits.work_output_tokens
        state["last_work_request_estimate"] = estimated
        triggered = forced or estimated >= self.configuration.limits.proactive_trigger_tokens
        if triggered:
            trigger = {
                "reason": "forced_turn" if forced else "estimated_threshold",
                "estimated_tokens": estimated,
                "threshold_tokens": self.configuration.limits.proactive_trigger_tokens,
                "hard_context_tokens": self.configuration.limits.context_window_tokens,
                "completed_turns": completed_turns,
            }
            state["pending_compaction_trigger"] = trigger
            self._append_event(state, "context_compaction_triggered", trigger)
        else:
            self._persist(state)
        return triggered

    def _compact(self, state: dict[str, Any]) -> None:
        self._verify_inputs()
        self._verify_workspace(state)
        source_checkpoint = state["checkpoints"][-1]
        inflight = state.get("model_inflight")
        if inflight is None:
            compact_estimate_messages = list(state["active_messages"]) + [
                {"role": "user", "content": COMPACTION_INSTRUCTION},
            ]
            estimated = self.model.estimate_tokens(compact_estimate_messages, mode="compaction")
            estimated += self.configuration.limits.compaction_output_tokens
            if estimated >= self.configuration.limits.context_window_tokens:
                raise ContextWindowOverflow(
                    "proactive context maintenance started too late for its own request"
                )
            inflight = self._begin_model_call(state, "compaction", source_checkpoint["checkpoint_sha256"])
        elif inflight.get("kind") != "compaction":
            raise StateIntegrityError("cannot compact while a work call is in flight")

        if inflight.get("response") is None:
            summary = self._attempt_model_call(
                state,
                inflight,
                lambda on_sample_started: self.model.compact(
                    state["active_messages"],
                    instruction=COMPACTION_INSTRUCTION,
                    max_tokens=self.configuration.limits.compaction_output_tokens,
                    on_sample_started=on_sample_started,
                ),
                lambda value: value.to_stored_json(),
            )
        else:
            summary = _summary_from_json(inflight["response"])
        self._verify_workspace(state)

        summary_value = summary.to_json()
        summary_sha256 = fingerprint(summary_value)
        continuation_number = len(state["continuations"]) + 1
        continuation_id = f"continuation-{continuation_number:06d}"
        source_workspace = state["expected_workspace_fingerprint"]
        safe_tail = self._safe_transcript_tail(state)
        continuation_record: dict[str, Any] = {
            "continuation_id": continuation_id,
            "source_checkpoint_id": source_checkpoint["checkpoint_id"],
            "source_checkpoint_sha256": source_checkpoint["checkpoint_sha256"],
            "source_policy_sha256": self.policy_sha256,
            "source_history_sha256": state["history_sha256"],
            "source_workspace_fingerprint": source_workspace,
            "source_completed_turns": state["counters"]["completed_turns"],
            "source_model_calls": state["counters"]["logical_calls_completed"],
            "source_tool_calls_completed": state["counters"]["tool_calls_completed"],
            "summary_sha256": summary_sha256,
            "complete_turn_boundary": True,
            "tool_calls_closed": True,
            "workspace_continuity": True,
            "trigger_reason": state["pending_compaction_trigger"]["reason"],
            "trigger_estimated_tokens": state["pending_compaction_trigger"]["estimated_tokens"],
            "trigger_threshold_tokens": state["pending_compaction_trigger"]["threshold_tokens"],
            "maintenance_reasoning_sha256": (
                sha256_bytes(summary.reasoning.encode("utf-8"))
                if summary.reasoning is not None else None
            ),
        }
        packet = {
            "kind": "repository_probe_continuation",
            "protocol_version": 2,
            "continuity": dict(continuation_record),
            "preserved": {
                **summary_value,
                "safe_transcript_tail": safe_tail,
                "workspace_manifest_sha256": state["workspace_manifest"]["manifest_sha256"],
                "workspace_category_sha256": state["workspace_manifest"]["category_sha256"],
            },
        }
        continuation_record["packet_sha256"] = fingerprint(packet)
        manifest = {
            "schema_version": RUN_MANIFEST_SCHEMA_VERSION,
            "run_policy": self.policy,
            "run_policy_sha256": self.policy_sha256,
            "previous_continuation_manifest_sha256": (
                state["continuations"][-1]["manifest_sha256"] if state["continuations"] else None
            ),
            "continuation": continuation_record,
            "packet": packet,
            "maintenance_reasoning": summary.reasoning,
        }
        manifest["manifest_sha256"] = fingerprint(manifest)
        state["active_messages"] = list(state["base_messages"]) + [{
            "role": "user",
            "content": CONTINUATION_INSTRUCTION + "\n" + canonical_json(packet).decode("utf-8"),
        }]
        state["continuations"].append(manifest)
        counters = state["counters"]
        counters["compaction_calls_completed"] += 1
        counters["compactions_completed"] += 1
        completed_turns = counters["completed_turns"]
        if completed_turns in self.configuration.limits.force_compaction_after_turns:
            state["forced_compactions_consumed"].append(completed_turns)
        self._append_logical_finished_event(
            state,
            inflight,
            usage=summary.provider_metadata.get("usage"),
            extra={"packet_sha256": continuation_record["packet_sha256"]},
            persist=False,
        )
        state["model_inflight"] = None
        state["pending_compaction_trigger"] = None
        self._checkpoint(state, "compaction")
        self._write_continuation_artifact(manifest)

        next_estimate = self.model.estimate_tokens(state["active_messages"], mode="work")
        next_estimate += self.configuration.limits.work_output_tokens
        if next_estimate >= self.configuration.limits.proactive_trigger_tokens:
            raise ContextWindowOverflow("context-maintenance packet did not restore proactive headroom")

    def _work_once(self, state: dict[str, Any]) -> None:
        self._verify_inputs()
        self._verify_workspace(state)
        inflight = state.get("model_inflight")
        if inflight is None:
            inflight = self._begin_model_call(
                state, "work", state["checkpoints"][-1]["checkpoint_sha256"]
            )
        elif inflight.get("kind") != "work":
            raise StateIntegrityError("cannot make a work call while context maintenance is in flight")

        if inflight.get("response") is None:
            turn = self._attempt_model_call(
                state,
                inflight,
                lambda on_sample_started: self.model.generate(
                    state["active_messages"],
                    max_tokens=self.configuration.limits.work_output_tokens,
                    on_sample_started=on_sample_started,
                ),
                lambda value: value.to_message(),
            )
        else:
            turn = _turn_from_json(inflight["response"])
        self._verify_workspace(state)
        self._preflight_turn(state, turn)

        assistant_message = turn.to_message()
        state["transcript"].append(assistant_message)
        state["active_messages"].append(assistant_message)
        state["counters"]["logical_calls_completed"] += 1
        state["counters"]["tool_calls_requested"] += len(turn.tool_calls)
        self._append_logical_finished_event(
            state,
            inflight,
            usage=turn.provider_metadata.get("usage"),
            extra={"tool_calls_requested": len(turn.tool_calls)},
            persist=False,
        )
        state["model_inflight"] = None

        if not turn.tool_calls:
            state["counters"]["completed_turns"] += 1
            self._checkpoint(state, "assistant-no-tools")
            return

        state["tool_inflight"] = {
            "status": "pending",
            "source_checkpoint_sha256": state["checkpoints"][-1]["checkpoint_sha256"],
            "actions": [action.to_json() for action in turn.tool_calls],
            "observations": [],
            "pre_workspace_fingerprint": state["expected_workspace_fingerprint"],
        }
        self._persist(state)

        observations: list[ToolObservation] = []
        for action in turn.tool_calls:
            self._verify_inputs()
            self._verify_workspace(state)
            try:
                observation = self.process.execute(action)
            except Exception as error:
                raise IndeterminateToolCallError(
                    "tool process raised after durable intent; action will not be replayed: " + str(error)
                ) from error
            if observation.call_id != action.call_id:
                raise IndeterminateToolCallError(
                    "tool observation id did not match durable intent; action will not be replayed"
                )
            observations.append(observation)
            tool_message = _tool_message(observation)
            state["transcript"].append(tool_message)
            state["active_messages"].append(tool_message)
            state["counters"]["tool_calls_completed"] += 1
            state["tool_inflight"]["observations"].append(observation.to_json())
            try:
                current_fingerprint, current_manifest = self._current_workspace(state)
            except ProbeError:
                if len(state["tool_inflight"]["observations"]) == len(
                    state["tool_inflight"]["actions"]
                ):
                    state["counters"]["completed_turns"] += 1
                state["tool_inflight"]["status"] = "observations_recorded_workspace_invalid"
                self._persist(state)
                raise
            state["expected_workspace_fingerprint"] = current_fingerprint
            state["workspace_manifest"] = current_manifest
            if len(state["tool_inflight"]["observations"]) == len(state["tool_inflight"]["actions"]):
                state["counters"]["completed_turns"] += 1
                state["tool_inflight"].update({
                    "status": "observations_recorded",
                    "post_workspace_fingerprint": current_fingerprint,
                    "post_workspace_manifest": current_manifest,
                })
            self._persist(state)

        state["tool_inflight"] = None
        completed = (
            len(turn.tool_calls) == 1
            and turn.tool_calls[0].name == "bash"
            and turn.tool_calls[0].arguments == {"command": self.configuration.completion_command}
            and observations[0].returncode == 0
        )
        if completed:
            state["status"] = "completed"
        self._checkpoint(state, "completed" if completed else "assistant-tools")

    def _stored_tool_completion(self, tool_inflight: Mapping[str, Any]) -> bool:
        actions = tool_inflight.get("actions")
        observations = tool_inflight.get("observations")
        return bool(
            isinstance(actions, list)
            and len(actions) == 1
            and isinstance(actions[0], Mapping)
            and actions[0].get("name") == "bash"
            and actions[0].get("arguments") == {"command": self.configuration.completion_command}
            and isinstance(observations, list)
            and len(observations) == 1
            and isinstance(observations[0], Mapping)
            and observations[0].get("returncode") == 0
        )

    def _begin_model_call(
        self, state: dict[str, Any], kind: str, source_checkpoint_sha256: str,
    ) -> dict[str, Any]:
        if kind not in {"work", "compaction"}:
            raise StateIntegrityError("invalid model call kind")
        pending_trigger = state.get("pending_compaction_trigger")
        if (
            (kind == "work" and pending_trigger is not None)
            or (kind == "compaction" and not isinstance(pending_trigger, Mapping))
        ):
            raise StateIntegrityError("model call kind disagrees with compaction trigger state")
        counter_name = "logical_calls_started" if kind == "work" else "compaction_calls_started"
        state["counters"][counter_name] += 1
        inflight = {
            "kind": kind,
            "logical_index": state["counters"][counter_name],
            "source_checkpoint_sha256": source_checkpoint_sha256,
            "source_compaction_trigger_sha256": (
                fingerprint(pending_trigger) if kind == "compaction" else None
            ),
            "failures": 0,
            "sample_started": False,
            "response": None,
            "started_unix_seconds": time.time(),
        }
        state["model_inflight"] = inflight
        self._append_event(state, "logical_call_started", {
            "logical_kind": kind,
            "logical_index": inflight["logical_index"],
            "source_checkpoint_sha256": source_checkpoint_sha256,
            "started_unix_seconds": inflight["started_unix_seconds"],
        })
        return inflight

    def _assert_completed_response_retry_boundary(
        self, state: Mapping[str, Any], inflight: Mapping[str, Any],
    ) -> None:
        """Prove that a rejected response cannot have crossed a tool boundary."""

        logical_kind = inflight.get("kind")
        pending_trigger = state.get("pending_compaction_trigger")
        if (
            logical_kind not in {"work", "compaction"}
            or state.get("model_inflight") != inflight
            or inflight.get("response") is not None
            or inflight.get("sample_started") is not True
            or state.get("tool_inflight") is not None
        ):
            raise StateIntegrityError(
                "completed-response retry is outside the preregistered pre-tool boundary"
            )
        if logical_kind == "work":
            if (
                pending_trigger is not None
                or inflight.get("source_compaction_trigger_sha256") is not None
            ):
                raise StateIntegrityError(
                    "work-response retry unexpectedly has a compaction trigger"
                )
        elif (
            not isinstance(pending_trigger, Mapping)
            or inflight.get("source_compaction_trigger_sha256")
            != fingerprint(pending_trigger)
        ):
            raise StateIntegrityError(
                "compaction-response retry changed its durable trigger"
            )
        checkpoints = state.get("checkpoints")
        if not isinstance(checkpoints, list) or not checkpoints:
            raise StateIntegrityError("completed-response retry lacks a source checkpoint")
        checkpoint = checkpoints[-1]
        if (
            checkpoint.get("checkpoint_sha256")
            != inflight.get("source_checkpoint_sha256")
            or checkpoint.get("complete_turn_boundary") is not True
            or checkpoint.get("tool_calls_closed") is not True
            or checkpoint.get("history_sha256") != state.get("history_sha256")
            or checkpoint.get("history_message_count") != len(state["transcript"])
            or checkpoint.get("active_context_sha256")
            != state.get("active_context_sha256")
            or checkpoint.get("active_message_count") != len(state["active_messages"])
            or checkpoint.get("workspace_fingerprint")
            != state.get("expected_workspace_fingerprint")
        ):
            raise StateIntegrityError(
                "completed-response retry did not remain at its exact closed checkpoint"
            )
        checkpoint_counters = checkpoint.get("counters")
        counters = state.get("counters")
        if not isinstance(checkpoint_counters, Mapping) or not isinstance(counters, Mapping):
            raise StateIntegrityError("completed-response retry counters are malformed")
        for name in (
            "logical_calls_completed",
            "compaction_calls_completed",
            "tool_calls_requested",
            "tool_calls_completed",
            "completed_turns",
            "compactions_completed",
        ):
            if counters.get(name) != checkpoint_counters.get(name):
                raise StateIntegrityError(
                    "completed-response retry crossed a model or tool completion boundary"
                )
        self._assert_closed_transcript(state["transcript"])
        self._assert_closed_transcript(state["active_messages"])
        self._verify_inputs()
        self._verify_workspace(state)

    def _retain_rejected_model_response(
        self,
        state: dict[str, Any],
        inflight: Mapping[str, Any],
        attempt_index: int,
        error: RetryableCompletedResponseError,
    ) -> dict[str, Any]:
        self._assert_completed_response_retry_boundary(state, inflight)
        records = state.get("private_rejected_model_responses")
        if not isinstance(records, list):
            raise StateIntegrityError("private rejected-response state is malformed")
        response = strict_json_loads(
            canonical_json(error.rejected_response).decode("utf-8")
        )
        diagnostics = _completed_response_rejection_diagnostics(
            response, error.private_protocol_error, inflight["kind"]
        )
        if (
            error.rejection_logical_kind != inflight["kind"]
            or error.kind != _completed_response_retry_class(inflight["kind"])
            or diagnostics != error.safe_diagnostics
        ):
            raise StateIntegrityError("rejected-response diagnostics changed before retention")
        sequence = len(records)
        body: dict[str, Any] = {
            "rejection_id": f"rejection-{sequence:06d}",
            "sequence": sequence,
            "previous_rejection_sha256": (
                records[-1]["rejection_sha256"] if sequence else None
            ),
            "logical_kind": inflight["kind"],
            "logical_index": inflight["logical_index"],
            "attempt_index": attempt_index,
            "failure_count": inflight["failures"],
            "retry_class": error.kind,
            "source_checkpoint_sha256": inflight["source_checkpoint_sha256"],
            "source_compaction_trigger_sha256": inflight[
                "source_compaction_trigger_sha256"
            ],
            "recorded_unix_seconds": time.time(),
            "response": response,
            "response_sha256": diagnostics["response_sha256"],
            "response_length_bytes": diagnostics["response_length_bytes"],
            "private_protocol_error": error.private_protocol_error,
            "protocol_diagnostics": diagnostics,
        }
        body["rejection_sha256"] = fingerprint(body)
        records.append(body)
        return body

    def _attempt_model_call(
        self,
        state: dict[str, Any],
        inflight: dict[str, Any],
        invoke: Callable[[Callable[[], None]], Any],
        serialize: Callable[[Any], dict[str, Any]],
    ) -> Any:
        while True:
            self._verify_inputs()
            self._verify_workspace(state)
            state["counters"]["model_attempts_started"] += 1
            inflight["sample_started"] = False
            attempt_index = inflight.get("attempt_index", 0) + 1
            inflight["attempt_index"] = attempt_index
            attempt_started_unix = time.time()
            attempt_started_monotonic = time.monotonic()
            self._append_event(state, "model_attempt_started", {
                "logical_kind": inflight["kind"],
                "logical_index": inflight["logical_index"],
                "attempt_index": attempt_index,
                "started_unix_seconds": attempt_started_unix,
            })
            def mark_sample_started() -> None:
                if inflight["sample_started"] is not True:
                    inflight["sample_started"] = True
                    self._append_event(state, "model_sampling_started", {
                        "logical_kind": inflight["kind"],
                        "logical_index": inflight["logical_index"],
                        "attempt_index": attempt_index,
                        "started_unix_seconds": time.time(),
                    })
            try:
                result = invoke(mark_sample_started)
            except ContextWindowOverflow as error:
                state["counters"]["model_attempts_completed"] += 1
                self._append_attempt_finished_event(
                    state, inflight, attempt_index, attempt_started_unix,
                    attempt_started_monotonic, "hard_context_overflow", error.kind,
                )
                raise
            except IndeterminateModelResponseError as error:
                state["counters"]["model_attempts_completed"] += 1
                self._append_attempt_finished_event(
                    state, inflight, attempt_index, attempt_started_unix,
                    attempt_started_monotonic, "indeterminate", error.kind,
                )
                raise
            except RetryableCompletedResponseError as error:
                state["counters"]["model_attempts_completed"] += 1
                inflight["failures"] += 1
                rejection = self._retain_rejected_model_response(
                    state, inflight, attempt_index, error
                )
                if inflight["failures"] > self.configuration.limits.max_model_retries:
                    self._append_attempt_finished_event(
                        state, inflight, attempt_index, attempt_started_unix,
                        attempt_started_monotonic,
                        "completed_response_retry_exhausted", error.kind,
                        protocol_diagnostics=error.safe_diagnostics,
                        rejection_id=rejection["rejection_id"],
                        rejection_sha256=rejection["rejection_sha256"],
                    )
                    raise ModelProtocolError(
                        "fully received " + str(inflight.get("kind"))
                        + " response exhausted pre-tool retry policy",
                        safe_diagnostics=error.safe_diagnostics,
                    ) from error
                state["counters"]["retries"] += 1
                self._append_attempt_finished_event(
                    state, inflight, attempt_index, attempt_started_unix,
                    attempt_started_monotonic,
                    "completed_response_retry_scheduled", error.kind,
                    retry_backoff_seconds=self.configuration.limits.retry_backoff_seconds,
                    protocol_diagnostics=error.safe_diagnostics,
                    rejection_id=rejection["rejection_id"],
                    rejection_sha256=rejection["rejection_sha256"],
                )
                self.configuration.sleep(self.configuration.limits.retry_backoff_seconds)
                continue
            except RetryableModelError as error:
                state["counters"]["model_attempts_completed"] += 1
                if inflight["sample_started"] is True:
                    self._append_attempt_finished_event(
                        state, inflight, attempt_index, attempt_started_unix,
                        attempt_started_monotonic, "indeterminate", error.kind,
                    )
                    raise IndeterminateModelResponseError(
                        "adapter reported a retryable error after model sampling began"
                    )
                inflight["failures"] += 1
                if inflight["failures"] > self.configuration.limits.max_model_retries:
                    self._append_attempt_finished_event(
                        state, inflight, attempt_index, attempt_started_unix,
                        attempt_started_monotonic, "retry_exhausted", error.kind,
                    )
                    raise
                state["counters"]["retries"] += 1
                self._append_attempt_finished_event(
                    state, inflight, attempt_index, attempt_started_unix,
                    attempt_started_monotonic, "retry_scheduled", error.kind,
                    retry_backoff_seconds=self.configuration.limits.retry_backoff_seconds,
                )
                self.configuration.sleep(self.configuration.limits.retry_backoff_seconds)
                continue
            except ModelProtocolError as error:
                state["counters"]["model_attempts_completed"] += 1
                self._append_attempt_finished_event(
                    state, inflight, attempt_index, attempt_started_unix,
                    attempt_started_monotonic, "protocol_failure", error.kind,
                    protocol_diagnostics=error.safe_diagnostics,
                )
                raise
            state["counters"]["model_attempts_completed"] += 1
            serialized = serialize(result)
            inflight["response"] = serialized
            provider_metadata = serialized.get("provider_metadata", {})
            self._append_attempt_finished_event(
                state, inflight, attempt_index, attempt_started_unix,
                attempt_started_monotonic, "completed", None,
                usage=provider_metadata.get("usage") if isinstance(provider_metadata, Mapping) else None,
                response_model=provider_metadata.get("model") if isinstance(provider_metadata, Mapping) else None,
                response_system_fingerprint=(
                    provider_metadata.get("system_fingerprint")
                    if isinstance(provider_metadata, Mapping) else None
                ),
            )
            return result

    def _preflight_turn(self, state: Mapping[str, Any], turn: AssistantTurn) -> None:
        if len(turn.tool_calls) > self.configuration.limits.max_tool_calls_per_turn:
            raise ModelProtocolError("assistant requested too many tool calls in one turn")
        existing_ids = self._existing_tool_ids(state["transcript"])
        batch_ids: set[str] = set()
        for action in turn.tool_calls:
            if not action.call_id or action.call_id in existing_ids or action.call_id in batch_ids:
                raise ModelProtocolError("tool call ids must be non-empty and globally unique")
            batch_ids.add(action.call_id)
            if action.name != "bash":
                raise ModelProtocolError("only the bash tool is preregistered")
            if set(action.arguments) != {"command"} or not isinstance(action.arguments.get("command"), str):
                raise ModelProtocolError("bash arguments must be exactly one string field named command")

    @staticmethod
    def _existing_tool_ids(messages: Sequence[Mapping[str, Any]]) -> set[str]:
        result: set[str] = set()
        for message in messages:
            if message.get("role") != "assistant":
                continue
            for call in message.get("tool_calls", []):
                call_id = call.get("call_id")
                if isinstance(call_id, str):
                    result.add(call_id)
        return result

    def _safe_transcript_tail(self, state: Mapping[str, Any]) -> list[dict[str, Any]]:
        limit = self.configuration.limits.safe_tail_messages
        if limit == 0:
            return []
        conversation = state["transcript"][len(state["base_messages"]):]
        groups: list[list[dict[str, Any]]] = []
        current: list[dict[str, Any]] = []
        for message in conversation:
            role = message.get("role")
            if role == "assistant":
                if current:
                    groups.append(current)
                current = [message]
            elif role == "tool" and current:
                current.append(message)
            else:
                raise StateIntegrityError("transcript tail is not a sequence of completed turns")
        if current:
            groups.append(current)
        selected: list[list[dict[str, Any]]] = []
        count = 0
        for group in reversed(groups):
            if selected and count + len(group) > limit:
                break
            selected.append(group)
            count += len(group)
            if count >= limit:
                break
        result: list[dict[str, Any]] = []
        for group in reversed(selected):
            result.extend(group)
        self._assert_closed_transcript(result)
        return result

    def _append_event(
        self,
        state: dict[str, Any],
        kind: str,
        data: Mapping[str, Any],
        *,
        persist: bool = True,
    ) -> dict[str, Any]:
        sequence = len(state["events"])
        body: dict[str, Any] = {
            "event_id": f"event-{sequence:06d}",
            "sequence": sequence,
            "previous_event_sha256": (
                state["events"][-1]["event_sha256"] if sequence else None
            ),
            "kind": kind,
            "data": dict(data),
        }
        body["event_sha256"] = fingerprint(body)
        state["events"].append(body)
        if persist:
            self._persist(state)
        return body

    def _append_attempt_finished_event(
        self,
        state: dict[str, Any],
        inflight: Mapping[str, Any],
        attempt_index: int,
        started_unix_seconds: float,
        started_monotonic: float,
        outcome: str,
        retry_class: str | None,
        *,
        retry_backoff_seconds: float | None = None,
        usage: Any = None,
        response_model: Any = None,
        response_system_fingerprint: Any = None,
        protocol_diagnostics: Mapping[str, Any] | None = None,
        rejection_id: str | None = None,
        rejection_sha256: str | None = None,
    ) -> None:
        ended = time.time()
        data = {
            "logical_kind": inflight["kind"],
            "logical_index": inflight["logical_index"],
            "attempt_index": attempt_index,
            "started_unix_seconds": started_unix_seconds,
            "ended_unix_seconds": ended,
            "elapsed_seconds": max(0.0, time.monotonic() - started_monotonic),
            "outcome": outcome,
            "retry_class": retry_class,
            "retry_backoff_seconds": retry_backoff_seconds,
            "usage": usage,
            "response_model": response_model,
            "response_system_fingerprint": response_system_fingerprint,
        }
        if protocol_diagnostics is not None:
            data["protocol_diagnostics"] = dict(protocol_diagnostics)
        if rejection_id is not None or rejection_sha256 is not None:
            data["rejection_id"] = rejection_id
            data["rejection_sha256"] = rejection_sha256
        self._append_event(state, "model_attempt_finished", data)

    def _append_logical_finished_event(
        self,
        state: dict[str, Any],
        inflight: Mapping[str, Any],
        *,
        usage: Any,
        extra: Mapping[str, Any],
        persist: bool,
    ) -> None:
        ended = time.time()
        data = {
            "logical_kind": inflight["kind"],
            "logical_index": inflight["logical_index"],
            "started_unix_seconds": inflight["started_unix_seconds"],
            "ended_unix_seconds": ended,
            "elapsed_seconds": max(0.0, ended - inflight["started_unix_seconds"]),
            "outcome": "completed",
            "usage": usage,
            **dict(extra),
        }
        self._append_event(state, "logical_call_finished", data, persist=persist)

    def _checkpoint(self, state: dict[str, Any], kind: str) -> None:
        if state.get("tool_inflight") is not None:
            raise StateIntegrityError("cannot checkpoint a tool action in flight")
        if state.get("model_inflight") is not None:
            raise StateIntegrityError("cannot checkpoint a model call in flight")
        self._assert_closed_transcript(state["transcript"])
        self._assert_closed_transcript(state["active_messages"])
        self._refresh_hashes(state)
        sequence = len(state["checkpoints"])
        previous = state["checkpoints"][-1]["checkpoint_sha256"] if sequence else None
        body: dict[str, Any] = {
            "checkpoint_id": f"checkpoint-{sequence:06d}",
            "sequence": sequence,
            "previous_checkpoint_sha256": previous,
            "kind": kind,
            "policy_sha256": self.policy_sha256,
            "history_sha256": state["history_sha256"],
            "history_message_count": len(state["transcript"]),
            "active_context_sha256": state["active_context_sha256"],
            "active_message_count": len(state["active_messages"]),
            "workspace_fingerprint": state["expected_workspace_fingerprint"],
            "workspace_manifest": state["workspace_manifest"],
            "counters": dict(state["counters"]),
            "complete_turn_boundary": True,
            "tool_calls_closed": True,
        }
        body["checkpoint_sha256"] = fingerprint(body)
        state["checkpoints"].append(body)
        event_data: dict[str, Any] = {
            "checkpoint_id": body["checkpoint_id"],
            "checkpoint_sha256": body["checkpoint_sha256"],
            "checkpoint_kind": kind,
        }
        if kind == "compaction" and state["continuations"]:
            event_data["compaction_packet_sha256"] = state["continuations"][-1][
                "continuation"
            ]["packet_sha256"]
        self._append_event(state, "checkpoint_written", event_data, persist=False)
        self._persist(state)

    def _persist(self, state: dict[str, Any]) -> None:
        self._refresh_hashes(state)
        atomic_write_json(self.state_path, state)

    @staticmethod
    def _refresh_hashes(state: dict[str, Any]) -> None:
        state["history_sha256"] = fingerprint(state["transcript"])
        state["active_context_sha256"] = fingerprint(state["active_messages"])

    def _record_failure(self, state: dict[str, Any], error: ProbeError) -> None:
        if state.get("status") == "completed":
            return
        state["status"] = "failed"
        state["failure"] = {"kind": error.kind, "message": str(error)}
        safe_diagnostics = getattr(error, "safe_diagnostics", None)
        if safe_diagnostics is not None:
            state["failure"]["diagnostics"] = dict(safe_diagnostics)
        self._persist(state)

    def _result(self, state: Mapping[str, Any]) -> dict[str, Any]:
        return {
            "run_id": state["run_id"],
            "status": state["status"],
            "failure": state.get("failure"),
            "policy_sha256": self.policy_sha256,
            "last_checkpoint_sha256": state["checkpoints"][-1]["checkpoint_sha256"],
            "counters": dict(state["counters"]),
            "events_count": len(state["events"]),
            "state_path": str(self.state_path),
            "continuation_manifest_path": (
                str(self._continuation_path(state["continuations"][-1]))
                if state["continuations"] else None
            ),
        }

    def _continuation_path(self, manifest: Mapping[str, Any]) -> Path:
        continuation = manifest.get("continuation")
        if not isinstance(continuation, Mapping) or not isinstance(continuation.get("continuation_id"), str):
            raise StateIntegrityError("continuation artifact lacks an id")
        return self.continuations_dir / (continuation["continuation_id"] + ".json")

    def _write_continuation_artifact(self, manifest: Mapping[str, Any]) -> None:
        path = self._continuation_path(manifest)
        if path.exists():
            if read_json(path) != manifest:
                raise StateIntegrityError("numbered continuation artifact conflicts with durable state")
            return
        atomic_write_json(path, manifest)

    def _ensure_continuation_artifacts(self, state: Mapping[str, Any]) -> None:
        for manifest in state["continuations"]:
            self._write_continuation_artifact(manifest)

    def _validate_state(self, state: Any) -> None:
        if not isinstance(state, dict) or state.get("schema_version") != STATE_SCHEMA_VERSION:
            raise StateIntegrityError("state schema version is missing or unsupported")
        if state.get("policy_sha256") != self.policy_sha256:
            raise PolicyDriftError("state policy differs from current preregistration")
        if not isinstance(state.get("run_id"), str) or not state["run_id"]:
            raise StateIntegrityError("state run id is missing")
        if state.get("status") not in {"running", "completed", "failed"}:
            raise StateIntegrityError("state status is invalid")
        for name in (
            "base_messages", "transcript", "active_messages", "events",
            "checkpoints", "continuations", "private_rejected_model_responses",
        ):
            if not isinstance(state.get(name), list):
                raise StateIntegrityError(name + " must be an array")
        if state["transcript"][:len(state["base_messages"])] != state["base_messages"]:
            raise StateIntegrityError("full transcript does not retain the immutable base messages")
        if state["active_messages"][:len(state["base_messages"])] != state["base_messages"]:
            raise StateIntegrityError("active context does not retain the immutable base messages")
        if state["base_messages"] != self._base_messages():
            raise PolicyDriftError("stored immutable prompt context differs from current inputs")
        if fingerprint(state["transcript"]) != state.get("history_sha256"):
            raise StateIntegrityError("history hash does not match transcript")
        if fingerprint(state["active_messages"]) != state.get("active_context_sha256"):
            raise StateIntegrityError("active-context hash does not match messages")
        self._assert_closed_transcript(
            state["transcript"], allow_dangling=state.get("tool_inflight") is not None
        )
        self._validate_counters(state)
        self._validate_rejected_model_responses(state)
        self._validate_events(state)
        self._validate_checkpoints(state)
        _validate_sha256(state.get("initial_manifest_sha256"), "initial_manifest_sha256")
        self._validate_workspace_fingerprint(state.get("expected_workspace_fingerprint"))
        if not isinstance(state.get("workspace_manifest"), Mapping):
            raise StateIntegrityError("workspace manifest is missing")
        if fingerprint({
            "categories": state["workspace_manifest"].get("categories"),
            "category_sha256": state["workspace_manifest"].get("category_sha256"),
            "git_semantic": state["workspace_manifest"].get("git_semantic"),
        }) != state["workspace_manifest"].get("manifest_sha256"):
            raise StateIntegrityError("workspace manifest hash does not match its contents")
        if not isinstance(state.get("forced_compactions_consumed"), list):
            raise StateIntegrityError("forced compaction record must be an array")
        previous_continuation: str | None = None
        checkpoints_by_id = {
            checkpoint["checkpoint_id"]: checkpoint for checkpoint in state["checkpoints"]
        }
        for continuation_index, manifest in enumerate(state["continuations"], start=1):
            if not isinstance(manifest, dict):
                raise StateIntegrityError("continuation manifest must be an object")
            declared_manifest_sha256 = manifest.get("manifest_sha256")
            body = dict(manifest)
            body.pop("manifest_sha256", None)
            if declared_manifest_sha256 != fingerprint(body):
                raise StateIntegrityError("continuation manifest hash mismatch")
            if manifest.get("previous_continuation_manifest_sha256") != previous_continuation:
                raise StateIntegrityError("continuation manifest hash chain is broken")
            if manifest.get("run_policy_sha256") != self.policy_sha256 or manifest.get("run_policy") != self.policy:
                raise PolicyDriftError("continuation manifest policy drifted")
            continuation = manifest.get("continuation")
            packet = manifest.get("packet")
            if not isinstance(continuation, Mapping) or not isinstance(packet, Mapping):
                raise StateIntegrityError("continuation manifest lacks its packet")
            if continuation.get("continuation_id") != f"continuation-{continuation_index:06d}":
                raise StateIntegrityError("continuation id sequence is invalid")
            packet_sha256 = continuation.get("packet_sha256")
            if packet_sha256 != fingerprint(packet):
                raise StateIntegrityError("continuation packet hash mismatch")
            expected_continuity = dict(continuation)
            expected_continuity.pop("packet_sha256", None)
            if packet.get("continuity") != expected_continuity:
                raise StateIntegrityError("packet continuity does not match its manifest")
            source_checkpoint = checkpoints_by_id.get(continuation.get("source_checkpoint_id"))
            if source_checkpoint is None:
                raise StateIntegrityError("continuation source checkpoint is missing")
            if continuation.get("source_checkpoint_sha256") != source_checkpoint["checkpoint_sha256"]:
                raise StateIntegrityError("continuation source checkpoint hash mismatch")
            if continuation.get("source_history_sha256") != source_checkpoint["history_sha256"]:
                raise StateIntegrityError("continuation source history hash mismatch")
            if continuation.get("source_workspace_fingerprint") != source_checkpoint["workspace_fingerprint"]:
                raise StateIntegrityError("continuation source workspace mismatch")
            preserved = packet.get("preserved")
            if not isinstance(preserved, Mapping):
                raise StateIntegrityError("continuation packet preserved state is missing")
            summary_value = {
                name: preserved.get(name)
                for name in ("summary", "evidence", "unresolved_judgments", "latest_model_plan")
            }
            if continuation.get("summary_sha256") != fingerprint(summary_value):
                raise StateIntegrityError("continuation summary hash mismatch")
            previous_continuation = declared_manifest_sha256

    @staticmethod
    def _validate_rejected_model_responses(state: Mapping[str, Any]) -> None:
        previous: str | None = None
        checkpoint_hashes = {
            checkpoint.get("checkpoint_sha256")
            for checkpoint in state["checkpoints"]
            if isinstance(checkpoint, Mapping)
        }
        for sequence, rejection in enumerate(
            state["private_rejected_model_responses"]
        ):
            if not isinstance(rejection, dict):
                raise StateIntegrityError("private rejected response must be an object")
            declared = rejection.get("rejection_sha256")
            body = dict(rejection)
            body.pop("rejection_sha256", None)
            if declared != fingerprint(body):
                raise StateIntegrityError("private rejected-response hash mismatch")
            if (
                rejection.get("sequence") != sequence
                or rejection.get("rejection_id") != f"rejection-{sequence:06d}"
                or rejection.get("previous_rejection_sha256") != previous
            ):
                raise StateIntegrityError(
                    "private rejected-response hash chain is broken"
                )
            logical_kind = rejection.get("logical_kind")
            if logical_kind not in {"work", "compaction"}:
                raise StateIntegrityError(
                    "private rejection has an invalid logical kind"
                )
            if rejection.get("retry_class") != _completed_response_retry_class(
                logical_kind
            ):
                raise StateIntegrityError("private rejection has the wrong retry class")
            trigger_sha256 = rejection.get("source_compaction_trigger_sha256")
            if logical_kind == "work":
                if trigger_sha256 is not None:
                    raise StateIntegrityError(
                        "private work rejection has a compaction trigger hash"
                    )
            else:
                _validate_sha256(
                    trigger_sha256,
                    "private rejection source_compaction_trigger_sha256",
                )
            for name in ("logical_index", "attempt_index", "failure_count"):
                value = _nonnegative_int(
                    rejection.get(name), "private rejection " + name
                )
                if value < 1:
                    raise StateIntegrityError(
                        "private rejection " + name + " must be positive"
                    )
            source_checkpoint_sha256 = _validate_sha256(
                rejection.get("source_checkpoint_sha256"),
                "private rejection source_checkpoint_sha256",
            )
            if source_checkpoint_sha256 not in checkpoint_hashes:
                raise StateIntegrityError(
                    "private rejection source checkpoint is missing"
                )
            recorded = rejection.get("recorded_unix_seconds")
            if (
                isinstance(recorded, bool)
                or not isinstance(recorded, (int, float))
                or not math.isfinite(recorded)
            ):
                raise StateIntegrityError(
                    "private rejection timestamp is malformed"
                )
            response = rejection.get("response")
            private_error = rejection.get("private_protocol_error")
            diagnostics = rejection.get("protocol_diagnostics")
            if (
                not isinstance(response, Mapping)
                or not isinstance(private_error, str)
                or not isinstance(diagnostics, Mapping)
            ):
                raise StateIntegrityError(
                    "private rejected-response payload is malformed"
                )
            if (
                logical_kind == "compaction"
                and private_error
                != "context-maintenance response attempted a tool call"
            ):
                raise StateIntegrityError(
                    "private compaction rejection is not the exact tool-call rejection"
                )
            if logical_kind == "compaction":
                try:
                    parsed_turn = OpenAICompatibleAdapter._assistant_turn(response)
                except ModelProtocolError as error:
                    raise StateIntegrityError(
                        "private compaction rejection is not successfully parsed"
                    ) from error
                if not parsed_turn.tool_calls:
                    raise StateIntegrityError(
                        "private compaction rejection does not contain a parsed tool call"
                    )
            payload = canonical_json(dict(response))
            if (
                rejection.get("response_sha256") != sha256_bytes(payload)
                or rejection.get("response_length_bytes") != len(payload)
                or diagnostics
                != _completed_response_rejection_diagnostics(
                    response, private_error, logical_kind
                )
            ):
                raise StateIntegrityError(
                    "private rejected-response diagnostics do not match its payload"
                )
            previous = declared

    def _validate_events(self, state: Mapping[str, Any]) -> None:
        previous: str | None = None
        rejections_by_id = {
            rejection["rejection_id"]: rejection
            for rejection in state["private_rejected_model_responses"]
        }
        referenced_rejections: set[str] = set()
        for sequence, event in enumerate(state["events"]):
            if not isinstance(event, dict):
                raise StateIntegrityError("runner event must be an object")
            declared = event.get("event_sha256")
            body = dict(event)
            body.pop("event_sha256", None)
            if declared != fingerprint(body):
                raise StateIntegrityError("runner event hash mismatch")
            if event.get("sequence") != sequence or event.get("event_id") != f"event-{sequence:06d}":
                raise StateIntegrityError("runner event sequence is invalid")
            if event.get("previous_event_sha256") != previous:
                raise StateIntegrityError("runner event hash chain is broken")
            if not isinstance(event.get("kind"), str) or not isinstance(event.get("data"), Mapping):
                raise StateIntegrityError("runner event kind or data is malformed")
            if event.get("kind") == "model_attempt_finished":
                data = event["data"]
                outcome = data.get("outcome")
                completed_response_outcomes = {
                    "completed_response_retry_scheduled",
                    "completed_response_retry_exhausted",
                }
                if outcome in completed_response_outcomes:
                    rejection_id = data.get("rejection_id")
                    rejection = rejections_by_id.get(rejection_id)
                    if (
                        rejection is None
                        or rejection_id in referenced_rejections
                        or data.get("rejection_sha256")
                        != rejection.get("rejection_sha256")
                        or data.get("logical_kind")
                        != rejection.get("logical_kind")
                        or data.get("logical_index")
                        != rejection.get("logical_index")
                        or data.get("attempt_index")
                        != rejection.get("attempt_index")
                        or data.get("retry_class")
                        != _completed_response_retry_class(
                            rejection.get("logical_kind")
                        )
                        or rejection.get("retry_class") != data.get("retry_class")
                        or data.get("protocol_diagnostics")
                        != rejection.get("protocol_diagnostics")
                        or "response" in data
                        or "private_protocol_error" in data
                    ):
                        raise StateIntegrityError(
                            "completed-response event does not match its private rejection"
                        )
                    failure_count = rejection.get("failure_count")
                    retry_backoff = data.get("retry_backoff_seconds")
                    if (
                        outcome == "completed_response_retry_scheduled"
                        and (
                            failure_count > self.configuration.limits.max_model_retries
                            or isinstance(retry_backoff, bool)
                            or not isinstance(retry_backoff, (int, float))
                            or not math.isfinite(retry_backoff)
                            or retry_backoff
                            != self.configuration.limits.retry_backoff_seconds
                        )
                    ):
                        raise StateIntegrityError(
                            "completed-response retry event violates its budget or backoff"
                        )
                    if (
                        outcome == "completed_response_retry_exhausted"
                        and (
                            failure_count
                            != self.configuration.limits.max_model_retries + 1
                            or retry_backoff is not None
                        )
                    ):
                        raise StateIntegrityError(
                            "completed-response exhaustion unexpectedly schedules a retry"
                        )
                    referenced_rejections.add(rejection_id)
                elif data.get("rejection_id") is not None or data.get(
                    "rejection_sha256"
                ) is not None:
                    raise StateIntegrityError(
                        "non-rejection model event references a private rejection"
                    )
            previous = declared
        if referenced_rejections != set(rejections_by_id):
            raise StateIntegrityError(
                "private rejected response lacks one terminal model event"
            )
        inflight = state.get("model_inflight")
        if inflight is not None:
            if not isinstance(inflight, dict) or inflight.get("kind") not in {"work", "compaction"}:
                raise StateIntegrityError("model_inflight is malformed")
            _nonnegative_int(inflight.get("failures"), "model_inflight.failures")
            if not isinstance(inflight.get("sample_started"), bool):
                raise StateIntegrityError("model_inflight.sample_started must be a boolean")
            trigger_sha256 = inflight.get("source_compaction_trigger_sha256")
            if inflight["kind"] == "work":
                if trigger_sha256 is not None:
                    raise StateIntegrityError(
                        "work model_inflight has a compaction trigger hash"
                    )
            else:
                _validate_sha256(
                    trigger_sha256,
                    "model_inflight.source_compaction_trigger_sha256",
                )
            if inflight.get("response") is not None:
                if inflight["kind"] == "work":
                    _turn_from_json(inflight["response"])
                else:
                    _summary_from_json(inflight["response"])

    def _validate_counters(self, state: Mapping[str, Any]) -> None:
        counters = state.get("counters")
        if not isinstance(counters, dict) or set(counters) != set(_initial_counters()):
            raise StateIntegrityError("counter set is invalid")
        for name, value in counters.items():
            _nonnegative_int(value, "counters." + name)
        if counters["logical_calls_completed"] > counters["logical_calls_started"]:
            raise StateIntegrityError("completed logical calls exceed starts")
        if counters["compaction_calls_completed"] > counters["compaction_calls_started"]:
            raise StateIntegrityError("completed maintenance calls exceed starts")
        if counters["model_attempts_completed"] + counters["model_attempts_abandoned"] > counters["model_attempts_started"]:
            raise StateIntegrityError("finished model attempts exceed starts")
        if counters["tool_calls_completed"] > counters["tool_calls_requested"]:
            raise StateIntegrityError("completed tool calls exceed requests")
        assistant_count = sum(1 for message in state["transcript"] if message.get("role") == "assistant")
        tool_count = sum(1 for message in state["transcript"] if message.get("role") == "tool")
        requested_count = sum(
            len(message.get("tool_calls", []))
            for message in state["transcript"] if message.get("role") == "assistant"
        )
        if counters["logical_calls_completed"] != assistant_count:
            raise StateIntegrityError("logical call counter does not match transcript")
        if counters["tool_calls_requested"] != requested_count:
            raise StateIntegrityError("requested tool counter does not match transcript")
        if counters["tool_calls_completed"] != tool_count:
            raise StateIntegrityError("completed tool counter does not match transcript")
        closed_turns = assistant_count
        if state.get("tool_inflight") is not None and requested_count > tool_count:
            closed_turns -= 1
        if counters["completed_turns"] != closed_turns:
            raise StateIntegrityError("completed-turn counter does not match transcript closure")
        if counters["compactions_completed"] != len(state["continuations"]):
            raise StateIntegrityError("compaction counter does not match continuation records")

    def _validate_checkpoints(self, state: Mapping[str, Any]) -> None:
        previous: str | None = None
        last_counters = _initial_counters()
        for sequence, checkpoint in enumerate(state["checkpoints"]):
            if not isinstance(checkpoint, dict):
                raise StateIntegrityError("checkpoint must be an object")
            declared = checkpoint.get("checkpoint_sha256")
            body = dict(checkpoint)
            body.pop("checkpoint_sha256", None)
            if declared != fingerprint(body):
                raise StateIntegrityError("checkpoint hash mismatch")
            if checkpoint.get("sequence") != sequence or checkpoint.get("checkpoint_id") != f"checkpoint-{sequence:06d}":
                raise StateIntegrityError("checkpoint sequence is invalid")
            if checkpoint.get("previous_checkpoint_sha256") != previous:
                raise StateIntegrityError("checkpoint hash chain is broken")
            if checkpoint.get("policy_sha256") != self.policy_sha256:
                raise PolicyDriftError("checkpoint policy differs from preregistration")
            if checkpoint.get("complete_turn_boundary") is not True or checkpoint.get("tool_calls_closed") is not True:
                raise StateIntegrityError("checkpoint does not declare a closed boundary")
            history_count = _nonnegative_int(checkpoint.get("history_message_count"), "checkpoint history count")
            active_count = _nonnegative_int(checkpoint.get("active_message_count"), "checkpoint active count")
            if history_count > len(state["transcript"]):
                raise StateIntegrityError("checkpoint history count exceeds current state")
            if fingerprint(state["transcript"][:history_count]) != checkpoint.get("history_sha256"):
                raise StateIntegrityError("checkpoint history prefix changed")
            # Context maintenance deliberately discards older active contexts. Their counts and
            # hashes remain committed by the checkpoint chain, but only the latest active context
            # is still present and can be compared with the current state bytes.
            if sequence == len(state["checkpoints"]) - 1:
                if active_count > len(state["active_messages"]):
                    raise StateIntegrityError("latest checkpoint active count exceeds current state")
                if fingerprint(state["active_messages"][:active_count]) != checkpoint.get("active_context_sha256"):
                    raise StateIntegrityError("checkpoint active context changed")
            checkpoint_counters = checkpoint.get("counters")
            if not isinstance(checkpoint_counters, dict) or set(checkpoint_counters) != set(last_counters):
                raise StateIntegrityError("checkpoint counters are malformed")
            for name, value in checkpoint_counters.items():
                _nonnegative_int(value, "checkpoint counters." + name)
                if value < last_counters[name] or value > state["counters"][name]:
                    raise StateIntegrityError("checkpoint counters are non-monotone")
            if checkpoint_counters["tool_calls_requested"] != checkpoint_counters["tool_calls_completed"]:
                raise StateIntegrityError("checkpoint contains an unclosed tool request")
            if checkpoint_counters["logical_calls_completed"] != checkpoint_counters["completed_turns"]:
                raise StateIntegrityError("checkpoint contains an incomplete assistant turn")
            self._validate_workspace_fingerprint(checkpoint.get("workspace_fingerprint"))
            manifest = checkpoint.get("workspace_manifest")
            if not isinstance(manifest, Mapping) or manifest.get("manifest_sha256") != checkpoint["workspace_fingerprint"]["worktree_sha256"]:
                raise StateIntegrityError("checkpoint workspace manifest and fingerprint disagree")
            previous = declared
            last_counters = dict(checkpoint_counters)
        if not state["checkpoints"]:
            raise StateIntegrityError("state has no checkpoint")

    @staticmethod
    def _validate_workspace_fingerprint(value: Any) -> None:
        if not isinstance(value, Mapping):
            raise StateIntegrityError("workspace fingerprint must be an object")
        if value.get("algorithm") != WORKSPACE_FINGERPRINT_ALGORITHM:
            raise StateIntegrityError("workspace fingerprint algorithm is invalid")
        head = value.get("head_sha")
        if not isinstance(head, str) or re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", head) is None:
            raise StateIntegrityError("workspace head fingerprint is invalid")
        for name in ("worktree_sha256", "content_delta_sha256"):
            _validate_sha256(value.get(name), "workspace fingerprint " + name)
        _validate_sha256(value.get("git_semantic_sha256"), "workspace fingerprint git_semantic_sha256")
        if not isinstance(value.get("dirty"), bool):
            raise StateIntegrityError("workspace dirty flag must be boolean")

    @staticmethod
    def _assert_closed_transcript(
        messages: Sequence[Mapping[str, Any]], *, allow_dangling: bool = False,
    ) -> None:
        pending: list[str] = []
        seen_ids: set[str] = set()
        for message in messages:
            if not isinstance(message, Mapping):
                raise StateIntegrityError("transcript message must be an object")
            role = message.get("role")
            if pending:
                if role != "tool":
                    raise StateIntegrityError("assistant tool calls are not immediately observed")
                call_id = message.get("tool_call_id")
                if call_id != pending[0]:
                    raise StateIntegrityError("tool observation is orphaned, duplicate, or out of order")
                pending.pop(0)
                continue
            if role == "assistant":
                raw_calls = message.get("tool_calls", [])
                if not isinstance(raw_calls, list):
                    raise StateIntegrityError("assistant tool_calls must be an array")
                for raw_call in raw_calls:
                    if not isinstance(raw_call, Mapping):
                        raise StateIntegrityError("assistant tool call must be an object")
                    call_id = raw_call.get("call_id")
                    if not isinstance(call_id, str) or not call_id or call_id in seen_ids:
                        raise StateIntegrityError("tool call ids must be non-empty and unique")
                    seen_ids.add(call_id)
                    pending.append(call_id)
            elif role == "tool":
                raise StateIntegrityError("orphan tool observation")
            elif role not in {"system", "user"}:
                raise StateIntegrityError("unknown transcript role")
        if pending and not allow_dangling:
            raise StateIntegrityError("transcript ends with dangling tool calls")


def _resolve_config_path(base: Path, value: Any, field_name: str) -> Path:
    if not isinstance(value, str) or not value:
        raise ConfigurationError(field_name + " must be a non-empty path string")
    path = Path(value)
    return path if path.is_absolute() else base / path


def _mapping(value: Any, field_name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ConfigurationError(field_name + " must be an object")
    return value


def _artifact_from_config(base: Path, value: Any, field_name: str, role: str) -> ArtifactInput:
    if isinstance(value, str):
        path = _resolve_config_path(base, value, field_name)
        return ArtifactInput(label=path.name, path=path, role=role)
    item = _mapping(value, field_name)
    path = _resolve_config_path(base, item.get("path"), field_name + ".path")
    label = item.get("label", path.name)
    if not isinstance(label, str) or not label:
        raise ConfigurationError(field_name + ".label must be a non-empty string")
    model_path = item.get("model_path")
    if model_path is not None and (not isinstance(model_path, str) or not model_path):
        raise ConfigurationError(field_name + ".model_path must be a non-empty string")
    return ArtifactInput(label=label, path=path, role=role, model_path=model_path)


def _validate_docker_host_mapping(
    process: DockerExecProcess,
    *,
    host_path: Path,
    model_path: str,
    writable: bool,
    field_name: str,
) -> None:
    """Prove that one policy-bound host path is the path exposed to the model.

    Docker identities intentionally retain only a digest of each inspect ``Source`` path.  Derive
    the source root implied by the model-visible path and compare that digest, so a configuration
    cannot fingerprint one checkout or artifact while the tool container exposes another.
    """

    container_path = _normalized_container_path(model_path, field_name + ".model_path")
    candidates: list[tuple[int, Mapping[str, Any], PurePosixPath]] = []
    for record in process.allowed_mounts:
        destination = _normalized_container_path(
            record["destination"], "tool.allowed_mounts.destination",
        )
        try:
            relative = container_path.relative_to(destination)
        except ValueError:
            continue
        candidates.append((len(destination.parts), record, relative))
    if not candidates:
        raise ConfigurationError(
            field_name + " is not exposed by any preregistered Docker mount"
        )

    _, mount, relative = max(candidates, key=lambda item: item[0])
    if mount["type"] != "bind":
        raise ConfigurationError(field_name + " must be exposed through an exact bind mount")
    if mount["rw"] is not writable:
        access = "writable" if writable else "read-only"
        raise ConfigurationError(field_name + " must be exposed through a " + access + " mount")

    resolved_host_path = host_path.resolve(strict=False)
    if mount["source_kind"] == "regular_file":
        if relative.parts:
            raise ConfigurationError(
                field_name + " cannot be a descendant of a regular-file mount"
            )
        source_root = resolved_host_path
        if not source_root.is_file():
            raise ConfigurationError(field_name + " regular-file bind source does not exist")
    elif mount["source_kind"] == "directory":
        source_root = resolved_host_path
        for _ in relative.parts:
            source_root = source_root.parent
        if not source_root.is_dir():
            raise ConfigurationError(field_name + " directory bind source does not exist")
        if source_root.joinpath(*relative.parts).resolve(strict=False) != resolved_host_path:
            raise ConfigurationError(field_name + " does not map cleanly through its bind mount")
    else:
        raise ConfigurationError(field_name + " has an unsupported bind source kind")

    if mount["source_sha256"] != _host_path_identity_sha256(source_root):
        raise ConfigurationError(
            field_name + " host path does not match the Docker mount source identity"
        )


def _validate_docker_host_bindings(
    process: DockerExecProcess,
    *,
    workspace_root: Path,
    monitored_paths: Sequence[MonitoredPath],
    methodology: Sequence[ArtifactInput],
) -> None:
    _validate_docker_host_mapping(
        process,
        host_path=workspace_root,
        model_path=process.cwd,
        writable=True,
        field_name="workspace.root",
    )

    for index, item in enumerate(monitored_paths):
        field_name = f"workspace.manifest.{item.category}[{index}]"
        if item.model_path is None:
            raise ConfigurationError(field_name + ".model_path is required for docker_exec")
        if item.category in {"source", "test"} and not _path_is_within(
            item.path, workspace_root,
        ):
            raise ConfigurationError(field_name + " must be inside workspace.root")
        _validate_docker_host_mapping(
            process,
            host_path=item.path,
            model_path=item.model_path,
            writable=True,
            field_name=field_name,
        )

    for index, artifact in enumerate(methodology):
        field_name = f"methodology[{index}]"
        if artifact.model_path is None:
            raise ConfigurationError(field_name + ".model_path is required for docker_exec")
        _validate_docker_host_mapping(
            process,
            host_path=artifact.path,
            model_path=artifact.model_path,
            writable=False,
            field_name=field_name,
        )


def runner_from_config(path: Path) -> RepositoryProbeRunner:
    """Build the executable live runner from a preregistered JSON configuration."""

    raw = read_json(path)
    config = _mapping(raw, "configuration")
    base = path.resolve().parent
    workspace_config = _mapping(config.get("workspace"), "workspace")
    root = _resolve_config_path(base, workspace_config.get("root"), "workspace.root")
    manifest_config = _mapping(workspace_config.get("manifest"), "workspace.manifest")
    monitored: list[MonitoredPath] = []
    for category in ("tracker", "source", "test", "evidence"):
        values = manifest_config.get(category, [])
        if not isinstance(values, list):
            raise ConfigurationError("workspace.manifest." + category + " must be an array")
        for index, value in enumerate(values):
            field_name = f"workspace.manifest.{category}[{index}]"
            if isinstance(value, str):
                monitored_path = _resolve_config_path(base, value, field_name)
                label = monitored_path.name
                model_path = None
            else:
                item = _mapping(value, field_name)
                monitored_path = _resolve_config_path(base, item.get("path"), field_name + ".path")
                label = item.get("label", monitored_path.name)
                if not isinstance(label, str) or not label:
                    raise ConfigurationError(field_name + ".label must be a non-empty string")
                model_path = item.get("model_path")
                if model_path is not None and (
                    not isinstance(model_path, str) or not model_path
                ):
                    raise ConfigurationError(
                        field_name + ".model_path must be a non-empty string"
                    )
            monitored.append(MonitoredPath(
                category=category,
                path=monitored_path,
                label=label,
                model_path=model_path,
            ))
    methodology_values = config.get("methodology")
    if not isinstance(methodology_values, list) or not methodology_values:
        raise ConfigurationError("methodology must be a non-empty array in frozen reading order")
    methodology = tuple(
        _artifact_from_config(base, value, f"methodology[{index}]", "methodology")
        for index, value in enumerate(methodology_values)
    )
    task = _artifact_from_config(base, config.get("task"), "task", "task")

    limit_config = _mapping(config.get("limits"), "limits")
    try:
        limits = RunnerLimits(
            context_window_tokens=limit_config["context_window_tokens"],
            proactive_trigger_tokens=limit_config["proactive_trigger_tokens"],
            work_output_tokens=limit_config["work_output_tokens"],
            compaction_output_tokens=limit_config.get("compaction_output_tokens", 4096),
            max_logical_calls=limit_config.get("max_logical_calls", 1000),
            max_model_retries=limit_config.get("max_model_retries", 2),
            retry_backoff_seconds=limit_config.get("retry_backoff_seconds", 1.0),
            max_tool_calls_per_turn=limit_config.get("max_tool_calls_per_turn", 1),
            safe_tail_messages=limit_config.get("safe_tail_messages", 8),
            force_compaction_after_turns=tuple(limit_config.get("force_compaction_after_turns", [])),
        )
    except KeyError as error:
        raise ConfigurationError("missing required limit: " + str(error)) from error

    model_config = _mapping(config.get("model"), "model")
    served_identity = _mapping(model_config.get("served_identity"), "model.served_identity")
    accepted_response_models = served_identity.get("accepted_response_models")
    if not isinstance(accepted_response_models, list):
        raise ConfigurationError("model.served_identity.accepted_response_models must be an array")
    api_key_env = model_config.get("api_key_env")
    api_key_env_name: str | None = None
    if api_key_env is not None:
        if not isinstance(api_key_env, str) or not api_key_env:
            raise ConfigurationError("model.api_key_env must be a non-empty string")
        api_key = os.environ.get(api_key_env)
        if api_key is None:
            raise ConfigurationError("model API key environment variable is not set: " + api_key_env)
        api_key_source = "environment:" + api_key_env
        api_key_env_name = api_key_env
    else:
        api_key = model_config.get("api_key", "EMPTY")
        if not isinstance(api_key, str):
            raise ConfigurationError("model.api_key must be a string")
        api_key_source = "configuration-literal" if api_key != "EMPTY" else "literal-empty"
    model = OpenAICompatibleAdapter(
        endpoint=model_config["endpoint"],
        model=model_config["name"],
        accepted_response_models=tuple(accepted_response_models),
        served_model_revision=served_identity.get("revision"),
        served_model_precision=served_identity.get("precision"),
        served_context_window_tokens=served_identity.get("context_window_tokens"),
        server_fingerprint=served_identity.get("server_fingerprint"),
        transport=model_config.get("transport", "openai_chat_completions"),
        api_key=api_key,
        api_key_source=api_key_source,
        tls_ca_file=model_config.get("tls_ca_file"),
        tls_ca_sha256=model_config.get("tls_ca_sha256"),
        user_agent=model_config.get("user_agent", "bdrv1-repository-probe/2"),
        temperature=model_config.get("temperature", 0.2),
        reasoning_effort=model_config.get("reasoning_effort"),
        stream=model_config.get("stream", True),
        request_timeout_seconds=model_config.get("request_timeout_seconds", 3600.0),
        estimated_bytes_per_token=model_config.get("estimated_bytes_per_token", 2.0),
        token_estimate_fixed_overhead=model_config.get("token_estimate_fixed_overhead", 256),
        json_response_format=model_config.get("json_response_format", True),
        replay_reasoning_field=model_config.get("replay_reasoning_field", "reasoning_content"),
        work_tool_choice=model_config.get("work_tool_choice", "auto"),
        extra_body=_mapping(model_config.get("extra_body", {}), "model.extra_body"),
        context_maintenance_extra_body=_mapping(
            model_config.get("context_maintenance_extra_body", {}),
            "model.context_maintenance_extra_body",
        ),
    )

    tool_config = _mapping(config.get("tool", {}), "tool")
    backend = tool_config.get("backend")
    if backend not in {"docker_exec", "local_shell"}:
        raise ConfigurationError("tool.backend must explicitly be docker_exec or local_shell")
    raw_environment = tool_config.get("environment")
    if raw_environment is not None:
        environment = _mapping(raw_environment, "tool.environment")
        if not all(isinstance(key, str) and isinstance(value, str) for key, value in environment.items()):
            raise ConfigurationError("tool.environment keys and values must be strings")
    else:
        environment = None
    raw_scrub_names = tool_config.get("scrub_environment_names", [])
    if not isinstance(raw_scrub_names, list) or not all(isinstance(name, str) for name in raw_scrub_names):
        raise ConfigurationError("tool.scrub_environment_names must be an array of strings")
    scrub_names = set(raw_scrub_names)
    if api_key_env_name is not None:
        scrub_names.add(api_key_env_name)
    if environment and set(environment).intersection(scrub_names):
        raise ConfigurationError("tool.environment contains the configured model credential")
    if backend == "docker_exec":
        allowed_container_environment_names = tool_config.get(
            "allowed_container_environment_names", []
        )
        if not isinstance(allowed_container_environment_names, list) or not all(
            isinstance(name, str) and name
            for name in allowed_container_environment_names
        ):
            raise ConfigurationError(
                "tool.allowed_container_environment_names must be an array of non-empty strings"
            )
        if set(allowed_container_environment_names).intersection(scrub_names):
            raise ConfigurationError(
                "tool container environment allowlist contains the configured model credential"
            )
        docker_cwd = tool_config.get("cwd", "/workspace")
        if not isinstance(docker_cwd, str) or not docker_cwd:
            raise ConfigurationError("tool.cwd must be a non-empty container path")
        process: ToolProcess = DockerExecProcess(
            container=tool_config.get("container"),
            expected_container_id=tool_config.get("expected_container_id"),
            expected_image_fingerprint=tool_config.get("expected_image_fingerprint"),
            expected_container_config_sha256=tool_config.get("expected_container_config_sha256"),
            expected_network_mode=tool_config.get("expected_network_mode", "none"),
            expected_user=tool_config.get("expected_user"),
            allowed_mounts=tuple(tool_config.get("allowed_mounts", [])),
            allowed_container_environment_names=tuple(
                allowed_container_environment_names
            ),
            cwd=docker_cwd,
            timeout_seconds=tool_config.get("timeout_seconds", 900.0),
            max_observation_chars=tool_config.get("max_observation_chars", 12000),
            shell_path=tool_config.get("shell_path", "/bin/sh"),
            docker_binary=tool_config.get("docker_binary", "docker"),
            environment=environment,
        )
    else:
        tool_cwd = _resolve_config_path(base, tool_config.get("cwd", str(root)), "tool.cwd")
        process = LocalShellProcess(
            cwd=tool_cwd,
            timeout_seconds=tool_config.get("timeout_seconds", 900.0),
            max_observation_chars=tool_config.get("max_observation_chars", 12000),
            shell_path=tool_config.get("shell_path", "/bin/sh"),
            environment=environment,
            inherit_environment=tool_config.get("inherit_environment", False),
            scrub_environment_names=tuple(sorted(scrub_names)),
            trusted_disposable_host=tool_config.get("trusted_disposable_host", False),
        )
    state_dir = _resolve_config_path(base, config.get("state_dir"), "state_dir")
    completion_command = config.get("completion_command", DEFAULT_COMPLETION_COMMAND)
    if not isinstance(completion_command, str):
        raise ConfigurationError("completion_command must be a string")
    if isinstance(process, DockerExecProcess):
        _validate_docker_host_bindings(
            process,
            workspace_root=root,
            monitored_paths=monitored,
            methodology=methodology,
        )
    probe_configuration = ProbeConfiguration(
        state_dir=state_dir,
        workspace=WorkspaceLayout(
            root=root,
            monitored_paths=tuple(monitored),
            git_semantic_required=workspace_config.get("git_semantic_required", True),
            git_untracked_exclude_patterns=tuple(
                workspace_config.get("git_untracked_exclude_patterns", [])
            ),
            respect_git_ignore=workspace_config.get("respect_git_ignore", False),
        ),
        methodology=methodology,
        task=task,
        limits=limits,
        completion_command=completion_command,
    )
    return RepositoryProbeRunner(probe_configuration, model, process)


def container_identity_from_config(
    path: Path,
    *,
    executor: Callable[..., Any] = subprocess.run,
) -> dict[str, Any]:
    """Read-only Docker inspection using the config's security allowlists.

    The expected identity fields may still contain placeholders; this command computes the values
    that must replace them without creating, restarting, or mutating the container.
    """

    raw = read_json(path)
    config = _mapping(raw, "configuration")
    tool = _mapping(config.get("tool"), "tool")
    if tool.get("backend") != "docker_exec":
        raise ConfigurationError("container-identity requires tool.backend docker_exec")
    environment = tool.get("environment")
    if environment is not None:
        environment = _mapping(environment, "tool.environment")
    process = DockerExecProcess(
        container=tool.get("container"),
        expected_container_id="identity-inspection-placeholder",
        expected_image_fingerprint="identity-inspection-placeholder",
        expected_container_config_sha256="0" * 64,
        expected_network_mode=tool.get("expected_network_mode", "none"),
        expected_user=tool.get("expected_user"),
        allowed_mounts=tuple(tool.get("allowed_mounts", [])),
        allowed_container_environment_names=tuple(
            tool.get("allowed_container_environment_names", [])
        ),
        cwd=tool.get("cwd", "/workspace"),
        timeout_seconds=tool.get("timeout_seconds", 900.0),
        max_observation_chars=tool.get("max_observation_chars", 12000),
        shell_path=tool.get("shell_path", "/bin/sh"),
        docker_binary=tool.get("docker_binary", "docker"),
        environment=environment,
        executor=executor,
    )
    identity = process.inspect_security_identity()
    return {
        "container_id": identity["container_id"],
        "image_fingerprint": identity["image"],
        "security_identity": identity,
        "expected_container_config_sha256": fingerprint(identity),
    }


def run_forced_compaction_rehearsal(base_dir: Path) -> dict[str, Any]:
    """Run a deterministic two-turn task that must cross one real runner compaction."""

    workspace = base_dir / "workspace"
    inputs = base_dir / "inputs"
    state_dir = base_dir / "state"
    source = workspace / "source"
    tests = workspace / "tests"
    evidence = workspace / "evidence"
    for directory in (source, tests, evidence, inputs):
        directory.mkdir(parents=True, exist_ok=True)
    tracker = workspace / "tracker.data"
    methodology_path = inputs / "methodology.txt"
    task_path = inputs / "task.txt"
    tracker.write_text("opaque tracker bytes\n", encoding="utf-8")
    methodology_path.write_text("Inspect evidence before changing representation.\n", encoding="utf-8")
    task_path.write_text("Record one observation, then finish the repository task.\n", encoding="utf-8")
    (source / "subject.txt").write_text("before\n", encoding="utf-8")
    (tests / "test_subject.txt").write_text("expected\n", encoding="utf-8")
    (evidence / "notes.txt").write_text("none yet\n", encoding="utf-8")

    first_action = ToolAction("rehearsal-call-1", "bash", {"command": "inspect subject"})
    completion_action = ToolAction(
        "rehearsal-call-2", "bash", {"command": DEFAULT_COMPLETION_COMMAND}
    )
    model = ScriptedModelAdapter(
        turns=[
            AssistantTurn("I will record the observed boundary.", (first_action,)),
            AssistantTurn("The logical task is complete after continuing from the packet.", (completion_action,)),
        ],
        summaries=[CompactionSummary(
            summary="The subject was inspected and its observation was durably recorded.",
            evidence=("rehearsal-call-1 returned success and source/observed.txt now exists",),
            unresolved_judgments=("The final completion signal still has to be executed",),
            latest_model_plan="Verify preserved evidence, then send the exact completion command.",
        )],
        estimated_tokens=64,
    )

    def record_observation(action: ToolAction) -> None:
        del action
        (source / "observed.txt").write_text("observed\n", encoding="utf-8")

    process = ScriptedToolProcess(
        observations=[
            ToolObservation("rehearsal-call-1", 0, output="observed subject\n"),
            ToolObservation("rehearsal-call-2", 0, output="COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n"),
        ],
        effects=[record_observation, None],
    )
    configuration = ProbeConfiguration(
        state_dir=state_dir,
        workspace=WorkspaceLayout(
            root=workspace,
            monitored_paths=(
                MonitoredPath("tracker", tracker, "tracker"),
                MonitoredPath("source", source, "source"),
                MonitoredPath("test", tests, "tests"),
                MonitoredPath("evidence", evidence, "evidence"),
            ),
            git_semantic_required=False,
        ),
        methodology=(ArtifactInput("rehearsal-methodology", methodology_path),),
        task=ArtifactInput("rehearsal-task", task_path, role="task"),
        limits=RunnerLimits(
            context_window_tokens=4096,
            proactive_trigger_tokens=3000,
            work_output_tokens=128,
            compaction_output_tokens=128,
            max_logical_calls=4,
            max_model_retries=0,
            retry_backoff_seconds=0,
            force_compaction_after_turns=(1,),
        ),
    )
    result = RepositoryProbeRunner(configuration, model, process).run()
    if len(model.work_requests) != 2 or len(model.compaction_requests) != 1:
        raise StateIntegrityError("forced rehearsal did not use the expected live control path")
    second_request = canonical_json(model.work_requests[1]).decode("utf-8")
    if "repository_probe_continuation" not in second_request:
        raise StateIntegrityError("second rehearsal work call did not receive the continuation packet")
    result["rehearsal"] = {
        "work_calls": len(model.work_requests),
        "compaction_calls": len(model.compaction_requests),
        "tool_calls": len(process.calls),
        "continued_with_packet": True,
    }
    return result


def _example_config() -> dict[str, Any]:
    return {
        "state_dir": "/private/tmp/repository-probe-state",
        "workspace": {
            "root": "/absolute/host/path/to/repo",
            "git_semantic_required": True,
            "git_untracked_exclude_patterns": ["target/*", ".mvn-cache/*"],
            "respect_git_ignore": False,
            "manifest": {
                "tracker": [{
                    "label": "tracker",
                    "path": "/absolute/host/path/to/slices_progress.yaml",
                    "model_path": "/workspace/bdr/slices_progress.yaml",
                }],
                "source": [{
                    "label": "main-source",
                    "path": "/absolute/host/path/to/repo/src/main",
                    "model_path": "/workspace/repo/src/main",
                }],
                "test": [{
                    "label": "tests",
                    "path": "/absolute/host/path/to/repo/src/test",
                    "model_path": "/workspace/repo/src/test",
                }],
                "evidence": [{
                    "label": "scratch",
                    "path": "/absolute/host/path/to/scratch",
                    "model_path": "/workspace/scratch",
                }],
            },
        },
        "methodology": [
            {"label": name, "path": "/absolute/host/path/to/bdr/" + name, "model_path": "/workspace/bdr/" + name}
            for name in (
                "01-METHOD.md", "02-FINDING-BOUNDARIES.md", "03-GUARDRAILS.md",
                "04-PHILOSOPHY.md", "05-CASE-STUDY.md", "README.md", "SKILL.md",
                "slices.py", "slices_progress.template.yaml",
            )
        ],
        "task": {"label": "task", "path": "/absolute/host/path/to/task.txt"},
        "model": {
            "endpoint": "http://127.0.0.1:8000/v1/chat/completions",
            "name": "bdr-candidate",
            "served_identity": {
                "accepted_response_models": ["bdr-candidate"],
                "revision": "replace-with-model-revision",
                "precision": "replace-with-serving-precision",
                "context_window_tokens": 131072,
                "server_fingerprint": "response-unavailable"
            },
            "api_key": "EMPTY",
            "user_agent": "bdrv1-repository-probe/2",
            "stream": True,
            "reasoning_effort": "medium",
            "temperature": 0.2,
            "estimated_bytes_per_token": 2.0,
            "replay_reasoning_field": "reasoning_content",
            "work_tool_choice": "auto",
        },
        "limits": {
            "context_window_tokens": 131072,
            "proactive_trigger_tokens": 100000,
            "work_output_tokens": 8192,
            "compaction_output_tokens": 4096,
            "max_logical_calls": 1000,
            "max_model_retries": 2,
        },
        "tool": {
            "backend": "docker_exec",
            "container": "bdrv1-stage3-candidate",
            "expected_container_id": "replace-with-immutable-container-id",
            "expected_image_fingerprint": "sha256:replace-with-image-id",
            "expected_container_config_sha256": "0" * 64,
            "expected_network_mode": "none",
            "expected_user": "1000:1000",
            "allowed_mounts": [
                {
                    "type": "bind",
                    "destination": destination,
                    "source_sha256": "0" * 64,
                    "source_kind": source_kind,
                    "rw": writable,
                    "propagation": "rprivate"
                }
                for destination, writable, source_kind in (
                    ("/workspace/repo", True, "directory"),
                    ("/workspace/bdr", False, "directory"),
                    ("/workspace/bdr/slices_progress.yaml", True, "regular_file"),
                    ("/workspace/scratch", True, "directory"),
                    ("/workspace/dependency-cache", True, "directory"),
                    ("/tmp", True, "directory"),
                )
            ],
            "allowed_container_environment_names": [
                "PATH", "HOME", "PAGER", "MANPAGER", "GIT_PAGER", "MAVEN_OPTS"
            ],
            "cwd": "/workspace/repo",
            "timeout_seconds": 900,
            "max_observation_chars": 12000,
            "environment": {
                "PAGER": "cat",
                "MANPAGER": "cat",
                "GIT_PAGER": "cat"
            },
        },
        "completion_command": DEFAULT_COMPLETION_COMMAND,
    }


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    run_parser = subparsers.add_parser("run", help="run or safely resume a preregistered probe")
    run_parser.add_argument("--config", type=Path, required=True)
    run_parser.add_argument(
        "--pause-after-compactions",
        type=int,
        help=(
            "exit successfully at the durable checkpoint after at least this many completed "
            "context-maintenance calls; rerun without this option to resume"
        ),
    )
    rehearsal_parser = subparsers.add_parser(
        "rehearse", help="run the deterministic forced-compaction integration rehearsal"
    )
    rehearsal_parser.add_argument("--directory", type=Path)
    subparsers.add_parser("example-config", help="print a live JSON configuration template")
    identity_parser = subparsers.add_parser(
        "container-identity",
        help="read-only inspect a configured disposable Docker container",
    )
    identity_parser.add_argument("--config", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "run":
            result = runner_from_config(args.config).run(
                pause_after_compactions=args.pause_after_compactions,
            )
        elif args.command == "rehearse":
            if args.directory is None:
                with tempfile.TemporaryDirectory(prefix="bdrv1-probe-rehearsal-") as temporary:
                    result = run_forced_compaction_rehearsal(Path(temporary))
            else:
                result = run_forced_compaction_rehearsal(args.directory)
        elif args.command == "container-identity":
            result = container_identity_from_config(args.config)
        else:
            print(json.dumps(_example_config(), indent=2, sort_keys=True))
            return 0
    except ProbeError as error:
        print(json.dumps({"status": "failed", "kind": error.kind, "message": str(error)}), file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
