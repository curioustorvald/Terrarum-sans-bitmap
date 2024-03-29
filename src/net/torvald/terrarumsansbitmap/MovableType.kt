package net.torvald.terrarumsansbitmap

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import net.torvald.terrarumsansbitmap.gdx.CodePoint
import net.torvald.terrarumsansbitmap.gdx.CodepointSequence
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.getHash
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.TextCacheObj
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.ShittyGlyphLayout
import java.lang.Math.pow
import kotlin.math.*

/**
 * Created by minjaesong on 2024-03-24.
 */
class MovableType(
    val font: TerrarumSansBitmap,
    val inputText: CodepointSequence,
    val width: Int,
    internal val isNull: Boolean = false
): Disposable {

    var height = 0; private set
    internal val hash: Long = inputText.getHash()
    private var disposed = false
    private val lines = ArrayList<List<Block>>()

    override fun dispose() {
        if (!disposed) {
            disposed = true
            lines.forEach {
                it.forEach {
                    it.block.dispose()
                }
            }
        }
    }

    // perform typesetting
    init { if (inputText.isNotEmpty() && !isNull) {

        if (width < 100) throw IllegalArgumentException("Width too narrow; width must be at least 100 pixels (got $width)")

        val inputCharSeqsTokenised = inputText.tokenise()

        val inputWords = inputCharSeqsTokenised.map {
            val seq = it.also { seq ->
                seq.add(0, 0)
                seq.add(0)
            }

            font.createTextCache(CodepointSequence(seq))
        }.toMutableList() // list of [ word, word, \n, word, word, word, ... ]


        println("Length of input text: ${inputText.size}")
        println("Token size: ${inputCharSeqsTokenised.size}")
        println("Paper width: $width")

        var currentLine = ArrayList<Block>()
        var wordCount = 0

        fun dequeue() {
            wordCount += 1
        }

        fun flush() {
//            println("\n  Anchors [$wordCount] =${" ".repeat(if (wordCount < 10) 3 else if (wordCount < 100) 2 else 1)}${currentLine.map { it.posX }.joinToString()}\n")

            // flush the line
            lines.add(currentLine)
            currentLine = ArrayList()
        }

        fun justifyAndFlush(
            lineWidthNow: Int,
            thisWordObj: TextCacheObj,
            thisWord: ShittyGlyphLayout,
            hyphenated: Boolean = false,
            hyphenationScore0: Int? = null,
            hyphenationScore: Float? = null
        ) {
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
            }
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
                    println("Strategy [L ${lines.size}]: line is shorter than the paper width ($lineWidthNow < $width)")

                    // flush the line
                    if (lineWidthNow >= 0) flush()

                    // remove the word from the list of future words
                    dequeue()
                }
                // decide if it should add last word and make newline, or make newline then add the word
                // would adding the current word would cause line overflow?
                else if (lineWidthNow + spaceWidth + thisWord.width >= width) {
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

        println("Strategy [L ${lines.size}]: (end of the text)")
        flush()



        height = lines.size
    } }

    fun draw(batch: Batch, x: Int, y: Int, lineStart: Int = 0, linesToDraw: Int = -1, lineHeight: Int = 24) =
        draw(batch, x.toFloat(), y.toFloat(), lineStart, linesToDraw, lineHeight)

    fun draw(batch: Batch, x: Float, y: Float, lineStart: Int = 0, linesToDraw: Int = 2147483647, lineHeight: Int = 24) {
        if (isNull) return

        lines.subList(lineStart, minOf(lines.size, lineStart + linesToDraw)).forEachIndexed { lineNum, lineBlocks ->
//            println("Line [${lineNum+1}] anchors: "+ lineBlocks.map { it.posX }.joinToString())

            lineBlocks.forEach {
                batch.draw(it.block.glyphLayout!!.linotype, x + it.posX - 16, y + lineNum * lineHeight)
            }

//            font.draw(batch, "I", x, y + lineNum * lineHeight + 14)
        }
    }

    private data class Block(var posX: Int, val block: TextCacheObj) { // a single word
        fun getEndPos() = this.posX + this.block.glyphLayout!!.width
    }

    companion object {
        private val periods = listOf(0x2E, 0x3A, 0x21, 0x3F, 0x2026, 0x3002, 0xff0e).toSortedSet()
        private val quots = listOf(0x22, 0x27, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A).toSortedSet()
        private val commas = listOf(0x2C, 0x3B, 0x3001, 0xff0c).toSortedSet()
        private val hangable = listOf(0x2E, 0x2C).toSortedSet()
        private val spaceWidth = 5
        private val hangWidth = 6

        private fun Int.toHex() = "U+${this.toString(16).padStart(4, '0').toUpperCase()}"

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
        private fun coalesceIndices(listOfJobs: List<Int>): IntArray {
            if (listOfJobs.isEmpty()) return IntArray(0)

//            println("      sample: ${listOfJobs.joinToString()}")

            val ret = IntArray(listOfJobs.maxOrNull()!! + 1)
            listOfJobs.forEach {
                ret[it] += 1
            }

//            println("      ret: ${ret.joinToString()}")

            return ret
        }

        private fun CodepointSequence.tokenise(): MutableList<CodepointSequence> {
            val tokens = mutableListOf<CodepointSequence>()
            var currentToken = mutableListOf<Int>()

            val controlCharStack = ArrayList<CodePoint>()
            var colourCode: CodePoint? = null
            var colourCodeRemovalRequested = false

            fun getControlHeader() = if (colourCode != null)
                CodepointSequence(controlCharStack.reversed() + colourCode)
            else
                CodepointSequence(controlCharStack.reversed())

            fun submitBlock(c: CodepointSequence) {
                tokens.add(CodepointSequence(getControlHeader() + c))

                if (colourCodeRemovalRequested) {
                    colourCodeRemovalRequested = false
                    colourCode = null
                }
            }

            fun appendToWord(char: CodePoint) {
                currentToken.add(char)
            }

            this.forEach {
                if (it == 0x20 || it == 0x0A) {
                    submitBlock(CodepointSequence(currentToken))
                    if (it != 0x20)
                        submitBlock(CodepointSequence(listOf(it)))
                    currentToken = mutableListOf()
                }
                else if (it.isCJ()) {
                    // flush out existing buffer
                    CodepointSequence(currentToken).let {
                        if (it.isNotEmpty()) submitBlock(it)
                    }
                    // tokenise this single character
                    submitBlock(CodepointSequence(listOf(it)))
                    // prepare new buffer, even if it's wasted because next character is also Chinese/Japanese
                    currentToken = mutableListOf()
                }
                else if (it.isColourCode()) {
                    colourCode = it
                    appendToWord(it)
                }
                else if (it == 0x100000) {
                    colourCodeRemovalRequested = true
                    appendToWord(it)
                }
                else if (it.isControlIn()) {
                    controlCharStack.add(0, it)
                }
                else if (it.isControlOut()) {
                    controlCharStack.removeAt(0)
                }
                else {
                    appendToWord(it)
                }
            }

            // Add the last token if it's not empty
            submitBlock(CodepointSequence(currentToken))

            return tokens
        }

        private fun <E> java.util.ArrayList<E>.penultimate(): E {
            return this[this.size - 2]
        }

        private fun penaliseTightening(score: Int): Float = 0.0006f * score * score * score + 0.18f * score

        private fun penaliseHyphenation(score: Int): Float = (10.0 * pow(score.toDouble(), 1.0/3.0) + 0.47*score).toFloat()

        private fun CodePoint.isCJ() = listOf(4, 6).any {
            TerrarumSansBitmap.codeRange[it].contains(this)
        }

        private fun CodePoint.isControlIn() = controlIns.contains(this)
        private fun CodePoint.isControlOut() = controlOuts.contains(this)
        private fun CodePoint.isColourCode() = colourCodes.contains(this)

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
            for (i in 1 until  this.size) {
                val thisChar = this[i]
                val prevChar = this[i-1]
                if (!isVowel(thisChar) && isVowel(prevChar))
                    hyphenateCandidates.add(i)
                if (isHangulPK(prevChar) && isHangulI(thisChar))
                    splitCandidates.add((i))
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

        private fun CodepointSequence.toReadable() = this.joinToString("") { Character.toString(it.toChar()) }

    } // end of companion object
}

