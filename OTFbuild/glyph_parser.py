"""
Extract glyph bitmaps and tag-column properties from TGA sprite sheets.
Ported from TerrarumSansBitmap.kt:buildWidthTable() and GlyphSheetParser.kt.

Enhancement over v1: extracts all 6 diacritics anchors for GPOS mark feature.
"""

import os
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from tga_reader import TgaImage, read_tga
import sheet_config as SC


@dataclass
class DiacriticsAnchor:
    type: int
    x: int
    y: int
    x_used: bool
    y_used: bool


@dataclass
class GlyphProps:
    width: int
    is_low_height: bool = False
    nudge_x: int = 0
    nudge_y: int = 0
    diacritics_anchors: List[DiacriticsAnchor] = field(default_factory=lambda: [
        DiacriticsAnchor(i, 0, 0, False, False) for i in range(6)
    ])
    align_where: int = 0
    write_on_top: int = -1
    stack_where: int = 0
    ext_info: List[int] = field(default_factory=lambda: [0] * 15)
    has_kern_data: bool = False
    is_kern_y_type: bool = False
    kerning_mask: int = 255
    directive_opcode: int = 0
    directive_arg1: int = 0
    directive_arg2: int = 0

    @property
    def is_illegal(self):
        return self.directive_opcode == 255

    def required_ext_info_count(self):
        if self.stack_where == SC.STACK_BEFORE_N_AFTER:
            return 2
        if 0b10000_000 <= self.directive_opcode <= 0b10000_111:
            return 7
        return 0

    def is_pragma(self, pragma):
        if pragma == "replacewith":
            return 0b10000_000 <= self.directive_opcode <= 0b10000_111
        return False


@dataclass
class ExtractedGlyph:
    codepoint: int
    props: GlyphProps
    bitmap: List[List[int]]  # [row][col], 0 or 1


def _tagify(pixel):
    """Return 0 if alpha channel is zero, else return the original value."""
    return 0 if (pixel & 0xFF) == 0 else pixel


def _signed_byte(val):
    """Convert unsigned byte to signed."""
    return val - 256 if val >= 128 else val


def _parse_diacritics_anchors(image, code_start_x, code_start_y):
    """Parse 6 diacritics anchors from tag column rows 11-14."""
    anchors = []
    for i in range(6):
        y_pos = 13 - (i // 3) * 2
        shift = (3 - (i % 3)) * 8
        y_pixel = _tagify(image.get_pixel(code_start_x, code_start_y + y_pos))
        x_pixel = _tagify(image.get_pixel(code_start_x, code_start_y + y_pos + 1))
        y_used = ((y_pixel >> shift) & 128) != 0
        x_used = ((x_pixel >> shift) & 128) != 0
        y_val = (y_pixel >> shift) & 127 if y_used else 0
        x_val = (x_pixel >> shift) & 127 if x_used else 0
        anchors.append(DiacriticsAnchor(i, x_val, y_val, x_used, y_used))
    return anchors


def parse_variable_sheet(image, sheet_index, cell_w, cell_h, cols, is_xy_swapped):
    """Parse a variable-width sheet: extract tag column for properties, bitmap for glyph."""
    code_range = SC.CODE_RANGE[sheet_index]
    binary_code_offset = cell_w - 1  # tag column is last pixel column of cell
    result = {}

    for index, code in enumerate(code_range):
        if is_xy_swapped:
            cell_x = (index // cols) * cell_w
            cell_y = (index % cols) * cell_h
        else:
            cell_x = (index % cols) * cell_w
            cell_y = (index // cols) * cell_h

        code_start_x = cell_x + binary_code_offset
        code_start_y = cell_y

        # Width (5 bits)
        width = 0
        for y in range(5):
            if image.get_pixel(code_start_x, code_start_y + y) & 0xFF:
                width |= (1 << y)

        is_low_height = (image.get_pixel(code_start_x, code_start_y + 5) & 0xFF) != 0

        # Kerning data
        kerning_bit1 = _tagify(image.get_pixel(code_start_x, code_start_y + 6))
        # kerning_bit2 and kerning_bit3 are reserved
        is_kern_y_type = (kerning_bit1 & 0x80000000) != 0
        kerning_mask = (kerning_bit1 >> 8) & 0xFFFFFF
        has_kern_data = (kerning_bit1 & 0xFF) != 0
        if not has_kern_data:
            is_kern_y_type = False
            kerning_mask = 255

        # Compiler directives
        compiler_directives = _tagify(image.get_pixel(code_start_x, code_start_y + 9))
        directive_opcode = (compiler_directives >> 24) & 255
        directive_arg1 = (compiler_directives >> 16) & 255
        directive_arg2 = (compiler_directives >> 8) & 255

        # Nudge
        nudging_bits = _tagify(image.get_pixel(code_start_x, code_start_y + 10))
        nudge_x = _signed_byte((nudging_bits >> 24) & 0xFF)
        nudge_y = _signed_byte((nudging_bits >> 16) & 0xFF)

        # Diacritics anchors
        diacritics_anchors = _parse_diacritics_anchors(image, code_start_x, code_start_y)

        # Alignment
        align_where = 0
        for y in range(2):
            if image.get_pixel(code_start_x, code_start_y + y + 15) & 0xFF:
                align_where |= (1 << y)

        # Write on top
        write_on_top_raw = image.get_pixel(code_start_x, code_start_y + 17)  # NO tagify
        if (write_on_top_raw & 0xFF) == 0:
            write_on_top = -1
        else:
            if (write_on_top_raw >> 8) == 0xFFFFFF:
                write_on_top = 0
            else:
                write_on_top = (write_on_top_raw >> 28) & 15

        # Stack where
        stack_where0 = _tagify(image.get_pixel(code_start_x, code_start_y + 18))
        stack_where1 = _tagify(image.get_pixel(code_start_x, code_start_y + 19))
        if stack_where0 == 0x00FF00FF and stack_where1 == 0x00FF00FF:
            stack_where = SC.STACK_DONT
        else:
            stack_where = 0
            for y in range(2):
                if image.get_pixel(code_start_x, code_start_y + y + 18) & 0xFF:
                    stack_where |= (1 << y)

        ext_info = [0] * 15
        props = GlyphProps(
            width=width, is_low_height=is_low_height,
            nudge_x=nudge_x, nudge_y=nudge_y,
            diacritics_anchors=diacritics_anchors,
            align_where=align_where, write_on_top=write_on_top,
            stack_where=stack_where, ext_info=ext_info,
            has_kern_data=has_kern_data, is_kern_y_type=is_kern_y_type,
            kerning_mask=kerning_mask,
            directive_opcode=directive_opcode, directive_arg1=directive_arg1,
            directive_arg2=directive_arg2,
        )

        # Parse extInfo if needed
        ext_count = props.required_ext_info_count()
        if ext_count > 0:
            for x in range(ext_count):
                info = 0
                for y in range(20):
                    if image.get_pixel(cell_x + x, cell_y + y) & 0xFF:
                        info |= (1 << y)
                ext_info[x] = info

        # Extract glyph bitmap: only pixels within the glyph's declared width.
        # The tag column and any padding beyond width must be stripped.
        bitmap_w = min(width, cell_w - 1) if width > 0 else 0
        bitmap = []
        for row in range(cell_h):
            row_data = []
            for col in range(bitmap_w):
                px = image.get_pixel(cell_x + col, cell_y + row)
                row_data.append(1 if (px & 0xFF) != 0 else 0)
            bitmap.append(row_data)

        result[code] = ExtractedGlyph(code, props, bitmap)

    return result


def _read_hangul_cell(image, column, row, cell_w=SC.W_HANGUL_BASE, cell_h=SC.H):
    """Read a single cell from the Hangul johab sheet at (column, row)."""
    cell_x = column * cell_w
    cell_y = row * cell_h
    bitmap = []
    for r in range(cell_h):
        row_data = []
        for c in range(cell_w):
            px = image.get_pixel(cell_x + c, cell_y + r)
            row_data.append(1 if (px & 0xFF) != 0 else 0)
        bitmap.append(row_data)
    return bitmap


def parse_hangul_jamo_sheet(image, cell_w, cell_h):
    """
    Parse the Hangul Jamo sheet with correct row/column mapping.

    Layout in hangul_johab.tga:
      - Choseong (U+1100-U+115E): column = choseongIndex, row = 1
      - Jungseong (U+1161-U+11A7): column = jungseongIndex+1, row = 15
        (column 0 is filler U+1160, stored at row 15 col 0)
      - Jongseong (U+11A8-U+11FF): column = jongseongIndex, row = 17
        (index starts at 1 for 11A8)
      - Extended Choseong (U+A960-U+A97F): column = 96+offset, row = 1
      - Extended Jungseong (U+D7B0-U+D7C6): column = 72+offset, row = 15
      - Extended Jongseong (U+D7CB-U+D7FB): column = 89+offset, row = 17

    Each jamo gets a default-row bitmap. Multiple variant rows exist for
    syllable composition (handled separately by hangul.py / GSUB).
    """
    result = {}

    # U+1160 (Hangul Jungseong Filler) — column 0, row 15
    bm = _read_hangul_cell(image, 0, 15, cell_w, cell_h)
    result[0x1160] = ExtractedGlyph(0x1160, GlyphProps(width=cell_w), bm)

    # Choseong: U+1100-U+115E → column = cp - 0x1100, row = 1
    for cp in range(0x1100, 0x115F):
        col = cp - 0x1100
        bm = _read_hangul_cell(image, col, 1, cell_w, cell_h)
        result[cp] = ExtractedGlyph(cp, GlyphProps(width=cell_w), bm)

    # U+115F (Hangul Choseong Filler)
    col = 0x115F - 0x1100
    bm = _read_hangul_cell(image, col, 1, cell_w, cell_h)
    result[0x115F] = ExtractedGlyph(0x115F, GlyphProps(width=cell_w), bm)

    # Jungseong: U+1161-U+11A7 → column = (cp - 0x1160), row = 15
    for cp in range(0x1161, 0x11A8):
        col = cp - 0x1160
        bm = _read_hangul_cell(image, col, 15, cell_w, cell_h)
        result[cp] = ExtractedGlyph(cp, GlyphProps(width=cell_w), bm)

    # Jongseong: U+11A8-U+11FF → column = (cp - 0x11A8 + 1), row = 17
    for cp in range(0x11A8, 0x1200):
        col = cp - 0x11A8 + 1
        bm = _read_hangul_cell(image, col, 17, cell_w, cell_h)
        result[cp] = ExtractedGlyph(cp, GlyphProps(width=cell_w), bm)

    # Extended Choseong: U+A960-U+A97F → column = (cp - 0xA960 + 96), row = 1
    for cp in range(0xA960, 0xA980):
        col = cp - 0xA960 + 96
        bm = _read_hangul_cell(image, col, 1, cell_w, cell_h)
        result[cp] = ExtractedGlyph(cp, GlyphProps(width=cell_w), bm)

    # Extended Jungseong: U+D7B0-U+D7C6 → column = (cp - 0xD7B0 + 72), row = 15
    for cp in range(0xD7B0, 0xD7C7):
        col = cp - 0xD7B0 + 72
        bm = _read_hangul_cell(image, col, 15, cell_w, cell_h)
        result[cp] = ExtractedGlyph(cp, GlyphProps(width=cell_w), bm)

    # Extended Jongseong: U+D7CB-U+D7FB → column = (cp - 0xD7CB + 88 + 1), row = 17
    for cp in range(0xD7CB, 0xD7FC):
        col = cp - 0xD7CB + 88 + 1
        bm = _read_hangul_cell(image, col, 17, cell_w, cell_h)
        result[cp] = ExtractedGlyph(cp, GlyphProps(width=cell_w), bm)

    return result


def parse_fixed_sheet(image, sheet_index, cell_w, cell_h, cols):
    """Parse a fixed-width sheet (Hangul, Unihan, Runic, Custom Sym)."""
    # Hangul Jamo sheet has special layout — handled separately
    if sheet_index == SC.SHEET_HANGUL:
        return parse_hangul_jamo_sheet(image, cell_w, cell_h)

    code_range = SC.CODE_RANGE[sheet_index]
    result = {}

    fixed_width = {
        SC.SHEET_CUSTOM_SYM: 20,
        SC.SHEET_RUNIC: 9,
        SC.SHEET_UNIHAN: SC.W_UNIHAN,
    }.get(sheet_index, cell_w)

    for index, code in enumerate(code_range):
        cell_x = (index % cols) * cell_w
        cell_y = (index // cols) * cell_h

        bitmap = []
        for row in range(cell_h):
            row_data = []
            for col in range(cell_w):
                px = image.get_pixel(cell_x + col, cell_y + row)
                row_data.append(1 if (px & 0xFF) != 0 else 0)
            bitmap.append(row_data)

        props = GlyphProps(width=fixed_width)
        result[code] = ExtractedGlyph(code, props, bitmap)

    return result


def _empty_bitmap(w=SC.W_VAR_INIT, h=SC.H):
    return [[0] * w for _ in range(h)]


def parse_all_sheets(assets_dir):
    """Parse all sheets and return a map of codepoint -> ExtractedGlyph."""
    result = {}

    for sheet_index, filename in enumerate(SC.FILE_LIST):
        filepath = os.path.join(assets_dir, filename)
        if not os.path.exists(filepath):
            print(f"  [SKIP] {filename} not found")
            continue

        is_var = SC.is_variable(filename)
        is_xy = SC.is_xy_swapped(filename)
        is_ew = SC.is_extra_wide(filename)
        cell_w = SC.get_cell_width(sheet_index)
        cell_h = SC.get_cell_height(sheet_index)
        cols = SC.get_columns(sheet_index)

        tags = []
        if is_var: tags.append("VARIABLE")
        if is_xy: tags.append("XYSWAP")
        if is_ew: tags.append("EXTRAWIDE")
        if not tags: tags.append("STATIC")
        print(f"  Loading [{','.join(tags)}] {filename}")

        image = read_tga(filepath)

        if is_var:
            sheet_glyphs = parse_variable_sheet(image, sheet_index, cell_w, cell_h, cols, is_xy)
        else:
            sheet_glyphs = parse_fixed_sheet(image, sheet_index, cell_w, cell_h, cols)

        result.update(sheet_glyphs)

    # Fixed-width overrides
    _add_fixed_width_overrides(result)

    return result


def _add_fixed_width_overrides(result):
    """Apply fixed-width overrides."""
    # Hangul compat jamo
    for code in SC.CODE_RANGE_HANGUL_COMPAT:
        if code not in result:
            result[code] = ExtractedGlyph(code, GlyphProps(width=SC.W_HANGUL_BASE), _empty_bitmap(SC.W_HANGUL_BASE))

    # Zero-width ranges (only internal/PUA control ranges, not surrogates or full Plane 16)
    for code in range(0xFFFA0, 0x100000):
        result[code] = ExtractedGlyph(code, GlyphProps(width=0), _empty_bitmap(1, 1))

    # Null char
    result[0] = ExtractedGlyph(0, GlyphProps(width=0), _empty_bitmap(1, 1))

    # Replacement character at U+007F
    if 0x7F in result:
        result[0x7F].props.width = 15


def get_hangul_jamo_bitmaps(assets_dir):
    """
    Extract raw Hangul jamo bitmaps from the Hangul sheet for composition.
    Returns a function: (column_index, row) -> bitmap (list of list of int)
    """
    filename = SC.FILE_LIST[SC.SHEET_HANGUL]
    filepath = os.path.join(assets_dir, filename)
    if not os.path.exists(filepath):
        print("  [WARNING] Hangul sheet not found")
        return lambda idx, row: _empty_bitmap(SC.W_HANGUL_BASE)

    image = read_tga(filepath)
    cell_w = SC.W_HANGUL_BASE
    cell_h = SC.H

    def get_bitmap(index, row):
        cell_x = index * cell_w
        cell_y = row * cell_h
        bitmap = []
        for r in range(cell_h):
            row_data = []
            for c in range(cell_w):
                px = image.get_pixel(cell_x + c, cell_y + r)
                row_data.append(1 if (px & 0xFF) != 0 else 0)
            bitmap.append(row_data)
        return bitmap

    return get_bitmap


def extract_hangul_jamo_variants(assets_dir):
    """
    Extract ALL Hangul jamo variant bitmaps from hangul_johab.tga.
    Returns dict of (column, row) -> bitmap for every non-empty cell.
    Used by hangul.py to store variants in PUA for GSUB assembly.

    Layout:
      Row 0: Hangul Compatibility Jamo (U+3130-U+318F)
      Rows 1-14: Choseong variants (row depends on jungseong context)
      Rows 15-16: Jungseong variants (15=no final, 16=with final)
      Rows 17-18: Jongseong variants (17=normal, 18=rightie jungseong)
      Rows 19-24: Additional choseong variants (giyeok remapping)
    """
    filename = SC.FILE_LIST[SC.SHEET_HANGUL]
    filepath = os.path.join(assets_dir, filename)
    if not os.path.exists(filepath):
        return {}

    image = read_tga(filepath)
    cell_w = SC.W_HANGUL_BASE
    cell_h = SC.H

    variants = {}
    # Scan all rows that contain jamo data
    # Rows 0-24 at minimum, checking up to image height
    max_row = image.height // cell_h
    max_col = image.width // cell_w

    for row in range(max_row):
        for col in range(max_col):
            bm = _read_hangul_cell(image, col, row, cell_w, cell_h)
            # Check if non-empty
            if any(px for r in bm for px in r):
                variants[(col, row)] = bm

    return variants
