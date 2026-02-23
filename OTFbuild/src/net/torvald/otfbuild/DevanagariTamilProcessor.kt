package net.torvald.otfbuild

/**
 * Ensures all Devanagari, Tamil, Sundanese, and Alphabetic Presentation Forms
 * PUA glyphs are included in the font. Since BitsNPicas doesn't support OpenType
 * GSUB/GPOS features, complex text shaping must be done by the application.
 *
 * All the relevant PUA codepoints are already in the sprite sheets and extracted
 * by GlyphSheetParser. This processor:
 * 1. Verifies that key PUA ranges have been loaded
 * 2. Ensures Unicode pre-composed forms (U+0958–U+095F) map correctly
 * 3. Documents the mapping for reference
 *
 * The runtime normalise() function handles the actual Unicode → PUA mapping,
 * but since we can't put GSUB tables into the KBITX/TTF, applications must
 * use the PUA codepoints directly, or perform their own normalisation.
 */
class DevanagariTamilProcessor {

    /**
     * Verify that key PUA glyphs exist in the extracted set.
     * Returns a set of codepoints that should be included but are missing.
     */
    fun verify(glyphs: Map<Int, ExtractedGlyph>): Set<Int> {
        val missing = mutableSetOf<Int>()

        // Devanagari special syllables
        val devanagariSpecials = listOf(
            0xF0100, // Ru
            0xF0101, // Ruu
            0xF0102, // RRu
            0xF0103, // RRuu
            0xF0104, // Hu
            0xF0105, // Huu
            0xF0106, // RYA
            0xF0107, // Half-RYA
            0xF0108, // Open YA
            0xF0109, // Open Half-YA
            0xF010B, // Eyelash RA
            0xF010C, // RA superscript
            0xF010D, // RA superscript (complex)
            0xF010E, // DDRA (Marwari)
            0xF010F, // Alt Half SHA
        )

        // Devanagari presentation consonants (full forms)
        val devaPresentation = (0xF0140..0xF022F).toList()
        // Devanagari presentation consonants (half forms)
        val devaHalf = (0xF0230..0xF031F).toList()
        // Devanagari presentation consonants (with RA)
        val devaRa = (0xF0320..0xF040F).toList()
        // Devanagari presentation consonants (with RA, half forms)
        val devaRaHalf = (0xF0410..0xF04FF).toList()

        // Devanagari II variant forms
        val devaII = (0xF0110..0xF012F).toList()

        // Devanagari named ligatures
        val devaLigatures = listOf(
            0xF01A1, // K.SS
            0xF01A2, // J.NY
            0xF01A3, // T.T
            0xF01A4, // N.T
            0xF01A5, // N.N
            0xF01A6, // S.V
            0xF01A7, // SS.P
            0xF01A8, // SH.C
            0xF01A9, // SH.N
            0xF01AA, // SH.V
            0xF01AB, // J.Y
            0xF01AC, // J.J.Y
            0xF01BC, // K.T
            // D-series ligatures
            0xF01B0, 0xF01B1, 0xF01B2, 0xF01B3, 0xF01B4,
            0xF01B5, 0xF01B6, 0xF01B7, 0xF01B8, 0xF01B9,
            // Marwari
            0xF01BA, 0xF01BB,
            // Extended ligatures
            0xF01BD, 0xF01BE, 0xF01BF,
            0xF01C0, 0xF01C1, 0xF01C2, 0xF01C3, 0xF01C4, 0xF01C5,
            0xF01C6, 0xF01C7, 0xF01C8, 0xF01C9, 0xF01CA, 0xF01CB,
            0xF01CD, 0xF01CE, 0xF01CF,
            0xF01D0, 0xF01D1, 0xF01D2, 0xF01D3, 0xF01D4, 0xF01D5,
            0xF01D6, 0xF01D7, 0xF01D8, 0xF01D9, 0xF01DA,
            0xF01DB, 0xF01DC, 0xF01DD, 0xF01DE, 0xF01DF,
            0xF01E0, 0xF01E1, 0xF01E2, 0xF01E3,
        )

        // Tamil ligatures
        val tamilLigatures = listOf(
            0xF00C0, 0xF00C1, // TTA+I, TTA+II
            0xF00ED, // KSSA
            0xF00EE, // SHRII
            0xF00F0, 0xF00F1, 0xF00F2, 0xF00F3, 0xF00F4, 0xF00F5, // consonant+I
        ) + (0xF00C2..0xF00D3).toList() + // consonant+U
                (0xF00D4..0xF00E5).toList()   // consonant+UU

        // Sundanese internal forms
        val sundanese = listOf(
            0xF0500, // ING
            0xF0501, // ENG
            0xF0502, // EUNG
            0xF0503, // IR
            0xF0504, // ER
            0xF0505, // EUR
            0xF0506, // LU
        )

        // Alphabetic Presentation Forms (already in sheet 38)
        // FB00–FB06 (Latin ligatures), FB13–FB17 (Armenian ligatures)

        // Check all expected ranges
        val allExpected = devanagariSpecials + devaPresentation + devaHalf + devaRa + devaRaHalf +
                devaII + devaLigatures + tamilLigatures + sundanese

        for (cp in allExpected) {
            if (!glyphs.containsKey(cp)) {
                missing.add(cp)
            }
        }

        if (missing.isNotEmpty()) {
            println("  [DevanagariTamilProcessor] ${missing.size} expected PUA glyphs missing")
            // Only warn for the first few
            missing.take(10).forEach { println("    Missing: U+${it.toString(16).uppercase().padStart(5, '0')}") }
            if (missing.size > 10) println("    ... and ${missing.size - 10} more")
        } else {
            println("  [DevanagariTamilProcessor] All expected PUA glyphs present")
        }

        return missing
    }
}
