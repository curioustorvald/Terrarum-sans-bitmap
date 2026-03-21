---
name: add-unicode-block
description: Add a new Unicode script/block to the Terrarum Sans Bitmap font engine.
---
# Add Unicode Block

## Required inputs

The user must supply:
- **Script name** — human-readable name used in constant/function names (e.g. `Ogham`, `LatinExtE`)
- **TGA filename** — the sprite sheet filename without path (e.g. `ogham_variable.tga`)
- **Unicode range** — start and end codepoints inclusive (e.g. `U+1680..U+169F`)

If any of these are missing, ask for them before proceeding.

## Step 1 — Determine the next sheet index

Read the sheet index constants from both files to find the current highest index (excluding `SHEET_UNKNOWN = 254`):

- Kotlin: `src/net/torvald/terrarumsansbitmap/gdx/TerrarumSansBitmap.kt` — grep for `internal const val SHEET_`
- Python: `OTFbuild/sheet_config.py` — grep for `^SHEET_`

The new index = highest existing index + 1.

## Step 2 — Derive identifiers

From the script name, derive:
- **Kotlin constant**: `SHEET_<UPPER_SNAKE>_VARW` (e.g. `SHEET_OGHAM_VARW`)
- **Kotlin indexY function**: `<camelCase>IndexY` (e.g. `oghamIndexY`)
- **Python constant**: same as Kotlin constant
- **Range start hex**: the lower bound codepoint as a `0x`-prefixed Kotlin/Python literal

## Step 3 — Edit both files

Make all 6 edits. Read each section before editing.

### Kotlin: `src/net/torvald/terrarumsansbitmap/gdx/TerrarumSansBitmap.kt`

**a) Sheet index constant** — find the block of `internal const val SHEET_*` constants (just before `SHEET_UNKNOWN = 254`) and append:
```kotlin
internal const val SHEET_<NAME>_VARW = <INDEX>
```

**b) fileList entry** — find `internal val fileList` array and append before the closing `)`:
```kotlin
"<tga_filename>",
```

**c) codeRange entry** — find `internal val codeRange` array and append before the closing `)`:
```kotlin
0x<START>..<0x<END>, // SHEET_<NAME>_VARW
```
Use `+` to combine non-contiguous ranges if needed.

**d) getSheetwisePosition when-branch** — find the `when` block that dispatches to indexY functions (just before `else -> ch / 16`) and append:
```kotlin
SHEET_<NAME>_VARW -> <camelCase>IndexY(ch)
```

**e) indexY function** — find the block of private `*IndexY` functions near the bottom of the companion object and append:
```kotlin
private fun <camelCase>IndexY(c: CodePoint) = (c - 0x<START>) / 16
```

### Python: `OTFbuild/sheet_config.py`

**f) Sheet index constant** — find the block of `SHEET_* = <n>` constants (just before `SHEET_UNKNOWN = 254`) and append:
```python
SHEET_<NAME>_VARW = <INDEX>
```

**g) FILE_LIST entry** — find `FILE_LIST = [` array and append before the closing `]`:
```python
"<tga_filename>",
```

**h) CODE_RANGE entry** — find `CODE_RANGE = [` array and append before the closing `]`:
```python
list(range(0x<START>, 0x<END+1>)),                                                  # <INDEX>: <ScriptName>
```

**i) index_y lambda** — find the dict in `get_index_y(sheet_index, c)` (just before `SHEET_HANGUL: lambda: 0`) and append:
```python
SHEET_<NAME>_VARW: lambda: (c - 0x<START>) // 16,
```

## Step 4 — Verify

After all edits, confirm:
1. The Kotlin constant, fileList, codeRange, when-branch, and indexY function are all present and consistent.
2. The Python constant, FILE_LIST, CODE_RANGE, and index_y lambda are all present and consistent.
3. The indices in both files match.
4. The range end in `CODE_RANGE` is `end + 1` (Python `range` is exclusive).
