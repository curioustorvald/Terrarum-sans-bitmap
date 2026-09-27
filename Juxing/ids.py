"""
Ideographic Description Sequence (IDS) parsing.

A decomposition node is either
  - a leaf: a component name (str) -- a single character such as '氵', or an
    unencoded component such as '{12}', or
  - an operator node: a tuple (op, child, child[, child]).

Operator nodes are hashable, so identical sub-expressions share one identity.
"""

from typing import List, Tuple, Union

Node = Union[str, tuple]

# binary operators (IDS 1.x plus the Unicode 15.1 additions)
IDC_BINARY = frozenset('⿰⿱⿴⿵⿶⿷⿸⿹⿺⿻⿼⿽㇯')
# ternary operators
IDC_TERNARY = frozenset('⿲⿳')
# unary operators (Unicode 15.1): mirror, rotate
IDC_UNARY = frozenset('⿾⿿')

IDC_ALL = IDC_BINARY | IDC_TERNARY | IDC_UNARY

# operators whose children can be laid out in rectangular boxes
SPLIT_H = frozenset('⿰⿲')  # left to right
SPLIT_V = frozenset('⿱⿳')  # top to bottom
SURROUND = frozenset('⿴⿵⿶⿷⿸⿹⿺⿼⿽')
# operators the layout engine cannot express; such nodes are always drawn whole
UNLAYOUTABLE = frozenset('⿻㇯⿾⿿')

IVI = '〾'  # Ideographic Variation Indicator
UNREPRESENTABLE = '？'


def arity(op: str) -> int:
    if op in IDC_BINARY:
        return 2
    if op in IDC_TERNARY:
        return 3
    if op in IDC_UNARY:
        return 1
    return 0


def tokenise(s: str) -> List[str]:
    """Split an IDS string into tokens; '{n}' is one token."""
    toks = []
    i = 0
    while i < len(s):
        if s[i] == '{':
            j = s.index('}', i)
            toks.append(s[i:j + 1])
            i = j + 1
        else:
            toks.append(s[i])
            i += 1
    return toks


def parse(s: str) -> Node:
    """Parse an IDS string into a node. Raises ValueError on malformed input."""
    toks = tokenise(s)
    node, end = _parse(toks, 0)
    if end != len(toks):
        raise ValueError(f"Trailing tokens in IDS '{s}'")
    return node


def _parse(toks: List[str], i: int) -> Tuple[Node, int]:
    if i >= len(toks):
        raise ValueError("Truncated IDS")
    t = toks[i]
    n = arity(t)
    if n == 0:
        return t, i + 1
    children = []
    i += 1
    for _ in range(n):
        c, i = _parse(toks, i)
        children.append(c)
    return (t, *children), i


def to_string(node: Node) -> str:
    if isinstance(node, str):
        return node
    return node[0] + ''.join(to_string(c) for c in node[1:])


def is_op(node: Node) -> bool:
    return isinstance(node, tuple)


def leaves(node: Node):
    """Yield every leaf component of a node."""
    if isinstance(node, str):
        yield node
    else:
        for c in node[1:]:
            yield from leaves(c)
