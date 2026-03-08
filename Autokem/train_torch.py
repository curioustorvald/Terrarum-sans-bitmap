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
from pathlib import Path

import numpy as np

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

def collect_from_sheet(path, is_xyswap):
    """Extract labelled samples from a single TGA sheet."""
    img = read_tga(path)
    cell_w, cell_h = 16, 20
    cols = img.width // cell_w
    rows = img.height // cell_h
    total_cells = cols * rows

    inputs = []
    labels = []

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

    return inputs, labels


def collect_all_samples(assets_dir):
    """Scan assets_dir for *_variable.tga, collect all labelled samples."""
    all_inputs = []
    all_labels = []
    file_count = 0

    for name in sorted(os.listdir(assets_dir)):
        if not name.endswith('_variable.tga'):
            continue
        if 'extrawide' in name:
            continue

        is_xyswap = 'xyswap' in name
        path = os.path.join(assets_dir, name)
        inputs, labels = collect_from_sheet(path, is_xyswap)
        if inputs:
            print(f"  {name}: {len(inputs)} samples")
            all_inputs.extend(inputs)
            all_labels.extend(labels)
            file_count += 1

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
            # self.fc2 = nn.Linear(256, 48)
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

    n_train = int(total * (1 - args.val_split))
    X_train, X_val = X[:n_train], X[n_train:]
    y_train, y_val = y[:n_train], y[n_train:]
    print(f"Train: {n_train}, Validation: {total - n_train}\n")

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
