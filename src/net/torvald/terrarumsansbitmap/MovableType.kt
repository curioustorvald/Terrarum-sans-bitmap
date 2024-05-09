package net.torvald.terrarumsansbitmap

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import net.torvald.terrarumsansbitmap.gdx.CodePoint
import net.torvald.terrarumsansbitmap.gdx.CodepointSequence
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.FIXED_BLOCK_1
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.getHash
import kotlin.math.*
import kotlin.properties.Delegates

enum class TypesettingStrategy {
    JUSTIFIED, RAGGED_RIGHT, RAGGED_LEFT, CENTRED
}

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
    textWidth: Int,
    strategy: TypesettingStrategy = TypesettingStrategy.JUSTIFIED
): Disposable {


    private var isNull = false

    internal constructor(
        font: TerrarumSansBitmap,
        inputText: CodepointSequence,
        textWidth: Int,
        strategy: TypesettingStrategy = TypesettingStrategy.JUSTIFIED,
        isNull: Boolean
    ) : this(font, inputText, textWidth, strategy) {
        this.isNull = isNull
    }

    var height = 0; private set
    internal val hash: Long = inputText.getHash()
    private var disposed = false
//    val typesettedSlugs = ArrayList<List<Block>>()
    val typesettedSlugs = ArrayList<CodepointSequence>()

    var width = 0; private set

    constructor(
        font: TerrarumSansBitmap,
        string: String,
        paperWidth: Int,
        strategy: TypesettingStrategy = TypesettingStrategy.JUSTIFIED
    ) : this(font, font.normaliseStringForMovableType(string), paperWidth, strategy)

    override fun dispose() {
        if (!disposed) {
            disposed = true
        }
    }

    private var paperWidth by Delegates.notNull<Int>()

    // perform typesetting
    init { if (inputText.isNotEmpty() && !isNull) {
        paperWidth = textWidth / font.scale
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
            fun dispatchSlug(align: TypesettingStrategy, unindent: Int) {
                val frozen = slug.freezeIntoCodepointSequence(font)

                // insert empty blocks to the left
                if (align == TypesettingStrategy.RAGGED_LEFT) {
                    // has hangables?
                    val penult = frozen.penultimateOrNull()
                    val hang = if (penult == null)
                        0
                    else if (hangable.contains(penult))
                        hangWidth
                    else if (hangableFW.contains(penult))
                        hangWidthFW
                    else
                        0

                    val diff = paperWidth - (font.getWidthNormalised(frozen) - hang)
                    if (diff != 0) {
                        frozen.addAll(0, diff.glueSizeToGlueChars())
                    }
                }
                else if (align == TypesettingStrategy.CENTRED) {
                    // has hangables?
                    val penult = frozen.penultimateOrNull()
                    val hang = if (penult == null)
                        0
                    else if (hangable.contains(penult))
                        hangWidth / 2
                    else if (hangableFW.contains(penult))
                        hangWidthFW / 2
                    else
                        0

                    val diff = (paperWidth - (font.getWidthNormalised(frozen)) - hang) / 2
                    if (diff != 0) {
                        frozen.addAll(0, diff.glueSizeToGlueChars())
                    }
                }

                if (unindent != 0) {
                    frozen.addAll(0, (-unindent).glueSizeToGlueChars())
                }

                typesettedSlugs.add(frozen)

                slug = ArrayList()
                slugWidth = 0
            }

            ///////////////////////////////////////////////////////////////////////////////////////////////

            // the slug is likely end with a glue, must take care of it (but don't modify the slug itself)
            fun getBadnessW(box: NoTexGlyphLayout, availableGlues: Int, unindentSize: Int): Triple<Double, Int, Any?> {
                val slug = slug.toMutableList()

                // remove the trailing glue(s?) in the slug copy
                while (slug.lastOrNull()?.block?.isGlue() == true) {
                    slug.removeLastOrNull()
                }

                var slugWidth = (slug.lastOrNull()?.getEndPos() ?: 0) - unindentSize
                if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangable.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangableFW.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth).absoluteValue
                val badness = penaliseWidening(difference, availableGlues.toDouble())

                return Triple(badness, difference, null)
            }

            fun getBadnessT(box: NoTexGlyphLayout, availableGlues: Int, unindentSize: Int): Triple<Double, Int, Any?> {
                val slug = slug.toMutableList()

                // add the box to the slug copy
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, box))

                var slugWidth = slugWidth + box.width - unindentSize
                if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangable.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && slug.last().block.penultimateCharOrNull != null && hangableFW.contains(slug.last().block.penultimateCharOrNull))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth).absoluteValue
                val badness = penaliseTightening(difference, availableGlues.toDouble())

                // return -INF if you used up all the available glues
                if (difference > availableGlues)
                    return Triple(Double.POSITIVE_INFINITY, difference, null)
                else
                    return Triple(badness, difference, null)
            }

            fun getBadnessH(box: NoTexGlyphLayout, diff: Int, availableGlues: Int, unindentSize: Int, currentWidth: Int): Triple<Double, Int, Any?> {
                // don't hyphenate if:
                // - the word is too short (5 chars or less)
                // - the word is pre-hyphenated (ends with hyphen-null)
                val glyphCount = box.text.count { it in 32..0xFFF6F && it !in 0xFFF0..0xFFFF }
                if (glyphCount <= (if (paperWidth < 350) 4 else if (paperWidth < 480) 5 else 6) || box.text.penultimate() == 0x2D)
                    return Triple(Double.POSITIVE_INFINITY, 2147483647, null)

                val slug = slug.toMutableList() // ends with a glue

                // calculate new slug width which contains the given box
                val slugWidth = slugWidth + box.width - unindentSize

//                println("Width: $slugWidth/$paperWidth")

                val cutPoint = box.width - (slugWidth - paperWidth) + hyphenWidth
                val (hyphHead, hyphTail) = box.text.hyphenate(font, cutPoint).toList().map { createGlyphLayout(font, it) }

//                println("Hyphenating '${box.text.toReadable()}' at $cutPoint px -> ${hyphHead.text.toReadable()} ${hyphTail.text.toReadable()}")

                if (hyphTail.text.isEmpty())
                    return Triple(Double.POSITIVE_INFINITY, 2147483647, null)

                // add the hyphHead to the slug copy
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, hyphHead)) // now ends with 'word-'

                val slugWidth1 = slug.last().getEndPos() - unindentSize - hyphenWidth

                val difference = paperWidth - slugWidth1
                val badness = penaliseHyphenation(difference.absoluteValue, availableGlues.toDouble())

                return Triple(badness, difference, hyphHead to hyphTail)
            }

            ///////////////////////////////////////////////////////////////////////////////////////////////

            while (boxes.isNotEmpty()) {
                val box = dequeue()

                if (box.isNotGlue()) {
                    // deal with the hangables
                    val firstChar = slug.firstOrNull()?.block?.secondCharOrNull
                    val lastChar = box.penultimateCharOrNull
                    val slugUnindent = when (strategy) {
                        TypesettingStrategy.JUSTIFIED -> {
                            if (firstChar == null)
                                0
                            else if (hangable.contains(firstChar))
                                hangWidth
                            else
                                0
                        }
                        else -> 0
                    }

//                    if (slugUnindent != 0) println("Slug unindentation $slugUnindent on text ${slug.joinToString(" ") { it.block.text.toReadable() }}")

                    val slugWidthForOverflowCalc = when (strategy) {
                        TypesettingStrategy.JUSTIFIED -> {
                            if (lastChar == null)
                                slugWidth - slugUnindent
                            else if (hangable.contains(lastChar))
                                slugWidth - hangWidth - slugUnindent
                            else if (hangableFW.contains(lastChar))
                                slugWidth - hangWidthFW - slugUnindent
                            else
                                slugWidth - slugUnindent
                        }
                        else -> slugWidth
                    }

                    val truePaperWidth = when (strategy) {
                        TypesettingStrategy.JUSTIFIED -> paperWidth
                        TypesettingStrategy.RAGGED_RIGHT, TypesettingStrategy.RAGGED_LEFT, TypesettingStrategy.CENTRED -> paperWidth + 2
                    }

                    // if adding the box would cause overflow
                    if (slugWidthForOverflowCalc + box.width > truePaperWidth) {
                        // if adding the box would cause overflow (justified)
                        if (strategy == TypesettingStrategy.JUSTIFIED) {
                            // text overflow occured; set the width to the max value
                            width = paperWidth

                            val initialGlueCount = slug.getGlueSizeSum(font)

                            // badness: always positive and weighted
                            // widthDelta: can be positive or negative
                            var (badnessW, widthDeltaW, _) = getBadnessW(
                                box,
                                initialGlueCount,
                                slugUnindent
                            ) // widthDeltaW is always positive
                            var (badnessT, widthDeltaT, _) = getBadnessT(
                                box,
                                initialGlueCount,
                                slugUnindent
                            ) // widthDeltaT is always positive
                            var (badnessH, widthDeltaH, hyph) = getBadnessH(
                                box,
                                box.width - slugWidthForOverflowCalc,
                                initialGlueCount,
                                slugUnindent,
                                slugWidthForOverflowCalc + box.width
                            ) // widthDeltaH can be anything

                            badnessT -= 0.1 // try to break even
                            badnessH -= 0.01 // try to break even
                            val disableHyphThre = 5.0

                            // disable hyphenation if badness of others is lower than the threshold
                            if ((badnessW <= disableHyphThre || badnessT <= disableHyphThre)) {
                                badnessH = Double.POSITIVE_INFINITY
                            }

                            // disable hyphenation if hyphenating a word is impossible
                            if (hyph == null) {
                                badnessH = Double.POSITIVE_INFINITY
                            }


                            if (badnessH.isInfinite() && badnessW.isInfinite() && badnessT.isInfinite()) {
                                throw Error(
                                    "Typesetting failed: badness of all three strategies diverged to infinity\ntext (${slug.size} tokens): ${
                                        slug.map { it.block.text }.filter { it.isNotGlue() }
                                            .joinToString(" ") { it.toReadable() }
                                    }"
                                )
                            }


//                        println("\nLine: ${slug.map { it.block.text }.filter { it.isNotGlue() }.joinToString(" ") { it.toReadable() }}")
//                        println("W diff: $widthDeltaW, badness: $badnessW")
//                        println("T diff: $widthDeltaT, badness: $badnessT")
//                        println("H diff: $widthDeltaH, badness: $badnessH")

                            val (selectedBadness, selectedWidthDelta, selectedStrat) = listOf(
                                Triple(badnessW, widthDeltaW, "Widen"),
                                Triple(badnessT, widthDeltaT, "Tighten"),
                                Triple(badnessH, widthDeltaH, "Hyphenate"),
                            ).minByOrNull { it.first }!!


//                        if (selectedStrat == "Hyphenate") {
//                            val (hyphHead, hyphTail) = hyph as Pair<NoTexGlyphLayout?, NoTexGlyphLayout?>
//                            println("Selected: $selectedStrat (${hyphHead?.text?.toReadable()}, ${hyphTail?.text?.toReadable()}) (badness $selectedBadness, diff $selectedWidthDelta)")
//                        }
//                        else
//                            println("Selected: $selectedStrat (badness $selectedBadness, diff $selectedWidthDelta)")

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
                            dispatchSlug(strategy, slugUnindent)
                        }
                        // if adding the box would cause overflow (ragged-something, centred)
                        else {
                            // remove trailing glues
                            while (slug.lastOrNull()?.block?.isGlue() == true) {
                                slug.removeLast()
                            }

                            addHyphenatedTail(box)

                            dispatchSlug(strategy, slugUnindent)
                        }
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
                dispatchSlug(strategy, 0)
            }
        } // end of lines.forEach

        height = typesettedSlugs.size
    } }

    fun draw(batch: Batch, x: Int, y: Int, lineStart: Int = 0, linesToDraw: Int = 2147483647, lineHeight: Int = TerrarumSansBitmap.LINE_HEIGHT) =
        draw(batch, x.toFloat(), y.toFloat(), lineStart, linesToDraw, lineHeight)

    fun draw(batch: Batch, x: Int, y: Int) =
        draw(batch, x.toFloat(), y.toFloat(), 0, 2147483647, TerrarumSansBitmap.LINE_HEIGHT)

    fun draw(batch: Batch, x: Float, y: Float) =
        draw(batch, x, y, 0, 2147483647, TerrarumSansBitmap.LINE_HEIGHT)

    /**
     * @param drawJobs Draw call for specific lines (absolute line). This takes the form of Map from linnumber to draw function,
     * which has three arguments: (line's top-left position-x, line's top-left position-y, absolute line number)
     */
    fun draw(batch: Batch, x: Float, y: Float, lineStart: Int = 0, linesToDraw: Int = 2147483647, lineHeight: Int = TerrarumSansBitmap.LINE_HEIGHT) {
        if (isNull) return

        typesettedSlugs.subList(lineStart, minOf(typesettedSlugs.size, lineStart + linesToDraw)).forEachIndexed { lineNum, text ->
            font.drawNormalised(batch, text, x, y + lineNum * lineHeight * font.scale)
        }
    }

    data class Block(var posX: Int, val block: NoTexGlyphLayout, var colour: Color? = null) { // a single word
        fun getEndPos() = this.posX + this.block.width
    }

    companion object {
        private val periods = listOf(0x2E, 0x3A, 0x21, 0x3F, 0x2026, 0x3002, 0xff0e).toSortedSet()
        private val quots = listOf(0x22, 0x27, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A).toSortedSet()
        private val commas = listOf(0x2C, 0x3B, 0x3001, 0xff0c).toSortedSet()
        private val hangable = (listOf(0x2E, 0x2C, 0x2D, 0x3A, 0x3B, 0x22, 0x27) + (0x2018..0x201f)).toSortedSet()
        private val hangableFW = listOf(0x3001, 0x3002, 0xff0c, 0xff0e).toSortedSet()
        private const val hangWidth = 6
        private const val hyphenWidth = 6
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
                val ret = CodepointSequence(controlCharList.filter { index > it.second }.map { it.first })
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
                tokens.add(glue.glueSizeToGlueChars())
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
        private fun <E> java.util.ArrayList<E>.penultimateOrNull(): E? {
            return this.getOrNull(this.size - 2)
        }

        private fun penaliseWidening(score: Int, availableGlues: Double): Double =
            100.0 * (score / availableGlues).pow(3.0)
//            pow(score.toDouble(), 2.0)
        private fun penaliseTightening(score: Int, availableGlues: Double): Double =
            100.0 * (score / availableGlues).pow(3.0)
//            pow(score.toDouble(), 2.0)
        private fun penaliseHyphenation(score: Int, availableGlues: Double): Double =
            100.0 * (score / availableGlues).pow(3.0)
//            pow(score.toDouble().absoluteValue, 3.0 * tanh(paperWidth / 650.0))

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
        private fun CodePoint.glueCharToGlueSize() = when (this) {
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
            fun returnWithCC(fore: CodepointSequence, post: CodepointSequence): Pair<CodepointSequence, CodepointSequence> {
                val cc = fore.lastOrNull { it in 0x100000..0x10FFFF }
                return fore to post.also { if (cc != null) it.add(1, cc) }
            }

            //            val middlePoint = this.size / 2
            // search for the end of the vowel cluster for left and right
            // one with the least distance from the middle point will be used for hyphenating point
            val hyphenateCandidates = ArrayList<Int>() // stores indices
            val splitCandidates = ArrayList<Int>() // stores indices
            var i = 3
            while (i < this.size - 4) {
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

            val splitPoint = splitCandidates.minByOrNull { (font.getWidthNormalised(CodepointSequence(this.slice(0..it))).div(font.scale) - optimalCuttingPointInPx).absoluteValue }
            val hyphPoint = hyphenateCandidates.minByOrNull { (font.getWidthNormalised(CodepointSequence(this.slice(0..it))).div(font.scale) - optimalCuttingPointInPx).absoluteValue }

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

                return returnWithCC(fore, post)
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

                return returnWithCC(fore, post)
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
            0xF0520 to 7, // why????
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

        private fun CharArray.toSurrogatedString(): String = if (this.size == 1) "${this[0]}" else "${this[0]}${this[1]}"

        private inline fun Int.codepointToString() = Character.toChars(this).toSurrogatedString()

        private fun CodepointSequence.toReadable() = this.joinToString("") {
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
            else if (it in FIXED_BLOCK_1..FIXED_BLOCK_1+15)
                " <block ${it - FIXED_BLOCK_1 + 1}>"
            else if (it in GLUE_NEGATIVE_ONE..GLUE_POSITIVE_SIXTEEN)
                " <glue ${it.glueCharToGlueSize()}> "
            else if (it in 0xF0541..0xF055A) {
                (it - 0xF0541 + 0x1D670).codepointToString()
            }
            else if (it in 0xF0561..0xF057A) {
                (it - 0xF0561 + 0x1D68A).codepointToString()
            }
            else if (it in 0xF0530..0xF0539) {
                (it - 0xF0530 + 0x1D7F6).codepointToString()
            }
            else if (it in 0xF0520..0xF057F) {
                (it - 0xF0520 + 0x20).codepointToString()
            }
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
                        "<Glue ${it.first().glueCharToGlueSize()}>"
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

        private fun Int.glueSizeToGlueChars(): CodepointSequence {
            val tokens = CodepointSequence()

            if (this == 0)
                tokens.add(ZWSP)
            else if (this.absoluteValue <= 16)
                if (this > 0)
                    tokens.addAll(listOf(GLUE_POSITIVE_ONE + (this - 1)))
                else
                    tokens.addAll(listOf(GLUE_NEGATIVE_ONE + (this.absoluteValue - 1)))
            else {
                val fullGlues = this.absoluteValue / 16
                val smallGlues = this.absoluteValue % 16
                if (smallGlues > 0) {
                    if (this > 0)
                        tokens.addAll(
                            List(fullGlues) { GLUE_POSITIVE_SIXTEEN } +
                                    listOf(GLUE_POSITIVE_ONE + (smallGlues - 1))
                        )
                    else
                        tokens.addAll(
                            List(fullGlues) { GLUE_NEGATIVE_SIXTEEN } +
                                    listOf(GLUE_NEGATIVE_ONE + (smallGlues - 1))
                        )
                }
                else {
                    if (this > 0)
                        tokens.addAll(
                            List(fullGlues) { GLUE_POSITIVE_SIXTEEN }
                        )
                    else
                        tokens.addAll(
                            List(fullGlues) { GLUE_NEGATIVE_SIXTEEN }
                        )
                }
            }

            return tokens
        }

        private fun List<Block>.freezeIntoCodepointSequence(font: TerrarumSansBitmap): CodepointSequence {
            val out = CodepointSequence()

            val input = this.filter { it.block.text.isNotGlue() }
            if (input.isEmpty()) return out

            // process line indents
            if (input.first().posX > 0)
                out.addAll(input.first().posX.glueSizeToGlueChars())

            // process blocks
            input.forEachIndexed { index, it ->
                val posX = it.posX + 1 - font.interchar * 2
                val prevEndPos = if (index == 0) 0 else input[index-1].getEndPos()
                if (index > 0 && posX != prevEndPos) {
                    out.addAll((posX - prevEndPos).glueSizeToGlueChars())
                }
                out.addAll(it.block.text)
            }

            return out
        }

        data class NoTexGlyphLayout(val text: CodepointSequence, val width: Int) {
            val penultimateCharOrNull: CodePoint?
                get() = text.getOrNull(text.size - 2)
            val secondCharOrNull: CodePoint?
                get() = text.getOrNull(1)
        }

        private fun createGlyphLayout(font: TerrarumSansBitmap, str: CodepointSequence): NoTexGlyphLayout {
            return NoTexGlyphLayout(str, font.getWidthNormalised(str).div(font.scale))
        }

        private fun List<Block>.getGlueSizeSum(font: TerrarumSansBitmap): Int {
            var out = 0

            val input = this.filter { it.block.text.isNotGlue() }
            if (input.isEmpty()) return 0

            // process blocks
            input.forEachIndexed { index, it ->
                val posX = it.posX + 1 - font.interchar * 2
                val prevEndPos = if (index == 0) 0 else input[index-1].getEndPos()
                if (index > 0 && posX != prevEndPos) {
                    out += posX - prevEndPos
                }
            }

            return out
        }

    } // end of companion object
}


