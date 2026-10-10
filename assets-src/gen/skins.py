"""Steward's NPC skins: hand-authored 64x64 player-format skins, one module per character in gen/chars/<id>.py
(see skinlib.py for face orientation, chars/base.py for the Box painter). Ported from AgentCraft Worlds (MIT; see NOTICE).

python gen/skins.py [id ...]
"""
from __future__ import annotations

import importlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import OUT, save_png  # noqa: E402

CAST = ["steward"]


def build(ids=None):
    out = OUT / "textures" / "entity"
    results = {}
    for cid in CAST:
        if ids and cid not in ids:
            continue
        sk = importlib.import_module(f"chars.{cid}").build()
        probs = sk.validate()
        if probs:
            raise SystemExit(f"{cid}: skin validation failed:\n  " + "\n  ".join(probs[:20]))
        save_png(sk.img, out / f"{cid}.png")
        results[cid] = out / f"{cid}.png"
        print("skin", cid, "ok")
    return results


if __name__ == "__main__":
    build(sys.argv[1:] or None)
