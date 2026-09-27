"""
Planner: decides which glyphlettes have to be drawn, and in what order.

Every target ideograph has a slot tree (see geometry.py). Any slot in that tree
may be drawn whole as a glyphlette, in which case nothing below it is needed.
Choosing *where to cut* every tree is a trade-off:

  - cutting low (small parts) lets parts be shared between many characters,
  - cutting high (drawing big chunks or whole characters) avoids drawing parts
    that nothing else uses, and looks better.

The planner minimises the total drawing effort, where drawing a glyphlette costs
OVERHEAD + (strokes of its component), plus a quality cost for every joint
between glyphlettes in every assembled character (JOINT_COST, or JOINED_JOINT_COST
where strokes have to meet across the joint). Starting from the deepest
possible cut, it collapses a slot into a single glyphlette whenever the glyphlettes
used only underneath it, plus the joints it would remove, cost at least as much as
drawing the slot itself. Slots are visited from
the smallest up, and passes repeat until nothing changes. Glyphlettes that are
already issued for drawing cost nothing, so later plans build on existing work.

The resulting glyphlettes are then put in drawing order by a greedy pass that
always picks the glyphlette bringing the most (frequency-weighted) characters
closest to completion per unit of effort.
"""

import heapq
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Dict, List, Set

import glyphlettes as GL
from geometry import SIZE_TOLERANCE, Layout, SlotKey

OVERHEAD = 3        # effort of drawing any glyphlette, in strokes
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
    uses: Dict[SlotKey, int]                 # glyphlette to draw -> number of slots it serves
    chars: Dict[int, List[SlotKey]]          # code point -> glyphlettes to draw for it
    why: Dict[SlotKey, str] = field(default_factory=dict)  # why the planner draws a splittable slot whole
    cover: Dict[SlotKey, SlotKey] = field(default_factory=dict)  # leaf slot -> glyphlette drawn for it
    generated: Set[SlotKey] = field(default_factory=set)   # leaf slots that are generated
    order: List[SlotKey] = field(default_factory=list)
    progress: List[Dict[int, int]] = field(default_factory=list)  # after each glyphlette: tier -> completed chars

    def glyphlettes(self) -> Set[SlotKey]:
        return set(self.uses)


def cost(layout: Layout, key: SlotKey) -> int:
    return OVERHEAD + layout.strokes(key.comp)


def make_plan(layout: Layout, targets: List[int], sunk: Set[SlotKey] = frozenset(), free=None,
              unit=None, verbose=True) -> Plan:
    """
    sunk: glyphlettes already issued (cost nothing, reused by nearby sizes)
    free: predicate for slots that are generated rather than drawn (cost nothing)
    unit: predicate for slots that must never be split
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

    def price(k):
        return 0 if k in sunk or free(k) else cost(layout, k)

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
            saved = sum(price(e) for e, m in sub.items()
                        if e in leaves and counts.get(e, 0) == n * m)
            pieces = sum(m for e, m in sub.items() if e in leaves)
            joints = n * (own_joints(t) + sum(m * own_joints(u) for u, m in sub.items() if u not in leaves))
            if price(t) - saved - joints <= WHOLE_BIAS:
                leaves.add(t)
                why[t] = (f"assembled in {n} character{'s' if n != 1 else ''} from {pieces} pieces: "
                          f"drawing it whole ({price(t)}) is cheaper than the joints ({joints:g}) "
                          f"and the pieces only it uses ({saved})")
                for d, m in sub.items():
                    counts[d] -= n * m
                changed += 1
        closed = close_downward()
        if closed:
            counts = count_occurrences()
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
    cover = choose_drawings({k: n for k, n in slot_uses.items() if k not in generated}, sunk)
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
    if verbose:
        reused = sum(1 for k, d in cover.items() if k != d)
        print(f"  {len(generated)} slots generated, {reused} slots reuse a drawing up to "
              f"{SIZE_TOLERANCE}px smaller")
    plan = Plan(layout, leaves, dict(uses), chars, why, cover, generated)
    order_plan(plan, sunk)
    return plan


def choose_drawings(slot_uses: Dict[SlotKey, int], sunk: Set[SlotKey]) -> Dict[SlotKey, SlotKey]:
    """
    Decide which sizes of each component to draw. A drawing may serve slots up to
    SIZE_TOLERANCE px larger per axis, so per component: issued drawings serve what
    they can, then sizes are drawn in order of use (the most used sizes exactly),
    each serving the rarer sizes just above it. Returns slot -> glyphlette drawn for it,
    always the largest drawing that fits, as the assembler picks.
    """
    by_comp: Dict[str, List[SlotKey]] = defaultdict(list)
    cover: Dict[SlotKey, SlotKey] = {}
    for k in slot_uses:
        if k.role:
            cover[k] = k          # frames are drawn at their exact size
        else:
            by_comp[k.comp].append(k)
    issued: Dict[str, List[SlotKey]] = defaultdict(list)
    for k in sunk:
        if not k.role:
            issued[k.comp].append(k)
    for comp, slots in by_comp.items():
        drawings = list(issued.get(comp, ()))
        covered = {t for t in slots if any(GL.reusable_for(d, t) or d == t for d in drawings)}
        for s in sorted(slots, key=lambda k: (-slot_uses[k], k.w * k.h, k)):
            if s in covered:
                continue
            drawings.append(s)
            covered.update(t for t in slots if t == s or GL.reusable_for(s, t))
        for t in slots:
            fits = [d for d in drawings if d == t or GL.reusable_for(d, t)]
            cover[t] = t if t in fits else max(fits, key=lambda d: (d.w * d.h, d.w))
    return cover


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
