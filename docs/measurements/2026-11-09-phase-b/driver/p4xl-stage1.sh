#!/bin/bash
# Pixel 4 XL stage 1: wait for charging to end, idle baseline, fix the cooling rule, screening queue.
# Safe to run again after the phone was away: the rule is fixed once and finished runs are skipped.
set -u
P=$(cd "$(dirname "$0")" && pwd); cd "$P"
export SKIN_SENSOR=sdm-therm-monitor OUT=$P/p4xl/session1
mkdir -p "$OUT"
CHARGE_WAIT_S=7200 ./waitcharge.sh || exit 2
max=$(grep -o 'skin_max_c=[0-9.]*' "$OUT/cooling-rule.txt" 2>/dev/null | head -1 | cut -d= -f2)
if [ -z "$max" ]; then
  plateau=$(./idle-baseline2.sh) || exit 2
  max=$(awk -v p="$plateau" 'BEGIN{m=p+0.5; if(m>39.0)m=39.0; printf "%.1f", m}')
  echo "$(date -u +%FT%TZ) idle_plateau_c=$plateau skin_max_c=$max (rule written before any timed run: thermal status 0 and sdm-therm-monitor <= highest of the last ten idle readings + 0.5 C, capped at 39.0 C; status 1 starts at 40.0 C)" | tee -a "$OUT/cooling-rule.txt"
fi
SKIN_MAX=$max GATE_S=3600 bash ./queue.sh q-screen.txt
