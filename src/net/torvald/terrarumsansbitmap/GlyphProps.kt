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
        val stackWhere: Int = 0,
        var extInfo: IntArray? = null
) {
    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_RIGHT = 1
        const val ALIGN_CENTRE = 2
        const val ALIGN_BEFORE = 3

        const val STACK_UP = 0
        const val STACK_DOWN = 1
        const val STACK_BEFORE_N_AFTER = 2
        const val STACK_UP_N_DOWN = 3

        const val DIA_OVERLAY = 1
        const val DIA_JOINER = 2

        private fun Boolean.toInt() = if (this) 1 else 0
    }

    constructor(width: Int, tags: Int) : this(
            width,
            tags.ushr(7).and(1) == 1,
            tags.ushr(5).and(3),
            tags.ushr(1).and(15),
            tags.and(1) == 1,
            tags.ushr(8).and(3)
    )

    fun isOverlay() = writeOnTop && alignXPos == 1

    override fun hashCode(): Int {
        val tags = rtl.toInt() or alignXPos.shl(1) or alignWhere.shl(5) or
                   writeOnTop.toInt().shl(7) or stackWhere.shl(8)

        var hash = -2128831034

        extInfo?.forEach {
            hash = hash xor it
            hash = hash * 16777619
        }

        hash = hash xor tags
        hash = hash * 167677619

        return hash
    }

    override fun equals(other: Any?): Boolean {
        // comparing hash because I'm lazy
        return other is GlyphProps && this.hashCode() == other.hashCode()
    }

    fun requiredExtInfoCount() = if (stackWhere == STACK_BEFORE_N_AFTER) 2 else 0
}