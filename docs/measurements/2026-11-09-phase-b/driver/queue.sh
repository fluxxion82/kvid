#!/bin/bash
# OUT=dir SKIN_MAX=x queue.sh queuefile ; each line: label class KEY=VALUE...
set -u
P=$(dirname "$0"); OUT=${OUT:?}; export OUT; Q=$1
SERIAL=${SERIAL:?set SERIAL to the adb serial}; SKIN_MAX=${SKIN_MAX:?}; GATE_S=${GATE_S:-1200}
mkdir -p "$OUT"; touch "$OUT/done"
while read -r label cls rest <&3; do
  [ -z "$label" ] && continue
  grep -qx "$label" "$OUT/done" && continue
  adb -s "$SERIAL" get-state >/dev/null 2>&1 || { echo "DEVICE GONE before $label"; exit 2; }
  t0=$(date +%s); gate=ok
  while :; do
    line=$($P/therm.sh) || { echo "DEVICE GONE before $label"; exit 2; }
    echo "$line" | grep -q "thermal_status=[0-9]" || { echo "DEVICE GONE before $label"; exit 2; }
    skin=$(echo "$line" | sed 's/.*skin_c=\([0-9.]*\).*/\1/'); st=$(echo "$line" | sed 's/.*thermal_status=\([0-9]*\).*/\1/')
    if [ "$st" = 0 ] && awk "BEGIN{exit !($skin<=$SKIN_MAX)}"; then break; fi
    if [ $(( $(date +%s) - t0 )) -gt $GATE_S ]; then gate=timeout; break; fi
    sleep 20
  done
  echo "$(date -u +%FT%TZ) gate label=$label waited_s=$(( $(date +%s) - t0 )) gate=$gate $line" >> "$OUT/gate.log"
  if [ $gate = timeout ]; then echo "GATE TIMEOUT before $label ($line)"; exit 3; fi
  echo "== $label"
  if bash $P/run.sh "$label" "$cls" $rest; then echo "$label" >> "$OUT/done"; else echo "$label" >> "$OUT/failed"; fi
done 3< "$Q"
echo QUEUE DONE
