package net.torvald.imagefont

import org.newdawn.slick.*

/**
 * Created by minjaesong on 16-01-20.
 */
class GameFontImpl : GameFontBase() {

    init {

        GameFontBase.hangulSheet = SpriteSheet(
                "./assets/graphics/fonts/hangul_johab.tga", GameFontBase.W_HANGUL, GameFontBase.H_HANGUL)
        GameFontBase.asciiSheet = SpriteSheet(
                "./assets/graphics/fonts/ascii_variable.tga", 15, 19, 1)
        GameFontBase.extASheet = SpriteSheet(
                "./assets/graphics/fonts/LatinExtA_variable.tga", 15, 19, 1)
        GameFontBase.kanaSheet = SpriteSheet(
                "./assets/graphics/fonts/kana.tga", GameFontBase.W_KANA, GameFontBase.H_KANA)
        GameFontBase.cjkPunct = SpriteSheet(
                "./assets/graphics/fonts/cjkpunct.tga", GameFontBase.W_ASIAN_PUNCT, GameFontBase.H_KANA)
        /*uniHan = new SpriteSheet(
                "./assets/graphics/fonts/unifont_unihan"
                        + ((!terrarum.gameLocale.contains("zh"))
                        ? "_ja" : "")
                        +".tga"
                , W_UNIHAN, H_UNIHAN
        );*/
        GameFontBase.cyrilic = SpriteSheet(
                "./assets/graphics/fonts/cyrilic_variable.tga", 15, 19, 1)
        GameFontBase.fullwidthForms = SpriteSheet(
                "./assets/graphics/fonts/fullwidth_forms.tga", GameFontBase.W_UNIHAN, GameFontBase.H_UNIHAN)
        GameFontBase.uniPunct = SpriteSheet(
                "./assets/graphics/fonts/unipunct.tga", GameFontBase.W_LATIN_WIDE, GameFontBase.H)
        GameFontBase.wenQuanYi_1 = SpriteSheet(
                "./assets/graphics/fonts/wenquanyi_11pt_part1.tga", 16, 18, 2)
        GameFontBase.wenQuanYi_2 = SpriteSheet(
                "./assets/graphics/fonts/wenquanyi_11pt_part2.tga", 16, 18, 2)
        GameFontBase.greekSheet = SpriteSheet(
                "./assets/graphics/fonts/greek_variable.tga", 15, 19, 1)
        GameFontBase.romanianSheet = SpriteSheet(
                "./assets/graphics/fonts/romana_wide.tga", GameFontBase.W_LATIN_WIDE, GameFontBase.H)
        GameFontBase.romanianSheetNarrow = SpriteSheet(
                "./assets/graphics/fonts/romana_narrow.tga", GameFontBase.W_LATIN_NARROW, GameFontBase.H)

        val shk = arrayOf(
                GameFontBase.asciiSheet,
                GameFontBase.hangulSheet,
                GameFontBase.extASheet,
                GameFontBase.kanaSheet,
                GameFontBase.cjkPunct,
                null, // Full unihan, filler because we're using WenQuanYi
                GameFontBase.cyrilic,
                GameFontBase.fullwidthForms,
                GameFontBase.uniPunct,
                GameFontBase.wenQuanYi_1,
                GameFontBase.wenQuanYi_2,
                GameFontBase.greekSheet,
                GameFontBase.romanianSheet,
                GameFontBase.romanianSheetNarrow,
        )
        GameFontBase.sheetKey = shk


        buildWidthTable(asciiSheet, 0, 0..0xFF)
        buildWidthTable(extASheet, 0x100, 0..0x7F)
        buildWidthTable(cyrilic, 0x400, 0..0x5F)
        buildWidthTable(greekSheet, 0x370, 0..0x5F)
    }
}
