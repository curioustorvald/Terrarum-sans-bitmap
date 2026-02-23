package net.torvald.otfbuild

import java.io.File
import java.io.InputStream

/**
 * Simple TGA reader for uncompressed true-colour images (Type 2).
 * Returns RGBA8888 pixel data.
 */
class TgaImage(val width: Int, val height: Int, val pixels: IntArray) {
    /** Get pixel at (x, y) as RGBA8888. */
    fun getPixel(x: Int, y: Int): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0
        return pixels[y * width + x]
    }
}

object TgaReader {

    fun read(file: File): TgaImage = read(file.inputStream())

    fun read(input: InputStream): TgaImage {
        val data = input.use { it.readBytes() }
        var pos = 0

        fun u8() = data[pos++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)

        val idLength = u8()
        val colourMapType = u8()
        val imageType = u8()

        // colour map spec (5 bytes)
        u16(); u16(); u8()

        // image spec
        val xOrigin = u16()
        val yOrigin = u16()
        val width = u16()
        val height = u16()
        val bitsPerPixel = u8()
        val descriptor = u8()

        val topToBottom = (descriptor and 0x20) != 0
        val bytesPerPixel = bitsPerPixel / 8

        // skip ID
        pos += idLength

        // skip colour map
        if (colourMapType != 0) {
            throw UnsupportedOperationException("Colour-mapped TGA not supported")
        }

        if (imageType != 2) {
            throw UnsupportedOperationException("Only uncompressed true-colour TGA is supported (type 2), got type $imageType")
        }

        if (bytesPerPixel !in 3..4) {
            throw UnsupportedOperationException("Only 24-bit or 32-bit TGA supported, got ${bitsPerPixel}-bit")
        }

        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            val y = if (topToBottom) row else (height - 1 - row)
            for (x in 0 until width) {
                val b = data[pos++].toInt() and 0xFF
                val g = data[pos++].toInt() and 0xFF
                val r = data[pos++].toInt() and 0xFF
                val a = if (bytesPerPixel == 4) data[pos++].toInt() and 0xFF else 0xFF

                // Store as RGBA8888
                pixels[y * width + x] = (r shl 24) or (g shl 16) or (b shl 8) or a
            }
        }

        return TgaImage(width, height, pixels)
    }
}
