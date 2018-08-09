# Terrarum Sans Bitmap

![Font sample](demo.PNG)

This font is a bitmap font used in [my game project called Terrarum](https://gitlab.com/minjaesong/terrarum) (hence the name). The font supports more than 90 % of european languages, as well as Chinese, Japanese and Korean. More technical side, it supports Latin-1 Supplement, Latin Ext-A, Latin Ext-B, IPA Extension (required by some languages), Greek, Cyrillic (+ Bulgarian, Serbian variants) and the supplement, Armenian, Thai (beta version), Georgian, Unicode Punctuations, CJK Punctuations, Kana, Chinese (limited to Unihan and Ext-A), Hangul (all 11 172 possible syllables) and Fullwidth forms.

The JAR package is meant to be used with Slick2d (extends ```Font``` class) and LibGDX (extends ```BitmapFont``` class). If you are not using the framework, please refer to the __Font metrics__ section to implement the font metrics correctly on your system.

The issue page is open. If you have some issues to submit, or have a question, please leave it on the page.

#### Little notes
- To display Bulgarian/Serbian variants, you need special Control Characters. (GameFontBase.charsetOverrideBulgarian -- U+FFFF9; GameFontBase.charsetOverrideSerbian -- U+FFFFA)
- All Han characters are in Chinese variant, no other variants are to be supported as most Chinese, Japanese and Korean can understand other's variant and to be honest, we don't bother anyway.
- Indian script in general is not perfect: this font will never do the proper ligatures (I can't draw all the 1 224 possible combinations). Hopefully it's still be able to understand without them.

### Design Goals

- Sans-serif
- Realise (some of) handwritten forms
    - Combininig with the sans-serif, this stands for **no over-simplification**
- Condensed capitals for efficient space usage


## Using on your game

- Firstly, place the .jar to your library path and assets folder to the main directory of the app, then:

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

    GameFontBase.toColorCode(argb4444: Int) -- returns String
    GameFontBase.toColorCode(r: Int, g: Int, b: Int) -- returns String
    GameFontBase.toColorCode(r: Int, g: Int, b: Int, a: Int) -- returns String

```argb4444``` takes whole ARGB (in that order) as input, that is, from 0x0000 to 0xFFFF.
```r, g, b(, a)``` takes RGB and A separately, in the range of 0x0..0xF. Any value exceeding the range **are unchecked and may wreak havoc**, so be careful.

U+100000 is used to disable previously-applied color codes (going back to original colour), even if it looks like ARGB of all zero.


## Contribution guidelines

Please refer to [CONTRIBUTING.md](https://github.com/minjaesong/Terrarum-sans-bitmap/blob/master/CONTRIBUTING.md)

## Acknowledgement

Thanks to kind people of [/r/Typography](https://www.reddit.com/r/typography/) for amazing feedbacks.

CJK Ideographs are powered by [WenQuanYi Font](http://wenq.org/wqy2/index.cgi?BitmapSong). The font is distributed under the GNU GPL version 2. Although, in some countries including where I'm based on, the shapes of typefaces are not copyrightable (the program codes—e.g. TTF—do), we would like to give a credit for the font and the people behind it.
