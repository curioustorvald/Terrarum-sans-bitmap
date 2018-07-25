import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import com.badlogic.gdx.graphics.GL20
import net.torvald.terrarumsansbitmap.gdx.GameFontBase

/**
 * Created by minjaesong on 2018-07-26.
 */
class FontTestGDX : Game() {

    lateinit var font: GameFontBase

    override fun create() {
        font = GameFontBase("./assets")
    }

    override fun getScreen(): Screen? {
        return null
    }

    override fun setScreen(screen: Screen?) {
    }

    override fun render() {

        Gdx.gl.glClearColor(.094f, .094f, .094f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

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
    appConfig.width = 740 // photographic ratio (1.5:1)
    appConfig.height = 1110 // photographic ratio (1.5:1)
    appConfig.title = "Terrarum Sans Bitmap Test (GDX)"

    LwjglApplication(FontTestGDX(), appConfig)
}
