/*
 * Terrarum Sans Bitmap
 * 
 * Copyright (c) 2017 Minjae Song (Torvald)
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

package net.torvald.terrarumsansbitmap.slick2d

import net.torvald.terrarumsansbitmap.gdx.GameFontBase
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.JUNG_COUNT
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.JONG_COUNT
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.W_ASIAN_PUNCT
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.W_HANGUL
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.W_KANA
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.W_UNIHAN
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.W_LATIN_WIDE
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.W_VAR_INIT
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.HGAP_VAR
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.H
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.H_UNIHAN
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SIZE_CUSTOM_SYM
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_ASCII_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_HANGUL
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_EXTA_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_EXTB_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_KANA
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_CJK_PUNCT
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_UNIHAN
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_CYRILIC_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_FW_UNI
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_UNI_PUNCT
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_GREEK_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_THAI_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_HAYEREN_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_KARTULI_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_IPA_VARW
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_CUSTOM_SYM
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_UNKNOWN
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_RUNIC
import net.torvald.terrarumsansbitmap.gdx.GameFontBase.Companion.SHEET_LATIN_EXT_ADD_VARW
import org.newdawn.slick.Color
import org.newdawn.slick.Font
import org.newdawn.slick.Image
import org.newdawn.slick.SpriteSheet
import org.newdawn.slick.opengl.Texture
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.GZIPInputStream

/**
 * LibGDX->Slick2D back-port of Terrarum Sans Bitmap implementation
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
 * @param noShadow Self-explanatory
 * @param flipY If you have Y-down coord system implemented on your GDX (e.g. legacy codebase), set this to ```true``` so that the shadow won't be upside-down. For glyph getting upside-down, set ```TextureRegionPack.globalFlipY = true```.
 *
 * Created by minjaesong on 2017-06-15.
 */
class GameFontBase(fontDir: String, val noShadow: Boolean = false) : Font {

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

    private fun isHangul(c: Char) = c.toInt() in codeRange[SHEET_HANGUL]
    private fun isAscii(c: Char) = c.toInt() in codeRange[SHEET_ASCII_VARW]
    private fun isRunic(c: Char) = c.toInt() in codeRange[SHEET_RUNIC]
    private fun isExtA(c: Char) = c.toInt() in codeRange[SHEET_EXTA_VARW]
    private fun isExtB(c: Char) = c.toInt() in codeRange[SHEET_EXTB_VARW]
    private fun isKana(c: Char) = c.toInt() in codeRange[SHEET_KANA]
    private fun isCJKPunct(c: Char) = c.toInt() in codeRange[SHEET_CJK_PUNCT]
    private fun isUniHan(c: Char) = c.toInt() in codeRange[SHEET_UNIHAN]
    private fun isCyrilic(c: Char) = c.toInt() in codeRange[SHEET_CYRILIC_VARW]
    private fun isFullwidthUni(c: Char) = c.toInt() in codeRange[SHEET_FW_UNI]
    private fun isUniPunct(c: Char) = c.toInt() in codeRange[SHEET_UNI_PUNCT]
    private fun isGreek(c: Char) = c.toInt() in codeRange[SHEET_GREEK_VARW]
    private fun isThai(c: Char) = c.toInt() in codeRange[SHEET_THAI_VARW]
    private fun isDiacritics(c: Char) = c.toInt() in 0xE34..0xE3A
            || c.toInt() in 0xE47..0xE4E
            || c.toInt() == 0xE31
    private fun isCustomSym(c: Char) = c.toInt() in codeRange[SHEET_CUSTOM_SYM]
    private fun isArmenian(c: Char) = c.toInt() in codeRange[SHEET_HAYEREN_VARW]
    private fun isKartvelian(c: Char) = c.toInt() in codeRange[SHEET_KARTULI_VARW]
    private fun isIPA(c: Char) = c.toInt() in codeRange[SHEET_IPA_VARW]
    private fun isColourCodeHigh(c: Char) = c.toInt() in 0b110110_1111000000..0b110110_1111111111 // only works with JVM (which uses UTF-16 internally)
    private fun isColourCodeLow(c: Char) = c.toInt() in 0b110111_0000000000..0b110111_1111111111 // only works with JVM (which uses UTF-16 internally)
    private fun isLatinExtAdd(c: Char) = c.toInt() in 0x1E00..0x1EFF




    private fun extAindexX(c: Char) = (c.toInt() - 0x100) % 16
    private fun extAindexY(c: Char) = (c.toInt() - 0x100) / 16

    private fun extBindexX(c: Char) = (c.toInt() - 0x180) % 16
    private fun extBindexY(c: Char) = (c.toInt() - 0x180) / 16

    private fun runicIndexX(c: Char) = (c.toInt() - 0x16A0) % 16
    private fun runicIndexY(c: Char) = (c.toInt() - 0x16A0) / 16

    private fun kanaIndexX(c: Char) = (c.toInt() - 0x3040) % 16
    private fun kanaIndexY(c: Char) = (c.toInt() - 0x3040) / 16

    private fun cjkPunctIndexX(c: Char) = (c.toInt() - 0x3000) % 16
    private fun cjkPunctIndexY(c: Char) = (c.toInt() - 0x3000) / 16

    private fun cyrilicIndexX(c: Char) = (c.toInt() - 0x400) % 16
    private fun cyrilicIndexY(c: Char) = (c.toInt() - 0x400) / 16

    private fun fullwidthUniIndexX(c: Char) = (c.toInt() - 0xFF00) % 16
    private fun fullwidthUniIndexY(c: Char) = (c.toInt() - 0xFF00) / 16

    private fun uniPunctIndexX(c: Char) = (c.toInt() - 0x2000) % 16
    private fun uniPunctIndexY(c: Char) = (c.toInt() - 0x2000) / 16

    private fun unihanIndexX(c: Char) = (c.toInt() - 0x3400) % 256
    private fun unihanIndexY(c: Char) = (c.toInt() - 0x3400) / 256

    private fun greekIndexX(c: Char) = (c.toInt() - 0x370) % 16
    private fun greekIndexY(c: Char) = (c.toInt() - 0x370) / 16

    private fun thaiIndexX(c: Char) = (c.toInt() - 0xE00) % 16
    private fun thaiIndexY(c: Char) = (c.toInt() - 0xE00) / 16

    private fun symbolIndexX(c: Char) = (c.toInt() - 0xE000) % 16
    private fun symbolIndexY(c: Char) = (c.toInt() - 0xE000) / 16

    private fun armenianIndexX(c: Char) = (c.toInt() - 0x530) % 16
    private fun armenianIndexY(c: Char) = (c.toInt() - 0x530) / 16

    private fun kartvelianIndexX(c: Char) = (c.toInt() - 0x10D0) % 16
    private fun kartvelianIndexY(c: Char) = (c.toInt() - 0x10D0) / 16

    private fun ipaIndexX(c: Char) = (c.toInt() - 0x250) % 16
    private fun ipaIndexY(c: Char) = (c.toInt() - 0x250) / 16

    private fun latinExtAddX(c: Char) = (c.toInt() - 0x1E00) % 16
    private fun latinExtAddY(c: Char) = (c.toInt() - 0x1E00) / 16

    private fun getColour(charHigh: Char, charLow: Char): Color { // input: 0x10ARGB, out: RGBA8888
        val codePoint = Character.toCodePoint(charHigh, charLow)

        if (colourBuffer.containsKey(codePoint))
            return colourBuffer[codePoint]!!

        val r = codePoint.and(0xF000).ushr(12)
        val g = codePoint.and(0x0F00).ushr(8)
        val b = codePoint.and(0x00F0).ushr(4)
        val a = codePoint.and(0x000F)

        val col = Color(a.shl(28) or a.shl(24) or r.shl(20) or r.shl(16) or g.shl(12) or g.shl(8) or b.shl(4) or b)


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
            SHEET_UNI_PUNCT,
            SHEET_GREEK_VARW,
            SHEET_THAI_VARW,
            SHEET_HAYEREN_VARW,
            SHEET_KARTULI_VARW,
            SHEET_IPA_VARW,
            SHEET_LATIN_EXT_ADD_VARW
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
            "puae000-e0ff.tga"
    )
    private val cyrilic_bg = "cyrilic_bulgarian_variable.tga"
    private val cyrilic_sr = "cyrilic_serbian_variable.tga"
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
            0xE000..0xE0FF
    )
    private val glyphWidths: HashMap<Int, Int> = HashMap() // if the value is negative, it's diacritics
    private val sheets: Array<SpriteSheet>


    init {
        val sheetsPack = ArrayList<SpriteSheet>()

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


            val image: Image


            // unpack gz if applicable
            if (it.endsWith(".gz")) {
                val gzi = GZIPInputStream(FileInputStream(fontParentDir + it))
                val wholeFile = gzi.readBytes()
                gzi.close()
                val fos = BufferedOutputStream(FileOutputStream("tmp_wenquanyi.tga"))
                fos.write(wholeFile)
                fos.flush()
                fos.close()

                image = Image("tmp_wenquanyi.tga")

                File("tmp_wenquanyi.tga").delete()
            }
            else {
                image = Image(fontParentDir + it)
            }

            val texture = image.texture

            if (isVariable) {
                println("[TerrarumSansBitmap] loading texture $it [VARIABLE]")
                buildWidthTable(texture, codeRange[index], 16)
            }
            else {
                println("[TerrarumSansBitmap] loading texture $it")
            }

            val texRegPack = if (isVariable) {
                SpriteSheet(image, W_VAR_INIT, H - 1, HGAP_VAR)
            }
            else if (index == SHEET_UNIHAN) {
                SpriteSheet(image, W_UNIHAN, H_UNIHAN) // the only exception that is height is 16
            }
            // below they all have height of 20 'H'
            else if (index == SHEET_FW_UNI) {
                SpriteSheet(image, W_UNIHAN, H)
            }
            else if (index == SHEET_CJK_PUNCT) {
                SpriteSheet(image, W_ASIAN_PUNCT, H)
            }
            else if (index == SHEET_KANA) {
                SpriteSheet(image, W_KANA, H)
            }
            else if (index == SHEET_HANGUL) {
                SpriteSheet(image, W_HANGUL, H)
            }
            else if (index == SHEET_CUSTOM_SYM) {
                SpriteSheet(image, SIZE_CUSTOM_SYM, SIZE_CUSTOM_SYM) // TODO variable
            }
            else if (index == SHEET_RUNIC) {
                SpriteSheet(image, W_LATIN_WIDE, H)
            }
            else throw IllegalArgumentException("[TerrarumSansBitmap] Unknown sheet index: $index")


            sheetsPack.add(texRegPack)
        }

        sheets = sheetsPack.toTypedArray()
    }

    private var localeBuffer = ""

    fun reload(locale: String) {
        if (!localeBuffer.startsWith("ru") && locale.startsWith("ru")) {
            val image = Image(fontParentDir + fileList[SHEET_CYRILIC_VARW])
            sheets[SHEET_CYRILIC_VARW].destroy()
            sheets[SHEET_CYRILIC_VARW] = SpriteSheet(image, W_VAR_INIT, H, HGAP_VAR, 0)
        }
        else if (!localeBuffer.startsWith("bg") && locale.startsWith("bg")) {
            val image = Image(fontParentDir + cyrilic_bg)
            sheets[SHEET_CYRILIC_VARW].destroy()
            sheets[SHEET_CYRILIC_VARW] = SpriteSheet(image, W_VAR_INIT, H, HGAP_VAR, 0)
        }
        else if (!localeBuffer.startsWith("sr") && locale.startsWith("sr")) {
            val image = Image(fontParentDir + cyrilic_sr)
            sheets[SHEET_CYRILIC_VARW].destroy()
            sheets[SHEET_CYRILIC_VARW] = SpriteSheet(image, W_VAR_INIT, H, HGAP_VAR, 0)
        }

        localeBuffer = locale
    }

    override fun getLineHeight(): Int = H
    override fun getHeight(p0: String) = lineHeight




    private val offsetUnihan = (H - H_UNIHAN) / 2
    private val offsetCustomSym = (H - SIZE_CUSTOM_SYM) / 2

    private var textBuffer: CharSequence = ""
    private var textBWidth = intArrayOf() // absolute posX of glyphs from print-origin
    private var textBGSize = intArrayOf() // width of each glyph

    override fun drawString(x: Float, y: Float, str: String) {
        drawString(x, y, str, Color.white)
    }

    override fun drawString(p0: Float, p1: Float, p2: String?, p3: Color?, p4: Int, p5: Int) {
        throw UnsupportedOperationException()
    }

    override fun drawString(x: Float, y: Float, str: String, color: Color) {
        // always draw at integer position; this is bitmap font after all
        val x = Math.round(x).toFloat()
        val y = Math.round(y).toFloat()


        if (textBuffer != str) {
            textBuffer = str
            val widths = getWidthOfCharSeq(str)

            textBGSize = widths

            textBWidth = IntArray(str.length, { charIndex ->
                if (charIndex == 0)
                    0
                else {
                    var acc = 0
                    (0..charIndex - 1).forEach { acc += maxOf(0, widths[it]) } // don't accumulate diacrtics (which has negative value)
                    /*return*/acc
                }
            })
        }


        //print("[TerrarumSansBitmap] widthTable for $textBuffer: ")
        //textBWidth.forEach { print("$it ") }; println()


        var mainCol = color
        var shadowCol = color.darker(0.5f)


        var index = 0
        while (index <= textBuffer.lastIndex) {
            val c = textBuffer[index]
            val sheetID = getSheetType(c)
            val sheetXY = getSheetwisePosition(c)

            //println("[TerrarumSansBitmap] sprite:  $sheetID:${sheetXY[0]}x${sheetXY[1]}")

            if (isColourCodeHigh(c)) {
                val cchigh = c
                val cclow = textBuffer[index + 1]

                if (Character.toCodePoint(cchigh, cclow) == 0x100000) {
                    mainCol = color
                    shadowCol = color.darker(0.5f)
                }
                else {
                    mainCol = getColour(cchigh, cclow)
                    shadowCol = mainCol.darker(0.5f)
                }

                index += 1
            }
            else if (isColourCodeLow(c)) {
                throw Error("Unexpected encounter of ColourCodeLow at index $index of String '$textBuffer'")
            }
            else if (sheetID == SHEET_HANGUL) {
                val hangulSheet = sheets[SHEET_HANGUL]
                val hIndex = c.toInt() - 0xAC00

                val indexCho = getHanChosung(hIndex)
                val indexJung = getHanJungseong(hIndex)
                val indexJong = getHanJongseong(hIndex)

                val choRow = getHanInitialRow(hIndex)
                val jungRow = getHanMedialRow(hIndex)
                val jongRow = getHanFinalRow(hIndex)


                if (!noShadow) {
                    hangulSheet.getSubImage(indexCho, choRow  ).draw(x + textBWidth[index] + 1, y, shadowCol)
                    hangulSheet.getSubImage(indexCho, choRow  ).draw(x + textBWidth[index]    , y, shadowCol)
                    hangulSheet.getSubImage(indexCho, choRow  ).draw(x + textBWidth[index] + 1, y, shadowCol)

                    hangulSheet.getSubImage(indexJung, jungRow).draw(x + textBWidth[index] + 1, y, shadowCol)
                    hangulSheet.getSubImage(indexJung, jungRow).draw(x + textBWidth[index]    , y, shadowCol)
                    hangulSheet.getSubImage(indexJung, jungRow).draw(x + textBWidth[index] + 1, y, shadowCol)

                    hangulSheet.getSubImage(indexJong, jongRow).draw(x + textBWidth[index] + 1, y, shadowCol)
                    hangulSheet.getSubImage(indexJong, jongRow).draw(x + textBWidth[index]    , y, shadowCol)
                    hangulSheet.getSubImage(indexJong, jongRow).draw(x + textBWidth[index] + 1, y, shadowCol)
                }


                hangulSheet.getSubImage(indexCho, choRow  ).draw(x + textBWidth[index], y, mainCol)
                hangulSheet.getSubImage(indexJung, jungRow).draw(x + textBWidth[index], y, mainCol)
                hangulSheet.getSubImage(indexJong, jongRow).draw(x + textBWidth[index], y, mainCol)
            }
            else {
                try {
                    val offset = if (!isDiacritics(c)) 0 else {
                        if (index > 0) // LIMITATION: does not support double (or more) diacritics properly
                            (textBGSize[index] - textBGSize[index - 1]) / 2
                        else
                            textBGSize[index]
                    }

                    if (!noShadow) {
                        sheets[sheetID].getSubImage(sheetXY[0], sheetXY[1]).draw(
                                x + textBWidth[index] + 1 + offset,
                                y + (if (sheetID == SHEET_UNIHAN) // evil exceptions
                                    offsetUnihan
                                else if (sheetID == SHEET_CUSTOM_SYM)
                                    offsetCustomSym
                                else
                                    0),
                                shadowCol
                        )
                        sheets[sheetID].getSubImage(sheetXY[0], sheetXY[1]).draw(
                                x + textBWidth[index] + offset,
                                y + (if (sheetID == SHEET_UNIHAN) // evil exceptions
                                    offsetUnihan + 1
                                else if (sheetID == SHEET_CUSTOM_SYM)
                                    offsetCustomSym + 1
                                else
                                    1),
                                shadowCol
                        )
                        sheets[sheetID].getSubImage(sheetXY[0], sheetXY[1]).draw(
                                x + textBWidth[index] + 1 + offset,
                                y + (if (sheetID == SHEET_UNIHAN) // evil exceptions
                                    offsetUnihan + 1
                                else if (sheetID == SHEET_CUSTOM_SYM)
                                    offsetCustomSym + 1
                                else
                                    1),
                                shadowCol
                        )
                    }


                    sheets[sheetID].getSubImage(sheetXY[0], sheetXY[1]).draw(
                            x + textBWidth[index] + offset,
                            y + if (sheetID == SHEET_UNIHAN) // evil exceptions
                                        offsetUnihan
                                    else if (sheetID == SHEET_CUSTOM_SYM)
                                        offsetCustomSym
                                    else 0,
                            mainCol
                    )
                }
                catch (noSuchGlyph: ArrayIndexOutOfBoundsException) {
                }
            }


            index += 1
        }

    }


    fun dispose() {
        sheets.forEach { it.destroy() }
    }

    private fun getWidthOfCharSeq(s: CharSequence): IntArray {
        val len = IntArray(s.length)
        for (i in 0..s.lastIndex) {
            val chr = s[i]
            val ctype = getSheetType(s[i])

            if (variableWidthSheets.contains(ctype)) {
                if (!glyphWidths.containsKey(chr.toInt())) {
                    println("[TerrarumSansBitmap] no width data for glyph number ${Integer.toHexString(chr.toInt()).toUpperCase()}")
                    len[i] = W_LATIN_WIDE
                }

                len[i] = glyphWidths[chr.toInt()]!!
            }
            else if (isColourCodeHigh(chr) || isColourCodeLow(chr))
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

    private fun getSheetType(c: Char): Int {
        if (isHangul(c))
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
            return SHEET_UNI_PUNCT
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
        else
            return SHEET_UNKNOWN
        // fixed width
        // fallback
    }

    private fun getSheetwisePosition(ch: Char): IntArray {
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
            SHEET_UNI_PUNCT -> {
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
            else -> {
                sheetX = ch.toInt() % 16
                sheetY = ch.toInt() / 16
            }
        }

        return intArrayOf(sheetX, sheetY)
    }

    fun buildWidthTable(texture: Texture, codeRange: IntRange, cols: Int = 16) {
        val binaryCodeOffset = W_VAR_INIT

        val cellW = W_VAR_INIT + 1
        val cellH = H

        for (code in codeRange) {

            val cellX = ((code - codeRange.start) % cols) * cellW
            val cellY = ((code - codeRange.start) / cols) * cellH

            val codeStartX = cellX + binaryCodeOffset
            val codeStartY = cellY

            var glyphWidth = 0

            for (downCtr in 0..3) {
                // if ALPHA is not zero, assume it's 1
                if (texture.textureData[4 * (codeStartX + (codeStartY + downCtr) * texture.textureWidth) + 3] != 0.toByte()) {
                    glyphWidth = glyphWidth or (1 shl downCtr)
                }
            }

            val isDiacritics = texture.textureData[4 * (codeStartX + (codeStartY + H - 1) * texture.textureWidth) + 3] != 0.toByte()
            if (isDiacritics)
                glyphWidth = -glyphWidth

            glyphWidths[code] = glyphWidth
        }
    }


    override fun getWidth(text: String): Int {
        return getWidthOfCharSeq(text).sum()
    }



    var interchar = 0
    var scale = 1
        set(value) {
            if (value > 0) field = value
            else throw IllegalArgumentException("Font scale cannot be zero or negative (input: $value)")
        }

    fun toColorCode(rgba4444: Int): String = GameFontBase.toColorCode(rgba4444)
    fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = toColorCode(r.shl(12) or g.shl(8) or b.shl(4) or a)
    val noColorCode = toColorCode(0x0000)

    companion object {
        fun toColorCode(rgba4444: Int): String = Character.toChars(0x100000 + rgba4444).toColCode()
        fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = toColorCode(r.shl(12) or g.shl(8) or b.shl(4) or a)
        private fun CharArray.toColCode(): String = "${this[0]}${this[1]}"

        val noColorCode = toColorCode(0x0000)
    }
}