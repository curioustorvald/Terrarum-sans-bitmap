#!/usr/bin/env python3
"""
PyTorch training script for Autokem — drop-in replacement for `autokem train`.

Reads the same *_variable.tga sprite sheets, trains the same architecture,
and saves weights in safetensors format loadable by the C inference code.

Usage:
    python train_keras.py                  # train with defaults
    python train_keras.py --epochs 300     # override max epochs
    python train_keras.py --lr 0.0005      # override learning rate
    python train_keras.py --save model.safetensors

Requirements:
    pip install torch numpy
"""

import argparse
import json
import os
import struct
import sys
import unicodedata
from pathlib import Path

import numpy as np

# ---- Sheet code ranges (imported from OTFbuild/sheet_config.py) ----

_otfbuild = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'OTFbuild')
try:
    sys.path.insert(0, _otfbuild)
    from sheet_config import FILE_LIST as _FILE_LIST, CODE_RANGE as _CODE_RANGE
    sys.path.pop(0)
    _CODE_RANGE_MAP = {}
    for _i, _fn in enumerate(_FILE_LIST):
        if _i < len(_CODE_RANGE):
            _CODE_RANGE_MAP[_fn] = _CODE_RANGE[_i]
except ImportError:
    _CODE_RANGE_MAP = {}


# ---- TGA reader (matches OTFbuild/tga_reader.py and Autokem/tga.c) ----

class TgaImage:
    __slots__ = ('width', 'height', 'pixels')

    def __init__(self, width, height, pixels):
        self.width = width
        self.height = height
        self.pixels = pixels  # flat list of RGBA8888 ints

    def get_pixel(self, x, y):
        if x < 0 or x >= self.width or y < 0 or y >= self.height:
            return 0
        return self.pixels[y * self.width + x]


def read_tga(path):
    with open(path, 'rb') as f:
        data = f.read()

    pos = 0
    id_length = data[pos]; pos += 1
    _colour_map_type = data[pos]; pos += 1
    image_type = data[pos]; pos += 1
    pos += 5  # colour map spec
    pos += 2  # x_origin
    pos += 2  # y_origin
    width = struct.unpack_from('<H', data, pos)[0]; pos += 2
    height = struct.unpack_from('<H', data, pos)[0]; pos += 2
    bits_per_pixel = data[pos]; pos += 1
    descriptor = data[pos]; pos += 1
    top_to_bottom = (descriptor & 0x20) != 0
    bpp = bits_per_pixel // 8
    pos += id_length

    if image_type != 2 or bpp not in (3, 4):
        raise ValueError(f"Unsupported TGA: type={image_type}, bpp={bits_per_pixel}")

    pixels = [0] * (width * height)
    for row in range(height):
        y = row if top_to_bottom else (height - 1 - row)
        for x in range(width):
            b = data[pos]; g = data[pos+1]; r = data[pos+2]
            a = data[pos+3] if bpp == 4 else 0xFF
            pos += bpp
            pixels[y * width + x] = (r << 24) | (g << 16) | (b << 8) | a

    return TgaImage(width, height, pixels)


def tagify(pixel):
    return 0 if (pixel & 0xFF) == 0 else pixel


# ---- Data collection (matches Autokem/train.c) ----

def collect_from_sheet(path, is_xyswap, code_range=None):
    """Extract labelled samples from a single TGA sheet."""
    img = read_tga(path)
    cell_w, cell_h = 16, 20
    cols = img.width // cell_w
    rows = img.height // cell_h
    total_cells = cols * rows

    inputs = []
    labels = []
    skipped_lm = 0

    for index in range(total_cells):
        if is_xyswap:
            cell_x = (index // cols) * cell_w
            cell_y = (index % cols) * cell_h
        else:
            cell_x = (index % cols) * cell_w
            cell_y = (index // cols) * cell_h

        tag_x = cell_x + (cell_w - 1)
        tag_y = cell_y

        # Width (5-bit)
        width = 0
        for y in range(5):
            if img.get_pixel(tag_x, tag_y + y) & 0xFF:
                width |= (1 << y)
        if width == 0:
            continue

        # Skip modifier letters, symbols, punctuation
        if code_range is not None and index < len(code_range):
            cp = code_range[index]
            try:
                cat = unicodedata.category(chr(cp))
                if cat == 'Lm' or cat[0] in ('S', 'P'):
                    skipped_lm += 1
                    continue
            except (ValueError, OverflowError):
                pass

        # Kern data pixel at Y+6
        kern_pixel = tagify(img.get_pixel(tag_x, tag_y + 6))
        if (kern_pixel & 0xFF) == 0:
            continue  # no kern data

        # Extract labels
        is_kern_ytype = 1.0 if (kern_pixel & 0x80000000) != 0 else 0.0
        kerning_mask = (kern_pixel >> 8) & 0xFFFFFF
        is_low_height = 1.0 if (img.get_pixel(tag_x, tag_y + 5) & 0xFF) != 0 else 0.0

        # Shape bits: A(7) B(6) C(5) D(4) E(3) F(2) G(1) H(0) J(15) K(14)
        shape = [
            float((kerning_mask >> 7) & 1),  # A
            float((kerning_mask >> 6) & 1),  # B
            float((kerning_mask >> 5) & 1),  # C
            float((kerning_mask >> 4) & 1),  # D
            float((kerning_mask >> 3) & 1),  # E
            float((kerning_mask >> 2) & 1),  # F
            float((kerning_mask >> 1) & 1),  # G
            float((kerning_mask >> 0) & 1),  # H
            float((kerning_mask >> 15) & 1), # J
            float((kerning_mask >> 14) & 1), # K
        ]

        # 15x20 binary input
        inp = np.zeros((20, 15), dtype=np.float32)
        for gy in range(20):
            for gx in range(15):
                p = img.get_pixel(cell_x + gx, cell_y + gy)
                if (p & 0x80) != 0:
                    inp[gy, gx] = 1.0

        inputs.append(inp)
        labels.append(shape + [is_kern_ytype, is_low_height])

    return inputs, labels, skipped_lm


def collect_all_samples(assets_dir):
    """Scan assets_dir for *_variable.tga, collect all labelled samples."""
    all_inputs = []
    all_labels = []
    file_count = 0
    total_skipped_lm = 0

    for name in sorted(os.listdir(assets_dir)):
        if not name.endswith('_variable.tga'):
            continue
        if 'extrawide' in name:
            continue

        is_xyswap = 'xyswap' in name
        code_range = _CODE_RANGE_MAP.get(name, None)
        path = os.path.join(assets_dir, name)
        inputs, labels, skipped_lm = collect_from_sheet(path, is_xyswap, code_range)
        total_skipped_lm += skipped_lm
        if inputs:
            suffix = f" (skipped {skipped_lm})" if skipped_lm else ""
            print(f"  {name}: {len(inputs)} samples{suffix}")
            all_inputs.extend(inputs)
            all_labels.extend(labels)
            file_count += 1

    if total_skipped_lm:
        print(f"  Filtered (Lm/S/P): {total_skipped_lm}")

    return np.array(all_inputs), np.array(all_labels, dtype=np.float32), file_count


# ---- Model (matches Autokem/nn.c architecture) ----

def build_model():
    """
    Conv2D(1->32, 7x7, padding=1) -> SiLU
    Conv2D(32->64, 7x7, padding=1) -> SiLU
    GlobalAveragePooling2D -> [64]
    Dense(256) -> SiLU
    Dense(12) -> sigmoid
    """
    import torch
    import torch.nn as nn

    class Keminet(nn.Module):
        def __init__(self):
            super().__init__()
            self.conv1 = nn.Conv2d(1, 32, 7, padding=1)
            self.conv2 = nn.Conv2d(32, 64, 7, padding=1)
            self.fc1 = nn.Linear(64, 256)
            # self.fc2 = nn.Linear(256, 128)
            self.output = nn.Linear(256, 12)
            self.tf = nn.SiLU()

            # He init
            for m in self.modules():
                if isinstance(m, (nn.Conv2d, nn.Linear)):
                    nn.init.kaiming_normal_(m.weight, a=0.01, nonlinearity='leaky_relu')
                    if m.bias is not None:
                        nn.init.zeros_(m.bias)

        def forward(self, x):
            x = self.tf(self.conv1(x))
            x = self.tf(self.conv2(x))
            x = x.mean(dim=(2, 3))  # global average pool
            x = self.tf(self.fc1(x))
            # x = self.tf(self.fc2(x))
            x = torch.sigmoid(self.output(x))
            return x

    return Keminet()


# ---- Safetensors export (matches Autokem/safetensor.c layout) ----

def export_safetensors(model, path, total_samples, epochs, val_loss):
    """
    Save model weights in safetensors format compatible with the C code.

    C code expects these tensor names with these shapes:
      conv1.weight  [out_ch, in_ch, kh, kw]  — PyTorch matches this layout
      conv1.bias    [out_ch]
      conv2.weight  [out_ch, in_ch, kh, kw]
      conv2.bias    [out_ch]
      fc1.weight    [out_features, in_features]  — PyTorch matches this layout
      fc1.bias      [out_features]
      fc2.weight    [out_features, in_features]
      fc2.bias      [out_features]
      output.weight [out_features, in_features]
      output.bias   [out_features]
    """
    tensor_names = [
        'conv1.weight', 'conv1.bias',
        'conv2.weight', 'conv2.bias',
        'fc1.weight', 'fc1.bias',
        # 'fc2.weight', 'fc2.bias',
        'output.weight', 'output.bias',
    ]

    state = model.state_dict()

    header = {}
    header['__metadata__'] = {
        'samples': str(total_samples),
        'epochs': str(epochs),
        'val_loss': f'{val_loss:.6f}',
    }

    data_parts = []
    offset = 0
    for name in tensor_names:
        arr = state[name].detach().cpu().numpy().astype(np.float32)
        raw = arr.tobytes()
        header[name] = {
            'dtype': 'F32',
            'shape': list(arr.shape),
            'data_offsets': [offset, offset + len(raw)],
        }
        data_parts.append(raw)
        offset += len(raw)

    header_json = json.dumps(header, separators=(',', ':')).encode('utf-8')
    padded_len = (len(header_json) + 7) & ~7
    header_json = header_json + b' ' * (padded_len - len(header_json))

    with open(path, 'wb') as f:
        f.write(struct.pack('<Q', len(header_json)))
        f.write(header_json)
        for part in data_parts:
            f.write(part)

    total_bytes = 8 + len(header_json) + offset
    print(f"Saved model to {path} ({total_bytes} bytes)")


def load_safetensors(model, path):
    """Load weights from safetensors file into the PyTorch model."""
    import torch

    with open(path, 'rb') as f:
        header_len = struct.unpack('<Q', f.read(8))[0]
        header_json = f.read(header_len)
        header = json.loads(header_json)
        data_start = 8 + header_len

        state = model.state_dict()
        for name in state:
            if name not in header:
                print(f"  Warning: tensor '{name}' not in safetensors")
                continue
            entry = header[name]
            off_start, off_end = entry['data_offsets']
            f.seek(data_start + off_start)
            raw = f.read(off_end - off_start)
            arr = np.frombuffer(raw, dtype=np.float32).reshape(entry['shape'])
            state[name] = torch.from_numpy(arr.copy())

        model.load_state_dict(state)
    print(f"Loaded weights from {path}")


# ---- Pretty-print helpers ----

BIT_NAMES = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'Ytype', 'LowH']
SHAPE_CHARS = 'ABCDEFGHJK'
MIRROR_PAIRS = [(0, 1), (2, 3), (4, 5), (6, 7), (8, 9)]  # A↔B, C↔D, E↔F, G↔H, J↔K


def format_tag(bits_12):
    """Format 12 binary bits as keming_machine tag string, e.g. 'ABCDEFGH(B)'."""
    chars = ''.join(SHAPE_CHARS[i] for i in range(10) if bits_12[i])
    if not chars:
        chars = '(empty)'
    mode = '(Y)' if bits_12[10] else '(B)'
    low = ' low' if bits_12[11] else ''
    return f'{chars}{mode}{low}'


def print_label_distribution(labels, total):
    counts = labels.sum(axis=0).astype(int)
    parts = [f'{BIT_NAMES[b]}:{counts[b]}({100*counts[b]/total:.0f}%)' for b in range(12)]
    print(f"Label distribution:\n  {' '.join(parts)}")


def print_examples_and_accuracy(model, X_val, y_val, max_examples=8):
    """Print example predictions and per-bit accuracy on validation set."""
    import torch

    model.eval()
    with torch.no_grad():
        preds = model(X_val).cpu().numpy()

    y_np = y_val.cpu().numpy() if hasattr(y_val, 'cpu') else y_val
    pred_bits = (preds >= 0.5).astype(int)
    tgt_bits = y_np.astype(int)

    n_val = len(y_np)
    n_examples = 0

    print("\nGlyph Tags — validation predictions:")
    for i in range(n_val):
        mismatch = not np.array_equal(pred_bits[i], tgt_bits[i])
        if n_examples < max_examples and (mismatch or i < 4):
            actual_tag = format_tag(tgt_bits[i])
            pred_tag = format_tag(pred_bits[i])
            status = 'MISMATCH' if mismatch else 'ok'
            print(f"  actual={actual_tag:<20s} pred={pred_tag:<20s} {status}")
            n_examples += 1

    correct = (pred_bits == tgt_bits)
    per_bit = correct.sum(axis=0)
    total_correct = correct.sum()

    print(f"\nPer-bit accuracy ({n_val} val samples):")
    parts = [f'{BIT_NAMES[b]}:{100*per_bit[b]/n_val:.1f}%' for b in range(12)]
    print(f"  {' '.join(parts)}")
    print(f"  Overall: {total_correct}/{n_val*12} ({100*total_correct/(n_val*12):.2f}%)")


# ---- Data augmentation ----

def _shape_key(label):
    """10-bit shape tuple from label (A through K)."""
    return tuple(int(label[i]) for i in range(10))


def _mirror_shape(key):
    """Swap mirror pairs: A↔B, C↔D, E↔F, G↔H, J↔K."""
    m = list(key)
    for a, b in MIRROR_PAIRS:
        m[a], m[b] = m[b], m[a]
    return tuple(m)


def _mirror_label(label):
    """Mirror shape bits in label, keep ytype and lowheight."""
    m = label.copy()
    for a, b in MIRROR_PAIRS:
        m[a], m[b] = m[b], m[a]
    return m


def _shift_image(img, dx, dy):
    """Shift 2D image by (dx, dy), fill with 0."""
    h, w = img.shape
    shifted = np.zeros_like(img)
    sx0, sx1 = max(0, -dx), min(w, w - dx)
    sy0, sy1 = max(0, -dy), min(h, h - dy)
    dx0, dx1 = max(0, dx), min(w, w + dx)
    dy0, dy1 = max(0, dy), min(h, h + dy)
    shifted[dy0:dy1, dx0:dx1] = img[sy0:sy1, sx0:sx1]
    return shifted


def _augment_one(img, label, rng):
    """One augmented copy: random 1px shift + 1% pixel dropout."""
    dx = rng.integers(-1, 2)  # -1, 0, or 1
    dy = rng.integers(-1, 2)  # -1, 0, or 1
    aug = _shift_image(img, dx, dy)
    # mask = rng.random(aug.shape) > 0.01
    # aug = aug * mask
    return aug, label.copy()


def _do_mirror_augmentation(X, y, rng):
    """For each mirror pair (S, mirror(S)), fill deficit from the common side."""
    shape_counts = {}
    shape_indices = {}
    for i in range(len(y)):
        key = _shape_key(y[i])
        shape_counts[key] = shape_counts.get(key, 0) + 1
        shape_indices.setdefault(key, []).append(i)

    new_X, new_y = [], []
    done = set()  # avoid processing both directions

    for key, count in shape_counts.items():
        if key in done:
            continue
        mkey = _mirror_shape(key)
        done.add(key)
        done.add(mkey)
        if mkey == key:
            continue  # symmetric shape
        mcount = shape_counts.get(mkey, 0)
        if count == mcount:
            continue
        # Mirror from the larger side to fill the smaller side
        if count > mcount:
            src_key, deficit = key, count - mcount
        else:
            src_key, deficit = mkey, mcount - count
        indices = shape_indices.get(src_key, [])
        if not indices:
            continue
        chosen = rng.choice(indices, size=deficit, replace=True)
        for idx in chosen:
            new_X.append(np.fliplr(X[idx]).copy())
            new_y.append(_mirror_label(y[idx]))

    if new_X:
        X = np.concatenate([X, np.array(new_X)])
        y = np.concatenate([y, np.array(new_y)])
    return X, y


def _compute_rarity_weights(y):
    """Per-sample weight: sum of inverse bit frequencies for all 12 bits.

    Samples with rare bit values (e.g. J=1 at 13%, C=0 at 8%) get higher weight.
    """
    bit_freq = y.mean(axis=0)  # [12], P(bit=1)
    weights = np.zeros(len(y))
    for i in range(len(y)):
        w = 0.0
        for b in range(12):
            p = bit_freq[b] if y[i, b] > 0.5 else (1.0 - bit_freq[b])
            w += 1.0 / max(p, 0.01)
        weights[i] = w
    return weights


def _do_rarity_augmentation(X, y, rng, target_new):
    """Create target_new augmented samples, drawn proportionally to rarity weight."""
    if target_new <= 0:
        return X, y

    weights = _compute_rarity_weights(y)
    weights /= weights.sum()

    chosen = rng.choice(len(X), size=target_new, replace=True, p=weights)

    new_X, new_y = [], []
    for idx in chosen:
        aug_img, aug_label = _augment_one(X[idx], y[idx], rng)
        new_X.append(aug_img)
        new_y.append(aug_label)

    X = np.concatenate([X, np.array(new_X)])
    y = np.concatenate([y, np.array(new_y)])
    return X, y


def _print_bit_freq(y, label):
    """Print per-bit frequencies for diagnostics."""
    freq = y.mean(axis=0)
    names = BIT_NAMES
    parts = [f'{names[b]}:{freq[b]*100:.0f}%' for b in range(12)]
    print(f"  {label}: {' '.join(parts)}")


def augment_training_data(X_train, y_train, rng, aug_factor=3.0):
    """
    Three-phase data augmentation:
      1. Mirror augmentation — fill deficit between mirror-paired shapes
      2. Rarity-weighted — samples with rare bit values get more copies (shift+dropout)
      3. Y-type boost — repeat phases 1-2 scoped to Y-type samples only
    """
    n0 = len(X_train)
    _print_bit_freq(y_train, 'Before')

    # Phase 1: Mirror augmentation
    X_train, y_train = _do_mirror_augmentation(X_train, y_train, rng)
    n1 = len(X_train)

    # Phase 2: Rarity-weighted augmentation — target aug_factor × original size
    target_new = int(n0 * aug_factor) - n1
    X_train, y_train = _do_rarity_augmentation(X_train, y_train, rng, target_new)
    n2 = len(X_train)

    # Phase 3: Y-type boost — same pipeline for Y-type subset only
    ytype_mask = y_train[:, 10] > 0.5
    n_ytype_existing = int(ytype_mask.sum())
    if n_ytype_existing > 0:
        X_yt = X_train[ytype_mask]
        y_yt = y_train[ytype_mask]
        X_yt, y_yt = _do_mirror_augmentation(X_yt, y_yt, rng)
        # Double the Y-type subset via rarity augmentation
        yt_new = n_ytype_existing
        X_yt, y_yt = _do_rarity_augmentation(X_yt, y_yt, rng, yt_new)
        if len(X_yt) > n_ytype_existing:
            X_train = np.concatenate([X_train, X_yt[n_ytype_existing:]])
            y_train = np.concatenate([y_train, y_yt[n_ytype_existing:]])

    n3 = len(X_train)

    _print_bit_freq(y_train, 'After ')
    print(f"Data augmentation: {n0} → {n3} samples ({n3/n0:.1f}×)")
    print(f"  Mirror: +{n1 - n0}, Rarity: +{n2 - n1}, Y-type boost: +{n3 - n2}")

    return X_train, y_train


# ---- Main ----

def main():
    parser = argparse.ArgumentParser(description='Train Autokem model (PyTorch)')
    parser.add_argument('--assets', default='../src/assets',
                        help='Path to assets directory (default: ../src/assets)')
    parser.add_argument('--save', default='autokem.safetensors',
                        help='Output safetensors path (default: autokem.safetensors)')
    parser.add_argument('--load', default=None,
                        help='Load weights from safetensors before training')
    parser.add_argument('--epochs', type=int, default=200, help='Max epochs (default: 200)')
    parser.add_argument('--batch-size', type=int, default=32, help='Batch size (default: 32)')
    parser.add_argument('--lr', type=float, default=0.001, help='Learning rate (default: 0.001)')
    parser.add_argument('--patience', type=int, default=10,
                        help='Early stopping patience (default: 10)')
    parser.add_argument('--val-split', type=float, default=0.2,
                        help='Validation split (default: 0.2)')
    parser.add_argument('--no-augment', action='store_true',
                        help='Disable data augmentation')
    parser.add_argument('--aug-factor', type=float, default=3.0,
                        help='Augmentation target multiplier (default: 3.0)')
    args = parser.parse_args()

    import torch
    import torch.nn as nn

    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"Device: {device}")

    # Collect data
    print("Collecting samples...")
    X, y, file_count = collect_all_samples(args.assets)

    if len(X) < 10:
        print(f"Error: too few samples ({len(X)})", file=sys.stderr)
        return 1

    total = len(X)
    print(f"Collected {total} samples from {file_count} sheets")
    print_label_distribution(y, total)

    nonzero = np.any(X.reshape(total, -1) > 0.5, axis=1).sum()
    print(f"  Non-empty inputs: {nonzero}/{total}\n")

    # Shuffle and split
    rng = np.random.default_rng(42)
    perm = rng.permutation(total)
    X, y = X[perm], y[perm]

    n_val = int(total * args.val_split)
    n_train = total - n_val
    X_train, X_val = X[:n_train], X[n_train:]
    y_train, y_val = y[:n_train], y[n_train:]
    print(f"Train: {n_train}, Validation: {n_val}")

    # Data augmentation (training set only)
    if not args.no_augment:
        X_train, y_train = augment_training_data(X_train, y_train, rng, args.aug_factor)
        n_train = len(X_train)
    print()

    # Convert to tensors — PyTorch conv expects [N, C, H, W]
    X_train_t = torch.from_numpy(X_train[:, np.newaxis, :, :]).to(device)  # [N,1,20,15]
    y_train_t = torch.from_numpy(y_train).to(device)
    X_val_t = torch.from_numpy(X_val[:, np.newaxis, :, :]).to(device)
    y_val_t = torch.from_numpy(y_val).to(device)

    # Build model
    model = build_model().to(device)

    if args.load:
        load_safetensors(model, args.load)

    total_params = sum(p.numel() for p in model.parameters())
    print(f"Model parameters: {total_params} ({total_params * 4 / 1024:.1f} KB)\n")

    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    loss_fn = nn.BCELoss()

    best_val_loss = float('inf')
    best_epoch = 0
    patience_counter = 0
    best_state = None

    for epoch in range(1, args.epochs + 1):
        # Training
        model.train()
        perm_train = torch.randperm(n_train, device=device)
        train_loss = 0.0
        n_batches = 0

        for start in range(0, n_train, args.batch_size):
            end = min(start + args.batch_size, n_train)
            idx = perm_train[start:end]

            optimizer.zero_grad()
            pred = model(X_train_t[idx])
            loss = loss_fn(pred, y_train_t[idx])
            loss.backward()
            optimizer.step()

            train_loss += loss.item()
            n_batches += 1

        train_loss /= n_batches

        # Validation
        model.eval()
        with torch.no_grad():
            val_pred = model(X_val_t)
            val_loss = loss_fn(val_pred, y_val_t).item()

        marker = ''
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            best_epoch = epoch
            patience_counter = 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
            marker = '  *best*'
        else:
            patience_counter += 1

        print(f"Epoch {epoch:3d}: train_loss={train_loss:.4f}  val_loss={val_loss:.4f}{marker}")

        if patience_counter >= args.patience:
            print(f"\nEarly stopping at epoch {epoch} (best epoch: {best_epoch})")
            break

    # Restore best weights
    if best_state is not None:
        model.load_state_dict(best_state)

    print(f"\nBest epoch: {best_epoch}, val_loss: {best_val_loss:.6f}")

    # Print accuracy
    model.eval()
    print_examples_and_accuracy(model, X_val_t, y_val, max_examples=8)

    # Save
    export_safetensors(model, args.save, total, best_epoch, best_val_loss)

    return 0


if __name__ == '__main__':
    sys.exit(main())
