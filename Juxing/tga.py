"""
Minimal TGA I/O for uncompressed 32-bit true-colour images, as used by all
Terrarum Sans Bitmap sprite sheets. Images are numpy arrays of shape (h, w, 4)
in RGBA order.
"""

import os
import struct

import numpy as np


def read(path: str) -> np.ndarray:
    with open(path, 'rb') as f:
        data = f.read()
    id_len, cmap_type, img_type = data[0], data[1], data[2]
    w, h = struct.unpack_from('<HH', data, 12)
    bpp, desc = data[16], data[17]
    if cmap_type != 0 or img_type != 2 or bpp not in (24, 32):
        raise ValueError(f"{path}: only uncompressed 24/32-bit true-colour TGA is supported")
    n = bpp // 8
    px = np.frombuffer(data, dtype=np.uint8, count=w * h * n, offset=18 + id_len).reshape(h, w, n)
    rgba = np.empty((h, w, 4), dtype=np.uint8)
    rgba[..., 0] = px[..., 2]
    rgba[..., 1] = px[..., 1]
    rgba[..., 2] = px[..., 0]
    rgba[..., 3] = px[..., 3] if n == 4 else 255
    if not desc & 0x20:  # bottom-up
        rgba = rgba[::-1]
    return np.ascontiguousarray(rgba)


def write(path: str, rgba: np.ndarray):
    h, w, _ = rgba.shape
    header = struct.pack('<BBBHHBHHHHBB', 0, 0, 2, 0, 0, 0, 0, 0, w, h, 32, 0x28)
    bgra = np.empty_like(rgba)
    bgra[..., 0] = rgba[..., 2]
    bgra[..., 1] = rgba[..., 1]
    bgra[..., 2] = rgba[..., 0]
    bgra[..., 3] = rgba[..., 3]
    tmp = path + '.part'
    with open(tmp, 'wb') as f:
        f.write(header)
        f.write(bgra.tobytes())
    os.replace(tmp, path)
