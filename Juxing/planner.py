"""
Planner: decides which glyphlettes have to be drawn, and in what order.

Every target ideograph has a slot tree (see geometry.py). Any slot in that tree
may be drawn whole as a glyphlette, in which case nothing below it is needed.
Choosing *where to cut* every tree is a trade-off:

  - cutting low (small parts) lets parts be shared between many characters,
  - cutting high (drawing big chunks or whole characters) avoids drawing parts
    that nothing else uses, and looks better.

A component is drawn once per *family* (glyphlettes.family: component, frame role
and aspect class), its *base glyphlette*, and its other sizes in that family are
derived from it (resize.py). So the effort is counted per family: the first size of
a family costs OVERHEAD + (strokes of its component), and every further size a share
of that (DERIVED_SHARE), the risk that it needs an override drawn by hand. Joints
between glyphlettes in every assembled character add a quality cost (JOINT_COST, or
JOINED_JOINT_COST where strokes have to meet across the joint).

Starting from the deepest possible cut, the planner collapses a slot into a single
glyphlette whenever the glyphlettes used only underneath it, plus the joints it would
remove, cost at least as much as drawing the slot itself. Slots are visited from the
smallest up, and passes repeat until nothing changes. Families that already have an
issued drawing cost nothing to start, so later plans build on existing work.

The plan then chooses the drawings of each family (choose_drawings): the base at the
largest size the family needs, plus an override for every size whose derivation from
a drawing on the sheets has been declined. The drawings are put in drawing order by a
greedy pass that always picks the one bringing the most (frequency-weighted)
characters closest to completion per unit of effort.
"""

import heapq
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Set

import glyphlettes as GL
from geometry import Layout, SlotKey

OVERHEAD = 3        # effort of drawing any glyphlette, in strokes
# Effort of a further size of a family, as a share of drawing its base: the risk that
# the derived size is declined or looks wrong and needs an override.
DERIVED_SHARE = 0.2
WHOLE_BIAS = 0      # collapse a slot even if that costs up to this much more effort
# Quality cost of one joint (a boundary between two assembled glyphlettes) in one
# character, in strokes of drawing effort. Drawing a slot whole removes its joints
# from every character using it, so slots assembled in many characters get drawn
# whole (糹@5x15), while rare assemblies stay assembled. Joined joints (strokes
# that must meet across the boundary, see geometry.Layout.joints) are riskier than
# separate ones: the protrusions have to line up in every character.
JOINT_COST = 0.5
JOINED_JOINT_COST = 1.5
TIER_WEIGHT = {1: 100.0, 2: 20.0, 3: 4.0, 4: 1.0}


@dataclass
class Plan:
    layout: Layout
    leaves: Set[SlotKey]                     # slots drawn whole (where the trees are cut)
    uses: Dict[SlotKey, int]                 # drawing -> number of slots it serves
    chars: Dict[int, List[SlotKey]]          # code point -> drawings it needs
    why: Dict[SlotKey, str] = field(default_factory=dict)  # why the planner draws a splittable slot whole
    cover: Dict[SlotKey, SlotKey] = field(default_factory=dict)  # leaf slot -> drawing it comes from
    generated: Set[SlotKey] = field(default_factory=set)   # leaf slots that are generated
    declined: Dict[SlotKey, List[SlotKey]] = field(default_factory=dict)  # slot -> drawings it declined
    order: List[SlotKey] = field(default_factory=list)
    progress: List[Dict[int, int]] = field(default_factory=list)  # after each glyphlette: tier -> completed chars

    def glyphlettes(self) -> Set[SlotKey]:
        return set(self.uses)

    def families(self) -> Dict[GL.Family, 'FamilyPlan']:
        out: Dict[GL.Family, FamilyPlan] = {}
        for k, d in self.cover.items():
            f = out.setdefault(GL.family(d), FamilyPlan())
            f.drawings.add(d)
            if k != d:
                f.derived.add(k)
        for f in out.values():
            f.base = max(f.drawings, key=lambda k: (k.w * k.h, k.w, k.h))
        return out

    def summary(self) -> str:
        """The drawing effort in one line: bases, derived sizes per base, overrides."""
        fams = self.families()
        per = sorted(len(f.derived) for f in fams.values()) or [0]
        q = lambda p: per[round(p * (len(per) - 1))]
        derived = sum(per)
        overrides = sum(len(f.drawings) - 1 for f in fams.values())
        return (f"{len(fams)} base glyphlettes ({derived} glyphlettes derived; min {per[0]}, 25% {q(.25)}, "
                f"50% {q(.5)}, 75% {q(.75)}, max {per[-1]} per base glyphlette; "
                f"{overrides} glyphlettes overridden)")


@dataclass
class FamilyPlan:
    drawings: Set[SlotKey] = field(default_factory=set)   # base and overrides
    derived: Set[SlotKey] = field(default_factory=set)    # sizes derived from them
    base: SlotKey = None                                   # the largest drawing


def cost(layout: Layout, key: SlotKey) -> int:
    return OVERHEAD + layout.strokes(key.comp)


def make_plan(layout: Layout, targets: List[int], sunk: Set[SlotKey] = frozenset(), free=None,
              unit=None, derive_ok=None, verbose=True, blank: Set[SlotKey] = frozenset()) -> Plan:
    """
    sunk: glyphlettes already issued (their families cost nothing to start)
    blank: the issued ones with nothing drawn yet. A blank one whose size no character's layout
        has any more (the layout changed since it was issued: 讠@9x15, issued while 训 was
        mis-measured) is no drawing at all: it is left out, so its family gets a base at a size
        in use, and it shows as an orphan. A drawn one is kept whatever its size.
    free: predicate for slots that are generated rather than drawn (cost nothing)
    unit: predicate for slots that must never be split
    derive_ok(src, dst): whether dst derives acceptably from the drawing of src; None
        while src is not drawn (assumed to work)
    """
    free = free or (lambda k: False)
    unit = unit or (lambda k: False)
    roots = {cp: layout.root(cp) for cp in targets}

    # discover every reachable slot (in a fixed order, so that plans are reproducible)
    stack = sorted(set(roots.values()), reverse=True)
    seen = set(stack)
    while stack:
        k = stack.pop()
        s = layout.slot(k)
        if s.parts and not unit(k):
            for c, _, _ in s.parts:
                if c not in seen:
                    seen.add(c)
                    stack.append(c)
    if verbose:
        print(f"  {len(seen)} distinct slots reachable from {len(targets)} characters")
    passed_over = {k for k in sunk if k in blank and k not in seen}
    sunk = set(sunk) - passed_over
    if verbose and passed_over:
        print(f"  {len(passed_over)} blank issued glyphlettes passed over (no layout uses their size): "
              f"{' '.join(map(str, sorted(passed_over)))}")

    started = {GL.family(k) for k in sunk}
    family_of = {k: GL.family(k) for k in seen}

    def family_cost(fam, sizes: int) -> float:
        """Effort of a family drawn in `sizes` sizes: its base, and a share of it for every further size."""
        if sizes <= 0:
            return 0.0
        full = OVERHEAD + layout.strokes(fam[0])
        return (0 if fam in started else full) + DERIVED_SHARE * full * (sizes - 1)

    leaves: Set[SlotKey] = {k for k in seen if layout.slot(k).parts is None or unit(k)}
    why: Dict[SlotKey, str] = {}

    def count_occurrences():
        counts: Dict[SlotKey, int] = defaultdict(int)
        # propagate from roots downward; children always have smaller area than
        # their parents except frames, which are leaves, so area order is topological
        for r in roots.values():
            counts[r] += 1
        for k in sorted(seen, key=lambda k: (-(k.w * k.h), k.role != '')):
            n = counts.get(k, 0)
            if n == 0 or k in leaves:
                continue
            for c, _, _ in layout.slot(k).parts:
                counts[c] += n
        return counts

    def in_use(counts) -> Dict[GL.Family, Set[SlotKey]]:
        """The sizes of every family that are drawn or derived (leaves in use, not generated)."""
        out: Dict[GL.Family, Set[SlotKey]] = defaultdict(set)
        for k in leaves:
            if counts.get(k, 0) > 0 and not free(k):
                out[family_of[k]].add(k)
        return out

    joint_cost: Dict[SlotKey, float] = {}

    def own_joints(k) -> float:
        c = joint_cost.get(k)
        if c is None:
            c = joint_cost[k] = sum(JOINED_JOINT_COST if j else JOINT_COST for j in layout.joints(k))
        return c

    def below(k) -> Dict[SlotKey, int]:
        """Active descendants of k with multiplicities (expansion stops at leaves)."""
        acc: Dict[SlotKey, int] = defaultdict(int)
        st = [(k, 1)]
        while st:
            x, m = st.pop()
            for c, _, _ in layout.slot(x).parts:
                acc[c] += m
                if c not in leaves:
                    st.append((c, m))
        return acc

    sizes_of = defaultdict(list)
    for k in seen:
        if not k.role:
            sizes_of[k.comp].append(k)

    def close_downward() -> int:
        """A component drawn whole in a box is drawn whole in every smaller box too
        (smaller only means more crowded), so a shared component such as 䜌 gets the
        same treatment in 灣 as in 彎 and 變."""
        added = 0
        for k in sorted((k for k in leaves if not k.role), key=lambda k: (-(k.w * k.h), k)):
            for k2 in sizes_of[k.comp]:
                if k2 not in leaves and k2.w <= k.w and k2.h <= k.h:
                    leaves.add(k2)
                    why[k2] = f"{k.comp} is drawn whole in the larger {k.w}x{k.h} box"
                    added += 1
        return added

    close_downward()
    counts = count_occurrences()
    active = in_use(counts)
    splittable = sorted((k for k in seen if k not in leaves), key=lambda k: (k.w * k.h, str(k)))
    for pass_no in range(1, 10):
        changed = 0
        for t in splittable:
            if t in leaves:
                continue
            n = counts.get(t, 0)
            if n == 0:
                continue
            sub = below(t)
            gone = [e for e, m in sub.items() if e in leaves and counts.get(e, 0) == n * m and not free(e)]
            # the families whose sizes change: those only used under t lose them, t's gains t
            change: Dict[GL.Family, int] = defaultdict(int)
            for e in gone:
                change[family_of[e]] -= 1
            if not free(t):
                change[family_of[t]] += 1
            delta = sum(family_cost(f, len(active[f]) + d) - family_cost(f, len(active[f]))
                        for f, d in change.items())
            pieces = sum(m for e, m in sub.items() if e in leaves)
            joints = n * (own_joints(t) + sum(m * own_joints(u) for u, m in sub.items() if u not in leaves))
            if delta - joints <= WHOLE_BIAS:
                leaves.add(t)
                own = family_cost(family_of[t], len(active[family_of[t]]) + 1) - \
                    family_cost(family_of[t], len(active[family_of[t]]))
                why[t] = (f"assembled in {n} character{'s' if n != 1 else ''} from {pieces} pieces: "
                          f"drawing it whole ({own:g}{'' if active[family_of[t]] else ', a new base'}) "
                          f"is cheaper than the joints ({joints:g}) and the pieces only it uses "
                          f"({own - delta:g})")
                for d, m in sub.items():
                    counts[d] -= n * m
                for e in gone:
                    active[family_of[e]].discard(e)
                if not free(t):
                    active[family_of[t]].add(t)
                changed += 1
        closed = close_downward()
        if closed:
            counts = count_occurrences()
            active = in_use(counts)
        if verbose:
            print(f"  collapse pass {pass_no}: {changed} slots collapsed, {closed} smaller sizes followed")
        if not changed and not closed:
            break

    # final cut of every character
    cut: Dict[int, List[SlotKey]] = {}
    slot_uses: Dict[SlotKey, int] = defaultdict(int)
    for cp, r in roots.items():
        parts = []
        st = [r]
        while st:
            k = st.pop()
            if k in leaves:
                parts.append(k)
            else:
                st.extend(c for c, _, _ in reversed(layout.slot(k).parts))
        cut[cp] = parts
        for k in parts:
            slot_uses[k] += 1

    generated = {k for k in slot_uses if k not in sunk and free(k)}
    cover, declined = choose_drawings({k: n for k, n in slot_uses.items() if k not in generated}, sunk,
                                      derive_ok or (lambda s, d: None))
    chars: Dict[int, List[SlotKey]] = {}
    uses: Dict[SlotKey, int] = defaultdict(int)
    for cp, parts in cut.items():
        need = []
        for k in parts:
            if k in generated:
                continue
            d = cover[k]
            uses[d] += 1
            if d not in need:
                need.append(d)
        chars[cp] = need
    plan = Plan(layout, leaves, dict(uses), chars, why, cover, generated, declined)
    if verbose:
        print(f"  {len(generated)} slots generated, {sum(1 for k, d in cover.items() if k != d)} derived")
    order_plan(plan, sunk)
    return plan


def choose_drawings(slot_uses: Dict[SlotKey, int], sunk: Set[SlotKey], derive_ok):
    """
    Decide what to draw in each family. Returns (slot -> drawing it comes from,
    slot -> drawings that declined it).

      - Sizes are derived from the issued drawings of their family where they can be
        (the closest one first, see glyphlettes.source_order): optimistically while a
        drawing is still blank, and only if its derivation is acceptable once drawn.
      - Sizes that no issued drawing can reach get one new drawing: the most used of
        them that can serve all the others (up to SIZE_TOLERANCE px smaller than the
        largest), so the most common size is drawn exactly; failing that, the smallest
        size covering all of them. So a family's base is its largest size, or nearly.
      - Sizes declined by every drawing that could serve them are drawn exactly, as
        overrides, largest first; each serves the declined sizes it can, optimistically.
    """
    by_family: Dict[GL.Family, List[SlotKey]] = defaultdict(list)
    for k in slot_uses:
        by_family[GL.family(k)].append(k)
    issued: Dict[GL.Family, List[SlotKey]] = defaultdict(list)
    for k in sunk:
        issued[GL.family(k)].append(k)

    cover: Dict[SlotKey, SlotKey] = {}
    declined: Dict[SlotKey, List[SlotKey]] = {}

    def serve(slots, drawings):
        """Cover what the drawings can; returns (unreached, declined) slots."""
        unreached, refused = [], []
        for t in slots:
            if t in drawings:
                cover[t] = t
                continue
            said_no = []
            for s in sorted((d for d in drawings if GL.derivable(d, t)), key=lambda d: GL.source_order(d, t)):
                if derive_ok(s, t) is False:
                    said_no.append(s)
                    continue
                cover[t] = s
                break
            else:
                (refused if said_no else unreached).append(t)
            if said_no:
                declined[t] = said_no
        return unreached, refused

    for fam, slots in by_family.items():
        drawings = list(issued.get(fam, ()))
        unreached, refused = serve(sorted(slots), drawings)
        if unreached:
            reach_all = [k for k in unreached if all(t == k or GL.derivable(k, t) for t in unreached)]
            drawings.append(max(reach_all, key=lambda k: (slot_uses[k], k.w * k.h, k)) if reach_all
                            else GL.bounding(unreached))
            more_unreached, more_refused = serve(unreached + refused, drawings)
            refused = more_unreached + more_refused
        for t in sorted(refused, key=lambda k: (-(k.w * k.h), k)):
            if t in cover:
                continue
            drawings.append(t)
            serve([u for u in refused if u not in cover], drawings)
    return cover, declined


def order_plan(plan: Plan, done: Set[SlotKey] = frozenset()):
    """Greedy drawing order. Glyphlettes in `done` are considered drawn already."""
    model = plan.layout.model
    weight = {cp: TIER_WEIGHT.get(model[chr(cp)].tier, 1.0) for cp in plan.chars}
    tier = {cp: model[chr(cp)].tier for cp in plan.chars}
    users: Dict[SlotKey, List[int]] = defaultdict(list)
    missing: Dict[int, int] = {}
    for cp, parts in plan.chars.items():
        need = set(parts) - set(done)
        missing[cp] = len(need)
        for k in need:
            users[k].append(cp)

    def score(k):
        return sum(weight[cp] / missing[cp] for cp in users[k] if missing[cp] > 0) / cost(plan.layout, k)

    completed = defaultdict(int)
    for cp, n in missing.items():
        if n == 0:
            completed[tier[cp]] += 1

    heap = [(-score(k), str(k), k) for k in users]
    heapq.heapify(heap)
    current = {h[2]: -h[0] for h in heap}
    drawn: Set[SlotKey] = set(done)
    order, progress = [], []
    while heap:
        neg, _, k = heapq.heappop(heap)
        if k in drawn or abs(-neg - current[k]) > 1e-12:
            continue
        drawn.add(k)
        order.append(k)
        touched = set()
        for cp in users[k]:
            missing[cp] -= 1
            if missing[cp] == 0:
                completed[tier[cp]] += 1
            else:
                touched.update(p for p in plan.chars[cp] if p not in drawn)
        for p in touched:
            current[p] = score(p)
            heapq.heappush(heap, (-current[p], str(p), p))
        progress.append(dict(completed))
    plan.order = order
    plan.progress = progress
