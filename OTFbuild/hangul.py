"""
Compose 11,172 Hangul syllables (U+AC00-U+D7A3) from jamo sprite pieces.
Also composes Hangul Compatibility Jamo (U+3130-U+318F).

Ported from HangulCompositor.kt and TerrarumSansBitmap.kt.
"""

from typing import Dict, List, Tuple

from glyph_parser import ExtractedGlyph, GlyphProps, get_hangul_jamo_bitmaps
import sheet_config as SC


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


def compose_hangul(assets_dir) -> Dict[int, ExtractedGlyph]:
    """
    Compose all Hangul syllables and compatibility jamo.
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

    print(f"  Hangul composition done: {len(result)} glyphs")
    return result
