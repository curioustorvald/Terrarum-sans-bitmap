import com.badlogic.gdx.*
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.ScreenUtils
import net.torvald.terrarumsansbitmap.gdx.GameFontBase

/**
 * Created by minjaesong on 2018-07-26.
 */
class FontTestGDX : Game() {

    lateinit var font: GameFontBase

    lateinit var inputText: List<String>

    lateinit var batch: SpriteBatch

    lateinit var frameBuffer: FrameBuffer

    lateinit var camera: OrthographicCamera


    private val demotextName = "testtext.txt"
    private val outimageName = "testing.png"

    override fun create() {
        font = GameFontBase("./assets", flipY = false, errorOnUnknownChar = false) // must test for two flipY cases

        val inTextFile = Gdx.files.internal("./$demotextName")
        val reader = inTextFile.reader("UTF-8")
        inputText = reader.readLines()
        reader.close()

        batch = SpriteBatch()




        println(font.charsetOverrideDefault)
        println(font.charsetOverrideBulgarian)
        println(font.charsetOverrideSerbian)

        frameBuffer = FrameBuffer(Pixmap.Format.RGBA8888, TEXW, TEXH, true)

        camera = OrthographicCamera(TEXW.toFloat(), TEXH.toFloat())
        camera.translate(TEXW.div(2f), 0f)
        camera.setToOrtho(true, TEXW.toFloat(), TEXH.toFloat())
        camera.update()


        Gdx.input.inputProcessor = Navigator(this)
    }

    override fun getScreen(): Screen? {
        return null
    }

    var scrollOffsetY = 0f

    override fun setScreen(screen: Screen?) {
    }

    var tex: Texture? = null
    var screenshotExported = false

    override fun render() {

        if (tex == null) {
            frameBuffer.begin()

            Gdx.gl.glClearColor(.141f, .141f, .141f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
            Gdx.gl.glEnable(GL20.GL_BLEND)
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

            batch.projectionMatrix = camera.combined
            batch.begin()

            batch.color = Color(0xeeeeeeff.toInt())
            inputText.forEachIndexed { index, s ->
                font.draw(batch, s, 10f, TEXH - 30f - index * font.lineHeight)
            }

            batch.end()


            // dump to file
            if (!screenshotExported) {
                val pixmap = ScreenUtils.getFrameBufferPixmap(0, 0, frameBuffer.width, frameBuffer.height)

                PixmapIO.writePNG(Gdx.files.local(outimageName), pixmap)
                pixmap.dispose()

                screenshotExported = true
            }


            frameBuffer.end()

            ///////////////

            tex = frameBuffer.colorBufferTexture
        }

        batch.begin()
        batch.color = Color.WHITE
        batch.draw(tex, 0f, (TEXH.toFloat()/appConfig.height)*TEXH - scrollOffsetY, TEXW.toFloat(), -(TEXH.toFloat() / appConfig.height) * TEXH.toFloat())


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
    }

    fun scrollAdd(x: Int = 1) {
        scrollOffsetY -= (TEXH.toFloat() / appConfig.height) * 20f * x
    }

    fun scrollSub(x: Int = 1) {
        scrollOffsetY += (TEXH.toFloat() / appConfig.height) * 20f * x
    }

    class Navigator(val main: FontTestGDX) : InputAdapter() {
        override fun scrolled(amount: Int): Boolean {
            if (amount >= 0)
                main.scrollSub(amount)
            else
                main.scrollAdd(-amount)

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
}

lateinit var appConfig: LwjglApplicationConfiguration
const val TEXW = 874
const val TEXH = 2400

fun main(args: Array<String>) {
    appConfig = LwjglApplicationConfiguration()
    appConfig.vSyncEnabled = false
    appConfig.resizable = false//true;
    appConfig.width = TEXW
    appConfig.height = 768
    appConfig.title = "Terrarum Sans Bitmap Test (GDX)"

    LwjglApplication(FontTestGDX(), appConfig)
}
