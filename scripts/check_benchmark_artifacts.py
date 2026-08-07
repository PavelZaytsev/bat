#!/usr/bin/env python3
"""Verify the content-addressed BDR pilot artifact graph without target source."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath, PureWindowsPath
from types import ModuleType
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PILOT_ROOT = REPOSITORY_ROOT / "benchmarks" / "pilot"
HEX_SHA256 = frozenset("0123456789abcdef")
HEX_OBJECT_ID = frozenset("0123456789abcdef")
GIT_TIMEOUT_SECONDS = 60


class VerificationError(RuntimeError):
    pass


@dataclass(frozen=True)
class Case:
    case_id: str
    directory: Path
    manifest: dict[str, Any]
    manifest_sha256: str
    oracle: dict[str, Any]
    oracle_sha256: str
    base_sha: str
    head_sha: str


@dataclass
class Counts:
    cases: int = 0
    runs: int = 0
    hashes: int = 0
    bundles: int = 0
    journals: int = 0
    referenced: set[Path] = field(default_factory=set)


def fail(source: Path | str, message: str) -> None:
    raise VerificationError(f"{source}: {message}")


def load_bdr_engine() -> ModuleType:
    engine_path = REPOSITORY_ROOT / "scripts" / "bdr.py"
    spec = importlib.util.spec_from_file_location("bdr_artifact_validator", engine_path)
    if spec is None or spec.loader is None:
        fail(engine_path, "cannot load the BDR state engine")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def directory_root(path: Path, source: Path | str) -> Path:
    try:
        metadata = os.lstat(path)
    except OSError as exc:
        fail(source, f"cannot inspect directory {path}: {exc}")
    reparse = getattr(metadata, "st_file_attributes", 0) & getattr(
        stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0
    )
    if stat.S_ISLNK(metadata.st_mode) or reparse:
        fail(source, f"directory must not be a symlink or reparse point: {path}")
    if not stat.S_ISDIR(metadata.st_mode):
        fail(source, f"expected a directory: {path}")
    return Path(os.path.abspath(path))


def run_git(arguments: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    environment.update(
        {
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_NO_REPLACE_OBJECTS": "1",
            "GIT_OPTIONAL_LOCKS": "0",
            "LC_ALL": "C",
            "LANG": "C",
        }
    )
    try:
        return subprocess.run(
            ["git", *arguments],
            cwd=cwd,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            env=environment,
            timeout=GIT_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        fail(cwd, f"Git command exceeded {GIT_TIMEOUT_SECONDS} seconds: {' '.join(arguments)}")
    except OSError as exc:
        fail(cwd, f"cannot run Git: {exc}")


def require_git(arguments: list[str], cwd: Path, source: Path, purpose: str) -> str:
    result = run_git(arguments, cwd)
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        fail(source, f"{purpose} failed: {detail}")
    return result.stdout


def regular_file(path: Path, source: Path | str) -> Path:
    try:
        metadata = os.lstat(path)
    except OSError as exc:
        fail(source, f"cannot inspect {path}: {exc}")
    if stat.S_ISLNK(metadata.st_mode):
        fail(source, f"artifact must not be a symlink: {path}")
    if not stat.S_ISREG(metadata.st_mode):
        fail(source, f"artifact must be a regular file: {path}")
    return path


def remember(counts: Counts, path: Path) -> Path:
    counts.referenced.add(Path(os.path.abspath(path)))
    return path


def artifact_path(base: Path, relative: Any, boundary: Path, source: Path | str) -> Path:
    if not isinstance(relative, str) or not relative or "\x00" in relative or "\\" in relative:
        fail(source, f"invalid portable relative path: {relative!r}")
    posix = PurePosixPath(relative)
    windows = PureWindowsPath(relative)
    if posix.is_absolute() or windows.is_absolute() or windows.drive:
        fail(source, f"artifact path must be relative: {relative!r}")

    boundary_absolute = Path(os.path.abspath(boundary))
    candidate = Path(os.path.abspath(base.joinpath(*posix.parts)))
    try:
        parts = candidate.relative_to(boundary_absolute).parts
    except ValueError:
        fail(source, f"artifact path escapes {boundary}: {relative!r}")

    cursor = boundary_absolute
    for part in parts:
        cursor /= part
        try:
            metadata = os.lstat(cursor)
        except OSError as exc:
            fail(source, f"cannot inspect artifact path {relative!r}: {exc}")
        if stat.S_ISLNK(metadata.st_mode):
            fail(source, f"artifact path traverses a symlink: {relative!r}")
    return regular_file(candidate, source)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as exc:
        fail(path, f"cannot hash artifact: {exc}")
    return digest.hexdigest()


def require_sha256(value: Any, source: Path | str, field: str) -> str:
    if not isinstance(value, str) or len(value) != 64 or any(c not in HEX_SHA256 for c in value):
        fail(source, f"{field} must be a lowercase SHA-256 digest")
    return value


def require_object_id(value: Any, source: Path | str, field: str) -> str:
    if (
        not isinstance(value, str)
        or len(value) != 40
        or any(c not in HEX_OBJECT_ID for c in value)
    ):
        fail(source, f"{field} must be a full lowercase SHA-1 Git object ID (40 hex characters)")
    return value


def require_text(value: dict[str, Any], field: str, source: Path | str) -> str:
    item = value.get(field)
    if not isinstance(item, str) or not item:
        fail(source, f"{field} must be a non-empty string")
    return item


def strict_object(path: Path, engine: ModuleType) -> dict[str, Any]:
    regular_file(path, path)
    try:
        return engine.loads_strict(path.read_text(encoding="utf-8"), str(path))
    except (OSError, UnicodeError, engine.BdrError) as exc:
        fail(path, str(exc))


def verify_hash(path: Path, expected: Any, source: Path | str, field: str, counts: Counts) -> str:
    remember(counts, path)
    wanted = require_sha256(expected, source, field)
    actual = sha256_file(path)
    if actual != wanted:
        fail(source, f"{field} mismatch for {path}: expected {wanted}, found {actual}")
    counts.hashes += 1
    return actual


def bundle_prerequisites(path: Path) -> list[str]:
    prerequisites: list[str] = []
    try:
        with path.open("rb") as stream:
            signature = stream.readline()
            if not signature.startswith(b"# v") or not signature.rstrip().endswith(b"git bundle"):
                fail(path, "invalid Git bundle signature")
            for _ in range(100_000):
                line = stream.readline()
                if line in (b"", b"\n", b"\r\n"):
                    break
                if line.startswith(b"-"):
                    prerequisites.append(line[1:].split(b" ", 1)[0].decode("ascii", "replace"))
            else:
                fail(path, "bundle header is unreasonably large")
    except OSError as exc:
        fail(path, f"cannot read bundle header: {exc}")
    return prerequisites


def verify_bundle(
    path: Path,
    required_commits: list[str],
    ancestor_pairs: list[tuple[str, str]],
    counts: Counts,
    expected_ref: str | None = None,
    expected_ref_sha: str | None = None,
    expected_tip_sha: str | None = None,
    patch_path: Path | None = None,
    patch_start_sha: str | None = None,
    patch_final_sha: str | None = None,
) -> None:
    require_git(["bundle", "verify", str(path)], REPOSITORY_ROOT, path, "bundle verification")
    if bundle_prerequisites(path):
        fail(path, "bundle depends on prerequisite objects instead of recording complete history")

    heads_text = require_git(["bundle", "list-heads", str(path)], REPOSITORY_ROOT, path, "bundle head listing")
    heads: dict[str, str] = {}
    for line in heads_text.splitlines():
        fields = line.split()
        if len(fields) == 2:
            heads[fields[1]] = fields[0]
    if not heads:
        fail(path, "bundle exposes no refs")
    if expected_ref is not None and heads.get(expected_ref) != expected_ref_sha:
        fail(path, f"{expected_ref} does not resolve to declared head {expected_ref_sha}")
    if expected_tip_sha is not None and expected_tip_sha not in heads.values():
        fail(path, f"no bundle ref resolves to declared final head {expected_tip_sha}")

    with tempfile.TemporaryDirectory(prefix="bdr-bundle-check-") as temp:
        bare = Path(temp) / "objects.git"
        require_git(["init", "--bare", "-q", str(bare)], REPOSITORY_ROOT, path, "temporary repository initialization")
        require_git(
            ["--git-dir", str(bare), "bundle", "unbundle", str(path)],
            REPOSITORY_ROOT,
            path,
            "bundle unpack",
        )
        for commit in dict.fromkeys(required_commits):
            result = run_git(["--git-dir", str(bare), "cat-file", "-e", f"{commit}^{{commit}}"], REPOSITORY_ROOT)
            if result.returncode:
                fail(path, f"bundle does not contain declared commit {commit}")
        for ancestor, descendant in ancestor_pairs:
            result = run_git(
                ["--git-dir", str(bare), "merge-base", "--is-ancestor", ancestor, descendant],
                REPOSITORY_ROOT,
            )
            if result.returncode:
                fail(path, f"declared lineage is false: {ancestor} is not an ancestor of {descendant}")
        patch_parameters = (patch_path, patch_start_sha, patch_final_sha)
        if any(value is not None for value in patch_parameters):
            if not all(value is not None for value in patch_parameters):
                fail(path, "patch verification requires a patch, starting SHA, and final SHA")
            checkout = Path(temp) / "patch-checkout"
            require_git(
                [
                    "--git-dir",
                    str(bare),
                    "worktree",
                    "add",
                    "--detach",
                    str(checkout),
                    str(patch_start_sha),
                ],
                REPOSITORY_ROOT,
                path,
                "patch checkout",
            )
            require_git(
                ["apply", "--index", "--binary", "--whitespace=nowarn", str(patch_path)],
                checkout,
                path,
                "final patch application",
            )
            actual_tree = require_git(["write-tree"], checkout, path, "patched tree calculation").strip()
            expected_tree = require_git(
                ["--git-dir", str(bare), "rev-parse", f"{patch_final_sha}^{{tree}}"],
                REPOSITORY_ROOT,
                path,
                "final tree lookup",
            ).strip()
            if actual_tree != expected_tree:
                fail(
                    patch_path or path,
                    f"patch produces tree {actual_tree}, but declared final commit has tree {expected_tree}",
                )
    counts.bundles += 1


def verify_case(case_directory: Path, pilot_root: Path, engine: ModuleType, counts: Counts) -> Case:
    case_directory = directory_root(case_directory, case_directory)
    manifest_path = case_directory / "manifest.json"
    artifacts_path = case_directory / "case-artifacts.json"
    remember(counts, artifacts_path)
    manifest = strict_object(manifest_path, engine)
    artifacts = strict_object(artifacts_path, engine)
    if manifest.get("schema") != "bdr.dev/pilot-manifest/v1":
        fail(manifest_path, "unsupported or missing pilot manifest schema")
    if artifacts.get("schema") != "bdr.dev/pilot-case-artifacts/v1":
        fail(artifacts_path, "unsupported or missing case artifact schema")
    case_id = require_text(manifest, "case_id", manifest_path)
    if case_id != case_directory.name:
        fail(manifest_path, f"case_id {case_id!r} does not match directory {case_directory.name!r}")
    if artifacts.get("case_id") != case_id:
        fail(artifacts_path, "case_id does not match manifest")

    manifest_digest = verify_hash(
        manifest_path,
        artifacts.get("manifest_sha256"),
        artifacts_path,
        "manifest_sha256",
        counts,
    )
    target = manifest.get("target")
    bundle = artifacts.get("target_bundle")
    if not isinstance(target, dict) or not isinstance(bundle, dict):
        fail(manifest_path, "target and target_bundle must be objects")
    base_sha = require_object_id(target.get("base_sha"), manifest_path, "target.base_sha")
    head_sha = require_object_id(target.get("head_sha"), manifest_path, "target.head_sha")
    if bundle.get("base_sha") != base_sha or bundle.get("head_sha") != head_sha:
        fail(artifacts_path, "target bundle pins do not match the manifest target")
    bundle_path = artifact_path(case_directory, bundle.get("path"), case_directory, artifacts_path)
    verify_hash(bundle_path, bundle.get("sha256"), artifacts_path, "target_bundle.sha256", counts)
    head_ref = require_text(bundle, "head_ref", artifacts_path)
    if bundle.get("complete_history") is not True:
        fail(artifacts_path, "target_bundle.complete_history must be true")
    verify_bundle(
        bundle_path,
        [base_sha, head_sha],
        [(base_sha, head_sha)],
        counts,
        expected_ref=head_ref,
        expected_ref_sha=head_sha,
    )

    private_oracle = manifest.get("private_oracle")
    if not isinstance(private_oracle, dict) or private_oracle.get("actor_readable") is not False:
        fail(manifest_path, "private_oracle must be an object with actor_readable=false")
    oracle_path = artifact_path(
        case_directory,
        private_oracle.get("path"),
        pilot_root,
        manifest_path,
    )
    directory_root(oracle_path.parent, oracle_path)
    remember(counts, oracle_path)
    oracle = strict_object(oracle_path, engine)
    if oracle.get("schema") != "bdr.dev/pilot-oracle/v1":
        fail(oracle_path, "unsupported or missing pilot oracle schema")
    if oracle.get("case_id") != case_id:
        fail(oracle_path, "case_id does not match manifest")
    oracle_target = oracle.get("target")
    if not isinstance(oracle_target, dict):
        fail(oracle_path, "target must be an object")
    if oracle_target.get("base_sha") != base_sha or oracle_target.get("head_sha") != head_sha:
        fail(oracle_path, "target pins do not match manifest")
    known_bugs = oracle.get("known_bugs")
    if not isinstance(known_bugs, list):
        fail(oracle_path, "known_bugs must be an array")
    bug_ids: set[str] = set()
    for index, bug in enumerate(known_bugs):
        if not isinstance(bug, dict):
            fail(oracle_path, f"known_bugs[{index}] must be an object")
        bug_id = require_text(bug, "id", oracle_path)
        if bug_id in bug_ids:
            fail(oracle_path, f"duplicate known bug id {bug_id!r}")
        bug_ids.add(bug_id)
        test_asset = bug.get("test_asset")
        if not isinstance(test_asset, dict):
            fail(oracle_path, f"known bug {bug_id} has no test_asset object")
        test_path = artifact_path(oracle_path.parent, test_asset.get("path"), oracle_path.parent, oracle_path)
        verify_hash(test_path, test_asset.get("sha256"), oracle_path, f"{bug_id}.test_asset.sha256", counts)

    counts.cases += 1
    return Case(
        case_id=case_id,
        directory=case_directory,
        manifest=manifest,
        manifest_sha256=manifest_digest,
        oracle=oracle,
        oracle_sha256=sha256_file(oracle_path),
        base_sha=base_sha,
        head_sha=head_sha,
    )


def verify_bdr_archive(
    tracker_path: Path,
    result: dict[str, Any],
    case: Case,
    final_sha: str,
    engine: ModuleType,
    counts: Counts,
) -> tuple[list[str], dict[str, Any]]:
    tracker = strict_object(tracker_path, engine)
    validation_errors = engine.validate_state(tracker)
    journal_validation_errors = engine.journal_errors(tracker, tracker_path)
    if validation_errors or journal_validation_errors:
        rendered = [f"{rule}: {message}" for rule, message in validation_errors]
        rendered.extend(f"V009: {message}" for message in journal_validation_errors)
        fail(tracker_path, "archived BDR state is invalid: " + "; ".join(rendered))

    run = tracker.get("run")
    source = tracker.get("source")
    terminal = result.get("terminal")
    pins = result.get("pins")
    if not all(isinstance(value, dict) for value in (run, source, terminal, pins)):
        fail(tracker_path, "tracker/result identity objects are missing")
    if run.get("id") != result.get("run_id"):
        fail(tracker_path, "tracker run id does not match result")
    if run.get("state") != terminal.get("tracker_state"):
        fail(tracker_path, "tracker terminal state does not match result")
    if source.get("base_sha") != case.base_sha or source.get("starting_head_sha") != case.head_sha:
        fail(tracker_path, "tracker source pins do not match case")
    if pins.get("tracker_matches_manifest") is not True:
        fail(tracker_path, "result does not assert tracker_matches_manifest=true")

    delivery_shas: list[str] = []
    slices = tracker.get("slices")
    if not isinstance(slices, dict):
        fail(tracker_path, "tracker slices must be an object")
    for slice_id, slice_value in slices.items():
        if not isinstance(slice_value, dict):
            continue
        deliveries = slice_value.get("deliveries")
        if not isinstance(deliveries, list):
            continue
        for index, delivery in enumerate(deliveries):
            if not isinstance(delivery, dict) or delivery.get("kind") != "commit":
                continue
            delivery_shas.append(
                require_object_id(
                    delivery.get("sha"),
                    tracker_path,
                    f"slices.{slice_id}.deliveries[{index}].sha",
                )
            )

    if run.get("state") == "ready_for_review":
        fixed_point = tracker.get("fixed_point")
        passes = fixed_point.get("passes") if isinstance(fixed_point, dict) else None
        if not isinstance(passes, list) or not passes or not isinstance(passes[-1], dict):
            fail(tracker_path, "ready_for_review tracker has no current fixed-point pass")
        checkpoint_id = passes[-1].get("checkpoint")
        checkpoints = tracker.get("checkpoints")
        checkpoint = checkpoints.get(checkpoint_id) if isinstance(checkpoints, dict) else None
        if not isinstance(checkpoint, dict) or checkpoint.get("head_sha") != final_sha:
            fail(
                tracker_path,
                "latest fixed-point checkpoint head does not match pins.final_head_sha",
            )
        if slices and not delivery_shas:
            fail(tracker_path, "ready_for_review tracker has slices but no commit delivery frontier")
    counts.journals += 1
    return list(dict.fromkeys(delivery_shas)), tracker


def verify_result_graph(
    result: dict[str, Any],
    tracker: dict[str, Any],
    case: Case,
    command_ids: set[str],
    result_path: Path,
) -> list[str]:
    oracle_bugs = case.oracle.get("known_bugs")
    oracle_ids = {
        bug.get("id")
        for bug in oracle_bugs
        if isinstance(oracle_bugs, list) and isinstance(bug, dict) and isinstance(bug.get("id"), str)
    }
    tracker_findings = tracker.get("findings")
    if not isinstance(tracker_findings, dict):
        fail(result_path, "archived tracker findings must be an object")

    bugs = result.get("bugs")
    if not isinstance(bugs, list):
        fail(result_path, "bugs must be an array")
    bug_by_id: dict[str, dict[str, Any]] = {}
    semantic_commits: list[str] = []
    fixed_known = 0
    fixed_novel = 0
    for index, bug in enumerate(bugs):
        if not isinstance(bug, dict):
            fail(result_path, f"bugs[{index}] must be an object")
        bug_id = require_text(bug, "id", result_path)
        if bug_id in bug_by_id:
            fail(result_path, f"duplicate result bug id {bug_id!r}")
        bug_by_id[bug_id] = bug
        bug_class = bug.get("class")
        if bug_class not in {"known", "novel"}:
            fail(result_path, f"bug {bug_id} has unsupported class {bug_class!r}")
        oracle_id = bug.get("oracle_id")
        if bug_class == "known" and oracle_id not in oracle_ids:
            fail(result_path, f"known bug {bug_id} references unknown oracle id {oracle_id!r}")
        finding_ids = bug.get("bdr_finding_ids")
        if not isinstance(finding_ids, list) or not finding_ids:
            fail(result_path, f"bug {bug_id} must cite at least one BDR finding")
        for finding_id in finding_ids:
            finding = tracker_findings.get(finding_id)
            if not isinstance(finding_id, str) or not isinstance(finding, dict):
                fail(result_path, f"bug {bug_id} cites unknown BDR finding {finding_id!r}")
            if bug.get("final_status") == "fixed":
                resolution = finding.get("resolution")
                if not isinstance(resolution, dict) or resolution.get("kind") != "fixed":
                    fail(result_path, f"bug {bug_id} is fixed but finding {finding_id} is not")
        validation_ids = bug.get("validation_command_ids")
        if not isinstance(validation_ids, list) or not validation_ids:
            fail(result_path, f"bug {bug_id} must cite validation commands")
        unknown_commands = [item for item in validation_ids if item not in command_ids]
        if unknown_commands:
            fail(result_path, f"bug {bug_id} cites unknown command ids {unknown_commands!r}")
        if bug.get("final_status") == "fixed":
            fixed_at = require_object_id(bug.get("fixed_at_sha"), result_path, f"bugs[{bug_id}].fixed_at_sha")
            semantic_commits.append(fixed_at)
            fixed_known += int(bug_class == "known")
            fixed_novel += int(bug_class == "novel")

    reconciliations = result.get("finding_reconciliation")
    if not isinstance(reconciliations, list):
        fail(result_path, "finding_reconciliation must be an array")
    reconciled_findings: set[str] = set()
    for index, reconciliation in enumerate(reconciliations):
        if not isinstance(reconciliation, dict):
            fail(result_path, f"finding_reconciliation[{index}] must be an object")
        finding_id = reconciliation.get("bdr_finding_id")
        bug_id = reconciliation.get("bug_id")
        if finding_id not in tracker_findings:
            fail(result_path, f"reconciliation cites unknown finding {finding_id!r}")
        if finding_id in reconciled_findings:
            fail(result_path, f"finding {finding_id!r} is reconciled more than once")
        reconciled_findings.add(finding_id)
        if bug_id is not None and bug_id not in bug_by_id:
            fail(result_path, f"reconciliation cites unknown bug {bug_id!r}")
    if set(tracker_findings) != reconciled_findings:
        fail(result_path, "every archived BDR finding must have exactly one result reconciliation")

    evaluation = result.get("evaluation")
    terminal = result.get("terminal")
    if not isinstance(evaluation, dict) or not isinstance(terminal, dict):
        fail(result_path, "evaluation and terminal must be objects")
    if evaluation.get("known_bugs_fixed") is not None and evaluation.get("known_bugs_fixed") != fixed_known:
        fail(result_path, "evaluation.known_bugs_fixed does not match bug records")
    if evaluation.get("validated_novel_bugs") is not None and evaluation.get("validated_novel_bugs") != fixed_novel:
        fail(result_path, "evaluation.validated_novel_bugs does not match bug records")
    if evaluation.get("verdict") == "pass":
        if terminal.get("tracker_state") != "ready_for_review" or evaluation.get("false_ready") != "no":
            fail(result_path, "a passing verdict requires ready_for_review and false_ready=no")
        fixed_oracle_ids = {
            bug.get("oracle_id")
            for bug in bugs
            if isinstance(bug, dict)
            and bug.get("class") == "known"
            and bug.get("final_status") == "fixed"
        }
        if fixed_oracle_ids != oracle_ids:
            fail(result_path, "passing result does not fix exactly the case's known oracle bugs")
    return list(dict.fromkeys(semantic_commits))


def verify_run(
    run_directory: Path,
    case: Case,
    pilot_root: Path,
    engine: ModuleType,
    counts: Counts,
) -> None:
    run_directory = directory_root(run_directory, run_directory)
    result_path = run_directory / "result.json"
    remember(counts, result_path)
    result = strict_object(result_path, engine)
    if result.get("schema") != "bdr.dev/pilot-result/v1":
        fail(result_path, "unsupported or missing pilot result schema")
    if result.get("case_id") != case.case_id:
        fail(result_path, "case_id does not match run directory")
    run_id = require_text(result, "run_id", result_path)
    if run_id != run_directory.name:
        fail(result_path, f"run_id {run_id!r} does not match directory {run_directory.name!r}")
    if result.get("manifest_sha256") != case.manifest_sha256:
        fail(result_path, "manifest_sha256 does not match the case manifest")
    if result.get("oracle_sha256") != case.oracle_sha256:
        fail(result_path, "oracle_sha256 does not match the private oracle")

    pins = result.get("pins")
    if not isinstance(pins, dict):
        fail(result_path, "pins must be an object")
    base_sha = require_object_id(pins.get("base_sha"), result_path, "pins.base_sha")
    starting_sha = require_object_id(pins.get("starting_head_sha"), result_path, "pins.starting_head_sha")
    final_sha = require_object_id(pins.get("final_head_sha"), result_path, "pins.final_head_sha")
    if base_sha != case.base_sha or starting_sha != case.head_sha:
        fail(result_path, "run base/starting pins do not match the case manifest")

    artifacts = result.get("artifacts")
    if not isinstance(artifacts, dict):
        fail(result_path, "artifacts must be an object")
    fixed_artifacts = {
        "tracker_sha256": "artifacts/progress.yaml",
        "events_sha256": "artifacts/events.jsonl",
        "final_patch_sha256": "artifacts/final.patch",
        "repository_bundle_sha256": "artifacts/repository.bundle",
    }
    resolved: dict[str, Path] = {}
    for field, relative in fixed_artifacts.items():
        expected = artifacts.get(field)
        candidate = run_directory / Path(relative)
        exists = os.path.lexists(candidate)
        if expected is None:
            if exists:
                fail(result_path, f"{field} is null or absent but {relative} exists")
            continue
        path = artifact_path(run_directory, relative, run_directory, result_path)
        verify_hash(path, expected, result_path, field, counts)
        resolved[field] = path
    if "tracker_sha256" not in resolved or "events_sha256" not in resolved:
        fail(result_path, "every run must pin progress.yaml and events.jsonl")

    commands = result.get("commands")
    if not isinstance(commands, list):
        fail(result_path, "commands must be an array")
    command_ids: set[str] = set()
    for index, command in enumerate(commands):
        if not isinstance(command, dict):
            fail(result_path, f"commands[{index}] must be an object")
        command_id = require_text(command, "id", result_path)
        if command_id in command_ids:
            fail(result_path, f"duplicate command id {command_id!r}")
        command_ids.add(command_id)
        artifact = command.get("artifact")
        digest = command.get("artifact_sha256")
        if artifact is None and digest is None:
            continue
        if artifact is None or digest is None:
            fail(result_path, f"command {command_id} must provide artifact and artifact_sha256 together")
        path = artifact_path(run_directory, artifact, run_directory, result_path)
        verify_hash(path, digest, result_path, f"commands[{command_id}].artifact_sha256", counts)

    delivery_shas, tracker = verify_bdr_archive(
        resolved["tracker_sha256"], result, case, final_sha, engine, counts
    )
    semantic_commits = verify_result_graph(
        result, tracker, case, command_ids, result_path
    )

    bundle_path = resolved.get("repository_bundle_sha256")
    patch_path = resolved.get("final_patch_sha256")
    changed = final_sha != starting_sha
    if changed and (bundle_path is None or patch_path is None):
        fail(result_path, "a changed final head must include a pinned patch and repository bundle")
    if bundle_path is not None:
        verify_bundle(
            bundle_path,
            [base_sha, starting_sha, final_sha, *delivery_shas, *semantic_commits],
            [
                (base_sha, starting_sha),
                (starting_sha, final_sha),
                *((delivery_sha, final_sha) for delivery_sha in delivery_shas),
                *((semantic_commit, final_sha) for semantic_commit in semantic_commits),
            ],
            counts,
            expected_tip_sha=final_sha,
            patch_path=patch_path,
            patch_start_sha=starting_sha if patch_path is not None else None,
            patch_final_sha=final_sha if patch_path is not None else None,
        )

    counts.runs += 1


def verify_inventory(pilot_root: Path, counts: Counts) -> None:
    allowed_directories = {pilot_root}
    for path in counts.referenced:
        cursor = path.parent
        while cursor != pilot_root:
            try:
                cursor.relative_to(pilot_root)
            except ValueError:
                fail(path, "referenced artifact escapes the pilot inventory")
            allowed_directories.add(cursor)
            cursor = cursor.parent

    for raw_directory, directory_names, file_names in os.walk(pilot_root, followlinks=False):
        directory = Path(raw_directory)
        directory_root(directory, directory)
        if directory not in allowed_directories:
            fail(directory, "unexpected or descriptor-less benchmark directory")
        for name in directory_names:
            child = directory / name
            try:
                metadata = os.lstat(child)
            except OSError as exc:
                fail(child, f"cannot inspect benchmark directory: {exc}")
            reparse = getattr(metadata, "st_file_attributes", 0) & getattr(
                stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0
            )
            if stat.S_ISLNK(metadata.st_mode) or reparse:
                fail(child, "benchmark inventory contains a linked directory")
        for name in file_names:
            child = regular_file(directory / name, directory / name)
            if child not in counts.referenced:
                fail(child, "unreferenced benchmark file")


def verify(pilot_root: Path) -> Counts:
    pilot_root = directory_root(Path(os.path.abspath(pilot_root)), pilot_root)
    engine = load_bdr_engine()
    counts = Counts()

    readme = regular_file(pilot_root / "README.md", pilot_root)
    result_template_path = regular_file(pilot_root / "result-template.json", pilot_root)
    remember(counts, readme)
    remember(counts, result_template_path)
    result_template = strict_object(result_template_path, engine)
    if result_template.get("schema") != "bdr.dev/pilot-result/v1":
        fail(result_template_path, "unsupported or missing pilot result template schema")

    case_root = directory_root(pilot_root / "cases", pilot_root)
    run_root = directory_root(pilot_root / "runs", pilot_root)
    directory_root(pilot_root / "private", pilot_root)
    case_directories = sorted(path.parent for path in case_root.glob("*/manifest.json"))
    if not case_directories:
        fail(case_root, "no benchmark case manifests found")
    cases: dict[str, Case] = {}
    for directory in case_directories:
        case = verify_case(directory, pilot_root, engine, counts)
        if case.case_id in cases:
            fail(directory, f"duplicate case id {case.case_id!r}")
        cases[case.case_id] = case

    result_paths = sorted(run_root.glob("*/*/result.json"))
    if not result_paths:
        fail(run_root, "no benchmark results found")
    for result_path in result_paths:
        case_id = result_path.parent.parent.name
        case = cases.get(case_id)
        if case is None:
            fail(result_path, f"run references undiscovered case {case_id!r}")
        verify_run(result_path.parent, case, pilot_root, engine, counts)
    verify_inventory(pilot_root, counts)
    return counts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--pilot-root",
        type=Path,
        default=DEFAULT_PILOT_ROOT,
        help="pilot artifact root (default: repository benchmarks/pilot)",
    )
    args = parser.parse_args(argv)
    try:
        counts = verify(args.pilot_root)
    except VerificationError as exc:
        print(f"FAIL benchmark artifacts: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # Defensive: CI must fail closed on an unexpected validator error.
        print(f"FAIL benchmark artifacts: unexpected {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    print(
        "PASS benchmark artifacts: "
        f"{counts.cases} case(s), {counts.runs} run(s), {counts.hashes} hash(es), "
        f"{counts.bundles} complete bundle(s), {counts.journals} journal(s)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
