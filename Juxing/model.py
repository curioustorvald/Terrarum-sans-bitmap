"""
The Juxing Han model: what every covered ideograph is made of.

The model is a table of *components*. Every target ideograph (CJK Unified
Ideographs + Extension A) is a component, and so is everything those ideographs
are built from, recursively (radical forms, Extension B+ phonetics, CJK strokes,
unencoded shapes such as '{12}' = right of 龍). Each component has exactly one
decomposition, chosen for the preferred region (G = China by default), or is an
atom (its decomposition is itself).

The model is generated from Unihan + BabelStone IDS (+ CNS 11643 stroke sequences)
by `juxing.py build-model`
and written to model/han_model.tsv. Hand corrections go in model/overrides.tsv
and are applied on top of the upstream data at build time; never edit the
generated table directly.
"""

import os
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Set

import ids as IDS
import sources as SRC

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(HERE, 'model')
MODEL_PATH = os.path.join(MODEL_DIR, 'han_model.tsv')
OVERRIDES_PATH = os.path.join(MODEL_DIR, 'overrides.tsv')

DEFAULT_REGION_PREF = 'GHTKJPVUSBXZ'

CJK_STROKES = range(0x31C0, 0x31F0)


@dataclass
class Component:
    name: str               # the component itself: a character, '{n}', or an IDS expression
    decomp: IDS.Node        # its decomposition; == name for atoms
    strokes: int
    cp: Optional[int] = None
    region: str = '-'       # IRG source letter the decomposition was taken from
    tier: int = 0           # 1..4 for target ideographs, 0 for pure components
    flags: Set[str] = field(default_factory=set)
    note: str = ''
    sequence: str = ''      # CNS 11643 stroke types: 1 horizontal, 2 vertical, 3 left-falling, 4 dot, 5 turning

    @property
    def is_atom(self) -> bool:
        return isinstance(self.decomp, str)

    @property
    def is_target(self) -> bool:
        return self.tier > 0


class HanModel:
    def __init__(self, components: Dict[str, Component]):
        self.components = components
        self.targets: List[int] = sorted(c.cp for c in components.values() if c.is_target)
        # how many components use each component directly
        self.parents: Dict[str, int] = {}
        for c in components.values():
            if not isinstance(c.decomp, str):
                for leaf in set(IDS.leaves(c.decomp)):
                    self.parents[leaf] = self.parents.get(leaf, 0) + 1

    def __getitem__(self, name: str) -> Component:
        return self.components[name]

    def get(self, name: str) -> Optional[Component]:
        return self.components.get(name)

    def strokes(self, node: IDS.Node) -> int:
        """Stroke count of a component name or an IDS expression."""
        if isinstance(node, str):
            c = self.components.get(node)
            return c.strokes if c else 3
        return sum(self.strokes(ch) for ch in node[1:])

    def target_chars(self) -> Iterable[Component]:
        for cp in self.targets:
            yield self.components[chr(cp)]


# ---------------------------------------------------------------------------
# Building

def read_overrides(path=OVERRIDES_PATH) -> Dict[str, tuple]:
    """
    overrides.tsv: <char or U+XXXX> <TAB> <IDS> [<TAB> note]
    An IDS equal to the character itself makes it an atom (always drawn whole).
    """
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding='utf-8') as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            cols = line.split('\t')
            key = cols[0].strip()
            if key.startswith('U+'):
                key = chr(int(key[2:], 16))
            ids = cols[1].strip()
            try:
                IDS.parse(ids)
            except ValueError as e:
                raise ValueError(f"{path}:{lineno}: bad IDS '{ids}': {e}")
            out[key] = (ids, cols[2].strip() if len(cols) > 2 else '')
    return out


def build(region_pref=DEFAULT_REGION_PREF) -> HanModel:
    print("Reading Unihan...")
    unihan = SRC.read_unihan()
    print("Reading BabelStone IDS...")
    entries, unencoded = SRC.read_babelstone_ids()
    equiv = SRC.read_equivalent_ideographs()
    radicals = SRC.read_radicals()
    print("Reading CNS 11643 stroke sequences...")
    sequences = SRC.read_cns_stroke_sequences()
    # unified ideographs that stand for a radical-block form, e.g. 糹 (U+2EAF), 飠 (U+2EDE)
    radical_forms = {u for r, u in equiv.items() if 0x2E80 <= ord(r) <= 0x2FDF}
    overrides = read_overrides()

    # chosen decomposition string per encoded character
    chosen: Dict[str, tuple] = {}
    for ch, e in entries.items():
        seq = SRC.pick_sequence(e, region_pref)
        if seq:
            chosen[ch] = seq

    # reverse index: IDS string -> character, used to name anonymous sub-expressions.
    # Target ideographs win over other characters; lower code points win otherwise.
    by_ids: Dict[str, str] = {}
    for ch in sorted(chosen, key=lambda c: (not SRC.is_target(ord(c)), ord(c))):
        ids_str = chosen[ch][0]
        if len(IDS.tokenise(ids_str)) > 1:
            by_ids.setdefault(ids_str, ch)

    components: Dict[str, Component] = {}

    def canonical(node: IDS.Node, owner: str) -> IDS.Node:
        """Replace anonymous sub-expressions that match an encoded character by that character."""
        if isinstance(node, str):
            return node
        s = IDS.to_string(node)
        named = by_ids.get(s)
        if named and named != owner:
            return named
        return (node[0], *(canonical(c, owner) for c in node[1:]))

    def decomposition_of(name: str):
        """Returns (node, region, flags, note) for a component name."""
        flags = set()
        note = ''
        if name in overrides:
            ids_str, note = overrides[name]
            flags.add('override')
            region = '*'
            ivi = False
        elif name in chosen:
            ids_str, region, ivi = chosen[name]
        else:
            return name, '-', flags, note
        if ivi:
            flags.add('ivi')
        if IDS.UNREPRESENTABLE in ids_str:
            flags.add('unrepresentable')
            return name, region, flags, note
        try:
            node = IDS.parse(ids_str)
        except ValueError:
            flags.add('malformed')
            return name, region, flags, note
        if node == name:
            return name, region, flags, note
        return canonical(node, name), region, flags, note

    def stroke_estimate(name: str, node: IDS.Node) -> int:
        if len(name) == 1:
            cp = ord(name)
            s = SRC.unihan_strokes(unihan.get(cp, {}))
            if s:
                return s
            if cp in CJK_STROKES:
                return 1
            if name in sequences:
                return len(sequences[name])
            if name in equiv:
                s = SRC.unihan_strokes(unihan.get(ord(equiv[name]), {}))
                if s:
                    return s
        if name in unencoded:
            frag = unencoded[name][1]
            if frag and IDS.UNREPRESENTABLE not in frag:
                try:
                    return max(1, sum(stroke_estimate(l, l) for l in IDS.leaves(IDS.parse(frag))))
                except ValueError:
                    pass
            return 3
        if not isinstance(node, str):
            return max(1, sum(stroke_estimate(l, l) for l in IDS.leaves(node)))
        return 3

    # breadth-first closure from the targets
    queue: List[str] = [chr(cp) for cp in SRC.target_codepoints()]
    seen = set(queue)
    while queue:
        name = queue.pop()
        node, region, flags, note = decomposition_of(name)
        cp = ord(name) if len(name) == 1 else None
        tier = SRC.unihan_tier(unihan.get(cp, {}), cp) if cp is not None and SRC.is_target(cp) else 0
        if name in unencoded:
            flags.add('unencoded')
            note = unencoded[name][0]
        if name in radicals or name in radical_forms:
            flags.add('radical')
        components[name] = Component(name, node, 0, cp, region, tier, flags, note, sequences.get(name, ''))
        # anonymous sub-expressions are handled by the layout engine directly;
        # only named leaves need entries of their own
        for leaf in IDS.leaves(node) if not isinstance(node, str) else ():
            if leaf not in seen:
                seen.add(leaf)
                queue.append(leaf)

    for name, comp in components.items():
        comp.strokes = stroke_estimate(name, comp.decomp)

    # Unencoded shapes without a usable fragment: infer the stroke count from the
    # characters that use them directly (owner strokes minus the other parts).
    inferred: Dict[str, List[int]] = {}
    for comp in components.values():
        node = comp.decomp
        if isinstance(node, str) or comp.strokes <= 0:
            continue
        parts = node[1:]
        for i, p in enumerate(parts):
            if isinstance(p, str) and p in unencoded and not unencoded[p][1].replace('？', ''):
                rest = sum(model_strokes(components, q) for j, q in enumerate(parts) if j != i)
                if comp.strokes - rest > 0:
                    inferred.setdefault(p, []).append(comp.strokes - rest)
    for name, values in inferred.items():
        values.sort()
        components[name].strokes = values[len(values) // 2]

    return HanModel(components)


def model_strokes(components: Dict[str, Component], node: IDS.Node) -> int:
    if isinstance(node, str):
        c = components.get(node)
        return c.strokes if c else 3
    return sum(model_strokes(components, ch) for ch in node[1:])


# ---------------------------------------------------------------------------
# Persistence

HEADER = """\
# Juxing Han model -- generated by `juxing.py build-model`. DO NOT EDIT;
# put corrections in overrides.tsv and rebuild.
#
# Sources: Unihan (Unicode License v3), BabelStone IDS (rights waived by the author),
# CNS 11643 stroke sequences: {attribution}.
#
# Columns:
#   component  a character, an unencoded shape {n}, or an IDS expression
#   cp         code point, '-' for unencoded shapes
#   ids        chosen decomposition; equal to the component for atoms
#   region     IRG source the decomposition describes; '*' = overridden
#   strokes    stroke count (Unihan kTotalStrokes, zh-Hans value) or an estimate
#   sequence   CNS 11643 stroke types: 1 horizontal, 2 vertical, 3 left-falling, 4 dot, 5 turning
#   tier       1..4 commonness for target ideographs, 0 for pure components
#   flags      ivi = approximate IDS, unrepresentable, override, unencoded, radical (Kangxi)
#   note       free text (description of unencoded shapes, override notes)
"""


def save(model: HanModel, path=MODEL_PATH):
    os.makedirs(os.path.dirname(path), exist_ok=True)

    def sort_key(c: Component):
        return (0 if c.is_target else 1, c.cp if c.cp is not None else 0x110000, c.name)

    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(HEADER.replace("{attribution}", SRC.CNS_ATTRIBUTION))
        for c in sorted(model.components.values(), key=sort_key):
            f.write('\t'.join([
                c.name,
                f"U+{c.cp:04X}" if c.cp is not None else '-',
                IDS.to_string(c.decomp),
                c.region,
                str(c.strokes),
                c.sequence or '-',
                str(c.tier),
                ','.join(sorted(c.flags)),
                c.note,
            ]) + '\n')


def load(path=MODEL_PATH) -> HanModel:
    if not os.path.exists(path):
        raise FileNotFoundError(f"{path} not found; run `make model` first")
    components = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            name, cp, ids_str, region, strokes, seq, tier, flags, note = line.rstrip('\n').split('\t')
            node = IDS.parse(ids_str) if ids_str != name else name
            components[name] = Component(
                name, node, int(strokes),
                int(cp[2:], 16) if cp != '-' else None,
                region, int(tier), set(filter(None, flags.split(','))), note, seq if seq != '-' else '')
    return HanModel(components)
