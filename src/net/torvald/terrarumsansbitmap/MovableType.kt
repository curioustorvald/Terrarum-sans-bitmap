package net.torvald.terrarumsansbitmap

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.utils.Disposable
import net.torvald.terrarumsansbitmap.gdx.CodepointSequence
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.getHash
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap.Companion.TextCacheObj
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
            val seq = if (it.isEmpty())
                CodepointSequence(listOf(0x00))
            else {
                it.also { seq ->
                    seq.add(0, 0)
                    seq.add(0)
                }
            }

            font.createTextCache(CodepointSequence(seq))
        }
        // list of [ word, word, \n, word, word, word, ... ]

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

        fun justifyAndFlush(lineWidthNow: Int, thisWordObj: TextCacheObj, thisWord: TerrarumSansBitmap.Companion.ShittyGlyphLayout) {
            val thislineEndsWithHangable = hangable.contains(currentLine.last().block.glyphLayout!!.textBuffer.penultimate())
            val nextWordEndsWithHangable = hangable.contains(thisWordObj.glyphLayout!!.textBuffer.penultimate())

            val scoreForWidening = (width - (lineWidthNow - if (thislineEndsWithHangable) hangWidth else 0)).toFloat()
            val thisWordWidth = thisWord.width - if (nextWordEndsWithHangable) hangWidth else 0
            val scoreForAddingWordThenTightening0 = lineWidthNow + spaceWidth + thisWordWidth - width
            val scoreForAddingWordThenTightening = penaliseTightening(scoreForAddingWordThenTightening0)
            // widen: 1, tighten: -1
            val operation = if (scoreForWidening == 0f && scoreForAddingWordThenTightening == 0f) 0
            else if (scoreForWidening < scoreForAddingWordThenTightening) 1
            else -1

            // if adding word and contracting is better (has LOWER score), add the word
            if (operation == -1) {
                currentLine.add(Block(lineWidthNow + spaceWidth, thisWordObj))
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
                val moveAmountsByWord = coalesceIndices(sortWordsByPriority(currentLine, round(finalScore.absoluteValue).toInt()))
                for (i in 1 until moveDeltas.size) {
                    moveDeltas[i] = moveDeltas[i - 1] + moveAmountsByWord.getOrElse(i) { 0 }
                }
            }

            moveDeltas.indices.forEach {
                moveDeltas[it] = moveDeltas[it] * finalScore.sign.toInt()
            }


            val widthOld = currentLine.last().let { it.posX + it.block.glyphLayout!!.width }

            val anchorsOld = currentLine.map { it.posX }

            // apply the operation
            moveDeltas.forEachIndexed { index, it ->
                val delta = operation * it
                currentLine[index].posX += delta
            }

            val anchorsNew = currentLine.map { it.posX }

            val widthNew = currentLine.last().let { it.posX + it.block.glyphLayout!!.width }

            val lineHeader = "Strategy [L ${lines.size}]: "
            val lineHeader2 = " ".repeat(lineHeader.length)
            println(lineHeader + (if (operation * finalScore.sign.toInt() == 0) "Nop" else if (operation * finalScore.sign.toInt() == 1) "Widen" else "Tighten") +
                    " (W $scoreForWidening, T $scoreForAddingWordThenTightening; $finalScore), " +
                    "width: $widthOld -> $widthNew, wordCount: $numberOfWords, " +
                    "thislineEndsWithHangable: $thislineEndsWithHangable, nextWordEndsWithHangable: $nextWordEndsWithHangable")
            println(lineHeader2 + "moveDelta: ${moveDeltas.map { it * operation }} (${moveDeltas.size})")
            println(lineHeader2 + "anchors old: $anchorsOld (${anchorsOld.size})")
            println(lineHeader2 + "anchors new: $anchorsNew (${anchorsNew.size})")
            println()

            // flush the line
            flush()
        }

        var thisWordObj = inputWords[wordCount]
        var thisWord = thisWordObj.glyphLayout!!
        var thisWordStr = thisWord.textBuffer // ALWAYS starts and ends with \0
        var lineWidthNow = if (currentLine.isEmpty()) -spaceWidth else currentLine.last().let { it.posX + it.block.glyphLayout!!.width }
        while (wordCount < inputWords.size) {
            thisWordObj = inputWords[wordCount]
            thisWord = thisWordObj.glyphLayout!!
            thisWordStr = thisWord.textBuffer // ALWAYS starts and ends with \0
            lineWidthNow = if (currentLine.isEmpty()) -spaceWidth else currentLine.last().let { it.posX + it.block.glyphLayout!!.width }

            println("Processing word [$wordCount] ${thisWordStr.joinToString("") { Character.toString(it.toChar()) }} ; \t\t${thisWordStr.joinToString(" ") { it.toHex() }}")

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

    private data class Block(var posX: Int, val block: TextCacheObj) // a single word

    companion object {
        private val periods = listOf(0x2E, 0x3A, 0x21, 0x3F, 0x2026, 0x3002).toHashSet()
        private val quots = listOf(0x22, 0x27, 0xAB, 0xBB, 0x2018, 0x2019, 0x201A, 0x201B, 0x201C, 0x201D, 0x201E, 0x201F, 0x2039, 0x203A).toHashSet()
        private val commas = listOf(0x2C, 0x3B, 0x3001).toHashSet()
        private val hangable = listOf(0x2E, 0x2C).toHashSet()
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

//                    println("    Index word [$it/$length]: ${thisWord.joinToString("") { Character.toString(it.toChar()) }} ; \t\t${thisWord.joinToString(" ") { it.toHex() }}")

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

            this.forEach {
                if (it == 0x20 || it == 0x0A) {
                    tokens.add(CodepointSequence(currentToken))
                    if (it != 0x20)
                        tokens.add(CodepointSequence(listOf(it)))
                    currentToken = mutableListOf()
                }
                else {
                    currentToken.add(it)
                }
            }

            // Add the last token if it's not empty
            if (currentToken.isNotEmpty()) {
                tokens.add(CodepointSequence(currentToken))
            }

            return tokens
        }

        private fun <E> java.util.ArrayList<E>.penultimate(): E {
            return this[this.size - 2]
        }

        private fun penaliseTightening(score: Int): Float = if (score < 0f)
            -(-score).toFloat().pow(1.05f)
        else
            score.toFloat().pow(1.05f)
    }
}

