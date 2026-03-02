# OTFbuild

Python toolchain that builds an OpenType (CFF) and Web Open Font (WOFF2) font from the TGA sprite sheets used by the bitmap font engine.

## Building

```bash
# builds both OTF and WOFF2
make all
```

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
| `otf2woff2.py` | OTF to WOFF2 wrapper |

### OpenType features generated (`opentype_features.py`)

- **ccmp** — replacewith expansions (DFLT); consonant-to-PUA mapping + vowel decompositions + anusvara upper (dev2); vowel decompositions (tml2)
- **kern** — pair positioning from `keming_machine.py`
- **liga** — Latin ligatures (ff, fi, fl, ffi, ffl, st) and Armenian ligatures
- **locl** — Bulgarian/Serbian Cyrillic alternates; Devanagari consonant-to-PUA mapping + vowel decompositions + anusvara upper (dev2, duplicated from ccmp for DirectWrite compatibility)
- **nukt, akhn, half, blwf, cjct, pres, blws, rphf, abvs, psts, calt** — Devanagari complex script shaping (all under `script dev2`)
- **pres** (tml2) — Tamil consonant+vowel ligatures
- **pres** (sund) — Sundanese diacritic combinations
- **ljmo, vjmo, tjmo** — Hangul jamo positional variants
- **mark** — GPOS mark-to-base diacritics positioning
- **mkmk** — GPOS mark-to-mark diacritics stacking (successive marks shift by H_DIACRITICS)

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

### languagesystem and language records

The `languagesystem` declarations in the preamble control which script/language records are created in the font tables. Key rules:

- `languagesystem` declarations must be at the **top level** of the feature file, not inside any `feature` block. Putting them inside `feature aalt { }` is invalid feaLib syntax and causes silent compilation failure.
- When a language-specific record exists (e.g. `dev2/MAR` from `languagesystem dev2 MAR;`), features registered under `script dev2;` only populate `dev2/dflt` — they are **not** automatically copied to `dev2/MAR`. The language record inherits only from DFLT, resulting in incomplete feature sets.
- Only declare language-specific records when you have `locl` or other language-differentiated features. Otherwise, use only `languagesystem <script> dflt;` to avoid partial feature inheritance that breaks DirectWrite and CoreText.

### Inspecting feature registration per script

To verify that features are correctly registered under each script:

```python
from fontTools.ttLib import TTFont

font = TTFont('OTFbuild/TerrarumSansBitmap.otf')
gsub = font['GSUB']

for sr in gsub.table.ScriptList.ScriptRecord:
    tag = sr.ScriptTag
    if sr.Script.DefaultLangSys:
        feats = []
        for idx in sr.Script.DefaultLangSys.FeatureIndex:
            fr = gsub.table.FeatureList.FeatureRecord[idx]
            feats.append(fr.FeatureTag)
        print(f"{tag}/dflt: {' '.join(sorted(set(feats)))}")
    for lsr in (sr.Script.LangSysRecord or []):
        feats = []
        for idx in lsr.LangSys.FeatureIndex:
            fr = gsub.table.FeatureList.FeatureRecord[idx]
            feats.append(fr.FeatureTag)
        print(f"{tag}/{lsr.LangSysTag}: {' '.join(sorted(set(feats)))}")
```

Expected output for dev2: `dev2/dflt: abvs akhn blwf blws calt ccmp cjct half liga locl nukt pres psts rphf`. If language-specific records (e.g. `dev2/MAR`) appear with only `ccmp liga`, the language records have incomplete feature inheritance — remove the corresponding `languagesystem` declaration.

### Debugging feature compilation failures

The build writes `debugout_features.fea` with the raw feature code before compilation. When compilation fails, inspect this file to find syntax errors. Common issues:

- **`languagesystem` inside a feature block** — must be at the top level
- **Named lookup defined inside a feature block** — applies unconditionally to all input. Define the lookup outside the feature block and reference it via contextual rules inside.
- **Glyph not in font** — a substitution references a glyph name that doesn't exist in the font's glyph order (e.g. a control character was removed)

### HarfBuzz Indic shaper (dev2) feature order

Understanding feature application order is critical for Devanagari debugging:

1. **Pre-reordering** (Unicode order): `ccmp`
2. **Reordering**: HarfBuzz reorders pre-base matras (e.g. I-matra U+093F moves before the consonant)
3. **Post-reordering**: `nukt` → `akhn` → `rphf` → `half` → `blwf` → `cjct` → `pres` → `abvs` → `blws` → `psts` → `haln` → `calt`
4. **GPOS**: `kern` → `mark`/`abvm` → `mkmk`

Implication: GSUB rules that need to match pre-base matras adjacent to post-base marks (e.g. anusvara substitution triggered by I-matra) must go in `ccmp`, not `psts`, because reordering separates them.

### Cross-platform shaper differences (DirectWrite, CoreText, HarfBuzz)

The three major shapers behave differently for Devanagari (dev2):

**DirectWrite (Windows)**:
- Feature order: `locl` → `nukt` → `akhn` → `rphf` → `rkrf` → `blwf` → `half` → `vatu` → `cjct` → `pres` → `abvs` → `blws` → `psts` → `haln` → `calt` → GPOS: `kern` → `dist` → `abvm` → `blwm`
- **Does NOT apply `ccmp`** for the dev2 script. All lookups that must run before `nukt` (e.g. consonant-to-PUA mapping) must be registered under `locl` instead.
- Tests reph eligibility via `would_substitute([RA, virama], rphf)` using **original Unicode codepoints** (before locl/ccmp). The `rphf` feature must include a rule with the Unicode form of RA, not just the PUA form.

**CoreText (macOS)**:
- Applies `ccmp` but may do so **after** reordering (unlike HarfBuzz which applies ccmp before reordering). This means pre-base matras (I-matra U+093F) are already reordered before the consonant, breaking adjacency rules like `sub 093F 0902'`.
- Tests reph eligibility using `would_substitute()` with Unicode codepoints, same as DirectWrite.
- Solution: add wider-context fallback rules in `abvs` (post-reordering) that match I-matra separated from anusvara by 1-3 intervening glyphs.

**HarfBuzz (reference)**:
- Applies `ccmp` **before** reordering (Unicode order).
- Reph detection is pattern-based (RA + halant + consonant at syllable start), not feature-based.
- Most lenient — works with PUA-only rules.

**Practical implication**: Define standalone lookups (e.g. `DevaConsonantMap`, `DevaVowelDecomp`) **outside** any feature block, then reference them from both `locl` and `ccmp`. This ensures DirectWrite (via locl) and HarfBuzz (via ccmp) both fire the lookups. The second application is a no-op since glyphs are already transformed.

Source: [Microsoft Devanagari shaping spec](https://learn.microsoft.com/en-us/typography/script-development/devanagari)
