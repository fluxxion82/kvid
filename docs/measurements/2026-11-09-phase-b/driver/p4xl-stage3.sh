#!/bin/bash
# Pixel 4 XL stage 3: two paired blocks of default threads against threads=2 on mixed; same cooling rule as stage 1.
set -u
P=$(cd "$(dirname "$0")" && pwd); cd "$P"
export SKIN_SENSOR=sdm-therm-monitor OUT=$P/p4xl/session3
mkdir -p "$OUT"
max=$(grep -o 'skin_max_c=[0-9.]*' "$P/p4xl/session1/cooling-rule.txt" | head -1 | cut -d= -f2)
[ -n "$max" ] || { echo "no cooling rule"; exit 1; }
SKIN_MAX=$max GATE_S=7200 bash ./queue.sh q-p4xl-3.txt
