package net.torvald.terrarumsansbitmap.slick2d

import net.torvald.terrarum.getPixel
import org.lwjgl.opengl.GL11
import org.newdawn.slick.*
import java.util.*

/**
 * Created by minjaesong on 16-01-27.
 */
open class GameFontBase(val noShadow: Boolean) : Font {

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

    private fun isHangul(c: Char) = c.toInt() in 0xAC00..0xD7A3
    private fun isAscii(c: Char) = c.toInt() in 0x20..0xFF
    private fun isRunic(c: Char) = runicList.contains(c)
    private fun isExtA(c: Char) = c.toInt() in 0x100..0x17F
    private fun isExtB(c: Char) = c.toInt() in 0x180..0x24F
    private fun isKana(c: Char) = c.toInt() in 0x3040..0x30FF
    private fun isCJKPunct(c: Char) = c.toInt() in 0x3000..0x303F
    private fun isUniHan(c: Char) = c.toInt() in 0x3400..0x9FFF
    private fun isCyrilic(c: Char) = c.toInt() in 0x400..0x45F
    private fun isFullwidthUni(c: Char) = c.toInt() in 0xFF00..0xFF1F
    private fun isUniPunct(c: Char) = c.toInt() in 0x2000..0x206F
    private fun isGreek(c: Char) = c.toInt() in 0x370..0x3CE
    private fun isThai(c: Char) = c.toInt() in 0xE00..0xE7F
    private fun isThaiDiacritics(c: Char) = c.toInt() in 0xE34..0xE3A
                                            || c.toInt() in 0xE47..0xE4E
                                            || c.toInt() == 0xE31
    private fun isCustomSym(c: Char) = c.toInt() in 0xE000..0xE0FF



    private fun extAindexX(c: Char) = (c.toInt() - 0x100) % 16
    private fun extAindexY(c: Char) = (c.toInt() - 0x100) / 16

    private fun extBindexX(c: Char) = (c.toInt() - 0x180) % 16
    private fun extBindexY(c: Char) = (c.toInt() - 0x180) / 16

    private fun runicIndexX(c: Char) = runicList.indexOf(c) % 16
    private fun runicIndexY(c: Char) = runicList.indexOf(c) / 16

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

    private val unihanWidthSheets = arrayOf(
            SHEET_UNIHAN,
            SHEET_FW_UNI,
            SHEET_UNIHAN
    )
    private val variableWidthSheets = arrayOf(
            SHEET_ASCII_VARW,
            SHEET_CYRILIC_VARW,
            SHEET_EXTA_VARW,
            SHEET_GREEK_VARW,
            SHEET_EXTB_VARW,
            SHEET_THAI_VARW
    )


    override fun getWidth(s: String) = getWidthSubstr(s, s.length)

    private fun getWidthSubstr(s: String, endIndex: Int): Int {
        var len = 0
        for (i in 0..endIndex - 1) {
            val chr = s[i]
            val ctype = getSheetType(s[i])

            if (variableWidthSheets.contains(ctype)) {
                len += try {
                    glyphWidths[chr.toInt()]!!
                }
                catch (e: kotlin.KotlinNullPointerException) {
                    println("KotlinNullPointerException on glyph number ${Integer.toHexString(chr.toInt()).toUpperCase()}")
                    //System.exit(1)
                    W_LATIN_WIDE // failsafe
                }
            }
            else if (ctype == SHEET_CJK_PUNCT)
                len += W_ASIAN_PUNCT
            else if (ctype == SHEET_HANGUL)
                len += W_HANGUL
            else if (ctype == SHEET_KANA)
                len += W_KANA
            else if (unihanWidthSheets.contains(ctype))
                len += W_UNIHAN
            else if (isThaiDiacritics(s[i]))
                len += 0 // set width of the glyph as -W_LATIN_WIDE
            else if (ctype == SHEET_CUSTOM_SYM)
                len += SIZE_KEYCAP
            else
                len += W_LATIN_WIDE

            if (i < endIndex - 1) len += interchar
        }
        return len * scale
    }

    override fun getHeight(s: String) = H * scale

    override fun getLineHeight() = H * scale

    override fun drawString(x: Float, y: Float, s: String) = drawString(x, y, s, Color.white)

    override fun drawString(x: Float, y: Float, s: String, color: Color) {
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glColorMask(true, true, true, true)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        var thisCol = color

        // hangul fonts first
        //hangulSheet.startUse() // disabling texture binding to make the font coloured
        // JOHAB
        for (i in 0..s.length - 1) {
            val ch = s[i]

            if (ch.isColourCode()) {
                thisCol = colourKey[ch]!!
                continue
            }

            if (isHangul(ch)) {
                val hIndex = ch.toInt() - 0xAC00

                val indexCho = getHanChosung(hIndex)
                val indexJung = getHanJungseong(hIndex)
                val indexJong = getHanJongseong(hIndex)

                val choRow = getHanInitialRow(hIndex)
                val jungRow = getHanMedialRow(hIndex)
                val jongRow = getHanFinalRow(hIndex)

                hangulSheet.getSubImage(indexCho, choRow).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - W_HANGUL).toFloat(),
                        Math.round(y).toFloat(),
                        scale.toFloat(), thisCol, noShadow
                )
                hangulSheet.getSubImage(indexJung, jungRow).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - W_HANGUL).toFloat(),
                        Math.round(y).toFloat(),
                        scale.toFloat(), thisCol, noShadow
                )
                hangulSheet.getSubImage(indexJong, jongRow).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - W_HANGUL).toFloat(),
                        Math.round(y).toFloat(),
                        scale.toFloat(), thisCol, noShadow
                )
            }
        }
        //hangulSheet.endUse()

        // WenQuanYi
        //uniHan.startUse()

        for (i in 0..s.length - 1) {
            val ch = s[i]

            if (ch.isColourCode()) {
                thisCol = colourKey[ch]!!
                continue
            }

            if (isUniHan(ch)) {
                val glyphW = getWidth("" + ch)
                uniHan.getSubImage(unihanIndexX(ch), unihanIndexY(ch)).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),
                        Math.round((H - H_UNIHAN) / 2 + y).toFloat(),
                        scale.toFloat(), thisCol, noShadow
                )
            }
        }

        //uniHan.endUse()

        // regular fonts
        var prevInstance = -1
        for (i in 0..s.length - 1) {
            val ch = s[i]

            if (ch.isColourCode()) {
                thisCol = colourKey[ch]!!
                continue
            }

            if (!isHangul(ch) && !isUniHan(ch)) {

                // if not init, endUse first
                if (prevInstance != -1) {
                    //sheetKey[prevInstance].endUse()
                }
                //sheetKey[getSheetType(ch)].startUse()
                prevInstance = getSheetType(ch)

                val sheetX: Int
                val sheetY: Int
                when (prevInstance) {
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
                    else -> {
                        sheetX = ch.toInt() % 16
                        sheetY = ch.toInt() / 16
                    }
                }

                val glyphW = getWidth("" + ch)
                try {
                    sheetKey[prevInstance]!!.getSubImage(sheetX, sheetY).drawWithShadow(
                            Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),

                            // to deal with the height difference of the sheets
                            Math.round(y).toFloat() +
                            (if (prevInstance == SHEET_CUSTOM_SYM) (H - SIZE_KEYCAP) / 2 // completely legit height adjustment
                            else 0).toFloat(),

                            scale.toFloat(), thisCol, noShadow
                    )
                }
                catch (e: ArrayIndexOutOfBoundsException) {
                    // character that does not exist in the sheet. No render, pass.
                }
                catch (e1: RuntimeException) {
                    // System.err.println("[GameFontBase] RuntimeException raised while processing character '$ch' (U+${Integer.toHexString(ch.toInt()).toUpperCase()})")
                    // e1.printStackTrack()
                }
            }

        }
        if (prevInstance != -1) {
            //sheetKey[prevInstance].endUse()
        }

        GL11.glEnd()
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
        else
            return SHEET_UNKNOWN// fixed width punctuations
        // fixed width
        // fallback
    }

    /**
     * Draw part of a string to the screen. Note that this will still position the text as though
     * it's part of the bigger string.
     * @param x
     * *
     * @param y
     * *
     * @param s
     * *
     * @param color
     * *
     * @param startIndex
     * *
     * @param endIndex
     */
    override fun drawString(x: Float, y: Float, s: String, color: Color, startIndex: Int, endIndex: Int) {
        val unprintedHead = s.substring(0, startIndex)
        val printedBody = s.substring(startIndex, endIndex)
        val xoff = getWidth(unprintedHead)
        drawString(x + xoff, y, printedBody, color)
    }

    fun Char.isColourCode() = colourKey.containsKey(this)

    fun buildWidthTable(sheet: SpriteSheet, codeOffset: Int, codeRange: IntRange, rows: Int = 16) {
        val binaryCodeOffset = 15

        val cellW = sheet.getSubImage(0, 0).width + 1  // should be 16
        val cellH = sheet.getSubImage(0, 0).height + 1 // should be 20

        // control chars
        for (ccode in codeRange) {
            val glyphX = ccode % rows
            val glyphY = ccode / rows

            val codeStartX = (glyphX * cellW) + binaryCodeOffset
            val codeStartY = (glyphY * cellH)

            var glyphWidth = 0
            for (downCtr in 0..3) {
                // if alpha is not zero, assume it's 1
                if (sheet.texture.getPixel(codeStartX, codeStartY + downCtr)[3] == 255) {
                    glyphWidth = glyphWidth or (1 shl downCtr)
                }
            }

            glyphWidths[codeOffset + ccode] = glyphWidth
        }
    }

    companion object {

        internal val glyphWidths: HashMap<Int, Int> = HashMap()

        lateinit internal var hangulSheet: SpriteSheet
        lateinit internal var asciiSheet: SpriteSheet
        lateinit internal var runicSheet: SpriteSheet
        lateinit internal var extASheet: SpriteSheet
        lateinit internal var extBSheet: SpriteSheet
        lateinit internal var kanaSheet: SpriteSheet
        lateinit internal var cjkPunct: SpriteSheet
        lateinit internal var uniHan: SpriteSheet
        lateinit internal var cyrilic: SpriteSheet
        lateinit internal var fullwidthForms: SpriteSheet
        lateinit internal var uniPunct: SpriteSheet
        lateinit internal var greekSheet: SpriteSheet
        lateinit internal var thaiSheet: SpriteSheet
        lateinit internal var customSheet: SpriteSheet

        internal val JUNG_COUNT = 21
        internal val JONG_COUNT = 28

        internal val W_ASIAN_PUNCT = 10
        internal val W_HANGUL = 12
        internal val W_KANA = 12
        internal val W_UNIHAN = 16
        internal val W_LATIN_WIDE = 9 // width of regular letters

        internal val H = 20
        internal val H_UNIHAN = 16

        internal val SIZE_KEYCAP = 18

        internal val SHEET_ASCII_VARW =     0
        internal val SHEET_HANGUL =         1
        internal val SHEET_EXTA_VARW =      2
        internal val SHEET_EXTB_VARW =      3
        internal val SHEET_KANA =           4
        internal val SHEET_CJK_PUNCT =      5
        internal val SHEET_UNIHAN =         6
        internal val SHEET_CYRILIC_VARW =   7
        internal val SHEET_FW_UNI =         8
        internal val SHEET_UNI_PUNCT =      9
        internal val SHEET_GREEK_VARW =     10
        internal val SHEET_THAI_VARW =      11
        internal val SHEET_CUSTOM_SYM =     12

        internal val SHEET_UNKNOWN = 254

        lateinit internal var sheetKey: Array<SpriteSheet?>

        /**
         * Runic letters list used for game. The set is
         * Younger Futhark + Medieval rune 'e' + Punct + Runic Almanac

         * BEWARE OF SIMILAR-LOOKING RUNES, especially:

         * * Algiz ᛉ instead of Maðr ᛘ

         * * Short-Twig Hagall ᚽ instead of Runic Letter E ᛂ

         * * Runic Letter OE ᚯ instead of Óss ᚬ

         * Examples:
         * ᛭ᛋᛁᚴᚱᛁᚦᛦ᛭
         * ᛭ᛂᛚᛋᛅ᛭ᛏᚱᚢᛏᚾᛁᚾᚴᚢᚾᛅ᛬ᛅᚱᚾᛅᛏᛅᛚᛋ
         */
        internal val runicList = arrayOf('ᚠ', 'ᚢ', 'ᚦ', 'ᚬ', 'ᚱ', 'ᚴ', 'ᚼ', 'ᚾ', 'ᛁ', 'ᛅ', 'ᛋ', 'ᛏ', 'ᛒ', 'ᛘ', 'ᛚ', 'ᛦ', 'ᛂ', '᛬', '᛫', '᛭', 'ᛮ', 'ᛯ', 'ᛰ')

        var interchar = 0
        var scale = 1
            set(value) {
                if (value > 0) field = value
                else throw IllegalArgumentException("Font scale cannot be zero or negative (input: $value)")
            }

        val colourKey = hashMapOf(
                Pair(0x10.toChar(), Color(0xFFFFFF)), //*w hite
                Pair(0x11.toChar(), Color(0xFFE080)), //*y ellow
                Pair(0x12.toChar(), Color(0xFFB020)), //o range
                Pair(0x13.toChar(), Color(0xFF8080)), //*r ed
                Pair(0x14.toChar(), Color(0xFFA0E0)), //f uchsia
                Pair(0x15.toChar(), Color(0xE0A0FF)), //*m agenta (purple)
                Pair(0x16.toChar(), Color(0x8080FF)), //*b lue
                Pair(0x17.toChar(), Color(0x80FFFF)), //c yan
                Pair(0x18.toChar(), Color(0x80FF80)), //*g reen
                Pair(0x19.toChar(), Color(0x008000)), //v iridian
                Pair(0x1A.toChar(), Color(0x805030)), //x (khaki)
                Pair(0x1B.toChar(), Color(0x808080))  //*k
                //* marked: commonly used
        )
        val colToCode = hashMapOf(
                Pair("w", 0x10.toChar()),
                Pair("y", 0x11.toChar()),
                Pair("o", 0x12.toChar()),
                Pair("r", 0x13.toChar()),
                Pair("f", 0x14.toChar()),
                Pair("m", 0x15.toChar()),
                Pair("b", 0x16.toChar()),
                Pair("c", 0x17.toChar()),
                Pair("g", 0x18.toChar()),
                Pair("v", 0x19.toChar()),
                Pair("x", 0x1A.toChar()),
                Pair("k", 0x1B.toChar())
        )
        val codeToCol = hashMapOf(
                Pair("w", colourKey[0x10.toChar()]),
                Pair("y", colourKey[0x11.toChar()]),
                Pair("o", colourKey[0x12.toChar()]),
                Pair("r", colourKey[0x13.toChar()]),
                Pair("f", colourKey[0x14.toChar()]),
                Pair("m", colourKey[0x15.toChar()]),
                Pair("b", colourKey[0x16.toChar()]),
                Pair("c", colourKey[0x17.toChar()]),
                Pair("g", colourKey[0x18.toChar()]),
                Pair("v", colourKey[0x19.toChar()]),
                Pair("x", colourKey[0x1A.toChar()]),
                Pair("k", colourKey[0x1B.toChar()])
        )
    }// end of companion object
}

fun Image.drawWithShadow(x: Float, y: Float, color: Color, noShadow: Boolean) =
        this.drawWithShadow(x, y, 1f, color, noShadow)

fun Image.drawWithShadow(x: Float, y: Float, scale: Float, color: Color, noShadow: Boolean) {
    if (!noShadow) {
        this.draw(x + 1, y + 1, scale, color.darker(0.5f))
        this.draw(x, y + 1, scale, color.darker(0.5f))
        this.draw(x + 1, y, scale, color.darker(0.5f))
    }

    this.draw(x, y, scale, color)
}