"""
Convert 1-bit bitmap arrays to TrueType quadratic outlines.

Each set pixel becomes part of a rectangle contour drawn clockwise.
Adjacent identical horizontal runs are merged vertically into rectangles.

Scale: x_left = col * SCALE, y_top = (BASELINE_ROW - row) * SCALE
where BASELINE_ROW = 16 (ascent in pixels).
"""

from typing import Dict, List, Tuple

import sheet_config as SC

SCALE = SC.SCALE
BASELINE_ROW = 16  # pixels from top to baseline


def trace_bitmap(bitmap, glyph_width_px):
    """
    Convert a bitmap to a list of rectangle contours.

    Each rectangle is ((x0, y0), (x1, y1)) in font units, where:
    - (x0, y0) is bottom-left
    - (x1, y1) is top-right

    Returns list of (x0, y0, x1, y1) tuples representing rectangles.
    """
    if not bitmap or not bitmap[0]:
        return []

    h = len(bitmap)
    w = len(bitmap[0])

    # Step 1: Find horizontal runs per row
    runs = []  # list of (row, col_start, col_end)
    for row in range(h):
        col = 0
        while col < w:
            if bitmap[row][col]:
                start = col
                while col < w and bitmap[row][col]:
                    col += 1
                runs.append((row, start, col))
            else:
                col += 1

    # Step 2: Merge vertically adjacent identical runs into rectangles
    rects = []  # (row_start, row_end, col_start, col_end)
    used = [False] * len(runs)

    for i, (row, cs, ce) in enumerate(runs):
        if used[i]:
            continue
        # Try to extend this run downward
        row_end = row + 1
        j = i + 1
        while j < len(runs):
            r2, cs2, ce2 = runs[j]
            if r2 > row_end:
                break
            if r2 == row_end and cs2 == cs and ce2 == ce and not used[j]:
                used[j] = True
                row_end = r2 + 1
            j += 1
        rects.append((row, row_end, cs, ce))

    # Step 3: Convert to font coordinates
    contours = []
    for row_start, row_end, col_start, col_end in rects:
        x0 = col_start * SCALE
        x1 = col_end * SCALE
        y_top = (BASELINE_ROW - row_start) * SCALE
        y_bottom = (BASELINE_ROW - row_end) * SCALE
        contours.append((x0, y_bottom, x1, y_top))

    return contours


def draw_glyph_to_pen(contours, pen, x_offset=0, y_offset=0):
    """
    Draw rectangle contours to a TTGlyphPen or similar pen.
    Each rectangle is drawn as a clockwise closed contour (4 on-curve points).

    x_offset/y_offset shift all contours (used for alignment positioning).
    """
    for x0, y0, x1, y1 in contours:
        ax0 = x0 + x_offset
        ax1 = x1 + x_offset
        ay0 = y0 + y_offset
        ay1 = y1 + y_offset
        # Clockwise: bottom-left -> top-left -> top-right -> bottom-right
        pen.moveTo((ax0, ay0))
        pen.lineTo((ax0, ay1))
        pen.lineTo((ax1, ay1))
        pen.lineTo((ax1, ay0))
        pen.closePath()
