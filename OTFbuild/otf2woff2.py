#!/usr/bin/env python3
"""Convert an OTF/TTF font to WOFF2 format."""
import sys
from fontTools.ttLib import TTFont

src, dst = sys.argv[1], sys.argv[2]
font = TTFont(src)
font.flavor = 'woff2'
font.save(dst)
print(f"  Written {dst}")
