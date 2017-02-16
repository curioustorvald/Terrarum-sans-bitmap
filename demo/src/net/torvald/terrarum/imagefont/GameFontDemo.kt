package net.torvald.terrarum.imagefont

import net.torvald.imagefont.GameFontImpl
import org.newdawn.slick.*
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.*

/**
 * Created by SKYHi14 on 2017-02-16.
 */

class GameFontDemo : BasicGame("Terrarum Sans Bitmap Demo") {

    lateinit var gameFont: Font

    override fun init(p0: GameContainer?) {
        gameFont = GameFontImpl()
    }

    override fun update(gc: GameContainer, delta: Int) {
    }

    override fun render(gc: GameContainer, g: Graphics) {
        g.font = gameFont
        g.background = Color(0x282828)
        text.forEachIndexed { i, s ->
            g.drawString(s, 10f, 10f + i * gameFont.lineHeight)
        }
    }

    companion object {
        lateinit var gameLocale: String
        var hasError: Boolean
        val text = ArrayList<String>()

        init {
            try {
                val prop = Properties()
                prop.load(FileInputStream("./config.properties"))

                gameLocale = prop.getProperty("locale")

                if (gameLocale.length < 2)
                    throw IllegalArgumentException("Bad locale setting: “$gameLocale”")


                Files.lines(FileSystems.getDefault().getPath("./text.txt")).forEach(
                        { text.add(it) }
                )

                hasError = false
            }
            catch (e: Exception) {
                gameLocale = "enUS"
                hasError = true
                text.add("There was some problem loading the demo :(")
                text.add("This is what JVM says:")
                text.add("")
                e.message!!.split('\n').forEach {
                    text.add(it)
                }
            }
        }
    }
}

fun main(args: Array<String>) {

    System.setProperty("java.library.path", "lib")
    System.setProperty("org.lwjgl.librarypath", File("lib").absolutePath)

    val WIDTH = 1000
    val HEIGHT = 800

    try {
        val appgc = AppGameContainer(GameFontDemo())
        appgc.setDisplayMode(WIDTH, HEIGHT, false)

        appgc.setMultiSample(0)
        appgc.setShowFPS(false)

        // game will run normally even if it is not focused
        appgc.setUpdateOnlyWhenVisible(false)
        appgc.alwaysRender = true

        appgc.start()
    }
    catch (ex: Exception) {
        ex.printStackTrace()
    }
}