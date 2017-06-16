package net.torvald.terrarumsansbitmap.gdx

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

    val regions: Array<TextureRegion>

    private val horizontalCount = (texture.width - 2 * hFrame + hGap) / (tileW + hGap)
    private val verticalCount = (texture.height - 2 * vFrame + vGap) / (tileH + vGap)

    init {
        //println("texture: $texture, dim: ${texture.width} x ${texture.height}, grid: $horizontalCount x $verticalCount, cellDim: $tileW x $tileH")

        regions = Array<TextureRegion>(horizontalCount * verticalCount, {
            val region = TextureRegion()
            val rx = (it % horizontalCount * (tileW + hGap)) + hFrame
            val ry = (it / horizontalCount * (tileH + vGap)) + vFrame

            region.setRegion(texture)
            region.setRegion(rx, ry, tileW, tileH)

            /*return*/region
        })
    }

    fun get(x: Int, y: Int) = regions[y * horizontalCount + x]

    fun dispose() {
        texture.dispose()
    }

}