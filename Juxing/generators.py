"""
Procedural glyphlettes for simple rectilinear components (口 日 目 白 工 木 土 ...).

These components are a few straight 1px strokes, so every size the layout asks for
can be generated instead of drawn. Generated glyphlettes cost nothing to plan and
never appear on the worklist, but a hand drawing of the same slot always wins: to
restyle one, issue it with `juxing.py issue --key 口@5x15`, which prefills its cell
with the generated ink for you to edit.

Rules:
  - Whole characters (15x15 plain slots) are never generated; standalone 口 or 木
    deserve hand drawing.
  - A shape keeps a plausible aspect ratio: in a 5x15 box 口 is 5x7 and 日 5x13,
    centred, rather than stretched to fill the box; enclosed shapes also keep clear
    of the sides of wide boxes (口 on top of 呆 is 11px wide, not 15).
  - The outer verticals of box-like shapes (口 囗 日 曰 目 田 白 山 中 由 甲 申 凵 匚)
    run past the bottom stroke: 1px, or 2px when the shape is extra tall. This is
    how these characters are made, not a serif of the typeface, but it disappears
    when a shape is squashed vertically to about 60% of its natural proportion.
  - If the box cannot hold the strokes with 1px gaps between parallel ones
    (目 needs 7 rows), the serif goes first, then the generator declines and the
    slot must be drawn.
"""

from typing import Callable, Dict, Optional, Tuple

import numpy as np

import geometry as GEO
from geometry import SlotKey

# (array (h, w), w, h, serif length) -> False if the shape does not fit
Shape = Callable[[np.ndarray, int, int, int], bool]

SERIF_TALL = 13       # shapes at least this tall get 2px serifs
SERIF_SQUASH = 0.6    # no serifs at or below this fraction of the natural height


class Spec:
    def __init__(self, draw: Shape, max_wide: float, max_tall: float, min_w: int = 3, min_h: int = 3,
                 boxy: bool = False, natural: Optional[float] = None):
        self.draw = draw
        self.max_wide = max_wide    # width may be at most this many times the height
        self.max_tall = max_tall    # height may be at most this many times the width
        self.min_w, self.min_h = min_w, min_h
        # enclosed shapes (口 日 田 ...) keep clear of the sides of wide boxes, while
        # stroke-like ones (一 十 土 木 ...) may span them
        self.boxy = boxy
        # natural height / width of shapes with bottom serifs; None = no serifs
        self.natural = natural


def _side_margin(w: int) -> int:
    return 2 if w >= 13 else 1 if w >= 9 else 0


def _odd_at_most(v: float, limit: int) -> int:
    n = min(int(v), limit)
    return n if n % 2 == 1 else n - 1


def _fit(spec: Spec, w: int, h: int) -> Optional[Tuple[int, int, int, int]]:
    """The rectangle (x, y, w, h) the shape occupies inside a w x h box: centred, odd-sized."""
    fw = _odd_at_most(spec.max_wide * h, w - 2 * _side_margin(w) if spec.boxy else w)
    fh = _odd_at_most(spec.max_tall * fw, h)
    fw = _odd_at_most(spec.max_wide * fh, fw)
    if fw < spec.min_w or fh < spec.min_h:
        return None
    return (w - fw) // 2, (h - fh) // 2, fw, fh


def serif_length(natural: Optional[float], w: int, h: int) -> int:
    if natural is None or h / (w * natural) <= SERIF_SQUASH:
        return 0
    return 2 if h >= SERIF_TALL else 1


# --- stroke helpers (operate on a (h, w) boolean array) ---------------------------

def _h(a, y, x0=0, x1=None):
    a[y, x0:(a.shape[1] if x1 is None else x1 + 1)] = True


def _v(a, x, y0=0, y1=None):
    a[y0:(a.shape[0] if y1 is None else y1 + 1), x] = True


def _box(a, y0, y1, s, left=True, right=True, top=True):
    """A box from row y0 down to row y1 whose side walls run s rows past its bottom stroke."""
    w = a.shape[1]
    if top:
        _h(a, y0)
    _h(a, y1 - s)
    if left:
        _v(a, 0, y0, y1)
    if right:
        _v(a, w - 1, y0, y1)


def _rows(h: int, n: int):
    """n row positions spread evenly over h rows, first and last included."""
    return [round(i * (h - 1) / (n - 1)) for i in range(n)]


# --- shapes ---------------------------------------------------------------------
# Each draws into a (h, w) array; s is the length of the bottom serifs (0 = none).

def _kou(a, w, h, s):       # 口 (also 囗 as a frame)
    if h - s < 3:
        return False
    _box(a, 0, h - 1, s)
    return True


def _ri(a, w, h, s):        # 日 曰: box with a middle bar
    body = h - s
    if body < 5:
        return False
    _box(a, 0, h - 1, s)
    _h(a, (body - 1) // 2)
    return True


def _mu(a, w, h, s):        # 目: box with two bars
    body = h - s
    if body < 7:
        return False
    _box(a, 0, h - 1, s)
    for y in _rows(body, 4)[1:3]:
        _h(a, y)
    return True


def _tian(a, w, h, s):      # 田
    body = h - s
    if body < 5 or w < 5:
        return False
    _box(a, 0, h - 1, s)
    _h(a, (body - 1) // 2)
    _v(a, w // 2, 0, body - 1)
    return True


def _bai(a, w, h, s):       # 白: a tick above 日
    body = h - 1 - s
    if body < 5 or w < 3:
        return False
    a[0, w // 2] = True
    _box(a, 1, h - 1, s)
    _h(a, 1 + (body - 1) // 2)
    return True


def _gong(a, w, h, s):      # 工
    inset = max(1, w // 6) if w >= 7 else 0
    _h(a, 0, inset, w - 1 - inset)
    _h(a, h - 1)
    _v(a, w // 2)
    return True


def _tu(a, w, h, s, long_top=False):    # 土 (士 with long_top)
    if h < 5:
        return False
    inset = max(1, w // 5) if w >= 5 else 0
    mid = max(1, round(h * 0.4))
    if mid >= h - 2:
        mid = h - 3
    if long_top:
        _h(a, mid)
        _h(a, h - 1, inset, w - 1 - inset)
    else:
        _h(a, mid, inset, w - 1 - inset)
        _h(a, h - 1)
    _v(a, w // 2)
    return True


def _wang(a, w, h, s):      # 王
    if h < 5:
        return False
    inset = 1 if w >= 5 else 0
    _h(a, 0, inset, w - 1 - inset)
    _h(a, h // 2, inset, w - 1 - inset)
    _h(a, h - 1)
    _v(a, w // 2)
    return True


def _shi(a, w, h, s):       # 十
    _h(a, h // 2)
    _v(a, w // 2)
    return True


def _yi(a, w, h, s):        # 一
    _h(a, h // 2)
    return True


def _er(a, w, h, s):        # 二
    inset = max(1, w // 6) if w >= 5 else 0
    top, bottom = (h // 4, h - 1 - h // 4)
    if bottom - top < 2:
        return False
    _h(a, top, inset, w - 1 - inset)
    _h(a, bottom)
    return True


def _san(a, w, h, s):       # 三
    if h < 5:
        return False
    inset = max(1, w // 6) if w >= 5 else 0
    middle = inset + 1 if w >= 7 else inset
    ys = _rows(h, 3)
    _h(a, ys[0], inset, w - 1 - inset)
    _h(a, ys[1], middle, w - 1 - middle)
    _h(a, ys[2])
    return True


def _gun(a, w, h, s):       # 丨
    _v(a, w // 2)
    return True


def _shan(a, w, h, s):      # 山
    if w < 5 or h - s < 3:
        return False
    bottom = h - 1 - s
    _v(a, w // 2, 0, bottom)
    side = max(1, round(bottom * 0.4))
    _v(a, 0, side)
    _v(a, w - 1, side)
    _h(a, bottom)
    return True


def _zhong(a, w, h, s):     # 中: its box's walls run past the box, the stem to the bottom
    if h < 5:
        return False
    top = max(1, h // 5)
    y1 = h - 1 - top
    s = min(s, h - 1 - y1)
    _box(a, top, y1 + s, s)
    _v(a, w // 2)
    return True


def _field_with_stem(up: bool, down: bool):   # 由 甲 申
    def draw(a, w, h, s):
        stem = max(2, h // 4)
        y0 = stem if up else 0
        y1 = h - 1 - stem if down else h - 1 - s   # the box's bottom stroke
        if y1 - y0 + 1 < 5 or w < 5:
            return False
        _box(a, y0, y1 + s, s)
        _h(a, y0 + (y1 - y0) // 2)
        _v(a, w // 2)
        return True
    return draw


def _jiong(a, w, h, s):     # 冂: top and sides
    _h(a, 0); _v(a, 0); _v(a, w - 1)
    return True


def _kan(a, w, h, s):       # 凵: bottom and sides
    if h - s < 2:
        return False
    _box(a, 0, h - 1, s, top=False)
    return True


def _fang(a, w, h, s):      # 匚: top, left and bottom
    if h - s < 3:
        return False
    _box(a, 0, h - 1, s, right=False)
    return True


def _mi(a, w, h, s):        # 冖: a bar with short legs
    if h < 2:
        return False
    _h(a, 0)
    legs = min(h - 1, 2)
    _v(a, 0, 0, legs)
    _v(a, w - 1, 0, legs)
    return True


def _tou(a, w, h, s):       # 亠: a dot over a bar
    if h < 3:
        return False
    a[0, w // 2] = True
    _h(a, 1)
    return True


def _mian(a, w, h, s):      # 宀: a dot over 冖
    if h < 3:
        return False
    a[0, w // 2] = True
    _h(a, 1)
    legs = min(h - 1, 3)
    _v(a, 0, 1, legs)
    _v(a, w - 1, 1, legs)
    return True


def _cao(a, w, h, s):       # 艹: a bar crossed by two stems
    if w < 5:
        return False
    _h(a, h // 2)
    q = max(1, w // 4)
    _v(a, q)
    _v(a, w - 1 - q)
    return True


def _mu4(a, w, h, s):       # 木: bar, stem, and legs at 45° from under the bar
    if w < 5 or h < 5:
        return False
    bar = max(1, h // 3)
    cx = w // 2
    _h(a, bar)
    _v(a, cx)
    for i in range(1, cx + 1):
        y = bar + i
        if y >= h:
            break
        a[y, cx - i] = a[y, cx + i] = True
    return True


SPECS: Dict[str, Spec] = {
    '口': Spec(_kou, max_wide=2.2, max_tall=1.4, boxy=True, natural=1.0),
    '日': Spec(_ri, max_wide=3.0, max_tall=2.6, min_h=5, boxy=True, natural=1.3),
    '曰': Spec(_ri, max_wide=3.5, max_tall=1.2, min_h=5, boxy=True, natural=0.75),
    '目': Spec(_mu, max_wide=1.6, max_tall=3.2, min_h=7, boxy=True, natural=1.8),
    '田': Spec(_tian, max_wide=1.6, max_tall=1.6, min_w=5, min_h=5, boxy=True, natural=1.0),
    '白': Spec(_bai, max_wide=1.6, max_tall=2.6, min_h=7, boxy=True, natural=1.3),
    '工': Spec(_gong, max_wide=1.8, max_tall=1.4),
    '土': Spec(_tu, max_wide=1.8, max_tall=2.2, min_h=5),
    '士': Spec(lambda a, w, h, s: _tu(a, w, h, s, long_top=True), max_wide=1.8, max_tall=2.2, min_h=5),
    '王': Spec(_wang, max_wide=1.8, max_tall=2.6, min_h=5),
    '十': Spec(_shi, max_wide=2.0, max_tall=2.0),
    '一': Spec(_yi, max_wide=99, max_tall=99, min_h=1),
    '二': Spec(_er, max_wide=4.0, max_tall=1.4),
    '三': Spec(_san, max_wide=3.0, max_tall=1.8, min_h=5),
    '丨': Spec(_gun, max_wide=99, max_tall=99, min_w=1),
    '山': Spec(_shan, max_wide=2.2, max_tall=1.6, min_w=5, natural=0.9),
    '中': Spec(_zhong, max_wide=1.6, max_tall=2.6, min_w=5, min_h=5, boxy=True, natural=1.1),
    '由': Spec(_field_with_stem(True, False), max_wide=1.4, max_tall=2.2, min_w=5, min_h=7, boxy=True,
              natural=1.2),
    '甲': Spec(_field_with_stem(False, True), max_wide=1.4, max_tall=2.2, min_w=5, min_h=7, boxy=True,
              natural=1.2),
    '申': Spec(_field_with_stem(True, True), max_wide=1.2, max_tall=3.0, min_w=5, min_h=9, boxy=True,
              natural=1.4),
    '冂': Spec(_jiong, max_wide=99, max_tall=99),
    '凵': Spec(_kan, max_wide=99, max_tall=99, natural=0.9),
    '匚': Spec(_fang, max_wide=99, max_tall=99, natural=1.0),
    '冖': Spec(_mi, max_wide=99, max_tall=0.6, min_h=2),
    '亠': Spec(_tou, max_wide=99, max_tall=0.8),
    '宀': Spec(_mian, max_wide=99, max_tall=0.6),
    '艹': Spec(_cao, max_wide=99, max_tall=0.6, min_w=5),
    '木': Spec(_mu4, max_wide=1.6, max_tall=2.6, min_w=5, min_h=5),
}

# Frames that are plain outlines of their box: (draw, natural height/width for serifs).
# A frame's serifs raise its bottom stroke, which must stay clear of its inner box:
# layout.tsv gives these frames a bottom inset of 4 (stroke, 1px gap, 2px serif).
FRAMES = {('囗', '⿴'): (_kou, 1.0), ('冂', '⿵'): (_jiong, None),
          ('凵', '⿶'): (_kan, 0.9), ('匚', '⿷'): (_fang, 1.0)}

_cache: Dict[Tuple[SlotKey, Optional[int]], Optional[np.ndarray]] = {}


def generate(key: SlotKey, bottom_inset: Optional[int] = None) -> Optional[np.ndarray]:
    """
    The generated glyphlette as a (h, w) boolean array, or None if it cannot be generated.
    bottom_inset: for frames, how far their inner box stays from the bottom (Layout.insets);
    serifs are only drawn where they leave the inner box clear.
    """
    ck = (key, bottom_inset)
    if ck in _cache:
        return _cache[ck]
    out = None
    if key.role:
        frame = FRAMES.get((key.comp, key.role))
        if frame is not None:
            draw, natural = frame
            s = serif_length(natural, key.w, key.h)
            s = min(s, max(0, (bottom_inset or 0) - 2))   # bottom stroke + 1px gap above the inner box
            out = np.zeros((key.h, key.w), dtype=bool)
            draw(out, key.w, key.h, s)
    elif key.comp in SPECS and not (key.w == GEO.BODY_W and key.h == GEO.BODY_H):
        spec = SPECS[key.comp]
        fit = _fit(spec, key.w, key.h)
        if fit is not None:
            x, y, fw, fh = fit
            for s in dict.fromkeys((serif_length(spec.natural, fw, fh), 0)):
                shape = np.zeros((fh, fw), dtype=bool)
                if spec.draw(shape, fw, fh, s):
                    out = np.zeros((key.h, key.w), dtype=bool)
                    out[y:y + fh, x:x + fw] = shape
                    break
    _cache[ck] = out
    return out


def generate_for(layout, key: SlotKey) -> Optional[np.ndarray]:
    """generate() with the frame insets of a layout."""
    if key.role:
        return generate(key, layout.insets(key.comp, key.role, key.w, key.h)[3])
    return generate(key)


def is_unit(key: SlotKey) -> bool:
    """Components with a generator are never split: the generator knows their shape
    (田 is not a frame with a floating 十). Standalone, they are drawn by hand."""
    return not key.role and key.comp in SPECS


def can_generate(key: SlotKey) -> bool:
    if key.role:
        return (key.comp, key.role) in FRAMES
    return generate(key) is not None
