# Terrarum Sans Bitmap

![Font sample — necessary information in this image is also provided below.](demo.PNG)

This font is a bitmap font used in [my game project called Terrarum](https://github.com/minjaesong/Terrarum) (hence the name). The font supports more than 90 % of european languages, as well as Chinese, Japanese, and Korean.

The font is provided in following formats:

* **OTF** — This is the version you want most likely. It's compatible with anything that supports OpenType fonts.
* **WOFF2** — This is OTF font repackaged as a web font. You will want this if you want to use it on a web page.
* **JAR** — This is the version you want if you work with LibGDX. (extends ```BitmapFont``` class)

The issue page is open. If you have some issues to submit, or have a question, please leave it on the page.

#### Notes and Limitations
- JAR version comes with its own shaping and typesetting engine, texture caching, and self-contained assets. It is NOT compatible with `GlyphLayout`.
- (JAR only) Displaying Bulgarian/Serbian variants of Cyrillic requires special Control Characters. (`GameFontBase.charsetOverrideBulgarian` -- U+FFFC1; `GameFontBase.charsetOverrideSerbian` -- U+FFFC2)
- All Han characters are in Mainland Chinese variant. There is no plan to support the other variants unless there is someone willing to do the drawing of the characters
- Only the Devanagari and Tamil has full (as much as I can) ligature support for Indic scripts -- Bengali script does not have any ligature support

### Design Goals

- Sans-serif
- Realise (some of) handwritten forms
    - Combininig with the sans-serif, this stands for **no over-simplification**
- Condensed capitals for efficient space usage

## Download

- Go ahead to the [release tab](https://github.com/minjaesong/Terrarum-sans-bitmap/releases), and download the most recent version. It is **not** advised to use the .jar found within the repository, they're experimental builds I use during the development, and may contain bugs like leaking memory.

## Using on your LibGDX project

- Firstly, place the .jar to your library path, then:

On your code (Kotlin):

    import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap

    class YourGame : Game() {

        lateinit var fontGame: Font
    
        override fun create() {
            fontGame = TerrarumSansBitmap(...)
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

    import net.torvald.terrarumsansbitmap.gdx.TerrarumSansBitmap;

    class YourGame extends BasicGame {

        Font fontGame;
    
        @Override void create() {
            fontGame = new TerrarumSansBitmap(...);
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

CJK Ideographs are powered by [WenQuanYi Font](http://wenq.org/wqy2/index.cgi?BitmapSong). The font is distributed under the GNU GPL version 2. Although the shapes of typefaces are not copyrightable (the program codes—e.g. TTF—do), we would like to give a credit to the font and the people behind it.
