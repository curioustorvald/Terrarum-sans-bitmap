"""
Generate OpenType feature code (feaLib syntax) for GSUB/GPOS tables.

Features implemented:
- kern: GPOS pair positioning from KemingMachine
- liga: Standard ligatures (Alphabetic Presentation Forms)
- locl: Bulgarian/Serbian Cyrillic variants
- Devanagari GSUB: nukt, akhn (+ conjuncts), half, blwf, cjct, blws, rphf, abvs
- Tamil GSUB: consonant+vowel ligatures, KSSA, SHRII
- Sundanese GSUB: diacritic combinations
- mark: GPOS mark-to-base positioning (diacritics anchors)
"""

import math
from typing import Dict, List, Set, Tuple

from glyph_parser import ExtractedGlyph
import sheet_config as SC


def glyph_name(cp):
    """Generate standard glyph name for a codepoint."""
    if cp == 0:
        return ".notdef"
    if cp == 0x20:
        return "space"
    if cp <= 0xFFFF:
        return f"uni{cp:04X}"
    return f"u{cp:05X}" if cp <= 0xFFFFF else f"u{cp:06X}"


def generate_features(glyphs, kern_pairs, font_glyph_set,
                      replacewith_subs=None, jamo_data=None):
    """
    Generate complete OpenType feature code string.

    Args:
        glyphs: dict of codepoint -> ExtractedGlyph
        kern_pairs: dict of (left_cp, right_cp) -> kern_value_in_font_units
        font_glyph_set: set of glyph names actually present in the font
        replacewith_subs: list of (source_cp, [target_cp, ...]) for ccmp
        jamo_data: dict with Hangul jamo GSUB data
    Returns:
        Feature code string for feaLib compilation.
    """
    parts = []

    def has(cp):
        return glyph_name(cp) in font_glyph_set

    preamble = """\
languagesystem DFLT dflt;
languagesystem latn dflt;
languagesystem cyrl dflt;
languagesystem grek dflt;
languagesystem hang KOR ;
languagesystem hang KOH ;
languagesystem cyrl SRB ;
languagesystem cyrl BGR ;
languagesystem dev2 dflt;
languagesystem deva dflt;
languagesystem tml2 dflt;
languagesystem sund dflt;
"""
    if preamble:
        parts.append(preamble)

    # ccmp feature (replacewith directives + Hangul jamo decomposition)
    ccmp_code = _generate_ccmp(replacewith_subs or [], has)
    if ccmp_code:
        parts.append(ccmp_code)

    # Hangul jamo GSUB assembly
    hangul_code = _generate_hangul_gsub(glyphs, has, jamo_data)
    if hangul_code:
        parts.append(hangul_code)

    # kern feature
    kern_code = _generate_kern(kern_pairs, has)
    if kern_code:
        parts.append(kern_code)

    # liga feature
    liga_code = _generate_liga(has)
    if liga_code:
        parts.append(liga_code)

    # locl feature (Bulgarian/Serbian)
    locl_code = _generate_locl(glyphs, has)
    if locl_code:
        parts.append(locl_code)

    # Devanagari features
    deva_code = _generate_devanagari(glyphs, has, replacewith_subs or [])
    if deva_code:
        parts.append(deva_code)

    # Tamil features
    tamil_code = _generate_tamil(glyphs, has, replacewith_subs or [])
    if tamil_code:
        parts.append(tamil_code)

    # Sundanese features
    sund_code = _generate_sundanese(glyphs, has)
    if sund_code:
        parts.append(sund_code)

    # IPA tone bar graphs
    tone_code = _generate_tone_bars(has)
    if tone_code:
        parts.append(tone_code)

    # mark feature
    mark_code = _generate_mark(glyphs, has)
    if mark_code:
        parts.append(mark_code)

    # Anusvara GPOS (must come AFTER mark so lookups are ordered correctly)
    anus_gpos = _generate_anusvara_gpos(glyphs, has)
    if anus_gpos:
        parts.append(anus_gpos)

    return '\n\n'.join(parts)


def _generate_ccmp(replacewith_subs, has):
    """Generate ccmp feature for replacewith directives (multiple substitution).

    Devanagari (0x0900-097F) and Tamil (0x0B80-0BFF) source codepoints are
    excluded here because their ccmp lookups must live under the script-
    specific tags (dev2, tml2).  DirectWrite and CoreText do not fall back
    from a script-specific ccmp to DFLT.
    """
    if not replacewith_subs:
        return ""

    # Ranges handled by script-specific ccmp features
    _SCRIPT_RANGES = (
        range(0x0900, 0x0980),  # Devanagari → dev2 ccmp
        range(0x0B80, 0x0C00),  # Tamil → tml2 ccmp
    )

    subs = []
    for src_cp, target_cps in replacewith_subs:
        if any(src_cp in r for r in _SCRIPT_RANGES):
            continue
        if not has(src_cp):
            continue
        if not all(has(t) for t in target_cps):
            continue
        src = glyph_name(src_cp)
        targets = ' '.join(glyph_name(t) for t in target_cps)
        subs.append(f"    sub {src} by {targets};")

    if not subs:
        return ""

    lines = ["feature ccmp {", "    lookup ReplacewithExpansion {"]
    lines.extend(subs)
    lines.append("    } ReplacewithExpansion;")
    lines.append("} ccmp;")
    return '\n'.join(lines)


def _generate_hangul_gsub(glyphs, has, jamo_data):
    """
    Generate Hangul jamo GSUB lookups for syllable assembly.

    When a shaping engine encounters consecutive Hangul Jamo (Choseong +
    Jungseong + optional Jongseong), these lookups substitute each jamo
    with the correct positional variant from the PUA area.

    The row selection logic mirrors the Kotlin code:
      - Choseong row depends on which jungseong follows AND whether jongseong
        exists (row increments by 1 when jongseong is present).  Giyeok-class
        choseong get remapped rows when combined with JUNGSEONG_UU.
      - Jungseong row is 15 (no final) or 16 (with final)
      - Jongseong row is 17 (normal) or 18 (after rightie jungseong)
    """
    if not jamo_data:
        return ""

    pua_fn = jamo_data['pua_fn']

    # Build codepoint lists (standard + extended jamo ranges)
    cho_ranges = list(range(0x1100, 0x115F)) + list(range(0xA960, 0xA97D))
    jung_ranges = list(range(0x1160, 0x11A8)) + list(range(0xD7B0, 0xD7C7))
    jong_ranges = list(range(0x11A8, 0x1200)) + list(range(0xD7CB, 0xD7FC))

    cho_cps = [cp for cp in cho_ranges if has(cp)]
    jung_cps = [cp for cp in jung_ranges if has(cp)]
    jong_cps = [cp for cp in jong_ranges if has(cp)]

    if not cho_cps or not jung_cps:
        return ""

    def _jung_idx(cp):
        return SC.to_hangul_jungseong_index(cp)

    def _cho_col(cp):
        return SC.to_hangul_choseong_index(cp)

    def _jong_col(cp):
        return SC.to_hangul_jongseong_index(cp)

    lines = []

    # ----------------------------------------------------------------
    # Step 1: Compute choseong row mapping
    # ----------------------------------------------------------------
    # Group jungseong codepoints by the choseong row they produce.
    # Separate non-giyeok (general) from giyeok (remapped) mappings.
    # Key: (row, has_jong) → [jung_cps]
    jung_groups_general = {}   # for non-giyeok choseong (i=1)
    jung_groups_giyeok = {}    # for giyeok choseong where row differs

    for jcp in jung_cps:
        idx = _jung_idx(jcp)
        if idx is None:
            continue
        for f in [0, 1]:
            try:
                row_ng = SC.get_han_initial_row(1, idx, f)
            except (ValueError, KeyError):
                continue
            jung_groups_general.setdefault((row_ng, f), []).append(jcp)

            # Giyeok choseong get remapped rows for JUNGSEONG_UU
            if idx in SC.JUNGSEONG_UU:
                try:
                    row_g = SC.get_han_initial_row(0, idx, f)
                except (ValueError, KeyError):
                    continue
                if row_g != row_ng:
                    jung_groups_giyeok.setdefault((row_g, f), []).append(jcp)

    # Identify giyeok choseong codepoints
    giyeok_cho_cps = []
    for ccp in cho_cps:
        try:
            col = _cho_col(ccp)
            if col in SC.CHOSEONG_GIYEOKS:
                giyeok_cho_cps.append(ccp)
        except ValueError:
            pass

    # Collect all unique choseong rows
    all_cho_rows = set()
    for (row, _f) in jung_groups_general:
        all_cho_rows.add(row)
    for (row, _f) in jung_groups_giyeok:
        all_cho_rows.add(row)

    # ----------------------------------------------------------------
    # Step 2: Create choseong substitution lookups (one per row)
    # ----------------------------------------------------------------
    cho_lookup_names = {}
    for cho_row in sorted(all_cho_rows):
        lookup_name = f"ljmo_row{cho_row}"
        subs = []
        for ccp in cho_cps:
            try:
                col = _cho_col(ccp)
            except ValueError:
                continue
            variant_pua = pua_fn(col, cho_row)
            if has(variant_pua):
                subs.append(f"    sub {glyph_name(ccp)} by {glyph_name(variant_pua)};")
        if subs:
            lines.append(f"lookup {lookup_name} {{")
            lines.extend(subs)
            lines.append(f"}} {lookup_name};")
            lines.append("")
            cho_lookup_names[cho_row] = lookup_name

    # ----------------------------------------------------------------
    # Step 3: Create jungseong substitution lookups (row 15 and 16)
    # ----------------------------------------------------------------
    vjmo_has = {}
    for jung_row in [15, 16]:
        lookup_name = f"vjmo_row{jung_row}"
        subs = []
        for jcp in jung_cps:
            idx = _jung_idx(jcp)
            if idx is None:
                continue
            variant_pua = pua_fn(idx, jung_row)
            if has(variant_pua):
                subs.append(f"    sub {glyph_name(jcp)} by {glyph_name(variant_pua)};")
        if subs:
            lines.append(f"lookup {lookup_name} {{")
            lines.extend(subs)
            lines.append(f"}} {lookup_name};")
            lines.append("")
            vjmo_has[jung_row] = True

    # ----------------------------------------------------------------
    # Step 4: Create jongseong substitution lookups (row 17 and 18)
    # ----------------------------------------------------------------
    tjmo_has = {}
    for jong_row in [17, 18]:
        lookup_name = f"tjmo_row{jong_row}"
        subs = []
        for jcp in jong_cps:
            col = _jong_col(jcp)
            if col is None:
                continue
            variant_pua = pua_fn(col, jong_row)
            if has(variant_pua):
                subs.append(f"    sub {glyph_name(jcp)} by {glyph_name(variant_pua)};")
        if subs:
            lines.append(f"lookup {lookup_name} {{")
            lines.extend(subs)
            lines.append(f"}} {lookup_name};")
            lines.append("")
            tjmo_has[jong_row] = True

    # ----------------------------------------------------------------
    # Step 5: Generate ljmo feature (choseong contextual substitution)
    # ----------------------------------------------------------------
    feature_lines = []

    if cho_lookup_names:
        feature_lines.append("feature ljmo {")
        feature_lines.append("    script hang;")

        # Define glyph classes
        cho_names = [glyph_name(c) for c in cho_cps]
        feature_lines.append(f"    @cho_all = [{' '.join(cho_names)}];")

        if giyeok_cho_cps:
            giyeok_names = [glyph_name(c) for c in giyeok_cho_cps]
            feature_lines.append(f"    @cho_giyeok = [{' '.join(giyeok_names)}];")

        if jong_cps:
            jong_names = [glyph_name(c) for c in jong_cps]
            feature_lines.append(f"    @jong_all = [{' '.join(jong_names)}];")

        # Define jungseong group classes (unique names)
        cls_idx = [0]

        def _make_jung_class(jcps, prefix):
            name = f"@jung_{prefix}_{cls_idx[0]}"
            cls_idx[0] += 1
            feature_lines.append(f"    {name} = [{' '.join(glyph_name(c) for c in jcps)}];")
            return name

        # Giyeok-specific rules first (most specific: giyeok cho + UU jung)
        # With-jong before without-jong for each group.
        giyeok_rules = []
        for (row, f) in sorted(jung_groups_giyeok.keys()):
            if row not in cho_lookup_names:
                continue
            jcps = jung_groups_giyeok[(row, f)]
            cls_name = _make_jung_class(jcps, "gk")
            giyeok_rules.append((row, f, cls_name))

        # Sort: with-jong (f=1) before without-jong (f=0)
        for row, f, cls_name in sorted(giyeok_rules, key=lambda x: (-x[1], x[0])):
            lookup = cho_lookup_names[row]
            if f == 1 and jong_cps:
                feature_lines.append(
                    f"    sub @cho_giyeok' lookup {lookup} {cls_name} @jong_all;")
            else:
                feature_lines.append(
                    f"    sub @cho_giyeok' lookup {lookup} {cls_name};")

        # General rules: with-jong first, then without-jong
        general_rules = []
        for (row, f) in sorted(jung_groups_general.keys()):
            if row not in cho_lookup_names:
                continue
            jcps = jung_groups_general[(row, f)]
            cls_name = _make_jung_class(jcps, "ng")
            general_rules.append((row, f, cls_name))

        # With-jong rules
        for row, f, cls_name in sorted(general_rules, key=lambda x: x[0]):
            if f != 1:
                continue
            if not jong_cps:
                continue
            lookup = cho_lookup_names[row]
            feature_lines.append(
                f"    sub @cho_all' lookup {lookup} {cls_name} @jong_all;")

        # Without-jong rules (fallback)
        for row, f, cls_name in sorted(general_rules, key=lambda x: x[0]):
            if f != 0:
                continue
            lookup = cho_lookup_names[row]
            feature_lines.append(
                f"    sub @cho_all' lookup {lookup} {cls_name};")

        feature_lines.append("} ljmo;")
        feature_lines.append("")

    # ----------------------------------------------------------------
    # Step 6: Generate vjmo feature (jungseong contextual substitution)
    # ----------------------------------------------------------------
    if 15 in vjmo_has:
        feature_lines.append("feature vjmo {")
        feature_lines.append("    script hang;")

        jung_names = [glyph_name(c) for c in jung_cps]
        feature_lines.append(f"    @jungseong = [{' '.join(jung_names)}];")

        if jong_cps and 16 in vjmo_has:
            jong_names = [glyph_name(c) for c in jong_cps]
            feature_lines.append(f"    @jongseong = [{' '.join(jong_names)}];")
            feature_lines.append("    sub @jungseong' lookup vjmo_row16 @jongseong;")

        # Fallback: no jongseong following → row 15
        feature_lines.append("    sub @jungseong' lookup vjmo_row15;")

        feature_lines.append("} vjmo;")
        feature_lines.append("")

    # ----------------------------------------------------------------
    # Step 7: Generate tjmo feature (jongseong contextual substitution)
    # ----------------------------------------------------------------
    if 17 in tjmo_has and jong_cps:
        feature_lines.append("feature tjmo {")
        feature_lines.append("    script hang;")

        # Rightie jungseong class: original + PUA row 15/16 variants
        rightie_glyphs = []
        for idx in sorted(SC.JUNGSEONG_RIGHTIE):
            # Original Unicode jungseong
            cp = 0x1160 + idx
            if has(cp):
                rightie_glyphs.append(glyph_name(cp))
            # PUA variants (after vjmo substitution)
            for row in [15, 16]:
                pua = pua_fn(idx, row)
                if has(pua):
                    rightie_glyphs.append(glyph_name(pua))
        # Extended jungseong that are rightie
        for jcp in jung_cps:
            if jcp < 0xD7B0:
                continue
            idx = _jung_idx(jcp)
            if idx is not None and idx in SC.JUNGSEONG_RIGHTIE:
                rightie_glyphs.append(glyph_name(jcp))

        # All jungseong variants class (original + PUA row 15/16)
        all_jung_variants = []
        for jcp in jung_cps:
            idx = _jung_idx(jcp)
            if idx is None:
                continue
            all_jung_variants.append(glyph_name(jcp))
            for row in [15, 16]:
                pua = pua_fn(idx, row)
                if has(pua):
                    all_jung_variants.append(glyph_name(pua))

        jong_names = [glyph_name(c) for c in jong_cps]
        feature_lines.append(f"    @jongseong_all = [{' '.join(jong_names)}];")

        if rightie_glyphs and 18 in tjmo_has:
            feature_lines.append(
                f"    @rightie_jung = [{' '.join(rightie_glyphs)}];")
            feature_lines.append(
                "    sub @rightie_jung @jongseong_all' lookup tjmo_row18;")

        if all_jung_variants:
            feature_lines.append(
                f"    @all_jung_variants = [{' '.join(all_jung_variants)}];")
            feature_lines.append(
                "    sub @all_jung_variants @jongseong_all' lookup tjmo_row17;")

        feature_lines.append("} tjmo;")
        feature_lines.append("")

    if not lines and not feature_lines:
        return ""

    return '\n'.join(lines + feature_lines)


def _generate_kern(kern_pairs, has):
    """Generate kern feature using class-based pair positioning.

    Left glyphs with identical kerning patterns are grouped into
    classes, producing one compact PairPosFormat2 lookup per class.
    This avoids the 16-bit PairSetOffset overflow that occurs when
    a single PairPosFormat1 subtable exceeds 65 536 bytes (which
    happens with ~855 left glyphs × ~855 right glyphs per set).
    """
    if not kern_pairs:
        return ""

    from collections import defaultdict

    # Filter to valid pairs and group by left glyph
    by_left = {}
    for (left_cp, right_cp), value in kern_pairs.items():
        if has(left_cp) and has(right_cp):
            by_left.setdefault(left_cp, []).append((right_cp, value))

    if not by_left:
        return ""

    # Group left glyphs by their complete kerning signature
    # (the full set of right-glyph + value pairs)
    sig_to_lefts = defaultdict(list)
    for left_cp, pairs in by_left.items():
        sig = frozenset(pairs)
        sig_to_lefts[sig].append(left_cp)

    print(f"  [kern] {len(sig_to_lefts)} unique left-glyph classes "
          f"from {len(by_left)} left glyphs")

    # Build class definitions (outside feature block) and lookups
    class_defs = []
    lookup_defs = []
    lookup_names = []

    for i, (sig, left_cps) in enumerate(
        sorted(sig_to_lefts.items(), key=lambda x: min(x[1]))
    ):
        left_name = f"@kL{i}"
        left_glyphs = ' '.join(glyph_name(cp) for cp in sorted(left_cps))
        class_defs.append(f"{left_name} = [{left_glyphs}];")

        # Group right glyphs by kern value
        val_to_rights = defaultdict(list)
        for right_cp, value in sig:
            val_to_rights[value].append(right_cp)

        lk_name = f"kern_{i}"
        lk_lines = [f"lookup {lk_name} {{"]
        lk_lines.append("    lookupflag IgnoreMarks;")

        for value, right_cps in sorted(val_to_rights.items()):
            right_name = f"@kR{i}v{abs(value)}"
            right_glyphs = ' '.join(glyph_name(cp) for cp in sorted(right_cps))
            class_defs.append(f"{right_name} = [{right_glyphs}];")
            lk_lines.append(f"    pos {left_name} {right_name} {value};")

        lk_lines.append(f"}} {lk_name};")
        lookup_defs.append('\n'.join(lk_lines))
        lookup_names.append(lk_name)

    # Feature block references all lookups
    feat_lines = ["feature kern {"]
    for ln in lookup_names:
        feat_lines.append(f"    lookup {ln};")
    feat_lines.append("} kern;")

    return '\n'.join(class_defs + [""] + lookup_defs + [""] + feat_lines)


def _generate_liga(has):
    """Generate liga feature for Alphabetic Presentation Forms."""
    subs = []

    _liga_rules = [
        ([0x66, 0x66, 0x69], 0xFB03, "ffi"),
        ([0x66, 0x66, 0x6C], 0xFB04, "ffl"),
        ([0x66, 0x66], 0xFB00, "ff"),
        ([0x66, 0x69], 0xFB01, "fi"),
        ([0x66, 0x6C], 0xFB02, "fl"),
        ([0x17F, 0x74], 0xFB05, "long-s t"),
        ([0x73, 0x74], 0xFB06, "st"),
    ]

    for seq, result_cp, name in _liga_rules:
        if all(has(c) for c in seq) and has(result_cp):
            seq_names = ' '.join(glyph_name(c) for c in seq)
            subs.append(f"    sub {seq_names} by {glyph_name(result_cp)}; # {name}")

    _armenian_rules = [
        ([0x574, 0x576], 0xFB13, "men now"),
        ([0x574, 0x565], 0xFB14, "men ech"),
        ([0x574, 0x56B], 0xFB15, "men ini"),
        ([0x57E, 0x576], 0xFB16, "vew now"),
        ([0x574, 0x56D], 0xFB17, "men xeh"),
    ]

    for seq, result_cp, name in _armenian_rules:
        if all(has(c) for c in seq) and has(result_cp):
            seq_names = ' '.join(glyph_name(c) for c in seq)
            subs.append(f"    sub {seq_names} by {glyph_name(result_cp)}; # Armenian {name}")

    if not subs:
        return ""

    lines = ["feature liga {"]
    lines.extend(subs)
    lines.append("} liga;")
    return '\n'.join(lines)


def _generate_locl(glyphs, has):
    """Generate locl feature for Bulgarian and Serbian Cyrillic variants."""
    bg_subs = []
    sr_subs = []

    for pua in range(0xF0000, 0xF0060):
        cyrillic = pua - 0xF0000 + 0x0400
        if has(pua) and has(cyrillic):
            pua_bm = glyphs[pua].bitmap
            cyr_bm = glyphs[cyrillic].bitmap
            if pua_bm != cyr_bm:
                bg_subs.append(f"        sub {glyph_name(cyrillic)} by {glyph_name(pua)};")

    for pua in range(0xF0060, 0xF00C0):
        cyrillic = pua - 0xF0060 + 0x0400
        if has(pua) and has(cyrillic):
            pua_bm = glyphs[pua].bitmap
            cyr_bm = glyphs[cyrillic].bitmap
            if pua_bm != cyr_bm:
                sr_subs.append(f"        sub {glyph_name(cyrillic)} by {glyph_name(pua)};")

    if not bg_subs and not sr_subs:
        return ""

    lines = ["feature locl {"]
    lines.append("    script cyrl;")
    if bg_subs:
        lines.append("    language BGR;")
        lines.append("    lookup BulgarianForms {")
        lines.extend(bg_subs)
        lines.append("    } BulgarianForms;")
    if sr_subs:
        lines.append("    language SRB;")
        lines.append("    lookup SerbianForms {")
        lines.extend(sr_subs)
        lines.append("    } SerbianForms;")
    lines.append("} locl;")
    return '\n'.join(lines)


def _generate_devanagari(glyphs, has, replacewith_subs=None):
    """Generate Devanagari GSUB features: ccmp (consonant mapping + vowel decomposition), nukt, akhn (+ conjuncts), half, blwf, cjct, blws, rphf, abvs."""
    features = []

    # --- ccmp: Map Unicode consonants to internal PUA presentation forms ---
    # This mirrors the Kotlin normalise() pass 0.
    ccmp_subs = []
    for uni_cp in range(0x0915, 0x093A):
        internal = SC.to_deva_internal(uni_cp)
        if has(uni_cp) and has(internal):
            ccmp_subs.append(
                f"    sub {glyph_name(uni_cp)} by {glyph_name(internal)};"
            )
    # Also map nukta-forms U+0958-095F to their PUA equivalents
    for uni_cp in range(0x0958, 0x0960):
        try:
            internal = SC.to_deva_internal(uni_cp)
            if has(uni_cp) and has(internal):
                ccmp_subs.append(
                    f"    sub {glyph_name(uni_cp)} by {glyph_name(internal)};"
                )
        except ValueError:
            pass

    # --- ccmp: Devanagari vowel decompositions ---
    # Independent vowels like U+0910 (AI) decompose into base + matra.
    # These must be in the dev2 ccmp so HarfBuzz applies them during
    # Devanagari shaping (DFLT ccmp is not used when dev2 is present).
    vowel_decomp_subs = []
    if replacewith_subs:
        for src_cp, target_cps in replacewith_subs:
            if not (0x0900 <= src_cp <= 0x097F):
                continue
            # Skip consonants (already handled above as single subs)
            if 0x0915 <= src_cp <= 0x0939 or 0x0958 <= src_cp <= 0x095F:
                continue
            if len(target_cps) < 2:
                continue
            if not has(src_cp):
                continue
            if not all(has(t) for t in target_cps):
                continue
            targets = ' '.join(glyph_name(t) for t in target_cps)
            vowel_decomp_subs.append(
                f"    sub {glyph_name(src_cp)} by {targets};"
            )

    # --- ccmp: Contextual Anusvara upper variant ---
    # Must be in ccmp (before reordering) because I-matra (U+093F) is pre-base
    # and gets reordered before the consonant.  In Unicode order (before
    # reordering), all matras are adjacent to anusvara: C + matra + anusvara.
    # Reph is NOT a substitution trigger (only a GPOS positioning trigger).
    anusvara_upper = SC.DEVANAGARI_ANUSVARA_UPPER
    anusvara_ccmp_subs = []
    if has(0x0902) and has(anusvara_upper):
        anusvara_triggers = [
            0x093A, 0x093B, 0x093F, 0x0940,
            0x0945, 0x0946, 0x0947, 0x0948,
            0x0949, 0x094A, 0x094B, 0x094C,
            0x094F,
        ]
        for cp in anusvara_triggers:
            if has(cp):
                anusvara_ccmp_subs.append(
                    f"    sub {glyph_name(cp)}"
                    f" {glyph_name(0x0902)}' lookup AnusvaraUpper;"
                )

    if ccmp_subs or vowel_decomp_subs or anusvara_ccmp_subs:
        ccmp_parts = []
        # Define lookups OUTSIDE feature blocks so they can be referenced
        # from both locl (for DirectWrite) and ccmp (for HarfBuzz).
        # DirectWrite's dev2 shaper does not apply ccmp but does apply locl.
        if anusvara_ccmp_subs:
            ccmp_parts.append(f"lookup AnusvaraUpper {{")
            ccmp_parts.append(f"    sub {glyph_name(0x0902)} by {glyph_name(anusvara_upper)};")
            ccmp_parts.append(f"}} AnusvaraUpper;")
            ccmp_parts.append("")
        if ccmp_subs:
            ccmp_parts.append("lookup DevaConsonantMap {")
            ccmp_parts.extend(ccmp_subs)
            ccmp_parts.append("} DevaConsonantMap;")
            ccmp_parts.append("")
        if vowel_decomp_subs:
            ccmp_parts.append("lookup DevaVowelDecomp {")
            ccmp_parts.extend(vowel_decomp_subs)
            ccmp_parts.append("} DevaVowelDecomp;")
            ccmp_parts.append("")
        # locl for dev2/deva — DirectWrite applies locl as the first
        # feature for Devanagari shaping.  Registering consonant mapping
        # and vowel decomposition here ensures they fire on DirectWrite.
        # Both dev2 (new Indic) and deva (old Indic) script tags are
        # needed for CoreText compatibility.
        ccmp_parts.append("feature locl {")
        for _st in ['dev2', 'deva']:
            ccmp_parts.append(f"    script {_st};")
            if ccmp_subs:
                ccmp_parts.append("    lookup DevaConsonantMap;")
            if anusvara_ccmp_subs:
                ccmp_parts.extend(anusvara_ccmp_subs)
            if vowel_decomp_subs:
                ccmp_parts.append("    lookup DevaVowelDecomp;")
        ccmp_parts.append("} locl;")
        ccmp_parts.append("")
        # ccmp for dev2/deva — HarfBuzz applies ccmp before reordering
        ccmp_parts.append("feature ccmp {")
        for _st in ['dev2', 'deva']:
            ccmp_parts.append(f"    script {_st};")
            if ccmp_subs:
                ccmp_parts.append("    lookup DevaConsonantMap;")
            if anusvara_ccmp_subs:
                ccmp_parts.extend(anusvara_ccmp_subs)
            if vowel_decomp_subs:
                ccmp_parts.append("    lookup DevaVowelDecomp;")
        ccmp_parts.append("} ccmp;")
        features.append('\n'.join(ccmp_parts))

    # --- nukt: consonant + nukta -> nukta form ---
    # Now operates on PUA forms (after ccmp)
    nukt_subs = []
    for uni_cp in range(0x0915, 0x093A):
        internal = SC.to_deva_internal(uni_cp)
        nukta_form = internal + 48
        if has(internal) and has(0x093C) and has(nukta_form):
            nukt_subs.append(
                f"    sub {glyph_name(internal)} {glyph_name(0x093C)} by {glyph_name(nukta_form)};"
            )
    if nukt_subs:
        nukt_body = '\n'.join(nukt_subs)
        features.append("feature nukt {\n    script dev2;\n" + nukt_body
                         + "\n    script deva;\n" + nukt_body + "\n} nukt;")

    # --- akhn: akhand ligatures + conjuncts ---
    # All conjunct ligatures (C1 + virama + C2 → ligature) go in akhn
    # because HarfBuzz applies akhn with F_GLOBAL masking (all glyphs
    # in the syllable can trigger the lookup).  The half feature uses
    # per-glyph masking (only pre-base consonants), which prevents
    # 3-glyph conjuncts from matching across the pre-base/base boundary.
    # akhn also runs before half, so conjuncts take priority over
    # half-forms when both could match.
    def _di(u):
        """Convert Unicode Devanagari consonant to internal PUA form."""
        try:
            return SC.to_deva_internal(u)
        except ValueError:
            return u  # already PUA or non-consonant

    akhn_subs = []

    # ISCII: RA + ZWJ + virama + YA -> RYA (uF0106)
    ra_int = SC.to_deva_internal(0x0930)
    ya_int = SC.to_deva_internal(0x092F)
    RYA = 0xF0106
    if has(ra_int) and has(0x200D) and has(SC.DEVANAGARI_VIRAMA) and has(ya_int) and has(RYA):
        akhn_subs.append(
            f"    sub {glyph_name(ra_int)} {glyph_name(0x200D)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(ya_int)} by {glyph_name(RYA)}; # RYA"
        )

    # ISCII eyelash-RA: RRA + virama -> eyelash-RA, RA + virama + ZWJ -> eyelash-RA
    rra_int = SC.to_deva_internal(0x0931)
    EYELASH_RA = 0xF010B
    if has(ra_int) and has(0x200D) and has(SC.DEVANAGARI_VIRAMA) and has(EYELASH_RA):
        akhn_subs.append(
            f"    sub {glyph_name(ra_int)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(0x200D)} by {glyph_name(EYELASH_RA)}; # eyelash-RA (RA+H+ZWJ)"
        )
    if has(rra_int) and has(SC.DEVANAGARI_VIRAMA) and has(EYELASH_RA):
        akhn_subs.append(
            f"    sub {glyph_name(rra_int)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(EYELASH_RA)}; # eyelash-RA (RRA+H)"
        )

    ka_int = SC.to_deva_internal(0x0915)
    ssa_int = SC.to_deva_internal(0x0937)
    ja_int = SC.to_deva_internal(0x091C)
    nya_int = SC.to_deva_internal(0x091E)
    if has(ka_int) and has(SC.DEVANAGARI_VIRAMA) and has(ssa_int) and has(SC.DEVANAGARI_LIG_K_SS):
        akhn_subs.append(
            f"    sub {glyph_name(ka_int)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(ssa_int)} by {glyph_name(SC.DEVANAGARI_LIG_K_SS)};"
        )
    if has(ja_int) and has(SC.DEVANAGARI_VIRAMA) and has(nya_int) and has(SC.DEVANAGARI_LIG_J_NY):
        akhn_subs.append(
            f"    sub {glyph_name(ja_int)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(nya_int)} by {glyph_name(SC.DEVANAGARI_LIG_J_NY)};"
        )

    _conjuncts = [
        (0x0915, 0x0924, SC.DEVANAGARI_LIG_K_T, "K.T"),
        (0x0918, 0x091F, 0xF01BD, "GH.TT"),
        (0x0918, 0x0920, 0xF01BE, "GH.TTH"),
        (0x0918, 0x0922, 0xF01BF, "GH.DDH"),
        (0x0919, 0x0915, 0xF01CE, "NG.K"),
        (0x0919, 0x0916, 0xF01CF, "NG.KH"),
        (0x0919, 0x0917, 0xF01D2, "NG.G"),
        (0x0919, 0x0918, 0xF01D3, "NG.GH"),
        (0x0919, 0x0928, 0xF01CD, "NG.N"),
        (0x0919, 0x092E, 0xF01D4, "NG.M"),
        (0x091B, 0x0935, 0xF01D5, "CH.V"),
        (0x091C, 0x092F, SC.DEVANAGARI_LIG_J_Y, "J.Y"),
        (0x091F, 0x0915, 0xF01E0, "TT.K"),
        (0x091F, 0x091F, 0xF01D6, "TT.TT"),
        (0x091F, 0x0920, 0xF01D7, "TT.TTH"),
        (0x091F, 0x092A, 0xF01E1, "TT.P"),
        (0x091F, 0x0935, 0xF01D8, "TT.V"),
        (0x091F, 0x0936, 0xF01E2, "TT.SH"),
        (0x091F, 0x0938, 0xF01E3, "TT.S"),
        (0x0920, 0x0920, 0xF01D9, "TTH.TTH"),
        (0x0920, 0x0935, 0xF01DA, "TTH.V"),
        (0x0921, 0x0917, 0xF01D0, "DD.G"),
        (0x0921, 0x0921, 0xF01DB, "DD.DD"),
        (0x0921, 0x0922, 0xF01DC, "DD.DDH"),
        (0x0921, 0x092D, 0xF01D1, "DD.BH"),
        (0x0921, 0x0935, 0xF01DD, "DD.V"),
        (0x0922, 0x0922, 0xF01DE, "DDH.DDH"),
        (0x0922, 0x0935, 0xF01DF, "DDH.V"),
        (0x0924, 0x0924, SC.DEVANAGARI_LIG_T_T, "T.T"),
        (0x0926, 0x0917, 0xF01B0, "D.G"),
        (0x0926, 0x0918, 0xF01B1, "D.GH"),
        (0x0926, 0x0926, 0xF01B2, "D.D"),
        (0x0926, 0x0927, 0xF01B3, "D.DH"),
        (0x0926, 0x0928, 0xF01B4, "D.N"),
        (0x0926, 0x092C, 0xF01B5, "D.B"),
        (0x0926, 0x092D, 0xF01B6, "D.BH"),
        (0x0926, 0x092E, 0xF01B7, "D.M"),
        (0x0926, 0x092F, 0xF01B8, "D.Y"),
        (0x0926, 0x0935, 0xF01B9, "D.V"),
        (0x0928, 0x0924, SC.DEVANAGARI_LIG_N_T, "N.T"),
        (0x0928, 0x0928, SC.DEVANAGARI_LIG_N_N, "N.N"),
        (0x092A, 0x091F, 0xF01C0, "P.TT"),
        (0x092A, 0x0920, 0xF01C1, "P.TTH"),
        (0x092A, 0x0922, 0xF01C2, "P.DDH"),
        (0x0936, 0x091A, SC.DEVANAGARI_LIG_SH_C, "SH.C"),
        (0x0936, 0x0928, SC.DEVANAGARI_LIG_SH_N, "SH.N"),
        (0x0936, 0x0935, SC.DEVANAGARI_LIG_SH_V, "SH.V"),
        (0x0937, 0x091F, 0xF01C3, "SS.TT"),
        (0x0937, 0x0920, 0xF01C4, "SS.TTH"),
        (0x0937, 0x0922, 0xF01C5, "SS.DDH"),
        (0x0937, 0x092A, SC.DEVANAGARI_LIG_SS_P, "SS.P"),
        (0x0938, 0x0935, SC.DEVANAGARI_LIG_S_V, "S.V"),
        (0x0939, 0x0923, 0xF01C6, "H.NN"),
        (0x0939, 0x0928, 0xF01C7, "H.N"),
        (0x0939, 0x092E, 0xF01C8, "H.M"),
        (0x0939, 0x092F, 0xF01C9, "H.Y"),
        (0x0939, 0x0932, 0xF01CA, "H.L"),
        (0x0939, 0x0935, 0xF01CB, "H.V"),
        # Marwari DD (U+0978) — not mapped to PUA by ccmp, so _di(0x0978)
        # returns 0x0978 unchanged.  C2 uses standard Unicode (→ PUA via _di).
        (0x0978, 0x0978, SC.MARWARI_LIG_DD_DD,  "mDD.DD"),
        (0x0978, 0x0922, SC.MARWARI_LIG_DD_DDH, "mDD.DDH"),
        (0x0978, 0x092F, SC.MARWARI_LIG_DD_Y,   "mDD.Y"),
    ]
    for c1_uni, c2_uni, result, name in _conjuncts:
        c1 = _di(c1_uni)
        c2 = _di(c2_uni)
        if has(c1) and has(SC.DEVANAGARI_VIRAMA) and has(c2) and has(result):
            akhn_subs.append(
                f"    sub {glyph_name(c1)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(c2)} by {glyph_name(result)}; # {name}"
            )
    if akhn_subs:
        akhn_body = '\n'.join(akhn_subs)
        features.append("feature akhn {\n    script dev2;\n" + akhn_body
                         + "\n    script deva;\n" + akhn_body + "\n} akhn;")

    # --- half: consonant (PUA) + virama -> half form ---
    # After ccmp, consonants are in PUA form, so reference PUA here.
    # Covers all PUA consonants including conjuncts produced by akhn
    # (e.g. DD.G + virama -> half-DD.G).  The half feature uses
    # per-glyph masking (only pre-base consonants get the mask), so
    # conjunct glyphs formed by akhn inherit the pre-base mask and
    # their following virama also has the pre-base mask.
    half_subs = []
    for internal in SC.DEVANAGARI_PRESENTATION_CONSONANTS:
        half_form = internal + 240
        if has(internal) and has(SC.DEVANAGARI_VIRAMA) and has(half_form):
            half_subs.append(
                f"    sub {glyph_name(internal)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(half_form)};"
            )
    # RYA (uF0106) has a special half form (uF0107), not at +240
    HALF_RYA = 0xF0107
    if has(RYA) and has(SC.DEVANAGARI_VIRAMA) and has(HALF_RYA):
        half_subs.append(
            f"    sub {glyph_name(RYA)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(HALF_RYA)};"
        )
    # Eyelash-RA's half form is itself
    if has(EYELASH_RA) and has(SC.DEVANAGARI_VIRAMA):
        half_subs.append(
            f"    sub {glyph_name(EYELASH_RA)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(EYELASH_RA)};"
        )
    # Marwari DD.Y (uF016E) has special half form (uF016F), not at +240
    if has(SC.MARWARI_LIG_DD_Y) and has(SC.DEVANAGARI_VIRAMA) and has(SC.MARWARI_HALFLIG_DD_Y):
        half_subs.append(
            f"    sub {glyph_name(SC.MARWARI_LIG_DD_Y)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(SC.MARWARI_HALFLIG_DD_Y)};"
        )
    if half_subs:
        half_body = '\n'.join(half_subs)
        features.append("feature half {\n    script dev2;\n" + half_body
                         + "\n    script deva;\n" + half_body + "\n} half;")

    # --- blwf: virama + RA -> below-base RA (rakaar) ---
    # This serves two purposes:
    # 1. Tells HarfBuzz that RA can be below-base during base detection
    #    (HarfBuzz tests would_substitute([virama, RA], blwf) with ORIGINAL
    #    Unicode glyphs, before ccmp has run)
    # 2. Actually substitutes virama + RA -> rakaar mark during shaping
    #    (after ccmp, RA is in PUA form)
    ra_int = SC.to_deva_internal(0x0930)
    ra_sub = SC.DEVANAGARI_RA_SUB
    blwf_subs = []
    # Unicode form (for base detection before ccmp)
    if has(SC.DEVANAGARI_VIRAMA) and has(0x0930) and has(ra_sub):
        blwf_subs.append(
            f"    sub {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(0x0930)} by {glyph_name(ra_sub)};"
        )
    # PUA form (for actual substitution after ccmp)
    if has(SC.DEVANAGARI_VIRAMA) and has(ra_int) and has(ra_sub):
        blwf_subs.append(
            f"    sub {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(ra_int)} by {glyph_name(ra_sub)};"
        )
    if blwf_subs:
        blwf_body = '\n'.join(blwf_subs)
        features.append("feature blwf {\n    script dev2;\n" + blwf_body
                         + "\n    script deva;\n" + blwf_body + "\n} blwf;")

    # --- cjct: consonant (PUA) + below-base RA -> RA-appended form ---
    # After blwf converts virama+RA to rakaar mark, cjct combines it
    # with the preceding consonant to produce the ra-appended glyph.
    # Covers all PUA consonants: basic, nukta forms, AND conjuncts
    # (e.g. DD.G + rakaar -> DD.G.RA).
    #
    # A second lookup converts RA-appended + virama -> RA-appended half,
    # since the half feature has already run before cjct.
    # Lookups defined OUTSIDE the feature block so they can be referenced
    # from both dev2 and deva script sections without name collisions.
    cjct_lookups = []
    cjct_lookup_refs = []

    # Lookup 1: consonant + rakaar -> RA-appended form
    ra_append_subs = []
    for internal in SC.DEVANAGARI_PRESENTATION_CONSONANTS:
        ra_form = internal + 480
        if has(internal) and has(ra_sub) and has(ra_form):
            ra_append_subs.append(
                f"    sub {glyph_name(internal)} {glyph_name(ra_sub)} by {glyph_name(ra_form)};"
            )
    # Marwari DD + rakaar -> DD.R (DD stays as uni0978, not PUA)
    if has(SC.MARWARI_DD) and has(ra_sub) and has(SC.MARWARI_LIG_DD_R):
        ra_append_subs.append(
            f"    sub {glyph_name(SC.MARWARI_DD)} {glyph_name(ra_sub)} by {glyph_name(SC.MARWARI_LIG_DD_R)};"
        )
    if ra_append_subs:
        cjct_lookups.append("lookup CjctRaAppend {")
        cjct_lookups.extend(ra_append_subs)
        cjct_lookups.append("} CjctRaAppend;")
        cjct_lookup_refs.append("    lookup CjctRaAppend;")

    # Lookup 2: RA-appended + virama -> RA-appended half form
    ra_half_subs = []
    for ra_form in SC.DEVANAGARI_PRESENTATION_CONSONANTS_WITH_RA:
        ra_half = ra_form + 240  # +240 from RA-appended = +720 from base
        if has(ra_form) and has(SC.DEVANAGARI_VIRAMA) and has(ra_half):
            ra_half_subs.append(
                f"    sub {glyph_name(ra_form)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(ra_half)};"
            )
    if ra_half_subs:
        cjct_lookups.append("lookup CjctRaHalf {")
        cjct_lookups.extend(ra_half_subs)
        cjct_lookups.append("} CjctRaHalf;")
        cjct_lookup_refs.append("    lookup CjctRaHalf;")

    if cjct_lookup_refs:
        cjct_feat = cjct_lookups + ["", "feature cjct {"]
        for _st in ['dev2', 'deva']:
            cjct_feat.append(f"    script {_st};")
            cjct_feat.extend(cjct_lookup_refs)
        cjct_feat.append("} cjct;")
        features.append('\n'.join(cjct_feat))

    # --- blws: RA/RRA/HA (PUA) + U/UU -> special syllables ---
    blws_subs = []
    _blws_rules = [
        (_di(0x0930), SC.DEVANAGARI_U, SC.DEVANAGARI_SYLL_RU, "Ru"),
        (_di(0x0930), SC.DEVANAGARI_UU, SC.DEVANAGARI_SYLL_RUU, "Ruu"),
        (_di(0x0931), SC.DEVANAGARI_U, SC.DEVANAGARI_SYLL_RRU, "RRu"),
        (_di(0x0931), SC.DEVANAGARI_UU, SC.DEVANAGARI_SYLL_RRUU, "RRuu"),
        (_di(0x0939), SC.DEVANAGARI_U, SC.DEVANAGARI_SYLL_HU, "Hu"),
        (_di(0x0939), SC.DEVANAGARI_UU, SC.DEVANAGARI_SYLL_HUU, "Huu"),
    ]
    for c1, c2, result, name in _blws_rules:
        if has(c1) and has(c2) and has(result):
            blws_subs.append(
                f"    sub {glyph_name(c1)} {glyph_name(c2)} by {glyph_name(result)}; # {name}"
            )
    if blws_subs:
        blws_body = '\n'.join(blws_subs)
        features.append("feature blws {\n    script dev2;\n" + blws_body
                         + "\n    script deva;\n" + blws_body + "\n} blws;")

    # --- rphf: RA + virama -> reph ---
    # Must include BOTH Unicode and PUA rules:
    # - Unicode rule: needed by shapers (CoreText, DirectWrite) that test
    #   reph eligibility via would_substitute() BEFORE ccmp/locl maps RA
    #   to its PUA form
    # - PUA rule: matches the actual glyph after ccmp/locl has run
    if has(ra_int) and has(SC.DEVANAGARI_VIRAMA) and has(SC.DEVANAGARI_RA_SUPER):
        rphf_rules = []
        if has(0x0930):
            rphf_rules.append(
                f"    sub {glyph_name(0x0930)} {glyph_name(SC.DEVANAGARI_VIRAMA)}"
                f" by {glyph_name(SC.DEVANAGARI_RA_SUPER)};"
            )
        rphf_rules.append(
            f"    sub {glyph_name(ra_int)} {glyph_name(SC.DEVANAGARI_VIRAMA)}"
            f" by {glyph_name(SC.DEVANAGARI_RA_SUPER)};"
        )
        rphf_lines = ["feature rphf {"]
        for _st in ['dev2', 'deva']:
            rphf_lines.append(f"    script {_st};")
            rphf_lines.extend(rphf_rules)
        rphf_lines.append("} rphf;")
        features.append('\n'.join(rphf_lines))

    # --- pres: alternate half-SHA before LA ---
    # SHA+virama+LA uses a special half-SHA form (uF010F) instead of the
    # regular half form (uF0251).  half has already run at this point.
    ALT_HALF_SHA = 0xF010F
    half_sha = SC.to_deva_internal(0x0936) + 240  # 0xF0251
    la_int = SC.to_deva_internal(0x0932)           # 0xF015D
    if has(half_sha) and has(la_int) and has(ALT_HALF_SHA):
        pres_lines = []
        pres_lines.append(f"lookup AltHalfSha {{")
        pres_lines.append(f"    sub {glyph_name(half_sha)} by {glyph_name(ALT_HALF_SHA)};")
        pres_lines.append(f"}} AltHalfSha;")
        pres_lines.append("")
        pres_lines.append("feature pres {")
        for _st in ['dev2', 'deva']:
            pres_lines.append(f"    script {_st};")
            pres_lines.append(f"    sub {glyph_name(half_sha)}' lookup AltHalfSha {glyph_name(la_int)};")
        pres_lines.append("} pres;")
        features.append('\n'.join(pres_lines))

    # --- abvs: complex reph + post-reordering anusvara upper ---
    # Complex reph: the Kotlin engine uses complex reph (U+F010D) when a
    # devanagariSuperscript mark precedes reph, or any vowel matra
    # (e.g. i-matra) exists in the syllable.
    # After dev2 reordering, glyph order is:
    #   [pre-base matras] + [base] + [below-base] + [above-base] + [reph]
    # We use chaining contextual substitution to detect these conditions.
    #
    # Anusvara upper fallback: CoreText may apply ccmp AFTER reordering,
    # which separates I-matra from anusvara (KA I-MATRA ANUSVARA →
    # I-MATRA KA ANUSVARA).  The ccmp/locl rule `sub 093F 0902'` won't
    # match when they're separated.  Add wider-context rules here (abvs
    # runs post-reordering on all shapers).

    # Broad Devanagari class for context gaps
    deva_any_cps = (
        list(range(0xF0140, 0xF0165)) +  # PUA consonants
        list(range(0xF0170, 0xF0195)) +  # nukta forms
        list(range(0xF0230, 0xF0255)) +  # half forms
        list(range(0xF0320, 0xF0405)) +  # RA-appended forms
        list(range(0x093A, 0x094D)) +     # vowel signs/matras
        list(range(0x0900, 0x0903)) +     # signs
        [0x094E, 0x094F, 0x0951] +
        list(range(0x0953, 0x0956)) +
        [SC.DEVANAGARI_RA_SUB] +          # below-base RA
        [r for _, _, r, _ in _conjuncts]  # conjunct result glyphs
    )
    deva_any_glyphs = [glyph_name(cp) for cp in sorted(set(deva_any_cps)) if has(cp)]

    abvs_lookups = []
    abvs_body = []

    if has(SC.DEVANAGARI_RA_SUPER) and has(SC.DEVANAGARI_RA_SUPER_COMPLEX) and deva_any_glyphs:
        trigger_cps = (
            list(range(0x0900, 0x0903)) +
            list(range(0x093A, 0x093C)) +    # 093A-093B only (not 093C)
            [0x0940] +                       # II-matra
            list(range(0x0945, 0x094D)) +    # candra E .. AU (not 0941-0944)
            [0x094F, 0x0951] +
            list(range(0x0953, 0x0956))
        )
        trigger_glyphs = [glyph_name(cp) for cp in trigger_cps if has(cp)]

        if trigger_glyphs:
            reph = glyph_name(SC.DEVANAGARI_RA_SUPER)
            complex_reph = glyph_name(SC.DEVANAGARI_RA_SUPER_COMPLEX)

            abvs_lookups.append(f"lookup ComplexReph {{")
            abvs_lookups.append(f"    sub {reph} by {complex_reph};")
            abvs_lookups.append(f"}} ComplexReph;")

            abvs_body.append(f"    @complexRephTriggers = [{' '.join(trigger_glyphs)}];")
            # Rule 1: trigger mark/vowel immediately before reph
            abvs_body.append(f"    sub @complexRephTriggers {reph}' lookup ComplexReph;")
            # Rules 2-4: i-matra separated from reph by 1-3 intervening glyphs
            abvs_body.append(f"    sub {glyph_name(0x093F)} @devaAny {reph}' lookup ComplexReph;")
            abvs_body.append(f"    sub {glyph_name(0x093F)} @devaAny @devaAny {reph}' lookup ComplexReph;")
            abvs_body.append(f"    sub {glyph_name(0x093F)} @devaAny @devaAny @devaAny {reph}' lookup ComplexReph;")

    # Post-reordering anusvara upper: catch I-matra separated from
    # anusvara by reordering (1-3 intervening consonants/marks).
    # On HarfBuzz, ccmp already handled this (no-op here); on CoreText,
    # ccmp may run after reordering so the adjacency rule didn't match.
    if has(0x093F) and has(0x0902) and has(anusvara_upper) and deva_any_glyphs:
        abvs_body.append(f"    sub {glyph_name(0x093F)} @devaAny"
                         f" {glyph_name(0x0902)}' lookup AnusvaraUpper;")
        abvs_body.append(f"    sub {glyph_name(0x093F)} @devaAny @devaAny"
                         f" {glyph_name(0x0902)}' lookup AnusvaraUpper;")
        abvs_body.append(f"    sub {glyph_name(0x093F)} @devaAny @devaAny @devaAny"
                         f" {glyph_name(0x0902)}' lookup AnusvaraUpper;")

    # Reverse anusvara upper when complex reph is present: the vertical
    # offset is encoded in the glyph choice (uni0902 sits 2px lower than
    # uF016C), so when complex reph precedes, use the lower form.
    if has(0x0902) and has(anusvara_upper) and has(SC.DEVANAGARI_RA_SUPER_COMPLEX):
        abvs_lookups.append(f"lookup AnusvaraLower {{")
        abvs_lookups.append(f"    sub {glyph_name(anusvara_upper)} by {glyph_name(0x0902)};")
        abvs_lookups.append(f"}} AnusvaraLower;")
        abvs_body.append(
            f"    sub {glyph_name(SC.DEVANAGARI_RA_SUPER_COMPLEX)}"
            f" {glyph_name(anusvara_upper)}' lookup AnusvaraLower;"
        )

    if abvs_body:
        abvs_lines = abvs_lookups[:]
        if abvs_lookups:
            abvs_lines.append("")
        abvs_lines.append("feature abvs {")
        for _st in ['dev2', 'deva']:
            abvs_lines.append(f"    script {_st};")
            if deva_any_glyphs:
                abvs_lines.append(f"    @devaAny = [{' '.join(deva_any_glyphs)}];")
            abvs_lines.extend(abvs_body)
        abvs_lines.append("} abvs;")
        features.append('\n'.join(abvs_lines))

    # --- psts: I-matra/II-matra length variants + open Ya ---
    # Must run AFTER abvs because abvs uses uni093F as context for complex
    # reph substitution.  If I-matra were substituted before abvs, those
    # contextual rules would break.
    #
    # HarfBuzz dev2 feature order: init → pres → abvs → blws → psts → haln
    # psts has F_GLOBAL_MANUAL_JOINERS masking → applied to ALL glyphs in the
    # syllable, so it works for both pre-base I-matra and post-base II-matra.
    matra_lookups, matra_body = _generate_psts_matra_variants(glyphs, has, _conjuncts)
    ya_lookups, ya_body = _generate_psts_open_ya(glyphs, has)
    anus_lookups, anus_body = _generate_psts_anusvara(glyphs, has, _conjuncts)
    all_lookups = matra_lookups + ya_lookups + anus_lookups
    all_body = matra_body + ya_body + anus_body
    if all_body:
        feat = ["feature psts {"]
        for _st in ['dev2', 'deva']:
            feat.append(f"    script {_st};")
            feat.extend(all_body)
        feat.append("} psts;")
        features.append('\n'.join(all_lookups + [''] + feat))

    # --- calt: contextual Visarga ---
    # When Visarga (U+0903) is followed by a Devanagari character (mid-word),
    # substitute with interword variant (uF010A).  calt is applied globally
    # across syllable boundaries, unlike psts which is per-syllable.
    INTERWORD_VISARGA = 0xF010A
    visarga = 0x0903
    if has(visarga) and has(INTERWORD_VISARGA):
        # Build class of Devanagari characters that can follow Visarga mid-word:
        # PUA consonants (full, half, RA-appended, RA-appended half) + independent vowels
        deva_following_cps = (
            list(SC.DEVANAGARI_PRESENTATION_CONSONANTS) +
            list(range(0xF0230, 0xF0320)) +   # half forms
            list(SC.DEVANAGARI_PRESENTATION_CONSONANTS_WITH_RA) +
            list(range(0xF0410, 0xF0500)) +   # RA-appended half forms
            list(range(0x0904, 0x0915)) +      # independent vowels
            list(range(0x0915, 0x093A)) +      # Unicode consonants (before ccmp)
            list(range(0x0958, 0x0962))         # nukta consonants
        )
        deva_following = [glyph_name(cp) for cp in sorted(set(deva_following_cps)) if has(cp)]
        if deva_following:
            calt_lines = []
            calt_lines.append(f"lookup InterwordVisarga {{")
            calt_lines.append(f"    sub {glyph_name(visarga)} by {glyph_name(INTERWORD_VISARGA)};")
            calt_lines.append(f"}} InterwordVisarga;")
            calt_lines.append("")
            calt_lines.append("feature calt {")
            for _st in ['dev2', 'deva']:
                calt_lines.append(f"    script {_st};")
                calt_lines.append(f"    @devaFollowing = [{' '.join(deva_following)}];")
                calt_lines.append(f"    sub {glyph_name(visarga)}' lookup InterwordVisarga @devaFollowing;")
            calt_lines.append("} calt;")
            features.append('\n'.join(calt_lines))

    if not features:
        return ""
    return '\n\n'.join(features)


def _generate_psts_matra_variants(glyphs, has, conjuncts):
    """Generate psts feature for I-matra and II-matra length variant selection.

    The bitmap font has 16 length variants for each of I-matra (U+093F) and
    II-matra (U+0940), mapped to PUA U+F0110-F011F and U+F0120-F012F.
    The variant is selected based on the consonant's stem position (anchor[0].x)
    and width, mirroring the Kotlin engine logic.
    """
    from collections import defaultdict

    if not has(0x093F) and not has(0x0940):
        return ""

    # Check that at least some variant glyphs exist
    has_i_variants = any(has(0xF0110 + i) for i in range(16))
    has_ii_variants = any(has(0xF0120 + i) for i in range(16))
    if not has_i_variants and not has_ii_variants:
        return ""

    def anchor_x(cp):
        """Get anchor[0].x for a glyph, defaulting to 0."""
        if cp not in glyphs:
            return 0
        a = glyphs[cp].props.diacritics_anchors[0]
        return a.x if a.x_used else 0

    def glyph_width(cp):
        """Get glyph width."""
        if cp not in glyphs:
            return 0
        return glyphs[cp].props.width

    # Collect base consonants (full forms that can serve as syllable base)
    # Includes: presentation consonants, nukta forms, conjuncts, RA-appended forms
    base_cps = set()
    for cp in SC.DEVANAGARI_PRESENTATION_CONSONANTS:
        if has(cp):
            base_cps.add(cp)
    for cp in SC.DEVANAGARI_PRESENTATION_CONSONANTS_WITH_RA:
        if has(cp):
            base_cps.add(cp)
    # Add conjunct result glyphs (they are also full consonants)
    for _, _, result, _ in conjuncts:
        if has(result):
            base_cps.add(result)

    # Collect half consonants
    half_cps = set()
    for cp in SC.DEVANAGARI_PRESENTATION_CONSONANTS_HALF:
        if has(cp):
            half_cps.add(cp)
    for cp in SC.DEVANAGARI_PRESENTATION_CONSONANTS_WITH_RA_HALF:
        if has(cp):
            half_cps.add(cp)

    if not base_cps:
        return ""

    lines = []

    # ===== I-matra variant lookups and rules =====
    if has(0x093F) and has_i_variants:
        # Create 16 single-substitution lookups
        for var in range(16):
            target = 0xF0110 + var
            if has(target):
                lines.append(f"lookup IMatraVar{var} {{")
                lines.append(f"    sub {glyph_name(0x093F)} by {glyph_name(target)};")
                lines.append(f"}} IMatraVar{var};")

        # --- Group base consonants by I-matra variant index ---
        # Formula: var_idx = clamp(anchor_x + 2, 6, 21) - 6
        i_base_groups = defaultdict(set)  # var_idx -> set of base cps
        for cp in sorted(base_cps):
            ax = anchor_x(cp)
            var_idx = min(max(ax + 2, 6), 21) - 6
            i_base_groups[var_idx].add(cp)

        # --- Group half consonants by width ---
        # Half consonants only contribute their width to the variant calc,
        # so we can group them into width-classes to avoid O(n^2) rule explosion.
        half_by_width = defaultdict(set)  # width -> set of half cps
        for half_cp in half_cps:
            hw = glyph_width(half_cp)
            half_by_width[hw].add(half_cp)

        # --- Group (half_width, base) pairs by variant index ---
        # For half+base: var_idx = clamp(half_width + anchor_x + 2, 6, 21) - 6
        # Key: (half_width, var_idx) -> set of base cps
        i_hw_base = defaultdict(lambda: defaultdict(set))
        for hw, _ in sorted(half_by_width.items()):
            for cp in base_cps:
                ax = anchor_x(cp)
                var_idx = min(max(hw + ax + 2, 6), 21) - 6
                i_hw_base[hw][var_idx].add(cp)

        # --- Group (half_width1, half_width2, base) by variant index ---
        # For half+half+base: var_idx = clamp(hw1 + hw2 + anchor_x + 2, 6, 21) - 6
        i_hww_base = defaultdict(lambda: defaultdict(set))
        half_widths = sorted(half_by_width.keys())
        for hw1 in half_widths:
            for hw2 in half_widths:
                for cp in base_cps:
                    ax = anchor_x(cp)
                    var_idx = min(max(hw1 + hw2 + ax + 2, 6), 21) - 6
                    i_hww_base[(hw1, hw2)][var_idx].add(cp)

        # Build psts feature rules
        # Rules must be ordered longest-context-first (first match wins)
        psts_i_lines = []

        # Case C: half + half + base (4-glyph context)
        # Use width-class groups: @halfW{w} for half consonants of width w
        hh_class_idx = 0
        for (hw1, hw2), var_groups in sorted(i_hww_base.items()):
            for var_idx, bases in sorted(var_groups.items()):
                if not has(0xF0110 + var_idx):
                    continue
                base_names = ' '.join(glyph_name(cp) for cp in sorted(bases))
                h1_names = ' '.join(glyph_name(cp) for cp in sorted(half_by_width[hw1]))
                h2_names = ' '.join(glyph_name(cp) for cp in sorted(half_by_width[hw2]))
                cls_b = f"@iHH{hh_class_idx}"
                cls_h1 = f"@iHH1_{hh_class_idx}"
                cls_h2 = f"@iHH2_{hh_class_idx}"
                psts_i_lines.append(f"    {cls_b} = [{base_names}];")
                psts_i_lines.append(f"    {cls_h1} = [{h1_names}];")
                psts_i_lines.append(f"    {cls_h2} = [{h2_names}];")
                psts_i_lines.append(
                    f"    sub {glyph_name(0x093F)}' lookup IMatraVar{var_idx} "
                    f"{cls_h1} {cls_h2} {cls_b};"
                )
                hh_class_idx += 1

        # Case B: half + base (3-glyph context)
        hb_class_idx = 0
        for hw, var_groups in sorted(i_hw_base.items()):
            for var_idx, bases in sorted(var_groups.items()):
                if not has(0xF0110 + var_idx):
                    continue
                base_names = ' '.join(glyph_name(cp) for cp in sorted(bases))
                h_names = ' '.join(glyph_name(cp) for cp in sorted(half_by_width[hw]))
                cls_b = f"@iHB{hb_class_idx}"
                cls_h = f"@iH{hb_class_idx}"
                psts_i_lines.append(f"    {cls_b} = [{base_names}];")
                psts_i_lines.append(f"    {cls_h} = [{h_names}];")
                psts_i_lines.append(
                    f"    sub {glyph_name(0x093F)}' lookup IMatraVar{var_idx} "
                    f"{cls_h} {cls_b};"
                )
                hb_class_idx += 1

        # Case A: base only (2-glyph context)
        for var_idx, bases in sorted(i_base_groups.items()):
            if not has(0xF0110 + var_idx):
                continue
            base_names = ' '.join(glyph_name(cp) for cp in sorted(bases))
            cls = f"@iB{var_idx}"
            psts_i_lines.append(f"    {cls} = [{base_names}];")
            psts_i_lines.append(
                f"    sub {glyph_name(0x093F)}' lookup IMatraVar{var_idx} {cls};"
            )

    else:
        psts_i_lines = []

    # ===== II-matra variant lookups and rules =====
    if has(0x0940) and has_ii_variants:
        # Create 16 single-substitution lookups
        for var in range(16):
            target = 0xF0120 + var
            if has(target):
                lines.append(f"lookup IIMatraVar{var} {{")
                lines.append(f"    sub {glyph_name(0x0940)} by {glyph_name(target)};")
                lines.append(f"}} IIMatraVar{var};")

        # Group base consonants by II-matra variant index
        # Formula: var_idx = 15 - (clamp(width - anchor_x + 1, 4, 19) - 4)
        # (0xF012F - result gives the codepoint, so var_idx 0 = 0xF012F,
        #  var_idx 15 = 0xF0120; we reverse so var_idx 0 maps to 0xF0120)
        # Actually from the plan: 0xF012F - (clamp(w+1, 4, 19) - 4)
        # where w = width - anchor_x
        # So the PUA codepoint = 0xF012F - (clamp(w+1, 4, 19) - 4)
        # If we define var_idx as offset from 0xF0120:
        #   pua = 0xF0120 + var_idx
        #   var_idx = 0xF012F - (clamp(w+1, 4, 19) - 4) - 0xF0120
        #           = 15 - (clamp(w+1, 4, 19) - 4)
        ii_base_groups = defaultdict(set)
        for cp in sorted(base_cps):
            w = glyph_width(cp) - anchor_x(cp)
            clamped = min(max(w + 1, 4), 19) - 4
            var_idx = 15 - clamped  # 0xF012F - clamped → offset from 0xF0120
            ii_base_groups[var_idx].add(cp)

        psts_ii_lines = []
        for var_idx, bases in sorted(ii_base_groups.items()):
            target = 0xF0120 + var_idx
            if not has(target):
                continue
            base_names = ' '.join(glyph_name(cp) for cp in sorted(bases))
            cls = f"@iiB{var_idx}"
            psts_ii_lines.append(f"    {cls} = [{base_names}];")
            psts_ii_lines.append(
                f"    sub {cls} {glyph_name(0x0940)}' lookup IIMatraVar{var_idx};"
            )
    else:
        psts_ii_lines = []

    if not psts_i_lines and not psts_ii_lines:
        return [], []

    return lines, psts_i_lines + psts_ii_lines


def _generate_psts_anusvara(glyphs, has, conjuncts):
    """No longer used — anusvara GSUB moved to ccmp (pre-reordering).

    Returns empty lists for backwards compatibility with the psts assembly.
    """
    return [], []


def _generate_psts_open_ya(glyphs, has):
    """Generate psts rules for open Ya substitution.

    In the bitmap font, Ya (uF015A) uses an "open" variant (uF0108) when it
    follows certain half-form consonants.  Half-Ya (uF024A) similarly becomes
    open-half-Ya (uF0109).

    Returns (lookup_lines, feature_body_lines) like _generate_psts_matra_variants.
    """
    OPEN_YA = 0xF0108
    OPEN_HALF_YA = 0xF0109
    YA_INT = 0xF015A       # 0x092F.toDevaInternal()
    HALF_YA = YA_INT + 240  # 0xF024A

    if not has(OPEN_YA) or not has(YA_INT):
        return [], []

    # Consonants whose half forms trigger open Ya (from Kotlin ligateIndicConsonants):
    # 1. Basic consonants: KA, NGA, CHA, TTA, TTH, DD, DDH
    open_ya_full = {
        SC.to_deva_internal(cp)
        for cp in [0x0915, 0x0919, 0x091B, 0x091F, 0x0920, 0x0921, 0x0922]
    }
    # 2. D.RA
    open_ya_full.add(0xF0331)
    # 3. Conjuncts in 0xF01B0..0xF01DF
    for cp in range(0xF01B0, 0xF01E0):
        if has(cp):
            open_ya_full.add(cp)
    # 4. RA-appended conjuncts in 0xF0390..0xF03BF
    for cp in range(0xF0390, 0xF03C0):
        if has(cp):
            open_ya_full.add(cp)

    # Collect the HALF forms of all these consonants
    open_ya_halfs = set()
    for cp in open_ya_full:
        half = cp + 240
        if has(half):
            open_ya_halfs.add(half)

    if not open_ya_halfs:
        return [], []

    lookups = []
    lookups.append(f"lookup OpenYa {{")
    lookups.append(f"    sub {glyph_name(YA_INT)} by {glyph_name(OPEN_YA)};")
    lookups.append(f"}} OpenYa;")

    if has(OPEN_HALF_YA) and has(HALF_YA):
        lookups.append(f"lookup OpenHalfYa {{")
        lookups.append(f"    sub {glyph_name(HALF_YA)} by {glyph_name(OPEN_HALF_YA)};")
        lookups.append(f"}} OpenHalfYa;")

    body = []
    half_names = ' '.join(glyph_name(cp) for cp in sorted(open_ya_halfs))
    body.append(f"    @openYaHalfs = [{half_names}];")
    body.append(f"    sub @openYaHalfs {glyph_name(YA_INT)}' lookup OpenYa;")
    if has(OPEN_HALF_YA) and has(HALF_YA):
        body.append(f"    sub @openYaHalfs {glyph_name(HALF_YA)}' lookup OpenHalfYa;")

    return lookups, body


def _generate_tamil(glyphs, has, replacewith_subs=None):
    """Generate Tamil GSUB features (ccmp + pres under tml2)."""
    features = []

    # --- tml2 ccmp: Tamil replacewith decompositions ---
    # Must be under tml2 so DirectWrite/CoreText see them.
    if replacewith_subs:
        tamil_ccmp = []
        for src_cp, target_cps in replacewith_subs:
            if not (0x0B80 <= src_cp <= 0x0BFF):
                continue
            if not has(src_cp) or not all(has(t) for t in target_cps):
                continue
            src = glyph_name(src_cp)
            targets = ' '.join(glyph_name(t) for t in target_cps)
            tamil_ccmp.append(f"        sub {src} by {targets};")
        if tamil_ccmp:
            features.append("feature ccmp {\n    script tml2;\n"
                            "    lookup TamilDecomp {\n"
                            + '\n'.join(tamil_ccmp)
                            + "\n    } TamilDecomp;\n} ccmp;")

    subs = []

    _tamil_i_rules = [
        (0x0B99, 0xF00F0, "nga+i"),
        (0x0BAA, 0xF00F1, "pa+i"),
        (0x0BAF, 0xF00F2, "ya+i"),
        (0x0BB2, 0xF00F3, "la+i"),
        (0x0BB5, 0xF00F4, "va+i"),
        (0x0BB8, 0xF00F5, "sa+i"),
    ]
    for cons, result, name in _tamil_i_rules:
        if has(cons) and has(SC.TAMIL_I) and has(result):
            subs.append(f"    sub {glyph_name(cons)} {glyph_name(SC.TAMIL_I)} by {glyph_name(result)}; # {name}")

    if has(0x0B9F) and has(0x0BBF) and has(0xF00C0):
        subs.append(f"    sub {glyph_name(0x0B9F)} {glyph_name(0x0BBF)} by {glyph_name(0xF00C0)}; # tta+i")
    if has(0x0B9F) and has(0x0BC0) and has(0xF00C1):
        subs.append(f"    sub {glyph_name(0x0B9F)} {glyph_name(0x0BC0)} by {glyph_name(0xF00C1)}; # tta+ii")

    for idx, cons in enumerate(SC.TAMIL_LIGATING_CONSONANTS):
        u_form = 0xF00C2 + idx
        uu_form = 0xF00D4 + idx
        if has(cons) and has(0x0BC1) and has(u_form):
            subs.append(f"    sub {glyph_name(cons)} {glyph_name(0x0BC1)} by {glyph_name(u_form)};")
        if has(cons) and has(0x0BC2) and has(uu_form):
            subs.append(f"    sub {glyph_name(cons)} {glyph_name(0x0BC2)} by {glyph_name(uu_form)};")

    if has(0x0B95) and has(0x0BCD) and has(0x0BB7) and has(SC.TAMIL_KSSA):
        subs.append(f"    sub {glyph_name(0x0B95)} {glyph_name(0x0BCD)} {glyph_name(0x0BB7)} by {glyph_name(SC.TAMIL_KSSA)}; # KSSA")

    if has(0x0BB6) and has(0x0BCD) and has(0x0BB0) and has(0x0BC0) and has(SC.TAMIL_SHRII):
        subs.append(f"    sub {glyph_name(0x0BB6)} {glyph_name(0x0BCD)} {glyph_name(0x0BB0)} {glyph_name(0x0BC0)} by {glyph_name(SC.TAMIL_SHRII)}; # SHRII (sha)")
    if has(0x0BB8) and has(0x0BCD) and has(0x0BB0) and has(0x0BC0) and has(SC.TAMIL_SHRII):
        subs.append(f"    sub {glyph_name(0x0BB8)} {glyph_name(0x0BCD)} {glyph_name(0x0BB0)} {glyph_name(0x0BC0)} by {glyph_name(SC.TAMIL_SHRII)}; # SHRII (sa)")

    if subs:
        lines = ["feature pres {", "    script tml2;"]
        lines.extend(subs)
        lines.append("} pres;")
        features.append('\n'.join(lines))

    return '\n\n'.join(features) if features else ""


def _generate_sundanese(glyphs, has):
    """Generate Sundanese GSUB feature for diacritic combinations."""
    subs = []
    _rules = [
        (0x1BA4, 0x1B80, SC.SUNDANESE_ING, "panghulu+panyecek=ing"),
        (0x1BA8, 0x1B80, SC.SUNDANESE_ENG, "pamepet+panyecek=eng"),
        (0x1BA9, 0x1B80, SC.SUNDANESE_EUNG, "paneuleung+panyecek=eung"),
        (0x1BA4, 0x1B81, SC.SUNDANESE_IR, "panghulu+panglayar=ir"),
        (0x1BA8, 0x1B81, SC.SUNDANESE_ER, "pamepet+panglayar=er"),
        (0x1BA9, 0x1B81, SC.SUNDANESE_EUR, "paneuleung+panglayar=eur"),
        (0x1BA3, 0x1BA5, SC.SUNDANESE_LU, "panyuku+panglayar=lu"),
    ]
    for c1, c2, result, name in _rules:
        if has(c1) and has(c2) and has(result):
            subs.append(f"    sub {glyph_name(c1)} {glyph_name(c2)} by {glyph_name(result)}; # {name}")

    if not subs:
        return ""

    lines = ["feature pres {", "    script sund;"]
    lines.extend(subs)
    lines.append("} pres;")
    return '\n'.join(lines)


def _generate_mark(glyphs, has):
    """
    Generate GPOS mark-to-base positioning using diacritics anchors from tag column.

    Marks are grouped by (writeOnTop, alignment) into separate mark classes
    and lookups.  Different alignments need different default base anchor
    positions to match the Kotlin engine's fallback behaviour:
      - ALIGN_CENTRE: base anchor at width/2
      - ALIGN_RIGHT:  base anchor at width
      - ALIGN_LEFT/BEFORE: base anchor at 0 (mark sits at base origin)
    """
    # Collect ALL non-mark glyphs as potential bases (excluding CJK
    # ideographs and Braille which are unlikely to receive combining marks
    # and would bloat the GPOS table).
    _EXCLUDE_RANGES = (
        range(0x3400, 0xA000),   # CJK Unified Ideographs (Ext A + main)
        range(0xAC00, 0xD800),   # Hangul Syllables
        range(0x2800, 0x2900),   # Braille
    )
    # I-matra glyphs excluded from MarkToBase (they should not attract
    # mark attachment — marks attach to the consonant, not the matra).
    _EXCLUDE_CPS = {0x093F} | set(range(0xF0110, 0xF0120))
    all_bases = {}
    marks = {}

    for cp, g in glyphs.items():
        if not has(cp):
            continue
        if g.props.write_on_top >= 0:
            marks[cp] = g
        elif g.bitmap and g.props.width > 0:
            if cp not in _EXCLUDE_CPS and not any(cp in r for r in _EXCLUDE_RANGES):
                all_bases[cp] = g

    if not all_bases or not marks:
        return ""

    lines = []
    mark_anchors = {}  # cp -> mark_x, for MarkToMark mark2 anchor computation

    _align_suffix = {
        SC.ALIGN_LEFT: 'l',
        SC.ALIGN_RIGHT: 'r',
        SC.ALIGN_CENTRE: 'c',
        SC.ALIGN_BEFORE: 'b',
    }

    # Group marks by (writeOnTop, alignment, isDiacriticalMark, stackCat).
    # Diacritical marks (U+0300-036F) need separate classes because their
    # base anchors are adjusted for lowheight bases (e.g. lowercase 'e').
    # Type-0 (above): shift down 4px; Type-2 (overlay): shift down 2px.
    # Stack category splits stacking marks from non-stacking ones so that
    # MarkToMark only chains marks that actually stack together.
    def _stack_cat(sw):
        if sw in (SC.STACK_UP, SC.STACK_UP_N_DOWN):
            return 'up'
        elif sw == SC.STACK_DOWN:
            return 'dn'
        else:
            return 'ns'

    mark_groups = {}  # (mark_type, align, is_dia, stack_cat) -> [(cp, g), ...]
    for cp, g in marks.items():
        is_dia = (0x0300 <= cp <= 0x036F)
        sc = _stack_cat(g.props.stack_where)
        key = (g.props.write_on_top, g.props.align_where, is_dia, sc)
        mark_groups.setdefault(key, []).append((cp, g))

    # Emit markClass definitions
    for (mark_type, align, is_dia, scat), mark_list in sorted(mark_groups.items()):
        suffix = _align_suffix.get(align, 'x')
        class_name = f"@mark_t{mark_type}_{suffix}" + ("_dia" if is_dia else "") + f"_{scat}"
        for cp, g in mark_list:
            if align == SC.ALIGN_CENTRE:
                # Match Kotlin: anchorPoint - HALF_VAR_INIT centres the
                # cell on the anchor.  For U+0900-0902 the Kotlin engine
                # uses (W_VAR_INIT + 1) / 2 instead (1 px nudge left).
                bm_cols = SC.W_VAR_INIT
                if 0x0900 <= cp <= 0x0902:
                    half = (SC.W_VAR_INIT + 1) // 2
                else:
                    half = (SC.W_VAR_INIT - 1) // 2
                x_offset = math.ceil((g.props.width - bm_cols) / 2) * SC.SCALE
                x_offset -= g.props.nudge_x * SC.SCALE
                mark_x = x_offset + half * SC.SCALE
            elif align == SC.ALIGN_RIGHT:
                # nudge_x is already baked into the CFF contour x_offset
                # (font_builder.py line 286).  Setting mark_x = 0 means
                # the nudge is applied once (via the contour), not twice.
                mark_x = 0
            else:
                # ALIGN_LEFT / ALIGN_BEFORE: mark sits at base origin.
                mark_x = 0
            mark_y = SC.ASCENT
            mark_anchors[cp] = mark_x
            lines.append(
                f"markClass {glyph_name(cp)} <anchor {mark_x} {mark_y}> {class_name};"
            )

    # Generate one lookup per (mark_type, align, is_dia, stack_cat) group.
    lookup_names = []
    for (mark_type, align, is_dia, scat), mark_list in sorted(mark_groups.items()):
        suffix = _align_suffix.get(align, 'x')
        class_name = f"@mark_t{mark_type}_{suffix}" + ("_dia" if is_dia else "") + f"_{scat}"
        lookup_name = f"mark_t{mark_type}_{suffix}" + ("_dia" if is_dia else "") + f"_{scat}"
        lines.append(f"lookup {lookup_name} {{")

        for cp, g in sorted(all_bases.items()):
            anchor = (g.props.diacritics_anchors[mark_type]
                      if mark_type < len(g.props.diacritics_anchors)
                      else None)
            has_explicit = anchor and (anchor.x_used or anchor.y_used)

            # Determine the anchor x for this mark_type.
            # Subtract nudge_x because in Kotlin the base position
            # already includes -nudgeX (posX = -nudgeX + ...),
            # so the anchor is relative to the shifted position.
            # In OTF, nudge_x is baked into the contour x_offset
            # but not the advance, so the base anchor must also
            # account for it.
            anchor_x = ((anchor.x if (has_explicit and anchor.x_used)
                         else g.props.width // 2)
                        - g.props.nudge_x)
            ay = ((SC.ASCENT // SC.SCALE - anchor.y) * SC.SCALE
                  if (has_explicit and anchor.y_used) else SC.ASCENT)

            W = g.props.width
            ba = g.props.align_where

            if align in (SC.ALIGN_LEFT, SC.ALIGN_BEFORE):
                # Kotlin ignores base anchors for these alignments;
                # the mark always sits at posX[base].
                ax = 0
                ay = SC.ASCENT
            elif align == SC.ALIGN_CENTRE:
                # Kotlin uses different formulas depending on whether
                # the base is ALIGN_RIGHT or not (line 1237 vs 1243).
                if ba == SC.ALIGN_RIGHT:
                    # posX[mark] = posX[base] + anchorX + (W+1)//2
                    ax = (anchor_x + W + (W + 1) // 2
                          - SC.W_VAR_INIT
                          + (SC.W_VAR_INIT - 1) // 2) * SC.SCALE
                elif ba == SC.ALIGN_CENTRE:
                    ax = (anchor_x
                          + math.ceil((W - SC.W_VAR_INIT) / 2)
                          ) * SC.SCALE
                else:
                    # ALIGN_LEFT / ALIGN_BEFORE
                    ax = anchor_x * SC.SCALE
            elif align == SC.ALIGN_RIGHT:
                if not has_explicit:
                    ax = W * SC.SCALE
                else:
                    ax = (anchor.x if anchor.x_used
                          else W) * SC.SCALE

            # Lowheight adjustment for combining diacritical marks:
            # shift base anchor Y down so diacritics sit closer to
            # the shorter base glyph.
            if is_dia and g.props.is_low_height:
                if mark_type == 2:  # overlay
                    ay -= SC.H_OVERLAY_LOWERCASE_SHIFTDOWN * SC.SCALE
                else:  # above (type 0)
                    ay -= SC.H_STACKUP_LOWERCASE_SHIFTDOWN * SC.SCALE

            lines.append(
                f"    pos base {glyph_name(cp)}"
                f" <anchor {ax} {ay}> mark {class_name};"
            )

        lines.append(f"}} {lookup_name};")
        lines.append("")
        lookup_names.append(lookup_name)

    # --- MarkToMark lookups for diacritics stacking ---
    # When multiple marks of the same type stack on a base, MarkToMark
    # positions each successive mark relative to the previous one,
    # shifted by H_DIACRITICS pixels in the stacking direction.
    # Only 'up' and 'dn' groups participate; 'ns' (non-stacking) marks
    # are excluded so they don't get repositioned by MarkToMark.
    mkmk_lookup_names = []
    for (mark_type, align, is_dia, scat), mark_list in sorted(mark_groups.items()):
        if scat == 'ns':
            continue

        suffix = _align_suffix.get(align, 'x')
        class_name = f"@mark_t{mark_type}_{suffix}" + ("_dia" if is_dia else "") + f"_{scat}"
        mkmk_name = f"mkmk_t{mark_type}_{suffix}" + ("_dia" if is_dia else "") + f"_{scat}"
        lines.append(f"lookup {mkmk_name} {{")

        if scat == 'up':
            m2y = SC.ASCENT + SC.H_DIACRITICS * SC.SCALE
        else:  # 'dn'
            m2y = SC.ASCENT - SC.H_DIACRITICS * SC.SCALE

        for cp, g in mark_list:
            mx = mark_anchors.get(cp, 0)
            lines.append(
                f"    pos mark {glyph_name(cp)}"
                f" <anchor {mx} {m2y}> mark {class_name};"
            )

        lines.append(f"}} {mkmk_name};")
        lines.append("")
        mkmk_lookup_names.append(mkmk_name)

    # Register MarkToBase lookups under mark for non-Devanagari scripts.
    # For dev2/deva, abvm already includes these lookups.  Registering
    # mark/mkmk under dev2/deva too risks double-application on shapers
    # (CoreText, DirectWrite) that may process mark AND abvm separately.
    _NON_DEVA_SCRIPTS = ['DFLT', 'latn', 'cyrl', 'grek', 'hang', 'tml2', 'sund']
    lines.append("feature mark {")
    for _st in _NON_DEVA_SCRIPTS:
        lines.append(f"    script {_st};")
        for ln in lookup_names:
            lines.append(f"    lookup {ln};")
    lines.append("} mark;")

    # Register MarkToMark lookups under mkmk (non-Devanagari only)
    if mkmk_lookup_names:
        lines.append("")
        lines.append("feature mkmk {")
        for _st in _NON_DEVA_SCRIPTS:
            lines.append(f"    script {_st};")
            for ln in mkmk_lookup_names:
                lines.append(f"    lookup {ln};")
        lines.append("} mkmk;")

    # For Devanagari, HarfBuzz's Indic v2 shaper uses abvm/blwm
    # features for mark positioning, not the generic 'mark' feature.
    # Register the same lookups under abvm for both dev2 and deva scripts.
    lines.append("")
    lines.append("feature abvm {")
    for _st in ['dev2', 'deva']:
        lines.append(f"    script {_st};")
        for ln in lookup_names:
            lines.append(f"    lookup {ln};")
        for ln in mkmk_lookup_names:
            lines.append(f"    lookup {ln};")
    lines.append("} abvm;")

    return '\n'.join(lines)


def _generate_anusvara_gpos(glyphs, has):
    """Generate GPOS contextual positioning for both anusvara forms.

    For uF016C (anusvara upper):
    - complex reph: +3px X, -2px Y
    - uni094F (or reph + 094F): +3px X
    - 0x093A, 0x0948, 0x094C (or reph + these): +2px X

    For uni0902 (regular anusvara):
    - complex reph: +3px X, -2px Y
    - simple reph: +2px X

    This MUST be appended AFTER _generate_mark() output so its abvm lookups
    come after mark-to-base lookups in the LookupList.  MarkToBase SETS the
    mark offset; the subsequent SinglePos ADDS to it.
    """
    anusvara_upper = SC.DEVANAGARI_ANUSVARA_UPPER
    anusvara = 0x0902
    complex_reph = SC.DEVANAGARI_RA_SUPER_COMPLEX
    simple_reph = SC.DEVANAGARI_RA_SUPER

    has_upper = has(anusvara_upper)
    has_regular = has(anusvara)

    if not has_upper and not has_regular:
        return ""

    lines = []

    # --- Lookups for anusvara upper (uF016C) ---
    if has_upper:
        lines.append(f"lookup AnusvaraUpperShift2 {{")
        lines.append(f"    pos {glyph_name(anusvara_upper)} <100 0 0 0>;")
        lines.append(f"}} AnusvaraUpperShift2;")

        lines.append(f"lookup AnusvaraUpperShift3 {{")
        lines.append(f"    pos {glyph_name(anusvara_upper)} <150 0 0 0>;")
        lines.append(f"}} AnusvaraUpperShift3;")

    # --- Lookups for regular anusvara (uni0902) ---
    if has_regular:
        lines.append(f"lookup AnusvaraRegShift2 {{")
        lines.append(f"    pos {glyph_name(anusvara)} <100 0 0 0>;")
        lines.append(f"}} AnusvaraRegShift2;")

    # --- MarkToMark: anusvara attaches to complex reph ---
    # Without explicit MarkToMark, two marks on the same base get
    # shaper-specific heuristic stacking (HarfBuzz, DirectWrite, and
    # CoreText all disagree by ~100 units).  MarkToMark gives the font
    # explicit control and suppresses those heuristics.
    has_mkmk = False
    if has(complex_reph):
        mkmk_lines = []
        if has_upper:
            mkmk_lines.append(
                f"    markClass {glyph_name(anusvara_upper)}"
                f" <anchor 100 800> @anuUpperToReph;")
        if has_regular:
            mkmk_lines.append(
                f"    markClass {glyph_name(anusvara)}"
                f" <anchor 150 800> @anuRegToReph;")
        if has_upper:
            mkmk_lines.append(
                f"    pos mark {glyph_name(complex_reph)}"
                f" <anchor 150 800> mark @anuUpperToReph;")
        if has_regular:
            mkmk_lines.append(
                f"    pos mark {glyph_name(complex_reph)}"
                f" <anchor 150 800> mark @anuRegToReph;")
        if mkmk_lines:
            lines.append("")
            lines.append("lookup AnusvaraToComplexReph {")
            lines.extend(mkmk_lines)
            lines.append("} AnusvaraToComplexReph;")
            has_mkmk = True

    # Collect contextual positioning rules into NAMED lookups so that
    # both dev2 and deva script sections reference the SAME lookup index.
    # Without this, feaLib creates separate anonymous lookups for each
    # script section, and shapers that merge both dev2/deva features
    # (CoreText, DirectWrite) would apply the shift TWICE.
    #
    # NOTE: complex_reph + anusvara cases are handled by MarkToMark
    # above (AnusvaraToComplexReph), NOT by ChainContextPos.
    abvm_rules = []

    # --- Rules for anusvara upper (uF016C) ---
    # After reordering: base, [matras], reph?, anusvara.
    # When reph is present between matra and anusvara, use 3-glyph backtrack.
    # Rules ordered longest-context-first (first match wins).
    if has_upper:
        # Matra + simple reph + anusvara (3-glyph context: matra in backtrack)
        if has(simple_reph):
            if has(0x094F):
                abvm_rules.append(
                    f"    pos {glyph_name(0x094F)} {glyph_name(simple_reph)}"
                    f" {glyph_name(anusvara_upper)}' lookup AnusvaraUpperShift3;"
                )
            for cp in [0x093A, 0x0948, 0x094C]:
                if has(cp):
                    abvm_rules.append(
                        f"    pos {glyph_name(cp)} {glyph_name(simple_reph)}"
                        f" {glyph_name(anusvara_upper)}' lookup AnusvaraUpperShift2;"
                    )

        # Matra directly before anusvara (no reph)
        if has(0x094F):
            abvm_rules.append(
                f"    pos {glyph_name(0x094F)}"
                f" {glyph_name(anusvara_upper)}' lookup AnusvaraUpperShift3;"
            )
        for cp in [0x093A, 0x0948, 0x094C]:
            if has(cp):
                abvm_rules.append(
                    f"    pos {glyph_name(cp)}"
                    f" {glyph_name(anusvara_upper)}' lookup AnusvaraUpperShift2;"
                )

    # --- Rules for regular anusvara (uni0902) ---
    # Regular anusvara has no matra trigger (else it would be upper).
    # Complex reph case handled by MarkToMark; only simple reph here.
    if has_regular:
        # Simple reph → +2px X
        if has(simple_reph):
            abvm_rules.append(
                f"    pos {glyph_name(simple_reph)}"
                f" {glyph_name(anusvara)}' lookup AnusvaraRegShift2;"
            )

    # --- Emit named lookup ---
    if abvm_rules:
        lines.append("")
        lines.append("lookup AnusvaraCtxShift {")
        lines.extend(abvm_rules)
        lines.append("} AnusvaraCtxShift;")

    lines.append("")
    lines.append("feature abvm {")
    for _st in ['dev2', 'deva']:
        lines.append(f"    script {_st};")
        if has_mkmk:
            lines.append("    lookup AnusvaraToComplexReph;")
        if abvm_rules:
            lines.append("    lookup AnusvaraCtxShift;")
    lines.append("} abvm;")

    return '\n'.join(lines)


def _generate_tone_bars(has):
    """
    Generate GSUB lookups for IPA tone bar graphs (U+02E5-U+02E9).

    When two or more modifier letter tone bars appear consecutively,
    they are ligated into graph glyphs:
    - Each pair produces: hairspace (U+200A) + graph glyph (U+FFE20 + tone1*5 + tone2)
    - The final tone bar in a sequence becomes an end cap (U+FFE39)
    - Lone tone bars are left unchanged.
    """
    tone_bars = [0x02E5 + i for i in range(5)]
    hairspace = 0x200A
    endcap = 0xFFE39

    # Check required glyphs exist
    if not all(has(tb) for tb in tone_bars):
        return ""
    if not has(hairspace) or not has(endcap):
        return ""

    lines = []

    # Step 1: Multiple substitution lookups for each (tone1, tone2) pair.
    # Each lookup maps one tone bar to hairspace + graph glyph.
    # These are standalone lookups, only invoked via chaining context.
    valid_pairs = []
    for i in range(5):
        for j in range(5):
            graph_cp = 0xFFE20 + i * 5 + j
            if has(graph_cp):
                lookup_name = f"tone_{i}_{j}"
                lines.append(f"lookup {lookup_name} {{")
                lines.append(f"    sub {glyph_name(tone_bars[i])} by"
                             f" {glyph_name(hairspace)} {glyph_name(graph_cp)};")
                lines.append(f"}} {lookup_name};")
                lines.append("")
                valid_pairs.append((i, j))

    if not valid_pairs:
        return ""

    # Step 2: End cap single substitution lookup.
    # Replaces any tone bar with the end cap glyph.
    lines.append("lookup tone_endcap {")
    for i in range(5):
        lines.append(f"    sub {glyph_name(tone_bars[i])} by {glyph_name(endcap)};")
    lines.append("} tone_endcap;")
    lines.append("")

    # Step 3: Chaining contextual substitution for pairs.
    # When tone_bar_i is followed by tone_bar_j, apply multisub to replace
    # tone_bar_i with hairspace + graph(i,j). The following tone_bar_j is
    # lookahead context only, so it remains for the next pair.
    lines.append("lookup ToneBarPairs {")
    for i, j in valid_pairs:
        lines.append(f"    sub {glyph_name(tone_bars[i])}'"
                     f" lookup tone_{i}_{j}"
                     f" {glyph_name(tone_bars[j])};")
    lines.append("} ToneBarPairs;")
    lines.append("")

    # Step 4: Chaining contextual substitution for end cap.
    # After ToneBarPairs, the last tone bar in a sequence is preceded by
    # a graph glyph. Replace it with the end cap.
    graph_names = []
    for cp in range(0xFFE20, 0xFFE39):
        if has(cp):
            graph_names.append(glyph_name(cp))

    if graph_names:
        lines.append(f"@tone_graph = [{' '.join(graph_names)}];")
        lines.append("")
        lines.append("lookup ToneBarEndCap {")
        for i in range(5):
            lines.append(f"    sub @tone_graph"
                         f" {glyph_name(tone_bars[i])}' lookup tone_endcap;")
        lines.append("} ToneBarEndCap;")
        lines.append("")

    # Register lookups in liga feature
    lines.append("feature liga {")
    lines.append("    lookup ToneBarPairs;")
    if graph_names:
        lines.append("    lookup ToneBarEndCap;")
    lines.append("} liga;")

    return '\n'.join(lines)
