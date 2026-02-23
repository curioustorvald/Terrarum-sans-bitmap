package net.torvald.otfbuild

import com.kreative.bitsnpicas.BitmapFontGlyph
import java.io.File

/**
 * Glyph properties extracted from tag column.
 * Mirrors GlyphProps from the runtime but is standalone.
 */
data class ExtractedGlyphProps(
    val width: Int,
    val isLowHeight: Boolean = false,
    val nudgeX: Int = 0,
    val nudgeY: Int = 0,
    val alignWhere: Int = 0,
    val writeOnTop: Int = -1,
    val stackWhere: Int = 0,
    val hasKernData: Boolean = false,
    val isKernYtype: Boolean = false,
    val kerningMask: Int = 255,
    val directiveOpcode: Int = 0,
    val directiveArg1: Int = 0,
    val directiveArg2: Int = 0,
    val extInfo: IntArray = IntArray(15),
) {
    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_RIGHT = 1
        const val ALIGN_CENTRE = 2
        const val ALIGN_BEFORE = 3

        const val STACK_UP = 0
        const val STACK_DOWN = 1
        const val STACK_BEFORE_N_AFTER = 2
        const val STACK_UP_N_DOWN = 3
        const val STACK_DONT = 4
    }

    fun requiredExtInfoCount(): Int =
        if (stackWhere == STACK_BEFORE_N_AFTER) 2
        else if (directiveOpcode in 0b10000_000..0b10000_111) 7
        else 0

    fun isPragma(pragma: String) = when (pragma) {
        "replacewith" -> directiveOpcode in 0b10000_000..0b10000_111
        else -> false
    }

    val isIllegal: Boolean get() = directiveOpcode == 255
}

data class ExtractedGlyph(
    val codepoint: Int,
    val props: ExtractedGlyphProps,
    val bitmap: Array<ByteArray>, // [row][col], 0 or -1(0xFF)
)

/**
 * Extracts glyph bitmaps and properties from TGA sprite sheets.
 * Ported from TerrarumSansBitmap.buildWidthTable() and related methods.
 */
class GlyphSheetParser(private val assetsDir: String) {

    private fun Boolean.toInt() = if (this) 1 else 0
    /** @return 32-bit number: if alpha channel is zero, return 0; else return the original value */
    private fun Int.tagify() = if (this and 0xFF == 0) 0 else this

    /**
     * Parse all sheets and return a map of codepoint -> (props, bitmap).
     */
    fun parseAll(): Map<Int, ExtractedGlyph> {
        val result = HashMap<Int, ExtractedGlyph>(65536)

        SheetConfig.fileList.forEachIndexed { sheetIndex, filename ->
            val file = File(assetsDir, filename)
            if (!file.exists()) {
                println("  [SKIP] $filename not found")
                return@forEachIndexed
            }

            val isVariable = SheetConfig.isVariable(filename)
            val isXYSwapped = SheetConfig.isXYSwapped(filename)
            val isExtraWide = SheetConfig.isExtraWide(filename)
            val cellW = SheetConfig.getCellWidth(sheetIndex)
            val cellH = SheetConfig.getCellHeight(sheetIndex)
            val cols = SheetConfig.getColumns(sheetIndex)

            val image = TgaReader.read(file)

            val statusParts = mutableListOf<String>()
            if (isVariable) statusParts.add("VARIABLE")
            if (isXYSwapped) statusParts.add("XYSWAP")
            if (isExtraWide) statusParts.add("EXTRAWIDE")
            if (statusParts.isEmpty()) statusParts.add("STATIC")
            println("  Loading [${statusParts.joinToString()}] $filename")

            if (isVariable) {
                parseVariableSheet(image, sheetIndex, cellW, cellH, cols, isXYSwapped, result)
            } else {
                parseFixedSheet(image, sheetIndex, cellW, cellH, cols, result)
            }
        }

        // Add fixed-width overrides
        addFixedWidthOverrides(result)

        return result
    }

    /**
     * Parse a variable-width sheet: extract tag column for properties, bitmap for glyph.
     */
    private fun parseVariableSheet(
        image: TgaImage,
        sheetIndex: Int,
        cellW: Int,
        cellH: Int,
        cols: Int,
        isXYSwapped: Boolean,
        result: HashMap<Int, ExtractedGlyph>
    ) {
        val codeRangeList = SheetConfig.codeRange[sheetIndex]
        val binaryCodeOffset = cellW - 1 // tag column is last pixel column of cell

        codeRangeList.forEachIndexed { index, code ->
            val cellX: Int
            val cellY: Int

            if (isXYSwapped) {
                cellX = (index / cols) * cellW  // row becomes X
                cellY = (index % cols) * cellH  // col becomes Y
            } else {
                cellX = (index % cols) * cellW
                cellY = (index / cols) * cellH
            }

            val codeStartX = cellX + binaryCodeOffset
            val codeStartY = cellY

            // Parse tag column
            val width = (0..4).fold(0) { acc, y ->
                acc or ((image.getPixel(codeStartX, codeStartY + y).and(0xFF) != 0).toInt() shl y)
            }
            val isLowHeight = image.getPixel(codeStartX, codeStartY + 5).and(0xFF) != 0

            // Kerning data
            val kerningBit1 = image.getPixel(codeStartX, codeStartY + 6).tagify()
            val kerningBit2 = image.getPixel(codeStartX, codeStartY + 7).tagify()
            val kerningBit3 = image.getPixel(codeStartX, codeStartY + 8).tagify()
            var isKernYtype = (kerningBit1 and 0x80000000.toInt()) != 0
            var kerningMask = kerningBit1.ushr(8).and(0xFFFFFF)
            val hasKernData = kerningBit1 and 0xFF != 0
            if (!hasKernData) {
                isKernYtype = false
                kerningMask = 255
            }

            // Compiler directives
            val compilerDirectives = image.getPixel(codeStartX, codeStartY + 9).tagify()
            val directiveOpcode = compilerDirectives.ushr(24).and(255)
            val directiveArg1 = compilerDirectives.ushr(16).and(255)
            val directiveArg2 = compilerDirectives.ushr(8).and(255)

            // Nudge
            val nudgingBits = image.getPixel(codeStartX, codeStartY + 10).tagify()
            val nudgeX = nudgingBits.ushr(24).toByte().toInt()
            val nudgeY = nudgingBits.ushr(16).toByte().toInt()

            // Diacritics anchors (we don't store them in ExtractedGlyphProps for now but could)
            // For alignment and width, they are useful during composition but not in final output

            // Alignment
            val alignWhere = (0..1).fold(0) { acc, y ->
                acc or ((image.getPixel(codeStartX, codeStartY + y + 15).and(0xFF) != 0).toInt() shl y)
            }

            // Write on top
            var writeOnTop = image.getPixel(codeStartX, codeStartY + 17) // NO .tagify()
            if (writeOnTop and 0xFF == 0) writeOnTop = -1
            else {
                writeOnTop = if (writeOnTop.ushr(8) == 0xFFFFFF) 0 else writeOnTop.ushr(28) and 15
            }

            // Stack where
            val stackWhere0 = image.getPixel(codeStartX, codeStartY + 18).tagify()
            val stackWhere1 = image.getPixel(codeStartX, codeStartY + 19).tagify()
            val stackWhere = if (stackWhere0 == 0x00FF00FF && stackWhere1 == 0x00FF00FF)
                ExtractedGlyphProps.STACK_DONT
            else (0..1).fold(0) { acc, y ->
                acc or ((image.getPixel(codeStartX, codeStartY + y + 18).and(0xFF) != 0).toInt() shl y)
            }

            val extInfo = IntArray(15)
            val props = ExtractedGlyphProps(
                width, isLowHeight, nudgeX, nudgeY, alignWhere, writeOnTop, stackWhere,
                hasKernData, isKernYtype, kerningMask, directiveOpcode, directiveArg1, directiveArg2, extInfo
            )

            // Parse extInfo if needed
            val extCount = props.requiredExtInfoCount()
            if (extCount > 0) {
                for (x in 0 until extCount) {
                    var info = 0
                    for (y in 0..19) {
                        if (image.getPixel(cellX + x, cellY + y).and(0xFF) != 0) {
                            info = info or (1 shl y)
                        }
                    }
                    extInfo[x] = info
                }
            }

            // Extract glyph bitmap: all pixels in cell except tag column
            val bitmapW = cellW - 1 // exclude tag column
            val bitmap = Array(cellH) { row ->
                ByteArray(bitmapW) { col ->
                    val px = image.getPixel(cellX + col, cellY + row)
                    if (px and 0xFF != 0) 0xFF.toByte() else 0
                }
            }

            result[code] = ExtractedGlyph(code, props, bitmap)
        }
    }

    /**
     * Parse a fixed-width sheet (Hangul, Unihan, Runic, Custom Sym).
     */
    private fun parseFixedSheet(
        image: TgaImage,
        sheetIndex: Int,
        cellW: Int,
        cellH: Int,
        cols: Int,
        result: HashMap<Int, ExtractedGlyph>
    ) {
        val codeRangeList = SheetConfig.codeRange[sheetIndex]
        val fixedWidth = when (sheetIndex) {
            SheetConfig.SHEET_CUSTOM_SYM -> 20
            SheetConfig.SHEET_HANGUL -> SheetConfig.W_HANGUL_BASE
            SheetConfig.SHEET_RUNIC -> 9
            SheetConfig.SHEET_UNIHAN -> SheetConfig.W_UNIHAN
            else -> cellW
        }

        codeRangeList.forEachIndexed { index, code ->
            val cellX = (index % cols) * cellW
            val cellY = (index / cols) * cellH

            val bitmap = Array(cellH) { row ->
                ByteArray(cellW) { col ->
                    val px = image.getPixel(cellX + col, cellY + row)
                    if (px and 0xFF != 0) 0xFF.toByte() else 0
                }
            }

            val props = ExtractedGlyphProps(fixedWidth)
            result[code] = ExtractedGlyph(code, props, bitmap)
        }
    }

    /**
     * Apply fixed-width overrides as in buildWidthTableFixed().
     */
    private fun addFixedWidthOverrides(result: HashMap<Int, ExtractedGlyph>) {
        // Hangul compat jamo
        SheetConfig.codeRangeHangulCompat.forEach { code ->
            if (!result.containsKey(code)) {
                result[code] = ExtractedGlyph(code, ExtractedGlyphProps(SheetConfig.W_HANGUL_BASE), emptyBitmap())
            }
        }

        // Zero-width ranges
        (0xD800..0xDFFF).forEach { result[it] = ExtractedGlyph(it, ExtractedGlyphProps(0), emptyBitmap()) }
        (0x100000..0x10FFFF).forEach { result[it] = ExtractedGlyph(it, ExtractedGlyphProps(0), emptyBitmap()) }
        (0xFFFA0..0xFFFFF).forEach { result[it] = ExtractedGlyph(it, ExtractedGlyphProps(0), emptyBitmap()) }

        // Insular letter
        result[0x1D79]?.let { /* already in sheet */ } ?: run {
            result[0x1D79] = ExtractedGlyph(0x1D79, ExtractedGlyphProps(9), emptyBitmap())
        }

        // Replacement character at U+007F
        result[0x7F]?.let { existing ->
            result[0x7F] = existing.copy(props = existing.props.copy(width = 15))
        }

        // Null char
        result[0] = ExtractedGlyph(0, ExtractedGlyphProps(0), emptyBitmap())
    }

    private fun emptyBitmap() = Array(SheetConfig.H) { ByteArray(SheetConfig.W_VAR_INIT) }

    /**
     * Extracts raw Hangul jamo bitmaps from the Hangul sheet for composition.
     * Returns a function: (index, row) -> bitmap
     */
    fun getHangulJamoBitmaps(): (Int, Int) -> Array<ByteArray> {
        val filename = SheetConfig.fileList[SheetConfig.SHEET_HANGUL]
        val file = File(assetsDir, filename)
        if (!file.exists()) {
            println("  [WARNING] Hangul sheet not found")
            return { _, _ -> Array(SheetConfig.H) { ByteArray(SheetConfig.W_HANGUL_BASE) } }
        }

        val image = TgaReader.read(file)
        val cellW = SheetConfig.W_HANGUL_BASE
        val cellH = SheetConfig.H

        return { index: Int, row: Int ->
            val cellX = index * cellW
            val cellY = row * cellH
            Array(cellH) { r ->
                ByteArray(cellW) { c ->
                    val px = image.getPixel(cellX + c, cellY + r)
                    if (px and 0xFF != 0) 0xFF.toByte() else 0
                }
            }
        }
    }
}
