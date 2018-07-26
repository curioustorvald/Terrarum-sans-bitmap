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
