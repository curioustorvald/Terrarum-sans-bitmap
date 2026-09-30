"""
Layout geometry: turns a component's decomposition into pixel boxes.

A *slot* is a component placed into a box of a given size. It is identified by
a SlotKey (component, width, height, role). The layout of a slot depends on
nothing but its key, so every occurrence of e.g. 青 in an 11x15 box is laid
out identically, wherever it appears. This is what makes glyphlettes reusable.

Invariant: the body box is odd-sized (15x15) and every split produces odd-sized
parts (the separating gap is 1px), so every box has a centre column and a centre
row, and symmetric components (木 米 小 ...) can be drawn symmetrically.

Roles
  ''     plain slot: the component occupies the box
  op     surround frame: the outer component of a surround operator ⿴⿵⿶⿷⿸⿹⿺⿼⿽,
         drawn over the whole box with the inner area left empty. A frame is a
         different drawing from the same component used plainly (广 vs 广 as ⿸ frame).
"""

import math
import os
import unicodedata
from dataclasses import dataclass
from typing import Dict, List, NamedTuple, Optional, Tuple

import numpy as np

import ids as IDS
from model import HanModel

HERE = os.path.dirname(os.path.abspath(__file__))
LAYOUT_PATH = os.path.join(HERE, 'model', 'layout.tsv')          # hand settings
INFERRED_PATH = os.path.join(HERE, 'model', 'inferred.tsv')      # from the reference font (infer.py)

# ---------------------------------------------------------------------------
# Global parameters

CELL_W = 16
CELL_H = 16
# The body box inside a 16x16 sheet cell. Column 15 and row 0 are left empty as
# inter-character spacing; with the engine's 2px vertical offset the body spans
# rows 3..17 of the 20px line, which matches the Hangul syllables.
BODY_X, BODY_Y, BODY_W, BODY_H = 0, 1, 15, 15

# A size may be derived from a drawing of its family up to this many pixels smaller on
# each axis (it is stretched; see resize.py). Larger drawings serve any smaller size.
SIZE_TOLERANCE = 2

GAP = 1           # space between the parts of a split
MIN_PART = 3      # no part of a split may be thinner than this
MIN_INNER = 3     # minimum inner box of a surround
REF = 15          # preferences in layout.tsv are expressed for a 15px box
# Split proportions measured in Chiron Hei HK (Layout.reference_split): a measurement's
# weight falls with the difference of log aspect ratios over this width, and fewer than
# REF_MIN_WEIGHT measurements' worth leaves the split to the parts' demand
REF_ASPECT_WIDTH = 0.3
REF_MIN_WEIGHT = 0.5
# A stacked part reaches up into the part above it where the reference has its ink reach this
# far (px of the body) or further into the ink above: under a roof (宀 冖 穴), between its legs.
# Its box then starts 2px higher (4px from 3px), overlapping the box above.
OVERLAP_MIN = 1.0
# ... and under a roof ('roof' in layout.tsv: 宀 冖, and tops ending in one), whose legs stand at
# the sides with room between them, where it reaches half a pixel: the part's first row then
# shares a row with the legs' tips, as in the reference
ROOF_OVERLAP_MIN = 0.5
# Ink sizes measured in Chiron Hei HK (Layout.ink_size): measurements in boxes of other
# sizes count too, with a weight falling with the difference of the boxes over this many px
REF_SIZE_WIDTH = 1.0

# Components simpler than this are never split: assembling 月 from a frame and 二,
# or 仁 from 亻 and 二, gains nothing over drawing them whole. (Radicals get no special
# treatment: they are not indivisible units of drawing, 言 is just lines.)
MIN_SPLIT_STROKES = 5
# Unencoded shapes ({n}) are often just fragments of one character ('bottom of 年').
# Splitting a character into such a fragment only pays off if the fragment is shared.
MIN_FRAGMENT_USERS = 3
# How much more crowded (see Layout.crowding) the single free part of a split may
# be than its parent (parts with a size in layout.tsv are not free)
CROWDING_TOLERANCE = 1.25
# How crowded a part may be when it shares a split with other free parts. This is
# a fixed limit, not relative to the parent: crowded material has to stay in one
# piece, because drawn whole the artist can merge gaps and share strokes between
# its parts, while split every part gets a rigid box and a 1px gap. (A relative
# limit loosens as boxes get tighter: it cut 䜌 in 灣 into 3px slivers while
# keeping it whole in 彎 and 變.)
MAX_CROWDING = 1.25

# Positional forms of the same component, for twin detection (糹…糸 in 䜌 is a
# twin pair just like 木…木 in 𣡾). Each form maps to its base component.
VARIANT_FORMS = {
    '糹': '糸', '纟': '糸', '訁': '言', '讠': '言', '釒': '金', '钅': '金',
    '飠': '食', '饣': '食', '牜': '牛', '𤣩': '王', '亻': '人', '扌': '手',
    '氵': '水', '忄': '心', '犭': '犬', '礻': '示', '衤': '衣', '⺼': '月',
    '𧾷': '足', '𥫗': '竹', '艹': '艸',
}


def base_form(comp: str) -> str:
    return VARIANT_FORMS.get(comp, comp)

# contexts of the layout.tsv 'place' column
PLACE_CONTEXTS = ('left', 'right', 'middle', 'top', 'bottom', 'inner', 'whole', 'any')

# layout.tsv preference names of the first and last part of each split
SIDES = {'⿰': ('left', 'right'), '⿲': ('left', 'right'), '⿱': ('top', 'bottom'), '⿳': ('top', 'bottom')}

# --- Size estimation ----------------------------------------------------------
# A component's minimum size is estimated by counting its parallel stroke *levels*
# along each axis: horizontal strokes stacked above each other need a row each and
# a gap between them, vertical strokes a column each, while strokes at right angles
# can touch. The minimum extent along an axis is therefore 2 * levels - 1 pixels.
# 言 = 亠 + 二 + 口 is five and a half rows of horizontals but only two columns: it
# needs 10 rows, yet fits in 3 columns.

# levels (horizontal, vertical) of single strokes, by CJK stroke code
_HORIZONTAL, _VERTICAL, _SLANT, _TURN = (1, 0), (0, 1), (0.5, 0.5), (1, 1)
_STROKE_CODE_LEVELS = {'H': _HORIZONTAL, 'T': _HORIZONTAL, 'S': _VERTICAL, 'SG': _VERTICAL,
                       'D': _SLANT, 'P': _SLANT, 'N': _SLANT, 'SP': _SLANT, 'PD': _SLANT,
                       'TN': _SLANT, 'XG': _SLANT, 'WG': _SLANT, 'PG': _SLANT}
# single-stroke unified ideographs and radicals, by their stroke code
_STROKE_CHARS = {'一': 'H', '丨': 'S', '亅': 'SG', '丶': 'D', '丿': 'P', '乀': 'N', '乁': 'N',
                 '𡿨': 'PD', '乙': 'HZWG', '乚': 'SWG', '乛': 'HZ', '𠃊': 'SZ', '𠃌': 'HZG',
                 '𠃋': 'PZ', '𠃍': 'HZ', '⺄': 'HZWG', '𠄌': 'SWZ'}


_STROKE_MIN_BOX = {_HORIZONTAL: (3, 1), _VERTICAL: (1, 3), _SLANT: (2, 2), _TURN: (3, 3)}


def stroke_code(comp: str) -> Optional[str]:
    code = _STROKE_CHARS.get(comp)
    if code is None and len(comp) == 1 and 0x31C0 <= ord(comp) <= 0x31EF:
        try:
            code = unicodedata.name(comp).rsplit(' ', 1)[-1]   # 'CJK STROKE HZG'
        except ValueError:
            code = None
    return code


def stroke_levels(comp: str) -> Tuple[float, float]:
    return _STROKE_CODE_LEVELS.get(stroke_code(comp), _TURN)


# --- Joints --------------------------------------------------------------------
# Rule of thumb for where assembled parts meet: horizontals tend to stay separate,
# verticals tend to join. A stacked (⿱ ⿳) joint where a vertical stroke reaches the
# boundary from either side is *joined* (糸: the hook of 小 meets 幺), and the artist
# bridges the gap with protruding pixels; one faced only by horizontals, dots and
# slants is *separate* (言: 亠 / 二 / 口). Side-by-side and surround joints are separate.
# An edge is described by the kinds of stroke reaching it: h, v or s (slant/dot).

EDGES = ('top', 'bottom', 'left', 'right')
# sides of the box a surround frame covers; on the other sides the inner part reaches the edge
FRAME_COVERS = {'⿴': 'tblr', '⿵': 'tlr', '⿶': 'blr', '⿷': 'tbl',
                '⿸': 'tl', '⿹': 'tr', '⿺': 'bl', '⿼': 'tbr', '⿽': 'br'}
_BOX_EDGES = {'top': frozenset('h'), 'bottom': frozenset('h'), 'left': frozenset('v'), 'right': frozenset('v')}


def stroke_edges(comp: str) -> Dict[str, frozenset]:
    """Edges of a single stroke, following its segments: HZ starts horizontal and ends vertical."""
    segments = []
    for letter in stroke_code(comp) or 'Z':
        if letter in 'HT':
            segments.append('h')
        elif letter == 'S':
            segments.append('v')
        elif letter in 'PDN':
            segments.append('s')
        elif letter in 'ZW':   # a turn or bend: the next segment runs the other way
            segments.append('h' if segments and segments[-1] in 'vs' else 'v')
    segments = segments or ['s']   # slanted hooks and circles (XG, Q)
    every = frozenset(segments)
    return {'top': frozenset(segments[0]), 'bottom': frozenset(segments[-1]), 'left': every, 'right': every}


def atom_levels(strokes: int) -> float:
    """Levels on each axis of a shape known only by its stroke count (口 3: 2, 木 4: 3)."""
    return math.ceil(math.sqrt(strokes + 1))


def sequence_levels(sequence: str) -> Tuple[float, float]:
    """
    Levels (rows, columns) from a CNS stroke-type sequence: horizontals are rows,
    verticals columns, turning strokes one of each, dots and slants half of each.
    Counts cannot tell how strokes are arranged (灬 is four dots in one row), so
    shapes set in layout.tsv take precedence.
    """
    n1, n2, n3, n4, n5 = (sequence.count(d) for d in '12345')
    slant = 0.5 * (n3 + n4)
    return n1 + n5 + slant, n2 + n5 + slant


# levels the walls of a surround frame add around its inner part: (horizontal, vertical)
FRAME_LEVELS = {'⿴': (2, 2), '⿵': (1, 2), '⿶': (1, 2), '⿷': (2, 1),
                '⿸': (1, 1), '⿹': (1, 1), '⿺': (1, 1), '⿼': (1, 1), '⿽': (1, 1)}


def _extent(levels: float) -> float:
    return max(1.0, 2 * levels - 1)

# Default inner-box insets (left, top, right, bottom) of surround frames at 15x15
DEFAULT_INSETS = {
    '⿴': (2, 2, 2, 2),   # 囗
    '⿵': (2, 2, 2, 0),   # 冂
    '⿶': (2, 0, 2, 2),   # 凵
    '⿷': (2, 2, 0, 2),   # 匚
    '⿸': (4, 4, 0, 0),   # 广
    '⿹': (0, 4, 4, 0),   # 勹
    '⿺': (4, 0, 0, 4),   # 辶
    '⿼': (0, 2, 4, 2),   # surround from right
    '⿽': (0, 0, 4, 4),   # surround from lower right
}


class SlotKey(NamedTuple):
    comp: str
    w: int
    h: int
    role: str = ''

    def __str__(self):
        s = f"{self.comp}@{self.w}x{self.h}"
        return s + '/' + self.role if self.role else s

    @staticmethod
    def parse(s: str) -> 'SlotKey':
        role = ''
        if '/' in s[-2:]:
            s, role = s.rsplit('/', 1)
        comp, size = s.rsplit('@', 1)
        w, h = size.split('x')
        return SlotKey(comp, int(w), int(h), role)


@dataclass
class Slot:
    key: SlotKey
    op: str                                        # operator of the split, '' for leaf-only slots
    parts: Optional[List[Tuple[SlotKey, int, int]]]  # (child key, dx, dy); None = must be drawn whole
    why_whole: str = ''                            # reason a slot cannot be split
    # rows each stacked part reaches up into the part above it (under a roof: 宀 over 由), one
    # per joint; the boxes then overlap by that less the gap. None: no part does.
    overlaps: Optional[List[int]] = None


@dataclass
class ComponentRule:
    left: Optional[float] = None
    right: Optional[float] = None
    top: Optional[float] = None
    bottom: Optional[float] = None
    insets: Dict[str, Tuple[int, int, int, int]] = None
    levels: Optional[Tuple[float, float]] = None
    place: Dict[str, Dict[str, float]] = None   # context -> {'x': fraction, 'y': fraction}
    minimum: Dict[str, float] = None            # side -> the least size there ('>=7' in layout.tsv)
    roof: bool = False                          # a roof over the part below ('roof' in the frame column)


def read_layout_rules(path=LAYOUT_PATH) -> Dict[str, ComponentRule]:
    """
    layout.tsv columns: component, left, right, top, bottom, frame, levels, place, note
      left/right  preferred width (px, in a 15px-wide box) as the left/right part of ⿰ ⿲
      top/bottom  preferred height (px, in a 15px-tall box) as the top/bottom part of ⿱ ⿳
      frame       inner-box insets when used as a surround frame, e.g. '⿸4,4,0,0'
                  (left,top,right,bottom at 15x15; several separated by spaces)
      levels      shape as 'rows,columns' of parallel stroke levels (see Layout.levels)
      place       preferred position in a context for the evenness pass, e.g. 'left:y=0.45'
      '-' or empty means no preference.
    """
    rules: Dict[str, ComponentRule] = {}
    if not os.path.exists(path):
        return rules
    with open(path, encoding='utf-8') as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            cols = (line.split('\t') + [''] * 9)[:9]
            comp = cols[0].strip()
            if comp in rules:
                raise ValueError(f"{path}:{lineno}: {comp} has a row already (one row per component)")

            def num(s):
                s = s.strip()
                return float(s) if s and s != '-' and not s.startswith('>=') else None

            minimum = {side: float(v.strip()[2:]) for side, v in zip(('left', 'right', 'top', 'bottom'), cols[1:5])
                       if v.strip().startswith('>=')}

            insets = {}
            roof = False
            for spec in cols[5].split():
                if spec == '-':
                    continue
                if spec == 'roof':
                    roof = True
                    continue
                op, nums = spec[0], spec[1:]
                if op not in IDS.SURROUND:
                    raise ValueError(f"{path}:{lineno}: '{op}' is not a surround operator")
                insets[op] = tuple(int(n) for n in nums.split(','))
                if len(insets[op]) != 4:
                    raise ValueError(f"{path}:{lineno}: frame insets need 4 numbers")
            levels = None
            if cols[6].strip() not in ('', '-'):
                levels = tuple(float(n) for n in cols[6].split(','))
                if len(levels) != 2:
                    raise ValueError(f"{path}:{lineno}: levels need 2 numbers (rows,columns)")
            place = {}
            for spec in cols[7].split():
                if spec == '-':
                    continue
                context, _, coords = spec.partition(':')
                if context not in PLACE_CONTEXTS or not coords:
                    raise ValueError(f"{path}:{lineno}: bad place '{spec}' (contexts: {' '.join(PLACE_CONTEXTS)})")
                place[context] = {}
                for c in coords.split(','):
                    axis, _, v = c.partition('=')
                    if axis not in ('x', 'y') or not 0 <= float(v) <= 1:
                        raise ValueError(f"{path}:{lineno}: bad place '{spec}' (x=/y= fractions 0..1)")
                    place[context][axis] = float(v)
            rules[comp] = ComponentRule(num(cols[1]), num(cols[2]), num(cols[3]), num(cols[4]), insets, levels,
                                        place, minimum, roof)
    return rules


def merge_rules(inferred: Dict[str, ComponentRule], hand: Dict[str, ComponentRule]) -> Dict[str, ComponentRule]:
    """The rules in effect: inferred ones, each value overridden by a hand setting."""
    out = dict(inferred)
    for comp, b in hand.items():
        a = inferred.get(comp)
        if a is None:
            out[comp] = b
            continue
        place = {c: dict(v) for c, v in (a.place or {}).items()}
        for c, v in (b.place or {}).items():
            place.setdefault(c, {}).update(v)
        out[comp] = ComponentRule(
            *(getattr(b, f) if getattr(b, f) is not None else getattr(a, f) for f in ('left', 'right', 'top', 'bottom')),
            insets={**(a.insets or {}), **(b.insets or {})},
            levels=b.levels if b.levels is not None else a.levels,
            place=place, minimum=b.minimum, roof=b.roof)
    return out


def _weighted_median(values: np.ndarray, weights: np.ndarray) -> float:
    order = np.argsort(values, kind='stable')
    acc = np.cumsum(weights[order])
    return float(values[order][np.searchsorted(acc, acc[-1] / 2)])


def _child_spaces(op: str, space, kids, overlap_min=lambda comp: OVERLAP_MIN) -> List[Tuple[float, float, float, float]]:
    """
    The space Chiron Hei HK gives each part of a node (in face units), from the space of
    the node and the parts' measured ink: a split divides it at the middle of the gaps
    between the parts' ink (where a stacked part reaches up under the one above it by
    OVERLAP_MIN or more, their spaces overlap instead, each running to its own ink); a frame has the node's space, and its inside runs to the middle
    of the gap to each wall (where the wall's ink comes closest to the inside), a pixel
    clear of the wall at least, and to the node's space on open sides.
    """
    x0, y0, x1, y1 = space
    if op in IDS.SPLIT_H or op in IDS.SPLIT_V:
        horizontal = op in IDS.SPLIT_H
        ends = [(b.x0, b.x1) if horizontal else (b.y0, b.y1) for b in kids]
        starts, stops = [x0 if horizontal else y0], []
        for upper, a, b in zip(kids, ends, ends[1:]):
            if not horizontal and (a[1] - b[0]) * BODY_H >= overlap_min(upper.comp):
                # the lower part reaches up under the upper one (宀 over 由): their spaces
                # overlap, each to its own ink, as their boxes do (Layout._split_slot)
                stops.append(a[1])
                starts.append(b[0])
            else:
                stops.append((a[1] + b[0]) / 2)
                starts.append((a[1] + b[0]) / 2)
        stops.append(x1 if horizontal else y1)
        return [(a, y0, b, y1) if horizontal else (x0, a, x1, b) for a, b in zip(starts, stops)]
    if op in IDS.SURROUND and len(kids) == 2:
        frame, inner = kids
        walls = FRAME_COVERS.get(op, '')
        # where the frame's ink comes closest to the inside, as measured (reference._walls),
        # else a stroke in from the frame's ink box
        near = getattr(frame, 'walls', None) or (None,) * 4
        stroke = 1 / BODY_W
        wl = near[0] if near[0] is not None else frame.x0 + stroke
        wt = near[1] if near[1] is not None else frame.y0 + stroke
        wr = near[2] if near[2] is not None else frame.x1 - stroke
        wb = near[3] if near[3] is not None else frame.y1 - stroke
        # the middle of the gap, and a pixel clear of the wall's ink at least, as the parts
        # of a split are a pixel apart (床: 木 nests under 广's 丿, all but touching it)
        clear = GAP / BODY_W
        return [space, (
            max((inner.x0 + wl) / 2, wl + clear) if 'l' in walls else x0,
            max((inner.y0 + wt) / 2, wt + clear) if 't' in walls else y0,
            min((inner.x1 + wr) / 2, wr - clear) if 'r' in walls else x1,
            min((inner.y1 + wb) / 2, wb - clear) if 'b' in walls else y1)]
    return [space] * len(kids)


class Layout:
    """Computes (and memoises) the slot DAG for a model."""

    def __init__(self, model: HanModel, rules: Dict[str, ComponentRule] = None,
                 inferred: Dict[str, ComponentRule] = None):
        self.model = model
        # hand settings (layout.tsv) override the preferences inferred from the reference
        # font (inferred.tsv) value by value
        self.hand = rules if rules is not None else read_layout_rules()
        self.inferred = inferred if inferred is not None else read_layout_rules(INFERRED_PATH)
        self.rules = merge_rules(self.inferred, self.hand)
        self.slots: Dict[SlotKey, Slot] = {}
        self._decomp_cache: Dict[str, IDS.Node] = {}
        self._levels: Dict[str, Tuple[float, float]] = {}
        self._edges: Dict[str, Dict[str, frozenset]] = {}
        self._reference = None
        self._ref_splits = None
        self._ref_sizes = None
        self._ink_sizes: Dict[Tuple[str, int, int], Optional[Tuple[float, float]]] = {}

    @property
    def reference(self):
        """The parts of every character as Chiron Hei HK lays them out (reference.py)."""
        if self._reference is None:
            import reference as RF
            self._reference = RF.Reference.load()
        return self._reference

    # -- decomposition lookup ------------------------------------------------

    def decomposition(self, comp: str) -> IDS.Node:
        """The decomposition node of a component name or anonymous IDS expression."""
        c = self.model.get(comp)
        if c is not None:
            return c.decomp
        if comp[0] in IDS.IDC_ALL:
            node = self._decomp_cache.get(comp)
            if node is None:
                node = self._decomp_cache[comp] = IDS.parse(comp)
            return node
        return comp

    def strokes(self, comp: str) -> int:
        c = self.model.get(comp)
        if c is not None:
            return c.strokes
        return self.model.strokes(self.decomposition(comp))

    def is_stroke(self, comp: str) -> bool:
        return self.strokes(comp) <= 1

    # -- the slot DAG --------------------------------------------------------

    def root(self, cp: int) -> SlotKey:
        return SlotKey(chr(cp), BODY_W, BODY_H)

    def slot(self, key: SlotKey) -> Slot:
        s = self.slots.get(key)
        if s is None:
            s = self.slots[key] = self._make_slot(key)
        return s

    def crowding(self, comp: str, w: int, h: int) -> float:
        """How much a component must be compressed to fit a box; <= 1 means it fits."""
        dw, dh = self.demand(comp)
        return max(dw / w, dh / h)

    def _make_slot(self, key: SlotKey) -> Slot:
        s = self._checked_slot(key)
        if s.parts:
            # Consistency: a component is only split in a box if it is also split in
            # every roomier box. Checking one step up in each direction is enough, as
            # those slots are subject to the same rule.
            for w, h in ((key.w + 2, key.h), (key.w, key.h + 2)):
                if w > BODY_W or h > BODY_H:
                    continue
                roomier = self.slot(SlotKey(key.comp, w, h))
                if roomier.parts is None:
                    why = roomier.why_whole
                    if not why.startswith('whole at '):
                        why = f"whole at {w}x{h}: {why}"
                    return Slot(key, s.op, None, why)
        return s

    def _checked_slot(self, key: SlotKey) -> Slot:
        s = self._split_slot(key)
        if s.parts:
            crowded = self._crowded(key, s.op, [c for c, _, _ in s.parts])
            if crowded is not None:
                dw, dh = self.demand(crowded.comp)
                return Slot(key, s.op, None, f"{crowded} would be too crowded (wants {dw:g}x{dh:g})")
        return s

    def _crowded(self, key: SlotKey, op: str, parts: List[SlotKey]) -> Optional[SlotKey]:
        """
        The part of a split that would be too crowded, if any. A part may not be squeezed
        harder than the whole it belongs to. Dense characters are compressed anyway at this
        size; what must not happen is a split that starves one part to make room for another.
        A single free part (the rest has sizes set by hand in layout.tsv, e.g. 氵 + 彎) stays
        one piece and may inherit the crowding of the whole, within tolerance.
        """
        limit = max(1.0, self.crowding(key.comp, key.w, key.h)) * CROWDING_TOLERANCE
        sides = SIDES.get(op, ())
        free = []
        for i, child in enumerate(parts):
            if child.role:
                continue
            # a size set by hand in layout.tsv is legible by declaration (one measured in
            # the reference font says nothing about legibility at 15px)
            side = sides[0] if i == 0 and sides else sides[1] if i == len(parts) - 1 and sides else None
            if side and self.preference(child.comp, side, hand=True) is not None:
                continue
            free.append(child)
        if len(free) > 1:
            # parts sharing the split get a fixed limit, so a tighter box never
            # allows a split that a roomier box of the same component refuses
            limit = MAX_CROWDING
        for child in free:
            if self.crowding(child.comp, child.w, child.h) > limit:
                return child
        return None

    def never_split(self, comp_name: str) -> Optional[str]:
        """Why a component is never split, whatever its box (None if it may be)."""
        comp = self.model.get(comp_name)
        if comp is not None and 'ivi' in comp.flags:
            return 'approximate IDS'
        node = self.decomposition(comp_name)
        if isinstance(node, str):
            return 'atom'
        op = node[0]
        if op in IDS.UNLAYOUTABLE:
            return f"{op} cannot be laid out"
        if self.strokes(comp_name) < MIN_SPLIT_STROKES:
            return 'too simple to split'
        children = [IDS.to_string(c) for c in node[1:]]
        if any(self.is_stroke(c) for c in children):
            return 'bare stroke part'
        for c in children:
            if c.startswith('{') and self.model.parents.get(c, 0) < MIN_FRAGMENT_USERS:
                return f"{c} is a fragment used by only {self.model.parents.get(c, 0)}"
        return None

    def _split_slot(self, key: SlotKey) -> Slot:
        if key.role:
            return Slot(key, '', None, 'frame')
        why = self.never_split(key.comp)
        node = self.decomposition(key.comp)
        op = '' if isinstance(node, str) else node[0]
        if why is not None:
            return Slot(key, op if why not in ('approximate IDS', 'atom') else '', None, why)
        children = [IDS.to_string(c) for c in node[1:]]

        if op in IDS.SPLIT_H or op in IDS.SPLIT_V:
            horizontal = op in IDS.SPLIT_H
            extent = key.w if horizontal else key.h
            def fits(sizes):
                parts = [SlotKey(c, sz, key.h) if horizontal else SlotKey(c, key.w, sz)
                         for c, sz in zip(children, sizes)]
                return self._crowded(key, op, parts) is None

            sizes = self.split(children, extent, horizontal, key.comp, key.w / key.h,
                               whole=key.w == BODY_W and key.h == BODY_H, fits=fits)
            if sizes is None:
                return Slot(key, op, None, 'too small to split')
            # a stacked part reaches up into the part above where the reference has it do so
            # (under a roof: 由 between the legs of 宀), by an even number of rows so its box
            # stays odd; the part above keeps a row of its own at least
            ups = [0] * (len(children) - 1)
            if not horizontal:
                over = self.reference_overlaps(key.comp, key.w / key.h, whole=key.w == BODY_W and key.h == BODY_H)
                for i, o in enumerate(over or ()):
                    px = o * extent
                    if px >= self.overlap_min(children[i]):
                        ups[i] = min(2 * max(1, int(px / 2 + 0.5)), (sizes[i] - 1) // 2 * 2)
            parts = []
            pos = 0
            for i, (c, size) in enumerate(zip(children, sizes)):
                up = ups[i - 1] if i else 0
                if horizontal:
                    parts.append((SlotKey(c, size, key.h), pos, 0))
                else:
                    parts.append((SlotKey(c, key.w, size + up), 0, pos - up))
                pos += size + GAP
            return Slot(key, op, parts, overlaps=ups if any(ups) else None)

        if op in IDS.SURROUND:
            outer, inner = children
            l, t, r, b = self.insets(outer, op, key.w, key.h)
            iw, ih = key.w - l - r, key.h - t - b
            if iw < MIN_INNER or ih < MIN_INNER:
                return Slot(key, op, None, 'too small to split')
            return Slot(key, op, [
                (SlotKey(outer, key.w, key.h, op), 0, 0),
                (SlotKey(inner, iw, ih), l, t),
            ])

        return Slot(key, op, None, f"unknown operator {op}")

    # -- splitting -------------------------------------------------------------

    def preference(self, comp: str, side: str, hand: bool = False) -> Optional[float]:
        """A component's size as the first or last part of a split, in px of a 15px box: set
        by hand in layout.tsv, or (unless `hand`) inferred from the reference font."""
        rule = (self.hand if hand else self.rules).get(comp)
        return getattr(rule, side) if rule else None

    def reference_split(self, comp: str, horizontal: bool, aspect: float, whole: bool = False) -> Optional[List[float]]:
        """
        Where Chiron Hei HK divides a component along an axis (reference.py), as shares of
        its extent: the middle of the space between each pair of parts.
          - A whole character (`whole`) is divided as Chiron Hei HK divides that very
            character.
          - A component inside others is measured wherever it appears. A measurement counts
            more the closer its shape is to `aspect` (width / height), since a squeezed 昌
            divides differently from a standalone one, and the standalone form counts
            triple. The weighted median resists the odd bad cut.
        """
        if self._ref_splits is None:
            self._ref_splits = self._index_reference_splits()
        entries = self._ref_splits.get((comp, horizontal))
        if not entries:
            return None
        if whole:
            own = [shares for _, shares, _, alone, _ in entries if alone]
            return own[0] if own else None
        la = math.log(aspect)
        weighted = []
        for ref_aspect, shares, quality, alone, _ in entries:
            w = quality * (3.0 if alone else 1.0) * math.exp(-((math.log(ref_aspect) - la) / REF_ASPECT_WIDTH) ** 2)
            if w > 0:
                weighted.append((w, shares))
        total = sum(w for w, _ in weighted)
        if total < REF_MIN_WEIGHT:
            return None
        out = []
        for k in range(len(weighted[0][1])):
            vals = sorted((sh[k], w) for w, sh in weighted)
            acc = 0.0
            for v, w in vals:
                acc += w
                if acc >= total / 2:
                    out.append(v)
                    break
        return out if out == sorted(out) else None

    def is_roof(self, comp: str) -> bool:
        """Whether a component is a roof over the part below it: set so in layout.tsv (宀 冖), or
        a stack ending in one (the ⺍冖 over 学's 子)."""
        rule = self.hand.get(comp)
        if rule and rule.roof:
            return True
        node = self.decomposition(comp)
        return not isinstance(node, str) and node[0] in IDS.SPLIT_V and node[-1] != comp and \
            self.is_roof(IDS.to_string(node[-1]))

    def overlap_min(self, comp: str) -> float:
        """How far (px) a part must reach up under `comp` in the reference to reach up under it here."""
        return ROOF_OVERLAP_MIN if self.is_roof(comp) else OVERLAP_MIN

    def reference_overlaps(self, comp: str, aspect: float, whole: bool = False) -> Optional[List[float]]:
        """
        How far each part of a ⿱⿳ reaches up into the part above it in Chiron Hei HK, as shares
        of the height (negative: a gap between them), measured as reference_split measures
        the splits: a whole character by itself, a component by the weighted median wherever
        it appears. 宙: 由 reaches 1.7px up between the legs of 宀.
        """
        if self._ref_splits is None:
            self._ref_splits = self._index_reference_splits()
        entries = self._ref_splits.get((comp, False))
        if not entries:
            return None
        if whole:
            own = [over for _, _, _, alone, over in entries if alone]
            return own[0] if own else None
        la = math.log(aspect)
        weighted = [(quality * (3.0 if alone else 1.0) * math.exp(-((math.log(a) - la) / REF_ASPECT_WIDTH) ** 2), over)
                    for a, _, quality, alone, over in entries]
        weighted = [(w, o) for w, o in weighted if w > 0]
        if sum(w for w, _ in weighted) < REF_MIN_WEIGHT:
            return None
        wts = np.array([w for w, _ in weighted])
        return [_weighted_median(np.array([o[k] for _, o in weighted]), wts) for k in range(len(weighted[0][1]))]

    def _index_reference_splits(self):
        index: Dict[Tuple[str, bool], List[Tuple[float, List[float], float]]] = {}
        for ch, nodes in self.reference.data.items():
            for path, box in nodes.items():
                node = self.decomposition(box.comp)
                if isinstance(node, str) or not (node[0] in IDS.SPLIT_H or node[0] in IDS.SPLIT_V):
                    continue
                horizontal = node[0] in IDS.SPLIT_H
                kids = [nodes.get(f"{path}.{k}" if path else str(k)) for k in range(len(node) - 1)]
                if any(k is None for k in kids) or box.w <= 0 or box.h <= 0:
                    continue
                lo, size = (box.x0, box.w) if horizontal else (box.y0, box.h)
                ends = [(k.x0, k.x1) if horizontal else (k.y0, k.y1) for k in kids]
                # the middle of the gap between the parts; where the lower part reaches up under
                # the upper one (宀 over 由), the end of the upper part, which keeps its legs (the
                # lower part's box reaches up past it: reference_overlaps)
                # (the overlap is measured as a share of the node, as _split_slot applies it)
                shares = [((a[1] if not horizontal and (a[1] - b[0]) / size * REF >= self.overlap_min(k.comp)
                            else (a[1] + b[0]) / 2) - lo) / size for k, a, b in zip(kids, ends, ends[1:])]
                if any(not 0 < v < 1 for v in shares) or shares != sorted(shares):
                    continue
                quality = (0.5 if kids[0].ambiguous else 1.0) * max(0.0, 1 - 2 * kids[0].cut)
                alone = path == '' and ch == box.comp
                # how far each part's ink reaches into the ink before it, as a share of the extent
                over = [(a[1] - b[0]) / size for a, b in zip(ends, ends[1:])]
                index.setdefault((box.comp, horizontal), []).append((box.w / box.h, shares, quality, alone, over))
        return index

    def ink_size(self, comp: str, w: int, h: int) -> Optional[Tuple[float, float]]:
        """
        How large Chiron Hei HK draws a component placed in a w x h box, in pixels: its
        ink as a share of the space Chiron Hei HK gives it (the weighted median over every
        character where the layout gives it this box), times the box. The space of a part
        runs to the middle of the gaps between it and its neighbours, so a part touching
        its neighbour fills its box towards it (早's 十 reaches 日), and a floating one keeps
        its margins (口 left of 吃 叫 唱 is about 4x11 in 5x15). Boxes of other sizes count
        less the further they are (REF_SIZE_WIDTH), so a rare box borrows from its
        neighbours; a measurement counts less the more ink its cut went through, and half if
        the cut was ambiguous (reference.py). None without enough measurements.
        """
        k = (comp, w, h)
        if k in self._ink_sizes:
            return self._ink_sizes[k]
        if self._ref_sizes is None:
            self._ref_sizes = self._index_ink_sizes()
        out = None
        entries = self._ref_sizes.get(comp)
        if entries is not None:
            bw, bh, fx, fy, q = entries
            wt = q * np.exp(-((bw - w) ** 2 + (bh - h) ** 2) / (2 * REF_SIZE_WIDTH ** 2))
            if wt.sum() >= REF_MIN_WEIGHT:
                out = (_weighted_median(fx, wt) * w, _weighted_median(fy, wt) * h)
        self._ink_sizes[k] = out
        return out

    def _index_ink_sizes(self) -> Dict[str, Tuple[np.ndarray, ...]]:
        """Per component: the boxes it is laid out in, and the share of its space its ink
        takes there in Chiron Hei HK, across and down."""
        rows: Dict[str, List[Tuple[int, int, float, float, float]]] = {}
        ref = self.reference

        def walk(nodes, key, path, space):
            s = self.slot(key)
            if not s.parts:
                return
            kids = [nodes.get(f"{path}.{i}" if path else str(i)) for i in range(len(s.parts))]
            if any(b is None for b in kids):
                return    # the measurement failed here, so it did below too
            for i, ((c, _, _), box, sp) in enumerate(zip(s.parts, kids, _child_spaces(s.op, space, kids, self.overlap_min))):
                sw, sh = sp[2] - sp[0], sp[3] - sp[1]
                if not c.role and box.comp == c.comp and box.w > 0 and box.h > 0 and sw > 0 and sh > 0:
                    q = (0.5 if box.ambiguous else 1.0) * max(0.0, 1 - 2 * box.cut)
                    if q > 0:
                        rows.setdefault(c.comp, []).append((c.w, c.h, box.w / sw, box.h / sh, q))
                walk(nodes, c, f"{path}.{i}" if path else str(i), sp)

        for cp in self.model.targets:
            nodes = ref.data.get(chr(cp))
            if nodes:
                walk(nodes, self.root(cp), '', (0.0, 0.0, 1.0, 1.0))
        return {comp: tuple(np.array(col, dtype=float) for col in zip(*r)) for comp, r in rows.items()}

    def split(self, children: List[str], extent: int, horizontal: bool, parent: str = None,
              aspect: float = None, whole: bool = False, fits=None, inferred: bool = True) -> Optional[List[int]]:
        """
        Divide `extent` pixels among the children (plus GAP between them).
        Returns the part sizes, or None if the parts would be thinner than MIN_PART.
        Target sizes, first found first:
          - a size set by hand in layout.tsv for the first or last part;
          - with the parent component and its box's aspect, the proportions of the parent
            as measured in the reference font (reference_split);
          - otherwise the size the reference font gives the first and last parts wherever
            they appear (inferred.tsv), the rest of the space shared by how much room
            each part wants along the axis (demand).
        Twins (林 昌) take no preference: they split evenly. A least size set by hand ('>=7' in
        layout.tsv: 川 on the right of 训 釧) rules out the partitions that go below it.
        `fits(sizes)`: whether a partition is legible; the closest one that is wins.
        Not `inferred`: without the sizes inferred from the reference (for measuring the
        reference itself, which would otherwise lean on its own earlier measurements).
        """
        n = len(children)
        avail = extent - GAP * (n - 1)
        if avail < MIN_PART * n:
            return None

        sides = ('left', 'right') if horizontal else ('top', 'bottom')
        twins = len({base_form(c) for c in children}) == 1
        ends = [] if twins else [(0, sides[0]), (n - 1, sides[1])]
        scale = extent / REF
        targets = [None] * n
        fixed = 0.0
        for i, side in ends:
            p = self.preference(children[i], side, hand=True)
            if p is not None and targets[i] is None:
                targets[i] = p * scale
                fixed += targets[i]
        free = [i for i in range(n) if targets[i] is None]
        measured = self.reference_split(parent, horizontal, aspect, whole) if parent and aspect else None
        if free and not measured and inferred:
            # the parent is not measured: its first and last parts take the size the
            # reference gives them wherever they appear
            for i, side in ends:
                p = self.preference(children[i], side)
                if p is not None and targets[i] is None:
                    targets[i] = p * scale
                    fixed += targets[i]
            free = [i for i in range(n) if targets[i] is None]
        if free:
            # the rest of the space, as the parent is measured or by demand
            if measured:
                edges = [0.0] + measured + [1.0]
                sizes = [(b - a) * extent - GAP * ((0 < i) + (i < n - 1)) / 2
                         for i, (a, b) in enumerate(zip(edges, edges[1:]))]
                weights = [sizes[i] for i in free]
            else:
                axis = 0 if horizontal else 1
                weights = [self.demand(children[i])[axis] for i in free]
            remaining = max(avail - fixed, MIN_PART * len(free))
            for i, wt in zip(free, weights):
                targets[i] = remaining * wt / sum(weights)
        elif fixed != avail:
            # every part has a preferred size: keep their ratio
            targets = [t * avail / fixed for t in targets]

        candidates = _odd_partitions(avail, n)
        least = [0] * n
        for i, side in ends:
            rule = self.hand.get(children[i])
            if rule and rule.minimum and side in rule.minimum:
                least[i] = round(rule.minimum[side] * scale)
        if any(least):
            candidates = [p for p in candidates if all(sz >= m for sz, m in zip(p, least))] or candidates
        if n == 3 and base_form(children[0]) == base_form(children[2]):
            # 木缶木, 糹言糸: the outer twins get the same size
            candidates = [p for p in candidates if p[0] == p[2]] or candidates
        # the closest partition to the targets; on ties the later part (right/bottom) is the
        # larger. The targets are where the reference puts the parts, legibility is a limit:
        # the closest partition that `fits` wins (䱂: 魚 7px beside 幼, not 5 as the
        # reference would have it, which is too narrow for its strokes)
        ranked = sorted((round(sum((s - t) ** 2 for s, t in zip(sizes, targets)), 6),
                         tuple(-s for s in reversed(sizes)), sizes) for sizes in candidates)
        if not ranked:
            return None
        if fits is not None:
            for _, _, sizes in ranked:
                if fits(sizes):
                    return list(sizes)
        return list(ranked[0][2])

    def demand(self, comp: str) -> Tuple[float, float]:
        """
        The smallest box (w, h) in which a component can be drawn legibly, from its
        stroke levels (see stroke_levels): w = 2 * vertical levels - 1, h = 2 *
        horizontal levels - 1. Splits also share their space out by this, so parts
        get room along the split axis in proportion to what they need there.
        """
        lh, lv = self.levels(comp)
        if self.strokes(comp) <= 1:
            # a lone stroke still needs its length: 一 3x1, 丨 1x3, 丶 1x1, 乙 3x3
            minw, minh = _STROKE_MIN_BOX.get(stroke_levels(comp), (3, 3))
        else:
            minw = minh = MIN_PART  # anything with two strokes needs 3px both ways (小, 亠)
        return max(_extent(lv), minw), max(_extent(lh), minh)

    def levels(self, comp: str) -> Tuple[float, float]:
        """Parallel stroke levels (horizontal, vertical) of a component, from its structure.
        This is about shape only: radicals are measured through their structure too,
        even though they are always drawn whole."""
        d = self._levels.get(comp)
        if d is not None:
            return d
        self._levels[comp] = (1, 1)  # guards against cyclic decompositions
        s = self.strokes(comp)
        node = self.decomposition(comp)
        rule = self.rules.get(comp)
        if rule is not None and rule.levels is not None:
            d = rule.levels
        elif s <= 1:
            d = stroke_levels(comp)
        elif isinstance(node, str) and node != comp:
            d = self.levels(node)        # an alias such as 訁 = 言, 𠄠 = 二
        elif isinstance(node, str):
            c = self.model.get(comp)
            if c is not None and c.sequence:
                d = sequence_levels(c.sequence)
            else:
                a = atom_levels(s)
                d = (a, a)
        elif node[0] == '㇯':
            # 'A minus B' is described through its parent (五 = ⿱一𫝀, 𫝀 = ㇯五一):
            # recursing would go round in circles, so measure it by its strokes
            a = atom_levels(s)
            d = (a, a)
        else:
            op = node[0]
            kids = [self.levels(IDS.to_string(c)) for c in node[1:]]
            if op in IDS.SPLIT_H:
                d = (max(k[0] for k in kids), sum(k[1] for k in kids))
            elif op in IDS.SPLIT_V:
                d = (sum(k[0] for k in kids), max(k[1] for k in kids))
            elif op in IDS.SURROUND:
                (oh, ov), (ih, iv) = kids
                fh, fv = FRAME_LEVELS[op]
                d = (max(oh, ih + fh), max(ov, iv + fv))
            else:
                # overlaid or transformed: the parts' strokes interleave, so their levels
                # add up (十 = 一 over 丨 is one row and one column, 由 = 日 through 丨 three
                # rows and three columns, 串 four rows and three columns). A guess from the
                # stroke count alone made 十 2x2 levels, so 田 (囗 around 十) 7x7 px, not 5x5.
                d = (sum(k[0] for k in kids), sum(k[1] for k in kids))
                if d == (0, 0):
                    a = atom_levels(s)
                    d = (a, a)
        self._levels[comp] = d
        return d

    def edges(self, comp: str) -> Dict[str, frozenset]:
        """Kinds of stroke (h, v, s) reaching each edge of a component, from its structure."""
        d = self._edges.get(comp)
        if d is not None:
            return d
        self._edges[comp] = _BOX_EDGES  # guards against cyclic decompositions
        node = self.decomposition(comp)
        if self.strokes(comp) <= 1:
            d = stroke_edges(comp)
        elif isinstance(node, str) and node != comp:
            d = self.edges(node)
        elif isinstance(node, str) or node[0] == '㇯':
            d = _BOX_EDGES
        else:
            op = node[0]
            kids = [self.edges(IDS.to_string(c)) for c in node[1:]]
            if op in IDS.SPLIT_H:
                d = {'top': frozenset().union(*(k['top'] for k in kids)),
                     'bottom': frozenset().union(*(k['bottom'] for k in kids)),
                     'left': kids[0]['left'], 'right': kids[-1]['right']}
            elif op in IDS.SPLIT_V:
                d = {'top': kids[0]['top'], 'bottom': kids[-1]['bottom'],
                     'left': frozenset().union(*(k['left'] for k in kids)),
                     'right': frozenset().union(*(k['right'] for k in kids))}
            elif op in IDS.SURROUND:
                outer, inner = kids
                covers = FRAME_COVERS[op]
                d = {e: outer[e] if e[0] in covers else outer[e] | inner[e] for e in EDGES}
            else:
                d = {e: frozenset().union(*(k[e] for k in kids)) for e in EDGES}
        self._edges[comp] = d
        return d

    def joints(self, key: SlotKey) -> List[bool]:
        """The joints between the parts of a split slot, True for joined ones (see Joints above)."""
        s = self.slot(key)
        if not s.parts:
            return []
        if s.op not in IDS.SPLIT_V:
            return [False] * (len(s.parts) - 1)
        out = []
        for i, ((a, _, _), (b, _, _)) in enumerate(zip(s.parts, s.parts[1:])):
            if s.overlaps and s.overlaps[i]:
                out.append(False)       # under a roof: the legs stand beside the part below
                continue
            out.append('v' in self.edges(a.comp)['bottom'] or 'v' in self.edges(b.comp)['top'])
        return out

    def insets(self, outer: str, op: str, w: int, h: int, inferred: bool = True) -> Tuple[int, int, int, int]:
        """The inner box of a surround frame in a w x h box, as insets (left, top, right,
        bottom): set by hand, inferred from the reference font (unless not `inferred`), or
        the operator's default; scaled from 15x15 (see _scale_pair)."""
        rule = (self.rules if inferred else self.hand).get(outer)
        base = rule.insets.get(op) if rule and rule.insets else None
        if base is None:
            base = DEFAULT_INSETS[op]
        l, t, r, b = base
        l, r = _scale_pair(l, r, w)
        t, b = _scale_pair(t, b, h)
        return l, t, r, b


def _scale_inset(v: int, extent: int) -> int:
    # Frames are made of strokes, which do not get thinner in smaller boxes: an
    # inset shrinks with the box but never below 2 (1px stroke + 1px gap).
    if v == 0 or extent >= REF:
        return v
    return max(min(v, 2), round(v * extent / REF))


def _scale_pair(a: int, b: int, extent: int) -> Tuple[int, int]:
    """Scale a pair of insets from REF to `extent`, keeping the inner span's parity odd."""
    a, b = _scale_inset(a, extent), _scale_inset(b, extent)
    if (extent - a - b) % 2 == 0:
        # widen the larger inset by one so the inner span stays odd
        if a >= b:
            a += 1
        else:
            b += 1
    return a, b


_PARTITION_CACHE: Dict[Tuple[int, int], List[Tuple[int, ...]]] = {}


def _odd_partitions(total: int, n: int) -> List[Tuple[int, ...]]:
    """
    All ways to write `total` as n parts >= MIN_PART, preferring all-odd parts.
    If no all-odd partition exists (wrong parity), partitions with exactly one
    even part are returned instead.
    """
    key = (total, n)
    if key in _PARTITION_CACHE:
        return _PARTITION_CACHE[key]

    def gen(total, n, odd_only):
        if n == 1:
            if total >= MIN_PART and (not odd_only or total % 2 == 1):
                yield (total,)
            return
        for first in range(MIN_PART, total - MIN_PART * (n - 1) + 1):
            if odd_only and first % 2 == 0:
                continue
            for rest in gen(total - first, n - 1, odd_only):
                yield (first,) + rest

    result = list(gen(total, n, True))
    if not result:
        result = [p for p in gen(total, n, False) if sum(1 for s in p if s % 2 == 0) <= 1]
    _PARTITION_CACHE[key] = result
    return result


# ---------------------------------------------------------------------------
# Walking a character's layout

def placements(layout: Layout, key: SlotKey, x: int, y: int, choose,
               unit=lambda k: False) -> Optional[List[Tuple[SlotKey, int, int]]]:
    """
    Resolve a slot into drawable leaves.
    `choose(key)` returns True if the slot should be used whole at this level;
    `unit(key)` marks slots that must never be split.
    Returns [(key, x, y)] or None if some part can be neither used whole nor split.
    """
    if choose(key):
        return [(key, x, y)]
    s = layout.slot(key)
    if s.parts is None or unit(key):
        return None
    out = []
    for child, dx, dy in s.parts:
        r = placements(layout, child, x + dx, y + dy, choose, unit)
        if r is None:
            return None
        out.extend(r)
    return out
