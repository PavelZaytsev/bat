#!/usr/bin/env python3
"""Validate the public Java 25 bounded-fix fixture and execute it when a JDK is available."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples" / "java-fix-v1"


def fail(message: str) -> None:
    raise ValueError(message)


def java_sources(tree: Path, source_kind: str) -> list[str]:
    return sorted(str(path) for path in (tree / "src" / source_kind / "java").rglob("*.java"))


def run_java(sources: list[str], expected_exit: int, fingerprint: str | None) -> None:
    with tempfile.TemporaryDirectory(prefix="bat-java-fix-v1-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        compile_result = subprocess.run(
            ["javac", "--release", "25", "-d", str(classes), *sources],
            text=True,
            capture_output=True,
            check=False,
        )
        if compile_result.returncode:
            fail(f"fixture compilation failed: {compile_result.stderr.strip()}")
        result = subprocess.run(
            ["java", "-cp", str(classes), "dev.bat.examples.cache.StreamingCacheRootProof"],
            text=True,
            capture_output=True,
            check=False,
        )
        output = result.stdout + result.stderr
        if result.returncode != expected_exit:
            fail(f"fixture exit {result.returncode}, expected {expected_exit}: {output.strip()}")
        if fingerprint is not None and fingerprint not in output:
            fail(
                f"fixture failure did not contain assertion fingerprint {fingerprint!r}: "
                f"{output.strip()}"
            )


def main() -> int:
    try:
        manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("schema") != "bat.dev/java-fix-v1-fixture/v1":
            fail("unexpected fixture schema")
        if manifest.get("java_release") != 25:
            fail("fixture must target Java 25")
        slices = manifest.get("required_slices")
        if not isinstance(slices, list) or len(slices) != 2:
            fail("fixture must declare exactly two required slices")
        if [item.get("classification") for item in slices] != ["root", "required"]:
            fail("fixture slices must be root then required")
        if slices[1].get("parent") != slices[0].get("id"):
            fail("required fixture slice must be root-reachable")
        if not manifest.get("out_of_scope"):
            fail("fixture must contain a visible out-of-scope finding")
        failure_channel = manifest.get("failure_channel", {})
        if failure_channel.get("required_representation") != "sealed domain value":
            fail("expected domain failure must remain value-based")
        if failure_channel.get("introduced_or_broadened_exceptions") != []:
            fail("reference repair may not introduce or broaden exceptions")

        buggy = FIXTURE / manifest["trees"]["buggy"]
        repaired = FIXTURE / manifest["trees"]["repaired"]
        buggy_main = java_sources(buggy, "main")
        buggy_test = java_sources(buggy, "test")
        repaired_main = java_sources(repaired, "main")
        repaired_test = java_sources(repaired, "test")
        if not all((buggy_main, buggy_test, repaired_main, repaired_test)):
            fail("fixture trees must contain production and proof sources")

        buggy_cache = (buggy / "src/main/java/dev/bat/examples/cache/StreamingCache.java").read_text()
        repaired_cache = (repaired / "src/main/java/dev/bat/examples/cache/StreamingCache.java").read_text()
        if "NoSuchElementException" not in buggy_cache or "NoSuchElementException" in repaired_cache:
            fail("fixture must remove the broadened expected-miss exception path")
        if "debugEntryCount" not in buggy_cache or "debugEntryCount" not in repaired_cache:
            fail("out-of-scope diagnostic finding must remain present")

        javac = shutil.which("javac")
        java = shutil.which("java")
        jdk_probe = (
            subprocess.run([javac, "-version"], text=True, capture_output=True, check=False)
            if javac is not None and java is not None else None
        )
        version_match = (
            re.search(r"\bjavac\s+(\d+)", jdk_probe.stdout + jdk_probe.stderr)
            if jdk_probe is not None and jdk_probe.returncode == 0 else None
        )
        if version_match is None or int(version_match.group(1)) < 25:
            print("PASS Java fix fixture contract; SKIP Java execution (JDK 25 unavailable)")
            return 0

        fingerprint = manifest["objective"]["failure_fingerprint"]
        expected = manifest["expected"]
        run_java(buggy_main + buggy_test, expected["buggy_exit_code"], fingerprint)
        run_java(repaired_main + repaired_test, expected["repaired_exit_code"], None)
        run_java(buggy_main + repaired_test, expected["aggregate_counterfactual_exit_code"], fingerprint)
        print("PASS Java fix fixture: root red, repair green, aggregate counterfactual red")
        return 0
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"FAIL Java fix fixture: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
