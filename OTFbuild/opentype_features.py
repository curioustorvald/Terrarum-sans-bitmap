"""
Generate OpenType feature code (feaLib syntax) for GSUB/GPOS tables.

Features implemented:
- kern: GPOS pair positioning from KemingMachine
- liga: Standard ligatures (Alphabetic Presentation Forms)
- locl: Bulgarian/Serbian Cyrillic variants
- Devanagari GSUB: nukt, akhn, half, vatu, pres, blws, rphf
- Tamil GSUB: consonant+vowel ligatures, KSSA, SHRII
- Sundanese GSUB: diacritic combinations
- mark: GPOS mark-to-base positioning (diacritics anchors)
"""

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
    deva_code = _generate_devanagari(glyphs, has)
    if deva_code:
        parts.append(deva_code)

    # Tamil features
    tamil_code = _generate_tamil(glyphs, has)
    if tamil_code:
        parts.append(tamil_code)

    # Sundanese features
    sund_code = _generate_sundanese(glyphs, has)
    if sund_code:
        parts.append(sund_code)

    # mark feature
    mark_code = _generate_mark(glyphs, has)
    if mark_code:
        parts.append(mark_code)

    return '\n\n'.join(parts)


def _generate_ccmp(replacewith_subs, has):
    """Generate ccmp feature for replacewith directives (multiple substitution)."""
    if not replacewith_subs:
        return ""

    subs = []
    for src_cp, target_cps in replacewith_subs:
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
      - Choseong row depends on which jungseong follows and whether jongseong exists
      - Jungseong row is 15 (no final) or 16 (with final)
      - Jongseong row is 17 (normal) or 18 (rightie jungseong)
    """
    if not jamo_data:
        return ""

    pua_fn = jamo_data['pua_fn']

    # Build contextual substitution lookups
    # Strategy: use ljmo/vjmo/tjmo features (standard Hangul OpenType features)
    #
    # ljmo: choseong → positional variant (depends on following jungseong)
    # vjmo: jungseong → positional variant (depends on whether jongseong follows)
    # tjmo: jongseong → positional variant (depends on preceding jungseong)

    lines = []

    # --- ljmo: Choseong variant selection ---
    # For each choseong, we need variants for different jungseong contexts.
    # Row 1 is the default (basic vowels like ㅏ).
    # We use contextual alternates: choseong' lookup X jungseong
    ljmo_lookups = []

    # Group jungseong indices by which choseong row they select
    # From getHanInitialRow: the row depends on jungseong index (p) and has-final (f)
    # For GSUB, we pre-compute for f=0 (no final) since we can't know yet
    row_to_jung_indices = {}
    for p in range(96):  # all possible jungseong indices
        # Without jongseong first; use i=1 to avoid giyeok edge cases
        try:
            row_nf = SC.get_han_initial_row(1, p, 0)
        except (ValueError, KeyError):
            continue
        if row_nf not in row_to_jung_indices:
            row_to_jung_indices[row_nf] = []
        row_to_jung_indices[row_nf].append(p)

    # For each unique choseong row, create a lookup that substitutes
    # the default choseong glyph with the variant at that row
    for cho_row, jung_indices in sorted(row_to_jung_indices.items()):
        if cho_row == 1:
            continue  # row 1 is the default, no substitution needed

        lookup_name = f"ljmo_row{cho_row}"
        subs = []

        # For standard choseong (U+1100-U+115E)
        for cho_cp in range(0x1100, 0x115F):
            col = cho_cp - 0x1100
            variant_pua = pua_fn(col, cho_row)
            if has(cho_cp) and has(variant_pua):
                subs.append(f"        sub {glyph_name(cho_cp)} by {glyph_name(variant_pua)};")

        if subs:
            lines.append(f"lookup {lookup_name} {{")
            lines.extend(subs)
            lines.append(f"}} {lookup_name};")
            ljmo_lookups.append((lookup_name, jung_indices))

    # --- vjmo: Jungseong variant selection ---
    # Row 15 = no jongseong following, Row 16 = jongseong follows
    # We need two lookups
    vjmo_subs_16 = []  # with-final variant (row 16)
    for jung_cp in range(0x1161, 0x11A8):
        col = jung_cp - 0x1160
        variant_pua = pua_fn(col, 16)
        if has(jung_cp) and has(variant_pua):
            vjmo_subs_16.append(f"    sub {glyph_name(jung_cp)} by {glyph_name(variant_pua)};")

    if vjmo_subs_16:
        lines.append("lookup vjmo_withfinal {")
        lines.extend(vjmo_subs_16)
        lines.append("} vjmo_withfinal;")

    # --- tjmo: Jongseong variant selection ---
    # Row 17 = normal, Row 18 = after rightie jungseong
    tjmo_subs_18 = []
    for jong_cp in range(0x11A8, 0x1200):
        col = jong_cp - 0x11A8 + 1
        variant_pua = pua_fn(col, 18)
        if has(jong_cp) and has(variant_pua):
            tjmo_subs_18.append(f"    sub {glyph_name(jong_cp)} by {glyph_name(variant_pua)};")

    if tjmo_subs_18:
        lines.append("lookup tjmo_rightie {")
        lines.extend(tjmo_subs_18)
        lines.append("} tjmo_rightie;")

    # --- Build the actual features using contextual substitution ---

    # Jungseong class definitions for contextual rules
    # Build classes of jungseong glyphs that trigger specific choseong rows
    feature_lines = []

    # ljmo feature: contextual choseong substitution
    if ljmo_lookups:
        feature_lines.append("feature ljmo {")
        feature_lines.append("    script hang;")
        for lookup_name, jung_indices in ljmo_lookups:
            # Build jungseong class for this row
            jung_glyphs = []
            for idx in jung_indices:
                cp = 0x1160 + idx
                if has(cp):
                    jung_glyphs.append(glyph_name(cp))
            if not jung_glyphs:
                continue
            class_name = f"@jung_for_{lookup_name}"
            feature_lines.append(f"    {class_name} = [{' '.join(jung_glyphs)}];")

        # Contextual rules: choseong' [lookup X] jungseong
        # For each choseong, if followed by a jungseong in the right class,
        # apply the variant lookup
        for lookup_name, jung_indices in ljmo_lookups:
            jung_glyphs = []
            for idx in jung_indices:
                cp = 0x1160 + idx
                if has(cp):
                    jung_glyphs.append(glyph_name(cp))
            if not jung_glyphs:
                continue
            class_name = f"@jung_for_{lookup_name}"
            # Build choseong class
            cho_glyphs = [glyph_name(cp) for cp in range(0x1100, 0x115F) if has(cp)]
            if cho_glyphs:
                feature_lines.append(f"    @choseong = [{' '.join(cho_glyphs)}];")
                feature_lines.append(f"    sub @choseong' lookup {lookup_name} {class_name};")

        feature_lines.append("} ljmo;")

    # vjmo feature: jungseong gets row 16 variant when followed by jongseong
    if vjmo_subs_16:
        jong_glyphs = [glyph_name(cp) for cp in range(0x11A8, 0x1200) if has(cp)]
        if jong_glyphs:
            feature_lines.append("feature vjmo {")
            feature_lines.append("    script hang;")
            jung_glyphs = [glyph_name(cp) for cp in range(0x1161, 0x11A8) if has(cp)]
            feature_lines.append(f"    @jongseong = [{' '.join(jong_glyphs)}];")
            feature_lines.append(f"    @jungseong = [{' '.join(jung_glyphs)}];")
            feature_lines.append(f"    sub @jungseong' lookup vjmo_withfinal @jongseong;")
            feature_lines.append("} vjmo;")

    # tjmo feature: jongseong gets row 18 variant when after rightie jungseong
    if tjmo_subs_18:
        rightie_glyphs = []
        for idx in sorted(SC.JUNGSEONG_RIGHTIE):
            cp = 0x1160 + idx
            if has(cp):
                rightie_glyphs.append(glyph_name(cp))
            # Also check PUA variants (row 16)
            pua16 = pua_fn(idx, 16)
            if has(pua16):
                rightie_glyphs.append(glyph_name(pua16))
        if rightie_glyphs:
            feature_lines.append("feature tjmo {")
            feature_lines.append("    script hang;")
            feature_lines.append(f"    @rightie_jung = [{' '.join(rightie_glyphs)}];")
            jong_glyphs = [glyph_name(cp) for cp in range(0x11A8, 0x1200) if has(cp)]
            feature_lines.append(f"    @jongseong_all = [{' '.join(jong_glyphs)}];")
            feature_lines.append(f"    sub @rightie_jung @jongseong_all' lookup tjmo_rightie;")
            feature_lines.append("} tjmo;")

    if not lines and not feature_lines:
        return ""

    return '\n'.join(lines + [''] + feature_lines)


def _generate_kern(kern_pairs, has):
    """Generate kern feature from pair positioning data."""
    if not kern_pairs:
        return ""

    lines = ["feature kern {"]
    count = 0
    for (left_cp, right_cp), value in sorted(kern_pairs.items()):
        if has(left_cp) and has(right_cp):
            lines.append(f"    pos {glyph_name(left_cp)} {glyph_name(right_cp)} {value};")
            count += 1

    if count == 0:
        return ""
    lines.append("} kern;")
    return '\n'.join(lines)


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


def _generate_devanagari(glyphs, has):
    """Generate Devanagari GSUB features: ccmp (consonant mapping), nukt, akhn, half, vatu, pres, blws, rphf."""
    features = []

    # --- ccmp: Map Unicode consonants to internal PUA presentation forms ---
    # This is the critical first step: U+0915-0939 have width=0 in the sheet,
    # the actual glyph bitmaps live at their PUA forms (0xF0140+).
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
    if ccmp_subs:
        features.append(
            "feature ccmp {\n    script dev2;\n"
            "    lookup DevaConsonantMap {\n"
            + '\n'.join("    " + s for s in ccmp_subs)
            + "\n    } DevaConsonantMap;\n} ccmp;"
        )

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
        features.append("feature nukt {\n    script dev2;\n" + '\n'.join(nukt_subs) + "\n} nukt;")

    # --- akhn: akhand ligatures ---
    # Must reference PUA forms after ccmp
    akhn_subs = []
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
    if akhn_subs:
        features.append("feature akhn {\n    script dev2;\n" + '\n'.join(akhn_subs) + "\n} akhn;")

    # --- half: consonant (PUA) + virama -> half form ---
    # After ccmp, consonants are in PUA form, so reference PUA here
    half_subs = []
    for uni_cp in range(0x0915, 0x093A):
        internal = SC.to_deva_internal(uni_cp)
        half_form = internal + 240
        if has(internal) and has(SC.DEVANAGARI_VIRAMA) and has(half_form):
            half_subs.append(
                f"    sub {glyph_name(internal)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(half_form)};"
            )
    if half_subs:
        features.append("feature half {\n    script dev2;\n" + '\n'.join(half_subs) + "\n} half;")

    # --- vatu: consonant (PUA) + virama + RA (PUA) -> RA-appended form ---
    ra_int = SC.to_deva_internal(0x0930)
    vatu_subs = []
    for uni_cp in range(0x0915, 0x093A):
        internal = SC.to_deva_internal(uni_cp)
        ra_form = internal + 480
        if has(internal) and has(SC.DEVANAGARI_VIRAMA) and has(ra_int) and has(ra_form):
            vatu_subs.append(
                f"    sub {glyph_name(internal)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(ra_int)} by {glyph_name(ra_form)};"
            )
    if vatu_subs:
        features.append("feature vatu {\n    script dev2;\n" + '\n'.join(vatu_subs) + "\n} vatu;")

    # --- pres: named conjunct ligatures (using PUA forms) ---
    def _di(u):
        """Convert Unicode Devanagari consonant to internal PUA form."""
        try:
            return SC.to_deva_internal(u)
        except ValueError:
            return u  # already PUA or non-consonant

    pres_subs = []
    _conjuncts = [
        (0x0915, 0x0924, SC.DEVANAGARI_LIG_K_T, "K.T"),
        (0x0924, 0x0924, SC.DEVANAGARI_LIG_T_T, "T.T"),
        (0x0928, 0x0924, SC.DEVANAGARI_LIG_N_T, "N.T"),
        (0x0928, 0x0928, SC.DEVANAGARI_LIG_N_N, "N.N"),
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
        (0x0938, 0x0935, SC.DEVANAGARI_LIG_S_V, "S.V"),
        (0x0937, 0x092A, SC.DEVANAGARI_LIG_SS_P, "SS.P"),
        (0x0936, 0x091A, SC.DEVANAGARI_LIG_SH_C, "SH.C"),
        (0x0936, 0x0928, SC.DEVANAGARI_LIG_SH_N, "SH.N"),
        (0x0936, 0x0935, SC.DEVANAGARI_LIG_SH_V, "SH.V"),
        (0x0918, 0x091F, 0xF01BD, "GH.TT"),
        (0x0918, 0x0920, 0xF01BE, "GH.TTH"),
        (0x0918, 0x0922, 0xF01BF, "GH.DDH"),
        (0x091F, 0x091F, 0xF01D6, "TT.TT"),
        (0x091F, 0x0920, 0xF01D7, "TT.TTH"),
        (0x0920, 0x0920, 0xF01D9, "TTH.TTH"),
        (0x0921, 0x0921, 0xF01DB, "DD.DD"),
        (0x0921, 0x0922, 0xF01DC, "DD.DDH"),
        (0x0922, 0x0922, 0xF01DE, "DDH.DDH"),
        (0x092A, 0x091F, 0xF01C0, "P.TT"),
        (0x092A, 0x0920, 0xF01C1, "P.TTH"),
        (0x092A, 0x0922, 0xF01C2, "P.DDH"),
        (0x0937, 0x091F, 0xF01C3, "SS.TT"),
        (0x0937, 0x0920, 0xF01C4, "SS.TTH"),
        (0x0937, 0x0922, 0xF01C5, "SS.DDH"),
        (0x0939, 0x0923, 0xF01C6, "H.NN"),
        (0x0939, 0x0928, 0xF01C7, "H.N"),
        (0x0939, 0x092E, 0xF01C8, "H.M"),
        (0x0939, 0x092F, 0xF01C9, "H.Y"),
        (0x0939, 0x0932, 0xF01CA, "H.L"),
        (0x0939, 0x0935, 0xF01CB, "H.V"),
    ]
    for c1_uni, c2_uni, result, name in _conjuncts:
        c1 = _di(c1_uni)
        c2 = _di(c2_uni)
        if has(c1) and has(SC.DEVANAGARI_VIRAMA) and has(c2) and has(result):
            pres_subs.append(
                f"    sub {glyph_name(c1)} {glyph_name(SC.DEVANAGARI_VIRAMA)} {glyph_name(c2)} by {glyph_name(result)}; # {name}"
            )
    if pres_subs:
        features.append("feature pres {\n    script dev2;\n" + '\n'.join(pres_subs) + "\n} pres;")

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
        features.append("feature blws {\n    script dev2;\n" + '\n'.join(blws_subs) + "\n} blws;")

    # --- rphf: RA (PUA) + virama -> reph ---
    if has(ra_int) and has(SC.DEVANAGARI_VIRAMA) and has(SC.DEVANAGARI_RA_SUPER):
        rphf_code = (
            f"feature rphf {{\n"
            f"    script dev2;\n"
            f"    sub {glyph_name(ra_int)} {glyph_name(SC.DEVANAGARI_VIRAMA)} by {glyph_name(SC.DEVANAGARI_RA_SUPER)};\n"
            f"}} rphf;"
        )
        features.append(rphf_code)

    if not features:
        return ""
    return '\n\n'.join(features)


def _generate_tamil(glyphs, has):
    """Generate Tamil GSUB features."""
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

    if not subs:
        return ""

    lines = ["feature pres {", "    script tml2;"]
    lines.extend(subs)
    lines.append("} pres;")
    return '\n'.join(lines)


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
    """
    bases_with_anchors = {}
    marks = {}

    for cp, g in glyphs.items():
        if not has(cp):
            continue
        if g.props.write_on_top >= 0:
            marks[cp] = g
        elif any(a.x_used or a.y_used for a in g.props.diacritics_anchors):
            bases_with_anchors[cp] = g

    if not bases_with_anchors or not marks:
        return ""

    lines = []

    # Group marks by writeOnTop type
    mark_classes = {}
    for cp, g in marks.items():
        mark_type = g.props.write_on_top
        if mark_type not in mark_classes:
            mark_classes[mark_type] = []
        mark_classes[mark_type].append((cp, g))

    for mark_type, mark_list in sorted(mark_classes.items()):
        class_name = f"@mark_type{mark_type}"
        for cp, g in mark_list:
            mark_x = (g.props.width * SC.SCALE) // 2
            mark_y = SC.ASCENT
            lines.append(
                f"markClass {glyph_name(cp)} <anchor {mark_x} {mark_y}> {class_name};"
            )

    lines.append("")
    lines.append("feature mark {")

    for mark_type, mark_list in sorted(mark_classes.items()):
        class_name = f"@mark_type{mark_type}"
        lookup_name = f"mark_type{mark_type}"
        lines.append(f"    lookup {lookup_name} {{")

        for cp, g in sorted(bases_with_anchors.items()):
            anchor = g.props.diacritics_anchors[mark_type] if mark_type < 6 else None
            if anchor and (anchor.x_used or anchor.y_used):
                ax = anchor.x * SC.SCALE
                ay = (SC.ASCENT // SC.SCALE - anchor.y) * SC.SCALE
                lines.append(f"        pos base {glyph_name(cp)} <anchor {ax} {ay}> mark {class_name};")

        lines.append(f"    }} {lookup_name};")

    lines.append("} mark;")

    return '\n'.join(lines)
