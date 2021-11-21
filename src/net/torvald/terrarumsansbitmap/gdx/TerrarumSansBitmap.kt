/*
 * Terrarum Sans Bitmap
 * 
 * Copyright (c) 2017-2021 Minjae Song (Torvald)
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
import net.torvald.terrarumsansbitmap.GlyphProps
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
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
 * so that the shadow won't be upside-down. For glyph getting upside-down, set ```TextureRegionPack.globalFlipY = true```.
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
            val isVariable1 = it.endsWith("_variable.tga")
            val isVariable2 = variableWidthSheets.contains(index)
            val isVariable = isVariable1 && isVariable2
            val isXYSwapped = it.contains("xyswap", true)

            // idiocity check
            if (isVariable1 && !isVariable2)
                throw Error("font is named as variable on the name but not enlisted as")
            else if (!isVariable1 && isVariable2)
                throw Error("font is enlisted as variable on the name but not named as")


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
            val texRegPack = if (isVariable) {
                PixmapRegionPack(pixmap, W_VAR_INIT, H, HGAP_VAR, 0, xySwapped = isXYSwapped)
            }
            else if (index == SHEET_UNIHAN) {
                PixmapRegionPack(pixmap, W_UNIHAN, H_UNIHAN) // the only exception that is height is 16
            }
            // below they all have height of 20 'H'
            else if (index == SHEET_FW_UNI) {
                PixmapRegionPack(pixmap, W_UNIHAN, H)
            }
            else if (index == SHEET_CJK_PUNCT) {
                PixmapRegionPack(pixmap, W_ASIAN_PUNCT, H)
            }
            else if (index == SHEET_KANA) {
                PixmapRegionPack(pixmap, W_KANA, H)
            }
            else if (index == SHEET_HANGUL) {
                PixmapRegionPack(pixmap, W_HANGUL_BASE, H)
            }
            else if (index == SHEET_CUSTOM_SYM) {
                PixmapRegionPack(pixmap, SIZE_CUSTOM_SYM, SIZE_CUSTOM_SYM) // TODO variable
            }
            else if (index == SHEET_RUNIC) {
                PixmapRegionPack(pixmap, W_LATIN_WIDE, H)
            }
            else throw IllegalArgumentException("Unknown sheet index: $index")

            //texRegPack.texture.setFilter(minFilter, magFilter)

            sheetsPack.add(texRegPack)

            pixmap.dispose() // you are terminated
        }

        sheets = sheetsPack.toTypedArray()

        // make sure null char is actually null (draws nothing and has zero width)
        sheets[SHEET_ASCII_VARW].regions[0].setColor(0)
        sheets[SHEET_ASCII_VARW].regions[0].fill()
        glyphProps[0] = GlyphProps(0, 0)
    }

    override fun getLineHeight(): Float = H.toFloat()

    override fun getXHeight() = 8f
    override fun getCapHeight() = 12f
    override fun getAscent() = 3f
    override fun getDescent() = 3f
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

    private var nullProp = GlyphProps(15, 0)

    private val pixmapOffsetY = 10

    fun draw(batch: Batch, charSeq: CharSequence, x: Int, y: Int) = draw(batch, charSeq, x.toFloat(), y.toFloat())
    override fun draw(batch: Batch, charSeq: CharSequence, x: Float, y: Float) = drawNormalised(batch, charSeq.toCodePoints(), x, y)
    fun draw(batch: Batch, codepoints: CodepointSequence, x: Int, y: Int) = drawNormalised(batch, codepoints.normalise(), x.toFloat(), y.toFloat())
    fun draw(batch: Batch, codepoints: CodepointSequence, x: Float, y: Float) = drawNormalised(batch, codepoints.normalise(), x, y)

    fun drawNormalised(batch: Batch, codepoints: CodepointSequence, x: Float, y: Float): GlyphLayout? {

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


            if (!flipY) {
                batch.draw(tempLinotype, x.toFloat(), (y - pixmapOffsetY).toFloat())
            }
            else {
                batch.draw(tempLinotype,
                        x.toFloat(),
                        (y - pixmapOffsetY + (tempLinotype.height)).toFloat(),
                        (tempLinotype.width.toFloat()),
                        -(tempLinotype.height.toFloat())
                )
            }

        }

        return null
    }

    private fun Int.charInfo() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}: ${Character.getName(this)}"


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
        else if (isKana(c))
            return SHEET_KANA
        else if (isUniHan(c))
            return SHEET_UNIHAN
        else if (isAscii(c))
            return SHEET_ASCII_VARW
        else if (isExtA(c))
            return SHEET_EXTA_VARW
        else if (isExtB(c))
            return SHEET_EXTB_VARW
        else if (isCyrilic(c))
            return SHEET_CYRILIC_VARW
        else if (isUniPunct(c))
            return SHEET_UNI_PUNCT_VARW
        else if (isCJKPunct(c))
            return SHEET_CJK_PUNCT
        else if (isFullwidthUni(c))
            return SHEET_FW_UNI
        else if (isGreek(c))
            return SHEET_GREEK_VARW
        else if (isThai(c))
            return SHEET_THAI_VARW
        else if (isCustomSym(c))
            return SHEET_CUSTOM_SYM
        else if (isArmenian(c))
            return SHEET_HAYEREN_VARW
        else if (isKartvelian(c))
            return SHEET_KARTULI_VARW
        else if (isIPA(c))
            return SHEET_IPA_VARW
        else if (isRunic(c))
            return SHEET_RUNIC
        else if (isLatinExtAdd(c))
            return SHEET_LATIN_EXT_ADD_VARW
        else if (isCherokee(c))
            return SHEET_TSALAGI_VARW
        else if (isInsular(c))
            return SHEET_INSUAR_VARW
        else if (isNagariBengali(c))
            return SHEET_NAGARI_BENGALI_VARW
        else if (isKartvelianCaps(c))
            return SHEET_KARTULI_CAPS_VARW
        else if (isDiacriticalMarks(c))
            return SHEET_DIACRITICAL_MARKS_VARW
        else if (isPolytonicGreek(c))
            return SHEET_GREEK_POLY_VARW
        else if (isExtC(c))
            return SHEET_EXTC_VARW
        else if (isExtD(c))
            return SHEET_EXTD_VARW
        else if (isCurrencies(c))
            return SHEET_CURRENCIES_VARW
        else if (isInternalSymbols(c))
            return SHEET_INTERNAL_VARW
        else
            return SHEET_UNKNOWN
        // fixed width
        // fallback
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
            SHEET_FW_UNI -> {
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
            SHEET_INSUAR_VARW -> {
                sheetX = insularIndexX(ch)
                sheetY = insularIndexY(ch)
            }
            SHEET_NAGARI_BENGALI_VARW -> {
                sheetX = nagariIndexX(ch)
                sheetY = nagariIndexY(ch)
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
            else -> {
                sheetX = ch % 16
                sheetY = ch / 16
            }
        }

        return intArrayOf(sheetX, sheetY)
    }

    private fun buildWidthTable(pixmap: Pixmap, codeRange: Iterable<Int>, cols: Int = 16) {
        val binaryCodeOffset = W_VAR_INIT

        val cellW = W_VAR_INIT + 1
        val cellH = H

        for (code in codeRange) {

            val cellX = ((code - codeRange.first()) % cols) * cellW
            val cellY = ((code - codeRange.first()) / cols) * cellH

            val codeStartX = cellX + binaryCodeOffset
            val codeStartY = cellY
            val tagStartY = codeStartY + 10

            var width = 0
            var tags = 0

            for (y in 0..4) {
                // if ALPHA is not zero, assume it's 1
                if (pixmap.getPixel(codeStartX, codeStartY + y).and(0xFF) != 0) {
                    width = width or (1 shl y)
                }
            }

            for (y in 0..9) {
                // if ALPHA is not zero, assume it's 1
                if (pixmap.getPixel(codeStartX, tagStartY + y).and(0xFF) != 0) {
                    tags = tags or (1 shl y)
                }
            }


            // lowheight bit
            val isLowHeight = (pixmap.getPixel(codeStartX, codeStartY + 5).and(0xFF) != 0)

            // Keming machine parameters
            val kerningBit1 = pixmap.getPixel(codeStartX, codeStartY + 6)
            val kerningBit2 = pixmap.getPixel(codeStartX, codeStartY + 7)
            val kerningBit3 = pixmap.getPixel(codeStartX, codeStartY + 8)
            val isKerningYtype = ((kerningBit1 and 0x80000000.toInt()) != 0)
            val kerningMask = kerningBit1.ushr(8).and(0xFFFFFF)
            val hasKerningBit = kerningBit1 and 255 != 0//(kerningBit1 and 255 != 0 && kerningMask != 0xFFFF)


            //dbgprn("$code: Width $width, tags $tags")
            if (hasKerningBit)
                dbgprn("$code: W $width, tags $tags, low? $isLowHeight, kern ${kerningMask.toString(16).padStart(6,'0')} (raw: ${kerningBit1.toLong().and(4294967295).toString(16).padStart(8,'0')})")

            /*val isDiacritics = pixmap.getPixel(codeStartX, codeStartY + H - 1).and(0xFF) != 0
            if (isDiacritics)
                glyphWidth = -glyphWidth*/

            glyphProps[code] = if (hasKerningBit) GlyphProps(width, tags, isLowHeight, isKerningYtype, kerningMask) else GlyphProps(width, tags)

            // extra info
            val extCount = glyphProps[code]?.requiredExtInfoCount() ?: 0
            if (extCount > 0) {

                glyphProps[code]?.extInfo = IntArray(extCount)

                for (x in 0 until extCount) {
                    var info = 0
                    for (y in 0..18) {
                        // if ALPHA is not zero, assume it's 1
                        if (pixmap.getPixel(cellX + x, cellY + y).and(0xFF) != 0) {
                            info = info or (1 shl y)
                        }
                    }

                    glyphProps[code]!!.extInfo!![x] = info
                }
            }

        }
    }

    private fun buildWidthTableFixed() {
        // fixed-width props
        codeRange[SHEET_CJK_PUNCT].forEach { glyphProps[it] = GlyphProps(W_ASIAN_PUNCT, 0) }
        codeRange[SHEET_CUSTOM_SYM].forEach { glyphProps[it] = GlyphProps(20, 0) }
        codeRange[SHEET_FW_UNI].forEach { glyphProps[it] = GlyphProps(W_UNIHAN, 0) }
        codeRange[SHEET_HANGUL].forEach { glyphProps[it] = GlyphProps(W_HANGUL_BASE, 0) }
        codeRangeHangulCompat.forEach { glyphProps[it] = GlyphProps(W_HANGUL_BASE, 0) }
        codeRange[SHEET_KANA].forEach { glyphProps[it] = GlyphProps(W_KANA, 0) }
        codeRange[SHEET_RUNIC].forEach { glyphProps[it] = GlyphProps(9, 0) }
        codeRange[SHEET_UNIHAN].forEach { glyphProps[it] = GlyphProps(W_UNIHAN, 0) }
        (0xD800..0xDFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }
        (0x100000..0x10FFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }
        (0xFFFA0..0xFFFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }


        // manually add width of one orphan insular letter
        // WARNING: glyphs in 0xA770..0xA778 has invalid data, further care is required
        glyphProps[0x1D79] = GlyphProps(9, 0)


        // U+007F is DEL originally, but this font stores bitmap of Replacement Character (U+FFFD)
        // to this position. String replacer will replace U+FFFD into U+007F.
        glyphProps[0x7F] = GlyphProps(15, 0)

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
     * posXbuffer's size is greater than the string, last element marks the width of entire string.
     */
    private fun buildPosMap(str: List<Int>): Pair<IntArray, IntArray> {
        val posXbuffer = IntArray(str.size + 1) { 0 }
        val posYbuffer = IntArray(str.size) { 0 }


        var nonDiacriticCounter = 0 // index of last instance of non-diacritic char
        var stackUpwardCounter = 0
        var stackDownwardCounter = 0

        val HALF_VAR_INIT = W_VAR_INIT.minus(1).div(2)

        // this is starting to get dirty...
        // persisting value. the value is set a few characters before the actual usage
        var extraWidth = 0

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
                else if (!thisProp.writeOnTop) {
                    posXbuffer[charIndex] = when (itsProp.alignWhere) {
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
                    extraWidth = 0
                }
                else if (thisProp.writeOnTop && thisProp.alignXPos == GlyphProps.DIA_JOINER) {
                    posXbuffer[charIndex] = when (itsProp.alignWhere) {
                        GlyphProps.ALIGN_RIGHT ->
                            posXbuffer[nonDiacriticCounter] + W_VAR_INIT + alignmentOffset
                        //GlyphProps.ALIGN_CENTRE ->
                        //    posXbuffer[nonDiacriticCounter] + HALF_VAR_INIT + itsProp.width + alignmentOffset
                        else ->
                            posXbuffer[nonDiacriticCounter] + itsProp.width + alignmentOffset

                    }
                }
                else {
                    // set X pos according to alignment information
                    posXbuffer[charIndex] = when (thisProp.alignWhere) {
                        GlyphProps.ALIGN_LEFT, GlyphProps.ALIGN_BEFORE -> posXbuffer[nonDiacriticCounter]
                        GlyphProps.ALIGN_RIGHT -> {
                            posXbuffer[nonDiacriticCounter] - (W_VAR_INIT - itsProp.width)
                        }
                        GlyphProps.ALIGN_CENTRE -> {
                            val alignXPos = if (itsProp.alignXPos == 0) itsProp.width.div(2) else itsProp.alignXPos

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
                    if (thisProp.alignWhere == GlyphProps.ALIGN_CENTRE) {
                        when (thisProp.stackWhere) {
                            GlyphProps.STACK_DOWN -> {
                                posYbuffer[charIndex] = H_DIACRITICS * stackDownwardCounter
                                stackDownwardCounter++
                            }
                            GlyphProps.STACK_UP -> {
                                posYbuffer[charIndex] = -H_DIACRITICS * stackUpwardCounter

                                // shift down on lowercase if applicable
                                if (getSheetType(thisChar) in autoShiftDownOnLowercase &&
                                        lastNonDiacriticChar.isLowHeight()) {
                                    //dbgprn("AAARRRRHHHH for character ${thisChar.toHex()}")
                                    //dbgprn("lastNonDiacriticChar: ${lastNonDiacriticChar.toHex()}")
                                    //dbgprn("cond: ${thisProp.alignXPos == GlyphProps.DIA_OVERLAY}, charIndex: $charIndex")
                                    if (thisProp.alignXPos == GlyphProps.DIA_OVERLAY)
                                        posYbuffer[charIndex] -= H_OVERLAY_LOWERCASE_SHIFTDOWN * (!flipY).toSign() // if minus-assign doesn't work, try plus-assign
                                    else
                                        posYbuffer[charIndex] -= H_STACKUP_LOWERCASE_SHIFTDOWN * (!flipY).toSign() // if minus-assign doesn't work, try plus-assign
                                }

                                stackUpwardCounter++
                            }
                            GlyphProps.STACK_UP_N_DOWN -> {
                                posYbuffer[charIndex] = H_DIACRITICS * stackDownwardCounter
                                stackDownwardCounter++
                                posYbuffer[charIndex] = -H_DIACRITICS * stackUpwardCounter
                                stackUpwardCounter++
                            }
                        // for BEFORE_N_AFTER, do nothing in here
                        }
                    }
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
                    if (lastCharProp?.writeOnTop == true) {
                        val realDiacriticWidth = if (lastCharProp.alignWhere == GlyphProps.ALIGN_CENTRE) {
                            (lastCharProp.width).div(2) + penultCharProp.alignXPos
                        }
                        else if (lastCharProp.alignWhere == GlyphProps.ALIGN_RIGHT) {
                            (lastCharProp.width) + penultCharProp.alignXPos
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

        return posXbuffer to posYbuffer
    }

    private fun CodepointSequence.normalise(): CodepointSequence {
        val seq = CodepointSequence()

        var i = 0
        while (i < this.size) {
            val c = this[i]
            val cPrev = this.getOrElse(i-1) { -1 }
            val cNext = this.getOrElse(i+1) { -1 }

            // LET THE NORMALISATION BEGIN //

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
            // disassemble Hangul Syllables into Initial-Peak-Final encoding
            else if (c in 0xAC00..0xD7A3) {
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
            else if (diacriticDotRemoval.containsKey(c) && glyphProps[cNext]?.writeOnTop == true && glyphProps[cNext]?.stackWhere == GlyphProps.STACK_UP) {
                seq.add(diacriticDotRemoval[c]!!)
            }
            // rearrange {letter, before-and-after diacritics} as {letter, before-diacritics, after-diacritics}
            // {letter, before-diacritics} part will be dealt with swapping code below
            // DOES NOT WORK if said diacritics has codepoint > 0xFFFF
            else if (i < this.lastIndex && this[i + 1] <= 0xFFFF &&
                glyphProps[this[i + 1]]?.stackWhere == GlyphProps.STACK_BEFORE_N_AFTER) {
                val diacriticsProp = glyphProps[this[i + 1]]!!
                seq.add(c)
                seq.add(diacriticsProp.extInfo!![0])
                seq.add(diacriticsProp.extInfo!![1])
                i++
            }
            // U+007F is DEL originally, but this font stores bitmap of Replacement Character (U+FFFD)
            // to this position. This line will replace U+FFFD into U+007F.
            else if (c == 0xFFFD) {
                seq.add(0x7F) // 0x7F in used internally to display <??> character
            }
            else {
                seq.add(c)
            }

            i++
        }

        // swap position of {letter, diacritics that comes before the letter}
        i = 1
        while (i <= seq.lastIndex) {

            if ((glyphProps[seq[i]] ?: nullProp).alignWhere == GlyphProps.ALIGN_BEFORE) {
                val t = seq[i - 1]
                seq[i - 1] = seq[i]
                seq[i] = t
            }

            i++
        }

        return seq
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

        this.forEach {
            hashAccumulator = hashAccumulator xor it.toLong()
            hashAccumulator *= hashPrime
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

    fun CodePoint.isLowHeight() = glyphProps[this]?.isLowheight == true || this in lowHeightLetters

    private fun getKerning(prevChar: CodePoint, thisChar: CodePoint): Int {
        val maskL = glyphProps[prevChar]?.kerningMask
        val maskR = glyphProps[thisChar]?.kerningMask
        return if (glyphProps[prevChar]?.hasKernData == true && glyphProps[thisChar]?.hasKernData == true) {
            kerningRules.forEachIndexed { index, it ->
                if (it.first.matches(maskL!!) && it.second.matches(maskR!!)) {
                    val contraction = if (glyphProps[prevChar]?.isKernYtype == true || glyphProps[thisChar]?.isKernYtype == true) it.yy else it.bb

                    dbgprn("Kerning rule match #${index+1}: ${prevChar.toChar()}${thisChar.toChar()}, Rule:${it.first} ${it.second}; Contraction: $contraction")

                    return -contraction
                }
            }
            return 0
        }
        else 0
        /*else if (prevChar in lowHeightLetters) {
            return if (thisChar in kernTees) kernTee // lh - T
            else if (thisChar in kernYees) kernYee   // lh - Y
            else 0
        }
        else if (prevChar in kernElls) {
            return if (thisChar in kernTees) kernTee // L - T
            else if (thisChar in kernVees) kernYee   // L - V
            else if (thisChar in kernYees) kernYee   // L - Y
            else 0
        }
        else if (prevChar in kernTees) {
            return if (thisChar in lowHeightLetters) kernTee // T - lh
            else if (thisChar in kernJays) kernTee           // T - J
            else if (thisChar in kernAyes) kernYee           // T - A
            else if (thisChar in kernDees) kernTee           // T - d
            else 0
        }
        else if (prevChar in kernYees) {
            return if (thisChar in lowHeightLetters) kernYee // Y - lh
            else if (thisChar in kernAyes) kernYee           // Y - A
            else if (thisChar in kernJays) kernYee           // Y - J
            else if (thisChar in kernDees) kernYee           // Y - d
            else 0
        }
        else if (prevChar in kernAyes) {
            return if (thisChar in kernVees) kernAV  // A - V
            else if (thisChar in kernTees) kernAV    // A - T
            else if (thisChar in kernYees) kernYee   // A - Y
            else 0
        }
        else if (prevChar in kernVees) {
            return if (thisChar in kernAyes) kernAV  // V - A
            else if (thisChar in kernJays) kernAV    // V - J
            else if (thisChar in kernDees) kernAV    // V - d
            else 0
        }
        else if (prevChar in kernGammas) {
            return if (thisChar in kernAyes) kernYee       // Γ - Α
            else if (thisChar in lowHeightLetters) kernTee // Γ - lh
            else if (thisChar in kernJays) kernTee         // Γ - J
            else if (thisChar in kernDees) kernTee         // Γ - d
            else 0
        }
        else if (prevChar in kernBees) {
            return if (thisChar in kernTees) kernTee // b - T
            else if (thisChar in kernYees) kernYee   // b - Y
            else 0
        }
        else if (prevChar in kernLowVees) {
            return if (thisChar in kernTees) kernTee
            else if (thisChar in kernLowLambdas) kernAVlow
            else 0
        }
        else if (prevChar in kernLowLambdas) {
            return if (thisChar in kernTees) kernTee
            else if (thisChar in kernLowVees) kernAVlow
            else 0
        }
        else if (prevChar in slashes) {
            return if (thisChar in kernDees || thisChar in lowHeightLetters) kernSlash // / - d
            else if (thisChar in slashes) kernDoubleSlash
            else 0
        }
        else 0*/
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
        internal val SHEET_FW_UNI =            8
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
        internal val SHEET_INSUAR_VARW =       21 // currently only for U+1D79
        internal val SHEET_NAGARI_BENGALI_VARW=22
        internal val SHEET_KARTULI_CAPS_VARW = 23
        internal val SHEET_DIACRITICAL_MARKS_VARW = 24
        internal val SHEET_GREEK_POLY_VARW   = 25
        internal val SHEET_EXTC_VARW =         26
        internal val SHEET_EXTD_VARW =         27
        internal val SHEET_CURRENCIES_VARW =   28
        internal val SHEET_INTERNAL_VARW = 29

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


        private val unihanWidthSheets = arrayOf(
                SHEET_UNIHAN,
                SHEET_FW_UNI
        )
        private val variableWidthSheets = arrayOf(
                SHEET_ASCII_VARW,
                SHEET_EXTA_VARW,
                SHEET_EXTB_VARW,
                SHEET_CYRILIC_VARW,
                SHEET_UNI_PUNCT_VARW,
                SHEET_GREEK_VARW,
                SHEET_THAI_VARW,
                SHEET_HAYEREN_VARW,
                SHEET_KARTULI_VARW,
                SHEET_IPA_VARW,
                SHEET_LATIN_EXT_ADD_VARW,
                SHEET_BULGARIAN_VARW,
                SHEET_SERBIAN_VARW,
                SHEET_TSALAGI_VARW,
                SHEET_INSUAR_VARW,
                SHEET_NAGARI_BENGALI_VARW,
                SHEET_KARTULI_CAPS_VARW,
                SHEET_DIACRITICAL_MARKS_VARW,
                SHEET_GREEK_POLY_VARW,
                SHEET_EXTC_VARW,
                SHEET_EXTD_VARW,
                SHEET_CURRENCIES_VARW,
                SHEET_INTERNAL_VARW
        )
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
                "fullwidth_forms.tga",
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
                "insular_variable.tga",
                "devanagari_bengali_variable.tga",
                "kartuli_allcaps_variable.tga",
                "diacritical_marks_variable.tga",
                "greek_polytonic_xyswap_variable.tga",
                "latinExtC_variable.tga",
                "latinExtD_variable.tga",
                "currencies_variable.tga",
                "internal_variable.tga"
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
                0xFF00..0xFF1F, // SHEET_FW_UNI
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
                0x1D79..0x1D79, // SHEET_INSULAR_VARW; todo: Phonetic Extensions et al, 1D00..1DFF
                0x900..0x9FF, // SHEET_NAGARI_BENGALI_VARW
                0x1C90..0x1CBF, // SHEET_KARTULI_CAPS_VARW
                0x300..0x36F, // SHEET_DIACRITICAL_MARKS_VARW
                0x1F00..0x1FFF, // SHEET_GREEK_POLY_VARW
                0x2C60..0x2C7F, // SHEET_EXTC_VARW
                0xA720..0xA7FF, // SHEET_EXTD_VARW
                0x20A0..0x20CF, // SHEET_CURRENCIES_VARW
                0xFFE00..0xFFF9F // SHEET_INTERNAL_VARW
        )
        private val codeRangeHangulCompat = 0x3130..0x318F

        private val diacriticDotRemoval = hashMapOf(
            'i'.toInt() to 0x131,
            'j'.toInt() to 0x237
        )

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

        private val jungseongWide = (jungseongOU.toList() + jungseongEU.toList()).toSortedSet()

        // index of the peak, 0 being blank, 1 being ㅏ
        // indices of peaks that number of lit pixels (vertically counted) on x=11 is greater than 7
        private val hangulPeaksWithExtraWidth = arrayOf(2,4,6,8,11,16,32,33,37,42,44,48,50,71,75,78,79,83,86,87,88,94).toSortedSet()

        /**
         * @param i Initial (Choseong)
         * @param p Peak (Jungseong)
         * @param f Final (Jongseong)
         */
        private fun getHanInitialRow(i: Int, p: Int, f: Int): Int {
            val ret =
                    if (p in jungseongI) 3
                    else if (p in jungseongOUComplex) 7
                    else if (p in jungseongOEWI) 11
                    else if (p in jungseongOU) 5
                    else if (p in jungseongEU) 9
                    else if (p in jungseongYI) 13
                    else 1

            return if (f == 0) ret else ret + 1
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
        private fun isFullwidthUni(c: CodePoint) = c in codeRange[SHEET_FW_UNI]
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
        private fun isInsular(c: CodePoint) = c == 0x1D79
        private fun isNagariBengali(c: CodePoint) = c in codeRange[SHEET_NAGARI_BENGALI_VARW]
        private fun isKartvelianCaps(c: CodePoint) = c in codeRange[SHEET_KARTULI_CAPS_VARW]
        private fun isDiacriticalMarks(c: CodePoint) = c in codeRange[SHEET_DIACRITICAL_MARKS_VARW]
        private fun isPolytonicGreek(c: CodePoint) = c in codeRange[SHEET_GREEK_POLY_VARW]
        private fun isExtC(c: CodePoint) = c in codeRange[SHEET_EXTC_VARW]
        private fun isExtD(c: CodePoint) = c in codeRange[SHEET_EXTD_VARW]
        private fun isHangulCompat(c: CodePoint) = c in codeRangeHangulCompat
        private fun isCurrencies(c: CodePoint) = c in codeRange[SHEET_CURRENCIES_VARW]
        private fun isInternalSymbols(c: CodePoint) = c in codeRange[SHEET_INTERNAL_VARW]

        // underscored name: not a charset
        private fun _isCaps(c: CodePoint) = Character.isUpperCase(c) || isKartvelianCaps(c)


        private fun extAindexX(c: CodePoint) = (c - 0x100) % 16
        private fun extAindexY(c: CodePoint) = (c - 0x100) / 16

        private fun extBindexX(c: CodePoint) = (c - 0x180) % 16
        private fun extBindexY(c: CodePoint) = (c - 0x180) / 16

        private fun runicIndexX(c: CodePoint) = (c - 0x16A0) % 16
        private fun runicIndexY(c: CodePoint) = (c - 0x16A0) / 16

        private fun kanaIndexX(c: CodePoint) = (c - 0x3040) % 16
        private fun kanaIndexY(c: CodePoint) =
                if (c in 0x31F0..0x31FF) 12
                else if (c in 0x1B000..0x1B00F) 13
                else (c - 0x3040) / 16

        private fun cjkPunctIndexX(c: CodePoint) = (c - 0x3000) % 16
        private fun cjkPunctIndexY(c: CodePoint) = (c - 0x3000) / 16

        private fun cyrilicIndexX(c: CodePoint) = (c - 0x400) % 16
        private fun cyrilicIndexY(c: CodePoint) = (c - 0x400) / 16

        private fun fullwidthUniIndexX(c: CodePoint) = (c - 0xFF00) % 16
        private fun fullwidthUniIndexY(c: CodePoint) = (c - 0xFF00) / 16

        private fun uniPunctIndexX(c: CodePoint) = (c - 0x2000) % 16
        private fun uniPunctIndexY(c: CodePoint) = (c - 0x2000) / 16

        private fun unihanIndexX(c: CodePoint) = (c - 0x3400) % 256
        private fun unihanIndexY(c: CodePoint) = (c - 0x3400) / 256

        private fun greekIndexX(c: CodePoint) = (c - 0x370) % 16
        private fun greekIndexY(c: CodePoint) = (c - 0x370) / 16

        private fun thaiIndexX(c: CodePoint) = (c - 0xE00) % 16
        private fun thaiIndexY(c: CodePoint) = (c - 0xE00) / 16

        private fun symbolIndexX(c: CodePoint) = (c - 0xE000) % 16
        private fun symbolIndexY(c: CodePoint) = (c - 0xE000) / 16

        private fun armenianIndexX(c: CodePoint) = (c - 0x530) % 16
        private fun armenianIndexY(c: CodePoint) = (c - 0x530) / 16

        private fun kartvelianIndexX(c: CodePoint) = (c - 0x10D0) % 16
        private fun kartvelianIndexY(c: CodePoint) = (c - 0x10D0) / 16

        private fun ipaIndexX(c: CodePoint) = (c - 0x250) % 16
        private fun ipaIndexY(c: CodePoint) = (c - 0x250) / 16

        private fun latinExtAddX(c: CodePoint) = (c - 0x1E00) % 16
        private fun latinExtAddY(c: CodePoint) = (c - 0x1E00) / 16

        private fun cherokeeIndexX(c: CodePoint) = (c - 0x13A0) % 16
        private fun cherokeeIndexY(c: CodePoint) = (c - 0x13A0) / 16

        private fun insularIndexX(c: CodePoint) = 0
        private fun insularIndexY(c: CodePoint) = 0

        private fun nagariIndexX(c: CodePoint) = (c - 0x900) % 16
        private fun nagariIndexY(c: CodePoint) = (c - 0x900) / 16

        private fun kartvelianCapsIndexX(c: CodePoint) = (c - 0x1C90) % 16
        private fun kartvelianCapsIndexY(c: CodePoint) = (c - 0x1C90) / 16

        private fun diacriticalMarksIndexX(c: CodePoint) = (c - 0x300) % 16
        private fun diacriticalMarksIndexY(c: CodePoint) = (c - 0x300) / 16

        private fun polytonicGreekIndexX(c: CodePoint) = (c - 0x1F00) % 16
        private fun polytonicGreekIndexY(c: CodePoint) = (c - 0x1F00) / 16

        private fun extCIndexX(c: CodePoint) = (c - 0x2C60) % 16
        private fun extCIndexY(c: CodePoint) = (c - 0x2C60) / 16

        private fun extDIndexX(c: CodePoint) = (c - 0xA720) % 16
        private fun extDIndexY(c: CodePoint) = (c - 0xA720) / 16

        private fun currenciesIndexX(c: CodePoint) = (c - 0x20A0) % 16
        private fun currenciesIndexY(c: CodePoint) = (c - 0x20A0) / 16

        private fun internalIndexX(c: CodePoint) = (c - 0xFFE00) % 16
        private fun internalIndexY(c: CodePoint) = (c - 0xFFE00) / 16
        /*
#!/usr/bin/python3

s = ""
a = []

for c in s:
    a.append("0x{0:x}".format(ord(c)))

print(','.join(a))
 */     // acegijmnopqrsuvwxyzɱɳʙɾɽʒʂʐʋɹɻɥɟɡɢʛȵɲŋɴʀɕʑçʝxɣχʁʜʍɰʟɨʉɯuʊøɘɵɤəɛœɜɞʌɔæɐɶɑɒɚɝɩɪʅʈʏʞⱥⱦⱱⱳⱴⱶⱷⱸⱺⱻꜥꜩꜫꜭꜯꜰꜱꜳꜵꜷꜹꜻꜽꜿꝋꝍꝏꝑꝓꝕꝗꝙꝛꝝꝟꝡꝫꝯꝳꝴꝵꝶꝷꝺꝼꝿꞁꞃꞅꞇꞑꞓꞔꞛꞝꞟꞡꞥꞧꞩꞮꞷꟺ\uA7AF\uA7B9\uA7C3\uA7CAƍƞơƣƨưƴƶƹƺƽƿıȷ
        private val lowHeightLetters = intArrayOf(0x61,0x63,0x65,0x67,0x69,0x6a,0x6d,0x6e,0x6f,0x70,0x71,0x72,0x73,0x75,0x76,0x77,0x78,0x79,0x7a,0x271,0x273,0x299,0x27e,0x27d,0x292,0x282,0x290,0x28b,0x279,0x27b,0x265,0x25f,0x261,0x262,0x29b,0x235,0x272,0x14b,0x274,0x280,0x255,0x291,0xe7,0x29d,0x78,0x263,0x3c7,0x281,0x29c,0x28d,0x270,0x29f,0x268,0x289,0x26f,0x75,0x28a,0xf8,0x258,0x275,0x264,0x259,0x25b,0x153,0x25c,0x25e,0x28c,0x254,0xe6,0x250,0x276,0x251,0x252,0x25a,0x25d,0x269,0x26a,0x285,0x288,0x28f,0x29e,0x2c65,0x2c66,0x2c71,0x2c73,0x2c74,0x2c76,0x2c77,0x2c78,0x2c7a,0x2c7b,0xa725,0xa729,0xa72b,0xa72d,0xa72f,0xa730,0xa731,0xa733,0xa735,0xa737,0xa739,0xa73b,0xa73d,0xa73f,0xa74b,0xa74d,0xa74f,0xa751,0xa753,0xa755,0xa757,0xa759,0xa75b,0xa75d,0xa75f,0xa761,0xa76b,0xa76f,0xa773,0xa774,0xa775,0xa776,0xa777,0xa77a,0xa77c,0xa77f,0xa781,0xa783,0xa785,0xa787,0xa791,0xa793,0xa794,0xa79b,0xa79d,0xa79f,0xa7a1,0xa7a5,0xa7a7,0xa7a9,0xa7ae,0xa7b7,0xa7fa,0xa7af,0xa7b9,0xa7c3,0xa7ca,0x18d,0x19e,0x1a1,0x1a3,0x1a8,0x1b0,0x1b4,0x1b6,0x1b9,0x1ba,0x1bd,0x1bf,0x131,0x237,0xFFE01).toSortedSet()
        // TŢŤƬƮȚͲΤТҬᛏṪṬṮṰⲦϮϯⴶꚌꚐᎢᛠꓔ
        private val kernTees = intArrayOf(0x54,0x162,0x164,0x1ac,0x1ae,0x21a,0x372,0x3a4,0x422,0x4ac,0x16cf,0x1e6a,0x1e6c,0x1e6e,0x1e70,0x2ca6,0x3ee,0x3ef,0x2d36,0xa68c,0xa690,0x13a2,0x16e0,0xa4d4).toSortedSet()
        // ŦȾYÝŶŸɎΎΫΥҮҰᛉᛘẎỲỴỶỸὙὛὝὟῪΎꓬȲ
        private val kernYees = intArrayOf(0x166,0x23e,0x59,0xdd,0x176,0x178,0x24e,0x38e,0x3ab,0x3a5,0x4ae,0x4b0,0x16c9,0x16d8,0x1e8e,0x1ef2,0x1ef4,0x1ef6,0x1ef8,0x1f59,0x1f5b,0x1f5d,0x1f5f,0x1fea,0x1feb,0xa4ec,0x232).toSortedSet()
        // VṼṾⱯⴸꓦꓯꝞ
        private val kernVees = intArrayOf(0x56,0x1e7c,0x1e7e,0x2c6f,0x2d38,0xa4e6,0xa4ef,0xa75e).toSortedSet()
        // AÀÁÂÃÄÅĀĂĄǍǞǠǺȀȂȦɅΆΑΛАДЛѦӅӐӒԮḀẠẢẤẦẨẪẬẮẰẲẴẶἈἉἊἋἌἍἎἏᾸᾹᾺΆꓥꓮꙞꙢꙤꚀꚈꜲ
        private val kernAyes = intArrayOf(0x41,0xc0,0xc1,0xc2,0xc3,0xc4,0xc5,0x100,0x102,0x104,0x1cd,0x1de,0x1e0,0x1fa,0x200,0x202,0x226,0x245,0x386,0x391,0x39b,0x410,0x414,0x41b,0x466,0x4c5,0x4d0,0x4d2,0x52e,0x1e00,0x1ea0,0x1ea2,0x1ea4,0x1ea6,0x1ea8,0x1eaa,0x1eac,0x1eae,0x1eb0,0x1eb2,0x1eb4,0x1eb6,0x1f08,0x1f09,0x1f0a,0x1f0b,0x1f0c,0x1f0d,0x1f0e,0x1f0f,0x1fb8,0x1fb9,0x1fba,0x1fbb,0xa4e5,0xa4ee,0xa65e,0xa662,0xa664,0xa680,0xa688,0xa732).toSortedSet()
        // LĹĻĽĿŁʟᏞᴌḶḸḺḼꓡꓕꝆꝈꞭꞱꮮւևⳐⳑԼⱢⱠ
        private val kernElls = intArrayOf(0x4c,0x139,0x13b,0x13d,0x13f,0x141,0x29f,0x13de,0x1d0c,0x1e36,0x1e38,0x1e3a,0x1e3c,0xa4e1,0xa4d5,0xa746,0xa748,0xa7ad,0xa7b1,0xabae,0x582,0x587,0x2cd0,0x2cd1,0x53c,0x2c62,0x2c60).toSortedSet()
        // ΓЃГҐҒӶӺᎱᚨᚩᚪᚫᚹᛇᛚᛛᛢᛮⲄꓩꞄ
        private val kernGammas = intArrayOf(0x393,0x403,0x413,0x490,0x492,0x4f6,0x4fa,0x13b1,0x16a8,0x16a9,0x16aa,0x16ab,0x16b9,0x16c7,0x16da,0x16db,0x16e2,0x16ee,0x2c84,0xa4e9,0xa784).toSortedSet()
        // JĴɹɺɻͿᛇᴊᎫᏗꓕꓙꞱꭻꮧ
        private val kernJays = intArrayOf(0x4a,0x134,0x279,0x27a,0x27b,0x37f,0x16c7,0x1d0a,0x13ab,0x13d7,0xa4d5,0xa4d9,0xa7b1,0xab7b,0xaba7).toSortedSet()
        // dďđƌɗʠḋԀԁԂԃԺժմվփᎴᏊᏯᲫᶁᶑḍḏḑḓⴓⴛⴣⴥꝱꟈ
        private val kernDees = intArrayOf(0x64,0x10f,0x111,0x18c,0x257,0x2a0,0x1e0b,0x500,0x501,0x502,0x503,0x53a,0x56a,0x574,0x57e,0x583,0x13b4,0x13ca,0x13ef,0x1cab,0x1d81,0x1d91,0x1e0d,0x1e0f,0x1e11,0x1e13,0x2d13,0x2d1b,0x2d23,0x2d25,0xa771,0xa7c8)
        // bhkƙþĥķƄƅƕƙƥǩǶȟɓɦɧʣʤʥʪʫЪЬҺһԂԃԈԊԠԢԦԧԽՒիխհնփևსხᏏᏓᏥᲮᵬᶀᶄḅḇḣḥḧḱḳḵᾈᾉᾊᾋᾌᾍᾎᾏᾘᾙᾚᾛᾜᾝᾞᾟᾨᾩᾪᾫᾬᾭᾮᾯῌⴐⴑⴙⴛᲆⱨⱪꙎꝃꝧꞗꞣ
        private val kernBees = intArrayOf(0x62,0x68,0x6b,0x199,0xfe,0x125,0x137,0x184,0x185,0x195,0x199,0x1a5,0x1e9,0x1f6,0x21f,0x253,0x266,0x267,0x2a3,0x2a4,0x2a5,0x2aa,0x2ab,0x42a,0x42c,0x4ba,0x4bb,0x502,0x503,0x508,0x50a,0x520,0x522,0x526,0x527,0x53d,0x552,0x56b,0x56d,0x570,0x576,0x583,0x587,0x10e1,0x10ee,0x13cf,0x13d3,0x13e5,0x1cae,0x1d6c,0x1d80,0x1d84,0x1e05,0x1e07,0x1e23,0x1e25,0x1e27,0x1e31,0x1e33,0x1e35,0x1f88,0x1f89,0x1f8a,0x1f8b,0x1f8c,0x1f8d,0x1f8e,0x1f8f,0x1f98,0x1f99,0x1f9a,0x1f9b,0x1f9c,0x1f9d,0x1f9e,0x1f9f,0x1fa8,0x1fa9,0x1faa,0x1fab,0x1fac,0x1fad,0x1fae,0x1faf,0x1fcc,0x2d10,0x2d11,0x2d19,0x2d1b,0x1c86,0x2c68,0x2c6a,0xa64e,0xa743,0xa767,0xa797,0xa7a3)
        // yÿŷƴɏɣɤʏγνуўѵѷүұӯӱӳ
        private val kernLowVees = intArrayOf(0x79,0xff,0x177,0x1b4,0x24f,0x263,0x264,0x28f,0x3b3,0x3bd,0x443,0x45e,0x475,0x477,0x4af,0x4b1,0x4ef,0x4f1,0x4f3).toSortedSet()
        // ƛʌλлљѧԉԓԡԯ
        private val kernLowLambdas = intArrayOf(0x19b,0x28c,0x3bb,0x43b,0x459,0x467,0x509,0x513,0x521,0x52f).toSortedSet()

        private val slashes = intArrayOf(0x2f)

        private val kernTee = -2
        private val kernYee = -1
        private val kernAV = -1
        private val kernAVlow = -1
        private val kernSlash = -1
        private val kernDoubleSlash = -2


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
                Kem(ing("_@_`___`__"),ing("`_________")),
                Kem(ing("_@_@___`__"),ing("`___`_@___")),
                Kem(ing("_@_@___`__"),ing("`___@_____"),1,1),
                Kem(ing("___`_`____"),ing("`___@_`___")),
                Kem(ing("___`_`____"),ing("`_@___`___")),

//                Kem(ing("_`________"),ing("@_`___`___")),
//                Kem(ing("_`___`_@__"),ing("@_@___`___")),
//                Kem(ing("_`___@____"),ing("@_@___`___"),1,1),
//                Kem(ing("_`___@_`__"),ing("__`_`_____")),
//                Kem(ing("_`_@___`__"),ing("__`_`_____")),
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
