package net.torvald.otfbuild

import com.kreative.bitsnpicas.BitmapFont
import com.kreative.bitsnpicas.BitmapFontGlyph
import com.kreative.bitsnpicas.Font
import com.kreative.bitsnpicas.exporter.KbitxBitmapFontExporter
import java.io.File

/**
 * Orchestrates the entire font building pipeline:
 * 1. Parse all TGA sheets
 * 2. Create BitmapFont with metrics
 * 3. Add all extracted glyphs
 * 4. Compose Hangul syllables
 * 5. Verify Devanagari/Tamil PUA glyphs
 * 6. Generate kerning pairs
 * 7. Export to KBITX
 */
class KbitxBuilder(private val assetsDir: String) {

    fun build(outputPath: String) {
        println("=== Terrarum Sans Bitmap OTF Builder ===")
        println("Assets: $assetsDir")
        println("Output: $outputPath")
        println()

        // 1. Create BitmapFont with metrics
        println("[1/7] Creating BitmapFont...")
        val font = BitmapFont(
            16,  // emAscent: baseline to top of em square
            4,   // emDescent: baseline to bottom of em square
            16,  // lineAscent
            4,   // lineDescent
            8,   // xHeight
            12,  // capHeight
            0    // lineGap
        )

        // Set font names
        font.setName(Font.NAME_FAMILY, "Terrarum Sans Bitmap")
        font.setName(Font.NAME_STYLE, "Regular")
        font.setName(Font.NAME_VERSION, "Version 1.0")
        font.setName(Font.NAME_FAMILY_AND_STYLE, "Terrarum Sans Bitmap Regular")
        font.setName(Font.NAME_COPYRIGHT, "Copyright (c) 2017-2026 see CONTRIBUTORS.txt")
        font.setName(Font.NAME_DESCRIPTION, "Bitmap font for Terrarum game engine")
        font.setName(Font.NAME_LICENSE_DESCRIPTION, "MIT License")

        // 2. Parse all TGA sheets
        println("[2/7] Parsing TGA sprite sheets...")
        val parser = GlyphSheetParser(assetsDir)
        val allGlyphs = parser.parseAll()
        println("  Parsed ${allGlyphs.size} glyphs from sheets")

        // 3. Add all extracted glyphs to BitmapFont
        println("[3/7] Adding glyphs to BitmapFont...")
        var addedCount = 0
        var skippedCount = 0

        for ((codepoint, extracted) in allGlyphs) {
            // Skip zero-width control characters and surrogates — don't add empty glyphs
            if (extracted.props.width <= 0 && codepoint != 0x7F) {
                // Still add zero-width glyphs that have actual bitmap data
                val hasPixels = extracted.bitmap.any { row -> row.any { it.toInt() and 0xFF != 0 } }
                if (!hasPixels) {
                    skippedCount++
                    continue
                }
            }

            // Skip internal-only codepoints that would cause issues
            if (codepoint in 0x100000..0x10FFFF || codepoint in 0xD800..0xDFFF) {
                skippedCount++
                continue
            }

            val glyph = extractedToBitmapFontGlyph(extracted)
            font.putCharacter(codepoint, glyph)
            addedCount++
        }
        println("  Added $addedCount glyphs, skipped $skippedCount")

        // 4. Compose Hangul syllables
        println("[4/7] Composing Hangul syllables...")
        val hangulCompositor = HangulCompositor(parser)
        val hangulGlyphs = hangulCompositor.compose()
        for ((codepoint, pair) in hangulGlyphs) {
            val (glyph, _) = pair
            font.putCharacter(codepoint, glyph)
        }
        println("  Added ${hangulGlyphs.size} Hangul glyphs")

        // 5. Verify Devanagari/Tamil PUA
        println("[5/7] Verifying Devanagari/Tamil PUA glyphs...")
        val devaTamilProcessor = DevanagariTamilProcessor()
        devaTamilProcessor.verify(allGlyphs)

        // 6. Generate kerning pairs
        println("[6/7] Generating kerning pairs...")
        val kemingMachine = KemingMachine()
        val kernPairs = kemingMachine.generateKerningPairs(allGlyphs)
        for ((pair, offset) in kernPairs) {
            font.setKernPair(pair, offset)
        }
        println("  Added ${kernPairs.size} kerning pairs")

        // 7. Add spacing characters
        println("[7/7] Finalising...")
        addSpacingCharacters(font, allGlyphs)

        // Add .notdef from U+007F (replacement character)
        allGlyphs[0x7F]?.let {
            val notdefGlyph = extractedToBitmapFontGlyph(it)
            font.putNamedGlyph(".notdef", notdefGlyph)
        }

        // Contract glyphs to trim whitespace
        font.contractGlyphs()

        // Auto-fill any missing name fields
        font.autoFillNames()

        // Count glyphs
        val totalGlyphs = font.characters(false).size
        println()
        println("Total glyph count: $totalGlyphs")

        // Export
        println("Exporting to KBITX: $outputPath")
        val exporter = KbitxBitmapFontExporter()
        exporter.exportFontToFile(font, File(outputPath))

        println("Done!")
    }

    private fun extractedToBitmapFontGlyph(extracted: ExtractedGlyph): BitmapFontGlyph {
        val bitmap = extracted.bitmap
        val props = extracted.props
        val h = bitmap.size
        val w = if (h > 0) bitmap[0].size else 0

        val glyphData = Array(h) { row ->
            ByteArray(w) { col -> bitmap[row][col] }
        }

        val glyph = BitmapFontGlyph()
        glyph.setGlyph(glyphData)

        // y = distance from top of glyph to baseline
        // For most glyphs this is 16 (baseline at row 16 from top in a 20px cell)
        // For Unihan: baseline at row 14 (offset by 2 from the 16px cell centred in 20px)
        val sheetIndex = getSheetIndex(extracted.codepoint)
        val baseline = when (sheetIndex) {
            SheetConfig.SHEET_UNIHAN -> 14
            SheetConfig.SHEET_CUSTOM_SYM -> 16
            else -> 16
        }
        glyph.setXY(0, baseline)
        glyph.setCharacterWidth(props.width)

        return glyph
    }

    private fun getSheetIndex(codepoint: Int): Int {
        // Check fixed sheets first
        if (codepoint in 0xF0000..0xF005F) return SheetConfig.SHEET_BULGARIAN_VARW
        if (codepoint in 0xF0060..0xF00BF) return SheetConfig.SHEET_SERBIAN_VARW

        for (i in SheetConfig.codeRange.indices.reversed()) {
            if (codepoint in SheetConfig.codeRange[i]) return i
        }
        return SheetConfig.SHEET_UNKNOWN
    }

    /**
     * Add spacing characters as empty glyphs with correct advance widths.
     */
    private fun addSpacingCharacters(font: BitmapFont, allGlyphs: Map<Int, ExtractedGlyph>) {
        val figWidth = allGlyphs[0x30]?.props?.width ?: 9
        val punctWidth = allGlyphs[0x2E]?.props?.width ?: 6
        val em = 12 + 1 // as defined in the original

        fun Int.halveWidth() = this / 2 + 1

        val spacings = mapOf(
            SheetConfig.NQSP to em.halveWidth(),
            SheetConfig.MQSP to em,
            SheetConfig.ENSP to em.halveWidth(),
            SheetConfig.EMSP to em,
            SheetConfig.THREE_PER_EMSP to (em / 3 + 1),
            SheetConfig.QUARTER_EMSP to (em / 4 + 1),
            SheetConfig.SIX_PER_EMSP to (em / 6 + 1),
            SheetConfig.FSP to figWidth,
            SheetConfig.PSP to punctWidth,
            SheetConfig.THSP to 2,
            SheetConfig.HSP to 1,
            SheetConfig.ZWSP to 0,
            SheetConfig.ZWNJ to 0,
            SheetConfig.ZWJ to 0,
            SheetConfig.SHY to 0,
        )

        for ((cp, width) in spacings) {
            val glyph = BitmapFontGlyph()
            glyph.setGlyph(Array(SheetConfig.H) { ByteArray(0) })
            glyph.setXY(0, 16)
            glyph.setCharacterWidth(width)
            font.putCharacter(cp, glyph)
        }

        // NBSP: same width as space
        val spaceWidth = allGlyphs[32]?.props?.width ?: 7
        val nbspGlyph = BitmapFontGlyph()
        nbspGlyph.setGlyph(Array(SheetConfig.H) { ByteArray(0) })
        nbspGlyph.setXY(0, 16)
        nbspGlyph.setCharacterWidth(spaceWidth)
        font.putCharacter(SheetConfig.NBSP, nbspGlyph)
    }
}
