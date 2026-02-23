"""
Orchestrate fonttools TTFont assembly.

1. Parse all sheets -> glyphs dict
2. Compose Hangul -> add to dict
3. Expand replacewith directives
4. Create glyph order and cmap
5. Trace all bitmaps -> CFF charstrings
6. Set hmtx, hhea, OS/2, head, name, post
7. Generate and compile OpenType features via feaLib
8. Add EBDT/EBLC bitmap strike at ppem=20
9. Save OTF
"""

import time
from typing import Dict

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.feaLib.builder import addOpenTypeFeatures
from fontTools.ttLib import TTFont
import io

from glyph_parser import ExtractedGlyph, GlyphProps, parse_all_sheets
from hangul import compose_hangul, get_jamo_gsub_data, HANGUL_PUA_BASE
from bitmap_tracer import trace_bitmap, draw_glyph_to_pen, SCALE, BASELINE_ROW
from keming_machine import generate_kerning_pairs
from opentype_features import generate_features, glyph_name
import sheet_config as SC


# Codepoints that get cmap entries (user-visible)
# PUA forms used internally by GSUB get glyphs but NO cmap entries
def _should_have_cmap(cp):
    """Determine if a codepoint should have a cmap entry."""
    # Standard Unicode characters always get cmap entries
    if cp < 0xE000:
        return True
    # Custom sym PUA range
    if 0xE000 <= cp <= 0xE0FF:
        return True
    # Codestyle PUA
    if 0xF0520 <= cp <= 0xF057F:
        return True
    # Hangul syllables
    if 0xAC00 <= cp <= 0xD7A3:
        return True
    # Hangul compat jamo
    if 0x3130 <= cp <= 0x318F:
        return True
    # SMP characters (Enclosed Alphanumeric Supplement, Hentaigana, etc.)
    if 0x1F100 <= cp <= 0x1F1FF:
        return True
    if 0x1B000 <= cp <= 0x1B16F:
        return True
    # Everything in standard Unicode ranges (up to 0xFFFF plus SMP)
    if cp <= 0xFFFF:
        return True
    # Internal PUA forms — GSUB-only, no cmap
    if 0xF0000 <= cp <= 0xF0FFF:
        return False
    # Internal control characters
    if 0xFFE00 <= cp <= 0xFFFFF:
        return False
    return True


def _expand_replacewith(glyphs):
    """
    Find glyphs with 'replacewith' directive and generate GSUB multiple
    substitution data. Returns list of (source_cp, [target_cp, ...]).

    A replacewith glyph's extInfo contains up to 7 codepoints that the
    glyph expands to (e.g. U+01C7 "LJ" → [0x4C, 0x4A]).
    """
    replacements = []
    for cp, g in glyphs.items():
        if g.props.is_pragma("replacewith"):
            targets = []
            count = g.props.required_ext_info_count()
            for i in range(count):
                val = g.props.ext_info[i]
                if val != 0:
                    targets.append(val)
            if targets:
                replacements.append((cp, targets))
    return replacements


def build_font(assets_dir, output_path, no_bitmap=False, no_features=False):
    """Build the complete OTF font."""
    t0 = time.time()

    # Step 1: Parse all sheets
    print("Step 1: Parsing glyph sheets...")
    glyphs = parse_all_sheets(assets_dir)
    print(f"  Parsed {len(glyphs)} glyphs from sheets")

    # Step 2: Compose Hangul
    print("Step 2: Composing Hangul syllables...")
    hangul_glyphs = compose_hangul(assets_dir)
    glyphs.update(hangul_glyphs)
    print(f"  Total glyphs after Hangul: {len(glyphs)}")

    # Step 2b: Copy PUA consonant glyphs to Unicode positions
    # In the bitmap font, consonants U+0915-0939 have width=0 and empty bitmaps
    # because the engine normalises them to PUA forms (0xF0140+) before rendering.
    # For OTF, we need the Unicode positions to have actual outlines so that
    # consonants render even without GSUB shaping.
    print("Step 2b: Populating Devanagari consonant glyphs from PUA forms...")
    deva_copied = 0
    for uni_cp in range(0x0915, 0x093A):
        try:
            pua_cp = SC.to_deva_internal(uni_cp)
        except ValueError:
            continue
        if pua_cp in glyphs and uni_cp in glyphs:
            pua_g = glyphs[pua_cp]
            uni_g = glyphs[uni_cp]
            if uni_g.props.width == 0 and pua_g.props.width > 0:
                uni_g.props.width = pua_g.props.width
                uni_g.bitmap = pua_g.bitmap
                deva_copied += 1
    # Also copy nukta consonant forms U+0958-095F
    for uni_cp in range(0x0958, 0x0960):
        try:
            pua_cp = SC.to_deva_internal(uni_cp)
        except ValueError:
            continue
        if pua_cp in glyphs and uni_cp in glyphs:
            pua_g = glyphs[pua_cp]
            uni_g = glyphs[uni_cp]
            if uni_g.props.width == 0 and pua_g.props.width > 0:
                uni_g.props.width = pua_g.props.width
                uni_g.bitmap = pua_g.bitmap
                deva_copied += 1
    print(f"  Copied {deva_copied} consonant glyphs from PUA forms")

    # Step 3: Expand replacewith directives
    print("Step 3: Processing replacewith directives...")
    replacewith_subs = _expand_replacewith(glyphs)
    print(f"  Found {len(replacewith_subs)} replacewith substitutions")

    # Step 3b: Compose fallback bitmaps for replacewith glyphs
    # Glyphs with replacewith directives have width=0 and no bitmap; they
    # rely on GSUB ccmp to expand into their target sequence.  Renderers
    # without GSUB support would show whitespace.  Build a composite
    # bitmap by concatenating the target glyphs' bitmaps side by side.
    print("Step 3b: Composing fallback bitmaps for replacewith glyphs...")
    composed = 0
    for src_cp, target_cps in replacewith_subs:
        src_g = glyphs.get(src_cp)
        if src_g is None or src_g.props.width > 0:
            continue  # already has content (e.g. Deva consonants fixed above)
        # Resolve target glyphs
        target_gs = [glyphs.get(t) for t in target_cps]
        if not all(target_gs):
            continue
        # Compute total advance and composite height
        total_width = sum(g.props.width for g in target_gs)
        if total_width == 0:
            continue
        bm_height = max((len(g.bitmap) for g in target_gs if g.bitmap), default=SC.H)
        # Build composite bitmap
        composite = [[0] * total_width for _ in range(bm_height)]
        x = 0
        for tg in target_gs:
            if not tg.bitmap:
                x += tg.props.width
                continue
            cols = min(tg.props.width, len(tg.bitmap[0])) if tg.props.width > 0 else len(tg.bitmap[0])
            for row in range(min(len(tg.bitmap), bm_height)):
                for col in range(cols):
                    dst_col = x + col
                    if dst_col < total_width and tg.bitmap[row][col]:
                        composite[row][dst_col] = 1
            if tg.props.width > 0:
                x += tg.props.width
            # Zero-width targets (combining marks) overlay at current position
        src_g.props.width = total_width
        src_g.bitmap = composite
        composed += 1
    print(f"  Composed {composed} fallback bitmaps")

    # Step 4: Create glyph order and cmap
    print("Step 4: Building glyph order and cmap...")
    glyph_order = [".notdef"]
    cmap = {}
    glyph_set = set()

    # Sort codepoints for deterministic output
    sorted_cps = sorted(glyphs.keys())

    for cp in sorted_cps:
        g = glyphs[cp]
        if g.props.is_illegal:
            continue
        name = glyph_name(cp)
        if name == ".notdef":
            continue
        if name in glyph_set:
            continue
        glyph_order.append(name)
        glyph_set.add(name)
        if _should_have_cmap(cp):
            cmap[cp] = name

    print(f"  Glyph order: {len(glyph_order)} glyphs, cmap: {len(cmap)} entries")

    # Step 5: Build font with fonttools (CFF/OTF)
    print("Step 5: Building font tables...")
    fb = FontBuilder(SC.UNITS_PER_EM, isTTF=False)
    fb.setupGlyphOrder(glyph_order)
    fb.setupCharacterMap(cmap)

    # Step 6: Trace bitmaps -> CFF charstrings
    print("Step 6: Tracing bitmaps to CFF outlines...")

    charstrings = {}

    # .notdef glyph (empty box)
    pen = T2CharStringPen(SC.UNITS_PER_EM // 2, None)
    pen.moveTo((0, 0))
    pen.lineTo((0, SC.ASCENT))
    pen.lineTo((SC.UNITS_PER_EM // 2, SC.ASCENT))
    pen.lineTo((SC.UNITS_PER_EM // 2, 0))
    pen.closePath()
    _m = 2 * SCALE
    pen.moveTo((_m, _m))
    pen.lineTo((SC.UNITS_PER_EM // 2 - _m, _m))
    pen.lineTo((SC.UNITS_PER_EM // 2 - _m, SC.ASCENT - _m))
    pen.lineTo((_m, SC.ASCENT - _m))
    pen.closePath()
    charstrings[".notdef"] = pen.getCharString()

    traced_count = 0
    for cp in sorted_cps:
        g = glyphs[cp]
        if g.props.is_illegal:
            continue
        name = glyph_name(cp)
        if name == ".notdef" or name not in glyph_set:
            continue

        advance = g.props.width * SCALE

        # Compute alignment offset (lsb shift).
        # The Kotlin code draws the full cell at an offset position:
        #   ALIGN_LEFT: offset = 0
        #   ALIGN_RIGHT: offset = width - W_VAR_INIT (negative)
        #   ALIGN_CENTRE: offset = ceil((width - W_VAR_INIT) / 2) (negative)
        #   ALIGN_BEFORE: offset = 0
        # The bitmap cell width depends on the sheet type.
        import math
        bm_cols = len(g.bitmap[0]) if g.bitmap and g.bitmap[0] else 0
        if g.props.align_where == SC.ALIGN_RIGHT:
            x_offset = (g.props.width - bm_cols) * SCALE
        elif g.props.align_where == SC.ALIGN_CENTRE:
            x_offset = math.ceil((g.props.width - bm_cols) / 2) * SCALE
        else:
            x_offset = 0

        contours = trace_bitmap(g.bitmap, g.props.width)

        pen = T2CharStringPen(advance, None)
        if contours:
            draw_glyph_to_pen(contours, pen, x_offset=x_offset)
            traced_count += 1
        charstrings[name] = pen.getCharString()

    print(f"  Traced {traced_count} glyphs with outlines")

    fb.setupCFF(
        psName="TerrarumSansBitmap-Regular",
        fontInfo={},
        charStringsDict=charstrings,
        privateDict={},
    )

    # Step 7: Set metrics
    print("Step 7: Setting font metrics...")
    metrics = {}
    metrics[".notdef"] = (SC.UNITS_PER_EM // 2, 0)

    for cp in sorted_cps:
        g = glyphs[cp]
        if g.props.is_illegal:
            continue
        name = glyph_name(cp)
        if name == ".notdef" or name not in glyph_set:
            continue
        advance = g.props.width * SCALE
        metrics[name] = (advance, 0)

    fb.setupHorizontalMetrics(metrics)
    fb.setupHorizontalHeader(
        ascent=SC.ASCENT,
        descent=-SC.DESCENT
    )

    fb.setupNameTable({
        "familyName": "Terrarum Sans Bitmap",
        "styleName": "Regular",
    })

    fb.setupOS2(
        sTypoAscender=SC.ASCENT,
        sTypoDescender=-SC.DESCENT,
        sTypoLineGap=SC.LINE_GAP,
        usWinAscent=SC.ASCENT,
        usWinDescent=SC.DESCENT,
        sxHeight=SC.X_HEIGHT,
        sCapHeight=SC.CAP_HEIGHT,
        fsType=0,
    )

    fb.setupPost()
    fb.setupHead(unitsPerEm=SC.UNITS_PER_EM)

    font = fb.font

    # Step 8: Generate and compile OpenType features
    if not no_features:
        print("Step 8: Generating OpenType features...")
        kern_pairs = generate_kerning_pairs(glyphs)
        print(f"  {len(kern_pairs)} kerning pairs")

        jamo_data = get_jamo_gsub_data()
        fea_code = generate_features(glyphs, kern_pairs, glyph_set,
                                     replacewith_subs=replacewith_subs,
                                     jamo_data=jamo_data)

        if fea_code.strip():
            print("  Compiling features with feaLib...")
            try:
                fea_stream = io.StringIO(fea_code)
                addOpenTypeFeatures(font, fea_stream)
                print("  Features compiled successfully")
            except Exception as e:
                print(f"  [WARNING] Feature compilation failed: {e}")
                print("  Continuing without OpenType features")
        else:
            print("  No features to compile")
    else:
        print("Step 8: Skipping OpenType features (--no-features)")

    # Step 9: Add bitmap strike (EBDT/EBLC)
    if not no_bitmap:
        print("Step 9: Adding bitmap strike...")
        _add_bitmap_strike(font, glyphs, glyph_order, glyph_set)
    else:
        print("Step 9: Skipping bitmap strike (--no-bitmap)")

    # Save
    print(f"Saving to {output_path}...")
    font.save(output_path)

    elapsed = time.time() - t0
    print(f"Done! Built {len(glyph_order)} glyphs in {elapsed:.1f}s")
    print(f"Output: {output_path}")


def _add_bitmap_strike(font, glyphs, glyph_order, glyph_set):
    """Add EBDT/EBLC embedded bitmap strike at ppem=20 via TTX roundtrip."""
    import tempfile
    import os as _os

    ppem = 20
    name_to_id = {name: idx for idx, name in enumerate(glyph_order)}

    bitmap_entries = []
    for name in glyph_order:
        if name == ".notdef":
            continue
        cp = _name_to_cp(name)
        if cp is None or cp not in glyphs:
            continue
        g = glyphs[cp]
        if g.props.is_illegal or g.props.width == 0:
            continue

        bitmap = g.bitmap
        h = len(bitmap)
        w = len(bitmap[0]) if h > 0 else 0
        if w == 0 or h == 0:
            continue

        hex_rows = []
        for row in bitmap:
            row_bytes = bytearray()
            for col_start in range(0, w, 8):
                byte_val = 0
                for bit in range(8):
                    col = col_start + bit
                    if col < w and row[col]:
                        byte_val |= (0x80 >> bit)
                row_bytes.append(byte_val)
            hex_rows.append(row_bytes.hex())

        bitmap_entries.append({
            'name': name,
            'gid': name_to_id.get(name, 0),
            'height': h,
            'width': w,
            'advance': g.props.width,
            'hex_rows': hex_rows,
        })

    if not bitmap_entries:
        print("  No bitmap data to embed")
        return

    gid_sorted = sorted(bitmap_entries, key=lambda e: e['gid'])

    runs = []
    current_run = [gid_sorted[0]]
    for i in range(1, len(gid_sorted)):
        if gid_sorted[i]['gid'] == gid_sorted[i-1]['gid'] + 1:
            current_run.append(gid_sorted[i])
        else:
            runs.append(current_run)
            current_run = [gid_sorted[i]]
    runs.append(current_run)

    ebdt_xml = ['<EBDT>', '<header version="2.0"/>', '<strikedata index="0">']
    for entry in gid_sorted:
        ebdt_xml.append(f'  <cbdt_bitmap_format_1 name="{entry["name"]}">')
        ebdt_xml.append(f'    <SmallGlyphMetrics>')
        ebdt_xml.append(f'      <height value="{entry["height"]}"/>')
        ebdt_xml.append(f'      <width value="{entry["width"]}"/>')
        ebdt_xml.append(f'      <BearingX value="0"/>')
        ebdt_xml.append(f'      <BearingY value="{BASELINE_ROW}"/>')
        ebdt_xml.append(f'      <Advance value="{entry["advance"]}"/>')
        ebdt_xml.append(f'    </SmallGlyphMetrics>')
        ebdt_xml.append(f'    <rawimagedata>')
        for hr in entry['hex_rows']:
            ebdt_xml.append(f'      {hr}')
        ebdt_xml.append(f'    </rawimagedata>')
        ebdt_xml.append(f'  </cbdt_bitmap_format_1>')
    ebdt_xml.append('</strikedata>')
    ebdt_xml.append('</EBDT>')

    all_gids = [e['gid'] for e in gid_sorted]
    desc = -(SC.H - BASELINE_ROW)

    def _line_metrics_xml(direction, caret_num=1):
        return [
            f'    <sbitLineMetrics direction="{direction}">',
            f'      <ascender value="{BASELINE_ROW}"/>',
            f'      <descender value="{desc}"/>',
            f'      <widthMax value="{SC.W_WIDEVAR_INIT}"/>',
            f'      <caretSlopeNumerator value="{caret_num}"/>',
            '      <caretSlopeDenominator value="0"/>',
            '      <caretOffset value="0"/>',
            '      <minOriginSB value="0"/>',
            '      <minAdvanceSB value="0"/>',
            f'      <maxBeforeBL value="{BASELINE_ROW}"/>',
            f'      <minAfterBL value="{desc}"/>',
            '      <pad1 value="0"/>',
            '      <pad2 value="0"/>',
            f'    </sbitLineMetrics>',
        ]

    eblc_xml = [
        '<EBLC>', '<header version="2.0"/>',
        '<strike index="0">', '  <bitmapSizeTable>',
        '    <colorRef value="0"/>',
    ]
    eblc_xml.extend(_line_metrics_xml("hori", 1))
    eblc_xml.extend(_line_metrics_xml("vert", 0))
    eblc_xml.extend([
        f'    <startGlyphIndex value="{all_gids[0]}"/>',
        f'    <endGlyphIndex value="{all_gids[-1]}"/>',
        f'    <ppemX value="{ppem}"/>',
        f'    <ppemY value="{ppem}"/>',
        '    <bitDepth value="1"/>',
        '    <flags value="1"/>',
        '  </bitmapSizeTable>',
    ])

    for run in runs:
        first_gid = run[0]['gid']
        last_gid = run[-1]['gid']
        eblc_xml.append(f'  <eblc_index_sub_table_1 imageFormat="1" firstGlyphIndex="{first_gid}" lastGlyphIndex="{last_gid}">')
        for entry in run:
            eblc_xml.append(f'    <glyphLoc name="{entry["name"]}"/>')
        eblc_xml.append('  </eblc_index_sub_table_1>')

    eblc_xml.append('</strike>')
    eblc_xml.append('</EBLC>')

    try:
        ttx_content = '<?xml version="1.0" encoding="UTF-8"?>\n<ttFont>\n'
        ttx_content += '\n'.join(ebdt_xml) + '\n'
        ttx_content += '\n'.join(eblc_xml) + '\n'
        ttx_content += '</ttFont>\n'

        with tempfile.NamedTemporaryFile(mode='w', suffix='.ttx', delete=False) as f:
            f.write(ttx_content)
            ttx_path = f.name

        font.importXML(ttx_path)
        _os.unlink(ttx_path)

        print(f"  Added bitmap strike at {ppem}ppem with {len(bitmap_entries)} glyphs ({len(runs)} index subtables)")
    except Exception as e:
        print(f"  [WARNING] Bitmap strike failed: {e}")
        print("  Continuing without bitmap strike")


def _name_to_cp(name):
    """Convert glyph name back to codepoint."""
    if name == ".notdef":
        return None
    if name == "space":
        return 0x20
    if name.startswith("uni"):
        try:
            return int(name[3:], 16)
        except ValueError:
            return None
    if name.startswith("u"):
        try:
            return int(name[1:], 16)
        except ValueError:
            return None
    return None
