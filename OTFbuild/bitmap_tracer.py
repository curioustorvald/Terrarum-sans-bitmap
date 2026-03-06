"""
Convert 1-bit bitmap arrays to CFF outlines by tracing connected pixel blobs.

Each connected component of filled pixels becomes a single closed contour
(plus additional contours for any holes). Adjacent collinear edges are
merged, minimising vertex count.

Scale: x = col * SCALE, y = (BASELINE_ROW - row) * SCALE
where BASELINE_ROW = 16 (ascent in pixels).
"""

from typing import List, Tuple

import sheet_config as SC

SCALE = SC.SCALE
BASELINE_ROW = 16  # pixels from top to baseline


def _turn_priority(in_dx, in_dy, out_dx, out_dy):
    """
    Return priority for outgoing direction relative to incoming.
    Lower = preferred (rightmost turn in y-down grid coordinates).

    This produces outer contours that are CW in font coordinates (y-up)
    and hole contours that are CCW, matching the non-zero winding rule.
    """
    # Normalise to unit directions
    nidx = (1 if in_dx > 0 else -1) if in_dx else 0
    nidy = (1 if in_dy > 0 else -1) if in_dy else 0
    ndx = (1 if out_dx > 0 else -1) if out_dx else 0
    ndy = (1 if out_dy > 0 else -1) if out_dy else 0

    # Right turn in y-down coords: (-in_dy, in_dx)
    if (ndx, ndy) == (-nidy, nidx):
        return 0
    # Straight
    if (ndx, ndy) == (nidx, nidy):
        return 1
    # Left turn: (in_dy, -in_dx)
    if (ndx, ndy) == (nidy, -nidx):
        return 2
    # U-turn
    return 3


def _simplify(contour):
    """Remove collinear intermediate vertices from a rectilinear contour."""
    n = len(contour)
    if n < 3:
        return contour
    result = []
    for i in range(n):
        p = contour[(i - 1) % n]
        c = contour[i]
        q = contour[(i + 1) % n]
        # Cross product of consecutive edge vectors
        if (c[0] - p[0]) * (q[1] - c[1]) - (c[1] - p[1]) * (q[0] - c[0]) != 0:
            result.append(c)
    return result if len(result) >= 3 else contour


def trace_bitmap(bitmap, glyph_width_px):
    """
    Convert a bitmap to polygon contours by tracing connected pixel blobs.

    Returns a list of contours, where each contour is a list of (x, y)
    tuples in font units.  Outer contours are clockwise, hole contours
    counter-clockwise (non-zero winding rule).
    """
    if not bitmap or not bitmap[0]:
        return []

    h = len(bitmap)
    w = len(bitmap[0])

    def filled(r, c):
        return 0 <= r < h and 0 <= c < w and bitmap[r][c]

    # -- Step 1: collect directed boundary edges --
    # Pixel (r, c) occupies grid square (c, r)-(c+1, r+1).
    # Edge direction keeps the filled region to the left (in y-down coords).
    edge_map = {}  # start_vertex -> [end_vertex, ...]

    for r in range(h):
        for c in range(w):
            if not bitmap[r][c]:
                continue
            if not filled(r - 1, c):          # top boundary
                edge_map.setdefault((c, r), []).append((c + 1, r))
            if not filled(r + 1, c):          # bottom boundary
                edge_map.setdefault((c + 1, r + 1), []).append((c, r + 1))
            if not filled(r, c - 1):          # left boundary
                edge_map.setdefault((c, r + 1), []).append((c, r))
            if not filled(r, c + 1):          # right boundary
                edge_map.setdefault((c + 1, r), []).append((c + 1, r + 1))

    if not edge_map:
        return []

    # -- Step 2: trace contours using rightmost-turn rule --
    used = set()
    contours = []

    for sv in sorted(edge_map):
        for ev in edge_map[sv]:
            if (sv, ev) in used:
                continue

            path = [sv]
            prev, curr = sv, ev
            used.add((sv, ev))

            while curr != sv:
                path.append(curr)
                idx, idy = curr[0] - prev[0], curr[1] - prev[1]

                candidates = [e for e in edge_map.get(curr, [])
                              if (curr, e) not in used]
                if not candidates:
                    break

                best = min(candidates,
                           key=lambda e: _turn_priority(
                               idx, idy, e[0] - curr[0], e[1] - curr[1]))

                used.add((curr, best))
                prev, curr = curr, best

            path = _simplify(path)
            if len(path) >= 3:
                contours.append([
                    (x * SCALE, (BASELINE_ROW - y) * SCALE)
                    for x, y in path
                ])

    return contours


def draw_glyph_to_pen(contours, pen, x_offset=0, y_offset=0):
    """
    Draw polygon contours to a T2CharStringPen (or compatible pen).

    Each contour is a list of (x, y) vertices forming a closed polygon.
    x_offset/y_offset shift all contours (used for alignment positioning).
    """
    for contour in contours:
        x, y = contour[0]
        pen.moveTo((x + x_offset, y + y_offset))
        for x, y in contour[1:]:
            pen.lineTo((x + x_offset, y + y_offset))
        pen.closePath()
