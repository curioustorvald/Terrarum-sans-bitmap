package net.torvald.terrarumsansbitmap

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import net.torvald.terrarumsansbitmap.MovableType.Companion.isGlue
import net.torvald.terrarumsansbitmap.MovableType.Companion.isNotGlue
import net.torvald.terrarumsansbitmap.gdx.CodePoint
import net.torvald.terrarumsansbitmap.gdx.CodepointSequence
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.getHash
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.TextCacheObj
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.ShittyGlyphLayout
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
    val paperWidth: Int,
    internal val isNull: Boolean = false
): Disposable {

    var height = 0; private set
    internal val hash: Long = inputText.getHash()
    private var disposed = false
    private val typesettedSlugs = ArrayList<List<Block>>()

    override fun dispose() {
        if (!disposed) {
            disposed = true
            typesettedSlugs.forEach {
                it.forEach {
                    it.block.dispose()
                }
            }
        }
    }

    // perform typesetting
    init { if (inputText.isNotEmpty() && !isNull) {
        if (paperWidth < 100) throw IllegalArgumentException("Width too narrow; width must be at least 100 pixels (got $paperWidth)")

        println("Paper width: $paperWidth")

        val lines = inputText.tokenise()
        lines.debugprint()

        lines.forEachIndexed { linenum, it ->
            println("Processing input text line ${linenum + 1} (word count: ${it.size})...")

            val boxes: MutableList<TextCacheObj> = it.map { font.createTextCache(it) }.toMutableList()
            var slug = ArrayList<Block>() // slug of the linotype machine
            var slugWidth = 0
            var ignoreThisLine = false

            fun dequeue() = boxes.removeFirst()
            fun addHyphenatedTail(box: TextCacheObj) = boxes.add(0, box)
            fun addToSlug(box: TextCacheObj) {
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, box))
                slugWidth += box.width
            }
            fun dispatchSlug() {
                typesettedSlugs.add(slug)

                slug = ArrayList()
                slugWidth = 0
            }

            ///////////////////////////////////////////////////////////////////////////////////////////////

            // the slug is likely end with a glue, must take care of it (but don't modify the slug itself)
            fun getBadnessW(box: TextCacheObj): Pair<Float, Int> {
                val slug = slug.toMutableList()

                // remove the trailing glue(s?) in the slug copy
                while (slug.lastOrNull()?.block?.isGlue() == true) {
                    slug.removeLastOrNull()
                }

                var slugWidth = slug.lastOrNull()?.getEndPos() ?: 0
                if (slug.isNotEmpty() && hangable.contains(slug.last().block.penultimateChar))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && hangableFW.contains(slug.last().block.penultimateChar))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth).absoluteValue
                val badness = difference.toFloat()

                return badness to difference
            }

            fun getBadnessT(box: TextCacheObj): Pair<Float, Int> {
                val slug = slug.toMutableList()

                // add the box to the slug copy
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, box))

                var slugWidth = slugWidth + box.width
                if (slug.isNotEmpty() && hangable.contains(slug.last().block.penultimateChar))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && hangableFW.contains(slug.last().block.penultimateChar))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth).absoluteValue
                val badness = penaliseTightening(difference)

                return badness to difference
            }

            fun getBadnessH(box: TextCacheObj): Pair<Float, Int> {
                val slug = slug.toMutableList()
                val (hyphHead, hyphTail) = box.text.hyphenate().toList().map { font.createTextCache(it) }

                // add the hyphHead to the slug copy
                val nextPosX = (slug.lastOrNull()?.getEndPos() ?: 0)
                slug.add(Block(nextPosX, hyphHead))

                var slugWidth = slugWidth + hyphHead.width
                if (slug.isNotEmpty() && hangable.contains(slug.last().block.penultimateChar))
                    slugWidth -= hangWidth
                else if (slug.isNotEmpty() && hangableFW.contains(slug.last().block.penultimateChar))
                    slugWidth -= hangWidthFW

                val difference = (paperWidth - slugWidth)
                val badness = penaliseHyphenation(difference.absoluteValue)

                return badness to difference
            }

            ///////////////////////////////////////////////////////////////////////////////////////////////

            while (boxes.isNotEmpty()) {
                val box = dequeue()

                if (box.isNotGlue()) {
                    // deal with the hangables
                    val slugWidthForOverflowCalc = if (hangable.contains(box.penultimateChar))
                        slugWidth - hangWidth
                    else if (hangableFW.contains(box.penultimateChar))
                        slugWidth - hangWidthFW
                    else
                        slugWidth

                    // if adding the box would cause overflow
                    if (slugWidthForOverflowCalc + box.width > paperWidth) {
                        // badness: always positive and weighted
                        // widthDelta: can be positive or negative
                        val (badnessW, widthDeltaW) = getBadnessW(box) // widthDeltaW is always positive
                        val (badnessT, widthDeltaT) = getBadnessT(box) // widthDeltaT is always positive
                        val (badnessH, widthDeltaH) = getBadnessH(box) // widthDeltaH can be anything

                        val (selectedBadness, selectedWidthDelta, selectedStrat) = listOf(
                            Triple(badnessW, widthDeltaW, "Widen"),
                            Triple(badnessT, widthDeltaT, "Tighten"),
                            Triple(badnessH, widthDeltaH, "Hyphenate"),
                        ).minByOrNull { it.first }!!

                        println("    Line ${typesettedSlugs.size + 1} Strat: $selectedStrat (badness $selectedBadness, delta $selectedWidthDelta; full badness WTH = $badnessW, $badnessT, $badnessH; full delta WTH = $widthDeltaW, $widthDeltaT, $widthDeltaH)")
                        println("          Interim Slug: [ ${slug.map { it.block.text.toReadable() }.joinToString(" | ")} ]")

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

                                moveSlugsToFitTheWidth(operation, slug, selectedWidthDelta)

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

                                val (hyphHead, hyphTail) = box.text.hyphenate().toList().map { font.createTextCache(it) }

                                // widen: 1, tighten: -1
                                val operation = widthDeltaH.sign

                                // insert hyphHead into the slug
                                addToSlug(hyphHead)

                                moveSlugsToFitTheWidth(operation, slug, selectedWidthDelta)

                                // put the tail into the upcoming words
                                addHyphenatedTail(hyphTail)
                            }
                        }

                        println("  > Line ${typesettedSlugs.size + 1} Final Slug: [ ${slug.map { it.block.text.toReadable() }.joinToString(" | ")} ]")
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
                println("  > Line ${typesettedSlugs.size + 1} Final Slug: [ ${slug.map { it.block.text.toReadable() }.joinToString(" | ")} ]")
                dispatchSlug()
            }
        } // end of lines.forEach

        height = typesettedSlugs.size
    } }



    private fun lololololol() { if (inputText.isNotEmpty() && !isNull) {

        if (paperWidth < 100) throw IllegalArgumentException("Width too narrow; width must be at least 100 pixels (got $paperWidth)")

        val inputCharSeqsTokenised = inputText.tokenise()

        val inputWords = inputCharSeqsTokenised.map {
            TODO()
        }.toMutableList() // list of [ word, word, \n, word, word, word, ... ]


        println("Length of input text: ${inputText.size}")
        println("Token size: ${inputCharSeqsTokenised.size}")
        println("Paper width: $paperWidth")

        var currentLine = ArrayList<Block>()
        var wordCount = 0

        fun dequeue() {
            wordCount += 1
        }

        fun flush() {
//            println("\n  Anchors [$wordCount] =${" ".repeat(if (wordCount < 10) 3 else if (wordCount < 100) 2 else 1)}${currentLine.map { it.posX }.joinToString()}\n")

            // flush the line
            typesettedSlugs.add(currentLine)
            currentLine = ArrayList()
        }

        fun justifyAndFlush(
            lineWidthNow: Int,
            thisWordObj: TextCacheObj,
            thisWord: ShittyGlyphLayout,
            hyphenated: Boolean = false,
            hyphenationScore0: Int? = null,
            hyphenationScore: Float? = null
        ) { /*
            println("    JustifyAndFlush: widthNow = $lineWidthNow, thisWord = ${thisWord.textBuffer.toReadable()}, hyphenated = $hyphenated")


            val thislineEndsWithHangable =
                hangable.contains(currentLine.last().block.glyphLayout!!.textBuffer.penultimate())
            val nextWordEndsWithHangable = hangable.contains(thisWordObj.glyphLayout!!.textBuffer.penultimate())

            val scoreForWidening =
                (width - (lineWidthNow - if (thislineEndsWithHangable) hangWidth else 0)).toFloat()
            val thisWordWidth = thisWord.width - if (nextWordEndsWithHangable) hangWidth else 0
            val scoreForAddingWordThenTightening0 = lineWidthNow + spaceWidth + thisWordWidth - width
            val scoreForAddingWordThenTightening = penaliseTightening(scoreForAddingWordThenTightening0)

            val (hyphFore, hypePost) = if (hyphenated) thisWord.textBuffer to CodepointSequence()
                else thisWord.textBuffer.hyphenate() // hypePost may be empty!
            val scoreForHyphenateThenTryAgain0 = if (!hyphenated) {
                val halfWordWidth = font.getWidth(hyphFore)
                (lineWidthNow + spaceWidth + halfWordWidth - width)
            }
            else {
                2147483647
            }
            val scoreForHyphenateThenTryAgain = penaliseHyphenation(scoreForHyphenateThenTryAgain0)

            println("Prestrategy [L ${lines.size}] Scores: W $scoreForWidening, T $scoreForAddingWordThenTightening ($scoreForAddingWordThenTightening0), H $scoreForHyphenateThenTryAgain ($scoreForHyphenateThenTryAgain0)")


            if (scoreForHyphenateThenTryAgain < minOf(scoreForWidening, scoreForAddingWordThenTightening) && !hyphenated) {
                println("        Hyphenation: '${hyphFore.toReadable()}' '${hypePost.toReadable()}'")

                inputWords[wordCount] = font.createTextCache(hyphFore)
                inputWords.add(wordCount + 1, font.createTextCache(hypePost))

                // testing only
//                inputWords[wordCount] = font.createTextCache(CodepointSequence("FORE-".toCharArray().map { it.toInt() }))
//                inputWords.add(wordCount + 1, font.createTextCache((CodepointSequence("POST".toCharArray().map { it.toInt() }))))

                val thisWordObj = inputWords[wordCount]
                val thisWord = thisWordObj.glyphLayout!!

                val newBlock = Block(currentLine.last().getEndPos() + spaceWidth, thisWordObj)
                currentLine.add(newBlock)

                val lineWidthNow = if (currentLine.isEmpty()) -spaceWidth
                else currentLine.penultimate().getEndPos() - 1 // subtract the tiny space AFTER the hyphen

                justifyAndFlush(lineWidthNow, thisWordObj, thisWord, true, scoreForHyphenateThenTryAgain0, scoreForHyphenateThenTryAgain)
            }
            else {
                // widen: 1, tighten: -1
                val operation = if (hyphenated) -1
                else if (scoreForWidening == 0f && scoreForAddingWordThenTightening == 0f) 0
                else if (scoreForWidening < scoreForAddingWordThenTightening) 1
                else -1

                // if adding word and contracting is better (has LOWER score), add the word
                if (operation == -1) {
                    if (!hyphenated) currentLine.add(Block(lineWidthNow + spaceWidth, thisWordObj))
                    // remove this word from the list of future words
                    dequeue()
                }

                val numberOfWords = currentLine.size

                // continue with the widening/contraction
                val moveDeltas = IntArray(numberOfWords)

                val finalScore = when (operation) {
                    1 -> scoreForWidening.toFloat()
                    -1 -> scoreForAddingWordThenTightening0.toFloat()
                    else -> 0f
                }

                if (numberOfWords > 1) {
                    val moveAmountsByWord =
                            coalesceIndices(sortWordsByPriority(currentLine, round(finalScore.absoluteValue).toInt()))
                    for (i in 1 until moveDeltas.size) {
                        moveDeltas[i] = moveDeltas[i - 1] + moveAmountsByWord.getOrElse(i) { 0 }
                    }
                }

                moveDeltas.indices.forEach {
                    moveDeltas[it] = moveDeltas[it] * finalScore.sign.toInt()
                }


                val widthOld = currentLine.last().getEndPos()

                val anchorsOld = currentLine.map { it.posX }

                // apply the operation
                moveDeltas.forEachIndexed { index, it ->
                    val delta = operation * it
                    currentLine[index].posX += delta
                }

                val anchorsNew = currentLine.map { it.posX }

                val widthNew = currentLine.last().getEndPos()

                val lineHeader = "Strategy [L ${lines.size}]: "
                val lineHeader2 = " ".repeat(lineHeader.length)
                println(
                        lineHeader + (if (operation * finalScore.sign.toInt() == 0) "Nop" else if (operation * finalScore.sign.toInt() == 1) "Widen" else "Tighten") +
                                " (W $scoreForWidening, T $scoreForAddingWordThenTightening, H $hyphenationScore; $finalScore), " +
                                "width: $widthOld -> $widthNew, wordCount: $numberOfWords, " +
                                "thislineEndsWithHangable: $thislineEndsWithHangable, nextWordEndsWithHangable: $nextWordEndsWithHangable"
                )
                println(lineHeader2 + "moveDelta: ${moveDeltas.map { it * operation }} (${moveDeltas.size})")
                println(lineHeader2 + "anchors old: $anchorsOld (${anchorsOld.size})")
                println(lineHeader2 + "anchors new: $anchorsNew (${anchorsNew.size})")
                println()

                // flush the line
                flush()
            }*/
        }

        var thisWordObj: TextCacheObj
        var thisWord: ShittyGlyphLayout
        var thisWordStr: CodepointSequence
        var lineWidthNow: Int
        while (wordCount < inputWords.size) {
            thisWordObj = inputWords[wordCount]
            thisWord = thisWordObj.glyphLayout!!
            thisWordStr = thisWord.textBuffer // ALWAYS starts and ends with \0

            lineWidthNow = if (currentLine.isEmpty()) -spaceWidth
            else currentLine.last().getEndPos()

            // thisWordStr.size > 2 : ignores nulls that somehow being inserted between CJ characters
            // (thisWordStr.size == 2 && currentLine.isEmpty()) : but DON'T ignore new empty lines (the line starts with TWO NULLS then NULL-LF-NULL)
            if (thisWordStr.size > 2 || (thisWordStr.size == 2 && currentLine.isEmpty())) {

                val spaceWidth = if (thisWordStr[1].isCJ() && currentLine.isNotEmpty()) 0 else spaceWidth

                println(
                    "Processing word [$wordCount] ${thisWordStr.toReadable()} ; \t\t${
                        thisWordStr.joinToString(
                            " "
                        ) { it.toHex() }
                    }"
                )

                // if the word is \n
                if (thisWordStr.size == 3 && thisWordStr[1] == 0x0A) {
                    println("Strategy [L ${typesettedSlugs.size}]: line is shorter than the paper width ($lineWidthNow < $paperWidth)")

                    // flush the line
                    if (lineWidthNow >= 0) flush()

                    // remove the word from the list of future words
                    dequeue()
                }
                // decide if it should add last word and make newline, or make newline then add the word
                // would adding the current word would cause line overflow?
                else if (lineWidthNow + spaceWidth + thisWord.width >= paperWidth) {
                    justifyAndFlush(lineWidthNow, thisWordObj, thisWord)
                }
                // typeset the text normally
                else {
                    currentLine.add(Block(lineWidthNow + spaceWidth, thisWordObj))

                    // remove the word from the list of future words
                    dequeue()
                }
            }
            else {
                dequeue()
            }
        } // end while

        println("Strategy [L ${typesettedSlugs.size}]: (end of the text)")
        flush()



        height = typesettedSlugs.size
    } }

    fun draw(batch: Batch, x: Int, y: Int, lineStart: Int = 0, linesToDraw: Int = -1, lineHeight: Int = 24) =
        draw(batch, x.toFloat(), y.toFloat(), lineStart, linesToDraw, lineHeight)

    fun draw(batch: Batch, x: Float, y: Float, lineStart: Int = 0, linesToDraw: Int = 2147483647, lineHeight: Int = 24) {
        if (isNull) return

        typesettedSlugs.subList(lineStart, minOf(typesettedSlugs.size, lineStart + linesToDraw)).forEachIndexed { lineNum, lineBlocks ->
//            println("Line [${lineNum+1}] anchors: "+ lineBlocks.map { it.posX }.joinToString())

            lineBlocks.forEach {
                batch.draw(it.block.glyphLayout!!.linotype, x + it.posX - 16, y + lineNum * lineHeight)
            }

//            font.draw(batch, "I", x, y + lineNum * lineHeight + 14)
        }
    }

    private data class Block(var posX: Int, val block: TextCacheObj) { // a single word
        fun getEndPos() = this.posX + this.block.width
//        fun isGlue() = this.block.text.isGlue()
//        inline fun isNotGlue() = !isGlue()
//        fun getGlueWidth() = this.block.text[0].toGlueSize()
    }

    companion object {
        private val periods = listOf(0x2E, 0x3A, 0x21, 0x3F, 0x2026, 0x3002, 0xff0e).toSortedSet()
        private val quots = listOf(0x22, 0x27, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A).toSortedSet()
        private val commas = listOf(0x2C, 0x3B, 0x3001, 0xff0c).toSortedSet()
        private val hangable = listOf(0x2E, 0x2C).toSortedSet()
        private val hangableFW = listOf(0x3001, 0x3002, 0xff0c, 0xff0e).toSortedSet()
        private const val spaceWidth = 5
        private const val hangWidth = 6
        private const val hangWidthFW = TerrarumSansBitmap.W_ASIAN_PUNCT

        private fun CodePoint.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

        private fun moveSlugsToFitTheWidth(operation: Int, slug: ArrayList<Block>, selectedWidthDelta: Int) {
            var gluesInfo = slug.mapIndexed { index, block -> block to index }.filter { (block, index) ->
                block.block.isGlue()
            }.map { (block, index) ->
                val prevBlockEndsWith = if (index == 0) null else slug[index - 1].block.penultimateChar // last() will just return {NUL}
                Triple(block, index, prevBlockEndsWith)
            }
            // if there are no glues, put spaces between all characters
            if (gluesInfo.isEmpty()) {
                gluesInfo = slug.subList(1, slug.size).mapIndexed { index, block ->
                    val prevBlockEndsWith = slug[index].block.penultimateChar // last() will just return {NUL}
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
         * @return indices of blocks in the `currentLine`
         */
        private fun sortWordsByPriority(currentLine: List<Block>, length: Int): List<Int> {
            // priority:
            // 1. words ending with period/colon/!/?/ellipses
            // 2. words ending or starting with quotation marks or <>s
            // 3. words ending with comma or semicolon
            // 4. words

            val ret = ArrayList<Int>()

            while (ret.size < length) {
                // give "score" then sort by it to give both priority and randomisation
                val sackOfIndices = (1 until currentLine.size).map {
                    val thisWord = currentLine[it].block.glyphLayout!!.textBuffer

//                    println("    Index word [$it/$length]: ${thisWord.toReadable()} ; \t\t${thisWord.joinToString(" ") { it.toHex() }}")

                    val thisWordEnd = thisWord[thisWord.lastIndex]
                    val thisWordFirst = thisWord[0]

                    val priority = if (periods.contains(thisWordEnd))
                        1
                    else if (quots.contains(thisWordEnd) or quots.contains(thisWordFirst))
                        2
                    else if (commas.contains(thisWordEnd))
                        3
                    else
                        255

                    it to (Math.random() * 65535).toInt().or(priority.shl(16))
                }.sortedBy { it.second }.map { it.first }

                ret.addAll(sackOfIndices)
            }

            if (ret.isEmpty()) return emptyList()
            return ret.toList().subList(0, length)
        }

        // return: [ job count for 0th word, job count for 1st word, job count for 2nd word, ... ]
        private fun coalesceIndices(listOfJobs: IntArray): IntArray {
            if (listOfJobs.isEmpty()) return IntArray(0)

//            println("      sample: ${listOfJobs.joinToString()}")

            val ret = IntArray(listOfJobs.maxOrNull()!! + 1)
            listOfJobs.forEach {
                ret[it] += 1
            }

//            println("      ret: ${ret.joinToString()}")

            return ret
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

            val controlCharStack = ArrayList<CodePoint>()
            var colourCode: CodePoint? = null
            var colourCodeRemovalRequested = false

            var cM: CodePoint? = null
            var glue = 0

            fun getControlHeader() = if (colourCode != null)
                CodepointSequence(controlCharStack.reversed() + colourCode)
            else
                CodepointSequence(controlCharStack.reversed())



            fun sendoutBox() {
                tokens.add(CodepointSequence(listOf(0) + getControlHeader() + boxBuffer + listOf(0)))

                if (colourCodeRemovalRequested) {
                    colourCodeRemovalRequested = false
                    colourCode = null
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

            this.forEachIndexed { index, it ->
                val c0 = it

                if (c0.isColourCode()) {
                    colourCode = c0
                    appendToBuffer(c0)
                }
                else if (c0 == 0x100000) {
                    colourCodeRemovalRequested = true
                    appendToBuffer(c0)
                }
                else if (c0.isControlIn()) {
                    controlCharStack.add(0, c0)
                }
                else if (c0.isControlOut()) {
                    controlCharStack.removeAt(0)
                }
                else if (c0 == 0x0A) {
                    sendoutBox()
                    proceedToNextLine()
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
                    if (boxBuffer.isNotEmpty())
                        sendoutBox()
                    appendZeroGlue()
                    sendoutGlue()

                    appendToBuffer(c0)
                }
                else if (c0.isCJpunctOrParenEnd()) {
                    if (cM.isWhiteSpace())
                        sendoutGlue()

                    appendToBuffer(c0)
                }
                else if (c0.isCJ()) {
                    if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (cM.isCJparenStart()) {
                        /* do nothing */
                    }
                    else if (cM.isCJpunctOrParenEnd()) {
                        sendoutBox()
                        appendZeroGlue()
                        sendoutGlue()
                    }
                    else { // includes if cM.isCJ()
                        sendoutBox()
                    }

                    appendToBuffer(c0)
                }
                else {
                    if (cM.isCJ()) {
                        sendoutBox()
                    }
                    else if (cM.isWhiteSpace()) {
                        sendoutGlue()
                    }
                    else if (cM.isCJpunctOrParenEnd()) {
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
                if ((it[0].size == 2 && it[0][0] == 0 && it[0][1] == 0) || it[0].isZeroGlue())
                    it.removeAt(0)
            }

            return lines
        }

        private fun <E> java.util.ArrayList<E>.penultimate(): E {
            return this[this.size - 2]
        }

        private fun penaliseTightening(score: Int): Float = 0.0006f * score * score * score + 0.18f * score

        private fun penaliseHyphenation(score: Int): Float = (10.0 * pow(score.toDouble(), 1.0/3.0) + 0.47*score).toFloat()

        private fun CodePoint?.isCJ() = if (this == null) false else listOf(4, 6, 20).any {
            TerrarumSansBitmap.codeRange[it].contains(this)
        }

        private fun CodePoint?.isWhiteSpace() = if (this == null) false else whitespaceGlues.contains(this)

        private fun CodePoint?.isCJparenStart() = if (this == null) false else cjparenStarts.contains(this)
        private fun CodePoint?.isCJpunctOrParenEnd() = if (this == null) false else (cjpuncts.contains(this) || cjparenEnds.contains(this))
        private fun CodePoint?.isSmallKana() = if (this == null) false else jaSmallKanas.contains(this)
        private fun CodePoint?.isControlIn() = if (this == null) false else controlIns.contains(this)
        private fun CodePoint?.isControlOut() = if (this == null) false else controlOuts.contains(this)
        private fun CodePoint?.isColourCode() = if (this == null) false else colourCodes.contains(this)

        private fun CodepointSequence.isGlue() = this.size == 1 && (this[0] == ZWSP || this[0] in 0xFFFE0..0xFFFFF)
        private fun CodepointSequence.isNotGlue() = !this.isGlue()
        private fun CodepointSequence.isZeroGlue() = this.size == 1 && (this[0] == ZWSP)
        private fun CodePoint.toGlueSize() = when (this) {
            ZWSP -> 0
            in 0xFFFE0..0xFFFEF -> -(this - 0xFFFE0 + 1)
            in 0xFFFF0..0xFFFFF -> this - 0xFFFF0 + 1
            else -> throw IllegalArgumentException()
        }

        /**
         * Hyphenates the word at the middle ("paragraph" -> "para-graph")
         * 
         * @return left word ("para-"), right word ("graph")
         */
        private fun CodepointSequence.hyphenate(): Pair<CodepointSequence, CodepointSequence> {
            val middlePoint = this.size / 2
            // search for the end of the vowel cluster for left and right
            // one with the least distance from the middle point will be used for hyphenating point
            val hyphenateCandidates = ArrayList<Int>()
            val splitCandidates = ArrayList<Int>()
            var i = 1
            while (i < this.size) {
                val thisChar = this[i]
                val prevChar = this[i-1]
                if (!isVowel(thisChar) && isVowel(prevChar))
                    hyphenateCandidates.add(i)
                else if (thisChar == SHY && isVowel((prevChar))) {
                    hyphenateCandidates.add(i)
                    i += 1 // skip SHY
                }
                if (isHangulPK(prevChar) && isHangulI(thisChar))
                    splitCandidates.add((i))

                i += 1
            }

            hyphenateCandidates.removeIf { it <= 2 || it >= this.size - 2 }
            splitCandidates.removeIf { it <= 2 || it >= this.size - 2 }

//            println("Hyphenating ${this.toReadable()} -> [${hyphenateCandidates.joinToString()}]")

            if (hyphenateCandidates.isEmpty() && splitCandidates.isEmpty()) {
                return this to CodepointSequence()
            }

            // priority: 1st split, 2nd hyphenate

            val splitPoint = splitCandidates.minByOrNull { (it - middlePoint).absoluteValue }
            val hyphPoint = hyphenateCandidates.minByOrNull { (it - middlePoint).absoluteValue }

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

        private fun isVowel(c: CodePoint) = vowels.contains(c)
        private fun isHangulI(c: CodePoint) = hangulI.contains(c)
        private fun isHangulPK(c: CodePoint) = hangulPK.contains(c)

        private val vowels = (listOf(0x41, 0x45, 0x49, 0x4f, 0x55, 0x59, 0x41, 0x65, 0x69, 0x6f, 0x75, 0x79) +
                (0xc0..0xc6) + (0xc8..0xcf) + (0xd2..0xd6) + (0xd8..0xdd) +
                (0xe0..0xe6) + (0xe8..0xef) + (0xf2..0xf6) + (0xf8..0xfd) +
                (0xff..0x105) + (0x112..0x118) + (0x128..0x131) + (0x14c..0x153) +
                (0x168..0x173) + (0x176..0x178)).toSortedSet()

        private val hangulI = ((0x1100..0x115E) + (0xA960..0xA97F)).toSortedSet()
        private val hangulPK = ((0x1160..0x11FF) + (0xD7B0..0xD7FF)).toSortedSet()
        private val colourCodes = (0x10F000..0x10FFFF).toSortedSet()
        private val controlIns = listOf(0xFFFA2, 0xFFFA3, 0xFFFC1, 0xFFFC2).toSortedSet()
        private val controlOuts = listOf(0xFFFBF, 0xFFFC0).toSortedSet()
        private val whitespaceGlues = hashMapOf(
            0x20 to 5,
            0x3000 to 16,
        )
        private val cjpuncts = listOf(0x203c, 0x2047, 0x2048, 0x2049, 0x3001, 0x3002, 0x3006, 0x303b, 0x30a0, 0x30fb, 0x30fc, 0x301c, 0xff01, 0xff0c, 0xff0e, 0xff1a, 0xff1b, 0xff1f, 0xff5e, 0xff65).toSortedSet()
        private val cjparenStarts = listOf(0x3008, 0x300A, 0x300C, 0x300E, 0x3010, 0x3014, 0x3016, 0x3018, 0x301A, 0x30fb, 0xff65).toSortedSet()
        private val cjparenEnds = listOf(0x3009, 0x300B, 0x300D, 0x300F, 0x3011, 0x3015, 0x3017, 0x3019, 0x301B).toSortedSet()
        private val jaSmallKanas = "ァィゥェォッャュョヮヵヶぁぃぅぇぉっゃゅょゎゕゖㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ".map { it.toInt() }.toSortedSet()

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

        private fun TextCacheObj.isNotGlue(): Boolean {
            return this.glyphLayout!!.textBuffer.isNotGlue()
        }
        private fun TextCacheObj.isGlue(): Boolean {
            return this.glyphLayout!!.textBuffer.isGlue()
        }

    } // end of companion object
}

