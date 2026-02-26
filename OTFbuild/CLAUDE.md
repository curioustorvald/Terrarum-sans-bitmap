# OTFbuild

Python toolchain that builds an OpenType (CFF) font from the TGA sprite sheets used by the bitmap font engine.

## Building

```bash
pip install fonttools
python3 OTFbuild/build_font.py src/assets -o OTFbuild/TerrarumSansBitmap.otf
```

Options:
- `--no-bitmap` — skip EBDT/EBLC bitmap strike (faster builds for iteration)
- `--no-features` — skip GSUB/GPOS OpenType features

## Debugging with HarfBuzz

Install `uharfbuzz` for shaping tests:

```bash
pip install uharfbuzz
```

Shape text and inspect glyph substitutions, advances, and positioning:

```python
import uharfbuzz as hb
from fontTools.ttLib import TTFont

with open('OTFbuild/TerrarumSansBitmap.otf', 'rb') as f:
    font_data = f.read()

blob = hb.Blob(font_data)
face = hb.Face(blob)
font = hb.Font(face)

text = "ऐतिहासिक"
buf = hb.Buffer()
buf.add_str(text)
buf.guess_segment_properties()
hb.shape(font, buf)

ttfont = TTFont('OTFbuild/TerrarumSansBitmap.otf')
glyph_order = ttfont.getGlyphOrder()

for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
    name = glyph_order[info.codepoint]
    print(f"  {name} advance=({pos.x_advance},{pos.y_advance}) cluster={info.cluster}")
```

Key things to check:
- **advance=(0,0)** on a visible character means the glyph is zero-width (likely missing outline or failed GSUB substitution)
- **glyph name starts with `uF0`** means GSUB substituted to an internal PUA form (expected for Devanagari consonants, Hangul jamo variants, etc.)
- **cluster** groups glyphs that originated from the same input character(s)

### Inspecting GSUB tables

```python
from fontTools.ttLib import TTFont

font = TTFont('OTFbuild/TerrarumSansBitmap.otf')
gsub = font['GSUB']

# List scripts and their features
for sr in gsub.table.ScriptList.ScriptRecord:
    tag = sr.ScriptTag
    if sr.Script.DefaultLangSys:
        for idx in sr.Script.DefaultLangSys.FeatureIndex:
            fr = gsub.table.FeatureList.FeatureRecord[idx]
            print(f"  {tag}/{fr.FeatureTag}: lookups={fr.Feature.LookupListIndex}")

# Inspect a specific lookup's substitution mappings
lookup = gsub.table.LookupList.Lookup[18]  # e.g. DevaConsonantMap
for st in lookup.SubTable:
    for src, dst in st.mapping.items():
        print(f"  {src} -> {dst}")
```

### Checking glyph outlines and metrics

```python
font = TTFont('OTFbuild/TerrarumSansBitmap.otf')
hmtx = font['hmtx']
cff = font['CFF ']

name = 'uni0915'  # Devanagari KA
w, lsb = hmtx[name]
cs = cff.cff.topDictIndex[0].CharStrings[name]
cs.decompile()
has_outlines = len(cs.program) > 2  # more than just width + endchar
print(f"{name}: advance={w}, has_outlines={has_outlines}")
```

## Architecture

### Build pipeline (`font_builder.py`)

1. **Parse sheets** — `glyph_parser.py` reads each TGA sprite sheet, extracts per-glyph bitmaps and tag-column metadata (width, alignment, diacritics anchors, kerning data, directives)
2. **Compose Hangul** — `hangul.py` assembles 11,172 precomposed Hangul syllables from jamo components and stores jamo variants in PUA for GSUB
3. **Populate Devanagari** — consonants U+0915-0939 have width=0 in the sprite sheet (the Kotlin engine normalises them to PUA forms); the builder copies PUA glyph data back to the Unicode positions so they render without GSUB
4. **Expand replacewith** — glyphs with the `replacewith` directive (opcode 0x80-0x87) are collected for GSUB multiple substitution (e.g. U+0910 -> U+090F U+0947)
5. **Build glyph order and cmap** — PUA internal forms (0xF0000-0xF0FFF) get glyphs but no cmap entries
6. **Trace bitmaps** — `bitmap_tracer.py` converts 1-bit bitmaps to CFF rectangle contours (50 units/pixel)
7. **Set metrics** — hmtx, hhea, OS/2, head, name, post tables
8. **OpenType features** — `opentype_features.py` generates feaLib code, compiled via `fontTools.feaLib`
9. **Bitmap strike** — optional EBDT/EBLC at 20ppem via TTX import

### Module overview

| Module | Purpose |
|---|---|
| `build_font.py` | CLI entry point |
| `font_builder.py` | Orchestrates the build pipeline |
| `sheet_config.py` | Sheet indices, code ranges, index functions, metric constants, Hangul/Devanagari/Tamil/Sundanese constants |
| `glyph_parser.py` | TGA sprite sheet parsing; extracts bitmaps and tag-column properties |
| `tga_reader.py` | Low-level TGA image reader |
| `bitmap_tracer.py` | Converts 1-bit bitmaps to CFF outlines (rectangle merging) |
| `opentype_features.py` | Generates GSUB/GPOS feature code for feaLib |
| `keming_machine.py` | Generates kerning pairs from glyph kern masks |
| `hangul.py` | Hangul syllable composition and jamo GSUB data |

### OpenType features generated (`opentype_features.py`)

- **ccmp** — replacewith expansions (DFLT); consonant-to-PUA mapping + vowel decompositions (dev2)
- **kern** — pair positioning from `keming_machine.py`
- **liga** — Latin ligatures (ff, fi, fl, ffi, ffl, st) and Armenian ligatures
- **locl** — Bulgarian/Serbian Cyrillic alternates
- **nukt, akhn, half, vatu, pres, blws, rphf** — Devanagari complex script shaping (all under `script dev2`)
- **pres** (tml2) — Tamil consonant+vowel ligatures
- **pres** (sund) — Sundanese diacritic combinations
- **ljmo, vjmo, tjmo** — Hangul jamo positional variants
- **mark** — GPOS mark-to-base diacritics positioning

### Devanagari PUA mapping

The bitmap font engine normalises Devanagari consonants to internal PUA forms before rendering. The OTF builder mirrors this:

| Unicode range | PUA range | Purpose |
|---|---|---|
| U+0915-0939 | 0xF0140-0xF0164 | Base consonants |
| U+0915-0939 +48 | 0xF0170-0xF0194 | Nukta forms (consonant + U+093C) |
| U+0915-0939 +240 | 0xF0230-0xF0254 | Half forms (consonant + virama) |
| U+0915-0939 +480 | 0xF0320-0xF0404 | RA-appended forms (consonant + virama + RA) |
| U+0915-0939 +720 | 0xF0410-0xF04F4 | RA-appended half forms (consonant + virama + RA + virama) |

Mapping formula: `to_deva_internal(c)` = `c - 0x0915 + 0xF0140` for U+0915-0939.

### Script tag gotcha

When a script-specific feature exists in GSUB (e.g. `ccmp` under `dev2`), HarfBuzz uses **only** the script-specific lookups and does **not** fall back to the DFLT script's lookups for that feature. Any substitutions needed for a specific script must be registered under that script's tag.
