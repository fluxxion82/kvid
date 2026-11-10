#!/bin/bash
# waits until the phone stops charging (holds at its limit), as in session 1; logs readings
P=$(dirname "$0"); OUT=${OUT:?}; t0=$(date +%s)
while :; do
  line=$($P/therm.sh); echo "$(date -u +%FT%TZ) charge-wait $line" >> "$OUT/charge-wait.log"
  echo "$line" | grep -q "thermal_status=[0-9]" || { echo "DEVICE GONE in charge wait"; exit 2; }
  echo "$line" | grep -q "charge_status=2" || break
  [ $(( $(date +%s) - t0 )) -gt ${CHARGE_WAIT_S:-1800} ] && { echo "CHARGE WAIT TIMEOUT ($line)"; break; }
  sleep 30
done
echo "charge wait ended: $line"
