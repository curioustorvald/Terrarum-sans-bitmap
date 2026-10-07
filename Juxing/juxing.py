#!/usr/bin/env python3
"""
Juxing (聚形成字) -- assembles Han ideographs from hand-drawn glyphlettes.

Workflow
  build-model  derive model/han_model.tsv from Unihan + BabelStone IDS (+ overrides)
  reference    measure the parts of every character in Chiron Hei HK -> model/reference.tsv
  infer        infer the layout preferences from the measurements -> model/inferred.tsv
  plan         work out which glyphlettes are needed and in which order; writes out/worklist.tsv
  show         explain how characters are laid out and assembled
  mockup       draw layout mock-ups of characters (for checking the layout)
  issue        allocate the next glyphlettes to the drawing sheets, with guides
  refresh      regenerate the guides on the drawing sheets (after drawing)
  retire       give up orphan cells (issued, no longer planned) for reuse
  status       drawing progress, coverage and sheet problems
  generated    gallery of the procedurally generated glyphlettes (口 日 木 ...)
  derived      gallery of the sizes derived from drawn glyphlettes, worst first (for overrides)
  serve        web app: status at a glance, and a pixel editor for the drawing sheets
  check        consistency checks (run after editing model/layout.tsv or model/overrides.tsv)
  assemble     compose all characters that can be made and write the Han sheet

Run `juxing.py <command> -h` for options.
"""

import argparse
import os
import shutil
import sys
from collections import Counter, defaultdict
from typing import List

import numpy as np

import assembler as ASM
import generators as GN
import geometry as GEO
import glyphlettes as GL
import infer as INF
import model as M
import planner as P
import reference as RF
import render as R
import resize as RZ
import sources as SRC
import suggest as SG

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, 'out')
OUTPUT_SHEET = os.path.join(OUT_DIR, 'juxing.tga')
DERIVED_CACHE = os.path.join(OUT_DIR, 'derived.cache')
ASSETS_DIR = os.path.join(HERE, '..', 'src', 'assets')
SAMPLE_TEXT = os.path.join(HERE, 'sample_text.txt')

TIER_NAMES = {1: 'everyday', 2: 'common', 3: 'URO rest', 4: 'Ext. A rest'}


class Context:
    """Everything most commands need, loaded once."""

    def __init__(self, with_plan=True, verbose=False):
        self.model = M.load()
        self.layout = GEO.Layout(self.model)
        self.registry = GL.Registry()
        self.sheets = GL.Sheets(self.registry)
        self.hand = self.sheets.drawn()           # hand drawings on the sheets
        # + generated + derived from the drawings of their family
        self.drawn = GL.Library(self.hand, self.layout, RZ.Cache(DERIVED_CACHE))
        self.plan = None
        if with_plan:
            self.plan = P.make_plan(self.layout, self.model.targets, set(self.registry.by_key),
                                    free=lambda k: GN.can_generate(k, self.layout), unit=GN.is_unit, derive_ok=self.drawn.derive_ok,
                                    verbose=verbose, blank=set(self.registry.by_key) - set(self.hand))

    def is_leaf(self, key):
        return key in self.drawn or (self.plan is not None and key in self.plan.leaves)

    def users(self):
        u = defaultdict(list)
        for cp, parts in self.plan.chars.items():
            for k in parts:
                u[k].append(cp)
        return u

    def examples(self, users, n):
        """The n most typical characters among `users`: common ones with few parts first."""
        return sorted(users, key=lambda c: (self.model[chr(c)].tier, len(self.plan.chars[c]), c))[:n]

    def tier_totals(self):
        return Counter(self.model[chr(cp)].tier for cp in self.model.targets)


def parse_chars(args_chars):
    out = []
    for token in args_chars:
        if token.upper().startswith('U+'):
            out.append(chr(int(token[2:], 16)))
        else:
            out.extend(ch for ch in token if not ch.isspace())
    return out


# ---------------------------------------------------------------------------

def cmd_fetch(args):
    SRC.fetch(force=args.force)


def cmd_build_model(args):
    m = M.build(args.region)
    M.save(m)
    flags = Counter(f for c in m.components.values() for f in c.flags)
    print(f"Wrote {M.MODEL_PATH}: {len(m.targets)} target ideographs, "
          f"{len(m.components) - len(m.targets)} further components")
    print("  flags: " + ', '.join(f"{k} {v}" for k, v in sorted(flags.items())))


def cmd_reference(args):
    """Measure the reference layouts in Chiron Hei HK (or review some of them)."""
    path = args.font or RF.find_font()
    if not path:
        raise SystemExit(f"Chiron Hei HK not found: unpack it from {RF.FONT_URL} into 'sources/Chiron Hei HK/', "
                         "install it, or give --font")
    if args.sheet:
        chars = parse_chars(args.sheet)
        out = args.output or os.path.join(OUT_DIR, 'reference.png')
        os.makedirs(OUT_DIR, exist_ok=True)
        model = M.load()
        R.reference_sheet(GEO.Layout(model), RF.Renderer(path), chars, out)
        print(f"Wrote {out}")
        return
    model = M.load()
    renderer = RF.Renderer(path)
    print(f"Measuring {len(model.targets)} characters in {renderer.name} ({path})...")

    def progress(done, total):
        if done % 2000 < 200 or done == total:
            print(f"  {done}/{total}", flush=True)

    data = RF.build(model.targets, path, progress=progress)
    RF.save(data, renderer)
    nodes = [b for boxes in data.values() for p, b in boxes if p]
    split = sum(1 for boxes in data.values() if any(p for p, _ in boxes))
    print(f"Wrote {RF.REFERENCE_PATH}: {len(data)} characters, {split} of them split into parts, "
          f"{len(nodes)} parts measured")
    print(f"  clean splits {sum(1 for b in nodes if not b.cut) / max(1, len(nodes)):.0%}, "
          f"ambiguous {sum(1 for b in nodes if b.ambiguous) / max(1, len(nodes)):.0%}")


def cmd_infer(args):
    """Infer the layout preferences from the reference measurements."""
    layout = GEO.Layout(M.load(), inferred={})
    if not len(layout.reference):
        raise SystemExit(f"{RF.REFERENCE_PATH} is missing: run `juxing.py reference` first")
    prefs = INF.infer(layout)
    font = ''
    with open(RF.REFERENCE_PATH, encoding='utf-8') as f:
        for line in f:
            if line.startswith('# Measured in '):
                font = line[len('# Measured in '):].strip()
                break
    INF.save(prefs, font or 'the reference font')
    count = Counter()
    for p in prefs.values():
        count['sizes'] += sum(1 for s in ('left', 'right', 'top', 'bottom') if s in p)
        count['frames'] += len(p['frame'])
        count['places'] += len(p['place'])
    print(f"Wrote {INF.INFERRED_PATH}: {len(prefs)} components; {count['sizes']} part sizes, "
          f"{count['frames']} frame insets, {count['places']} placements")
    overridden = [(c, r) for c, r in layout.hand.items() if any(
        getattr(r, s) is not None for s in ('left', 'right', 'top', 'bottom')) or r.insets or r.place]
    if overridden:
        print(f"  layout.tsv overrides some of them for {len(overridden)} components: "
              f"{' '.join(c for c, _ in overridden[:40])}")


def cmd_plan(args):
    ctx = Context(verbose=True)
    plan, layout = ctx.plan, ctx.layout
    issued = set(ctx.registry.by_key)
    needed = plan.glyphlettes()
    effort = sum(P.cost(layout, k) for k in needed)
    whole = sum(P.cost(layout, layout.root(cp)) for cp in ctx.model.targets)
    todo = [k for k in plan.order]
    fams = plan.families()
    print()
    print(plan.summary())
    print(f"Glyphlettes to draw: {len(needed)}  (effort {effort} strokes; drawing every character "
          f"whole would be {whole}, {effort / whole:.0%})")
    print(f"  issued           : {len(needed & issued)} of them"
          f" ({sum(1 for k in needed & issued if k in ctx.hand)} drawn)")
    print(f"  not yet issued   : {len(todo)}")
    orphans = issued - needed
    if orphans:
        print(f"  orphans          : {len(orphans)} issued glyphlettes the plan no longer uses")
    if plan.declined:
        print(f"  declined sizes   : {len(plan.declined)} sizes the resizer declined need an override "
              f"(python3 juxing.py derived)")
    print(f"Generated          : {len(plan.generated)} slots of {len({k.comp for k in plan.generated})} "
          f"components need no drawing")

    totals = ctx.tier_totals()
    print("\nCharacters complete once the next N glyphlettes are drawn (issued ones count as drawn):")
    print("  " + "N".rjust(6) + ''.join(f"{TIER_NAMES[t]:>14}" for t in (1, 2, 3, 4)))
    marks = [n for n in (50, 100, 250, 500, 1000, 2000, 3000, 5000, 7500, 10000) if n < len(todo)] + [len(todo)]
    for n in marks:
        if n == 0:
            continue
        pr = plan.progress[n - 1]
        print("  " + str(n).rjust(6) + ''.join(f"{pr.get(t, 0):>8}/{totals[t]:<5}" for t in (1, 2, 3, 4)))

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, 'worklist.tsv')
    users = ctx.users()
    serves = defaultdict(list)
    for k, d in plan.cover.items():
        serves[d].append(k)
    with open(path, 'w', encoding='utf-8') as f:
        f.write("#rank\tglyphlette\tkind\tuses\teffort\tsizes\texamples\tnote\t" +
                '\t'.join(f"done_tier{t}" for t in (1, 2, 3, 4)) + '\n')
        for i, k in enumerate(todo):
            ex = ctx.examples(users[k], 8)
            c = ctx.model.get(k.comp)
            pr = plan.progress[i]
            kind = 'base' if fams[GL.family(k)].base == k else 'override'
            sizes = ' '.join(f"{s.w}x{s.h}" for s in sorted(serves[k], key=lambda s: (-s.w * s.h, s)))
            f.write(f"{i + 1}\t{k}\t{kind}\t{plan.uses[k]}\t{P.cost(layout, k)}\t{sizes}\t"
                    f"{''.join(map(chr, ex))}\t{c.note if c else ''}\t" +
                    '\t'.join(str(pr.get(t, 0)) for t in (1, 2, 3, 4)) + '\n')
    print(f"\nWrote {path}")
    if args.top:
        print(f"\nNext {args.top} glyphlettes to draw:")
        for i, k in enumerate(todo[:args.top]):
            ex = ctx.examples(users[k], 6)
            more = len(serves[k]) - (k in serves[k])
            print(f"  {i + 1:5}  {str(k):<16} used {plan.uses[k]:>5}×"
                  f"{f'  +{more} sizes' if more else '':<11}  e.g. {''.join(map(chr, ex))}")


def cmd_show(args):
    ctx = Context()
    scorer = None
    for ch in parse_chars(args.chars):
        cp = ord(ch)
        comp = ctx.model.get(ch)
        if comp is None or not comp.is_target:
            print(f"{ch} U+{cp:04X}: not covered by the model")
            continue
        print(f"{ch} U+{cp:04X}  ids {M.IDS.to_string(comp.decomp)}  strokes {comp.strokes}  "
              f"tier {comp.tier} ({TIER_NAMES.get(comp.tier, '-')})  region {comp.region}"
              + (f"  flags {','.join(sorted(comp.flags))}" if comp.flags else ''))
        print("  slot tree:")
        _print_tree(ctx, ctx.layout.root(cp), GEO.BODY_X, GEO.BODY_Y, 2, args.full)
        planned = GL.cut_placements(ctx.layout, cp, lambda k: k in ctx.plan.leaves)
        print("  planned glyphlettes:")
        for line in R.ascii_layout(ctx.layout, planned, ctx.drawn):
            print('    ' + line)
        assembled = ASM.resolve(ctx.layout, cp, ctx.drawn)
        if assembled is None:
            print("  assembled: not yet (some parts are not drawn)")
        else:
            if assembled != planned:
                print("  assembled from: " + ', '.join(str(k) for k, _, _ in assembled))
            if scorer is None:
                scorer = ASM.font_scorer(ctx.layout, ctx.drawn)
            cell, arr = ASM.assemble_one(ctx.layout, cp, ctx.drawn, scorer)
            print("  as assembled:")
            for row in cell[GEO.BODY_Y:GEO.BODY_Y + GEO.BODY_H, GEO.BODY_X:GEO.BODY_X + GEO.BODY_W]:
                print('    ' + ' '.join('█' if v else '·' for v in row))
            moves = [f"{p.key} " + ', '.join(filter(None, [
                f"moved {p.dx:+d},{p.dy:+d}" if p.dx or p.dy else '',
                f"resized {p.gw:+d}x{p.gh:+d}" if p.gw or p.gh else '',
                f"placed by rule {p.context}:" + ','.join(f"{a}={v:g}" for a, v in p.target.items())
                if p.target else '']))
                for p in arr.parts if p.dx or p.dy or p.gw or p.gh or p.target]
            print("  evenness pass: " + ('; '.join(moves) if moves else 'no change'))
        print()


def _print_tree(ctx, key, x, y, depth, full):
    s = ctx.layout.slot(key)
    marks = []
    if key in ctx.plan.leaves:
        marks.append('GLYPHLETTE')
    if key in ctx.registry.by_key:
        marks.append(f"#{ctx.registry.by_key[key].id}")
    kind = ctx.drawn.kind(key)
    if kind == 'derived':
        src, d, _ = ctx.drawn.derivation(key)
        marks.append(f"derived from {src}, cost {d.cost:.1f}")
    elif kind:
        marks.append(kind)
    elif key in ctx.plan.generated:
        marks.append('generated')
    elif key in ctx.plan.cover and ctx.plan.cover[key] != key:
        marks.append(f"to be derived from {ctx.plan.cover[key]}")
    if key in ctx.plan.declined:
        marks.append("declined by " + ', '.join(map(str, ctx.plan.declined[key])))
    if not s.parts:
        why = f"  (whole: {s.why_whole})"
    elif key in ctx.plan.why:
        why = f"  (whole by plan: {ctx.plan.why[key]})"
    else:
        why = f"  {s.op} " + ', '.join('joined' if j else 'separate' for j in ctx.layout.joints(key))
    print('  ' * depth + f"{key} at ({x},{y})" + (f" [{' '.join(marks)}]" if marks else '') + why)
    if s.parts and (full or key not in ctx.plan.leaves):
        for c, dx, dy in s.parts:
            _print_tree(ctx, c, x + dx, y + dy, depth + 1, full)


def cmd_mockup(args):
    ctx = Context()
    chars = parse_chars(args.chars)
    if args.file:
        with open(args.file, encoding='utf-8') as f:
            chars += [ch for ch in f.read() if SRC.is_target(ord(ch))]
    os.makedirs(OUT_DIR, exist_ok=True)
    out = args.output or os.path.join(OUT_DIR, 'mockup.png')

    def placements_of(cp):
        if args.planned:
            return GL.cut_placements(ctx.layout, cp, lambda k: k in ctx.plan.leaves)
        return ASM.resolve(ctx.layout, cp, ctx.drawn) or \
            GL.cut_placements(ctx.layout, cp, lambda k: k in ctx.plan.leaves)

    R.mockup(ctx.layout, chars, placements_of, out, scale=args.scale, per_row=args.per_row)
    print(f"Wrote {out}")


def cmd_issue(args):
    ctx = Context()
    new_keys = []
    if args.whole:
        for ch in parse_chars(args.whole):
            new_keys.append(ctx.layout.root(ord(ch)))
    for k in args.key or []:
        new_keys.append(GEO.SlotKey.parse(k))
    if args.count:
        new_keys.extend(ctx.plan.order[:args.count])
    try:
        issued = issue(ctx, new_keys, group=args.group)
    except ValueError as e:
        raise SystemExit(str(e))
    if not issued:
        print("Nothing to issue.")
        return
    first, last = issued[0], issued[-1]
    print(f"Issued {len(issued)} glyphlettes: #{first.id}..#{last.id} "
          f"(sheet {first.sheet:02d} row {first.row} .. sheet {last.sheet:02d} row {last.row})")


def draft(ctx, k, users=None):
    """
    Where a glyphlette goes when it is issued, and what its cell starts with: (exemplar,
    box x, box y, ink as a cell-sized mask). The ink is the generated glyphlette, or the
    size resized from the drawings of its family (declined or not); an override is shown
    in a character that uses its size, derived until now.
    """
    users = users if users is not None else ctx.users()
    u = (users.get(k) or users.get(ctx.plan.cover.get(k))
         or ([ord(k.comp)] if len(k.comp) == 1 and SRC.is_target(ord(k.comp)) else []))
    ex, bx, by = GL.pick_exemplar(ctx.layout, k, u, lambda x: x == k or ctx.is_leaf(x))
    ink = np.zeros((GEO.CELL_H, GEO.CELL_W), dtype=bool)
    g = GN.generate_for(ctx.layout, k)
    if g is not None:
        ink[by:by + k.h, bx:bx + k.w] = g
    else:
        _, d, _ = ctx.drawn.derivation(k)
        if d is not None:
            ys, xs = np.nonzero(d.mask)
            ys, xs = ys + by - d.by, xs + bx - d.bx
            ok = (ys >= 0) & (ys < GEO.CELL_H) & (xs >= 0) & (xs < GEO.CELL_W)
            ink[ys[ok], xs[ok]] = True
    return ex, bx, by, ink


def suggestion(ctx, tracer: 'SG.Tracer', k, exemplar: str, at, users=None):
    """A glyphlette traced from Chiron Hei HK for the cell of `k` (its box at `at`), for the
    artist to start from (suggest.py), or None: traced from the exemplar and the typical
    characters using the drawing of `k`."""
    users = users if users is not None else ctx.users()
    d = k if k in ctx.plan.uses else ctx.plan.cover.get(k, k)
    chars = [exemplar] + [chr(c) for c in ctx.examples(users.get(d, []), 3 * SG.TRIES)]
    # then the other drawings of its family, nearest size first: a rare size (氵@7x15, beside
    # narrow parts) has few examples, often cut badly
    fam = GL.family(k)
    for o in sorted((o for o in users if o != d and GL.family(o) == fam),
                    key=lambda o: (abs(o.w - k.w) + abs(o.h - k.h), str(o))):
        chars += [chr(c) for c in ctx.examples(users[o], SG.TRIES)]
    if len(k.comp) == 1 and SRC.is_target(ord(k.comp)):
        chars.append(k.comp)
    return tracer.suggest(k, chars, at)


def issue(ctx, keys, group=False, verbose=True) -> List[GL.Entry]:
    """Allocate cells to glyphlettes (skipping issued ones), prefill them and write the sheets."""
    reg = ctx.registry
    new_keys = [k for k in dict.fromkeys(keys) if k not in reg.by_key]
    for k in new_keys:
        if k.w > GEO.BODY_W or k.h > GEO.BODY_H:
            raise ValueError(f"{k} does not fit the body box")
    if not new_keys:
        return []
    if group:
        rank = {k: i for i, k in enumerate(new_keys)}
        first_of = {}
        for k in new_keys:
            first_of.setdefault(k.comp, rank[k])
        new_keys.sort(key=lambda k: (first_of[k.comp], k.role, -k.w * k.h))

    users = ctx.users()
    touched = set()
    out = []
    for k in new_keys:
        ex, bx, by, ink = draft(ctx, k, users)
        e = reg.issue(k, bx, by, ex)
        out.append(e)
        touched.add(e.sheet)
        ctx.sheets.cell(e)[ink] = GL.COLOUR_INK
    reg.save()
    for n in sorted(touched):
        _write_sheet(ctx, n, verbose)
    return out


def _write_sheet(ctx, n, verbose=True):
    ctx.sheets.render_guides(n, ctx.layout, ctx.is_leaf, ctx.drawn)
    ctx.sheets.save(n)
    os.makedirs(OUT_DIR, exist_ok=True)
    entries = ctx.registry.on_sheet(n)
    idx = os.path.join(OUT_DIR, f"sheet_{n:02d}_index.png")
    R.sheet_index(ctx.sheets.image(n), entries, ctx.layout, idx)
    if verbose:
        print(f"  {os.path.relpath(GL.sheet_path(n))}  (index: {os.path.relpath(idx)})")


def orphans(ctx) -> List[GL.Entry]:
    """Issued cells the plan no longer uses."""
    needed = ctx.plan.glyphlettes()
    return [e for e in ctx.registry.entries if e.key not in needed]


def retire(ctx, entries: List[GL.Entry], verbose=True):
    """
    Give cells up: each keeps its number, so no other cell moves, and its line in the
    registry says '-'; its ink and guides are cleared, and the next glyphlette issued takes
    the cell. A retired drawing is gone (the sheets are in git, if it was committed).
    """
    if not entries:
        return
    touched = set()
    for e in entries:
        ctx.sheets.cell(e)[...] = 0
        ctx.registry.retire(e)
        touched.add(e.sheet)
    ctx.registry.save()
    # the retired drawings are no context for the other cells' guides any more
    ctx.hand = ctx.sheets.drawn()
    ctx.drawn = GL.Library(ctx.hand, ctx.layout, ctx.drawn.cache)
    for n in sorted(touched):
        _write_sheet(ctx, n, verbose)


def cmd_retire(args):
    """Retire orphans: cells the plan no longer uses."""
    ctx = Context()
    reg = ctx.registry
    spare = {e.id: e for e in orphans(ctx)}
    chosen = []
    if args.orphans:
        chosen += [e for e in spare.values() if e.key not in ctx.hand]
    for name in args.cells:
        e = reg.by_id.get(int(name)) if name.isdigit() else reg.by_key.get(GEO.SlotKey.parse(name))
        if e is None:
            raise SystemExit(f"{name}: no such issued cell")
        if e.id not in spare:
            raise SystemExit(f"{name}: #{e.id} {e.key} is in the plan; only orphans can be retired")
        if e.key in ctx.hand and not args.drawn:
            raise SystemExit(f"{name}: #{e.id} {e.key} is drawn; retiring it erases the drawing (add --drawn)")
        chosen.append(e)
    chosen = sorted({e.id: e for e in chosen}.values(), key=lambda e: e.id)
    if not chosen:
        left = [f"#{e.id} {e.key} ({'drawn' if e.key in ctx.hand else 'blank'})" for e in spare.values()]
        print("Nothing to retire." + (f" Orphans: {', '.join(left)}" if left else " There are no orphans."))
        return
    retire(ctx, chosen)
    print(f"Retired {len(chosen)} cell(s): {', '.join(f'#{e.id} {e.key}' for e in chosen)}; "
          f"the next glyphlettes issued take them")
    kept = [e for e in spare.values() if e not in chosen]
    if kept:
        print(f"  orphans kept: {', '.join(f'#{e.id} {e.key}' for e in kept)} (drawn: name them with --drawn to retire)")


def cmd_suggest(args):
    """Print suggestions traced from Chiron Hei HK (the editor's Suggest), beside the drawing."""
    ctx = Context()
    users = ctx.users()
    tracer = SG.Tracer(ctx.layout)
    for name in args.cells:
        e = (ctx.registry.by_id.get(int(name)) if name.isdigit()
             else ctx.registry.by_key.get(GEO.SlotKey.parse(name)))
        if e is None:
            k = GEO.SlotKey.parse(name)
            ex, bx, by, _ = draft(ctx, k, users)
            e = GL.Entry(None, k, bx, by, ex)
        sg = suggestion(ctx, tracer, e.key, e.exemplar, (e.bx, e.by), users)
        print(f"{e.key}" + (f" #{e.id}" if e.id is not None else " (not issued)") +
              (f": traced from {sg.source}" if sg else ": no suggestion"))
        if sg is None:
            continue
        drawn = ctx.hand[e.key].mask if e.key in ctx.hand else None
        for y in range(GEO.CELL_H):
            row = ''.join('#' if v else '.' for v in sg.ink[y])
            print(f"  {row}   {''.join('#' if v else '.' for v in drawn[y])}" if drawn is not None else f"  {row}")


def cmd_refresh(args):
    ctx = Context()
    for n in range(ctx.registry.sheet_count()):
        _write_sheet(ctx, n)


def cmd_status(args):
    ctx = Context()
    reg = ctx.registry
    needed = ctx.plan.glyphlettes()
    print(f"Issued glyphlettes : {len(reg.entries)} on {reg.sheet_count()} sheet(s)")
    print(f"  drawn            : {len(ctx.hand)}")
    print(f"  still to draw    : {sum(1 for e in reg.entries if e.key not in ctx.hand)}")
    print(f"Plan needs         : {len(needed)} glyphlettes, {len(needed - set(reg.by_key))} not yet issued")
    print(f"  {ctx.plan.summary()}")
    derived = [k for k in ctx.plan.cover if ctx.drawn.kind(k) == 'derived']
    if derived:
        print(f"  derived now      : {len(derived)} sizes from drawn glyphlettes")
    if ctx.plan.declined:
        print(f"  declined         : {len(ctx.plan.declined)} sizes need an override "
              f"(python3 juxing.py derived; issue them with --key)")
    spare = orphans(ctx)
    if spare:
        blank = sum(1 for e in spare if e.key not in ctx.hand)
        print(f"Orphans            : {len(spare)} issued glyphlettes are no longer in the plan "
              f"(still usable if drawn); {blank} blank (juxing.py retire --orphans)")
    if reg.retired:
        print(f"Retired cells      : {len(reg.retired)}, taken by the next glyphlettes issued")
    protruding = sum(1 for d in ctx.hand.values() if d.protrusions())
    if protruding:
        print(f"  protruding       : {protruding} drawn glyphlettes have ink outside their box (overlaid as drawn)")
    glyphs = ASM.assemble(ctx.layout, ctx.model.targets, ctx.drawn)
    totals = ctx.tier_totals()
    done = Counter(ctx.model[chr(cp)].tier for cp in glyphs)
    print("\nCharacters that can be assembled:")
    for t in (1, 2, 3, 4):
        print(f"  {TIER_NAMES[t]:<12} {done[t]:>6} / {totals[t]}")
    print(f"  {'total':<12} {len(glyphs):>6} / {len(ctx.model.targets)}")


def cmd_assemble(args):
    ctx = Context(with_plan=False)
    glyphs = ASM.assemble(ctx.layout, ctx.model.targets, ctx.drawn, even=not args.no_even)
    for cp in ASM.HEXAGRAMS:
        glyphs[cp] = ASM.hexagram(cp)
    os.makedirs(OUT_DIR, exist_ok=True)
    out = args.output or OUTPUT_SHEET
    ASM.write_sheet(out, glyphs)
    han = len(glyphs) - len(ASM.HEXAGRAMS)
    print(f"Wrote {out}: {han} of {len(ctx.model.targets)} ideographs assembled "
          f"({han / len(ctx.model.targets):.1%}), plus {len(ASM.HEXAGRAMS)} hexagram symbols")
    R.coverage_map(set(glyphs), os.path.join(OUT_DIR, 'coverage.png'))
    if os.path.exists(SAMPLE_TEXT):
        with open(SAMPLE_TEXT, encoding='utf-8') as f:
            lines = [l.rstrip('\n') for l in f if not l.startswith('#')]
        R.text_preview(glyphs, lines, os.path.join(OUT_DIR, 'preview.png'))
    print(f"  previews: {os.path.join(OUT_DIR, 'coverage.png')}, {os.path.join(OUT_DIR, 'preview.png')}")
    if args.install:
        dest = os.path.join(ASSETS_DIR, os.path.basename(OUTPUT_SHEET))
        shutil.copyfile(out, dest)
        print(f"  installed to {os.path.normpath(dest)}")


def cmd_generated(args):
    """Gallery of the generated glyphlettes the plan uses, for review."""
    ctx = Context()
    used = Counter()
    for k in ctx.plan.generated:
        used[k] += 1
    slot_uses = Counter()
    for cp in ctx.model.targets:
        for k, _, _ in GL.cut_placements(ctx.layout, cp, lambda k: k in ctx.plan.leaves):
            if k in ctx.plan.generated:
                slot_uses[k] += 1
    items = sorted(ctx.plan.generated, key=lambda k: (k.comp, -slot_uses[k], k))
    out = args.output or os.path.join(OUT_DIR, 'generated.png')
    os.makedirs(OUT_DIR, exist_ok=True)
    R.gallery([(k, GN.generate_for(ctx.layout, k), slot_uses[k]) for k in items], out)
    by_comp = Counter(k.comp for k in items)
    print(f"{len(items)} generated glyphlettes of {len(by_comp)} components serve "
          f"{sum(slot_uses.values())} slots: " + ' '.join(f"{c}{n}" for c, n in by_comp.most_common()))
    # characters that need no hand drawing at all, as the assembler makes them
    free = sorted((cp for cp in ctx.model.targets if not ctx.plan.chars[cp]),
                  key=lambda cp: (ctx.model[chr(cp)].tier, cp))
    glyphs = ASM.assemble(ctx.layout, free, GL.Library({}, ctx.layout))
    chars_out = os.path.join(OUT_DIR, 'generated_chars.png')
    R.text_preview(glyphs, [''.join(map(chr, free[i:i + 25])) for i in range(0, len(free), 25)], chars_out)
    print(f"{len(free)} characters need no hand drawing at all")
    print(f"Wrote {out}, {chars_out}")


def derived_review(ctx, comps=None):
    """
    Every family with a drawing on the sheets, worst first: [(worst cost, family, items)]
    where items are (key, box mask, state, cost, source) with state 'drawn', 'derived'
    or 'declined' (the mask is then the resizer's best attempt).
    """
    sizes = defaultdict(set)
    for k in ctx.plan.cover:
        if comps is None or k.comp in comps:
            sizes[GL.family(k)].add(k)
    for k in ctx.hand:
        if comps is None or k.comp in comps:
            sizes[GL.family(k)].add(k)
    rows = []
    for fam, keys in sizes.items():
        drawn = sorted((k for k in keys if k in ctx.hand), key=lambda k: (-k.w * k.h, k))
        if not drawn:
            continue
        items, worst = [], 0.0
        for k in drawn:
            d = ctx.hand[k]
            items.append((k, d.mask[d.by:d.by + k.h, d.bx:d.bx + k.w], 'drawn', 0.0, k))
        for k in sorted(keys - set(drawn), key=lambda k: (-k.w * k.h, k)):
            src, d, _ = ctx.drawn.derivation(k)
            if d is None:
                continue
            box = d.mask[d.by:d.by + k.h, d.bx:d.bx + k.w]
            items.append((k, box, 'derived' if d.ok else 'declined', d.cost, src))
            worst = max(worst, d.cost if d.ok else float('inf'))
        rows.append((worst, fam, items))
    rows.sort(key=lambda r: (-r[0], r[1]))
    return rows


def derived_score(ctx, comps=None, verbose=False):
    """
    The resizer against the overrides drawn by hand: every drawn size of a family resized
    from the family's largest drawing (its base), compared with the drawing. Prints, per
    size, the pixels that differ and the Ls (kinks) the resizer adds; returns the totals.
    """
    fams = defaultdict(list)
    for k in ctx.hand:
        if not k.role and (comps is None or k.comp in comps):
            fams[GL.family(k)].append(k)
    sizes = pixels = kinks = exact = 0
    for fam, keys in sorted(fams.items()):
        if len(keys) < 2:
            continue
        keys.sort(key=lambda k: (-k.w * k.h, k))
        base, b = keys[0], ctx.hand[keys[0]]
        base_kinks = RZ.kinks(b.mask[b.by:b.by + base.h, b.bx:b.bx + base.w])
        for k in keys[1:]:
            r = RZ.derive(b.mask, b.bx, b.by, base.w, base.h, k.w, k.h)
            d = ctx.hand[k]
            want = d.mask[d.by:d.by + k.h, d.bx:d.bx + k.w]
            got = r.mask[r.by:r.by + k.h, r.bx:r.bx + k.w] if r is not None else np.zeros_like(want)
            diff = int((want ^ got).sum())
            extra = max(0, RZ.kinks(got) - base_kinks)
            sizes += 1
            pixels += diff
            kinks += extra
            exact += diff == 0
            state = 'declined' if r is None or not r.ok else 'derived'
            print(f"  {str(k):14s} from {str(base):14s} {diff:3d} px differ, {extra} Ls added ({state})")
            if verbose:
                for a, g in zip(want, got):
                    print('      ' + ''.join('#' if v else '.' for v in a).ljust(17)
                          + ''.join('#' if v else '.' for v in g))
    print(f"{sizes} sizes drawn by hand: {exact} derived exactly, {pixels} pixels differ, {kinks} Ls added")
    return sizes, pixels, kinks


def cmd_derived(args):
    """Gallery of the sizes derived from drawn glyphlettes, worst family first, for choosing overrides."""
    ctx = Context()
    if args.score:
        derived_score(ctx, set(parse_chars(args.chars)) if args.chars else None, verbose=args.verbose)
        return
    rows = derived_review(ctx, set(parse_chars(args.chars)) if args.chars else None)
    if not rows:
        print("No drawn glyphlettes yet (or none of these components): nothing is derived.")
        return
    items = [i for _, _, its in rows for i in its]
    derived = sum(1 for i in items if i[2] == 'derived')
    declined = sorted(i[0] for i in items if i[2] == 'declined')
    shown = []
    for _, (comp, role, aspect), its in rows[:args.limit]:
        base = its[0][0]
        shown.append(((comp, f"{aspect}{' ' + role if role else ''}"),
                      [(k, box, state, 'drawn' if state == 'drawn' else 'declined' if state == 'declined'
                        else f"{cost:.1f}" + ('' if src == base else f" ←{src.w}×{src.h}"))
                       for k, box, state, cost, src in its]))
    out = args.output or os.path.join(OUT_DIR, 'derived.png')
    os.makedirs(OUT_DIR, exist_ok=True)
    R.derived_gallery(shown, out)
    print(f"{derived} sizes derived from the drawings of {len(rows)} families; {len(declined)} declined"
          + (': ' + ' '.join(map(str, declined[:40])) + (' ...' if len(declined) > 40 else '')
             if declined else ''))
    print(f"Wrote {out} ({len(shown)} families, worst first; declined sizes in red, captions are costs, "
          f"'←W×H' when derived from an override)")


def cmd_serve(args):
    import webapp
    webapp.serve(sys.modules[__name__], args.host, args.port)


def cmd_check(args):
    """Consistency checks of the model, the layout rules, the geometry and the registry."""
    ctx = Context(with_plan=False)
    model, layout = ctx.model, ctx.layout
    problems = 0

    def report(msg):
        nonlocal problems
        problems += 1
        if problems <= 200:
            print('  ' + msg)

    print("Model...")
    # cycles make size estimates depend on evaluation order; ㇯ ('A minus B', which
    # refers to its own parent) is measured without recursion and may form them
    state = {}
    sys.setrecursionlimit(max(sys.getrecursionlimit(), 20000))

    def parts(c):
        node = layout.decomposition(c)
        if isinstance(node, str):
            return [node] if node != c else []
        return [] if node[0] == '㇯' else [M.IDS.to_string(x) for x in node[1:]]

    def visit(c, path):
        st = state.get(c)
        if st == 1:
            report(f"cyclic decomposition: {' -> '.join(path[path.index(c):] + [c])}")
            return
        if st == 2:
            return
        state[c] = 1
        for p in parts(c):
            visit(p, path + [c])
        state[c] = 2

    for c in list(model.components):
        visit(c, [])

    print("Layout rules...")
    for comp in layout.hand:
        if model.get(comp) is None:
            report(f"layout.tsv: {comp} is not a component of the model")
    for comp, rule in layout.rules.items():
        for op, ins in (rule.insets or {}).items():
            l, t, r, b = ins
            if (GEO.REF - l - r) % 2 == 0 or (GEO.REF - t - b) % 2 == 0:
                where = 'layout.tsv' if op in ((layout.hand.get(comp) or GEO.ComponentRule()).insets or {}) \
                    else 'inferred.tsv'
                report(f"{where}: {comp} {op}{','.join(map(str, ins))} leaves an even-sized inner box")
    if not layout.inferred:
        report(f"{GEO.INFERRED_PATH} is missing: run `juxing.py infer`")

    print("Geometry of every reachable slot...")
    seen = set()
    stack = [layout.root(cp) for cp in model.targets]
    while stack:
        k = stack.pop()
        if k in seen:
            continue
        seen.add(k)
        if k.w > GEO.BODY_W or k.h > GEO.BODY_H or k.w < 1 or k.h < 1:
            report(f"{k}: size outside the body")
        s = layout.slot(k)
        if not s.parts:
            continue
        boxes = []
        for c, dx, dy in s.parts:
            if dx < 0 or dy < 0 or dx + c.w > k.w or dy + c.h > k.h:
                report(f"{k}: part {c} at ({dx},{dy}) sticks out")
            if not c.role:
                boxes.append((dx, dy, c.w, c.h, c))
            stack.append(c)
        # a stacked part may reach up into the one above it (Slot.overlaps: under a roof)
        allowed = {(i, i + 1): up - GEO.GAP for i, up in enumerate(s.overlaps or ()) if up}
        for i, (x1, y1, w1, h1, c1) in enumerate(boxes):
            for j, (x2, y2, w2, h2, c2) in enumerate(boxes[i + 1:], start=i + 1):
                if x1 < x2 + w2 and x2 < x1 + w1 and y1 < y2 + h2 and y2 < y1 + h1:
                    if (i, j) in allowed and y1 + h1 - y2 == allowed[(i, j)]:
                        continue
                    report(f"{k}: parts {c1} and {c2} overlap")
    print(f"  {len(seen)} slots checked")

    print("Registry...")
    for e in ctx.registry.entries:
        k = e.key
        if not k.comp or (k.comp[0] in M.IDS.IDC_ALL and not _parses(k.comp)):
            report(f"#{e.id} {k}: unreadable component")
        if e.bx < 0 or e.by < 0 or e.bx + k.w > GEO.CELL_W or e.by + k.h > GEO.CELL_H:
            report(f"#{e.id} {k}: box at ({e.bx},{e.by}) does not fit the cell")

    print("Hexagrams...")
    if len({ASM.hexagram(cp).tobytes() for cp in ASM.HEXAGRAMS}) != 64:
        report("hexagram symbols are not all distinct")

    print(f"{problems} problem(s)" if problems else "All good.")
    if problems:
        sys.exit(1)


def _parses(s):
    try:
        M.IDS.parse(s)
        return True
    except ValueError:
        return False


# ---------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)

    p = sub.add_parser('fetch', help='download the upstream data into sources/')
    p.add_argument('--force', action='store_true', help='download again even if present')
    p.set_defaults(func=cmd_fetch)

    p = sub.add_parser('build-model', help='(re)build model/han_model.tsv from sources and overrides')
    p.add_argument('--region', default=M.DEFAULT_REGION_PREF,
                   help=f'IRG source preference order (default {M.DEFAULT_REGION_PREF})')
    p.set_defaults(func=cmd_build_model)

    p = sub.add_parser('reference', help='measure the parts of every character in Chiron Hei HK')
    p.add_argument('--font', help="the font file (default: 'sources/Chiron Hei HK/', then an installed Chiron Hei HK)")
    p.add_argument('--sheet', nargs='+', help='only draw these characters with their measured parts, for review')
    p.add_argument('-o', '--output')
    p.set_defaults(func=cmd_reference)

    p = sub.add_parser('infer', help='infer the layout preferences from the reference measurements')
    p.set_defaults(func=cmd_infer)

    p = sub.add_parser('plan', help='work out the glyphlettes to draw; writes out/worklist.tsv')
    p.add_argument('--top', type=int, default=30, help='print the first N glyphlettes to draw')
    p.set_defaults(func=cmd_plan)

    p = sub.add_parser('show', help='explain the layout of characters')
    p.add_argument('chars', nargs='+', help='characters or U+XXXX')
    p.add_argument('--full', action='store_true', help='show the slot tree below planned glyphlettes too')
    p.set_defaults(func=cmd_show)

    p = sub.add_parser('mockup', help='render layout mock-ups to a PNG')
    p.add_argument('chars', nargs='*', help='characters or U+XXXX')
    p.add_argument('--file', help='also take the characters of this text file')
    p.add_argument('--planned', action='store_true', help='show the planned cut even if other parts are drawn')
    p.add_argument('--scale', type=int, default=8)
    p.add_argument('--per-row', type=int, default=8)
    p.add_argument('-o', '--output')
    p.set_defaults(func=cmd_mockup)

    p = sub.add_parser('issue', help='allocate glyphlettes to the drawing sheets')
    p.add_argument('count', type=int, nargs='?', default=0, help='issue the next N glyphlettes of the worklist')
    p.add_argument('--whole', nargs='+', help='issue these characters to be drawn whole')
    p.add_argument('--key', nargs='+', help='issue these glyphlettes, e.g. 木@5x15 or 广@15x15/⿸')
    p.add_argument('--group', action='store_true', help='keep glyphlettes of the same component together')
    p.set_defaults(func=cmd_issue)

    p = sub.add_parser('refresh', help='regenerate guides on the drawing sheets and their indices')
    p.set_defaults(func=cmd_refresh)

    p = sub.add_parser('retire', help='give up orphan cells (no longer planned) for reuse')
    p.add_argument('cells', nargs='*', help='cells by number or glyphlette (e.g. 12 or 讠@9x15)')
    p.add_argument('--orphans', action='store_true', help='all blank orphans')
    p.add_argument('--drawn', action='store_true', help='allow retiring drawn cells named (erases the drawing)')
    p.set_defaults(func=cmd_retire)

    p = sub.add_parser('suggest', help='glyphlettes traced from Chiron Hei HK, as the editor suggests them')
    p.add_argument('cells', nargs='+', help='cells by number or glyphlette (e.g. 36 or 寸@7x15)')
    p.set_defaults(func=cmd_suggest)

    p = sub.add_parser('status', help='drawing progress and sheet problems')
    p.set_defaults(func=cmd_status)

    p = sub.add_parser('generated', help='gallery of the generated glyphlettes the plan uses')
    p.add_argument('-o', '--output')
    p.set_defaults(func=cmd_generated)

    p = sub.add_parser('derived', help='gallery of the sizes derived from drawn glyphlettes, worst first')
    p.add_argument('chars', nargs='*', help='only these components')
    p.add_argument('--limit', type=int, default=150, help='families to show')
    p.add_argument('--score', action='store_true',
                   help='resize every override drawn by hand from its base and compare (tests the resizer)')
    p.add_argument('-v', '--verbose', action='store_true', help='with --score: show drawing and result')
    p.add_argument('-o', '--output')
    p.set_defaults(func=cmd_derived)

    p = sub.add_parser('serve', help='web app: status at a glance and a pixel editor for the sheets')
    p.add_argument('--host', default='127.0.0.1', help='address to listen on (default 127.0.0.1: this machine only)')
    p.add_argument('--port', type=int, default=8765)
    p.set_defaults(func=cmd_serve)

    p = sub.add_parser('check', help='consistency checks of model, layout rules, geometry and registry')
    p.set_defaults(func=cmd_check)

    p = sub.add_parser('assemble', help='compose the characters and write the Han sheet')
    p.add_argument('-o', '--output', help=f'output TGA (default {os.path.relpath(OUTPUT_SHEET, HERE)})')
    p.add_argument('--install', action='store_true', help='also copy the sheet into src/assets')
    p.add_argument('--no-even', action='store_true', help='skip the evenness pass (evenness.py)')
    p.set_defaults(func=cmd_assemble)

    args = ap.parse_args(argv)
    args.func(args)


if __name__ == '__main__':
    main()
