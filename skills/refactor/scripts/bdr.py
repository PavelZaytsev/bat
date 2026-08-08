#!/usr/bin/env python3
"""Launch the canonical BDR engine from a complete BAT plugin installation."""

from __future__ import annotations

import runpy
import sys
from pathlib import Path


def main() -> int:
    engine = Path(__file__).resolve().parents[3] / "scripts" / "bdr.py"
    if not engine.is_file():
        print(
            "bdr: canonical engine is missing; install the complete BAT plugin, not the skill directory alone",
            file=sys.stderr,
        )
        return 2
    namespace = runpy.run_path(str(engine))
    return int(namespace["main"]())


if __name__ == "__main__":
    raise SystemExit(main())
