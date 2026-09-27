"""
Evenness pass: refines each assembled character by greedy search, after
  P.-K. Lai, D.-Y. Yeung, M.-C. Pong, "A Heuristic Search Approach to Chinese Glyph
  Generation Using Hierarchical Character Composition", Computer Processing of
  Oriental Languages 10(3), 1996 (and the 1995 conference version),
adapted to 1-bit 16px glyphs assembled from glyphlettes drawn at size.

The paper transforms component bounding boxes (move, enlarge, shrink) and keeps
whichever change most improves a weighted sum of normalised "beauty metrics" that
quantify calligraphy rules: 四平八稳 alignment and stability, 布白均匀 even white
space, 穿插避让 closing gaps between components. Here:

  - Parts only move within their own boxes, so they never collide. There is often
    room: generated parts are smaller than their boxes (and can be regenerated a
    size up or down), reused drawings sit in boxes up to 2px larger, and hand
    drawings rarely fill theirs.
  - The inner part of a surround may use the largest empty rectangle inside the
    frame's actual ink (keeping 1px clear), not just its configured box.
  - Parts next to a joined joint do not move vertically, so bridging still meets.
  - Protruding pixels move with their part.

Metrics (0 = ideal), each normalised by the font's own statistics as in the paper,
MN = (M / (mean + sd))^2, measured over the initial configurations:
  cog       centre of gravity away from the body centre
  spacing   unevenness (coefficient of variation) of the gaps between parallel
            strokes, along every row and column
  gaps      adjacent parts not exactly one clear pixel apart
  align     parts of a stack (⿱ ⿳) off the stack's centre line
  density   spread of ink density between parts
  rule      parts away from their preferred position (layout.tsv 'place' column),
            the paper's rule-based metric: distance over box size, per axis
  moved     distance from the initial configuration (keeps changes small)

A part with a preferred position also starts there, as far as its room and the
no-contact rule allow; the 'rule' metric keeps other metrics from pulling it away.
The paper's border-elimination metric is left out: it concerns trimming margins
after scaling, which never happens here, and it rewards pushing parts apart.

Hard constraint: a change may not bring two parts into contact (touching or
overlapping) unless they already were, e.g. at a bridged or protruding joint.
"""

import math
from typing import Dict, List, Optional, Tuple

import numpy as np

import generators as GN
import geometry as GEO
import ids as IDS
from geometry import Layout, SlotKey

WEIGHTS = {'cog': 1.0, 'spacing': 1.0, 'gaps': 1.5, 'align': 1.0, 'density': 0.3, 'rule': 1.5, 'moved': 0.5}
# The paper scales each metric by mean + sd over the font's initial configurations.
# A metric that hardly varies there (every initial stack is centred, so 'align' is
# always 0) would get a tiny or zero scale and then dominate or vanish; these floors,
# in the metric's own units, keep one pixel of error meaningful.
SCALE_FLOOR = {'cog': 0.5 / GEO.BODY_W, 'spacing': 0.1, 'gaps': 0.5, 'align': 1 / GEO.BODY_W,
               'density': 0.05, 'rule': 0.5 / GEO.BODY_W, 'moved': 1.0}
MAX_STEPS = 16
# Whether hand-drawn parts may be moved within their boxes too (not only generated
# and reused ones). A drawing that fills its box cannot move either way.
MOVE_DRAWN = True
MOVES = ((1, 0), (-1, 0), (0, 1), (0, -1))
GROWS = ((2, 0), (-2, 0), (0, 2), (0, -2))

Rect = Tuple[int, int, int, int]   # x0, y0, x1, y1 (exclusive)
BODY: Rect = (GEO.BODY_X, GEO.BODY_Y, GEO.BODY_X + GEO.BODY_W, GEO.BODY_Y + GEO.BODY_H)


def _bbox(mask: np.ndarray) -> Optional[Rect]:
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


def _dilate(mask: np.ndarray) -> np.ndarray:
    """8-neighbourhood dilation by one pixel."""
    out = mask.copy()
    out[1:] |= mask[:-1]
    out[:-1] |= mask[1:]
    row = out.copy()
    out[:, 1:] |= row[:, :-1]
    out[:, :-1] |= row[:, 1:]
    return out


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
        self.stack: Optional[Tuple[int, int]] = None      # (x0, x1) of its ⿱/⿳ stack
        self.dx = self.dy = 0
        self.gw = self.gh = 0                   # size change of a generated part
        self.ink = base

    def state(self):
        return self.dx, self.dy, self.gw, self.gh

    def set_state(self, dx, dy, gw, gh) -> bool:
        """Apply a state; False (and unchanged) if it is not allowed."""
        if self.fixed and (dx, dy, gw, gh) != (0, 0, 0, 0):
            return False
        if (gw or gh) and not self.generated:
            return False
        if self.pin_y and (dy or gh):
            return False
        if gw or gh:
            w, h = self.key.w + gw, self.key.h + gh
            if w < 1 or h < 1:
                return False
            g = GN.generate(SlotKey(self.key.comp, w, h))
            if g is None:
                return False
            x0, y0 = self.box[0] + (self.key.w - w) // 2 + dx, self.box[1] + (self.key.h - h) // 2 + dy
            core = _bbox(g)
            if core is None:
                return False
            cx0, cy0, cx1, cy1 = core[0] + x0, core[1] + y0, core[2] + x0, core[3] + y0
            if x0 < 0 or y0 < 0 or x0 + w > GEO.CELL_W or y0 + h > GEO.CELL_H:
                return False
            ink = _place(g, x0, y0)
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
        # parts in contact at the start (bridged or protruding joints) may stay so
        self.contact = {(i, j) for i, j in self._all_pairs() if self._touching(i, j)}
        self._surround_regions()
        self._contexts(layout.root(cp))
        for gy, x0, x1 in joined_rows:
            for p in self.parts:
                px0, py0, px1, py1 = p.box
                if px0 < x1 and x0 < px1 and (py1 == gy or py0 == gy + 1):
                    p.pin_y = True
        self._apply_rules()

    def _pairs(self):
        pairs = []
        for i, a in enumerate(self.parts):
            for j in range(i + 1, len(self.parts)):
                b = self.parts[j]
                ax0, ay0, ax1, ay1 = a.box
                bx0, by0, bx1, by1 = b.box
                side = (ax1 + GEO.GAP == bx0 or bx1 + GEO.GAP == ax0) and ay0 < by1 and by0 < ay1
                stacked = (ay1 + GEO.GAP == by0 or by1 + GEO.GAP == ay0) and ax0 < bx1 and bx0 < ax1
                if side or stacked or self._encloses(a, b) or self._encloses(b, a):
                    pairs.append((i, j))
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
        """Each part's position in its parent (left, top, inner, ...), and the stack it is centred in."""
        at = {(p.key, p.box[0], p.box[1]): p for p in self.parts}

        def walk(key, x, y):
            s = self.layout.slot(key)
            if not s.parts or (key, x, y) in at:
                return
            n = len(s.parts)
            for i, (c, dx, dy) in enumerate(s.parts):
                p = at.get((c, x + dx, y + dy))
                if p is not None:
                    if c.role:
                        p.context = 'frame'
                    elif s.op in IDS.SURROUND:
                        p.context = 'inner'
                    elif s.op in IDS.SPLIT_H:
                        p.context = 'left' if i == 0 else 'right' if i == n - 1 else 'middle'
                    else:
                        p.context = 'top' if i == 0 else 'bottom' if i == n - 1 else 'middle'
                        p.stack = (x, x + key.w)
                walk(c, x + dx, y + dy)

        walk(root, GEO.BODY_X, GEO.BODY_Y)
        for p in self.parts:
            rule = self.layout.rules.get(p.key.comp)
            if rule and rule.place:
                p.target = rule.place.get(p.context) or rule.place.get('any') or {}

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
        body = self.compose()[BODY[1]:BODY[3], BODY[0]:BODY[2]]
        ys, xs = np.nonzero(body)
        if len(ys) == 0:
            return {k: 0.0 for k in WEIGHTS}
        m = {'cog': abs(xs.mean() - (GEO.BODY_W - 1) / 2) / GEO.BODY_W
                    + abs(ys.mean() - (GEO.BODY_H - 1) / 2) / GEO.BODY_H}
        gaps = []
        for line in list(body) + list(body.T):
            idx = np.flatnonzero(line)
            if len(idx) > 1:
                d = np.diff(idx) - 1
                gaps.extend(d[d > 0].tolist())
        m['spacing'] = float(np.std(gaps) / np.mean(gaps)) if len(gaps) > 1 else 0.0
        g = 0.0
        for i, j in self.pairs:
            a, b = self.parts[i].ink, self.parts[j].ink
            d, grown = 1, a
            while d <= 4 and not (grown & b).any():
                grown = _dilate(grown)
                d += 1
            # d = 1: touching or overlapping, 2: one clear pixel between, ...
            g += 0.0 if d == 2 else (1.0 if d == 1 else float((d - 2) ** 2))
        m['gaps'] = g / max(1, len(self.pairs))
        al = 0.0
        for p in self.parts:
            if p.stack:
                bb = _bbox(p.ink)
                if bb:
                    al += abs((bb[0] + bb[2]) - (p.stack[0] + p.stack[1])) / 2
        m['align'] = al / GEO.BODY_W
        dens = []
        for p in self.parts:
            bb = _bbox(p.ink)
            if bb:
                dens.append(p.ink.sum() / ((bb[2] - bb[0]) * (bb[3] - bb[1])))
        m['density'] = (max(dens) - min(dens)) if len(dens) > 1 else 0.0
        rule, ruled = 0.0, 0
        for p in self.parts:
            if p.target:
                bb = _bbox(p.ink)
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

    def movable(self) -> bool:
        return any(not p.fixed and (p.generated or p.region != p.box or
                                    (p.core and (p.core[2] - p.core[0] < p.key.w or p.core[3] - p.core[1] < p.key.h)))
                   for p in self.parts)


class Scorer:
    """The paper's evaluation function h = sum w_i (M_i / (mean_i + sd_i))^2."""

    def __init__(self, samples: List[Dict[str, float]], weights=None):
        self.weights = weights or WEIGHTS
        self.scale = {}
        for k in self.weights:
            vals = [s[k] for s in samples] or [0.0]
            mean = sum(vals) / len(vals)
            sd = math.sqrt(sum((v - mean) ** 2 for v in vals) / len(vals))
            self.scale[k] = max(mean + sd, SCALE_FLOOR[k])

    def __call__(self, m: Dict[str, float]) -> float:
        return sum(w * (m[k] / self.scale[k]) ** 2 for k, w in self.weights.items())


def optimise(arr: Arrangement, score: Scorer) -> Tuple[float, float]:
    """Greedy search over moves and (generated parts) resizes. Returns (score before, after)."""
    best = score(arr.metrics())
    start = best
    for _ in range(MAX_STEPS):
        choice = None
        for k, p in enumerate(arr.parts):
            dx, dy, gw, gh = p.state()
            for cand in [(dx + mx, dy + my, gw, gh) for mx, my in MOVES] + \
                        ([(dx, dy, gw + ax, gh + ay) for ax, ay in GROWS] if p.generated else []):
                if not p.set_state(*cand):
                    continue
                if arr.new_contact(k):
                    p.set_state(dx, dy, gw, gh)
                    continue
                s = score(arr.metrics())
                if s < best - 1e-9 and (choice is None or s < choice[0]):
                    choice = (s, p, cand)
                p.set_state(dx, dy, gw, gh)
        if choice is None:
            break
        best, p, cand = choice
        p.set_state(*cand)
    return start, best
