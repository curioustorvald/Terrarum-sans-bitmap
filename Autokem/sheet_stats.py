#!/usr/bin/env python3
"""
Spritesheet statistics generator for TerrarumSansBitmap.

Scans all *_variable.tga sheets and reports:
  - Width distribution
  - Compiler directives (replaceWith breakdown)
  - Kerning shape distribution
  - Lowheight count
  - Diacritics (anchors, writeOnTop, stacking)
  - Glyphs missing kerning data
  - Dot removal directives
  - Nudge usage
  - Alignment modes
  - Per-sheet summary

Usage:
    python sheet_stats.py [assets_dir]
    python sheet_stats.py ../src/assets
"""

import os
import struct
import sys
from collections import Counter, defaultdict

# ---- TGA reader ----

class TgaImage:
    __slots__ = ('width', 'height', 'pixels')

    def __init__(self, width, height, pixels):
        self.width = width
        self.height = height
        self.pixels = pixels

    def get_pixel(self, x, y):
        if x < 0 or x >= self.width or y < 0 or y >= self.height:
            return 0
        return self.pixels[y * self.width + x]


def read_tga(path):
    with open(path, 'rb') as f:
        data = f.read()
    pos = 0
    id_length = data[pos]; pos += 1
    pos += 1  # colour_map_type
    image_type = data[pos]; pos += 1
    pos += 5
    pos += 4  # x/y origin
    width = struct.unpack_from('<H', data, pos)[0]; pos += 2
    height = struct.unpack_from('<H', data, pos)[0]; pos += 2
    bits_per_pixel = data[pos]; pos += 1
    descriptor = data[pos]; pos += 1
    top_to_bottom = (descriptor & 0x20) != 0
    bpp = bits_per_pixel // 8
    pos += id_length
    if image_type != 2 or bpp not in (3, 4):
        raise ValueError(f"Unsupported TGA: type={image_type}, bpp={bits_per_pixel}")
    pixels = [0] * (width * height)
    for row in range(height):
        y = row if top_to_bottom else (height - 1 - row)
        for x in range(width):
            b = data[pos]; g = data[pos+1]; r = data[pos+2]
            a = data[pos+3] if bpp == 4 else 0xFF
            pos += bpp
            pixels[y * width + x] = (r << 24) | (g << 16) | (b << 8) | a
    return TgaImage(width, height, pixels)


def tagify(pixel):
    return 0 if (pixel & 0xFF) == 0 else pixel


def signed_byte(val):
    return val - 256 if val >= 128 else val


# ---- Unicode range classification ----

# Ranges to EXCLUDE from "missing kern" report
EXCLUDE_KERN_RANGES = [
    (0x3400, 0xA000, 'CJK Unified Ideographs'),
    (0x1100, 0x1200, 'Hangul Jamo'),
    (0xA960, 0xA980, 'Hangul Jamo Extended-A'),
    (0xD7B0, 0xD800, 'Hangul Jamo Extended-B'),
    (0x3130, 0x3190, 'Hangul Compatibility Jamo'),
    (0xAC00, 0xD7A4, 'Hangul Syllables'),
    (0xE000, 0xE100, 'Custom Symbols (PUA)'),
    (0xF0000, 0xF0600, 'Internal PUA'),
    (0xFFE00, 0x100000, 'Internal control/PUA'),
    (0x2800, 0x2900, 'Braille'),
    (0x1FB00, 0x1FC00, 'Legacy Computing Symbols'),
    (0x2400, 0x2440, 'Control Pictures'),
    (0x3000, 0x3040, 'CJK Punctuation'),
    (0x3040, 0x3100, 'Hiragana/Katakana'),
    (0x31F0, 0x3200, 'Katakana Phonetic Ext'),
    (0xFF00, 0x10000, 'Halfwidth/Fullwidth'),
    (0x16A0, 0x1700, 'Runic'),
    (0x300, 0x370, 'Combining Diacritical Marks'),
    (0x1B000, 0x1B170, 'Hentaigana'),
]


def is_excluded_from_kern(cp):
    for lo, hi, _ in EXCLUDE_KERN_RANGES:
        if lo <= cp < hi:
            return True
    return False


def unicode_block_name(cp):
    """Rough Unicode block classification for display."""
    blocks = [
        (0x0000, 0x0080, 'Basic Latin'),
        (0x0080, 0x0100, 'Latin-1 Supplement'),
        (0x0100, 0x0180, 'Latin Extended-A'),
        (0x0180, 0x0250, 'Latin Extended-B'),
        (0x0250, 0x02B0, 'IPA Extensions'),
        (0x02B0, 0x0300, 'Spacing Modifier Letters'),
        (0x0300, 0x0370, 'Combining Diacritical Marks'),
        (0x0370, 0x0400, 'Greek and Coptic'),
        (0x0400, 0x0530, 'Cyrillic'),
        (0x0530, 0x0590, 'Armenian'),
        (0x0900, 0x0980, 'Devanagari'),
        (0x0980, 0x0A00, 'Bengali'),
        (0x0B80, 0x0C00, 'Tamil'),
        (0x0E00, 0x0E80, 'Thai'),
        (0x10D0, 0x1100, 'Georgian'),
        (0x1100, 0x1200, 'Hangul Jamo'),
        (0x13A0, 0x13F6, 'Cherokee'),
        (0x1B80, 0x1BC0, 'Sundanese'),
        (0x1C80, 0x1CC0, 'Cyrillic Extended'),
        (0x1D00, 0x1DC0, 'Phonetic Extensions'),
        (0x1E00, 0x1F00, 'Latin Extended Additional'),
        (0x1F00, 0x2000, 'Greek Extended'),
        (0x2000, 0x2070, 'General Punctuation'),
        (0x20A0, 0x20D0, 'Currency Symbols'),
        (0x2100, 0x2200, 'Letterlike Symbols'),
        (0x2C60, 0x2C80, 'Latin Extended-C'),
        (0x2DE0, 0x2E00, 'Cyrillic Extended-A'),
        (0xA640, 0xA6A0, 'Cyrillic Extended-B'),
        (0xA720, 0xA800, 'Latin Extended-D'),
        (0xFB00, 0xFB50, 'Alphabetic Presentation Forms'),
        (0x1F100, 0x1F200, 'Enclosed Alphanumeric Supplement'),
        (0xF0000, 0xF0060, 'PUA Bulgarian'),
        (0xF0060, 0xF00C0, 'PUA Serbian'),
        (0xF0100, 0xF0500, 'PUA Devanagari Internal'),
        (0xF0500, 0xF0600, 'PUA Sundanese/Codestyle'),
    ]
    for lo, hi, name in blocks:
        if lo <= cp < hi:
            return name
    return f'U+{cp:04X}'


# ---- Code ranges (from sheet_config.py) ----

CODE_RANGE = [
    list(range(0x00, 0x100)),
    list(range(0x1100, 0x1200)) + list(range(0xA960, 0xA980)) + list(range(0xD7B0, 0xD800)),
    list(range(0x100, 0x180)),
    list(range(0x180, 0x250)),
    list(range(0x3040, 0x3100)) + list(range(0x31F0, 0x3200)),
    list(range(0x3000, 0x3040)),
    list(range(0x3400, 0xA000)),
    list(range(0x400, 0x530)),
    list(range(0xFF00, 0x10000)),
    list(range(0x2000, 0x20A0)),
    list(range(0x370, 0x3CF)),
    list(range(0xE00, 0xE60)),
    list(range(0x530, 0x590)),
    list(range(0x10D0, 0x1100)),
    list(range(0x250, 0x300)),
    list(range(0x16A0, 0x1700)),
    list(range(0x1E00, 0x1F00)),
    list(range(0xE000, 0xE100)),
    list(range(0xF0000, 0xF0060)),
    list(range(0xF0060, 0xF00C0)),
    list(range(0x13A0, 0x13F6)),
    list(range(0x1D00, 0x1DC0)),
    list(range(0x900, 0x980)) + list(range(0xF0100, 0xF0500)),
    list(range(0x1C90, 0x1CC0)),
    list(range(0x300, 0x370)),
    list(range(0x1F00, 0x2000)),
    list(range(0x2C60, 0x2C80)),
    list(range(0xA720, 0xA800)),
    list(range(0x20A0, 0x20D0)),
    list(range(0xFFE00, 0xFFFA0)),
    list(range(0x2100, 0x2200)),
    list(range(0x1F100, 0x1F200)),
    list(range(0x0B80, 0x0C00)) + list(range(0xF00C0, 0xF0100)),
    list(range(0x980, 0xA00)),
    list(range(0x2800, 0x2900)),
    list(range(0x1B80, 0x1BC0)) + list(range(0x1CC0, 0x1CD0)) + list(range(0xF0500, 0xF0510)),
    list(range(0xF0110, 0xF0130)),
    list(range(0xF0520, 0xF0580)),
    list(range(0xFB00, 0xFB18)),
    list(range(0x1B000, 0x1B170)),
    list(range(0x2400, 0x2440)),
    list(range(0x1FB00, 0x1FC00)),
    list(range(0xA640, 0xA6A0)),
    list(range(0x2DE0, 0x2E00)),
    list(range(0x1C80, 0x1C8F)),
]

FILE_LIST = [
    "ascii_variable.tga",
    "hangul_johab.tga",
    "latinExtA_variable.tga",
    "latinExtB_variable.tga",
    "kana_variable.tga",
    "cjkpunct_variable.tga",
    "wenquanyi.tga",
    "cyrilic_variable.tga",
    "halfwidth_fullwidth_variable.tga",
    "unipunct_variable.tga",
    "greek_variable.tga",
    "thai_variable.tga",
    "hayeren_variable.tga",
    "kartuli_variable.tga",
    "ipa_ext_variable.tga",
    "futhark.tga",
    "latinExt_additional_variable.tga",
    "puae000-e0ff.tga",
    "cyrilic_bulgarian_variable.tga",
    "cyrilic_serbian_variable.tga",
    "tsalagi_variable.tga",
    "phonetic_extensions_variable.tga",
    "devanagari_variable.tga",
    "kartuli_allcaps_variable.tga",
    "diacritical_marks_variable.tga",
    "greek_polytonic_xyswap_variable.tga",
    "latinExtC_variable.tga",
    "latinExtD_variable.tga",
    "currencies_variable.tga",
    "internal_variable.tga",
    "letterlike_symbols_variable.tga",
    "enclosed_alphanumeric_supplement_variable.tga",
    "tamil_extrawide_variable.tga",
    "bengali_variable.tga",
    "braille_variable.tga",
    "sundanese_variable.tga",
    "devanagari_internal_extrawide_variable.tga",
    "pua_codestyle_ascii_variable.tga",
    "alphabetic_presentation_forms_extrawide_variable.tga",
    "hentaigana_variable.tga",
    "control_pictures_variable.tga",
    "symbols_for_legacy_computing_variable.tga",
    "cyrilic_extB_variable.tga",
    "cyrilic_extA_variable.tga",
    "cyrilic_extC_variable.tga",
]


def is_variable(fn):
    return fn.endswith('_variable.tga')


def is_extra_wide(fn):
    return 'extrawide' in fn.lower()


def is_xyswap(fn):
    return 'xyswap' in fn.lower()


# ---- Shape tag formatting ----

SHAPE_CHARS = 'ABCDEFGHJK'


def format_shape(mask, is_ytype):
    """Format kerning mask + ytype as keming_machine tag, e.g. 'ABCDEFGH(B)'."""
    bits = []
    for i, ch in enumerate(SHAPE_CHARS):
        bit_pos = [7, 6, 5, 4, 3, 2, 1, 0, 15, 14][i]
        if (mask >> bit_pos) & 1:
            bits.append(ch)
    chars = ''.join(bits) if bits else '(empty)'
    mode = '(Y)' if is_ytype else '(B)'
    return f'{chars}{mode}'


# ---- Parsing ----

def parse_diacritics_anchors(img, tag_x, tag_y):
    """Return number of defined diacritics anchors (0-6)."""
    count = 0
    for i in range(6):
        y_pos = 13 - (i // 3) * 2
        shift = (3 - (i % 3)) * 8
        y_pixel = tagify(img.get_pixel(tag_x, tag_y + y_pos))
        x_pixel = tagify(img.get_pixel(tag_x, tag_y + y_pos + 1))
        y_used = ((y_pixel >> shift) & 128) != 0
        x_used = ((x_pixel >> shift) & 128) != 0
        if y_used or x_used:
            count += 1
    return count


def parse_variable_sheet(path, code_range, is_xy, is_ew):
    """Parse a variable-width sheet and yield per-glyph stats dicts."""
    img = read_tga(path)
    cell_w = 32 if is_ew else 16
    cell_h = 20
    cols = img.width // cell_w

    for index, code in enumerate(code_range):
        if is_xy:
            cell_x = (index // cols) * cell_w
            cell_y = (index % cols) * cell_h
        else:
            cell_x = (index % cols) * cell_w
            cell_y = (index // cols) * cell_h

        tag_x = cell_x + (cell_w - 1)
        tag_y = cell_y

        # Width
        width = 0
        for y in range(5):
            if img.get_pixel(tag_x, tag_y + y) & 0xFF:
                width |= (1 << y)

        if width == 0:
            continue  # empty cell

        # Lowheight
        is_low_height = (img.get_pixel(tag_x, tag_y + 5) & 0xFF) != 0

        # Kerning data
        kern_pixel = tagify(img.get_pixel(tag_x, tag_y + 6))
        has_kern = (kern_pixel & 0xFF) != 0
        is_ytype = (kern_pixel & 0x80000000) != 0 if has_kern else False
        kern_mask = ((kern_pixel >> 8) & 0xFFFFFF) if has_kern else 0

        # Dot removal (Y+7)
        dot_pixel = tagify(img.get_pixel(tag_x, tag_y + 7))
        has_dot_removal = dot_pixel != 0

        # Compiler directive (Y+9)
        dir_pixel = tagify(img.get_pixel(tag_x, tag_y + 9))
        opcode = (dir_pixel >> 24) & 0xFF
        arg1 = (dir_pixel >> 16) & 0xFF
        arg2 = (dir_pixel >> 8) & 0xFF

        # Nudge (Y+10)
        nudge_pixel = tagify(img.get_pixel(tag_x, tag_y + 10))
        nudge_x = signed_byte((nudge_pixel >> 24) & 0xFF) if nudge_pixel else 0
        nudge_y = signed_byte((nudge_pixel >> 16) & 0xFF) if nudge_pixel else 0
        has_nudge = nudge_x != 0 or nudge_y != 0

        # Diacritics anchors (Y+11..Y+14)
        n_anchors = parse_diacritics_anchors(img, tag_x, tag_y)

        # Alignment (Y+15..Y+16)
        align = 0
        for y in range(2):
            if img.get_pixel(tag_x, tag_y + 15 + y) & 0xFF:
                align |= (1 << y)

        # WriteOnTop (Y+17)
        wot_raw = img.get_pixel(tag_x, tag_y + 17)
        has_write_on_top = (wot_raw & 0xFF) != 0

        # Stack (Y+18..Y+19)
        s0 = tagify(img.get_pixel(tag_x, tag_y + 18))
        s1 = tagify(img.get_pixel(tag_x, tag_y + 19))
        if s0 == 0x00FF00FF and s1 == 0x00FF00FF:
            stack_where = 4  # STACK_DONT
        else:
            stack_where = 0
            for y in range(2):
                if img.get_pixel(tag_x, tag_y + 18 + y) & 0xFF:
                    stack_where |= (1 << y)

        yield {
            'code': code,
            'width': width,
            'lowheight': is_low_height,
            'has_kern': has_kern,
            'is_ytype': is_ytype,
            'kern_mask': kern_mask,
            'has_dot_removal': has_dot_removal,
            'opcode': opcode,
            'opcode_arg1': arg1,
            'opcode_arg2': arg2,
            'has_nudge': has_nudge,
            'nudge_x': nudge_x,
            'nudge_y': nudge_y,
            'n_anchors': n_anchors,
            'align': align,
            'has_write_on_top': has_write_on_top,
            'stack_where': stack_where,
        }


# ---- Main ----

def main():
    assets_dir = sys.argv[1] if len(sys.argv) > 1 else '../src/assets'

    # Accumulators
    all_glyphs = []
    per_sheet = defaultdict(lambda: {'total': 0, 'kern': 0, 'lowh': 0, 'directives': 0})
    sheets_scanned = 0

    print(f"Scanning {assets_dir}...\n")

    for sheet_idx, filename in enumerate(FILE_LIST):
        if not is_variable(filename):
            continue
        if sheet_idx >= len(CODE_RANGE):
            continue

        path = os.path.join(assets_dir, filename)
        if not os.path.exists(path):
            continue

        is_xy = is_xyswap(filename)
        is_ew = is_extra_wide(filename)
        code_range = CODE_RANGE[sheet_idx]

        count = 0
        for g in parse_variable_sheet(path, code_range, is_xy, is_ew):
            g['sheet'] = filename
            all_glyphs.append(g)
            s = per_sheet[filename]
            s['total'] += 1
            if g['has_kern']:
                s['kern'] += 1
            if g['lowheight']:
                s['lowh'] += 1
            if g['opcode'] != 0:
                s['directives'] += 1
            count += 1

        sheets_scanned += 1

    total = len(all_glyphs)
    if total == 0:
        print("No glyphs found!")
        return 1

    print(f"Scanned {sheets_scanned} variable sheets, {total} glyphs with width > 0\n")

    # ---- 1. Width distribution ----
    width_counter = Counter(g['width'] for g in all_glyphs)
    print("=" * 60)
    print("WIDTH DISTRIBUTION")
    print("=" * 60)
    for w in sorted(width_counter):
        c = width_counter[w]
        bar = '#' * (c * 40 // max(width_counter.values()))
        print(f"  w={w:2d}: {c:5d} ({100*c/total:5.1f}%)  {bar}")
    print(f"  Total: {total}")

    # ---- 2. Compiler directives ----
    dir_glyphs = [g for g in all_glyphs if g['opcode'] != 0]
    print(f"\n{'=' * 60}")
    print("COMPILER DIRECTIVES")
    print("=" * 60)
    print(f"  Total glyphs with directives: {len(dir_glyphs)}/{total} ({100*len(dir_glyphs)/total:.1f}%)")

    opcode_counter = Counter()
    replace_counts = Counter()
    illegal_count = 0
    for g in dir_glyphs:
        op = g['opcode']
        opcode_counter[op] += 1
        if 0x80 <= op <= 0x87:
            n_replace = op & 0x07
            replace_counts[n_replace] += 1
        if op == 255:
            illegal_count += 1

    if opcode_counter:
        print(f"\n  By opcode:")
        for op in sorted(opcode_counter):
            c = opcode_counter[op]
            if 0x80 <= op <= 0x87:
                label = f'replaceWith (n={op & 0x07})'
            elif op == 255:
                label = 'ILLEGAL (0xFF)'
            else:
                label = f'unknown'
            print(f"    0x{op:02X} ({label}): {c}")

    if replace_counts:
        print(f"\n  replaceWith breakdown:")
        for n in sorted(replace_counts):
            print(f"    {n} replacement char(s): {replace_counts[n]}")

    if illegal_count:
        print(f"  Illegal glyphs: {illegal_count}")

    # ---- 3. Kerning shapes ----
    kern_glyphs = [g for g in all_glyphs if g['has_kern']]
    print(f"\n{'=' * 60}")
    print("KERNING SHAPES")
    print("=" * 60)
    print(f"  Glyphs with kern data: {len(kern_glyphs)}/{total} ({100*len(kern_glyphs)/total:.1f}%)")

    shape_counter = Counter()
    for g in kern_glyphs:
        tag = format_shape(g['kern_mask'], g['is_ytype'])
        shape_counter[tag] += 1

    n_unique = len(shape_counter)
    n_kern = len(kern_glyphs)
    ytype_count = sum(1 for g in kern_glyphs if g['is_ytype'])
    btype_count = n_kern - ytype_count
    print(f"  Unique shapes: {n_unique}")
    print(f"  B-type: {btype_count} ({100*btype_count/n_kern:.1f}%)")
    print(f"  Y-type: {ytype_count} ({100*ytype_count/n_kern:.1f}%)")

    # Per-bit occurrences
    bit_names = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K']
    bit_positions = [7, 6, 5, 4, 3, 2, 1, 0, 15, 14]
    print(f"\n  Per-bit occurrences ({n_kern} glyphs with kern):")
    for name, pos in zip(bit_names, bit_positions):
        c = sum(1 for g in kern_glyphs if (g['kern_mask'] >> pos) & 1)
        bar = '#' * (c * 30 // n_kern)
        print(f"    {name}: {c:5d}/{n_kern} ({100*c/n_kern:5.1f}%)  {bar}")

    print(f"\n  Top shapes (of {n_unique} unique):")
    for tag, c in shape_counter.most_common(30):
        bar = '#' * (c * 30 // shape_counter.most_common(1)[0][1])
        print(f"    {tag:<22s} {c:4d} ({100*c/len(kern_glyphs):5.1f}%)  {bar}")
    if n_unique > 30:
        remaining = sum(c for _, c in shape_counter.most_common()[30:])
        print(f"    ... {n_unique - 30} more shapes: {remaining} glyphs")

    # ---- 4. Lowheight ----
    lowh_glyphs = [g for g in all_glyphs if g['lowheight']]
    print(f"\n{'=' * 60}")
    print("LOWHEIGHT")
    print("=" * 60)
    print(f"  Lowheight glyphs: {len(lowh_glyphs)}/{total} ({100*len(lowh_glyphs)/total:.1f}%)")

    # ---- 5. Diacritics / stacking ----
    anchor_glyphs = [g for g in all_glyphs if g['n_anchors'] > 0]
    wot_glyphs = [g for g in all_glyphs if g['has_write_on_top']]
    stack_names = {0: 'STACK_UP', 1: 'STACK_DOWN', 2: 'STACK_BEFORE_N_AFTER',
                   3: 'STACK_UP_N_DOWN', 4: 'STACK_DONT'}
    stack_counter = Counter(g['stack_where'] for g in all_glyphs if g['stack_where'] != 0)

    print(f"\n{'=' * 60}")
    print("DIACRITICS & STACKING")
    print("=" * 60)
    print(f"  Glyphs with diacritics anchors: {len(anchor_glyphs)}/{total} ({100*len(anchor_glyphs)/total:.1f}%)")
    anchor_count_dist = Counter(g['n_anchors'] for g in anchor_glyphs)
    for n in sorted(anchor_count_dist):
        print(f"    {n} anchor(s): {anchor_count_dist[n]}")
    print(f"  Glyphs with writeOnTop: {len(wot_glyphs)}")
    if stack_counter:
        print(f"  Stack modes:")
        for sw, c in stack_counter.most_common():
            print(f"    {stack_names.get(sw, f'?{sw}')}: {c}")

    # ---- 6. Dot removal ----
    dot_glyphs = [g for g in all_glyphs if g['has_dot_removal']]
    print(f"\n{'=' * 60}")
    print("DOT REMOVAL")
    print("=" * 60)
    print(f"  Glyphs with dot removal directive: {len(dot_glyphs)}/{total} ({100*len(dot_glyphs)/total:.1f}%)")

    # ---- 7. Nudge ----
    nudge_glyphs = [g for g in all_glyphs if g['has_nudge']]
    print(f"\n{'=' * 60}")
    print("NUDGE")
    print("=" * 60)
    print(f"  Glyphs with nudge: {len(nudge_glyphs)}/{total} ({100*len(nudge_glyphs)/total:.1f}%)")
    if nudge_glyphs:
        nudge_x_vals = Counter(g['nudge_x'] for g in nudge_glyphs if g['nudge_x'] != 0)
        nudge_y_vals = Counter(g['nudge_y'] for g in nudge_glyphs if g['nudge_y'] != 0)
        if nudge_x_vals:
            print(f"  X nudge values: {dict(sorted(nudge_x_vals.items()))}")
        if nudge_y_vals:
            print(f"  Y nudge values: {dict(sorted(nudge_y_vals.items()))}")

    # ---- 8. Alignment ----
    align_names = {0: 'LEFT', 1: 'RIGHT', 2: 'CENTRE', 3: 'BEFORE'}
    align_counter = Counter(g['align'] for g in all_glyphs if g['align'] != 0)
    print(f"\n{'=' * 60}")
    print("ALIGNMENT")
    print("=" * 60)
    if align_counter:
        for a, c in align_counter.most_common():
            print(f"  {align_names.get(a, f'?{a}')}: {c}")
    else:
        print("  All glyphs use default (LEFT) alignment")

    # ---- 9. Missing kern data ----
    missing = [g for g in all_glyphs
               if not g['has_kern']
               and g['opcode'] == 0
               and not is_excluded_from_kern(g['code'])]
    print(f"\n{'=' * 60}")
    print("MISSING KERNING DATA")
    print("=" * 60)
    print(f"  Glyphs without kern (excl. CJK/Hangul/symbols/diacriticals): "
          f"{len(missing)}/{total} ({100*len(missing)/total:.1f}%)")
    if missing:
        by_block = defaultdict(list)
        for g in missing:
            by_block[unicode_block_name(g['code'])].append(g['code'])
        print(f"\n  By block:")
        for block in sorted(by_block, key=lambda b: by_block[b][0]):
            cps = by_block[block]
            sample = ', '.join(f'U+{c:04X}' for c in cps[:8])
            more = f' ... +{len(cps)-8}' if len(cps) > 8 else ''
            print(f"    {block}: {len(cps)}  ({sample}{more})")

    # ---- 10. Per-sheet summary ----
    print(f"\n{'=' * 60}")
    print("PER-SHEET SUMMARY")
    print("=" * 60)
    print(f"  {'Sheet':<52s} {'Total':>5s} {'Kern':>5s} {'LowH':>5s} {'Dir':>4s}")
    print(f"  {'-'*52} {'-'*5} {'-'*5} {'-'*5} {'-'*4}")
    for fn in sorted(per_sheet):
        s = per_sheet[fn]
        print(f"  {fn:<52s} {s['total']:5d} {s['kern']:5d} {s['lowh']:5d} {s['directives']:4d}")

    return 0


if __name__ == '__main__':
    sys.exit(main())
