package net.torvald.terrarumsansbitmap

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import net.torvald.terrarumsansbitmap.gdx.CodePoint
import net.torvald.terrarumsansbitmap.gdx.CodepointSequence
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.getHash
import java.lang.Math.pow
import kotlin.math.*

/**
 * Despite "CJK" texts needing their own typesetting rule, in this code Korean texts are typesetted much like
 * the western texts minus the hyphenation rule (it does hyphenate just like the western texts, but omits the
 * actual hyphen character), therefore only the "CJ" texts get their own typesetting rule.
 *
 * Created by minjaesong on 2024-03-24.
 */
class MovableType(
    val font: TerrarumSansBitmap,
    val inputText: CodepointSequence,
    paperWidth: Int,
    internal val isNull: Boolean = false
): Disposable {

    var height = 0; private set
    internal val hash: Long = inputText.getHash()
    private var disposed = false
    val typesettedSlugs = ArrayList<List<Block>>()

    var width = 0; private set

    constructor(font: TerrarumSansBitmap, string: String, paperWidth: Int) : this(font, font.normaliseStringForMovableType(string), paperWidth)

    override fun dispose() {
        if (!disposed) {
            disposed = true
        }
    }

    // perform typesetting
    init { if (inputText.isNotEmpty() && !isNull) {
        if (paperWidth < 100) throw IllegalArgumentException("Width too narrow; width must be at least 100 pixels (got $paperWidth)")

//        println("Paper width: $paperWidth")

        val lines = inputText.tokenise()
//        lines.debugprint()

        lines.forEachIndexed { linenum, it ->
//            println("Processing input text line ${linenum + 1} (word count: ${it.size})...")

            val boxes: MutableList<NoTexGlyphLayout> = it.map { createGlyphLayout(font, it) }.toMutableList()
            var slug = ArrayList<Block>() // slug of the linotype machine
            var slugWidth = 0
            var ignoreThisLine = false

            fun dequeue() = boxes.removeFirst()
            fun addHyphenatedTail(box: NoTexGlyphLayout) = boxes.add(0, box)
            fun addToSlug(box: NoTexGlyphLayout) {
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, box))
                slugWidth += box.width

                width = maxOf(width, nextPosX + box.width)
            }
            fun dispatchSlug() {
                typesettedSlugs.add(slug)

                slug = ArrayList()
                slugWidth = 0
            }

            ///////////////////////////////////////////////////////////////////////////////////////////////

            // the slug is likely end with a glue, must take care of it (but don't modify the slug itself)
            fun getBadnessW(box: NoTexGlyphLayout): Triple<Int, Int, Any?> {
                val slug = slug.toMutableList()

                // remove the trailing glue(s?) in the slug copy
                while (slug.lastOrNull()?.block?.isGlue() == true) {
                    slug.removeLastOrNull()
                }

                var slugWidth = slug.lastOrNull()?.getEndPos() ?: 0
                if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangable.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangableFW.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth).absoluteValue
                val badness = penaliseWidening(difference)

                return Triple(badness, difference, null)
            }

            fun getBadnessT(box: NoTexGlyphLayout): Triple<Int, Int, Any?> {
                val slug = slug.toMutableList()

                // add the box to the slug copy
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, box))

                var slugWidth = slugWidth + box.width
                if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangable.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangableFW.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth).absoluteValue
                val badness = penaliseTightening(difference)

                return Triple(badness, difference, null)
            }

            fun getBadnessH(box: NoTexGlyphLayout, diff: Int): Triple<Int, Int, Any?> {
                // don't hyphenate if:
                // - the word is too short (5 chars or less)
                // - the word is pre-hyphenated (ends with hyphen-null)
                if (box.text.size <= 8 || box.text.penultimate() == 0x2D)
                    return Triple(2147483647, 2147483647, null)

                val slug = slug.toMutableList()
                val (hyphHead, hyphTail) = box.text.hyphenate(font, diff).toList().map { createGlyphLayout(font, it) }

                // add the hyphHead to the slug copy
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, hyphHead))

                var slugWidth = slugWidth + hyphHead.width
                if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangable.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangableFW.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth)
                val badness = penaliseHyphenation(difference.absoluteValue)

                return Triple(badness, difference, hyphHead to hyphTail)
            }

            ///////////////////////////////////////////////////////////////////////////////////////////////

            while (boxes.isNotEmpty()) {
                val box = dequeue()

                if (box.isNotGlue()) {
                    // deal with the hangables
                    val slugWidthForOverflowCalc = if (box.penultimateCharOrNull == null)
                        slugWidth
                    else if (hangable.contains(box.penultimateCharOrNull))
                        slugWidth - hangWidth
                    else if (hangableFW.contains(box.penultimateCharOrNull))
                        slugWidth - hangWidthFW
                    else
                        slugWidth

                    // if adding the box would cause overflow
                    if (slugWidthForOverflowCalc + box.width > paperWidth) {
                        // text overflow occured; set the width to the max value
                        width = paperWidth

                        // badness: always positive and weighted
                        // widthDelta: can be positive or negative
                        val (badnessW, widthDeltaW, _) = getBadnessW(box) // widthDeltaW is always positive
                        val (badnessT, widthDeltaT, _) = getBadnessT(box) // widthDeltaT is always positive
                        val (badnessH, widthDeltaH, hyph) = getBadnessH(box, box.width - slugWidthForOverflowCalc) // widthDeltaH can be anything

                        val (selectedBadness, selectedWidthDelta, selectedStrat) = listOf(
                            Triple(badnessW, widthDeltaW, "Widen"),
                            Triple(badnessT, widthDeltaT, "Tighten"),
                            Triple(badnessH, widthDeltaH, "Hyphenate"),
                        ).minByOrNull { it.first }!!

//                        println("    Line ${typesettedSlugs.size + 1} Strat: $selectedStrat (badness $selectedBadness, delta $selectedWidthDelta; full badness WTH = $badnessW, $badnessT, $badnessH; full delta WTH = $widthDeltaW, $widthDeltaT, $widthDeltaH)")
//                        println("          Interim Slug: [ ${slug.map { it.block.text.toReadable() }.joinToString(" | ")} ]")

                        when (selectedStrat) {
                            "Widen", "Tighten" -> {
                                // widen/tighten the spacing between blocks

                                // widen: 1, tighten: -1
                                val operation = if (selectedStrat == "Widen") 1 else -1

                                // Widen: remove the trailing glue(s?) in the slug
                                if (selectedStrat == "Widen") {
                                    while (slug.lastOrNull()?.block?.isGlue() == true) {
                                        slug.removeLast()
                                    }
                                }
                                // Tighten: add the box to the slug
                                else {
                                    addToSlug(box)
                                    // remove glues on the upcoming blocks
                                    while (boxes.firstOrNull()?.isGlue() == true) {
                                        boxes.removeFirst()
                                    }
                                }

                                moveSlugsToFitTheWidth(operation, slug, selectedWidthDelta.absoluteValue)

                                // put the trailing word back into the upcoming words
                                if (selectedStrat == "Widen") {
                                    addHyphenatedTail(box)
                                }
                                // if tightening leaves an empty line behind, signal the typesetter to discard that line
                                else if (selectedStrat == "Tighten" && boxes.isEmpty()) {
                                    ignoreThisLine = true
                                }
                            }
                            "Hyphenate" -> {
                                // insert hyphen-head to the slug
                                // widen/tighten the spacing between blocks using widthDeltaH
                                // insert hyphen-tail to the list of upcoming boxes

                                val (hyphHead, hyphTail) = hyph as Pair<NoTexGlyphLayout, NoTexGlyphLayout>

                                // widen: 1, tighten: -1
                                val operation = widthDeltaH.sign

                                // insert hyphHead into the slug
                                addToSlug(hyphHead)

                                moveSlugsToFitTheWidth(operation, slug, selectedWidthDelta.absoluteValue)

                                // put the tail into the upcoming words
                                addHyphenatedTail(hyphTail)
                            }
                        }

//                        println("  > Line ${typesettedSlugs.size + 1} Final Slug: [ ${slug.map { it.block.text.toReadable() }.joinToString(" | ")} ]")
                        dispatchSlug()
                    }
                    // typeset the boxes normally
                    else {
                        addToSlug(box)
                    }
                }
                else { // box is glue
                    addToSlug(box)
                }
            } // end of while (boxes.isNotEmpty())

            if (!ignoreThisLine) {
//                println("  > Line ${typesettedSlugs.size + 1} Final Slug: [ ${slug.map { it.block.text.toReadable() }.joinToString(" | ")} ]")
                dispatchSlug()
            }
        } // end of lines.forEach

        height = typesettedSlugs.size
    } }

    fun draw(batch: Batch, x: Int, y: Int, lineStart: Int = 0, linesToDraw: Int = 2147483647, lineHeight: Int = TerrarumSansBitmap.LINE_HEIGHT, drawJobs: Map<Int, (Float, Float, Int) -> Unit> = HashMap()) =
        draw(batch, x.toFloat(), y.toFloat(), lineStart, linesToDraw, lineHeight, drawJobs)

    fun draw(batch: Batch, x: Int, y: Int, drawJobs: Map<Int, (Float, Float, Int) -> Unit> = HashMap()) =
        draw(batch, x.toFloat(), y.toFloat(), 0, 2147483647, TerrarumSansBitmap.LINE_HEIGHT, drawJobs)

    fun draw(batch: Batch, x: Float, y: Float, drawJobs: Map<Int, (Float, Float, Int) -> Unit> = HashMap()) =
        draw(batch, x, y, 0, 2147483647, TerrarumSansBitmap.LINE_HEIGHT, drawJobs)

    /**
     * @param drawJobs Draw call for specific lines (absolute line). This takes the form of Map from linnumber to draw function,
     * which has three arguments: (line's top-left position-x, line's top-left position-y, absolute line number)
     */
    fun draw(batch: Batch, x: Float, y: Float, lineStart: Int = 0, linesToDraw: Int = 2147483647, lineHeight: Int = TerrarumSansBitmap.LINE_HEIGHT, drawJobs: Map<Int, (Float, Float, Int) -> Unit> = HashMap()) {
        if (isNull) return

        typesettedSlugs.subList(lineStart, minOf(typesettedSlugs.size, lineStart + linesToDraw)).forEachIndexed { lineNum, lineBlocks ->
//            println("Line [${lineNum+1}] anchors: "+ lineBlocks.map { it.posX }.joinToString())

            val absoluteLineNum = lineStart + lineNum

            drawJobs[absoluteLineNum]?.invoke(x, y + lineNum * lineHeight, absoluteLineNum)

            lineBlocks.forEach {
                lateinit var oldColour: Color

                if (it.colour != null) {
                    oldColour = batch.color.cpy()
                    batch.color = it.colour
                }

                font.drawNormalised(batch,
                    it.block.text,
                    x + it.posX,
                    y + lineNum * lineHeight * font.scale
                )

                if (it.colour != null)
                    batch.color = oldColour
            }
        }
    }

    data class Block(var posX: Int, val block: NoTexGlyphLayout, var colour: Color? = null) { // a single word
        fun getEndPos() = this.posX + this.block.width
    }

    companion object {
        private val periods = listOf(0x2E, 0x3A, 0x21, 0x3F, 0x2026, 0x3002, 0xff0e).toSortedSet()
        private val quots = listOf(0x22, 0x27, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A).toSortedSet()
        private val commas = listOf(0x2C, 0x3B, 0x3001, 0xff0c).toSortedSet()
        private val hangable = (listOf(0x2E, 0x2C, 0x3A, 0x3B, 0x22, 0x27) + (0x2018..0x201f)).toSortedSet()
        private val hangableFW = listOf(0x3001, 0x3002, 0xff0c, 0xff0e).toSortedSet()
        private const val hangWidth = 6
        private const val hangWidthFW = 16

        private fun CodePoint.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

        private fun moveSlugsToFitTheWidth(operation: Int, slug: ArrayList<Block>, selectedWidthDelta: Int) {
            var gluesInfo = slug.mapIndexed { index, block -> block to index }.filter { (block, index) ->
                block.block.isGlue()
            }.map { (block, index) ->
                val prevBlockEndsWith = if (index == 0) null else slug[index - 1].block.penultimateCharOrNull // last() will just return {NUL}
                Triple(block, index, prevBlockEndsWith)
            }.filter { it.third != null }
            // if there are no glues, put spaces between all characters
            if (gluesInfo.isEmpty()) {
                gluesInfo = slug.subList(1, slug.size).mapIndexed { index, block ->
                    val prevBlockEndsWith = slug[index].block.penultimateCharOrNull // last() will just return {NUL}
                    Triple(block, index + 1, prevBlockEndsWith)
                }
            }
            val gluesMoveAmounts0 = getGluesMoveAmounts(gluesInfo, selectedWidthDelta) // first order derivative of gluesMoveAmounts
            val gluesMoveAmounts = IntArray(slug.size) // actual move values
            for (i in 1 until gluesMoveAmounts.size){
                gluesMoveAmounts[i] = gluesMoveAmounts[i - 1] + gluesMoveAmounts0.getOrElse(i) { 0 }
            }

            // move blocks using gluesMoveAmounts
            gluesMoveAmounts.forEachIndexed { index, moveAmounts ->
                slug[index].posX += moveAmounts * operation
            }
        }

        /**
         * Returns move amounts in following format:
         * intArray(0, 0, <absolute value of move amount>, 0, 0, 0, <absolute value of move amount>, ...)
         */
        private fun getGluesMoveAmounts(gluesInfo: List<Triple<Block, Int, CodePoint?>>, moveAmount: Int): IntArray {
            if (gluesInfo.isEmpty()) throw IllegalArgumentException("Glues info is empty!")

            val operations = HashMap<Int, Int>() // key: index, value: number of hits
            var operationsSize = 0

            while (operationsSize < moveAmount) {
                val li = gluesInfo.sortedBy { (block, index, thisWordEnd) ->
                    val priority = if (thisWordEnd == null)
                        255
                    else if (periods.contains(thisWordEnd))
                        1
                    else if (quots.contains(thisWordEnd) or quots.contains(block.block.text.firstOrNull()))
                        2
                    else if (commas.contains(thisWordEnd))
                        3
                    else
                        255

                    (Math.random() * 65535).toInt().or(priority.shl(16))
                }
                var c = 0
                while (operationsSize < moveAmount && c < li.size) {
                    val index = li[c].second
                    operations[index] = (operations[index] ?: 0) + 1

                    c += 1
                    operationsSize += 1
                }
            }

            val arrayoid = operations.entries.toList().map { it.key to it.value }.sortedBy { it.first }
            if (arrayoid.isEmpty()) return IntArray(0)

            val array = IntArray(arrayoid.last().first + 1)
            arrayoid.forEach { (index, hits) ->
                array[index] = hits
            }
            return array
        }

        /**
         * This function will tokenise input string into a list of boxes.
         *
         * Each element in the outer list is a single line of the text. The line can be empty.
         *
         * Inner list (ArrayList) contains the boxes for the single line.
         */
        private fun CodepointSequence.tokenise(): List<ArrayList<CodepointSequence>> {
            val lines = ArrayList<ArrayList<CodepointSequence>>()
            var tokens = ArrayList<CodepointSequence>()
            var boxBuffer = ArrayList<CodePoint>()

            val controlCharList = ArrayList<Pair<CodePoint, Int>>()

            var ccRemovalReqByPredicate: ((Pair<CodePoint, Int>) -> Boolean)? = null
            var ccRemovalReqPopping = false

            var cM: CodePoint? = null
            var glue = 0

            fun getControlHeader(row: Int, word: Int): CodepointSequence {
                val index = row * 65536 or word

//                println("GetControlHeader $row, $word -> $index")
//                println("    ControlChars: ${controlCharList.joinToString()}")

                val ret = CodepointSequence(controlCharList.filter { index > it.second }.map { it.first })

//                println("    Filtered: ${ret.joinToString()}")

                return ret
            }


            fun addControlChar(char: CodePoint) {
                val row = lines.size
                val word = tokens.size
                val index = row * 65536 or word
                controlCharList.add(char to index)
            }

            fun requestControlCharRemovalIf(predicate: (Pair<CodePoint, Int>) -> Boolean) {
                ccRemovalReqByPredicate = predicate
            }
            fun requestControlCharRemovalPop() {
                ccRemovalReqPopping = true
            }


            fun sendoutBox() {
                val row = lines.size
                val word = tokens.size

                if (boxBuffer.isNotEmpty()) {
                    tokens.add(CodepointSequence(listOf(0) + getControlHeader(row, word) + boxBuffer + listOf(0)))
                }

                if (ccRemovalReqByPredicate != null) {
                    controlCharList.removeIf(ccRemovalReqByPredicate!!)
                    ccRemovalReqByPredicate = null
                }
                if (ccRemovalReqPopping) {
                    controlCharList.removeLastOrNull()
                    ccRemovalReqPopping = false
                }

                boxBuffer = ArrayList()
            }

            fun sendoutGlue() {
                if (glue == 0)
                    tokens.add(CodepointSequence(listOf((ZWSP))))
                else if (glue.absoluteValue <= 16)
                    if (glue > 0)
                        tokens.add(CodepointSequence(listOf(GLUE_POSITIVE_ONE + (glue - 1))))
                    else
                        tokens.add(CodepointSequence(listOf(GLUE_NEGATIVE_ONE + (glue.absoluteValue - 1))))
                else {
                    val fullGlues = glue.absoluteValue / 16
                    val smallGlues = glue.absoluteValue % 16
                    if (glue > 0)
                        tokens.add(CodepointSequence(
                            List(fullGlues) { GLUE_POSITIVE_SIXTEEN } +
                                    listOf(GLUE_POSITIVE_ONE + (smallGlues - 1))
                        ))
                    else
                        tokens.add(CodepointSequence(
                            List(fullGlues) { GLUE_NEGATIVE_SIXTEEN } +
                                    listOf(GLUE_NEGATIVE_ONE + (smallGlues - 1))
                        ))
                }

                glue = 0
            }

            fun appendToBuffer(char: CodePoint) {
                boxBuffer.add(char)
            }

            fun appendGlue(char: CodePoint) {
                glue += whitespaceGlues[char] ?: throw NullPointerException("${char.toHex()} is not a whitespace")
            }

            fun appendZeroGlue() {
                glue += 0
            }

            fun proceedToNextLine() {
                lines.add(tokens)
                tokens = ArrayList<CodepointSequence>()
                cM = null
            }

            this.forEachIndexed { indexxxx, it ->
                val c0 = it

                if (c0.isColourCode()) {
                    if (glue > 0) sendoutGlue()
                    addControlChar(c0)
                    appendToBuffer(c0)
                }
                else if (c0 == 0x100000) {
                    requestControlCharRemovalIf { (it.first in 0x10F000..0x10FFFF) }
                    if (glue > 0) sendoutGlue()
                    appendToBuffer(c0)
                }
                else if (c0.isControlIn()) {
                    if (glue > 0) sendoutGlue()
                    addControlChar(c0)
                    appendToBuffer(c0)
                }
                else if (c0.isControlOut()) {
                    if (glue > 0) sendoutGlue()
                    requestControlCharRemovalPop()
                    appendToBuffer(c0)
                }
                else if (c0 == 0x0A) { // \n
                    glue = 0
                    sendoutBox()
                    proceedToNextLine()
                }
                else if (c0 == 0x2D) { // hyphen
                    if (glue > 0) sendoutGlue()
                    appendToBuffer(c0)
                    sendoutBox()
                }
                else if (c0.isWhiteSpace()) {
                    if (cM != null && !cM.isWhiteSpace())
                        sendoutBox()

                    appendGlue(c0)
                }
                else if (c0.isSmallKana()) {
                    if (cM.isSmallKana() || cM.isCJ()) {
                        appendToBuffer(c0)
                    }
                    else {
                        sendoutBox()
                        appendToBuffer(c0)
                    }
                }
                else if (c0.isCJparenStart()) {
                    sendoutBox()

                    appendZeroGlue()
                    sendoutGlue()

                    appendToBuffer(c0)
                }
                else if (c0.isParenOpen()) {
                    sendoutBox()

                    if (glue > 0) sendoutGlue()

                    appendToBuffer(c0)
                }
                else if (c0.isCJparenEnd()) {
                    appendToBuffer(c0)
                    sendoutBox()

                    appendZeroGlue()
                    sendoutGlue()
                }
                else if (c0.isParenClose()) {
                    appendToBuffer(c0)
                    sendoutBox()

                    if (glue > 0) sendoutGlue()
                }
                else if (c0.isCJpunct()) {
                    if (cM.isWhiteSpace()) sendoutGlue()

                    appendToBuffer(c0)
                }
                else if (c0.isCJ()) {
                    if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (cM.isCJparenStart()) {
                        /* do nothing */
                    }
                    else if (cM.isCJpunct() || cM.isNumeric()) {
                        sendoutBox()

                        // don't append glue for kana-dash
                        if (cM != 0x30FC) {
                            appendZeroGlue()
                            sendoutGlue()
                        }
                    }
                    else { // includes if cM.isCJ()
                        sendoutBox()
                    }

                    appendToBuffer(c0)
                }
                else if (c0.isThaiConso()) {
                    if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (cM.isThaiConso() || cM.isThaiVowel()) {
                        sendoutBox()
                    }
                    else {
                        sendoutBox()
                    }

                    appendToBuffer(c0)
                }
                else if (isHangulI(c0)) {
                    if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (!isHangulPK(cM ?: 0) && !cM.isWesternPunctOrQuotes() && !cM.isParens() && !cM.isCJpunct() && !cM.isCJparenStart()) {
                        sendoutBox()
                    }

                    appendToBuffer(c0)
                }
                else if (c0.isNumeric()) {
                    if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (cM.isCJ()) {
                        sendoutBox()
                        appendZeroGlue()
                        sendoutGlue()
                    }
                    else if (cM != null && !cM!!.isNumeric()) {
                        sendoutBox()
                    }

                    appendToBuffer(c0)
                }
                // tokenise camelCase
                else if (cM.isMiniscule() && c0.isMajuscule()) {
                    if (glue > 0) sendoutGlue()
                    sendoutBox()
                    appendToBuffer(c0)
                }
                else {
                    if (!isHangulPK(c0) && !c0.isWesternPunctOrQuotes() && !c0.isCJpunct() && !c0.isParens() && isHangulPK(cM ?: 0)) {
                        sendoutBox()
                    }
                    else if (cM.isCJ() || cM.isNumeric()) {
                        sendoutBox()
                    }
                    else if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (cM.isCJpunct()) {
                        sendoutBox()
                        appendZeroGlue()
                        sendoutGlue()
                    }

                    appendToBuffer(c0)
                }

                cM = c0
            }

            // Add the last token if it's not empty
            sendoutBox()
            proceedToNextLine()

            lines.forEach {
                if (it.isNotEmpty() && ((it[0].size == 2 && it[0][0] == 0 && it[0][1] == 0) || it[0].isZeroGlue()))
                    it.removeAt(0)
            }

            return lines
        }

        private fun <E> java.util.ArrayList<E>.penultimate(): E {
            return this[this.size - 2]
        }

        private fun penaliseWidening(score: Int): Int = score*score
        private fun penaliseTightening(score: Int): Int = score*score//0.0006f * score * score * score + 0.18f * score
        private fun penaliseHyphenation(score: Int): Int = score*score//(10.0 * pow(score.toDouble(), 1.0/3.0) + 0.47*score).toFloat()

        private fun isVowel(c: CodePoint) = vowels.contains(c)

        private fun CodePoint?.isCJ() = if (this == null) false else listOf(4, 6, 12, 13, 20, 23, ).any {
            TerrarumSansBitmap.codeRange[it].contains(this)
        }

        private fun isHangulI(c: CodePoint) = hangulI.contains(c)
        private fun isHangulPK(c: CodePoint) = hangulPK.contains(c)

        private fun CodePoint?.isNumeric() = if (this == null) false else Character.isDigit(this)

        private fun CodePoint?.isWhiteSpace() = if (this == null) false else whitespaceGlues.contains(this)

        private fun CodePoint?.isCJparenStart() = if (this == null) false else cjparenStarts.contains(this)
        private fun CodePoint?.isCJparenEnd() = if (this == null) false else cjparenEnds.contains(this)
//        private fun CodePoint?.isCJpunctOrParenEnd() = if (this == null) false else (cjpuncts.contains(this) || cjparenEnds.contains(this))
        private fun CodePoint?.isCJpunct() = if (this == null) false else cjpuncts.contains(this)
        private fun CodePoint?.isSmallKana() = if (this == null) false else jaSmallKanas.contains(this)
        private fun CodePoint?.isControlIn() = if (this == null) false else controlIns.contains(this)
        private fun CodePoint?.isControlOut() = if (this == null) false else controlOuts.contains(this)
        private fun CodePoint?.isColourCode() = if (this == null) false else colourCodes.contains(this)
        private fun CodePoint?.isThaiConso() = if (this == null) false else this in 0x0E01..0x0E2F
        private fun CodePoint?.isThaiVowel() = if (this == null) false else (this in 0x0E30..0x0E3E || this in 0x0E40..0x0E4E)

        private fun CodepointSequence.isGlue() = this.size == 1 && (this[0] == ZWSP || this[0] in 0xFFFE0..0xFFFFF)
        private fun CodepointSequence.isNotGlue() = !this.isGlue()
        private fun CodepointSequence.isZeroGlue() = this.size == 1 && (this[0] == ZWSP)
        private fun CodePoint.toGlueSize() = when (this) {
            ZWSP -> 0
            in 0xFFFE0..0xFFFEF -> -(this - 0xFFFE0 + 1)
            in 0xFFFF0..0xFFFFF -> this - 0xFFFF0 + 1
            else -> throw IllegalArgumentException()
        }

        private fun CodePoint?.isWesternPunctOrQuotes() = if (this == null) false else (westernPuncts.contains(this) || quots.contains(this))
        private fun CodePoint?.isParens() = if (this == null) false else parens.contains(this)
        private fun CodePoint?.isParenOpen() = if (this == null) false else parenOpen.contains(this)
        private fun CodePoint?.isParenClose() = if (this == null) false else parenClose.contains(this)

        private fun CodePoint?.isMajuscule() = if (this == null) false else Character.isUpperCase(this)
        private fun CodePoint?.isMiniscule() = if (this == null) false else Character.isLowerCase(this)

        /**
         * Hyphenates the word at the middle ("paragraph" -> "para-graph")
         * 
         * @return left word ("para-"), right word ("graph")
         */
        private fun CodepointSequence.hyphenate(font: TerrarumSansBitmap, optimalCuttingPointInPx: Int): Pair<CodepointSequence, CodepointSequence> {
//            val middlePoint = this.size / 2
            // search for the end of the vowel cluster for left and right
            // one with the least distance from the middle point will be used for hyphenating point
            val hyphenateCandidates = ArrayList<Int>() // stores indices
            val splitCandidates = ArrayList<Int>() // stores indices
            var i = 3
            while (i < this.size - 3) {
                val thisChar = this[i]
                val prevChar = this[i-1]
                if (isVowel(prevChar) && !isVowel(thisChar) || !isVowel(prevChar) && isVowel(thisChar)) {
                    hyphenateCandidates.add(i+1)
                }
                else if (thisChar == SHY && isVowel((prevChar))) {
                    hyphenateCandidates.add(i)
                    i += 1 // skip SHY
                }
                if (isHangulPK(prevChar) && isHangulI(thisChar))
                    splitCandidates.add((i))

                i += 1
            }

//            println("Hyphenating ${this.toReadable()} -> [${hyphenateCandidates.joinToString()}]")

            if (hyphenateCandidates.isEmpty() && splitCandidates.isEmpty()) {
                return this to CodepointSequence()
            }

            // priority: 1st split, 2nd hyphenate

            val splitPoint = splitCandidates.minByOrNull { (font.getWidth(CodepointSequence(this.slice(0..it))) - optimalCuttingPointInPx).absoluteValue }
            val hyphPoint = hyphenateCandidates.minByOrNull { (font.getWidth(CodepointSequence(this.slice(0..it))) - optimalCuttingPointInPx).absoluteValue }

//            println("hyphPoint = $hyphPoint")

            if (splitPoint != null) {
                val fore = this.subList(0, splitPoint).toMutableList().let {
                    it.add(0x00)
                    CodepointSequence(it)
                }
                val post = this.subList(splitPoint, this.size).toMutableList().let {
                    it.add(0, 0x00)
                    CodepointSequence(it)
                }

//                println("hyph return: ${fore.toReadable()} ${post.toReadable()}")

                return fore to post
            }
            else if (hyphPoint != null) {
                val fore = this.subList(0, hyphPoint).toMutableList().let {
                    it.add(0x2d); it.add(0x00)
                    CodepointSequence(it)
                }
                val post = this.subList(hyphPoint, this.size).toMutableList().let {
                    it.add(0, 0x00)
                    CodepointSequence(it)
                }

//                println("hyph return: ${fore.toReadable()} ${post.toReadable()}")

                return fore to post
            }
            else {
                return this to CodepointSequence()
            }
        }

        private val vowels = (listOf(0x41, 0x45, 0x49, 0x4f, 0x55, 0x59, 0x41, 0x65, 0x69, 0x6f, 0x75, 0x79) +
                (0xc0..0xc6) + (0xc8..0xcf) + (0xd2..0xd6) + (0xd8..0xdd) +
                (0xe0..0xe6) + (0xe8..0xef) + (0xf2..0xf6) + (0xf8..0xfd) +
                (0xff..0x105) + (0x112..0x118) + (0x128..0x131) + (0x14c..0x153) +
                (0x168..0x173) + (0x176..0x178) +
                listOf(0x391,0x395,0x397,0x399,0x39f,0x3a5,0x3a9,0x3aa,0x3ab) +
                listOf(0x3b1,0x3b5,0x3b7,0x3b9,0x3bf,0x3c5,0x3c9,0x3ca,0x3cb) +
                listOf(0x400,0x401,0x404,0x406,0x407,0x40d,0x40e) +
                listOf(0x450,0x451,0x454,0x456,0x457,0x45d,0x45e) +
                listOf(0x410,0x415,0x418,0x41e,0x423,0x42d) +
                listOf(0x430,0x435,0x438,0x43e,0x443,0x44d) +
                (0x48a..0x48d) + (0x4d0..0x4db) + (0x4e2..0x4f3) +
                listOf(0x531,0x535,0x537,0x538,0x53b,0x548,0x555) +
                listOf(0x560,0x561,0x565,0x567,0x568,0x56b,0x578,0x585) +
                listOf(0x10d0,0x10d4,0x10d8,0x10dd,0x10e3) +
                listOf(0x1c90,0x1c94,0x1c98,0x1c9d,0x1ca3)
                ).toSortedSet()

        private val hangulI = ((0x1100..0x115E) + (0xA960..0xA97F)).toSortedSet()
        private val hangulPK = ((0x1160..0x11FF) + (0xD7B0..0xD7FF)).toSortedSet()
        private val colourCodes = (0x10F000..0x10FFFF).toSortedSet()
        private val controlIns = listOf(0xFFFA2, 0xFFFA3, 0xFFFC1, 0xFFFC2).toSortedSet()
        private val controlOuts = listOf(0xFFFBF, 0xFFFC0).toSortedSet()
        private val whitespaceGlues = hashMapOf(
            0x20 to 4,
            0x3000 to 16,
        )
        private val cjpuncts = listOf(0x203c, 0x2047, 0x2048, 0x2049, 0x3001, 0x3002, 0x3006, 0x303b, 0x30a0, 0x30fb, 0x30fc, 0x301c, 0xff01, 0xff0c, 0xff0e, 0xff1a, 0xff1b, 0xff1f, 0xff5e, 0xff65).toSortedSet()
        private val cjparenStarts = listOf(0x3008, 0x300A, 0x300C, 0x300E, 0x3010, 0x3014, 0x3016, 0x3018, 0x301A, 0x30fb, 0xff65).toSortedSet()
        private val cjparenEnds = listOf(0x3009, 0x300B, 0x300D, 0x300F, 0x3011, 0x3015, 0x3017, 0x3019, 0x301B).toSortedSet()
        private val jaSmallKanas = "ァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ".map { it.toInt() }.toSortedSet()
        private val westernPuncts = listOf(0x21,0x2C,0x2E,0x2F,0x3A,0x3B,0x3F,0x7E).toSortedSet()
        private val parens = listOf(0x28,0x29,0x5B,0x5D,0x7B,0x7D).toSortedSet()
        private val parenOpen = listOf(0x28,0x5B,0x7B).toSortedSet().also { it.addAll(cjparenStarts) }
        private val parenClose = listOf(0x29,0x5D,0x7D).toSortedSet().also { it.addAll(cjparenEnds) }

        private const val ZWSP = 0x200B
        private const val SHY = 0xAD
        private const val NBSP = 0xA0
        private const val GLUE_POSITIVE_ONE = 0xFFFF0
        private const val GLUE_POSITIVE_SIXTEEN = 0xFFFFF
        private const val GLUE_NEGATIVE_ONE = 0xFFFE0
        private const val GLUE_NEGATIVE_SIXTEEN = 0xFFFEF

        private fun CodepointSequence.toReadable() = this.joinToString("") {
            if (it in 0x00..0x1f)
                "${(0x2400 + it).toChar()}"
            else if (it == 0x20)
                "\u2423"
            else if (it == NBSP)
                "{NBSP}"
            else if (it == SHY)
                "{SHY}"
            else if (it == ZWSP)
                "{ZWSP}"
            else if (it >= 0xF0000)
                it.toHex() + " "
            else
                Character.toString(it.toChar())
        }


        private fun List<ArrayList<CodepointSequence>>.debugprint() {
            println("Tokenised (${this.size} lines):")
            this.forEach {
                val readables = it.map {
                    if (it.isEmpty())
                        "<!! EMPTY !!>"
                    else if (it.isGlue())
                        "<Glue ${it.first().toGlueSize()}>"
                    else
                        it.toReadable()
                }
                println("(${readables.size})[ ${readables.joinToString(" | ")} ]")
            }
        }

        private fun NoTexGlyphLayout.isNotGlue(): Boolean {
            return this.text.isNotGlue()
        }
        private fun NoTexGlyphLayout.isGlue(): Boolean {
            return this.text.isGlue()
        }


    } // end of companion object

    data class NoTexGlyphLayout(val text: CodepointSequence, val width: Int) {
        val penultimateCharOrNull: CodePoint?
            get() = text.getOrNull(text.size - 2)
    }

    private fun createGlyphLayout(font: TerrarumSansBitmap, str: CodepointSequence): NoTexGlyphLayout {
        return NoTexGlyphLayout(str, font.getWidth(str))
    }

}

