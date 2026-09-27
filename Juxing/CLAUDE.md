# Juxing (聚形成字)

Assembles the Han ideographs of Terrarum Sans Bitmap from hand-drawn *glyphlettes*.
It covers the CJK Unified Ideographs (U+4E00–U+9FFF) and Extension A (U+3400–U+4DBF),
27,584 characters, and is meant to replace `wenquanyi.tga` completely.

Juxing is a standalone Python tool with no dependency on GDX or on OTFbuild. Its only output is
a sprite sheet in the same format as the one it replaces. Both font engines load that sheet as
`SHEET_UNIHAN` with no code changes other than the file name.

## Ground rules

- **Nothing from WenQuanYi is used**: no glyphs, crops, traces or metrics. Every glyph pixel comes
  from the glyphlette sheets drawn for this project. (WenQuanYi is GPL, and bitmaps in fonts may
  become copyrightable in the EU.)
- **Data sources are permissive only.**
  - Unihan, EquivalentUnifiedIdeograph and CJKRadicals: Unicode License v3.
  - BabelStone `IDS.TXT` (Andrew West): the author waives all rights.
  - CNS 11643 attribute data (Ministry of Digital Affairs, Taiwan): Open Government Data
    License v1.0, compatible with CC BY 4.0. It requires attribution, which the generated model
    carries in its header. Only the stroke-sequence and Unicode mapping tables are used; the CNS
    fonts never are.
  - CHISE / cjkvi-ids IDS data is GPLv2 and is deliberately **not** used.
- Label fonts (Noto Sans CJK, BabelStone Han) are used only to caption guide images and mock-ups.
  Nothing rendered with them ever reaches a drawing sheet or the output.

## Concepts

| Term | Meaning |
|---|---|
| component | A character, an unencoded shape such as `{12}` ("right of 龍"), or an anonymous IDS expression. Each has one decomposition, or none (an atom). |
| slot | A component placed in a box of a given size: `SlotKey(comp, w, h, role)`, written `青@11x15`. A slot's layout depends only on its key, so the same slot looks the same in every character. |
| frame | The outer part of a surround operator (⿴⿵⿶⿷⿸⿹⿺⿼⿽), drawn over the whole box with the inner area left empty, written `辶@15x15/⿺`. It is a different drawing from the plain component. |
| glyphlette | A slot that is drawn by hand. It can be a radical, a phonetic part, a large chunk, or a whole character. |
| generated glyphlette | A slot of a simple rectilinear component (口 日 目 白 工 木 土 …) drawn by code (`generators.py`). It is never on the worklist, and a hand drawing of the same slot always wins. |

### Generated glyphlettes (`generators.py`)
- 27 components: 口 囗 日 曰 目 田 白 工 土 士 王 十 一 二 三 丨 山 中 由 甲 申 冂 凵 匚 冖 亠 宀 艹 木.
- **Sizing:** shapes keep a plausible aspect ratio inside their box, centred (a left-hand 口 is
  5×7). Enclosed shapes keep clear of the sides of wide boxes (口 on top of 呆 is 11px wide).
- **Bottom serifs:** the outer verticals of 口 囗 日 曰 目 田 白 山 中 由 甲 申 凵 匚 run past the
  bottom stroke. This is how these characters are made, not a serif of the typeface.
  - 1px normally, 2px when the shape is 13px tall or more (`SERIF_TALL`).
  - Dropped when the shape is squashed to 60% of its natural height or less (`SERIF_SQUASH`), so
    口 atop 古 or 日 atop 早 have none.
  - The serif takes the shape's own rows. If that leaves the strokes no room, the serif goes first.
  - The 囗 ⿴, 凵 ⿶ and 匚 ⿷ frames have a bottom inset of 4 in `layout.tsv`: stroke, 1px gap
    and a 2px serif.
- **Units:** a component with a generator is never split (`is_unit`). 田 is not a 囗 frame with a
  floating 十. Standalone (15×15), these characters are drawn by hand.
- **Declining:** a generator declines when its strokes can't fit with 1px gaps (目 needs 7 rows);
  such slots stay on the worklist.
| plan | Where each character's slot tree is cut into glyphlettes. |
| exemplar | The common character in which a glyphlette is shown, in context, on the drawing sheet. |

## Directory layout

```
Juxing/
  juxing.py        CLI entry point (all commands)
  ids.py           IDS tokeniser/parser
  sources.py       download + parse Unihan, BabelStone IDS, CJKRadicals, EquivalentUnifiedIdeograph
  model.py         builds/loads the Han model (model/han_model.tsv)
  geometry.py      layout engine: slot DAG, splits, frames, legibility rules
  planner.py       which glyphlettes to draw (cut optimisation, size reuse) and in what order
  generators.py    procedural glyphlettes for simple components (口 日 木 ...)
  glyphlettes.py   registry, drawing sheets (guides, ink), and the Library of available glyphlettes
  assembler.py     composes characters, writes the Han sheet, procedural hexagrams
  render.py        terminal diagrams, sheet indices, mock-ups, previews
  tga.py           TGA I/O
  sample_text.txt  text rendered into out/preview.png
  model/
    han_model.tsv  GENERATED model (committed so assembling needs no downloads)
    overrides.tsv  hand corrections to decompositions (atoms, replacement IDS)
    layout.tsv     hand-tuned layout rules (preferred part sizes, frame insets)
  glyphlettes/
    registry.tsv   issued glyphlettes and their permanent cells (committed)
    sheet_NN.tga   drawing sheets (committed; LFS)
  sources/         downloaded upstream data (ignored by git)
  out/             juxing.tga, worklist, indices, previews (ignored by git)
```

## Workflow

```bash
cd Juxing
make plan                          # what to draw, in which order -> out/worklist.tsv
python3 juxing.py show 清 灣 這      # how characters are laid out and what they need
python3 juxing.py mockup 清灣這林     # out/mockup.png: boxes with reference components squeezed in
python3 juxing.py generated         # out/generated.png + generated_chars.png: review the generated parts
# ... tune model/layout.tsv / model/overrides.tsv, `make model`, `make check`, repeat ...

python3 juxing.py issue 256        # allocate the next 256 glyphlettes to glyphlettes/sheet_NN.tga
# draw them (Krita etc.), save as uncompressed 32-bit TGA
make refresh                       # redraw guides: drawn parts now appear as context in other cells
make status                        # progress, coverage per tier, ink outside boxes
make                               # assemble out/juxing.tga + out/preview.png + out/coverage.png
make install                       # copy out/juxing.tga into ../src/assets
```

**Tune the layout before issuing.** A glyphlette is identified by its component *and size*. If a
layout rule changes after a glyphlette has been drawn, the characters that used it now need a
glyphlette of a different size. The old one stays in its cell as an orphan, which `status`
reports.

Other issuing options:
- `issue --whole 鬱 龜`: draw these characters whole. The assembler always prefers the largest
  drawn piece, so this overrides any assembly you don't like.
- `issue --key 木@5x15 广@15x15/⿸`: issue specific glyphlettes. A generated one is prefilled with
  its generated ink, so you can restyle it; once on a sheet, your drawing replaces the generated
  one everywhere.
- `issue 256 --group`: keep the glyphlettes of one component next to each other within the batch.

### Drawing sheets

- Each sheet is 32×32 cells of 16×16 px (512×512), holding 1,024 glyphlettes. Cells are assigned
  in issue order and never move. Find a cell in `out/sheet_NN_index.png`, which shows each cell
  zoomed and labelled with its component, size, cell number and exemplar.
- **Ink is opaque white** (alpha 255, every channel ≥ 128), the same as every other sheet.
  Everything else is guide and is regenerated by `refresh`:
  - blue: the box to draw in
  - red: inside a frame, keep empty
  - grey: parts of the exemplar not drawn yet
  - faint white: parts of the exemplar already drawn
- **The box is where a glyphlette goes, not a limit on its ink.** Pixels drawn outside it,
  anywhere in the cell, are kept and overlaid wherever the glyphlette is placed (clipped to the
  body). Use this to bridge joints, e.g. the hook of 小 reaching up through the gap to join 幺.
  Parts that are already drawn appear as faint context, protrusions included, so you can line
  them up. `status` counts glyphlettes with protrusions.
- A glyphlette may also use less than its box. The box is the space available, not a size to
  fill.

## How the model works

### 1. Model (`model.py`, `model/han_model.tsv`)
- For every target character and, recursively, every component it uses, the builder picks one IDS
  from BabelStone in region order `GHTKJPVUSBXZ`. G (China) comes first to match the font's
  Chinese-style Han.
- Anonymous sub-expressions that match an encoded character's IDS are renamed to that character,
  so a shared sub-part is recognised as one component wherever it appears.
- Stroke counts come from Unihan `kTotalStrokes` (zh-Hans value) or are estimated. Unencoded shapes
  take the median of "owner strokes minus sibling strokes" across their users.
- Commonness tier:
  1. Everyday: GB2312 L1, Big5 L1, Jōyō, Korean education hanja, HK grade list, Hanyu Pinlu.
  2. Common: GB2312 L2, Big5 L2, IICore, JIS X 0208, Jinmeiyō.
  3. The rest of the URO, plus Ext. A characters in UnihanCore2020.
  4. The rest of Ext. A.
- Flags:
  - `radical`: Kangxi radicals and their radical-block forms. Informational only: radicals are not
    indivisible units of drawing, and are split or kept whole like any other component.
  - `ivi`: approximate IDS.
  - `unencoded`, `unrepresentable`, `override`.

### 2. Geometry (`geometry.py`, `model/layout.tsv`)
- **Body:** 15×15 at (0,1) in the 16×16 cell. Column 15 and row 0 are spacing. With the engine's
  2px offset the body covers rows 3–17 of the 20px line, matching Hangul.
- **Odd boxes:** every box is odd-sized. Splits leave a 1px gap and produce odd parts
  (3/5/7/9/11/13), so every box has a centre column and a centre row for symmetric components.
- **Size model (`Layout.levels`, `Layout.demand`):** a component's shape is counted in parallel
  stroke *levels*. Rows are stacked horizontal elements and columns are side-by-side vertical ones;
  dots and slants count half, and turning strokes count one of each.
  - Levels add up along a split (rows down ⿱⿳, columns across ⿰⿲) and take the maximum across
    it. Surround walls add levels around the inner part.
  - The minimum size along an axis is 2 × levels − 1 px, and at least 3px for anything with two
    strokes. Parallel strokes need gaps; strokes at right angles can touch. So 言 (亠 + 二 + 口) is
    3×10: five and a half rows but two columns.
  - Every component is measured through its structure, radicals included. Aliases (訁 = 言,
    𠄠 = 二) take their target's shape.
  - Atoms the IDS doesn't decompose (幺, 灬, 攵…) take their shape from the `levels` column of
    `layout.tsv` (rows,columns). Failing that, they use CNS 11643 stroke-type counts: horizontals
    are rows, verticals columns, turns one of each, dots and slants half. Failing that, a
    stroke-count guess. Hand-set shapes win because counts can't tell arrangement: 氵 is three
    dots stacked, 灬 four in a row.
- **⿰⿲ / ⿱⿳ split sizes:**
  - A part's preferred size (`layout.tsv` left/right/top/bottom, given for a 15px box and scaled)
    is used first.
  - Remaining space is shared by each part's minimum size *along the split axis*. So 䜌 (three
    columns: wide but short) and 弓 (stacked horizontals) split 9:5 in height, not 11:3 as stroke
    counts would suggest.
  - The closest all-odd partition wins; ties give the later part more space.
  - Twins split evenly, including positional forms of one component (林, 絲 = 糹糸).
    Outer twins of ⿲/⿳ are symmetric (木缶木, 糹言糸). The forms are listed in `VARIANT_FORMS`.
- **Frames:** the inner box comes from insets (`layout.tsv` frame column, or the operator default).
  Insets shrink with the box but never below 2px, since frames are made of strokes.
- **Never split:**
  - components with fewer than 5 strokes;
  - ⿻ ㇯ ⿾ ⿿;
  - approximate IDS;
  - splits with a bare single-stroke part;
  - splits into an unencoded fragment used by fewer than 3 components (e.g. "bottom of 年").
- **Crowding:** a component's structural minimum size is `demand`, and crowding = demand / box.
  Parts whose size is set in `layout.tsv` are exempt; the others are *free*.
  - Several free parts: none may exceed `MAX_CROWDING` (1.25), a fixed limit. Crowded material stays
    in one piece, because drawn whole the artist can merge gaps and share strokes.
  - A single free part (e.g. 彎 beside 氵): it may inherit the parent's crowding × 1.25.
- **Consistency:** a component is only split in a box if it is also split in every roomier box
  (checked +2px at a time). A tighter box never gets cut into more pieces than a roomier one, so
  灣, 彎 and 變 treat 䜌 alike.

### 3. Planner (`planner.py`)
- **Cost:** drawing a glyphlette costs `3 + strokes`. Every joint between glyphlettes in every
  assembled character is a quality risk and costs extra (`Layout.joints` classifies them):
  - `JOINED_JOINT_COST` (1.5) for a joined joint: a vertical stroke reaches a stacked (⿱⿳)
    boundary from either side, so the parts must meet via protrusions that line up in every
    character (糸: the hook of 小 meets 幺). Rule of thumb: horizontals tend to stay separate,
    verticals tend to join.
  - `JOINT_COST` (0.5) for a separate joint: side-by-side and surround joints, and stacked joints
    faced only by horizontals, dots and slants (言: 亠 / 二 / 口).
  - Which kinds of stroke reach each edge (`Layout.edges`) comes from the structure. A stroke's
    first and last segments give its top and bottom edge (HZ: horizontal on top, vertical at
    the bottom). Atoms count as box-shaped.
- **Collapse:** start from the deepest cut. Visiting slots smallest first, a slot becomes a single
  glyphlette when the glyphlettes used *only* beneath it, plus the joints it removes, cost at least
  as much as drawing it. So slots assembled in many characters get drawn whole (糹@5x15 in 485
  characters, 䜌@15x9 in 17), while rare assemblies stay assembled. Passes repeat until nothing
  changes. Issued glyphlettes cost 0, so re-planning builds on existing work.
- **Consistency:** a component drawn whole in a box is drawn whole in every smaller box too
  (smaller only means more crowded). 䜌 is drawn whole in 變 and 彎, so it's whole in 灣 as well.
- **Generated slots** (`generators.py`) cost nothing and never enter the worklist.
- **Size reuse:** a drawing may serve slots of the same component up to `SIZE_TOLERANCE` (2) px
  larger per axis, centred in them. Frames and whole characters are exact.
  - Per component, issued drawings serve what they can first.
  - Then sizes are chosen in order of use: the most-used sizes are drawn exactly, and each serves
    the rarer sizes just above it (`choose_drawings`).
  - `plan.cover` maps each slot to the glyphlette drawn for it.
- `show` prints why each unsplit slot is whole (the geometry's reason, or the planner's), and
  whether a slot is drawn, generated, or reuses another size.
- **Drawing order:** greedy. The next glyphlette is the one with the highest
  Σ(tier weight / missing parts of each character using it) / cost. Tier weights are
  100 / 20 / 4 / 1.
- **Knobs:** `OVERHEAD`, `JOINT_COST` and `JOINED_JOINT_COST` (higher means more and bigger whole
  pieces), `WHOLE_BIAS`
  (collapse even when it costs up to this much more), `TIER_WEIGHT`.
- Plans are deterministic: slots are discovered in a fixed order, and ㇯ ("A minus B", which refers
  to its own parent) is measured without recursion. `check` reports any cyclic decomposition.

Current numbers (G preference, default rules):

| | |
|---|---|
| Glyphlettes to draw for all 27,584 characters | 7,532 (effort 18% of drawing every character whole) |
| Generated instead | 473 glyphlettes of 27 components, serving 10,358 slots; 144 characters need no drawing |
| Reusing a drawing up to 2px smaller | 2,114 slot sizes |
| First 1,000 glyphlettes complete | 3,720 of 7,071 everyday characters |
| First 3,000 complete | 6,221 everyday characters |
| All everyday characters | complete by glyphlette 4,686 |

### 4. Assembler (`assembler.py`)
- Glyphlettes come from a `Library`, in order of preference: a hand drawing of exactly the slot,
  a generated glyphlette, or a hand drawing up to 2px smaller, centred.
- Each character is built from the **shallowest** cut whose glyphlettes are all available, so a
  larger drawn piece always wins over its parts.
- The parts' drawings, protrusions included, are OR-ed together at their box positions, then
  clipped to the body.
- **Bridging:** in a joined joint's gap row, where a vertical stroke on one side meets ink on the
  other and both touching parts are generated, the gap pixel is filled (十 meets 日 in 早).
  Hand-drawn parts bridge with their own protrusions.
- U+4DC0–U+4DFF (Yijing hexagram symbols, between Ext. A and the URO in the sheet) are generated
  procedurally from the King Wen sequence.
- Output: `out/juxing.tga`, 4096×1728, 256 columns of 16×16 cells from U+3400, white on
  transparent. Header and pixel conventions are identical to `wenquanyi.tga`.

## Switching the font over from WenQuanYi

`juxing.tga` is a drop-in replacement for `wenquanyi.tga`. To switch:
1. `make install`.
2. Replace `"wenquanyi.tga"` with `"juxing.tga"` in the file lists of:
   - `src/net/torvald/terrarumsansbitmap/gdx/TerrarumSansBitmap.kt`
   - `OTFbuild/sheet_config.py`
   - `Autokem/sheet_stats.py`
3. Delete `src/assets/wenquanyi.tga` and `work_files/wenquanyi_addendum.kra`.
4. Update the WenQuanYi credit in `README.md`.

Characters that are not assembled yet are blank in the sheet.

## Debugging

- `python3 juxing.py show <chars> [--full]`: the slot tree, each unsplit slot's reason for staying
  whole, the planned glyphlettes as a 16×16 letter map, and drawn state.
- `python3 juxing.py check`: every reachable slot stays inside its parent and split parts don't
  overlap. It also finds layout rules for components the model doesn't have, even-sized frame
  interiors, registry cells that don't fit, and duplicate hexagram symbols.
- `out/worklist.tsv`: the full drawing order, with uses, effort, example characters and
  cumulative completion per tier.
