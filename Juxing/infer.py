"""
Layout preferences inferred from the reference font: model/inferred.tsv.

The reference font (reference.py, model/reference.tsv) is Juxing's visual target: how big
a part is and where it sits. Lai, Yeung & Pong's paper is the source of the system's
design (slots, splits, frames, the evenness metrics and search), but the numbers the
design needs come from the reference, not from guesses:

  left/right/top/bottom  the size of a component as the first or last part of a ⿰⿲ or
                         ⿱⿳, in px of a 15px box: the share of its parent's space its own
                         space takes, where a split divides the space at the middle of the
                         gaps between the parts' ink. The geometry uses it where the
                         reference has not measured the parent (Layout.split). Twins
                         (林 昌) are left out: they split evenly anyway.
  frame                  the inner box of a surround frame, as insets at 15x15: its inside's
                         space (to the middle of the gap to each wall, the node's edge on open
                         sides; geometry._child_spaces), rounded so that the inner box is
                         odd-sized, walls at least 2px. Only frames 11px or larger count
                         (MIN_FRAME): insets shrink with smaller frames, their strokes don't.
  place                  where a component's ink sits in its space, per context (left right
                         middle top bottom inner), for the evenness pass's rule-based metric
                         on parts the reference has not measured in their character.

Each value is the weighted median over every measurement in every character: a measurement
counts less the more of its parent's ink its cut went through, and half if the cut was
ambiguous (as in Layout.ink_size). Values with less than MIN_WEIGHT measurements' worth
are left out.

`levels` is not inferred: it says how many 1px strokes a component needs at 15px, which
a font drawn at high resolution does not tell (counting strokes on its rendering counts
every crossing of a curve: 乡 5 rows, 飠 7). It stays in layout.tsv with the other hand
settings, which override inferred values one by one.
"""

import os
from collections import defaultdict
from typing import Dict, List, Tuple

import numpy as np

import geometry as GEO
import ids as IDS

HERE = os.path.dirname(os.path.abspath(__file__))
INFERRED_PATH = os.path.join(HERE, 'model', 'inferred.tsv')

MIN_WEIGHT = 3.0
# Frame insets are given for a 15x15 frame and shrink with it, but its strokes don't (an
# inset never goes below 2px: Layout.insets), so only frames about as large as that tell
# them: a nested 囗 5px wide spends half its width on walls.
MIN_FRAME = 11 / GEO.REF
SIDES = {True: ('left', 'right'), False: ('top', 'bottom')}      # by horizontal


def _quality(box) -> float:
    return (0.5 if box.ambiguous else 1.0) * max(0.0, 1 - 2 * box.cut)


def collect(layout):
    """The raw measurements: sides[(comp, side)], places[(comp, context)] and
    insets[(comp, op)], each a list of (value, weight)."""
    sides: Dict[Tuple[str, str], List] = defaultdict(list)
    places: Dict[Tuple[str, str], List] = defaultdict(list)
    insets: Dict[Tuple[str, str], List] = defaultdict(list)

    for ch, nodes in layout.reference.data.items():
        def visit(path, space):
            box = nodes.get(path)
            if box is None or layout.never_split(box.comp) is not None:
                return
            node = layout.decomposition(box.comp)
            if isinstance(node, str):
                return
            op, n = node[0], len(node) - 1
            kids = [nodes.get(f"{path}.{k}" if path else str(k)) for k in range(n)]
            if any(k is None for k in kids):
                return
            spaces = GEO._child_spaces(op, space, kids, layout.overlap_min, box.bounds)
            sx0, sy0, sx1, sy1 = space
            if op in IDS.SPLIT_H or op in IDS.SPLIT_V:
                horizontal = op in IDS.SPLIT_H
                extent = (sx1 - sx0) if horizontal else (sy1 - sy0)
                twins = len({GEO.base_form(k.comp) for k in kids}) == 1
                first, last = SIDES[horizontal]
                for k, (kb, sp) in enumerate(zip(kids, spaces)):
                    q = _quality(kb)
                    if q <= 0 or extent <= 0:
                        continue
                    share = ((sp[2] - sp[0]) if horizontal else (sp[3] - sp[1])) / extent
                    if not twins and k == 0:
                        sides[(kb.comp, first)].append((share * GEO.REF, q))
                    if not twins and k == n - 1:
                        sides[(kb.comp, last)].append((share * GEO.REF, q))
                    context = first if k == 0 else last if k == n - 1 else 'middle'
                    _place(places, kb, sp, context, q)
            elif op in IDS.SURROUND and n == 2:
                frame, inner = kids
                q = min(_quality(frame), _quality(inner))
                w, h = sx1 - sx0, sy1 - sy0
                ix0, iy0, ix1, iy1 = spaces[1]
                if q > 0 and w >= MIN_FRAME and h >= MIN_FRAME:
                    insets[(frame.comp, op)].append((
                        ((ix0 - sx0) / w * GEO.REF, (iy0 - sy0) / h * GEO.REF,
                         (sx1 - ix1) / w * GEO.REF, (sy1 - iy1) / h * GEO.REF), q))
                    _place(places, inner, spaces[1], 'inner', q)
            for k, sp in enumerate(spaces):
                visit(f"{path}.{k}" if path else str(k), sp)

        visit('', (0.0, 0.0, 1.0, 1.0))
    return sides, places, insets


def _place(places, box, space, context, q):
    x0, y0, x1, y1 = space
    if q > 0 and x1 > x0 and y1 > y0:
        places[(box.comp, context)].append((
            (((box.x0 + box.x1) / 2 - x0) / (x1 - x0), ((box.y0 + box.y1) / 2 - y0) / (y1 - y0)), q))


def _median(entries, axis=None) -> Tuple[float, float]:
    """Weighted median of the values (or of one axis of tuple values), and the total weight."""
    vals = np.array([v if axis is None else v[axis] for v, _ in entries], dtype=float)
    wts = np.array([w for _, w in entries], dtype=float)
    return GEO._weighted_median(vals, wts), float(wts.sum())


def _round_pair(a: float, b: float, walled: Tuple[bool, bool]) -> Tuple[int, int]:
    """Whole-pixel insets nearest (a, b) leaving an odd inner span of the 15px box: an even
    sum. Walls need 2px at least (a stroke and a gap); open sides are 0. Ties go to the
    larger insets, clearer of the frame."""
    lo = [2 if wl else 0 for wl in walled]
    cands = []
    for x in range(lo[0], GEO.REF):
        for y in range(lo[1], GEO.REF):
            if (not walled[0] and x) or (not walled[1] and y):
                continue
            if (x + y) % 2 or GEO.REF - x - y < GEO.MIN_INNER:
                continue
            cands.append(((x - a) ** 2 + (y - b) ** 2, -(x + y), x, y))
    _, _, x, y = min(cands)
    return x, y


def infer(layout):
    """{comp: {'left': .., 'right': .., 'top': .., 'bottom': .., 'frame': {op: insets},
    'place': {context: (x, y)}, 'n': {what: weight}}}"""
    sides, places, insets = collect(layout)
    out: Dict[str, Dict] = defaultdict(lambda: {'frame': {}, 'place': {}, 'n': {}})
    for (comp, side), entries in sides.items():
        v, wt = _median(entries)
        if wt >= MIN_WEIGHT:
            out[comp][side] = round(v, 1)
            out[comp]['n'][side] = wt
    for (comp, context), entries in places.items():
        (x, wt), (y, _) = _median(entries, 0), _median(entries, 1)
        if wt >= MIN_WEIGHT:
            out[comp]['place'][context] = (round(min(1.0, max(0.0, x)), 2), round(min(1.0, max(0.0, y)), 2))
            out[comp]['n'][context] = wt
    for (comp, op), entries in insets.items():
        meds = [_median(entries, k)[0] for k in range(4)]
        wt = sum(w for _, w in entries)
        if wt < MIN_WEIGHT:
            continue
        walls = GEO.FRAME_COVERS.get(op, '')
        l, r = _round_pair(meds[0], meds[2], ('l' in walls, 'r' in walls))
        t, b = _round_pair(meds[1], meds[3], ('t' in walls, 'b' in walls))
        out[comp]['frame'][op] = (l, t, r, b)
        out[comp]['n'][op] = wt
    return dict(out)


def save(prefs: Dict[str, Dict], font: str, path: str = INFERRED_PATH):
    def usage(item):
        return -sum(item[1]['n'].values())

    with open(path + '.part', 'w', encoding='utf-8', newline='\n') as f:
        f.write("# Juxing layout preferences -- GENERATED by `juxing.py infer` from model/reference.tsv; do not edit.\n")
        f.write(f"# Inferred from the part boxes measured in {font} (see reference.tsv for its attribution);\n")
        f.write("# values in layout.tsv override these one by one. Columns as in layout.tsv:\n")
        f.write("#   left/right/top/bottom  size as the first/last part of a split, px of a 15px box\n")
        f.write("#   frame                  inner-box insets as a surround frame at 15x15\n")
        f.write("#   place                  where the ink sits in its space, per context (fractions)\n")
        f.write("#   note                   the measurements' worth behind each value\n")
        f.write("#comp\tleft\tright\ttop\tbottom\tframe\tlevels\tplace\tnote\n")
        for comp, p in sorted(prefs.items(), key=usage):
            sides = [f"{p[s]:g}" if s in p else '-' for s in ('left', 'right', 'top', 'bottom')]
            frame = ' '.join(f"{op}{','.join(map(str, ins))}" for op, ins in sorted(p['frame'].items())) or '-'
            place = ' '.join(f"{c}:x={x:.2f},y={y:.2f}" for c, (x, y) in sorted(
                p['place'].items(), key=lambda kv: GEO.PLACE_CONTEXTS.index(kv[0]))) or '-'
            note = ' '.join(f"{k}={v:.0f}" for k, v in p['n'].items())
            f.write('\t'.join([comp] + sides + [frame, '-', place, note]) + '\n')
    os.replace(path + '.part', path)
