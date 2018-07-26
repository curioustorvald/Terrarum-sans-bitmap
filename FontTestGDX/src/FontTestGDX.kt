import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import net.torvald.terrarumsansbitmap.gdx.GameFontBase

/**
 * Created by minjaesong on 2018-07-26.
 */
class FontTestGDX : Game() {

    lateinit var font: GameFontBase

    lateinit var inputText: List<String>

    lateinit var batch: SpriteBatch

    override fun create() {
        font = GameFontBase("./assets", flipY = false) // must test for two cases

        val inTextFile = Gdx.files.internal("./FontTestGDX/demotext.txt")
        val reader = inTextFile.reader()
        inputText = reader.readLines()
        reader.close()

        batch = SpriteBatch()




        println("START")

        val l = intArrayOf(0xF00F,
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
                0xFF0F,
                0xEF0F,
                0xDF0F,
                0xCF0F,
                0xBF0F,
                0xAF0F,
                0x9F0F,
                0x8F0F,
                0x7F0F,
                0x6F0F,
                0x5F0F,
                0x4F0F,
                0x3F0F,
                0x2F0F,
                0x1F0F,
                0x0F0F,
                0x0F1F,
                0x0F2F,
                0x0F3F,
                0x0F4F,
                0x0F5F,
                0x0F6F,
                0x0F7F,
                0x0F8F,
                0x0F9F,
                0x0FAF,
                0x0FBF,
                0x0FCF,
                0x0FDF,
                0x0FEF,
                0x0FFF,
                0x0EFF,
                0x0DFF,
                0x0CFF,
                0x0BFF,
                0x0AFF,
                0x09FF,
                0x08FF,
                0x07FF,
                0x06FF,
                0x05FF,
                0x04FF,
                0x03FF,
                0x02FF,
                0x01FF,
                0x00FF,
                0x10FF,
                0x20FF,
                0x30FF,
                0x40FF,
                0x50FF,
                0x60FF,
                0x70FF,
                0x80FF,
                0x90FF,
                0xA0FF,
                0xB0FF,
                0xC0FF,
                0xD0FF,
                0xE0FF,
                0xF0FF)
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

        println(font.charsetOverrideNormal)
        println(font.charsetOverrideBulgarian)
        println(font.charsetOverrideSerbian)
        println(font.noColorCode)
        println(font.toColorCode(0xFFFF))
    }

    override fun getScreen(): Screen? {
        return null
    }

    override fun setScreen(screen: Screen?) {
    }

    override fun render() {

        Gdx.gl.glClearColor(.141f, .141f, .141f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)


        batch.begin()

        inputText.forEachIndexed { index, s ->
            font.draw(batch, s, 10f, appConfig.height - 30f - index * font.lineHeight)
        }

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
}

lateinit var appConfig: LwjglApplicationConfiguration

fun main(args: Array<String>) {
    appConfig = LwjglApplicationConfiguration()
    appConfig.vSyncEnabled = false
    appConfig.resizable = false//true;
    appConfig.width = 960
    appConfig.height = 2048
    appConfig.title = "Terrarum Sans Bitmap Test (GDX)"

    LwjglApplication(FontTestGDX(), appConfig)
}
