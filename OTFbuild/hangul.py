"""
Compose 11,172 Hangul syllables (U+AC00-U+D7A3) from jamo sprite pieces.
Also composes Hangul Compatibility Jamo (U+3130-U+318F).
Also stores all jamo variant bitmaps in PUA for GSUB-based jamo assembly.

Ported from HangulCompositor.kt and TerrarumSansBitmap.kt.
"""

from typing import Dict, List, Tuple

from glyph_parser import (
    ExtractedGlyph, GlyphProps, get_hangul_jamo_bitmaps,
    extract_hangul_jamo_variants, _read_hangul_cell, _empty_bitmap,
)
import sheet_config as SC

# PUA range for Hangul jamo variant storage.
# We need space for: max_col * max_row variants.
# Using 0xF0600-0xF0FFF (2560 slots, more than enough).
HANGUL_PUA_BASE = 0xF0600


def _compose_bitmaps(a, b, w, h):
    """OR two bitmaps together."""
    result = []
    for row in range(h):
        row_data = []
        for col in range(w):
            av = a[row][col] if row < len(a) and col < len(a[row]) else 0
            bv = b[row][col] if row < len(b) and col < len(b[row]) else 0
            row_data.append(1 if av or bv else 0)
        result.append(row_data)
    return result


def _compose_bitmap_into(target, source, w, h):
    """OR source bitmap into target (mutates target)."""
    for row in range(min(h, len(target), len(source))):
        for col in range(min(w, len(target[row]), len(source[row]))):
            if source[row][col]:
                target[row][col] = 1


def _pua_for_jamo_variant(col, row):
    """Get PUA codepoint for a jamo variant at (column, row) in the sheet."""
    # Encode as base + row * 256 + col (supports up to 256 columns per row)
    return HANGUL_PUA_BASE + row * 256 + col


def compose_hangul(assets_dir) -> Dict[int, ExtractedGlyph]:
    """
    Compose all Hangul syllables, compatibility jamo, and jamo variants.
    Returns a dict of codepoint -> ExtractedGlyph.
    """
    get_jamo = get_hangul_jamo_bitmaps(assets_dir)
    cell_w = SC.W_HANGUL_BASE
    cell_h = SC.H
    result = {}

    # Compose Hangul Compatibility Jamo (U+3130-U+318F)
    for c in range(0x3130, 0x3190):
        index = c - 0x3130
        bitmap = get_jamo(index, 0)
        props = GlyphProps(width=cell_w)
        result[c] = ExtractedGlyph(c, props, bitmap)

    # Compose 11,172 Hangul syllables (U+AC00-U+D7A3)
    print("  Composing 11,172 Hangul syllables...")
    for c in range(0xAC00, 0xD7A4):
        c_int = c - 0xAC00
        index_cho = c_int // (SC.JUNG_COUNT * SC.JONG_COUNT)
        index_jung = c_int // SC.JONG_COUNT % SC.JUNG_COUNT
        index_jong = c_int % SC.JONG_COUNT  # 0 = no jongseong

        # Map to jamo codepoints
        cho_cp = 0x1100 + index_cho
        jung_cp = 0x1161 + index_jung
        jong_cp = 0x11A8 + index_jong - 1 if index_jong > 0 else 0

        # Get sheet indices
        i_cho = SC.to_hangul_choseong_index(cho_cp)
        i_jung = SC.to_hangul_jungseong_index(jung_cp)
        if i_jung is None:
            i_jung = 0
        i_jong = 0
        if jong_cp != 0:
            idx = SC.to_hangul_jongseong_index(jong_cp)
            if idx is not None:
                i_jong = idx

        # Get row positions
        cho_row = SC.get_han_initial_row(i_cho, i_jung, i_jong)
        jung_row = SC.get_han_medial_row(i_cho, i_jung, i_jong)
        jong_row = SC.get_han_final_row(i_cho, i_jung, i_jong)

        # Get jamo bitmaps
        cho_bitmap = get_jamo(i_cho, cho_row)
        jung_bitmap = get_jamo(i_jung, jung_row)

        # Compose
        composed = _compose_bitmaps(cho_bitmap, jung_bitmap, cell_w, cell_h)
        if index_jong > 0:
            jong_bitmap = get_jamo(i_jong, jong_row)
            _compose_bitmap_into(composed, jong_bitmap, cell_w, cell_h)

        # Determine advance width
        advance_width = cell_w + 1 if i_jung in SC.HANGUL_PEAKS_WITH_EXTRA_WIDTH else cell_w

        props = GlyphProps(width=advance_width)
        result[c] = ExtractedGlyph(c, props, composed)

    print(f"  Hangul syllable composition done: {len(result)} glyphs")

    # Store jamo variant bitmaps in PUA for GSUB assembly
    print("  Extracting jamo variants for GSUB...")
    variants = extract_hangul_jamo_variants(assets_dir)
    variant_count = 0
    for (col, row), bm in variants.items():
        pua = _pua_for_jamo_variant(col, row)
        if pua not in result:
            result[pua] = ExtractedGlyph(pua, GlyphProps(width=cell_w), bm)
            variant_count += 1

    print(f"  Stored {variant_count} jamo variant glyphs in PUA (0x{HANGUL_PUA_BASE:05X}+)")
    print(f"  Total Hangul glyphs: {len(result)}")
    return result


def get_jamo_gsub_data():
    """
    Generate the data needed for Hangul jamo GSUB lookups.

    Returns a dict with:
      - 'cho_rows': dict mapping (i_jung, has_jong) -> row for choseong
      - 'jung_rows': dict mapping has_jong -> row for jungseong
      - 'jong_rows': dict mapping is_rightie -> row for jongseong
      - 'pua_fn': function(col, row) -> PUA codepoint

    These are the row-selection rules from the Kotlin code:
      Choseong row = getHanInitialRow(i_cho, i_jung, i_jong)
      Jungseong row = 15 if no final, else 16
      Jongseong row = 17 if jungseong is not rightie, else 18
    """
    return {
        'pua_fn': _pua_for_jamo_variant,
        'pua_base': HANGUL_PUA_BASE,
    }
