#!/bin/bash
# OUT=dir SKIN_MAX=x queue.sh queuefile ; each line: label class KEY=VALUE...
set -u
P=$(dirname "$0"); OUT=${OUT:?}; export OUT; Q=$1
SERIAL=${SERIAL:?set SERIAL to the adb serial}; export SERIAL; SKIN_MAX=${SKIN_MAX:?}; GATE_S=${GATE_S:-1200}
mkdir -p "$OUT"; touch "$OUT/done"
while read -r label cls rest <&3; do
  [ -z "$label" ] && continue
  grep -qx "$label" "$OUT/done" && continue
  n=0; until adb -s "$SERIAL" get-state >/dev/null 2>&1; do n=$((n+1)); [ $n -gt 12 ] && { echo "DEVICE GONE before $label"; exit 2; }; sleep 10; done
  [ "$(grep -cx "$label" "$OUT/disturbed" 2>/dev/null)" -ge 3 ] 2>/dev/null && { echo "SKIP $label: disturbed three times"; continue; }
  t0=$(date +%s); gate=ok
  while :; do
    line=$($P/therm.sh)
    echo "$line" | grep -q "thermal_status=[0-9]" || { sleep 30; line=$($P/therm.sh); echo "$line" | grep -q "thermal_status=[0-9]" || { echo "DEVICE GONE before $label"; exit 2; }; }
    if [ "$($P/fg.sh)" != launcher ]; then
      [ $(( $(date +%s) - t0 )) -gt $GATE_S ] && { gate=timeout; line="$line front=other"; break; }
      sleep 20; continue; fi
    echo "$line" | grep -Eq "wake=(1|Awake)" || { adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP; echo "$(date -u +%FT%TZ) screen was off before $label; sent KEYCODE_WAKEUP ($line)" >> "$OUT/gate.log"; sleep 5; continue; }
    skin=$(echo "$line" | sed 's/.*skin_c=\([0-9.]*\).*/\1/'); st=$(echo "$line" | sed 's/.*thermal_status=\([0-9]*\).*/\1/')
    if [ "$st" = 0 ] && awk "BEGIN{exit !($skin<=$SKIN_MAX)}"; then break; fi
    if [ $(( $(date +%s) - t0 )) -gt $GATE_S ]; then gate=timeout; break; fi
    sleep 20
  done
  echo "$(date -u +%FT%TZ) gate label=$label waited_s=$(( $(date +%s) - t0 )) gate=$gate $line" >> "$OUT/gate.log"
  if [ $gate = timeout ]; then echo "GATE TIMEOUT before $label ($line)"; exit 3; fi
  echo "== $label"
  bash $P/run.sh "$label" "$cls" $rest; rc=$?
  case $rc in 0) echo "$label" >> "$OUT/done";; 5) echo "$label" >> "$OUT/disturbed";; *) echo "$label" >> "$OUT/failed";; esac
done 3< "$Q"
echo QUEUE DONE
