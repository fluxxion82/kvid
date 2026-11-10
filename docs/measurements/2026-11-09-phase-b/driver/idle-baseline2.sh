#!/bin/bash
# OUT=dir idle-baseline2.sh : logs the idle skin temperature every 30 s to $OUT/idle-baseline.log.
# Stops when thermal status is 0 and the skin reading fell by less than 0.2 C over the last 5 minutes
# (at least 10 readings), or after 45 minutes. Prints the highest of the last 10 readings.
P=$(dirname "$0"); OUT=${OUT:?}; n=0; hist=()
while [ $n -lt 90 ]; do
  line=$($P/therm.sh); echo "$(date -u +%FT%TZ) $line" >> "$OUT/idle-baseline.log"
  skin=$(echo "$line" | sed 's/.*skin_c=\([0-9.]*\).*/\1/'); st=$(echo "$line" | sed 's/.*thermal_status=\([0-9]*\).*/\1/')
  [ -z "$st" ] && { echo "DEVICE GONE in idle baseline" >&2; exit 2; }
  hist+=("$skin"); n=$((n+1))
  if [ $n -ge 11 ] && [ "$st" = 0 ]; then old=${hist[$((n-11))]}; if awk "BEGIN{exit !($old-$skin<0.2)}"; then break; fi; fi
  sleep 30
done
printf '%s\n' "${hist[@]}" | tail -10 | sort -n | tail -1
