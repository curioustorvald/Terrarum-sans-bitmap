package net.torvald.terrarumsansbitmap.slick2d

import net.torvald.terrarum.Terrarum

/**
 * Created by minjaesong on 16-01-20.
 */
class GameFontImpl(noShadow: Boolean = false) : GameFontBase(noShadow = noShadow) {

    init {

        GameFontBase.hangulSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/hangul_johab.tga", GameFontBase.W_HANGUL, GameFontBase.H)
        GameFontBase.asciiSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/ascii_variable.tga", 15, 19, 1)
        GameFontBase.runicSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/futhark.tga", GameFontBase.W_LATIN_WIDE, GameFontBase.H)
        GameFontBase.extASheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/LatinExtA_variable.tga", 15, 19, 1)
        GameFontBase.extBSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/LatinExtB_variable.tga", 15, 19, 1)
        GameFontBase.kanaSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/kana.tga", GameFontBase.W_KANA, GameFontBase.H)
        GameFontBase.cjkPunct = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/cjkpunct.tga", GameFontBase.W_ASIAN_PUNCT, GameFontBase.H)
        GameFontBase.cyrilic = org.newdawn.slick.SpriteSheet(
                when (Terrarum.gameLocale.substring(0..1)) {
                    "bg" -> "./assets/graphics/fonts/cyrilic_bulgarian_variable.tga"
                    "sr" -> "./assets/graphics/fonts/cyrilic_serbian_variable.tga"
                    else -> "./assets/graphics/fonts/cyrilic_variable.tga"
                }, 15, 19, 1)
        GameFontBase.fullwidthForms = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/fullwidth_forms.tga", GameFontBase.W_UNIHAN, GameFontBase.H_UNIHAN)
        GameFontBase.uniPunct = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/unipunct.tga", GameFontBase.W_LATIN_WIDE, GameFontBase.H)
        GameFontBase.uniHan = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/wenquanyi.tga.gz", 16, 16) // ~32 MB
        GameFontBase.greekSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/greek_variable.tga", 15, 19, 1)
        GameFontBase.thaiSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/thai_variable.tga", 15, 19, 1)
        GameFontBase.customSheet = org.newdawn.slick.SpriteSheet(
                "./assets/graphics/fonts/puae000-e0ff.tga", GameFontBase.SIZE_KEYCAP, GameFontBase.SIZE_KEYCAP)

        val shk = arrayOf(
                GameFontBase.asciiSheet,
                GameFontBase.hangulSheet,
                GameFontBase.runicSheet,
                GameFontBase.extASheet,
                GameFontBase.extBSheet,
                GameFontBase.kanaSheet,
                GameFontBase.cjkPunct,
                GameFontBase.uniHan,
                GameFontBase.cyrilic,
                GameFontBase.fullwidthForms,
                GameFontBase.uniPunct,
                GameFontBase.greekSheet,
                GameFontBase.thaiSheet,
                null, // Thai EF, filler because not being used right now
                GameFontBase.customSheet
        )
        GameFontBase.sheetKey = shk


        buildWidthTable(GameFontBase.asciiSheet, 0,     0..0xFF)
        buildWidthTable(GameFontBase.extASheet,  0x100, 0..0x7F)
        buildWidthTable(GameFontBase.extBSheet,  0x180, 0..0xCF)
        buildWidthTable(GameFontBase.cyrilic,    0x400, 0..0x5F)
        buildWidthTable(GameFontBase.greekSheet, 0x370, 0..0x5F)
    }

    fun reload() {
        GameFontBase.cyrilic.destroy()
        GameFontBase.cyrilic = org.newdawn.slick.SpriteSheet(
                when (Terrarum.gameLocale.substring(0..1)) {
                    "bg" -> "./assets/graphics/fonts/cyrilic_bulgarian_variable.tga"
                    "sr" -> "./assets/graphics/fonts/cyrilic_serbian_variable.tga"
                    else -> "./assets/graphics/fonts/cyrilic_variable.tga"
                }, 15, 19, 1)
    }
}
