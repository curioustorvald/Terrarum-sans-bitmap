package net.torvald.terrarumsansbitmap

/**
 * Created by minjaesong on 2018-08-07.
 */
data class GlyphProps(
        val width: Int,
        val writeOnTop: Boolean,
        val alignWhere: Int,
        val alignXPos: Int,
        val rtl: Boolean = false,
        val diacriticsStackDown: Boolean = false,
        val diacriticsBeforeGlyph: Boolean = false
) {
    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val CENTRE = 2
    }

    constructor(width: Int, tags: Int) : this(
            width,
            tags.ushr(7).and(1) == 1,
            tags.ushr(5).and(3),
            tags.ushr(1).and(15),
            tags.and(1) == 1,
            tags.ushr(8).and(1) == 1,
            tags.ushr(9).and(1) == 1
    )
}