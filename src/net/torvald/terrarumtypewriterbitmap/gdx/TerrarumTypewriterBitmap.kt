package net.torvald.terrarumtypewriterbitmap.gdx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.utils.GdxRuntimeException
import net.torvald.terrarumsansbitmap.GlyphProps
import net.torvald.terrarumsansbitmap.gdx.*
import net.torvald.terrarumsansbitmap.gdx.CodePoint
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.TextCacheObj
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.ShittyGlyphLayout
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.Reader
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Config File Syntax:
 *
 * ```
 * identifier,image file name,relative codepoint
 * # working example:
 * intl_qwerty_typewriter,typewriter_intl_qwerty.tga,0
 * ko_kr_3set-390_typewriter,typewriter_ko_3set-390.tga,16
 * ```
 *
 * // the Relative Codepoint of 16 should point to U+F3000
 *
 * Created by minjaesong on 2021-11-04.
 */
class TerrarumTypewriterBitmap(
        fontDir: String,
        configFile: Reader,
        val flipY: Boolean = false,
        var errorOnUnknownChar: Boolean = false,
        val textCacheSize: Int = 256,
        val debug: Boolean = false
) : BitmapFont() {

    override fun getLineHeight() = 20f

    override fun getXHeight() = 8f
    override fun getCapHeight() = 12f
    override fun getAscent() = 3f
    override fun getDescent() = 3f
    override fun isFlipped() = flipY

    var interchar = 0

    private val glyphProps = HashMap<CodePoint, GlyphProps>()
    private val sheets = HashMap<String, PixmapRegionPack>()

    private val spriteSheetNames = HashMap<String, String>()
    private val codepointStart = HashMap<String, CodePoint>()
    private val codepointToSheetID = HashMap<Int, String>()

    private var textCacheCap = 0
    private val textCache = HashMap<Long, TextCacheObj>(textCacheSize * 2)
    private val colourBuffer = HashMap<CodePoint, ARGB8888>()

    /**
     * Insertion sorts the last element fo the textCache
     */
    private fun addToCache(text: CodepointSequence, linotype: Texture, width: Int) {
        val cacheObj =
            TextCacheObj(text.getHash(), ShittyGlyphLayout(text, linotype, width))

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

    init {
        val fontParentDir = if (fontDir.endsWith('/') || fontDir.endsWith('\\')) fontDir else "$fontDir/"

        configFile.forEachLine {
            if (!it.startsWith("#")) {
                val csv = it.split(',')
                if (csv.size != 3) throw IllegalArgumentException("Malformed CSV line: '$it'")
                val key = csv[0]
                val sheetname = csv[1]
                val cpstart = csv[2].toInt() * 256 + 0xF2000

                spriteSheetNames[key] = sheetname
                codepointStart[key] = cpstart

                for (k in cpstart until cpstart + 256) {
                    codepointToSheetID[k] = key
                }
            }
        }

        spriteSheetNames.forEach { key, filename ->
            var pixmap: Pixmap

            println("[TerrarumTypewriterBitmap] loading texture $filename [VARIABLE]")

            // unpack gz if applicable
            if (filename.endsWith(".gz")) {
                val tmpFileName = "tmp_${filename.dropLast(7)}.tga"

                try {
                    val gzi = GZIPInputStream(Gdx.files.internal(fontParentDir + filename).read(8192))
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
                    System.err.println("[TerrarumTypewriterBitmap] said texture not found, skipping...")

                    pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
                }
                //File(tmpFileName).delete()
            }
            else {
                pixmap = try {
                    Pixmap(Gdx.files.internal(fontParentDir + filename))
                } catch (e: GdxRuntimeException) {
                    //e.printStackTrace()
                    System.err.println("[TerrarumTypewriterBitmap] said texture not found, skipping...")

                    Pixmap(1, 1, Pixmap.Format.RGBA8888)
                }
            }

            val cpstart = codepointStart[key]!!
            buildWidthTable(pixmap, cpstart until cpstart + 256, 16)

            val texRegPack = PixmapRegionPack(pixmap,
                TerrarumSansBitmap.W_VAR_INIT,
                TerrarumSansBitmap.H,
                TerrarumSansBitmap.HGAP_VAR, 0
            )

            sheets[key] = texRegPack

            pixmap.dispose() // you are terminated
        }

        glyphProps[0] = GlyphProps(0, 0)

    }

    private fun getSheetType(c: CodePoint) = codepointToSheetID[c] ?: "unknown"
    private fun getSheetwisePosition(cPrev: Int, ch: Int) = getSheetType(ch).let {
        val coff = ch - (codepointStart[it] ?: 0)
        intArrayOf(coff % 16, coff / 16)
    }


    private fun buildWidthTable(pixmap: Pixmap, codeRange: Iterable<Int>, cols: Int = 16) {
        val binaryCodeOffset = TerrarumSansBitmap.W_VAR_INIT

        val cellW = TerrarumSansBitmap.W_VAR_INIT + 1
        val cellH = TerrarumSansBitmap.H

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

            if (code and 127 == 67) width *= -1 // the backspace key
            if (debug) println("${code.charInfo()}: Width $width, tags $tags")

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

    private val pixmapOffsetY = 10
    private val linotypePad = 16
    private var flagFirstRun = true
    private var textBuffer = CodepointSequence(256)
    private lateinit var tempLinotype: Texture
    private var nullProp = GlyphProps(15, 0)


    fun draw(batch: Batch, codepoints: CodepointSequence, x: Float, y: Float): GlyphLayout? {
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

                //println("text not in buffer: $charSeq")


                //textBuffer.forEach { print("${it.toHex()} ") }
                //println()


//                resetHash(charSeq, x.toFloat(), y.toFloat())


                val _pw = posXbuffer.last() + 2*linotypePad
                val _ph = TerrarumSansBitmap.H + (pixmapOffsetY * 2)
                if (_pw < 0 || _ph < 0) throw RuntimeException("Illegal linotype dimension (w: $_pw, h: $_ph)")
                val linotypePixmap = Pixmap(_pw, _ph, Pixmap.Format.RGBA8888)


                var index = 0
                while (index <= textBuffer.lastIndex) {
                    val c = textBuffer[index]
                    val sheetID = getSheetType(c)
                    val (sheetX, sheetY) =
                        if (index == 0) getSheetwisePosition(0, c)
                        else getSheetwisePosition(textBuffer[index - 1], c)
                    val hash = getHash(c) // to be used to simulate printing irregularity

                    if (TerrarumSansBitmap.isColourCode(c)) {
                        if (c == 0x100000) {
                            renderCol = -1
                        }
                        else {
                            renderCol = getColour(c)
                        }
                    }
                    else {
                        try {
                            val posY = posYbuffer[index].flipY()
                            val posX = posXbuffer[index]
                            val texture = sheets[sheetID]?.get(sheetX, sheetY)

                            texture?.let {
                                linotypePixmap.drawPixmap(it, posX + linotypePad, posY + pixmapOffsetY, renderCol)
                            }

                        }
                        catch (noSuchGlyph: ArrayIndexOutOfBoundsException) {
                        }
                    }


                    index++
                }

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
                batch.draw(tempLinotype, (x - linotypePad).toFloat(), (y - pixmapOffsetY).toFloat())
            }
            else {
                batch.draw(tempLinotype,
                    (x - linotypePad).toFloat(),
                    (y - pixmapOffsetY + (tempLinotype.height)).toFloat(),
                    (tempLinotype.width.toFloat()),
                    -(tempLinotype.height.toFloat())
                )
            }

        }

        return null
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

        val HALF_VAR_INIT = TerrarumSansBitmap.W_VAR_INIT.minus(1).div(2)

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
                val kerning = 0


                //println("char: ${thisChar.charInfo()}\nproperties: $thisProp")


                var alignmentOffset = when (thisProp.alignWhere) {
                    GlyphProps.ALIGN_LEFT -> 0
                    GlyphProps.ALIGN_RIGHT -> thisProp.width - TerrarumSansBitmap.W_VAR_INIT
                    GlyphProps.ALIGN_CENTRE -> Math.ceil((thisProp.width - TerrarumSansBitmap.W_VAR_INIT) / 2.0).toInt()
                    else -> 0 // implies "diacriticsBeforeGlyph = true"
                }


                if (!thisProp.writeOnTop) {
                    posXbuffer[charIndex] = when (itsProp.alignWhere) {
                        GlyphProps.ALIGN_RIGHT ->
                            posXbuffer[nonDiacriticCounter] + TerrarumSansBitmap.W_VAR_INIT + alignmentOffset + interchar + kerning + extraWidth
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
                            posXbuffer[nonDiacriticCounter] + TerrarumSansBitmap.W_VAR_INIT + alignmentOffset
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
                            posXbuffer[nonDiacriticCounter] - (TerrarumSansBitmap.W_VAR_INIT - itsProp.width)
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
                                posYbuffer[charIndex] = TerrarumSansBitmap.H_DIACRITICS * stackDownwardCounter
                                stackDownwardCounter++
                            }
                            GlyphProps.STACK_UP -> {
                                posYbuffer[charIndex] = -TerrarumSansBitmap.H_DIACRITICS * stackUpwardCounter

                                // shift down on lowercase if applicable
                                /*if (getSheetType(thisChar) in TerrarumSansBitmap.autoShiftDownOnLowercase &&
                                    lastNonDiacriticChar.isLowHeight()) {
                                    //println("AAARRRRHHHH for character ${thisChar.toHex()}")
                                    //println("lastNonDiacriticChar: ${lastNonDiacriticChar.toHex()}")
                                    //println("cond: ${thisProp.alignXPos == GlyphProps.DIA_OVERLAY}, charIndex: $charIndex")
                                    if (thisProp.alignXPos == GlyphProps.DIA_OVERLAY)
                                        posYbuffer[charIndex] -= TerrarumSansBitmap.H_OVERLAY_LOWERCASE_SHIFTDOWN // if minus-assign doesn't work, try plus-assign
                                    else
                                        posYbuffer[charIndex] -= TerrarumSansBitmap.H_STACKUP_LOWERCASE_SHIFTDOWN // if minus-assign doesn't work, try plus-assign
                                }*/

                                stackUpwardCounter++
                            }
                            GlyphProps.STACK_UP_N_DOWN -> {
                                posYbuffer[charIndex] = TerrarumSansBitmap.H_DIACRITICS * stackDownwardCounter
                                stackDownwardCounter++
                                posYbuffer[charIndex] = -TerrarumSansBitmap.H_DIACRITICS * stackUpwardCounter
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
            val penultCharProp = glyphProps[str[nonDiacriticCounter]]!!
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

    // randomiser effect hash ONLY
    private val hashBasis = -3750763034362895579L
    private val hashPrime = 1099511628211L
    private var hashAccumulator = hashBasis
    fun getHash(char: Int): Long {
        hashAccumulator = hashAccumulator xor char.toLong()
        hashAccumulator *= hashPrime
        return hashAccumulator
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

    private fun Int.charInfo() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}: ${Character.getName(this)}"


    override fun dispose() {
        super.dispose()
        textCache.values.forEach { it.dispose() }
        sheets.values.forEach { it.dispose() }
    }
}