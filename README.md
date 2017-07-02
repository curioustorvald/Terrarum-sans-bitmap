# Terrarum Sans Bitmap

![Font sample](https://github.com/minjaesong/Terrarum-sans-bitmap/blob/master/font_test_3.PNG)

This font is a bitmap font used in [my game project called Terrarum](https://gitlab.com/minjaesong/terrarum) (hence the name). The font supports more than 90 % of european languages, as well as Chinese, Japanese and Korean. More technical side, it supports Latin-1 Supplement, Latin Ext-A, Latin Ext-B, IPA Extension (required by some languages), Greek, Cyrillic (+ Bulgarian, Serbian variants) and the supplement, Armenian, Thai (beta version), Georgian, Unicode Punctuations, CJK Punctuations, Kana, Chinese (limited to Unihan and Ext-A), Hangul (all 11 172 possible syllables) and Fullwidth forms.

The JAR package is meant to be used with Slick2d (extends ```Font``` class) and LibGDX (extends ```BitmapFont``` class). If you are not using the framework, please refer to the __Font metrics__ section to implement the font metrics correctly on your system.

The issue page is open. If you have some issues to submit, or have a question, please leave it on the page.

#### Little notes
- You can't display Bulgarian, Russian and Serbian variants at the same time without some more codes; ```reload()```ing them or creating multiple instances of the font would be the easiest solutions.
- All Han characters are in Chinese variant, no other variants are to be supported as most Chinese, Japanese and Korean can understand other's variant and to be honest, we don't bother anyway.

## Contribution guidelines

You can contribute to the font by fixing wrong glyphs, suggesting better ones, extending character set (letters for other writing systems or filling in the blanks on the existing ones), or code for other game frameworks (not limited to Java). Please leave pull request for that.

Font Spritesheets are stored in ```assets/graphics/fonts``` directory. Image format must be TGA with Alpha — no PNG. If someone needs PNG, they can batch-convert the font using utils like ImageMagick.


## Using on your game

- Firstly, place the .jar to your library path and unzip spritesheets, then:

### Using on LibGDX

On your code (Kotlin):

    class YourGame : Game() {

        lateinit var fontGame: Font
    
        override fun create() {
            fontGame = GameFontBase(path_to_assets)
            ...
        }
        
        override fun render() {
            batch.begin()
            ...
            fontGame.draw(batch, text, ...)
            ...
            batch.end()
        }
    }
    
On your code (Java):

    class YourGame extends BasicGame {

        Font fontGame;
    
        @Override void create() {
            fontGame = new GameFontBase(path_to_assets);
            ...
        }
        
        @Override void render() {
            batch.begin();
            ...
            fontGame.draw(batch, text, ...);
            ...
            batch.end();
        }
    }


### Using on Slick2d

On your code (Kotlin):

    class YourGame : BasicGame("YourGameName") {

        lateinit var fontGame: Font
    
        override fun init(gc: GameContainer) {
            fontGame = GameFontBase(path_to_assets)
            ...
        }
        
        override fun render(gc: GameContainer, g: Graphics) {
            g.font = fontGame
            g.drawString(...)
        }
    }
    
On your code (Java):

    class YourGame extends BasicGame {

        Font fontGame;
    
        @Override void init(GameContainer gc) {
            fontGame = new GameFontBase(path_to_assets);
            ...
        }
        
        @Override void render(GameContainer gc, Graphics g) {
            g.setFont(fontGame);
            g.drawString(...);
        }
    }

### How to Use Color Code

Color codes are individual unicode characters. While you can somehow make a raw character and paste in on your code, it's certainly not desirable. Fortunately, we're also providing utility functions for the color codes.

    GameFontBase.toColorCode(rgba4444: Int)
    GameFontBase.toColorCode(r: Int, g: Int, b: Int)
    GameFontBase.toColorCode(r: Int, g: Int, b: Int, a: Int)

```rgba4444``` takes whole RGBA as input, that is, from 0x0000 to 0xFFFF. Most significant bits represents Red, and least significant bits represents Alpha (which should be fixed as F for the most time)
``r, g, b(, a)``` takes RGB and A separately, in the range of 0..F. Any value exceeds the range **are unchecked and may wreak havoc**, so be careful.


## Font metrics

Although the font is basically a Spritesheet, some of the sheet expects variable widths to be supported. Any sheets with ```_variable``` means it expects variable widths. Anything else expects fixed width (regular Spritesheet behaviour). ```cjkpunct``` has width of 10, ```kana``` and ```hangul_johab``` has width of 12, ```wenquanyi``` has width of 16.

### Parsing glyph widths for variable font sheets

![Sample of Font Spritesheet with annotation](https://github.com/minjaesong/Terrarum-sans-bitmap/blob/master/width_bit_encoding_annotated.png)

Width is encoded in binary bits, on pixels. On the font spritesheet, every glyph has vertical dots on their top-right side (to be exact, every (16k - 1)th pixel on x axis). Above image is a sample of the font, with width information coloured in magenta. From top to bottom, each dot represents 1, 2, 4 and 8. For example, in the above image, ! (exclamation mark) has width of 5, " (double quote) has width of 6, # (octothorp) has width of 8, $ (dollar sign) has width of 9.

### Implementing the Korean writing system

On this font, Hangul letters are printed by assemblying two or three letter pieces. There are 10 sets of Hangul letter pieces on the font. Top 6 are initials, middle 2 are medials, and bottom 2 are finals. On the rightmost side, there's eight assembled glyphs to help you with (assuming you have basic knowledge on the writing system). Top 6 tells you how to use 6 initials, and bottom 2 tells you how to use 2 finals.

This is a Kotlin-like pseudocode for assembling the glyph:

    function getHanChosung(hanIndex: Int) = hanIndex / (21 * 28)
    function getHanJungseong(hanIndex: Int) = hanIndex / 28 % 21
    function getHanJongseong(hanIndex: Int) = hanIndex % 28

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

CJK Ideographs are powered by [WenQuanYi Font](http://wenq.org/wqy2/index.cgi?BitmapSong). The font is distributed under the GNU GPL version 2. Although the glyphs themselves are not copyrightable (the program codes—e.g. TTF—do), we would like to give a credit for the font and the people behind it.
