package net.torvald.terrarumsansbitmap.gdx

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap

/**
 * Created by minjaesong on 2018-09-17.
 */
class PixmapRegionPack(
        pixmap: Pixmap,
        val tileW: Int,
        val tileH: Int,
        val hGap: Int = 0,
        val vGap: Int = 0,
        val hFrame: Int = 0,
        val vFrame: Int = 0,
        val xySwapped: Boolean = false // because Unicode chart does, duh
) {

    //constructor(ref: String, tileW: Int, tileH: Int, hGap: Int = 0, vGap: Int = 0, hFrame: Int = 0, vFrame: Int = 0, xySwapped: Boolean = false) :
    //        this(Pixmap(ref), tileW, tileH, hGap, vGap, hFrame, vFrame, xySwapped)
    constructor(fileHandle: FileHandle, tileW: Int, tileH: Int, hGap: Int = 0, vGap: Int = 0, hFrame: Int = 0, vFrame: Int = 0, xySwapped: Boolean = false) :
            this(Pixmap(fileHandle), tileW, tileH, hGap, vGap, hFrame, vFrame, xySwapped)

    val horizontalCount = (pixmap.width - 2 * hFrame + hGap) / (tileW + hGap)
    val verticalCount = (pixmap.height - 2 * vFrame + vGap) / (tileH + vGap)

    val regions: Array<Pixmap>

    init {
        if (!xySwapped) {
            regions = Array<Pixmap>(horizontalCount * verticalCount, {
                val region = Pixmap(tileW, tileH, Pixmap.Format.RGBA8888)
                val rx = (it % horizontalCount * (tileW + hGap)) + hFrame
                val ry = (it / horizontalCount * (tileH + vGap)) + vFrame

                region.drawPixmap(pixmap, 0, 0,
                        rx * (tileW + hGap),
                        ry * (tileH + vGap),
                        tileW, tileH
                )

                // todo globalFlipY ?

                /*return*/region
            })
        }
        else {
            regions = Array<Pixmap>(horizontalCount * verticalCount, {
                val region = Pixmap(tileW, tileH, Pixmap.Format.RGBA8888)
                val rx = (it / verticalCount * (tileW + hGap)) + hFrame
                val ry = (it % verticalCount * (tileH + vGap)) + vFrame

                region.drawPixmap(pixmap, 0, 0,
                        rx * (tileW + hGap),
                        ry * (tileH + vGap),
                        tileW, tileH
                )

                // todo globalFlipY ?

                /*return*/region
            })
        }
    }

    fun get(x: Int, y: Int) = regions[y * horizontalCount + x]

    fun dispose() {
        regions.forEach { it.dispose() }
    }

}