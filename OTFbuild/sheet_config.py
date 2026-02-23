"""
Sheet definitions, code ranges, index functions, and font metric constants.
Ported from TerrarumSansBitmap.kt companion object and SheetConfig.kt.
"""

# Font metrics
H = 20
H_UNIHAN = 16
W_HANGUL_BASE = 13
W_UNIHAN = 16
W_LATIN_WIDE = 9
W_VAR_INIT = 15
W_WIDEVAR_INIT = 31
HGAP_VAR = 1
SIZE_CUSTOM_SYM = 20

H_DIACRITICS = 3
H_STACKUP_LOWERCASE_SHIFTDOWN = 4
H_OVERLAY_LOWERCASE_SHIFTDOWN = 2

LINE_HEIGHT = 24

# OTF metrics (1000 UPM, scale = 50 units/pixel)
UNITS_PER_EM = 1000
SCALE = 50  # units per pixel
ASCENT = 16 * SCALE   # 800
DESCENT = 4 * SCALE   # 200
X_HEIGHT = 8 * SCALE  # 400
CAP_HEIGHT = 12 * SCALE  # 600
LINE_GAP = (LINE_HEIGHT - H) * SCALE  # 200

# Sheet indices
SHEET_ASCII_VARW = 0
SHEET_HANGUL = 1
SHEET_EXTA_VARW = 2
SHEET_EXTB_VARW = 3
SHEET_KANA = 4
SHEET_CJK_PUNCT = 5
SHEET_UNIHAN = 6
SHEET_CYRILIC_VARW = 7
SHEET_HALFWIDTH_FULLWIDTH_VARW = 8
SHEET_UNI_PUNCT_VARW = 9
SHEET_GREEK_VARW = 10
SHEET_THAI_VARW = 11
SHEET_HAYEREN_VARW = 12
SHEET_KARTULI_VARW = 13
SHEET_IPA_VARW = 14
SHEET_RUNIC = 15
SHEET_LATIN_EXT_ADD_VARW = 16
SHEET_CUSTOM_SYM = 17
SHEET_BULGARIAN_VARW = 18
SHEET_SERBIAN_VARW = 19
SHEET_TSALAGI_VARW = 20
SHEET_PHONETIC_EXT_VARW = 21
SHEET_DEVANAGARI_VARW = 22
SHEET_KARTULI_CAPS_VARW = 23
SHEET_DIACRITICAL_MARKS_VARW = 24
SHEET_GREEK_POLY_VARW = 25
SHEET_EXTC_VARW = 26
SHEET_EXTD_VARW = 27
SHEET_CURRENCIES_VARW = 28
SHEET_INTERNAL_VARW = 29
SHEET_LETTERLIKE_MATHS_VARW = 30
SHEET_ENCLOSED_ALPHNUM_SUPL_VARW = 31
SHEET_TAMIL_VARW = 32
SHEET_BENGALI_VARW = 33
SHEET_BRAILLE_VARW = 34
SHEET_SUNDANESE_VARW = 35
SHEET_DEVANAGARI2_INTERNAL_VARW = 36
SHEET_CODESTYLE_ASCII_VARW = 37
SHEET_ALPHABETIC_PRESENTATION_FORMS = 38
SHEET_HENTAIGANA_VARW = 39

SHEET_UNKNOWN = 254

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
]

CODE_RANGE = [
    list(range(0x00, 0x100)),                                                       # 0: ASCII
    list(range(0x1100, 0x1200)) + list(range(0xA960, 0xA980)) + list(range(0xD7B0, 0xD800)),  # 1: Hangul Jamo
    list(range(0x100, 0x180)),                                                      # 2: Latin Ext A
    list(range(0x180, 0x250)),                                                      # 3: Latin Ext B
    list(range(0x3040, 0x3100)) + list(range(0x31F0, 0x3200)),                      # 4: Kana
    list(range(0x3000, 0x3040)),                                                    # 5: CJK Punct
    list(range(0x3400, 0xA000)),                                                    # 6: Unihan
    list(range(0x400, 0x530)),                                                      # 7: Cyrillic
    list(range(0xFF00, 0x10000)),                                                   # 8: Halfwidth/Fullwidth
    list(range(0x2000, 0x20A0)),                                                    # 9: Uni Punct
    list(range(0x370, 0x3CF)),                                                      # 10: Greek
    list(range(0xE00, 0xE60)),                                                      # 11: Thai
    list(range(0x530, 0x590)),                                                      # 12: Armenian
    list(range(0x10D0, 0x1100)),                                                    # 13: Georgian
    list(range(0x250, 0x300)),                                                      # 14: IPA
    list(range(0x16A0, 0x1700)),                                                    # 15: Runic
    list(range(0x1E00, 0x1F00)),                                                    # 16: Latin Ext Additional
    list(range(0xE000, 0xE100)),                                                    # 17: Custom Sym (PUA)
    list(range(0xF0000, 0xF0060)),                                                  # 18: Bulgarian
    list(range(0xF0060, 0xF00C0)),                                                  # 19: Serbian
    list(range(0x13A0, 0x13F6)),                                                    # 20: Cherokee
    list(range(0x1D00, 0x1DC0)),                                                    # 21: Phonetic Ext
    list(range(0x900, 0x980)) + list(range(0xF0100, 0xF0500)),                      # 22: Devanagari
    list(range(0x1C90, 0x1CC0)),                                                    # 23: Georgian Caps
    list(range(0x300, 0x370)),                                                      # 24: Diacritical Marks
    list(range(0x1F00, 0x2000)),                                                    # 25: Greek Polytonic
    list(range(0x2C60, 0x2C80)),                                                    # 26: Latin Ext C
    list(range(0xA720, 0xA800)),                                                    # 27: Latin Ext D
    list(range(0x20A0, 0x20D0)),                                                    # 28: Currencies
    list(range(0xFFE00, 0xFFFA0)),                                                  # 29: Internal
    list(range(0x2100, 0x2150)),                                                    # 30: Letterlike
    list(range(0x1F100, 0x1F200)),                                                  # 31: Enclosed Alphanum Supl
    list(range(0x0B80, 0x0C00)) + list(range(0xF00C0, 0xF0100)),                    # 32: Tamil
    list(range(0x980, 0xA00)),                                                      # 33: Bengali
    list(range(0x2800, 0x2900)),                                                    # 34: Braille
    list(range(0x1B80, 0x1BC0)) + list(range(0x1CC0, 0x1CD0)) + list(range(0xF0500, 0xF0510)),  # 35: Sundanese
    list(range(0xF0110, 0xF0130)),                                                  # 36: Devanagari2 Internal
    list(range(0xF0520, 0xF0580)),                                                  # 37: Codestyle ASCII
    list(range(0xFB00, 0xFB18)),                                                    # 38: Alphabetic Presentation
    list(range(0x1B000, 0x1B170)),                                                  # 39: Hentaigana
]

CODE_RANGE_HANGUL_COMPAT = range(0x3130, 0x3190)

ALT_CHARSET_CODEPOINT_OFFSETS = [
    0,
    0xF0000 - 0x400,  # Bulgarian
    0xF0060 - 0x400,  # Serbian
    0xF0520 - 0x20,   # Codestyle
]

ALT_CHARSET_CODEPOINT_DOMAINS = [
    range(0, 0x10FFFF + 1),
    range(0x400, 0x460),
    range(0x400, 0x460),
    range(0x20, 0x80),
]

# Unicode spacing characters
NQSP = 0x2000
MQSP = 0x2001
ENSP = 0x2002
EMSP = 0x2003
THREE_PER_EMSP = 0x2004
QUARTER_EMSP = 0x2005
SIX_PER_EMSP = 0x2006
FSP = 0x2007
PSP = 0x2008
THSP = 0x2009
HSP = 0x200A
ZWSP = 0x200B
ZWNJ = 0x200C
ZWJ = 0x200D
SHY = 0xAD
NBSP = 0xA0
OBJ = 0xFFFC

FIXED_BLOCK_1 = 0xFFFD0
MOVABLE_BLOCK_M1 = 0xFFFE0
MOVABLE_BLOCK_1 = 0xFFFF0

CHARSET_OVERRIDE_DEFAULT = 0xFFFC0
CHARSET_OVERRIDE_BG_BG = 0xFFFC1
CHARSET_OVERRIDE_SR_SR = 0xFFFC2
CHARSET_OVERRIDE_CODESTYLE = 0xFFFC3

# Alignment constants
ALIGN_LEFT = 0
ALIGN_RIGHT = 1
ALIGN_CENTRE = 2
ALIGN_BEFORE = 3

# Stack constants
STACK_UP = 0
STACK_DOWN = 1
STACK_BEFORE_N_AFTER = 2
STACK_UP_N_DOWN = 3
STACK_DONT = 4


def is_variable(filename):
    return filename.endswith("_variable.tga")


def is_xy_swapped(filename):
    return "xyswap" in filename.lower()


def is_extra_wide(filename):
    return "extrawide" in filename.lower()


def get_cell_width(sheet_index):
    """Returns the cell pitch in the sprite sheet (includes HGAP_VAR for variable sheets)."""
    fn = FILE_LIST[sheet_index]
    if is_extra_wide(fn):
        return W_WIDEVAR_INIT + HGAP_VAR  # 32
    if is_variable(fn):
        return W_VAR_INIT + HGAP_VAR  # 16
    if sheet_index == SHEET_UNIHAN:
        return W_UNIHAN
    if sheet_index == SHEET_HANGUL:
        return W_HANGUL_BASE
    if sheet_index == SHEET_CUSTOM_SYM:
        return SIZE_CUSTOM_SYM
    if sheet_index == SHEET_RUNIC:
        return W_LATIN_WIDE
    return W_VAR_INIT + HGAP_VAR


def get_cell_height(sheet_index):
    if sheet_index == SHEET_UNIHAN:
        return H_UNIHAN
    if sheet_index == SHEET_CUSTOM_SYM:
        return SIZE_CUSTOM_SYM
    return H


def get_columns(sheet_index):
    if sheet_index == SHEET_UNIHAN:
        return 256
    return 16


# Hangul constants
JUNG_COUNT = 21
JONG_COUNT = 28

# Hangul shape arrays (sorted sets)
JUNGSEONG_I = frozenset([21, 61])
JUNGSEONG_OU = frozenset([9, 13, 14, 18, 34, 35, 39, 45, 51, 53, 54, 64, 73, 80, 83])
JUNGSEONG_OU_COMPLEX = frozenset(
    [10, 11, 16] + list(range(22, 34)) + [36, 37, 38] + list(range(41, 45)) +
    list(range(46, 51)) + list(range(56, 60)) + [63] + list(range(67, 73)) +
    list(range(74, 80)) + list(range(81, 84)) + list(range(85, 92)) + [93, 94]
)
JUNGSEONG_RIGHTIE = frozenset([2, 4, 6, 8, 11, 16, 32, 33, 37, 42, 44, 48, 50, 71, 72, 75, 78, 79, 83, 86, 87, 88, 94])
JUNGSEONG_OEWI = frozenset([12, 15, 17, 40, 52, 55, 89, 90, 91])
JUNGSEONG_EU = frozenset([19, 62, 66])
JUNGSEONG_YI = frozenset([20, 60, 65])
JUNGSEONG_UU = frozenset([14, 15, 16, 17, 18, 27, 30, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 59, 67, 68, 73, 77, 78, 79, 80, 81, 82, 83, 84, 91])
JUNGSEONG_WIDE = frozenset(list(JUNGSEONG_OU) + list(JUNGSEONG_EU))
CHOSEONG_GIYEOKS = frozenset([0, 1, 15, 23, 30, 34, 45, 51, 56, 65, 82, 90, 100, 101, 110, 111, 115])
HANGUL_PEAKS_WITH_EXTRA_WIDTH = frozenset([2, 4, 6, 8, 11, 16, 32, 33, 37, 42, 44, 48, 50, 71, 75, 78, 79, 83, 86, 87, 88, 94])

GIYEOK_REMAPPING = {5: 19, 6: 20, 7: 21, 8: 22, 11: 23, 12: 24}


def is_hangul_choseong(c):
    return 0x1100 <= c <= 0x115F or 0xA960 <= c <= 0xA97F


def is_hangul_jungseong(c):
    return 0x1160 <= c <= 0x11A7 or 0xD7B0 <= c <= 0xD7C6


def is_hangul_jongseong(c):
    return 0x11A8 <= c <= 0x11FF or 0xD7CB <= c <= 0xD7FB


def is_hangul_compat(c):
    return 0x3130 <= c <= 0x318F


def to_hangul_choseong_index(c):
    if 0x1100 <= c <= 0x115F:
        return c - 0x1100
    if 0xA960 <= c <= 0xA97F:
        return c - 0xA960 + 96
    raise ValueError(f"Not a choseong: U+{c:04X}")


def to_hangul_jungseong_index(c):
    if 0x1160 <= c <= 0x11A7:
        return c - 0x1160
    if 0xD7B0 <= c <= 0xD7C6:
        return c - 0xD7B0 + 72
    return None


def to_hangul_jongseong_index(c):
    if 0x11A8 <= c <= 0x11FF:
        return c - 0x11A8 + 1
    if 0xD7CB <= c <= 0xD7FB:
        return c - 0xD7CB + 88 + 1
    return None


def get_han_initial_row(i, p, f):
    if p in JUNGSEONG_I:
        ret = 3
    elif p in JUNGSEONG_OEWI:
        ret = 11
    elif p in JUNGSEONG_OU_COMPLEX:
        ret = 7
    elif p in JUNGSEONG_OU:
        ret = 5
    elif p in JUNGSEONG_EU:
        ret = 9
    elif p in JUNGSEONG_YI:
        ret = 13
    else:
        ret = 1

    if f != 0:
        ret += 1

    if p in JUNGSEONG_UU and i in CHOSEONG_GIYEOKS:
        mapped = GIYEOK_REMAPPING.get(ret)
        if mapped is None:
            raise ValueError(f"Giyeok remapping failed: i={i} p={p} f={f} ret={ret}")
        return mapped
    return ret


def get_han_medial_row(i, p, f):
    return 15 if f == 0 else 16


def get_han_final_row(i, p, f):
    return 17 if p not in JUNGSEONG_RIGHTIE else 18


# Kerning constants
KEMING_BIT_MASK = [1 << b for b in [7, 6, 5, 4, 3, 2, 1, 0, 15, 14]]

# Special characters for r+dot kerning
LOWERCASE_RS = frozenset([0x72, 0x155, 0x157, 0x159, 0x211, 0x213, 0x27c, 0x1e59, 0x1e58, 0x1e5f])
DOTS = frozenset([0x2c, 0x2e])

# Devanagari internal encoding
DEVANAGARI_UNICODE_NUQTA_TABLE = [0xF0170, 0xF0171, 0xF0172, 0xF0177, 0xF017C, 0xF017D, 0xF0186, 0xF018A]


def to_deva_internal(c):
    if 0x0915 <= c <= 0x0939:
        return c - 0x0915 + 0xF0140
    if 0x0958 <= c <= 0x095F:
        return DEVANAGARI_UNICODE_NUQTA_TABLE[c - 0x0958]
    raise ValueError(f"No internal form for U+{c:04X}")


DEVANAGARI_CONSONANTS = frozenset(
    list(range(0x0915, 0x093A)) + list(range(0x0958, 0x0960)) +
    list(range(0x0978, 0x0980)) + list(range(0xF0140, 0xF0500)) +
    list(range(0xF0106, 0xF010A))
)

# Sundanese internal forms
SUNDANESE_ING = 0xF0500
SUNDANESE_ENG = 0xF0501
SUNDANESE_EUNG = 0xF0502
SUNDANESE_IR = 0xF0503
SUNDANESE_ER = 0xF0504
SUNDANESE_EUR = 0xF0505
SUNDANESE_LU = 0xF0506

# Tamil constants
TAMIL_KSSA = 0xF00ED
TAMIL_SHRII = 0xF00EE
TAMIL_I = 0xBBF
TAMIL_LIGATING_CONSONANTS = [
    0x0B95, 0x0B99, 0x0B9A, 0x0B9E, 0x0B9F, 0x0BA3, 0x0BA4, 0x0BA8,
    0x0BA9, 0x0BAA, 0x0BAE, 0x0BAF, 0x0BB0, 0x0BB1, 0x0BB2, 0x0BB3,
    0x0BB4, 0x0BB5,
]

# Devanagari special codepoints
DEVANAGARI_VIRAMA = 0x94D
DEVANAGARI_NUQTA = 0x93C
DEVANAGARI_RA = to_deva_internal(0x930)
DEVANAGARI_YA = to_deva_internal(0x92F)
DEVANAGARI_RRA = to_deva_internal(0x931)
DEVANAGARI_VA = to_deva_internal(0x935)
DEVANAGARI_HA = to_deva_internal(0x939)
DEVANAGARI_U = 0x941
DEVANAGARI_UU = 0x942
DEVANAGARI_I_VOWEL = 0x093F
DEVANAGARI_II_VOWEL = 0x0940
DEVANAGARI_RYA = 0xF0106
DEVANAGARI_HALF_RYA = 0xF0107
DEVANAGARI_OPEN_YA = 0xF0108
DEVANAGARI_OPEN_HALF_YA = 0xF0109
DEVANAGARI_ALT_HALF_SHA = 0xF010F
DEVANAGARI_EYELASH_RA = 0xF010B
DEVANAGARI_RA_SUPER = 0xF010C
DEVANAGARI_RA_SUPER_COMPLEX = 0xF010D
MARWARI_DD = 0x978
MARWARI_LIG_DD_R = 0xF010E

DEVANAGARI_SYLL_RU = 0xF0100
DEVANAGARI_SYLL_RUU = 0xF0101
DEVANAGARI_SYLL_RRU = 0xF0102
DEVANAGARI_SYLL_RRUU = 0xF0103
DEVANAGARI_SYLL_HU = 0xF0104
DEVANAGARI_SYLL_HUU = 0xF0105

# Devanagari ligature codepoints
DEVANAGARI_LIG_K_T = 0xF01BC
DEVANAGARI_LIG_K_SS = 0xF01A1
DEVANAGARI_LIG_J_NY = 0xF01A2
DEVANAGARI_LIG_T_T = 0xF01A3
DEVANAGARI_LIG_N_T = 0xF01A4
DEVANAGARI_LIG_N_N = 0xF01A5
DEVANAGARI_LIG_S_V = 0xF01A6
DEVANAGARI_LIG_SS_P = 0xF01A7
DEVANAGARI_LIG_SH_C = 0xF01A8
DEVANAGARI_LIG_SH_N = 0xF01A9
DEVANAGARI_LIG_SH_V = 0xF01AA
DEVANAGARI_LIG_J_Y = 0xF01AB
DEVANAGARI_LIG_J_J_Y = 0xF01AC

MARWARI_LIG_DD_DD = 0xF01BA
MARWARI_LIG_DD_DDH = 0xF01BB
MARWARI_LIG_DD_Y = 0xF016E
MARWARI_HALFLIG_DD_Y = 0xF016F

# Devanagari range sets for feature generation
DEVANAGARI_PRESENTATION_CONSONANTS = range(0xF0140, 0xF0230)
DEVANAGARI_PRESENTATION_CONSONANTS_HALF = range(0xF0230, 0xF0320)
DEVANAGARI_PRESENTATION_CONSONANTS_WITH_RA = range(0xF0320, 0xF0410)
DEVANAGARI_PRESENTATION_CONSONANTS_WITH_RA_HALF = range(0xF0410, 0xF0500)

# Index functions
def _kana_index_y(c):
    return 12 if 0x31F0 <= c <= 0x31FF else (c - 0x3040) // 16

def _unihan_index_y(c):
    return (c - 0x3400) // 256

def _devanagari_index_y(c):
    return ((c - 0x0900) if c < 0xF0000 else (c - 0xF0080)) // 16

def _tamil_index_y(c):
    return ((c - 0x0B80) if c < 0xF0000 else (c - 0xF0040)) // 16

def _sundanese_index_y(c):
    if c >= 0xF0500:
        return (c - 0xF04B0) // 16
    if c < 0x1BC0:
        return (c - 0x1B80) // 16
    return (c - 0x1C80) // 16


def index_x(c):
    return c % 16

def unihan_index_x(c):
    return (c - 0x3400) % 256

def index_y(sheet_index, c):
    """Y-index (row) for codepoint c in the given sheet."""
    return {
        SHEET_ASCII_VARW: lambda: c // 16,
        SHEET_UNIHAN: lambda: _unihan_index_y(c),
        SHEET_EXTA_VARW: lambda: (c - 0x100) // 16,
        SHEET_EXTB_VARW: lambda: (c - 0x180) // 16,
        SHEET_KANA: lambda: _kana_index_y(c),
        SHEET_CJK_PUNCT: lambda: (c - 0x3000) // 16,
        SHEET_CYRILIC_VARW: lambda: (c - 0x400) // 16,
        SHEET_HALFWIDTH_FULLWIDTH_VARW: lambda: (c - 0xFF00) // 16,
        SHEET_UNI_PUNCT_VARW: lambda: (c - 0x2000) // 16,
        SHEET_GREEK_VARW: lambda: (c - 0x370) // 16,
        SHEET_THAI_VARW: lambda: (c - 0xE00) // 16,
        SHEET_CUSTOM_SYM: lambda: (c - 0xE000) // 16,
        SHEET_HAYEREN_VARW: lambda: (c - 0x530) // 16,
        SHEET_KARTULI_VARW: lambda: (c - 0x10D0) // 16,
        SHEET_IPA_VARW: lambda: (c - 0x250) // 16,
        SHEET_RUNIC: lambda: (c - 0x16A0) // 16,
        SHEET_LATIN_EXT_ADD_VARW: lambda: (c - 0x1E00) // 16,
        SHEET_BULGARIAN_VARW: lambda: (c - 0xF0000) // 16,
        SHEET_SERBIAN_VARW: lambda: (c - 0xF0060) // 16,
        SHEET_TSALAGI_VARW: lambda: (c - 0x13A0) // 16,
        SHEET_PHONETIC_EXT_VARW: lambda: (c - 0x1D00) // 16,
        SHEET_DEVANAGARI_VARW: lambda: _devanagari_index_y(c),
        SHEET_KARTULI_CAPS_VARW: lambda: (c - 0x1C90) // 16,
        SHEET_DIACRITICAL_MARKS_VARW: lambda: (c - 0x300) // 16,
        SHEET_GREEK_POLY_VARW: lambda: (c - 0x1F00) // 16,
        SHEET_EXTC_VARW: lambda: (c - 0x2C60) // 16,
        SHEET_EXTD_VARW: lambda: (c - 0xA720) // 16,
        SHEET_CURRENCIES_VARW: lambda: (c - 0x20A0) // 16,
        SHEET_INTERNAL_VARW: lambda: (c - 0xFFE00) // 16,
        SHEET_LETTERLIKE_MATHS_VARW: lambda: (c - 0x2100) // 16,
        SHEET_ENCLOSED_ALPHNUM_SUPL_VARW: lambda: (c - 0x1F100) // 16,
        SHEET_TAMIL_VARW: lambda: _tamil_index_y(c),
        SHEET_BENGALI_VARW: lambda: (c - 0x980) // 16,
        SHEET_BRAILLE_VARW: lambda: (c - 0x2800) // 16,
        SHEET_SUNDANESE_VARW: lambda: _sundanese_index_y(c),
        SHEET_DEVANAGARI2_INTERNAL_VARW: lambda: (c - 0xF0110) // 16,
        SHEET_CODESTYLE_ASCII_VARW: lambda: (c - 0xF0520) // 16,
        SHEET_ALPHABETIC_PRESENTATION_FORMS: lambda: (c - 0xFB00) // 16,
        SHEET_HENTAIGANA_VARW: lambda: (c - 0x1B000) // 16,
        SHEET_HANGUL: lambda: 0,
    }.get(sheet_index, lambda: c // 16)()
