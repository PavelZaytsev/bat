#!/usr/bin/env python3
"""Evaluator-side integrity checks for BDRv1 run artifacts.

This module deliberately does not import or modify the frozen BDRv1 validator.  The slice status
vocabulary is derived from that frozen bundle:

* ``pending``, ``in_progress``, ``done``, and ``blocked`` are the states rendered by
  ``methodology/slices.py``;
* ``superseded`` is a real slice disposition recorded by ``methodology/05-CASE-STUDY.md``.

Finding dispositions such as ``fixed`` are a different vocabulary.  In particular, accepting
``fixed`` as a *slice* status lets the frozen validator's done-only closure rules be bypassed, so
this independent preflight rejects missing and unknown slice statuses.

When a compacted-continuation manifest is supplied, the evaluator also verifies the immutable run
policy's canonical-JSON fingerprint and requires explicit continuity declarations.  It validates
the artifact contract only; checkpointing, compaction, and continuation remain runner concerns.
"""

from __future__ import annotations

import argparse
from collections.abc import Mapping, Sequence
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any

import yaml


SLICE_STATUS_ORDER = ("pending", "in_progress", "done", "blocked", "superseded")
ALLOWED_SLICE_STATUSES = frozenset(SLICE_STATUS_ORDER)

RUN_MANIFEST_SCHEMA_VERSION = "bdrv1-repository-run/v1"
WORKSPACE_FINGERPRINT_ALGORITHM = "bdr-workspace-v2"

_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_CONTINUATION_ID_FIELDS = ("continuation_id", "source_checkpoint_id")
_CONTINUATION_SHA256_FIELDS = (
    "source_checkpoint_sha256",
    "source_policy_sha256",
    "source_history_sha256",
    "summary_sha256",
    "packet_sha256",
)
_CONTINUATION_COUNTER_FIELDS = (
    "source_completed_turns",
    "source_model_calls",
    "source_tool_calls_completed",
)
_CONTINUITY_DECLARATIONS = (
    "complete_turn_boundary",
    "tool_calls_closed",
    "workspace_continuity",
)


def canonical_json(value: Any) -> bytes:
    """Return the canonical JSON bytes used by BDR experiment fingerprints."""

    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def policy_fingerprint(policy: Mapping[str, Any]) -> str:
    """Fingerprint an immutable run policy using canonical JSON and SHA-256."""

    if not isinstance(policy, Mapping):
        raise TypeError("run policy must be a mapping")
    return hashlib.sha256(canonical_json(policy)).hexdigest()


def validate_slice_statuses(tracker: Any) -> list[str]:
    """Return problems with the evaluator-owned slice status invariant."""

    if not isinstance(tracker, Mapping):
        return ["tracker must be a mapping"]

    if "slices" not in tracker:
        return ["tracker.slices is missing"]

    slices = tracker["slices"]
    if not isinstance(slices, list):
        return ["tracker.slices must be a list"]

    problems: list[str] = []
    allowed = ", ".join(SLICE_STATUS_ORDER)
    for index, slice_record in enumerate(slices):
        location = f"slices[{index}]"
        if not isinstance(slice_record, Mapping):
            problems.append(f"{location} must be a mapping")
            continue

        if "id" in slice_record:
            location += f" (id={slice_record['id']!r})"

        if "status" not in slice_record or slice_record["status"] is None:
            problems.append(f"{location}: slice status is missing; allowed: {allowed}")
            continue

        status = slice_record["status"]
        if not isinstance(status, str) or status not in ALLOWED_SLICE_STATUSES:
            problems.append(
                f"{location}: unknown slice status {status!r}; allowed: {allowed}"
            )

    return problems


def _valid_sha256(value: Any) -> bool:
    return isinstance(value, str) and _SHA256_RE.fullmatch(value) is not None


def _validate_workspace_fingerprint(value: Any) -> list[str]:
    field = "continuation.source_workspace_fingerprint"
    if not isinstance(value, Mapping):
        return [f"{field} must be a mapping"]

    problems: list[str] = []
    if set(value) != {
        "algorithm",
        "head_sha",
        "worktree_sha256",
        "content_delta_sha256",
        "git_semantic_sha256",
        "dirty",
    }:
        problems.append(f"{field} has the wrong fields")
    if value.get("algorithm") != WORKSPACE_FINGERPRINT_ALGORITHM:
        problems.append(
            f"{field}.algorithm must be {WORKSPACE_FINGERPRINT_ALGORITHM!r}"
        )

    head_sha = value.get("head_sha")
    if not isinstance(head_sha, str) or not re.fullmatch(
        r"(?:[0-9a-f]{40}|[0-9a-f]{64})", head_sha
    ):
        problems.append(
            f"{field}.head_sha must be a 40- or 64-character lowercase hex digest"
        )

    for name in ("worktree_sha256", "content_delta_sha256", "git_semantic_sha256"):
        if not _valid_sha256(value.get(name)):
            problems.append(f"{field}.{name} must be a 64-character lowercase SHA-256")

    if not isinstance(value.get("dirty"), bool):
        problems.append(f"{field}.dirty must be a boolean")
    return problems


def _validate_continuation_packet(
    continuation: Mapping[str, Any], packet: Any,
) -> list[str]:
    if not isinstance(packet, Mapping):
        return ["manifest.packet must be a mapping"]

    problems: list[str] = []
    if set(packet) != {"kind", "protocol_version", "continuity", "preserved"}:
        problems.append("manifest.packet has the wrong fields")
    if packet.get("kind") != "repository_probe_continuation":
        problems.append("manifest.packet.kind must be 'repository_probe_continuation'")
    if packet.get("protocol_version") != 2:
        problems.append("manifest.packet.protocol_version must be 2")

    expected_continuity = dict(continuation)
    expected_continuity.pop("packet_sha256", None)
    if packet.get("continuity") != expected_continuity:
        problems.append("manifest.packet.continuity does not match manifest.continuation")

    preserved = packet.get("preserved")
    if not isinstance(preserved, Mapping):
        problems.append("manifest.packet.preserved must be a mapping")
        return problems
    required_preserved_fields = {
        "summary",
        "evidence",
        "unresolved_judgments",
        "latest_model_plan",
        "safe_transcript_tail",
        "workspace_manifest_sha256",
        "workspace_category_sha256",
    }
    if set(preserved) != required_preserved_fields:
        problems.append("manifest.packet.preserved has the wrong fields")

    summary = {
        name: preserved.get(name)
        for name in (
            "summary", "evidence", "unresolved_judgments", "latest_model_plan",
        )
    }
    for name in ("summary", "latest_model_plan"):
        if not isinstance(summary[name], str):
            problems.append(f"manifest.packet.preserved.{name} must be a string")
    for name in ("evidence", "unresolved_judgments"):
        value = summary[name]
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            problems.append(
                f"manifest.packet.preserved.{name} must be an array of strings"
            )
    try:
        summary_sha256 = policy_fingerprint(summary)
    except (TypeError, ValueError) as error:
        problems.append(f"packet preserved summary is not canonical JSON: {error}")
    else:
        if continuation.get("summary_sha256") != summary_sha256:
            problems.append(
                "continuation.summary_sha256 does not match packet preserved summary"
            )

    if not isinstance(preserved.get("safe_transcript_tail"), list):
        problems.append("manifest.packet.preserved.safe_transcript_tail must be an array")
    workspace_manifest_sha256 = preserved.get("workspace_manifest_sha256")
    if not _valid_sha256(workspace_manifest_sha256):
        problems.append(
            "manifest.packet.preserved.workspace_manifest_sha256 must be a SHA-256"
        )
    else:
        workspace = continuation.get("source_workspace_fingerprint")
        if (
            isinstance(workspace, Mapping)
            and workspace_manifest_sha256 != workspace.get("worktree_sha256")
        ):
            problems.append(
                "packet workspace manifest does not match continuation workspace fingerprint"
            )
    category_sha256 = preserved.get("workspace_category_sha256")
    if not isinstance(category_sha256, Mapping):
        problems.append(
            "manifest.packet.preserved.workspace_category_sha256 must be a mapping"
        )
    else:
        if set(category_sha256) != {"tracker", "source", "test", "evidence"}:
            problems.append(
                "manifest.packet.preserved.workspace_category_sha256 has the wrong fields"
            )
        for name, value in category_sha256.items():
            if not isinstance(name, str) or not _valid_sha256(value):
                problems.append(
                    "manifest.packet.preserved.workspace_category_sha256 is malformed"
                )
                break
    return problems


def validate_continuation_manifest(
    manifest: Any,
    *,
    current_policy: Mapping[str, Any] | None = None,
    expected_policy_sha256: str | None = None,
) -> list[str]:
    """Validate a supplied compacted-continuation manifest.

    ``current_policy`` and ``expected_policy_sha256`` are optional independent bindings supplied by
    the evaluator.  When present, both must agree with the manifest's embedded immutable policy.
    Even without them, the embedded policy, its declared fingerprint, and the continuation's source
    fingerprint must agree, preventing drift inside the continuation artifact.
    """

    if not isinstance(manifest, Mapping):
        return ["continuation manifest must be a mapping"]

    problems: list[str] = []
    required_manifest_fields = {
        "schema_version",
        "run_policy",
        "run_policy_sha256",
        "previous_continuation_manifest_sha256",
        "continuation",
        "packet",
        "maintenance_reasoning",
        "manifest_sha256",
    }
    if set(manifest) != required_manifest_fields:
        problems.append("continuation manifest has the wrong top-level fields")
    if manifest.get("schema_version") != RUN_MANIFEST_SCHEMA_VERSION:
        problems.append(
            "manifest.schema_version must be " f"{RUN_MANIFEST_SCHEMA_VERSION!r}"
        )

    declared_manifest_sha256 = manifest.get("manifest_sha256")
    if not _valid_sha256(declared_manifest_sha256):
        problems.append(
            "manifest.manifest_sha256 must be a 64-character lowercase SHA-256"
        )
    else:
        manifest_body = dict(manifest)
        manifest_body.pop("manifest_sha256", None)
        try:
            actual_manifest_sha256 = policy_fingerprint(manifest_body)
        except (TypeError, ValueError) as error:
            problems.append(f"continuation manifest is not canonical-JSON serializable: {error}")
        else:
            if declared_manifest_sha256 != actual_manifest_sha256:
                problems.append(
                    "manifest.manifest_sha256 does not match canonical manifest contents"
                )

    previous_manifest_sha256 = manifest.get("previous_continuation_manifest_sha256")
    if previous_manifest_sha256 is not None and not _valid_sha256(
        previous_manifest_sha256
    ):
        problems.append(
            "manifest.previous_continuation_manifest_sha256 must be null or a SHA-256"
        )

    embedded_policy = manifest.get("run_policy")
    embedded_policy_sha256: str | None = None
    if not isinstance(embedded_policy, Mapping):
        problems.append("manifest.run_policy must be a mapping")
    else:
        try:
            embedded_policy_sha256 = policy_fingerprint(embedded_policy)
        except (TypeError, ValueError) as error:
            problems.append(f"manifest.run_policy is not canonical-JSON serializable: {error}")

    declared_policy_sha256 = manifest.get("run_policy_sha256")
    if not _valid_sha256(declared_policy_sha256):
        problems.append(
            "manifest.run_policy_sha256 must be a 64-character lowercase SHA-256"
        )
    elif (
        embedded_policy_sha256 is not None
        and declared_policy_sha256 != embedded_policy_sha256
    ):
        problems.append(
            "manifest.run_policy_sha256 does not match canonical manifest.run_policy"
        )

    current_policy_sha256: str | None = None
    if current_policy is not None:
        try:
            current_policy_sha256 = policy_fingerprint(current_policy)
        except (TypeError, ValueError) as error:
            problems.append(f"current run policy is not canonical-JSON serializable: {error}")

    if expected_policy_sha256 is not None:
        if not _valid_sha256(expected_policy_sha256):
            problems.append(
                "expected policy fingerprint must be a 64-character lowercase SHA-256"
            )
        elif current_policy_sha256 is not None and expected_policy_sha256 != current_policy_sha256:
            problems.append("expected policy fingerprint does not match current run policy")
        else:
            current_policy_sha256 = expected_policy_sha256

    if current_policy_sha256 is None:
        current_policy_sha256 = embedded_policy_sha256

    if (
        current_policy_sha256 is not None
        and _valid_sha256(declared_policy_sha256)
        and declared_policy_sha256 != current_policy_sha256
    ):
        problems.append("manifest run policy has drifted from the evaluator's current policy")

    continuation = manifest.get("continuation")
    if not isinstance(continuation, Mapping):
        problems.append("manifest.continuation must be a mapping")
        return problems

    required_continuation_fields = {
        *_CONTINUATION_ID_FIELDS,
        *_CONTINUATION_SHA256_FIELDS,
        *_CONTINUATION_COUNTER_FIELDS,
        *_CONTINUITY_DECLARATIONS,
        "source_workspace_fingerprint",
        "trigger_reason",
        "trigger_estimated_tokens",
        "trigger_threshold_tokens",
        "maintenance_reasoning_sha256",
    }
    if set(continuation) != required_continuation_fields:
        problems.append("manifest.continuation has the wrong fields")

    for name in _CONTINUATION_ID_FIELDS:
        value = continuation.get(name)
        if not isinstance(value, str) or not value.strip():
            problems.append(f"continuation.{name} must be a non-empty string")

    continuation_id = continuation.get("continuation_id")
    id_match = (
        re.fullmatch(r"continuation-([0-9]{6})", continuation_id)
        if isinstance(continuation_id, str) else None
    )
    if id_match is None or int(id_match.group(1)) < 1:
        problems.append(
            "continuation.continuation_id must be continuation-NNNNNN with a positive ordinal"
        )
    elif int(id_match.group(1)) == 1:
        if previous_manifest_sha256 is not None:
            problems.append("the first continuation must not declare a previous manifest")
    elif not _valid_sha256(previous_manifest_sha256):
        problems.append("a continuation after the first must declare its previous manifest")

    checkpoint_id = continuation.get("source_checkpoint_id")
    if not isinstance(checkpoint_id, str) or re.fullmatch(
        r"checkpoint-[0-9]{6}", checkpoint_id,
    ) is None:
        problems.append(
            "continuation.source_checkpoint_id must be checkpoint-NNNNNN"
        )

    for name in _CONTINUATION_SHA256_FIELDS:
        if not _valid_sha256(continuation.get(name)):
            problems.append(
                f"continuation.{name} must be a 64-character lowercase SHA-256"
            )

    source_policy_sha256 = continuation.get("source_policy_sha256")
    if (
        current_policy_sha256 is not None
        and _valid_sha256(source_policy_sha256)
        and source_policy_sha256 != current_policy_sha256
    ):
        problems.append("continuation source policy has drifted from the current run policy")

    for name in _CONTINUATION_COUNTER_FIELDS:
        value = continuation.get(name)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            problems.append(f"continuation.{name} must be a non-negative integer")

    if continuation.get("trigger_reason") not in {"forced_turn", "estimated_threshold"}:
        problems.append(
            "continuation.trigger_reason must be forced_turn or estimated_threshold"
        )
    for name in ("trigger_estimated_tokens", "trigger_threshold_tokens"):
        value = continuation.get(name)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            problems.append(f"continuation.{name} must be a non-negative integer")

    maintenance_reasoning = manifest.get("maintenance_reasoning")
    reasoning_sha256 = continuation.get("maintenance_reasoning_sha256")
    if maintenance_reasoning is None:
        if reasoning_sha256 is not None:
            problems.append(
                "continuation.maintenance_reasoning_sha256 must be null when reasoning is absent"
            )
    elif not isinstance(maintenance_reasoning, str):
        problems.append("manifest.maintenance_reasoning must be a string or null")
    elif reasoning_sha256 != hashlib.sha256(
        maintenance_reasoning.encode("utf-8")
    ).hexdigest():
        problems.append(
            "continuation.maintenance_reasoning_sha256 does not match maintenance reasoning"
        )

    problems.extend(
        _validate_workspace_fingerprint(continuation.get("source_workspace_fingerprint"))
    )

    for name in _CONTINUITY_DECLARATIONS:
        if continuation.get(name) is not True:
            problems.append(
                f"continuation.{name} must explicitly be true for compacted continuation"
            )

    packet = manifest.get("packet")
    packet_sha256 = continuation.get("packet_sha256")
    if isinstance(packet, Mapping) and _valid_sha256(packet_sha256):
        try:
            actual_packet_sha256 = policy_fingerprint(packet)
        except (TypeError, ValueError) as error:
            problems.append(f"manifest.packet is not canonical-JSON serializable: {error}")
        else:
            if packet_sha256 != actual_packet_sha256:
                problems.append(
                    "continuation.packet_sha256 does not match canonical manifest.packet"
                )
    problems.extend(_validate_continuation_packet(continuation, packet))

    return problems


def validate_continuation_chain(
    manifests: Sequence[Any],
    *,
    current_policy: Mapping[str, Any] | None = None,
    expected_policy_sha256: str | None = None,
) -> list[str]:
    """Validate an origin-complete ordered continuation chain.

    Supplying continuation #2 without #1 is deliberately insufficient: the predecessor's
    canonical manifest digest is the evidence that makes #2's link meaningful.
    """

    if (
        not isinstance(manifests, Sequence)
        or isinstance(manifests, (str, bytes))
        or not manifests
    ):
        return ["continuation chain must be a non-empty sequence"]

    problems: list[str] = []
    previous_manifest_sha256: str | None = None
    chain_policy_sha256: str | None = None
    for index, manifest in enumerate(manifests, start=1):
        manifest_problems = validate_continuation_manifest(
            manifest,
            current_policy=current_policy,
            expected_policy_sha256=expected_policy_sha256,
        )
        problems.extend(
            f"continuation manifest {index}: {problem}"
            for problem in manifest_problems
        )
        if not isinstance(manifest, Mapping):
            continue
        continuation = manifest.get("continuation")
        expected_id = f"continuation-{index:06d}"
        if not isinstance(continuation, Mapping) or continuation.get(
            "continuation_id"
        ) != expected_id:
            problems.append(
                f"continuation manifest {index}: expected id {expected_id}"
            )
        if manifest.get(
            "previous_continuation_manifest_sha256"
        ) != previous_manifest_sha256:
            problems.append(
                f"continuation manifest {index}: previous-manifest link is broken"
            )
        declared_policy = manifest.get("run_policy_sha256")
        if index == 1 and _valid_sha256(declared_policy):
            chain_policy_sha256 = declared_policy
        elif chain_policy_sha256 is not None and declared_policy != chain_policy_sha256:
            problems.append(
                f"continuation manifest {index}: run policy changed inside the chain"
            )
        declared = manifest.get("manifest_sha256")
        previous_manifest_sha256 = declared if _valid_sha256(declared) else None
    return problems


def validate_run(
    tracker: Any,
    *,
    continuation_manifest: Any | None = None,
    current_policy: Mapping[str, Any] | None = None,
    expected_policy_sha256: str | None = None,
) -> list[str]:
    """Validate the evaluator-owned invariants for one run."""

    problems = validate_slice_statuses(tracker)
    if continuation_manifest is None:
        if current_policy is not None or expected_policy_sha256 is not None:
            problems.append("a policy binding was supplied without a continuation manifest")
        return problems

    problems.extend(
        validate_continuation_manifest(
            continuation_manifest,
            current_policy=current_policy,
            expected_policy_sha256=expected_policy_sha256,
        )
    )
    return problems


def load_structured_file(path: Path) -> Any:
    """Load JSON by suffix and otherwise load YAML safely."""

    text = path.read_text()
    if path.suffix.lower() == ".json":
        return json.loads(text)
    return yaml.safe_load(text)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tracker", type=Path, required=True)
    parser.add_argument(
        "--continuation-manifest",
        type=Path,
        action="append",
        help=(
            "Continuation manifest in chain order; repeat for every manifest from #1 "
            "through the latest."
        ),
    )
    parser.add_argument(
        "--run-policy",
        type=Path,
        help="Optional current policy file to bind the continuation against.",
    )
    parser.add_argument(
        "--expected-policy-sha256",
        help="Optional externally recorded current policy fingerprint.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        tracker = load_structured_file(args.tracker)
        manifests = (
            [load_structured_file(path) for path in args.continuation_manifest]
            if args.continuation_manifest else []
        )
        policy = load_structured_file(args.run_policy) if args.run_policy else None
    except (OSError, json.JSONDecodeError, yaml.YAMLError) as error:
        print(f"evaluation input error: {error}", file=sys.stderr)
        return 2

    problems = validate_slice_statuses(tracker)
    if manifests:
        problems.extend(validate_continuation_chain(
            manifests,
            current_policy=policy,
            expected_policy_sha256=args.expected_policy_sha256,
        ))
    elif policy is not None or args.expected_policy_sha256 is not None:
        problems.append("a policy binding was supplied without a continuation manifest")
    for problem in problems:
        print(f"x {problem}")
    if problems:
        return 1

    print("evaluation preflight passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
