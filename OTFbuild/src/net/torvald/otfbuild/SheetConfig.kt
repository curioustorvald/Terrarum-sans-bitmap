package net.torvald.otfbuild

typealias CodePoint = Int

/**
 * Ported from TerrarumSansBitmap.kt companion object.
 * All sheet definitions, code ranges, index functions, and font metric constants.
 */
object SheetConfig {

    // Font metrics
    const val H = 20
    const val H_UNIHAN = 16
    const val W_HANGUL_BASE = 13
    const val W_UNIHAN = 16
    const val W_LATIN_WIDE = 9
    const val W_VAR_INIT = 15
    const val W_WIDEVAR_INIT = 31
    const val HGAP_VAR = 1
    const val SIZE_CUSTOM_SYM = 20

    const val H_DIACRITICS = 3
    const val H_STACKUP_LOWERCASE_SHIFTDOWN = 4
    const val H_OVERLAY_LOWERCASE_SHIFTDOWN = 2

    const val LINE_HEIGHT = 24

    // Sheet indices
    const val SHEET_ASCII_VARW = 0
    const val SHEET_HANGUL = 1
    const val SHEET_EXTA_VARW = 2
    const val SHEET_EXTB_VARW = 3
    const val SHEET_KANA = 4
    const val SHEET_CJK_PUNCT = 5
    const val SHEET_UNIHAN = 6
    const val SHEET_CYRILIC_VARW = 7
    const val SHEET_HALFWIDTH_FULLWIDTH_VARW = 8
    const val SHEET_UNI_PUNCT_VARW = 9
    const val SHEET_GREEK_VARW = 10
    const val SHEET_THAI_VARW = 11
    const val SHEET_HAYEREN_VARW = 12
    const val SHEET_KARTULI_VARW = 13
    const val SHEET_IPA_VARW = 14
    const val SHEET_RUNIC = 15
    const val SHEET_LATIN_EXT_ADD_VARW = 16
    const val SHEET_CUSTOM_SYM = 17
    const val SHEET_BULGARIAN_VARW = 18
    const val SHEET_SERBIAN_VARW = 19
    const val SHEET_TSALAGI_VARW = 20
    const val SHEET_PHONETIC_EXT_VARW = 21
    const val SHEET_DEVANAGARI_VARW = 22
    const val SHEET_KARTULI_CAPS_VARW = 23
    const val SHEET_DIACRITICAL_MARKS_VARW = 24
    const val SHEET_GREEK_POLY_VARW = 25
    const val SHEET_EXTC_VARW = 26
    const val SHEET_EXTD_VARW = 27
    const val SHEET_CURRENCIES_VARW = 28
    const val SHEET_INTERNAL_VARW = 29
    const val SHEET_LETTERLIKE_MATHS_VARW = 30
    const val SHEET_ENCLOSED_ALPHNUM_SUPL_VARW = 31
    const val SHEET_TAMIL_VARW = 32
    const val SHEET_BENGALI_VARW = 33
    const val SHEET_BRAILLE_VARW = 34
    const val SHEET_SUNDANESE_VARW = 35
    const val SHEET_DEVANAGARI2_INTERNAL_VARW = 36
    const val SHEET_CODESTYLE_ASCII_VARW = 37
    const val SHEET_ALPHABETIC_PRESENTATION_FORMS = 38
    const val SHEET_HENTAIGANA_VARW = 39

    const val SHEET_UNKNOWN = 254

    val fileList = arrayOf(
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
    )

    val codeRange: Array<List<Int>> = arrayOf(
        (0..0xFF).toList(),
        (0x1100..0x11FF).toList() + (0xA960..0xA97F).toList() + (0xD7B0..0xD7FF).toList(),
        (0x100..0x17F).toList(),
        (0x180..0x24F).toList(),
        (0x3040..0x30FF).toList() + (0x31F0..0x31FF).toList(),
        (0x3000..0x303F).toList(),
        (0x3400..0x9FFF).toList(),
        (0x400..0x52F).toList(),
        (0xFF00..0xFFFF).toList(),
        (0x2000..0x209F).toList(),
        (0x370..0x3CE).toList(),
        (0xE00..0xE5F).toList(),
        (0x530..0x58F).toList(),
        (0x10D0..0x10FF).toList(),
        (0x250..0x2FF).toList(),
        (0x16A0..0x16FF).toList(),
        (0x1E00..0x1EFF).toList(),
        (0xE000..0xE0FF).toList(),
        (0xF0000..0xF005F).toList(),
        (0xF0060..0xF00BF).toList(),
        (0x13A0..0x13F5).toList(),
        (0x1D00..0x1DBF).toList(),
        (0x900..0x97F).toList() + (0xF0100..0xF04FF).toList(),
        (0x1C90..0x1CBF).toList(),
        (0x300..0x36F).toList(),
        (0x1F00..0x1FFF).toList(),
        (0x2C60..0x2C7F).toList(),
        (0xA720..0xA7FF).toList(),
        (0x20A0..0x20CF).toList(),
        (0xFFE00..0xFFF9F).toList(),
        (0x2100..0x214F).toList(),
        (0x1F100..0x1F1FF).toList(),
        (0x0B80..0x0BFF).toList() + (0xF00C0..0xF00FF).toList(),
        (0x980..0x9FF).toList(),
        (0x2800..0x28FF).toList(),
        (0x1B80..0x1BBF).toList() + (0x1CC0..0x1CCF).toList() + (0xF0500..0xF050F).toList(),
        (0xF0110..0xF012F).toList(),
        (0xF0520..0xF057F).toList(),
        (0xFB00..0xFB17).toList(),
        (0x1B000..0x1B16F).toList(),
    )

    val codeRangeHangulCompat = 0x3130..0x318F

    val altCharsetCodepointOffsets = intArrayOf(
        0,
        0xF0000 - 0x400, // Bulgarian
        0xF0060 - 0x400, // Serbian
        0xF0520 - 0x20,  // Codestyle
    )

    val altCharsetCodepointDomains = arrayOf(
        0..0x10FFFF,
        0x400..0x45F,
        0x400..0x45F,
        0x20..0x7F,
    )

    // Unicode spacing characters
    const val NQSP = 0x2000
    const val MQSP = 0x2001
    const val ENSP = 0x2002
    const val EMSP = 0x2003
    const val THREE_PER_EMSP = 0x2004
    const val QUARTER_EMSP = 0x2005
    const val SIX_PER_EMSP = 0x2006
    const val FSP = 0x2007
    const val PSP = 0x2008
    const val THSP = 0x2009
    const val HSP = 0x200A
    const val ZWSP = 0x200B
    const val ZWNJ = 0x200C
    const val ZWJ = 0x200D
    const val SHY = 0xAD
    const val NBSP = 0xA0
    const val OBJ = 0xFFFC

    const val FIXED_BLOCK_1 = 0xFFFD0
    const val MOVABLE_BLOCK_M1 = 0xFFFE0
    const val MOVABLE_BLOCK_1 = 0xFFFF0

    const val CHARSET_OVERRIDE_DEFAULT = 0xFFFC0
    const val CHARSET_OVERRIDE_BG_BG = 0xFFFC1
    const val CHARSET_OVERRIDE_SR_SR = 0xFFFC2
    const val CHARSET_OVERRIDE_CODESTYLE = 0xFFFC3

    // Sheet type detection
    fun isVariable(filename: String) = filename.endsWith("_variable.tga")
    fun isXYSwapped(filename: String) = filename.contains("xyswap", ignoreCase = true)
    fun isExtraWide(filename: String) = filename.contains("extrawide", ignoreCase = true)

    /** Returns the cell width for a given sheet index. */
    fun getCellWidth(sheetIndex: Int): Int = when {
        isExtraWide(fileList[sheetIndex]) -> W_WIDEVAR_INIT
        isVariable(fileList[sheetIndex]) -> W_VAR_INIT
        sheetIndex == SHEET_UNIHAN -> W_UNIHAN
        sheetIndex == SHEET_HANGUL -> W_HANGUL_BASE
        sheetIndex == SHEET_CUSTOM_SYM -> SIZE_CUSTOM_SYM
        sheetIndex == SHEET_RUNIC -> W_LATIN_WIDE
        else -> W_VAR_INIT
    }

    /** Returns the cell height for a given sheet index. */
    fun getCellHeight(sheetIndex: Int): Int = when (sheetIndex) {
        SHEET_UNIHAN -> H_UNIHAN
        SHEET_CUSTOM_SYM -> SIZE_CUSTOM_SYM
        else -> H
    }

    /** Number of columns per row for the sheet. */
    fun getColumns(sheetIndex: Int): Int = when (sheetIndex) {
        SHEET_UNIHAN -> 256
        else -> 16
    }

    // Index functions (X position in sheet)
    fun indexX(c: CodePoint): Int = c % 16
    fun unihanIndexX(c: CodePoint): Int = (c - 0x3400) % 256

    // Index functions (Y position in sheet) — per sheet type
    fun indexY(sheetIndex: Int, c: CodePoint): Int = when (sheetIndex) {
        SHEET_ASCII_VARW -> c / 16
        SHEET_UNIHAN -> unihanIndexY(c)
        SHEET_EXTA_VARW -> (c - 0x100) / 16
        SHEET_EXTB_VARW -> (c - 0x180) / 16
        SHEET_KANA -> kanaIndexY(c)
        SHEET_CJK_PUNCT -> (c - 0x3000) / 16
        SHEET_CYRILIC_VARW -> (c - 0x400) / 16
        SHEET_HALFWIDTH_FULLWIDTH_VARW -> (c - 0xFF00) / 16
        SHEET_UNI_PUNCT_VARW -> (c - 0x2000) / 16
        SHEET_GREEK_VARW -> (c - 0x370) / 16
        SHEET_THAI_VARW -> (c - 0xE00) / 16
        SHEET_CUSTOM_SYM -> (c - 0xE000) / 16
        SHEET_HAYEREN_VARW -> (c - 0x530) / 16
        SHEET_KARTULI_VARW -> (c - 0x10D0) / 16
        SHEET_IPA_VARW -> (c - 0x250) / 16
        SHEET_RUNIC -> (c - 0x16A0) / 16
        SHEET_LATIN_EXT_ADD_VARW -> (c - 0x1E00) / 16
        SHEET_BULGARIAN_VARW -> (c - 0xF0000) / 16
        SHEET_SERBIAN_VARW -> (c - 0xF0060) / 16
        SHEET_TSALAGI_VARW -> (c - 0x13A0) / 16
        SHEET_PHONETIC_EXT_VARW -> (c - 0x1D00) / 16
        SHEET_DEVANAGARI_VARW -> devanagariIndexY(c)
        SHEET_KARTULI_CAPS_VARW -> (c - 0x1C90) / 16
        SHEET_DIACRITICAL_MARKS_VARW -> (c - 0x300) / 16
        SHEET_GREEK_POLY_VARW -> (c - 0x1F00) / 16
        SHEET_EXTC_VARW -> (c - 0x2C60) / 16
        SHEET_EXTD_VARW -> (c - 0xA720) / 16
        SHEET_CURRENCIES_VARW -> (c - 0x20A0) / 16
        SHEET_INTERNAL_VARW -> (c - 0xFFE00) / 16
        SHEET_LETTERLIKE_MATHS_VARW -> (c - 0x2100) / 16
        SHEET_ENCLOSED_ALPHNUM_SUPL_VARW -> (c - 0x1F100) / 16
        SHEET_TAMIL_VARW -> tamilIndexY(c)
        SHEET_BENGALI_VARW -> (c - 0x980) / 16
        SHEET_BRAILLE_VARW -> (c - 0x2800) / 16
        SHEET_SUNDANESE_VARW -> sundaneseIndexY(c)
        SHEET_DEVANAGARI2_INTERNAL_VARW -> (c - 0xF0110) / 16
        SHEET_CODESTYLE_ASCII_VARW -> (c - 0xF0520) / 16
        SHEET_ALPHABETIC_PRESENTATION_FORMS -> (c - 0xFB00) / 16
        SHEET_HENTAIGANA_VARW -> (c - 0x1B000) / 16
        SHEET_HANGUL -> 0 // Hangul uses special row logic
        else -> c / 16
    }

    private fun kanaIndexY(c: CodePoint): Int =
        if (c in 0x31F0..0x31FF) 12
        else (c - 0x3040) / 16

    private fun unihanIndexY(c: CodePoint): Int = (c - 0x3400) / 256

    private fun devanagariIndexY(c: CodePoint): Int =
        (if (c < 0xF0000) (c - 0x0900) else (c - 0xF0080)) / 16

    private fun tamilIndexY(c: CodePoint): Int =
        (if (c < 0xF0000) (c - 0x0B80) else (c - 0xF0040)) / 16

    private fun sundaneseIndexY(c: CodePoint): Int =
        (if (c >= 0xF0500) (c - 0xF04B0) else if (c < 0x1BC0) (c - 0x1B80) else (c - 0x1C80)) / 16

    // Hangul constants
    const val JUNG_COUNT = 21
    const val JONG_COUNT = 28

    // Hangul shape arrays (sorted)
    val jungseongI = sortedSetOf(21, 61)
    val jungseongOU = sortedSetOf(9, 13, 14, 18, 34, 35, 39, 45, 51, 53, 54, 64, 73, 80, 83)
    val jungseongOUComplex = (listOf(10, 11, 16) + (22..33).toList() + listOf(36, 37, 38) + (41..44).toList() +
            (46..50).toList() + (56..59).toList() + listOf(63) + (67..72).toList() + (74..79).toList() +
            (81..83).toList() + (85..91).toList() + listOf(93, 94)).toSortedSet()
    val jungseongRightie = sortedSetOf(2, 4, 6, 8, 11, 16, 32, 33, 37, 42, 44, 48, 50, 71, 72, 75, 78, 79, 83, 86, 87, 88, 94)
    val jungseongOEWI = sortedSetOf(12, 15, 17, 40, 52, 55, 89, 90, 91)
    val jungseongEU = sortedSetOf(19, 62, 66)
    val jungseongYI = sortedSetOf(20, 60, 65)
    val jungseongUU = sortedSetOf(14, 15, 16, 17, 18, 27, 30, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 59, 67, 68, 73, 77, 78, 79, 80, 81, 82, 83, 84, 91)
    val jungseongWide = (jungseongOU.toList() + jungseongEU.toList()).toSortedSet()
    val choseongGiyeoks = sortedSetOf(0, 1, 15, 23, 30, 34, 45, 51, 56, 65, 82, 90, 100, 101, 110, 111, 115)
    val hangulPeaksWithExtraWidth = sortedSetOf(2, 4, 6, 8, 11, 16, 32, 33, 37, 42, 44, 48, 50, 71, 75, 78, 79, 83, 86, 87, 88, 94)

    val giyeokRemapping = hashMapOf(
        5 to 19, 6 to 20, 7 to 21, 8 to 22, 11 to 23, 12 to 24,
    )

    fun isHangulChoseong(c: CodePoint) = c in 0x1100..0x115F || c in 0xA960..0xA97F
    fun isHangulJungseong(c: CodePoint) = c in 0x1160..0x11A7 || c in 0xD7B0..0xD7C6
    fun isHangulJongseong(c: CodePoint) = c in 0x11A8..0x11FF || c in 0xD7CB..0xD7FB
    fun isHangulCompat(c: CodePoint) = c in codeRangeHangulCompat

    fun toHangulChoseongIndex(c: CodePoint): Int =
        if (c in 0x1100..0x115F) c - 0x1100
        else if (c in 0xA960..0xA97F) c - 0xA960 + 96
        else throw IllegalArgumentException("Not a choseong: U+${c.toString(16)}")

    fun toHangulJungseongIndex(c: CodePoint): Int? =
        if (c in 0x1160..0x11A7) c - 0x1160
        else if (c in 0xD7B0..0xD7C6) c - 0xD7B0 + 72
        else null

    fun toHangulJongseongIndex(c: CodePoint): Int? =
        if (c in 0x11A8..0x11FF) c - 0x11A8 + 1
        else if (c in 0xD7CB..0xD7FB) c - 0xD7CB + 88 + 1
        else null

    fun getHanInitialRow(i: Int, p: Int, f: Int): Int {
        var ret = when {
            p in jungseongI -> 3
            p in jungseongOEWI -> 11
            p in jungseongOUComplex -> 7
            p in jungseongOU -> 5
            p in jungseongEU -> 9
            p in jungseongYI -> 13
            else -> 1
        }
        if (f != 0) ret += 1
        return if (p in jungseongUU && i in choseongGiyeoks) {
            giyeokRemapping[ret] ?: throw NullPointerException("i=$i p=$p f=$f ret=$ret")
        } else ret
    }

    fun getHanMedialRow(i: Int, p: Int, f: Int): Int = if (f == 0) 15 else 16

    fun getHanFinalRow(i: Int, p: Int, f: Int): Int =
        if (p !in jungseongRightie) 17 else 18

    // Kerning constants
    val kemingBitMask: IntArray = intArrayOf(7, 6, 5, 4, 3, 2, 1, 0, 15, 14).map { 1 shl it }.toIntArray()

    // Special characters for r+dot kerning
    val lowercaseRs = sortedSetOf(0x72, 0x155, 0x157, 0x159, 0x211, 0x213, 0x27c, 0x1e59, 0x1e58, 0x1e5f)
    val dots = sortedSetOf(0x2c, 0x2e)

    // Devanagari internal encoding
    fun Int.toDevaInternal(): Int {
        if (this in 0x0915..0x0939) return this - 0x0915 + 0xF0140
        else if (this in 0x0958..0x095F) return devanagariUnicodeNuqtaTable[this - 0x0958]
        else throw IllegalArgumentException("No internal form for U+${this.toString(16)}")
    }

    val devanagariUnicodeNuqtaTable = intArrayOf(0xF0170, 0xF0171, 0xF0172, 0xF0177, 0xF017C, 0xF017D, 0xF0186, 0xF018A)

    val devanagariConsonants = ((0x0915..0x0939).toList() + (0x0958..0x095F).toList() + (0x0978..0x097F).toList() +
            (0xF0140..0xF04FF).toList() + (0xF0106..0xF0109).toList()).toHashSet()
}
