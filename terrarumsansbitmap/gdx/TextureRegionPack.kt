package net.torvald.terrarumsansbitmap.gdx

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion

/**
 * Created by minjaesong on 2017-06-15.
 */
class TextureRegionPack(
        val texture: Texture,
        val tileW: Int,
        val tileH: Int,
        val hGap: Int = 0,
        val vGap: Int = 0,
        val hFrame: Int = 0,
        val vFrame: Int = 0
) {

    constructor(ref: String, tileW: Int, tileH: Int, hGap: Int = 0, vGap: Int = 0, hFrame: Int = 0, vFrame: Int = 0) :
            this(Texture(ref), tileW, tileH, hGap, vGap, hFrame, vFrame)
    constructor(fileHandle: FileHandle, tileW: Int, tileH: Int, hGap: Int = 0, vGap: Int = 0, hFrame: Int = 0, vFrame: Int = 0) :
            this(Texture(fileHandle), tileW, tileH, hGap, vGap, hFrame, vFrame)

    companion object {
        /** Intented for Y-down coord system, typically fon Non-GDX codebase */
        var globalFlipY = false
    }

    val regions: Array<TextureRegion>

    val horizontalCount = (texture.width - 2 * hFrame + hGap) / (tileW + hGap)
    val verticalCount = (texture.height - 2 * vFrame + vGap) / (tileH + vGap)

    init {
        //println("texture: $texture, dim: ${texture.width} x ${texture.height}, grid: $horizontalCount x $verticalCount, cellDim: $tileW x $tileH")

        regions = Array<TextureRegion>(horizontalCount * verticalCount, {
            val region = TextureRegion()
            val rx = (it % horizontalCount * (tileW + hGap)) + hFrame
            val ry = (it / horizontalCount * (tileH + vGap)) + vFrame

            region.setRegion(texture)
            region.setRegion(rx, ry, tileW, tileH)

            region.flip(false, globalFlipY)

            /*return*/region
        })
    }

    fun get(x: Int, y: Int) = regions[y * horizontalCount + x]

    fun dispose() {
        texture.dispose()
    }

}