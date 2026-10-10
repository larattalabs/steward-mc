"""Steward's item icons, 16x16, painted as literal pixel grids like the skins ('.' transparent).

python gen/items.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from common import OUT, paint_grid, pc, save_png  # noqa: E402
from chars.base import T  # noqa: E402

# THE STEWARD'S LEDGER: a closed book in the steward's coat green, a darker spine on the left, cream page edges on the right
# and bottom, a brass clasp across the fore-edge and a brass corner, a cream label on the cover.
LEDGER = [
    "................",
    "...@@@@@@@@@@...",
    "..CA@@@@@@@@@q..",
    "..CAAAAAAAAAAqr.",
    "..CAAqqqqqqAAqr.",
    "..CAAqccccqAAqr.",
    "..CAAqqqqqqAAqr.",
    "..CAAAAAAAAAAyY.",
    "..CAAAAAAAAAAyk.",
    "..CAAAAAAAAAAqr.",
    "..CAAAAAAAAAAqr.",
    "..CBBBBBBBBBBqr.",
    "..CBBBBBBBBByyr.",
    "..DCCCCCCCCCCkr.",
    "...Dqqqqqqqqqqr.",
    "................",
]


def build():
    coat = T("cast_ramps", "steward")
    L = {
        "@": coat[0], "A": coat[1], "B": coat[2], "C": coat[3], "D": coat[4],
        "q": pc("ramps.cream[1]"), "c": pc("ramps.cream[3]"), "r": pc("ramps.cream[3]"),
        "y": pc("ramps.brass[1]"), "Y": pc("ramps.brass[2]"), "k": pc("ramps.brass[3]"),
    }
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    paint_grid(img, 0, 0, LEDGER, L)
    out = OUT / "textures" / "item" / "steward_ledger.png"
    save_png(img, out)
    print("item steward_ledger ok")
    return out


if __name__ == "__main__":
    build()
