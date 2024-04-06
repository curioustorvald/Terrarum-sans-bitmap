import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.StreamUtils
import net.torvald.terrarumsansbitmap.MovableType
import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Created by minjaesong on 2018-07-26.
 */
class FontTestGDX : Game() {

    lateinit var font: TerrarumSansBitmap

    lateinit var inputText: String

    lateinit var batch: FlippingSpriteBatch

    lateinit var frameBuffer: FrameBuffer

    lateinit var camera: OrthographicCamera

    private val testing = true

    private val demotextName = if (testing) "testtext.txt" else "demotext_unaligned.txt"
    private val outimageName = if (testing) "testing.PNG" else "demo.PNG"

    private lateinit var faketex: Texture

    private val lineHeight = 24


    lateinit var layout: MovableType

    private lateinit var testtex: TextureRegion

    override fun create() {
        font = TerrarumSansBitmap("./assets", debug = true, flipY = false, errorOnUnknownChar = false, shadowAlpha = 0.5f) // must test for two flipY cases

        testtex = TextureRegion(Texture("./testtex.tga"))

        val inTextFile = Gdx.files.internal("./$demotextName")
        val reader = inTextFile.reader("UTF-8")
        inputText = reader.readLines().joinToString("\n")
        reader.close()

        batch = FlippingSpriteBatch()


        // create faketex
        val fakepix = Pixmap(1,1,Pixmap.Format.RGBA8888)
        fakepix.drawPixel(0,0,-1)
        faketex = Texture(fakepix)
        fakepix.dispose()

        frameBuffer = FrameBuffer(Pixmap.Format.RGBA8888, TEXW, TEXH, true)

        camera = OrthographicCamera(TEXW.toFloat(), TEXH.toFloat())
        camera.translate(0f, 0f)
        camera.setToOrtho(false, TEXW.toFloat(), TEXH.toFloat())
        camera.update()


        Gdx.input.inputProcessor = Navigator(this)


        layout = font.typesetParagraph(batch, inputText, TEXW - 48)
    }

    override fun getScreen(): Screen? {
        return null
    }

    var scrollOffsetY = 0f

    override fun setScreen(screen: Screen?) {
    }

    var tex: Texture? = null
    var screenshotExported = false

    private val backcol = Color(.141f, .141f, .141f, 1f)

    override fun render() {

        if (tex == null) {
            frameBuffer.begin()

            Gdx.gl.glClearColor(.141f, .141f, .141f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
            Gdx.gl.glEnable(GL20.GL_BLEND)
            batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA) // for not premultiplied textures

            batch.projectionMatrix = camera.combined
            batch.begin()

            batch.color = backcol
            batch.draw(faketex, 0f, 0f, TEXW.toFloat(), TEXH.toFloat())
            batch.flush()

            batch.color = Color.WHITE
//            inputText.forEachIndexed { index, s ->
//                font.draw(batch, s, 10f, TEXH - 30f - index * lineHeight)
//            }

            // draw position debuggers
//            font.draw(batch, "soft\uFE0F\u00ADhyphen\uFE0F\u00ADated", 24f, 12f)
//            batch.draw(testtex, 24f, 12f)
//            val layoutDrawCall = { x: Float, y: Float, _: Int -> batch.draw(testtex, x, y) }
//            layout.draw(batch, 24f, 12f, mapOf(0 to layoutDrawCall))
            // end of draw position debuggers
            layout.draw(batch, 24f, 12f)

            batch.end()


            // dump to file
            if (!screenshotExported) {
                val pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, frameBuffer.width, frameBuffer.height)

                PixmapIO.writePNG(Gdx.files.local(outimageName), pixmap)
//                writeTGA(Gdx.files.local(outimageName), pixmap, false)
                pixmap.dispose()

                screenshotExported = true
            }


            frameBuffer.end()

            ///////////////

            tex = frameBuffer.colorBufferTexture
        }


        Gdx.gl.glClearColor(.141f, .141f, .141f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA) // for not premultiplied textures

        camera.setToOrtho(true, WIDTH.toFloat(), HEIGHT.toFloat())

        batch.projectionMatrix = camera.combined
        batch.begin()
        batch.color = Color.WHITE
        batch.draw(tex!!, 0f, scrollOffsetY)
        batch.end()
    }

    override fun pause() {
    }

    override fun resume() {
    }

    override fun resize(width: Int, height: Int) {
    }

    override fun dispose() {
        font.dispose()
        faketex.dispose()
        testtex.texture.dispose()
    }

    fun scrollAdd(x: Int = 1) {
        scrollOffsetY += lineHeight * x
    }

    fun scrollSub(x: Int = 1) {
        scrollOffsetY -= lineHeight * x
    }

    class Navigator(val main: FontTestGDX) : InputAdapter() {
        override fun scrolled(amountX: Float, amountY: Float): Boolean {
            if (amountY >= 0)
                main.scrollSub(amountY.toInt())
            else
                main.scrollAdd(-amountY.toInt())

            return true
        }

        override fun keyDown(keycode: Int): Boolean {
            if (keycode == Input.Keys.UP)
                main.scrollAdd()
            else if (keycode == Input.Keys.DOWN)
                main.scrollSub()

            return true
        }
    }


    @Throws(IOException::class)
    private fun writeTGA(file: FileHandle, pixmap: Pixmap, flipY: Boolean) {
        val output = file.write(false)
        try {
            _writeTGA(output, pixmap, true, flipY)
        } finally {
            StreamUtils.closeQuietly(output)
        }
    }

    @Throws(IOException::class)
    private fun _writeTGA(out: OutputStream, pixmap: Pixmap, verbatim: Boolean, flipY: Boolean) {
        val width: ByteArray =  toShortLittle(pixmap.width)
        val height: ByteArray = toShortLittle(pixmap.height)
        val zero: ByteArray =   toShortLittle(0)
        out.write(0) // ID field: empty
        out.write(0) // no colour map, but should be ignored anyway as it being unmapped RGB
        out.write(2) // 2 means unmapped RGB
        out.write(byteArrayOf(0, 0, 0, 0, 0)) // color map spec: empty
        out.write(zero) // x origin: 0
        out.write(zero) // y origin: 0
        out.write(width) // width
        out.write(height) // height
        out.write(32) // image pixel size: we're writing 32-bit image (8bpp BGRA)
        out.write(8) // image descriptor: dunno, Photoshop writes 8 in there

        // write actual image data
        // since we're following Photoshop's conventional header, we also follows Photoshop's
        // TGA saving scheme, that is:
        //     1. BGRA order
        //     2. Y-Flipped but not X-Flipped
        if (!flipY) {
            for (y in pixmap.height - 1 downTo 0) {
                for (x in 0 until pixmap.width) {
                    writeTga(x, y, verbatim, pixmap, out)
                }
            }
        } else {
            for (y in 0 until pixmap.height) {
                for (x in 0 until pixmap.width) {
                    writeTga(x, y, verbatim, pixmap, out)
                }
            }
        }


        // write footer
        // 00 00 00 00 00 00 00 00 TRUEVISION-XFILE 2E 00
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        if (verbatim) out.write("TRUEVISION-XFILE".toByteArray()) else out.write("TerrarumHappyTGA".toByteArray())
        out.write(byteArrayOf(0x2E, 0))
        out.flush()
        out.close()
    }

    private val zeroalpha = byteArrayOf(0, 0, 0, 0)
    @Throws(IOException::class)
    private fun writeTga(x: Int, y: Int, verbatim: Boolean, pixmap: Pixmap, out: OutputStream) {
        val color = pixmap.getPixel(x, y)

        // if alpha == 0, write special value instead
        if (verbatim && color and 0xFF == 0) {
            out.write(zeroalpha)
        } else {
            out.write(RGBAtoBGRA(color))
        }
    }

    private fun toShortLittle(i: Int): ByteArray {
        return byteArrayOf(
            (i and 0xFF).toByte(),
            (i ushr 8 and 0xFF).toByte()
        )
    }

    private fun RGBAtoBGRA(rgba: Int): ByteArray {
        return byteArrayOf(
            (rgba ushr 8 and 0xFF).toByte(),
            (rgba ushr 16 and 0xFF).toByte(),
            (rgba ushr 24 and 0xFF).toByte(),
            (rgba and 0xFF).toByte()
        )
    }
}

class FlippingSpriteBatch(size: Int = 1000) : SpriteBatch(size) {

    /**
     * This function draws the flipped version of the image by giving flipped uv-coord to the SpriteBatch
     */
    override fun draw(texture: Texture, x: Float, y: Float, width: Float, height: Float) =
        draw(texture, x, y, width, height, 0f, 0f, 1f, 1f)

    override fun draw(texture: Texture, x: Float, y: Float) =
        draw(texture, x, y, texture.width.toFloat(), texture.height.toFloat(), 0f, 0f, 1f, 1f)

    fun drawFlipped(texture: Texture, x: Float, y: Float, width: Float, height: Float) =
        draw(texture, x, y, width, height, 0f, 1f, 1f, 0f)
    fun drawFlipped(texture: Texture, x: Float, y: Float) =
        draw(texture, x, y, texture.width.toFloat(), texture.height.toFloat(), 0f, 1f, 1f, 0f)


    /**
     * This function does obey the flipping set to the TextureRegion and try to draw flipped version of it,
     * without touching the flipping setting of the given region.
     */
    override fun draw(region: TextureRegion, x: Float, y: Float, width: Float, height: Float) =
        draw(region.texture, x, y, width, height, region.u, region.v, region.u2, region.v2)

    override fun draw(region: TextureRegion, x: Float, y: Float) =
        draw(region.texture, x, y, region.regionWidth.toFloat(), region.regionHeight.toFloat(), region.u, region.v, region.u2, region.v2)

    fun drawFlipped(region: TextureRegion, x: Float, y: Float, width: Float, height: Float) =
        draw(region.texture, x, y, width, height, region.u, region.v2, region.u2, region.v)
    fun drawFlipped(region: TextureRegion, x: Float, y: Float) =
        draw(region.texture, x, y, region.regionWidth.toFloat(), region.regionHeight.toFloat(), region.u, region.v2, region.u2, region.v)



    /**
     * NOTE TO SELF:
     *
     * It seems that original SpriteBatch Y-flips when it's drawing a texture, but NOT when it's drawing a textureregion
     *
     * (textureregion's default uv-coord is (0,0,1,1)
     */
}

lateinit var appConfig: Lwjgl3ApplicationConfiguration
const val TEXW = 800
const val TEXH = 24 * 170

const val WIDTH = TEXW
const val HEIGHT = 768

fun main(args: Array<String>) {
    appConfig = Lwjgl3ApplicationConfiguration()
    appConfig.useVsync(false)
    appConfig.setResizable(false)
    appConfig.setWindowedMode(WIDTH, HEIGHT)
    appConfig.setTitle("Terrarum Sans Bitmap Test")
    
    Lwjgl3Application(FontTestGDX(), appConfig)
}
