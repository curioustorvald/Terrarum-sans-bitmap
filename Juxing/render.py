"""
Human-facing views: terminal layout diagrams, sheet indices, layout mock-ups and
text previews.

Labels and mock-ups are rendered with a system CJK font (Noto Sans CJK, falling
back to BabelStone Han for rarer components). These are *reference images only*;
nothing rendered here ever goes into a drawing sheet or the output font.
"""

import os
import shutil
import string
import subprocess
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import geometry as GEO
import glyphlettes as GL
from geometry import Layout, SlotKey

BG = (0x24, 0x24, 0x24, 255)
FG = (0xFF, 0xFF, 0xFF, 255)
DIM = (0x80, 0x80, 0x80, 255)
BOX_COLOURS = [
    (0x33, 0x99, 0xFF), (0xFF, 0x99, 0x33), (0x66, 0xCC, 0x66), (0xDD, 0x55, 0xDD),
    (0xEE, 0xDD, 0x44), (0x44, 0xDD, 0xDD), (0xFF, 0x66, 0x66), (0xAA, 0xAA, 0xFF),
]

LABEL_FONT_FAMILIES = ['Noto Sans CJK SC', 'Noto Sans CJK KR', 'BabelStone Han', 'Droid Sans Fallback']


class LabelFont:
    """Draws text with the first candidate font that has each character."""

    def __init__(self, size: int):
        self.size = size
        self.fonts: List[Tuple[ImageFont.FreeTypeFont, set]] = []
        paths = []
        env = os.environ.get('JUXING_LABEL_FONT')
        if env:
            paths.append(env)
        if shutil.which('fc-match'):
            for fam in LABEL_FONT_FAMILIES:
                try:
                    p = subprocess.run(['fc-match', '-f', '%{file}', fam], capture_output=True, text=True).stdout
                except OSError:
                    p = ''
                if p and p not in paths and fam.split()[0].lower() in os.path.basename(p).lower():
                    paths.append(p)
        from fontTools.ttLib import TTFont
        for p in paths:
            try:
                cmap = set(TTFont(p, fontNumber=0, lazy=True).getBestCmap())
                self.fonts.append((ImageFont.truetype(p, size), cmap))
            except Exception:
                continue
        self.fallback = ImageFont.load_default()

    def font_for(self, ch: str):
        for f, cmap in self.fonts:
            if ord(ch) in cmap:
                return f
        return None

    def draw(self, d: ImageDraw.ImageDraw, xy, text: str, fill):
        x, y = xy
        for ch in text:
            f = self.font_for(ch) or self.fallback
            d.text((x, y), ch, font=f, fill=fill)
            x += d.textlength(ch, font=f)
        return x

    def width(self, d: ImageDraw.ImageDraw, text: str) -> float:
        return sum(d.textlength(ch, font=self.font_for(ch) or self.fallback) for ch in text)


def describe(layout: Layout, key: SlotKey) -> str:
    c = layout.model.get(key.comp)
    note = f" ({c.note})" if c is not None and c.note else ''
    return f"{key}{note}"


# ---------------------------------------------------------------------------
# Terminal

def ascii_layout(layout: Layout, placements: List[Tuple[SlotKey, int, int]], drawn=None) -> List[str]:
    """A 16x16 character grid showing which glyphlette covers each pixel; drawn ink as █."""
    grid = [['·'] * GEO.CELL_W for _ in range(GEO.CELL_H)]
    for i, (k, x, y) in enumerate(placements):
        mark = string.ascii_letters[i % 52]
        inner = None
        if k.role:
            l, t, r, b = layout.insets(k.comp, k.role, k.w, k.h)
            inner = (x + l, y + t, x + k.w - r, y + k.h - b)
        for yy in range(y, y + k.h):
            for xx in range(x, x + k.w):
                if not (inner and inner[0] <= xx < inner[2] and inner[1] <= yy < inner[3]):
                    grid[yy][xx] = mark
    if drawn:
        ink = GL.compose([p for p in placements if p[0] in drawn], drawn)
        for yy, xx in zip(*np.nonzero(ink)):
            grid[yy][xx] = '█'
    lines = [' '.join(row) for row in grid]
    for i, (k, x, y) in enumerate(placements):
        state = ''
        if drawn is not None:
            kind = drawn.kind(k) if hasattr(drawn, 'kind') else ('drawn' if k in drawn else None)
            state = (f"  [derived from {drawn.source(k)}]" if kind == 'derived' else
                     f"  [{kind}]" if kind else '  [not drawn]')
        lines.append(f"  {string.ascii_letters[i % 52]} = {describe(layout, k)} at ({x},{y}){state}")
    return lines


# ---------------------------------------------------------------------------
# Sheet index

def sheet_index(sheet_rgba: np.ndarray, entries, layout: Layout, out_path: str, scale=4):
    """A zoomed copy of a drawing sheet with every cell labelled."""
    cw, ch = GEO.CELL_W * scale, GEO.CELL_H * scale
    label_h = 34
    cols = sheet_rgba.shape[1] // GEO.CELL_W
    rows = max((e.row for e in entries), default=0) + 1
    pad = 6
    W, H = cols * (cw + pad) + pad, rows * (ch + label_h + pad) + pad
    img = Image.new('RGBA', (W, H), BG)
    zoom = Image.fromarray(sheet_rgba, 'RGBA').resize(
        (sheet_rgba.shape[1] * scale, sheet_rgba.shape[0] * scale), Image.NEAREST)
    d = ImageDraw.Draw(img)
    big = LabelFont(16)
    small = LabelFont(11)
    for e in entries:
        x0 = pad + e.col * (cw + pad)
        y0 = pad + e.row * (ch + label_h + pad)
        cell = zoom.crop((e.col * cw, e.row * ch, e.col * cw + cw, e.row * ch + ch))
        img.alpha_composite(cell, (x0, y0))
        d.rectangle((x0 - 1, y0 - 1, x0 + cw, y0 + ch), outline=(0x50, 0x50, 0x50, 255))
        k = e.key
        comp = k.comp if len(k.comp) <= 3 else k.comp[:3] + '…'
        x = big.draw(d, (x0, y0 + ch + 1), comp, FG)
        size = f" {k.w}×{k.h}" + (k.role if k.role else '')
        small.draw(d, (x + 1, y0 + ch + 5), size, DIM)
        small.draw(d, (x0, y0 + ch + 19), f"#{e.id} {e.exemplar}", DIM)
    img.save(out_path)


# ---------------------------------------------------------------------------
# Layout mock-ups (for checking the layout before anything is drawn)

def mockup(layout: Layout, chars: Sequence[str], placements_of, out_path: str, scale=8, per_row=8):
    """
    Each character's boxes, with its components rendered into them by a system font.
    Solid boxes are glyphlettes; frames show their inner area hatched.
    """
    font_cache: Dict[int, LabelFont] = {}
    cw, chh = GEO.CELL_W * scale, GEO.CELL_H * scale
    label_h = 30
    pad = 10
    n = len(chars)
    cols = min(per_row, n)
    rows = (n + per_row - 1) // per_row
    img = Image.new('RGBA', (cols * (cw + pad) + pad, rows * (chh + label_h + pad) + pad), BG)
    d = ImageDraw.Draw(img)
    title = LabelFont(20)
    caption = LabelFont(12)
    for i, ch in enumerate(chars):
        x0 = pad + (i % per_row) * (cw + pad)
        y0 = pad + (i // per_row) * (chh + label_h + pad)
        d.rectangle((x0, y0, x0 + cw - 1, y0 + chh - 1), fill=(0x30, 0x30, 0x30, 255))
        # the body
        d.rectangle((x0 + GEO.BODY_X * scale, y0 + GEO.BODY_Y * scale,
                     x0 + (GEO.BODY_X + GEO.BODY_W) * scale - 1, y0 + (GEO.BODY_Y + GEO.BODY_H) * scale - 1),
                    outline=(0x48, 0x48, 0x48, 255))
        pl = placements_of(ord(ch))
        for j, (k, x, y) in enumerate(pl or []):
            col = BOX_COLOURS[j % len(BOX_COLOURS)]
            bx, by = x0 + x * scale, y0 + y * scale
            bw, bh = k.w * scale, k.h * scale
            layer = Image.new('RGBA', (bw, bh), (0, 0, 0, 0))
            ld = ImageDraw.Draw(layer)
            ld.rectangle((0, 0, bw - 1, bh - 1), fill=col + (0x30,), outline=col + (0xC0,))
            inner = None
            if k.role:
                l, t, r, b = layout.insets(k.comp, k.role, k.w, k.h)
                inner = (l * scale, t * scale, (k.w - r) * scale - 1, (k.h - b) * scale - 1)
                ld.rectangle(inner, fill=(0x30, 0x30, 0x30, 255), outline=col + (0x80,))
            # component rendered with a reference font, squeezed into the box
            glyph = _render_component(k.comp, bw, bh, font_cache)
            if glyph is not None:
                tint = Image.new('RGBA', (bw, bh), col + (255,))
                tint.putalpha(glyph)
                if inner:
                    mask = Image.new('L', (bw, bh), 255)
                    ImageDraw.Draw(mask).rectangle(inner, fill=0)
                    a = np.minimum(np.array(tint.getchannel('A')), np.array(mask))
                    tint.putalpha(Image.fromarray(a))
                layer.alpha_composite(tint)
            img.alpha_composite(layer, (bx, by))
        if pl is None:
            d.line((x0, y0, x0 + cw, y0 + chh), fill=(0xAA, 0x33, 0x33, 255), width=2)
        title.draw(d, (x0, y0 + chh + 3), ch, FG)
        pieces = f" {len(pl)} part{'s' if pl and len(pl) != 1 else ''}" if pl else ' —'
        caption.draw(d, (x0 + 26, y0 + chh + 9), pieces, DIM)
    img.save(out_path)


def _render_component(comp: str, bw: int, bh: int, cache) -> Optional[Image.Image]:
    if len(comp) != 1:
        return None
    size = 96
    f = cache.get(size)
    if f is None:
        f = cache[size] = LabelFont(size)
    font = f.font_for(comp)
    if font is None:
        return None
    tmp = Image.new('L', (size * 2, size * 2), 0)
    ImageDraw.Draw(tmp).text((size // 2, size // 2), comp, font=font, fill=255)
    bbox = tmp.getbbox()
    if not bbox:
        return None
    glyph = tmp.crop(bbox)
    m = max(1, min(bw, bh) // 16)
    return glyph.resize((max(1, bw - 2 * m), max(1, bh - 2 * m)), Image.LANCZOS).crop(
        (-m, -m, bw - m, bh - m))


# ---------------------------------------------------------------------------
# Output previews

def text_preview(glyphs: Dict[int, np.ndarray], lines: List[str], out_path: str, scale=3):
    """Render text with the assembled glyphs; missing characters show as dim boxes."""
    width = max((len(l) for l in lines), default=1)
    img = np.zeros((len(lines) * 20 * scale, width * GEO.CELL_W * scale, 4), dtype=np.uint8)
    img[...] = BG
    tofu = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
    tofu[GEO.BODY_Y + 2, 2:GEO.BODY_W - 2] = tofu[GEO.BODY_Y + GEO.BODY_H - 3, 2:GEO.BODY_W - 2] = True
    tofu[GEO.BODY_Y + 2:GEO.BODY_Y + GEO.BODY_H - 2, 2] = True
    tofu[GEO.BODY_Y + 2:GEO.BODY_Y + GEO.BODY_H - 2, GEO.BODY_W - 3] = True
    for row, line in enumerate(lines):
        for col, ch in enumerate(line):
            g = glyphs.get(ord(ch))
            colour = FG
            if g is None:
                if ch.isspace():
                    continue
                g, colour = tofu, (0x55, 0x55, 0x55, 255)
            y0 = (row * 20 + 2) * scale
            x0 = col * GEO.CELL_W * scale
            big = np.kron(g, np.ones((scale, scale), dtype=bool))
            img[y0:y0 + big.shape[0], x0:x0 + big.shape[1]][big] = colour
    Image.fromarray(img, 'RGBA').save(out_path)


def coverage_map(covered: set, out_path: str, first=0x3400, end=0xA000, scale=3):
    """One pixel per code point of the Han sheet: white = assembled, grey = missing."""
    n = end - first
    img = np.zeros((n // 256, 256, 4), dtype=np.uint8)
    img[...] = (0x40, 0x40, 0x40, 255)
    for cp in covered:
        i = cp - first
        img[i // 256, i % 256] = FG
    Image.fromarray(img, 'RGBA').resize((256 * scale, (n // 256) * scale), Image.NEAREST).save(out_path)


def gallery(items, out_path: str, scale=4, per_row=24):
    """Glyphlettes (key, (h, w) mask, uses) drawn at their box size, labelled."""
    cw, ch = GEO.CELL_W * scale, GEO.CELL_H * scale
    label_h, pad = 30, 6
    rows = max(1, (len(items) + per_row - 1) // per_row)
    img = Image.new('RGBA', (per_row * (cw + pad) + pad, rows * (ch + label_h + pad) + pad), BG)
    d = ImageDraw.Draw(img)
    big, small = LabelFont(14), LabelFont(10)
    for i, (k, mask, uses) in enumerate(items):
        x0 = pad + (i % per_row) * (cw + pad)
        y0 = pad + (i // per_row) * (ch + label_h + pad)
        d.rectangle((x0, y0, x0 + k.w * scale - 1, y0 + k.h * scale - 1), fill=(0x1E, 0x32, 0x50, 255))
        for yy, xx in zip(*np.nonzero(mask)):
            d.rectangle((x0 + xx * scale, y0 + yy * scale, x0 + xx * scale + scale - 1,
                         y0 + yy * scale + scale - 1), fill=FG)
        x = big.draw(d, (x0, y0 + ch + 1), k.comp, FG)
        small.draw(d, (x + 2, y0 + ch + 4), f"{k.w}×{k.h}{k.role}", DIM)
        small.draw(d, (x0, y0 + ch + 17), f"{uses}×", DIM)
    img.save(out_path)


DERIVED_BG = {'drawn': (0x1E, 0x32, 0x50, 255), 'derived': (0x30, 0x30, 0x30, 255),
              'declined': (0x58, 0x1E, 0x1E, 255)}


def derived_gallery(rows, out_path: str, scale=4):
    """
    One row per family: its drawings, then every size derived from them.
    rows: [(label, [(key, box mask (h, w), state, caption)])], state being 'drawn',
    'derived' or 'declined' (the mask is then the draft the resizer gave up on).
    """
    pad, label_w, cap_h = 6, 110, 28
    col = lambda k: max(k.w * scale, 40)
    widths = [label_w + sum(col(k) + pad for k, _, _, _ in items) + pad for _, items in rows]
    row_h = GEO.BODY_H * scale + cap_h + pad
    img = Image.new('RGBA', (max(widths, default=200), max(1, len(rows)) * row_h + pad), BG)
    d = ImageDraw.Draw(img)
    big, small = LabelFont(16), LabelFont(10)
    for r, (label, items) in enumerate(rows):
        y0 = pad + r * row_h
        big.draw(d, (pad, y0), label[0], FG)
        small.draw(d, (pad, y0 + 22), label[1], DIM)
        x0 = label_w
        for k, mask, state, caption in items:
            d.rectangle((x0, y0, x0 + k.w * scale - 1, y0 + k.h * scale - 1), fill=DERIVED_BG[state])
            for yy, xx in zip(*np.nonzero(mask)):
                d.rectangle((x0 + xx * scale, y0 + yy * scale, x0 + xx * scale + scale - 1,
                             y0 + yy * scale + scale - 1), fill=FG)
            small.draw(d, (x0, y0 + GEO.BODY_H * scale + 2), f"{k.w}×{k.h}", FG if state != 'derived' else DIM)
            small.draw(d, (x0, y0 + GEO.BODY_H * scale + 14), caption, DIM)
            x0 += col(k) + pad
    img.save(out_path)


REFERENCE_COLOURS = [(0xE6, 0x50, 0x50), (0x50, 0xA0, 0xF0), (0x5A, 0xC8, 0x78), (0xF0, 0xB4, 0x28),
                     (0xC8, 0x5A, 0xDC), (0x3C, 0xD2, 0xD2)]


def reference_sheet(layout: Layout, renderer, chars: Sequence[str], out_path: str, per_row: int = 10):
    """
    The characters as Chiron Hei HK draws them, with the boxes measured for their parts
    (reference.py), for checking the segmentation. Reference images only.
    """
    import reference as RF
    fx0, fy0, fx1, fy1 = renderer.face
    fw, fh = fx1 - fx0, fy1 - fy0
    pad, cap = 8, 22
    tw, th = int(fw) + 2 * pad, int(fh) + 2 * pad + cap
    rows = max(1, (len(chars) + per_row - 1) // per_row)
    img = Image.new('RGB', (per_row * tw, rows * th), BG[:3])
    d = ImageDraw.Draw(img)
    small = LabelFont(11)
    for i, ch in enumerate(chars):
        ox, oy = (i % per_row) * tw, (i // per_row) * th
        m = renderer.render(ch)
        if m is not None:
            crop = m[int(fy0) - pad:int(fy1) + pad, int(fx0) - pad:int(fx1) + pad]
            glyph = Image.fromarray((crop * 150).astype(np.uint8)).convert('RGB')
            img.paste(glyph, (ox, oy))
        d.rectangle((ox + pad, oy + pad, ox + pad + fw, oy + pad + fh), outline=(60, 60, 60))
        boxes = RF.measure(layout, renderer, ch)
        for p, b in boxes:
            if not p or '.' in p:
                continue
            c = REFERENCE_COLOURS[int(p) % len(REFERENCE_COLOURS)]
            d.rectangle((ox + pad + b.x0 * fw, oy + pad + b.y0 * fh, ox + pad + b.x1 * fw - 1, oy + pad + b.y1 * fh - 1),
                        outline=c, width=2 if not b.ambiguous else 1)
        tops = [b for p, b in boxes if p and '.' not in p]
        note = ' '.join(b.comp[:3] for b in tops) + (' ?' if any(b.ambiguous for b in tops) else '')
        small.draw(d, (ox + pad, oy + th - cap + 2), f"{ch} {note}", FG)
    img.save(out_path)
