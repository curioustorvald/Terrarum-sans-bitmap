"""
Suggestions: a glyphlette traced from Chiron Hei HK, for the artist to start from.

The editor shows it as an overlay to draw over (Suggest, T); nothing of it reaches a drawing
sheet unless the artist draws it and saves. Tracing a part of Chiron Hei HK makes the result a Modified Version of it under the SIL
Open Font License 1.1, which Terrarum Sans Bitmap is under too: its copyright notice is to be
credited (see CLAUDE.md, ground rules).

How a slot is traced:
  - The part's own ink: characters using the slot (the exemplar first; failing those, other
    sizes of its family) are segmented as reference.py segments them, and the part's ink taken
    at the slot's path. Characters are traced until TRIES of them are clean (at most LOOK), and
    the one that agrees best with the others wins (the medoid, weighing each by how clean its
    cut was, and less for every piece (contour) more or fewer than the part has on its own, a
    sign of a wrong cut): a cut in the wrong place may look clean, but it is outvoted.
  - Where it goes: the part's space in Chiron Hei HK (geometry._child_spaces: to the middle of
    the gaps to its neighbours) maps onto the slot's box, as the layout maps it, except along
    an axis where the ink all but fills its space (FILL): there the ink fills the box, as drawn
    glyphlettes do (月 釒 隹 7px wide in 7x15); a part that floats in its space keeps its margins
    (口 left of 吃). A frame's inside maps onto the frame's inner box.
  - Strokes: the ink is thinned to its centrelines, spurs pruned and stroke ends extended by
    the half stroke width that thinning eats.
  - Grid fitting, as hinting does: the centrelines that run straight across (horizontal
    strokes) are snapped to rows and those that run down (vertical strokes) to columns, as near
    their places as they can be with a blank pixel between parallel strokes that face each
    other; the rest of the ink is warped along with them, piecewise linearly. A frame's walls
    stay clear of its inner box.
  - The warped centrelines are drawn 1px wide; a diagonal step that came out as an L loses
    its corner, and 2x2 clumps are thinned.
"""

import math
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np
from scipy import ndimage

import geometry as GEO
import ids as IDS
import reference as R
from geometry import SlotKey

TRIES = 8              # clean views of the part traced; the one agreeing best with the others wins
LOOK = 16              # ... out of at most this many characters
STRAIGHT = 20          # degrees: a centreline this close to an axis runs along it (a stroke level)
COHERENT = 0.7         # ... if it is that straight around it (junctions and dots are not)
WINDOW = 5             # render px: the neighbourhood that tells a centreline's direction
MIN_LEVEL = 1.0        # box px: shorter straight runs are no stroke levels (dots, hooks)
SAME_LEVEL = 0.5       # box px: levels this close are one (a bar crossed by a stem: 十)
FACING = 0.5           # box px: levels overlapping this much along the other axis face each other
CROWD_COST = 2.0       # facing levels on neighbouring rows (no blank between)
MERGE_COST = 20.0      # facing levels on one row
EDGE = 0.6             # box px: ink this close outside the box is a stroke ending at its edge
FILL = 0.8             # ink filling this much of its space along an axis fills the box along it
EXTRA_PIECE = 0.5      # a traced part's weight, per piece (contour) more or fewer than the part has on its own
OWN_QUALITY = 0.3      # the pick: disagreement with the others plus this much of its own uncleanness


@dataclass
class Suggestion:
    ink: np.ndarray       # cell-sized mask
    source: str           # the character traced
    quality: float        # of its measurement: 1 for a clean cut


class Tracer:
    def __init__(self, layout, font_path: str = None):
        self.layout = layout
        path = font_path or R.find_font()
        if path is None:
            raise FileNotFoundError("Chiron Hei HK is not found (sources/Chiron Hei HK/ or installed)")
        self.renderer = R.Renderer(path)
        self._measured: Dict[str, Tuple[Dict[str, R.Box], Dict[str, np.ndarray]]] = {}

    # -- the part --------------------------------------------------------------

    def suggest(self, key: SlotKey, chars: List[str], at: Tuple[int, int]) -> Optional[Suggestion]:
        """The traced glyphlette for `key`, its box at `at` in the cell, from the characters
        `chars` (using the slot, or failing that another size of its family)."""
        traced = []
        alone = self.renderer.pieces(key.comp) if not key.role else None
        for ch in [c for c in dict.fromkeys(chars) if c]:
            found = self._part(ch, key)
            if found is None or found[-1] <= 0:
                continue
            mask, space, inner, quality, pieces = found
            if alone is not None:
                # more or fewer pieces (contours) than the part has on its own: likely cut
                # wrongly, with a neighbour's stroke or without one of its own
                quality *= EXTRA_PIECE ** abs(pieces - alone)
            ink = self._trace(key, mask, space, inner, *at)
            if ink.any():
                traced.append(Suggestion(ink, ch, quality))
            # enough clean views of the part; else go on to other characters (up to LOOK)
            if sum(t.quality >= 0.5 for t in traced) >= TRIES or len(traced) >= LOOK:
                break
        if not traced:
            return None
        near = [ndimage.binary_dilation(t.ink, np.ones((3, 3))) for t in traced]

        def apart(i, j):          # the share of either's ink not within a pixel of the other's
            a, b = traced[i].ink, traced[j].ink
            return 1 - ((a & near[j]).sum() + (b & near[i]).sum()) / (a.sum() + b.sum())

        def score(i):             # disagreement with the others, by their quality, and its own
            others = [j for j in range(len(traced)) if j != i]
            wsum = sum(traced[j].quality for j in others)
            agree = sum(traced[j].quality * apart(i, j) for j in others) / wsum if wsum else 0.0
            return agree + OWN_QUALITY * (1 - traced[i].quality)
        return traced[min(range(len(traced)), key=lambda i: (score(i), -traced[i].quality))]

    def _measure(self, ch: str):
        if ch not in self._measured:
            masks: Dict[str, np.ndarray] = {}
            counts: Dict[str, int] = {}
            boxes = dict(R.measure(self.layout, self.renderer, ch, masks, counts))
            self._measured[ch] = (boxes, masks, counts)
            if len(self._measured) > 256:
                self._measured.pop(next(iter(self._measured)))
        return self._measured[ch]

    def _part(self, ch: str, key: SlotKey):
        """(mask, space, inner space, quality, pieces) of `key` in `ch` (or of the nearest size
        of its component in the same role), or None."""
        path = _find(self.layout, ch, key)
        if path is None:
            return None
        boxes, masks, counts = self._measure(ch)
        if path not in masks:
            return None
        # the space down the path, and the quality of every cut on the way
        space, quality, inner = (0.0, 0.0, 1.0, 1.0), 1.0, None
        steps = path.split('.') if path else []
        for depth, k in enumerate(steps):
            parent = '.'.join(steps[:depth])
            box = boxes.get(parent)
            node = self.layout.decomposition(box.comp) if box else None
            if box is None or isinstance(node, str):
                return None
            kids = [boxes.get(f"{parent}.{i}" if parent else str(i)) for i in range(len(node) - 1)]
            if any(b is None for b in kids):
                return None
            spaces = GEO._child_spaces(node[0], space, kids, self.layout.overlap_min, box.bounds)
            if key.role and depth == len(steps) - 1:
                inner = spaces[1]
            space = spaces[int(k)]
            kid = kids[int(k)]
            quality *= (0.5 if kid.ambiguous else 1.0) * max(0.0, 1 - 2 * kid.cut)
        mask = masks[path]
        if not key.role:
            # where the ink all but fills its space, it fills the box (drawn glyphlettes do)
            fx0, fy0, fx1, fy1 = self.renderer.face
            ys, xs = np.nonzero(mask)
            ink = ((xs.min() - fx0) / (fx1 - fx0), (ys.min() - fy0) / (fy1 - fy0),
                   (xs.max() + 1 - fx0) / (fx1 - fx0), (ys.max() + 1 - fy0) / (fy1 - fy0))
            space = list(space)
            for a, b in ((0, 2), (1, 3)):
                if ink[b] - ink[a] >= FILL * (space[b] - space[a]):
                    space[a], space[b] = ink[a], ink[b]
            space = tuple(space)
        return mask, space, inner, quality, counts[path]

    # -- tracing ---------------------------------------------------------------

    def _trace(self, key: SlotKey, mask: np.ndarray, space, inner, bx: int, by: int) -> np.ndarray:
        fx0, fy0, fx1, fy1 = self.renderer.face
        sx0, sy0, sx1, sy1 = space
        # render px -> box px: the part's space onto its box
        ax = key.w / ((sx1 - sx0) * (fx1 - fx0))
        ay = key.h / ((sy1 - sy0) * (fy1 - fy0))
        to_x = lambda px: (px - fx0 - sx0 * (fx1 - fx0)) * ax
        to_y = lambda py: (py - fy0 - sy0 * (fy1 - fy0)) * ay

        skel, dist = _centrelines(mask)
        ys, xs = np.nonzero(skel)
        if not len(ys):
            return np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
        ex_y, ex_x = _end_extensions(skel, dist)
        py = to_y(np.concatenate([ys, ex_y]) + 0.5)
        px = to_x(np.concatenate([xs, ex_x]) + 0.5)

        # the frame's inner box, and where the reference has it
        fixed_x, fixed_y = [(0.0, 0.0), (key.w * 1.0, key.w * 1.0)], [(0.0, 0.0), (key.h * 1.0, key.h * 1.0)]
        walls = {}
        if key.role and inner is not None:
            l, t, r, b = self.layout.insets(key.comp, key.role, key.w, key.h)
            covers = GEO.FRAME_COVERS.get(key.role, '')
            ix0, iy0 = to_x(fx0 + inner[0] * (fx1 - fx0)), to_y(fy0 + inner[1] * (fy1 - fy0))
            ix1, iy1 = to_x(fx0 + inner[2] * (fx1 - fx0)), to_y(fy0 + inner[3] * (fy1 - fy0))
            if 'l' in covers:
                fixed_x.append((ix0, float(l)))
                walls['l'] = (ix0, l)
            if 'r' in covers:
                fixed_x.append((ix1, float(key.w - r)))
                walls['r'] = (ix1, key.w - r)
            if 't' in covers:
                fixed_y.append((iy0, float(t)))
                walls['t'] = (iy0, t)
            if 'b' in covers:
                fixed_y.append((iy1, float(key.h - b)))
                walls['b'] = (iy1, key.h - b)

        horiz, vert = _levels(skel, ys, xs, to_x, to_y)
        rows = _fit(horiz, key.h, -by, GEO.CELL_H - 1 - by, walls.get('t'), walls.get('b'))
        cols = _fit(vert, key.w, -bx, GEO.CELL_W - 1 - bx, walls.get('l'), walls.get('r'))
        wy = _warp([(p, r + 0.5) for (p, _, _, _), r in zip(horiz, rows)], fixed_y)
        wx = _warp([(p, c + 0.5) for (p, _, _, _), c in zip(vert, cols)], fixed_x)

        out = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
        cy = np.floor(_to_edge(wy(py), key.h)).astype(int) + by
        cx = np.floor(_to_edge(wx(px), key.w)).astype(int) + bx
        # within the body: the assembler clips everything else
        ok = (cy >= GEO.BODY_Y) & (cy < GEO.BODY_Y + GEO.BODY_H) & (cx >= GEO.BODY_X) & (cx < GEO.BODY_X + GEO.BODY_W)
        out[cy[ok], cx[ok]] = True
        if key.role and inner is not None:
            l, t, r, b = self.layout.insets(key.comp, key.role, key.w, key.h)
            out[by + t:by + key.h - b, bx + l:bx + key.w - r] = False
        return _clean(out)


def _find(layout, ch: str, key: SlotKey) -> Optional[str]:
    """The path of the slot `key` in the layout of `ch`, or of its component in the same role
    at the nearest size."""
    found = []

    def walk(k, path):
        if k.comp == key.comp and k.role == key.role:
            found.append((abs(k.w - key.w) + abs(k.h - key.h), path))
        for i, (c, _, _) in enumerate(layout.slot(k).parts or ()):
            walk(c, f"{path}.{i}" if path else str(i))

    if len(ch) != 1:
        return None
    walk(SlotKey(ch, GEO.BODY_W, GEO.BODY_H), '')
    return min(found)[1] if found else None


# ---------------------------------------------------------------------------
# Centrelines

def _neighbours(p: np.ndarray):
    """The 8 neighbours of every pixel, clockwise from north (P2..P9 of Zhang & Suen)."""
    q = np.pad(p, 1)
    return [q[:-2, 1:-1], q[:-2, 2:], q[1:-1, 2:], q[2:, 2:], q[2:, 1:-1], q[2:, :-2], q[1:-1, :-2], q[:-2, :-2]]


def _thin(mask: np.ndarray) -> np.ndarray:
    """Zhang-Suen thinning to 1px centrelines."""
    m = mask.astype(np.uint8)
    while True:
        changed = False
        for step in (0, 1):
            n = _neighbours(m)
            b = sum(x.astype(np.int32) for x in n)
            seq = n + [n[0]]
            a = sum(((seq[i] == 0) & (seq[i + 1] == 1)).astype(np.int32) for i in range(8))
            p2, p4, p6, p8 = n[0], n[2], n[4], n[6]
            if step == 0:
                c = ((p2 & p4 & p6) == 0) & ((p4 & p6 & p8) == 0)
            else:
                c = ((p2 & p4 & p8) == 0) & ((p2 & p6 & p8) == 0)
            gone = (m == 1) & (b >= 2) & (b <= 6) & (a == 1) & c
            if gone.any():
                m[gone] = 0
                changed = True
        if not changed:
            return m.astype(bool)


def _count(skel: np.ndarray) -> np.ndarray:
    return sum(x.astype(np.int32) for x in _neighbours(skel.astype(np.uint8)))


_STEPS = [(-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1)]


def _walk_from(skel: np.ndarray, y: int, x: int, limit: int) -> Tuple[List[Tuple[int, int]], bool]:
    """Follow a centreline from an end: (its pixels up to a junction or `limit`, reached a junction)."""
    h, w = skel.shape
    path, prev = [(y, x)], None
    while len(path) <= limit:
        cy, cx = path[-1]
        nxt = [(cy + dy, cx + dx) for dy, dx in _STEPS
               if 0 <= cy + dy < h and 0 <= cx + dx < w and skel[cy + dy, cx + dx]
               and (cy + dy, cx + dx) != prev and (cy + dy, cx + dx) not in path]
        if len(nxt) != 1:
            return path, len(nxt) > 1
        prev = path[-1]
        path.append(nxt[0])
    return path, False


def _centrelines(mask: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """(centrelines with spurs pruned, distance of each pixel to the background)."""
    dist = ndimage.distance_transform_edt(mask)
    skel = _thin(mask)
    width = 2 * float(np.median(dist[skel])) if skel.any() else 0
    # a branch shorter than a stroke is wide, from an end to a junction, is a spur of a corner
    ends = np.argwhere(skel & (_count(skel) == 1))
    for y, x in ends:
        path, junction = _walk_from(skel, int(y), int(x), int(width * 1.5) + 1)
        if junction and len(path) < width * 1.5:
            for p in path[:-1]:
                skel[p] = False
    return skel, dist


def _end_extensions(skel: np.ndarray, dist: np.ndarray) -> Tuple[np.ndarray, np.ndarray]:
    """Points that carry each stroke end on to where its ink ends: thinning stops half a
    stroke short of it."""
    ys, xs = [], []
    for y, x in np.argwhere(skel & (_count(skel) == 1)):
        path, _ = _walk_from(skel, int(y), int(x), 6)
        if len(path) < 3:
            continue
        dy, dx = path[0][0] - path[-1][0], path[0][1] - path[-1][1]
        n = math.hypot(dy, dx)
        reach = float(dist[y, x])
        for t in np.arange(1.0, reach + 0.01, 0.5):
            ys.append(y + dy / n * t)
            xs.append(x + dx / n * t)
    return np.array(ys), np.array(xs)


# ---------------------------------------------------------------------------
# Grid fitting

def _levels(skel, ys, xs, to_x, to_y):
    """The straight runs of the centrelines along each axis, merged where they line up:
    ([(position, weight, start, end)] across, [...] down), in box px, positions centred."""
    r = WINDOW
    oy, ox = np.mgrid[-r:r + 1, -r:r + 1]
    disc = (oy ** 2 + ox ** 2) <= r * r
    s = skel.astype(float)
    sxx = ndimage.correlate(s, (ox ** 2 * disc).astype(float), mode='constant')
    syy = ndimage.correlate(s, (oy ** 2 * disc).astype(float), mode='constant')
    sxy = ndimage.correlate(s, (ox * oy * disc).astype(float), mode='constant')
    angle = np.degrees(0.5 * np.arctan2(2 * sxy, sxx - syy))
    coherence = np.sqrt((sxx - syy) ** 2 + 4 * sxy ** 2) / np.maximum(sxx + syy, 1e-9)
    straight = skel & (coherence >= COHERENT)
    out = []
    for kind in ('h', 'v'):
        sel = straight & ((np.abs(angle) <= STRAIGHT) if kind == 'h' else (np.abs(angle) >= 90 - STRAIGHT))
        lab, n = ndimage.label(sel, structure=np.ones((3, 3)))
        runs = []
        for i in range(1, n + 1):
            yy, xx = np.nonzero(lab == i)
            along = to_x(xx + 0.5) if kind == 'h' else to_y(yy + 0.5)
            across = to_y(yy + 0.5) if kind == 'h' else to_x(xx + 0.5)
            a, b = float(along.min()), float(along.max())
            if b - a >= MIN_LEVEL:
                runs.append([float(np.median(across)), b - a, a, b])
        runs.sort()
        merged = []
        for run in runs:
            if merged and run[0] - merged[-1][0] < SAME_LEVEL:
                m = merged[-1]
                wsum = m[1] + run[1]
                merged[-1] = [(m[0] * m[1] + run[0] * run[1]) / wsum, wsum, min(m[2], run[2]), max(m[3], run[3])]
            else:
                merged.append(run)
        out.append([tuple(m) for m in merged])
    return out[0], out[1]


def _fit(levels, extent: int, lo_cell: int, hi_cell: int, near=None, far=None) -> List[int]:
    """Whole rows (or columns) for stroke levels: each near its position, in order, with a
    blank between levels that face each other (or a cost for none). `near`/`far`: a frame's
    wall before and after the inner box, (position in the reference, inner box edge)."""
    if not levels:
        return []
    ranges = []
    for p, _, _, _ in levels:
        lo, hi = (0, extent - 1) if -0.5 <= p <= extent + 0.5 else (lo_cell, hi_cell)
        if near is not None and p < near[0]:
            hi = min(hi, near[1] - 2)          # the wall, then a gap, then the inner box
        if far is not None and p > far[0]:
            lo = max(lo, far[1] + 1)
        ranges.append((lo, max(lo, hi)))
    n = len(levels)
    span = range(lo_cell, hi_cell + 1)
    inf = float('inf')
    cost = [{r: inf for r in span} for _ in range(n)]
    back = [{} for _ in range(n)]
    for i, (p, wt, a, b) in enumerate(levels):
        weight = 1 + wt / max(extent, 1)
        lo, hi = ranges[i]
        for r in range(lo, hi + 1):
            here = weight * (r + 0.5 - p) ** 2
            if i == 0:
                cost[i][r] = here
                continue
            pp, _, pa, pb = levels[i - 1]
            facing = min(b, pb) - max(a, pa) >= FACING
            best, arg = inf, None
            for q, c in cost[i - 1].items():
                if c == inf or q > r:
                    continue
                gap = r - q
                extra = 0.0
                if facing:
                    extra = MERGE_COST if gap == 0 else CROWD_COST if gap == 1 else 0.0
                if c + extra < best:
                    best, arg = c + extra, q
            if arg is not None:
                cost[i][r] = best + here
                back[i][r] = arg
    r = min(cost[-1], key=cost[-1].get)
    if cost[-1][r] == inf:
        return [int(min(max(round(p - 0.5), lo), hi)) for (p, _, _, _), (lo, hi) in zip(levels, ranges)]
    out = [r]
    for i in range(n - 1, 0, -1):
        r = back[i][r]
        out.append(r)
    return out[::-1]


def _to_edge(v: np.ndarray, extent: int) -> np.ndarray:
    """A stroke ending at the edge of its space ends at the edge of its box, not a pixel past
    it: ink within EDGE px outside the box is drawn on its last row (or column)."""
    v = np.where((v >= extent) & (v < extent + EDGE), extent - 1e-3, v)
    return np.where((v < 0) & (v > -EDGE), 0.0, v)


def _warp(anchors, fixed):
    """A monotone piecewise linear map through the levels' anchors, and through the fixed
    anchors (the box's edges, a frame's inner box) where no level is near; slope 1 beyond."""
    pts = list(anchors) + [f for f in fixed if all(abs(f[0] - a[0]) > 0.75 for a in anchors)]
    pts.sort()
    xs, ys = [], []
    for x, y in pts:
        if xs and x - xs[-1] < 1e-6:
            continue
        xs.append(x)
        ys.append(max(y, ys[-1]) if ys else y)
    xs, ys = np.array(xs), np.array(ys)

    def f(v):
        v = np.asarray(v, dtype=float)
        out = np.interp(v, xs, ys)
        out = np.where(v < xs[0], ys[0] + (v - xs[0]), out)
        return np.where(v > xs[-1], ys[-1] + (v - xs[-1]), out)
    return f


# ---------------------------------------------------------------------------
# Clean-up

def _clean(ink: np.ndarray) -> np.ndarray:
    """A diagonal step drawn as an L loses its corner (`##. / .##` -> `#.. / .##`), unless the
    L is a corner of straight strokes; 2x2 clumps lose a pixel that nothing else needs."""
    ink = ink.copy()
    h, w = ink.shape
    get = lambda y, x: 0 <= y < h and 0 <= x < w and ink[y, x]
    changed = True
    while changed:
        changed = False
        for y, x in np.argwhere(ink):
            nb = [(dy, dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1) if (dy or dx) and get(y + dy, x + dx)]
            if len(nb) != 2:
                continue
            (ay, ax), (cy, cx) = nb
            # one horizontal and one vertical neighbour, each a stub (no run beyond)
            if {abs(ay), abs(cy)} != {0, 1} or {abs(ax), abs(cx)} != {0, 1} or (ay == 0) == (cy == 0):
                continue
            hy, hx = (ay, ax) if ay == 0 else (cy, cx)
            vy, vx = (cy, cx) if ay == 0 else (ay, ax)
            if get(y, x + 2 * hx) or get(y + 2 * vy, x):
                continue
            ink[y, x] = False
            changed = True
        for y in range(h - 1):
            for x in range(w - 1):
                if ink[y:y + 2, x:x + 2].all():
                    for dy, dx in ((0, 0), (0, 1), (1, 0), (1, 1)):
                        if _removable(ink, y + dy, x + dx):
                            ink[y + dy, x + dx] = False
                            changed = True
                            break
    return ink


def _removable(ink: np.ndarray, y: int, x: int) -> bool:
    """Whether a pixel can go without splitting its neighbours apart (8-connected) or cutting
    a stroke short."""
    h, w = ink.shape
    win = np.zeros((3, 3), dtype=bool)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if 0 <= y + dy < h and 0 <= x + dx < w:
                win[dy + 1, dx + 1] = ink[y + dy, x + dx]
    win[1, 1] = False
    if win.sum() < 2:
        return False
    _, n = ndimage.label(win, structure=np.ones((3, 3)))
    return n == 1
