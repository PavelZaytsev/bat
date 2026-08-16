#!/usr/bin/env python3
"""Send the frozen BDRv1 bundle and one task packet directly to a chat endpoint.

This is deliberately a transport shim, not a workflow controller. The model receives the
methodology as ordinary context and decides how to apply it.
"""

import argparse
import json
from pathlib import Path
import time
from urllib.request import Request, urlopen


BUNDLE_FILES = (
    "01-METHOD.md",
    "02-FINDING-BOUNDARIES.md",
    "03-GUARDRAILS.md",
    "04-PHILOSOPHY.md",
    "05-CASE-STUDY.md",
    "README.md",
    "slices.py",
    "slices_progress.template.yaml",
)

SYSTEM = """You are working in an ordinary coding-agent session.

Completely read the supplied Boundary-Driven Refactoring bundle and use it as the methodology.
Do not invent a replacement workflow or compress it into a new protocol. The phases are evidence
checkpoints, not a requirement to move monotonically forward. Rewind or repartition when evidence
requires it. Treat current code, tests, and measured behavior as evidence; do not treat issue prose,
tracker prose, or a green validator as proof about code. Make uncertain judgments explicit and
reversible. Do not claim a test or command ran unless you actually ran it.

For the task, report: (1) K and I for every finding, (2) the partition by K and what would falsify
it, (3) the value or ownership representation for K, (4) the next justified phase action including
rewinds/splits/transfers, (5) honest tracker changes and what slices.py can and cannot prove, and
(6) unresolved foreign facts and the cheapest way to resolve each.
"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, default=Path(__file__).parent / "methodology")
    parser.add_argument("--packet", type=Path, required=True)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8000/v1/chat/completions")
    parser.add_argument("--model", required=True)
    parser.add_argument("--effort", help="Omit for models or servers without reasoning_effort.")
    parser.add_argument("--max-tokens", type=int, default=32768)
    parser.add_argument("--temperature", type=float, default=0.2)
    parser.add_argument("--out", type=Path, required=True)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sections = []
    for name in BUNDLE_FILES:
        text = (args.bundle / name).read_text()
        sections.append(f"\n===== FILE: {name} =====\n{text}\n")

    user = "Here is the complete methodology bundle:\n" + "".join(sections)
    user += "\n===== TASK PACKET =====\n" + args.packet.read_text()
    body = {
        "model": args.model,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user},
        ],
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "stream": False,
    }
    if args.effort:
        body["reasoning_effort"] = args.effort

    request = Request(
        args.endpoint,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.time()
    with urlopen(request, timeout=3600) as response:
        result = json.loads(response.read())
    elapsed = time.time() - started

    result["_experiment"] = {
        "elapsed_seconds": elapsed,
        "bundle_files": BUNDLE_FILES,
        "packet": str(args.packet),
        "reasoning_effort": args.effort,
        "max_tokens": args.max_tokens,
        "temperature": args.temperature,
    }
    args.out.write_text(json.dumps(result, indent=2))

    message = result["choices"][0]["message"]
    print(json.dumps(result.get("usage", {}), indent=2))
    print(f"elapsed_seconds={elapsed:.1f}")
    reasoning = message.get("reasoning_content") or message.get("reasoning")
    if reasoning:
        print("\n===== REASONING =====\n" + reasoning)
    print("\n===== ANSWER =====\n" + (message.get("content") or ""))


if __name__ == "__main__":
    main()
