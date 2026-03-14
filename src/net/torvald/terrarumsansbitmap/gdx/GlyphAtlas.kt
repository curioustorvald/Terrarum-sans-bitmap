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

    val pixmap = Pixmap(atlasWidth, atlasHeight, Pixmap.Format.RGBA8888).also { it.blending = Pixmap.Blending.SourceOver }

    private val regions = HashMap<Long, AtlasRegion>()

    private var cursorX = 0
    private var cursorY = 0
    private var shelfHeight = 0

    private val pendingCells = ArrayList<PendingCell>()

    private class PendingCell(
        val sheetID: Int,
        val cellX: Int,
        val cellY: Int,
        val cropped: Pixmap,
        val offsetX: Int,
        val offsetY: Int
    )

    private fun atlasKey(sheetID: Int, cellX: Int, cellY: Int): Long =
        sheetID.toLong().shl(32) or cellX.toLong().shl(16) or cellY.toLong()

    /** Scans the cell for its non-transparent bounding box, crops, and queues for deferred packing. */
    fun queueCell(sheetID: Int, cellX: Int, cellY: Int, cellPixmap: Pixmap) {
        var minX = cellPixmap.width
        var minY = cellPixmap.height
        var maxX = -1
        var maxY = -1

        for (y in 0 until cellPixmap.height) {
            for (x in 0 until cellPixmap.width) {
                if (cellPixmap.getPixel(x, y) and 0xFF != 0) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (maxX < 0) return // entirely transparent, skip

        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1
        val cropped = Pixmap(cropW, cropH, Pixmap.Format.RGBA8888)
        cropped.drawPixmap(cellPixmap, 0, 0, minX, minY, cropW, cropH)

        pendingCells.add(PendingCell(sheetID, cellX, cellY, cropped, minX, minY))
    }

    /** Sorts queued cells by height desc then width desc, and packs into shelves. */
    fun packAllQueued() {
        pendingCells.sortWith(
            compareByDescending<PendingCell> { it.cropped.height }
                .thenByDescending { it.cropped.width }
        )

        for (cell in pendingCells) {
            val w = cell.cropped.width
            val h = cell.cropped.height

            // start new shelf if cell doesn't fit horizontally
            if (cursorX + w > atlasWidth) {
                cursorX = 0
                cursorY += shelfHeight
                shelfHeight = 0
            }

            pixmap.drawPixmap(cell.cropped, cursorX, cursorY)

            regions[atlasKey(cell.sheetID, cell.cellX, cell.cellY)] =
                AtlasRegion(cursorX, cursorY, w, h, cell.offsetX, cell.offsetY)

            cursorX += w
            if (h > shelfHeight) shelfHeight = h

            cell.cropped.dispose()
        }

        pendingCells.clear()
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
