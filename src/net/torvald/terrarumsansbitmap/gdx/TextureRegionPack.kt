/*
 * Terrarum Sans Bitmap
 * 
 * Copyright (c) 2017 Minjae Song (Torvald)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.torvald.terrarumsansbitmap.gdx

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable

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
        val vFrame: Int = 0,
        val xySwapped: Boolean = false // because Unicode chart does, duh
): Disposable {

    constructor(ref: String, tileW: Int, tileH: Int, hGap: Int = 0, vGap: Int = 0, hFrame: Int = 0, vFrame: Int = 0, xySwapped: Boolean = false) :
            this(Texture(ref), tileW, tileH, hGap, vGap, hFrame, vFrame, xySwapped)
    constructor(fileHandle: FileHandle, tileW: Int, tileH: Int, hGap: Int = 0, vGap: Int = 0, hFrame: Int = 0, vFrame: Int = 0, xySwapped: Boolean = false) :
            this(Texture(fileHandle), tileW, tileH, hGap, vGap, hFrame, vFrame, xySwapped)

    companion object {
        /** Intented for Y-down coord system, typically fon Non-GDX codebase */
        var globalFlipY = false
    }

    val regions: Array<TextureRegion>

    val horizontalCount = (texture.width - 2 * hFrame + hGap) / (tileW + hGap)
    val verticalCount = (texture.height - 2 * vFrame + vGap) / (tileH + vGap)

    init {
        //println("texture: $texture, dim: ${texture.width} x ${texture.height}, grid: $horizontalCount x $verticalCount, cellDim: $tileW x $tileH")

        if (!xySwapped) {
            regions = Array<TextureRegion>(horizontalCount * verticalCount) {
                val region = TextureRegion()
                val rx = (it % horizontalCount * (tileW + hGap)) + hFrame
                val ry = (it / horizontalCount * (tileH + vGap)) + vFrame

                region.setRegion(texture)
                region.setRegion(rx, ry, tileW, tileH)

                region.flip(false, globalFlipY)

                /*return*/region
            }
        }
        else {
            regions = Array<TextureRegion>(horizontalCount * verticalCount) {
                val region = TextureRegion()
                val rx = (it / verticalCount * (tileW + hGap)) + hFrame
                val ry = (it % verticalCount * (tileH + vGap)) + vFrame

                region.setRegion(texture)
                region.setRegion(rx, ry, tileW, tileH)

                region.flip(false, globalFlipY)

                /*return*/region
            }
        }
    }

    fun get(x: Int, y: Int) = regions[y * horizontalCount + x]

    override fun dispose() {
        texture.dispose()
    }

}