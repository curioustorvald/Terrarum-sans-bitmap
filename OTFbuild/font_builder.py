"""
Orchestrate fonttools TTFont assembly.

1. Parse all sheets -> glyphs dict
2. Compose Hangul -> add to dict
3. Create glyph order and cmap
4. Trace all bitmaps -> glyf table
5. Set hmtx, hhea, OS/2, head, name, post
6. Generate and compile OpenType features via feaLib
7. Add EBDT/EBLC bitmap strike at ppem=20
8. Save TTF
"""

import time
from typing import Dict

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.feaLib.builder import addOpenTypeFeatures
from fontTools.ttLib import TTFont
import io

from glyph_parser import ExtractedGlyph, parse_all_sheets
from hangul import compose_hangul
from bitmap_tracer import trace_bitmap, draw_glyph_to_pen, SCALE, BASELINE_ROW
from keming_machine import generate_kerning_pairs
from opentype_features import generate_features, glyph_name
import sheet_config as SC


# Codepoints that get cmap entries (user-visible)
# PUA forms used internally by GSUB get glyphs but NO cmap entries
_PUA_CMAP_RANGES = [
    range(0xE000, 0xE100),   # Custom symbols
    range(0xF0520, 0xF0580), # Codestyle ASCII
]


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
    # Internal PUA forms (Devanagari, Tamil, Sundanese, Bulgarian, Serbian internals)
    # These are GSUB-only and should NOT have cmap entries
    if 0xF0000 <= cp <= 0xF051F:
        return False
    # Internal control characters
    if 0xFFE00 <= cp <= 0xFFFFF:
        return False
    return True


def build_font(assets_dir, output_path, no_bitmap=False, no_features=False):
    """Build the complete TTF font."""
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

    # Step 3: Create glyph order and cmap
    print("Step 3: Building glyph order and cmap...")
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

    # Step 4: Build font with fonttools
    print("Step 4: Building font tables...")
    fb = FontBuilder(SC.UNITS_PER_EM, isTTF=True)
    fb.setupGlyphOrder(glyph_order)

    # Build cmap
    fb.setupCharacterMap(cmap)

    # Step 5: Trace bitmaps -> glyf table
    print("Step 5: Tracing bitmaps to outlines...")
    glyph_table = {}

    pen = TTGlyphPen(None)

    # .notdef glyph (empty box)
    pen.moveTo((0, 0))
    pen.lineTo((0, SC.ASCENT))
    pen.lineTo((SC.UNITS_PER_EM // 2, SC.ASCENT))
    pen.lineTo((SC.UNITS_PER_EM // 2, 0))
    pen.closePath()
    # Inner box
    _m = 2 * SCALE
    pen.moveTo((_m, _m))
    pen.lineTo((SC.UNITS_PER_EM // 2 - _m, _m))
    pen.lineTo((SC.UNITS_PER_EM // 2 - _m, SC.ASCENT - _m))
    pen.lineTo((_m, SC.ASCENT - _m))
    pen.closePath()
    glyph_table[".notdef"] = pen.glyph()

    traced_count = 0
    for cp in sorted_cps:
        g = glyphs[cp]
        if g.props.is_illegal:
            continue
        name = glyph_name(cp)
        if name == ".notdef" or name not in glyph_set:
            continue

        contours = trace_bitmap(g.bitmap, g.props.width)

        pen = TTGlyphPen(None)
        if contours:
            draw_glyph_to_pen(contours, pen)
            glyph_table[name] = pen.glyph()
            traced_count += 1
        else:
            # Empty glyph (space, zero-width, etc.)
            pen.moveTo((0, 0))
            pen.endPath()
            glyph_table[name] = pen.glyph()

    print(f"  Traced {traced_count} glyphs with outlines")

    fb.setupGlyf(glyph_table)

    # Step 6: Set metrics
    print("Step 6: Setting font metrics...")
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
        metrics[name] = (advance, 0)  # (advance_width, lsb)

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
        fsType=0,  # Installable embedding
    )

    fb.setupPost()
    fb.setupHead(unitsPerEm=SC.UNITS_PER_EM)

    font = fb.font

    # Step 7: Generate and compile OpenType features
    if not no_features:
        print("Step 7: Generating OpenType features...")
        kern_pairs = generate_kerning_pairs(glyphs)
        print(f"  {len(kern_pairs)} kerning pairs")

        fea_code = generate_features(glyphs, kern_pairs, glyph_set)

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
        print("Step 7: Skipping OpenType features (--no-features)")

    # Step 8: Add bitmap strike (EBDT/EBLC)
    if not no_bitmap:
        print("Step 8: Adding bitmap strike...")
        _add_bitmap_strike(font, glyphs, glyph_order, glyph_set)
    else:
        print("Step 8: Skipping bitmap strike (--no-bitmap)")

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

    # Collect bitmap data — only glyphs with actual pixels
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

        # Pack rows into hex
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

    # Split into contiguous GID runs for separate index subtables
    # This avoids the empty-name problem for gaps
    gid_sorted = sorted(bitmap_entries, key=lambda e: e['gid'])
    gid_to_entry = {e['gid']: e for e in gid_sorted}

    runs = []  # list of lists of entries
    current_run = [gid_sorted[0]]
    for i in range(1, len(gid_sorted)):
        if gid_sorted[i]['gid'] == gid_sorted[i-1]['gid'] + 1:
            current_run.append(gid_sorted[i])
        else:
            runs.append(current_run)
            current_run = [gid_sorted[i]]
    runs.append(current_run)

    # Build TTX XML for EBDT
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

    # Build TTX XML for EBLC
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

    # One index subtable per contiguous run — no gaps
    # Use format 1 (32-bit offsets) to avoid 16-bit overflow
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
