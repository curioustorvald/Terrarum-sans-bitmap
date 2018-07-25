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
    }

    override fun getScreen(): Screen? {
        return null
    }

    override fun setScreen(screen: Screen?) {
    }

    override fun render() {

        Gdx.gl.glClearColor(1f - 0xBA/255f, 1f - 0xDA/255f, 1f - 0x55/255f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)


        batch.begin()

        inputText.reversed().forEachIndexed { index, s ->
            font.draw(batch, s, 10f, 10f + index * font.lineHeight)
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

fun main(args: Array<String>) {
    val appConfig = LwjglApplicationConfiguration()
    appConfig.vSyncEnabled = false
    appConfig.resizable = false//true;
    appConfig.width = 1024 // photographic ratio (1.5:1)
    appConfig.height = 1024 // photographic ratio (1.5:1)
    appConfig.title = "Terrarum Sans Bitmap Test (GDX)"

    LwjglApplication(FontTestGDX(), appConfig)
}
