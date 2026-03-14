package net.torvald.terrarumsansbitmap.gdx

import com.badlogic.gdx.graphics.Pixmap

data class AtlasRegion(
    val atlasX: Int,
    val atlasY: Int,
    val width: Int,
    val height: Int,
    val offsetX: Int = 0,
    val offsetY: Int = 0
)

class GlyphAtlas(val atlasWidth: Int, val atlasHeight: Int) {

    val pixmap = Pixmap(atlasWidth, atlasHeight, Pixmap.Format.RGBA8888).also { it.blending = Pixmap.Blending.None }

    private val regions = HashMap<Long, AtlasRegion>()

    private var cursorX = 0
    private var cursorY = 0
    private var shelfHeight = 0

    private fun atlasKey(sheetID: Int, cellX: Int, cellY: Int): Long =
        sheetID.toLong().shl(32) or cellX.toLong().shl(16) or cellY.toLong()

    fun packCell(sheetID: Int, cellX: Int, cellY: Int, cellPixmap: Pixmap) {
        val w = cellPixmap.width
        val h = cellPixmap.height

        if (cursorX + w > atlasWidth) {
            cursorX = 0
            cursorY += shelfHeight
            shelfHeight = 0
        }

        pixmap.drawPixmap(cellPixmap, cursorX, cursorY)

        regions[atlasKey(sheetID, cellX, cellY)] = AtlasRegion(cursorX, cursorY, w, h)

        cursorX += w
        if (h > shelfHeight) shelfHeight = h
    }

    fun blitSheet(sheetID: Int, sheetPixmap: Pixmap, cellW: Int, cellH: Int, cols: Int, rows: Int) {
        if (cursorX > 0) {
            cursorX = 0
            cursorY += shelfHeight
            shelfHeight = 0
        }

        val baseY = cursorY

        pixmap.drawPixmap(sheetPixmap, 0, baseY)

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                regions[atlasKey(sheetID, cx, cy)] = AtlasRegion(cx * cellW, baseY + cy * cellH, cellW, cellH)
            }
        }

        cursorY = baseY + sheetPixmap.height
        cursorX = 0
        shelfHeight = 0
    }

    fun getRegion(sheetID: Int, cellX: Int, cellY: Int): AtlasRegion? =
        regions[atlasKey(sheetID, cellX, cellY)]

    fun clearRegion(region: AtlasRegion) {
        for (y in 0 until region.height) {
            for (x in 0 until region.width) {
                pixmap.drawPixel(region.atlasX + x, region.atlasY + y, 0)
            }
        }
    }

    fun dispose() {
        pixmap.dispose()
    }
}
