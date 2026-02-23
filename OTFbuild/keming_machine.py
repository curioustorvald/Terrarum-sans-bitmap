"""
Generate kerning pairs from shape rules.
Ported from TerrarumSansBitmap.kt "The Keming Machine" section.

6 base rules + 6 mirrored (auto-generated) = 12 rules total.
Also includes r+dot special pairs.

Output kern values scaled by SCALE (50 units/pixel):
  -1px -> -50 units, -2px -> -100 units
"""

from typing import Dict, Tuple

from glyph_parser import ExtractedGlyph
import sheet_config as SC

SCALE = SC.SCALE


class _Ing:
    """Pattern matcher for kerning shape bits."""

    def __init__(self, s):
        self.s = s
        self.care_bits = 0
        self.rule_bits = 0
        for index, char in enumerate(s):
            if char == '@':
                self.care_bits |= SC.KEMING_BIT_MASK[index]
                self.rule_bits |= SC.KEMING_BIT_MASK[index]
            elif char == '`':
                self.care_bits |= SC.KEMING_BIT_MASK[index]

    def matches(self, shape_bits):
        return (shape_bits & self.care_bits) == self.rule_bits


class _Kem:
    def __init__(self, first, second, bb=2, yy=1):
        self.first = first
        self.second = second
        self.bb = bb
        self.yy = yy


def _build_kerning_rules():
    """Build the 12 kerning rules (6 base + 6 mirrored)."""
    base_rules = [
        _Kem(_Ing("_`_@___`__"), _Ing("`_`___@___")),
        _Kem(_Ing("_@_`___`__"), _Ing("`_________")),
        _Kem(_Ing("_@_@___`__"), _Ing("`___@_@___"), 1, 1),
        _Kem(_Ing("_@_@_`_`__"), _Ing("`_____@___")),
        _Kem(_Ing("___`_`____"), _Ing("`___@_`___")),
        _Kem(_Ing("___`_`____"), _Ing("`_@___`___")),
    ]

    mirrored = []
    for rule in base_rules:
        left = rule.first.s
        right = rule.second.s
        new_left = []
        new_right = []
        for c in range(0, len(left), 2):
            new_left.append(right[c + 1])
            new_left.append(right[c])
            new_right.append(left[c + 1])
            new_right.append(left[c])
        mirrored.append(_Kem(
            _Ing(''.join(new_left)),
            _Ing(''.join(new_right)),
            rule.bb, rule.yy
        ))

    return base_rules + mirrored


_KERNING_RULES = _build_kerning_rules()


def generate_kerning_pairs(glyphs: Dict[int, ExtractedGlyph]) -> Dict[Tuple[int, int], int]:
    """
    Generate kerning pairs from all glyphs that have kerning data.
    Returns dict of (left_codepoint, right_codepoint) -> kern_offset_in_font_units.
    Negative values = tighter spacing.
    """
    result = {}

    # Collect all codepoints with kerning data
    kernable = {cp: g for cp, g in glyphs.items() if g.props.has_kern_data}

    if not kernable:
        print("  [KemingMachine] No glyphs with kern data found")
        return result

    print(f"  [KemingMachine] {len(kernable)} glyphs with kern data")

    # Special rule: lowercase r + dot
    r_dot_count = 0
    for r in SC.LOWERCASE_RS:
        for d in SC.DOTS:
            if r in glyphs and d in glyphs:
                result[(r, d)] = -1 * SCALE
                r_dot_count += 1

    # Apply kerning rules to all pairs
    kern_codes = list(kernable.keys())
    pairs_found = 0

    for left_code in kern_codes:
        left_props = kernable[left_code].props
        mask_l = left_props.kerning_mask

        for right_code in kern_codes:
            right_props = kernable[right_code].props
            mask_r = right_props.kerning_mask

            for rule in _KERNING_RULES:
                if rule.first.matches(mask_l) and rule.second.matches(mask_r):
                    contraction = rule.yy if (left_props.is_kern_y_type or right_props.is_kern_y_type) else rule.bb
                    if contraction > 0:
                        result[(left_code, right_code)] = -contraction * SCALE
                        pairs_found += 1
                    break  # first matching rule wins

    print(f"  [KemingMachine] Generated {pairs_found} kerning pairs (+ {r_dot_count} r-dot pairs)")
    return result
