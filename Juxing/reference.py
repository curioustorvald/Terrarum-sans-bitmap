"""
Reference layouts measured in Chiron Hei HK: Juxing's fourth source of truth.

Unihan and BabelStone say what a character is made of, and CNS 11643 how its strokes
run, but none of them says how big each part is or where it sits: that 昌's upper 日 is
narrower and shorter than the lower one. Chiron Hei HK (The Chiron Hei HK Project
Authors, SIL Open Font License 1.1) is a carefully designed font that covers every
target, so its glyphs are measured instead. It is derived from Source Han Sans and has
its metrics, with more uniform white space, which makes its parts easier to measure.

Only measurements are taken: the ink box of every part of a character's decomposition,
in units of the font's ideographic character face (BASE icfb/icft). The boxes guide the
layout and the evenness pass; no outline and no pixel of the font reaches a drawing sheet
or the output, except as the editor's suggestions (suggest.py), which the artist takes on
request. The measurements carry the font's attribution in their header.

Method, per character (rendered at RENDER px per em, Regular weight):
  - The ink comes in pieces: the glyph's contours, each with its counters cut out
    (Renderer.piece_masks). Chiron Hei HK, being variable, keeps overlapping strokes as
    separate contours, so a piece is a stroke or a few strokes drawn as one, and strokes
    that touch in the ink stay apart (兰's right dot on its first bar). A part has as many
    pieces in a character as on its own, mostly (expected_pieces).
  - The decomposition tree is followed from the root exactly as the geometry follows it
    (Layout.decomposition), so a measured node and a slot share their path: the child
    indices from the root, the frame of a surround being child 0 and its inside child 1.
  - A ⿰ ⿲ node is cut at the columns (⿱ ⿳ at the rows) that leave the least ink of any
    piece on the wrong side, or cut pieces where they are thinnest, near where the geometry
    would cut (the prior picks the right one of several clean gaps), with each part getting
    as many pieces as it should (a wrong gap gains or loses a stroke). Each piece goes to
    the side holding most of it, unless cutting it costs less (the stem of 出, shared). The
    node records where its parts divide (Box.bounds: between their bodies).
  - A surround's inside is a rectangle reaching the node's edges where the frame is open
    (冂: the bottom; 广: the bottom and the right); its edges on the frame's walls are
    searched as cuts are, starting from the layout's insets, with the inside's share of
    the ink pulled towards its share of the strokes (唇: 口 has 3 of 10, so the inside
    lies below the strokes inside 辰). Pieces of both (石 右 同) are divided along it.
  - Every node gets the box of its ink and 'cut', the share of its parent's ink that had
    to be cut to separate it: 0 for a clean split. A split that leaves a side without
    ink ends the measurement of that branch.

Output: model/reference.tsv (see Reference.save), which Layout and the evenness pass
read through Reference.load.
"""

import math
import os
import shutil
import subprocess
from dataclasses import dataclass
from multiprocessing import Pool
from typing import Dict, List, Optional, Tuple

import numpy as np

import ids as IDS

HERE = os.path.dirname(os.path.abspath(__file__))
REFERENCE_PATH = os.path.join(HERE, 'model', 'reference.tsv')
SOURCES_DIR = os.path.join(HERE, 'sources')

# a file in sources/ (as the Google Fonts download unpacks) wins over an installed font.
# Only Chiron Hei HK is looked for: another design would be another reference.
FONT_FILES = ['Chiron Hei HK/static/ChironHeiHK-Regular.ttf', 'Chiron Hei HK/ChironHeiHK-VariableFont_wght.ttf',
              'ChironHeiHK-Regular.ttf', 'ChironHeiHK-VariableFont_wght.ttf']
FONT_FAMILIES = ['Chiron Hei HK']
FONT_URL = 'https://fonts.google.com/specimen/Chiron+Hei+HK'
WEIGHT = 400
RENDER = 160            # px per em
CUT_BAND = 0.08         # cuts are not tried this close to the ends of a node
CUT_CHARGE = 0.03       # cutting a connected component costs this share of the node's ink...
TOUCH_COST = 2.0        # ...plus this many times its ink on the cut line
WALL_SHARE = 0.7        # a frame's wall holding at most this share of the ink is separate from the inside...
WALL_CUT = 20.0         # ...and cutting it costs this many times more
MIN_INNER_SHARE = 0.05  # an inside with less of the ink means the walls touch it after all
PRIOR = 0.5             # pull of the expected cut (times the node's ink, per squared share)
MIN_PART_INK = 0.04     # a cut leaving a part less of the node's ink than this is avoided
AMBIGUOUS_INK = 0.05    # a split is ambiguous if a cut moving this share of the ink across...
AMBIGUOUS_MARGIN = 0.02 # ...costs less than this share of the ink more (鬱: below 冖 or inside 鬯?)
COUNT_COST = 0.05       # a part with a piece more or fewer than it should have (per piece, of the ink)
SPECK = 0.003           # pieces with less than this share of the ink are not counted as pieces
SUPERSAMPLE = 4         # contours are filled at this many times the resolution


@dataclass
class Box:
    comp: str
    x0: float           # in units of the character face, 0..1, y downwards
    y0: float
    x1: float
    y1: float
    cut: float          # share of the parent's ink cut to separate this node
    ambiguous: bool = False   # another cut of the parent was nearly as good
    # a surround's frame: where its ink comes closest to the inside on each walled side
    # (left, top, right, bottom; None where open), within the inside's rows or columns
    walls: Optional[Tuple[Optional[float], Optional[float], Optional[float], Optional[float]]] = None
    # a split node: where its parts divide along the split, in face units (_bounds)
    bounds: Optional[Tuple[float, ...]] = None

    @property
    def w(self) -> float:
        return self.x1 - self.x0

    @property
    def h(self) -> float:
        return self.y1 - self.y0


# ---------------------------------------------------------------------------
# The font

def find_font() -> Optional[str]:
    for name in FONT_FILES:
        p = os.path.join(SOURCES_DIR, name)
        if os.path.exists(p):
            return p
    if shutil.which('fc-match'):
        for fam in FONT_FAMILIES:
            try:
                p = subprocess.run(['fc-match', '-f', '%{file}', fam], capture_output=True, text=True).stdout
            except OSError:
                continue
            base = os.path.basename(p).lower().replace('-', '').replace(' ', '')
            if p and 'chironheihk' in base:
                return p
    return None


class _Flatten:
    """A pen that records a glyph's contours as polylines in font units, curves flattened to
    segments of about `step` units."""

    def __new__(cls, glyph_set, step):
        from fontTools.pens.basePen import BasePen

        class Pen(BasePen):
            def __init__(self):
                super().__init__(glyph_set)
                self.contours, self.cur = [], None

            def _moveTo(self, pt):
                self.cur = [pt]

            def _lineTo(self, pt):
                self.cur.append(pt)

            def _qCurveToOne(self, p1, p2):
                p0 = self.cur[-1]
                n = max(2, math.ceil((math.dist(p0, p1) + math.dist(p1, p2)) / step))
                for i in range(1, n + 1):
                    t = i / n
                    u = 1 - t
                    self.cur.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                                     u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))

            def _curveToOne(self, p1, p2, p3):
                p0 = self.cur[-1]
                n = max(2, math.ceil((math.dist(p0, p1) + math.dist(p1, p2) + math.dist(p2, p3)) / step))
                for i in range(1, n + 1):
                    t = i / n
                    u = 1 - t
                    self.cur.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                                     u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))

            def _closePath(self):
                if self.cur and len(self.cur) > 2:
                    self.contours.append(self.cur)
                self.cur = None

            _endPath = _closePath

        return Pen()


class Renderer:
    """
    Renders characters as ink masks and knows where the character face is in them. A glyph
    is rendered contour by contour: Chiron Hei HK, being variable, keeps overlapping strokes
    as separate contours, so a contour is a stroke or a few strokes drawn as one (口's three),
    and strokes that touch in the ink stay apart as pieces (兰's right dot on its first bar).
    Rendered from the outlines themselves, unhinted, supersampled.
    """

    def __init__(self, path: str):
        from fontTools.ttLib import TTFont
        tt = TTFont(path, fontNumber=0, lazy=True)
        self.cmap = tt.getBestCmap()
        upem = tt['head'].unitsPerEm
        face = None
        if 'BASE' in tt:
            # the character face is square: of the records that disagree (Chiron Hei HK's
            # hani record is 918 units tall and 36 lower than its ink; its DFLT one fits),
            # take the one as tall as the face is wide
            base = tt['BASE'].table
            hs, vs = self._face(base.HorizAxis), self._face(base.VertAxis)
            if hs and vs:
                v = vs[0]
                h = min(hs, key=lambda b: abs((b[1] - b[0]) - (v[1] - v[0])))
                face = (v[0], h[0], v[1], h[1])      # x0, bottom, x1, top in font units
        if face is None:     # the usual 5% inset of the ideographic em box
            face = (0.05 * upem, -0.12 * upem + 0.05 * upem, 0.95 * upem, 0.88 * upem - 0.05 * upem)
        location = None
        if 'fvar' in tt:
            location = {a.axisTag: a.defaultValue for a in tt['fvar'].axes}
            for a in tt['fvar'].axes:
                if a.axisTag == 'wght':
                    location['wght'] = max(a.minValue, min(a.maxValue, WEIGHT))
        self.glyphs = tt.getGlyphSet(location=location)
        self.scale = s = RENDER / upem
        self.size = int(RENDER * 1.3)
        self.origin = (int(RENDER * 0.15), int(RENDER * 1.0))          # pen position, baseline
        ox, oy = self.origin
        self.face = (ox + face[0] * s, oy - face[3] * s, ox + face[2] * s, oy - face[1] * s)
        self.family = tt['name'].getDebugName(1) or os.path.basename(path)
        self.name = tt['name'].getDebugName(4) or os.path.basename(path)
        self.version = tt['name'].getDebugName(5) or ''
        self.copyright = tt['name'].getDebugName(0) or ''
        self._counts: Dict[str, Optional[int]] = {}

    @staticmethod
    def _face(axis) -> List[Tuple[int, int]]:
        """The (icfb, icft) of an axis's hani and DFLT records."""
        if axis is None:
            return []
        tags = list(axis.BaseTagList.BaselineTag)
        if 'icfb' not in tags or 'icft' not in tags:
            return []
        out = []
        for rec in axis.BaseScriptList.BaseScriptRecord:
            if rec.BaseScriptTag in ('hani', 'DFLT') and rec.BaseScript.BaseValues:
                vals = [c.Coordinate for c in rec.BaseScript.BaseValues.BaseCoord]
                out.append((vals[tags.index('icfb')], vals[tags.index('icft')]))
        return out

    def _contours(self, ch: str) -> Optional[List[Tuple[list, bool]]]:
        """A character's contours: (points in font units, outer?). Outer contours run
        clockwise, the counters inside them anticlockwise."""
        if len(ch) != 1 or ord(ch) not in self.cmap:
            return None
        pen = _Flatten(self.glyphs, 2 / self.scale)
        self.glyphs[self.cmap[ord(ch)]].draw(pen)
        return [(c, sum(x0 * y1 - x1 * y0 for (x0, y0), (x1, y1) in zip(c, c[1:] + c[:1])) < 0)
                for c in pen.contours]

    def _fill(self, points) -> np.ndarray:
        """A contour filled: the pixels at least half inside it."""
        from PIL import Image, ImageDraw
        ss, n = SUPERSAMPLE, self.size
        ox, oy = self.origin
        img = Image.new('L', (n * ss, n * ss), 0)
        ImageDraw.Draw(img).polygon([((ox + x * self.scale) * ss - 0.5, (oy - y * self.scale) * ss - 0.5)
                                     for x, y in points], fill=1)
        return np.asarray(img, dtype=np.uint8).reshape(n, ss, n, ss).sum(axis=(1, 3)) * 2 >= ss * ss

    def piece_masks(self, ch: str) -> Optional[List[np.ndarray]]:
        """A character's pieces of ink: each outer contour, with its counters cut out."""
        contours = self._contours(ch)
        if contours is None:
            return None
        outers = [self._fill(c) for c, outer in contours if outer]
        for c, outer in contours:
            if outer:
                continue
            hole = self._fill(c)
            # a counter belongs to the smallest contour around it
            around = [i for i, o in enumerate(outers) if (o & hole).sum() * 2 > hole.sum()]
            if around:
                i = min(around, key=lambda i: outers[i].sum())
                outers[i] = outers[i] & ~hole
        return [o for o in outers if o.any()]

    def render(self, ch: str) -> Optional[np.ndarray]:
        pieces = self.piece_masks(ch)
        if pieces is None:
            return None
        return np.any(pieces, axis=0) if pieces else np.zeros((self.size, self.size), dtype=bool)

    def pieces(self, comp: str) -> Optional[int]:
        """How many pieces (outer contours) a component has on its own, if it is a character
        of the font (川 3, 讠 4); else None."""
        if comp not in self._counts:
            contours = self._contours(comp)
            self._counts[comp] = None if contours is None else sum(outer for _, outer in contours)
        return self._counts[comp]

    def to_face(self, x0, y0, x1, y1) -> Tuple[float, float, float, float]:
        fx0, fy0, fx1, fy1 = self.face
        w, h = fx1 - fx0, fy1 - fy0
        return (x0 - fx0) / w, (y0 - fy0) / h, (x1 - fx0) / w, (y1 - fy0) / h


def expected_pieces(layout, renderer: Renderer, comp: str) -> Optional[int]:
    """
    How many pieces a part should have in a character: its own count if the font has it,
    else the sum over its parts (contours stay apart where strokes overlap, so they add up:
    训 7 = 讠 4 + 川 3, 笠 11 = 𥫗 6 + 立 5); None if not known.
    """
    memo = renderer.__dict__.setdefault('_expected', {})
    if comp in memo:
        return memo[comp]
    memo[comp] = renderer.pieces(comp)        # guards against cyclic decompositions
    if memo[comp] is None:
        node = layout.decomposition(comp)
        if not isinstance(node, str) and node[0] != '㇯':
            kids = [expected_pieces(layout, renderer, IDS.to_string(c)) for c in node[1:]]
            if all(k is not None for k in kids):
                memo[comp] = sum(kids)
    return memo[comp]


# ---------------------------------------------------------------------------
# Segmentation

def _bbox(mask: np.ndarray):
    ys, xs = np.nonzero(mask)
    if len(ys) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1


Frag = Tuple[int, np.ndarray]     # (piece number, the piece's ink in this node)


def _union(frags: List[Frag], shape) -> np.ndarray:
    out = np.zeros(shape, dtype=bool)
    for _, f in frags:
        out |= f
    return out


def _profiles(frags: List[Frag], bb, axis: int):
    """Per piece, its ink on every line of the node along an axis: ((pieces, lines) array,
    first line)."""
    lo, hi = (bb[0], bb[2]) if axis == 1 else (bb[1], bb[3])
    line = np.stack([f.sum(axis=0 if axis == 1 else 1)[lo:hi] for _, f in frags]).astype(np.int64)
    return line, lo


def _split_cuts(line: np.ndarray, expected: List[float], counts: List[Optional[int]] = None):
    """
    Cut a node's ink (per piece, per line) at len(expected) places, given as shares of the
    node's extent. A cut costs, for each piece, the lesser of its ink left on the wrong side
    and cutting it: CUT_CHARGE of the node's ink plus TOUCH_COST times its ink on the cut
    line, so a clean gap wins where there is one, and a piece is cut where it is thinnest
    (早: through the vertical of 十). A prior pulls the cuts towards the expected places,
    which picks the right one of several clean gaps (謝: between 言 and 身). Returns (cuts as
    line indices within the node, ambiguous: a cut moving AMBIGUOUS_INK of the ink across was
    within AMBIGUOUS_MARGIN of it as good), or None.
    counts: how many pieces each part should have (expected_pieces; None: not known). Every
    piece more or fewer than that costs COUNT_COST, a piece counting where most of it is:
    pieces are contours, which keep their count whatever they touch, so a part that gains
    or loses one has been cut wrongly (训: 川's first stroke sweeps under 讠, and the only
    clean gaps are inside 川). Counts are not always exact (吕's upper 口 is one contour, a
    口 on its own two), so this weighs against the other costs rather than deciding.
    """
    n = line.shape[1]
    parts = len(expected) + 1
    if n < parts * 4:
        return None
    cum = np.concatenate([np.zeros((line.shape[0], 1), dtype=np.int64), np.cumsum(line, axis=1)], axis=1)
    total = cum[:, -1:]
    ink = int(total.sum())
    band = max(1, int(n * CUT_BAND))
    cand = np.arange(band, n - band + 1)
    before = cum[:, cand]
    on_line = line[:, np.minimum(cand, n - 1)]
    cutting = CUT_CHARGE * ink + TOUCH_COST * on_line
    per_cut = np.minimum(np.minimum(before, total - before), np.where(on_line > 0, cutting, np.inf)).sum(axis=0)
    counted = total >= SPECK * ink
    known = counts is not None and any(c is not None for c in counts)

    def prior(c, e):
        return PRIOR * ink * ((c / n - e) ** 2)

    # an alternative cut must put a real share of the ink on the other side (another cut
    # in the same gap is the same split)
    at = cum.sum(axis=0)[cand]
    far = AMBIGUOUS_INK * ink
    margin = AMBIGUOUS_MARGIN * ink
    least = MIN_PART_INK * ink        # every part gets a real share of the ink (not a dot's tip)
    if parts == 2:
        cost = per_cut + prior(cand, expected[0]) + ink * ((at < least) | (ink - at < least))
        if known:
            # a piece counts where most of it is: cutting one can't make the counts right
            first = (counted & (2 * before > total)).sum(axis=0)
            off = np.zeros(len(cand))
            for got, want in ((first, counts[0]), (int(counted.sum()) - first, counts[1])):
                if want is not None:
                    off += np.abs(got - want)
            cost = cost + COUNT_COST * ink * off
        i = int(np.argmin(cost))
        others = cost[np.abs(at - at[i]) >= far]
        ambiguous = len(others) > 0 and others.min() - cost[i] < margin
        return [int(cand[i])], bool(ambiguous)
    c1, c2 = cand[:, None], cand[None, :]
    cost = per_cut[:, None] + per_cut[None, :] + prior(c1, expected[0]) + prior(c2, expected[1])
    a1, a2 = at[:, None], at[None, :]
    cost = cost + ink * ((a1 < least) | (a2 - a1 < least) | (ink - a2 < least))
    if known:
        # each piece's ink in the three parts, for every pair of cuts
        t = total[:, :, None]
        b1, b2 = before[:, :, None], before[:, None, :]
        shares = np.stack([np.broadcast_to(b1, (len(total), len(cand), len(cand))),
                           np.maximum(0, b2 - b1), t - np.maximum(b1, b2)])
        most = shares.argmax(axis=0)
        off = np.zeros((len(cand), len(cand)))
        for g, want in enumerate(counts):
            if want is not None:
                off += np.abs((counted[:, :, None] & (most == g)).sum(axis=0) - want)
        cost = cost + COUNT_COST * ink * off
    cost = np.where(c2 - c1 >= max(3, n // 8), cost, np.inf)
    i, j = np.unravel_index(np.argmin(cost), cost.shape)
    if not np.isfinite(cost[i, j]):
        return None
    away = (np.abs(at[:, None] - at[i]) >= far) | (np.abs(at[None, :] - at[j]) >= far)
    others = cost[away & np.isfinite(cost)]
    ambiguous = len(others) > 0 and others.min() - cost[i, j] < margin
    return [int(cand[i]), int(cand[j])], bool(ambiguous)


def _expected_cuts(layout, children: List[str], horizontal: bool, line: np.ndarray) -> List[float]:
    """
    Where the cuts should fall, as shares of the node's extent: halfway between where the
    geometry would cut and where the ink divides in proportion to the parts' strokes (the
    geometry gives a thin 冖 more room than its ink takes, stroke counts can't tell a dot
    from a stroke).
    """
    import geometry as GEO
    k = len(children)
    # not from sizes inferred from an earlier measurement: a cut in the wrong gap would
    # then pull the next measurement into the same gap (训: inside 川)
    sizes = layout.split(children, GEO.REF, horizontal, inferred=False)
    geo, pos = [], 0
    if sizes:
        for sz in sizes[:-1]:
            pos += sz
            geo.append((pos + GEO.GAP / 2) / GEO.REF)
            pos += GEO.GAP
    else:
        geo = [(i + 1) / k for i in range(k - 1)]
    strokes = [max(1, layout.strokes(c)) for c in children]
    profile = np.cumsum(line.sum(axis=0))
    by_ink = []
    for i in range(k - 1):
        share = sum(strokes[:i + 1]) / sum(strokes)
        by_ink.append(float(np.searchsorted(profile, share * profile[-1])) / len(profile))
    return [(a + b) / 2 for a, b in zip(geo, by_ink)]


def _assign_split(frags: List[Frag], cuts, axis: int, lo: int, ink: int) -> Tuple[List[List[Frag]], int]:
    """
    The pieces of each part between the cuts. A piece goes whole to the part holding most of
    it, unless cutting it costs less than leaving its ink on the wrong side, as _split_cuts
    reckons it: then it is cut along the cuts (the stem 出's two parts share; a contour of
    strokes from both). A stroke reaching across with a little of its ink stays whole with its
    part (全: the tail of 人's 丿 beside 王).
    """
    groups: List[List[Frag]] = [[] for _ in range(len(cuts) + 1)]
    lines = [lo + c for c in cuts]
    cut_ink = 0
    for pid, f in frags:
        prof = f.sum(axis=0 if axis == 1 else 1)
        edges = [0] + lines + [len(prof)]
        shares = [int(prof[a:b].sum()) for a, b in zip(edges, edges[1:])]
        wrong = sum(shares) - max(shares)
        crossed = [c for c in lines if prof[min(c, len(prof) - 1)] > 0]
        cutting = sum(CUT_CHARGE * ink + TOUCH_COST * prof[min(c, len(prof) - 1)] for c in crossed)
        if not crossed or wrong <= cutting:
            groups[int(np.argmax(shares))].append((pid, f))
            continue
        cut_ink += wrong
        for e, (a, b) in enumerate(zip(edges, edges[1:])):
            part = np.zeros_like(f)
            if axis == 1:
                part[:, a:b] = f[:, a:b]
            else:
                part[a:b, :] = f[a:b, :]
            if part.any():
                groups[e].append((pid, part))
    return groups, cut_ink


# The sides of a surround's box its frame closes; its inside is open on the others
FRAME_WALLS = {'⿴': 'lrtb', '⿵': 'lrt', '⿶': 'lrb', '⿷': 'ltb', '⿸': 'lt', '⿹': 'rt', '⿺': 'lb',
               '⿼': 'rtb', '⿽': 'rb'}


def _assign_frame(frags: List[Frag], op: str, insets: Tuple[float, float, float, float], inner_share: float,
                  guard: bool = True):
    """
    (frame pieces, inner pieces, ink cut) of a surround. The inside is a rectangle whose
    four edges are searched as cuts are: on the frame's walls from the layout's insets, on
    the sides IDS calls open from the node's edge (勹 is open to the left, but its 丿 closes
    it: a clean gap there wins over cutting the sweep). Each piece costs the lesser of its
    ink on the wrong side and cutting it along the rectangle (a contour of strokes from both,
    as in 石 右 同); priors pull the inside's share of the ink towards the share its strokes
    should have (唇: 口 against 辰) and the edges towards where they started. Pieces cut by
    the rectangle are divided along it; the others go whole to the side holding most of
    them. The frame's walls are not cut unless they must be: when guarding them leaves the
    inside nearly empty, the inside touches them (唇), and the search is done again without
    the guard.
    """
    walls = FRAME_WALLS.get(op)
    if not walls or not frags:
        return None
    x0, y0, x1, y1 = _bbox(_union(frags, frags[0][1].shape))
    w, h = x1 - x0, y1 - y0
    if w < 8 or h < 8:
        return None
    comps = np.stack([f[y0:y1, x0:x1] for _, f in frags]).astype(np.int64)     # [k, h, w]
    k = len(frags)
    sat = np.zeros((k, h + 1, w + 1), dtype=np.int64)
    sat[:, 1:, 1:] = comps.cumsum(1).cumsum(2)
    rows = np.zeros((k, h, w + 1), dtype=np.int64)                 # ink along each row
    rows[:, :, 1:] = comps.cumsum(2)
    cols = np.zeros((k, w, h + 1), dtype=np.int64)                 # ink along each column
    cols[:, :, 1:] = comps.transpose(0, 2, 1).cumsum(2)
    totals = sat[:, -1, -1]
    ink = int(totals.sum())
    el, et, er, eb = (v if side in walls else 0.0 for v, side in zip(insets, 'ltrb'))
    # the frame's walls: pieces reaching the node's edge on a walled side. Unless one holds
    # most of the ink (the inside touches it: 石 右 房), they are not to be cut
    reach = {'l': comps[:, :, :2].any(axis=(1, 2)), 'r': comps[:, :, -2:].any(axis=(1, 2)),
             't': comps[:, :2, :].any(axis=(1, 2)), 'b': comps[:, -2:, :].any(axis=(1, 2))}
    anchor = np.zeros(k, dtype=bool)
    for side in walls:
        anchor |= reach[side]
    protect = np.where(guard & anchor & (totals <= WALL_SHARE * ink), WALL_CUT, 1.0)

    def evaluate(L, T, R, B):
        inside = sat[:, B, R] - sat[:, T, R] - sat[:, B, L] + sat[:, T, L]
        edge = np.zeros(k, dtype=np.int64)
        if T > 0:
            edge += rows[:, T, R] - rows[:, T, L]
        if B < h:
            edge += rows[:, B - 1, R] - rows[:, B - 1, L]
        if L > 0:
            edge += cols[:, L, B] - cols[:, L, T]
        if R < w:
            edge += cols[:, R - 1, B] - cols[:, R - 1, T]
        wrong = np.minimum(inside, totals - inside)
        cutting = np.where(edge > 0, (CUT_CHARGE * ink + TOUCH_COST * edge) * protect, np.inf)
        whole = wrong <= cutting
        # the inside's ink once assigned: whole pieces by majority, cut ones as divided
        inner_ink = np.where(whole, np.where(inside * 2 >= totals, totals, 0), inside).sum()
        c = float(np.minimum(wrong, cutting).sum())
        c += PRIOR * ink * (inner_ink / max(1, ink) - inner_share) ** 2
        c += 0.25 * PRIOR * ink * ((L / w - el) ** 2 + (T / h - et) ** 2 + ((w - R) / w - er) ** 2
                                   + ((h - B) / h - eb) ** 2)
        return c

    # coordinate descent over the four edges, from the expectation
    L, T = int(round(el * w)), int(round(et * h))
    R, B = w - int(round(er * w)), h - int(round(eb * h))
    L, T = max(0, min(L, w - 4)), max(0, min(T, h - 4))
    R, B = max(L + 4, min(R, w)), max(T + 4, min(B, h))
    best = evaluate(L, T, R, B)
    for _ in range(4):
        moved = False
        for side in 'ltrb':
            if side == 'l':
                opts = [(v, T, R, B) for v in range(0, R - 3)]
            elif side == 'r':
                opts = [(L, T, v, B) for v in range(L + 4, w + 1)]
            elif side == 't':
                opts = [(L, v, R, B) for v in range(0, B - 3)]
            else:
                opts = [(L, T, R, v) for v in range(T + 4, h + 1)]
            for o in opts:
                c = evaluate(*o)
                if c < best - 1e-9:
                    best, (L, T, R, B), moved = c, o, True
        if not moved:
            break
    ys, xs = np.mgrid[0:h, 0:w]
    rect = np.zeros(frags[0][1].shape, dtype=bool)
    rect[y0:y1, x0:x1] = (xs >= L) & (xs < R) & (ys >= T) & (ys < B)
    inside = sat[:, B, R] - sat[:, T, R] - sat[:, B, L] + sat[:, T, L]
    edge = np.zeros(k, dtype=np.int64)
    if T > 0:
        edge += rows[:, T, R] - rows[:, T, L]
    if B < h:
        edge += rows[:, B - 1, R] - rows[:, B - 1, L]
    if L > 0:
        edge += cols[:, L, B] - cols[:, L, T]
    if R < w:
        edge += cols[:, R - 1, B] - cols[:, R - 1, T]
    frame, inner, cut_ink = [], [], 0
    for i, (pid, f) in enumerate(frags):
        wrong = min(inside[i], totals[i] - inside[i])
        # divided where that costs less than leaving its ink on the wrong side, as evaluated
        if edge[i] > 0 and wrong > (CUT_CHARGE * ink + TOUCH_COST * edge[i]) * protect[i]:
            cut_ink += int(wrong)
            if (f & rect).any():
                inner.append((pid, f & rect))
            if (f & ~rect).any():
                frame.append((pid, f & ~rect))
        else:
            (inner if inside[i] * 2 >= totals[i] else frame).append((pid, f))
    if guard and sum(int(f.sum()) for _, f in inner) < MIN_INNER_SHARE * ink:
        return _assign_frame(frags, op, insets, inner_share, guard=False)
    return frame, inner, cut_ink


def _walls(frame: np.ndarray, inner: np.ndarray, op: str, renderer: 'Renderer'):
    """
    Where a frame's ink comes closest to its inside on each walled side, in face units
    (left, top, right, bottom; None where open or where no ink faces the inside): the
    frame's ink beside the inside's rows (left, right) or above and below its columns.
    A frame's box says little about its walls where they slant (广's 丿 reaches the left
    edge only at its tail, well below the inside's top).
    """
    bb = _bbox(inner)
    if bb is None:
        return None
    ix0, iy0, ix1, iy1 = bb
    walls = FRAME_WALLS.get(op, '')
    rows, cols = frame[iy0:iy1], frame[:, ix0:ix1]
    out = []
    for side in 'ltrb':
        v = None
        if side in walls:
            if side == 'l':
                hit = np.nonzero(rows[:, :ix0].any(axis=0))[0]
                v = renderer.to_face(hit.max() + 1, 0, 0, 0)[0] if len(hit) else None
            elif side == 'r':
                hit = np.nonzero(rows[:, ix1:].any(axis=0))[0]
                v = renderer.to_face(ix1 + hit.min(), 0, 0, 0)[0] if len(hit) else None
            elif side == 't':
                hit = np.nonzero(cols[:iy0].any(axis=1))[0]
                v = renderer.to_face(0, hit.max() + 1, 0, 0)[1] if len(hit) else None
            else:
                hit = np.nonzero(cols[iy1:].any(axis=1))[0]
                v = renderer.to_face(0, iy1 + hit.min(), 0, 0)[1] if len(hit) else None
        out.append(v)
    return tuple(out)


def _bounds(groups: List[List[Frag]], axis: int, bb) -> List[float]:
    """
    Where consecutive parts of a split divide, in px along the split: the line leaving the
    least of either part's ink on the other's side, and the middle of the run of lines that
    leave as little. Between parts apart that is the middle of the gap between them; where
    a part's stroke reaches under its neighbour (鳴: 鳥's foot under 口), it is between their
    bodies, not between their boxes.
    """
    lo, hi = (bb[0], bb[2]) if axis == 1 else (bb[1], bb[3])
    profs = [np.zeros(hi - lo, dtype=np.int64) for _ in groups]
    for prof, g in zip(profs, groups):
        for _, f in g:
            prof += f.sum(axis=0 if axis == 1 else 1)[lo:hi]
    out = []
    for a, b in zip(profs, profs[1:]):
        # at line x: a's ink from x on, plus b's ink before x
        cost = (a.sum() - np.concatenate(([0], np.cumsum(a)))) + np.concatenate(([0], np.cumsum(b)))
        best = int(np.argmin(cost))
        i = j = best
        while i > 0 and cost[i - 1] == cost[best]:
            i -= 1
        while j < len(cost) - 1 and cost[j + 1] == cost[best]:
            j += 1
        out.append(lo + (i + j) / 2)
    return out


def measure(layout, renderer: Renderer, ch: str, masks: Dict[str, np.ndarray] = None,
            counts: Dict[str, int] = None) -> List[Tuple[str, Box]]:
    """[(path, box)] of a character's decomposition tree; [] if the font lacks it. `masks`, if
    given, receives every node's ink by path, and `counts` its number of pieces (suggest.py
    traces them)."""
    pieces = renderer.piece_masks(ch)
    if not pieces:
        return []
    shape = pieces[0].shape
    out = []

    def visit(comp: str, frags: List[Frag], path: str, cut: float, ambiguous: bool = False, walls=None):
        m = _union(frags, shape)
        bb = _bbox(m)
        if bb is None:
            return
        box = Box(comp, *renderer.to_face(*bb), cut, ambiguous, walls)
        out.append((path, box))
        if masks is not None:
            masks[path] = m
        if counts is not None:
            counts[path] = len({pid for pid, _ in frags})
        if layout.never_split(comp) is not None:     # as the geometry: 广 is not 丶 over 厂
            return
        node = layout.decomposition(comp)
        op = node[0]
        children = [IDS.to_string(x) for x in node[1:]]
        ink = int(m.sum())
        if op in IDS.SPLIT_H or op in IDS.SPLIT_V:
            axis = 1 if op in IDS.SPLIT_H else 0
            line, lo = _profiles(frags, bb, axis)
            found = _split_cuts(line, _expected_cuts(layout, children, axis == 1, line),
                                [expected_pieces(layout, renderer, c) for c in children])
            if found is None:
                return
            cuts, amb = found
            groups, cut_ink = _assign_split(frags, cuts, axis, lo, ink)
        elif op in IDS.SURROUND:
            import geometry as GEO
            # the search starts from the operator's insets (or a hand setting), not from
            # insets inferred from an earlier measurement, which would make a measurement
            # depend on the one before
            l, t, r, b = layout.insets(children[0], op, GEO.REF, GEO.REF, inferred=False)
            # the inside's expected share of the ink: its strokes are shorter than the
            # frame's, in proportion to its size
            sf, si = (max(1, layout.strokes(c)) for c in children)
            size = ((GEO.REF - l - r) + (GEO.REF - t - b)) / (2 * GEO.REF)
            got = _assign_frame(frags, op, (l / GEO.REF, t / GEO.REF, r / GEO.REF, b / GEO.REF),
                                si * size / (si * size + sf))
            if got is None:
                return
            frame, inner, cut_ink = got
            groups, amb = [frame, inner], False
        else:
            return
        if any(not g for g in groups):
            return
        if op in IDS.SPLIT_H or op in IDS.SPLIT_V:
            box.bounds = tuple(renderer.to_face(v, v, v, v)[0 if axis == 1 else 1] for v in _bounds(groups, axis, bb))
        walls = (_walls(_union(groups[0], shape), _union(groups[1], shape), op, renderer)
                 if op in IDS.SURROUND else None)
        for k, (child, g) in enumerate(zip(children, groups)):
            visit(child, g, f"{path}.{k}" if path else str(k), cut_ink / ink, amb, walls if k == 0 else None)

    visit(ch, list(enumerate(pieces)), '', 0.0)
    return out


# ---------------------------------------------------------------------------
# Building, saving, loading

_worker = {}


def _init(font_path):
    import geometry as GEO
    import model as M
    _worker['layout'] = GEO.Layout(M.load())
    _worker['renderer'] = Renderer(font_path)


def _measure_many(chars):
    return [(ch, measure(_worker['layout'], _worker['renderer'], ch)) for ch in chars]


def build(targets: List[int], font_path: str, processes: int = None, progress=None) -> Dict[str, List[Tuple[str, Box]]]:
    chars = [chr(cp) for cp in targets]
    chunks = [chars[i:i + 200] for i in range(0, len(chars), 200)]
    out = {}
    with Pool(processes, initializer=_init, initargs=(font_path,)) as pool:
        for n, batch in enumerate(pool.imap(_measure_many, chunks)):
            for ch, boxes in batch:
                if boxes:
                    out[ch] = boxes
            if progress:
                progress(min(len(chars), (n + 1) * 200), len(chars))
    return out


def save(data: Dict[str, List[Tuple[str, Box]]], renderer: Renderer, path: str = REFERENCE_PATH):
    with open(path + '.part', 'w', encoding='utf-8', newline='\n') as f:
        f.write("# Juxing reference layouts -- GENERATED by `juxing.py reference`; do not edit.\n")
        f.write(f"# Measured in {renderer.name} {renderer.version}\n")
        f.write(f"# {renderer.copyright}\n")
        f.write(f"# {renderer.family} is licensed under the SIL Open Font License 1.1. Only the ink boxes of\n")
        f.write("# the parts of each character are recorded here; no outline of the font is.\n")
        f.write("# Coordinates are thousandths of the ideographic character face, y downwards.\n")
        f.write("# path: child indices from the character (the frame of a surround is 0, its inside 1).\n")
        f.write("# cut: thousandths of the parent's ink cut to separate the part (0 = a clean split).\n")
        f.write("# amb: 1 if another cut of the parent was nearly as good (the split may be wrong).\n")
        f.write("# walls: of a surround's frame, where its ink comes closest to the inside on the left, top,\n")
        f.write("#   right and bottom (within the inside's rows or columns; '-' where open).\n")
        f.write("# bounds: of a split, where its parts divide along it (between their bodies).\n")
        f.write("#char\tpath\tcomponent\tx0\ty0\tx1\ty1\tcut\tamb\twalls\tbounds\n")
        for ch in sorted(data, key=ord):
            for p, b in data[ch]:
                walls = ','.join('-' if v is None else str(round(v * 1000)) for v in b.walls) if b.walls else '-'
                bounds = ','.join(str(round(v * 1000)) for v in b.bounds) if b.bounds else '-'
                f.write(f"{ch}\t{p or '-'}\t{b.comp}\t{round(b.x0 * 1000)}\t{round(b.y0 * 1000)}\t"
                        f"{round(b.x1 * 1000)}\t{round(b.y1 * 1000)}\t{round(b.cut * 1000)}\t{int(b.ambiguous)}\t"
                        f"{walls}\t{bounds}\n")
    os.replace(path + '.part', path)


class Reference:
    """The measured boxes: reference[char][path] -> Box (path '' for the whole character)."""

    def __init__(self, data: Dict[str, Dict[str, Box]]):
        self.data = data

    @staticmethod
    def load(path: str = REFERENCE_PATH) -> 'Reference':
        data: Dict[str, Dict[str, Box]] = {}
        if os.path.exists(path):
            with open(path, encoding='utf-8') as f:
                for line in f:
                    if line.startswith('#'):
                        continue
                    cols = line.rstrip('\n').split('\t')
                    ch, p, comp, x0, y0, x1, y1, cut, amb = cols[:9]
                    walls = cols[9] if len(cols) > 9 else '-'
                    walls = None if walls == '-' else tuple(None if v == '-' else int(v) / 1000
                                                            for v in walls.split(','))
                    bounds = cols[10] if len(cols) > 10 else '-'
                    bounds = None if bounds == '-' else tuple(int(v) / 1000 for v in bounds.split(','))
                    data.setdefault(ch, {})['' if p == '-' else p] = Box(
                        comp, int(x0) / 1000, int(y0) / 1000, int(x1) / 1000, int(y1) / 1000, int(cut) / 1000,
                        amb == '1', walls, bounds)
        return Reference(data)

    def get(self, ch: str, path: str = '') -> Optional[Box]:
        return self.data.get(ch, {}).get(path)

    def __contains__(self, ch) -> bool:
        return ch in self.data

    def __len__(self) -> int:
        return len(self.data)
