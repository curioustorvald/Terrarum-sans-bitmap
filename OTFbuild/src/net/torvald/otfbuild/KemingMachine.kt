package net.torvald.otfbuild

import com.kreative.bitsnpicas.GlyphPair

/**
 * Generates kerning pairs from shape rules.
 * Ported from TerrarumSansBitmap.kt "The Keming Machine" section.
 */
class KemingMachine {

    private class Ing(val s: String) {
        private var careBits = 0
        private var ruleBits = 0

        init {
            s.forEachIndexed { index, char ->
                when (char) {
                    '@' -> {
                        careBits = careBits or SheetConfig.kemingBitMask[index]
                        ruleBits = ruleBits or SheetConfig.kemingBitMask[index]
                    }
                    '`' -> {
                        careBits = careBits or SheetConfig.kemingBitMask[index]
                    }
                }
            }
        }

        fun matches(shapeBits: Int) = ((shapeBits and careBits) == ruleBits)

        override fun toString() = "C:${careBits.toString(2).padStart(16, '0')}-R:${ruleBits.toString(2).padStart(16, '0')}"
    }

    private data class Kem(val first: Ing, val second: Ing, val bb: Int = 2, val yy: Int = 1)

    private val kerningRules: List<Kem>

    init {
        val baseRules = listOf(
            Kem(Ing("_`_@___`__"), Ing("`_`___@___")),
            Kem(Ing("_@_`___`__"), Ing("`_________")),
            Kem(Ing("_@_@___`__"), Ing("`___@_@___"), 1, 1),
            Kem(Ing("_@_@_`_`__"), Ing("`_____@___")),
            Kem(Ing("___`_`____"), Ing("`___@_`___")),
            Kem(Ing("___`_`____"), Ing("`_@___`___")),
        )

        // Automatically create mirrored versions
        val mirrored = baseRules.map { rule ->
            val left = rule.first.s
            val right = rule.second.s
            val newLeft = StringBuilder()
            val newRight = StringBuilder()

            for (c in left.indices step 2) {
                newLeft.append(right[c + 1]).append(right[c])
                newRight.append(left[c + 1]).append(left[c])
            }

            Kem(Ing(newLeft.toString()), Ing(newRight.toString()), rule.bb, rule.yy)
        }

        kerningRules = baseRules + mirrored
    }

    /**
     * Generate kerning pairs from all glyphs that have kerning data.
     * @return Map of GlyphPair to kern offset (negative values = tighter)
     */
    fun generateKerningPairs(glyphs: Map<Int, ExtractedGlyph>): Map<GlyphPair, Int> {
        val result = HashMap<GlyphPair, Int>()

        // Collect all codepoints with kerning data
        val kernableGlyphs = glyphs.filter { it.value.props.hasKernData }

        if (kernableGlyphs.isEmpty()) {
            println("  [KemingMachine] No glyphs with kern data found")
            return result
        }

        println("  [KemingMachine] ${kernableGlyphs.size} glyphs with kern data")

        // Special rule: lowercase r + dot
        for (r in SheetConfig.lowercaseRs) {
            for (d in SheetConfig.dots) {
                if (glyphs.containsKey(r) && glyphs.containsKey(d)) {
                    result[GlyphPair(r, d)] = -1
                }
            }
        }

        // Apply kerning rules to all pairs
        val kernCodes = kernableGlyphs.keys.toIntArray()
        var pairsFound = 0

        for (leftCode in kernCodes) {
            val leftProps = kernableGlyphs[leftCode]!!.props
            val maskL = leftProps.kerningMask

            for (rightCode in kernCodes) {
                val rightProps = kernableGlyphs[rightCode]!!.props
                val maskR = rightProps.kerningMask

                for (rule in kerningRules) {
                    if (rule.first.matches(maskL) && rule.second.matches(maskR)) {
                        val contraction = if (leftProps.isKernYtype || rightProps.isKernYtype) rule.yy else rule.bb
                        if (contraction > 0) {
                            result[GlyphPair(leftCode, rightCode)] = -contraction
                            pairsFound++
                        }
                        break // first matching rule wins
                    }
                }
            }
        }

        println("  [KemingMachine] Generated $pairsFound kerning pairs (+ ${SheetConfig.lowercaseRs.size * SheetConfig.dots.size} r-dot pairs)")
        return result
    }
}
