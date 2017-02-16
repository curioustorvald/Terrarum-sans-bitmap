# Terrarum Sans Bitmap

![Font sample](https://github.com/minjaesong/Terrarum-sans-bitmap/blob/master/font_test_3.PNG)

![Cyrillic sample – Russian, Bulgarian, Serbian](https://github.com/minjaesong/Terrarum-sans-bitmap/blob/master/terrarum_sans_cyrillic_2.png)

This font is a bitmap font used in my game project, Terrarum (hence the name). It Supports ExtA, Romanian (subset of ExtB), Cyrillic (Russian, Bulgarian, Serbian), Greek, Chinese, Japanese and Korean.

The code for the fonts are meant to be used with Slick2d (extends ```Font``` class). If you are not using the framework, please refer to the __Font metrics__ section to implement the font metrics correctly on your system.

The issue page is open. If you have some issues to submit, or have a question, please leave it on the page.


## Contribution guidelines

You can contribute to the font by fixing wrong glyphs, suggesting better ones, extending character set (like Latin Extended B), or code for other game frameworks such as LibGDX. Please leave pull request for that.

Font images are stored in ```assets/graphics/fonts``` directory. Image format must be TGA with Alpha — no PNG. If someone needs PNG, they can batch-convert the font using utils like ImageMagick.


## Using on Slick2d

On your code (Kotlin):

    class YourGame {

        lateinit var fontGame: Font
    
        override fun initStatesList(gc: GameContainer) {
            fontGame = GameFontImpl()
            ...
        }
        
        override fun render(gc: GameContainer, g: Graphics) {
            g.font = fontGame
            g.drawString(...)
        }
    }
    
On your code (Java):

    class YourGame {

        Font fontGame;
    
        void initStatesList(GameContainer gc) {
            fontGame = new GameFontImpl();
            ...
        }
        
        void render(GameContainer gc, Graphics g) {
            g.setFont(fontGame);
            g.drawString(...);
        }
    }

## Font metrics

The font expects both variable widths and fixed width to be supported. Any images with ```_variable``` means it expects variable widths. Anything else expects fixed width. ```romana_narrow``` has width of 6 (no, not 5), ```romana_wide``` has width of 9, ```cjkpunct``` has width of 10, ```kana``` has width of 12, ```hangul_johab``` has width of 11, ```wenquanyi``` has width of 16.

### Parsing glyph widths for variable font sheets

Width is encoded in binary number, on pixels. On the font image, every glyph has vertical dots on their right side (to be exact, every (16k - 1)th pixel on x axis). From top to bottom, each dot represents 1, 2, 4 and 8. For example, ASCII glyph 'C' has width of 9, 'W' has width of 11, " (double quote) has width of 6.

### Implementing the Korean writing system

On this font, Hangul letters are printed by assemblying two or three letter pieces. There are 10 sets of Hangul letter pieces on the font. Top 6 are initials, middle 2 are medials, and bottom 2 are finals. On the rightmost side, there's eight assembled glyphs to help you with (assuming you have basic knowledge on the writing system). Top 6 tells you how to use 6 initials, and bottom 2 tells you how to use 2 finals.

This is a Kotlin-like pseudocode for assembling the glyph:

    jungseongWide = arrayOf(8, 12, 13, 17, 18, 21)
    jungseongComplex = arrayOf(9, 10, 11, 14, 15, 16, 22)
    
    function getHanInitialRow(hanIndex: Int): Int {
        val ret: Int

        if (isJungseongWide(hanIndex))
            ret = 2
        else if (isJungseongComplex(hanIndex))
            ret = 4
        else
            ret = 0

        return if (getHanJongseong(hanIndex) == 0) ret else ret + 1
    }
    
    function isJungseongWide(hanIndex: Int) = jungseongWide.contains(getHanJungseong(hanIndex))
    function isJungseongComplex(hanIndex: Int) = jungseongComplex.contains(getHanJungseong(hanIndex))

    function getHanInitialRow(hanIndex: Int): Int {
        val ret: Int

        if (isJungseongWide(hanIndex))
            ret = 2
        else if (isJungseongComplex(hanIndex))
            ret = 4
        else
            ret = 0

        return if (getHanJongseong(hanIndex) == 0) ret else ret + 1
    }

    function getHanMedialRow(hanIndex: Int) = if (getHanJongseong(hanIndex) == 0) 6 else 7

    function getHanFinalRow(hanIndex: Int): Int {
        val jungseongIndex = getHanJungseong(hanIndex)

        return if (jungseongWide.contains(jungseongIndex))
            8
        else
            9
    }
    
    function isHangul(c: Char) = c.toInt() >= 0xAC00 && c.toInt() < 0xD7A4
    
    ...
    
    for (each Char on the string) {
        if (isHangul(Char)) {
            val hIndex = Char.toInt() - 0xAC00

            val indexCho = getHanChosung(hIndex)
            val indexJung = getHanJungseong(hIndex)
            val indexJong = getHanJongseong(hIndex)

            val choRow = getHanInitialRow(hIndex)
            val jungRow = getHanMedialRow(hIndex)
            val jongRow = getHanFinalRow(hIndex)

            // get sub image from sprite sheet
            val choseongImage =  hangulSheet.getSubImage(indexCho, choRow)
            val jungseongImage = hangulSheet.getSubImage(indexJung, jungRow)
            val jongseongImage = hangulSheet.getSubImage(indexJong, jongRow)
            
            // actual drawing part
            draw choseongImage to somewhere you want
            draw jungseongImage on top of choseongImage
            draw jongseongImage on top of choseongImage
        }
        ...
    }

## Acknowledgement

Thanks to kind people of [/r/Typography](https://www.reddit.com/r/typography/) for amazing feedbacks.
