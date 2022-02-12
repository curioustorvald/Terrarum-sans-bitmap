package net.torvald.terrarumsansbitmap

/**
 * Created by minjaesong on 2021-11-25.
 */
data class DiacriticsAnchor(val type: Int, val x: Int, val y: Int, val xUsed: Boolean, val yUsed: Boolean)
/**
 * Created by minjaesong on 2018-08-07.
 */
data class GlyphProps(
        val width: Int,

        val isLowheight: Boolean = false,

        val nudgeX: Int = 0,
        val nudgeY: Int = 0,

        val diacriticsAnchors: Array<DiacriticsAnchor> = Array(6) { DiacriticsAnchor(it, 0, 0, false, false) },

        val alignWhere: Int = 0, // ALIGN_LEFT..ALIGN_BEFORE

        val writeOnTop: Int = -1, // -1: false, 0: Type-0, 1: Type-1, etc;

        val stackWhere: Int = 0, // STACK_UP..STACK_UP_N_DOWN

        val extInfo: IntArray = IntArray(15),

        val hasKernData: Boolean = false,
        val isKernYtype: Boolean = false,
        val kerningMask: Int = 255,

        val directiveOpcode: Int = 0, // 8-bits wide
        val directiveArg1: Int = 0, // 8-bits wide
        val directiveArg2: Int = 0, // 8-bits wide

        val rtl: Boolean = false,
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
        const val STACK_DONT = 4

        const val DIA_OVERLAY = 2
//        const val DIA_JOINER = 2

        private fun Boolean.toInt() = if (this) 1 else 0
    }

    /*constructor(width: Int, tags: Int) : this(
        width,
        tags.ushr(7).and(1) == 1,
        tags.ushr(5).and(3),
        tags.ushr(1).and(15),
        tags.and(1) == 1,
        tags.ushr(8).and(3),
        tags.and(1) == 1
    )

    constructor(width: Int, tags: Int, isLowheight: Boolean, isKernYtype: Boolean, kerningMask: Int) : this(
        width,
        tags.ushr(7).and(1) == 1,
        tags.ushr(5).and(3),
        tags.ushr(1).and(15),
        tags.and(1) == 1,
        tags.ushr(8).and(3),
        tags.and(1) == 1,
        null,

        true,
        isLowheight,
        isKernYtype,
        kerningMask
    )*/

//    fun isOverlay() = writeOnTop && alignXPos == 1

    override fun hashCode(): Int {
        val tags = rtl.toInt() or alignWhere.shl(5) or
                   writeOnTop.toInt().shl(7) or stackWhere.shl(8)

        var hash = -2128831034

        extInfo.forEach {
            hash = hash xor it
            hash = hash * 16777619
        }

        diacriticsAnchors.forEach {
            hash = hash xor it.type
            hash = hash * 16777619
            hash = hash xor (it.x or (if (it.xUsed) 128 else 0))
            hash = hash * 16777619
            hash = hash xor (it.y or (if (it.yUsed) 128 else 0))
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

    fun requiredExtInfoCount() =
        if (stackWhere == STACK_BEFORE_N_AFTER)
            2
        else if (directiveOpcode in 0b10000_000..0b10000_111)
            7
        else 0

    fun isPragma(pragma: String) = when (pragma) {
        "replacewith" -> directiveOpcode in 0b10000_000..0b10000_111
        else -> false
    }

    fun forEachExtInfo(action: (Int) -> Unit) = extInfo.slice(0 until requiredExtInfoCount()).forEach(action)
    fun forEachExtInfoIndexed(action: (Int, Int) -> Unit) = extInfo.slice(0 until requiredExtInfoCount()).forEachIndexed(action)

    val isIllegal: Boolean
        get() = directiveOpcode == 255
}