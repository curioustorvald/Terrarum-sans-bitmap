package net.torvald.terrarumtypewriterbitmap.gdx

import com.badlogic.gdx.graphics.g2d.BitmapFont

/**
 * Created by minjaesong on 2021-11-04.
 */
class TerrarumTypewriterBitmap(
        fontDir: String,
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


}