from __future__ import annotations

import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest


BDRV1 = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BDRV1))

from evaluate_run import (  # noqa: E402
    ALLOWED_SLICE_STATUSES,
    RUN_MANIFEST_SCHEMA_VERSION,
    main,
    policy_fingerprint,
    validate_continuation_chain,
    validate_continuation_manifest,
    validate_run,
    validate_slice_statuses,
)
from evaluate_run import load_structured_file  # noqa: E402


STAGE1_TRACKER = BDRV1 / "results/artifacts/qwen38-stage1-slices_progress.yaml"
STAGE2_TRACKER = BDRV1 / "results/artifacts/qwen38-stage2-slices_progress.yaml"


def sample_policy() -> dict:
    return {
        "schema_version": "bdrv1-run-policy/v1",
        "context_window": 131072,
        "max_output_tokens": 8192,
        "observation": {"max_chars": 12000, "head_chars": 6000, "tail_chars": 6000},
        "retry": {"max_attempts": 10},
    }


def digest(character: str) -> str:
    return character * 64


def valid_manifest(
    policy: dict | None = None,
    *,
    ordinal: int = 1,
    previous_manifest_sha256: str | None = None,
) -> dict:
    policy = copy.deepcopy(policy if policy is not None else sample_policy())
    fingerprint = policy_fingerprint(policy)
    summary = {
        "summary": "Measured evidence is preserved.",
        "evidence": ["the completed tool action returned success"],
        "unresolved_judgments": ["the final repair still needs verification"],
        "latest_model_plan": "Verify the repair and finish autonomously.",
    }
    continuation = {
        "continuation_id": f"continuation-{ordinal:06d}",
        "source_checkpoint_id": f"checkpoint-{ordinal + 16:06d}",
        "source_checkpoint_sha256": digest("a"),
        "source_policy_sha256": fingerprint,
        "source_history_sha256": digest("b"),
        "source_workspace_fingerprint": {
            "algorithm": "bdr-workspace-v2",
            "head_sha": "c" * 40,
            "worktree_sha256": digest("d"),
            "content_delta_sha256": digest("e"),
            "git_semantic_sha256": digest("9"),
            "dirty": True,
        },
        "source_completed_turns": 16,
        "source_model_calls": 17,
        "source_tool_calls_completed": 15,
        "summary_sha256": policy_fingerprint(summary),
        "complete_turn_boundary": True,
        "tool_calls_closed": True,
        "workspace_continuity": True,
        "trigger_reason": "estimated_threshold",
        "trigger_estimated_tokens": 100100,
        "trigger_threshold_tokens": 100000,
        "maintenance_reasoning_sha256": None,
    }
    packet = {
        "kind": "repository_probe_continuation",
        "protocol_version": 2,
        "continuity": copy.deepcopy(continuation),
        "preserved": {
            **summary,
            "safe_transcript_tail": [],
            "workspace_manifest_sha256": digest("d"),
            "workspace_category_sha256": {
                "tracker": digest("1"),
                "source": digest("2"),
                "test": digest("3"),
                "evidence": digest("4"),
            },
        },
    }
    continuation["packet_sha256"] = policy_fingerprint(packet)
    manifest = {
        "schema_version": RUN_MANIFEST_SCHEMA_VERSION,
        "run_policy": policy,
        "run_policy_sha256": fingerprint,
        "previous_continuation_manifest_sha256": previous_manifest_sha256,
        "continuation": continuation,
        "packet": packet,
        "maintenance_reasoning": None,
    }
    manifest["manifest_sha256"] = policy_fingerprint(manifest)
    return manifest


class SliceStatusTests(unittest.TestCase):
    def test_allowed_slice_statuses_are_derived_from_frozen_artifacts(self) -> None:
        # Four states come from slices.py's slice renderer; superseded is recorded in the case study.
        self.assertEqual(
            {"pending", "in_progress", "done", "blocked", "superseded"},
            ALLOWED_SLICE_STATUSES,
        )

    def test_archived_stage2_fixed_slice_status_is_rejected(self) -> None:
        tracker = load_structured_file(STAGE2_TRACKER)

        problems = validate_slice_statuses(tracker)

        self.assertTrue(any("unknown slice status 'fixed'" in problem for problem in problems))
        self.assertTrue(any("id=0" in problem for problem in problems))

    def test_archived_stage1_pending_slice_status_is_accepted(self) -> None:
        tracker = load_structured_file(STAGE1_TRACKER)

        self.assertEqual([], validate_slice_statuses(tracker))

    def test_superseded_slice_status_is_accepted(self) -> None:
        tracker = {"slices": [{"id": 5, "status": "superseded"}]}

        self.assertEqual([], validate_slice_statuses(tracker))

    def test_missing_slice_status_is_rejected(self) -> None:
        tracker = {"slices": [{"id": 7}]}

        self.assertEqual(1, len(validate_slice_statuses(tracker)))
        self.assertIn("slice status is missing", validate_slice_statuses(tracker)[0])


class ContinuationManifestTests(unittest.TestCase):
    def test_canonical_policy_fingerprint_is_order_independent(self) -> None:
        first = {"z": 1, "nested": {"b": 2, "a": "é"}}
        second = {"nested": {"a": "é", "b": 2}, "z": 1}

        self.assertEqual(policy_fingerprint(first), policy_fingerprint(second))

    def test_valid_compacted_continuation_is_accepted(self) -> None:
        policy = sample_policy()
        manifest = valid_manifest(policy)

        self.assertEqual(
            [], validate_continuation_manifest(manifest, current_policy=policy)
        )

    def test_external_policy_drift_is_rejected(self) -> None:
        manifest = valid_manifest()
        drifted = sample_policy()
        drifted["max_output_tokens"] = 32768

        problems = validate_continuation_manifest(manifest, current_policy=drifted)

        self.assertTrue(any("drifted" in problem for problem in problems))

    def test_source_policy_drift_is_rejected(self) -> None:
        manifest = valid_manifest()
        manifest["continuation"]["source_policy_sha256"] = digest("0")

        problems = validate_continuation_manifest(manifest)

        self.assertIn(
            "continuation source policy has drifted from the current run policy", problems
        )

    def test_external_expected_policy_fingerprint_drift_is_rejected(self) -> None:
        manifest = valid_manifest()

        problems = validate_continuation_manifest(
            manifest, expected_policy_sha256=digest("0")
        )

        self.assertTrue(any("drifted" in problem for problem in problems))

    def test_manifest_policy_fingerprint_is_recomputed(self) -> None:
        manifest = valid_manifest()
        manifest["run_policy"]["max_output_tokens"] = 4096

        problems = validate_continuation_manifest(manifest)

        self.assertIn(
            "manifest.run_policy_sha256 does not match canonical manifest.run_policy", problems
        )

    def test_manifest_packet_and_self_digest_are_required_and_recomputed(self) -> None:
        manifest = valid_manifest()
        del manifest["packet"]

        problems = validate_continuation_manifest(manifest)

        self.assertTrue(any("wrong top-level fields" in problem for problem in problems))
        self.assertTrue(any("manifest.packet" in problem for problem in problems))
        self.assertTrue(any("manifest_sha256 does not match" in problem for problem in problems))

        manifest = valid_manifest()
        manifest["packet"]["preserved"]["latest_model_plan"] = "tampered plan"
        manifest["continuation"]["packet_sha256"] = policy_fingerprint(
            manifest["packet"]
        )
        manifest["manifest_sha256"] = policy_fingerprint({
            key: value for key, value in manifest.items() if key != "manifest_sha256"
        })

        problems = validate_continuation_manifest(manifest)

        self.assertIn(
            "continuation.summary_sha256 does not match packet preserved summary",
            problems,
        )

    def test_packet_continuity_must_equal_the_manifest_continuation(self) -> None:
        manifest = valid_manifest()
        manifest["packet"]["continuity"]["source_completed_turns"] += 1
        manifest["continuation"]["packet_sha256"] = policy_fingerprint(
            manifest["packet"]
        )
        manifest["manifest_sha256"] = policy_fingerprint({
            key: value for key, value in manifest.items() if key != "manifest_sha256"
        })

        problems = validate_continuation_manifest(manifest)

        self.assertIn(
            "manifest.packet.continuity does not match manifest.continuation",
            problems,
        )

    def test_continuation_chain_binds_second_manifest_to_first(self) -> None:
        first = valid_manifest()
        second = valid_manifest(
            ordinal=2,
            previous_manifest_sha256=first["manifest_sha256"],
        )

        self.assertEqual([], validate_continuation_chain([first, second]))

        second["previous_continuation_manifest_sha256"] = digest("0")
        second["manifest_sha256"] = policy_fingerprint({
            key: value for key, value in second.items() if key != "manifest_sha256"
        })
        problems = validate_continuation_chain([first, second])

        self.assertTrue(any("previous-manifest link is broken" in p for p in problems))

        drifted_policy = sample_policy()
        drifted_policy["max_output_tokens"] = 4096
        second = valid_manifest(
            drifted_policy,
            ordinal=2,
            previous_manifest_sha256=first["manifest_sha256"],
        )

        problems = validate_continuation_chain([first, second])

        self.assertTrue(any("run policy changed inside the chain" in p for p in problems))

    def test_every_compaction_continuity_declaration_is_required(self) -> None:
        for field in (
            "complete_turn_boundary",
            "tool_calls_closed",
            "workspace_continuity",
        ):
            with self.subTest(field=field):
                manifest = valid_manifest()
                del manifest["continuation"][field]

                problems = validate_continuation_manifest(manifest)

                self.assertTrue(any(field in problem for problem in problems))

    def test_policy_binding_is_optional_only_without_a_manifest(self) -> None:
        tracker = {"slices": [{"id": 0, "status": "pending"}]}

        self.assertEqual([], validate_run(tracker))
        problems = validate_run(tracker, current_policy=sample_policy())
        self.assertIn("a policy binding was supplied without a continuation manifest", problems)

    def test_cli_rejects_archived_stage2_tracker(self) -> None:
        with redirect_stdout(io.StringIO()):
            self.assertEqual(1, main(["--tracker", str(STAGE2_TRACKER)]))

    def test_cli_accepts_stage1_with_valid_json_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            manifest_path = Path(temporary_directory) / "continuation-manifest.json"
            manifest_path.write_text(json.dumps(valid_manifest()))
            with redirect_stdout(io.StringIO()):
                self.assertEqual(
                    0,
                    main(
                        [
                            "--tracker",
                            str(STAGE1_TRACKER),
                            "--continuation-manifest",
                            str(manifest_path),
                        ]
                    ),
                )

    def test_cli_requires_the_complete_ordered_continuation_chain(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            first = valid_manifest()
            second = valid_manifest(
                ordinal=2,
                previous_manifest_sha256=first["manifest_sha256"],
            )
            first_path = temporary / "continuation-000001.json"
            second_path = temporary / "continuation-000002.json"
            first_path.write_text(json.dumps(first), encoding="utf-8")
            second_path.write_text(json.dumps(second), encoding="utf-8")

            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, main([
                    "--tracker", str(STAGE1_TRACKER),
                    "--continuation-manifest", str(second_path),
                ]))
                self.assertEqual(0, main([
                    "--tracker", str(STAGE1_TRACKER),
                    "--continuation-manifest", str(first_path),
                    "--continuation-manifest", str(second_path),
                ]))


if __name__ == "__main__":
    unittest.main()
