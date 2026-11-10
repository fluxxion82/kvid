#!/bin/bash
# Pixel 4 XL stage 1: wait for charging to end, idle baseline, fix the cooling rule, screening queue.
set -u
P=$(cd "$(dirname "$0")" && pwd); cd "$P"
export SERIAL=${SERIAL:?set SERIAL to the adb serial} SKIN_SENSOR=sdm-therm-monitor OUT=$P/p4xl/session1
mkdir -p "$OUT"
CHARGE_WAIT_S=7200 ./waitcharge.sh || exit 2
plateau=$(./idle-baseline2.sh) || exit 2
max=$(awk -v p="$plateau" 'BEGIN{m=p+0.5; if(m>39.0)m=39.0; printf "%.1f", m}')
echo "$(date -u +%FT%TZ) idle_plateau_c=$plateau skin_max_c=$max (rule written before any timed run: thermal status 0 and sdm-therm-monitor <= highest of the last ten idle readings + 0.5 C, capped at 39.0 C; status 1 starts at 40.0 C)" | tee -a "$OUT/cooling-rule.txt"
SKIN_MAX=$max GATE_S=3600 bash ./queue.sh q-screen.txt
