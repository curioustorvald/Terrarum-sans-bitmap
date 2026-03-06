# Autokem

CNN-based tool that predicts kerning tag bits for font sprite sheets.
Trains on manually-tagged `*_variable.tga` sheets (~2650 samples across 24 sheets), then applies learned predictions to new or untagged sheets.

## Building

```bash
cd Autokem
make          # optimised build (-Ofast)
make debug    # ASan + UBSan, no optimisation
make clean
```

## Usage

```bash
./autokem train                              # train on ../src/assets/*_variable.tga
./autokem apply ../src/assets/foo_variable.tga  # apply model to a sheet
./autokem stats                              # print model tensor shapes + metadata
./autokem help
```

- `train` scans `../src/assets/` for `*_variable.tga` (skips `*extrawide*`), collects labelled samples, trains with 80/20 split + early stopping, saves `autokem.safetensors`
- `apply` creates `.bak` backup, runs inference per cell, writes Y+5 (lowheight) and Y+6 (kern data) pixels. Skips cells with width=0, writeOnTop, or compiler directives
- Model file `autokem.safetensors` must be in the working directory

## Architecture

### Neural network

```
Input: 15x20x1 binary (300 values, alpha >= 0x80 → 1.0)
  Conv2D(1→12, 3x3, same) → LeakyReLU(0.01)
  Conv2D(12→16, 3x3, same) → LeakyReLU(0.01)
  Flatten → 4800
  Dense(4800→24) → LeakyReLU(0.01)
  ├── Dense(24→10) → sigmoid  (shape bits A-H, J, K)
  ├── Dense(24→1)  → sigmoid  (Y-type)
  └── Dense(24→1)  → sigmoid  (lowheight)
Total: ~117,388 params (~460 KB float32)
```

Training: Adam (lr=0.001, beta1=0.9, beta2=0.999), BCE loss, batch size 32, early stopping patience 10.

### File layout

| File | Purpose |
|------|---------|
| `main.c` | CLI dispatch |
| `tga.h/tga.c` | TGA reader/writer — BGRA↔RGBA8888, row-order handling, per-pixel write-in-place |
| `nn.h/nn.c` | Tensor, Conv2D (same padding), Dense, LeakyReLU, sigmoid, Adam, He init |
| `safetensor.h/safetensor.c` | `.safetensors` serialisation — 12 named tensors + JSON metadata |
| `train.h/train.c` | Data collection from sheets, training loop, validation, label distribution |
| `apply.h/apply.c` | Backup, eligibility checks, inference, pixel composition |

## Pixel format

All pixels are RGBA8888: `(R<<24) | (G<<16) | (B<<8) | A`. TGA files store bytes as BGRA — the reader/writer swaps B↔R.

### Tag column (rightmost pixel column of each 16x20 cell)

| Row | Field | Encoding |
|-----|-------|----------|
| Y+0..Y+4 | Width | 5-bit binary, alpha != 0 → bit set |
| Y+5 | lowheight | alpha=0xFF → lowheight, alpha=0 → not |
| Y+6 | Kern data | See below |
| Y+9 | Compiler directive | opcode in R byte; skip cell if != 0 |
| Y+17 | writeOnTop | alpha != 0 → skip cell |

### Y+6 kern data pixel

```
  R byte:  Y0000000   (Y-type flag in MSB, bit 31)
  G byte:  JK000000   (J = bit 23, K = bit 22)
  B byte:  ABCDEFGH   (A = bit 15, ..., H = bit 8)
  A byte:  0xFF       (hasKernData flag — must be 0xFF, not 0x01)
```

`tagify(pixel)`: returns 0 if alpha == 0, else full pixel value.
`kerningMask = (pixel >> 8) & 0xFFFFFF` then extract individual bits.

### Shape bit layout

```
 A-B   top (unset for lowheight minuscules like e)
 |-|
 C-D   middle hole for majuscules (like C)
 E-F   middle hole for minuscules (like c)
 G-H
 ---   baseline
 |-|
 J-K   descender
```

## Key pitfalls

- **Alpha must be 0xFF, not 0x01.** All manually-tagged sheets use alpha=255 for kern/lowheight pixels. Writing alpha=1 is functionally accepted by the font engine (`& 0xFF != 0`) but produces visually transparent pixels that look like nothing was written.
- **TGA byte order**: file stores BGRA, memory is RGBA8888. Must swap B↔R on both read and write.
- **Row order**: check TGA descriptor bit 5 (`top_to_bottom`) for both read and write paths.
- **XY-swap**: `*_xyswap_variable.tga` sheets use column-major cell enumeration. Both train and apply detect `xyswap` in the filename.
- **Overfitting**: 117K params vs ~2650 samples — early stopping is essential. The model will memorise training data almost perfectly.
- **Sigmoid stability**: two-branch form (`x >= 0` vs `x < 0`) to avoid `exp()` overflow.

## Reference files

| File | What to check |
|------|---------------|
| `TerrarumSansBitmap.kt:917-930` | Tag parsing (Y+5, Y+6, tagify) |
| `TerrarumSansBitmap.kt:3082-3134` | Keming rules, kemingBitMask, rule matching |
| `OTFbuild/tga_reader.py` | TGA BGRA→RGBA conversion (reference impl) |
| `OTFbuild/glyph_parser.py:107-194` | Sheet parsing, eligibility, xyswap |
| `keming_machine.txt` | Bit encoding spec, shape examples, rule definitions |

## Verification

1. `make && ./autokem train` — should find ~2650 samples, label distribution should show A~55%, C~92%, etc.
2. `./autokem stats` — prints tensor shapes, training metadata
3. `./autokem apply ../src/assets/currencies_variable.tga` — creates `.bak`, writes kern bits
4. Check applied pixels with Python: `from tga_reader import read_tga; img.get_pixel(tag_x, tag_y+6)` — alpha should be 0xFF, not 0x00
5. `java -jar FontDemoGDX.jar` with modified sheet to visually verify kerning
