"""
Stroke-preserving resizing: derives the sizes of a component from one drawing.

A component is drawn once per *family* (glyphlettes.family: component, frame role and
aspect class) at the family's largest size, the *base glyphlette*. Every other size of
the family is derived from it here, unless an *override* (a hand drawing of that exact
size) exists. Overrides are sources too: a size is derived from the closest drawing of
its family that is at least as large (glyphlettes.Library).

Glyphlettes are 1px line drawings, so they are resized by mapping whole rows and
columns rather than by resampling:

  - Every source row goes to one target row, in order. Shrinking merges adjacent rows
    (their ink is OR-ed together); growing inserts a row between two, through which
    vertical strokes continue and across which slants take a step. Columns likewise,
    independently of the rows.
  - Strokes stay 1px wide: merging two rows shortens the vertical strokes crossing both
    by a pixel and leaves horizontal ones alone; a slant gets a 2px step.

A stroke is treated as a stroke: a diagonal step stays diagonal. Where the drawing
steps `##.. / ..#. / ...#`, the 3px version is `#.. / .#. / ..#` (the first run a pixel
shorter), never `##. / .#. / ..#` (an L) nor `##. / ..# / ..#` (the step turned down).

Which rows merge (or grow) is chosen per axis by trying every choice (a box is at most
15px across) and scoring it:

  spacing  the distances between consecutive rows with stroke features (horizontal
           runs of 2px or longer, stroke ends) away from proportional, and likewise
           the margins to the box. Keeps parallel strokes evenly spaced (目 stays
           even) and empty margins go first.
  place    features away from where proportional scaling puts them (weaker; keeps
           the drawing balanced in its box)
  collide  two rows that both have horizontal runs, one above the other, merged into
           one (a corner lost), or runs brought next to each other by merging the
           rows between them
  short    a run along the axis shrunk to a single pixel, in proportion to the pixels
           lost: W_SHORT for a run with something on its side (the crossing of 撇 and 点
           over the stem of 木, a corner) or a tick on its own; W_STEP for a step of a
           slant (it continues diagonally and nothing touches its sides: a slope is
           rasterised so), W_HEAD for the step a stroke starts or ends with (the head
           `##` of a dot). Between steps otherwise alike, the lower or further right one
           goes first (EARLIER): strokes mostly start at the top or the left.
  tail     a stub's extent changed (a 1px serif lost or grown)
  jog      a row inserted where a slant crosses (the slant gets a step)
  grow     a row inserted inside a stub (a 1px serif would become 2px)
  mirror   a lopsided choice for a symmetric drawing (大 火 木 stay symmetric)
and, as hints that only rank the maps (the other axis may undo what they measure), the
diagonal steps merged across, the Ls a merge makes, and diagonal stroke ends flattened.

The best row maps and the best column maps are then combined, cheapest first. A
combination is acceptable only if it keeps the drawing's topology: strokes that are
apart stay apart (no two 8-connected components merge) and counters stay open (the
Euler number, hence the number of enclosed holes, is unchanged). It may not have more
2x2 clumps of ink than the source. Its result is cleaned up and scored:

  unkink   an L made where the source stepped diagonally loses its corner pixel
           (`##. / .#.` -> `#.. / .#.`), unless its source lines hold an L too (the
           corner of 口), the pixel lies inside a straight run (the bar of 女 where slants
           cross it), or its removal would change the topology or lose a stroke end.
           Each pixel taken out costs W_HEAD (it shortens a run).
  ends     W_ENDS for every stroke end lost (a dot or hook swallowed by its stroke)
  kinks    W_KINK for every L left that the source doesn't have
  turn     W_TURN for every diagonal step of the source whose image is straight (merged
           across on one axis only; merged on both, a step has only become shorter)
  tails    W_TAIL for every diagonal stroke end whose image no longer leaves in its
           direction (the check is on the result: `##. / ..#` keeps a dot's step though
           its tail is a pixel shorter)
Of the first few acceptable combinations the cheapest wins. If none is acceptable, or
the best one costs more than MAX_COST not counting turns and tails (which rank shapes
but are often unavoidable in a tight size: 阝 3px wide can only be `#.#`), the size is
*declined* and needs a drawing of its own; the best result is still returned, as a
starting point for drawing it.

A drawing of separate strokes (氵 冫 讠 灬: 8-connected parts, no ink outside the box, not
left-right symmetric) is resized stroke by stroke (_derive_parts): each part on its own
as above, into a box near its proportional size (a pixel either way, W_SIZE), then the
parts are placed near their proportional places (W_POS). A small part may keep its shape
rather than its proportions: a dot 5x3 in a 3px-wide 氵 is `##. / ..#`, not a slash three
rows tall. The parts may not touch, keep their order along each axis, keep the gaps
between facing parts in proportion (W_GAP), and cost W_NEAR for every pixel newly in line
with a pixel of another part a single pixel away (two stroke ends lined up like that read
as one broken stroke).

Frames resize band by band: the wall bands (the frame insets of the source and the
target size, Layout.insets) and the inner band are mapped separately, so the inside of
the frame lands exactly on the inside of the target. Ink outside the box (protrusions)
keeps its distance from the box.
"""

import atexit
import hashlib
import heapq
import itertools
import os
import pickle
from dataclasses import dataclass
from functools import lru_cache
from typing import List, Optional, Sequence, Tuple

import numpy as np

W_PLACE = 0.3
W_SPACING = 1.0
W_MARGIN = 0.25
W_COLLIDE = 2.0
W_SHORT = 3.0
W_STEP = 1.0            # W_SHORT for a run that is a step of a slant, per pixel lost
W_HEAD = 2.0            # ... for the step a stroke starts or ends with (the head `##` of a dot)
EARLIER = 0.05          # steps nearer the top or the left cost this much more to shrink (at most)
W_TAIL = 4.0            # above W_SHORT: a stroke end keeps its direction before a run keeps its length
W_TAIL_HINT = 1.0       # ranks the maps of an axis by the diagonal ends they may flatten (see _Features)
W_JOG = 0.5
W_MIRROR = 3.0
W_ENDS = 6.0
W_KINK = 5.0            # a diagonal step turned into an L (three pixels of a 2x2 square), on the result
W_KINK_HINT = 1.0       # ranks the maps of an axis by the Ls they may make (the other axis may undo them)
W_TURN = 3.0            # a diagonal step turned straight (a dot's step turned down: `#. / #. / .#`), on the result
W_TURN_HINT = 1.0       # ranks the maps of an axis by the diagonal steps they merge across
MAX_COST = 12.0         # a result that costs more is declined all the same
MIRROR_MIN = 0.7        # a drawing at least this symmetric (share of mirrored ink) keeps its symmetry
TAIL = 2                # pixels in from a stroke end that keep their direction
MAX_MAPS = 20000        # maps of one axis considered (frames multiply the maps of their bands)
MAX_CHECKS = 1500       # maps of one axis checked for topology
TOP_PER_AXIS = 40       # cheapest maps of each axis that keep the topology, combined
MAX_TRIES = 400         # combinations tried before declining
ENOUGH = 16             # acceptable combinations compared

# Drawings of separate strokes (氵 冫 忄 讠 灬) are resized stroke by stroke (_derive_parts)
W_SIZE = 1.0            # a part's size away from its proportional size, per px squared
W_POS = 0.5             # a part's centre away from its proportional place, per px squared
W_GAP = 0.5             # the gap between two facing parts away from proportional, per px squared
W_NEAR = 3.0            # a pixel of one part newly in line with one of another, a pixel apart
PART_SIZES = 1          # sizes tried either side of a part's proportional size
BEAM = 64               # partial placements kept while placing the parts

# the 8 neighbours of a pixel, in order around it
_RING = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))

# Bump when the method or its weights change, so that cached results are recomputed.
VERSION = 13


@dataclass
class Derived:
    mask: np.ndarray    # ink, box at (bx, by); protrusions around it
    bx: int
    by: int
    cost: float
    ok: bool            # False = declined (topology broken or cost > MAX_COST); mask is a draft


@dataclass
class _Map:
    cost: float
    to: np.ndarray          # target line of every source line
    grown: Tuple[int, ...]  # source lines after which a line is inserted
    n: int                  # number of target lines
    hint: float = 0.0       # what the map may cost on the result (Ls, flattened ends): ranks maps only


# ---------------------------------------------------------------------------
# Topology

def _pad(mask: np.ndarray, dtype=bool) -> np.ndarray:
    p = np.zeros((mask.shape[0] + 2, mask.shape[1] + 2), dtype=dtype)
    p[1:-1, 1:-1] = mask
    return p


def euler8(mask: np.ndarray) -> int:
    """Euler number (components - holes) with 8-connected ink, by counting 2x2 bit-quads (Gray 1971)."""
    p = _pad(mask, np.uint8)
    q = p[:-1, :-1] + 2 * p[:-1, 1:] + 4 * p[1:, :-1] + 8 * p[1:, 1:]
    n = np.bincount(q.ravel(), minlength=16)
    n1 = n[1] + n[2] + n[4] + n[8]
    n3 = n[7] + n[11] + n[13] + n[14]
    nd = n[6] + n[9]
    return int(n1 - n3 - 2 * nd) // 4


def clumps(mask: np.ndarray) -> int:
    """Number of 2x2 squares that are all ink."""
    return int((mask[:-1, :-1] & mask[:-1, 1:] & mask[1:, :-1] & mask[1:, 1:]).sum())


def _quads(mask: np.ndarray) -> np.ndarray:
    """Ink pixels in every 2x2 square (h - 1, w - 1)."""
    m = mask.astype(np.int8)
    return m[:-1, :-1] + m[:-1, 1:] + m[1:, :-1] + m[1:, 1:]


def kinks(mask: np.ndarray) -> int:
    """
    Number of 2x2 squares with three pixels of ink: an L. Where a horizontal stroke meets
    a vertical one (the corners of 口) that is how it is drawn; where a stroke steps
    diagonally it is a kink: `##. / .#. / ..#` for the clean `#.. / .#. / ..#`.
    """
    return int((_quads(mask) == 3).sum()) if mask.shape[0] > 1 and mask.shape[1] > 1 else 0


def ends(mask: np.ndarray) -> int:
    """Number of stroke ends (pixels whose ink neighbours form at most one group, see _is_end)."""
    p = _pad(mask)
    h, w = mask.shape
    ring = [p[1 + dy:1 + dy + h, 1 + dx:1 + dx + w] for dy, dx in _RING]
    groups = sum(ring[i] & ~ring[i - 1] for i in range(8))
    count = sum(r.astype(np.int8) for r in ring)
    return int((mask & (groups <= 1) & (count <= 3)).sum())


def components(mask: np.ndarray) -> np.ndarray:
    """8-connected components as a (count, h, w) stack of masks."""
    labels = np.zeros(mask.shape, dtype=np.int32)
    h, w = mask.shape
    n = 0
    for y0, x0 in zip(*np.nonzero(mask)):
        if labels[y0, x0]:
            continue
        n += 1
        labels[y0, x0] = n
        stack = [(y0, x0)]
        while stack:
            y, x = stack.pop()
            for yy in range(max(y - 1, 0), min(y + 2, h)):
                for xx in range(max(x - 1, 0), min(x + 2, w)):
                    if mask[yy, xx] and not labels[yy, xx]:
                        labels[yy, xx] = n
                        stack.append((yy, xx))
    return np.stack([labels == i for i in range(1, n + 1)]) if n else np.zeros((0,) + mask.shape, bool)


def _separate(stack: np.ndarray) -> bool:
    """Whether the components of a stack neither overlap nor touch (8-neighbourhood)."""
    if len(stack) < 2:
        return True
    if (stack.sum(0) > 1).any():
        return False
    lab = np.zeros(stack.shape[1:], dtype=np.int32)
    for i, m in enumerate(stack):
        lab[m] = i + 1
    for a, b in ((lab[:, :-1], lab[:, 1:]), (lab[:-1, :], lab[1:, :]),
                 (lab[:-1, :-1], lab[1:, 1:]), (lab[:-1, 1:], lab[1:, :-1])):
        if ((a > 0) & (b > 0) & (a != b)).any():
            return False
    return True


# ---------------------------------------------------------------------------
# Applying a map

def _continuation(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """
    Ink of a line inserted between lines a and b (last axis runs along the lines):
    strokes crossing straight continue through it, and a slant crossing diagonally
    takes a step under its upper pixel.
    """
    c = a & b
    left = np.zeros_like(b)
    left[..., 1:] = b[..., :-1]         # b has ink at x - 1
    right = np.zeros_like(b)
    right[..., :-1] = b[..., 1:]        # b has ink at x + 1
    near = c.copy()
    near[..., 1:] |= c[..., :-1]
    near[..., :-1] |= c[..., 1:]        # c at x - 1, x or x + 1: already joined
    return c | (a & (left | right) & ~near)


def _apply_rows(stack: np.ndarray, m: _Map) -> np.ndarray:
    """Map the rows (axis 1) of a (k, h, w) stack."""
    out = np.zeros((stack.shape[0], m.n, stack.shape[2]), dtype=bool)
    # rows merged into one target row are consecutive
    starts = np.flatnonzero(np.r_[True, m.to[1:] != m.to[:-1]])
    out[:, m.to[starts]] = np.logical_or.reduceat(stack, starts, axis=1)
    for y in m.grown:
        out[:, m.to[y] + 1] |= _continuation(stack[:, y], stack[:, y + 1])
    return out


def _apply(stack: np.ndarray, rows: _Map, cols: _Map) -> np.ndarray:
    return _apply_rows(_apply_rows(stack, rows).transpose(0, 2, 1), cols).transpose(0, 2, 1)


# ---------------------------------------------------------------------------
# Candidate maps of one axis (the rows; columns are the rows of the transpose)

@lru_cache(maxsize=None)
def _steps(n: int, m: int) -> np.ndarray:
    """
    Every way of mapping n lines onto m, as steps between consecutive lines:
    (choices, n - 1) of 1 (next line), 0 (merged with the previous one) or 2 (a line
    inserted in between).
    """
    k = abs(n - m)
    if n < 1 or k > max(n - 1, 0):
        return np.zeros((0, max(n - 1, 0)), dtype=np.int8)
    combos = list(itertools.combinations(range(n - 1), k))
    s = np.ones((len(combos), n - 1), dtype=np.int8)
    if k:
        idx = np.array(combos, dtype=np.int64)
        s[np.arange(len(combos))[:, None], idx] = 0 if m < n else 2
    return s


def _runs(line: np.ndarray) -> List[Tuple[int, int]]:
    """Maximal runs of ink (start, end inclusive) in a 1-D array."""
    out, start = [], None
    for i, v in enumerate(line):
        if v and start is None:
            start = i
        elif not v and start is not None:
            out.append((start, i - 1))
            start = None
    if start is not None:
        out.append((start, len(line) - 1))
    return out


def _neighbours(ink: np.ndarray, y: int, x: int):
    h, w = ink.shape
    return [(yy, xx) for yy in range(max(y - 1, 0), min(y + 2, h)) for xx in range(max(x - 1, 0), min(x + 2, w))
            if (yy, xx) != (y, x) and ink[yy, xx]]



def _groups(ink: np.ndarray, y: int, x: int) -> Tuple[int, int]:
    """(groups of ink neighbours around a pixel, number of ink neighbours)."""
    h, w = ink.shape
    ring = [0 <= y + dy < h and 0 <= x + dx < w and bool(ink[y + dy, x + dx]) for dy, dx in _RING]
    return sum(1 for i in range(8) if ring[i] and not ring[i - 1]), sum(ring)


def _is_end(ink: np.ndarray, y: int, x: int) -> bool:
    """
    Whether an ink pixel ends a stroke: its neighbours form at most one group around it.
    (Counting neighbours is not enough: a pixel below the corner of a box, like the
    serif of 口, touches two pixels that touch each other.)
    """
    groups, count = _groups(ink, y, x)
    return groups <= 1 and count <= 3


def _tails(ink: np.ndarray) -> List[Tuple[int, int, int, int, bool]]:
    """
    Steps of stroke ends whose extent must not change: (y, x) of the end pixel, of a
    pixel up to TAIL steps in, and whether the end is a stub. Their direction is what
    makes a dot, a hook or a slant tip what it is, so diagonal steps are kept. Straight
    ends are kept only on stubs (a junction within TAIL steps, like the serif of 口): a
    long straight stroke may be shortened at its end like anywhere else, but a stub
    neither vanishes nor grows (serifs are 1px).
    """
    out = []
    for y, x in zip(*np.nonzero(ink)):
        if not _is_end(ink, y, x):
            continue
        path = [(int(y), int(x))]
        stub = False
        while len(path) <= TAIL:
            cy, cx = path[-1]
            nxt = [p for p in _neighbours(ink, cy, cx) if p not in path]
            if not nxt:
                break
            d = (cy - path[-2][0], cx - path[-2][1]) if len(path) > 1 else None
            # keep going the same way; failing that, straight before diagonal
            p = min(nxt, key=lambda p: ((p[0] - cy, p[1] - cx) != d, abs(p[0] - cy) + abs(p[1] - cx), p))
            path.append(p)
            if _groups(ink, *p)[0] >= 3:
                stub = True
                break
        for py, px in path[1:]:
            if stub or (py != path[0][0] and px != path[0][1]):
                out.append(path[0] + (py, px, stub))
    return out


def _is_step(ink: np.ndarray, x: int, y0: int, y1: int) -> bool:
    """Whether the run of column x from row y0 to y1 is a step of a slant: nothing touches
    its sides, and it continues diagonally at one end at least."""
    h, w = ink.shape
    for xx in (x - 1, x + 1):
        if 0 <= xx < w and ink[y0:y1 + 1, xx].any():
            return False
    return any(0 <= yy < h and 0 <= xx < w and ink[yy, xx] for yy in (y0 - 1, y1 + 1) for xx in (x - 1, x + 1))


def _diagonal_ends(ink: np.ndarray) -> List[Tuple[int, int, int, int]]:
    """Stroke ends whose last step is diagonal (dots, hooks, slant tips): (y, x, dy, dx),
    the end and the direction it leaves in."""
    out = []
    for y, x in zip(*np.nonzero(ink)):
        if not _is_end(ink, y, x):
            continue
        nb = _neighbours(ink, y, x)
        if len(nb) == 1 and nb[0][0] != y and nb[0][1] != x:
            out.append((int(y), int(x), int(y - nb[0][0]), int(x - nb[0][1])))
    return out


def _diagonal_steps(ink: np.ndarray) -> List[Tuple[int, int, int, int]]:
    """Pairs of ink pixels that meet diagonally only, a step of a slant: (y, x, y', x'),
    each pair once. Diagonal neighbours that also meet through a common orthogonal
    neighbour (the corner of `#. / ##`) are not a step."""
    out = []
    h, w = ink.shape
    for y, x in zip(*np.nonzero(ink)):
        for dx in (-1, 1):
            yy, xx = y + 1, x + dx
            if yy < h and 0 <= xx < w and ink[yy, xx] and not ink[y, xx] and not ink[yy, x]:
                out.append((int(y), int(x), int(yy), int(xx)))
    return out


def _turned(steps, rows: '_Map', cols: '_Map') -> int:
    """Diagonal steps whose image is straight: merged across on one axis only (a step that
    merges on both axes has only become shorter)."""
    n = 0
    for y0, x0, y1, x1 in steps:
        dy = int(rows.to[y1]) - int(rows.to[y0])
        dx = int(cols.to[x1]) - int(cols.to[x0])
        n += (dy == 0) != (dx == 0)
    return n


def _lost_tails(out: np.ndarray, tails, rows: '_Map', cols: '_Map') -> int:
    """Diagonal stroke ends whose image no longer leaves in their direction (a dot's step
    turned straight: `##.. / ..#. / ...#` -> `##. / ..# / ..#`)."""
    h, w = out.shape
    lost = 0
    for y, x, dy, dx in tails:
        ey, ex = int(rows.to[y]), int(cols.to[x])
        py, px = ey - dy, ex - dx
        if not (0 <= py < h and 0 <= px < w and out[ey, ex] and out[py, px]):
            lost += 1
    return lost


class _Features:
    """What the cost of a row map looks at, measured on the source (lines = rows)."""

    def __init__(self, ink: np.ndarray):
        h, w = ink.shape
        self.weight = np.zeros(h)                   # importance of each row's position
        self.collide = np.zeros(max(h - 1, 0))      # merging rows y and y+1
        self.jog = np.zeros(max(h - 1, 0))          # inserting a row between y and y+1 (slant steps)
        self.grow = np.zeros(max(h - 1, 0))         # inserting a row between y and y+1 (stubs lengthen)
        self.kink = np.zeros(max(h - 1, 0))         # merging rows y and y+1 (diagonal steps become Ls)
        self.diag = np.zeros(max(h - 1, 0))         # diagonal steps between rows y and y+1
        across = [[r for r in _runs(ink[y]) if r[1] > r[0]] for y in range(h)]
        ends = np.zeros_like(ink)
        for y, x in zip(*np.nonzero(ink)):
            ends[y, x] = _is_end(ink, y, x)
        for y in range(h):
            self.weight[y] = len(across[y]) + 0.5 * ends[y].sum() + 0.1 * ink[y].sum()
        # rows that carry a horizontal run or a stroke end: the spacing between them is what reads
        self.marks = [y for y in range(h) if across[y] or ends[y].any()]
        run_mask = np.zeros_like(ink)
        for y in range(h):
            for a, b in across[y]:
                run_mask[y, a:b + 1] = True
        def touching(a: int, b: int) -> int:
            """Pixels of the runs of row a that touch runs of row b once the two rows are adjacent."""
            if a < 0 or b >= h:
                return 0
            near = run_mask[b].copy()
            near[1:] |= run_mask[b][:-1]
            near[:-1] |= run_mask[b][1:]
            return int((run_mask[a] & near).sum())

        for y in range(h - 1):
            # merging rows y and y+1 stacks their runs, and brings the runs of the rows
            # around them together (the horizontal of 木 onto the crossing below it)
            self.collide[y] = touching(y, y + 1) + touching(y - 1, y + 1) + touching(y, y + 2)
            self.jog[y] = (_continuation(ink[y], ink[y + 1]) & ~(ink[y] & ink[y + 1])).sum()
            # the Ls that merging rows y and y+1 makes with the rows around them
            a, b = max(y - 1, 0), min(y + 3, h)
            merged = np.concatenate([ink[a:y], ink[y:y + 1] | ink[y + 1:y + 2], ink[y + 2:b]])
            self.kink[y] = max(0, kinks(merged) - kinks(ink[a:b]))
        for y0, _, y1, _ in _diagonal_steps(ink):
            self.diag[min(y0, y1)] += 1
        # spans along the axis that must not shrink to a single row, with the cost of it:
        # vertical runs of 2px or more (in proportion to the pixels lost), and the last
        # steps of stroke ends that are not horizontal
        # A run that is a step of a slant (it continues diagonally at an end, and nothing
        # touches its sides: the `##` of a dot) may shrink cheaply, a slope is rasterised so;
        # one with a crossing or a corner on its side, or a tick on its own, may not.
        # Between steps otherwise alike, the later one (lower, or further right) goes first:
        # strokes mostly start at the top or the left, and keep their start (a dot `##.. /
        # ..##` in 3 columns is `##. / ..#`).
        self.spans = []
        for x in range(w):
            first = 1 + EARLIER * (w - 1 - x) / max(1, w - 1)
            for y0, y1 in _runs(ink[:, x]):
                if y1 > y0:
                    if not _is_step(ink, x, y0, y1):
                        step = W_SHORT
                    elif _is_end(ink, y0, x) or _is_end(ink, y1, x):
                        step = W_HEAD * first
                    else:
                        step = W_STEP * first
                    self.spans.append((y0, y1, step * (y1 - y0)))
        # Stubs (serifs) keep their extent. A diagonal stroke end must keep its direction,
        # which is checked on the result (_lost_tails: `##. / ..#` keeps a dot's step though
        # its tail is shorter); here it only ranks the maps.
        tails = _tails(ink)
        self.spans += [(min(a, b), max(a, b), W_TAIL) for a, _, b, _, stub in tails if a != b and stub]
        self.hint_spans = [(min(a, b), max(a, b), W_TAIL_HINT) for a, _, b, _, stub in tails if a != b and not stub]
        for a, _, b, _, stub in tails:
            if stub:
                self.grow[min(a, b):max(a, b)] += 1


def _mirror_score(ink: np.ndarray) -> float:
    """Share of the ink whose mirror image (top to bottom) is also ink."""
    n = ink.sum()
    return float((ink & ink[::-1]).sum() / n) if n else 0.0


def _band_maps(f: _Features, lo: int, n: int, m: int, mirror: float) -> Tuple[np.ndarray, np.ndarray]:
    """Every map of source rows lo..lo+n-1 onto m target rows: (costs, hints, local target rows
    (choices, n)). Hints rank the maps by what they may cost once both axes are applied."""
    s = _steps(n, m)
    if len(s) == 0:
        return np.zeros(0), np.zeros(0), np.zeros((0, n), dtype=np.int64)
    g = np.zeros((len(s), n), dtype=np.int64)
    if n > 1:
        g[:, 1:] = np.cumsum(s, axis=1)
    scale = m / n
    ideal = (np.arange(n) + 0.5) * scale - 0.5
    cost = W_PLACE * ((g - ideal) ** 2) @ f.weight[lo:lo + n]
    # spacing: consecutive marked rows, and the margins to the ends of the band
    marks = [y - lo for y in f.marks if lo <= y < lo + n]
    if marks:
        a, b = np.array(marks[:-1], dtype=np.int64), np.array(marks[1:], dtype=np.int64)
        cost += W_SPACING * ((g[:, b] - g[:, a] - (b - a) * scale) ** 2).sum(1)
        cost += W_MARGIN * ((g[:, marks[0]] - marks[0] * scale) ** 2
                            + (m - 1 - g[:, marks[-1]] - (n - 1 - marks[-1]) * scale) ** 2)
    if n > 1:
        cost += W_COLLIDE * (s == 0) @ f.collide[lo:lo + n - 1]
        hint = W_KINK_HINT * (s == 0) @ f.kink[lo:lo + n - 1] + W_TURN_HINT * (s == 0) @ f.diag[lo:lo + n - 1]
        cost += W_JOG * (s == 2) @ f.jog[lo:lo + n - 1]
        cost += W_TAIL * (s == 2) @ np.minimum(f.grow[lo:lo + n - 1], 1)
        if mirror >= MIRROR_MIN:
            cost += W_MIRROR * mirror * (s != s[:, ::-1]).sum(1) / 2
    else:
        hint = np.zeros(len(s))
    for spans, into in ((f.spans, cost), (f.hint_spans, hint)):
        for y0, y1, weight in spans:
            a, b = max(y0, lo) - lo, min(y1, lo + n - 1) - lo
            if a >= b:
                continue
            outside = (y1 - y0) - (b - a)
            into += weight * ((g[:, b] - g[:, a] + outside) < 1)
    return cost, hint, g


def _axis_maps(ink: np.ndarray, box: Tuple[int, int], bands: Sequence[Tuple[int, int]],
               mirror: bool) -> Tuple[np.ndarray, np.ndarray, int]:
    """
    Every map of the rows of `ink`, cheapest first (with hints): (costs, hints, target row of
    every source row (choices, rows), number of target rows). The box spans rows box[0]..box[0]+box[1]-1
    and is made of bands (source length, target length); rows before the box stay where
    they are, rows after it keep their distance from its end.
    """
    lo, n = box
    total = ink.shape[0]
    m = sum(b[1] for b in bands)
    f = _Features(ink)
    sym = _mirror_score(ink[lo:lo + n]) if mirror and len(bands) == 1 else 0.0
    cost = np.zeros(1)
    hint = np.zeros(1)
    g = np.zeros((1, 0), dtype=np.int64)
    start = dst = 0
    for bn, bm in bands:
        c, hc, bg = _band_maps(f, lo + start, bn, bm, sym)
        if len(c) == 0:
            return np.zeros(0), np.zeros(0), np.zeros((0, total), dtype=np.int64), 0
        cost = (cost[:, None] + c[None, :]).ravel()
        hint = (hint[:, None] + hc[None, :]).ravel()
        g = np.concatenate([np.repeat(g, len(c), axis=0), np.tile(bg + dst, (len(g), 1))], axis=1)
        if len(cost) > MAX_MAPS:
            keep = np.argsort(cost + hint, kind='stable')[:MAX_MAPS]
            cost, hint, g = cost[keep], hint[keep], g[keep]
        start, dst = start + bn, dst + bm
    to = np.empty((len(cost), total), dtype=np.int64)
    to[:, :lo] = np.arange(lo)
    to[:, lo:lo + n] = lo + g
    to[:, lo + n:] = np.arange(lo + n, total) - n + m
    order = np.argsort(cost + hint, kind='stable')
    return cost[order], hint[order], to[order], total - n + m


def _map(cost: float, to: np.ndarray, n: int, hint: float = 0.0) -> _Map:
    grown = tuple(int(y) for y in np.nonzero(np.diff(to) == 2)[0])
    return _Map(float(cost), to, grown, n, float(hint))


# ---------------------------------------------------------------------------

def _bands(size: int, before: int, after: int, new_size: int, new_before: int, new_after: int):
    """Bands of a frame along one axis: wall before, inside, wall after (empty ones dropped)."""
    src = (before, size - before - after, after)
    dst = (new_before, new_size - new_before - new_after, new_after)
    if any((a == 0) != (b == 0) for a, b in zip(src, dst)):
        return None
    return [(a, b) for a, b in zip(src, dst) if a]


def _unkink(out: np.ndarray, ink: np.ndarray, rows: _Map, cols: _Map, keeps_topology,
            lost_ends) -> Tuple[np.ndarray, int]:
    """
    Take the corner pixel out of every L the maps made where the source stepped diagonally
    (`##. / .#. / ..#` -> `#.. / .#. / ..#`): a stroke stays a stroke, and the run it
    started with is a pixel shorter. An L whose source lines hold an L too (the corner of 口)
    is kept, and so is a pixel whose removal would change the topology or lose a stroke end.
    A pixel inside a straight run (ink on both sides of it along a row or a column: the bar
    of 女 where slants cross it) is never taken out; one at the end of a run may be, which
    only shortens it (the stem of 扌 where its hook turns).
    Returns the result and the number of pixels taken out (each shortens a run: W_HEAD).
    """
    def source(to: np.ndarray, a: int) -> np.ndarray:
        """Source lines behind target lines a and a+1; an inserted line has the source
        lines on either side of it behind it."""
        lo = np.searchsorted(to, a, side='left') - 1
        hi = np.searchsorted(to, a + 1, side='right')
        return np.arange(max(lo, 0), min(hi + 1, len(to)))

    def inside(line: np.ndarray, i: int) -> bool:
        return 0 < i < len(line) - 1 and line[i - 1] and line[i + 1]

    out = out.copy()
    removed = 0
    changed = True
    while changed:
        changed = False
        for y, x in zip(*np.nonzero(_quads(out) == 3)):
            sr, sc = source(rows.to, y), source(cols.to, x)
            if len(sr) and len(sc) and kinks(ink[np.ix_(sr, sc)]):
                continue        # the source has a corner here
            q = out[y:y + 2, x:x + 2]
            my, mx = np.argwhere(~q)[0]
            cy, cx = y + 1 - my, x + 1 - mx         # the corner: opposite the missing pixel
            if inside(out[cy], cx) or inside(out[:, cx], cy):
                continue        # inside a straight stroke: taking it out would cut the stroke
            before = lost_ends(out)
            out[cy, cx] = False
            if keeps_topology(out[None]) and lost_ends(out) <= before:
                changed = True
                removed += 1
                break
            out[cy, cx] = True
    return out, removed


def derive(mask: np.ndarray, bx: int, by: int, w: int, h: int, new_w: int, new_h: int,
           insets=None, new_insets=None) -> Optional[Derived]:
    """
    Resize a drawing whose box is (bx, by, w, h) in `mask` to a new_w x new_h box.
    insets / new_insets: frame insets (left, top, right, bottom) of the source and the
    target size; the inside of a frame maps onto the inside of the target.
    Returns None if the sizes cannot be mapped at all (e.g. a band that would have to
    grow by more than it has lines).
    A drawing of separate strokes (氵 冫 忄 讠 灬) is resized stroke by stroke where it can
    be (_derive_parts), each stroke keeping its own shape; the rest as a whole.
    """
    if not insets:
        parts = _derive_parts(mask.astype(bool), bx, by, w, h, new_w, new_h)
        if parts is not None:
            return parts
    return _derive_whole(mask, bx, by, w, h, new_w, new_h, insets, new_insets)


def _derive_parts(ink: np.ndarray, bx: int, by: int, w: int, h: int, new_w: int, new_h: int) -> Optional[Derived]:
    """
    Resize a drawing of separate strokes (8-connected parts) stroke by stroke: each part is
    resized on its own (_derive_whole) into a box near its proportional size, then the parts
    are placed near their proportional places. A part may take a size a pixel off either
    way (PART_SIZES) where that keeps its shape (a dot 5x3 drawn in 3 columns keeps its head
    and its step, `##. / ..#`, rather than turning into a slash three rows tall). Placement
    keeps the parts apart (they may not touch), in the same order along each axis, with the
    gaps between facing parts in proportion (W_GAP), and costs W_NEAR for every pixel newly
    in line with a pixel of another part with a single pixel between them: two stroke ends
    lined up like that read as one broken stroke (氵 3px wide: the rising stroke starts a row
    lower, clear of the second dot's tail).
    None where this does not apply: a single stroke, ink outside the box, a left-right
    symmetric drawing (八 丷: the whole method keeps symmetry), or no placement found.
    """
    box = ink[by:by + h, bx:bx + w]
    if not box.any() or box.sum() != ink.sum():
        return None
    parts = components(box)
    if len(parts) < 2 or _mirror_score(box.T) >= MIRROR_MIN:
        return None
    sx, sy = new_w / w, new_h / h
    info, options = [], []
    for part in parts:
        ys, xs = np.nonzero(part)
        x0, x1, y0, y1 = int(xs.min()), int(xs.max()) + 1, int(ys.min()), int(ys.max()) + 1
        cw, ch = x1 - x0, y1 - y0
        sub = part[y0:y1, x0:x1]
        tw_, th_ = cw * sx, ch * sy
        cx_, cy_ = (x0 + x1) / 2 * sx, (y0 + y1) / 2 * sy
        opts = []
        for tw in range(max(1, int(np.floor(tw_)) - PART_SIZES), min(new_w, int(np.ceil(tw_)) + PART_SIZES) + 1):
            for th in range(max(1, int(np.floor(th_)) - PART_SIZES), min(new_h, int(np.ceil(th_)) + PART_SIZES) + 1):
                r = _derive_whole(sub, 0, 0, cw, ch, tw, th)
                if r is None or not r.ok:
                    continue
                m = r.mask[:th, :tw]
                if not m.any():
                    continue
                size_cost = W_SIZE * ((tw - tw_) ** 2 + (th - th_) ** 2)
                for x in sorted({int(np.floor(cx_ - tw / 2)), int(np.ceil(cx_ - tw / 2))}):
                    for y in sorted({int(np.floor(cy_ - th / 2)), int(np.ceil(cy_ - th / 2))}):
                        x, y = min(max(x, 0), new_w - tw), min(max(y, 0), new_h - th)
                        pos_cost = W_POS * ((x + tw / 2 - cx_) ** 2 + (y + th / 2 - cy_) ** 2)
                        opts.append((r.cost + size_cost + pos_cost, x, y, m))
        if not opts:
            return None
        info.append((x0, y0, x1, y1, part))
        options.append(sorted(opts, key=lambda o: o[0]))

    def near(a: np.ndarray, b: np.ndarray) -> int:
        """Pixels of a in line with pixels of b, one pixel apart."""
        return int((a[:-2] & b[2:]).sum() + (a[2:] & b[:-2]).sum() + (a[:, :-2] & b[:, 2:]).sum()
                   + (a[:, 2:] & b[:, :-2]).sum())

    order = sorted(range(len(parts)), key=lambda i: -int(parts[i].sum()))
    src_near = {(i, j): near(parts[i], parts[j]) for i in order for j in order if i < j}
    # (cost, cost of placement only, placed parts [(i, x, y, tw, th, cell)], cells touched by them)
    beam = [(0.0, [], np.zeros((new_h, new_w), dtype=bool))]
    for i in order:
        x0, y0, x1, y1, _ = info[i]
        grown = []
        for cost, placed, occ in beam:
            for c, x, y, m in options[i]:
                th, tw = m.shape
                cell = np.zeros((new_h, new_w), dtype=bool)
                cell[y:y + th, x:x + tw] = m
                if (cell & occ).any():
                    continue            # touches or overlaps a part already placed
                extra, fine = 0.0, True
                for j, px, py, pw, ph, pcell in placed:
                    a0, b0, a1, b1, _ = info[j]
                    # facing parts keep their order and their gap in proportion
                    for lo, hi, plo, phi, olo, ohi, polo, pohi, t0, t1, pt0, pt1, sc in (
                            (x0, x1, a0, a1, y0, y1, b0, b1, x, x + tw, px, px + pw, sx),
                            (y0, y1, b0, b1, x0, x1, a0, a1, y, y + th, py, py + ph, sy)):
                        if hi <= plo:
                            if t1 > pt0:
                                fine = False
                            elif olo < pohi and polo < ohi:
                                extra += W_GAP * ((pt0 - t1) - (plo - hi) * sc) ** 2
                        elif phi <= lo:
                            if pt1 > t0:
                                fine = False
                            elif olo < pohi and polo < ohi:
                                extra += W_GAP * ((t0 - pt1) - (lo - phi) * sc) ** 2
                    extra += W_NEAR * max(0, near(cell, pcell) - src_near[(min(i, j), max(i, j))])
                if not fine:
                    continue
                grown.append((cost + c + extra, placed + [(i, x, y, tw, th, cell)], occ | _dilate(cell)))
        if not grown:
            return None
        grown.sort(key=lambda b: b[0])
        beam = grown[:BEAM]
    cost, placed, _ = beam[0]
    out = np.zeros((ink.shape[0] - h + new_h, ink.shape[1] - w + new_w), dtype=bool)
    for _, x, y, _, _, cell in placed:
        out[by:by + new_h, bx:bx + new_w] |= cell
    return Derived(out, bx, by, float(cost), True)


def _dilate(m: np.ndarray) -> np.ndarray:
    """The pixels an ink mask covers or touches (8-neighbourhood)."""
    p = _pad(m)
    out = np.zeros_like(p)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            out[1 + dy:p.shape[0] - 1 + dy, 1 + dx:p.shape[1] - 1 + dx] |= m
    return out[1:-1, 1:-1]


def _derive_whole(mask: np.ndarray, bx: int, by: int, w: int, h: int, new_w: int, new_h: int,
                  insets=None, new_insets=None) -> Optional[Derived]:
    """Resize a drawing as a whole, by one map of its rows and one of its columns (see top)."""
    ink = mask.astype(bool)
    if insets:
        l, t, r, b = insets
        nl, nt, nr, nb = new_insets
        rows = _bands(h, t, b, new_h, nt, nb)
        cols = _bands(w, l, r, new_w, nl, nr)
        if rows is None or cols is None:
            return None
    else:
        rows, cols = [(h, new_h)], [(w, new_w)]
    row_cost, row_hint, row_to, n_rows = _axis_maps(ink, (by, h), rows, mirror=not insets)
    col_cost, col_hint, col_to, n_cols = _axis_maps(ink.T, (bx, w), cols, mirror=not insets)
    if not len(row_cost) or not len(col_cost):
        return None
    first = _apply(ink[None], _map(row_cost[0], row_to[0], n_rows), _map(col_cost[0], col_to[0], n_cols))[0]
    draft = Derived(first, bx, by, float(row_cost[0] + col_cost[0]), False)
    if not ink.any():
        draft.ok = True
        return draft

    stack = components(ink)
    euler = euler8(ink)
    base_clumps = clumps(ink)
    base_ends = ends(ink)
    base_kinks = kinks(ink)
    diagonal_ends = _diagonal_ends(ink)
    diagonal_steps = _diagonal_steps(ink)

    def keeps_topology(result: np.ndarray) -> bool:
        return _separate(result) and euler8(result.any(0)) == euler

    def lost_ends(result: np.ndarray) -> int:
        return max(0, base_ends - ends(result))

    # Merging only ever joins more ink, so a map that breaks the topology on its own
    # breaks it in every combination: keep the cheapest maps of each axis that don't,
    # ranked with the stroke ends they lose and the clumps they make on their own (the
    # other axis could still undo a clump, but hardly ever does).
    def valid_maps(cost, hint, to, n, apply_one):
        clean, clumpy = [], []
        for i in range(min(len(cost), MAX_CHECKS)):
            m = _map(cost[i], to[i], n, hint[i])
            result = apply_one(m)
            if keeps_topology(result):
                ink1 = result.any(0)
                ranked = (m.cost + m.hint + W_ENDS * lost_ends(ink1), m)
                if clumps(ink1) > base_clumps:
                    clumpy.append(ranked)
                else:
                    clean.append(ranked)
                    if len(clean) == TOP_PER_AXIS:
                        break
        clean.sort(key=lambda o: o[0])
        clumpy.sort(key=lambda o: o[0])
        return clean + clumpy[:TOP_PER_AXIS - len(clean)]

    same_rows = _Map(0.0, np.arange(ink.shape[0]), (), ink.shape[0])
    same_cols = _Map(0.0, np.arange(ink.shape[1]), (), ink.shape[1])
    row_maps = valid_maps(row_cost, row_hint, row_to, n_rows, lambda m: _apply(stack, m, same_cols))
    col_maps = valid_maps(col_cost, col_hint, col_to, n_cols, lambda m: _apply(stack, same_rows, m))
    if not row_maps or not col_maps:
        return draft

    # combine them cheapest first
    found = []
    frontier = [(row_maps[0][0] + col_maps[0][0], 0, 0)]
    seen = {(0, 0)}
    tries = 0
    while frontier and tries < MAX_TRIES and len(found) < ENOUGH:
        _, i, j = heapq.heappop(frontier)
        for ni, nj in ((i + 1, j), (i, j + 1)):
            if ni < len(row_maps) and nj < len(col_maps) and (ni, nj) not in seen:
                seen.add((ni, nj))
                heapq.heappush(frontier, (row_maps[ni][0] + col_maps[nj][0], ni, nj))
        tries += 1
        rm, cm = row_maps[i][1], col_maps[j][1]
        result = _apply(stack, rm, cm)
        if not keeps_topology(result):
            continue
        out, unkinked = _unkink(result.any(0), ink, rm, cm, keeps_topology, lost_ends)
        if clumps(out) > base_clumps:
            continue
        # what makes a result unacceptable (declined above MAX_COST), and what only makes it
        # worse than another: a diagonal step turned straight or a flattened stroke end is
        # often unavoidable in a tight size (阝 3px wide: its loops become `#.#`)
        cost = rm.cost + cm.cost + W_ENDS * lost_ends(out) + W_KINK * max(0, kinks(out) - base_kinks) \
            + W_HEAD * unkinked
        shape = W_TAIL * _lost_tails(out, diagonal_ends, rm, cm) + W_TURN * _turned(diagonal_steps, rm, cm)
        found.append((cost + shape, cost, out))
    if not found:
        return draft
    rank, cost, out = min(found, key=lambda f: f[0])
    return Derived(out, bx, by, float(rank), cost <= MAX_COST)


# ---------------------------------------------------------------------------
# Cache: deriving every size of the font takes a while, and the drawings rarely change

class Cache:
    def __init__(self, path: str):
        self.path = path
        self.data = {}
        self.dirty = False
        if os.path.exists(path):
            try:
                with open(path, 'rb') as f:
                    stored = pickle.load(f)
                if stored.get('version') == VERSION:
                    self.data = stored['data']
            except (OSError, pickle.PickleError, EOFError, KeyError):
                self.data = {}
        atexit.register(self.save)

    @staticmethod
    def key(mask: np.ndarray, *args) -> bytes:
        h = hashlib.sha1(np.packbits(mask.astype(bool)).tobytes())
        h.update(repr((mask.shape,) + args).encode())
        return h.digest()

    def get(self, key: bytes):
        return self.data.get(key, False)

    def put(self, key: bytes, value):
        self.data[key] = value
        self.dirty = True

    def save(self):
        if not self.dirty:
            return
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        with open(self.path + '.part', 'wb') as f:
            pickle.dump({'version': VERSION, 'data': self.data}, f, protocol=pickle.HIGHEST_PROTOCOL)
        os.replace(self.path + '.part', self.path)
        self.dirty = False
