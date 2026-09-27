#!/usr/bin/env python3
"""
Juxing (聚形成字) -- assembles Han ideographs from hand-drawn glyphlettes.

Workflow
  build-model  derive model/han_model.tsv from Unihan + BabelStone IDS (+ overrides)
  plan         work out which glyphlettes are needed and in which order; writes out/worklist.tsv
  show         explain how characters are laid out and assembled
  mockup       draw layout mock-ups of characters (for tuning model/layout.tsv)
  issue        allocate the next glyphlettes to the drawing sheets, with guides
  refresh      regenerate the guides on the drawing sheets (after drawing)
  status       drawing progress, coverage and sheet problems
  generated    gallery of the procedurally generated glyphlettes (口 日 木 ...)
  check        consistency checks (run after editing model/layout.tsv or model/overrides.tsv)
  assemble     compose all characters that can be made and write the Han sheet

Run `juxing.py <command> -h` for options.
"""

import argparse
import os
import shutil
import sys
from collections import Counter, defaultdict

import assembler as ASM
import generators as GN
import geometry as GEO
import glyphlettes as GL
import model as M
import planner as P
import render as R
import sources as SRC

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, 'out')
OUTPUT_SHEET = os.path.join(OUT_DIR, 'juxing.tga')
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
        self.drawn = GL.Library(self.hand, self.layout)   # + generated + reused nearby sizes
        self.plan = None
        if with_plan:
            self.plan = P.make_plan(self.layout, self.model.targets, set(self.registry.by_key),
                                    free=GN.can_generate, unit=GN.is_unit, verbose=verbose)

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


def cmd_plan(args):
    ctx = Context(verbose=True)
    plan, layout = ctx.plan, ctx.layout
    issued = set(ctx.registry.by_key)
    needed = plan.glyphlettes()
    effort = sum(P.cost(layout, k) for k in needed)
    whole = sum(P.cost(layout, layout.root(cp)) for cp in ctx.model.targets)
    todo = [k for k in plan.order]
    print()
    print(f"Glyphlettes needed : {len(needed)}  (effort {effort} strokes; drawing every character "
          f"whole would be {whole}, {effort / whole:.0%})")
    print(f"  issued           : {len(needed & issued)} of them"
          f" ({sum(1 for k in needed & issued if k in ctx.hand)} drawn)")
    print(f"  not yet issued   : {len(todo)}")
    orphans = issued - needed
    if orphans:
        print(f"  orphans          : {len(orphans)} issued glyphlettes the plan no longer uses")
    reused = sum(1 for k, d in plan.cover.items() if k != d)
    print(f"Generated          : {len(plan.generated)} slots of {len({k.comp for k in plan.generated})} "
          f"components need no drawing")
    print(f"Reused sizes       : {reused} slots use a drawing up to {GEO.SIZE_TOLERANCE}px smaller")

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
    with open(path, 'w', encoding='utf-8') as f:
        f.write("#rank\tglyphlette\tuses\teffort\texamples\tnote\t" +
                '\t'.join(f"done_tier{t}" for t in (1, 2, 3, 4)) + '\n')
        for i, k in enumerate(todo):
            ex = ctx.examples(users[k], 8)
            c = ctx.model.get(k.comp)
            pr = plan.progress[i]
            f.write(f"{i + 1}\t{k}\t{plan.uses[k]}\t{P.cost(layout, k)}\t{''.join(map(chr, ex))}\t"
                    f"{c.note if c else ''}\t" + '\t'.join(str(pr.get(t, 0)) for t in (1, 2, 3, 4)) + '\n')
    print(f"\nWrote {path}")
    if args.top:
        print(f"\nNext {args.top} glyphlettes to draw:")
        for i, k in enumerate(todo[:args.top]):
            ex = ctx.examples(users[k], 6)
            print(f"  {i + 1:5}  {str(k):<16} used {plan.uses[k]:>5}×  e.g. {''.join(map(chr, ex))}")


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
                f"resized {p.gw:+d}x{p.gh:+d}" if p.gw or p.gh else '']))
                for p in arr.parts if p.dx or p.dy or p.gw or p.gh]
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
    if kind == 'reused':
        marks.append(f"reuses {ctx.drawn.source(key)}")
    elif kind:
        marks.append(kind)
    elif key in ctx.plan.generated:
        marks.append('generated')
    elif key in ctx.plan.cover and ctx.plan.cover[key] != key:
        marks.append(f"to be drawn as {ctx.plan.cover[key]}")
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
    reg = ctx.registry
    new_keys = []
    if args.whole:
        for ch in parse_chars(args.whole):
            new_keys.append(ctx.layout.root(ord(ch)))
    for k in args.key or []:
        new_keys.append(GEO.SlotKey.parse(k))
    if args.count:
        new_keys.extend(ctx.plan.order[:args.count])
    new_keys = [k for k in dict.fromkeys(new_keys) if k not in reg.by_key]
    if not new_keys:
        print("Nothing to issue.")
        return
    if args.group:
        rank = {k: i for i, k in enumerate(new_keys)}
        first_of = {}
        for k in new_keys:
            first_of.setdefault(k.comp, rank[k])
        new_keys.sort(key=lambda k: (first_of[k.comp], k.role, -k.w * k.h))

    users = ctx.users()
    touched = set()
    for k in new_keys:
        if k.w > GEO.BODY_W or k.h > GEO.BODY_H:
            raise SystemExit(f"{k} does not fit the body box")
        u = users.get(k) or ([ord(k.comp)] if len(k.comp) == 1 and SRC.is_target(ord(k.comp)) else [])
        ex, bx, by = GL.pick_exemplar(ctx.layout, k, u, lambda x: x == k or ctx.is_leaf(x))
        e = reg.issue(k, bx, by, ex)
        touched.add(e.sheet)
        g = GN.generate_for(ctx.layout, k)
        if g is not None:
            # start from the generated glyphlette: the hand drawing replaces it
            ctx.sheets.cell(e)[by:by + k.h, bx:bx + k.w][g] = GL.COLOUR_INK
    reg.save()
    for n in sorted(touched):
        _write_sheet(ctx, n)
    first, last = reg.entries[-len(new_keys)], reg.entries[-1]
    print(f"Issued {len(new_keys)} glyphlettes: #{first.id}..#{last.id} "
          f"(sheet {first.sheet:02d} row {first.row} .. sheet {last.sheet:02d} row {last.row})")


def _write_sheet(ctx, n):
    ctx.sheets.render_guides(n, ctx.layout, ctx.is_leaf, ctx.drawn)
    ctx.sheets.save(n)
    os.makedirs(OUT_DIR, exist_ok=True)
    first = n * GL.PER_SHEET
    entries = ctx.registry.entries[first:first + GL.PER_SHEET]
    idx = os.path.join(OUT_DIR, f"sheet_{n:02d}_index.png")
    R.sheet_index(ctx.sheets.image(n), entries, ctx.layout, idx)
    print(f"  {os.path.relpath(GL.sheet_path(n))}  (index: {os.path.relpath(idx)})")


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
    orphans = [e for e in reg.entries if e.key not in needed]
    if orphans:
        print(f"Orphans            : {len(orphans)} issued glyphlettes are no longer in the plan "
              f"(still usable if drawn)")
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
    for comp, rule in layout.rules.items():
        if model.get(comp) is None:
            report(f"layout.tsv: {comp} is not a component of the model")
        for op, ins in (rule.insets or {}).items():
            l, t, r, b = ins
            if (GEO.REF - l - r) % 2 == 0 or (GEO.REF - t - b) % 2 == 0:
                report(f"layout.tsv: {comp} {op}{','.join(map(str, ins))} leaves an even-sized inner box")

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
        for i, (x1, y1, w1, h1, c1) in enumerate(boxes):
            for x2, y2, w2, h2, c2 in boxes[i + 1:]:
                if x1 < x2 + w2 and x2 < x1 + w1 and y1 < y2 + h2 and y2 < y1 + h1:
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

    p = sub.add_parser('status', help='drawing progress and sheet problems')
    p.set_defaults(func=cmd_status)

    p = sub.add_parser('generated', help='gallery of the generated glyphlettes the plan uses')
    p.add_argument('-o', '--output')
    p.set_defaults(func=cmd_generated)

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
