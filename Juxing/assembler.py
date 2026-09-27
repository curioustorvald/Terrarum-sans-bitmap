"""
Assembler: composes ideographs from drawn glyphlettes and writes the Han sheet.

A character is assembled from the *shallowest* cut of its slot tree whose parts
are all available (drawn, generated, or reused from a size up to 2px smaller), by
overlaying their drawings (protruding pixels included) and clipping to the body.
Joined joints between generated parts are bridged automatically (see bridge()): a glyphlette drawn for a whole character or a large chunk always
wins over smaller parts, so any assembly can be overridden simply by drawing
the larger piece (issue it with `juxing.py issue --whole <chars>`).

The output sheet has the same layout as the sheet it replaces: 256 columns of
16x16 cells, one row per 256 code points from U+3400 to U+9FFF (4096x1728 px),
white ink on transparent. U+4DC0..U+4DFF (Yijing hexagram symbols, which sit
between Ext. A and the URO) are drawn procedurally.
"""

from typing import Dict, List, Optional, Tuple

import numpy as np

import evenness as EV
import generators as GN
import geometry as GEO
import glyphlettes as GL
import tga
from geometry import Layout, SlotKey

SHEET_FIRST = 0x3400
SHEET_END = 0xA000
SHEET_COLS = 256
SHEET_ROWS = (SHEET_END - SHEET_FIRST) // SHEET_COLS

Placement = Tuple[SlotKey, int, int]


def resolve(layout: Layout, cp: int, drawn: Dict[SlotKey, GL.Drawing]) -> Optional[List[Placement]]:
    """The shallowest cut of the character whose glyphlettes are all drawn, or None."""
    return GEO.placements(layout, layout.root(cp), GEO.BODY_X, GEO.BODY_Y, lambda k: k in drawn, GN.is_unit)


def joined_gaps(layout: Layout, cp: int, drawn) -> List[Tuple[int, int, int]]:
    """Gap rows (y, x0, x1) of the joined joints in the character's resolved layout."""
    out = []

    def walk(key, x, y):
        if key in drawn or GN.is_unit(key):
            return
        s = layout.slot(key)
        if not s.parts:
            return
        for (a, ax, ay), (b, _, _), joined in zip(s.parts, s.parts[1:], layout.joints(key)):
            if joined:
                out.append((y + ay + a.h, x, x + key.w))
        for c, dx, dy in s.parts:
            walk(c, x + dx, y + dy)

    walk(layout.root(cp), GEO.BODY_X, GEO.BODY_Y)
    return out


def bridge(cell: np.ndarray, gaps, placements: List[Placement], drawn):
    """
    Join verticals across joined joints where both touching parts are generated
    (hand-drawn parts bridge with their own protruding pixels): an empty gap pixel
    with ink above and below, one side of which continues as a vertical stroke,
    is filled.
    """
    def owner(x, y):
        for k, px, py in placements:
            if px <= x < px + k.w and py <= y < py + k.h:
                return k
        return None

    for gy, x0, x1 in gaps:
        if gy < 2 or gy + 2 >= cell.shape[0]:
            continue
        for x in range(x0, x1):
            if cell[gy, x] or not (cell[gy - 1, x] and cell[gy + 1, x]):
                continue
            if not (cell[gy - 2, x] or cell[gy + 2, x]):
                continue
            above, below = owner(x, gy - 1), owner(x, gy + 1)
            if above and below and drawn.kind(above) == 'generated' and drawn.kind(below) == 'generated':
                cell[gy, x] = True


def font_scorer(layout: Layout, drawn: GL.Library, limit: int = 2000) -> 'EV.Scorer':
    """
    The evenness scorer, normalised (as in the paper) over the font's own initial
    configurations: an evenly spaced, fixed sample of every character that can be
    assembled, so that a character gets the same result however it is assembled.
    """
    ready = [cp for cp in layout.model.targets if resolve(layout, cp, drawn) is not None]
    step = max(1, len(ready) // limit)
    samples = []
    for cp in ready[::step]:
        p = resolve(layout, cp, drawn)
        samples.append(EV.Arrangement(layout, cp, p, drawn, joined_gaps(layout, cp, drawn)).metrics())
    return EV.Scorer(samples)


def assemble_one(layout: Layout, cp: int, drawn: GL.Library, scorer: Optional['EV.Scorer']):
    """
    Compose one character, refined by the evenness pass when a scorer is given.
    Returns (cell, arrangement or None), or None if the character cannot be made yet.
    """
    p = resolve(layout, cp, drawn)
    if p is None:
        return None
    gaps = joined_gaps(layout, cp, drawn)
    if scorer is None:
        cell = GL.compose(p, drawn)
        arr = None
    else:
        arr = EV.Arrangement(layout, cp, p, drawn, gaps)
        if arr.movable():
            EV.optimise(arr, scorer)
        cell = arr.compose() & GL.BODY_MASK
    bridge(cell, gaps, p, drawn)
    return cell, arr


def assemble(layout: Layout, targets: List[int], drawn: GL.Library, even=True,
             scorer: 'EV.Scorer' = None) -> Dict[int, np.ndarray]:
    """Compose every character that can be made; with `even`, refined by the evenness pass."""
    if even and scorer is None:
        scorer = font_scorer(layout, drawn)
    out = {}
    for cp in targets:
        r = assemble_one(layout, cp, drawn, scorer if even else None)
        if r is not None:
            out[cp] = r[0]
    return out


def write_sheet(path: str, glyphs: Dict[int, np.ndarray]):
    img = np.zeros((SHEET_ROWS * GEO.CELL_H, SHEET_COLS * GEO.CELL_W, 4), dtype=np.uint8)
    for cp, cell in glyphs.items():
        i = cp - SHEET_FIRST
        x = (i % SHEET_COLS) * GEO.CELL_W
        y = (i // SHEET_COLS) * GEO.CELL_H
        img[y:y + GEO.CELL_H, x:x + GEO.CELL_W][cell] = (255, 255, 255, 255)
    tga.write(path, img)


# ---------------------------------------------------------------------------
# Yijing hexagram symbols (U+4DC0..U+4DFF)

# trigrams as lines from the bottom up, 1 = unbroken (yang)
_TRIGRAM = {
    'qian': (1, 1, 1), 'dui': (1, 1, 0), 'li': (1, 0, 1), 'zhen': (1, 0, 0),
    'xun': (0, 1, 1), 'kan': (0, 1, 0), 'gen': (0, 0, 1), 'kun': (0, 0, 0),
}

# King Wen sequence as (lower, upper) trigrams
_KING_WEN = [
    ('qian', 'qian'), ('kun', 'kun'), ('zhen', 'kan'), ('kan', 'gen'),
    ('qian', 'kan'), ('kan', 'qian'), ('kan', 'kun'), ('kun', 'kan'),
    ('qian', 'xun'), ('dui', 'qian'), ('qian', 'kun'), ('kun', 'qian'),
    ('li', 'qian'), ('qian', 'li'), ('gen', 'kun'), ('kun', 'zhen'),
    ('zhen', 'dui'), ('xun', 'gen'), ('dui', 'kun'), ('kun', 'xun'),
    ('zhen', 'li'), ('li', 'gen'), ('kun', 'gen'), ('zhen', 'kun'),
    ('zhen', 'qian'), ('qian', 'gen'), ('zhen', 'gen'), ('xun', 'dui'),
    ('kan', 'kan'), ('li', 'li'), ('gen', 'dui'), ('xun', 'zhen'),
    ('gen', 'qian'), ('qian', 'zhen'), ('kun', 'li'), ('li', 'kun'),
    ('li', 'xun'), ('dui', 'li'), ('gen', 'kan'), ('kan', 'zhen'),
    ('dui', 'gen'), ('zhen', 'xun'), ('qian', 'dui'), ('xun', 'qian'),
    ('kun', 'dui'), ('xun', 'kun'), ('kan', 'dui'), ('xun', 'kan'),
    ('li', 'dui'), ('xun', 'li'), ('zhen', 'zhen'), ('gen', 'gen'),
    ('gen', 'xun'), ('dui', 'zhen'), ('li', 'zhen'), ('gen', 'li'),
    ('xun', 'xun'), ('dui', 'dui'), ('kan', 'xun'), ('dui', 'kan'),
    ('dui', 'xun'), ('gen', 'zhen'), ('li', 'kan'), ('kan', 'li'),
]
assert len(set(_KING_WEN)) == 64

HEXAGRAMS = range(0x4DC0, 0x4E00)


def hexagram(cp: int) -> np.ndarray:
    lower, upper = _KING_WEN[cp - HEXAGRAMS.start]
    lines = _TRIGRAM[lower] + _TRIGRAM[upper]
    cell = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
    x0, x1 = GEO.BODY_X + 1, GEO.BODY_X + GEO.BODY_W - 1   # 13px wide lines
    mid = (x0 + x1) // 2
    bottom = GEO.BODY_Y + GEO.BODY_H - 3
    for i, yang in enumerate(lines):
        y = bottom - 2 * i
        cell[y, x0:x1] = True
        if not yang:
            cell[y, mid - 1:mid + 2] = False
    return cell
