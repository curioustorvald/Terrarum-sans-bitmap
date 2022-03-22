#### Pixel Artists Wanted: for Arabic (all four forms) and other Indian scripts (all the ligatures). Must willing to follow the styles and have a knowledge in Unicode. Your name will be inscribed in the list of contributors.

You can contribute to the font by fixing wrong glyphs, suggesting better ones, extending character set (letters for other writing systems or filling in the blanks on the existing ones), or code for other game frameworks (not limited to Java). Please leave pull request for that.

Font Spritesheets are stored in ```assets/graphics/fonts``` directory. Image format must be TGA with Alpha — no PNG. If someone needs PNG, they can batch-convert the font using utils like ImageMagick.


#### Before getting started, you did read our design goals, right? Good. Now you may continue your awesome work.

## Ascenders, descenders, width informations (aka Glyph Tags)

![Alas, use more modern browser or get better internet connexion!](glyph_height_pos_annotation.png)

Above image is a reference you can use while you draw some letters. Capital B is drawn as a reference. Orange-tinted area is for lowercase, x-height must be the same as that of said tinted area (lowercase Alpha is also drawn for the reference). NOTE THAT x-height is taller than centre bar (capital A is an exception). Height of the ascender of the lowercase letters must be the same as height of capital letters.

Red-tinted area SHOULD NOT CONTAIN any dots, it's emptied for compatibility. (Slick2d—you can define size of "gaps" of the spritesheet, but you can't define horizontal and vertical gap separately)

Blue-tinted area cotains width of the glyph in binary, uppermost dot is the Least Significant Bit.

Green-tinted area contains extra informations, left blank for most cases. We'll call it Glyph Tags.

Tinted-in-magenta shows the height where diacritics should be placed, for both uppercase and lowercase.

Each cell is 16 px wide, and any glyph you draw **must be contained within leftside FIFTEEN pixels**.




## Font Metrics for variable-width font sheets

Although the font is basically a Spritesheet, some of the sheet expects variable widths to be supported. Any sheets with ```_variable``` means it expects variable widths. Anything else expects fixed width (regular Spritesheet behaviour). ```cjkpunct``` has width of 10, ```kana``` and ```hangul_johab``` has width of 12, ```wenquanyi``` has width of 16.

### Parsing Glyph Tags

![Sample of Font Spritesheet with annotation](width_bit_encoding_annotated.png)

Width is encoded in binary bits, on pixels. On the font spritesheet, every glyph has vertical dots on their top-right side (to be exact, every (16k - 1)th pixel on x axis). Above image is a sample of the font, with width information coloured in magenta. From top to bottom, each dot represents 1, 2, 4 and 8. For example, in the above image, ! (exclamation mark) has width of 5, " (double quote) has width of 6, # (octothorp) has width of 8, $ (dollar sign) has width of 9.

### Glyph Tags

Rightmost vertical column (should be 20 px tall) contains the tags. Tags are defined as following:

```
(LSB) W -,
      W  |
      W  |= Width of the character
      W  |
      W -'
      m --Is this character lowheight?
      K -,
      K  |= Tags used by the "Keming Machine"
      K -'
      Q ---Compiler Directive (see below)
      n --,
      Y -, `-Nudging Bits (see below)
      X  |
      Y  |= Diacritics Anchor Points (see below)
      X -'  
      A -,_ 0 Align  1 Align  0 Align   1 Align before
      A -'  0 Left   0 Right  1 Centre* 1 the glyph
      D --Diacritics Type Bit (see below; not all diacritics are actually marked as a diacritics on the spritesheet)
      S -,_ 0 Stack  1 Stack  0 Before  1 Up &
(MSB) S -'  0 up     0 down   1 &After   1 Down* (e.g. U+0C48)

Align Centre is actually "align to where the anchor point is". Said anchor point default to the X-centre of the glyph.

Up&Down:
1. when two pixels are both #00FF00 it's "don't stack"
2. otherwise, it's actually up&down

```

#### Nudging Bits Encoding

    <MSB,Red> SXXXXXXX SYYYYYYY 00000000 <LSB,Blue>

Each X and Y numbers are Signed 8-Bit Integer.

X-positive: nudges towards left  
Y-positive: nudges towards up  

#### Diacritics Anchor Point Encoding

4 Pixels are further divided as follows:

| LSB |   | Red | Green | Blue |
| ------------ | ------------ | ------------ | ------------ | ------------ |
| Y | Anchor point Y for: | undefined | undefined | undefined |
| X | Anchor point X for: | undefined | undefined | undefined |
| Y | Anchor point Y for: | (unused) | (unused) | (unused) |
| X | Anchor point X for: | Type-0 | Type-1 | Type-2 |
| **MSB** |   |   |   |   |

    <MSB,Red> 1Y1Y1Y1Y 1Y2Y2Y2Y 1Y3Y3Y3Y <LSB,Blue>
    <MSB,Red> 1X1X1X1X 1X2X2X2X 1X3X3X3X <LSB,Blue>

where Red is first, Green is second, Blue is the third diacritics.
MSB for each word must be set so that the pixel would appear brighter on the image editor.
(the font program will only read low 7 bits for each RGB channel)

#### Diacritics Type Bit Encoding

    <MSB,Red> FFFFFFFF FFFFFFFF FFFFFFFF <LSB,Blue> (For Type-0)
    <MSB,Red> TTTT0000 00000000 00000000 <LSB,Blue> (For Type-1 to Type-15)

Certain types of diacritics have predefined meanings (but some writing systems define their own meaning e.g. Devanagari):

* Type-0: Above
* Type-1: Below (when it should be separated from being above)
* Type-2: Overlaid (will shift down 2 pixels for lowheight glyphs instead of the default of 4 pixels)


#### Compiler Directives

    <MSB,Red> [Opcode] [arg1] [arg2] <LSB,Blue>

Currently supported opcodes:

*00000000: No-operation; does not use the Compiler Directive system.
    
*10000111: Replace a character with maximum 7 subchars.
  Replacement characters are encoded vertically from X-zero, bit by bit
  (colour of the pixel doesn't matter) with LSB sitting on Y-zero.

*11111111: Tagging Used by the Subsystems. e.g. #FF0000 marks invalid combination.

#### Stack Up/Down

When the tag is stack-up, it'll be drawn 4 px lower if the underlying
character is lowercase.

#### Align-To-This-X-Pos

Since this tag does not make sense for diacritics, they will use the value for compeletely different purpose:

    0 : Nothing special
    1 : Covers previous character; it's neither stack-up nor down.
        Will be drawn 2 px lower if the underlying character is lowercase
    2 : Joiner.
    3..15: undefined

#### Diacritics That Comes Before and After

When this tag is set, the font compiler will replace this glyph with two extra code points given in the bitmap.

To implement those, this two extra code points are needed, which are provided in the Unicode's Reference Chart (www.unicode.org/charts/PDF/Uxxxx.pdf) The code points must be "drawn" in the bitmap, in the same manor as a tagging system. The zeroth column (x = 0) has the "before" character, the first column (x = 1) has the "after". All nineteen pixels (bits) are read by the font, which encompasses U+0000..U+EFFFF

For working examples, take a note at the bengali sprite sheet.

This tag can be used as a general "replace this with these" directive, as long as you're replacing it into two letters. This directive is exploited to construct dutch ligature "IJ" (U+0132 and U+0133), in the sheet LatinExtA.

Also note that the font compiler will not "stack" these diacritics.

#### The Keming Machine Tags

Keming Machine Tags define the rough shape of the glyph. Please read `keming_machine.txt` for further information.


#### NOTES
- If glyphs are right or centre aligned, they must be aligned in the same way inside of the bitmap; the font compiler assumes every variable-width glyphs to have a width of 15, regardless of the tagged width.
- If the diacritic is aligned before the glyph, the diacritic itself is always assumed as left-aligned, as the font compiler will exchange position of said diacritic and the glyph right before it.

![Visual representation of left/right/centre align](alignment_illustration.jpg)

(fun fact: it was drawn on Rhodia memopad with Lamy 2000, then photographed and edited on my iPhone. Letter used is a Cherokee WE Ꮺ)

## Technical Limitations

- Each spritesheet is 4096x4096 maximum, which is a size of 4K Texture. However it is recommended to be smaller or equal to 1024x1024.
- Glyphs exceeding 15px of width needs to be broken down with 2 or more characters. Wider sheets WILL NOT BE IMPLEMENTED, can't waste much pixels just for few superwide glyphs.
- Due to how the compiler is coded, actual glyph must have alpha value of 255, the tags must have alpha values LESS THAN 255 (and obviously greater than zero). RGB plane of the TGA image doesn't do anything, keep it as #FFFFFF white.

## Implementation of the Korean writing system

On this font, Hangul letters are printed by assemblying two or three letter pieces. There are 10 sets of Hangul letter pieces on the font. Top 6 are initials, middle 2 are medials, and bottom 2 are finals. On the rightmost side, there's eight assembled glyphs to help you with (assuming you have basic knowledge on the writing system). Top 6 tells you how to use 6 initials, and bottom 2 tells you how to use 2 finals.

This is a Kotlin-like pseudocode for assembling the glyph:

    // NOTE: this code implements modern Hangul only, in the unicode range of 0xAC00..0xD7A3.
    // the spritesheet is made to accomodate Johab encoding scheme, but can still be used with the following code.
    // for the code for full Johab encoding (U+1100.. that includes Old Korean), please refer to the actual code in the repo.
    
    function getHanChosung(hanIndex: Int) = hanIndex / (21 * 28)
    function getHanJungseong(hanIndex: Int) = hanIndex / 28 % 21
    function getHanJongseong(hanIndex: Int) = hanIndex % 28

    jungseongWide = arrayOf(9,13,14,18,19)
    jungseongComplex = arrayOf(10,11,12,15,16,17,20,23)

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
