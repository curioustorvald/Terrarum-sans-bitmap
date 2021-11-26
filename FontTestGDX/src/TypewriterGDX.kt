import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.audio.AudioDevice
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.utils.GdxRuntimeException
import net.torvald.terrarum.gamecontroller.InputStrober
import net.torvald.terrarumsansbitmap.gdx.CodepointSequence
import net.torvald.terrarumtypewriterbitmap.gdx.TerrarumTypewriterBitmap
import java.io.StringReader
import kotlin.math.roundToInt

/**
 * Created by minjaesong on 2021-11-05.
 */
class TypewriterGDX(val width: Int, val height: Int, val cols: Int) : Game() {

    lateinit var font: TerrarumTypewriterBitmap
    lateinit var batch: SpriteBatch
//    lateinit var frameBuffer: FrameBuffer
    lateinit var camera: OrthographicCamera

    lateinit var inputStrober: InputStrober

    lateinit var sndMovingkey: Sound
    lateinit var sndDeadkey: Sound
    lateinit var sndShiftin: Sound
    lateinit var sndShiftout: Sound
    lateinit var sndSpace: Sound
    lateinit var sndCRs: Array<Sound>
    lateinit var sndLF: Sound

    override fun create() {
        font = TerrarumTypewriterBitmap(
            "./assets/typewriter",
            StringReader(
                """ko_kr_3set-390_typewriter,typewriter_ko_3set-390.tga,16
                |en_intl_qwerty_typewriter,typewriter_intl_qwerty.tga,0
            """.trimMargin()
            ),
            true, false, 256, true
        )

        batch = SpriteBatch()

//        frameBuffer = FrameBuffer(Pixmap.Format.RGBA8888, TEXW, TEXH, true)

        camera = OrthographicCamera(width.toFloat(), height.toFloat())
        camera.translate(width.div(2f), 0f)
        camera.setToOrtho(true, width.toFloat(), height.toFloat())
        camera.update()


        inputStrober = InputStrober(this)

        try {
            sndMovingkey = Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/movingkey.wav"))
            sndDeadkey = Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/deadkey.wav"))
            sndShiftin = Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/shiftin.wav"))
            sndShiftout = Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/shiftout.wav"))
            sndSpace = Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/space.wav"))

            sndCRs = Array(6) {
                Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/cr$it.wav"))
            }

            sndLF = Gdx.audio.newSound(Gdx.files.internal("assets/typewriter/audio/crlf.wav"))
        }
        catch (e: GdxRuntimeException) {
            e.printStackTrace()
        }
    }

    private val textbuf: ArrayList<CodepointSequence> = arrayListOf(
        CodepointSequence(listOf(
            39,50,29, // kva (HANG_GONG)
            42,31, // nc (HANG_SE)
            74,48,51, // ;tw (HANG_BEOL)
            62, // space
            184,164,171,170, // >HON (ASC_3-90)
            62, // space
            74,48, // ;t (HANG_BEO)
            43,12, // o5 (HANG_CYU)
            38,48,51, // jtw (HANG_EOL)
            164, // H (ASC_-)
            75,34, // 'f (HANG_TA)
            40,34, // lf (HANG_JA)
            39,32  // kd (HANG_GI)
        ).map { it + 0xF3000 }),
        CodepointSequence(/* new line */)
    )

    var keylayoutbase = 0xF3000
    private val printableKeys = ((Input.Keys.NUM_0..Input.Keys.NUM_9) + (Input.Keys.A..Input.Keys.PERIOD) + 62 + (Input.Keys.BACKSPACE..Input.Keys.SLASH)).toHashSet()

    fun acceptKey(keycode: Int) {
        println("[TypewriterGDX] Accepting key: $keycode")

        if (keycode == Input.Keys.ENTER) {
            val tbufsize = textbuf.last().size.div(cols.toFloat()).times(6f).coerceIn(0f,6f).roundToInt() // 0..6
            textbuf.add(CodepointSequence())
            if (tbufsize == 0) sndLF.play()
            else sndCRs[tbufsize - 1].play()
        }
        else if (printableKeys.contains(keycode and 127)) {
            val cp = keycode + keylayoutbase
            textbuf.last().add(cp)
//            println("[TypewriterGDX] width: ${font.glyphProps[cp]}")

            // play audio
            val isDeadkey = font.glyphProps[cp]?.width == 0
            if (isDeadkey) {
                sndDeadkey.play()
            }
            else if (keycode == Input.Keys.SPACE || keycode == Input.Keys.BACKSPACE) {
                sndSpace.play()
            }
            else {
                sndMovingkey.play()
            }
        }
        else if (keycode == 128+Input.Keys.SHIFT_LEFT || keycode == 128+Input.Keys.SHIFT_RIGHT) {
            sndShiftin.play()
        }
    }

    /**
     * For Shift-out only
     */
    fun shiftOut() {
        sndShiftout.play()
    }


    private val textCol = Color(0.1f,0.1f,0.1f,1f)
    override fun render() {
        Gdx.gl.glClearColor(0.97f,0.96f,0.95f,1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_TEXTURE_2D)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE)

        batch.projectionMatrix = camera.combined
        batch.begin()

        batch.color = textCol

        try {
            textbuf.forEachIndexed { index, s ->
                font.draw(batch, s, 40f, 40f + 20 * index)
            }
        }
        catch (e: ConcurrentModificationException) {}

        batch.end()
    }

    override fun dispose() {
        font.dispose()
        batch.dispose()
        inputStrober.dispose()
        sndMovingkey.dispose()
        sndDeadkey.dispose()
        sndShiftin.dispose()
        sndShiftout.dispose()
        sndSpace.dispose()
        sndCRs.forEach { it.dispose() }
        sndLF.dispose()
    }
}

class TypewriterInput(val main: TypewriterGDX) : InputAdapter() {

    private var shiftIn = false

    override fun keyDown(keycode: Int): Boolean {
        // FIXME this shiftIn would not work at all...
        shiftIn = (keycode == Input.Keys.SHIFT_LEFT || keycode == Input.Keys.SHIFT_RIGHT)
        if (keycode < 128 && keycode != Input.Keys.SHIFT_LEFT && keycode != Input.Keys.SHIFT_RIGHT) {
            main.acceptKey(shiftIn.toInt() * 128 + keycode)
        }
        return true
    }

    private fun Boolean.toInt() = if (this) 1 else 0
}

fun main(args: Array<String>) {
    appConfig = Lwjgl3ApplicationConfiguration()
    appConfig.useVsync(false)
    appConfig.setResizable(false)
    appConfig.setWindowedMode(600, 800)
    appConfig.setTitle("Terrarum Typewriter Bitmap Test")

    Lwjgl3Application(TypewriterGDX(600, 800, 64), appConfig)
}
