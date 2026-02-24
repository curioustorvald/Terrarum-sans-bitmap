# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Building the JAR
The project uses IntelliJ IDEA project files (`.iml`) for building. Build the main library:
- Main library JAR: `lib/TerrarumSansBitmap.jar`
- Font test application JAR: `FontDemoGDX.jar`

### Testing Font Rendering
Run the font test application:
```bash
java -jar FontDemoGDX.jar
```
The test application demonstrates font rendering with text from `demotext_unaligned.txt` and outputs to `demo.PNG`.

### Key Development Files
- **Source code**: `src/net/torvald/terrarumsansbitmap/`
- **Font assets**: `assets/` directory (TGA format with alpha channel)
- **Test text**: `demotext.txt`, `demotext_unaligned.txt`, `testtext.txt`
- **Demo output**: Generated PNG files for visual verification

## Architecture Overview

### Core Components

**TerrarumSansBitmap** (`src/net/torvald/terrarumsansbitmap/gdx/TerrarumSansBitmap.kt`)
- Main font class extending LibGDX's BitmapFont
- Handles font asset loading from TGA sprite sheets
- Manages variable-width character rendering with complex glyph tagging system
- Supports multiple writing systems (Latin, CJK, Cyrillic, etc.)

**MovableType** (`src/net/torvald/terrarumsansbitmap/MovableType.kt`)
- Advanced typesetting engine with justified text layout
- Implements line-breaking, hyphenation, and kerning
- Supports multiple typesetting strategies (justified, ragged, centered)
- Handles complex text shaping for international scripts

**GlyphProps** (`src/net/torvald/terrarumsansbitmap/GlyphProps.kt`)
- Defines glyph properties including width, diacritics anchors, alignment
- Manages kerning data and special rendering directives
- Handles complex glyph tagging system for font behavior

### Font Asset System

**Glyph Encoding**
- Font data stored in TGA sprite sheets with embedded metadata
- Width encoded in binary dots on rightmost column
- Complex tagging system for diacritics, kerning, and special behaviors
- Variable-width sheets use `_variable` naming convention

**Character Support**
- Latin scripts with full diacritics support
- CJK ideographs (Chinese variant)
- Korean Hangul with syllable composition
- Cyrillic with Bulgarian/Serbian variants (requires control characters U+FFFC1, U+FFFC2)
- Devanagari, Tamil with ligature support
- Many other scripts (see assets directory)

**Typewriter Font**
- Separate typewriter bitmap font in `src/net/torvald/terrarumtypewriterbitmap/`
- Includes audio feedback system with typing sounds
- Supports international QWERTY and Korean 3-set layouts

### Key Technical Details

**Color Coding System**
- Uses Unicode private use area for color codes
- Utility functions: `GameFontBase.toColorCode()` for ARGB4444 format
- U+100000 disables color codes

**Korean Hangul Assembly**
- Decomposes Unicode Hangul into jamo components
- Assembles glyphs from initial/medial/final sprite pieces
- Supports modern Hangul range (U+AC00-U+D7A3)

**Font Metrics**
- Variable-width sheets parse glyph tags from sprite metadata
- Fixed-width sheets: `cjkpunct` (10px), `kana`/`hangul_johab` (12px), `wenquanyi` (16px)
- Diacritics positioning via anchor point system
