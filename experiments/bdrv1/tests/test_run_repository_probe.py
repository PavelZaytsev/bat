from __future__ import annotations

import copy
from dataclasses import replace
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from typing import Any, Mapping, Sequence


BDRV1 = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BDRV1))

from evaluate_run import validate_continuation_manifest  # noqa: E402
from run_repository_probe import (  # noqa: E402
    AdapterCapabilities,
    ArtifactInput,
    AssistantTurn,
    BASH_TOOL_SCHEMA,
    CompactionSummary,
    ConfigurationError,
    ContextWindowOverflow,
    DEFAULT_COMPLETION_COMMAND,
    DockerExecProcess,
    IndeterminateModelResponseError,
    IndeterminateToolCallError,
    LocalShellProcess,
    ModelProtocolError,
    MonitoredPath,
    OpenAICompatibleAdapter,
    PolicyDriftError,
    ProbeConfiguration,
    RepositoryProbeRunner,
    RetryableCompactionToolResponseError,
    RetryableCompletedResponseError,
    RetryableModelError,
    RunnerLimits,
    ScriptedModelAdapter,
    ScriptedToolProcess,
    StateIntegrityError,
    ToolAction,
    ToolObservation,
    WORK_SYSTEM_INSTRUCTION,
    WorkspaceDriftError,
    WorkspaceLayout,
    atomic_write_json,
    _example_config,
    canonical_json,
    container_identity_from_config,
    fingerprint,
    main,
    read_json,
    run_forced_compaction_rehearsal,
    runner_from_config,
    sha256_bytes,
    strict_json_loads,
)


class BashToolContractTests(unittest.TestCase):
    def test_command_is_explicitly_a_single_string_even_for_multiline_patches(self) -> None:
        command_schema = BASH_TOOL_SCHEMA["function"]["parameters"]["properties"]["command"]

        self.assertEqual("string", command_schema["type"])
        self.assertIn("multiline", command_schema["description"])
        self.assertIn("never an array", command_schema["description"])
        self.assertIn("command field is one JSON string", WORK_SYSTEM_INSTRUCTION)
        self.assertIn("without an array bracket", WORK_SYSTEM_INSTRUCTION)


def git(cwd: Path, *arguments: str) -> None:
    subprocess.run(
        ["git", "-C", str(cwd), *arguments],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


class FakeStreamingHTTPResponse:
    def __init__(self, lines: Sequence[bytes]) -> None:
        self.lines = list(lines)

    def __enter__(self) -> "FakeStreamingHTTPResponse":
        return self

    def __exit__(self, *arguments: Any) -> None:
        del arguments

    def __iter__(self) -> Any:
        return iter(self.lines)


class ProbeFixture:
    def __init__(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="probe-runner-test-")
        self.base = Path(self.temporary.name)
        self.workspace = self.base / "repo"
        self.inputs = self.base / "inputs"
        self.state_dir = self.base / "state"
        self.source = self.workspace / "src"
        self.tests = self.workspace / "tests"
        self.evidence = self.workspace / "evidence"
        for directory in (self.source, self.tests, self.evidence, self.inputs):
            directory.mkdir(parents=True, exist_ok=True)
        self.tracker = self.workspace / "tracker.data"
        self.other = self.workspace / "other.txt"
        self.methodology = self.inputs / "methodology.txt"
        self.task = self.inputs / "task.txt"
        self.tracker.write_text("opaque state words\n", encoding="utf-8")
        self.other.write_text("outside selected surfaces\n", encoding="utf-8")
        self.methodology.write_text("Use measured repository evidence.\n", encoding="utf-8")
        self.task.write_text("Complete the repository task autonomously.\n", encoding="utf-8")
        (self.source / "subject.txt").write_text("before\n", encoding="utf-8")
        (self.tests / "test_subject.txt").write_text("expected\n", encoding="utf-8")
        (self.evidence / "notes.txt").write_text("none\n", encoding="utf-8")
        (self.workspace / ".gitignore").write_text("cache/\n", encoding="utf-8")
        git(self.workspace, "init", "-q")
        git(self.workspace, "config", "user.email", "probe@example.invalid")
        git(self.workspace, "config", "user.name", "Probe Test")
        git(self.workspace, "add", ".")
        git(self.workspace, "commit", "-qm", "fixture")

    def __enter__(self) -> "ProbeFixture":
        return self

    def __exit__(self, *arguments: Any) -> None:
        del arguments
        self.temporary.cleanup()

    def limits(self, **overrides: Any) -> RunnerLimits:
        values: dict[str, Any] = {
            "context_window_tokens": 4096,
            "proactive_trigger_tokens": 3000,
            "work_output_tokens": 128,
            "compaction_output_tokens": 128,
            "max_logical_calls": 10,
            "max_model_retries": 1,
            "retry_backoff_seconds": 0,
            "max_tool_calls_per_turn": 1,
            "safe_tail_messages": 8,
            "force_compaction_after_turns": (),
        }
        values.update(overrides)
        return RunnerLimits(**values)

    def configuration(self, limits: RunnerLimits | None = None) -> ProbeConfiguration:
        return ProbeConfiguration(
            state_dir=self.state_dir,
            workspace=WorkspaceLayout(
                root=self.workspace,
                monitored_paths=(
                    MonitoredPath("tracker", self.tracker, "tracker"),
                    MonitoredPath("source", self.source, "source"),
                    MonitoredPath("test", self.tests, "tests"),
                    MonitoredPath("evidence", self.evidence, "evidence"),
                ),
                git_semantic_required=True,
                git_untracked_exclude_patterns=("cache/*",),
                respect_git_ignore=False,
            ),
            methodology=(ArtifactInput("methodology", self.methodology),),
            task=ArtifactInput("task", self.task, role="task"),
            limits=limits or self.limits(),
        )


def completion_turn(call_id: str = "complete-call") -> AssistantTurn:
    return AssistantTurn(
        "The repository task is complete.",
        (ToolAction(call_id, "bash", {"command": DEFAULT_COMPLETION_COMMAND}),),
    )


def completion_observation(call_id: str = "complete-call") -> ToolObservation:
    return ToolObservation(call_id, 0, output="COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT\n")


class OverflowAdapter:
    capabilities = AdapterCapabilities()

    def __init__(self) -> None:
        self.generate_calls = 0

    def policy_value(self) -> dict[str, Any]:
        return {"adapter": "overflow-test/v1", "capabilities": self.capabilities.policy_value()}

    def estimate_tokens(self, messages: Sequence[Mapping[str, Any]], *, mode: str) -> int:
        del messages, mode
        return 64

    def generate(
        self, messages: Sequence[Mapping[str, Any]], *, max_tokens: int,
        on_sample_started: Any = None,
    ) -> AssistantTurn:
        del messages, max_tokens
        del on_sample_started
        self.generate_calls += 1
        raise ContextWindowOverflow("provider hard limit")

    def compact(
        self, messages: Sequence[Mapping[str, Any]], *, instruction: str, max_tokens: int,
        on_sample_started: Any = None,
    ) -> CompactionSummary:
        del messages, instruction, max_tokens, on_sample_started
        raise AssertionError("compaction should not run")


class PartialStreamAdapter(OverflowAdapter):
    def policy_value(self) -> dict[str, Any]:
        return {"adapter": "partial-stream-test/v1", "capabilities": self.capabilities.policy_value()}

    def generate(
        self, messages: Sequence[Mapping[str, Any]], *, max_tokens: int,
        on_sample_started: Any = None,
    ) -> AssistantTurn:
        del messages, max_tokens
        self.generate_calls += 1
        if on_sample_started is not None:
            on_sample_started()
        raise IndeterminateModelResponseError("sampled bytes then connection loss")


class FlakyAdapter(OverflowAdapter):
    def policy_value(self) -> dict[str, Any]:
        return {"adapter": "flaky-test/v1", "capabilities": self.capabilities.policy_value()}

    def generate(
        self, messages: Sequence[Mapping[str, Any]], *, max_tokens: int,
        on_sample_started: Any = None,
    ) -> AssistantTurn:
        del messages, max_tokens
        del on_sample_started
        self.generate_calls += 1
        if self.generate_calls == 1:
            raise RetryableModelError("zero-response transport failure")
        return completion_turn()


class CompletedResponseRejectionAdapter(OverflowAdapter):
    def __init__(self, reject_calls: int = 1) -> None:
        super().__init__()
        self.reject_calls = reject_calls
        self.rejection_secrets: list[str] = []

    def policy_value(self) -> dict[str, Any]:
        return {
            "adapter": "completed-response-rejection-test/v1",
            "capabilities": self.capabilities.policy_value(),
        }

    def generate(
        self, messages: Sequence[Mapping[str, Any]], *, max_tokens: int,
        on_sample_started: Any = None,
    ) -> AssistantTurn:
        del messages, max_tokens
        self.generate_calls += 1
        if on_sample_started is not None:
            on_sample_started()
        if self.generate_calls <= self.reject_calls:
            secret = f"PRIVATE_REJECTED_RESPONSE_{self.generate_calls}"
            self.rejection_secrets.append(secret)
            response = {
                "id": f"rejected-{self.generate_calls}",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [{
                    "message": {
                        "content": "",
                        "reasoning_content": "private rejected reasoning " + secret,
                        "tool_calls": [{
                            "id": f"rejected-call-{self.generate_calls}",
                            "type": "function",
                            "function": {
                                "name": "bash",
                                "arguments": '{"command":"' + secret,
                            },
                        }],
                    },
                    "finish_reason": "tool_calls",
                }],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                },
            }
            raise RetryableCompletedResponseError(
                "tool arguments are not strict JSON", response
            )
        return completion_turn()


class CompactionToolRejectionAdapter(OverflowAdapter):
    def __init__(self, reject_compactions: int = 1) -> None:
        super().__init__()
        self.reject_compactions = reject_compactions
        self.compact_calls = 0
        self.rejection_secrets: list[str] = []

    def policy_value(self) -> dict[str, Any]:
        return {
            "adapter": "compaction-tool-rejection-test/v1",
            "capabilities": self.capabilities.policy_value(),
        }

    def generate(
        self, messages: Sequence[Mapping[str, Any]], *, max_tokens: int,
        on_sample_started: Any = None,
    ) -> AssistantTurn:
        del messages, max_tokens
        self.generate_calls += 1
        if on_sample_started is not None:
            on_sample_started()
        if self.generate_calls == 1:
            return AssistantTurn(
                "Inspect the candidate.",
                (ToolAction("inspect-call", "bash", {"command": "inspect"}),),
            )
        if self.generate_calls == 2:
            return completion_turn()
        raise ModelProtocolError("compaction test work script exhausted")

    def compact(
        self, messages: Sequence[Mapping[str, Any]], *, instruction: str,
        max_tokens: int, on_sample_started: Any = None,
    ) -> CompactionSummary:
        del messages, instruction, max_tokens
        self.compact_calls += 1
        if on_sample_started is not None:
            on_sample_started()
        if self.compact_calls <= self.reject_compactions:
            secret = f"PRIVATE_COMPACTION_TOOL_{self.compact_calls}"
            self.rejection_secrets.append(secret)
            response = {
                "id": f"rejected-compaction-{self.compact_calls}",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [{
                    "message": {
                        "content": "",
                        "reasoning_content": "private maintenance reasoning " + secret,
                        "tool_calls": [{
                            "id": f"maintenance-call-{self.compact_calls}",
                            "type": "function",
                            "function": {
                                "name": "bash",
                                "arguments": json.dumps({"command": secret}),
                            },
                        }],
                    },
                    "finish_reason": "tool_calls",
                }],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                },
            }
            raise RetryableCompactionToolResponseError(
                "context-maintenance response attempted a tool call", response
            )
        return CompactionSummary(
            summary="Inspection completed.",
            evidence=("inspect-call returned success",),
            unresolved_judgments=(),
            latest_model_plan="Complete the task.",
        )


class PartialCompactionStreamAdapter(CompactionToolRejectionAdapter):
    def policy_value(self) -> dict[str, Any]:
        return {
            "adapter": "partial-compaction-stream-test/v1",
            "capabilities": self.capabilities.policy_value(),
        }

    def compact(
        self, messages: Sequence[Mapping[str, Any]], *, instruction: str,
        max_tokens: int, on_sample_started: Any = None,
    ) -> CompactionSummary:
        del messages, instruction, max_tokens
        self.compact_calls += 1
        if on_sample_started is not None:
            on_sample_started()
        raise IndeterminateModelResponseError(
            "sampled context-maintenance bytes then connection loss"
        )


class RunnerIntegrationTests(unittest.TestCase):
    def test_forced_compaction_rehearsal_continues_one_logical_run(self) -> None:
        with tempfile.TemporaryDirectory(prefix="forced-rehearsal-test-") as temporary:
            result = run_forced_compaction_rehearsal(Path(temporary))
            state = read_json(Path(result["state_path"]))
            manifest = read_json(Path(result["continuation_manifest_path"]))

        self.assertEqual("completed", result["status"])
        self.assertEqual(
            {"work_calls": 2, "compaction_calls": 1, "tool_calls": 2, "continued_with_packet": True},
            result["rehearsal"],
        )
        self.assertEqual(1, state["counters"]["compactions_completed"])
        self.assertEqual(2, state["counters"]["logical_calls_completed"])
        self.assertEqual(0, state["counters"]["retries"])
        self.assertNotIn("repository_probe_continuation", canonical_json(state["transcript"]).decode())
        self.assertIn("repository_probe_continuation", canonical_json(state["active_messages"]).decode())
        tail = manifest["packet"]["preserved"]["safe_transcript_tail"]
        self.assertEqual(["assistant", "tool"], [message["role"] for message in tail])
        self.assertEqual([], validate_continuation_manifest(manifest, current_policy=manifest["run_policy"]))

    def test_pause_at_compaction_checkpoint_cold_resumes_without_tool_replay(self) -> None:
        class ContextAwareAdapter:
            capabilities = AdapterCapabilities()

            def __init__(self) -> None:
                self.work_requests: list[list[dict[str, Any]]] = []
                self.compaction_requests: list[list[dict[str, Any]]] = []

            def policy_value(self) -> dict[str, Any]:
                return {
                    "adapter": "context-aware-resume-test/v1",
                    "capabilities": self.capabilities.policy_value(),
                }

            def estimate_tokens(
                self, messages: Sequence[Mapping[str, Any]], *, mode: str,
            ) -> int:
                del messages, mode
                return 64

            def generate(
                self, messages: Sequence[Mapping[str, Any]], *, max_tokens: int,
                on_sample_started: Any = None,
            ) -> AssistantTurn:
                del max_tokens
                if on_sample_started is not None:
                    on_sample_started()
                request = [dict(message) for message in messages]
                self.work_requests.append(request)
                if "repository_probe_continuation" in canonical_json(request).decode("utf-8"):
                    return completion_turn()
                return AssistantTurn(
                    "I will inspect the subject before continuing.",
                    (ToolAction("inspect-call", "bash", {"command": "inspect"}),),
                    reasoning="The first evidence boundary must be inspected.",
                )

            def compact(
                self, messages: Sequence[Mapping[str, Any]], *, instruction: str,
                max_tokens: int, on_sample_started: Any = None,
            ) -> CompactionSummary:
                del instruction, max_tokens
                if on_sample_started is not None:
                    on_sample_started()
                self.compaction_requests.append([dict(message) for message in messages])
                return CompactionSummary(
                    "The initial inspection completed.",
                    ("inspect-call returned success",),
                    (),
                    "Continue from the durable packet and complete the task.",
                )

        class StatelessProcess:
            def __init__(self) -> None:
                self.calls: list[ToolAction] = []

            def policy_value(self) -> dict[str, Any]:
                return {"process": "stateless-resume-test/v1"}

            def preflight(self) -> None:
                return None

            def execute(self, action: ToolAction) -> ToolObservation:
                self.calls.append(action)
                if action.call_id == "inspect-call" and action.arguments == {"command": "inspect"}:
                    return ToolObservation("inspect-call", 0, output="observed\n")
                if action.call_id == "complete-call" and action.arguments == {
                    "command": DEFAULT_COMPLETION_COMMAND,
                }:
                    return completion_observation()
                raise AssertionError("unexpected tool action")

        class UnavailableProcess(StatelessProcess):
            def __init__(self) -> None:
                super().__init__()
                self.preflight_calls = 0

            def preflight(self) -> None:
                self.preflight_calls += 1
                raise ModelProtocolError("tool container is temporarily unavailable")

        with ProbeFixture() as fixture:
            configuration = fixture.configuration(
                fixture.limits(force_compaction_after_turns=(1,))
            )
            first_model = ContextAwareAdapter()
            first_process = StatelessProcess()
            first_runner = RepositoryProbeRunner(configuration, first_model, first_process)

            paused = first_runner.run(pause_after_compactions=1)
            paused_state = read_json(first_runner.state_path)

            duplicate_model = ContextAwareAdapter()
            unavailable_process = UnavailableProcess()
            duplicate_runner = RepositoryProbeRunner(
                configuration, duplicate_model, unavailable_process,
            )
            duplicate_pause = duplicate_runner.run(pause_after_compactions=1)

            resumed_model = ContextAwareAdapter()
            resumed_process = StatelessProcess()
            resumed_runner = RepositoryProbeRunner(configuration, resumed_model, resumed_process)
            completed = resumed_runner.run()

        self.assertEqual("running", paused["status"])
        self.assertEqual("compaction_checkpoint", paused["pause"]["kind"])
        self.assertTrue(paused["pause"]["safe_to_resume"])
        self.assertEqual(paused, duplicate_pause)
        self.assertEqual(0, unavailable_process.preflight_calls)
        self.assertEqual(0, len(duplicate_model.work_requests))
        self.assertEqual("compaction", paused_state["checkpoints"][-1]["kind"])
        self.assertIsNone(paused_state["model_inflight"])
        self.assertIsNone(paused_state["tool_inflight"])
        self.assertEqual(["inspect-call"], [call.call_id for call in first_process.calls])
        self.assertEqual("completed", completed["status"])
        self.assertEqual(paused["run_id"], completed["run_id"])
        self.assertEqual(paused["policy_sha256"], completed["policy_sha256"])
        self.assertEqual(["complete-call"], [call.call_id for call in resumed_process.calls])
        self.assertEqual(1, len(resumed_model.work_requests))
        self.assertIn(
            "repository_probe_continuation",
            canonical_json(resumed_model.work_requests[0]).decode("utf-8"),
        )

    def test_pause_after_compactions_requires_positive_integer(self) -> None:
        with ProbeFixture() as fixture:
            runner = RepositoryProbeRunner(
                fixture.configuration(),
                ScriptedModelAdapter([completion_turn()], [], estimated_tokens=64),
                ScriptedToolProcess([completion_observation()]),
            )
            for invalid in (0, -1, True):
                with self.subTest(invalid=invalid), self.assertRaises(ConfigurationError):
                    runner.run(pause_after_compactions=invalid)

    def test_repeated_pause_rejects_workspace_drift_without_model_or_tool_replay(self) -> None:
        with ProbeFixture() as fixture:
            first = AssistantTurn(
                "I will inspect the subject.",
                (ToolAction("inspect-call", "bash", {"command": "inspect"}),),
                reasoning="Establish the first durable evidence boundary.",
            )
            model = ScriptedModelAdapter(
                turns=[first, completion_turn()],
                summaries=[CompactionSummary(
                    "The inspection completed.",
                    ("inspect-call returned success",),
                    (),
                    "Continue from the compacted checkpoint.",
                )],
                estimated_tokens=64,
            )
            process = ScriptedToolProcess([
                ToolObservation("inspect-call", 0, output="observed\n"),
                completion_observation(),
            ])
            runner = RepositoryProbeRunner(
                fixture.configuration(fixture.limits(force_compaction_after_turns=(1,))),
                model,
                process,
            )

            paused = runner.run(pause_after_compactions=1)
            (fixture.source / "subject.txt").write_text("external drift\n", encoding="utf-8")

            with self.assertRaises(WorkspaceDriftError):
                runner.run(pause_after_compactions=1)
            failed_state = read_json(runner.state_path)

        self.assertEqual("running", paused["status"])
        self.assertEqual(1, model.generate_calls)
        self.assertEqual(1, model.compact_calls)
        self.assertEqual(["inspect-call"], [call.call_id for call in process.calls])
        self.assertEqual("failed", failed_state["status"])
        self.assertEqual("workspace_drift", failed_state["failure"]["kind"])

    def test_compaction_preserves_reasoning_evidence_judgments_and_plan(self) -> None:
        with ProbeFixture() as fixture:
            first = AssistantTurn(
                "I inspected the subject.",
                (ToolAction("inspect-call", "bash", {"command": "inspect"}),),
                reasoning="The reader boundary is still uncertain; inspect the chunk transition next.",
            )
            model = ScriptedModelAdapter(
                turns=[first, completion_turn()],
                summaries=[CompactionSummary(
                    "Inspection completed.",
                    ("inspect-call returned success",),
                    ("Chunk-transition ownership is unresolved",),
                    "Probe the transition, then finish if falsification holds.",
                )],
                estimated_tokens=64,
            )
            process = ScriptedToolProcess(
                [ToolObservation("inspect-call", 0, output="observed\n"), completion_observation()]
            )
            runner = RepositoryProbeRunner(
                fixture.configuration(fixture.limits(force_compaction_after_turns=(1,))), model, process
            )

            result = runner.run()
            manifest = read_json(Path(result["continuation_manifest_path"]))

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, model.compact_calls)
        self.assertEqual(
            "The reader boundary is still uncertain; inspect the chunk transition next.",
            model.compaction_requests[0][-2]["reasoning"],
        )
        preserved = manifest["packet"]["preserved"]
        self.assertIn("inspect-call returned success", preserved["evidence"])
        self.assertIn("Chunk-transition ownership is unresolved", preserved["unresolved_judgments"])
        self.assertIn("Probe the transition", preserved["latest_model_plan"])
        self.assertIn("reasoning", canonical_json(preserved["safe_transcript_tail"]).decode())

    def test_hard_overflow_after_below_trigger_estimate_is_runner_failure(self) -> None:
        with ProbeFixture() as fixture:
            model = OverflowAdapter()
            process = ScriptedToolProcess([])
            runner = RepositoryProbeRunner(fixture.configuration(), model, process)

            with self.assertRaises(ContextWindowOverflow):
                runner.run()
            state = read_json(runner.state_path)

        self.assertEqual("hard_context_overflow", state["failure"]["kind"])
        self.assertEqual(0, state["counters"]["compactions_completed"])
        self.assertEqual(0, state["counters"]["retries"])
        self.assertEqual(1, model.generate_calls)

    def test_zero_response_failure_retries_without_new_logical_call(self) -> None:
        with ProbeFixture() as fixture:
            model = FlakyAdapter()
            process = ScriptedToolProcess([completion_observation()])
            result = RepositoryProbeRunner(fixture.configuration(), model, process).run()

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, result["counters"]["logical_calls_started"])
        self.assertEqual(2, result["counters"]["model_attempts_started"])
        self.assertEqual(1, result["counters"]["retries"])

    def test_fully_received_rejection_retries_same_checkpoint_and_retains_private_response(self) -> None:
        with ProbeFixture() as fixture:
            model = CompletedResponseRejectionAdapter()
            process = ScriptedToolProcess([completion_observation()])
            runner = RepositoryProbeRunner(fixture.configuration(), model, process)

            result = runner.run()
            state = read_json(runner.state_path)

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, result["counters"]["logical_calls_started"])
        self.assertEqual(2, result["counters"]["model_attempts_started"])
        self.assertEqual(2, result["counters"]["model_attempts_completed"])
        self.assertEqual(1, result["counters"]["retries"])
        self.assertEqual(1, len(process.calls))
        self.assertEqual(DEFAULT_COMPLETION_COMMAND, process.calls[0].arguments["command"])
        self.assertEqual(1, len(state["private_rejected_model_responses"]))
        rejection = state["private_rejected_model_responses"][0]
        secret = model.rejection_secrets[0]
        self.assertIn(secret, canonical_json(rejection["response"]).decode("utf-8"))
        public_state = canonical_json({
            "events": state["events"],
            "failure": state["failure"],
        }).decode("utf-8")
        self.assertNotIn(secret, public_state)
        self.assertEqual(
            state["checkpoints"][0]["checkpoint_sha256"],
            rejection["source_checkpoint_sha256"],
        )
        terminal = [
            event for event in state["events"]
            if event["kind"] == "model_attempt_finished"
            and event["data"]["outcome"] == "completed_response_retry_scheduled"
        ][0]
        self.assertEqual(rejection["rejection_id"], terminal["data"]["rejection_id"])
        self.assertEqual(
            rejection["protocol_diagnostics"],
            terminal["data"]["protocol_diagnostics"],
        )
        contract = runner.policy["runner"]["completed_response_recovery"]
        self.assertEqual("forbidden", contract["response_repair"])
        self.assertEqual("same-closed-checkpoint", contract["retry_source"])

    def test_fully_received_rejection_exhaustion_fails_closed_without_tool_execution(self) -> None:
        with ProbeFixture() as fixture:
            model = CompletedResponseRejectionAdapter(reject_calls=10)
            process = ScriptedToolProcess([])
            runner = RepositoryProbeRunner(fixture.configuration(), model, process)

            with self.assertRaises(ModelProtocolError):
                runner.run()
            state = read_json(runner.state_path)

        self.assertEqual([], process.calls)
        self.assertEqual(0, state["counters"]["tool_calls_requested"])
        self.assertEqual(0, state["counters"]["tool_calls_completed"])
        self.assertEqual(0, state["counters"]["logical_calls_completed"])
        self.assertEqual(2, state["counters"]["model_attempts_started"])
        self.assertEqual(2, state["counters"]["model_attempts_completed"])
        self.assertEqual(1, state["counters"]["retries"])
        self.assertEqual(2, len(state["private_rejected_model_responses"]))
        self.assertEqual("model_protocol_error", state["failure"]["kind"])
        outcomes = [
            event["data"]["outcome"] for event in state["events"]
            if event["kind"] == "model_attempt_finished"
        ]
        self.assertEqual([
            "completed_response_retry_scheduled",
            "completed_response_retry_exhausted",
        ], outcomes)
        public_state = canonical_json({
            "events": state["events"],
            "failure": state["failure"],
        }).decode("utf-8")
        for secret in model.rejection_secrets:
            self.assertNotIn(secret, public_state)

    def test_restart_resumes_completed_response_retry_from_same_closed_checkpoint(self) -> None:
        with ProbeFixture() as fixture:
            def crash_during_backoff(seconds: float) -> None:
                self.assertEqual(3600, seconds)
                raise RuntimeError("simulated crash during completed-response backoff")

            configuration = replace(
                fixture.configuration(
                    fixture.limits(retry_backoff_seconds=3600)
                ),
                sleep=crash_during_backoff,
            )
            first_model = CompletedResponseRejectionAdapter()
            first_process = ScriptedToolProcess([completion_observation()])
            first_runner = RepositoryProbeRunner(
                configuration, first_model, first_process
            )
            with self.assertRaisesRegex(RuntimeError, "simulated crash"):
                first_runner.run()

            crashed = read_json(first_runner.state_path)
            source_checkpoint_sha256 = crashed["checkpoints"][-1]["checkpoint_sha256"]
            rejection = crashed["private_rejected_model_responses"][0]
            self.assertEqual([], first_process.calls)
            self.assertEqual(source_checkpoint_sha256, rejection["source_checkpoint_sha256"])
            self.assertEqual(
                "completed_response_retry_scheduled",
                crashed["events"][-1]["data"]["outcome"],
            )

            resumed_model = CompletedResponseRejectionAdapter()
            resumed_model.generate_calls = 1
            resumed_backoffs: list[float] = []
            resumed_process = ScriptedToolProcess([completion_observation()])
            resumed_runner = RepositoryProbeRunner(
                replace(configuration, sleep=resumed_backoffs.append),
                resumed_model,
                resumed_process,
            )
            result = resumed_runner.run()
            recovered = read_json(resumed_runner.state_path)

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, first_model.generate_calls)
        self.assertEqual(2, resumed_model.generate_calls)
        self.assertEqual(1, len(resumed_process.calls))
        self.assertEqual(1, len(recovered["private_rejected_model_responses"]))
        self.assertEqual(1, result["counters"]["logical_calls_started"])
        self.assertEqual(2, result["counters"]["model_attempts_started"])
        self.assertEqual(1, result["counters"]["retries"])
        self.assertEqual(1, len(resumed_backoffs))
        self.assertGreater(resumed_backoffs[0], 0)
        self.assertLessEqual(resumed_backoffs[0], 3600)

    def test_private_rejection_corruption_is_refused_on_restart(self) -> None:
        with ProbeFixture() as fixture:
            model = CompletedResponseRejectionAdapter()
            runner = RepositoryProbeRunner(
                fixture.configuration(),
                model,
                ScriptedToolProcess([completion_observation()]),
            )
            runner.run()
            state = read_json(runner.state_path)
            state["private_rejected_model_responses"][0]["response"]["model"] = "tampered"
            atomic_write_json(runner.state_path, state)

            with self.assertRaises(StateIntegrityError):
                RepositoryProbeRunner(
                    fixture.configuration(),
                    CompletedResponseRejectionAdapter(),
                    ScriptedToolProcess([completion_observation()]),
                ).run()

    def test_compaction_tool_rejection_retries_same_checkpoint_without_execution(self) -> None:
        with ProbeFixture() as fixture:
            model = CompactionToolRejectionAdapter()
            process = ScriptedToolProcess([
                ToolObservation("inspect-call", 0, output="inspected\n"),
                completion_observation(),
            ])
            runner = RepositoryProbeRunner(
                fixture.configuration(
                    fixture.limits(force_compaction_after_turns=(1,))
                ),
                model,
                process,
            )

            result = runner.run()
            state = read_json(runner.state_path)

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, result["counters"]["compaction_calls_started"])
        self.assertEqual(1, result["counters"]["compaction_calls_completed"])
        self.assertEqual(1, result["counters"]["compactions_completed"])
        self.assertEqual(1, result["counters"]["retries"])
        self.assertEqual(["inspect", DEFAULT_COMPLETION_COMMAND], [
            action.arguments["command"] for action in process.calls
        ])
        rejection = state["private_rejected_model_responses"][0]
        self.assertEqual("compaction", rejection["logical_kind"])
        self.assertEqual(
            RetryableCompactionToolResponseError.kind,
            rejection["retry_class"],
        )
        self.assertEqual(
            "context-maintenance response attempted a tool call",
            rejection["private_protocol_error"],
        )
        secret = model.rejection_secrets[0]
        self.assertIn(secret, canonical_json(rejection["response"]).decode("utf-8"))
        public_state = canonical_json({
            "events": state["events"], "failure": state["failure"],
        }).decode("utf-8")
        self.assertNotIn(secret, public_state)
        terminal = [
            event for event in state["events"]
            if event["kind"] == "model_attempt_finished"
            and event["data"]["outcome"] == "completed_response_retry_scheduled"
        ][0]
        self.assertEqual(rejection["rejection_id"], terminal["data"]["rejection_id"])
        contract = runner.policy["runner"]["compaction_tool_response_recovery"]
        self.assertEqual("post-parse-tool-call-rejection-only", contract["eligibility"])
        self.assertEqual("forbidden", contract["tool_execution"])
        self.assertEqual("forbidden", contract["other_compaction_protocol_retry"])

    def test_compaction_tool_rejection_exhaustion_fails_closed_without_execution(self) -> None:
        with ProbeFixture() as fixture:
            model = CompactionToolRejectionAdapter(reject_compactions=10)
            process = ScriptedToolProcess([
                ToolObservation("inspect-call", 0, output="inspected\n"),
            ])
            runner = RepositoryProbeRunner(
                fixture.configuration(
                    fixture.limits(force_compaction_after_turns=(1,))
                ),
                model,
                process,
            )

            with self.assertRaises(ModelProtocolError):
                runner.run()
            state = read_json(runner.state_path)

        self.assertEqual(["inspect"], [
            action.arguments["command"] for action in process.calls
        ])
        self.assertEqual(1, state["counters"]["tool_calls_requested"])
        self.assertEqual(1, state["counters"]["tool_calls_completed"])
        self.assertEqual(3, state["counters"]["model_attempts_started"])
        self.assertEqual(1, state["counters"]["retries"])
        self.assertEqual(2, len(state["private_rejected_model_responses"]))
        self.assertTrue(all(
            record["logical_kind"] == "compaction"
            for record in state["private_rejected_model_responses"]
        ))
        outcomes = [
            event["data"]["outcome"] for event in state["events"]
            if event["kind"] == "model_attempt_finished"
        ]
        self.assertEqual([
            "completed",
            "completed_response_retry_scheduled",
            "completed_response_retry_exhausted",
        ], outcomes)

    def test_restart_resumes_compaction_tool_retry_from_same_trigger_and_checkpoint(self) -> None:
        with ProbeFixture() as fixture:
            def crash_during_backoff(seconds: float) -> None:
                self.assertEqual(3600, seconds)
                raise RuntimeError("simulated crash during compaction retry backoff")

            limits = fixture.limits(
                force_compaction_after_turns=(1,), retry_backoff_seconds=3600,
            )
            configuration = replace(
                fixture.configuration(limits), sleep=crash_during_backoff
            )
            observations = [
                ToolObservation("inspect-call", 0, output="inspected\n"),
                completion_observation(),
            ]
            first_model = CompactionToolRejectionAdapter()
            first_process = ScriptedToolProcess(observations)
            first_runner = RepositoryProbeRunner(
                configuration, first_model, first_process
            )
            with self.assertRaisesRegex(RuntimeError, "simulated crash"):
                first_runner.run()

            crashed = read_json(first_runner.state_path)
            rejection = crashed["private_rejected_model_responses"][0]
            trigger_sha256 = crashed["model_inflight"][
                "source_compaction_trigger_sha256"
            ]
            self.assertEqual(["inspect"], [
                action.arguments["command"] for action in first_process.calls
            ])
            self.assertEqual(
                crashed["checkpoints"][-1]["checkpoint_sha256"],
                rejection["source_checkpoint_sha256"],
            )

            resumed_model = CompactionToolRejectionAdapter()
            resumed_model.generate_calls = 1
            resumed_model.compact_calls = 1
            resumed_process = ScriptedToolProcess(observations)
            resumed_process.calls.append(
                ToolAction("inspect-call", "bash", {"command": "inspect"})
            )
            resumed_backoffs: list[float] = []
            resumed_runner = RepositoryProbeRunner(
                replace(configuration, sleep=resumed_backoffs.append),
                resumed_model,
                resumed_process,
            )
            result = resumed_runner.run()
            recovered = read_json(resumed_runner.state_path)

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, result["counters"]["compaction_calls_started"])
        self.assertEqual(1, result["counters"]["compaction_calls_completed"])
        self.assertEqual(1, result["counters"]["retries"])
        self.assertEqual(1, len(recovered["private_rejected_model_responses"]))
        self.assertEqual(1, len(resumed_backoffs))
        self.assertGreater(resumed_backoffs[0], 0)
        self.assertLessEqual(resumed_backoffs[0], 3600)
        self.assertEqual(
            trigger_sha256,
            recovered["private_rejected_model_responses"][0][
                "source_compaction_trigger_sha256"
            ],
        )
        self.assertEqual(
            ["inspect", DEFAULT_COMPLETION_COMMAND],
            [action.arguments["command"] for action in resumed_process.calls],
        )

    def test_compaction_rejection_corruption_is_refused_after_success(self) -> None:
        with ProbeFixture() as fixture:
            configuration = fixture.configuration(
                fixture.limits(force_compaction_after_turns=(1,))
            )
            observations = [
                ToolObservation("inspect-call", 0, output="inspected\n"),
                completion_observation(),
            ]
            runner = RepositoryProbeRunner(
                configuration,
                CompactionToolRejectionAdapter(),
                ScriptedToolProcess(observations),
            )
            runner.run()
            state = read_json(runner.state_path)
            state["private_rejected_model_responses"][0]["response"]["model"] = "tampered"
            atomic_write_json(runner.state_path, state)

            with self.assertRaises(StateIntegrityError):
                RepositoryProbeRunner(
                    configuration,
                    CompactionToolRejectionAdapter(),
                    ScriptedToolProcess(observations),
                ).run()

    def test_partial_compaction_stream_is_not_retried(self) -> None:
        with ProbeFixture() as fixture:
            model = PartialCompactionStreamAdapter()
            process = ScriptedToolProcess([
                ToolObservation("inspect-call", 0, output="inspected\n"),
            ])
            runner = RepositoryProbeRunner(
                fixture.configuration(
                    fixture.limits(force_compaction_after_turns=(1,))
                ),
                model,
                process,
            )

            with self.assertRaises(IndeterminateModelResponseError):
                runner.run()
            state = read_json(runner.state_path)

        self.assertEqual(1, model.compact_calls)
        self.assertEqual(0, state["counters"]["retries"])
        self.assertEqual([], state["private_rejected_model_responses"])
        self.assertEqual(["inspect"], [
            action.arguments["command"] for action in process.calls
        ])

    def test_partial_sampled_stream_is_not_retried(self) -> None:
        with ProbeFixture() as fixture:
            model = PartialStreamAdapter()
            process = ScriptedToolProcess([])
            runner = RepositoryProbeRunner(fixture.configuration(), model, process)

            with self.assertRaises(IndeterminateModelResponseError):
                runner.run()
            state = read_json(runner.state_path)

        self.assertEqual(1, model.generate_calls)
        self.assertEqual(0, state["counters"]["retries"])
        self.assertEqual("indeterminate_model_response", state["failure"]["kind"])
        self.assertEqual([], process.calls)
        self.assertEqual([], state["private_rejected_model_responses"])

    def test_restart_after_persisted_indeterminate_attempt_never_resamples(self) -> None:
        with ProbeFixture() as fixture:
            configuration = fixture.configuration()
            first_model = PartialStreamAdapter()
            first_runner = RepositoryProbeRunner(
                configuration, first_model, ScriptedToolProcess([])
            )
            with (
                patch.object(
                    first_runner,
                    "_record_failure",
                    side_effect=RuntimeError("simulated crash before failure record"),
                ),
                self.assertRaisesRegex(RuntimeError, "simulated crash"),
            ):
                first_runner.run()

            crashed = read_json(first_runner.state_path)
            terminal = [
                event for event in crashed["events"]
                if event["kind"] == "model_attempt_finished"
            ][-1]
            self.assertEqual("running", crashed["status"])
            self.assertIsNone(crashed["failure"])
            self.assertEqual("indeterminate", terminal["data"]["outcome"])
            self.assertTrue(crashed["model_inflight"]["sample_started"])

            resumed_model = PartialStreamAdapter()
            resumed_runner = RepositoryProbeRunner(
                configuration, resumed_model, ScriptedToolProcess([])
            )
            with self.assertRaises(IndeterminateModelResponseError):
                resumed_runner.run()
            recovered = read_json(resumed_runner.state_path)

        self.assertEqual(1, first_model.generate_calls)
        self.assertEqual(0, resumed_model.generate_calls)
        self.assertEqual(1, recovered["counters"]["model_attempts_started"])
        self.assertEqual(1, recovered["counters"]["model_attempts_completed"])
        self.assertEqual("indeterminate_model_response", recovered["failure"]["kind"])

    def test_restart_after_zero_sample_retry_scheduled_resumes_next_attempt(self) -> None:
        with ProbeFixture() as fixture:
            def crash_during_backoff(seconds: float) -> None:
                self.assertEqual(3600, seconds)
                raise RuntimeError("simulated crash during retry backoff")

            configuration = replace(
                fixture.configuration(
                    fixture.limits(retry_backoff_seconds=3600)
                ),
                sleep=crash_during_backoff,
            )
            first_model = FlakyAdapter()
            first_runner = RepositoryProbeRunner(
                configuration,
                first_model,
                ScriptedToolProcess([completion_observation()]),
            )
            with self.assertRaisesRegex(RuntimeError, "simulated crash during retry"):
                first_runner.run()

            crashed = read_json(first_runner.state_path)
            terminal = [
                event for event in crashed["events"]
                if event["kind"] == "model_attempt_finished"
            ][-1]
            self.assertEqual("running", crashed["status"])
            self.assertEqual("retry_scheduled", terminal["data"]["outcome"])
            self.assertFalse(crashed["model_inflight"]["sample_started"])

            resumed_model = FlakyAdapter()
            resumed_model.generate_calls = 1
            resumed_backoffs: list[float] = []
            resumed_runner = RepositoryProbeRunner(
                replace(configuration, sleep=resumed_backoffs.append),
                resumed_model,
                ScriptedToolProcess([completion_observation()]),
            )
            result = resumed_runner.run()

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, first_model.generate_calls)
        self.assertEqual(2, resumed_model.generate_calls)
        self.assertEqual(2, result["counters"]["model_attempts_started"])
        self.assertEqual(2, result["counters"]["model_attempts_completed"])
        self.assertEqual(1, result["counters"]["retries"])
        self.assertEqual(1, len(resumed_backoffs))
        self.assertGreater(resumed_backoffs[0], 0)
        self.assertLessEqual(resumed_backoffs[0], 3600)

    def test_exact_completion_command_is_required_after_its_observation(self) -> None:
        with ProbeFixture() as fixture:
            compound = ToolAction(
                "compound-call", "bash", {"command": DEFAULT_COMPLETION_COMMAND + " && true"}
            )
            model = ScriptedModelAdapter(
                turns=[
                    AssistantTurn("Not exact.", (compound,)),
                    AssistantTurn("Mentioning " + DEFAULT_COMPLETION_COMMAND + " in prose is not terminal."),
                    completion_turn(),
                ],
                summaries=[],
            )
            process = ScriptedToolProcess([
                ToolObservation("compound-call", 0, output="marker\n"),
                completion_observation(),
            ])
            result = RepositoryProbeRunner(fixture.configuration(), model, process).run()

        self.assertEqual("completed", result["status"])
        self.assertEqual(3, result["counters"]["completed_turns"])
        self.assertEqual(2, len(process.calls))


class StreamAndAdapterTests(unittest.TestCase):
    def adapter(self, **overrides: Any) -> OpenAICompatibleAdapter:
        values: dict[str, Any] = {
            "endpoint": "http://127.0.0.1:1/v1/chat/completions",
            "model": "candidate",
            "accepted_response_models": ("candidate",),
            "served_model_revision": "test-revision",
            "served_model_precision": "test-precision",
            "served_context_window_tokens": 4096,
            "server_fingerprint": "test-system-fingerprint",
            "api_key": "EMPTY",
            "stream": True,
        }
        values.update(overrides)
        return OpenAICompatibleAdapter(**values)

    @staticmethod
    def compact_stream(
        content: str,
        reasoning: str | None = None,
        *,
        finish_reason: str = "stop",
    ) -> FakeStreamingHTTPResponse:
        delta: dict[str, Any] = {"content": content}
        if reasoning is not None:
            delta["reasoning_content"] = reasoning
        events = [
            {
                "id": "compact-response-1",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [{"delta": delta, "finish_reason": finish_reason}],
            },
            {
                "id": "compact-response-1",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                },
            },
        ]
        lines = [
            ("data: " + json.dumps(event, separators=(",", ":")) + "\n").encode(
                "utf-8"
            )
            for event in events
        ]
        lines.append(b"data: [DONE]\n")
        return FakeStreamingHTTPResponse(lines)

    @staticmethod
    def complete_tool_stream(arguments: str) -> FakeStreamingHTTPResponse:
        events = [
            {
                "id": "maintenance-tool-response-1",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "maintenance-call-1",
                            "function": {"name": "bash", "arguments": arguments},
                        }],
                    },
                    "finish_reason": "tool_calls",
                }],
            },
            {
                "id": "maintenance-tool-response-1",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                },
            },
        ]
        lines = [
            ("data: " + json.dumps(event, separators=(",", ":")) + "\n").encode(
                "utf-8"
            )
            for event in events
        ]
        lines.append(b"data: [DONE]\n")
        return FakeStreamingHTTPResponse(lines)

    def test_compaction_request_uses_exact_strict_json_schema(self) -> None:
        adapter = self.adapter()
        summary_value = {
            "summary": "Repository evidence is preserved.",
            "evidence": ["The focused test passed."],
            "unresolved_judgments": ["The broad test remains open."],
            "latest_model_plan": "Run the broad test next.",
        }
        captured: dict[str, Any] = {}

        def fake_urlopen(request: Any, **keywords: Any) -> FakeStreamingHTTPResponse:
            captured["request"] = request
            captured["keywords"] = keywords
            return self.compact_stream(json.dumps(summary_value))

        with patch("run_repository_probe.urlopen", side_effect=fake_urlopen):
            summary = adapter.compact(
                [{"role": "system", "content": "continue the same task"}],
                instruction="maintain context",
                max_tokens=512,
            )

        request_body = json.loads(captured["request"].data.decode("utf-8"))
        expected_response_format = {
            "type": "json_schema",
            "json_schema": {
                "name": "repository_probe_context_maintenance",
                "strict": True,
                "schema": {
                    "type": "object",
                    "properties": {
                        "summary": {"type": "string"},
                        "evidence": {
                            "type": "array",
                            "items": {"type": "string"},
                        },
                        "unresolved_judgments": {
                            "type": "array",
                            "items": {"type": "string"},
                        },
                        "latest_model_plan": {"type": "string"},
                    },
                    "required": [
                        "summary",
                        "evidence",
                        "unresolved_judgments",
                        "latest_model_plan",
                    ],
                    "additionalProperties": False,
                },
            },
        }
        self.assertEqual(expected_response_format, request_body["response_format"])
        self.assertNotIn("tools", request_body)
        self.assertNotIn("tool_choice", request_body)
        self.assertNotIn("parallel_tool_calls", request_body)
        self.assertEqual(summary_value, summary.to_json())
        policy_format = adapter.policy_value()["context_maintenance_response_format"]
        self.assertEqual("openai-compatible-json-schema/v1", policy_format["protocol"])
        self.assertEqual(fingerprint(expected_response_format), policy_format[
            "response_format_sha256"
        ])

    def test_complete_compaction_tool_call_is_privately_retryable_but_never_requested(self) -> None:
        secret = "PRIVATE_CONTEXT_MAINTENANCE_TOOL_COMMAND"
        captured: dict[str, Any] = {}

        def fake_urlopen(request: Any, **keywords: Any) -> FakeStreamingHTTPResponse:
            del keywords
            captured["body"] = json.loads(request.data.decode("utf-8"))
            return self.complete_tool_stream(json.dumps({"command": secret}))

        with (
            patch("run_repository_probe.urlopen", side_effect=fake_urlopen),
            self.assertRaises(RetryableCompactionToolResponseError) as raised,
        ):
            self.adapter().compact(
                [{"role": "user", "content": "maintain context"}],
                instruction="compact",
                max_tokens=128,
            )

        error = raised.exception
        self.assertEqual(
            "context-maintenance response attempted a tool call",
            error.private_protocol_error,
        )
        self.assertIn(secret, canonical_json(error.rejected_response).decode("utf-8"))
        self.assertNotIn(secret, str(error))
        self.assertEqual("compaction", error.safe_diagnostics["logical_kind"])
        self.assertNotIn("tools", captured["body"])
        self.assertNotIn("tool_choice", captured["body"])
        self.assertNotIn("parallel_tool_calls", captured["body"])

    def test_compaction_retry_class_refuses_no_tool_or_malformed_tool_responses(self) -> None:
        no_tool_response = {
            "choices": [{
                "message": {"content": "ordinary maintenance"},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            "model": "candidate",
            "system_fingerprint": "test-system-fingerprint",
        }
        with self.assertRaisesRegex(ValueError, "at least one"):
            RetryableCompactionToolResponseError(
                "context-maintenance response attempted a tool call",
                no_tool_response,
            )

        with (
            patch(
                "run_repository_probe.urlopen",
                return_value=self.complete_tool_stream('{"command":'),
            ),
            self.assertRaises(ModelProtocolError) as raised,
        ):
            self.adapter().compact(
                [{"role": "user", "content": "maintain"}],
                instruction="compact",
                max_tokens=128,
            )
        self.assertIs(type(raised.exception), ModelProtocolError)

    def test_compaction_identity_failure_is_not_retryable(self) -> None:
        response = self.compact_stream(json.dumps({
            "summary": "preserved",
            "evidence": [],
            "unresolved_judgments": [],
            "latest_model_plan": "continue",
        }))
        wrong_identity_lines = [
            line.replace(b'"model":"candidate"', b'"model":"unregistered"')
            for line in response.lines
        ]
        with (
            patch(
                "run_repository_probe.urlopen",
                return_value=FakeStreamingHTTPResponse(wrong_identity_lines),
            ),
            self.assertRaises(ModelProtocolError) as raised,
        ):
            self.adapter().compact(
                [{"role": "user", "content": "maintain"}],
                instruction="compact",
                max_tokens=128,
            )
        self.assertIs(type(raised.exception), ModelProtocolError)

    def test_named_bash_tool_choice_is_work_only_and_policy_bound(self) -> None:
        adapter = self.adapter(work_tool_choice="bash")
        captured: list[dict[str, Any]] = []
        summary_value = {
            "summary": "preserved",
            "evidence": [],
            "unresolved_judgments": [],
            "latest_model_plan": "continue",
        }
        work_events = [
            {
                "id": "work-response-1",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call-1",
                            "function": {
                                "name": "bash",
                                "arguments": json.dumps({"command": "pwd"}),
                            },
                        }],
                    },
                    "finish_reason": "tool_calls",
                }],
            },
            {
                "id": "work-response-1",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                },
            },
        ]
        work_lines = [
            ("data: " + json.dumps(event, separators=(",", ":")) + "\n").encode(
                "utf-8"
            )
            for event in work_events
        ]
        work_lines.append(b"data: [DONE]\n")
        responses = iter([
            FakeStreamingHTTPResponse(work_lines),
            self.compact_stream(json.dumps(summary_value)),
        ])

        def fake_urlopen(request: Any, **keywords: Any) -> FakeStreamingHTTPResponse:
            captured.append({"request": request, "keywords": keywords})
            return next(responses)

        with patch("run_repository_probe.urlopen", side_effect=fake_urlopen):
            adapter.generate([{"role": "user", "content": "work"}], max_tokens=128)
            adapter.compact(
                [{"role": "user", "content": "maintain"}],
                instruction="compact",
                max_tokens=128,
            )

        work_body = json.loads(captured[0]["request"].data.decode("utf-8"))
        maintenance_body = json.loads(captured[1]["request"].data.decode("utf-8"))
        named_choice = {"type": "function", "function": {"name": "bash"}}
        self.assertEqual(named_choice, work_body["tool_choice"])
        self.assertEqual([BASH_TOOL_SCHEMA], work_body["tools"])
        self.assertFalse(work_body["parallel_tool_calls"])
        self.assertNotIn("tools", maintenance_body)
        self.assertNotIn("tool_choice", maintenance_body)
        self.assertNotIn("parallel_tool_calls", maintenance_body)
        self.assertEqual(named_choice, adapter.policy_value()["work_tool_choice"])
        self.assertEqual("auto", self.adapter().policy_value()["work_tool_choice"])
        self.assertNotEqual(
            fingerprint(adapter.policy_value()),
            fingerprint(self.adapter().policy_value()),
        )
        estimation_messages = [{"role": "user", "content": "same prompt"}]
        default_adapter = self.adapter()
        for mode in ("work", "compaction"):
            self.assertEqual(
                default_adapter.estimate_tokens(estimation_messages, mode=mode),
                adapter.estimate_tokens(estimation_messages, mode=mode),
            )

        with patch(
            "run_repository_probe.urlopen",
            return_value=self.compact_stream("no tool call"),
        ), self.assertRaises(RetryableCompletedResponseError):
            adapter.generate([{"role": "user", "content": "work"}], max_tokens=128)

    def test_complete_malformed_tool_json_is_classified_for_private_pre_tool_recovery(self) -> None:
        secret = "FULL_REJECTED_TOOL_ARGUMENT_SECRET"
        events = [
            {
                "id": "work-response-malformed",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [{
                    "delta": {
                        "tool_calls": [{
                            "index": 0,
                            "id": "call-malformed",
                            "function": {
                                "name": "bash",
                                "arguments": '{"command":"' + secret,
                            },
                        }],
                    },
                    "finish_reason": "tool_calls",
                }],
            },
            {
                "id": "work-response-malformed",
                "model": "candidate",
                "system_fingerprint": "test-system-fingerprint",
                "choices": [],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 20,
                    "total_tokens": 120,
                },
            },
        ]
        lines = [
            ("data: " + json.dumps(event, separators=(",", ":")) + "\n").encode(
                "utf-8"
            )
            for event in events
        ] + [b"data: [DONE]\n"]

        with (
            patch(
                "run_repository_probe.urlopen",
                return_value=FakeStreamingHTTPResponse(lines),
            ),
            self.assertRaises(RetryableCompletedResponseError) as raised,
        ):
            self.adapter().generate(
                [{"role": "user", "content": "work"}], max_tokens=128
            )

        error = raised.exception
        response_bytes = canonical_json(error.rejected_response)
        self.assertIn(secret, response_bytes.decode("utf-8"))
        self.assertNotIn(secret, str(error))
        self.assertEqual(
            sha256_bytes(response_bytes),
            error.safe_diagnostics["response_sha256"],
        )
        argument_diagnostics = error.safe_diagnostics["tool_calls"][0]["arguments"]
        self.assertEqual(
            sha256_bytes(('{"command":"' + secret).encode("utf-8")),
            argument_diagnostics["sha256"],
        )

    def test_response_identity_failure_is_not_a_completed_response_retry(self) -> None:
        response = self.compact_stream("ordinary work response")
        wrong_identity_lines = [
            line.replace(b'"model":"candidate"', b'"model":"unregistered"')
            for line in response.lines
        ]

        with (
            patch(
                "run_repository_probe.urlopen",
                return_value=FakeStreamingHTTPResponse(wrong_identity_lines),
            ),
            self.assertRaises(ModelProtocolError) as raised,
        ):
            self.adapter().generate(
                [{"role": "user", "content": "work"}], max_tokens=128
            )

        self.assertIs(type(raised.exception), ModelProtocolError)

    def test_work_tool_choice_rejects_unregistered_values(self) -> None:
        for value in (None, "none", "required", "bash ", {"name": "bash"}):
            with self.subTest(value=value), self.assertRaises(ConfigurationError):
                self.adapter(work_tool_choice=value)

    def test_context_maintenance_extra_body_is_isolated_and_policy_bound(self) -> None:
        maintenance_extra_body = {
            "chat_template_kwargs": {"enable_thinking": False}
        }
        adapter = self.adapter(
            reasoning_effort="medium",
            extra_body={"top_k": 20},
            context_maintenance_extra_body=maintenance_extra_body,
        )
        summary_value = {
            "summary": "preserved",
            "evidence": [],
            "unresolved_judgments": [],
            "latest_model_plan": "continue",
        }
        responses = iter([
            self.compact_stream("ordinary work response"),
            self.compact_stream(json.dumps(summary_value)),
        ])
        request_bodies: list[dict[str, Any]] = []

        def fake_urlopen(request: Any, **keywords: Any) -> FakeStreamingHTTPResponse:
            del keywords
            request_bodies.append(json.loads(request.data.decode("utf-8")))
            return next(responses)

        with patch("run_repository_probe.urlopen", side_effect=fake_urlopen):
            adapter.generate(
                [{"role": "user", "content": "work"}], max_tokens=128
            )
            adapter.compact(
                [{"role": "user", "content": "maintain"}],
                instruction="compact",
                max_tokens=128,
            )

        work_body, maintenance_body = request_bodies
        self.assertEqual("medium", work_body["reasoning_effort"])
        self.assertNotIn("reasoning_effort", maintenance_body)
        self.assertNotIn("chat_template_kwargs", work_body)
        self.assertEqual(
            {"enable_thinking": False},
            maintenance_body["chat_template_kwargs"],
        )
        self.assertEqual(20, work_body["top_k"])
        self.assertEqual(20, maintenance_body["top_k"])

        policy = adapter.policy_value()
        self.assertEqual("medium", policy["work_reasoning_effort"])
        self.assertIsNone(policy["context_maintenance_reasoning_effort"])
        self.assertEqual(
            ["chat_template_kwargs"],
            policy["context_maintenance_extra_body_keys"],
        )
        self.assertEqual(
            fingerprint(maintenance_extra_body),
            policy["context_maintenance_extra_body_sha256"],
        )

        invalid_configurations = (
            {"context_maintenance_extra_body": {"response_format": {}}},
            {"context_maintenance_extra_body": {"reasoning_effort": "low"}},
            {
                "extra_body": {"chat_template_kwargs": {"work": True}},
                "context_maintenance_extra_body": maintenance_extra_body,
            },
        )
        for overrides in invalid_configurations:
            with self.subTest(overrides=overrides), self.assertRaises(ConfigurationError):
                self.adapter(**overrides)

    def test_wrong_compaction_keys_persist_only_structural_diagnostics(self) -> None:
        wrong_value = {
            "summary": "preserved",
            "evidence": [],
            "PairKey": "wrong protocol field",
            "latest_model_plan": "continue",
        }
        content = json.dumps(wrong_value, separators=(",", ":"))
        with ProbeFixture() as fixture:
            runner = RepositoryProbeRunner(
                fixture.configuration(
                    fixture.limits(force_compaction_after_turns=(0,))
                ),
                self.adapter(),
                ScriptedToolProcess([]),
            )
            with (
                patch(
                    "run_repository_probe.urlopen",
                    return_value=self.compact_stream(content),
                ),
                self.assertRaises(ModelProtocolError),
            ):
                runner.run()
            state = read_json(runner.state_path)

        diagnostics = state["failure"]["diagnostics"]
        self.assertEqual("object", diagnostics["response_top_level_type"])
        self.assertEqual(
            ["evidence", "latest_model_plan", "summary"],
            diagnostics["observed_expected_fields"],
        )
        self.assertEqual(
            ["unresolved_judgments"], diagnostics["missing_expected_fields"]
        )
        self.assertEqual(4, diagnostics["observed_field_count"])
        self.assertEqual(1, diagnostics["unexpected_field_count"])
        self.assertEqual(
            fingerprint(["PairKey"]), diagnostics["unexpected_fields_sha256"]
        )
        self.assertEqual(
            sha256_bytes(content.encode("utf-8")),
            diagnostics["response_content_sha256"],
        )
        self.assertEqual(
            len(content.encode("utf-8")),
            diagnostics["response_content_length_bytes"],
        )
        terminal = [
            event for event in state["events"]
            if event["kind"] == "model_attempt_finished"
        ][-1]
        self.assertEqual(diagnostics, terminal["data"]["protocol_diagnostics"])
        self.assertEqual(0, state["counters"]["retries"])
        self.assertEqual([], state["private_rejected_model_responses"])

    def test_protocol_failure_diagnostics_redact_content_reasoning_keys_and_api_key(self) -> None:
        provider_secret = "PROVIDER_API_KEY_SECRET_CANARY"
        content_secret = "RAW_CONTENT_SECRET_CANARY"
        reasoning_secret = "RAW_REASONING_SECRET_CANARY"
        secret_key = "IDENTIFIER_SHAPED_SECRET_KEY_CANARY"
        wrong_value = {
            "summary": content_secret,
            "evidence": [],
            secret_key: content_secret,
            "latest_model_plan": content_secret,
        }
        content = json.dumps(wrong_value, separators=(",", ":"))
        with ProbeFixture() as fixture:
            runner = RepositoryProbeRunner(
                fixture.configuration(
                    fixture.limits(force_compaction_after_turns=(0,))
                ),
                self.adapter(
                    api_key=provider_secret,
                    api_key_source="environment:MODEL_PROVIDER_KEY",
                ),
                ScriptedToolProcess([]),
            )
            with (
                patch(
                    "run_repository_probe.urlopen",
                    return_value=self.compact_stream(content, reasoning_secret),
                ),
                self.assertRaises(ModelProtocolError) as raised,
            ):
                runner.run()
            persisted = "\n".join(
                path.read_text(encoding="utf-8")
                for path in sorted(fixture.state_dir.rglob("*.json"))
            )

        for secret in (
            provider_secret, content_secret, reasoning_secret, secret_key, content,
        ):
            with self.subTest(secret=secret):
                self.assertNotIn(secret, persisted)
                self.assertNotIn(secret, str(raised.exception))

    def test_empty_compaction_content_persists_safe_finish_and_reasoning_diagnostics(self) -> None:
        reasoning_secret = "PRIVATE_REASONING_SECRET_CANARY"
        reasoning = json.dumps({
            "summary": reasoning_secret,
            "evidence": [reasoning_secret],
            "unresolved_judgments": [],
            "latest_model_plan": reasoning_secret,
        }, separators=(",", ":"))
        with ProbeFixture() as fixture:
            runner = RepositoryProbeRunner(
                fixture.configuration(
                    fixture.limits(force_compaction_after_turns=(0,))
                ),
                self.adapter(),
                ScriptedToolProcess([]),
            )
            with (
                patch(
                    "run_repository_probe.urlopen",
                    return_value=self.compact_stream(
                        "", reasoning, finish_reason="length"
                    ),
                ),
                self.assertRaises(ModelProtocolError),
            ):
                runner.run()
            state = read_json(runner.state_path)
            persisted = runner.state_path.read_text(encoding="utf-8")

        diagnostics = state["failure"]["diagnostics"]
        self.assertEqual("length", diagnostics["finish_reason"])
        self.assertEqual(0, diagnostics["response_content_length_bytes"])
        self.assertEqual(
            sha256_bytes(b""), diagnostics["response_content_sha256"]
        )
        self.assertTrue(diagnostics["response_reasoning_present"])
        self.assertEqual(
            len(reasoning.encode("utf-8")),
            diagnostics["response_reasoning_length_bytes"],
        )
        self.assertEqual(
            sha256_bytes(reasoning.encode("utf-8")),
            diagnostics["response_reasoning_sha256"],
        )
        self.assertEqual("candidate", diagnostics["response_model"])
        self.assertEqual(
            "test-system-fingerprint",
            diagnostics["response_system_fingerprint"],
        )
        self.assertEqual({
            "prompt_tokens": 100,
            "completion_tokens": 20,
            "total_tokens": 120,
        }, diagnostics["usage"])
        self.assertEqual("unparsed", diagnostics["response_top_level_type"])
        self.assertEqual(0, state["counters"]["compactions_completed"])
        self.assertNotIn(reasoning, persisted)
        self.assertNotIn(reasoning_secret, persisted)

    def test_fragmented_streamed_tool_id_name_and_arguments_are_reassembled(self) -> None:
        events = [
            b'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"ba","arguments":"{\\\"com"}}]},"finish_reason":null}]}\n',
            b'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"_1","function":{"name":"sh","arguments":"mand\\\":\\\"pwd\\\"}"}}]},"finish_reason":"tool_calls"}]}\n',
            b'data: [DONE]\n',
        ]

        result = self.adapter()._read_stream(events)
        turn = self.adapter()._assistant_turn(result)

        self.assertEqual(1, len(turn.tool_calls))
        self.assertEqual("call_1", turn.tool_calls[0].call_id)
        self.assertEqual("bash", turn.tool_calls[0].name)
        self.assertEqual({"command": "pwd"}, turn.tool_calls[0].arguments)

    def test_eof_after_sampled_chunk_without_terminal_marker_fails_closed(self) -> None:
        events = [b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n']

        with self.assertRaises(IndeterminateModelResponseError):
            self.adapter()._read_stream(events)

    def test_io_failure_after_sampled_chunk_fails_closed(self) -> None:
        def events() -> Any:
            yield b'data: {"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}\n'
            raise OSError("connection reset")

        with self.assertRaises(IndeterminateModelResponseError):
            self.adapter()._read_stream(events())

    def test_io_failure_before_sampled_chunk_is_retryable(self) -> None:
        def events() -> Any:
            if False:
                yield b""
            raise OSError("connection reset")

        with self.assertRaises(RetryableModelError):
            self.adapter()._read_stream(events())

    def test_reasoning_is_replayed_using_preregistered_server_field(self) -> None:
        adapter = self.adapter(replay_reasoning_field="reasoning_content")
        normalized = {
            "role": "assistant",
            "content": "calling a tool",
            "reasoning": "evidence and next plan",
            "tool_calls": [ToolAction("id-1", "bash", {"command": "pwd"}).to_json()],
        }

        message = adapter._openai_message(normalized)

        self.assertEqual("evidence and next plan", message["reasoning_content"])
        self.assertEqual("reasoning_content", adapter.policy_value()["replay_reasoning_field"])

    def test_local_shell_is_an_executable_process_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            process = LocalShellProcess(
                cwd=root, timeout_seconds=10, trusted_disposable_host=True
            )
            observation = process.execute(ToolAction(
                "shell-call", "bash", {"command": "printf observed > result.txt"}
            ))

            self.assertEqual(0, observation.returncode)
            self.assertEqual("observed", (root / "result.txt").read_text())
            bounded = LocalShellProcess(
                cwd=root, max_observation_chars=12000, trusted_disposable_host=True
            )._observation(
                "bounded", 0, "x" * 13000, None
            )
            self.assertEqual(6000, len(bounded.output_head or ""))
            self.assertEqual(6000, len(bounded.output_tail or ""))
            self.assertEqual(1000, bounded.elided_chars)


class DriftAndCrashSafetyTests(unittest.TestCase):
    def initialized_runner(
        self, fixture: ProbeFixture,
    ) -> tuple[RepositoryProbeRunner, ScriptedModelAdapter, ScriptedToolProcess]:
        model = ScriptedModelAdapter([completion_turn()], [], estimated_tokens=64)
        process = ScriptedToolProcess([completion_observation()])
        runner = RepositoryProbeRunner(fixture.configuration(), model, process)
        runner._load_or_initialize()
        return runner, model, process

    def test_whole_git_identity_detects_new_change_when_repo_was_already_dirty(self) -> None:
        with ProbeFixture() as fixture:
            fixture.other.write_text("already dirty\n", encoding="utf-8")
            runner, model, process = self.initialized_runner(fixture)
            fixture.other.write_text("different dirty content\n", encoding="utf-8")

            with self.assertRaises(WorkspaceDriftError):
                runner.run()

        self.assertEqual(0, model.generate_calls)
        self.assertEqual([], process.calls)

    def test_whole_git_identity_detects_staged_and_untracked_byte_changes(self) -> None:
        for change_kind in ("staged", "untracked"):
            with self.subTest(change_kind=change_kind), ProbeFixture() as fixture:
                runner, model, process = self.initialized_runner(fixture)
                if change_kind == "staged":
                    fixture.other.write_text("staged mutation\n", encoding="utf-8")
                    git(fixture.workspace, "add", "other.txt")
                else:
                    candidate = fixture.workspace / "untracked.txt"
                    candidate.write_text("first bytes\n", encoding="utf-8")

                with self.assertRaises(WorkspaceDriftError):
                    runner.run()

                self.assertEqual(0, model.generate_calls)
                self.assertEqual([], process.calls)

    def test_existing_untracked_file_content_change_is_detected(self) -> None:
        with ProbeFixture() as fixture:
            untracked = fixture.workspace / "untracked.txt"
            untracked.write_text("first bytes\n", encoding="utf-8")
            runner, model, process = self.initialized_runner(fixture)
            untracked.write_text("second bytes, same status entry\n", encoding="utf-8")

            with self.assertRaises(WorkspaceDriftError):
                runner.run()

        self.assertEqual(0, model.generate_calls)
        self.assertEqual([], process.calls)

    def test_git_stat_refresh_and_ignored_cache_do_not_drift(self) -> None:
        with ProbeFixture() as fixture:
            runner, _, _ = self.initialized_runner(fixture)
            git(fixture.workspace, "status", "--short")
            cache = fixture.workspace / "cache"
            cache.mkdir()
            (cache / "artifact.bin").write_bytes(b"disposable")

            state = runner._load_or_initialize()
            runner._verify_workspace(state)

    def test_policy_and_frozen_input_drift_are_refused_before_model(self) -> None:
        with ProbeFixture() as fixture:
            runner, model, process = self.initialized_runner(fixture)
            fixture.methodology.write_text("changed methodology\n", encoding="utf-8")

            with self.assertRaises(PolicyDriftError):
                runner.run()

        self.assertEqual(0, model.generate_calls)
        self.assertEqual([], process.calls)

    def test_pending_tool_intent_is_indeterminate_and_never_replayed(self) -> None:
        with ProbeFixture() as fixture:
            runner, _, _ = self.initialized_runner(fixture)
            state = read_json(runner.state_path)
            action = ToolAction("pending-call", "bash", {"command": "mutate"})
            assistant = AssistantTurn("I will mutate.", (action,)).to_message()
            state["transcript"].append(assistant)
            state["active_messages"].append(assistant)
            state["counters"].update({
                "logical_calls_started": 1,
                "logical_calls_completed": 1,
                "model_attempts_started": 1,
                "model_attempts_completed": 1,
                "tool_calls_requested": 1,
            })
            state["tool_inflight"] = {
                "status": "pending",
                "source_checkpoint_sha256": state["checkpoints"][-1]["checkpoint_sha256"],
                "actions": [action.to_json()],
                "observations": [],
                "pre_workspace_fingerprint": state["expected_workspace_fingerprint"],
            }
            runner._persist(state)
            fresh_model = ScriptedModelAdapter([completion_turn()], [], estimated_tokens=64)
            fresh_process = ScriptedToolProcess([completion_observation()])
            resumed = RepositoryProbeRunner(fixture.configuration(), fresh_model, fresh_process)

            with self.assertRaises(IndeterminateToolCallError):
                resumed.run()

        self.assertEqual(0, fresh_model.generate_calls)
        self.assertEqual([], fresh_process.calls)

    def test_complete_recorded_observation_finalizes_without_reexecution(self) -> None:
        with ProbeFixture() as fixture:
            runner, _, _ = self.initialized_runner(fixture)
            state = read_json(runner.state_path)
            action = ToolAction("recorded-call", "bash", {"command": "inspect"})
            observation = ToolObservation("recorded-call", 0, output="done\n")
            assistant = AssistantTurn("Inspection complete.", (action,)).to_message()
            state["transcript"].extend([assistant, {
                "role": "tool",
                "tool_call_id": "recorded-call",
                "content": canonical_json({
                    "returncode": 0, "output": "done\n", "elided_chars": 0,
                }).decode(),
            }])
            state["active_messages"] = list(state["transcript"])
            state["counters"].update({
                "logical_calls_started": 1,
                "logical_calls_completed": 1,
                "model_attempts_started": 1,
                "model_attempts_completed": 1,
                "tool_calls_requested": 1,
                "tool_calls_completed": 1,
                "completed_turns": 1,
            })
            state["tool_inflight"] = {
                "status": "observations_recorded",
                "source_checkpoint_sha256": state["checkpoints"][-1]["checkpoint_sha256"],
                "actions": [action.to_json()],
                "observations": [observation.to_json()],
                "pre_workspace_fingerprint": state["expected_workspace_fingerprint"],
                "post_workspace_fingerprint": state["expected_workspace_fingerprint"],
                "post_workspace_manifest": state["workspace_manifest"],
            }
            runner._persist(state)
            fresh_model = ScriptedModelAdapter([completion_turn()], [], estimated_tokens=64)
            fresh_process = ScriptedToolProcess([completion_observation()])
            resumed = RepositoryProbeRunner(fixture.configuration(), fresh_model, fresh_process)
            loaded = resumed._load_or_initialize()

            resumed._recover_transient_state(loaded)

            recovered = read_json(resumed.state_path)
        self.assertIsNone(recovered["tool_inflight"])
        self.assertEqual("recovered-observation", recovered["checkpoints"][-1]["kind"])
        self.assertEqual([], fresh_process.calls)
        self.assertEqual(0, fresh_model.generate_calls)

    def test_checkpoint_hash_rejects_transcript_tampering(self) -> None:
        with ProbeFixture() as fixture:
            runner, _, _ = self.initialized_runner(fixture)
            state = read_json(runner.state_path)
            state["transcript"][0]["content"] = "tampered"
            state["history_sha256"] = __import__("hashlib").sha256(
                canonical_json(state["transcript"])
            ).hexdigest()
            atomic_write_json(runner.state_path, state)
            fresh_model = ScriptedModelAdapter([completion_turn()], [], estimated_tokens=64)
            fresh_process = ScriptedToolProcess([completion_observation()])

            with self.assertRaises(StateIntegrityError):
                RepositoryProbeRunner(fixture.configuration(), fresh_model, fresh_process).run()


class RecordingDockerExecutor:
    def __init__(self, inspected: dict[str, Any]) -> None:
        self.inspected = inspected
        self.calls: list[tuple[list[str], dict[str, Any]]] = []

    def __call__(self, arguments: Sequence[str], **keywords: Any) -> subprocess.CompletedProcess[bytes]:
        command = list(arguments)
        self.calls.append((command, dict(keywords)))
        if len(command) > 1 and command[1] == "inspect":
            output = json.dumps([self.inspected]).encode("utf-8")
        else:
            output = b"container command output\n"
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr=b"")


class DockerExecSecurityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="docker-exec-test-")
        self.base = Path(self.temporary.name)
        self.repo = self.base / "repo"
        self.bdr = self.base / "bdr"
        self.scratch = self.base / "scratch"
        self.cache = self.base / "m2"
        for directory in (self.repo, self.bdr, self.scratch, self.cache):
            directory.mkdir()
        self.tracker = self.base / "slices_progress.yaml"
        self.tracker.write_text("opaque tracker\n", encoding="utf-8")
        self.mounts = [
            self.mount(self.repo, "/workspace/repo", True, "directory"),
            self.mount(self.bdr, "/workspace/bdr", False, "directory"),
            self.mount(
                self.tracker,
                "/workspace/bdr/slices_progress.yaml",
                True,
                "regular_file",
            ),
            self.mount(self.scratch, "/workspace/scratch", True, "directory"),
            self.mount(
                self.cache, "/workspace/dependency-cache", True, "directory"
            ),
        ]
        self.inspected = {
            "Id": "container-id-123",
            "Image": "sha256:image-123",
            "State": {"Running": True},
            "Config": {
                "User": "1000:1000",
                "Env": ["PATH=/usr/bin:/bin", "PAGER=cat"],
            },
            "HostConfig": {
                "NetworkMode": "none",
                "Privileged": False,
                "CapAdd": None,
                "CapDrop": ["ALL"],
                "Devices": None,
                "PidMode": "",
                "IpcMode": "",
                "ReadonlyRootfs": True,
                "SecurityOpt": ["no-new-privileges"],
            },
            "NetworkSettings": {"Networks": {}},
            "Mounts": [
                {
                    "Type": record["type"],
                    "Source": str(source),
                    "Destination": record["destination"],
                    "RW": record["rw"],
                    "Propagation": record["propagation"],
                }
                for source, record in zip(
                    (self.repo, self.bdr, self.tracker, self.scratch, self.cache),
                    self.mounts,
                )
            ],
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def mount(
        source: Path, destination: str, writable: bool, source_kind: str,
    ) -> dict[str, Any]:
        return {
            "type": "bind",
            "destination": destination,
            "source_sha256": sha256_bytes(str(source).encode("utf-8")),
            "source_kind": source_kind,
            "rw": writable,
            "propagation": "rprivate",
        }

    def identity(self, inspected: Mapping[str, Any] | None = None) -> dict[str, Any]:
        return DockerExecProcess.security_identity_from_inspect(
            inspected or self.inspected,
            expected_network_mode="none",
            expected_user="1000:1000",
            allowed_mounts=self.mounts,
            allowed_environment_names=("PATH", "PAGER"),
        )

    def process(
        self,
        inspected: dict[str, Any] | None = None,
        *,
        expected_identity: Mapping[str, Any] | None = None,
        expected_user: str = "1000:1000",
    ) -> tuple[DockerExecProcess, RecordingDockerExecutor]:
        payload = inspected or self.inspected
        executor = RecordingDockerExecutor(payload)
        identity = dict(expected_identity or self.identity())
        process = DockerExecProcess(
            container="bdrv1-stage3-candidate",
            expected_container_id="container-id-123",
            expected_image_fingerprint="sha256:image-123",
            expected_container_config_sha256=fingerprint(identity),
            cwd="/workspace/repo",
            expected_network_mode="none",
            expected_user=expected_user,
            allowed_mounts=tuple(self.mounts),
            allowed_container_environment_names=("PATH", "PAGER"),
            executor=executor,
        )
        return process, executor

    def test_exact_directory_and_tracker_file_mounts_are_accepted(self) -> None:
        identity = self.identity()
        mount_kinds = {
            record["destination"]: record["source_kind"] for record in identity["mounts"]
        }
        self.assertEqual("regular_file", mount_kinds["/workspace/bdr/slices_progress.yaml"])
        self.assertEqual(
            {"directory"},
            {
                kind for destination, kind in mount_kinds.items()
                if destination != "/workspace/bdr/slices_progress.yaml"
            },
        )
        process, _ = self.process()
        observation = process.execute(ToolAction("call-1", "bash", {"command": "pwd"}))
        self.assertEqual(0, observation.returncode)

    def test_address_free_docker_desktop_none_network_is_accepted_and_bound(self) -> None:
        inspected = copy.deepcopy(self.inspected)
        inspected["NetworkSettings"]["Networks"] = {
            "none": {
                "Aliases": None,
                "DNSNames": None,
                "DriverOpts": None,
                "EndpointID": "endpoint-id-123",
                "Gateway": "",
                "GlobalIPv6Address": "",
                "GlobalIPv6PrefixLen": 0,
                "GwPriority": 0,
                "IPAMConfig": None,
                "IPAddress": "",
                "IPPrefixLen": 0,
                "IPv6Gateway": "",
                "Links": None,
                "MacAddress": "",
                "NetworkID": "network-id-123",
            }
        }

        identity = self.identity(inspected)

        self.assertEqual(False, identity["runtime_networks"]["none"]["addressed"])
        self.assertEqual(
            sha256_bytes(b"network-id-123"),
            identity["runtime_networks"]["none"]["network_id_sha256"],
        )

        addressed = copy.deepcopy(inspected)
        addressed["NetworkSettings"]["Networks"]["none"]["IPAddress"] = "172.20.0.2"
        with self.assertRaises(ModelProtocolError):
            self.identity(addressed)

    def test_expected_container_user_must_be_explicitly_nonroot(self) -> None:
        for value in ("", "root", "root:1000", "0", "0:1000"):
            with self.subTest(expected_user=value), self.assertRaises(ConfigurationError):
                self.process(expected_user=value)

        config_path = self.base / "missing-user.json"
        config_path.write_text(json.dumps({
            "tool": {
                "backend": "docker_exec",
                "container": "bdrv1-stage3-candidate",
                "allowed_mounts": self.mounts,
                "allowed_container_environment_names": ["PATH", "PAGER"],
                "cwd": "/workspace/repo",
            }
        }), encoding="utf-8")
        executor = RecordingDockerExecutor(self.inspected)
        with self.assertRaises(ConfigurationError):
            container_identity_from_config(config_path, executor=executor)
        self.assertEqual([], executor.calls)

    def test_mount_source_hash_is_exact_inspect_path_string_not_content(self) -> None:
        expected = sha256_bytes(str(self.tracker).encode("utf-8"))
        content_hash = sha256_bytes(self.tracker.read_bytes())
        by_destination = {
            record["destination"]: record for record in self.identity()["mounts"]
        }
        self.assertNotEqual(content_hash, expected)
        self.assertEqual(expected, self.mounts[2]["source_sha256"])
        self.assertEqual(expected, by_destination[
            "/workspace/bdr/slices_progress.yaml"
        ]["source_sha256"])

        self.tracker.write_text("changed opaque tracker bytes\n", encoding="utf-8")
        changed_by_destination = {
            record["destination"]: record for record in self.identity()["mounts"]
        }
        self.assertEqual(expected, changed_by_destination[
            "/workspace/bdr/slices_progress.yaml"
        ]["source_sha256"])

    def test_docker_exec_keeps_model_command_in_one_argv_element(self) -> None:
        process, executor = self.process()
        command = "printf safe; $(touch /tmp/must-not-run-on-host)"

        process.execute(ToolAction("call-argv", "bash", {"command": command}))

        exec_arguments, exec_keywords = executor.calls[-1]
        self.assertEqual("docker", exec_arguments[0])
        self.assertEqual(["/bin/sh", "-lc", command], exec_arguments[-3:])
        self.assertEqual(command, exec_arguments[-1])
        self.assertNotIn("shell", exec_keywords)

    def test_unsafe_container_or_mount_surfaces_are_rejected(self) -> None:
        mutations = {
            "effective_user_root_name": lambda value: value["Config"].update(
                {"User": "root"}
            ),
            "effective_user_root_uid": lambda value: value["Config"].update(
                {"User": "0"}
            ),
            "effective_user_root_uid_with_group": lambda value: value["Config"].update(
                {"User": "0:1000"}
            ),
            "effective_user_empty": lambda value: value["Config"].update(
                {"User": ""}
            ),
            "privileged": lambda value: value["HostConfig"].update({"Privileged": True}),
            "capabilities_not_dropped": lambda value: value["HostConfig"].update(
                {"CapDrop": []}
            ),
            "writable_rootfs": lambda value: value["HostConfig"].update(
                {"ReadonlyRootfs": False}
            ),
            "network_mode": lambda value: value["HostConfig"].update({"NetworkMode": "bridge"}),
            "runtime_network": lambda value: value["NetworkSettings"].update(
                {"Networks": {"bridge": {}}}
            ),
            "host_root": lambda value: value["Mounts"][0].update({"Source": "/"}),
            "docker_socket": lambda value: value["Mounts"][0].update(
                {"Source": "/var/run/docker.sock"}
            ),
            "unexpected_environment": lambda value: value["Config"]["Env"].append(
                "SECRET_TOKEN=not-allowed"
            ),
            "unexpected_mount": lambda value: value["Mounts"].append({
                "Type": "bind",
                "Source": str(self.base),
                "Destination": "/workspace/unexpected",
                "RW": False,
                "Propagation": "rprivate",
            }),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                inspected = copy.deepcopy(self.inspected)
                mutate(inspected)
                with self.assertRaises(ModelProtocolError):
                    self.identity(inspected)

    def test_container_identity_drift_is_rejected_before_exec(self) -> None:
        expected_identity = self.identity()
        drifted = copy.deepcopy(self.inspected)
        drifted["Config"]["Env"][0] = "PATH=/different"
        process, executor = self.process(drifted, expected_identity=expected_identity)

        with self.assertRaises(ModelProtocolError):
            process.execute(ToolAction("drift-call", "bash", {"command": "pwd"}))

        self.assertEqual(1, len(executor.calls))
        self.assertEqual("inspect", executor.calls[0][0][1])

    def test_read_only_container_identity_command_computes_placeholder_values(self) -> None:
        config_path = self.base / "identity-config.json"
        config_path.write_text(json.dumps({
            "tool": {
                "backend": "docker_exec",
                "container": "bdrv1-stage3-candidate",
                "expected_container_id": "not-yet-known",
                "expected_image_fingerprint": "not-yet-known",
                "expected_container_config_sha256": "not-yet-known",
                "expected_network_mode": "none",
                "expected_user": "1000:1000",
                "allowed_mounts": self.mounts,
                "allowed_container_environment_names": ["PATH", "PAGER"],
                "cwd": "/workspace/repo",
            }
        }), encoding="utf-8")
        executor = RecordingDockerExecutor(self.inspected)

        result = container_identity_from_config(config_path, executor=executor)

        self.assertEqual("container-id-123", result["container_id"])
        self.assertEqual("sha256:image-123", result["image_fingerprint"])
        self.assertEqual(fingerprint(result["security_identity"]), result[
            "expected_container_config_sha256"
        ])
        self.assertEqual(1, len(executor.calls))
        self.assertEqual("inspect", executor.calls[0][0][1])

    def test_container_identity_cli_prints_read_only_inspection_result(self) -> None:
        config_path = self.base / "identity-cli.json"
        config_path.write_text("{}", encoding="utf-8")
        expected = {
            "container_id": "container-id-123",
            "image_fingerprint": "sha256:image-123",
            "security_identity": {"running": True},
            "expected_container_config_sha256": "a" * 64,
        }
        with (
            patch(
                "run_repository_probe.container_identity_from_config",
                return_value=expected,
            ) as inspect_command,
            patch("sys.stdout", new_callable=io.StringIO) as output,
        ):
            returncode = main(["container-identity", "--config", str(config_path)])

        self.assertEqual(0, returncode)
        inspect_command.assert_called_once_with(config_path)
        self.assertEqual(expected, json.loads(output.getvalue()))

    def test_example_config_uses_repository_workdir_and_tracker_file_overlay(self) -> None:
        example = _example_config()
        tool = example["tool"]
        self.assertEqual("/workspace/repo", tool["cwd"])
        self.assertEqual("1000:1000", tool["expected_user"])
        mounts = {record["destination"]: record for record in tool["allowed_mounts"]}
        self.assertEqual("regular_file", mounts[
            "/workspace/bdr/slices_progress.yaml"
        ]["source_kind"])
        self.assertTrue(mounts["/workspace/bdr/slices_progress.yaml"]["rw"])
        self.assertIn("/workspace/dependency-cache", mounts)
        self.assertIn("/tmp", mounts)
        self.assertNotIn("/root/.m2", mounts)
        self.assertEqual(
            "/workspace/bdr/slices_progress.yaml",
            example["workspace"]["manifest"]["tracker"][0]["model_path"],
        )
        self.assertEqual(
            "/workspace/repo/src/main",
            example["workspace"]["manifest"]["source"][0]["model_path"],
        )
        self.assertEqual("auto", example["model"]["work_tool_choice"])


class ServedIdentityAndEnvelopeTests(unittest.TestCase):
    def adapter(self, **overrides: Any) -> OpenAICompatibleAdapter:
        values: dict[str, Any] = {
            "endpoint": "http://127.0.0.1:1/v1/chat/completions",
            "model": "candidate-request-name",
            "accepted_response_models": ("candidate-served-name",),
            "served_model_revision": "revision-abc",
            "served_model_precision": "fp8",
            "served_context_window_tokens": 131072,
            "server_fingerprint": "server-template-123",
            "api_key": "EMPTY",
            "stream": True,
        }
        values.update(overrides)
        return OpenAICompatibleAdapter(**values)

    @staticmethod
    def valid_identity_response() -> dict[str, Any]:
        return {
            "model": "candidate-served-name",
            "system_fingerprint": "server-template-123",
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
        }

    def test_served_model_identity_usage_and_server_fingerprint_fail_closed(self) -> None:
        adapter = self.adapter()
        failures = {
            "model": {**self.valid_identity_response(), "model": "different-model"},
            "server": {
                **self.valid_identity_response(),
                "system_fingerprint": "different-server",
            },
            "missing_usage": {
                key: value
                for key, value in self.valid_identity_response().items()
                if key != "usage"
            },
            "negative_usage": {
                **self.valid_identity_response(),
                "usage": {"prompt_tokens": 100, "completion_tokens": -1, "total_tokens": 99},
            },
        }
        adapter._validate_response_identity(self.valid_identity_response())
        for name, response in failures.items():
            with self.subTest(name=name), self.assertRaises(ModelProtocolError):
                adapter._validate_response_identity(response)

    def test_https_ca_bundle_is_byte_bound_and_verified(self) -> None:
        with tempfile.TemporaryDirectory(prefix="tls-ca-test-") as temporary:
            ca_path = Path(temporary) / "ca.pem"
            ca_path.write_text("synthetic test CA bytes\n", encoding="utf-8")
            ca_sha256 = sha256_bytes(ca_path.read_bytes())
            with patch("run_repository_probe.ssl.create_default_context") as create_context:
                adapter = self.adapter(
                    endpoint="https://model.example.invalid/v1/chat/completions",
                    tls_ca_file=str(ca_path),
                    tls_ca_sha256=ca_sha256,
                )

            self.assertEqual(ca_sha256, adapter.policy_value()["tls"]["ca_sha256"])
            create_context.assert_called_once_with(cafile=str(ca_path))

            with self.assertRaises(ConfigurationError):
                self.adapter(
                    endpoint="https://model.example.invalid/v1/chat/completions",
                    tls_ca_file=str(ca_path),
                    tls_ca_sha256="0" * 64,
                )
            with self.assertRaises(ConfigurationError):
                self.adapter(
                    endpoint="http://127.0.0.1:8000/v1/chat/completions",
                    tls_ca_file=str(ca_path),
                    tls_ca_sha256=ca_sha256,
                )

    def test_user_agent_is_policy_bound_and_rejects_header_injection(self) -> None:
        adapter = self.adapter(user_agent="bdrv1-canary/2")

        self.assertEqual("bdrv1-canary/2", adapter.policy_value()["user_agent"])
        for value in ("", "unsafe\nheader", "unsafe\rheader", "unsafe\x7fheader"):
            with self.subTest(value=value), self.assertRaises(ConfigurationError):
                self.adapter(user_agent=value)

    def test_stream_usage_only_tail_and_reasoning_survive_replay(self) -> None:
        adapter = self.adapter()
        events = [
            b'data: {"id":"response-1","model":"candidate-served-name","system_fingerprint":"server-template-123","choices":[{"delta":{"reasoning_content":"evidence ","content":"working"},"finish_reason":null}]}\n',
            b'data: {"id":"response-1","model":"candidate-served-name","system_fingerprint":"server-template-123","choices":[{"delta":{"reasoning_content":"and plan"},"finish_reason":"stop"}]}\n',
            b'data: {"id":"response-1","model":"candidate-served-name","system_fingerprint":"server-template-123","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}\n',
            b'data: [DONE]\n',
        ]

        result = adapter._read_stream(events)
        adapter._validate_response_identity(result)
        turn = adapter._assistant_turn(result)
        replayed = adapter._openai_message(turn.to_message())

        self.assertEqual("evidence and plan", turn.reasoning)
        self.assertEqual("evidence and plan", replayed["reasoning_content"])
        self.assertEqual(120, turn.provider_metadata["usage"]["total_tokens"])

    def test_all_nine_methodology_files_fit_before_and_after_forced_compaction(self) -> None:
        methodology_names = (
            "01-METHOD.md",
            "02-FINDING-BOUNDARIES.md",
            "03-GUARDRAILS.md",
            "04-PHILOSOPHY.md",
            "05-CASE-STUDY.md",
            "README.md",
            "SKILL.md",
            "slices.py",
            "slices_progress.template.yaml",
        )
        methodology_paths = [BDRV1 / "methodology" / name for name in methodology_names]
        estimator = self.adapter()

        class SizingScriptedAdapter(ScriptedModelAdapter):
            def estimate_tokens(
                self, messages: Sequence[Mapping[str, Any]], *, mode: str,
            ) -> int:
                return estimator.estimate_tokens(messages, mode=mode)

            def policy_value(self) -> dict[str, Any]:
                value = super().policy_value()
                value["token_estimator"] = estimator.policy_value()
                return value

        with ProbeFixture() as fixture:
            base_configuration = fixture.configuration()
            configuration = ProbeConfiguration(
                state_dir=fixture.state_dir,
                workspace=base_configuration.workspace,
                methodology=tuple(
                    ArtifactInput(
                        name,
                        path,
                        model_path="/workspace/bdr/" + name,
                    )
                    for name, path in zip(methodology_names, methodology_paths)
                ),
                task=base_configuration.task,
                limits=RunnerLimits(
                    context_window_tokens=131072,
                    proactive_trigger_tokens=100000,
                    work_output_tokens=8192,
                    compaction_output_tokens=4096,
                    max_logical_calls=4,
                    max_model_retries=0,
                    retry_backoff_seconds=0,
                    force_compaction_after_turns=(1,),
                ),
            )
            model = SizingScriptedAdapter(
                turns=[AssistantTurn("Initial evidence has been collected."), completion_turn()],
                summaries=[CompactionSummary(
                    "Initial evidence is preserved.",
                    ("The first completed turn established the starting evidence.",),
                    ("The completion decision remains open.",),
                    "Continue from the packet and finish only after verification.",
                )],
            )
            process = ScriptedToolProcess([completion_observation()])

            result = RepositoryProbeRunner(configuration, model, process).run()

        self.assertEqual("completed", result["status"])
        self.assertEqual(1, result["counters"]["compactions_completed"])
        self.assertGreater(sum(path.stat().st_size for path in methodology_paths), 190000)
        self.assertEqual(2, len(model.work_requests))
        for request in model.work_requests:
            request_text = canonical_json(request).decode("utf-8")
            self.assertLess(len(request_text.encode("utf-8")), 20000)
            self.assertLess(estimator.estimate_tokens(request, mode="work") + 8192, 100000)
        first_request = canonical_json(model.work_requests[0]).decode("utf-8")
        self.assertNotIn(methodology_paths[0].read_text(encoding="utf-8"), first_request)
        for name in methodology_names:
            self.assertIn("/workspace/bdr/" + name, first_request)


class ControlOpacityAndConfigurationTests(unittest.TestCase):
    def test_tracker_vocabulary_does_not_change_control_flow(self) -> None:
        results: list[tuple[dict[str, int], int]] = []
        for tracker_text in ("alpha beta gamma\n", "phase rewind done blocked superseded\n"):
            with ProbeFixture() as fixture:
                fixture.tracker.write_text(tracker_text, encoding="utf-8")
                git(fixture.workspace, "add", "tracker.data")
                git(fixture.workspace, "commit", "-qm", "tracker variant")
                model = ScriptedModelAdapter([completion_turn()], [], estimated_tokens=64)
                process = ScriptedToolProcess([completion_observation()])
                result = RepositoryProbeRunner(fixture.configuration(), model, process).run()
                results.append((result["counters"], model.generate_calls))

        self.assertEqual(results[0], results[1])

    def test_live_json_configuration_builds_real_model_and_process_adapters(self) -> None:
        with ProbeFixture() as fixture:
            config = {
                "state_dir": str(fixture.state_dir),
                "workspace": {
                    "root": str(fixture.workspace),
                    "git_semantic_required": True,
                    "manifest": {
                        "tracker": [str(fixture.tracker)],
                        "source": [str(fixture.source)],
                        "test": [str(fixture.tests)],
                        "evidence": [str(fixture.evidence)],
                    },
                },
                "methodology": [str(fixture.methodology)],
                "task": str(fixture.task),
                "model": {
                    "endpoint": "http://127.0.0.1:8000/v1/chat/completions",
                    "name": "candidate",
                    "served_identity": {
                        "accepted_response_models": ["candidate"],
                        "revision": "test-revision",
                        "precision": "test-precision",
                        "context_window_tokens": 131072,
                        "server_fingerprint": "response-unavailable",
                    },
                    "api_key_env": "PROBE_PROVIDER_KEY",
                    "stream": True,
                    "reasoning_effort": "medium",
                    "replay_reasoning_field": "reasoning_content",
                    "context_maintenance_extra_body": {
                        "chat_template_kwargs": {"enable_thinking": False}
                    },
                },
                "limits": {
                    "context_window_tokens": 131072,
                    "proactive_trigger_tokens": 100000,
                    "work_output_tokens": 8192,
                    "compaction_output_tokens": 4096,
                },
                "tool": {
                    "backend": "local_shell",
                    "cwd": str(fixture.workspace),
                    "max_observation_chars": 12000,
                    "trusted_disposable_host": True,
                },
            }
            config_path = fixture.base / "live.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")

            with patch.dict("os.environ", {"PROBE_PROVIDER_KEY": "must-not-reach-tool"}):
                default_runner = runner_from_config(config_path)
                config["model"]["work_tool_choice"] = "bash"
                config_path.write_text(json.dumps(config), encoding="utf-8")
                runner = runner_from_config(config_path)
                environment_observation = runner.process.execute(ToolAction(
                    "environment-check",
                    "bash",
                    {"command": "if [ -z \"${PROBE_PROVIDER_KEY+x}\" ]; then printf absent; else printf present; fi"},
                ))

        self.assertIsInstance(runner.model, OpenAICompatibleAdapter)
        self.assertIsInstance(runner.process, LocalShellProcess)
        self.assertTrue(runner.model.capabilities.streaming)
        self.assertEqual("absent", environment_observation.output)
        self.assertFalse(runner.policy["process"]["inherit_environment"])
        self.assertIn("PROBE_PROVIDER_KEY", runner.policy["process"]["scrub_environment_names"])
        self.assertEqual("reasoning_content", runner.policy["model"]["replay_reasoning_field"])
        self.assertEqual("auto", default_runner.policy["model"]["work_tool_choice"])
        self.assertEqual(
            {"type": "function", "function": {"name": "bash"}},
            runner.policy["model"]["work_tool_choice"],
        )
        self.assertEqual("bash", runner.model.work_tool_choice)
        self.assertEqual("medium", runner.policy["model"]["work_reasoning_effort"])
        self.assertIsNone(
            runner.policy["model"]["context_maintenance_reasoning_effort"]
        )
        self.assertEqual(
            ["chat_template_kwargs"],
            runner.policy["model"]["context_maintenance_extra_body_keys"],
        )
        self.assertEqual(
            fingerprint({"chat_template_kwargs": {"enable_thinking": False}}),
            runner.policy["model"]["context_maintenance_extra_body_sha256"],
        )
        self.assertEqual(8192, runner.policy["limits"]["work_output_tokens"])
        self.assertEqual(4096, runner.policy["limits"]["compaction_output_tokens"])
        self.assertEqual(12000, runner.policy["process"]["max_observation_chars"])
        self.assertEqual(runner.policy_sha256, __import__("hashlib").sha256(
            canonical_json(runner.policy)
        ).hexdigest())

    def test_docker_config_binds_host_workspace_and_methodology_to_model_paths(self) -> None:
        with ProbeFixture() as fixture:
            def mount(
                source: Path, destination: str, writable: bool, source_kind: str,
            ) -> dict[str, Any]:
                return {
                    "type": "bind",
                    "destination": destination,
                    "source_sha256": sha256_bytes(
                        str(source.resolve()).encode("utf-8")
                    ),
                    "source_kind": source_kind,
                    "rw": writable,
                    "propagation": "rprivate",
                }

            config = {
                "state_dir": str(fixture.state_dir),
                "workspace": {
                    "root": str(fixture.workspace),
                    "git_semantic_required": True,
                    "manifest": {
                        "tracker": [{
                            "path": str(fixture.tracker),
                            "model_path": "/workspace/repo/tracker.data",
                        }],
                        "source": [{
                            "path": str(fixture.source),
                            "model_path": "/workspace/repo/src",
                        }],
                        "test": [{
                            "path": str(fixture.tests),
                            "model_path": "/workspace/repo/tests",
                        }],
                        "evidence": [{
                            "path": str(fixture.evidence),
                            "model_path": "/workspace/repo/evidence",
                        }],
                    },
                },
                "methodology": [{
                    "path": str(fixture.methodology),
                    "model_path": "/workspace/bdr/methodology.txt",
                }],
                "task": str(fixture.task),
                "model": {
                    "endpoint": "http://127.0.0.1:8000/v1/chat/completions",
                    "name": "candidate",
                    "served_identity": {
                        "accepted_response_models": ["candidate"],
                        "revision": "test-revision",
                        "precision": "test-precision",
                        "context_window_tokens": 131072,
                        "server_fingerprint": "response-unavailable",
                    },
                    "api_key": "EMPTY",
                    "stream": True,
                    "replay_reasoning_field": "reasoning_content",
                },
                "limits": {
                    "context_window_tokens": 131072,
                    "proactive_trigger_tokens": 100000,
                    "work_output_tokens": 8192,
                    "compaction_output_tokens": 4096,
                },
                "tool": {
                    "backend": "docker_exec",
                    "container": "candidate-container",
                    "expected_container_id": "container-id",
                    "expected_image_fingerprint": "sha256:image-id",
                    "expected_container_config_sha256": "0" * 64,
                    "expected_network_mode": "none",
                    "expected_user": "1000:1000",
                    "allowed_mounts": [
                        mount(
                            fixture.workspace, "/workspace/repo", True, "directory",
                        ),
                        mount(
                            fixture.inputs, "/workspace/bdr", False, "directory",
                        ),
                    ],
                    "allowed_container_environment_names": [],
                    "cwd": "/workspace/repo",
                },
            }
            config_path = fixture.base / "docker-bound.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")

            runner = runner_from_config(config_path)

            self.assertEqual(
                "/workspace/repo/src",
                runner.policy["workspace"]["monitored_paths"][1]["model_path"],
            )
            self.assertEqual(
                "/workspace/bdr/methodology.txt",
                runner.policy["immutable_methodology"][0]["model_path"],
            )

            wrong_repo = copy.deepcopy(config)
            wrong_repo["tool"]["allowed_mounts"][0]["source_sha256"] = "f" * 64
            config_path.write_text(json.dumps(wrong_repo), encoding="utf-8")
            with self.assertRaisesRegex(
                ConfigurationError,
                "workspace.root host path does not match the Docker mount source identity",
            ):
                runner_from_config(config_path)

            wrong_methodology = copy.deepcopy(config)
            wrong_methodology["methodology"][0]["model_path"] = (
                "/workspace/bdr/different.txt"
            )
            config_path.write_text(json.dumps(wrong_methodology), encoding="utf-8")
            with self.assertRaisesRegex(
                ConfigurationError,
                r"methodology\[0\] does not map cleanly through its bind mount",
            ):
                runner_from_config(config_path)

    def test_docker_config_rejects_model_api_key_environment_allowlist(self) -> None:
        with ProbeFixture() as fixture:
            config = {
                "state_dir": str(fixture.state_dir),
                "workspace": {
                    "root": str(fixture.workspace),
                    "git_semantic_required": True,
                    "manifest": {
                        "tracker": [str(fixture.tracker)],
                        "source": [str(fixture.source)],
                        "test": [str(fixture.tests)],
                        "evidence": [str(fixture.evidence)],
                    },
                },
                "methodology": [str(fixture.methodology)],
                "task": str(fixture.task),
                "model": {
                    "endpoint": "http://127.0.0.1:8000/v1/chat/completions",
                    "name": "candidate",
                    "served_identity": {
                        "accepted_response_models": ["candidate"],
                        "revision": "test-revision",
                        "precision": "test-precision",
                        "context_window_tokens": 131072,
                        "server_fingerprint": "response-unavailable",
                    },
                    "api_key_env": "PROBE_PROVIDER_KEY",
                    "stream": True,
                    "replay_reasoning_field": "reasoning_content",
                },
                "limits": {
                    "context_window_tokens": 131072,
                    "proactive_trigger_tokens": 100000,
                    "work_output_tokens": 8192,
                    "compaction_output_tokens": 4096,
                },
                "tool": {
                    "backend": "docker_exec",
                    "container": "candidate-container",
                    "expected_container_id": "container-id",
                    "expected_image_fingerprint": "sha256:image-id",
                    "expected_container_config_sha256": "0" * 64,
                    "expected_network_mode": "none",
                    "expected_user": "1000:1000",
                    "allowed_mounts": [],
                    "allowed_container_environment_names": [
                        "PATH", "PROBE_PROVIDER_KEY"
                    ],
                    "cwd": "/workspace/repo",
                },
            }
            config_path = fixture.base / "credential-in-container.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")

            with (
                patch.dict(
                    "os.environ",
                    {"PROBE_PROVIDER_KEY": "must-never-reach-tool"},
                ),
                self.assertRaisesRegex(
                    ConfigurationError,
                    "container environment allowlist contains the configured model credential",
                ),
            ):
                runner_from_config(config_path)

    def test_canonical_json_and_strict_parser_are_deterministic(self) -> None:
        self.assertEqual(canonical_json({"z": 1, "a": "é"}), canonical_json({"a": "é", "z": 1}))
        self.assertNotEqual(canonical_json([1, 2]), canonical_json([2, 1]))
        with self.assertRaises(ValueError):
            strict_json_loads('{"a":1,"a":2}')
        with self.assertRaises(ValueError):
            strict_json_loads('{"number":NaN}')


if __name__ == "__main__":
    unittest.main()
