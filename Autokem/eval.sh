#!/usr/bin/env bash
# Run train_torch.py N times and report mean ± stddev of per-bit and overall accuracy.
# Usage: ./eval.sh [runs] [extra train_torch.py args...]
#   e.g. ./eval.sh 10
#        ./eval.sh 5 --epochs 300 --lr 0.0005

set -euo pipefail
cd "$(dirname "$0")"

RUNS="${1:-10}"
shift 2>/dev/null || true
EXTRA_ARGS="$*"
PYTHON="${PYTHON:-.venv/bin/python3}"
RESULTS_FILE=$(mktemp)

trap 'rm -f "$RESULTS_FILE"' EXIT

echo "=== Autokem evaluation: $RUNS runs ==="
[ -n "$EXTRA_ARGS" ] && echo "Extra args: $EXTRA_ARGS"
echo

for i in $(seq 1 "$RUNS"); do
    echo "--- Run $i/$RUNS ---"
    OUT=$("$PYTHON" train_torch.py --save /dev/null $EXTRA_ARGS 2>&1)

    # Extract per-bit line (the one after "Per-bit accuracy"):  A:53.9% B:46.7% ...
    PERBIT=$(echo "$OUT" | grep -A1 'Per-bit accuracy' | tail -1)
    # Extract overall line:  Overall: 5267/6660 (79.08%)
    OVERALL=$(echo "$OUT" | grep -oP 'Overall:.*\(\K[0-9.]+')
    # Extract val_loss
    VALLOSS=$(echo "$OUT" | grep -oP 'val_loss: \K[0-9.]+' | tail -1)

    # Parse per-bit percentages into a tab-separated line
    BITS=$(echo "$PERBIT" | grep -oP '[0-9.]+(?=%)' | tr '\n' '\t')

    echo "$BITS$OVERALL	$VALLOSS" >> "$RESULTS_FILE"
    echo "  val_loss=$VALLOSS  overall=$OVERALL%"
done

echo
echo "=== Results ($RUNS runs) ==="

"$PYTHON" - "$RESULTS_FILE" <<'PYEOF'
import sys
import numpy as np

names = ['A','B','C','D','E','F','G','H','J','K','Ytype','LowH','Overall','ValLoss']
data = []
with open(sys.argv[1]) as f:
    for line in f:
        vals = line.strip().split('\t')
        if len(vals) >= len(names):
            data.append([float(v) for v in vals[:len(names)]])

if not data:
    print("No data collected!")
    sys.exit(1)

arr = np.array(data)
means = arr.mean(axis=0)
stds = arr.std(axis=0)

print(f"{'Metric':<10s} {'Mean':>8s} {'StdDev':>8s}")
print("-" * 28)
for i, name in enumerate(names):
    unit = '' if name == 'ValLoss' else '%'
    print(f"{name:<10s} {means[i]:>7.2f}{unit} {stds[i]:>7.2f}{unit}")
PYEOF
