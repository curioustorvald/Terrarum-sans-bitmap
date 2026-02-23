package net.torvald.otfbuild

import com.kreative.bitsnpicas.BitmapFontGlyph

/**
 * Composes 11,172 Hangul syllables (U+AC00–U+D7A3) from jamo sprite pieces.
 * Also composes Hangul Compatibility Jamo (U+3130–U+318F).
 *
 * Ported from TerrarumSansBitmap.kt Hangul assembly logic.
 */
class HangulCompositor(private val parser: GlyphSheetParser) {

    private val getJamoBitmap = parser.getHangulJamoBitmaps()
    private val cellW = SheetConfig.W_HANGUL_BASE
    private val cellH = SheetConfig.H

    /**
     * Compose all Hangul syllables and compatibility jamo.
     * @return Map of codepoint to BitmapFontGlyph
     */
    fun compose(): Map<Int, Pair<BitmapFontGlyph, Int>> {
        val result = HashMap<Int, Pair<BitmapFontGlyph, Int>>(12000)

        // Compose Hangul Compatibility Jamo (U+3130–U+318F)
        // These are standalone jamo from row 0 of the sheet
        for (c in 0x3130..0x318F) {
            val index = c - 0x3130
            val bitmap = getJamoBitmap(index, 0)
            val glyph = bitmapToGlyph(bitmap, cellW, cellH)
            result[c] = glyph to cellW
        }

        // Compose 11,172 Hangul syllables (U+AC00–U+D7A3)
        println("  Composing 11,172 Hangul syllables...")
        for (c in 0xAC00..0xD7A3) {
            val cInt = c - 0xAC00
            val indexCho = cInt / (SheetConfig.JUNG_COUNT * SheetConfig.JONG_COUNT)
            val indexJung = cInt / SheetConfig.JONG_COUNT % SheetConfig.JUNG_COUNT
            val indexJong = cInt % SheetConfig.JONG_COUNT // 0 = no jongseong

            // Map to jamo codepoints
            val choCP = 0x1100 + indexCho
            val jungCP = 0x1161 + indexJung
            val jongCP = if (indexJong > 0) 0x11A8 + indexJong - 1 else 0

            // Get sheet indices
            val iCho = SheetConfig.toHangulChoseongIndex(choCP)
            val iJung = SheetConfig.toHangulJungseongIndex(jungCP) ?: 0
            val iJong = if (jongCP != 0) SheetConfig.toHangulJongseongIndex(jongCP) ?: 0 else 0

            // Get row positions
            val choRow = SheetConfig.getHanInitialRow(iCho, iJung, iJong)
            val jungRow = SheetConfig.getHanMedialRow(iCho, iJung, iJong)
            val jongRow = SheetConfig.getHanFinalRow(iCho, iJung, iJong)

            // Get jamo bitmaps
            val choBitmap = getJamoBitmap(iCho, choRow)
            val jungBitmap = getJamoBitmap(iJung, jungRow)

            // Compose
            val composed = composeBitmaps(choBitmap, jungBitmap, cellW, cellH)
            if (indexJong > 0) {
                val jongBitmap = getJamoBitmap(iJong, jongRow)
                composeBitmapInto(composed, jongBitmap, cellW, cellH)
            }

            // Determine advance width
            val advanceWidth = if (iJung in SheetConfig.hangulPeaksWithExtraWidth) cellW + 1 else cellW

            val glyph = bitmapToGlyph(composed, advanceWidth, cellH)
            result[c] = glyph to advanceWidth
        }

        println("  Hangul composition done: ${result.size} glyphs")
        return result
    }

    /**
     * Compose two bitmaps by OR-ing them together.
     */
    private fun composeBitmaps(a: Array<ByteArray>, b: Array<ByteArray>, w: Int, h: Int): Array<ByteArray> {
        val result = Array(h) { row ->
            ByteArray(w) { col ->
                val av = a.getOrNull(row)?.getOrNull(col)?.toInt()?.and(0xFF) ?: 0
                val bv = b.getOrNull(row)?.getOrNull(col)?.toInt()?.and(0xFF) ?: 0
                if (av != 0 || bv != 0) 0xFF.toByte() else 0
            }
        }
        return result
    }

    /**
     * OR a bitmap into an existing one.
     */
    private fun composeBitmapInto(target: Array<ByteArray>, source: Array<ByteArray>, w: Int, h: Int) {
        for (row in 0 until minOf(h, target.size, source.size)) {
            for (col in 0 until minOf(w, target[row].size, source[row].size)) {
                if (source[row][col].toInt() and 0xFF != 0) {
                    target[row][col] = 0xFF.toByte()
                }
            }
        }
    }

    companion object {
        /**
         * Convert a byte[][] bitmap to BitmapFontGlyph.
         */
        fun bitmapToGlyph(bitmap: Array<ByteArray>, advanceWidth: Int, cellH: Int): BitmapFontGlyph {
            val h = bitmap.size
            val w = if (h > 0) bitmap[0].size else 0
            val glyphData = Array(h) { row ->
                ByteArray(w) { col -> bitmap[row][col] }
            }
            // BitmapFontGlyph(byte[][] glyph, int offset, int width, int ascent)
            // offset = x offset (left side bearing), width = advance width, ascent = baseline from top
            val glyph = BitmapFontGlyph()
            glyph.setGlyph(glyphData)
            glyph.setXY(0, cellH) // y = ascent from top of em square to baseline
            glyph.setCharacterWidth(advanceWidth)
            return glyph
        }
    }
}
