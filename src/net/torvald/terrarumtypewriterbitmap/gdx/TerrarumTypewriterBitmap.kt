package net.torvald.terrarumtypewriterbitmap.gdx

import com.badlogic.gdx.graphics.g2d.BitmapFont
import java.io.File
import java.io.Reader

/**
 * Config File Syntax:
 *
 * ```
 * identifier,image file name,relative codepoint
 * # working example:
 * intl_qwerty_typewriter,typewriter_intl_qwerty.tga,0
 * ko_kr_3set-390_typewriter,typewriter_ko_3set-390.tga,16
 * ```
 *
 * // the Relative Codepoint of 16 should point to U+F3000
 *
 * Created by minjaesong on 2021-11-04.
 */
class TerrarumTypewriterBitmap(
        fontDir: String,
        configFile: Reader,
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