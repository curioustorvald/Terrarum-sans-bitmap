/*
 * Terrarum Sans Bitmap
 * 
 * Copyright (c) 2017-2024 see CONTRIBUTORS.txt
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
import net.torvald.terrarumsansbitmap.MovableType
import net.torvald.terrarumsansbitmap.MovableType.Companion.GLUE_NEGATIVE_ONE
import net.torvald.terrarumsansbitmap.MovableType.Companion.GLUE_POSITIVE_SIXTEEN
import net.torvald.terrarumsansbitmap.TypesettingStrategy
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.FIXED_BLOCK_1
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.NBSP
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.OBJ
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.SHY
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.ZWSP
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.glueCharToGlueSize
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

class CodepointSequence: MutableList<CodePoint> {
    private val data = ArrayList<CodePoint>()

    constructor()

    constructor(chars: Collection<CodePoint>) {
        data.addAll(chars)
    }

    override val size; get() = data.size
    val indices; get() = data.indices
    val lastIndex; get() = data.lastIndex

    fun forEach(action: (CodePoint) -> Unit) = data.forEach(action)
    fun forEachIndexed(action: (Int, CodePoint) -> Unit) = data.forEachIndexed(action)
    fun map(action: (CodePoint) -> Any?) = data.map(action)
    fun mapInxeded(action: (Int, CodePoint) -> Any?) = data.mapIndexed(action)
    fun first() = data.first()
    fun firstOrNull() = data.firstOrNull()
    fun first(predicate: (CodePoint) -> Boolean) = data.first(predicate)
    fun firstOrNull(predicate: (CodePoint) -> Boolean) = data.firstOrNull(predicate)
    fun last() = data.last()
    fun lastOrNull() = data.lastOrNull()
    fun last(predicate: (CodePoint) -> Boolean) = data.last(predicate)
    fun lastOrNull(predicate: (CodePoint) -> Boolean) = data.lastOrNull(predicate)
    fun filter(predicate: (CodePoint) -> Boolean) = data.filter(predicate)
    override fun add(index: Int, char: CodePoint) = data.add(index, char)
    override operator fun set(index: Int, char: CodePoint) = data.set(index, char)
    override fun add(char: CodePoint) = data.add(char)
    override fun addAll(chars: Collection<CodePoint>) = data.addAll(chars)
    fun addAll(cs: CodepointSequence) = data.addAll(cs.data)
    override fun removeAt(index: Int) = data.removeAt(index)
    override fun retainAll(elements: Collection<CodePoint>) = data.retainAll(elements)
    override fun remove(char: CodePoint) = data.remove(char)
    override operator fun get(index: Int) = data[index]
    override fun indexOf(element: CodePoint) = data.indexOf(element)
    fun getOrNull(index: Int) = data.getOrNull(index)
    fun getOrElse(index: Int, action: (Int) -> CodePoint) = data.getOrElse(index, action)
    override fun isEmpty() = data.isEmpty()
    override fun iterator() = data.iterator()
    override fun listIterator() = data.listIterator()
    override fun listIterator(index: Int) = data.listIterator()
    override fun lastIndexOf(element: CodePoint) = data.lastIndexOf(element)
    fun isNotEmpty() = data.isNotEmpty()
    fun count(predicate: (CodePoint) -> Boolean) = data.count(predicate)
    fun all(predicate: (CodePoint) -> Boolean) = data.all(predicate)
    fun any(predicate: (CodePoint) -> Boolean) = data.any(predicate)
    fun none(predicate: (CodePoint) -> Boolean) = data.none(predicate)
    override fun contains(char: CodePoint) = data.contains(char)
    fun removeIf(predicate: (CodePoint) -> Boolean) = data.removeIf(predicate)
    fun spliterator() = data.spliterator()
    fun stream() = data.stream()
    fun parallelStream() = data.parallelStream()
    override fun removeAll(elements: Collection<CodePoint>) = data.removeAll(elements)
    override fun addAll(index: Int, elements: Collection<CodePoint>) = data.addAll(index, elements)
    fun addAll(index: Int, elements: CodepointSequence) = data.addAll(index, elements.data)
    override fun subList(fromIndex: Int, toIndex: Int) = data.subList(fromIndex, toIndex)
    fun slice(indices: IntRange) = data.slice(indices)
    override fun clear() = data.clear()
    override fun containsAll(elements: Collection<CodePoint>) = data.containsAll(elements)

    fun penultimate() = data[data.size - 2]
    fun penultimateOrNull() = data.getOrNull(data.size - 2)

    fun toArray() = data.toArray()
    fun toList() = data.toList()


    fun isGlue() = data.size == 1 && (data[0] == ZWSP || data[0] in 0xFFFE0..0xFFFFF)
    fun isNotGlue() = !isGlue()
    fun isZeroGlue() = data.size == 1 && (data[0] == ZWSP)

    private fun CharArray.toSurrogatedString(): String = if (this.size == 1) "${this[0]}" else "${this[0]}${this[1]}"
    private inline fun Int.codepointToString() = Character.toChars(this).toSurrogatedString()
    private fun CodePoint.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

    fun toHexes() = data.joinToString(" ") { it.toHex() }

    fun toReadable() = data.joinToString("") {
        if (it in 0x00..0x1f)
            "${(0x2400 + it).toChar()}"
        else if (it == 0x20 || it == 0xF0520)
            "\u2423"
        else if (it == NBSP)
            "{NBSP}"
        else if (it == SHY)
            "{SHY}"
        else if (it == ZWSP)
            "{ZWSP}"
        else if (it == OBJ)
            "{OBJ:"
        else if (it in FIXED_BLOCK_1..FIXED_BLOCK_1 +15)
            " <block ${it - FIXED_BLOCK_1 + 1}>"
        else if (it in GLUE_NEGATIVE_ONE..GLUE_POSITIVE_SIXTEEN)
            " <glue ${it.glueCharToGlueSize()}> "
        else if (it == 0x100000)
            "{CC:null}"
        else if (it in 0x10F000..0x10FFFF) {
            val r = ((it and 0xF00) ushr 8).toString(16).toUpperCase()
            val g = ((it and 0x0F0) ushr 4).toString(16).toUpperCase()
            val b = ((it and 0x00F) ushr 0).toString(16).toUpperCase()
            "{CC:#$r$g$b}"
        }
        else if (it in 0xFFF70..0xFFF79)
            (it - 0xFFF70 + 0x30).codepointToString()
        else if (it == 0xFFF7D)
            "-"
        else if (it in 0xFFF80..0xFFF9A)
            (it - 0xFFF80 + 0x40).codepointToString()
        else if (it == 0xFFF9F)
            "}"
        else if (it in 0xF0541..0xF055A)
            (it - 0xF0541 + 0x1D670).codepointToString()
        else if (it in 0xF0561..0xF057A)
            (it - 0xF0561 + 0x1D68A).codepointToString()
        else if (it in 0xF0530..0xF0539)
            (it - 0xF0530 + 0x1D7F6).codepointToString()
        else if (it in 0xF0520..0xF057F)
            (it - 0xF0520 + 0x20).codepointToString()
        else if (it >= 0xF0000)
            it.toHex() + " "
        else
            Character.toString(it.toChar())
    }
}
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

    private var typesetCacheCap = 0
    private val typesetCache = HashMap<Long, MovableType>(textCacheSize)

    /**
     * Insertion sorts the last element fo the textCache
     */
    private fun addToCache(cacheObj: TextCacheObj) {
        if (textCacheCap < textCacheSize * 2) {
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

    private fun addToTypesetCache(cacheObj: MovableType) {
        if (typesetCacheCap < textCacheSize) {
            typesetCache[cacheObj.hash] = cacheObj
            typesetCacheCap += 1
        }
        else {
            // randomly eliminate one
            typesetCache.remove(typesetCache.keys.random())!!.dispose()

            // add new one
            typesetCache[cacheObj.hash] = cacheObj
        }
    }

    private fun getCache(hash: Long): TextCacheObj? {
        return textCache[hash]
    }

    private fun getTypesetCache(hash: Long): MovableType? {
        return typesetCache[hash]
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
    private val textReplaces = HashMap<CodePoint, CodePoint>()
    private val sheets: Array<PixmapRegionPack>

//    private var charsetOverride = 0

    private val tempDir = System.getProperty("java.io.tmpdir")
//    private val tempFiles = ArrayList<String>()

    init {
        val sheetsPack = ArrayList<PixmapRegionPack>()

        // first we create pixmap to read pixels, then make texture using pixmap
        fileList.forEachIndexed { index, it ->
            val isVariable = it.endsWith("_variable.tga")
            val isXYSwapped = it.contains("xyswap", true)
            val isExtraWide = it.contains("extrawide", true)

            var pixmap: Pixmap

            val status = ArrayList<String>()
            if (isVariable) status.add("VARIABLE")
            if (isXYSwapped) status.add("XYSWAP")
            if (isExtraWide) status.add("EXTRAWIDE")

            if (status.size > 0)
                dbgprn("loading texture [${status.joinToString()}] $it")
            else
                dbgprn("loading texture [STATIC] $it")


            // unpack gz if applicable
            if (it.endsWith(".gz")) {
                val tmpFilePath = tempDir + "/tmp_${it.dropLast(7)}.tga"

                try {
                    val gzi = GZIPInputStream(Gdx.files.internal(fontParentDir + it).read(8192))
                    val wholeFile = gzi.readBytes()
                    gzi.close()
                    val fos = BufferedOutputStream(FileOutputStream(tmpFilePath))
                    fos.write(wholeFile)
                    fos.flush()
                    fos.close()

                    pixmap = Pixmap(Gdx.files.absolute(tmpFilePath))
//                    tempFiles.add(tmpFilePath)
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

            if (isVariable) buildWidthTable(pixmap, codeRange[index], if (isExtraWide) 32 else 16)
            buildWidthTableFixed()
            buildWidthTableInternal()

            setupDynamicTextReplacer()

            /*if (!noShadow) {
                makeShadowForSheet(pixmap)
            }*/


            //val texture = Texture(pixmap)
            val texRegPack = if (isExtraWide)
                PixmapRegionPack(pixmap, W_WIDEVAR_INIT, H, HGAP_VAR, 0, xySwapped = isXYSwapped)
            else if (isVariable)
                PixmapRegionPack(pixmap, W_VAR_INIT, H, HGAP_VAR, 0, xySwapped = isXYSwapped)
            else if (index == SHEET_UNIHAN)
                PixmapRegionPack(pixmap, W_UNIHAN, H_UNIHAN) // the only exception that is height is 16
            // below they all have height of 20 'H'
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

    override fun getLineHeight(): Float = LINE_HEIGHT.toFloat() * scale
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
    private var textBuffer = CodepointSequence()

    private lateinit var tempLinotype: Texture

    private var nullProp = GlyphProps(15)

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
        val newCodepoints = codepoints

        // always draw at integer position; this is bitmap font after all
        val x = Math.round(x)
        val y = Math.round(y + (lineHeight - 20 * scale) / 2)

        val charSeqHash = newCodepoints.getHash()

        if (charSeqNotBlank) {

            var cacheObj = getCache(charSeqHash)

            if (cacheObj == null || flagFirstRun) {
                cacheObj = createTextCache(newCodepoints)
                addToCache(cacheObj)
            }

            textBuffer = cacheObj.glyphLayout!!.textBuffer
            tempLinotype = cacheObj.glyphLayout!!.linotype

            val linotypeScaleOffsetX = -linotypePaddingX * (scale - 1)
            val linotypeScaleOffsetY = -linotypePaddingY * (scale - 1) * (if (flipY) -1 else 1)

            batch.draw(tempLinotype,
                (x - linotypePaddingX).toFloat() + linotypeScaleOffsetX,
                (y - linotypePaddingY).toFloat() + linotypeScaleOffsetY + (if (flipY) (tempLinotype.height) else 0) * scale,
                tempLinotype.width.toFloat() * scale,
                (tempLinotype.height.toFloat()) * (if (flipY) -1 else 1) * scale
            )
        }

        return null
    }

    fun drawToPixmap(pixmap: Pixmap, string: String, x: Int, y: Int) {
        drawNormalisedToPixmap(pixmap, string.toCodePoints(), x, y)
    }

    fun drawNormalisedToPixmap(pixmap: Pixmap, codepoints: CodepointSequence, x: Int, y: Int) {
        val charSeqNotBlank = codepoints.size > 0 // determine emptiness BEFORE you hack a null chars in

        if (charSeqNotBlank) {
            val (linotypePixmap, _) = createLinotypePixmap(codepoints, false)
            linotypePixmap.filter = Pixmap.Filter.NearestNeighbour

            val linotypeScaleOffsetX = -linotypePaddingX * (scale - 1)
            val linotypeScaleOffsetY = -linotypePaddingY * (scale - 1) * (if (flipY) -1 else 1)

            pixmap.drawPixmap(linotypePixmap,
                0, 0, linotypePixmap.width, linotypePixmap.height,
                (x - linotypePaddingX) + linotypeScaleOffsetX,
                (y - linotypePaddingY) + linotypeScaleOffsetY + (if (flipY) (linotypePixmap.height) else 0) * scale,
                linotypePixmap.width * scale,
                (linotypePixmap.height) * (if (flipY) -1 else 1) * scale
            )

            linotypePixmap.dispose()
        }
    }

    internal fun createLinotypePixmap(newCodepoints: CodepointSequence, touchTheFlag: Boolean): Pair<Pixmap, Int> {
        fun Int.flipY() = this * if (flipY) 1 else -1

        var renderCol = -1 // subject to change with the colour code

        val textBuffer = newCodepoints

        val posmap = buildPosMap(textBuffer)

        if (touchTheFlag)
            flagFirstRun = false

        //dbgprn("text not in buffer: $charSeq")


        //textBuffer.forEach { print("${it.toHex()} ") }
        //dbgprn()


//                resetHash(charSeq, x.toFloat(), y.toFloat())

        val textWidth = posmap.width
        val _pw = textWidth + (linotypePaddingX * 2)
        val _ph = H + (linotypePaddingY * 2)
        if (_pw < 0 || _ph < 0) throw RuntimeException("Illegal linotype dimension (w: $_pw, h: $_ph)")
        val linotypePixmap = Pixmap(_pw, _ph, Pixmap.Format.RGBA8888)


        var index = 0
        while (index <= textBuffer.lastIndex) {
            try {
                var c = textBuffer[index]
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

                    linotypePixmap.drawPixmap(choTex,  posmap.x[index] + linotypePaddingX, linotypePaddingY, renderCol)
                    linotypePixmap.drawPixmap(jungTex, posmap.x[index] + linotypePaddingX, linotypePaddingY, renderCol)
                    linotypePixmap.drawPixmap(jongTex, posmap.x[index] + linotypePaddingX, linotypePaddingY, renderCol)


                    index += hangulLength - 1

                }
                else {
                    try {
                        val posY = posmap.y[index].flipY() +
                                if (sheetID == SHEET_UNIHAN) // evil exceptions
                                    offsetUnihan
                                else if (sheetID == SHEET_CUSTOM_SYM)
                                    offsetCustomSym
                                else 0

                        val posX = posmap.x[index]
                        val texture = sheets[sheetID].get(sheetX, sheetY)

                        linotypePixmap.drawPixmap(texture, posX + linotypePaddingX, posY + linotypePaddingY, renderCol)


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

        return linotypePixmap to textWidth
    }

    internal fun createTextCache(newCodepoints: CodepointSequence): TextCacheObj {
        // look, I know it sounds absurd, but having this code NOT duplicated (by moving it into a separate function) will cause most of the text to turn into a black rectange
        fun Int.flipY() = this * if (flipY) 1 else -1

        var renderCol = -1 // subject to change with the colour code

        val textBuffer = newCodepoints

        val posmap = buildPosMap(textBuffer)

        flagFirstRun = false

        //dbgprn("text not in buffer: $charSeq")


        //textBuffer.forEach { print("${it.toHex()} ") }
        //dbgprn()


//                resetHash(charSeq, x.toFloat(), y.toFloat())

        val textWidth = posmap.width
        val _pw = textWidth + (linotypePaddingX * 2)
        val _ph = H + (linotypePaddingY * 2)
        if (_pw < 0 || _ph < 0) throw RuntimeException("Illegal linotype dimension (w: $_pw, h: $_ph)")
        val linotypePixmap = Pixmap(_pw, _ph, Pixmap.Format.RGBA8888)


        var index = 0
        while (index <= textBuffer.lastIndex) {
            try {
                var c = textBuffer[index]
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

                    linotypePixmap.drawPixmap(choTex,  posmap.x[index] + linotypePaddingX, linotypePaddingY, renderCol)
                    linotypePixmap.drawPixmap(jungTex, posmap.x[index] + linotypePaddingX, linotypePaddingY, renderCol)
                    linotypePixmap.drawPixmap(jongTex, posmap.x[index] + linotypePaddingX, linotypePaddingY, renderCol)


                    index += hangulLength - 1

                }
                else {
                    try {
                        val posY = posmap.y[index].flipY() +
                                if (sheetID == SHEET_UNIHAN) // evil exceptions
                                    offsetUnihan
                                else if (sheetID == SHEET_CUSTOM_SYM)
                                    offsetCustomSym
                                else 0

                        val posX = posmap.x[index]
                        val texture = sheets[sheetID].get(sheetX, sheetY)

                        linotypePixmap.drawPixmap(texture, posX + linotypePaddingX, posY + linotypePaddingY, renderCol)


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
        // end of duplicated code
        //val (linotypePixmap, textWidth) = createLinotypePixmap(newCodepoints, true)

        val tempLinotype = Texture(linotypePixmap)
        tempLinotype.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)

        // make cache object
        val cacheObj = TextCacheObj(textBuffer.getHash(), ShittyGlyphLayout(textBuffer, tempLinotype, textWidth))
        linotypePixmap.dispose()

        return cacheObj
    }

    /**
     * Typesets given string and returns the typesetted results, with which the desired text can be drawn on the screen.
     * This method alone will NOT draw the text to the screen, use [MovableType.draw].
     */
    fun typesetParagraph(batch: Batch, charSeq: CharSequence, targetWidth: Int): MovableType =
        typesetParagraphNormalised(batch, normaliseStringForMovableType(charSeq), targetWidth.toFloat(), TypesettingStrategy.JUSTIFIED)
    /**
     * Typesets given string and returns the typesetted results, with which the desired text can be drawn on the screen.
     * This method alone will NOT draw the text to the screen, use [MovableType.draw].
     */
    fun typesetParagraph(batch: Batch, charSeq: CharSequence, targetWidth: Float): MovableType =
        typesetParagraphNormalised(batch, normaliseStringForMovableType(charSeq), targetWidth, TypesettingStrategy.JUSTIFIED)

    fun typesetParagraphRaggedRight(batch: Batch, charSeq: CharSequence, targetWidth: Int): MovableType =
        typesetParagraphNormalised(batch, normaliseStringForMovableType(charSeq), targetWidth.toFloat(), TypesettingStrategy.RAGGED_RIGHT)
    fun typesetParagraphRaggedRight(batch: Batch, charSeq: CharSequence, targetWidth: Float): MovableType =
        typesetParagraphNormalised(batch, normaliseStringForMovableType(charSeq), targetWidth, TypesettingStrategy.RAGGED_RIGHT)


    private val nullType = MovableType(this, "".toCodePoints(2), 0, isNull = true)

    /**
     * Typesets given string and returns the typesetted results, with which the desired text can be drawn on the screen.
     * This method alone will NOT draw the text to the screen, use [MovableType.draw].
     */
    fun typesetParagraphNormalised(batch: Batch, codepoints: CodepointSequence, targetWidth: Float, strategy: TypesettingStrategy): MovableType {
        val charSeqNotBlank = codepoints.size > 0 // determine emptiness BEFORE you hack a null chars in
        val newCodepoints = codepoints

        val charSeqHash = newCodepoints.getHash()

        if (charSeqNotBlank) {
            var cacheObj = getTypesetCache(charSeqHash)

            if (cacheObj == null || flagFirstRun) {
                cacheObj = MovableType(this, codepoints, targetWidth.toInt(), strategy)
                addToTypesetCache(cacheObj)
            }

            return cacheObj
        }
        else {
            return nullType
        }
    }


    override fun dispose() {
        super.dispose()
        textCache.values.forEach { it.dispose() }
        sheets.forEach { it.dispose() }
    }

    fun getSheetType(c: CodePoint): Int {
        if (isBulgarian(c))
            return SHEET_BULGARIAN_VARW
        else if (isSerbian(c))
            return SHEET_SERBIAN_VARW
        else if (isHangul(c))
            return SHEET_HANGUL
        else {
            for (i in codeRange.indices.reversed()) {
                if (c in codeRange[i]) return i
            }
            return SHEET_UNKNOWN
        }
    }

    private fun getSheetwisePosition(cPrev: Int, ch: Int): IntArray {
        val sheetType = getSheetType(ch)
        val sheetX: Int = if (sheetType == SHEET_UNIHAN) unihanIndexX(ch) else indexX(ch)
        val sheetY: Int = when (sheetType) {
            SHEET_UNIHAN ->  unihanIndexY(ch)
            SHEET_EXTA_VARW -> extAindexY(ch)
            SHEET_EXTB_VARW -> extBindexY(ch)
            SHEET_KANA -> kanaIndexY(ch)
            SHEET_CJK_PUNCT -> cjkPunctIndexY(ch)
            SHEET_CYRILIC_VARW -> cyrilicIndexY(ch)
            SHEET_HALFWIDTH_FULLWIDTH_VARW -> fullwidthUniIndexY(ch)
            SHEET_UNI_PUNCT_VARW -> uniPunctIndexY(ch)
            SHEET_GREEK_VARW -> greekIndexY(ch)
            SHEET_THAI_VARW -> thaiIndexY(ch)
            SHEET_CUSTOM_SYM -> symbolIndexY(ch)
            SHEET_HAYEREN_VARW -> armenianIndexY(ch)
            SHEET_KARTULI_VARW -> kartvelianIndexY(ch)
            SHEET_IPA_VARW -> ipaIndexY(ch)
            SHEET_RUNIC -> runicIndexY(ch)
            SHEET_LATIN_EXT_ADD_VARW -> latinExtAddY(ch)
            SHEET_BULGARIAN_VARW -> bulgarianIndexY(ch)
            SHEET_SERBIAN_VARW -> serbianIndexY(ch)
            SHEET_TSALAGI_VARW -> cherokeeIndexY(ch)
            SHEET_PHONETIC_EXT_VARW -> phoneticExtIndexY(ch)
            SHEET_DEVANAGARI_VARW -> devanagariIndexY(ch)
            SHEET_KARTULI_CAPS_VARW -> kartvelianCapsIndexY(ch)
            SHEET_DIACRITICAL_MARKS_VARW -> diacriticalMarksIndexY(ch)
            SHEET_GREEK_POLY_VARW -> polytonicGreekIndexY(ch)
            SHEET_EXTC_VARW -> extCIndexY(ch)
            SHEET_EXTD_VARW -> extDIndexY(ch)
            SHEET_CURRENCIES_VARW -> currenciesIndexY(ch)
            SHEET_INTERNAL_VARW -> internalIndexY(ch)
            SHEET_LETTERLIKE_MATHS_VARW -> letterlikeIndexY(ch)
            SHEET_ENCLOSED_ALPHNUM_SUPL_VARW -> enclosedAlphnumSuplY(ch)
            SHEET_TAMIL_VARW -> tamilIndexY(ch)
            SHEET_BENGALI_VARW -> bengaliIndexY(ch)
            SHEET_BRAILLE_VARW -> brailleIndexY(ch)
            SHEET_SUNDANESE_VARW -> sundaneseIndexY(ch)
            SHEET_DEVANAGARI2_INTERNAL_VARW -> devanagari2IndexY(ch)
            SHEET_CODESTYLE_ASCII_VARW -> codestyleAsciiIndexY(ch)
            else -> ch / 16
        }

        return intArrayOf(sheetX, sheetY)
    }

    private fun Boolean.toInt() = if (this) 1 else 0
    /** @return THIRTY-TWO bit number: this includes alpha channel value; or 0 if alpha is zero */
    private fun Int.tagify() = if (this and 255 == 0) 0 else this

    /**
     * Will happily overwrite any existing entries
     */
    private fun buildWidthTable(pixmap: Pixmap, codeRange: Iterable<Int>, cellW: Int = 16, cols: Int = 16) {
        val binaryCodeOffset = cellW - 1

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
//            if (stackWhere == GlyphProps.STACK_DOWN) dbgprn("Diacritics stack down: ${code.charInfo()}")
//            if (writeOnTop > -1 && alignWhere == GlyphProps.ALIGN_RIGHT && width > 0) dbgprn("Diacritics aligned to the right with width of $width: ${code.charInfo()}")
//            if (code in 0xF0000 until 0xF0060) dbgprn("Code ${code.toString(16)} width: $width")
        }
    }

    private fun buildWidthTableFixed() {
        // fixed-width props
        codeRange[SHEET_CUSTOM_SYM].forEach { glyphProps[it] = GlyphProps(20) }
        codeRange[SHEET_HANGUL].forEach { glyphProps[it] = GlyphProps(W_HANGUL_BASE) }
        codeRangeHangulCompat.forEach { glyphProps[it] = GlyphProps(W_HANGUL_BASE) }
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

    private fun Int.halveWidth() = this / 2 + 1

    private fun buildWidthTableInternal() {
        for (i in 0 until 16) {
            glyphProps[i] = GlyphProps(0)
            glyphProps[i + 16] = GlyphProps(0)
            glyphProps[FIXED_BLOCK_1 + i] = GlyphProps(i + 1)
            glyphProps[MOVABLE_BLOCK_1 + i] = GlyphProps(i + 1)
            glyphProps[MOVABLE_BLOCK_M1 + i] = GlyphProps(-i - 1)
        }

        for (i in 0 until 256) {
            glyphProps[0xF800 + i] = GlyphProps(0)
        }

        for (i in 0xFFF70..0xFFF9F) {
            glyphProps[i] = GlyphProps(0)
        }

        val figWidth = glyphProps[0x30]!!.width // 9 by default
        val punctWidth = glyphProps[0x2E]!!.width // 6 by default
        val em = 12 + 1

        glyphProps[NQSP] = GlyphProps(em.halveWidth()) // 7
        glyphProps[MQSP] = GlyphProps(em) // 13
        glyphProps[ENSP] = GlyphProps(em.halveWidth()) // 7
        glyphProps[EMSP] = GlyphProps(em) // 13
        glyphProps[THREE_PER_EMSP] = GlyphProps(em / 3 + 1) // 5
        glyphProps[QUARTER_EMSP] = GlyphProps(em / 4 + 1) // 4
        glyphProps[SIX_PER_EMSP] = GlyphProps(em / 6 + 1) // 3
        glyphProps[FSP] = GlyphProps(figWidth) // 9
        glyphProps[PSP] = GlyphProps(punctWidth) // 6
        glyphProps[THSP] = GlyphProps(2)
        glyphProps[HSP] = GlyphProps(1)
        glyphProps[ZWSP] = GlyphProps(0)
        glyphProps[ZWNJ] = GlyphProps(0)
        glyphProps[ZWJ] = GlyphProps(0)

        glyphProps[SHY] = GlyphProps(0)
        glyphProps[OBJ] = GlyphProps(0)
    }

    private fun setupDynamicTextReplacer() {
        // replace NBSP into a block of same width
        val spaceWidth = glyphProps[32]?.width ?: throw IllegalStateException()
        if (spaceWidth > 16) throw InternalError("Space (U+0020) character is too wide ($spaceWidth)")
        textReplaces[NBSP] = FIXED_BLOCK_1 + (spaceWidth - 1)
    }

    fun getWidth(text: String) = getWidthNormalised(text.toCodePoints())
    fun getWidth(s: CodepointSequence) = getWidthNormalised(s.normalise())

    fun getWidthNormalised(s: CodepointSequence): Int {
        if (s.isEmpty())
            return 0

        if (s.size == 1) {
            return scale * (glyphProps[s.first()]?.width ?: (
                    if (errorOnUnknownChar)
                        throw InternalError("No GlyphProps for char '${s.first().toHex()}' " +
                                "(${s.first().charInfo()})")
                    else
                        0
                    ))
        }

        val cacheObj = getCache(s.getHash())

        if (cacheObj != null) {
            return cacheObj.glyphLayout!!.width * scale
        }
        else {
            return buildPosMap(s).width * scale
        }
    }



    /**
     * THE function to typeset all the letters and their diacritics
     *
     * @return Pair of X-positions and Y-positions, of which the X-position's size is greater than the string
     * and the last element marks the width of entire string.
     */
    private fun buildPosMap(str: CodepointSequence): Posmap {
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


//                    if (thisChar in 0xF0000 until 0xF0060)
//                        dbgprn("char: ${thisChar.charInfo()}\nproperties: $thisProp")


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
                        // apply interchar only if this character is NOT a control char
                        val thisInterchar = if (thisChar.isLetter()) interchar else 0

                        posXbuffer[charIndex] = -thisProp.nudgeX +
                                when (itsProp.alignWhere) {
                                    GlyphProps.ALIGN_RIGHT ->
                                        posXbuffer[nonDiacriticCounter] + W_VAR_INIT + alignmentOffset + thisInterchar + kerning + extraWidth
                                    GlyphProps.ALIGN_CENTRE ->
                                        posXbuffer[nonDiacriticCounter] + HALF_VAR_INIT + itsProp.width + alignmentOffset + thisInterchar + kerning + extraWidth
                                    else ->
                                        posXbuffer[nonDiacriticCounter] + itsProp.width + alignmentOffset + thisInterchar + kerning + extraWidth
                                }
                        posYbuffer[charIndex] = -thisProp.nudgeY

                        nonDiacriticCounter = charIndex
                        extraWidth = thisProp.nudgeX // This resets extraWidth. NOTE: sign is flipped!

                        stackUpwardCounter = 0
                        stackDownwardCounter = 0
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
                        posXbuffer[charIndex] = -thisProp.nudgeX +
                                when (thisProp.alignWhere) {
                                    GlyphProps.ALIGN_LEFT, GlyphProps.ALIGN_BEFORE -> posXbuffer[nonDiacriticCounter]
                                    GlyphProps.ALIGN_RIGHT -> {
//                                        println("thisprop alignright $kerning, $extraWidth")

                                        val anchorPoint =
                                            if (!itsProp.diacriticsAnchors[diacriticsType].xUsed) itsProp.width else itsProp.diacriticsAnchors[diacriticsType].x

                                        extraWidth += thisProp.width
                                        posXbuffer[nonDiacriticCounter] + anchorPoint - W_VAR_INIT + kerning + extraWidth
                                    }
                                    GlyphProps.ALIGN_CENTRE -> {
                                        val anchorPoint =
                                            if (!itsProp.diacriticsAnchors[diacriticsType].xUsed) itsProp.width.div(2) else itsProp.diacriticsAnchors[diacriticsType].x

                                        if (itsProp.alignWhere == GlyphProps.ALIGN_RIGHT) {
                                            posXbuffer[nonDiacriticCounter] + anchorPoint + (itsProp.width + 1).div(2)
                                        } else {
                                            posXbuffer[nonDiacriticCounter] + anchorPoint - HALF_VAR_INIT
                                        }
                                    }
                                    else -> throw InternalError("Unsupported alignment: ${thisProp.alignWhere}")
                                }


                        // set Y pos according to diacritics position
                        when (thisProp.stackWhere) {
                            GlyphProps.STACK_DOWN -> {
                                posYbuffer[charIndex] = -thisProp.nudgeY + (H_DIACRITICS * stackDownwardCounter + -thisProp.nudgeY) * flipY.toSign()
                                stackDownwardCounter++
                            }
                            GlyphProps.STACK_UP -> {
                                posYbuffer[charIndex] = -thisProp.nudgeY + (-H_DIACRITICS * stackUpwardCounter + -thisProp.nudgeY) * flipY.toSign()
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
                                posYbuffer[charIndex] = -thisProp.nudgeY + (H_DIACRITICS * stackDownwardCounter + -thisProp.nudgeY) * flipY.toSign()
                                stackDownwardCounter++


                                posYbuffer[charIndex] = -thisProp.nudgeY + (-H_DIACRITICS * stackUpwardCounter + -thisProp.nudgeY) * flipY.toSign()
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

                        // Don't reset extraWidth here!
                    }
                }
            }

            // fill the last of the posXbuffer
            if (str.isNotEmpty()) {
                val lastCharProp = glyphProps[str.last()]
                val penultCharProp = glyphProps[str[nonDiacriticCounter]] ?:
                (if (errorOnUnknownChar) throw throw InternalError("No GlyphProps for char '${str[nonDiacriticCounter]}' " +
                        "(${str[nonDiacriticCounter].charInfo()})") else nullProp)
                posXbuffer[posXbuffer.lastIndex] = posXbuffer[posXbuffer.lastIndex - 1] + // DON'T add 1 to house the shadow, it totally breaks stuffs
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

        return Posmap(posXbuffer, posYbuffer)
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

    internal fun normaliseStringForMovableType(s: CharSequence) = s.toCodePoints(2)

    // basically an Unicode NFD with some additional flavours
    /**
     * @param normaliseOption 1-full, 2-omit null filling
     */
    private fun CodepointSequence.normalise(normaliseOption: Int = 1): CodepointSequence {
        val seq0 = CodepointSequence()
        val seq = CodepointSequence()
        val seq2 = CodepointSequence()
        val seq3 = CodepointSequence()
        val seq4 = CodepointSequence()
        val seq5 = CodepointSequence()
        val dis = this.utf16to32()

        var i = 0

//        dbgprn("Charsequence: ${dis.map { "${it.toChar()}${ZWNJ.toChar()}" }.joinToString(" ")}")

        seq0.add(0)
        while (i < dis.size) {
            var c = dis[i]
            val cNext = dis.getOrElse(i+1) { -1 }

            // replace characters in-line
            textReplaces[c]?.let {
                c = it
            }

            // turn Unicode Devanagari consonants into the internal counterpart
            if (c in 0x0915..0x0939 || c in 0x0958..0x095F)
                if (cNext == DEVANAGARI_NUQTA) {
                    seq0.add(c.toDevaInternal().internalDevaAddNukta())
                    i += 1
                }
                else {
                    seq0.add(c.toDevaInternal())
                }
            // re-order Sundanese diacritics
            else if ((c == 0x1BA1 || c == 0x1BA2) && cNext == 0x1BA5) {
                seq0.add(cNext); seq0.add(c); i += 1
            }
            // combine two Sundanese diacritics to internal counterpart
            else if (c == 0x1BA4 && cNext == 0x1B80) { seq0.add(SUNDANESE_ING); i += 1 }
            else if (c == 0x1BA8 && cNext == 0x1B80) { seq0.add(SUNDANESE_ENG); i += 1 }
            else if (c == 0x1BA9 && cNext == 0x1B80) { seq0.add(SUNDANESE_EUNG); i += 1 }
            else if (c == 0x1BA4 && cNext == 0x1B81) { seq0.add(SUNDANESE_IR); i += 1 }
            else if (c == 0x1BA8 && cNext == 0x1B81) { seq0.add(SUNDANESE_ER); i += 1 }
            else if (c == 0x1BA9 && cNext == 0x1B81) { seq0.add(SUNDANESE_EUR); i += 1 }
            else if (c == 0x1BA3 && cNext == 0x1BA5) { seq0.add(SUNDANESE_LU); i += 1 }
            else
                seq0.add(c)


            i += 1
        }
        seq0.add(0)



        i = 0
        while (i < seq0.size) {
//            val cPrev2 = seq0.getOrElse(i-2) { -1 }
            val cPrev = seq0.getOrElse(i-1) { -1 }
            val c = seq0[i]
            val cNext = seq0.getOrElse(i+1) { -1 }
            val cNext2 = seq0.getOrElse(i+2) { -1 }
            val cNext3 = seq0.getOrElse(i+3) { -1 }
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
            // tamil vowel I lig
            else if (c == 0xB99 && cNext == TAMIL_I) {
                seq.add(0xF00F0); i++
            }
            else if (c == 0xBAA && cNext == TAMIL_I) {
                seq.add(0xF00F1); i++
            }
            else if (c == 0xBAF && cNext == TAMIL_I) {
                seq.add(0xF00F2); i++
            }
            else if (c == 0xBB2 && cNext == TAMIL_I) {
                seq.add(0xF00F3); i++
            }
            else if (c == 0xBB5 && cNext == TAMIL_I) {
                seq.add(0xF00F4); i++
            }
            else if (c == 0xBB8 && cNext == TAMIL_I) {
                seq.add(0xF00F5); i++
            }
            else if (c == 0xB95 && cNext == 0xBCD && cNext2 == 0xBB7) {
                seq.add(TAMIL_KSSA); i += 2
            }
            // there are TWO ways to represent Tamil SHRII
            // https://www.unicode.org/L2/L2018/18054-tamil-shri.txt
            else if (c == 0xBB6 && cNext == 0xBCD && cNext2 == 0xBB0 && cNext3 == 0xBC0) {
                seq.add(TAMIL_SHRII); i += 3
            }
            else if (c == 0xBB8 && cNext == 0xBCD && cNext2 == 0xBB0 && cNext3 == 0xBC0) {
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
            // Alternative Forms of Cluster-initial RA
            else if (c == DEVANAGARI_RA && cNext == ZWJ && cNext2 == DEVANAGARI_VIRAMA && cNext3 == DEVANAGARI_YA) {
                seq.add(DEVANAGARI_RYA); i += 3
            }
            else if (c == DEVANAGARI_RA && cNext == ZWJ && cNext2 == DEVANAGARI_VIRAMA) {
                seq.add(DEVANAGARI_RA); i += 2
            }
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
            // Unicode Devanagari Rendering Rule R2-R4
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
//                dbgprn("  nop")

                seq.add(c)
            }

            i++
        }

        // BEGIN of Devanagari String Replacer 2 (lookbehind type)
        i = 0
        while (i < seq.size) {

            val cPrev2 = seq.getOrElse(i-2) { -1 }
            val cPrev = seq.getOrElse(i-1) { -1 }
            val c = seq[i]

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

            i++
        }
        // END of Devanagari String Replacer 2


        // second scan
        i = 0
        while (i < seq.size) {
            // swap position of {letter, diacritics that comes before the letter}
            // reposition [cluster, align-before, align-after] into [align-before, cluster, align-after]
            if (i > 0 && (glyphProps[seq[i]] ?: nullProp).alignWhere == GlyphProps.ALIGN_BEFORE) {
                val vowel = seq[i]
//                dbgprn("Vowel realign: index $i, ${vowel.charInfo()}")
                if (isDevanagari(vowel)) {
                    // scan for the consonant cluster backwards
                    // [not ligature glyphs] h h h h h c l r
                    var scanCounter = 1
                    while (true) {
                        val cAtCurs = seq.getOrElse(i - scanCounter) { -1 }
//                        dbgprn("    scan back $scanCounter, char: ${cAtCurs.charInfo()}")
                        if (scanCounter == 1 && devanagariConsonantsNonLig.contains(cAtCurs) ||
                            scanCounter > 1 && devanariConsonantsHalfs.contains(cAtCurs))
                            scanCounter += 1
                        else
                            break
                    } // scanCounter points at the terminator. the left-vowel must be placed at (i - scanCounter + 1)

                    seq.removeAt(i)
                    seq.add(i - scanCounter + 1, vowel)
                }
                else {
                    val t = seq[i - 1]
                    seq[i - 1] = seq[i]
                    seq[i] = t
                }
            }

            i++
        }


        // continuous ligation
        i = 0
        while (i < seq.size) {

            val cPrev = seq.getOrElse(i-1) { -1 }
            val c = seq[i]
            val cNext = seq.getOrElse(i+1) { -1 }

            // ligate IPA intonation graph
            if (c in 0x2E5..0x2E9 && cNext in 0x2E5..0x2E9) {
                seq2.add(0x200A)
                seq2.add(getIntonationGraph(c-0x2E5, cNext-0x2E5))
            }
            else if (cPrev in 0x2E5..0x2E9 && c in 0x2E5..0x2E9 && cNext !in 0x2E5..0x2E9) {
                seq2.add(0xFFE39)
            }
            else {
                seq2.add(c)
            }

            i++
        }


        // unpack replacewith
        // also ligate IPA intonation graph
        seq2.forEach {
            if (glyphProps[it]?.isPragma("replacewith") == true) {
//                dbgprn("Replacing ${it.charInfo()} into: ${glyphProps[it]!!.extInfo.map { it.toString(16) }.joinToString()}")
                glyphProps[it]!!.forEachExtInfo {
                    if (it != 0) seq3.add(it)
                }
            }
            else {
                seq3.add(it)
            }
        }

        // reposition devanagari RA-initials into RAsup
        i = 0
        dbgprnLig("seq3 = ${seq3.map { "${it.toCh()}${ZWNJ.toChar()}" }.joinToString(" ")}")

        val yankedCharacters  = Stack<Pair<Int, CodePoint>>() // Stack of <Position, CodePoint>; codepoint use -1 if not applicable
        var yankedDevanagariRaStatus = intArrayOf(0,0) // 0: none, 1: consonants, 2: virama, 3: vowel for this syllable
        var sawLeftI = false
        fun changeRaStatus(n: Int) {
            yankedDevanagariRaStatus[0] = yankedDevanagariRaStatus[1]
            yankedDevanagariRaStatus[1] = n
        }
        fun resetRaStatus() {
            yankedDevanagariRaStatus[0] = 0
            yankedDevanagariRaStatus[1] = 0

            sawLeftI = false
        }

        fun emptyOutYanked() {
            while (!yankedCharacters.empty()) {
                val poppedChar = yankedCharacters.pop()
                if (poppedChar.second == DEVANAGARI_RA)
                    if (seq4.last() in devanagariSuperscripts || sawLeftI)
                        seq4.add(DEVANAGARI_RA_SUPER_COMPLEX)
                    else
                        seq4.add(DEVANAGARI_RA_SUPER)
                else
                    seq4.add(yankedCharacters.pop().second)
            }
        }
        while (i < seq3.size) {
            val cPrev2 = seq3.getOrElse(i-2) { -1 }
            val cPrev = seq3.getOrElse(i-1) { -1 }
            val c = seq3[i]
            val cNext = seq3.getOrElse(i+1) { -1 }
            val cNext2 = seq3.getOrElse(i+2) { -1 }

            dbgprnLig("  र${yankedDevanagariRaStatus[1]} Chars: ${cPrev2.toCh()}${ZWNJ.toChar()} ${cPrev.toCh()}${ZWNJ.toChar()} [ ${c.toCh()}${ZWNJ.toChar()} ] ${cNext.toCh()}${ZWNJ.toChar()} ${cNext2.toCh()}${ZWNJ.toChar()}")


            // in Regex: RA vir VL* (C vir C (vir C)*)? VR* ᴿ [not V && not vir]
            // TERMINATOR: right vowel | non-half consonant before any consonants
            if (yankedDevanagariRaStatus[1] == 0 && c == DEVANAGARI_RA && cNext == DEVANAGARI_VIRAMA) {
                dbgprnLig("   Yanking RA (0 -> 1)")
                yankedCharacters.push(i to c)
                changeRaStatus(1)
            }
            else if (yankedDevanagariRaStatus[1] == 1 && yankedDevanagariRaStatus[0] == 0 && c == DEVANAGARI_VIRAMA) {
                dbgprnLig("   First Virama (1 -> 2)")
                changeRaStatus(2)
            }
            else if (yankedDevanagariRaStatus[1] == 2 && devanagariConsonants.contains(c)) {
                dbgprnLig("   Consonants after Virama (2 -> 1)")
                seq4.add(c)
                changeRaStatus(1)
            }
            else if (yankedDevanagariRaStatus[1] in listOf(1,3,5) && devanariConsonantsHalfs.contains(c)) {
                dbgprnLig("   Consonants Half Form (${yankedDevanagariRaStatus[1]} -> 3)")
                seq4.add(c)
                changeRaStatus(3)
            }
            else if (yankedDevanagariRaStatus[1] == 5 && devanagariConsonants.contains(c)) {
                dbgprnLig("   Consonants after Left Vowel (5 -> 1)")
                seq4.add(c)
                changeRaStatus(1)

                if (yankedDevanagariRaStatus[1] > 0) {
                    dbgprnLig("   Popping out RAsup (2)")
                    yankedCharacters.pop()
                    if (seq4.last() in devanagariSuperscripts || sawLeftI)
                        seq4.add(DEVANAGARI_RA_SUPER_COMPLEX)
                    else
                        seq4.add(DEVANAGARI_RA_SUPER)
                    resetRaStatus()
                }
            }
            else if ((yankedDevanagariRaStatus[1] > 0) && devanagariRightVowels.contains(c)) {
                dbgprnLig("   Right Vowels (${yankedDevanagariRaStatus[1]} -> 4)")
                seq4.add(c)
                changeRaStatus(4)
            }
            else if ((yankedDevanagariRaStatus[1] in 1..3) && devanagariVowels.contains(c)) {
                dbgprnLig("   Left Vowels (${yankedDevanagariRaStatus[1]} -> 5)")
                sawLeftI = true
                seq4.add(c)
                changeRaStatus(5)
            }
            else if (yankedDevanagariRaStatus[1] > 0 && devanariConsonantsHalfs.contains(cPrev) && devanagariConsonants.contains(c)) {
                dbgprnLig("   Consonant Tail after Halfs (${yankedDevanagariRaStatus[1]} -> 9)")
                seq4.add(c)
                changeRaStatus(9)
            }
            // -- termination or illegal state for Devanagari RA
            else if (yankedDevanagariRaStatus[1] > 0) {
                dbgprnLig("   Popping out RAsup")
                yankedCharacters.pop()
                if (seq4.last() in devanagariSuperscripts || sawLeftI)
                    seq4.add(DEVANAGARI_RA_SUPER_COMPLEX)
                else
                    seq4.add(DEVANAGARI_RA_SUPER)
                resetRaStatus()
                i-- // scan this character again next time
            }
            else if (!isDevanagari(c) && !yankedCharacters.empty()) {
                emptyOutYanked()
                seq4.add(c)
                resetRaStatus()
            }
            else {
                seq4.add(c)
            }

            i++
        }
        emptyOutYanked()

        seq4.add(0) // add dummy terminator

//        println("seq4 = " + seq4.joinToString(" ") { it.toCh() })

        // replace devanagari I/II with variants
        i = 0
        while (i < seq4.size) {
            val cPrev = seq4.getOrElse(i - 1) { -1 }
            val c = seq4[i]

            if (c == DEVANAGARI_I) {
                var j = 1
                var w = 0
                while (true) {
                    val cj = seq4.getOrElse(i + j) { -1 }
                    if (j > 3 || cj !in 0xF0140..0xF04FF)
                        break

                    if (cj in devanagariPresentationConsonants || cj in devanagariPresentationConsonantsWithRa) {
                        w += glyphProps[cj]?.diacriticsAnchors?.get(0)?.x ?: 0
                        break
                    }
                    else if (cj in devanagariPresentationConsonantsHalf || cj in devanagariPresentationConsonantsWithRaHalf) {
                        w += glyphProps[cj]?.width ?: 0
                        j += 1
                    }
                    else
                        break
                }

//                println("length: $w, consonant count: $j")

                seq4[i] = (w+2).coerceIn(6,21) - 6 + 0xF0110

                if (j > 1) i += j
            }
            else if (c == DEVANAGARI_II &&
                    (cPrev in devanagariPresentationConsonants || cPrev in devanagariPresentationConsonantsWithRa)) {
                val w = ((glyphProps[cPrev]?.width ?: 0) - (glyphProps[cPrev]?.diacriticsAnchors?.get(0)?.x ?: 0))

//                println("length: $w")

                seq4[i] = 0xF012F - ((w+1).coerceIn(4,19) - 4)
            }


            i++
        }


        // process charset overriding
        i = 0
        var charsetOverride = 0
        while (i < seq4.size) {
            val c = seq4[i]
            if (isCharsetOverride(c))
                charsetOverride = c - CHARSET_OVERRIDE_DEFAULT
            else {
                if (c in altCharsetCodepointDomains[charsetOverride])
                    seq5.add(c + altCharsetCodepointOffsets[charsetOverride])
                else
                    seq5.add(c)
            }

            i++
        }

//        println("seq5 = " + seq5.joinToString(" ") { it.toCh() })

        if (normaliseOption == 2) {
            while (seq5.remove(0)) {}
            return seq5
        }
        else {
            return seq5
        }
    }

    private fun dbgprnLig(i: Any) { if (false) println("[${this.javaClass.simpleName}] $i") }

    private fun CodePoint.toCh() = if (this >= 0xF0000) this.puaToUni() else if (this < 65536) "${this.toChar()}" else this.toHex()

    private val devaSyll = listOf("K","KH","G","GH","NG","C","CH","J","JH","NY","TT","TTH","DD","DDH","NN","T",
        "TH","D","DH","N","NNN","P","PH","B","BH","M","Y","R","RR","L","LL","LLL",
        "V","SH","SS","S","H","Q","KHH","GHH","Z","DDDH","RH","F","YY","x","x","x",
        "D.R.Y","K.SS","J.NY","T.T","N.T","N.N","S.P","SS.V","SH.C","SH.N","SH.V","x","x","x","x","x",
        "D.G","D.GH","D.D","D.DH","D.N","D.SS","D.BH","D.M","D.Y","D.V","mDD.DD","mDD.DDH","K.T","GH.TT","GH.TTH","GH.DDH",
        "P.TT","P.TTH","P.DDH","SS.TT","SS.TTH","SS.DDH","H.NN","H.T","H.M","H.Y","H.L","H.V","x","x","x","x",
        "DD.G","DD.BH","NG.G","NG.V","NG.M","CH.V","TT.TT","TT.TTH","TT.V","TTH.TTH","TTH.V","DD.DD","DD.DDH","DD.V","DDH.DDH","DDH.V"
    )
    // nuke this function when the time that compiled bytecode exceeds 64 kb finally arrives
    private fun CodePoint.puaToUni() = when (this) {
        0xF0100 -> "Ru"
        0xF0101 -> "Ruu"
        0xF0102 -> "RRu"
        0xF0103 -> "RRuu"
        0xF0104 -> "Hu"
        0xF0105 -> "Huu"
        0xF010B -> "ᴿᵃ"
        0xF010C -> "ᴿ¹"
        0xF010D -> "ᴿ²"
        0xF010E -> "DDRA"
        0xF010F -> "ᶴ"
        0xF024C -> "Resh"
        in 0xF0110..0xF011F -> "I-${(this - 0xF0110 + 1)}"
        in 0xF0120..0xF012F -> "II-${(this - 0xF0120 + 1)}"
        in 0xF0140 until 0xF0140+devaSyll.size -> devaSyll[this - 0xF0140]
        in 0xF0230 until 0xF0230+devaSyll.size -> devaSyll[this - 0xF0230] + "ʰ"
        in 0xF0320 until 0xF0320+devaSyll.size -> devaSyll[this - 0xF0320] + ".R"
        in 0xF0410 until 0xF0410+devaSyll.size -> devaSyll[this - 0xF0410] + ".Rʰ"
        else -> "<${this.toHex()}>"
    }


    /** Takes input string, do normalisation, and returns sequence of codepoints (Int)
     *
     * UTF-16 to ArrayList of Int. UTF-16 is because of Java
     * Note: CharSequence IS a String. java.lang.String implements CharSequence.
     *
     * Note to Programmer: DO NOT USE CHAR LITERALS, CODE EDITORS WILL CHANGE IT TO SOMETHING ELSE !!
     *
     * @param normaliseOption 0-don't, 1-full, 2-omit null filling
     */
    private fun CharSequence.toCodePoints(normaliseOption: Int = 1): CodepointSequence {
        val seq = CodepointSequence()
        this.forEach { seq.add(it.toInt()) }

        return when (normaliseOption) {
            0 -> seq
            else -> seq.normalise(normaliseOption)
        }
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
    val charsetOverrideCodestyle = Character.toChars(CHARSET_OVERRIDE_CODESTYLE).toSurrogatedString()

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
        // dirty way of special rule sue me lol
        if (lowercaseRs.contains(prevChar) && dots.contains(thisChar)) {
            return -1
        }

        // use keming machine
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




    private fun CodePoint.toHalfFormOrNull(): CodePoint? {
        if (this in devanagariBaseConsonantsExtended) return null
        if (this < 0xF0000) throw IllegalArgumentException("Normalise consonants to internal encoding first!")

        if (this == DEVANAGARI_RYA) return DEVANAGARI_HALF_RYA
        if (this == MARWARI_LIG_DD_Y) return MARWARI_HALFLIG_DD_Y
        if (this == DEVANAGARI_OPEN_YA) return DEVANAGARI_OPEN_HALF_YA

        (this + 240).let {
            if (glyphProps[it]?.isIllegal != false)
                return null
            else
                return it
        }
    }


    // TODO use proper version of Virama for respective scripts
    private fun CodePoint.toHalfFormOrVirama(): List<CodePoint> = this.toHalfFormOrNull().let {
//            println("[TerrarumSansBitmap] toHalfForm ${this.charInfo()} = ${it?.charInfo()}")
        if (it == null) listOf(this, DEVANAGARI_VIRAMA) else listOf(it)
    }


    // TODO use proper version of Virama for respective scripts
    private fun toRaAppended(c: CodePoint): List<CodePoint> {
        if (c == MARWARI_DD) return listOf(MARWARI_LIG_DD_R)

        (c + 480).let {
            if (glyphProps[it]?.isIllegal != false)
                return listOf(c, DEVANAGARI_VIRAMA, DEVANAGARI_RA)
            else
                return listOf(it)
        }
    }



    private fun ligateIndicConsonants(c1: CodePoint, c2: CodePoint, rec: Int = 0): List<CodePoint> {
//        dbgprn("Indic ligation${if (rec > 0) "$rec" else ""} ${c1.toCh()} - ${c2.toCh()}")
        if (c1 != DEVANAGARI_RA && c2 == DEVANAGARI_RA) return toRaAppended(c1) // Devanagari @.RA
        // when the font try to ligate KSSR, the arguments are K and SSR (for some reason I don't understand).
        // This method drops last Ra on c2 and then recursively ligates the remainder KSS, finally
        // attaches Ra on the conjunct and returns the results.
        else if (c1 != DEVANAGARI_RA && isRaAppended(c2)) {
//            dbgprn("Ends with RA, trying Rlig...")
            val c12WithNoRa = ligateIndicConsonants(c1, c2 - 480, rec + 1)
            if (c12WithNoRa.size == 1) {
                val c12andRa = toRaAppended(c12WithNoRa[0])
                if (c12andRa.size == 1) {
//                    dbgprn("Rligation successful: ${c12WithNoRa[0].toCh()} + R = ${c12andRa[0].toCh()}")
                    return c12andRa
                }
//                dbgprn("Rligation failed: ${c12WithNoRa[0].toCh()} + R = ${c12WithNoRa.map { it.toCh() }.joinToString(" + ")}")
            }
            // only return when ligation is possible, otherwise let the process continue so that
            // Ka-Vir-SSRa form could be returned
//            else dbgprn("Ligation failed, trying ${c1.toCh()} - ${c2.toCh()}")
        }
//        dbgprn("continue$rec: ${c1.toCh()} - ${c2.toCh()}")
        return when (c1) {
            0x0915.toDevaInternal() -> /* Devanagari KA */ when (c2) {
                0x0924.toDevaInternal() -> listOf(DEVANAGARI_LIG_K_T) // K.T
                0x0937.toDevaInternal() -> listOf(DEVANAGARI_LIG_K_SS) // K.SS
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // K.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0918.toDevaInternal() -> /* Devanagari GHA */ when (c2) {
                0x091F.toDevaInternal() -> listOf(0xF01BD) // GH.TT
                0x0920.toDevaInternal() -> listOf(0xF01BE) // GH.TTH
                0x0922.toDevaInternal() -> listOf(0xF01BF) // GH.DDH
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0919.toDevaInternal() -> /* Devanagari NGA */ when (c2) {
                0x0928.toDevaInternal() -> listOf(0xF01CD) // NG.N
                0x0915.toDevaInternal() -> listOf(0xF01CE) // NG.K
                0x0916.toDevaInternal() -> listOf(0xF01CF) // NG.KH
                0x0917.toDevaInternal() -> listOf(0xF01D2) // NG.G
                0x0918.toDevaInternal() -> listOf(0xF01D3) // NG.GH
                0x092E.toDevaInternal() -> listOf(0xF01D4) // NG.M
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // NG.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x091B.toDevaInternal() -> /* Devanagari CHA */ when (c2) {
                DEVANAGARI_VA -> listOf(0xF01D5) // CH.V
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // CH.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x091C.toDevaInternal() -> /* Devanagari JA */ when (c2) {
                0x091E.toDevaInternal() -> listOf(DEVANAGARI_LIG_J_NY) // J.NY
                DEVANAGARI_YA -> listOf(DEVANAGARI_LIG_J_Y) // J.Y
                DEVANAGARI_LIG_J_Y -> listOf(DEVANAGARI_LIG_J_J_Y) // J.J.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x091F.toDevaInternal() -> /* Devanagari TTA */ when (c2) {
                0x0915.toDevaInternal() -> listOf(0xF01E0) // TT.K
                0x092A.toDevaInternal() -> listOf(0xF01E1) // TT.P
                0x0936.toDevaInternal() -> listOf(0xF01E2) // TT.SH
                0x0938.toDevaInternal() -> listOf(0xF01E3) // TT.S
                0x091F.toDevaInternal() -> listOf(0xF01D6) // TT.TT
                0x0920.toDevaInternal() -> listOf(0xF01D7) // TT.TTH
                DEVANAGARI_VA -> listOf(0xF01D8) // TT.V
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // TT.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0920.toDevaInternal() -> /* Devanagari TTHA */ when (c2) {
                0x0920.toDevaInternal() -> listOf(0xF01D9) // TTH.TTH
                DEVANAGARI_VA -> listOf(0xF01DA) // TTH.V
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // TTH.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0921.toDevaInternal() -> /* Devanagari DDA */ when (c2) {
                0x0921.toDevaInternal() -> listOf(0xF01DB) // DD.DD
                0x0922.toDevaInternal() -> listOf(0xF01DC) // DD.DDH
                0x0917.toDevaInternal() -> listOf(0xF01D0) // DD.G
                0x092D.toDevaInternal() -> listOf(0xF01D1) // DD.BH
                DEVANAGARI_VA -> listOf(0xF01DD) // DD.V
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // DD.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0922.toDevaInternal() -> /* Devanagari DDHA */ when (c2) {
                0x0922.toDevaInternal() -> listOf(0xF01DE) // DDH.DDH
                DEVANAGARI_VA -> listOf(0xF01DF) // DDH.V
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // DDH.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0924.toDevaInternal() -> /* Devanagari TA */ when (c2) {
                0x0924.toDevaInternal() -> listOf(DEVANAGARI_LIG_T_T) // T.T
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0926.toDevaInternal() -> /* Devanagari DA */ when (c2) {
                0x0917.toDevaInternal() -> listOf(0xF01B0) // D.G
                0x0918.toDevaInternal() -> listOf(0xF01B1) // D.GH
                0x0926.toDevaInternal() -> listOf(0xF01B2) // D.D
                0x0927.toDevaInternal() -> listOf(0xF01B3) // D.DH
                0x0928.toDevaInternal() -> listOf(0xF01B4) // D.N
                0x092C.toDevaInternal() -> listOf(0xF01B5) // D.B
                0x092D.toDevaInternal() -> listOf(0xF01B6) // D.BH
                0x092E.toDevaInternal() -> listOf(0xF01B7) // D.M
                0x092F.toDevaInternal() -> listOf(0xF01B8) // D.Y
                0x0935.toDevaInternal() -> listOf(0xF01B9) // D.V
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0928.toDevaInternal() -> /* Devanagari NA */ when (c2) {
                0x0924.toDevaInternal() -> listOf(DEVANAGARI_LIG_N_T) // N.T
                0x0928.toDevaInternal() -> listOf(DEVANAGARI_LIG_N_N) // N.N
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x092A.toDevaInternal() -> /* Devanagari PA */ when (c2) {
                0x091F.toDevaInternal() -> listOf(0xF01C0) // P.TT
                0x0920.toDevaInternal() -> listOf(0xF01C1) // P.TTH
                0x0922.toDevaInternal() -> listOf(0xF01C2) // P.DDH
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0936.toDevaInternal() -> /* Devanagari SHA */ when (c2) {
                0x091A.toDevaInternal() -> listOf(DEVANAGARI_LIG_SH_C) // SH.C
                0x0928.toDevaInternal() -> listOf(DEVANAGARI_LIG_SH_N) // SH.N
                0x0932.toDevaInternal() -> listOf(DEVANAGARI_ALT_HALF_SHA, c2) // SH.L
                0x0935.toDevaInternal() -> listOf(DEVANAGARI_LIG_SH_V) // SH.V
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0937.toDevaInternal() -> /* Devanagari SSA */ when (c2) {
                0x091F.toDevaInternal() -> listOf(0xF01C3) // SS.TT
                0x0920.toDevaInternal() -> listOf(0xF01C4) // SS.TTH
                0x0922.toDevaInternal() -> listOf(0xF01C5) // SS.DDH
                0x092A.toDevaInternal() -> listOf(DEVANAGARI_LIG_SS_P) // SS.P
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0938.toDevaInternal() -> /* Devanagari SA */ when (c2) {
                0x0935.toDevaInternal() -> listOf(DEVANAGARI_LIG_S_V) // S.V
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0939.toDevaInternal() -> /* Devanagari HA */ when (c2) {
                0x0923.toDevaInternal() -> listOf(0xF01C6) // H.NN
                0x0928.toDevaInternal() -> listOf(0xF01C7) // H.N
                0x092E.toDevaInternal() -> listOf(0xF01C8) // H.M
                0x092F.toDevaInternal() -> listOf(0xF01C9) // H.Y
                0x0932.toDevaInternal() -> listOf(0xF01CA) // H.L
                0x0935.toDevaInternal() -> listOf(0xF01CB) // H.V
                else -> c1.toHalfFormOrVirama() + c2
            }
            0x0978 -> /* Marwari DDA */ when (c2) {
                0x0978 -> listOf(MARWARI_LIG_DD_DD) // DD.DD
                0x0922.toDevaInternal() -> listOf(MARWARI_LIG_DD_DDH) // DD.DDH
                DEVANAGARI_YA -> listOf(MARWARI_LIG_DD_Y) // DD.Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            0xF0331 -> /* Devanagari D.RA */ when (c2) {
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA // D.R+Y
                else -> c1.toHalfFormOrVirama() + c2
            }
            in (0xF01B0..0xF01DF) + (0xF0390..0xF03BF) -> when (c2) {
                DEVANAGARI_YA -> c1.toHalfFormOrVirama() + DEVANAGARI_OPEN_YA
                else -> c1.toHalfFormOrVirama() + c2
            }
            else -> c1.toHalfFormOrVirama() + c2 // TODO use proper version of Virama for respective scripts
        }
    }


    companion object {

        internal fun CodePoint.glueCharToGlueSize() = when (this) {
            ZWSP -> 0
            in 0xFFFE0..0xFFFEF -> -(this - 0xFFFE0 + 1)
            in 0xFFFF0..0xFFFFF -> this - 0xFFFF0 + 1
            else -> throw IllegalArgumentException()
        }

        const internal val linotypePaddingX = 16
        const internal val linotypePaddingY = 10

        const val LINE_HEIGHT = 24

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

        private fun Boolean.toSign() = if (this) 1 else -1

        /**
         * lowercase AND the height is equal to x-height (e.g. lowercase B, D, F, H, K, L, ... does not count
         */

        data class ShittyGlyphLayout(val textBuffer: CodepointSequence, val linotype: Texture, val width: Int)
        data class TextCacheObj(val hash: Long, val glyphLayout: ShittyGlyphLayout?): Comparable<TextCacheObj> {
            val text: CodepointSequence
                get() = glyphLayout!!.textBuffer
            val width: Int
                get() = glyphLayout!!.width
            val texture: Texture
                get() = glyphLayout!!.linotype
//            val penultimateChar: CodePoint
//                get() = text[text.size - 2]
            val penultimateCharOrNull: CodePoint?
                get() = text.getOrNull(text.size - 2)

            fun dispose() {
                glyphLayout?.linotype?.dispose()
            }

            override fun compareTo(other: TextCacheObj): Int {
                return (this.hash - other.hash).sign
            }
        }
        data class Posmap(val x: IntArray, val y: IntArray) {
            val width = x.maxOf { it }
        }


        private const val HCF = 0x115F
        private const val HJF = 0x1160

        internal const val JUNG_COUNT = 21
        internal const val JONG_COUNT = 28

        internal const val W_HANGUL_BASE = 13
        internal const val W_UNIHAN = 16
        internal const val W_LATIN_WIDE = 9 // width of regular letters
        internal const val W_VAR_INIT = 15 // it assumes width of 15 regardless of the tagged width
        internal const val W_WIDEVAR_INIT = 31 // it assumes width of 31 regardless of the tagged width

        internal const val HGAP_VAR = 1

        internal const val H = 20
        internal const val H_UNIHAN = 16

        internal const val H_DIACRITICS = 3

        internal const val H_STACKUP_LOWERCASE_SHIFTDOWN = 4
        internal const val H_OVERLAY_LOWERCASE_SHIFTDOWN = 2

        internal const val SIZE_CUSTOM_SYM = 20

        internal const val SHEET_ASCII_VARW =        0
        internal const val SHEET_HANGUL =            1
        internal const val SHEET_EXTA_VARW =         2
        internal const val SHEET_EXTB_VARW =         3
        internal const val SHEET_KANA =              4
        internal const val SHEET_CJK_PUNCT =         5
        internal const val SHEET_UNIHAN =            6
        internal const val SHEET_CYRILIC_VARW =      7
        internal const val SHEET_HALFWIDTH_FULLWIDTH_VARW = 8
        internal const val SHEET_UNI_PUNCT_VARW =    9
        internal const val SHEET_GREEK_VARW =        10
        internal const val SHEET_THAI_VARW =         11
        internal const val SHEET_HAYEREN_VARW =      12
        internal const val SHEET_KARTULI_VARW =      13
        internal const val SHEET_IPA_VARW =          14
        internal const val SHEET_RUNIC =             15
        internal const val SHEET_LATIN_EXT_ADD_VARW= 16
        internal const val SHEET_CUSTOM_SYM =        17
        internal const val SHEET_BULGARIAN_VARW =    18
        internal const val SHEET_SERBIAN_VARW =      19
        internal const val SHEET_TSALAGI_VARW =      20
        internal const val SHEET_PHONETIC_EXT_VARW = 21
        internal const val SHEET_DEVANAGARI_VARW=22
        internal const val SHEET_KARTULI_CAPS_VARW = 23
        internal const val SHEET_DIACRITICAL_MARKS_VARW = 24
        internal const val SHEET_GREEK_POLY_VARW   = 25
        internal const val SHEET_EXTC_VARW =         26
        internal const val SHEET_EXTD_VARW =         27
        internal const val SHEET_CURRENCIES_VARW =   28
        internal const val SHEET_INTERNAL_VARW = 29
        internal const val SHEET_LETTERLIKE_MATHS_VARW = 30
        internal const val SHEET_ENCLOSED_ALPHNUM_SUPL_VARW = 31
        internal const val SHEET_TAMIL_VARW = 32
        internal const val SHEET_BENGALI_VARW = 33
        internal const val SHEET_BRAILLE_VARW = 34
        internal const val SHEET_SUNDANESE_VARW = 35
        internal const val SHEET_DEVANAGARI2_INTERNAL_VARW = 36
        internal const val SHEET_CODESTYLE_ASCII_VARW = 37

        internal const val SHEET_UNKNOWN = 254

        // custom codepoints

        internal const val RICH_TEXT_MODIFIER_RUBY_MASTER = 0xFFFA0
        internal const val RICH_TEXT_MODIFIER_RUBY_SLAVE = 0xFFFA1
        internal const val RICH_TEXT_MODIFIER_SUPERSCRIPT = 0xFFFA2
        internal const val RICH_TEXT_MODIFIER_SUBSCRIPT = 0xFFFA3
        internal const val RICH_TEXT_MODIFIER_TAG_END = 0xFFFBF

        internal const val CHARSET_OVERRIDE_DEFAULT = 0xFFFC0
        internal const val CHARSET_OVERRIDE_BG_BG = 0xFFFC1
        internal const val CHARSET_OVERRIDE_SR_SR = 0xFFFC2
        internal const val CHARSET_OVERRIDE_CODESTYLE = 0xFFFC3

        const val FIXED_BLOCK_1 = 0xFFFD0
        const val MOVABLE_BLOCK_M1 = 0xFFFE0
        const val MOVABLE_BLOCK_1 = 0xFFFF0


        private val autoShiftDownOnLowercase = arrayOf(
            SHEET_DIACRITICAL_MARKS_VARW
        )

        private val fileList = arrayOf( // MUST BE MATCHING WITH SHEET INDICES!!
            "ascii_variable.tga",
            "hangul_johab.tga",
            "latinExtA_variable.tga",
            "latinExtB_variable.tga",
            "kana_variable.tga",
            "cjkpunct_variable.tga",
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
            "tamil_extrawide_variable.tga",
            "bengali_variable.tga",
            "braille_variable.tga",
            "sundanese_variable.tga",
            "devanagari_internal_extrawide_variable.tga",
            "pua_codestyle_ascii_variable.tga",
        )
        internal val codeRange = arrayOf( // MUST BE MATCHING WITH SHEET INDICES!!
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
            (0x900..0x97F) + (0xF0100..0xF04FF), // SHEET_DEVANAGARI_VARW
            0x1C90..0x1CBF, // SHEET_KARTULI_CAPS_VARW
            0x300..0x36F, // SHEET_DIACRITICAL_MARKS_VARW
            0x1F00..0x1FFF, // SHEET_GREEK_POLY_VARW
            0x2C60..0x2C7F, // SHEET_EXTC_VARW
            0xA720..0xA7FF, // SHEET_EXTD_VARW
            0x20A0..0x20CF, // SHEET_CURRENCIES_VARW
            0xFFE00..0xFFF9F, // SHEET_INTERNAL_VARW
            0x2100..0x214F, // SHEET_LETTERLIKE_MATHS_VARW
            0x1F100..0x1F1FF, // SHEET_ENCLOSED_ALPHNUM_SUPL_VARW
            (0x0B80..0x0BFF) + (0xF00C0..0xF00FF), // SHEET_TAMIL_VARW
            0x980..0x9FF, // SHEET_BENGALI_VARW
            0x2800..0x28FF, // SHEET_BRAILLE_VARW
            (0x1B80..0x1BBF) + (0x1CC0..0x1CCF) + (0xF0500..0xF050F), // SHEET_SUNDANESE_VARW
            0xF0110..0xF012F, // SHEET_DEVANAGARI2_INTERNAL_VARW
            0xF0520..0xF057F, // SHEET_CODESTYLE_ASCII_VARW
        )
        private val codeRangeHangulCompat = 0x3130..0x318F

        private val altCharsetCodepointOffsets = arrayOf(
                0, // null
                0xF0000 - 0x400, // bulgarian
                0xF0060 - 0x400, // serbian
                0xF0520 - 0x20, // codestyle
        )

        private val altCharsetCodepointDomains = arrayOf(
                0..0x10FFFF,
                0x400..0x45F,
                0x400..0x45F,
                0x20..0x7F,
        )

        private val diacriticDotRemoval = hashMapOf(
            'i'.toInt() to 0x131,
            'j'.toInt() to 0x237
        )

        internal fun Int.charInfo() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}: ${Character.getName(this)}"

        const val NQSP = 0x2000
        const val MQSP = 0x2001
        const val ENSP = 0x2002
        const val EMSP = 0x2003
        const val THREE_PER_EMSP = 0x2004
        const val QUARTER_EMSP = 0x2005
        const val SIX_PER_EMSP = 0x2006
        const val FSP = 0x2007
        const val PSP = 0x2008
        const val THSP = 0x2009
        const val HSP = 0x200A
        const val ZWSP = 0x200B
        const val ZWNJ = 0x200C
        const val ZWJ = 0x200D
        const val SHY = 0xAD
        const val NBSP = 0xA0
        const val OBJ = 0xFFFC

        private val tamilLigatingConsonants = listOf('க','ங','ச','ஞ','ட','ண','த','ந','ன','ப','ம','ய','ர','ற','ல','ள','ழ','வ').map { it.toInt() }.toIntArray() // this is the only thing that .indexOf() is called against, so NO HASHSET
        private const val TAMIL_KSSA = 0xF00ED
        private const val TAMIL_SHRII = 0xF00EE
        private const val TAMIL_I = 0xBBF


        private const val DEVANAGARI_VIRAMA = 0x94D
        private const val DEVANAGARI_NUQTA = 0x93C
        private val DEVANAGARI_RA = 0x930.toDevaInternal()
        private val DEVANAGARI_YA = 0x92F.toDevaInternal()
        private val DEVANAGARI_RRA = 0x931.toDevaInternal()
        private val DEVANAGARI_VA = 0x0935.toDevaInternal()
        private val DEVANAGARI_HA = 0x939.toDevaInternal()
        private const val DEVANAGARI_U = 0x941
        private const val DEVANAGARI_UU = 0x942
        private const val DEVANAGARI_I = 0x093F
        private const val DEVANAGARI_II = 0x0940
        private const val DEVANAGARI_RYA = 0xF0106
        private const val DEVANAGARI_HALF_RYA = 0xF0107

        private const val DEVANAGARI_SYLL_RU = 0xF0100
        private const val DEVANAGARI_SYLL_RUU = 0xF0101
        private const val DEVANAGARI_SYLL_RRU = 0xF0102
        private const val DEVANAGARI_SYLL_RRUU = 0xF0103
        private const val DEVANAGARI_SYLL_HU = 0xF0104
        private const val DEVANAGARI_SYLL_HUU = 0xF0105

        private const val DEVANAGARI_OPEN_YA = 0xF0108
        private const val DEVANAGARI_OPEN_HALF_YA = 0xF0109
        private const val DEVANAGARI_ALT_HALF_SHA = 0xF010F
        private const val DEVANAGARI_EYELASH_RA = 0xF010B
        private const val DEVANAGARI_RA_SUPER = 0xF010C
        private const val DEVANAGARI_RA_SUPER_COMPLEX = 0xF010D

        private const val MARWARI_DD = 0x978

        private const val DEVANAGARI_LIG_K_T = 0xF01BC
//        private const val DEVANAGARI_LIG_D_R_Y = 0xF01A0
        private const val DEVANAGARI_LIG_K_SS = 0xF01A1
        private const val DEVANAGARI_LIG_J_NY = 0xF01A2
        private const val DEVANAGARI_LIG_T_T = 0xF01A3
        private const val DEVANAGARI_LIG_N_T = 0xF01A4
        private const val DEVANAGARI_LIG_N_N = 0xF01A5
        private const val DEVANAGARI_LIG_S_V = 0xF01A6
        private const val DEVANAGARI_LIG_SS_P = 0xF01A7
        private const val DEVANAGARI_LIG_SH_C = 0xF01A8
        private const val DEVANAGARI_LIG_SH_N = 0xF01A9
        private const val DEVANAGARI_LIG_SH_V = 0xF01AA
        private const val DEVANAGARI_LIG_J_Y = 0xF01AB
        private const val DEVANAGARI_LIG_J_J_Y = 0xF01AC

        private const val MARWARI_LIG_DD_DD = 0xF01BA
        private const val MARWARI_LIG_DD_DDH = 0xF01BB
        private const val MARWARI_LIG_DD_Y = 0xF016E
        private const val MARWARI_HALFLIG_DD_Y = 0xF016F
        private const val MARWARI_LIG_DD_R = 0xF010E

        private const val SUNDANESE_ING = 0xF0500
        private const val SUNDANESE_ENG = 0xF0501
        private const val SUNDANESE_EUNG = 0xF0502
        private const val SUNDANESE_IR = 0xF0503
        private const val SUNDANESE_ER = 0xF0504
        private const val SUNDANESE_EUR = 0xF0505
        private const val SUNDANESE_LU = 0xF0506


        private val devanagariConsonants = ((0x0915..0x0939) + (0x0958..0x095F) + (0x0978..0x097F) +
                (0xF0140..0xF04FF) + (0xF0106..0xF0109)).toHashSet()
        private val devanagariVowels = ((0x093A..0x093C) + (0x093E..0x094C) + (0x094E..0x094F)).toHashSet()
        private val devanagariRightVowels = ((0x093A..0x093C) + 0x093E + (0x0940..0x094C) + 0x094F).toHashSet()

        private val devanagariBaseConsonants = 0x0915..0x0939
        private val devanagariBaseConsonantsWithNukta = 0x0958..0x095F
        private val devanagariBaseConsonantsExtended = 0x0978..0x097F
        private val devanagariPresentationConsonants = 0xF0140..0xF022F
        private val devanagariPresentationConsonantsHalf = 0xF0230..0xF031F
        private val devanagariPresentationConsonantsWithRa = 0xF0320..0xF040F
        private val devanagariPresentationConsonantsWithRaHalf = 0xF0410..0xF04FF

        private val devanagariSuperscripts = ((0x0900..0x0902) + (0x093A..0x093B) + 0x0940 + (0x0945..0x094C) + 0x094F + 0x0951 + (0x0953..0x0955)).toHashSet()

        private val devanagariConsonantsNonLig = (devanagariBaseConsonants +
                devanagariBaseConsonantsWithNukta + devanagariBaseConsonantsExtended +
                devanagariPresentationConsonants + devanagariPresentationConsonantsWithRa).toHashSet()

        private val devanariConsonantsHalfs = (devanagariPresentationConsonantsHalf +
                devanagariPresentationConsonantsWithRaHalf + listOf(DEVANAGARI_HALF_RYA, DEVANAGARI_OPEN_HALF_YA)).toHashSet()

        private fun Int.internalDevaAddNukta(): Int {
            return this + 48
        }

        private val devanagariUnicodeNuqtaTable = intArrayOf(0xF0170,0xF0171,0xF0172,0xF0177,0xF017C,0xF017D,0xF0186,0xF018A)

        private fun Int.toDevaInternal(): Int {
            if (this in 0x0915..0x0939) return this - 0x0915 + 0xF0140
            else if (this in 0x0958..0x095F) return devanagariUnicodeNuqtaTable[this - 0x0958]
            else throw IllegalArgumentException("No Internal form exists for ${this.charInfo()}")
        }

        private fun isRaAppended(c: CodePoint) = c in (0xF0320..0xF04FF)

        /**
         * @param tone1 0..4 where 0 is extra-high
         */
        private fun getIntonationGraph(tone1: Int, tone2: Int): CodePoint {
            return 0xFFE20 + tone1 * 5 + tone2
        }

        // If this letter is a candidate to be influenced by the interchar property (i.e. is this character visible or whitespace and not a control character)
        fun CodePoint.isLetter() = (Character.isLetterOrDigit(this) || Character.isWhitespace(this) || this in 0xF0000 until 0xFFF70)

        private fun Int.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

        // Hangul Implementation Specific //

        private fun getWanseongHanChoseong(hanIndex: Int) = hanIndex / (JUNG_COUNT * JONG_COUNT)
        private fun getWanseongHanJungseong(hanIndex: Int) = hanIndex / JONG_COUNT % JUNG_COUNT
        private fun getWanseongHanJongseong(hanIndex: Int) = hanIndex % JONG_COUNT

        // THESE ARRAYS MUST BE SORTED
        // ㅣ
        private val jungseongI = arrayOf(21,61).toSortedSet()
        // ㅗ ㅛ ㅜ ㅠ
        private val jungseongOU = arrayOf(9,13,14,18,34,35,39,45,51,53,54,64,73,80,83).toSortedSet()
        // ㅘ ㅙ ㅞ
        private val jungseongOUComplex = (arrayOf(10,11,16) + (22..33).toList() + arrayOf(36,37,38) + (41..44).toList() + (46..50).toList() + (56..59).toList() + arrayOf(63) + (67..72).toList() + (74..79).toList() + (81..83).toList() + (85..91).toList() + arrayOf(93, 94)).toSortedSet()
        // ㅐ ㅒ ㅔ ㅖ etc
        private val jungseongRightie = arrayOf(2,4,6,8,11,16,32,33,37,42,44,48,50,71,72,75,78,79,83,86,87,88,94).toSortedSet()
        // ㅚ *ㅝ* ㅟ
        private val jungseongOEWI = arrayOf(12,15,17,40,52,55,89,90,91).toSortedSet()
        // ㅡ
        private val jungseongEU = arrayOf(19,62,66).toSortedSet()
        // ㅢ
        private val jungseongYI = arrayOf(20,60,65).toSortedSet()
        // ㅜ ㅝ ㅞ ㅟ ㅠ
        private val jungseongUU = arrayOf(14,15,16,17,18,27,30,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,59,67,68,73,77,78,79,80,81,82,83,84,91).toSortedSet()

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
                else if (p in jungseongOEWI) 11
                else if (p in jungseongOUComplex) 7
                else if (p in jungseongOU) 5
                else if (p in jungseongEU) 9
                else if (p in jungseongYI) 13
                else 1

            if (f != 0) ret += 1
            //println("getHanInitialRow $i $p $f -> $ret")
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

        // latin specific //

        private val lowercaseRs = arrayOf(0x72, 0x155, 0x157, 0x159, 0x211, 0x213, 0x27c, 0x1e59, 0x1e58, 0x1e5f).toSortedSet()
        private val dots = arrayOf(0x2c, 0x2e).toSortedSet()

        // END latin //

        private fun isHangul(c: CodePoint) = c in codeRange[SHEET_HANGUL] || c in codeRangeHangulCompat
        private fun isBulgarian(c: CodePoint) = c in 0xF0000..0xF005F
        private fun isSerbian(c: CodePoint) = c in 0xF0060..0xF00BF
        fun isColourCode(c: CodePoint) = c == 0x100000 || c in 0x10F000..0x10FFFF
        private fun isCharsetOverride(c: CodePoint) = c in 0xFFFC0..0xFFFCF
        private fun isDevanagari(c: CodePoint) = c in codeRange[SHEET_DEVANAGARI_VARW]
        private fun isHangulCompat(c: CodePoint) = c in codeRangeHangulCompat


        private fun indexX(c: CodePoint) = c % 16
        private fun unihanIndexX(c: CodePoint) = (c - 0x3400) % 256

        private fun extAindexY(c: CodePoint) = (c - 0x100) / 16
        private fun extBindexY(c: CodePoint) = (c - 0x180) / 16
        private fun runicIndexY(c: CodePoint) = (c - 0x16A0) / 16
        private fun kanaIndexY(c: CodePoint) =
            if (c in 0x31F0..0x31FF) 12
            else if (c in 0x1B000..0x1B00F) 13
            else (c - 0x3040) / 16
        private fun cjkPunctIndexY(c: CodePoint) = (c - 0x3000) / 16
        private fun cyrilicIndexY(c: CodePoint) = (c - 0x400) / 16
        private fun bulgarianIndexY(c: CodePoint) = (c - 0xF0000) / 16
        private fun serbianIndexY(c: CodePoint) = (c - 0xF0060) / 16
        private fun fullwidthUniIndexY(c: CodePoint) = (c - 0xFF00) / 16
        private fun uniPunctIndexY(c: CodePoint) = (c - 0x2000) / 16
        private fun unihanIndexY(c: CodePoint) = (c - 0x3400) / 256
        private fun greekIndexY(c: CodePoint) = (c - 0x370) / 16
        private fun thaiIndexY(c: CodePoint) = (c - 0xE00) / 16
        private fun symbolIndexY(c: CodePoint) = (c - 0xE000) / 16
        private fun armenianIndexY(c: CodePoint) = (c - 0x530) / 16
        private fun kartvelianIndexY(c: CodePoint) = (c - 0x10D0) / 16
        private fun ipaIndexY(c: CodePoint) = (c - 0x250) / 16
        private fun latinExtAddY(c: CodePoint) = (c - 0x1E00) / 16
        private fun cherokeeIndexY(c: CodePoint) = (c - 0x13A0) / 16
        private fun phoneticExtIndexY(c: CodePoint) = (c - 0x1D00) / 16
        private fun devanagariIndexY(c: CodePoint) = (if (c < 0xF0000) (c - 0x0900) else (c - 0xF0080)) / 16
        private fun bengaliIndexY(c: CodePoint) = (c - 0x980) / 16
        private fun kartvelianCapsIndexY(c: CodePoint) = (c - 0x1C90) / 16
        private fun diacriticalMarksIndexY(c: CodePoint) = (c - 0x300) / 16
        private fun polytonicGreekIndexY(c: CodePoint) = (c - 0x1F00) / 16
        private fun extCIndexY(c: CodePoint) = (c - 0x2C60) / 16
        private fun extDIndexY(c: CodePoint) = (c - 0xA720) / 16
        private fun currenciesIndexY(c: CodePoint) = (c - 0x20A0) / 16
        private fun internalIndexY(c: CodePoint) = (c - 0xFFE00) / 16
        private fun letterlikeIndexY(c: CodePoint) = (c - 0x2100) / 16
        private fun enclosedAlphnumSuplY(c: CodePoint) = (c - 0x1F100) / 16
        private fun tamilIndexY(c: CodePoint) = (if (c < 0xF0000) (c - 0x0B80) else (c - 0xF0040)) / 16
        private fun brailleIndexY(c: CodePoint) = (c - 0x2800) / 16
        private fun sundaneseIndexY(c: CodePoint) = (if (c >= 0xF0500) (c - 0xF04B0) else if (c < 0x1BC0) (c - 0x1B80) else (c - 0x1C80)) / 16
        private fun devanagari2IndexY(c: CodePoint) = (c - 0xF0110) / 16
        private fun codestyleAsciiIndexY(c: CodePoint) = (c - 0xF0520) / 16

        val charsetOverrideDefault = Character.toChars(CHARSET_OVERRIDE_DEFAULT).toSurrogatedString()
        val charsetOverrideBulgarian = Character.toChars(CHARSET_OVERRIDE_BG_BG).toSurrogatedString()
        val charsetOverrideSerbian = Character.toChars(CHARSET_OVERRIDE_SR_SR).toSurrogatedString()
        val charsetOverrideCodestyle = Character.toChars(CHARSET_OVERRIDE_CODESTYLE).toSurrogatedString()
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
