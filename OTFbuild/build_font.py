#!/usr/bin/env python3
"""
Terrarum Sans Bitmap OTF Builder v2 — Python + fonttools

Builds a TTF font with both vector-traced outlines (TrueType glyf)
and embedded bitmap strike (EBDT/EBLC) from TGA sprite sheets.

Usage:
    python3 OTFbuild/build_font.py src/assets -o OTFbuild/TerrarumSansBitmap.otf

Options:
    --no-bitmap     Skip EBDT/EBLC bitmap strike
    --no-features   Skip GSUB/GPOS OpenType features
"""

import argparse
import sys
import os

# Add OTFbuild dir to path for imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from font_builder import build_font


def main():
    parser = argparse.ArgumentParser(
        description="Build Terrarum Sans Bitmap TTF from TGA sprite sheets"
    )
    parser.add_argument(
        "assets_dir",
        help="Path to assets directory containing TGA sprite sheets"
    )
    parser.add_argument(
        "-o", "--output",
        default="OTFbuild/TerrarumSansBitmap.otf",
        help="Output OTF file path (default: OTFbuild/TerrarumSansBitmap.otf)"
    )
    parser.add_argument(
        "--no-bitmap",
        action="store_true",
        help="Skip EBDT/EBLC bitmap strike"
    )
    parser.add_argument(
        "--no-features",
        action="store_true",
        help="Skip GSUB/GPOS OpenType features"
    )

    args = parser.parse_args()

    if not os.path.isdir(args.assets_dir):
        print(f"Error: assets directory not found: {args.assets_dir}", file=sys.stderr)
        sys.exit(1)

    # Ensure output directory exists
    output_dir = os.path.dirname(args.output)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    print(f"Terrarum Sans Bitmap OTF Builder v2")
    print(f"  Assets: {args.assets_dir}")
    print(f"  Output: {args.output}")
    print()

    build_font(
        assets_dir=args.assets_dir,
        output_path=args.output,
        no_bitmap=args.no_bitmap,
        no_features=args.no_features,
    )


if __name__ == "__main__":
    main()
