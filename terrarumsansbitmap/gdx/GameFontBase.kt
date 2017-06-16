package net.torvald.terrarumsansbitmap.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

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
 * Created by minjaesong on 2017-06-15.
 */
class GameFontBase(fontDir: String, val noShadow: Boolean = false) : BitmapFont() {

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
    private fun isAscii(c: Char) = c.toInt() in 0..0xFF
    //private fun isRunic(c: Char) = runicList.contains(c)
    private fun isExtA(c: Char) = c.toInt() in 0x100..0x17F
    private fun isExtB(c: Char) = c.toInt() in 0x180..0x24F
    private fun isKana(c: Char) = c.toInt() in 0x3040..0x30FF
    private fun isCJKPunct(c: Char) = c.toInt() in 0x3000..0x303F
    private fun isUniHan(c: Char) = c.toInt() in 0x3400..0x9FFF
    private fun isCyrilic(c: Char) = c.toInt() in 0x400..0x4FF // 4FF: futureproof; currently only up to 45F is supported
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

    //private fun runicIndexX(c: Char) = runicList.indexOf(c) % 16
    //private fun runicIndexY(c: Char) = runicList.indexOf(c) / 16

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
            SHEET_FW_UNI
    )
    private val variableWidthSheets = arrayOf(
            SHEET_ASCII_VARW,
            SHEET_EXTA_VARW,
            SHEET_EXTB_VARW,
            SHEET_CYRILIC_VARW,
            SHEET_UNI_PUNCT,
            SHEET_GREEK_VARW,
            SHEET_THAI_VARW
    )

    private val fontParentDir = if (fontDir.endsWith('/') || fontDir.endsWith('\\')) fontDir else "$fontDir/"
    private val fileList = arrayOf( // MUST BE MATCHING WITH SHEET INDICES!!
            "ascii_variable.tga",
            "hangul_johab.tga",
            "LatinExtA_variable.tga",
            "LatinExtB_variable.tga",
            "kana.tga",
            "cjkpunct.tga",
            "wenquanyi.tga.gz",
            "cyrilic_variable.tga",
            "fullwidth_forms.tga",
            "unipunct_variable.tga",
            "greek_variable.tga",
            "thai_variable.tga",
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
            0x400..0x4FF,
            0xFF00..0xFF1F,
            0x2000..0x206F,
            0x370..0x3CE,
            0xE00..0xE7F,
            0xE000..0xE0FF
    )
    private val glyphWidths: HashMap<Int, Int> = HashMap()
    private val sheets: Array<TextureRegionPack>


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
                val gzi = GZIPInputStream(Gdx.files.internal(fontParentDir + it).read(8192))
                val wholeFile = gzi.readBytes()
                gzi.close()
                val fos = BufferedOutputStream(FileOutputStream("tmp_wenquanyi.tga"))
                fos.write(wholeFile)
                fos.flush()
                fos.close()

                pixmap = Pixmap(Gdx.files.internal("tmp_wenquanyi.tga"))

                File("tmp_wenquanyi.tga").delete()
            }
            else {
                pixmap = Pixmap(Gdx.files.internal(fontParentDir + it))
            }


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
            else throw IllegalArgumentException("[TerrarumSansBitmap] Unknown sheet index: $index")


            sheetsPack.add(texRegPack)

            pixmap.dispose() // you are terminated
        }

        sheets = sheetsPack.toTypedArray()
    }


    fun reload(locale: String) {
        if (locale.startsWith("ru")) {
            val pixmap = Pixmap(Gdx.files.internal(fontParentDir + fileList[SHEET_CYRILIC_VARW]))
            val texture = Texture(pixmap)
            sheets[SHEET_CYRILIC_VARW].dispose()
            sheets[SHEET_CYRILIC_VARW] = TextureRegionPack(texture, W_VAR_INIT, H, HGAP_VAR, 0)
            pixmap.dispose()
        }
        else if (locale.startsWith("bg")) {
            val pixmap = Pixmap(Gdx.files.internal(fontParentDir + cyrilic_bg))
            val texture = Texture(pixmap)
            sheets[SHEET_CYRILIC_VARW].dispose()
            sheets[SHEET_CYRILIC_VARW] = TextureRegionPack(texture, W_VAR_INIT, H, HGAP_VAR, 0)
            pixmap.dispose()
        }
        else if (locale.startsWith("sr")) {
            val pixmap = Pixmap(Gdx.files.internal(fontParentDir + cyrilic_sr))
            val texture = Texture(pixmap)
            sheets[SHEET_CYRILIC_VARW].dispose()
            sheets[SHEET_CYRILIC_VARW] = TextureRegionPack(texture, W_VAR_INIT, H, HGAP_VAR, 0)
            pixmap.dispose()
        }
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

    private var textBuffer: CharSequence = ""
    private var textBWidth = intArrayOf() // absolute posX of glyphs from print-origin

    override fun draw(batch: Batch, str: CharSequence, x: Float, y: Float): GlyphLayout? {
        if (textBuffer != str) {
            textBuffer = str
            val widths = getWidthOfCharSeq(str)

            textBWidth = Array(str.length, { charIndex ->
                if (charIndex == 0)
                    0
                else {
                    var acc = 0
                    (0..charIndex - 1).forEach { acc += widths[it] }
                    /*return*/acc
                }
            }).toIntArray()
        }


        //print("[TerrarumSansBitmap] widthTable for $textBuffer: ")
        //textBWidth.forEach { print("$it ") }; println()


        val mainCol = this.color.cpy()
        val shadowCol = this.color.cpy().mul(0.5f)


        textBuffer.forEachIndexed { index, c ->
            val sheetID = getSheetType(c)
            val sheetXY = getSheetwisePosition(c)

            //println("[TerrarumSansBitmap] sprite:  $sheetID:${sheetXY[0]}x${sheetXY[1]}")

            if (sheetID != SHEET_HANGUL) {

                if (!noShadow) {
                    batch.color = shadowCol
                    batch.draw(
                            sheets[sheetID].get(sheetXY[0], sheetXY[1]),
                            x + textBWidth[index] + 1,
                            y +
                            if (sheetID == SHEET_UNIHAN) // evil exceptions
                                offsetUnihan
                            else if (sheetID == SHEET_CUSTOM_SYM)
                                offsetCustomSym
                            else 0
                    )
                    batch.draw(
                            sheets[sheetID].get(sheetXY[0], sheetXY[1]),
                            x + textBWidth[index],
                            y - 1 +
                            if (sheetID == SHEET_UNIHAN) // evil exceptions
                                offsetUnihan
                            else if (sheetID == SHEET_CUSTOM_SYM)
                                offsetCustomSym
                            else 0
                    )
                    batch.draw(
                            sheets[sheetID].get(sheetXY[0], sheetXY[1]),
                            x + textBWidth[index] + 1,
                            y - 1 +
                            if (sheetID == SHEET_UNIHAN) // evil exceptions
                                offsetUnihan
                            else if (sheetID == SHEET_CUSTOM_SYM)
                                offsetCustomSym
                            else 0
                    )
                }


                batch.color = mainCol
                batch.draw(
                        sheets[sheetID].get(sheetXY[0], sheetXY[1]),
                        x + textBWidth[index],
                        y +
                        if (sheetID == SHEET_UNIHAN) // evil exceptions
                            offsetUnihan
                        else if (sheetID == SHEET_CUSTOM_SYM)
                            offsetCustomSym
                        else 0
                )
            }
            else { // JOHAB (assemble) HANGUL
                val hangulSheet = sheets[SHEET_HANGUL]
                val hIndex = c.toInt() - 0xAC00

                val indexCho = getHanChosung(hIndex)
                val indexJung = getHanJungseong(hIndex)
                val indexJong = getHanJongseong(hIndex)

                val choRow = getHanInitialRow(hIndex)
                val jungRow = getHanMedialRow(hIndex)
                val jongRow = getHanFinalRow(hIndex)


                if (!noShadow) {
                    batch.color = shadowCol

                    batch.draw(hangulSheet.get(indexCho, choRow), x + textBWidth[index] + 1, y)
                    batch.draw(hangulSheet.get(indexJung, jungRow), x + textBWidth[index], y - 1)
                    batch.draw(hangulSheet.get(indexJong, jongRow), x + textBWidth[index] + 1, y - 1)
                }


                batch.color = mainCol
                batch.draw(hangulSheet.get(indexCho, choRow), x + textBWidth[index], y)
                batch.draw(hangulSheet.get(indexJung, jungRow), x + textBWidth[index], y)
                batch.draw(hangulSheet.get(indexJong, jongRow), x + textBWidth[index], y)
            }
        }

        this.color = mainCol

        return null
    }


    override fun dispose() {
        super.dispose()

        sheets.forEach { it.dispose() }
    }

    private fun getWidthOfCharSeq(s: CharSequence): IntArray {
        val len = IntArray(s.length)
        for (i in 0..s.lastIndex) {
            val chr = s[i]
            val ctype = getSheetType(s[i])

            if (variableWidthSheets.contains(ctype)) {
                len[i] = try {
                    glyphWidths[chr.toInt()]!!
                }
                catch (e: kotlin.KotlinNullPointerException) {
                    println("[TerrarumSansBitmap] no width data for glyph number ${Integer.toHexString(chr.toInt()).toUpperCase()}")
                    //System.exit(1)
                    W_LATIN_WIDE // failsafe
                }
            }
            else if (ctype == SHEET_CJK_PUNCT)
                len[i] = W_ASIAN_PUNCT
            else if (ctype == SHEET_HANGUL)
                len[i] = W_HANGUL
            else if (ctype == SHEET_KANA)
                len[i] = W_KANA
            else if (unihanWidthSheets.contains(ctype))
                len[i] = W_UNIHAN
            else if (isThaiDiacritics(s[i]))
                len[i] = 0 // set width of the glyph as -W_LATIN_WIDE
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
            else -> {
                sheetX = ch.toInt() % 16
                sheetY = ch.toInt() / 16
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

            var glyphWidth = 0
            for (downCtr in 0..3) {
                // if ALPHA is not zero, assume it's 1
                if (pixmap.getPixel(codeStartX, codeStartY + downCtr).and(0xFF) != 0) {
                    glyphWidth = glyphWidth or (1 shl downCtr)
                }
            }

            glyphWidths[code] = glyphWidth
        }
    }


    companion object {

        internal val JUNG_COUNT = 21
        internal val JONG_COUNT = 28

        internal val W_ASIAN_PUNCT = 10
        internal val W_HANGUL = 12
        internal val W_KANA = 12
        internal val W_UNIHAN = 16
        internal val W_LATIN_WIDE = 9 // width of regular letters
        internal val W_VAR_INIT = 15

        internal val HGAP_VAR = 1

        internal val H = 20
        internal val H_UNIHAN = 16

        internal val SIZE_CUSTOM_SYM = 18

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
        //internal val runicList = arrayOf('ᚠ', 'ᚢ', 'ᚦ', 'ᚬ', 'ᚱ', 'ᚴ', 'ᚼ', 'ᚾ', 'ᛁ', 'ᛅ', 'ᛋ', 'ᛏ', 'ᛒ', 'ᛘ', 'ᛚ', 'ᛦ', 'ᛂ', '᛬', '᛫', '᛭', 'ᛮ', 'ᛯ', 'ᛰ')
        // TODO expand to full Unicode runes

        var interchar = 0
        var scale = 1
            set(value) {
                if (value > 0) field = value
                else throw IllegalArgumentException("Font scale cannot be zero or negative (input: $value)")
            }
    }

}