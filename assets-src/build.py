"""Regenerate Steward's art (the NPC skins).

    uv run --with pillow==11.3.0 python assets-src/build.py           # build into assets-src/out
    uv run --with pillow==11.3.0 python assets-src/build.py --verify  # build twice with different hash seeds, assert byte-identical
    uv run --with pillow==11.3.0 python assets-src/build.py --sync    # build, then copy into mod/src/main/resources

Ported from AgentCraft Worlds' build.py (MIT; see NOTICE).
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "gen"))

import skins  # noqa: E402
from common import OUT  # noqa: E402

MOD = HERE.parent / "mod" / "src" / "main" / "resources" / "assets" / "steward_mc"


def build():
    if OUT.exists():
        shutil.rmtree(OUT)
    skins.build()


def digest():
    h = hashlib.sha256()
    for p in sorted(OUT.rglob("*")):
        if p.is_file():
            h.update(str(p.relative_to(OUT)).encode())
            h.update(p.read_bytes())
    return h.hexdigest()


if __name__ == "__main__":
    if "--verify" in sys.argv:
        sums = []
        for seed in ("1", "2"):
            subprocess.run([sys.executable, str(Path(__file__))], check=True, env={**os.environ, "PYTHONHASHSEED": seed})
            sums.append(digest())
        if sums[0] != sums[1]:
            raise SystemExit("not deterministic: " + " vs ".join(sums))
        print("deterministic:", sums[0][:16])
    else:
        build()
        if "--sync" in sys.argv:
            for p in OUT.rglob("*.png"):
                dst = MOD / p.relative_to(OUT)
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(p, dst)
                print("synced", dst.relative_to(HERE.parent))
