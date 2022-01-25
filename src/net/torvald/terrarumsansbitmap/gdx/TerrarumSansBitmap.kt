/*
 * Terrarum Sans Bitmap
 * 
 * Copyright (c) 2017-2022 see CONTRIBUTORS.txt
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.torvald.terrarumsansbitmap.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.*
import com.badlogic.gdx.utils.GdxRuntimeException
import net.torvald.terrarumsansbitmap.DiacriticsAnchor
import net.torvald.terrarumsansbitmap.GlyphProps
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.roundToInt
import kotlin.math.sign

typealias CodepointSequence = ArrayList<CodePoint>
internal typealias CodePoint = Int
internal typealias ARGB8888 = Int
internal typealias Hash = Long

/**
 * LibGDX port of Terrarum Sans Bitmap implementation
 *
 * Filename and Extension for the spritesheet is hard-coded, which are:
 *
 *  - ascii_variable.tga
 *  - hangul_johab.tga
 *  - LatinExtA_variable.tga
 *  - LatinExtB_variable.tga
 *  - kana.tga
 *  - cjkpunct.tga
 *  - wenquanyi.tga.gz
 *  - cyrillic_variable.tga
 *  - fullwidth_forms.tga
 *  - unipunct_variable.tga
 *  - greek_variable.tga
 *  - thai_variable.tga
 *  - puae000-e0ff.tga
 *
 *
 * Glyphs are drawn lazily (calculated on-the-fly, rather than load up all), which is inevitable as we just can't load
 * up 40k+ characters on the machine, which will certainly make loading time painfully long.
 *
 * Color Codes have following Unicode mapping: U+10RGBA, A must be non-zero to be visible. U+100000 reverts any colour
 * code effects.
 *
 * ## Control Characters
 *
 * - U+100000: Clear colour keys
 * - U+100001..U+10FFFF: Colour key (in RGBA order)
 * - U+FFFC0: Charset override -- Default (incl. Russian, Ukrainian, etc.)
 * - U+FFFC1: Charset override -- Bulgarian
 * - U+FFFC2: Charset override -- Serbian
 *
 * ## Auto Shift Down
 *
 * Certain characters (e.g. Combining Diacritical Marks) will automatically shift down to accomodate lowercase letters.
 * Shiftdown only occurs when non-diacritic character before the mark is lowercase, and the mark itself would stack up.
 * Stack-up or down is defined using Tag system.
 *
 *
 *
 *
 * @param noShadow Self-explanatory
 * @param flipY If you have Y-down coord system implemented on your GDX (e.g. legacy codebase), set this to ```true```
 * so that the shadow won't be upside-down.
 *
 * Created by minjaesong on 2017-06-15.
 */
class TerrarumSansBitmap(
    fontDir: String,
    val noShadow: Boolean = false,
    val flipY: Boolean = false,
    val invertShadow: Boolean = false,
    var errorOnUnknownChar: Boolean = false,
    val textCacheSize: Int = 256,
    val debug: Boolean = false,
    val shadowAlpha: Float = 0.5f,
    val shadowAlphaPremultiply: Boolean = false
) : BitmapFont() {

    private fun dbgprn(i: Any) { if (debug) println("[${this.javaClass.simpleName}] $i") }

    constructor(fontDir: String, noShadow: Boolean, flipY: Boolean, invertShadow: Boolean) : this(fontDir, noShadow, flipY, invertShadow, false, 256, false)

    /* This font is a collection of various subsystems, and thus contains copious amount of quick-and-dirty codes.
     *
     * Notable subsystems:
     * - Hangul Assembler with 19 sets
     * - Cyrillic Bulgarian and Serbian Variant Selectors
     * - Colour Codes
     * - Insular, Kana (these are relatively trivial; they just attemps to merge various Unicode sections
     *     into one sheet and gives custom internal indices)
     */

    private var textCacheCap = 0
    private val textCache = HashMap<Long, TextCacheObj>(textCacheSize * 2)

    /**
     * Insertion sorts the last element fo the textCache
     */
    private fun addToCache(text: CodepointSequence, linotype: Texture, width: Int) {
        val cacheObj = TextCacheObj(text.getHash(), ShittyGlyphLayout(text, linotype, width))

        if (textCacheCap < textCacheSize) {
            textCache[cacheObj.hash] = cacheObj
            textCacheCap += 1
        }
        else {
            // randomly eliminate one
            textCache.remove(textCache.keys.random())!!.dispose()

            // add new one
            textCache[cacheObj.hash] = cacheObj
        }
    }

    private fun getCache(hash: Long): TextCacheObj? {
        return textCache[hash]
    }


    private fun getColour(codePoint: Int): Int { // input: 0x10F_RGB, out: RGBA8888
        if (colourBuffer.containsKey(codePoint))
            return colourBuffer[codePoint]!!

        val r = codePoint.and(0x0F00).ushr(8)
        val g = codePoint.and(0x00F0).ushr(4)
        val b = codePoint.and(0x000F)

        val col = r.shl(28) or r.shl(24) or
                g.shl(20) or g.shl(16) or
                b.shl(12) or b.shl(8) or
                0xFF


        colourBuffer[codePoint] = col
        return col
    }


    private val colourBuffer = HashMap<CodePoint, ARGB8888>()
    private val fontParentDir = if (fontDir.endsWith('/') || fontDir.endsWith('\\')) fontDir else "$fontDir/"


    /** Props of all printable Unicode points. */
    private val glyphProps = HashMap<CodePoint, GlyphProps>()
    private val sheets: Array<PixmapRegionPack>

    private var charsetOverride = 0

    init {
        val sheetsPack = ArrayList<PixmapRegionPack>()

        // first we create pixmap to read pixels, then make texture using pixmap
        fileList.forEachIndexed { index, it ->
            val isVariable = it.endsWith("_variable.tga")
            val isXYSwapped = it.contains("xyswap", true)


            var pixmap: Pixmap


            if (isVariable) {
                if (isXYSwapped) {
                    dbgprn("loading texture $it [VARIABLE, XYSWAP]")
                }
                else {
                    dbgprn("loading texture $it [VARIABLE]")
                }
            }
            else {
                if (isXYSwapped) {
                    dbgprn("loading texture $it [XYSWAP]")
                }
                else {
                    dbgprn("loading texture $it")
                }
            }


            // unpack gz if applicable
            if (it.endsWith(".gz")) {
                val tmpFileName = "tmp_${it.dropLast(7)}.tga"

                try {
                    val gzi = GZIPInputStream(Gdx.files.internal(fontParentDir + it).read(8192))
                    val wholeFile = gzi.readBytes()
                    gzi.close()
                    val fos = BufferedOutputStream(FileOutputStream(tmpFileName))
                    fos.write(wholeFile)
                    fos.flush()
                    fos.close()

                    pixmap = Pixmap(Gdx.files.internal(tmpFileName))
                }
                catch (e: GdxRuntimeException) {
                    //e.printStackTrace()
                    dbgprn("said texture not found, skipping...")

                    pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
                }
                //File(tmpFileName).delete()
            }
            else {
                try {
                    pixmap = Pixmap(Gdx.files.internal(fontParentDir + it))
                }
                catch (e: GdxRuntimeException) {
                    //e.printStackTrace()
                    dbgprn("said texture not found, skipping...")

                    // if non-ascii chart is missing, replace it with null sheet
                    pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
                    // else, notify by error
                    if (index == 0) {
                        println("[${this.javaClass.simpleName}] The ASCII sheet is gone, something is wrong.")
                        System.exit(1)
                    }
                }
            }

            if (isVariable) buildWidthTable(pixmap, codeRange[index], 16)
            buildWidthTableFixed()


            /*if (!noShadow) {
                makeShadowForSheet(pixmap)
            }*/


            //val texture = Texture(pixmap)
            val texRegPack = if (isVariable)
                PixmapRegionPack(pixmap, W_VAR_INIT, H, HGAP_VAR, 0, xySwapped = isXYSwapped)
            else if (index == SHEET_UNIHAN)
                PixmapRegionPack(pixmap, W_UNIHAN, H_UNIHAN) // the only exception that is height is 16
            // below they all have height of 20 'H'
            else if (index == SHEET_CJK_PUNCT)
                PixmapRegionPack(pixmap, W_ASIAN_PUNCT, H)
            else if (index == SHEET_KANA)
                PixmapRegionPack(pixmap, W_KANA, H)
            else if (index == SHEET_HANGUL)
                PixmapRegionPack(pixmap, W_HANGUL_BASE, H)
            else if (index == SHEET_CUSTOM_SYM)
                PixmapRegionPack(pixmap, SIZE_CUSTOM_SYM, SIZE_CUSTOM_SYM) // TODO variable
            else if (index == SHEET_RUNIC)
                PixmapRegionPack(pixmap, W_LATIN_WIDE, H)
            else throw IllegalArgumentException("Unknown sheet index: $index")

            //texRegPack.texture.setFilter(minFilter, magFilter)

            sheetsPack.add(texRegPack)

            pixmap.dispose() // you are terminated
        }

        sheets = sheetsPack.toTypedArray()

        // make sure null char is actually null (draws nothing and has zero width)
        sheets[SHEET_ASCII_VARW].regions[0].setColor(0)
        sheets[SHEET_ASCII_VARW].regions[0].fill()
        glyphProps[0] = GlyphProps(0)
    }

    override fun getLineHeight(): Float = H.toFloat() * scale
    override fun getXHeight() = 8f * scale
    override fun getCapHeight() = 12f * scale
    override fun getAscent() = 3f * scale
    override fun getDescent() = 3f * scale
    override fun isFlipped() = flipY

    override fun setFixedWidthGlyphs(glyphs: CharSequence) {
        throw UnsupportedOperationException("Nope, no monospace, and figures are already fixed width, bruv.")
    }


    init {
        setUseIntegerPositions(true)
        setOwnsTexture(true)
    }

    private val offsetUnihan = (H - H_UNIHAN) / 2
    private val offsetCustomSym = (H - SIZE_CUSTOM_SYM) / 2

    private var flagFirstRun = true
    private var textBuffer = CodepointSequence(256)

    private lateinit var tempLinotype: Texture

    private var nullProp = GlyphProps(15)

    private val pixmapOffsetY = 10

    fun draw(batch: Batch, charSeq: CharSequence, x: Int, y: Int) = draw(batch, charSeq, x.toFloat(), y.toFloat())
    override fun draw(batch: Batch, charSeq: CharSequence, x: Float, y: Float): GlyphLayout? {
//        charSeq.forEach { dbgprn("${it.toInt().charInfo()} ${glyphProps[it.toInt()]}") }
        return drawNormalised(batch, charSeq.toCodePoints(), x, y)
    }
    fun draw(batch: Batch, codepoints: CodepointSequence, x: Int, y: Int) = drawNormalised(batch, codepoints.normalise(), x.toFloat(), y.toFloat())
    fun draw(batch: Batch, codepoints: CodepointSequence, x: Float, y: Float) = drawNormalised(batch, codepoints.normalise(), x, y)

    fun drawNormalised(batch: Batch, codepoints: CodepointSequence, x: Float, y: Float): GlyphLayout? {

//        codepoints.forEach { dbgprn("${it.charInfo()} ${glyphProps[it]}") }

        // Q&D fix for issue #12
        // When the line ends with a diacritics, the whole letter won't render
        // If the line starts with a letter-with-diacritic, it will error out
        // Some diacritics (e.g. COMBINING TILDE) do not obey lowercase letters
        val charSeqNotBlank = codepoints.size > 0 // determine emptiness BEFORE you hack a null chars in
        val newCodepoints = CodepointSequence()
        newCodepoints.add(0)
        newCodepoints.addAll(codepoints)
        newCodepoints.add(0)

        fun Int.flipY() = this * if (flipY) 1 else -1

        // always draw at integer position; this is bitmap font after all
        val x = Math.round(x)
        val y = Math.round(y)

        val charSeqHash = newCodepoints.getHash()

        var renderCol = -1 // subject to change with the colour code

        if (charSeqNotBlank) {

            val cacheObj = getCache(charSeqHash)

            if (cacheObj == null || flagFirstRun) {
                textBuffer = newCodepoints

                val (posXbuffer, posYbuffer) = buildPosMap(textBuffer)

                flagFirstRun = false

                //dbgprn("text not in buffer: $charSeq")


                //textBuffer.forEach { print("${it.toHex()} ") }
                //dbgprn()


//                resetHash(charSeq, x.toFloat(), y.toFloat())


                val _pw = posXbuffer.last()
                val _ph = H + (pixmapOffsetY * 2)
                if (_pw < 0 || _ph < 0) throw RuntimeException("Illegal linotype dimension (w: $_pw, h: $_ph)")
                val linotypePixmap = Pixmap(_pw, _ph, Pixmap.Format.RGBA8888)


                var index = 0
                while (index <= textBuffer.lastIndex) {
                    try {
                        val c = textBuffer[index]
                        val sheetID = getSheetType(c)
                        val (sheetX, sheetY) =
                            if (index == 0) getSheetwisePosition(0, c)
                            else getSheetwisePosition(textBuffer[index - 1], c)
                        val hash = getHash(c) // to be used with Bad Transmission Modifier

                        if (isColourCode(c)) {
                            if (c == 0x100000) {
                                renderCol = -1
                            }
                            else {
                                renderCol = getColour(c)
                            }
                        }
                        else if (isCharsetOverride(c)) {
                            charsetOverride = c - CHARSET_OVERRIDE_DEFAULT
                        }
                        else if (sheetID == SHEET_HANGUL) {
                            // Flookahead for {I, P, F}

                            val cNext = if (index + 1 < textBuffer.size) textBuffer[index + 1] else 0
                            val cNextNext = if (index + 2 < textBuffer.size) textBuffer[index + 2] else 0

                            val hangulLength = if (isHangulJongseong(cNextNext) && isHangulJungseong(cNext))
                                3
                            else if (isHangulJungseong(cNext))
                                2
                            else
                                1

                            val (indices, rows) = toHangulIndexAndRow(c, cNext, cNextNext)

                            val (indexCho, indexJung, indexJong) = indices
                            val (choRow, jungRow, jongRow) = rows
                            val hangulSheet = sheets[SHEET_HANGUL]



                            val choTex = hangulSheet.get(indexCho, choRow)
                            val jungTex = hangulSheet.get(indexJung, jungRow)
                            val jongTex = hangulSheet.get(indexJong, jongRow)

                            linotypePixmap.drawPixmap(choTex,  posXbuffer[index], pixmapOffsetY, renderCol)
                            linotypePixmap.drawPixmap(jungTex, posXbuffer[index], pixmapOffsetY, renderCol)
                            linotypePixmap.drawPixmap(jongTex, posXbuffer[index], pixmapOffsetY, renderCol)


                            index += hangulLength - 1

                        }
                        else {
                            try {
                                val posY = posYbuffer[index].flipY() +
                                        if (sheetID == SHEET_UNIHAN) // evil exceptions
                                            offsetUnihan
                                        else if (sheetID == SHEET_CUSTOM_SYM)
                                            offsetCustomSym
                                        else 0

                                val posX = posXbuffer[index]
                                val texture = sheets[sheetID].get(sheetX, sheetY)

                                linotypePixmap.drawPixmap(texture, posX, posY + pixmapOffsetY, renderCol)


                            }
                            catch (noSuchGlyph: ArrayIndexOutOfBoundsException) {
                            }
                        }


                        index++
                    }
                    catch (e: NullPointerException) {
                        System.err.println("Shit hit the multithreaded fan")
                        e.printStackTrace()
                        break
                    }
                }


                makeShadow(linotypePixmap)

                tempLinotype = Texture(linotypePixmap)
                tempLinotype.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)

                // put things into cache
                //textCache[charSeq] = ShittyGlyphLayout(textBuffer, linotype!!)
                addToCache(textBuffer, tempLinotype, posXbuffer.last())
                linotypePixmap.dispose()
            }
            else {
                textBuffer = cacheObj.glyphLayout!!.textBuffer
                tempLinotype = cacheObj.glyphLayout!!.linotype
            }

            batch.draw(tempLinotype,
                x.toFloat(),
                (y - pixmapOffsetY).toFloat() + (if (flipY) (tempLinotype.height) else 0) * scale,
                tempLinotype.width.toFloat() * scale,
                (tempLinotype.height.toFloat()) * (if (flipY) -1 else 1) * scale
            )
        }

        return null
    }


    override fun dispose() {
        super.dispose()
        textCache.values.forEach { it.dispose() }
        sheets.forEach { it.dispose() }
    }

    private fun getSheetType(c: CodePoint): Int {
        if (charsetOverride == 1 && isBulgarian(c))
            return SHEET_BULGARIAN_VARW
        else if (charsetOverride == 2 && isBulgarian(c))
            return SHEET_SERBIAN_VARW
        else if (isHangul(c))
            return SHEET_HANGUL
        else {
            for (i in codeRange.indices) {
                if (c in codeRange[i]) return i
            }
            return SHEET_UNKNOWN
        }
    }

    private fun getSheetwisePosition(cPrev: Int, ch: Int): IntArray {
        val sheetX: Int; val sheetY: Int
        when (getSheetType(ch)) {
            SHEET_UNIHAN -> {
                sheetX = unihanIndexX(ch)
                sheetY = unihanIndexY(ch)
            }
            SHEET_EXTA_VARW -> {
                sheetX = extAindexX(ch)
                sheetY = extAindexY(ch)
            }
            SHEET_EXTB_VARW -> {
                sheetX = extBindexX(ch)
                sheetY = extBindexY(ch)
            }
            SHEET_KANA -> {
                sheetX = kanaIndexX(ch)
                sheetY = kanaIndexY(ch)
            }
            SHEET_CJK_PUNCT -> {
                sheetX = cjkPunctIndexX(ch)
                sheetY = cjkPunctIndexY(ch)
            }
            SHEET_CYRILIC_VARW -> {
                sheetX = cyrilicIndexX(ch)
                sheetY = cyrilicIndexY(ch)
            }
            SHEET_HALFWIDTH_FULLWIDTH_VARW -> {
                sheetX = fullwidthUniIndexX(ch)
                sheetY = fullwidthUniIndexY(ch)
            }
            SHEET_UNI_PUNCT_VARW -> {
                sheetX = uniPunctIndexX(ch)
                sheetY = uniPunctIndexY(ch)
            }
            SHEET_GREEK_VARW -> {
                sheetX = greekIndexX(ch)
                sheetY = greekIndexY(ch)
            }
            SHEET_THAI_VARW -> {
                sheetX = thaiIndexX(ch)
                sheetY = thaiIndexY(ch)
            }
            SHEET_CUSTOM_SYM -> {
                sheetX = symbolIndexX(ch)
                sheetY = symbolIndexY(ch)
            }
            SHEET_HAYEREN_VARW -> {
                sheetX = armenianIndexX(ch)
                sheetY = armenianIndexY(ch)
            }
            SHEET_KARTULI_VARW -> {
                sheetX = kartvelianIndexX(ch)
                sheetY = kartvelianIndexY(ch)
            }
            SHEET_IPA_VARW -> {
                sheetX = ipaIndexX(ch)
                sheetY = ipaIndexY(ch)
            }
            SHEET_RUNIC -> {
                sheetX = runicIndexX(ch)
                sheetY = runicIndexY(ch)
            }
            SHEET_LATIN_EXT_ADD_VARW -> {
                sheetX = latinExtAddX(ch)
                sheetY = latinExtAddY(ch)
            }
            SHEET_BULGARIAN_VARW, SHEET_SERBIAN_VARW -> { // expects Unicode charpoint, NOT an internal one
                sheetX = cyrilicIndexX(ch)
                sheetY = cyrilicIndexY(ch)
            }
            SHEET_TSALAGI_VARW -> {
                sheetX = cherokeeIndexX(ch)
                sheetY = cherokeeIndexY(ch)
            }
            SHEET_PHONETIC_EXT_VARW -> {
                sheetX = phoneticExtIndexX(ch)
                sheetY = phoneticExtIndexY(ch)
            }
            SHEET_DEVANAGARI_VARW -> {
                sheetX = devanagariIndexX(ch)
                sheetY = devanagariIndexY(ch)
            }
            SHEET_KARTULI_CAPS_VARW -> {
                sheetX = kartvelianCapsIndexX(ch)
                sheetY = kartvelianCapsIndexY(ch)
            }
            SHEET_DIACRITICAL_MARKS_VARW -> {
                sheetX = diacriticalMarksIndexX(ch)
                sheetY = diacriticalMarksIndexY(ch)
            }
            SHEET_GREEK_POLY_VARW -> {
                sheetX = polytonicGreekIndexX(ch)
                sheetY = polytonicGreekIndexY(ch)
            }
            SHEET_EXTC_VARW -> {
                sheetX = extCIndexX(ch)
                sheetY = extCIndexY(ch)
            }
            SHEET_EXTD_VARW -> {
                sheetX = extDIndexX(ch)
                sheetY = extDIndexY(ch)
            }
            SHEET_CURRENCIES_VARW -> {
                sheetX = currenciesIndexX(ch)
                sheetY = currenciesIndexY(ch)
            }
            SHEET_INTERNAL_VARW -> {
                sheetX = internalIndexX(ch)
                sheetY = internalIndexY(ch)
            }
            SHEET_LETTERLIKE_MATHS_VARW -> {
                sheetX = letterlikeIndexX(ch)
                sheetY = letterlikeIndexY(ch)
            }
            SHEET_ENCLOSED_ALPHNUM_SUPL_VARW -> {
                sheetX = enclosedAlphnumSuplX(ch)
                sheetY = enclosedAlphnumSuplY(ch)
            }
            SHEET_TAMIL_VARW -> {
                sheetX = tamilIndexX(ch)
                sheetY = tamilIndexY(ch)
            }
            SHEET_BENGALI_VARW -> {
                sheetX = bengaliIndexX(ch)
                sheetY = bengaliIndexY(ch)
            }
            else -> {
                sheetX = ch % 16
                sheetY = ch / 16
            }
        }

        return intArrayOf(sheetX, sheetY)
    }

    private fun Boolean.toInt() = if (this) 1 else 0
    /** @return THIRTY-TWO bit number: this includes alpha channel value; or 0 if alpha is zero */
    private fun Int.tagify() = if (this and 255 == 0) 0 else this

    private fun buildWidthTable(pixmap: Pixmap, codeRange: Iterable<Int>, cols: Int = 16) {
        val binaryCodeOffset = W_VAR_INIT

        val cellW = W_VAR_INIT + 1
        val cellH = H

        codeRange.forEachIndexed { index, code ->

            val cellX = (index % cols) * cellW
            val cellY = (index / cols) * cellH

            val codeStartX = cellX + binaryCodeOffset
            val codeStartY = cellY

            val width = (0..4).fold(0) { acc, y -> acc or ((pixmap.getPixel(codeStartX, codeStartY + y).and(255) != 0).toInt() shl y) }
            val isLowHeight = (pixmap.getPixel(codeStartX, codeStartY + 5).and(255) != 0)

            // Keming machine parameters
            val kerningBit1 = pixmap.getPixel(codeStartX, codeStartY + 6).tagify()
            val kerningBit2 = pixmap.getPixel(codeStartX, codeStartY + 7).tagify()
            val kerningBit3 = pixmap.getPixel(codeStartX, codeStartY + 8).tagify()
            var isKernYtype = ((kerningBit1 and 0x80000000.toInt()) != 0)
            var kerningMask = kerningBit1.ushr(8).and(0xFFFFFF)
            val hasKernData = kerningBit1 and 255 != 0//(kerningBit1 and 255 != 0 && kerningMask != 0xFFFF)
            if (!hasKernData) {
                isKernYtype = false
                kerningMask = 255
            }

            val compilerDirectives = pixmap.getPixel(codeStartX, codeStartY + 9).tagify()
            val directiveOpcode = compilerDirectives.ushr(24).and(255)
            val directiveArg1 = compilerDirectives.ushr(16).and(255)
            val directiveArg2 = compilerDirectives.ushr(8).and(255)


            val nudgingBits = pixmap.getPixel(codeStartX, codeStartY + 10).tagify()
            val nudgeX = nudgingBits.ushr(24).toByte().toInt() // signed 8-bit int
            val nudgeY = nudgingBits.ushr(16).toByte().toInt() // signed 8-bit int

            val diacriticsAnchors = (0..5).map {
                val yPos = 13 - (it / 3) * 2
                val shift = (3 - (it % 3)) * 8
                val yPixel = pixmap.getPixel(codeStartX, codeStartY + yPos).tagify()
                val xPixel = pixmap.getPixel(codeStartX, codeStartY + yPos + 1).tagify()
                val yUsed = (yPixel ushr shift) and 128 != 0
                val xUsed = (xPixel ushr shift) and 128 != 0
                val y = if (yUsed) (yPixel ushr shift) and 127 else 0
                val x = if (xUsed) (xPixel ushr shift) and 127 else 0

                DiacriticsAnchor(it, x, y, xUsed, yUsed)
            }.toTypedArray()

            val alignWhere = (0..1).fold(0) { acc, y -> acc or ((pixmap.getPixel(codeStartX, codeStartY + y + 15).and(255) != 0).toInt() shl y) }

            var writeOnTop = pixmap.getPixel(codeStartX, codeStartY + 17) // NO .tagify()
            if (writeOnTop and 255 == 0) writeOnTop = -1 // look for the alpha channel
            else {
                if (writeOnTop.ushr(8) == 0xFFFFFF) writeOnTop = 0
                else writeOnTop = writeOnTop.ushr(28) and 15
            }

            val stackWhere0 = pixmap.getPixel(codeStartX, codeStartY + 18).tagify()
            val stackWhere1 = pixmap.getPixel(codeStartX, codeStartY + 19).tagify()

            val stackWhere = if (stackWhere0 == 0x00FF00FF && stackWhere1 == 0x00FF00FF)
                GlyphProps.STACK_DONT
            else (0..1).fold(0) { acc, y -> acc or ((pixmap.getPixel(codeStartX, codeStartY + y + 18).and(255) != 0).toInt() shl y) }

            glyphProps[code] = GlyphProps(width, isLowHeight, nudgeX, nudgeY, diacriticsAnchors, alignWhere, writeOnTop, stackWhere, IntArray(15), hasKernData, isKernYtype, kerningMask, directiveOpcode, directiveArg1, directiveArg2)

            // extra info
            val extCount = glyphProps[code]?.requiredExtInfoCount() ?: 0
            if (extCount > 0) {

                for (x in 0 until extCount) {
                    var info = 0
                    for (y in 0..19) {
                        // if ALPHA is not zero, assume it's 1
                        if (pixmap.getPixel(cellX + x, cellY + y).and(255) != 0) {
                            info = info or (1 shl y)
                        }
                    }

                    glyphProps[code]!!.extInfo[x] = info
                }

//                println("[TerrarumSansBitmap] char with $extCount extra info: ${code.charInfo()}; opcode: ${directiveOpcode.toString(16)}")
//                println("contents: ${glyphProps[code]!!.extInfo.map { it.toString(16) }.joinToString()}")
            }


            // Debug prints //
//            dbgprn(">>  ${code.charInfo()}  <<  ")
//            if (nudgingBits != 0) dbgprn("${code.charInfo()} nudgeX=$nudgeX, nudgeY=$nudgeY, nudgingBits=0x${nudgingBits.toString(16)}")
//            if (writeOnTop >= 0) dbgprn("WriteOnTop: ${code.charInfo()} (Type-${writeOnTop})")
//            if (diacriticsAnchors.any { it.xUsed || it.yUsed }) dbgprn("${code.charInfo()} ${diacriticsAnchors.filter { it.xUsed || it.yUsed }.joinToString()}")
//            if (directiveOpcode != 0) dbgprn("Directive opcode ${directiveOpcode.toString(2)}: ${code.charInfo()}")
//            if (glyphProps[code]?.isPragma("replacewith") == true) dbgprn("Replacer: ${code.charInfo()} into ${glyphProps[code]!!.extInfo.map { it.toString(16) }.joinToString()}")
//            if (stackWhere == GlyphProps.STACK_DONT) dbgprn("Diacritics Don't stack: ${code.charInfo()}")
        }
    }

    private fun buildWidthTableFixed() {
        // fixed-width props
        codeRange[SHEET_CJK_PUNCT].forEach { glyphProps[it] = GlyphProps(W_ASIAN_PUNCT) }
        codeRange[SHEET_CUSTOM_SYM].forEach { glyphProps[it] = GlyphProps(20) }
        codeRange[SHEET_HANGUL].forEach { glyphProps[it] = GlyphProps(W_HANGUL_BASE) }
        codeRangeHangulCompat.forEach { glyphProps[it] = GlyphProps(W_HANGUL_BASE) }
        codeRange[SHEET_KANA].forEach { glyphProps[it] = GlyphProps(W_KANA) }
        codeRange[SHEET_RUNIC].forEach { glyphProps[it] = GlyphProps(9) }
        codeRange[SHEET_UNIHAN].forEach { glyphProps[it] = GlyphProps(W_UNIHAN) }
        (0xD800..0xDFFF).forEach { glyphProps[it] = GlyphProps(0) }
        (0x100000..0x10FFFF).forEach { glyphProps[it] = GlyphProps(0) }
        (0xFFFA0..0xFFFFF).forEach { glyphProps[it] = GlyphProps(0) }


        // manually add width of one orphan insular letter
        // WARNING: glyphs in 0xA770..0xA778 has invalid data, further care is required
        glyphProps[0x1D79] = GlyphProps(9)


        // U+007F is DEL originally, but this font stores bitmap of Replacement Character (U+FFFD)
        // to this position. String replacer will replace U+FFFD into U+007F.
        glyphProps[0x7F] = GlyphProps(15)

    }

    private val glyphLayout = GlyphLayout()

    fun getWidth(text: String) = getWidthNormalised(text.toCodePoints())
    fun getWidth(s: CodepointSequence) = getWidthNormalised(s.normalise())

    fun getWidthNormalised(s: CodepointSequence): Int {
        val cacheObj = getCache(s.getHash())

        if (cacheObj != null) {
            return cacheObj.glyphLayout!!.width
        }
        else {
            return buildPosMap(s).first.last()
        }
    }



    /**
     * THE function to typeset all the letters and their diacritics
     *
     * @return Pair of X-positions and Y-positions, of which the X-position's size is greater than the string
     * and the last element marks the width of entire string.
     */
    private fun buildPosMap(str: List<Int>): Pair<IntArray, IntArray> {
        val posXbuffer = IntArray(str.size + 1) { 0 }
        val posYbuffer = IntArray(str.size) { 0 }


        var nonDiacriticCounter = 0 // index of last instance of non-diacritic char
        var stackUpwardCounter = 0 // TODO separate stack counter for centre- and right aligned
        var stackDownwardCounter = 0

        val HALF_VAR_INIT = W_VAR_INIT.minus(1).div(2)

        // this is starting to get dirty...
        // persisting value. the value is set a few characters before the actual usage
        var extraWidth = 0

        try {
            for (charIndex in 0 until posXbuffer.size - 1) {
                if (charIndex > 0) {
                    // nonDiacriticCounter allows multiple diacritics

                    val thisChar = str[charIndex]
                    if (glyphProps[thisChar] == null && errorOnUnknownChar) {
                        val errorGlyphSB = StringBuilder()
                        Character.toChars(thisChar).forEach { errorGlyphSB.append(it) }

                        throw InternalError("No GlyphProps for char '$errorGlyphSB' " +
                                "(${thisChar.charInfo()})")
                    }
                    val thisProp = glyphProps[thisChar] ?: nullProp
                    val lastNonDiacriticChar = str[nonDiacriticCounter]
                    val itsProp = glyphProps[lastNonDiacriticChar] ?: nullProp
                    val kerning = getKerning(lastNonDiacriticChar, thisChar)


                    //dbgprn("char: ${thisChar.charInfo()}\nproperties: $thisProp")


                    var alignmentOffset = when (thisProp.alignWhere) {
                        GlyphProps.ALIGN_LEFT -> 0
                        GlyphProps.ALIGN_RIGHT -> thisProp.width - W_VAR_INIT
                        GlyphProps.ALIGN_CENTRE -> Math.ceil((thisProp.width - W_VAR_INIT) / 2.0).toInt()
                        else -> 0 // implies "diacriticsBeforeGlyph = true"
                    }


                    // shoehorn the wider-hangul-width thingamajig
                    // widen only when the next hangul char is not "jungseongWide"
                    // (애 in "애슬론" should not be widened)
                    val thisHangulJungseongIndex = toHangulJungseongIndex(thisChar)
                    val nextHangulJungseong1 = toHangulJungseongIndex(str.getOrNull(charIndex + 2) ?: 0) ?: -1
                    val nextHangulJungseong2 = toHangulJungseongIndex(str.getOrNull(charIndex + 3) ?: 0) ?: -1
                    if (isHangulJungseong(thisChar) && thisHangulJungseongIndex in hangulPeaksWithExtraWidth && (
                                nextHangulJungseong1 !in jungseongWide ||
                                        nextHangulJungseong2 !in jungseongWide
                                )) {
                        //dbgprn("char: ${thisChar.charInfo()}\nproperties: $thisProp")
                        //dbgprn("${thisChar.charInfo()}  ${str.getOrNull(charIndex + 2)?.charInfo()}  ${str.getOrNull(charIndex + 3)?.charInfo()}")
                        extraWidth += 1
                    }


                    if (isHangul(thisChar) && !isHangulChoseong(thisChar) && !isHangulCompat(thisChar)) {
                        posXbuffer[charIndex] = posXbuffer[nonDiacriticCounter]
                    }
                    // is this glyph NOT a diacritic?
                    else if (thisProp.writeOnTop < 0) {
                        posXbuffer[charIndex] = -thisProp.nudgeX +
                                when (itsProp.alignWhere) {
                                    GlyphProps.ALIGN_RIGHT ->
                                        posXbuffer[nonDiacriticCounter] + W_VAR_INIT + alignmentOffset + interchar + kerning + extraWidth
                                    GlyphProps.ALIGN_CENTRE ->
                                        posXbuffer[nonDiacriticCounter] + HALF_VAR_INIT + itsProp.width + alignmentOffset + interchar + kerning + extraWidth
                                    else ->
                                        posXbuffer[nonDiacriticCounter] + itsProp.width + alignmentOffset + interchar + kerning + extraWidth
                                }

                        nonDiacriticCounter = charIndex

                        stackUpwardCounter = 0
                        stackDownwardCounter = 0
                        extraWidth = thisProp.nudgeX // NOTE: sign is flipped!
                    }
                    // FIXME HACK: using 0th diacritics' X-anchor pos as a type selector
                    /*else if (thisProp.writeOnTop && thisProp.diacriticsAnchors[0].x == GlyphProps.DIA_JOINER) {
                        posXbuffer[charIndex] = when (itsProp.alignWhere) {
                            GlyphProps.ALIGN_RIGHT ->
                                posXbuffer[nonDiacriticCounter] + W_VAR_INIT + alignmentOffset
                            //GlyphProps.ALIGN_CENTRE ->
                            //    posXbuffer[nonDiacriticCounter] + HALF_VAR_INIT + itsProp.width + alignmentOffset
                            else ->
                                posXbuffer[nonDiacriticCounter] + itsProp.width + alignmentOffset

                        }
                    }*/
                    // is this glyph a diacritic?
                    else {
                        val diacriticsType = thisProp.writeOnTop
                        // set X pos according to alignment information
                        posXbuffer[charIndex] = when (thisProp.alignWhere) {
                            GlyphProps.ALIGN_LEFT, GlyphProps.ALIGN_BEFORE -> posXbuffer[nonDiacriticCounter]
                            GlyphProps.ALIGN_RIGHT -> {
                                val alignXPos = if (!itsProp.diacriticsAnchors[diacriticsType].xUsed) itsProp.width else itsProp.diacriticsAnchors[diacriticsType].x

                                posXbuffer[nonDiacriticCounter] - W_VAR_INIT + alignXPos
                            }
                            GlyphProps.ALIGN_CENTRE -> {
                                val alignXPos = if (!itsProp.diacriticsAnchors[diacriticsType].xUsed) itsProp.width.div(2) else itsProp.diacriticsAnchors[diacriticsType].x

                                if (itsProp.alignWhere == GlyphProps.ALIGN_RIGHT) {
                                    posXbuffer[nonDiacriticCounter] + alignXPos + (itsProp.width + 1).div(2)
                                }
                                else {
                                    posXbuffer[nonDiacriticCounter] + alignXPos - HALF_VAR_INIT
                                }
                            }
                            else -> throw InternalError("Unsupported alignment: ${thisProp.alignWhere}")
                        }


                        // set Y pos according to diacritics position
//                        if (thisProp.alignWhere == GlyphProps.ALIGN_CENTRE) {
                        when (thisProp.stackWhere) {
                            GlyphProps.STACK_DOWN -> {
                                posYbuffer[charIndex] = H_DIACRITICS * stackDownwardCounter * flipY.toSign()
                                stackDownwardCounter++
                            }
                            GlyphProps.STACK_UP -> {
                                posYbuffer[charIndex] = -H_DIACRITICS * stackUpwardCounter * flipY.toSign()
                                // shift down on lowercase if applicable
                                if (getSheetType(thisChar) in autoShiftDownOnLowercase &&
                                    lastNonDiacriticChar.isLowHeight()) {
                                    //dbgprn("AAARRRRHHHH for character ${thisChar.toHex()}")
                                    //dbgprn("lastNonDiacriticChar: ${lastNonDiacriticChar.toHex()}")
                                    //dbgprn("cond: ${thisProp.alignXPos == GlyphProps.DIA_OVERLAY}, charIndex: $charIndex")
                                    if (diacriticsType == GlyphProps.DIA_OVERLAY)
                                        posYbuffer[charIndex] += H_OVERLAY_LOWERCASE_SHIFTDOWN * flipY.toSign() // if minus-assign doesn't work, try plus-assign
                                    else
                                        posYbuffer[charIndex] += H_STACKUP_LOWERCASE_SHIFTDOWN * flipY.toSign() // if minus-assign doesn't work, try plus-assign
                                }

                                stackUpwardCounter++

//                                    dbgprn("lastNonDiacriticChar: ${lastNonDiacriticChar.charInfo()}; stack counter: $stackUpwardCounter")
                            }
                            GlyphProps.STACK_UP_N_DOWN -> {
                                posYbuffer[charIndex] = H_DIACRITICS * stackDownwardCounter * flipY.toSign()
                                stackDownwardCounter++


                                posYbuffer[charIndex] = -H_DIACRITICS * stackUpwardCounter * flipY.toSign()
                                // shift down on lowercase if applicable
                                if (getSheetType(thisChar) in autoShiftDownOnLowercase &&
                                    lastNonDiacriticChar.isLowHeight()) {
                                    if (diacriticsType == GlyphProps.DIA_OVERLAY)
                                        posYbuffer[charIndex] += H_OVERLAY_LOWERCASE_SHIFTDOWN * flipY.toSign() // if minus-assign doesn't work, try plus-assign
                                    else
                                        posYbuffer[charIndex] += H_STACKUP_LOWERCASE_SHIFTDOWN * flipY.toSign() // if minus-assign doesn't work, try plus-assign
                                }

                                stackUpwardCounter++
                            }
                            // for BEFORE_N_AFTER, do nothing in here
                        }
//                        }
                    }
                }
            }

            // fill the last of the posXbuffer
            if (str.isNotEmpty()) {
                val lastCharProp = glyphProps[str.last()]
                val penultCharProp = glyphProps[str[nonDiacriticCounter]] ?:
                (if (errorOnUnknownChar) throw throw InternalError("No GlyphProps for char '${str[nonDiacriticCounter]}' " +
                        "(${str[nonDiacriticCounter].charInfo()})") else nullProp)
                posXbuffer[posXbuffer.lastIndex] = 1 + posXbuffer[posXbuffer.lastIndex - 1] + // adding 1 to house the shadow
                        if (lastCharProp != null && lastCharProp.writeOnTop >= 0) {
                            val realDiacriticWidth = if (lastCharProp.alignWhere == GlyphProps.ALIGN_CENTRE) {
                                (lastCharProp.width).div(2) + penultCharProp.diacriticsAnchors[0].x
                            }
                            else if (lastCharProp.alignWhere == GlyphProps.ALIGN_RIGHT) {
                                (lastCharProp.width) + penultCharProp.diacriticsAnchors[0].x
                            }
                            else 0

                            maxOf(penultCharProp.width, realDiacriticWidth)
                        }
                        else {
                            (lastCharProp?.width ?: 0)
                        }
            }
            else {
                posXbuffer[0] = 0
            }
        }
        catch (e: NullPointerException) {}

        return posXbuffer to posYbuffer
    }

    private fun CodepointSequence.utf16to32(): CodepointSequence {
        val seq = CodepointSequence()

        var i = 0
        while (i < this.size) {
            val c = this[i]

            // check UTF-16 surrogates
            if (i < this.lastIndex && c.isHighSurrogate()) {
                val cNext = this[i + 1]

                if (!cNext.isLowSurrogate()) {
                    // replace with Unicode replacement char
                    seq.add(0x7F) // 0x7F in used internally to display <??> character
                }
                else {
                    val H = c
                    val L = cNext

                    seq.add(surrogatesToCodepoint(H, L))

                    i++ // skip next char (guaranteed to be Low Surrogate)
                }
            }
            else {
                seq.add(c)
            }

            i++
        }

        return seq
    }

    // basically an Unicode NFD with some additional flavours
    private fun CodepointSequence.normalise(): CodepointSequence {
        val seq = CodepointSequence()
        val seq2 = CodepointSequence()

        val yankedCharacters  = Stack<Pair<Int, CodePoint>>() // Stack of <Position, CodePoint>; codepoint use -1 if not applicable
        var yankedDevanagariRaStatus = intArrayOf(0,0) // 0: none, 1: consonants, 2: virama, 3: vowel for this syllable

        fun changeRaStatus(n: Int) {
            yankedDevanagariRaStatus[0] = yankedDevanagariRaStatus[1]
            yankedDevanagariRaStatus[1] = n
        }
        fun resetRaStatus() {
            yankedDevanagariRaStatus[0] = 0
            yankedDevanagariRaStatus[1] = 0
        }

        fun emptyOutYanked() {
            while (!yankedCharacters.empty()) {
                val poppedChar = yankedCharacters.pop()
                if (poppedChar.second == DEVANAGARI_RA)
                    seq.add(DEVANAGARI_RA_SUPER)
                else
                    seq.add(yankedCharacters.pop().second)
            }
        }

        var i = 0
        this.utf16to32().let { dis ->
        while (i < dis.size) {
            val cPrev2 = dis.getOrElse(i-2) { -1 }
            val cPrev = dis.getOrElse(i-1) { -1 }
            val c = dis[i]
            val cNext = dis.getOrElse(i+1) { -1 }
            val cNext2 = dis.getOrElse(i+2) { -1 }
            // can't use regular sliding window as the 'i' value is changed way too often

            // LET THE NORMALISATION BEGIN //

            // disassemble Hangul Syllables into Initial-Peak-Final encoding
            if (c in 0xAC00..0xD7A3) {
                val cInt = c - 0xAC00
                val indexCho  = getWanseongHanChoseong(cInt)
                val indexJung = getWanseongHanJungseong(cInt)
                val indexJong = getWanseongHanJongseong(cInt) - 1 // no Jongseong will be -1

                // these magic numbers only makes sense if you look at the Unicode chart of Hangul Jamo
                // https://www.unicode.org/charts/PDF/U1100.pdf
                seq.add(0x1100 + indexCho)
                seq.add(0x1161 + indexJung)
                if (indexJong >= 0) seq.add(0x11A8 + indexJong)
            }
            // normalise CJK Compatibility area because fuck them
            else if (c in 0x3300..0x33FF) {
                seq.add(0x7F) // fuck them
            }
            // add filler to malformed Hangul Initial-Peak-Final
            else if (isHangul(c) && !isHangulCompat(c)) {
                // possible cases

                // IPF: hangul on the sequence
                // i: HCF, p: HJF, x: non-hangul
                // +cPrev
                // v >$<: c
                // I >I< -> I p I
                // I >F< -> I p F
                // I >x< -> I p x

                // P >P< -> P i P

                // F >P< -> F i P
                // F >F< -> F ip F

                // x >P< -> x i P
                // x >F< -> x ip F

                if (isHangulChoseong(cPrev) && (isHangulChoseong(c) || isHangulJongseong(c) || !isHangul(c))) {
                    seq.add(HJF)
                }
                else if (isHangulJungseong(cPrev) && isHangulJungseong(c)) {
                    seq.add(HCF)
                }
                else if (isHangulJongseong(cPrev)) {
                    if (isHangulJungseong(c)) seq.add(HCF)
                    else if (isHangulJongseong(c)) { seq.add(HCF); seq.add(HJF) }
                }
                else if (!isHangul(cPrev)) {
                    if (isHangulJungseong(c)) seq.add(HCF)
                    else if (isHangulJongseong(c)) { seq.add(HCF); seq.add(HJF) }
                }

                seq.add(c)

            }
            // for lowercase i and j, if cNext is a diacritic that goes on top, remove the dots
            else if (diacriticDotRemoval.containsKey(c) && (glyphProps[cNext]?.writeOnTop ?: -1) >= 0 && glyphProps[cNext]?.stackWhere == GlyphProps.STACK_UP) {
                seq.add(diacriticDotRemoval[c]!!)
            }

            // BEGIN of tamil subsystem implementation
            else if (c == 0xB95 && cNext == 0xBCD && dis.getOrElse(i+2){-1} == 0xBB7) {
                seq.add(TAMIL_KSSA); i += 2
            }
            else if (c == 0xBB6 && cNext == 0xBCD && dis.getOrElse(i+2){-1} == 0xBB0 && dis.getOrElse(i+3){-1} == 0xBC0) {
                seq.add(TAMIL_SHRII); i += 3
            }
            else if (c == 0xB9F && cNext == 0xBBF) {
                seq.add(0xF00C0); i++
            }
            else if (c == 0xB9F && cNext == 0xBC0) {
                seq.add(0xF00C1); i++
            }
            else if (tamilLigatingConsonants.contains(c) && (cNext == 0xBC1 || cNext == 0xBC2)) {
                val it = tamilLigatingConsonants.indexOf(c)

                if (cNext == 0xBC1)
                    seq.add(0xF00C2 + it)
//                    dbgprn("${c.toString(16)} + ${cNext.toString(16)} replaced with ${(0xF00C2 + it).toString(16)}")
                else
                    seq.add(0xF00D4 + it)
//                    dbgprn("${c.toString(16)} + ${cNext.toString(16)} replaced with ${(0xF00D4 + it).toString(16)}")

                i += 1
            }
            // END of tamil subsystem implementation

            // BEGIN of devanagari string replacer
            // Unicode Devanagari Rendering Rule R14
            else if (c == DEVANAGARI_RA && cNext == DEVANAGARI_U) {
                seq.add(DEVANAGARI_SYLL_RU); i += 1
            }
            else if (c == DEVANAGARI_RA && cNext == DEVANAGARI_UU) {
                seq.add(DEVANAGARI_SYLL_RUU); i += 1
            }
            else if (c == DEVANAGARI_RRA && cNext == DEVANAGARI_U) {
                seq.add(DEVANAGARI_SYLL_RRU); i += 1
            }
            else if (c == DEVANAGARI_RRA && cNext == DEVANAGARI_UU) {
                seq.add(DEVANAGARI_SYLL_RRUU); i += 1
            }
            else if (c == DEVANAGARI_HA && cNext == DEVANAGARI_U) {
                seq.add(DEVANAGARI_SYLL_HU); i += 1
            }
            else if (c == DEVANAGARI_HA && cNext == DEVANAGARI_UU) {
                seq.add(DEVANAGARI_SYLL_HUU); i += 1
            }
            // Unicode Devanagari Rendering Rule R6-R8
            // (this must precede the ligaturing-machine coded on the 2nd pass, otherwise the rules below will cause undesirable effects)
            else if (devanagariConsonants.contains(c) && cNext == DEVANAGARI_VIRAMA && cNext2 == DEVANAGARI_RA) {
                seq.addAll(ligateIndicConsonants(c, cNext2))
                i += 2
            }
            // Unicode Devanagari Rendering Rule R5
            else if (c == DEVANAGARI_RRA && cNext == DEVANAGARI_VIRAMA || c == DEVANAGARI_RA && cNext == DEVANAGARI_VIRAMA && cNext2 == ZWJ) {
                seq.add(DEVANAGARI_EYELASH_RA)
                i += 1
            }
            // Unicode Devanagari Rendering Rule R2-R4
            // in Regex: RA (vir C)+ V* ᴿ [not V && not vir]
            else if (yankedDevanagariRaStatus[1] == 1 && c == DEVANAGARI_VIRAMA) {
                if (yankedDevanagariRaStatus[0] != 0)
                    seq.add(c)
                changeRaStatus(2)
            }
            else if (yankedDevanagariRaStatus[1] == 2 && devanagariConsonants.contains(c)) {
                seq.add(c)
                changeRaStatus(1)
            }
            else if ((yankedDevanagariRaStatus[1] == 1 || yankedDevanagariRaStatus[1] == 3) && devanagariVerbs.contains(c)) {
                seq.add(c)
                changeRaStatus(3)
            }
//            else if (yankedDevanagariRaStatus == 3 && !devanagariVerbs.contains(c)) {
            else if (yankedDevanagariRaStatus[1] > 0 && yankedCharacters.peek().second == DEVANAGARI_RA) { // termination or illegal state for Devanagari RA
                yankedCharacters.pop()
                seq.add(DEVANAGARI_RA_SUPER)
                resetRaStatus()
                i-- // scan this character again next time
            }
            else if (c == DEVANAGARI_RA && cNext == DEVANAGARI_VIRAMA && devanagariConsonants.contains(c)) {
                yankedCharacters.push(i to c)
                changeRaStatus(1)
            }
            else if (!isDevanagari(c) && !yankedCharacters.empty()) {
                emptyOutYanked()
                seq.add(c)
                resetRaStatus()
            }
            // WIP
            // END of devanagari string replacer
            // rearrange {letter, before-and-after diacritics} as {before-diacritics, letter, after-diacritics}
            else if (glyphProps[c]?.stackWhere == GlyphProps.STACK_BEFORE_N_AFTER) {
                val diacriticsProp = glyphProps[c]!!
                // seq.add(c) // base char is added by previous iteration, AS WE'RE LOOKING AT 'c' not 'cNext'
                seq.add(diacriticsProp.extInfo[0]) // align before
                seq.add(diacriticsProp.extInfo[1]) // align after
                // The order may seem "wrong" but trust me it'll be corrected by the swapping code below

//                dbgprn("B&A: ${cNext.charInfo()} replaced with ${diacriticsProp.extInfo[0].toString(16)} ${c.toString(16)} ${diacriticsProp.extInfo[1].toString(16)}")
            }
            // U+007F is DEL originally, but dis font stores bitmap of Replacement Character (U+FFFD)
            // to dis position. dis line will replace U+FFFD into U+007F.
            else {
                seq.add(c)
            }

            i++
        }
        emptyOutYanked()
        }


        // second scan
        // swap position of {letter, diacritics that comes before the letter}
        i = 1
        while (i <= seq.lastIndex) {

            if ((glyphProps[seq[i]] ?: nullProp).alignWhere == GlyphProps.ALIGN_BEFORE) {
                val t = seq[i - 1]
                seq[i - 1] = seq[i]
                seq[i] = t
            }

            val cPrev2 = seq.getOrElse(i-2) { -1 }
            val cPrev = seq.getOrElse(i-1) { -1 }
            val c = seq[i]

            // BEGIN of Devanagari String Replacer 2 (lookbehind type)
            // Devanagari Ligations (Lookbehind)
            if (devanagariConsonants.contains(cPrev2) && cPrev == DEVANAGARI_VIRAMA && devanagariConsonants.contains(c)) {
                i -= 2

                repeat(3) { seq.removeAt(i) }

                val ligature = ligateIndicConsonants(cPrev2, c)
                ligature.forEachIndexed { index, char ->
                    seq.add(i + index, char)
                }

                i += ligature.size
            }
            // END of Devanagari String Replacer 2


            i++
        }

        // unpack replacewith
        seq.forEach {
            if (glyphProps[it]?.isPragma("replacewith") == true) {
//                dbgprn("Replacing ${it.charInfo()} into: ${glyphProps[it]!!.extInfo.map { it.toString(16) }.joinToString()}")
                glyphProps[it]!!.forEachExtInfo {
                    seq2.add(it)
                }
            }
            else {
                seq2.add(it)
            }
        }

        return seq2
    }

    /** Takes input string, do normalisation, and returns sequence of codepoints (Int)
     *
     * UTF-16 to ArrayList of Int. UTF-16 is because of Java
     * Note: CharSequence IS a String. java.lang.String implements CharSequence.
     *
     * Note to Programmer: DO NOT USE CHAR LITERALS, CODE EDITORS WILL CHANGE IT TO SOMETHING ELSE !!
     */
    private fun CharSequence.toCodePoints(): CodepointSequence {
        val seq = CodepointSequence()
        this.forEach { seq.add(it.toInt()) }
        return seq.normalise()
    }

    private fun surrogatesToCodepoint(var0: Int, var1: Int): Int {
        return (var0.toInt() shl 10) + var1.toInt() + -56613888
    }


    /**
     * Edits the given pixmap so that it would have a shadow on it.
     *
     * This function must be called `AFTER buildWidthTable()`
     *
     * The pixmap must be mutable (beware of concurrentmodificationexception).
     */
    private fun makeShadowForSheet(pixmap: Pixmap) {
        for (y in 0..pixmap.height - 2) {
            for (x in 0..pixmap.width - 2) {
                val pxNow = pixmap.getPixel(x, y) // RGBA8888

                // if you have read CONTRIBUTING.md, it says the actual glyph part MUST HAVE alpha of 255.
                // but some of the older spritesheets still have width tag drawn as alpha of 255.
                // therefore we still skip every x+15th pixels

                if (x % 16 != 15) {
                    if (pxNow and 0xFF == 255) {
                        val pxRight = (x + 1) to y
                        val pxBottom = x to (y + 1)
                        val pxBottomRight = (x + 1) to (y + 1)
                        val opCue = listOf(pxRight, pxBottom, pxBottomRight)

                        opCue.forEach {
                            if (pixmap.getPixel(it.first, it.second) and 0xFF == 0) {
                                pixmap.drawPixel(it.first, it.second,
                                    // the shadow has the same colour, but alpha halved
                                    pxNow.and(0xFFFFFF00.toInt()).or(0x7F)
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    /**
     * Edits the given pixmap so that it would have a shadow on it.
     *
     * Meant to be used to give shadow to a linotype (typeset-finished line of pixmap)
     *
     * The pixmap must be mutable (beware of concurrentmodificationexception).
     */
    private fun makeShadow(pixmap: Pixmap?) {
        if (pixmap == null) return

        pixmap.blending = Pixmap.Blending.None

        // TODO when semitransparency is needed (e.g. anti-aliased)
        // make three more pixmap (pixmap 2 3 4)
        // translate source image over new pixmap, shift the texture to be a shadow
        // draw (3, 4) -> 2, px by px s.t. only overwrites RGBA==0 pixels in 2
        // for all pxs in 2, do:
        //      halve the alpha in 2
        //      draw 1 -> 2 s.t. only RGBA!=0-px-in-1 is drawn on 2; draw by overwrite
        // copy 2 -> 1 px by px
        // ---------------------------------------------------------------------------
        // this is under assumption that new pixmap is always all zero
        // it is possible the blending in the pixmap is bugged
        //
        // for now, no semitransparency (in colourcode && spritesheet)

        val jobQueue = if (!invertShadow) arrayOf(
            1 to 0,
            0 to 1,
            1 to 1
        ) else arrayOf(
            -1 to  0,
            0 to -1,
            -1 to -1
        )

        jobQueue.forEach {
            for (y in (if (invertShadow) 1 else 0) until (if (invertShadow) pixmap.height else pixmap.height - 1)) {
                for (x in (if (invertShadow) 1 else 0) until (if (invertShadow) pixmap.width else pixmap.width - 1)) {
                    val pixel = pixmap.getPixel(x, y) // RGBA8888
                    val newPixel = pixmap.getPixel(x + it.first, y + it.second)

                    val newColor = Color(pixel)
                    newColor.a *= shadowAlpha
                    if (shadowAlphaPremultiply) {
                        newColor.r *= shadowAlpha
                        newColor.g *= shadowAlpha
                        newColor.b *= shadowAlpha
                    }

                    // in the current version, all colour-coded glyphs are guaranteed
                    // to be opaque
                    if (pixel and 0xFF == 0xFF && newPixel and 0xFF == 0) {
                        pixmap.drawPixel(x + it.first, y + it.second, newColor.toRGBA8888())
                    }
                }
            }
        }

    }


    /***
     * @param col RGBA8888 representation
     */
    private fun Pixmap.drawPixmap(pixmap: Pixmap, xPos: Int, yPos: Int, col: Int) {
        for (y in 0 until pixmap.height) {
            for (x in 0 until pixmap.width) {
                val pixel = pixmap.getPixel(x, y) // Pixmap uses RGBA8888, while Color uses ARGB. What the fuck?

                val newPixel = pixel colorTimes col

                this.drawPixel(xPos + x, yPos + y, newPixel)
            }
        }
    }

    private fun Color.toRGBA8888() =
        (this.r * 255f).toInt().shl(24) or
                (this.g * 255f).toInt().shl(16) or
                (this.b * 255f).toInt().shl(8) or
                (this.a * 255f).toInt()

    /**
     * RGBA8888 representation
     */
    private fun Int.forceOpaque() = this.and(0xFFFFFF00.toInt()) or 0xFF

    private infix fun Int.colorTimes(other: Int): Int {
        val thisBytes = IntArray(4) { this.ushr(it * 8).and(255) }
        val otherBytes = IntArray(4) { other.ushr(it * 8).and(255) }

        return (thisBytes[0] times256 otherBytes[0]) or
                (thisBytes[1] times256 otherBytes[1]).shl(8) or
                (thisBytes[2] times256 otherBytes[2]).shl(16) or
                (thisBytes[3] times256 otherBytes[3]).shl(24)
    }

    private infix fun Int.times256(other: Int) = multTable255[this][other]

    private val multTable255 = Array(256) { left ->
        IntArray(256) { right ->
            (255f * (left / 255f).times(right / 255f)).roundToInt()
        }
    }


    /** High surrogate comes before the low. */
    private fun Char.isHighSurrogate() = (this.toInt() in 0xD800..0xDBFF)
    private fun Int.isHighSurrogate() = (this.toInt() in 0xD800..0xDBFF)
    /** CodePoint = 0x10000 + (H - 0xD800) * 0x400 + (L - 0xDC00) */
    private fun Char.isLowSurrogate() = (this.toInt() in 0xDC00..0xDFFF)
    private fun Int.isLowSurrogate() = (this.toInt() in 0xDC00..0xDFFF)


    var interchar = 0
    var scale = 1
        set(value) {
            if (value > 0) field = value
            else throw IllegalArgumentException("Font scale cannot be zero or negative (input: $value)")
        }

    fun toColorCode(argb4444: Int): String = TerrarumSansBitmap.toColorCode(argb4444)
    fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = TerrarumSansBitmap.toColorCode(r, g, b, a)
    val noColorCode = toColorCode(0x0000)

    val charsetOverrideDefault = Character.toChars(CHARSET_OVERRIDE_DEFAULT).toSurrogatedString()
    val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG).toSurrogatedString()
    val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR).toSurrogatedString()

    // randomiser effect hash ONLY
    private val hashBasis = -3750763034362895579L
    private val hashPrime = 1099511628211L
    private var hashAccumulator = hashBasis
    fun getHash(char: Int): Long {
        hashAccumulator = hashAccumulator xor char.toLong()
        hashAccumulator *= hashPrime
        return hashAccumulator
    }
    fun resetHash(charSeq: CharSequence, x: Float, y: Float) {
        hashAccumulator = hashBasis

        getHash(charSeq.crc32())
        getHash(x.toRawBits())
        getHash(y.toRawBits())
    }


    fun CodepointSequence.getHash(): Long {
        val hashBasis = -3750763034362895579L
        val hashPrime = 1099511628211L
        var hashAccumulator = hashBasis

        try {
            this.forEach {
                hashAccumulator = hashAccumulator xor it.toLong()
                hashAccumulator *= hashPrime
            }
        }
        catch (e: NullPointerException) {
            System.err.println("CodepointSequence is null?!")
            e.printStackTrace()
        }

        return hashAccumulator
    }



    private fun CharSequence.crc32(): Int {
        val crc = CRC32()
        this.forEach {
            val it = it.toInt()
            val b1 = it.shl(8).and(255)
            val b2 = it.and(255)
            crc.update(b1)
            crc.update(b2)
        }

        return crc.value.toInt()
    }

    fun CodePoint.isLowHeight() = glyphProps[this]?.isLowheight == true

    private fun getKerning(prevChar: CodePoint, thisChar: CodePoint): Int {
        val maskL = glyphProps[prevChar]?.kerningMask
        val maskR = glyphProps[thisChar]?.kerningMask
        return if (glyphProps[prevChar]?.hasKernData == true && glyphProps[thisChar]?.hasKernData == true) {
            kerningRules.forEachIndexed { index, it ->
                if (it.first.matches(maskL!!) && it.second.matches(maskR!!)) {
                    val contraction = if (glyphProps[prevChar]?.isKernYtype == true || glyphProps[thisChar]?.isKernYtype == true) it.yy else it.bb

//                    dbgprn("Kerning rule match #${index+1}: ${prevChar.toChar()}${thisChar.toChar()}, Rule:${it.first.s} ${it.second.s}; Contraction: $contraction")

                    return -contraction
                }
            }
            return 0
        }
        else 0
    }

    companion object {

        private fun Boolean.toSign() = if (this) 1 else -1

        /**
         * lowercase AND the height is equal to x-height (e.g. lowercase B, D, F, H, K, L, ... does not count
         */

        data class ShittyGlyphLayout(val textBuffer: CodepointSequence, val linotype: Texture, val width: Int)
        data class TextCacheObj(val hash: Long, val glyphLayout: ShittyGlyphLayout?): Comparable<TextCacheObj> {
            fun dispose() {
                glyphLayout?.linotype?.dispose()
            }

            override fun compareTo(other: TextCacheObj): Int {
                return (this.hash - other.hash).sign
            }
        }


        private val HCF = 0x115F
        private val HJF = 0x1160

        internal val JUNG_COUNT = 21
        internal val JONG_COUNT = 28

        internal val W_ASIAN_PUNCT = 10
        internal val W_HANGUL_BASE = 13
        internal val W_KANA = 12
        internal val W_UNIHAN = 16
        internal val W_LATIN_WIDE = 9 // width of regular letters
        internal val W_VAR_INIT = 15 // it assumes width of 15 regardless of the tagged width

        internal val HGAP_VAR = 1

        internal val H = 20
        internal val H_UNIHAN = 16

        internal val H_DIACRITICS = 3

        internal val H_STACKUP_LOWERCASE_SHIFTDOWN = 4
        internal val H_OVERLAY_LOWERCASE_SHIFTDOWN = 2

        internal val SIZE_CUSTOM_SYM = 18

        internal val SHEET_ASCII_VARW =        0
        internal val SHEET_HANGUL =            1
        internal val SHEET_EXTA_VARW =         2
        internal val SHEET_EXTB_VARW =         3
        internal val SHEET_KANA =              4
        internal val SHEET_CJK_PUNCT =         5
        internal val SHEET_UNIHAN =            6
        internal val SHEET_CYRILIC_VARW =      7
        internal val SHEET_HALFWIDTH_FULLWIDTH_VARW = 8
        internal val SHEET_UNI_PUNCT_VARW =    9
        internal val SHEET_GREEK_VARW =        10
        internal val SHEET_THAI_VARW =         11
        internal val SHEET_HAYEREN_VARW =      12
        internal val SHEET_KARTULI_VARW =      13
        internal val SHEET_IPA_VARW =          14
        internal val SHEET_RUNIC =             15
        internal val SHEET_LATIN_EXT_ADD_VARW= 16
        internal val SHEET_CUSTOM_SYM =        17
        internal val SHEET_BULGARIAN_VARW =    18
        internal val SHEET_SERBIAN_VARW =      19
        internal val SHEET_TSALAGI_VARW =      20
        internal val SHEET_PHONETIC_EXT_VARW = 21
        internal val SHEET_DEVANAGARI_VARW=22
        internal val SHEET_KARTULI_CAPS_VARW = 23
        internal val SHEET_DIACRITICAL_MARKS_VARW = 24
        internal val SHEET_GREEK_POLY_VARW   = 25
        internal val SHEET_EXTC_VARW =         26
        internal val SHEET_EXTD_VARW =         27
        internal val SHEET_CURRENCIES_VARW =   28
        internal val SHEET_INTERNAL_VARW = 29
        internal val SHEET_LETTERLIKE_MATHS_VARW = 30
        internal val SHEET_ENCLOSED_ALPHNUM_SUPL_VARW = 31
        internal val SHEET_TAMIL_VARW = 32
        internal val SHEET_BENGALI_VARW = 33

        internal val SHEET_UNKNOWN = 254

        // custom codepoints

        internal val RICH_TEXT_MODIFIER_RUBY_MASTER = 0xFFFA0
        internal val RICH_TEXT_MODIFIER_RUBY_SLAVE = 0xFFFA1
        internal val RICH_TEXT_MODIFIER_SUPERSCRIPT = 0xFFFA2
        internal val RICH_TEXT_MODIFIER_SUBSCRIPT = 0xFFFA3
        internal val RICH_TEXT_MODIFIER_TAG_END = 0xFFFBF

        internal val CHARSET_OVERRIDE_DEFAULT = 0xFFFC0
        internal val CHARSET_OVERRIDE_BG_BG = 0xFFFC1
        internal val CHARSET_OVERRIDE_SR_SR = 0xFFFC2


        private val autoShiftDownOnLowercase = arrayOf(
            SHEET_DIACRITICAL_MARKS_VARW
        )

        private val fileList = arrayOf( // MUST BE MATCHING WITH SHEET INDICES!!
            "ascii_variable.tga",
            "hangul_johab.tga",
            "latinExtA_variable.tga",
            "latinExtB_variable.tga",
            "kana.tga",
            "cjkpunct.tga",
            "wenquanyi.tga.gz",
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
            "tamil_variable.tga",
            "bengali_variable.tga",
        )
        private val codeRange = arrayOf( // MUST BE MATCHING WITH SHEET INDICES!!
            0..0xFF, // SHEET_ASCII_VARW
            (0x1100..0x11FF) + (0xA960..0xA97F) + (0xD7B0..0xD7FF), // SHEET_HANGUL, because Hangul Syllables are disassembled prior to the render
            0x100..0x17F, // SHEET_EXTA_VARW
            0x180..0x24F, // SHEET_EXTB_VARW
            (0x3040..0x30FF) + (0x31F0..0x31FF) + (0x1B000..0x1B001), // SHEET_KANA
            0x3000..0x303F, // SHEET_CJK_PUNCT
            0x3400..0x9FFF, // SHEET_UNIHAN
            0x400..0x52F, // SHEET_CYRILIC_VARW
            0xFF00..0xFFFF, // SHEET_HALFWIDTH_FULLWIDTH_VARW
            0x2000..0x209F, // SHEET_UNI_PUNCT_VARW
            0x370..0x3CE, // SHEET_GREEK_VARW
            0xE00..0xE5F, // SHEET_THAI_VARW
            0x530..0x58F, // SHEET_HAYEREN_VARW
            0x10D0..0x10FF, // SHEET_KARTULI_VARW
            0x250..0x2FF, // SHEET_IPA_VARW
            0x16A0..0x16FF, // SHEET_RUNIC
            0x1E00..0x1EFF, // SHEET_LATIN_EXT_ADD_VARW
            0xE000..0xE0FF, // SHEET_CUSTOM_SYM
            0xF0000..0xF005F, // SHEET_BULGARIAN_VARW; assign them to PUA
            0xF0060..0xF00BF, // SHEET_SERBIAN_VARW; assign them to PUA
            0x13A0..0x13F5, // SHEET_TSALAGI_VARW
            0x1D00..0x1DBF, // SHEET_PHONETIC_EXT_VARW
            (0x900..0x97F) + (0xF0100..0xF01FF), // SHEET_DEVANAGARI_VARW
            0x1C90..0x1CBF, // SHEET_KARTULI_CAPS_VARW
            0x300..0x36F, // SHEET_DIACRITICAL_MARKS_VARW
            0x1F00..0x1FFF, // SHEET_GREEK_POLY_VARW
            0x2C60..0x2C7F, // SHEET_EXTC_VARW
            0xA720..0xA7FF, // SHEET_EXTD_VARW
            0x20A0..0x20CF, // SHEET_CURRENCIES_VARW
            0xFFE00..0xFFF9F, // SHEET_INTERNAL_VARW
            0x2100..0x214F, // SHEET_LETTERLIKE_MATHS_VARW
            0x1F100..0x1F1FF, // SHEET_ENCLOSED_ALPHNUM_SUPL_VARW
            (0x0B80..0x0BFF) + (0xF00C0..0xF00EF), // SHEET_TAMIL_VARW
            0x980..0x9FF, // SHEET_BENGALI_VARW
        )
        private val codeRangeHangulCompat = 0x3130..0x318F

        private val diacriticDotRemoval = hashMapOf(
            'i'.toInt() to 0x131,
            'j'.toInt() to 0x237
        )

        internal fun Int.charInfo() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}: ${Character.getName(this)}"

        private val ZWNJ = 0x200C
        private val ZWJ = 0x200D

        private val tamilLigatingConsonants = listOf('க','ங','ச','ஞ','ட','ண','த','ந','ன','ப','ம','ய','ர','ற','ல','ள','ழ','வ').map { it.toInt() }.toIntArray()
        private val TAMIL_KSSA = 0xF00ED
        private val TAMIL_SHRII = 0xF00EE

        private val devanagariConsonants = ((0x0915..0x0939) + (0x0958..0x095F) + (0x0978..0x097F) + (0xF0105..0xF01FF)).toIntArray()
        private val devanagariVerbs = ((0x093A..0x093C) + (0x093E..0x094C) + (0x094E..0x094F)).toIntArray()

        private val devanagariBaseConsonants = 0x0915..0x0939
        private val devanagariBaseConsonantsWithNukta = 0x0958..0x095F
        private val devanagariBaseConsonantsExtended = 0x0978..0x097F
        private val devanagariPresentationConsonants = 0xF0140..0xF01FF
        private val devanagariPresentationConsonantsWithRa = 0xF0145..0xF017F

        private val DEVANAGARI_VIRAMA = 0x94D
        private val DEVANAGARI_RA = 0x930
        private val DEVANAGARI_YA = 0x92F
        private val DEVANAGARI_RRA = 0x931
        private val DEVANAGARI_VA = 0x0935
        private val DEVANAGARI_HA = 0x939
        private val DEVANAGARI_U = 0x941
        private val DEVANAGARI_UU = 0x942

        private val DEVANAGARI_SYLL_RU = 0xF0100
        private val DEVANAGARI_SYLL_RUU = 0xF0101
        private val DEVANAGARI_SYLL_RRU = 0xF0102
        private val DEVANAGARI_SYLL_RRUU = 0xF0103
        private val DEVANAGARI_SYLL_HU = 0xF0130
        private val DEVANAGARI_SYLL_HUU = 0xF0131

        private val DEVANAGARI_OPEN_YA = 0xF0136
        private val DEVANAGARI_OPEN_HALF_YA = 0xF0137
        private val DEVANAGARI_RA_SUPER = 0xF0104
        private val DEVANAGARI_EYELASH_RA = 0xF012A

        private val DEVANAGARI_LIG_K_SS = 0xF0181
        private val DEVANAGARI_LIG_J_NY = 0xF0184
        private val DEVANAGARI_LIG_T_T = 0xF018B

        private val DEVANAGARI_LIG_T_R = 0xF0154
        private val DEVANAGARI_LIG_SH_R = 0xF0166

        private val DEVANAGARI_LIG_K_SS_R = 0xF016B
        private val DEVANAGARI_LIG_J_NY_R = 0xF016C
        private val DEVANAGARI_LIG_T_T_R = 0xF016D

        private val DEVANAGARI_HALFLIG_K_SS = 0xF012B
        private val DEVANAGARI_HALFLIG_J_NY = 0xF012C
        private val DEVANAGARI_HALFLIG_T_T = 0xF012D

//        private val DEVANAGARI_HALFLIG_T_R = 0xF012E
//        private val DEVANAGARI_HALFLIG_SH_R = 0xF012F

        private val DEVANAGARI_HALF_FORMS = 0xF0100 // starting point for Devanagari half forms
        private val DEVANAGARI_LIG_X_R = 0xF0140 // starting point for Devanagari ligature CONSONANT+RA

        private fun CodePoint.toHalfFormOrNull(): CodePoint? {
            if (this == DEVANAGARI_LIG_K_SS) return DEVANAGARI_HALFLIG_K_SS
            if (this == DEVANAGARI_LIG_J_NY) return DEVANAGARI_HALFLIG_J_NY
            if (this == DEVANAGARI_LIG_T_T) return  DEVANAGARI_HALFLIG_T_T
            if (this == DEVANAGARI_OPEN_YA) return DEVANAGARI_OPEN_HALF_YA
            if (this in devanagariBaseConsonants) return (this - 0x0910 + DEVANAGARI_HALF_FORMS)
            if (this in devanagariBaseConsonantsWithNukta) return (this - 0x0920 + DEVANAGARI_HALF_FORMS)
            if (this in devanagariPresentationConsonantsWithRa) return this + 0x80
            return null
        }

        // TODO use proper version of Virama for respective scripts
        private fun CodePoint.toHalfFormOrVirama(): List<CodePoint> = this.toHalfFormOrNull().let {
            println("[TerrarumSansBitmap] toHalfForm ${this.charInfo()} = ${it?.charInfo()}")
            if (it == null) listOf(this, DEVANAGARI_VIRAMA) else listOf(it)
        }

        // TODO use proper version of Virama for respective scripts
        private fun toRaAppended(c: CodePoint): List<CodePoint> {
            if (c in devanagariBaseConsonants) return listOf(c - 0x0910 + DEVANAGARI_LIG_X_R)
            else if (c == DEVANAGARI_LIG_K_SS) return listOf(DEVANAGARI_LIG_K_SS_R)
            else if (c == DEVANAGARI_LIG_J_NY) return listOf(DEVANAGARI_LIG_J_NY_R)
            else if (c == DEVANAGARI_LIG_T_T) return listOf(DEVANAGARI_LIG_T_T_R)
            else return listOf(c, DEVANAGARI_VIRAMA, DEVANAGARI_RA)
        }

        private fun ligateIndicConsonants(c1: CodePoint, c2: CodePoint): List<CodePoint> {
            println("[TerrarumSansBitmap] Indic ligation ${c1.charInfo()} - ${c2.charInfo()}")
            if (c2 == DEVANAGARI_RA) return toRaAppended(c1) // Devanagari @.RA
            when (c1) {
                0x0915 -> /* Devanagari KA */ when (c2) {
                    0x0924 -> return listOf(0xF0180) // K.T
                    0x0937 -> return listOf(DEVANAGARI_LIG_K_SS) // K.SS
                    0xF0167 -> return listOf(DEVANAGARI_LIG_K_SS_R) // K.SS.R
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // K.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0918 -> /* Devanagari GHA */ when (c2) {
                    0x091F -> return listOf(0xF01A2) // GH.TT
                    0x0920 -> return listOf(0xF01A3) // GH.TTH
                    0x0922 -> return listOf(0xF01A4) // GH.DDH
                    0xF014F -> return listOf(0xF0172) // GH.TTR
                    0xF0150 -> return listOf(0xF0173) // GH.TTHR
                    0xF0152 -> return listOf(0xF0174) // GH.DDHR
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0919 -> /* Devanagari NGA */ when (c2) {
                    0x0917 -> return listOf(0xF0182) // NG.G
                    0x092E -> return listOf(0xF0183) // NG.M
                    DEVANAGARI_VA -> return listOf(0xF019C) // NG.V
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // NG.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x091B -> /* Devanagari CHA */ when (c2) {
                    DEVANAGARI_VA -> return listOf(0xF019D) // CH.V
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // CH.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x091C -> /* Devanagari JA */ when (c2) {
                    0x091E -> return listOf(DEVANAGARI_LIG_J_NY) // J.NY
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // J.Y
                    0xF014E -> return listOf(DEVANAGARI_LIG_J_NY_R) // J.NY.R
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x091F -> /* Devanagari TTA */ when (c2) {
                    0x091F -> return listOf(0xF0185) // TT.TT
                    0x0920 -> return listOf(0xF0186) // TT.TTH
                    DEVANAGARI_VA -> return listOf(0xF019E) // TT.V
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // TT.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0920 -> /* Devanagari TTHA */ when (c2) {
                    0x0920 -> return listOf(0xF0187) // TTH.TTH
                    DEVANAGARI_VA -> return listOf(0xF019F) // TTH.V
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // TTH.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0921 -> /* Devanagari DDA */ when (c2) {
                    0x0921 -> return listOf(0xF0188) // DD.DD
                    0x0922 -> return listOf(0xF0189) // DD.DDH
                    DEVANAGARI_VA -> return listOf(0xF01A0) // DD.V
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // DD.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0922 -> /* Devanagari DDHA */ when (c2) {
                    0x0922 -> return listOf(0xF018A) // DDH.DDH
                    DEVANAGARI_VA -> return listOf(0xF01A1) // DDH.V
                    DEVANAGARI_YA -> return c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // DDH.Y
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0924 -> /* Devanagari TA */ when (c2) {
                    0x0924 -> return listOf(DEVANAGARI_LIG_T_T) // T.T
                    DEVANAGARI_LIG_T_R -> return listOf(DEVANAGARI_LIG_T_T_R) // T.T.R
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0926 -> /* Devanagari DA */ when (c2) {
                    0x0917 -> return listOf(0xF019A) // D.G
                    0x0918 -> return listOf(0xF019B) // D.GH
                    0x0926 -> return listOf(0xF018C) // D.D
                    0x0927 -> return listOf(0xF018D) // D.DH
                    0x092C -> return listOf(0xF018E) // D.B
                    0x092D -> return listOf(0xF018F) // D.BH
                    0x092E -> return listOf(0xF0190) // D.M
                    0x092F -> return listOf(0xF0191) // D.Y
                    0x0935 -> return listOf(0xF0192) // D.V
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0928 -> /* Devanagari NA */ when (c2) {
                    0x0928 -> return listOf(0xF0193) // N.N
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x092A -> /* Devanagari PA */ when (c2) {
                    0x091F -> return listOf(0xF01A5) // P.TT
                    0x0920 -> return listOf(0xF01A6) // P.TTH
                    0x0922 -> return listOf(0xF01A7) // P.DDH
                    0xF014F -> return listOf(0xF0175) // P.TTR
                    0xF0150 -> return listOf(0xF0176) // P.TTHR
                    0xF0152 -> return listOf(0xF0177) // P.DDHR
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0937 -> /* Devanagari SSA */ when (c2) {
                    0x091F -> return listOf(0xF01A8) // SS.TT
                    0x0920 -> return listOf(0xF01A9) // SS.TTH
                    0x0922 -> return listOf(0xF01AA) // SS.DDH
                    0xF014F -> return listOf(0xF0178) // SS.TTR
                    0xF0150 -> return listOf(0xF0179) // SS.TTHR
                    0xF0152 -> return listOf(0xF017A) // SS.DDHR
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                0x0939 -> /* Devanagari HA */ when (c2) {
                    0x0923 -> return listOf(0xF0194) // H.NN
                    0x0928 -> return listOf(0xF0195) // H.N
                    0x092E -> return listOf(0xF0196) // H.M
                    0x092F -> return listOf(0xF0197) // H.Y
                    0x0932 -> return listOf(0xF0198) // H.L
                    0x0935 -> return listOf(0xF0199) // H.V
                    else -> return c1.toHalfFormOrVirama() + c2
                }
                else -> return c1.toHalfFormOrVirama() + c2 // TODO use proper version of Virama for respective scripts
            }
        }


        private fun Int.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

        // Hangul Implementation Specific //

        private fun getWanseongHanChoseong(hanIndex: Int) = hanIndex / (JUNG_COUNT * JONG_COUNT)
        private fun getWanseongHanJungseong(hanIndex: Int) = hanIndex / JONG_COUNT % JUNG_COUNT
        private fun getWanseongHanJongseong(hanIndex: Int) = hanIndex % JONG_COUNT

        // THESE ARRAYS MUST BE SORTED
        // ㅣ
        private val jungseongI = arrayOf(21,61).toSortedSet()
        // ㅗ ㅛ ㅜ ㅠ
        private val jungseongOU = arrayOf(9,13,14,18,34,35,39,45,51,53,54,64,80,83).toSortedSet()
        // ㅘ ㅙ ㅞ
        private val jungseongOUComplex = (arrayOf(10,11,16) + (22..33).toList() + arrayOf(36,37,38) + (41..44).toList() + arrayOf(46,47,48,49,50) + (56..59).toList() + arrayOf(63) + (67..79).toList() + arrayOf(81,82) + (84..93).toList()).toSortedSet()
        // ㅐ ㅒ ㅔ ㅖ etc
        private val jungseongRightie = arrayOf(2,4,6,8,11,16,32,33,37,42,44,48,50,71,72,75,78,79,83,86,87,88,94).toSortedSet()
        // ㅚ *ㅝ* ㅟ
        private val jungseongOEWI = arrayOf(12,15,17,40,52,55,89,90,91).toSortedSet()
        // ㅡ
        private val jungseongEU = arrayOf(19,62,66).toSortedSet()
        // ㅢ
        private val jungseongYI = arrayOf(20,60,65).toSortedSet()
        // ㅜ ㅝ ㅞ ㅟ ㅠ
        private val jungseongUU = arrayOf(14,15,16,17,18,27,30,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,59,67,68,77,78,79,80,81,82,83,84,91).toSortedSet()

        private val jungseongWide = (jungseongOU.toList() + jungseongEU.toList()).toSortedSet()

        private val choseongGiyeoks = arrayOf(0,1,15,23,30,34,45,51,56,65,82,90,100,101,110,111,115).toSortedSet()

        // index of the peak, 0 being blank, 1 being ㅏ
        // indices of peaks that number of lit pixels (vertically counted) on x=11 is greater than 7
        private val hangulPeaksWithExtraWidth = arrayOf(2,4,6,8,11,16,32,33,37,42,44,48,50,71,75,78,79,83,86,87,88,94).toSortedSet()

        private val giyeokRemapping = hashMapOf(
            5 to 19,
            6 to 20,
            7 to 21,
            8 to 22,
            11 to 23,
            12 to 24,
        )

        /**
         * @param i Initial (Choseong)
         * @param p Peak (Jungseong)
         * @param f Final (Jongseong)
         */
        private fun getHanInitialRow(i: Int, p: Int, f: Int): Int {
            var ret =
                if (p in jungseongI) 3
                else if (p in jungseongOUComplex) 7
                else if (p in jungseongOEWI) 11
                else if (p in jungseongOU) 5
                else if (p in jungseongEU) 9
                else if (p in jungseongYI) 13
                else 1

            if (f != 0) ret += 1

            return if (p in jungseongUU && i in choseongGiyeoks) giyeokRemapping[ret] ?: throw NullPointerException("i=$i p=$p f=$f ret=$ret") else ret
        }

        private fun getHanMedialRow(i: Int, p: Int, f: Int) = if (f == 0) 15 else 16

        private fun getHanFinalRow(i: Int, p: Int, f: Int): Int {

            return if (p !in jungseongRightie)
                17
            else
                18
        }

        private fun isHangulChoseong(c: CodePoint) = c in (0x1100..0x115F) || c in (0xA960..0xA97F)
        private fun isHangulJungseong(c: CodePoint) = c in (0x1160..0x11A7) || c in (0xD7B0..0xD7C6)
        private fun isHangulJongseong(c: CodePoint) = c in (0x11A8..0x11FF) || c in (0xD7CB..0xD7FB)

        private fun toHangulChoseongIndex(c: CodePoint) =
            if (!isHangulChoseong(c)) throw IllegalArgumentException("This Hangul sequence does not begin with Choseong (${c.toHex()})")
            else if (c in 0x1100..0x115F) c - 0x1100
            else c - 0xA960 + 96
        private fun toHangulJungseongIndex(c: CodePoint) =
            if (!isHangulJungseong(c)) null
            else if (c in 0x1160..0x11A7) c - 0x1160
            else c - 0xD7B0 + 72
        private fun toHangulJongseongIndex(c: CodePoint) =
            if (!isHangulJongseong(c)) null
            else if (c in 0x11A8..0x11FF) c - 0x11A8 + 1
            else c - 0xD7CB + 88 + 1

        /**
         * X-position in the spritesheet
         *
         * @param iCP Code point for Initial (Choseong)
         * @param pCP Code point for Peak (Jungseong)
         * @param fCP Code point for Final (Jongseong)
         */
        private fun toHangulIndex(iCP: CodePoint, pCP: CodePoint, fCP: CodePoint): IntArray {
            val indexI = toHangulChoseongIndex(iCP)
            val indexP = toHangulJungseongIndex(pCP) ?: 0
            val indexF = toHangulJongseongIndex(fCP) ?: 0

            return intArrayOf(indexI, indexP, indexF)
        }

        /**
         * @param iCP 0x1100..0x115F, 0xA960..0xA97F, 0x3130..0x318F
         * @param pCP 0x00, 0x1160..0x11A7, 0xD7B0..0xD7CA
         * @param fCP 0x00, 0x11A8..0x11FF, 0xD7BB..0xD7FF
         *
         * @return IntArray pair representing Hangul indices and rows (in this order)
         */
        private fun toHangulIndexAndRow(iCP: CodePoint, pCP: CodePoint, fCP: CodePoint): Pair<IntArray, IntArray> {
            if (isHangulCompat(iCP)) {
                return intArrayOf(iCP - 0x3130, 0, 0) to intArrayOf(0, 15, 17)
            }
            else {
                val (indexI, indexP, indexF) = toHangulIndex(iCP, pCP, fCP)

                val rowI = getHanInitialRow(indexI, indexP, indexF)
                val rowP = getHanMedialRow(indexI, indexP, indexF)
                val rowF = getHanFinalRow(indexI, indexP, indexF)

                return intArrayOf(indexI, indexP, indexF) to intArrayOf(rowI, rowP, rowF)
            }
        }


        // END Hangul //

        private fun isHangul(c: CodePoint) = c in codeRange[SHEET_HANGUL] || c in codeRangeHangulCompat
        private fun isAscii(c: CodePoint) = c in codeRange[SHEET_ASCII_VARW]
        private fun isRunic(c: CodePoint) = c in codeRange[SHEET_RUNIC]
        private fun isExtA(c: CodePoint) = c in codeRange[SHEET_EXTA_VARW]
        private fun isExtB(c: CodePoint) = c in codeRange[SHEET_EXTB_VARW]
        private fun isKana(c: CodePoint) = c in codeRange[SHEET_KANA]
        private fun isCJKPunct(c: CodePoint) = c in codeRange[SHEET_CJK_PUNCT]
        private fun isUniHan(c: CodePoint) = c in codeRange[SHEET_UNIHAN]
        private fun isCyrilic(c: CodePoint) = c in codeRange[SHEET_CYRILIC_VARW]
        private fun isFullwidthUni(c: CodePoint) = c in codeRange[SHEET_HALFWIDTH_FULLWIDTH_VARW]
        private fun isUniPunct(c: CodePoint) = c in codeRange[SHEET_UNI_PUNCT_VARW]
        private fun isGreek(c: CodePoint) = c in codeRange[SHEET_GREEK_VARW]
        private fun isThai(c: CodePoint) = c in codeRange[SHEET_THAI_VARW]
        /*private fun isDiacritics(c: CodePoint) = c in 0xE34..0xE3A
                || c in 0xE47..0xE4E
                || c == 0xE31*/
        private fun isCustomSym(c: CodePoint) = c in codeRange[SHEET_CUSTOM_SYM]
        private fun isArmenian(c: CodePoint) = c in codeRange[SHEET_HAYEREN_VARW]
        private fun isKartvelian(c: CodePoint) = c in codeRange[SHEET_KARTULI_VARW]
        private fun isIPA(c: CodePoint) = c in codeRange[SHEET_IPA_VARW]
        private fun isLatinExtAdd(c: CodePoint) = c in 0x1E00..0x1EFF
        private fun isBulgarian(c: CodePoint) = c in 0x400..0x45F
        fun isColourCode(c: CodePoint) = c == 0x100000 || c in 0x10F000..0x10FFFF
        private fun isCharsetOverride(c: CodePoint) = c in 0xFFFC0..0xFFFFF
        private fun isCherokee(c: CodePoint) = c in codeRange[SHEET_TSALAGI_VARW]
        private fun isPhoneticExt(c: CodePoint) = c in codeRange[SHEET_PHONETIC_EXT_VARW]
        private fun isDevanagari(c: CodePoint) = c in codeRange[SHEET_DEVANAGARI_VARW]
        private fun isKartvelianCaps(c: CodePoint) = c in codeRange[SHEET_KARTULI_CAPS_VARW]
        private fun isDiacriticalMarks(c: CodePoint) = c in codeRange[SHEET_DIACRITICAL_MARKS_VARW]
        private fun isPolytonicGreek(c: CodePoint) = c in codeRange[SHEET_GREEK_POLY_VARW]
        private fun isExtC(c: CodePoint) = c in codeRange[SHEET_EXTC_VARW]
        private fun isExtD(c: CodePoint) = c in codeRange[SHEET_EXTD_VARW]
        private fun isHangulCompat(c: CodePoint) = c in codeRangeHangulCompat
        private fun isCurrencies(c: CodePoint) = c in codeRange[SHEET_CURRENCIES_VARW]
        private fun isInternalSymbols(c: CodePoint) = c in codeRange[SHEET_INTERNAL_VARW]
        private fun isLetterlike(c: CodePoint) = c in codeRange[SHEET_LETTERLIKE_MATHS_VARW]
        private fun isEnclosedAlphnumSupl(c: CodePoint) = c in codeRange[SHEET_ENCLOSED_ALPHNUM_SUPL_VARW]
        private fun isTamil(c: CodePoint) = c in codeRange[SHEET_TAMIL_VARW]
        private fun isBengali(c: CodePoint) = c in codeRange[SHEET_BENGALI_VARW]


        private fun extAindexX(c: CodePoint) = c % 16
        private fun extAindexY(c: CodePoint) = (c - 0x100) / 16

        private fun extBindexX(c: CodePoint) = c % 16
        private fun extBindexY(c: CodePoint) = (c - 0x180) / 16

        private fun runicIndexX(c: CodePoint) = c % 16
        private fun runicIndexY(c: CodePoint) = (c - 0x16A0) / 16

        private fun kanaIndexX(c: CodePoint) = c % 16
        private fun kanaIndexY(c: CodePoint) =
            if (c in 0x31F0..0x31FF) 12
            else if (c in 0x1B000..0x1B00F) 13
            else (c - 0x3040) / 16

        private fun cjkPunctIndexX(c: CodePoint) = c % 16
        private fun cjkPunctIndexY(c: CodePoint) = (c - 0x3000) / 16

        private fun cyrilicIndexX(c: CodePoint) = c % 16
        private fun cyrilicIndexY(c: CodePoint) = (c - 0x400) / 16

        private fun fullwidthUniIndexX(c: CodePoint) = c % 16
        private fun fullwidthUniIndexY(c: CodePoint) = (c - 0xFF00) / 16

        private fun uniPunctIndexX(c: CodePoint) = c % 16
        private fun uniPunctIndexY(c: CodePoint) = (c - 0x2000) / 16

        private fun unihanIndexX(c: CodePoint) = (c - 0x3400) % 256
        private fun unihanIndexY(c: CodePoint) = (c - 0x3400) / 256

        private fun greekIndexX(c: CodePoint) = c % 16
        private fun greekIndexY(c: CodePoint) = (c - 0x370) / 16

        private fun thaiIndexX(c: CodePoint) = c % 16
        private fun thaiIndexY(c: CodePoint) = (c - 0xE00) / 16

        private fun symbolIndexX(c: CodePoint) = c % 16
        private fun symbolIndexY(c: CodePoint) = (c - 0xE000) / 16

        private fun armenianIndexX(c: CodePoint) = c % 16
        private fun armenianIndexY(c: CodePoint) = (c - 0x530) / 16

        private fun kartvelianIndexX(c: CodePoint) = c % 16
        private fun kartvelianIndexY(c: CodePoint) = (c - 0x10D0) / 16

        private fun ipaIndexX(c: CodePoint) = c % 16
        private fun ipaIndexY(c: CodePoint) = (c - 0x250) / 16

        private fun latinExtAddX(c: CodePoint) = c % 16
        private fun latinExtAddY(c: CodePoint) = (c - 0x1E00) / 16

        private fun cherokeeIndexX(c: CodePoint) = c % 16
        private fun cherokeeIndexY(c: CodePoint) = (c - 0x13A0) / 16

        private fun phoneticExtIndexX(c: CodePoint) = c % 16
        private fun phoneticExtIndexY(c: CodePoint) = (c - 0x1D00) / 16

        private fun devanagariIndexX(c: CodePoint) = c % 16
        private fun devanagariIndexY(c: CodePoint) = (if (c < 0xF0000) (c - 0x0900) else (c - 0xF0080)) / 16

        private fun bengaliIndexX(c: CodePoint) = c % 16
        private fun bengaliIndexY(c: CodePoint) = (c - 0x980) / 16

        private fun kartvelianCapsIndexX(c: CodePoint) = c % 16
        private fun kartvelianCapsIndexY(c: CodePoint) = (c - 0x1C90) / 16

        private fun diacriticalMarksIndexX(c: CodePoint) = c % 16
        private fun diacriticalMarksIndexY(c: CodePoint) = (c - 0x300) / 16

        private fun polytonicGreekIndexX(c: CodePoint) = c % 16
        private fun polytonicGreekIndexY(c: CodePoint) = (c - 0x1F00) / 16

        private fun extCIndexX(c: CodePoint) = c % 16
        private fun extCIndexY(c: CodePoint) = (c - 0x2C60) / 16

        private fun extDIndexX(c: CodePoint) = c % 16
        private fun extDIndexY(c: CodePoint) = (c - 0xA720) / 16

        private fun currenciesIndexX(c: CodePoint) = c % 16
        private fun currenciesIndexY(c: CodePoint) = (c - 0x20A0) / 16

        private fun internalIndexX(c: CodePoint) = c % 16
        private fun internalIndexY(c: CodePoint) = (c - 0xFFE00) / 16

        private fun letterlikeIndexX(c: CodePoint) = c % 16
        private fun letterlikeIndexY(c: CodePoint) = (c - 0x2100) / 16

        private fun enclosedAlphnumSuplX(c: CodePoint) = c % 16
        private fun enclosedAlphnumSuplY(c: CodePoint) = (c - 0x1F100) / 16

        private fun tamilIndexX(c: CodePoint) = c % 16
        private fun tamilIndexY(c: CodePoint) = (if (c < 0xF0000) (c - 0x0B80) else (c - 0xF0040)) / 16

        val charsetOverrideDefault = Character.toChars(CHARSET_OVERRIDE_DEFAULT).toSurrogatedString()
        val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG).toSurrogatedString()
        val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR).toSurrogatedString()
        fun toColorCode(argb4444: Int): String = Character.toChars(0x100000 + argb4444).toSurrogatedString()
        fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = toColorCode(a.shl(12) or r.shl(8) or g.shl(4) or b)
        private fun CharArray.toSurrogatedString(): String = "${this[0]}${this[1]}"

        val noColorCode = toColorCode(0x0000)



        // The "Keming" Machine //

        private val kemingBitMask: IntArray = intArrayOf(7,6,5,4,3,2,1,0,15,14).map { 1 shl it }.toIntArray()

        private class ing(val s: String) {

            private var careBits = 0
            private var ruleBits = 0

            init {
                s.forEachIndexed { index, char ->
                    when (char) {
                        '@' -> {
                            careBits = careBits or kemingBitMask[index]
                            ruleBits = ruleBits or kemingBitMask[index]
                        }
                        '`' -> {
                            careBits = careBits or kemingBitMask[index]
                        }
                    }
                }
            }

            fun matches(shapeBits: Int) = ((shapeBits and careBits) == ruleBits)

            override fun toString() = "C:${careBits.toString(2).padStart(16,'0')}-R:${ruleBits.toString(2).padStart(16,'0')}"
        }

        private data class Kem(val first: ing, val second: ing, val bb: Int = 2, val yy: Int = 1)

        /**
         * Legend: _ dont care
         *         @ must have a bit set
         *         ` must have a bit unset
         * Order: ABCDEFGHJK, where
         *
         * A·B < unset for lowheight miniscules, as in e
         * |·| < space we don't care
         * C·D < middle hole for majuscules, as in C
         * E·F < middle hole for miniscules, as in c
         * G·H
         *――― < baseline
         * |·|
         * J·K
         */
        private val kerningRules = arrayListOf(
            Kem(ing("_`_@___`__"),ing("`_`___@___")), // ул
            Kem(ing("_@_`___`__"),ing("`_________")),
            Kem(ing("_@_@___`__"),ing("`___@_@___"),1,1),
            Kem(ing("_@_@_`_`__"),ing("`_____@___")),
            Kem(ing("___`_`____"),ing("`___@_`___")),
            Kem(ing("___`_`____"),ing("`_@___`___")),
        )

        init {
            // automatically create mirrored version of the kerningRules
            val imax = kerningRules.size // to avoid concurrentmodificationshit
            for (i in 0 until imax) {
                val left = kerningRules[i].first.s
                val right = kerningRules[i].second.s
                val bb = kerningRules[i].bb
                val yy = kerningRules[i].yy

                val newleft = StringBuilder()
                val newright = StringBuilder()

                if (left.length != right.length && left.length % 2 != 0) throw IllegalArgumentException()

                for (c in 0 until left.length step 2) {
                    newleft.append(right[c+1],right[c])
                    newright.append(left[c+1],left[c])
                }

                kerningRules.add(Kem(ing("$newleft"),ing("$newright"),bb,yy))
            }


//            kerningRules.forEach { println("Keming ${it.first.s} - ${it.second.s} ; ${it.bb}/${it.yy}") }
        }

        // End of the Keming Machine
    }

}
