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

    override fun create() {
        font = GameFontBase("./assets", flipY = false, errorOnUnknownChar = true) // must test for two flipY cases

        val inTextFile = Gdx.files.internal("./demotext.txt")
        val reader = inTextFile.reader("UTF-8")
        inputText = reader.readLines()
        reader.close()

        batch = SpriteBatch()




        println("START")

        val l = intArrayOf(0xFF00,
                0xFF10,
                0xFF20,
                0xFF30,
                0xFF40,
                0xFF50,
                0xFF60,
                0xFF70,
                0xFF80,
                0xFF90,
                0xFFA0,
                0xFFB0,
                0xFFC0,
                0xFFD0,
                0xFFE0,
                0xFFF0,
                0xFEF0,
                0xFDF0,
                0xFCF0,
                0xFBF0,
                0xFAF0,
                0xF9F0,
                0xF8F0,
                0xF7F0,
                0xF6F0,
                0xF5F0,
                0xF4F0,
                0xF3F0,
                0xF2F0,
                0xF1F0,
                0xF0F0,
                0xF0F1,
                0xF0F2,
                0xF0F3,
                0xF0F4,
                0xF0F5,
                0xF0F6,
                0xF0F7,
                0xF0F8,
                0xF0F9,
                0xF0FA,
                0xF0FB,
                0xF0FC,
                0xF0FD,
                0xF0FE,
                0xF0FF,
                0xF0EF,
                0xF0DF,
                0xF0CF,
                0xF0BF,
                0xF0AF,
                0xF09F,
                0xF08F,
                0xF07F,
                0xF06F,
                0xF05F,
                0xF04F,
                0xF03F,
                0xF02F,
                0xF01F,
                0xF00F,
                0xF10F,
                0xF20F,
                0xF30F,
                0xF40F,
                0xF50F,
                0xF60F,
                0xF70F,
                0xF80F,
                0xF90F,
                0xFA0F,
                0xFB0F,
                0xFC0F,
                0xFD0F,
                0xFE0F,
                0xFF0F)
        val s = "ᚱᛂᚴᛋᛂᛋᛏᛋᚮᚾᛔᚢᛏᛚᚮᛋ᛬ᚱᛂᚴᛋᛋᚢᚼᚾᚢᛘᚢᛚᚾᛏᚮ᛬ᛏᚮᛋᛁᚮᚵᛂᚢᛏᚮᚱᛘᛔᚱᛂᚴᛋᛏ᛭ᛋᚢᚼᚾᚢᛋᛘᚮᛁᚵᚾᛁᛂᛏᚮᛑ᛭ᚵᛂᚢᛏᚮᚱ"
        var lc = 0
        var sc = 0

        while (lc < l.size) {
            print(font.toColorCode(l[lc]))
            print(s[sc])

            lc++
            sc++

            if (sc == s.length) break

            if (s[sc] == ' ') {
                print(" ")
                sc++
            }
        }

        println("${font.noColorCode}\nEND")

        println(font.toColorCode(0xF_F07))
        println(font.toColorCode(0x0000))

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

                PixmapIO.writePNG(Gdx.files.local("testing.PNG"), pixmap)
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
const val TEXH = 2060

fun main(args: Array<String>) {
    appConfig = LwjglApplicationConfiguration()
    appConfig.vSyncEnabled = false
    appConfig.resizable = false//true;
    appConfig.width = TEXW
    appConfig.height = 768
    appConfig.title = "Terrarum Sans Bitmap Test (GDX)"

    LwjglApplication(FontTestGDX(), appConfig)
}
