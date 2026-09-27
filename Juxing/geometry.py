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

import ids as IDS
from model import HanModel

HERE = os.path.dirname(os.path.abspath(__file__))
LAYOUT_PATH = os.path.join(HERE, 'model', 'layout.tsv')

# ---------------------------------------------------------------------------
# Global parameters

CELL_W = 16
CELL_H = 16
# The body box inside a 16x16 sheet cell. Column 15 and row 0 are left empty as
# inter-character spacing; with the engine's 2px vertical offset the body spans
# rows 3..17 of the 20px line, which matches the Hangul syllables.
BODY_X, BODY_Y, BODY_W, BODY_H = 0, 1, 15, 15

# A drawing may be reused for a slot of the same component up to this many pixels
# larger on each axis, centred in it (not for frames or whole characters).
SIZE_TOLERANCE = 2

GAP = 1           # space between the parts of a split
MIN_PART = 3      # no part of a split may be thinner than this
MIN_INNER = 3     # minimum inner box of a surround
REF = 15          # preferences in layout.tsv are expressed for a 15px box

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


@dataclass
class ComponentRule:
    left: Optional[int] = None
    right: Optional[int] = None
    top: Optional[int] = None
    bottom: Optional[int] = None
    insets: Dict[str, Tuple[int, int, int, int]] = None
    levels: Optional[Tuple[float, float]] = None
    place: Dict[str, Dict[str, float]] = None   # context -> {'x': fraction, 'y': fraction}


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

            def num(s):
                s = s.strip()
                return int(s) if s and s != '-' else None

            insets = {}
            for spec in cols[5].split():
                if spec == '-':
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
                                        place)
    return rules


class Layout:
    """Computes (and memoises) the slot DAG for a model."""

    def __init__(self, model: HanModel, rules: Dict[str, ComponentRule] = None):
        self.model = model
        self.rules = rules if rules is not None else read_layout_rules()
        self.slots: Dict[SlotKey, Slot] = {}
        self._decomp_cache: Dict[str, IDS.Node] = {}
        self._levels: Dict[str, Tuple[float, float]] = {}
        self._edges: Dict[str, Dict[str, frozenset]] = {}

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
            # A part may not be squeezed harder than the whole it belongs to. Dense
            # characters are compressed anyway at this size; what must not happen is
            # a split that starves one part to make room for another.
            # A single free part (the rest has sizes declared in layout.tsv, e.g. 氵 + 彎)
            # stays one piece and may inherit the crowding of the whole, within tolerance.
            limit = max(1.0, self.crowding(key.comp, key.w, key.h)) * CROWDING_TOLERANCE
            sides = SIDES.get(s.op, ())
            free = []
            for i, (child, _, _) in enumerate(s.parts):
                if child.role:
                    continue
                # a size set in layout.tsv is legible by declaration
                side = sides[0] if i == 0 and sides else sides[1] if i == len(s.parts) - 1 and sides else None
                if side and self.preference(child.comp, side) is not None:
                    continue
                free.append(child)
            if len(free) > 1:
                # parts sharing the split get a fixed limit, so a tighter box never
                # allows a split that a roomier box of the same component refuses
                limit = MAX_CROWDING
            for child in free:
                c = self.crowding(child.comp, child.w, child.h)
                if c > limit:
                    dw, dh = self.demand(child.comp)
                    return Slot(key, s.op, None, f"{child} would be too crowded (wants {dw:g}x{dh:g})")
        return s

    def _split_slot(self, key: SlotKey) -> Slot:
        if key.role:
            return Slot(key, '', None, 'frame')
        comp = self.model.get(key.comp)
        if comp is not None and 'ivi' in comp.flags:
            return Slot(key, '', None, 'approximate IDS')
        node = self.decomposition(key.comp)
        if isinstance(node, str):
            return Slot(key, '', None, 'atom')
        op = node[0]
        if op in IDS.UNLAYOUTABLE:
            return Slot(key, op, None, f"{op} cannot be laid out")
        if self.strokes(key.comp) < MIN_SPLIT_STROKES:
            return Slot(key, op, None, 'too simple to split')
        children = [IDS.to_string(c) for c in node[1:]]
        if any(self.is_stroke(c) for c in children):
            return Slot(key, op, None, 'bare stroke part')
        for c in children:
            if c.startswith('{') and self.model.parents.get(c, 0) < MIN_FRAGMENT_USERS:
                return Slot(key, op, None, f"{c} is a fragment used by only {self.model.parents.get(c, 0)}")

        if op in IDS.SPLIT_H or op in IDS.SPLIT_V:
            horizontal = op in IDS.SPLIT_H
            extent = key.w if horizontal else key.h
            sizes = self.split(children, extent, horizontal)
            if sizes is None:
                return Slot(key, op, None, 'too small to split')
            parts = []
            pos = 0
            for c, size in zip(children, sizes):
                if horizontal:
                    parts.append((SlotKey(c, size, key.h), pos, 0))
                else:
                    parts.append((SlotKey(c, key.w, size), 0, pos))
                pos += size + GAP
            return Slot(key, op, parts)

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

    def preference(self, comp: str, side: str) -> Optional[int]:
        rule = self.rules.get(comp)
        return getattr(rule, side) if rule else None

    def split(self, children: List[str], extent: int, horizontal: bool) -> Optional[List[int]]:
        """
        Divide `extent` pixels among the children (plus GAP between them).
        Returns the part sizes, or None if the parts would be thinner than MIN_PART.
        """
        n = len(children)
        avail = extent - GAP * (n - 1)
        if avail < MIN_PART * n:
            return None

        # target sizes: explicit preferences first (scaled from the 15px reference),
        # the rest of the space shared by how much room each part wants along the axis
        sides = ('left', 'right') if horizontal else ('top', 'bottom')
        prefs = [None] * n
        if len({base_form(c) for c in children}) > 1:  # twins such as 林 朋 昌 圭 絲 split evenly
            prefs[0] = self.preference(children[0], sides[0])
            prefs[-1] = self.preference(children[-1], sides[1])
        scale = extent / REF
        targets = [None] * n
        fixed = 0.0
        for i, p in enumerate(prefs):
            if p is not None:
                targets[i] = p * scale
                fixed += targets[i]
        free = [i for i in range(n) if targets[i] is None]
        if free:
            axis = 0 if horizontal else 1
            weights = [self.demand(children[i])[axis] for i in free]
            remaining = max(avail - fixed, MIN_PART * len(free))
            for i, wt in zip(free, weights):
                targets[i] = remaining * wt / sum(weights)
        elif fixed != avail:
            # every part has a preference: keep their ratio
            targets = [t * avail / fixed for t in targets]

        candidates = _odd_partitions(avail, n)
        if n == 3 and base_form(children[0]) == base_form(children[2]):
            # 木缶木, 糹言糸: the outer twins get the same size
            candidates = [p for p in candidates if p[0] == p[2]] or candidates
        best = None
        for sizes in candidates:
            err = sum((s - t) ** 2 for s, t in zip(sizes, targets))
            # on ties prefer the later part (right/bottom) to be the larger
            tie = tuple(-s for s in reversed(sizes))
            cand = (round(err, 6), tie, sizes)
            if best is None or cand < best:
                best = cand
        return list(best[2]) if best else None

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
                # overlaid or transformed: the parts share the space, which must still
                # hold the whole character's strokes
                a = atom_levels(s)
                d = (max(max(k[0] for k in kids), a), max(max(k[1] for k in kids), a))
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
        for (a, _, _), (b, _, _) in zip(s.parts, s.parts[1:]):
            out.append('v' in self.edges(a.comp)['bottom'] or 'v' in self.edges(b.comp)['top'])
        return out

    def insets(self, outer: str, op: str, w: int, h: int) -> Tuple[int, int, int, int]:
        rule = self.rules.get(outer)
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
