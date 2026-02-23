"""
TGA reader for uncompressed true-colour images (Type 2).
Stores pixels as RGBA8888: (R<<24 | G<<16 | B<<8 | A).

Matches the convention in TerrarumSansBitmap.kt where .and(255) checks
the alpha channel (lowest byte).
"""

import struct
from typing import List


class TgaImage:
    __slots__ = ('width', 'height', 'pixels')

    def __init__(self, width: int, height: int, pixels: List[int]):
        self.width = width
        self.height = height
        self.pixels = pixels  # flat array, row-major

    def get_pixel(self, x: int, y: int) -> int:
        """Get pixel at (x, y) as RGBA8888 (R in bits 31-24, A in bits 7-0)."""
        if x < 0 or x >= self.width or y < 0 or y >= self.height:
            return 0
        return self.pixels[y * self.width + x]


def read_tga(path: str) -> TgaImage:
    """Read an uncompressed true-colour TGA file."""
    with open(path, 'rb') as f:
        data = f.read()

    pos = 0

    def u8():
        nonlocal pos
        val = data[pos]
        pos += 1
        return val

    def u16():
        nonlocal pos
        val = struct.unpack_from('<H', data, pos)[0]
        pos += 2
        return val

    id_length = u8()
    colour_map_type = u8()
    image_type = u8()

    # colour map spec (5 bytes)
    u16(); u16(); u8()

    # image spec
    x_origin = u16()
    y_origin = u16()
    width = u16()
    height = u16()
    bits_per_pixel = u8()
    descriptor = u8()

    top_to_bottom = (descriptor & 0x20) != 0
    bytes_per_pixel = bits_per_pixel // 8

    # skip ID
    pos += id_length

    if colour_map_type != 0:
        raise ValueError("Colour-mapped TGA not supported")
    if image_type != 2:
        raise ValueError(f"Only uncompressed true-colour TGA supported (type 2), got type {image_type}")
    if bytes_per_pixel not in (3, 4):
        raise ValueError(f"Only 24-bit or 32-bit TGA supported, got {bits_per_pixel}-bit")

    pixels = [0] * (width * height)

    for row in range(height):
        y = row if top_to_bottom else (height - 1 - row)
        for x in range(width):
            b = data[pos]; pos += 1
            g = data[pos]; pos += 1
            r = data[pos]; pos += 1
            a = data[pos] if bytes_per_pixel == 4 else 0xFF
            if bytes_per_pixel == 4:
                pos += 1

            # Store as RGBA8888: R in high byte, A in low byte
            pixels[y * width + x] = (r << 24) | (g << 16) | (b << 8) | a

    return TgaImage(width, height, pixels)
