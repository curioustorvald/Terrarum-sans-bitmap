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
import com.badlogic.gdx.utils.GdxRuntimeException
import net.torvald.terrarumsansbitmap.GlyphProps
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import kotlin.NullPointerException
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
 * @param noShadow Self-explanatory
 * @param flipY If you have Y-down coord system implemented on your GDX (e.g. legacy codebase), set this to ```true```
 * so that the shadow won't be upside-down. For glyph getting upside-down, set ```TextureRegionPack.globalFlipY = true```.
 *
 * Created by minjaesong on 2017-06-15.
 */
class GameFontBase(fontDir: String, val noShadow: Boolean = false, val flipY: Boolean = false, val minFilter: Texture.TextureFilter = Texture.TextureFilter.Nearest, val magFilter: Texture.TextureFilter = Texture.TextureFilter.Nearest, var errorOnUnknownChar: Boolean = false, val textCacheSize: Int = 256, val debug: Boolean = false) : BitmapFont() {

    // Hangul Implementation Specific //

    private fun getWanseongHanChosung(hanIndex: Int) = hanIndex / (JUNG_COUNT * JONG_COUNT)
    private fun getWanseongHanJungseong(hanIndex: Int) = hanIndex / JONG_COUNT % JUNG_COUNT
    private fun getWanseongHanJongseong(hanIndex: Int) = hanIndex % JONG_COUNT

    private val jungseongWide: Array<Int> = arrayOf(9,13,14,18,19,34,35,39,45,51,53,54,62,64,66,80,83)
    private val jungseongComplex: Array<Int> = arrayOf(10,11,12,15,16,17,20) + (22..33).toList() + arrayOf(36,37,38) + (40..44).toList() + arrayOf(46,47,48,49,50,52) + (55..60).toList() + arrayOf(63,65) + (67..79).toList() + arrayOf(81,82) + (84..93).toList()

    private fun isJungseongWide(hanIndex: Int) = jungseongWide.binarySearch(hanIndex) >= 0
    private fun isJungseongComplex(hanIndex: Int) = jungseongComplex.binarySearch(hanIndex) >= 0

    /**
     * @param i Initial (Chosung)
     * @param p Peak (Jungseong)
     * @param f Final (Jongseong)
     */
    private fun getHanInitialRow(i: Int, p: Int, f: Int): Int {
        val ret =
                if (isJungseongWide(p))         2
                else if (isJungseongComplex(p)) 4
                else 0

        return if (f == 0) ret else ret + 1
    }

    private fun getHanMedialRow(i: Int, p: Int, f: Int) = if (f == 0) 6 else 7

    private fun getHanFinalRow(i: Int, p: Int, f: Int): Int {

        return if (isJungseongWide(p))
            8
        else
            9
    }

    private fun isHangulChosung(c: CodePoint) = c in (0x1100..0x115F) || c in (0xA960..0xA97F)
    private fun isHangulJungseong(c: CodePoint) = c in (0x1160..0x11A7) || c in (0xD7B0..0xD7C6)
    private fun isHangulJongseong(c: CodePoint) = c in (0x11A8..0x11FF) || c in (0xD7CB..0xD7FB)

    private fun toHangulChosungIndex(c: CodePoint) =
            if (!isHangulChosung(c)) throw IllegalArgumentException("This Hangul sequence does not begin with Chosung (${c.toHex()})")
            else if (c in 0x1100..0x115F) c - 0x1100
            else c - 0xA960 + 96
    private fun toHangulJungseongIndex(c: CodePoint) =
            if (!isHangulJungseong(c)) 0
            else if (c in 0x1160..0x11A7) c - 0x1160
            else c - 0xD7B0 + 72
    private fun toHangulJongseongIndex(c: CodePoint) =
            if (!isHangulJongseong(c)) 0
            else if (c in 0x11A8..0x11FF) c - 0x11A8 + 1
            else c - 0xD7CB + 88 + 1

    /**
     * X-position in the spritesheet
     *
     * @param iCP Code point for Initial (Chosung)
     * @param pCP Code point for Peak (Jungseong)
     * @param fCP Code point for Final (Jongseong
     */
    private fun toHangulIndex(iCP: CodePoint, pCP: CodePoint, fCP: CodePoint): IntArray {
        val indexI = toHangulChosungIndex(iCP)
        val indexP = toHangulJungseongIndex(pCP)
        val indexF = toHangulJongseongIndex(fCP)

        return intArrayOf(indexI, indexP, indexF)
    }

    private fun toHangulIndexAndRow(iCP: CodePoint, pCP: CodePoint, fCP: CodePoint): Pair<IntArray, IntArray> {
        val (indexI, indexP, indexF) = toHangulIndex(iCP, pCP, fCP)

        val rowI = getHanInitialRow(indexI, indexP, indexF)
        val rowP = getHanMedialRow(indexI, indexP, indexF)
        val rowF = getHanFinalRow(indexI, indexP, indexF)

        return intArrayOf(indexI, indexP, indexF) to intArrayOf(rowI, rowP, rowF)
    }


    // END Hangul //

    private fun isHangul(c: CodePoint) = c in codeRange[SHEET_HANGUL]
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
    private fun isColourCode(c: CodePoint) = c == 0x100000 || c in 0x10F000..0x10FFFF
    private fun isCharsetOverride(c: CodePoint) = c in 0xFFFC0..0xFFFFF
    private fun isCherokee(c: CodePoint) = c in codeRange[SHEET_TSALAGI_VARW]
    private fun isInsular(c: CodePoint) = c == 0x1D79 || c in 0xA779..0xA787
    private fun isNagariBengali(c: CodePoint) = c in codeRange[SHEET_NAGARI_BENGALI_VARW]
    private fun isKartvelianCaps(c: CodePoint) = c in codeRange[SHEET_KARTULI_CAPS_VARW]
    private fun isDiacriticalMarks(c: CodePoint) = c in codeRange[SHEET_DIACRITICAL_MARKS_VARW]
    private fun isPolytonicGreek(c: CodePoint) = c in codeRange[SHEET_GREEK_POLY_VARW]
    private fun isExtC(c: CodePoint) = c in codeRange[SHEET_EXTC_VARW]

    private fun isCaps(c: CodePoint) = Character.isUpperCase(c) || isKartvelianCaps(c)


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

    private fun insularIndexX(c: CodePoint) =
            if (c == 0x1D79) 0 else (c - 0xA770) % 16
    private fun insularIndexY(c: CodePoint) =
            if (c == 0x1D79) 0 else (c - 0xA770) / 16

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

    private val lowHeightLetters = "acegijmnopqrsuvwxyzɱɳʙɾɽʒʂʐʋɹɻɥɟɡɢʛȵɲŋɴʀɕʑçʝxɣχʁʜʍɰʟɨʉɯuʊøɘɵɤəɛœɜɞʌɔæɐɶɑɒɚɝɩɪʅʈʏʞⱥⱦⱱⱳⱴⱶⱷⱸⱺⱻ".toSortedSet()
    /**
     * lowercase AND the height is equal to x-height (e.g. lowercase B, D, F, H, K, L, ... does not count
     */
    private fun Int.isLowHeight() = this.and(0xFFFF).toChar() in lowHeightLetters


    // TODO (val posXbuffer: IntArray, val posYbuffer: IntArray) -> (val linotype: Pixmap)
    private data class ShittyGlyphLayout(val textBuffer: CodepointSequence, val linotype: Texture, val width: Int)

    //private val textCache = HashMap<CharSequence, ShittyGlyphLayout>()

    private data class TextCacheObj(var age: Int, val hash: Long, val glyphLayout: ShittyGlyphLayout?): Comparable<TextCacheObj> {
        fun dispose() {
            glyphLayout?.linotype?.dispose()
        }

        override fun compareTo(other: TextCacheObj): Int {
            return (this.hash - other.hash).sign
        }
    }
    private var textCacheCap = 0

    private val textCache = Array(textCacheSize) { TextCacheObj(-1, -1L, null) }

    /**
     * Insertion sorts the last element fo the textCache
     */
    private fun addToCache(text: CodepointSequence, linotype: Texture, width: Int) {
        // Caching rules:
        // 1. always accept new element.
        // 2. often-called element have higher chance of survival (see: getCache(long))


        if (textCacheCap < textCacheSize) {
            textCache[textCacheCap] = TextCacheObj(0, text.getHash(), ShittyGlyphLayout(text, linotype, width))
            textCacheCap += 1

            // make everybody age
            textCache.forEach {
                it.age += 1
            }
        }
        else {
            // search for an oldest element
            var oldestElemIndex = 0
            var ageOfOldest = textCacheCap
            textCache.forEachIndexed { index, it ->
                // make everybody age
                textCache[index].age += 1

                // mark oldest
                if (it.age > ageOfOldest) {
                    oldestElemIndex = index
                }
            }


            // dispose of the oldest one before overwriting
            textCache[oldestElemIndex].dispose()

            // overwrite oldest one
            textCache[oldestElemIndex] = TextCacheObj(0, text.getHash(), ShittyGlyphLayout(text, linotype, width))
        }

        // sort the list
        textCache.sortBy { it.hash }
    }

    private fun getCache(hash: Long): TextCacheObj? {
        var low = 0
        var high = textCacheCap - 1
        var key = -1


        while (low <= high) {
            val mid = (low + high).ushr(1) // safe from overflows

            val midVal = textCache[mid]

            if (hash > midVal.hash)
                low = mid + 1
            else if (hash < midVal.hash)
                high = mid - 1
            else {
                key = mid
                break // the condition is not enough to break the loop
            }
        }

        if (key < 0)
            return null

        // increment age count (see: addToCache(CodepointSequence, Pixmap, Int))
        textCache[key].age += 1

        return textCache[key]
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
            SHEET_EXTC_VARW
    )
    private val autoShiftDownOnLowercase = arrayOf(
            SHEET_DIACRITICAL_MARKS_VARW
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
            "insular_variable.tga",
            "devanagari_bengali_variable.tga",
            "kartuli_allcaps_variable.tga",
            "diacritical_marks_variable.tga",
            "greek_polytonic_xyswap_variable.tga",
            "latinExtC_variable.tga"
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
            0xF00000..0xF0005F, // SHEET_BULGARIAN_VARW; assign them to PUA
            0xF00060..0xF000BF, // SHEET_SERBIAN_VARW; assign them to PUA
            0x13A0..0x13F5, // SHEET_TSALAGI_VARW
            0xA770..0xA787, // SHEET_INSULAR_VARW; if it work, don't fix it (yet--wait until Latin Extended C)
            0x900..0x9FF, // SHEET_NAGARI_BENGALI_VARW
            0x1C90..0x1CBF, // SHEET_KARTULI_CAPS_VARW
            0x300..0x36F, // SHEET_DIACRITICAL_MARKS_VARW
            0x1F00..0x1FFF, // SHEET_GREEK_POLY_VARW
            0x2C60..0x2C7F // SHEET_EXTC_VARW
    )
    /** Props of all printable Unicode points. */
    private val glyphProps: HashMap<CodePoint, GlyphProps> = HashMap()
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
                throw Error("[TerrarumSansBitmap] font is named as variable on the name but not enlisted as")
            else if (!isVariable1 && isVariable2)
                throw Error("[TerrarumSansBitmap] font is enlisted as variable on the name but not named as")


            var pixmap: Pixmap


            if (isVariable) {
                if (isXYSwapped) {
                    println("[TerrarumSansBitmap] loading texture $it [VARIABLE, XYSWAP]")
                }
                else {
                    println("[TerrarumSansBitmap] loading texture $it [VARIABLE]")
                }
            }
            else {
                if (isXYSwapped) {
                    println("[TerrarumSansBitmap] loading texture $it [XYSWAP]")
                }
                else {
                    println("[TerrarumSansBitmap] loading texture $it")
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
                    System.err.println("[TerrarumSansBitmap] said texture not found, skipping...")

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
                    System.err.println("[TerrarumSansBitmap] said texture not found, skipping...")

                    // if non-ascii chart is missing, replace it with null sheet
                    pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
                    // else, notify by error
                    if (index == 0) {
                        System.err.println("[TerrarumSansBitmap] The ASCII sheet is gone, something is wrong.")
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
                PixmapRegionPack(pixmap, W_HANGUL, H)
            }
            else if (index == SHEET_CUSTOM_SYM) {
                PixmapRegionPack(pixmap, SIZE_CUSTOM_SYM, SIZE_CUSTOM_SYM) // TODO variable
            }
            else if (index == SHEET_RUNIC) {
                PixmapRegionPack(pixmap, W_LATIN_WIDE, H)
            }
            else throw IllegalArgumentException("[TerrarumSansBitmap] Unknown sheet index: $index")

            //texRegPack.texture.setFilter(minFilter, magFilter)

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

    private var flagFirstRun = true
    private var textBuffer = CodepointSequence(256)

    private lateinit var tempLinotype: Texture

    private lateinit var originalColour: Color

    private var nullProp = GlyphProps(15, 0)

    private val pixmapOffsetY = 10

    override fun draw(batch: Batch, charSeq: CharSequence, x: Float, y: Float): GlyphLayout? {
        if (debug)
            println("[TerrarumSansBitmap] max age: $textCacheCap")

        fun Int.flipY() = this * if (flipY) 1 else -1

        // always draw at integer position; this is bitmap font after all
        val x = Math.round(x)
        val y = Math.round(y)

        originalColour = batch.color.cpy()
        val mainColObj = originalColour
        var mainCol: Int = originalColour.toRGBA8888().forceOpaque()

        val charSeqHash = charSeq.toCodePoints().getHash()

        if (charSeq.isNotBlank()) {

            val cacheObj = getCache(charSeqHash)

            if (cacheObj == null || flagFirstRun) {
                textBuffer = charSeq.toCodePoints()

                val (posXbuffer, posYbuffer) = buildWidthAndPosBuffers(textBuffer)

                flagFirstRun = false

                //println("text not in buffer: $charSeq")


                //textBuffer.forEach { print("${it.toHex()} ") }
                //println()


                resetHash(charSeq, x.toFloat(), y.toFloat())

                val linotypePixmap = Pixmap(posXbuffer.last(), H + (pixmapOffsetY * 2), Pixmap.Format.RGBA8888)


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
                            mainCol = originalColour.toRGBA8888().forceOpaque()
                        }
                        else {
                            mainCol = getColour(c)
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

                        linotypePixmap.setColor(mainCol)
                        linotypePixmap.drawPixmap(choTex,  posXbuffer[index], pixmapOffsetY, mainCol)
                        linotypePixmap.drawPixmap(jungTex, posXbuffer[index], pixmapOffsetY, mainCol)
                        linotypePixmap.drawPixmap(jongTex, posXbuffer[index], pixmapOffsetY, mainCol)

                        //batch.color = mainCol
                        //batch.draw(choTex, x + posXbuffer[index].toFloat(), y)
                        //batch.draw(jungTex, x + posXbuffer[index].toFloat(), y)
                        //batch.draw(hangulSheet.get(indexJong, jongRow), x + posXbuffer[index].toFloat(), y)


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

                            linotypePixmap.drawPixmap(texture, posX, posY + pixmapOffsetY, mainCol)

                            //batch.color = mainCol
                            //batch.draw(texture, posX, posY)

                        }
                        catch (noSuchGlyph: ArrayIndexOutOfBoundsException) {
                            //batch.color = mainCol
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



            batch.color = mainColObj

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

        batch.color = originalColour
        return null
    }

    private fun Int.charInfo() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}: ${Character.getName(this)}"


    override fun dispose() {
        super.dispose()
        textCache.forEach { it.dispose() }
        sheets.forEach { it.dispose() }
    }

    /**
     * Used for positioning letters, NOT for the actual width.
     *
     * For actual width, use `getWidth()`
     */
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
            else -> {
                sheetX = ch % 16
                sheetY = ch / 16
            }
        }

        return intArrayOf(sheetX, sheetY)
    }

    fun buildWidthTable(pixmap: Pixmap, codeRange: Iterable<Int>, cols: Int = 16) {
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

            for (y in 0..3) {
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

            //println("$code: Width $width, tags $tags")

            /*val isDiacritics = pixmap.getPixel(codeStartX, codeStartY + H - 1).and(0xFF) != 0
            if (isDiacritics)
                glyphWidth = -glyphWidth*/

            glyphProps[code] = GlyphProps(width, tags)

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
        (0xFFFA0..0xFFFFF).forEach { glyphProps[it] = GlyphProps(0, 0) }


        // manually add width of one orphan insular letter
        // WARNING: glyphs in 0xA770..0xA778 has invalid data, further care is required
        glyphProps[0x1D79] = GlyphProps(9, 0)


        // U+007F is DEL originally, but this font stores bitmap of Replacement Character (U+FFFD)
        // to this position. String replacer will replace U+FFFD into U+007F.
        glyphProps[0x7F] = GlyphProps(15, 0)
    }

    private val glyphLayout = GlyphLayout()

    fun getWidth(text: String) = getWidth(text.toCodePoints())

    fun getWidth(s: CodepointSequence): Int {
        val cacheObj = getCache(s.getHash())

        if (cacheObj != null) {
            return cacheObj.glyphLayout!!.width
        }
        else {
            return buildWidthAndPosBuffers(s).first.last()
        }
    }


    /**
     * posXbuffer's size is greater than the string, last element marks the width of entire string.
     */
    private fun buildWidthAndPosBuffers(str: CodepointSequence): Pair<IntArray, IntArray> {
        val posXbuffer = IntArray(str.size + 1, { 0 })
        val posYbuffer = IntArray(str.size, { 0 })


        var nonDiacriticCounter = 0 // index of last instance of non-diacritic char
        var stackUpwardCounter = 0
        var stackDownwardCounter = 0

        val HALF_VAR_INIT = W_VAR_INIT.minus(1).div(2)

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


                //println("char: ${thisChar.charInfo()}\nproperties: $thisProp")


                val alignmentOffset = when (thisProp.alignWhere) {
                    GlyphProps.ALIGN_LEFT -> 0
                    GlyphProps.ALIGN_RIGHT -> thisProp.width - W_VAR_INIT
                    GlyphProps.ALIGN_CENTRE -> Math.ceil((thisProp.width - W_VAR_INIT) / 2.0).toInt()
                    else -> 0 // implies "diacriticsBeforeGlyph = true"
                }


                if (isHangul(thisChar) && !isHangulChosung(thisChar)) {
                    posXbuffer[charIndex] = if (isHangulChosung(lastNonDiacriticChar))
                        posXbuffer[nonDiacriticCounter]
                    else
                        posXbuffer[nonDiacriticCounter] + W_HANGUL
                }
                else if (!thisProp.writeOnTop) {
                    posXbuffer[charIndex] = when (itsProp.alignWhere) {
                        GlyphProps.ALIGN_RIGHT ->
                            posXbuffer[nonDiacriticCounter] + W_VAR_INIT + alignmentOffset + interchar
                        GlyphProps.ALIGN_CENTRE ->
                            posXbuffer[nonDiacriticCounter] + HALF_VAR_INIT + itsProp.width + alignmentOffset + interchar
                        else ->
                            posXbuffer[nonDiacriticCounter] + itsProp.width + alignmentOffset + interchar

                    }

                    nonDiacriticCounter = charIndex

                    stackUpwardCounter = 0
                    stackDownwardCounter = 0
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
                                    if (thisProp.alignXPos == GlyphProps.DIA_OVERLAY)
                                        posYbuffer[charIndex] += H_OVERLAY_LOWERCASE_SHIFTDOWN
                                    else
                                        posYbuffer[charIndex] += H_STACKUP_LOWERCASE_SHIFTDOWN
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
            val penultCharProp = glyphProps[nonDiacriticCounter]!!
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


    /** Takes input string, do normalisation, and returns sequence of codepoints (Int)
     *
     * UTF-16 to ArrayList of Int. UTF-16 is because of Java
     * Note: CharSequence IS a String. java.lang.String implements CharSequence.
     *
     * Note to Programmer: DO NOT USE CHAR LITERALS, CODE EDITORS WILL CHANGE IT TO SOMETHING ELSE !!
     */
    private fun CharSequence.toCodePoints(): CodepointSequence {
        val seq = ArrayList<Int>()

        var i = 0
        while (i < this.length) {
            val c = this[i]

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

                    seq.add(Character.toCodePoint(H, L))

                    i++ // skip next char (guaranteed to be Low Surrogate)
                }
            }
            // disassemble Hangul Syllables into Initial-Peak-Final encoding
            else if (c in 0xAC00.toChar()..0xD7A3.toChar()) {
                val cInt = c.toInt() - 0xAC00
                val indexCho  = getWanseongHanChosung(cInt)
                val indexJung = getWanseongHanJungseong(cInt)
                val indexJong = getWanseongHanJongseong(cInt) - 1 // no Jongseong will be -1

                // these magic numbers only makes sense if you look at the Unicode chart of Hangul Jamo
                // https://www.unicode.org/charts/PDF/U1100.pdf
                seq.add(0x1100 + indexCho)
                seq.add(0x1161 + indexJung)
                if (indexJong >= 0) seq.add(0x11A8 + indexJong)
            }
            // normalise CJK Compatibility area because fuck them
            else if (c in 0x3300.toChar()..0x33FF.toChar()) {
                seq.add(0x7F) // fuck them
            }
            // rearrange {letter, before-and-after diacritics} as {letter, before-diacritics, after-diacritics}
            // {letter, before-diacritics} part will be dealt with swapping code below
            // DOES NOT WORK if said diacritics has codepoint > 0xFFFF
            else if (i < this.lastIndex && this[i + 1].toInt() <= 0xFFFF &&
                    glyphProps[this[i + 1].toInt()]?.stackWhere == GlyphProps.STACK_BEFORE_N_AFTER) {
                val diacriticsProp = glyphProps[this[i + 1].toInt()]!!
                seq.add(c.toInt())
                seq.add(diacriticsProp.extInfo!![0])
                seq.add(diacriticsProp.extInfo!![1])
                i++
            }
            // U+007F is DEL originally, but this font stores bitmap of Replacement Character (U+FFFD)
            // to this position. This line will replace U+FFFD into U+007F.
            else if (c == 0xFFFD.toChar()) {
                seq.add(0x7F) // 0x7F in used internally to display <??> character
            }
            else {
                seq.add(c.toInt())
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

        val jobQueue = arrayOf(
                1 to 0,
                0 to 1,
                1 to 1
        )

        jobQueue.forEach {
            for (y in 0 until pixmap.height) {
                for (x in 0 until pixmap.width) {
                    val pixel = pixmap.getPixel(x, y) // RGBA8888


                    // in the current version, all colour-coded glyphs are guaranteed
                    // to be opaque
                    if (pixel and 0xFF == 0xFF) {
                        val newPixel = pixmap.getPixel(x + it.first, y + it.second)
                        val newColour = pixel.and(0xFFFFFF00.toInt()) or 0x80

                        if (newPixel and 0xFF == 0) {
                            pixmap.drawPixel(x + it.first, y + it.second, newColour)
                        }
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
        val thisBytes = IntArray(4, { this.ushr(it * 8).and(255) })
        val otherBytes = IntArray(4, { other.ushr(it * 8).and(255) })

        return (thisBytes[0] times256 otherBytes[0]) or
               (thisBytes[1] times256 otherBytes[1]).shl(8) or
               (thisBytes[2] times256 otherBytes[2]).shl(16) or
               (thisBytes[3] times256 otherBytes[3]).shl(24)
    }

    private infix fun Int.times256(other: Int) = multTable255[this][other]

    private val multTable255 = Array(256,
            { left ->
                IntArray(256,
                        { right ->
                            (255f * (left / 255f).times(right / 255f)).roundToInt()
                        }
                )
            }
    )


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

    val charsetOverrideDefault = Character.toChars(CHARSET_OVERRIDE_DEFAULT)
    val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG)
    val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR)

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


    private fun Int.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

    private fun CharSequence.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        this.forEach {
            val it = it.toInt()
            val b1 = it.shl(8).and(255).toByte()
            val b2 = it.and(255).toByte()
            digest.update(b1)
            digest.update(b2)
        }

        return digest.digest()
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
        internal val SHEET_INSUAR_VARW =       21
        internal val SHEET_NAGARI_BENGALI_VARW=22
        internal val SHEET_KARTULI_CAPS_VARW = 23
        internal val SHEET_DIACRITICAL_MARKS_VARW = 24
        internal val SHEET_GREEK_POLY_VARW   = 25
        internal val SHEET_EXTC_VARW =         26

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





        val charsetOverrideDefault = Character.toChars(CHARSET_OVERRIDE_DEFAULT)
        val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG)
        val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR)
        fun toColorCode(argb4444: Int): String = Character.toChars(0x100000 + argb4444).toColCode()
        fun toColorCode(r: Int, g: Int, b: Int, a: Int = 0x0F): String = toColorCode(a.shl(12) or r.shl(8) or g.shl(4) or b)
        private fun CharArray.toColCode(): String = "${this[0]}${this[1]}"

        val noColorCode = toColorCode(0x0000)
    }

}
