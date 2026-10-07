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
  - Chiron Hei HK (The Chiron Hei HK Project Authors, SIL Open Font License 1.1, no Reserved Font
    Name; derived from Source Han Sans) is measured: `reference.py` records the ink box of every
    part of every character (`model/reference.tsv`, with the font's attribution in its header).
    It is also traced, on request only, for the editor's *suggestions* (`suggest.py`, 3c): a
    suggestion is shown over the canvas for the artist to draw over, and is never written to a
    sheet by itself. A glyphlette drawn from a suggestion makes the font, in that
    part, a Modified Version of Chiron Hei HK, which the OFL allows (Terrarum Sans Bitmap is OFL 1.1
    too) provided its copyright notice goes with it: credit "Copyright 2025 The Chiron Hei HK
    Project Authors" (see Switching over). Nothing else of it reaches a sheet or the output.
- Label fonts (Noto Sans CJK, BabelStone Han) are used only to caption guide images and mock-ups.
  Nothing rendered with them ever reaches a drawing sheet or the output.
- **The reference font is the visual target; the paper is the design.** How big a part is and
  where it sits come from Chiron Hei HK (measured, then inferred for every component). Lai, Yeung
  & Pong's paper is the source of the system's design: slots, splits and frames, the evenness
  metrics and search. The sizes, insets and positions `layout.tsv` once held were guesses after
  the paper, and the reference made most parts larger (亻 4.3px not 3, bottoms 7 not 5); it now
  holds hand settings only.

## Concepts

| Term | Meaning |
|---|---|
| component | A character, an unencoded shape such as `{12}` ("right of 龍"), or an anonymous IDS expression. Each has one decomposition, or none (an atom). |
| slot | A component placed in a box of a given size: `SlotKey(comp, w, h, role)`, written `青@11x15`. A slot's layout depends only on its key, so the same slot looks the same in every character. |
| frame | The outer part of a surround operator (⿴⿵⿶⿷⿸⿹⿺⿼⿽), drawn over the whole box with the inner area left empty, written `辶@15x15/⿺`. It is a different drawing from the plain component. |
| glyphlette | A slot that is drawn by hand. It can be a radical, a phonetic part, a large chunk, or a whole character. |
| family | The sizes one drawing serves: `(component, frame role, aspect class)`, where a box at least √2 times taller than wide is *tall*, at least √2 times wider than tall is *wide*, else *square* (`glyphlettes.family`). 木 on the left (dot for the last stroke), 木 standalone and 木 at the bottom of 呆 are drawn differently, so they are different families. |
| base glyphlette | The one drawing of a family, at its largest size or nearly (see Planner). |
| derived glyphlette | Any other size of a family, resized from the closest drawing of the family by `resize.py`. Never drawn unless overridden. |
| override | A hand drawing of one exact size of a family that already has a base. It replaces the derived version and serves the smaller sizes near it too. Needed where the resizer *declines* a size, or where you don't like its result. |
| generated glyphlette | A slot of a simple rectilinear component (口 日 目 白 工 土 …) drawn by code (`generators.py`). It is never on the worklist, and a hand drawing of the same slot always wins. |

### Generated glyphlettes (`generators.py`)
- Components: 口 日 曰 目 田 白 工 土 士 王 十 一 二 三 丨 山 中 由 甲 申 冂 凵 匚 冖 亠 宀 艹, plus the
  frames 囗 ⿴, 冂 ⿵, 凵 ⿶ and 匚 ⿷ (`SPECS`, `FRAMES`).
- **Sizing, from Chiron Hei HK (`Layout.ink_size`):** a shape takes the share of its box that
  the component's ink takes of its space in Chiron Hei HK, in boxes like it. A part's space
  runs to the middle of the gaps to its neighbours (and of a frame's inside, to the middle of the
  gap to each wall), so:
  - a touching part fills its box towards its neighbour (早's 十 meets 日);
  - a floating one keeps its margins: 口 left of 吃 叫 唱 is 5×11 in its 5×15 box, 口 over a
    15×3 box 11px wide, 山 on the left 5×13.
  - How it's computed: the weighted median over every character where the layout gives the
    component that box. Boxes of other sizes count less the further they are (`REF_SIZE_WIDTH`,
    1px), so rare boxes borrow from their neighbours. A measurement counts less the more ink its
    cut went through, and half if ambiguous (叶's 口, cut through 45% of the ink, hardly counts).
  - Rounding: the nearest odd width (at least 5 for enclosed shapes where the box allows: a
    3px 口 has a 1px counter) and the nearest height, centred in the box.
  - A slot's shape is shared by every character using it, so it is typical rather than exact.
    The evenness pass then gives each generated part the size Chiron Hei HK gives it in that
    very character (see Evenness).
  - Without a measurement (a failed segmentation, or the evenness pass asking for a shape), a
    shape fills its box as far as the aspect limits of its Spec allow, centred, keeping clear of
    the sides of wide boxes.
- **Bottom serifs:** the outer verticals of 口 囗 日 曰 目 田 白 山 中 由 甲 申 凵 匚 run past the
  bottom stroke. This is how these characters are made, not a serif of the typeface.
  - Never longer than 1px.
  - Dropped when the shape is squashed to 60% of its natural height or less (`SERIF_SQUASH`), so
    口 atop 古 or 日 atop 早 have none, and on shapes under 5 rows (`SERIF_MIN_H`), so a 4-row 口
    keeps a 2-row counter.
  - The serif takes the shape's own rows. If that leaves the strokes no room, the serif goes first.
  - A frame's serif needs its bottom inset to leave the stroke and a clear pixel above the inner
    box: 囗 ⿴ and 匚 ⿷ have 3 (inferred), so they keep theirs; 凵 ⿶ has 2, so it has none.
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
  reference.py     measures the parts of every character in Chiron Hei HK (model/reference.tsv)
  infer.py         infers the layout preferences from the measurements (model/inferred.tsv)
  geometry.py      layout engine: slot DAG, splits, frames, legibility rules
  planner.py       which glyphlettes to draw (cut optimisation, families) and in what order
  generators.py    procedural glyphlettes for simple components (口 日 田 ...)
  resize.py        stroke-preserving resizing: derives the other sizes of a family from its drawing
  suggest.py       suggestions: a glyphlette traced from Chiron Hei HK, for the editor (on request)
  glyphlettes.py   registry, drawing sheets (guides, ink), families, and the Library of available glyphlettes
  assembler.py     composes characters, writes the Han sheet, procedural hexagrams
  evenness.py      evenness pass: refines each assembled character (after Lai, Yeung & Pong)
  render.py        terminal diagrams, sheet indices, mock-ups, previews
  webapp.py        web app server (`serve`): JSON API over the same Context, standard library only
  web/             web app front end (index.html, app.js, app.css; no dependencies, no build);
                   about.html is the About tab, plain HTML for anyone to edit
  tga.py           TGA I/O
  sample_text.txt  text rendered into out/preview.png
  model/
    han_model.tsv  GENERATED model (committed so assembling needs no downloads)
    reference.tsv  GENERATED part boxes measured in Chiron Hei HK (committed likewise)
    inferred.tsv   GENERATED layout preferences: part sizes, frame insets, placement (likewise)
    overrides.tsv  hand corrections to decompositions (atoms, replacement IDS)
    layout.tsv     hand settings: override inferred.tsv value by value; stroke levels of atoms
  glyphlettes/
    registry.tsv   issued glyphlettes and their permanent cells; retired cells say '-' (committed)
    sheet_NN.tga   drawing sheets (committed; LFS)
  sources/         downloaded upstream data (ignored by git)
  out/             juxing.tga, worklist, indices, previews, derived.cache (ignored by git)
```

## Workflow

```bash
cd Juxing
make plan                          # what to draw, in which order -> out/worklist.tsv
python3 juxing.py show 清 灣 這      # how characters are laid out and what they need
python3 juxing.py mockup 清灣這林     # out/mockup.png: boxes with reference components squeezed in
python3 juxing.py generated         # out/generated.png + generated_chars.png: review the generated parts
# ... fix decompositions in model/overrides.tsv, `make model`, `make reference`, `make check` ...
make reference                     # after `make model`: re-measure the parts in Chiron Hei HK and
                                   # infer the preferences from them (~20s; `make infer` for the latter)
# ... where you disagree with the reference, set a value in model/layout.tsv (it wins) ...
python3 juxing.py reference --sheet 昌唇區   # out/reference.png: the measured parts, for checking

python3 juxing.py issue 256        # allocate the next 256 glyphlettes to glyphlettes/sheet_NN.tga
# draw them (Krita etc.), save as uncompressed 32-bit TGA
make refresh                       # redraw guides: drawn parts now appear as context in other cells
make status                        # progress, coverage per tier, ink outside boxes, declined sizes
python3 juxing.py derived          # out/derived.png: every derived size, worst family first
python3 juxing.py derived --score  # the resizer against the overrides you drew by hand
python3 juxing.py suggest 36 寸@7x15   # a glyphlette traced from Chiron Hei HK, beside the drawing
python3 juxing.py issue --key 木@5x15   # override a derived size you don't like (prefilled with it)
python3 juxing.py retire --orphans # free the blank cells the plan no longer uses
make                               # assemble out/juxing.tga + out/preview.png + out/coverage.png
make install                       # copy out/juxing.tga into ../src/assets
make serve                         # the web app on http://127.0.0.1:8765/ (all of the above, graphically)
```

**Tune the layout before issuing.** A glyphlette is identified by its component *and size*. If a
layout rule changes after a glyphlette has been drawn, the characters that used it now need a
size that may not be drawn. Usually it is simply derived from the drawing of its family, which
stays in use. An override whose size falls out of the plan stays in its cell as an orphan, which
`status` reports.

**Retiring orphans.** Cells never move: a cell's number is its place on the sheet, so a registry
line is never deleted. An orphan can be *retired* instead: its line says `-`, its ink and guides are
cleared, and the next glyphlette issued takes the cell.
- `python3 juxing.py retire --orphans` retires every blank orphan.
- `python3 juxing.py retire 81` (or `亻@7x15`) retires a named orphan; a drawn one needs `--drawn`,
  as its drawing is erased. A drawn orphan still serves as a source for resizing its family, and
  is needed again if a layout comes back to its size, so it is kept unless named.
- In the web app, an orphan's cell says so over the canvas, with a button to retire it (a drawn
  one takes a second click).
- Cells in the plan can't be retired.

**Draw one size, review the rest.** Draw the base glyphlettes the worklist issues, then run
`derived` and look at every size the resizer made from them (`out/derived.png`: drawings on blue,
derived sizes with their cost, declined ones in red, worst family first). Override the ones you
don't like with `issue --key`; declined ones are put on the worklist automatically.

Other issuing options:
- `issue --whole 鬱 龜`: draw these characters whole. The assembler always prefers the largest
  drawn piece, so this overrides any assembly you don't like.
- `issue --key 木@5x15 广@15x15/⿸`: issue specific glyphlettes. A generated one is prefilled with
  its generated ink, so you can restyle it; once on a sheet, your drawing replaces the generated
  one everywhere. A derived one (an override) is prefilled with the resizer's version, or with its
  best attempt if it declined, and is shown in a character that uses it.
- `issue 256 --group`: keep the glyphlettes of one component next to each other within the batch.

### Web app (`make serve`)

`python3 juxing.py serve [--host 127.0.0.1] [--port 8765]` serves a workbench over the same
Context as the command line:
- **Status:** the summary line, issued / drawn / blank / declined counts, characters assembled per
  tier, the plan's completion curve per tier, and a coverage map of U+3400–U+9FFF (click a code
  point to inspect it).
- **Sheets:** the drawing sheets with their guides; blank cells and overrides outlined. Click a cell
  to edit it. At 1×–4×; the sheet's card grows past the page's width, centred, as far as the sheet
  needs and the window allows, so it scrolls sideways only when the window is too narrow.
- **Editor:** a pixel editor for one cell, with the same guides as the sheets (box, keep-empty,
  undrawn and drawn context, the 15×15 body). Left button draws, right erases, shift-click draws a
  line; arrows move the ink, Ctrl+Z / Ctrl+Shift+Z undo and redo, Ctrl+S saves, `[` `]` `N` go to
  the previous, next and next blank cell. A live preview shows, with the unsaved ink, every size
  derived from the drawing (declined ones in red) and the characters that use it.
  - **Select (M):** drag a rectangle, then drag it (or nudge it with the arrows) to move the ink in
    it. The moved ink floats (light blue) until it is dropped (click elsewhere, Enter, Escape,
    another tool, saving), and it is *porous*: its blank pixels are holes, so it never erases the
    ink it lands on. Ctrl+drag or Alt+drag moves a copy; Delete clears the selection; Ctrl+A
    selects everything. A move is one undo step.
  - **Overriding a size:** click a size under "Sizes of this family" to draw it by hand. A size
    that isn't issued opens as a *draft* (`#edit/<key>`), starting from the resizer's version, with
    a line over the canvas: "You are overriding this size". Nothing is issued until the draft is
    first saved, so looking costs nothing and there are no confirmation popups. An issued override
    shows the same line. Drawn and issued sizes open their cells.
    A draft's "Used in N characters" (and the characters in its preview) are those it will serve
    once issued: its own size's, and those of the sizes it will then be the closest issued
    drawing of (as `choose_drawings` gives sizes to drawings), not those of the drawing it is
    derived from now (衤@5x11: 2 characters, not 297). Checked against re-planning with the size
    issued: exact.
  - **Suggest (T):** a suggestion traced from Chiron Hei HK (3c), shown as orange dots over the
    canvas to draw over, with the character it was traced from. It is never ink by itself. The
    toggle is remembered, so with it on every cell opens with its suggestion (about 0.15 s each,
    cached per glyphlette).
- **Families:** every derived size next to the drawing it comes from, worst family first, as
  `derived` draws it. Click a drawing to edit it, or a derived size to override it (a draft, as in
  the editor).
- **Worklist:** the drawing order, with what each drawing serves; issue the next N from here.
- **Text:** any text rendered with the glyphs as they are now; click a character for its IDS and
  parts (drawn, generated, derived from what, or missing), with links to edit or issue them.
- **About:** what Juxing is and how to contribute, for people new to it: how it works, the
  vocabulary, the drawing workflow and conventions (draw from scratch, 1px strokes, what the
  resizer keeps), the editor's keys, and the sources and licences. It is `web/about.html`; keep it
  in step with the tools. `data-px="##../..#."` draws a small bitmap there.

Pixel previews (thumbnails, characters) are framed by a margin of their dark pixel surface, so
white ink on their edge doesn't vanish into a light page.

Saving writes the cell into its `sheet_NN.tga` and regenerates that sheet's guides, as `refresh`
does (the sheet index PNGs are left to `refresh`). The app re-reads the registry and the sheets
whenever they change on disk, so Krita can be used alongside, but not on the same sheet at the
same moment: a save rewrites the whole sheet. The plan is recomputed lazily after a save (about 3
s, on the next Status or Families view). The browser asks before leaving or reloading only when
the editor has unsaved changes. There is no authentication; keep the default host.
- API: `GET /api/draft?key=K` (the editor's cell for a size: its registry cell, or a draft issued on
  its first save), and `POST /api/preview` and `POST /api/save` take `{key, ink}` for a draft as well
  as `{id, ink}` for a cell. `juxing.draft()` is where a glyphlette goes and what it starts with,
  shared with `issue`.

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

### 1b. Reference layouts (`reference.py`, `model/reference.tsv`)
The fourth source of truth. Unihan, BabelStone and CNS say what a character is made of and how
its strokes run; none says how big each part is or where it sits (昌: the upper 日 about 2px
narrower and 1px shorter than the lower). A well-made font does, so it is measured: it is
Juxing's visual target.
- **Font:** Chiron Hei HK, Regular: `sources/Chiron Hei HK/static/ChironHeiHK-Regular.ttf` (the
  Google Fonts download unpacked into `sources/`), else an installed Chiron Hei HK. Only it is
  looked for, since another design would be another reference. It has Source Han Sans's metrics
  (its parts measure within 0.1px of Source Han Sans's on average) with more uniform white space,
  and Hong Kong glyph forms, whose structure rarely differs from the model's (G-first) decompositions.
- Coordinates are in units of the font's ideographic character face (BASE `icfb`/`icft`), which
  maps onto the 15×15 body. The face is square: where BASE records disagree (Chiron Hei HK's `hani`
  record is 918 units tall and sits 36 below its ink), the one as tall as the face is wide is used.
- **Pieces of ink are contours.** Chiron Hei HK is a variable font, and variable fonts keep
  overlapping strokes as separate contours, so a contour is a stroke, or a few strokes drawn as one
  (口's three). Strokes that touch, which merge in rendered ink, stay apart as pieces (兰's right dot
  on its first bar, 讠's rising stroke on 周's 丿 in 调, 𥫗's dots on 立 in 笠). And the counts add
  up: a character has as many outer contours as its parts have on their own (训 7 = 讠 4 + 川 3,
  笠 11 = 𥫗 6 + 立 5), with a few exceptions (吕's upper 口 is one contour, a 口 on its own two). Each
  contour is rendered from the outline (unhinted, supersampled 4×, `Renderer.piece_masks`), with its
  counters cut out; the glyph's ink is their union.
- **Segmentation**, per character rendered at 160px, following the model's decomposition exactly
  as the geometry does (`Layout.decomposition`, `Layout.never_split`), so a measured node and a slot
  share their path (child indices from the root; a surround's frame is 0, its inside 1). A node
  holds its pieces (or the parts of them cut off for it):
  - ⿰⿲ are cut at columns, ⿱⿳ at rows. A cut costs, per piece, the lesser of its ink left on the
    wrong side and cutting it (`CUT_CHARGE` plus its ink on the cut line), so clean gaps win. A
    prior pulls cuts halfway between where the geometry would cut and where the ink divides by
    stroke counts, which picks the right gap of several (謝: between 言 and 身). The geometry here
    uses hand settings and the size model only, never sizes inferred from an earlier measurement,
    which would lead a wrong cut into the same wrong place the next time (so re-run `make
    reference` after changing a size in `layout.tsv`). No part may get less than `MIN_PART_INK`;
  - **counts:** each part should get as many pieces as it has on its own (`expected_pieces`: the
    font's count if it has the part, else the sum over the part's own parts), a piece counting on
    the side holding most of it, and every piece more or fewer costs `COUNT_COST`. A cut in the
    wrong gap gains or loses a stroke, so this finds the boundary where a neighbour's stroke reaches
    under a part (训 釧 馴: 川's first stroke under the left part; 勒 凱: 力's and 几's 丿). As counts
    are not always exact, it weighs against the other costs rather than deciding;
  - a piece goes whole to the part holding most of it, unless cutting it costs less than leaving
    its ink on the wrong side, as the search reckons it: then it is cut along the cut (the stem
    出's two parts share). A stroke reaching across with a little of its ink stays whole (全: the
    tail of 人's 丿 beside 王);
  - **bounds:** where the parts of a split divide, the line leaving the least of either part's ink
    on the other's side, at the middle of the run of lines leaving as little: the middle of the
    gap where they are apart, between their bodies where a stroke of one reaches under the other
    (鳴: 鳥's foot under 口; the middle of their boxes would give 口 3px). Stored per split node and
    used for splits and spaces (`Layout.reference_split`, `geometry._child_spaces`) instead of the
    middle between boxes, except where a stacked part reaches up under the one above;
  - a surround's inside is a rectangle whose four edges are searched the same way, starting from
    the operator's default insets or a hand setting in `layout.tsv` (never from inferred ones, so
    a measurement doesn't depend on the one before), open sides from the node's edge (勹's 丿
    closes its "open" left). Its share of the ink is pulled towards its strokes' share, scaled by
    its size (唇: 口 against 辰). The frame's walls are not cut unless the inside touches them
    (石 右 房);
  - `cut` records the share of the parent's ink that had to be cut, `amb` whether another split
    was nearly as good (鬱: below 冖 or inside 鬯?). Branches whose split fails are left out;
  - `walls`, for a frame: where its ink comes closest to the inside on each walled side, within
    the inside's rows or columns. A frame's box says little where its walls slant: 广's 丿 reaches
    the left edge only at its tail, well below the inside's top.
- **Coverage:** all 27,584 characters, 139,536 parts; 0.5% of splittable nodes fail, 88% of
  the rest split cleanly (no ink cut) and 9% are ambiguous. Cutting rendered ink instead, as
  before contours, failed 1.1% and split 61% cleanly, 32% ambiguous: wherever strokes touched,
  the cut had to go through them, and often went through the wrong ones (调: 周's 丿 to 讠; 笠: 立's
  亠 to 𥫗; 兰: the cut under the first bar). Families measure more consistently too: 忄 is 5px wide
  in 89% of its characters (64% before), 阝 83% (60%), 扌 82% (71%), 釒 71% (61%).
- **What the font's structure says:** the decomposition is the model's (IDS), not the font's;
  the font has no table of its own (no composite glyphs, no `VARC`). Where the font draws a
  different structure, the decomposition is corrected in `model/overrides.tsv`: 兰 = ⿱䒑二, its
  dots on the first bar as in 羊 前 养 (Chiron Hei HK's and the G form).
  `reference --sheet` draws the measured boxes for review.
- **Space:** a part's space in the reference (`geometry._child_spaces`) is the share of its
  parent's space that is its own: a split divides at the middle of the gaps between the parts'
  ink; a frame's inside runs to the middle of the gap to each wall, a pixel clear of the wall at
  least (床: 木 nests under 广's 丿, all but touching it), and to the parent's space on open sides.
  Sizes are measured against it, so a touching part fills its space and a floating one doesn't.
- **Used by:**
  - the geometry, for split sizes, and through `inferred.tsv` for everything else (1c);
  - the generators, for the sizes of generated shapes (`Layout.ink_size`);
  - the evenness pass, for a target box for every part, and the start of generated parts;
  - the assembler, which doesn't bridge a joint Chiron Hei HK separates cleanly.

### 1c. Inferred preferences (`infer.py`, `model/inferred.tsv`)
What the geometry and the evenness pass need to know about a component wherever it appears,
inferred from every measurement of it (`make infer`, also run by `make reference`; under 1s).
Each value is a weighted median; a measurement counts less the more ink its cut went through,
and half if ambiguous; values with less than 3 measurements' worth (`MIN_WEIGHT`) are left out.
- **left/right/top/bottom:** a component's size as the first or last part of a split, in px of a
  15px box: its space's share of the parent's space. Twins are left out (they split evenly). E.g.
  亻 4.2, 氵 3.9, 扌 4.9, 讠 5.0, 刂 right 5.8, 艹 top 3.4, 宀 top 4.9, 心 bottom 5.6, 貝 bottom 10.7.
- **frame:** a surround frame's insets at 15×15: its inside's space, rounded so that the inner
  box is odd-sized and walls get 2px at least. Only frames 11px or larger count (`MIN_FRAME`):
  insets shrink with smaller frames, their strokes don't (a nested 囗 5px wide spends half its
  width on walls). E.g. 囗 ⿴3,3,3,3, 門 ⿵3,6,3,0, 冂 ⿵4,2,4,0, 广 ⿸4,4, 辶 ⿺6,0,0,2; 78 frames in
  all, the rest keep the operator's default.
- **place:** where a component's ink sits in its space, per context, for the parts the evenness
  pass has no measurement of in their character.
- **Not inferred: `levels`.** It says how many 1px strokes a component needs at 15px, which a font
  drawn at high resolution doesn't tell: counting strokes along scan lines of its rendering counts
  every crossing of a curve (乡 5 rows, 飠 7, where the design counts slants half). The levels of
  atoms stay hand-set in `layout.tsv`, as structure rather than appearance.
- `layout.tsv` overrides any inferred value, value by value (`Layout.hand` over `Layout.inferred`,
  merged in `Layout.rules`).

### 2. Geometry (`geometry.py`, `model/inferred.tsv`, `model/layout.tsv`)
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
  - Overlaid parts (⿻) interleave, so their levels add up: 十 = 一 over 丨 is one row and one
    column, 由 = 日 through 丨 three and three, so 田 (囗 around 十) is 5×5. (A guess from the stroke
    count used to make 十 2×2 levels and 田 7×7.) Where strokes coincide this overcounts (東), on
    the side of legibility.
  - Atoms the IDS doesn't decompose (幺, 灬, 攵…) take their shape from the `levels` column of
    `layout.tsv` (rows,columns). Failing that, they use CNS 11643 stroke-type counts: horizontals
    are rows, verticals columns, turns one of each, dots and slants half. Failing that, a
    stroke-count guess. Hand-set shapes win because counts can't tell arrangement: 氵 is three
    dots stacked, 灬 four in a row.
- **⿰⿲ / ⿱⿳ split sizes:**
  - A size set by hand in `layout.tsv` (left/right/top/bottom, for a 15px box, scaled) comes first.
    A least size (`>=7`) only rules out the partitions that go below it, and a most size (`<=4`)
    those above it; the reference decides within. They are design settings, for what the pixel
    form needs where the reference can't say:
    - tops that never stand tall: 𥫗 艹 丷 `<=4` (3 rows), 䒑 `<=5` (兰: Chiron sets 二 well
      below its 䒑, 6.5px), 宀 `<=5` (generated 4 rows tall at most, a 1px dot and legs of 3, while
      Chiron draws the radical 穴 with a long dot and legs, 6.9px, which left 3 empty rows over 八);
    - one width for a family where the reference sits on a rounding tie: 讠 measures about 4.5px
      on the left, between 3|11 and 5|9, and `讠 5` keeps all 151 ⿰讠 characters at 5|9 (24 would
      be 3px wide). 亻 (4.3) is on the same tie: 3px in 418 characters, 5px in 290;
    - 川 `>=7` on the right (训 5|9, 釧 馴 7|7) was set while the reference cut inside 川 where its
      first stroke sweeps under the left part; measured by contours, those characters come out so
      by themselves, and it changes nothing now.
    These settings were added while the reference was cut from rendered ink, and some answered
    its mis-cuts (调 笠 答 兰 釧); measured by contours, only their design remains.
  - Then the split falls where Chiron Hei HK puts it (`Layout.reference_split`: where the parts
    divide, the measured bounds). A whole character follows its own measurement; a component
    inside others the weighted median of its measurements wherever it appears, weighted by how
    close their aspect is to the box's (`REF_ASPECT_WIDTH`), its standalone form triple.
  - Where the parent isn't measured, its first and last parts take their inferred sizes
    (`inferred.tsv`), and the rest share the remaining space by each part's minimum size *along
    the split axis*. So 䜌 (three columns: wide but short) and 弓 (stacked horizontals) split 9:5
    in height, not 11:3 as stroke counts would suggest.
  - The closest all-odd partition wins; ties give the later part more space. Legibility is a
    limit, the reference a target: the closest partition that leaves no part too crowded (see
    Crowding) wins, rather than the split being refused (䱂: 魚 7px beside 幼, not the 5 the
    reference's proportions round to, which is too narrow for its strokes).
  - Root splits are 0.54px on average from where the reference divides each character (its
    measured bounds), and 555 of 24,112 are more than 1.5px off; with the paper's sizes in
    `layout.tsv` it was 0.80px and 3,087 (measured then between boxes). Parity limits it: odd parts
    with a 1px gap put boundaries 2px apart.
  - Twins split evenly, including positional forms of one component (林, 絲 = 糹糸).
    Outer twins of ⿲/⿳ are symmetric (木缶木, 糹言糸). The forms are listed in `VARIANT_FORMS`.
- **Under a roof:** a stacked part may reach up into the part above it, between its legs (宙: 由's
  stem rises between the legs of 宀, so 田 gets 8 rows instead of 6). Where the reference has the
  lower part's ink reach into the ink above (`reference_overlaps`: a whole character by itself, a
  component by the weighted median), the upper part keeps its full height, legs included, and the
  lower part's box starts 2px higher (4px from 3px), overlapping the box above (`Slot.overlaps`).
  - How far it must reach: under a *roof*, half a pixel (`ROOF_OVERLAP_MIN`: the part's first row
    then shares a row with the legs' tips, as in the reference); anywhere else 1px (`OVERLAP_MIN`).
    Roofs are set by hand, `roof` in the frame column of `layout.tsv`: 宀 and 冖, whose legs stand
    at the sides with room between them. A top ending in a roof counts as one (the ⺍冖 of 学 觉,
    the ⺌冖 of 常 党, the 艹冖 of 营). 艹's stems stand in the middle, so it is no roof: at half a
    pixel, 256 parts would reach up between them.
  - Parts whose top is too wide for the legs stay below, as in the reference (冠: 元, 0.3px).
  - A part reaching up under a roof may not start out touching it (the evenness pass steps it down
    within its taller box until it is clear), as its ink does not always fit between the legs.
  - Counted over the distinct slots of every character's layout, 1,212 stacked parts reach up
    this way, 399 under roofs (宀 213, 冖 89 in 军 冥 冢 冝 冤, 学 觉 …), the rest where they reach
    a whole pixel: under 大 62 (奔), 𥫗 47, 𠂉 42, the roof of 榮 勞 40, 龹 30, ⺈ 29, 穴 28, 八 27,
    艹 20, 𠆢 17 (企 伞), and others. (Measured from rendered ink, before contours, there were 929
    and 292: cut strokes hid much of the nesting.)
  - Such a joint is not joined (the legs stand beside the part below), and part spaces
    (`_child_spaces`) overlap likewise, so a generated part fills its taller box.
- **Frames:** the inner box comes from insets: set by hand, inferred (1c), or the operator default.
  Insets shrink with the box but never below 2px, since frames are made of strokes. They are the
  same for every character using the frame, since the frame's glyphlette is shared; where the
  inside is then too crowded (馬 in 風's 5px inside in 䬚), the character is drawn whole.
- **Never split:**
  - components with fewer than 5 strokes;
  - ⿻ ㇯ ⿾ ⿿;
  - approximate IDS;
  - splits with a bare single-stroke part;
  - splits into an unencoded fragment used by fewer than 3 components (e.g. "bottom of 年").
- **Crowding:** a component's structural minimum size is `demand`, and crowding = demand / box.
  Parts whose size is set by hand in `layout.tsv` are exempt (legible by declaration; a size
  measured in the reference says nothing about legibility at 15px); the others are *free*.
  - Several free parts: none may exceed `MAX_CROWDING` (1.25), a fixed limit. Crowded material stays
    in one piece, because drawn whole the artist can merge gaps and share strokes.
  - A single free part (e.g. 彎 beside 氵): it may inherit the parent's crowding × 1.25.
- **Consistency:** a component is only split in a box if it is also split in every roomier box
  (checked +2px at a time). A tighter box never gets cut into more pieces than a roomier one, so
  灣, 彎 and 變 treat 䜌 alike.

### 3. Planner (`planner.py`)
- **Cost:** counted per family. The first size of a family costs `3 + strokes` (its base
  glyphlette), every further size `DERIVED_SHARE` (0.2) of that: the risk that it is declined or
  needs an override. Every joint between glyphlettes in every assembled character is a quality
  risk and costs extra (`Layout.joints` classifies them):
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
  glyphlette when the family effort it adds is at most what it saves: the joints it removes, plus
  the sizes (or whole families) used *only* beneath it. So slots assembled in many characters get
  drawn whole (糹@5x15, 䜌@15x9), while rare assemblies stay assembled. Once a component has a
  base, drawing it whole at another size is cheap, so components tend to stay whole. Passes repeat
  until nothing changes. Families with an issued drawing cost nothing to start, so re-planning
  builds on existing work. A blank issued cell whose size no character's layout has any more (the
  layout changed after it was issued: 讠@9x15, issued while 训 was mis-measured) is passed over: its
  family gets a base at a size in use, and the cell shows as an orphan in `status`. A drawn cell
  is kept whatever its size: it still serves as a source for resizing.
- **Consistency:** a component drawn whole in a box is drawn whole in every smaller box too
  (smaller only means more crowded). 䜌 is drawn whole in 變 and 彎, so it's whole in 灣 as well.
- **Generated slots** (`generators.py`) cost nothing and never enter the worklist.
- **Drawings per family (`choose_drawings`):**
  - Sizes are derived from the issued drawings of their family where they can be, closest first
    (the smallest drawing at least as large on both axes, else one up to `SIZE_TOLERANCE` (2) px
    smaller). A blank drawing is assumed to work; a drawn one only if the resizer accepts the size.
  - Sizes no issued drawing reaches get one new drawing, the base: the most used of them that can
    serve all the others (氵@3x15 serves 氵@5x15, stretched), else the smallest size covering them
    all (木@9x15 serves 木@5x15). Whole characters (15×15) are always drawn.
  - Sizes declined by every drawing that could serve them get overrides, largest first; each
    serves the declined sizes it can.
  - `plan.cover` maps each slot to the drawing it comes from; `plan.declined` lists the drawings
    that declined a slot; `plan.summary()` is the one-line account below.
- `show` prints why each unsplit slot is whole (the geometry's reason, or the planner's), and
  whether a slot is drawn, generated, derived (from which drawing, at what cost) or declined.
- **Drawing order:** greedy. The next glyphlette is the one with the highest
  Σ(tier weight / missing parts of each character using it) / cost. Tier weights are
  100 / 20 / 4 / 1.
- **Knobs:** `OVERHEAD`, `JOINT_COST` and `JOINED_JOINT_COST` (higher means more and bigger whole
  pieces), `DERIVED_SHARE` (lower means more sizes derived per base), `WHOLE_BIAS` (collapse even
  when it costs up to this much more), `TIER_WEIGHT`; `glyphlettes.FAMILY_ASPECT`.
- Plans are deterministic: slots are discovered in a fixed order, and ㇯ ("A minus B", which refers
  to its own parent) is measured without recursion. `check` reports any cyclic decomposition.

Current numbers (G preference, default rules), as `plan` prints them:

> 4283 base glyphlettes (6169 glyphlettes derived; min 0, 25% 0, 50% 0, 75% 2, max 14 per base
> glyphlette; 84 glyphlettes overridden)

| | |
|---|---|
| Glyphlettes to draw for all 27,584 characters | 4,283 bases, plus the overrides the resizer asks for (effort 13% of drawing every character whole) |
| Derived instead of drawn | 6,169 slot sizes |
| Generated instead | 465 slot sizes of 26 components |
| All everyday characters | complete by glyphlette 2,467 |

(With the paper's sizes and insets in `layout.tsv` and the reference used for splits only: 4,414
bases, 12%, everyday characters complete by glyphlette 2,703. With split sizes from demand alone,
before any measurements: 4,142 bases, 11%, everyday complete by glyphlette 2,641.)

(Before families, drawing every size exactly, it was 7,544 glyphlettes, 18%, and everyday
characters complete by glyphlette 4,698.)

### 3b. Derived sizes (`resize.py`)
Glyphlettes are 1px line drawings, so they are resized by mapping whole rows and columns, never
by resampling. Every source row goes to one target row, in order: shrinking merges adjacent rows
(OR), growing inserts a row through which verticals continue and across which slants take a step.
Columns likewise, independently. Strokes stay 1px wide.
- **A stroke stays a stroke:** a diagonal step stays diagonal. `##.. / ..#. / ...#` in 3 columns
  is `#.. / .#. / ..#` (the first run a pixel shorter), never `##. / .#. / ..#` (an L, "kink")
  nor `##. / ..# / ..#` (the step turned down). This is what the user's 氵 overrides show
  (`glyphlettes/sheet_00.tga`, cells 64–74: every size free of Ls, dots keeping their head and
  step), and they are the test suite for changes here (`derived --score`, below).
- **Search:** per axis every choice is tried (a box is at most 15px) and scored:
  - `spacing`: gaps between consecutive rows with features (horizontal runs, stroke ends) away
    from proportional, and the margins likewise, so 目 stays even and empty margins go first;
  - `place`: features away from their proportional position (weak; keeps the balance);
  - `collide`: rows with horizontal runs merged, or brought together by merging the rows
    between them (the horizontal of 木 onto the crossing below it);
  - `short`: a run shrunk to one pixel, in proportion to the pixels lost. `W_SHORT` (3) for a run
    with something on its side (the crossing of 撇, stem and 点 in 木, a corner) or a tick on its
    own; `W_STEP` (1) for a step of a slant, which is how a slope is rasterised; `W_HEAD` (2) for
    the step a stroke starts or ends with (the head `##` of a dot). Otherwise alike, lower and
    further-right steps go first (`EARLIER`): strokes mostly start at the top or the left;
  - `tail` (stubs keep their extent: a 1px serif is neither lost nor grown), `jog` (a slant
    stepped by an inserted row), `mirror` (a lopsided choice for a symmetric drawing);
  - hints, which only rank the maps since the other axis may undo them: the diagonal steps a
    merge crosses, the Ls it makes, the diagonal stroke ends it flattens.
- **Acceptance:** the cheapest row and column maps are combined, cheapest first. A result must keep
  the topology: strokes that are apart stay apart (8-connected components) and counters stay open
  (Euler number). It may not add 2×2 clumps. Then, on the result:
  - **unkink:** an L the maps made where the source stepped diagonally loses its corner pixel
    (`W_HEAD`), unless the source has an L there too (口's corners), the pixel is inside a straight
    run (女's bar where slants cross it), or the topology or a stroke end would suffer;
  - `W_ENDS` (6) for every stroke end lost, `W_KINK` (5) for every L left over;
  - `W_TURN` (3) for every diagonal step whose image is straight (merged across on one axis only),
    `W_TAIL` (4) for every diagonal stroke end that no longer leaves in its direction. These two
    rank shapes but don't decline a size: in a tight size they are often unavoidable (阝 3px wide
    can only be `#.#`).
  - The best result is declined if the rest costs more than `MAX_COST` (12).
- **Stroke by stroke:** a drawing of separate strokes (氵 冫 讠 灬: 8-connected parts, all ink inside
  the box, not left-right symmetric) is resized part by part (`_derive_parts`). Each part is resized
  on its own into a box near its proportional size, a pixel either way (`W_SIZE`), so a small part
  may keep its shape rather than its proportions (a dot in a 3px-wide 氵 is `##. / ..#`, not a
  slash three rows tall). Then the parts are placed near their proportional places (`W_POS`) by a
  beam search: they may not touch, keep their order along each axis, keep the gaps between facing
  parts in proportion (`W_GAP`), and pay `W_NEAR` for every pixel newly in line with a pixel of
  another part a single pixel away (two stroke ends lined up so read as one broken stroke).
- **Frames** resize band by band (the inset bands and the inner band separately), so the inside
  of the frame lands on the inside of the target. Protrusions keep their distance from the box.
- **Speed:** tens of ms a size (stroke by stroke: a resize per part and candidate size). Results
  are cached in `out/derived.cache`, keyed by the drawing's ink, so only new or changed drawings are
  resized again. Bump `VERSION` after changing the method.
- **Knobs:** the `W_*` weights, `EARLIER`, `PART_SIZES`, `BEAM`, `MAX_COST`, `TAIL`, `MIRROR_MIN`.
- **Testing:** `python3 juxing.py derived --score [components] [-v]` resizes every size drawn by
  hand from its family's largest drawing and compares: pixels that differ, Ls added. Against the
  user's 氵 overrides (from 氵@9x15): no Ls added (the method before this added 36) and 80 pixels
  off in all (87 before); 7×15 is one pixel off, 3×11 four. Over the planned sizes of the drawn
  bases, the Ls went from 40 in 20 of 45 sizes to none, except in two drafts declined for them
  (女 5×9 and 5×11, which the method before accepted with their Ls).
- **Typical behaviour:** reductions to about 70% per axis are usually clean. 3px-wide versions of
  complex components and big square shapes squeezed to 5–7px are usually declined, and need an
  override. Where a dot has no room to stay apart from its stroke (忄 3px wide), the drawing is
  resized as a whole.

### 3c. Suggestions (`suggest.py`)
A starting point for drawing, not a replacement for it: a glyphlette traced from Chiron Hei HK,
on request (the editor's Suggest, `juxing.py suggest`). See the ground rules for what taking one
means under the OFL.
- **The part's ink:** characters using the slot (the exemplar first, then the typical users of
  its drawing, then those of the other drawings of its family, nearest size first) are segmented
  exactly as `reference.py` segments them (`reference.measure(..., masks)`), and the part's ink
  taken at the slot's path (or its component's nearest size in the same role).
- **Which one:** characters are traced until `TRIES` (8) are clean, from `LOOK` (16) at most, and
  the one that agrees best with the others wins: the medoid of the traces (the share of either's
  ink not within a pixel of the other's), each weighed by its cut's quality, halved for every
  piece of ink more than the part has on its own (`EXTRA_PIECE`), plus `OWN_QUALITY` of its own
  uncleanness. The reference's mis-cuts are why: a cut in the wrong clean gap looks clean (滫: 氵
  with 脩's 亻), and a rare size (氵@7x15, 7 characters) may have few good examples, so its
  family's are used too.
- **Where:** the part's space (`geometry._child_spaces`) maps onto the slot's box, except along an
  axis where its ink fills `FILL` (80%) of its space or more: there the ink fills the box, as
  drawn glyphlettes do (月 釒 隹 7px wide in 7×15), while a floating part keeps its margins (口
  left of 吃). A frame keeps its space, and its inside maps onto the frame's inner box.
- **Strokes:** the ink is thinned to 1px centrelines (Zhang–Suen), spurs shorter than a stroke is
  wide are pruned, and stroke ends carried on by the half stroke width thinning eats.
- **Grid fitting**, as hinting does: straight runs of the centrelines along an axis (a structure
  tensor over `WINDOW`, within `STRAIGHT` degrees, `COHERENT`) are stroke levels; levels within
  `SAME_LEVEL` are one (a bar crossed by a stem). They get whole rows (columns) by dynamic
  programming: each near its place, in order, with a blank between levels facing each other
  (`CROWD_COST`, `MERGE_COST`), a frame's walls a gap clear of its inner box. Everything else is
  warped with them, piecewise linearly. A stroke ending within `EDGE` outside the box ends on its
  edge, and nothing goes outside the body.
- **Drawn** 1px: a diagonal step that came out as an L loses its corner (not the corner of
  straight strokes), and 2×2 clumps lose a pixel nothing else needs.
- **How close:** against the 114 drawn glyphlettes (each traced as if blank), 99% of a
  suggestion's ink is within a pixel of the drawing and 97% of the drawing's within a pixel of the
  suggestion; 60% of the drawn pixels are exactly right. Most misses are a stroke a pixel off,
  which the porous Select moves in one go, and matters of style: the shape of 氵's dots, the hook
  of 扌, the foot of 木.

### 4. Assembler (`assembler.py`)
- Glyphlettes come from a `Library`, in order of preference: a hand drawing of exactly the slot,
  a generated glyphlette, or a size derived from the closest drawing of its family (the next
  closest if that one declines).
- Each character is built from the **shallowest** cut whose glyphlettes are all available, so a
  larger drawn piece always wins over its parts.
- The parts' drawings, protrusions included, are OR-ed together at their box positions, then
  clipped to the body.
- **Bridging:** in a joined joint's gap row, where a vertical stroke on one side meets ink on the
  other and both touching parts are generated, the gap pixel is filled (十 meets 日 in 早).
  Hand-drawn parts bridge with their own protrusions. A joint that Chiron Hei HK separates
  cleanly in the character (its split cut no ink) is not joined, whatever strokes face it (艹's
  stems stop short of 品 in 䓵), and its parts may move vertically in the evenness pass.
- U+4DC0–U+4DFF (Yijing hexagram symbols, between Ext. A and the URO in the sheet) are generated
  procedurally from the King Wen sequence.
- Output: `out/juxing.tga`, 4096×1728, 256 columns of 16×16 cells from U+3400, white on
  transparent. Header and pixel conventions are identical to `wenquanyi.tga`.

### 5. Evenness pass (`evenness.py`)
Each assembled character is refined by greedy search. This is after P.-K. Lai, D.-Y. Yeung and
M.-C. Pong: *A Heuristic Search Approach to Chinese Glyph Generation Using Hierarchical Character
Composition*, Computer Processing of Oriental Languages 10(3), 1996. The 1995 conference version is
*Chinese glyph generation using character composition and beauty evaluation metrics*. Both papers
are in `sources/`. They define only two of their ten metrics (centre of gravity, rule-based); the
rest are in Lai's M.Phil. thesis, which is not at hand, so those here are designed for 15px 1-bit
glyphs.
- **Search:** the paper moves and resizes component boxes, keeping whichever change most improves
  a weighted sum of "beauty metrics" that quantify calligraphy rules: 四平八稳 (alignment,
  stability), 布白均匀 (even white space), 穿插避让 (closing gaps between components).
- **What moves:**
  - Parts only move **within their own boxes**, so they can't collide. Generated parts may also be
    regenerated up to 2px narrower or wider and 1 or 2 rows shorter or taller than they start
    (`MAX_RESIZE` in all), at an exact ink size (`generators.shape`). Widths stay odd, so a
    central stroke stays central. Their ink must fit the box they sit in.
  - **Measured start:** a generated part that Chiron Hei HK measures on its own in the character,
    cleanly (weight ≥ `START_WEIGHT`: unambiguous, cut through at most 12.5% of the ink), starts at
    the size and place it has there, as near as whole pixels, its box and the contacts allow. The
    slot's shape is sized for every character using the slot; this one may differ: 昌's upper 日
    is narrower than the lower, 品's lower 口 larger than 口 in a 7×9 box usually is. Twins
    measured about the same size (within 1px) start at the same size and stay coupled; twins
    measured differently (昌 吕) are free of the twin rules.
  - There is room when generated parts don't fill their boxes, and when drawn or derived parts
    leave space.
  - `MOVE_DRAWN` (default on) lets hand-drawn parts move too.
- **Surrounds:** a lone part inside a frame may use the largest empty rectangle within the frame's
  actual ink (1px clear), as in the paper.
- **Hard constraints:**
  - No new contact between parts that weren't touching at the start.
  - Parts at a joined joint don't move vertically, so bridging still meets.
  - The glyph's outline (its ink's bounding box) may not shrink. The paper keeps the tightest
    bounding box fixed by rescaling at the end, which bitmaps can't do. Where Chiron Hei HK
    measures the character, the outline may shrink to within 1px of its outline there, so a
    rounding at the start isn't locked in (品's lower 口, 6px wide there, from 7 to 5px, 3px apart
    as evenly as their insides).
  - **Twins** (the same component next to itself, in boxes of any size) move alike across their
    split: side by side (吅 林) their vertical position, stacked (吕 圭) their horizontal one. In
    boxes of the same size they also resize alike. They ignore placement rules along the split, so
    吅's two 口 stay level. Twins measured differently are exempt (above).
- **Metrics (0 = ideal).** A *unit* is the parts under one child of a composition (吅 under 品).
  - 四平八稳, alignment and stability:
    - `cog`: centre of gravity off the body centre (the paper's M2).
    - `align`: units of a ⿱⿳ stack, and the inside of a ⿴⿵⿶ frame, off the centre line (summed
      over the units, weighted above `level`: a centred unit stays centred), by their *axis*: their one long vertical stroke in the middle third (十 土 木), else the middle
      of their ink.
    - `twins`: twins whose ink differs in size (the bottom of a 13px 品 is 口 in a 5px and a 7px box).
    - `level`: strokes of parts side by side that miss by one pixel (林 明): the horizontal strokes
      and the tops and bottoms of the parts. Lined up or clearly apart is fine; a near miss reads
      as a mistake. (Not for stacked parts: a top part 1px in on each side is 上小下大, 昌.)
  - 布白均匀, even white space:
    - `spacing`: down a stack, the white space between two units' facing horizontal strokes
      against the spacing of the strokes inside them, and the stack's gaps against each other
      (言: 亠, 二 and 口 as evenly spaced as the strokes of 二). Across a row, the same for
      vertical strokes. A stroke is a run across 60% of its unit or more, so slants meeting a
      stem are not strokes. Measured between units, not along every pixel row, so the result
      doesn't depend on where parts sit along the other axis.
    - `balance`: the white space around a part between two others (the middle of ⿲⿳), or
      inside a frame on every wall (four in 囗, three in 冂 凵 匚), unequal. 回's inner square is
      as far from the frame at the top as at the sides, not squashed flat or pushed aside.
  - 穿插避让: `gaps`: neighbouring parts not one clear pixel apart; a part inside a frame
    touching it or more than 2 clear pixels away. Pairs of measured parts are left to
    `reference`, which knows how far apart Chiron Hei HK sets them (宫: 口 tucked between the
    legs of 宀, so further from their tips).
  - 风格统一, from measurements: `reference`: every unit's ink away from where Chiron Hei HK
    puts that part of that character (mean distance of the four edges, in px; ambiguous splits
    count half, and a measurement counts less the more ink its cut went through). This is the
    paper's rule-based metric with a target for every part of every character. A measured part
    takes no placement rule. Over the 137 characters that assemble from generated parts alone,
    the parts are on average 0.36px from the measured boxes at the measured start and 0.44px
    after the pass, which trades some of it for even spacing. (With the paper's sizes, shapes
    filling their boxes and no measured start: 0.69px as laid out, 0.58px after.)
  - `density` (spread of ink density between parts), `rule` (see Placement rules), `moved`
    (displacement from the start, which keeps changes small).
- **Normalisation:** as in the paper, each metric is scaled as (M / (mean + sd))² over the font's
  own initial configurations. A fixed sample of every character that can be assembled is used, so a
  character gets the same result however it's assembled. `SCALE_FLOOR` stops a metric that never
  varies (every initial stack is centred) from vanishing or dominating. Metrics in `FIXED_SCALE`
  (`level`, `twins`, `reference`) are not normalised: the unrefined glyphs have so many near
  misses that the font's own mean would excuse them, and a pixel from the reference is a pixel.
- **Placement rules** (the paper's rule-based metric, its "relatively fixed position of the left
  radical 口"):
  - The `place` column (inferred from the reference, 1c; `layout.tsv` may override it) gives a
    component's preferred position per context, as fractions of its box for the centre of its
    ink. E.g. 口 has `left:x=0.53,y=0.46`: a little high on the left (叶 吐 唱 咕), and towards
    its neighbour. It only applies to parts the reference hasn't measured in their character.
  - Contexts: `left` `right` `middle` (⿰⿲), `top` `bottom` `middle` (⿱⿳), `inner`, `whole`,
    `any`.
  - A ruled part starts at its preferred position, as far as its room and the no-contact rule
    allow. The `rule` metric (distance over box size, per axis) keeps other metrics from pulling
    it away.
  - `show` reports it, e.g. `口@5x15 moved +0,-1, placed by rule left:x=0.53,y=0.46`.
- **Left out:** the paper's border-elimination metric. It concerns margins after scaling, which
  Juxing never does, and it rewards pushing parts apart.
- **Typical effect:**
  - Enclosed squares get equal white space on every wall (回: 2px, 3px below where the space is
    even; 冋 3·3·2), instead of 1px above and 2 or 3 at the sides.
  - Stacked strokes even out (言 2·2·2, 喆).
  - Strokes of neighbours line up instead of missing by a pixel (旺 日 and 王, 坤 土 and 申, 扣).
  - Upper 口 and 日 narrow over wider parts (古 早 晶 昌 吕); a unit stays on its stack's axis
    (模's 日 under 艹, 畾's top 田 over the pair); twins match (區's inner 品).
  - Twins side by side open up to even spacing (品 晶 㗊: the lower pair 5px wide, 3px apart,
    rather than 7px wide and 1px apart).
- **Tools:**
  - `show` prints the glyph as assembled and what the pass changed (e.g. `口@15x5 resized -2x+0`).
  - `assemble --no-even` skips the pass, e.g. for comparisons.
- **Cost:** about 3ms a character, so about 80s for the whole font. Values that depend only on a
  part's state are memoised.
- **Knobs:** `WEIGHTS`, `SCALE_FLOOR`, `FIXED_SCALE`, `MAX_STEPS`, `MAX_RESIZE`, `START_WEIGHT`,
  `MOVE_DRAWN`, and a `place` set in `layout.tsv` (for parts without a measurement).

## Switching the font over from WenQuanYi

`juxing.tga` is a drop-in replacement for `wenquanyi.tga`. To switch:
1. `make install`.
2. Replace `"wenquanyi.tga"` with `"juxing.tga"` in the file lists of:
   - `src/net/torvald/terrarumsansbitmap/gdx/TerrarumSansBitmap.kt`
   - `OTFbuild/sheet_config.py`
   - `Autokem/sheet_stats.py`
3. Delete `src/assets/wenquanyi.tga` and `work_files/wenquanyi_addendum.kra`.
4. Update the WenQuanYi credit in `README.md`. If any glyphlette was started from a suggestion (3c),
   credit Chiron Hei HK there too, with its copyright notice ("Copyright 2025 The Chiron Hei HK
   Project Authors", SIL Open Font License 1.1), as the OFL requires of a Modified Version.

Characters that are not assembled yet are blank in the sheet.

## Debugging

- `python3 juxing.py show <chars> [--full]`: the slot tree, each unsplit slot's reason for staying
  whole, the planned glyphlettes as a 16×16 letter map, and drawn state.
- `python3 juxing.py check`: every reachable slot stays inside its parent and split parts don't
  overlap. It also finds layout rules for components the model doesn't have, even-sized frame
  interiors, registry cells that don't fit, and duplicate hexagram symbols.
- `out/worklist.tsv`: the full drawing order, with kind (base or override), uses, effort, the sizes
  each drawing serves, example characters and cumulative completion per tier.
- `python3 juxing.py derived [components] [--limit N]`: `out/derived.png`, every derived size next
  to the drawing it comes from, worst family first; prints the declined sizes.
