package net.torvald.imagefont

import org.lwjgl.opengl.GL11
import org.newdawn.slick.*
import org.newdawn.slick.opengl.Texture
import java.nio.ByteOrder
import java.util.*

/**
 * Created by minjaesong on 16-01-27.
 */
open class GameFontBase : Font {

    private fun getHan(hanIndex: Int): IntArray {
        val han_x = hanIndex % JONG_COUNT
        val han_y = hanIndex / JONG_COUNT
        val ret = intArrayOf(han_x, han_y)
        return ret
    }

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

    private fun isHangul(c: Char) = c.toInt() >= 0xAC00 && c.toInt() < 0xD7A4
    private fun isAscii(c: Char) = c.toInt() >= 0x20 && c.toInt() <= 0xFF
    private fun isRunic(c: Char) = runicList.contains(c)
    private fun isExtA(c: Char) = c.toInt() >= 0x100 && c.toInt() < 0x180
    private fun isExtB(c: Char) = c.toInt() >= 0x180 && c.toInt() < 0x250
    private fun isKana(c: Char) = c.toInt() >= 0x3040 && c.toInt() < 0x3100
    private fun isCJKPunct(c: Char) = c.toInt() >= 0x3000 && c.toInt() < 0x3040
    private fun isUniHan(c: Char) = c.toInt() >= 0x3400 && c.toInt() < 0xA000
    private fun isCyrilic(c: Char) = c.toInt() >= 0x400 && c.toInt() < 0x460
    private fun isFullwidthUni(c: Char) = c.toInt() >= 0xFF00 && c.toInt() < 0xFF20
    private fun isUniPunct(c: Char) = c.toInt() >= 0x2000 && c.toInt() < 0x2070
    private fun isWenQuanYi1(c: Char) = c.toInt() >= 0x33F3 && c.toInt() <= 0x69FC
    private fun isWenQuanYi2(c: Char) = c.toInt() >= 0x69FD && c.toInt() <= 0x9FDC
    private fun isGreek(c: Char) = c.toInt() >= 0x370 && c.toInt() <= 0x3CE



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

    private fun uniHanIndexX(c: Char) = (c.toInt() - 0x3400) % 256
    private fun uniHanIndexY(c: Char) = (c.toInt() - 0x3400) / 256

    private fun cyrilicIndexX(c: Char) = (c.toInt() - 0x400) % 16
    private fun cyrilicIndexY(c: Char) = (c.toInt() - 0x400) / 16

    private fun fullwidthUniIndexX(c: Char) = (c.toInt() - 0xFF00) % 16
    private fun fullwidthUniIndexY(c: Char) = (c.toInt() - 0xFF00) / 16

    private fun uniPunctIndexX(c: Char) = (c.toInt() - 0x2000) % 16
    private fun uniPunctIndexY(c: Char) = (c.toInt() - 0x2000) / 16

    private fun wenQuanYiIndexX(c: Char) =
            (c.toInt() - if (c.toInt() <= 0x4DB5) 0x33F3 else 0x33F3 + 0x4A) % 32
    private fun wenQuanYi1IndexY(c: Char) = (c.toInt() - (0x33F3 + 0x4A)) / 32
    private fun wenQuanYi2IndexY(c: Char) = (c.toInt() - 0x69FD) / 32

    private fun greekIndexX(c: Char) = (c.toInt() - 0x370) % 16
    private fun greekIndexY(c: Char) = (c.toInt() - 0x370) / 16

    private val unihanWidthSheets = arrayOf(
            SHEET_UNIHAN,
            SHEET_FW_UNI,
            SHEET_WENQUANYI_1,
            SHEET_WENQUANYI_2
    )
    private val zeroWidthSheets = arrayOf(
            SHEET_COLOURCODE
    )
    private val variableWidthSheets = arrayOf(
            SHEET_ASCII_VARW,
            SHEET_CYRILIC_VARW,
            SHEET_EXTA_VARW,
            SHEET_GREEK_VARW,
            SHEET_EXTB_VARW
    )


    override fun getWidth(s: String) = getWidthSubstr(s, s.length)

    private fun getWidthSubstr(s: String, endIndex: Int): Int {
        var len = 0
        for (i in 0..endIndex - 1) {
            val chr = s[i]
            val ctype = getSheetType(s[i])

            if (variableWidthSheets.contains(ctype)) {
                len += try {
                    asciiWidths[chr.toInt()]!!
                }
                catch (e: kotlin.KotlinNullPointerException) {
                    println("KotlinNullPointerException on glyph number ${Integer.toHexString(chr.toInt()).toUpperCase()}")
                    //System.exit(1)
                    W_LATIN_WIDE // failsafe
                }
            }
            else if (zeroWidthSheets.contains(ctype))
                len += 0
            else if (ctype == SHEET_CJK_PUNCT)
                len += W_ASIAN_PUNCT
            else if (ctype == SHEET_HANGUL)
                len += W_HANGUL
            else if (ctype == SHEET_KANA)
                len += W_KANA
            else if (unihanWidthSheets.contains(ctype))
                len += W_UNIHAN
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

                val glyphW = getWidth(ch.toString())

                hangulSheet.getSubImage(indexCho, choRow).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),
                        Math.round(((H - H_HANGUL) / 2).toFloat() + y + 1f).toFloat(),
                        scale.toFloat(), thisCol
                )
                hangulSheet.getSubImage(indexJung, jungRow).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),
                        Math.round(((H - H_HANGUL) / 2).toFloat() + y + 1f).toFloat(),
                        scale.toFloat(), thisCol
                )
                hangulSheet.getSubImage(indexJong, jongRow).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),
                        Math.round(((H - H_HANGUL) / 2).toFloat() + y + 1f).toFloat(),
                        scale.toFloat(), thisCol
                )
            }
        }
        //hangulSheet.endUse()

        // unihan fonts
        /*uniHan.startUse();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (isUniHan(ch)) {
                int glyphW = getWidth("" + ch);
                uniHan.renderInUse(
                        Math.round(x
                                + getWidthSubstr(s, i + 1) - glyphW
                        )
                        , Math.round((H - H_UNIHAN) / 2 + y)
                        , uniHanIndexX(ch)
                        , uniHanIndexY(ch)
                );
            }
        }

        uniHan.endUse();*/

        // WenQuanYi 1
        //wenQuanYi_1.startUse()

        for (i in 0..s.length - 1) {
            val ch = s[i]

            if (ch.isColourCode()) {
                thisCol = colourKey[ch]!!
                continue
            }

            if (isWenQuanYi1(ch)) {
                val glyphW = getWidth("" + ch)
                wenQuanYi_1.getSubImage(wenQuanYiIndexX(ch), wenQuanYi1IndexY(ch)).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),
                        Math.round((H - H_UNIHAN) / 2 + y).toFloat(),
                        scale.toFloat(), thisCol
                )
            }
        }

        //wenQuanYi_1.endUse()
        // WenQuanYi 2
        //wenQuanYi_2.startUse()

        for (i in 0..s.length - 1) {
            val ch = s[i]

            if (ch.isColourCode()) {
                thisCol = colourKey[ch]!!
                continue
            }

            if (isWenQuanYi2(ch)) {
                val glyphW = getWidth("" + ch)
                wenQuanYi_2.getSubImage(wenQuanYiIndexX(ch), wenQuanYi2IndexY(ch)).drawWithShadow(
                        Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),
                        Math.round((H - H_UNIHAN) / 2 + y).toFloat(),
                        scale.toFloat(), thisCol
                )
            }
        }

        //wenQuanYi_2.endUse()

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
                    SHEET_EXTA_VARW    -> {
                        sheetX = extAindexX(ch)
                        sheetY = extAindexY(ch)
                    }
                    SHEET_EXTB_VARW    -> {
                        sheetX = extBindexX(ch)
                        sheetY = extBindexY(ch)
                    }
                    SHEET_KANA       -> {
                        sheetX = kanaIndexX(ch)
                        sheetY = kanaIndexY(ch)
                    }
                    SHEET_CJK_PUNCT  -> {
                        sheetX = cjkPunctIndexX(ch)
                        sheetY = cjkPunctIndexY(ch)
                    }
                    SHEET_CYRILIC_VARW -> {
                        sheetX = cyrilicIndexX(ch)
                        sheetY = cyrilicIndexY(ch)
                    }
                    SHEET_FW_UNI             -> {
                        sheetX = fullwidthUniIndexX(ch)
                        sheetY = fullwidthUniIndexY(ch)
                    }
                    SHEET_UNI_PUNCT            -> {
                        sheetX = uniPunctIndexX(ch)
                        sheetY = uniPunctIndexY(ch)
                    }
                    SHEET_GREEK_VARW           -> {
                        sheetX = greekIndexX(ch)
                        sheetY = greekIndexY(ch)
                    }
                    else                       -> {
                        sheetX = ch.toInt() % 16
                        sheetY = ch.toInt() / 16
                    }
                }

                val glyphW = getWidth("" + ch)
                try {
                    sheetKey[prevInstance]!!.getSubImage(sheetX, sheetY).drawWithShadow(
                            Math.round(x + getWidthSubstr(s, i + 1) - glyphW).toFloat(),

                            // to deal with the height difference of the sheets
                            Math.round(y).toFloat() + (if (prevInstance == SHEET_CJK_PUNCT) -1 // height hack
                            else if (prevInstance == SHEET_FW_UNI) (H - H_HANGUL) / 2    // completely legit height adjustment
                            else 0).toFloat(),

                            scale.toFloat(), thisCol
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
        else if (c.isColourCode())
            return SHEET_COLOURCODE
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
        fun Byte.toUint() = java.lang.Byte.toUnsignedInt(this)

        /** @return Intarray(R, G, B, A) */
        fun Texture.getPixel(x: Int, y: Int): IntArray {
            val textureWidth = this.textureWidth
            val hasAlpha = this.hasAlpha()

            val offset = (if (hasAlpha) 4 else 3) * (textureWidth * y + x) // 4: # of channels (RGBA)

            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                return intArrayOf(
                        this.textureData[offset].toUint(),
                        this.textureData[offset + 1].toUint(),
                        this.textureData[offset + 2].toUint(),
                        if (hasAlpha)
                            this.textureData[offset + 3].toUint()
                        else 255
                )
            }
            else {
                return intArrayOf(
                        this.textureData[offset + 2].toUint(),
                        this.textureData[offset + 1].toUint(),
                        this.textureData[offset].toUint(),
                        if (hasAlpha)
                            this.textureData[offset + 3].toUint()
                        else 255
                )
            }
        }

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

            asciiWidths[codeOffset + ccode] = glyphWidth
        }
    }

    companion object {

        lateinit internal var hangulSheet: SpriteSheet
        lateinit internal var asciiSheet: SpriteSheet

        internal val asciiWidths: HashMap<Int, Int> = HashMap()

        lateinit internal var extASheet: SpriteSheet
        lateinit internal var extBSheet: SpriteSheet
        lateinit internal var kanaSheet: SpriteSheet
        lateinit internal var cjkPunct: SpriteSheet
        // static SpriteSheet uniHan;
        lateinit internal var cyrilic: SpriteSheet
        lateinit internal var fullwidthForms: SpriteSheet
        lateinit internal var uniPunct: SpriteSheet
        lateinit internal var wenQuanYi_1: SpriteSheet
        lateinit internal var wenQuanYi_2: SpriteSheet
        lateinit internal var greekSheet: SpriteSheet

        internal val JUNG_COUNT = 21
        internal val JONG_COUNT = 28

        internal val W_ASIAN_PUNCT = 10
        internal val W_HANGUL = 11
        internal val W_KANA = 12
        internal val W_UNIHAN = 16
        internal val W_LATIN_WIDE = 9 // width of regular letters

        internal val H = 20
        internal val H_HANGUL = 16
        internal val H_UNIHAN = 16
        internal val H_KANA = 20

        internal val SHEET_ASCII_VARW = 0
        internal val SHEET_HANGUL = 1
        internal val SHEET_EXTA_VARW = 3
        internal val SHEET_EXTB_VARW = 4
        internal val SHEET_KANA = 5
        internal val SHEET_CJK_PUNCT = 6
        internal val SHEET_UNIHAN = 7
        internal val SHEET_CYRILIC_VARW = 8
        internal val SHEET_FW_UNI = 9
        internal val SHEET_UNI_PUNCT = 10
        internal val SHEET_WENQUANYI_1 = 11
        internal val SHEET_WENQUANYI_2 = 12
        internal val SHEET_GREEK_VARW = 13


        internal val SHEET_UNKNOWN = 254
        internal val SHEET_COLOURCODE = 255

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

        internal var interchar = 0
        internal var scale = 1
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

fun Image.drawWithShadow(x: Float, y: Float, color: Color) =
        this.drawWithShadow(x, y, 1f, color)

fun Image.drawWithShadow(x: Float, y: Float, scale: Float, color: Color) {
    this.draw(x + 1, y + 1, scale, color.darker(0.5f))
    this.draw(x    , y + 1, scale, color.darker(0.5f))
    this.draw(x + 1, y    , scale, color.darker(0.5f))

    this.draw(x, y, scale, color)
}
