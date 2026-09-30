"""
Evenness pass: refines each assembled character by greedy search, after
  P.-K. Lai, D.-Y. Yeung, M.-C. Pong, "A Heuristic Search Approach to Chinese Glyph
  Generation Using Hierarchical Character Composition", Computer Processing of
  Oriental Languages 10(3), 1996 (and the 1995 conference version),
adapted to 1-bit 16px glyphs assembled from glyphlettes drawn at size.

The paper transforms component bounding boxes (move, enlarge, shrink) and keeps
whichever change most improves a weighted sum of normalised "beauty metrics" that
quantify calligraphy rules: 四平八稳 alignment and stability, 布白均匀 even white
space, 穿插避让 closing gaps between components. The papers define only the centre of
gravity and the rule-based metric (the rest are in Lai's M.Phil. thesis, not at hand);
the others here are designed for 15px 1-bit glyphs. Here:

  - Parts only move within their own boxes, so they never collide. There is often
    room: generated parts are smaller than their boxes (and can be regenerated up to
    2px narrower or wider, 1 or 2 rows shorter or taller than they start), and drawn
    and derived parts rarely fill theirs.
  - A generated part that Chiron Hei HK measures cleanly in the character starts at
    the size and place it has there (the slot's shape is sized for every character
    using the slot): 昌's upper 日 narrower than the lower, 品's lower 口 larger than
    口 in a 7x9 box usually is. Twins it measures alike start alike.
  - The inner part of a surround may use the largest empty rectangle inside the
    frame's actual ink (keeping 1px clear), not just its configured box.
  - Parts next to a joined joint do not move vertically, so bridging still meets.
  - Protruding pixels move with their part.

Metrics (0 = ideal). A *unit* is the set of parts under one child of a composition
(吅 under 品 is two parts, measured as one).
  四平八稳, alignment and stability:
    cog       the centre of gravity away from the body centre (the paper's M2)
    align     the units of a ⿱⿳ stack, and the inside of a ⿴⿵⿶ frame, off the
              centre line, by their axis: their one long vertical stroke in the middle
              third if they have one (十 土 木), else the middle of their ink
    twins     twins whose ink differs in size (the two 口 at the bottom of a 13px 品,
              in boxes 5 and 7 wide); twins measured differently are what the
              measurement makes them instead (昌's upper 日 narrower)
    level     strokes of parts side by side that miss each other by one pixel: the
              horizontal strokes and the top and bottom of the parts (林 明). Lined up
              or clearly apart is fine; a near miss reads as a mistake. Share of the
              pair's features, averaged over the pairs. (Not for stacked parts: a top
              part 1px in on each side is 上小下大.)
  布白均匀, even white space:
    spacing   down a stack, the white space between two units' facing horizontal
              strokes against the spacing of the strokes inside them, and the stack's
              gaps against each other (言: 亠, 二 and 口 as evenly spaced as the strokes
              of 二); across a row, the same for vertical strokes. A stroke is a run
              across 60% of its unit or more, so slants meeting a stem are not one.
    balance   the white space around a part between two others (the middle of ⿲⿳),
              or inside a frame (on every wall: four in 囗, three in 冂 and 匚), unequal
              (回: the inner square as far from the frame at the top as at the sides)
  穿插避让:
    gaps      neighbouring parts not one clear pixel apart; a part inside a frame
              touching it or more than 2 clear pixels away. Pairs of measured parts are
              left to 'reference' (宫: 口 tucked between the legs of 宀)
  风格统一, from measurements:
    reference every unit's ink away from where Chiron Hei HK puts that part of that
              character (reference.py): the paper's rule-based metric, with a target
              for every part of every character. Mean distance of the four edges, px;
              a measurement counts less the more ink its cut went through, and half if
              the cut was ambiguous.
  others:
    density   spread of ink density between parts
    rule      parts away from their preferred position ('place', inferred.tsv or layout.tsv),
              the paper's rule-based metric: distance over box size, per axis
    moved     distance from the initial configuration (keeps changes small)

Each is normalised by the font's own statistics as in the paper, MN = (M / (mean +
sd))^2 over the initial configurations, except those in FIXED_SCALE: a near miss
means the same in every font, and the unrefined glyphs have so many that the font's
mean would excuse them.

A part with a preferred position also starts there, as far as its room and the
no-contact rule allow; the 'rule' metric keeps other metrics from pulling it away.
Twins (identical neighbours) ignore the rule along their split: 吅's two 口 stay level
although 口 prefers to sit high on the left and low on the right.

Hard constraints:
  - a change may not bring two parts into contact (touching or overlapping) unless
    they already were, e.g. at a bridged or protruding joint;
  - the outline of the glyph (the bounding box of its ink) may not shrink: the paper
    keeps the tightest bounding box fixed by rescaling at the end, which bitmaps
    cannot do, so the search may not pull a part in from the edge (the dot of 言 off
    the top row). Where Chiron Hei HK measures the character, it may shrink to
    within 1px of the character's outline there, so a rounding at the start isn't
    locked in (品's lower 口, 6px wide there, from 7 to 5);
  - twins (the same component next to itself) move alike across their split: side by
    side (吅 林) their vertical position, stacked (吕 圭) their horizontal one; in boxes
    of the same size they also resize alike, so a character stays symmetric. Twins
    Chiron Hei HK measures differently (昌 吕) are free of this.

The paper's border-elimination metric is left out: it concerns trimming margins after
scaling, which never happens here, and it rewards pushing parts apart.
"""

import math
from typing import Dict, List, Optional, Tuple

import numpy as np

import generators as GN
import geometry as GEO
import ids as IDS
from geometry import Layout, SlotKey

WEIGHTS = {'cog': 1.0, 'spacing': 1.5, 'balance': 1.5, 'gaps': 1.0, 'align': 3.0, 'level': 1.0,
           'twins': 1.0, 'reference': 2.0, 'density': 0.3, 'rule': 1.5, 'moved': 0.5}
# The paper scales each metric by mean + sd over the font's initial configurations.
# A metric that hardly varies there (every initial stack is centred, so 'align' is
# always 0) would get a tiny or zero scale and then dominate or vanish; these floors,
# in the metric's own units, keep one pixel of error meaningful.
SCALE_FLOOR = {'cog': 0.5 / GEO.BODY_W, 'spacing': 0.02, 'balance': 0.15, 'gaps': 0.5, 'align': 1 / GEO.BODY_W,
               'level': 0.5, 'twins': 1 / GEO.BODY_W, 'reference': 0.5, 'density': 0.05, 'rule': 0.5 / GEO.BODY_W, 'moved': 1.0}
# one feature in four near-missing, or twins 2px apart in size, counts as one unit
FIXED_SCALE = {'level': 0.25, 'twins': 2 / GEO.BODY_W, 'reference': 1.0}
# The walls of a frame around its inside (left, right, top, bottom): equal white space there.
WALLS = {'⿴': 'lrtb', '⿵': 'lrt', '⿶': 'lrb', '⿷': 'ltb'}
# Frames symmetric about their vertical centre line: the inside is centred on it.
CENTRED_INSIDE = frozenset('⿴⿵⿶')
MAX_STEPS = 16
# Whether hand-drawn parts may be moved within their boxes too (not only generated
# ones). A drawing that fills its box cannot move either way.
MOVE_DRAWN = True
MOVES = ((1, 0), (-1, 0), (0, 1), (0, -1))
MAX_RESIZE = 2    # a generated part may be regenerated this many px smaller or larger per axis, in all
# A generated part starts at the size and place Chiron Hei HK gives it in the character
# (_apply_measurements) if its measurement is at least this good (1: unambiguous, clean cut)
START_WEIGHT = 0.75
# Generated parts are regenerated a size up or down: 2px across (keeping an odd width, so a
# central stroke stays central), 1 or 2 rows down (the generator keeps bars evenly spaced)
GROWS = ((2, 0), (-2, 0), (0, 1), (0, -1), (0, 2), (0, -2))

Rect = Tuple[int, int, int, int]   # x0, y0, x1, y1 (exclusive)
BODY: Rect = (GEO.BODY_X, GEO.BODY_Y, GEO.BODY_X + GEO.BODY_W, GEO.BODY_Y + GEO.BODY_H)


def _bbox(mask: np.ndarray) -> Optional[Rect]:
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def _longest_runs(mask: np.ndarray) -> np.ndarray:
    """The longest run of ink in every row."""
    run = np.zeros(mask.shape[0], dtype=np.int32)
    best = run.copy()
    for x in range(mask.shape[1]):
        run = (run + 1) * mask[:, x]
        np.maximum(best, run, out=best)
    return best


def _dilate(mask: np.ndarray) -> np.ndarray:
    """8-neighbourhood dilation by one pixel."""
    out = mask.copy()
    out[1:] |= mask[:-1]
    out[:-1] |= mask[1:]
    row = out.copy()
    out[:, 1:] |= row[:, :-1]
    out[:, :-1] |= row[:, 1:]
    return out


def _w(box) -> float:
    return box[2] - box[0]


def _h(box) -> float:
    return box[3] - box[1]


def _place(mask: np.ndarray, x: int, y: int) -> np.ndarray:
    """A (h, w) mask placed at (x, y) in a cell."""
    cell = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
    h, w = mask.shape
    cell[y:y + h, x:x + w] = mask[:GEO.CELL_H - y, :GEO.CELL_W - x]
    return cell


def _shift(mask: np.ndarray, dx: int, dy: int) -> np.ndarray:
    if not dx and not dy:
        return mask
    out = np.zeros_like(mask)
    h, w = mask.shape
    src_y, dst_y = (slice(0, h - dy), slice(dy, h)) if dy >= 0 else (slice(-dy, h), slice(0, h + dy))
    src_x, dst_x = (slice(0, w - dx), slice(dx, w)) if dx >= 0 else (slice(-dx, w), slice(0, w + dx))
    out[dst_y, dst_x] = mask[src_y, src_x]
    return out


class Part:
    def __init__(self, key: SlotKey, x: int, y: int, base: np.ndarray, generated: bool):
        self.key = key
        self.box: Rect = (x, y, x + key.w, y + key.h)
        self.region: Rect = self.box          # where its in-box ink may be
        self.base = base                       # ink in cell coordinates, initial state
        self.core = _bbox(base[y:y + key.h, x:x + key.w])   # in-box ink, box coordinates
        self.generated = generated
        self.fixed = False
        self.pin_y = False
        self.context = 'whole'
        self.target: Dict[str, float] = {}      # preferred ink centre, fractions of the box
        self.start = (0, 0, 0, 0)              # state the search starts from
        self.dx = self.dy = 0
        self.gw = self.gh = 0                   # size change of a generated part
        self.ink = base

    def state(self):
        return self.dx, self.dy, self.gw, self.gh

    def set_state(self, dx, dy, gw, gh, anysize=False) -> bool:
        """Apply a state; False (and unchanged) if it is not allowed. A generated part is
        resized by at most MAX_RESIZE from its start, unless `anysize` (to set the start)."""
        if self.fixed and (dx, dy, gw, gh) != (0, 0, 0, 0):
            return False
        if (gw or gh) and not self.generated:
            return False
        if self.pin_y and (dy or gh):
            return False
        if not anysize and (abs(gw - self.start[2]) > MAX_RESIZE or abs(gh - self.start[3]) > MAX_RESIZE):
            return False
        if gw or gh:
            # the shape gw wider and gh taller than generated, centred where its ink was;
            # the ink must fit the cell, not the box it was generated for (the lower 日 of
            # 昌 is as wide as its box)
            if self.core is None:
                return False
            kx0, ky0, kx1, ky1 = self.core
            w, h = kx1 - kx0 + gw, ky1 - ky0 + gh
            g = GN.shape(self.key.comp, w, h) if w >= 1 and h >= 1 else None
            core = _bbox(g) if g is not None else None
            if core is None:
                return False
            g = g[core[1]:core[3], core[0]:core[2]]
            cx0 = self.box[0] + kx0 + (kx1 - kx0 - g.shape[1]) // 2 + dx
            cy0 = self.box[1] + ky0 + (ky1 - ky0 - g.shape[0]) // 2 + dy
            cx1, cy1 = cx0 + g.shape[1], cy0 + g.shape[0]
            if cx0 < 0 or cy0 < 0 or cx1 > GEO.CELL_W or cy1 > GEO.CELL_H:
                return False
            ink = _place(g, cx0, cy0)
        else:
            if self.core is None:
                return False
            x0, y0 = self.box[0] + dx, self.box[1] + dy
            cx0, cy0, cx1, cy1 = self.core[0] + x0, self.core[1] + y0, self.core[2] + x0, self.core[3] + y0
            ink = _shift(self.base, dx, dy)
        rx0, ry0, rx1, ry1 = self.region
        if not (rx0 <= cx0 and ry0 <= cy0 and cx1 <= rx1 and cy1 <= ry1):
            return False
        self.dx, self.dy, self.gw, self.gh = dx, dy, gw, gh
        self.ink = ink
        return True


class Arrangement:
    """The parts of one character, which of them are adjacent, and the metrics."""

    def __init__(self, layout: Layout, cp: int, placements, drawn, joined_rows):
        self.layout = layout
        self.parts: List[Part] = []
        for key, x, y in placements:
            base = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
            drawn[key].blit(base, x, y)
            part = Part(key, x, y, base, drawn.kind(key) == 'generated' and not key.role)
            part.fixed = drawn.kind(key) == 'drawn' and not MOVE_DRAWN
            self.parts.append(part)
        self.pairs = self._pairs()
        self._stroke_cache: Dict[Tuple, List[int]] = {}
        self._memo: Dict[Tuple, object] = {}
        # parts in contact at the start (bridged or protruding joints) may stay so
        self.contact = {(i, j) for i, j in self._all_pairs() if self._touching(i, j)}
        self.twins = self._twins()
        self._surround_regions()
        self._contexts(layout.root(cp))
        # measured twins are what Chiron Hei HK makes them (昌's upper 日 narrower, 林's left
        # 木 narrower than the right): the twin rules are for the rest, and for twins it
        # measures the same size (品's lower 口 stay alike)
        for gy, x0, x1 in joined_rows:
            for p in self.parts:
                px0, py0, px1, py1 = p.box
                if px0 < x1 and x0 < px1 and (py1 == gy or py0 == gy + 1):
                    p.pin_y = True
        self._clear_roofs()
        measured = self.measured_parts = {u[0] for u, _, _ in self.measured if len(u) == 1}
        alike = self._apply_measurements(self.twins)
        self.twins = {i: [(j, k) for j, k in tw if not (i in measured and j in measured) or (i, j) in alike]
                      for i, tw in self.twins.items()}
        self.twins = {i: tw for i, tw in self.twins.items() if tw}
        self._apply_rules()
        # the ink's bounding box at the start: the search may not shrink it (the paper keeps
        # the tightest bounding box of the glyph fixed, rescaling at the end; here parts
        # only move, so the outline is kept as a constraint instead)
        self.outline = self._outline_limit(self._outline(), layout.reference.get(chr(cp)))
        # the features of each pair at the start, as the fixed denominator of 'level'
        self.level_den = [len(self._features(i, kind)) + len(self._features(j, kind)) if kind == 'side' else 0
                          for i, j, kind in self.pairs]

    def _outline(self):
        return _bbox(self.compose()[BODY[1]:BODY[3], BODY[0]:BODY[2]])

    @staticmethod
    def _outline_limit(start, whole):
        """
        The outline the search may not shrink past: the outline at the start, relaxed to
        within 1px of the character's own outline in Chiron Hei HK where it has one. The
        start is rounded from measurements, and rounding shouldn't be locked in: 品's lower
        口 may go from 7 to 5px wide (3px apart instead of 1) where Chiron Hei HK has them
        6px wide.
        """
        if start is None or whole is None:
            return start
        clamp = lambda v: min(float(GEO.BODY_W), max(0.0, v * GEO.BODY_W))
        rx0, ry0, rx1, ry1 = clamp(whole.x0), clamp(whole.y0), clamp(whole.x1), clamp(whole.y1)
        return (max(start[0], math.floor(rx0 + 1)), max(start[1], math.floor(ry0 + 1)),
                min(start[2], math.ceil(rx1 - 1)), min(start[3], math.ceil(ry1 - 1)))

    def keeps_outline(self) -> bool:
        now, limit = self._outline(), self.outline
        if now is None or limit is None:
            return True
        return now[0] <= limit[0] and now[1] <= limit[1] and now[2] >= limit[2] and now[3] >= limit[3]

    def _twins(self) -> Dict[int, List[Tuple[int, str]]]:
        """The same component next to itself, facing it squarely (吅 林 吕 圭, also in boxes of
        different widths, as the bottom of a 13px wide 品): part -> [(twin, 'side' or 'stack')]. Twins move alike
        across their split (side by side their vertical position, stacked their horizontal
        one), resize alike when their boxes are the same, and the 'twins' metric keeps
        their ink the same size."""
        out: Dict[int, List[Tuple[int, str]]] = {}
        for i, j, kind in self.pairs:
            a, b = self.parts[i].key, self.parts[j].key
            ba, bb = self.parts[i].box, self.parts[j].box
            # facing each other squarely: the same rows side by side, the same columns stacked
            # (the top 口 of 品 is not a twin of either 口 below it)
            square = (ba[1], ba[3]) == (bb[1], bb[3]) if kind == 'side' else (ba[0], ba[2]) == (bb[0], bb[2])
            if kind != 'enclose' and square and a.comp == b.comp and not a.role and not b.role:
                out.setdefault(i, []).append((j, kind))
                out.setdefault(j, []).append((i, kind))
        return out

    def _pairs(self):
        """Neighbouring parts: (i, j, kind), kind being 'side' (⿰), 'stack' (⿱) or 'enclose'."""
        pairs = []
        for i, a in enumerate(self.parts):
            for j in range(i + 1, len(self.parts)):
                b = self.parts[j]
                ax0, ay0, ax1, ay1 = a.box
                bx0, by0, bx1, by1 = b.box
                if (ax1 + GEO.GAP == bx0 or bx1 + GEO.GAP == ax0) and ay0 < by1 and by0 < ay1:
                    pairs.append((i, j, 'side'))
                elif ((ay0 < by0 <= ay1 + GEO.GAP and ay1 < by1) or (by0 < ay0 <= by1 + GEO.GAP and by1 < ay1)) \
                        and ax0 < bx1 and bx0 < ax1:
                    # one above the other, a gap apart, or the lower reaching up under a roof
                    pairs.append((i, j, 'stack'))
                elif self._encloses(a, b) or self._encloses(b, a):
                    pairs.append((i, j, 'enclose'))
        return pairs

    def _all_pairs(self):
        n = len(self.parts)
        return [(i, j) for i in range(n) for j in range(i + 1, n)]

    def _touching(self, i: int, j: int) -> bool:
        return bool((_dilate(self.parts[i].ink) & self.parts[j].ink).any())

    def new_contact(self, k: int) -> bool:
        """Whether part k now touches a part it did not touch at the start."""
        grown = _dilate(self.parts[k].ink)
        for j, q in enumerate(self.parts):
            if j != k and (min(j, k), max(j, k)) not in self.contact and (grown & q.ink).any():
                return True
        return False

    @staticmethod
    def _encloses(frame: Part, inner: Part) -> bool:
        if not frame.key.role or inner.key.role:
            return False
        fx0, fy0, fx1, fy1 = frame.box
        ix0, iy0, ix1, iy1 = inner.box
        return fx0 <= ix0 and fy0 <= iy0 and ix1 <= fx1 and iy1 <= fy1

    def _surround_regions(self):
        """A lone part inside a frame may use the largest box within the frame, 1px clear of its ink."""
        for f in self.parts:
            if not f.key.role:
                continue
            blocked = _dilate(f.base)
            inside = [p for p in self.parts if self._encloses(f, p)]
            if len(inside) != 1:
                continue
            for p in inside:
                x0, y0, x1, y1 = p.box
                grown = True
                while grown:
                    grown = False
                    for nx0, ny0, nx1, ny1 in ((x0 - 1, y0, x1, y1), (x0, y0 - 1, x1, y1),
                                               (x0, y0, x1 + 1, y1), (x0, y0, x1, y1 + 1)):
                        if (f.box[0] <= nx0 and f.box[1] <= ny0 and nx1 <= f.box[2] and ny1 <= f.box[3]
                                and not blocked[ny0:ny1, nx0:nx1].any()):
                            x0, y0, x1, y1 = nx0, ny0, nx1, ny1
                            grown = True
                p.region = (x0, y0, x1, y1)

    def _contexts(self, root: SlotKey):
        """
        Each part's position in its parent (left, top, inner, ...), for the placement rules;
        and the units the alignment and white-space metrics look at. A unit is the set of
        parts under one child of a composition (吅 under 品 is two parts, measured as one):
          stacks    units centred on a line: the parts of a ⿱⿳ stack on its centre line,
                    the inside of a ⿴⿵⿶ frame on the frame's
          balanced  units whose white space should be equal on the sides that face
                    something: the middle of a ⿲⿳ (both neighbours), the inside of a
                    frame (every wall: all four in 囗, three in 冂)
        """
        at = {(p.key, p.box[0], p.box[1]): i for i, p in enumerate(self.parts)}
        self.stacks: List[Tuple[List[int], Tuple[int, int]]] = []
        self.balanced: List[Tuple[List[int], List[int], str]] = []   # (unit, others, sides)
        self.spaced: List[Tuple[List[List[int]], str]] = []   # (child units in order, 'x' or 'y')
        # the units' boxes as Chiron Hei HK lays the character out (reference.py), in cell px
        self.measured: List[Tuple[List[int], Tuple[float, float, float, float], float]] = []
        ch = root.comp

        def inside(x0, y0, x1, y1):
            return [i for i, p in enumerate(self.parts)
                    if x0 <= p.box[0] and y0 <= p.box[1] and p.box[2] <= x1 and p.box[3] <= y1]

        def walk(key, x, y, path=''):
            s = self.layout.slot(key)
            if not s.parts or (key, x, y) in at:
                return
            n = len(s.parts)
            node = inside(x, y, x + key.w, y + key.h)
            units_in_order = []
            for i, (c, dx, dy) in enumerate(s.parts):
                cx, cy = x + dx, y + dy
                j = at.get((c, cx, cy))
                if j is not None:
                    p = self.parts[j]
                    if c.role:
                        p.context = 'frame'
                    elif s.op in IDS.SURROUND:
                        p.context = 'inner'
                    elif s.op in IDS.SPLIT_H:
                        p.context = 'left' if i == 0 else 'right' if i == n - 1 else 'middle'
                    else:
                        p.context = 'top' if i == 0 else 'bottom' if i == n - 1 else 'middle'
                unit = [j] if j is not None else inside(cx, cy, cx + c.w, cy + c.h)
                sub = f"{path}.{i}" if path else str(i)
                if unit:
                    units_in_order.append(unit)
                    box = self.layout.reference.get(ch, sub)
                    if box is not None:
                        to_x = lambda v: GEO.BODY_X + min(1.0, max(0.0, v)) * GEO.BODY_W
                        to_y = lambda v: GEO.BODY_Y + min(1.0, max(0.0, v)) * GEO.BODY_H
                        wt = (0.5 if box.ambiguous else 1.0) * max(0.0, 1 - 2 * box.cut)
                        if wt > 0:
                            self.measured.append((unit, (to_x(box.x0), to_y(box.y0), to_x(box.x1), to_y(box.y1)), wt))
                if unit and not c.role:
                    others = [k for k in node if k not in unit]
                    middle = n == 3 and i == 1
                    if s.op in IDS.SPLIT_V:
                        self.stacks.append((unit, (x, x + key.w)))
                        if middle:
                            self.balanced.append((unit, others, 'tb'))
                    elif s.op in IDS.SPLIT_H:
                        if middle:
                            self.balanced.append((unit, others, 'lr'))
                    elif s.op in IDS.SURROUND:
                        if s.op in WALLS:
                            self.balanced.append((unit, others, WALLS[s.op]))
                        if s.op in CENTRED_INSIDE:
                            self.stacks.append((unit, (x, x + key.w)))
                walk(c, cx, cy, sub)
            # stroke spacing across the node: horizontal strokes down a stack, vertical
            # strokes across a row
            if len(units_in_order) >= 2 and (s.op in IDS.SPLIT_V or s.op in IDS.SPLIT_H):
                self.spaced.append((units_in_order, 'y' if s.op in IDS.SPLIT_V else 'x'))

        walk(root, GEO.BODY_X, GEO.BODY_Y)
        placed = {u[0] for u, _, _ in self.measured if len(u) == 1}
        for i, p in enumerate(self.parts):
            rule = self.layout.rules.get(p.key.comp)
            # a part measured in Chiron Hei HK has a target of its own; the placement
            # rules (inferred.tsv, or set in layout.tsv) are for the rest
            if rule and rule.place and i not in placed:
                p.target = dict(rule.place.get(p.context) or rule.place.get('any') or {})
                # a rule places a component beside a different one (口 high on the left of 叶);
                # twins keep level with each other instead (吅 吕)
                for _, kind in self.twins.get(i, ()):
                    p.target.pop('y' if kind == 'side' else 'x', None)

    def _clear_roofs(self):
        """
        A part reaching up under a roof (its box starts inside the box above: Slot.overlaps) may
        not touch the roof, though parts touching at the start may stay so elsewhere (a joined
        joint): its ink does not always fit between the legs (a top stroke as wide as the roof).
        It steps down within its taller box until it is clear, and its contact is not allowed.
        """
        for i, j in sorted(self.contact):
            a, b = self.parts[i], self.parts[j]
            upper, lower = (a, b) if a.box[1] < b.box[1] else (b, a)
            ux0, uy0, ux1, uy1 = upper.box
            lx0, ly0, lx1, ly1 = lower.box
            if not (uy0 < ly0 < uy1 and ux0 < lx1 and lx0 < ux1) or lower.fixed:
                continue
            self.contact.discard((i, j))
            for dy in (1, 2):
                if lower.set_state(0, dy, 0, 0) and not self._touching(i, j):
                    lower.start = lower.state()
                    break
            else:
                lower.set_state(0, 0, 0, 0)
                self.contact.add((i, j))        # it fills its box: leave it as drawn

    def _apply_measurements(self, twins):
        """
        Start each generated part that Chiron Hei HK measures on its own at the size and
        place it has in this very character, as near as whole pixels, its box and the
        contacts allow: the slot's shape is sized for every character using the slot
        (Layout.ink_size), this one may differ (昌's upper 日 narrower than the lower; 品's
        lower 口 larger than 口 in a 7x9 box usually is). Twins measured about the same size
        (within a pixel) get the same size; returns those pairs.
        """
        targets = {u[0]: (box, wt) for u, box, wt in self.measured if len(u) == 1 and wt >= START_WEIGHT}
        done = set()
        alike = set()
        for k, (box, _) in targets.items():
            p = self.parts[k]
            if k in done or not p.generated or p.fixed or p.core is None:
                continue
            group = [k] + [j for j, _ in twins.get(k, ()) if j in targets and j not in done
                           and self.parts[j].key == p.key and not self.parts[j].fixed
                           and abs(_w(targets[j][0]) - _w(box)) < 1 and abs(_h(targets[j][0]) - _h(box)) < 1]
            done.update(group)
            alike.update((i, j) for i in group for j in group if i != j)
            self._start_at(group, [targets[j][0] for j in group])
        return alike

    def _start_at(self, group: List[int], boxes):
        """Size the parts of a group alike and place each nearest its box (see above)."""
        p = self.parts[group[0]]
        kx0, ky0, kx1, ky1 = p.core
        cw, ch = kx1 - kx0, ky1 - ky0
        mw = sum(_w(b) for b in boxes) / len(boxes)
        mh = sum(_h(b) for b in boxes) / len(boxes)
        lo_w = max(1, int((mw - 1) // 2) * 2 + 1)      # the odd widths either side of mw
        widths = range(min(cw, lo_w), max(cw, lo_w + 2) + 1, 2)
        heights = range(max(1, min(ch, math.floor(mh))), max(ch, math.ceil(mh)) + 1)
        saved = [(k, self.parts[k].state()) for k in group]
        best = None
        for w in widths:
            for h in heights:
                gw, gh = w - cw, h - ch
                states, err = [], 0.0
                for k, b in zip(group, boxes):
                    cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
                    placed = self._place_near(k, gw, gh, (cx - mw / 2, cy - mh / 2, cx + mw / 2, cy + mh / 2))
                    if placed is None:
                        break
                    err += placed[0]
                    states.append(placed[1])
                else:
                    rank = (round(err, 6), abs(gw) + abs(gh))
                    if best is None or rank < best[0]:
                        best = (rank, states)
                for k, st in saved:
                    self.parts[k].set_state(*st, anysize=True)
        for k, st in zip(group, best[1] if best else ()):
            self.parts[k].set_state(*st, anysize=True)
            self.parts[k].start = st

    def _place_near(self, k: int, gw: int, gh: int, target) -> Optional[Tuple[float, Tuple[int, int, int, int]]]:
        """Part k resized by (gw, gh) and moved to where its ink is nearest the target box
        (mean distance of the edges): (error, state), or None if it fits nowhere near."""
        p = self.parts[k]
        # the size of its ink, and where it would be unshifted (as set_state places it)
        kx0, ky0, kx1, ky1 = p.core
        if gw or gh:
            g = GN.shape(p.key.comp, kx1 - kx0 + gw, ky1 - ky0 + gh)
            b = _bbox(g) if g is not None else None
            if b is None:
                return None
            sw, sh = b[2] - b[0], b[3] - b[1]
        else:
            sw, sh = kx1 - kx0, ky1 - ky0
        bx = p.box[0] + kx0 + (kx1 - kx0 - sw) // 2
        by = p.box[1] + ky0 + (ky1 - ky0 - sh) // 2
        tx0, ty0, tx1, ty1 = target
        ex, ey = (tx0 + tx1 - sw) / 2 - bx, (ty0 + ty1 - sh) / 2 - by
        cands = []
        for dx in {math.floor(ex), math.ceil(ex), 0}:
            for dy in {math.floor(ey), math.ceil(ey), 0}:
                x0, y0 = bx + dx, by + dy
                err = (abs(x0 - tx0) + abs(x0 + sw - tx1) + abs(y0 - ty0) + abs(y0 + sh - ty1)) / 4
                cands.append((err, abs(dx) + abs(dy), dx, dy))
        for err, _, dx, dy in sorted(cands):
            if p.set_state(dx, dy, gw, gh, anysize=True) and not self.new_contact(k):
                return err, (dx, dy, gw, gh)
        return None

    def _apply_rules(self):
        """Start ruled parts at their preferred position, as far as room and contacts allow."""
        for k, p in enumerate(self.parts):
            if not p.target or p.core is None:
                continue
            ex, ey = self._rule_offset(p)
            # step towards the target one pixel at a time, stopping where a step is refused
            dx = dy = 0
            for axis, total in (('x', ex), ('y', ey)):
                step = 1 if total > 0 else -1
                for _ in range(abs(total)):
                    nx, ny = (dx + step, dy) if axis == 'x' else (dx, dy + step)
                    if not p.set_state(nx, ny, 0, 0):
                        break
                    if self.new_contact(k):
                        p.set_state(dx, dy, 0, 0)
                        break
                    dx, dy = nx, ny
            p.start = p.state()

    def _rule_offset(self, p: Part) -> Tuple[int, int]:
        """Whole-pixel offset that brings the part's ink centre to its target."""
        bb = _bbox(p.ink)
        x0, y0, x1, y1 = p.box
        ex = ey = 0
        if 'x' in p.target:
            ex = round(x0 + p.target['x'] * (x1 - x0) - (bb[0] + bb[2]) / 2)
        if 'y' in p.target:
            ey = round(y0 + p.target['y'] * (y1 - y0) - (bb[1] + bb[3]) / 2)
        return ex, ey

    # -- metrics ---------------------------------------------------------------

    def compose(self) -> np.ndarray:
        cell = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
        for p in self.parts:
            cell |= p.ink
        return cell

    def metrics(self) -> Dict[str, float]:
        cell = self.compose()
        body = cell[BODY[1]:BODY[3], BODY[0]:BODY[2]]
        ys, xs = np.nonzero(body)
        if len(ys) == 0:
            return {k: 0.0 for k in WEIGHTS}
        m = {}
        # 四平八稳, geometric stability: the centre of gravity of the ink on the body's centre
        m['cog'] = (abs(xs.mean() - (GEO.BODY_W - 1) / 2) / GEO.BODY_W
                    + abs(ys.mean() - (GEO.BODY_H - 1) / 2) / GEO.BODY_H)
        # 布白均匀, feature stroke spacing: down a stack, the white space between two parts'
        # facing horizontal strokes matches the spacing of the strokes inside them and the
        # other gaps of the stack (言: 亠, 二 and 口 as evenly spaced as the strokes of 二);
        # across a row, the same for vertical strokes (川 林)
        dev, terms = 0.0, 0
        for units, axis in self.spaced:
            lines = [self._unit_strokes(u, axis) for u in units]
            between = []
            for a, b in zip(lines, lines[1:]):
                if not a or not b or b[0] <= a[-1]:
                    continue
                g = b[0] - a[-1] - 1
                between.append(g)
                inner = [a[-1] - a[-2] - 1] if len(a) >= 2 else []
                inner += [b[1] - b[0] - 1] if len(b) >= 2 else []
                for r in inner:
                    if r >= 1:
                        dev += ((g - r) / ((g + r) / 2)) ** 2
                        terms += 1
            if len(between) >= 2:
                mean = sum(between) / len(between)
                if mean > 0:
                    dev += sum(((g - mean) / mean) ** 2 for g in between)
                    terms += 1
        m['spacing'] = dev / max(1, len(self.spaced))
        # 布白均匀, white space: a part between two others, or inside a frame, has as much
        # white space on each side that faces something (回: the inner square as far from
        # the frame at the top as at the sides, not squashed flat nor pushed aside)
        bal, nb = 0.0, 0
        for unit, others, sides in self.balanced:
            gaps = [g for g in self._cached('sides' + sides, unit + others,
                                            lambda: self._side_gaps(unit, others, sides)) if g is not None]
            if len(gaps) >= 2:
                mean = sum(gaps) / len(gaps)
                bal += sum(abs(g - mean) for g in gaps) / len(gaps) / max(1.0, mean)
                nb += 1
        m['balance'] = bal / nb if nb else 0.0
        # 穿插避让: neighbouring parts one clear pixel apart, a part inside a frame one or two.
        # Pairs whose parts are both measured are left to 'reference', which knows how far
        # apart Chiron Hei HK sets them (宫: 口 tucked between the legs of 宀)
        g = 0.0
        for i, j, kind in self.pairs:
            if i in self.measured_parts and j in self.measured_parts:
                continue
            d = self._distance(i, j)          # 3: one clear pixel between
            if kind == 'enclose':
                g += 1.0 if d <= 2 else 0.0 if d <= 4 else float((d - 4) ** 2)
            else:
                g += 1.0 if d <= 2 else float((d - 3) ** 2)
        m['gaps'] = g / max(1, len(self.pairs))
        # 四平八稳, alignment: the parts of a stack (and the inside of a symmetric frame) on its
        # centre line, by their axis: the main vertical stroke if they have one (十 土 木),
        # else the middle of their ink
        al = 0.0
        for unit, (x0, x1) in self.stacks:
            ax = self._axis(unit)
            if ax is not None:
                al += abs(ax - (x0 + x1 - 1) / 2)
        m['align'] = al / GEO.BODY_W     # summed: every unit off its axis counts in full
        # 四平八稳, level: strokes of neighbouring parts either line up or are clearly apart;
        # a near miss by one pixel reads as a mistake (the horizontals of 林, the tops of 明,
        # the verticals of 圭)
        lev = 0.0
        for (i, j, kind), den in zip(self.pairs, self.level_den):
            if not den or kind != 'side':
                continue
            fa, fb = self._features(i, kind), self._features(j, kind)
            miss = sum(min(abs(f - t) for t in theirs) == 1
                       for mine, theirs in ((fa, fb), (fb, fa)) if theirs for f in mine)
            lev += miss / den
        m['level'] = lev / max(1, sum(1 for (_, _, k), d in zip(self.pairs, self.level_den) if d and k == 'side'))
        # 风格统一 / the paper's rule-based metric, from measurements: every unit where Chiron Hei
        # HK puts it (昌: the upper 日 narrower and shorter than the lower). Mean distance of
        # the four edges of its ink from the measured ones, in pixels
        err, wsum = 0.0, 0.0
        for unit, (tx0, ty0, tx1, ty1), wt in self.measured:
            bb = self._cached('ubox', unit, lambda: _bbox(self._ink(unit)))
            if bb is None:
                continue
            err += wt * (abs(bb[0] - tx0) + abs(bb[1] - ty0) + abs(bb[2] - tx1) + abs(bb[3] - ty1)) / 4
            wsum += wt
        m['reference'] = err / wsum if wsum else 0.0
        # 四平八稳, symmetry: twins the same size (the two 口 at the bottom of 品)
        tw, nt = 0.0, 0
        for i, others in self.twins.items():
            for j, _ in others:
                if i < j:
                    a, b = self._part_bbox(i), self._part_bbox(j)
                    if a and b:
                        tw += abs((a[2] - a[0]) - (b[2] - b[0])) + abs((a[3] - a[1]) - (b[3] - b[1]))
                        nt += 1
        m['twins'] = tw / nt / GEO.BODY_W if nt else 0.0
        dens = []
        for i, p in enumerate(self.parts):
            bb = self._part_bbox(i)
            if bb:
                dens.append(self._cached('ink', (i,), lambda: int(p.ink.sum())) / ((bb[2] - bb[0]) * (bb[3] - bb[1])))
        m['density'] = (max(dens) - min(dens)) if len(dens) > 1 else 0.0
        rule, ruled = 0.0, 0
        for i, p in enumerate(self.parts):
            if p.target:
                bb = self._part_bbox(i)
                if bb:
                    x0, y0, x1, y1 = p.box
                    for axis, (lo, hi, c) in (('x', (x0, x1, (bb[0] + bb[2]) / 2)),
                                              ('y', (y0, y1, (bb[1] + bb[3]) / 2))):
                        if axis in p.target:
                            rule += abs(c - (lo + p.target[axis] * (hi - lo))) / (hi - lo)
                    ruled += 1
        m['rule'] = rule / ruled if ruled else 0.0
        m['moved'] = sum(abs(p.dx - p.start[0]) + abs(p.dy - p.start[1])
                         + (abs(p.gw - p.start[2]) + abs(p.gh - p.start[3])) / 2 for p in self.parts) / len(self.parts)
        return m

    def _ink(self, idx) -> np.ndarray:
        out = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
        for i in idx:
            out |= self.parts[i].ink
        return out

    def _cached(self, name: str, parts, compute):
        """A value that depends only on the states of some parts, computed once per state."""
        ck = (name, tuple(parts), tuple(self.parts[k].state() for k in parts))
        hit = self._memo.get(ck, self)
        if hit is self:
            hit = self._memo[ck] = compute()
        return hit

    def _part_bbox(self, i: int):
        return self._cached('bbox', (i,), lambda: _bbox(self.parts[i].ink))

    def _distance(self, i: int, j: int) -> int:
        return self._cached('dist', (i, j), lambda: self._find_distance(i, j))

    def _find_distance(self, i: int, j: int) -> int:
        """1: overlapping, 2: touching, 3: one clear pixel between, ... up to 5."""
        a, b = self.parts[i].ink, self.parts[j].ink
        d, grown = 1, a
        while d <= 4 and not (grown & b).any():
            grown = _dilate(grown)
            d += 1
        return d

    def _side_gaps(self, unit, others, sides: str) -> List[Optional[int]]:
        """Clear pixels between the unit's ink and the others' on each side ('l' 'r' 't' 'b'),
        at the narrowest place; None where that side is open."""
        u, o = self._ink(unit), self._ink(others) & ~self._ink(unit)
        out = []
        for side in sides:
            uu, oo = (u, o) if side in 'lr' else (u.T, o.T)
            gap = None
            for r in np.flatnonzero(uu.any(axis=1)):
                ui = np.flatnonzero(uu[r])
                oi = np.flatnonzero(oo[r])
                if side in 'lt':
                    near = oi[oi < ui[0]]
                    g = int(ui[0] - near[-1] - 1) if len(near) else None
                else:
                    near = oi[oi > ui[-1]]
                    g = int(near[0] - ui[-1] - 1) if len(near) else None
                if g is not None:
                    gap = g if gap is None else min(gap, g)
            out.append(gap)
        return out

    def _axis(self, unit) -> Optional[float]:
        return self._cached('axis', unit, lambda: self._find_axis(unit))

    def _find_axis(self, unit) -> Optional[float]:
        """The unit's vertical axis: its one long vertical stroke in the middle third, else the middle of its ink."""
        ink = self._ink(unit)
        bb = _bbox(ink)
        if bb is None:
            return None
        x0, y0, x1, y1 = bb
        third = (x1 - x0) / 3
        runs = _longest_runs(ink[y0:y1, x0:x1].T)
        spines = [x0 + int(i) for i in np.flatnonzero(runs >= 0.6 * (y1 - y0))
                  if x0 + third <= x0 + i + 0.5 <= x1 - third]
        return float(spines[0]) if len(spines) == 1 else (x0 + x1 - 1) / 2

    def _unit_strokes(self, unit, axis: str) -> List[int]:
        return self._strokes(unit, axis) if len(unit) > 1 else self._strokes(unit[0], axis)

    def _strokes(self, i, axis: str) -> List[int]:
        """Rows with a horizontal stroke (axis 'y'), or columns with a vertical one ('x'), of a
        part or a unit (list of parts): a run across most of it (60%, at least 3px), not
        where slants meet a stem."""
        idx = tuple(i) if isinstance(i, list) else (i,)
        ck = (idx, axis, tuple(self.parts[k].state() for k in idx))
        hit = self._stroke_cache.get(ck)
        if hit is None:
            hit = self._stroke_cache[ck] = self._find_strokes(i, axis)
        return hit

    def _find_strokes(self, i, axis: str) -> List[int]:
        ink = self._ink(i) if isinstance(i, list) else self.parts[i].ink
        ink = ink if axis == 'y' else ink.T
        cols = np.flatnonzero(ink.any(axis=0))
        if len(cols) == 0:
            return []
        need = max(3, math.ceil(0.6 * (cols[-1] - cols[0] + 1)))
        return [int(r) for r in np.flatnonzero(_longest_runs(ink) >= need)]

    def _features(self, i: int, kind: str) -> List[int]:
        return self._cached('feat' + kind, (i,), lambda: self._find_features(i, kind))

    def _find_features(self, i: int, kind: str) -> List[int]:
        """Lines a neighbour's strokes should meet or clearly miss: for side-by-side parts the
        rows of horizontal strokes (3px or longer) and of the top and bottom of the ink; for
        stacked parts the columns of vertical strokes and of the left and right of the ink."""
        ink = self.parts[i].ink if kind == 'side' else self.parts[i].ink.T
        bb = _bbox(ink.T)          # (y0, x0, y1, x1) of the oriented ink
        if bb is None:
            return []
        return sorted({bb[0], bb[2] - 1} | set(self._strokes(i, 'y' if kind == 'side' else 'x')))

    def movable(self) -> bool:
        return any(not p.fixed and (p.generated or p.region != p.box or
                                    (p.core and (p.core[2] - p.core[0] < p.key.w or p.core[3] - p.core[1] < p.key.h)))
                   for p in self.parts)


class Scorer:
    """
    The paper's evaluation function h = sum w_i (M_i / (mean_i + sd_i))^2. Metrics in
    FIXED_SCALE are not normalised by the font's statistics: their unit already means
    something (one near miss per pair of parts), and the unrefined glyphs have them so
    often that the font's own mean would excuse them.
    """

    def __init__(self, samples: List[Dict[str, float]], weights=None):
        self.weights = weights or WEIGHTS
        self.scale = {}
        for k in self.weights:
            if k in FIXED_SCALE:
                self.scale[k] = FIXED_SCALE[k]
                continue
            vals = [s[k] for s in samples] or [0.0]
            mean = sum(vals) / len(vals)
            sd = math.sqrt(sum((v - mean) ** 2 for v in vals) / len(vals))
            self.scale[k] = max(mean + sd, SCALE_FLOOR[k])

    def __call__(self, m: Dict[str, float]) -> float:
        return sum(w * (m[k] / self.scale[k]) ** 2 for k, w in self.weights.items())


def optimise(arr: Arrangement, score: Scorer) -> Tuple[float, float]:
    """
    Greedy search over moves and (generated parts) resizes. Returns (score before, after).
    A change may not bring parts into new contact, nor shrink the outline of the glyph;
    twins (identical neighbours) take the same change across their split.
    """
    best = score(arr.metrics())
    start = best
    for _ in range(MAX_STEPS):
        choice = None
        for k, p in enumerate(arr.parts):
            dx, dy, gw, gh = p.state()
            for cand in [(dx + mx, dy + my, gw, gh) for mx, my in MOVES] + \
                        ([(dx, dy, gw + ax, gh + ay) for ax, ay in GROWS] if p.generated else []):
                change = _with_twins(arr, k, cand)
                if change is None:
                    continue
                saved = [(q, q.state()) for q, _ in change]
                ok = all(q.set_state(*st) for q, st in change)
                ok = ok and not any(arr.new_contact(arr.parts.index(q)) for q, _ in change) and arr.keeps_outline()
                if ok:
                    sc = score(arr.metrics())
                    if sc < best - 1e-9 and (choice is None or sc < choice[0]):
                        choice = (sc, change)
                for q, st in saved:
                    q.set_state(*st)
        if choice is None:
            break
        best, change = choice
        for q, st in change:
            q.set_state(*st)
    return start, best


def _with_twins(arr: Arrangement, k: int, cand) -> Optional[List[Tuple[Part, Tuple[int, int, int, int]]]]:
    """The change of part k to state cand, with the matching change of its twins."""
    p = arr.parts[k]
    dx, dy, gw, gh = p.state()
    change = [(p, cand)]
    resized = gw != cand[2] or gh != cand[3]
    for j, kind in arr.twins.get(k, ()):
        q = arr.parts[j]
        qx, qy, qw, qh = q.state()
        same = q.key == p.key
        if kind == 'side':
            moved = cand[1] != dy
            new = (qx, qy + cand[1] - dy, qw, qh)
        else:
            moved = cand[0] != dx
            new = (qx + cand[0] - dx, qy, qw, qh)
        if resized and same:
            if not q.generated:
                return None
            new = (new[0], new[1], qw + cand[2] - gw, qh + cand[3] - gh)
        if moved or (resized and same):
            change.append((q, new))
    return change
