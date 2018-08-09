/*
 * Terrarum Sans Bitmap
 * 
 * Copyright (c) 2017-2018 Minjae Song (Torvald)
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
import net.torvald.terrarumsansbitmap.GlyphProps
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

typealias CodepointSequence = ArrayList<Int>

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
 * Color Codes have following Unicode mapping: U+10RGBA, A must be non-zero to be visible. U+100000 reverts any colour code effects.
 *
 * ## Control Characters
 *
 * - U+100000: Clear colour keys
 * - U+100001..U+10FFFF: Colour key (in RGBA order)
 * - U+FFFF8: Charset override -- normal (incl. Russian, Ukrainian, etc.)
 * - U+FFFF9: Charset override -- Bulgarian
 * - U+FFFFA: Charset override -- Serbian
 *
 * @param noShadow Self-explanatory
 * @param flipY If you have Y-down coord system implemented on your GDX (e.g. legacy codebase), set this to ```true``` so that the shadow won't be upside-down. For glyph getting upside-down, set ```TextureRegionPack.globalFlipY = true```.
 *
 * Created by minjaesong on 2017-06-15.
 */
class GameFontBase(fontDir: String, val noShadow: Boolean = false, val flipY: Boolean = false, val minFilter: Texture.TextureFilter = Texture.TextureFilter.Nearest, val magFilter: Texture.TextureFilter = Texture.TextureFilter.Nearest, var errorOnUnknownChar: Boolean = false) : BitmapFont() {

    private fun getHanChosung(hanIndex: Int) = hanIndex / (JUNG_COUNT * JONG_COUNT)
    private fun getHanJungseong(hanIndex: Int) = hanIndex / JONG_COUNT % JUNG_COUNT
    private fun getHanJongseong(hanIndex: Int) = hanIndex % JONG_COUNT

    private val jungseongWide = arrayOf(8, 12, 13, 17, 18, 21)
    private val jungseongComplex = arrayOf(9, 10, 11, 14, 15, 16, 22)

    private fun isJungseongWide(hanIndex: Int) = jungseongWide.contains(getHanJungseong(hanIndex))
    private fun isJungseongComplex(hanIndex: Int) = jungseongComplex.contains(getHanJungseong(hanIndex))

    private fun getHanInitialRow(hanIndex: Int): Int {
        val ret: Int

        if (isJungseongWide(hanIndex))
            ret = 2
        else if (isJungseongComplex(hanIndex))
            ret = 4
        else
            ret = 0

        return if (getHanJongseong(hanIndex) == 0) ret else ret + 1
    }

    private fun getHanMedialRow(hanIndex: Int) = if (getHanJongseong(hanIndex) == 0) 6 else 7

    private fun getHanFinalRow(hanIndex: Int): Int {
        val jungseongIndex = getHanJungseong(hanIndex)

        return if (jungseongWide.contains(jungseongIndex))
            8
        else
            9
    }

    private fun isHangul(c: Int) = c in codeRange[SHEET_HANGUL]
    private fun isAscii(c: Int) = c in codeRange[SHEET_ASCII_VARW]
    private fun isRunic(c: Int) = c in codeRange[SHEET_RUNIC]
    private fun isExtA(c: Int) = c in codeRange[SHEET_EXTA_VARW]
    private fun isExtB(c: Int) = c in codeRange[SHEET_EXTB_VARW]
    private fun isKana(c: Int) = c in codeRange[SHEET_KANA] || c in 0x31F0..0x31FF || c in 0x1B000..0x1B001
    private fun isCJKPunct(c: Int) = c in codeRange[SHEET_CJK_PUNCT]
    private fun isUniHan(c: Int) = c in codeRange[SHEET_UNIHAN]
    private fun isCyrilic(c: Int) = c in codeRange[SHEET_CYRILIC_VARW]
    private fun isFullwidthUni(c: Int) = c in codeRange[SHEET_FW_UNI]
    private fun isUniPunct(c: Int) = c in codeRange[SHEET_UNI_PUNCT_VARW]
    private fun isGreek(c: Int) = c in codeRange[SHEET_GREEK_VARW]
    private fun isThai(c: Int) = c in codeRange[SHEET_THAI_VARW]
    /*private fun isDiacritics(c: Int) = c in 0xE34..0xE3A
            || c in 0xE47..0xE4E
            || c == 0xE31*/
    private fun isCustomSym(c: Int) = c in codeRange[SHEET_CUSTOM_SYM]
    private fun isArmenian(c: Int) = c in codeRange[SHEET_HAYEREN_VARW]
    private fun isKartvelian(c: Int) = c in codeRange[SHEET_KARTULI_VARW]
    private fun isIPA(c: Int) = c in codeRange[SHEET_IPA_VARW]
    private fun isLatinExtAdd(c: Int) = c in 0x1E00..0x1EFF
    private fun isBulgarian(c: Int) = c in 0x400..0x45F
    private fun isColourCode(c: Int) = c in 0x100000..0x10FFFF
    private fun isCharsetOverride(c: Int) = c in 0xFFFF8..0xFFFFF
    private fun isCherokee(c: Int) = c in codeRange[SHEET_TSALAGI_VARW]
    private fun isInsular(c: Int) = c == 0x1D79 || c in 0xA779..0xA787


    private fun extAindexX(c: Int) = (c - 0x100) % 16
    private fun extAindexY(c: Int) = (c - 0x100) / 16

    private fun extBindexX(c: Int) = (c - 0x180) % 16
    private fun extBindexY(c: Int) = (c - 0x180) / 16

    private fun runicIndexX(c: Int) = (c - 0x16A0) % 16
    private fun runicIndexY(c: Int) = (c - 0x16A0) / 16

    private fun kanaIndexX(c: Int) = (c - 0x3040) % 16
    private fun kanaIndexY(c: Int) =
            if (c in 0x31F0..0x31FF) 12
            else if (c in 0x1B000..0x1B00F) 13
            else (c - 0x3040) / 16

    private fun cjkPunctIndexX(c: Int) = (c - 0x3000) % 16
    private fun cjkPunctIndexY(c: Int) = (c - 0x3000) / 16

    private fun cyrilicIndexX(c: Int) = (c - 0x400) % 16
    private fun cyrilicIndexY(c: Int) = (c - 0x400) / 16

    private fun fullwidthUniIndexX(c: Int) = (c - 0xFF00) % 16
    private fun fullwidthUniIndexY(c: Int) = (c - 0xFF00) / 16

    private fun uniPunctIndexX(c: Int) = (c - 0x2000) % 16
    private fun uniPunctIndexY(c: Int) = (c - 0x2000) / 16

    private fun unihanIndexX(c: Int) = (c - 0x3400) % 256
    private fun unihanIndexY(c: Int) = (c - 0x3400) / 256

    private fun greekIndexX(c: Int) = (c - 0x370) % 16
    private fun greekIndexY(c: Int) = (c - 0x370) / 16

    private fun thaiIndexX(c: Int) = (c - 0xE00) % 16
    private fun thaiIndexY(c: Int) = (c - 0xE00) / 16

    private fun symbolIndexX(c: Int) = (c - 0xE000) % 16
    private fun symbolIndexY(c: Int) = (c - 0xE000) / 16

    private fun armenianIndexX(c: Int) = (c - 0x530) % 16
    private fun armenianIndexY(c: Int) = (c - 0x530) / 16

    private fun kartvelianIndexX(c: Int) = (c - 0x10D0) % 16
    private fun kartvelianIndexY(c: Int) = (c - 0x10D0) / 16

    private fun ipaIndexX(c: Int) = (c - 0x250) % 16
    private fun ipaIndexY(c: Int) = (c - 0x250) / 16

    private fun latinExtAddX(c: Int) = (c - 0x1E00) % 16
    private fun latinExtAddY(c: Int) = (c - 0x1E00) / 16

    private fun cherokeeIndexX(c: Int) = (c - 0x13A0) % 16
    private fun cherokeeIndexY(c: Int) = (c - 0x13A0) / 16

    private fun insularIndexX(c: Int) =
            if (c == 0x1D79) 0 else (c - 0xA770) % 16
    private fun insularIndexY(c: Int) =
            if (c == 0x1D79) 0 else (c - 0xA770) / 16

    private fun getColour(codePoint: Int): Color { // input: 0x10ARGB, out: RGBA8888
        if (colourBuffer.containsKey(codePoint))
            return colourBuffer[codePoint]!!

        val a = codePoint.and(0xF000).ushr(12)
        val r = codePoint.and(0x0F00).ushr(8)
        val g = codePoint.and(0x00F0).ushr(4)
        val b = codePoint.and(0x000F)

        val col = Color(r.shl(28) or r.shl(24) or g.shl(20) or g.shl(16) or b.shl(12) or b.shl(8) or a.shl(4) or a)


        colourBuffer[codePoint] = col
        return col
    }

    private val colourBuffer = HashMap<Int, Color>()

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
            SHEET_INSUAR_VARW
    )

    private val fontParentDir = if (fontDir.endsWith('/') || fontDir.endsWith('\\')) fontDir else "$fontDir/"
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
            "insular_variable.tga"
    )
    private val codeRange = arrayOf( // MUST BE MATCHING WITH SHEET INDICES!!
            0..0xFF,
            0xAC00..0xD7A3,
            0x100..0x17F,
            0x180..0x24F,
            0x3040..0x30FF,
            0x3000..0x303F,
            0x3400..0x9FFF,
            0x400..0x52F,
            0xFF00..0xFF1F,
            0x2000..0x205F,
            0x370..0x3CE,
            0xE00..0xE5F,
            0x530..0x58F,
            0x10D0..0x10FF,
            0x250..0x2AF,
            0x16A0..0x16FF,
            0x1E00..0x1EFF,
            0xE000..0xE0FF,
            0xF00000..0xF0005F, // assign them to PUA
            0xF00060..0xF000BF, // assign them to PUA
            0x13A0..0x13F5,
            0xA770..0xA787
    )
    private val glyphProps: HashMap<Int, GlyphProps> = HashMap()
    private val sheets: Array<TextureRegionPack>

    private var charsetOverride = 0

    init {
        val sheetsPack = ArrayList<TextureRegionPack>()

        // first we create pixmap to read pixels, then make texture using pixmap
        fileList.forEachIndexed { index, it ->
            val isVariable1 = it.endsWith("_variable.tga")
            val isVariable2 = variableWidthSheets.contains(index)
            val isVariable = isVariable1 && isVariable2

            // idiocity check
            if (isVariable1 && !isVariable2)
                throw Error("[TerrarumSansBitmap] font is named as variable on the name but not enlisted as")
            else if (!isVariable1 && isVariable2)
                throw Error("[TerrarumSansBitmap] font is enlisted as variable on the name but not named as")


            val pixmap: Pixmap


            // unpack gz if applicable
            if (it.endsWith(".gz")) {
                val tmpFileName = "tmp_${it.dropLast(7)}.tga"

                val gzi = GZIPInputStream(Gdx.files.internal(fontParentDir + it).read(8192))
                val wholeFile = gzi.readBytes()
                gzi.close()
                val fos = BufferedOutputStream(FileOutputStream(tmpFileName))
                fos.write(wholeFile)
                fos.flush()
                fos.close()

                pixmap = Pixmap(Gdx.files.internal(tmpFileName))

                File(tmpFileName).delete()
            }
            else {
                pixmap = Pixmap(Gdx.files.internal(fontParentDir + it))
            }


            buildWidthTableFixed()

            if (isVariable) {
                println("[TerrarumSansBitmap] loading texture $it [VARIABLE]")
                buildWidthTable(pixmap, codeRange[index], 16)
            }
            else {
                println("[TerrarumSansBitmap] loading texture $it")
            }

            val texture = Texture(pixmap)
            val texRegPack = if (isVariable) {
                TextureRegionPack(texture, W_VAR_INIT, H, HGAP_VAR, 0)
            }
            else if (index == SHEET_UNIHAN) {
                TextureRegionPack(texture, W_UNIHAN, H_UNIHAN) // the only exception that is height is 16
            }
            // below they all have height of 20 'H'
            else if (index == SHEET_FW_UNI) {
                TextureRegionPack(texture, W_UNIHAN, H)
            }
            else if (index == SHEET_CJK_PUNCT) {
                TextureRegionPack(texture, W_ASIAN_PUNCT, H)
            }
            else if (index == SHEET_KANA) {
                TextureRegionPack(texture, W_KANA, H)
            }
            else if (index == SHEET_HANGUL) {
                TextureRegionPack(texture, W_HANGUL, H)
            }
            else if (index == SHEET_CUSTOM_SYM) {
                TextureRegionPack(texture, SIZE_CUSTOM_SYM, SIZE_CUSTOM_SYM) // TODO variable
            }
            else if (index == SHEET_RUNIC) {
                TextureRegionPack(texture, W_LATIN_WIDE, H)
            }
            else throw IllegalArgumentException("[TerrarumSansBitmap] Unknown sheet index: $index")

            texRegPack.texture.setFilter(minFilter, magFilter)

            sheetsPack.add(texRegPack)

            pixmap.dispose() // you are terminated
        }

        sheets = sheetsPack.toTypedArray()
    }

    override fun getLineHeight(): Float = H.toFloat()

    override fun getXHeight() = lineHeight
    override fun getCapHeight() = lineHeight
    override fun getAscent() = 0f
    override fun getDescent() = 0f

    override fun isFlipped() = false

    override fun setFixedWidthGlyphs(glyphs: CharSequence) {
        throw UnsupportedOperationException("Nope, no monospace, and figures are already fixed width, bruv.")
    }


    init {
        setUseIntegerPositions(true)
        setOwnsTexture(true)
    }

    private val offsetUnihan = (H - H_UNIHAN) / 2
    private val offsetCustomSym = (H - SIZE_CUSTOM_SYM) / 2

    private var textBuffer = CodepointSequence(256)
    private var posXbuffer = intArrayOf() // absolute posX of glyphs from print-origin
    private var posYbuffer = intArrayOf() // absolute posY of glyphs from print-origin
    private var glyphWidthBuffer = intArrayOf() // width of each glyph

    private lateinit var originalColour: Color

    private var nullProp = GlyphProps(15, 0)

    override fun draw(batch: Batch, str: CharSequence, x: Float, y: Float): GlyphLayout? {
        val str = str.toCodePoints()

        fun Int.flipY() = this * if (flipY) 1 else -1


        // always draw at integer position; this is bitmap font after all
        val x = Math.round(x).toFloat()
        val y = Math.round(y).toFloat()


        if (textBuffer != str) {
            textBuffer = str
            val widths = getWidthOfCharSeq(str)

            glyphWidthBuffer = widths

            posXbuffer = IntArray(str.size, { 0 })
            posYbuffer = IntArray(str.size, { 0 })


            var nonDiacriticCounter = 0 // index of last instance of non-diacritic char
            var stackUpwardCounter = 0
            var stackDownwardCounter = 0
            for (charIndex in 0 until posXbuffer.size) {
                if (charIndex > 0) {
                    // nonDiacriticCounter allows multiple diacritics

                    val thisChar = textBuffer[charIndex]
                    if (glyphProps[thisChar] == null && errorOnUnknownChar) {
                        val errorGlyphSB = StringBuilder()
                        Character.toChars(thisChar).forEach { errorGlyphSB.append(it) }

                        throw InternalError("No GlyphProps for char '$errorGlyphSB' " +
                                "(U+${thisChar.toString(16).toUpperCase()}: ${Character.getName(thisChar)})")
                    }
                    val thisProp = glyphProps[thisChar] ?: nullProp
                    val lastNonDiacriticChar = textBuffer[nonDiacriticCounter]
                    val itsProp = glyphProps[lastNonDiacriticChar] ?: nullProp


                    //println("char: $thisChar; properties: $thisProp")


                    val alignmentOffset = when (thisProp.alignWhere) {
                        GlyphProps.LEFT -> 0
                        GlyphProps.RIGHT -> thisProp.width - W_VAR_INIT
                        GlyphProps.CENTRE -> Math.floor((thisProp.width - W_VAR_INIT) / 2.0).toInt()
                        else -> throw InternalError("Unsupported alignment: ${thisProp.alignWhere}")
                    }

                    if (!thisProp.writeOnTop) {
                        posXbuffer[charIndex] = posXbuffer[nonDiacriticCounter] + itsProp.width + alignmentOffset + interchar
                        nonDiacriticCounter = charIndex
                        stackUpwardCounter = 0
                        stackDownwardCounter = 0
                    }
                    else {
                        // set X pos according to alignment information
                        posXbuffer[charIndex] = when (thisProp.alignWhere) {
                            GlyphProps.LEFT -> posXbuffer[nonDiacriticCounter]
                            GlyphProps.RIGHT -> {
                                posXbuffer[nonDiacriticCounter] - (W_VAR_INIT - itsProp.width)
                            }
                            GlyphProps.CENTRE -> {
                                val alignXPos = if (itsProp.alignXPos == 0) itsProp.width.div(2) else itsProp.alignXPos

                                posXbuffer[nonDiacriticCounter] + alignXPos - (W_VAR_INIT - 1).div(2)
                            }
                            else -> throw InternalError("Unsupported alignment: ${thisProp.alignWhere}")
                        }


                        // set Y pos according to diacritics position
                        if (thisProp.diacriticsStackDown) {
                            posYbuffer[charIndex] = H_DIACRITICS * stackDownwardCounter
                            stackDownwardCounter++
                        }
                        else {
                            posYbuffer[charIndex] = -H_DIACRITICS * stackUpwardCounter
                            stackUpwardCounter++
                        }
                    }
                }
            }
        }


        //print("[TerrarumSansBitmap] widthTable for $textBuffer: ")
        //posXbuffer.forEach { print("$it ") }; println()


        originalColour = batch.color.cpy()
        var mainCol = originalColour
        var shadowCol = mainCol.cpy().mul(0.5f,0.5f,0.5f,1f)


        var index = 0
        while (index <= textBuffer.lastIndex) {
            val c = textBuffer[index]
            val sheetID = getSheetType(c)
            val (sheetX, sheetY) = getSheetwisePosition(c)

            //println("[TerrarumSansBitmap] sprite:  $sheetID:${sheetX}x${sheetY}")

            if (isColourCode(c)) {
                if (c == 0x100000) {
                    mainCol = originalColour
                    shadowCol = mainCol.cpy().mul(0.5f,0.5f,0.5f,1f)
                }
                else {
                    mainCol = getColour(c)
                    shadowCol = mainCol.cpy().mul(0.5f, 0.5f, 0.5f, 1f)
                }
            }
            else if (isCharsetOverride(c)) {
                charsetOverride = c - CHARSET_OVERRIDE_NULL
            }
            else if (sheetID == SHEET_HANGUL) {
                val hangulSheet = sheets[SHEET_HANGUL]
                val hIndex = c - 0xAC00

                val indexCho = getHanChosung(hIndex)
                val indexJung = getHanJungseong(hIndex)
                val indexJong = getHanJongseong(hIndex)

                val choRow = getHanInitialRow(hIndex)
                val jungRow = getHanMedialRow(hIndex)
                val jongRow = getHanFinalRow(hIndex)


                if (!noShadow) {
                    batch.color = shadowCol

                    batch.draw(hangulSheet.get(indexCho, choRow  ), x + posXbuffer[index] + 1, y)
                    batch.draw(hangulSheet.get(indexCho, choRow  ), x + posXbuffer[index]    , y + 1.flipY())
                    batch.draw(hangulSheet.get(indexCho, choRow  ), x + posXbuffer[index] + 1, y + 1.flipY())

                    batch.draw(hangulSheet.get(indexJung, jungRow), x + posXbuffer[index] + 1, y)
                    batch.draw(hangulSheet.get(indexJung, jungRow), x + posXbuffer[index]    , y + 1.flipY())
                    batch.draw(hangulSheet.get(indexJung, jungRow), x + posXbuffer[index] + 1, y + 1.flipY())

                    batch.draw(hangulSheet.get(indexJong, jongRow), x + posXbuffer[index] + 1, y)
                    batch.draw(hangulSheet.get(indexJong, jongRow), x + posXbuffer[index]    , y + 1.flipY())
                    batch.draw(hangulSheet.get(indexJong, jongRow), x + posXbuffer[index] + 1, y + 1.flipY())
                }


                batch.color = mainCol
                batch.draw(hangulSheet.get(indexCho, choRow)  , x + posXbuffer[index], y)
                batch.draw(hangulSheet.get(indexJung, jungRow), x + posXbuffer[index], y)
                batch.draw(hangulSheet.get(indexJong, jongRow), x + posXbuffer[index], y)
            }
            else {
                try {

                    val posY = y + posYbuffer[index].flipY() +
                            if (sheetID == SHEET_UNIHAN) // evil exceptions
                                offsetUnihan
                            else if (sheetID == SHEET_CUSTOM_SYM)
                                offsetCustomSym
                            else 0

                    val posX = x + posXbuffer[index]
                    val texture = sheets[sheetID].get(sheetX, sheetY)


                    if (!noShadow) {
                        batch.color = shadowCol
                        batch.draw(texture, posX + 1, posY + 1.flipY())
                        batch.draw(texture, posX    , posY + 1.flipY())
                        batch.draw(texture, posX + 1, posY)
                    }

                    batch.color = mainCol
                    batch.draw(texture, posX, posY)
                }
                catch (noSuchGlyph: ArrayIndexOutOfBoundsException) {
                    batch.color = mainCol
                }
            }


            index += 1
        }

        batch.color = originalColour

        return null
    }


    override fun dispose() {
        super.dispose()

        sheets.forEach { it.dispose() }
    }

    private fun getWidthOfCharSeq(s: CodepointSequence): IntArray {
        val len = IntArray(s.size)
        for (i in 0..s.lastIndex) {
            val chr = s[i]
            val ctype = getSheetType(s[i])

            if (variableWidthSheets.contains(ctype)) {
                if (!glyphProps.containsKey(chr)) {
                    System.err.println("[TerrarumSansBitmap] no width data for glyph number ${Integer.toHexString(chr.toInt()).toUpperCase()}")
                    len[i] = W_LATIN_WIDE
                }

                val prop = glyphProps[chr] ?: nullProp
                //println("${chr.toInt()} -> $prop")
                len[i] = prop.width * (if (prop.writeOnTop) -1 else 1)
            }
            else if (isColourCode(chr) || isCharsetOverride(chr))
                len[i] = 0
            else if (ctype == SHEET_CJK_PUNCT)
                len[i] = W_ASIAN_PUNCT
            else if (ctype == SHEET_HANGUL)
                len[i] = W_HANGUL
            else if (ctype == SHEET_KANA)
                len[i] = W_KANA
            else if (unihanWidthSheets.contains(ctype))
                len[i] = W_UNIHAN
            else if (ctype == SHEET_CUSTOM_SYM)
                len[i] = SIZE_CUSTOM_SYM
            else
                len[i] = W_LATIN_WIDE

            if (scale > 1) len[i] *= scale

            if (i < s.lastIndex) len[i] += interchar
        }
        return len
    }

    private fun getSheetType(c: Int): Int {
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
        else
            return SHEET_UNKNOWN
        // fixed width
        // fallback
    }

    private fun getSheetwisePosition(ch: Int): IntArray {
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
            else -> {
                sheetX = ch % 16
                sheetY = ch / 16
            }
        }

        return intArrayOf(sheetX, sheetY)
    }

    fun buildWidthTable(pixmap: Pixmap, codeRange: IntRange, cols: Int = 16) {
        val binaryCodeOffset = W_VAR_INIT

        val cellW = W_VAR_INIT + 1
        val cellH = H

        for (code in codeRange) {

            val cellX = ((code - codeRange.start) % cols) * cellW
            val cellY = ((code - codeRange.start) / cols) * cellH

            val codeStartX = cellX + binaryCodeOffset
            val codeStartY = cellY
            val tagStartY = codeStartY + 11

            var width = 0
            var tags = 0

            for (y in 0..3) {
                // if ALPHA is not zero, assume it's 1
                if (pixmap.getPixel(codeStartX, codeStartY + y).and(0xFF) != 0) {
                    width = width or (1 shl y)
                }
            }

            for (y in 0..8) {
                // if ALPHA is not zero, assume it's 1
                if (pixmap.getPixel(codeStartX, tagStartY + y).and(0xFF) != 0) {
                    tags = tags or (1 shl y)
                }
            }

            //println("$code: Width $width, tags $tags")

            /*val isDiacritics = pixmap.getPixel(codeStartX, codeStartY + H - 1).and(0xFF) != 0
            if (isDiacritics)
                glyphWidth = -glyphWidth*/

            glyphProps[code] = GlyphProps(width, tags)
        }
    }

    fun buildWidthTableFixed() {
        // fixed-width props
        this.codeRange[SHEET_CJK_PUNCT].forEach { glyphProps[it] = GlyphProps(W_ASIAN_PUNCT, 0) }
        this.codeRange[SHEET_CUSTOM_SYM].forEach { glyphProps[it] = GlyphProps(20, 0) }
        this.codeRange[SHEET_FW_UNI].forEach { glyphProps[it] = GlyphProps(W_UNIHAN, 0) }
        this.codeRange[SHEET_HANGUL].forEach { glyphProps[it] = GlyphProps(W_HANGUL, 0) }
        this.codeRange[SHEET_KANA].forEach { glyphProps[it] = GlyphProps(W_KANA, 0) }
        this.codeRange[SHEET_RUNIC].forEach { glyphProps[it] = GlyphProps(9, 0) }
        this.codeRange[SHEET_UNIHAN].forEach { glyphProps[it] = GlyphProps(W_UNIHAN, 0) }
        (0xD800..0xDFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }
        (0x100000..0x10FFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }
        (0xFFFF8..0xFFFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }


        // manually build width table of Kana Supplements
        (0x31F0..0x31FF).forEach { glyphProps[it] = GlyphProps(W_KANA, 0) }
        (0x1B000..0x1B001).forEach { glyphProps[it] = GlyphProps(W_KANA, 0) }

        // manually add width of one orphan insular letter
        // WARNING: glyphs in 0xA770..0xA778 has invalid data, further care is required
        glyphProps[0x1D79] = GlyphProps(9, 0)
    }

    private val glyphLayout = GlyphLayout()

    fun getWidth(text: String): Int {
        return getWidthOfCharSeq(text.toCodePoints()).sum()
    }


    /** UTF-16 to ArrayList of Int. UTF-16 is because of Java */
    private fun CharSequence.toCodePoints(): CodepointSequence {
        val seq = ArrayList<Int>()

        var i = 0
        while (i < this.length) {
            val c = this[i]

            if (i < this.lastIndex && c.isHighSurrogate()) {
                val cNext = this[i + 1]

                if (!cNext.isLowSurrogate())
                    throw IllegalArgumentException("Malformed UTF-16 String: High surrogate must be paired with low surrogate")

                val H = c
                val L = cNext

                seq.add(Character.toCodePoint(H, L))

                i++ // skip next char (guaranteed to be Low Surrogate)
            }
            else {
                seq.add(c.toInt())
            }

            i++
        }

        return seq
    }

    /** As CharSequence is just an Interface, copy-pasting the code would be the fastest way */
    private fun String.toCodePoints(): CodepointSequence {
        val seq = ArrayList<Int>()

        var i = 0
        while (i < this.length) {
            val c = this[i]

            if (i < this.lastIndex) {
                if (c.isHighSurrogate()) {
                    val cNext = this[i + 1]

                    if (!cNext.isLowSurrogate())
                        throw IllegalArgumentException("Malformed UTF-16 String: High surrogate must be paired with low surrogate")

                    val H = c
                    val L = cNext

                    seq.add(Character.toCodePoint(H, L))

                    i++ // skip next char (guaranteed to be Low Surrogate)
                }
            }
            else {
                seq.add(c.toInt())
            }
        }

        return seq
    }

    /** High surrogate comes before the low. */
    private fun Char.isHighSurrogate() = (this.toInt() in 0xD800..0xDBFF)
    /** CodePoint = 0x10000 + (H - 0xD800) * 0x400 + (L - 0xDC00) */
    private fun Char.isLowSurrogate() = (this.toInt() in 0xDC00..0xDFFF)


    var interchar = 0
    var scale = 1
        set(value) {
            if (value > 0) field = value
            else throw IllegalArgumentException("Font scale cannot be zero or negative (input: $value)")
        }

    fun toColorCode(argb4444: Int): String = GameFontBase.toColorCode(argb4444)
    fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = GameFontBase.toColorCode(r, g, b, a)
    val noColorCode = toColorCode(0x0000)

    val charsetOverrideNormal = Character.toChars(CHARSET_OVERRIDE_NULL)
    val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG)
    val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR)

    companion object {
        internal val JUNG_COUNT = 21
        internal val JONG_COUNT = 28

        internal val W_ASIAN_PUNCT = 10
        internal val W_HANGUL = 12
        internal val W_KANA = 12
        internal val W_UNIHAN = 16
        internal val W_LATIN_WIDE = 9 // width of regular letters
        internal val W_VAR_INIT = 15 // it assumes width of 15 regardless of the tagged width

        internal val HGAP_VAR = 1

        internal val H = 20
        internal val H_UNIHAN = 16

        internal val H_DIACRITICS = 3

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
        internal val SHEET_INSUAR_VARW =       21

        internal val SHEET_UNKNOWN = 254

        internal val CHARSET_OVERRIDE_NULL = 0xFFFF8
        internal val CHARSET_OVERRIDE_BG_BG = 0xFFFF9
        internal val CHARSET_OVERRIDE_SR_SR = 0xFFFFA


        val charsetOverrideNormal = Character.toChars(CHARSET_OVERRIDE_NULL)
        val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG)
        val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR)
        fun toColorCode(argb4444: Int): String = Character.toChars(0x100000 + argb4444).toColCode()
        fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = toColorCode(a.shl(12) or r.shl(8) or g.shl(4) or b)
        private fun CharArray.toColCode(): String = "${this[0]}${this[1]}"

        val noColorCode = toColorCode(0x0000)
    }

}