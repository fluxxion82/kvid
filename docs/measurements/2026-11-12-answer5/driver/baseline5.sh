#!/bin/bash
# OUT=dir SERIAL=x baseline5.sh : idle baseline at the start of a session; prints the session's threshold.
# One reading every 60 s. A reading counts when the thermal status is 0, the launcher is in front and the phone
# is not charging; a reading that does not count restarts the count. The baseline ends after BASE_N (21) counted
# readings in a row, that is 20 minutes. Idle level = median of the last 15; threshold = idle + 0.5 C, rounded
# to 0.1 C. A screen found off gets the wake key and is noted; it does not restart the count.
# Exit: 0 ok, 2 phone left, 3 no baseline within BASE_MAX_S (2 h).
set -u
P=$(dirname "$0"); OUT=${OUT:?}; SERIAL=${SERIAL:?}; export SERIAL
N=${BASE_N:-21}; STEP=${BASE_STEP_S:-60}; mkdir -p "$OUT"
LOG="$OUT/baseline-$(date -u +%Y%m%dT%H%M%SZ).log"; vals=(); t_end=$(( $(date +%s) + ${BASE_MAX_S:-7200} ))
while :; do
  line=$($P/therm5.sh); st=$(echo "$line" | sed -n 's/.*thermal_status=\([0-9][0-9]*\).*/\1/p')
  [ -z "$st" ] && { echo "$(date -u +%FT%TZ) phone left during the baseline" >> "$LOG"; exit 2; }
  skin=$(echo "$line" | sed 's/.*skin_c=\([0-9.]*\).*/\1/'); cs=$(echo "$line" | sed 's/.*charge_status=\([0-9]*\).*/\1/')
  fg=$($P/fg.sh); why=
  [ "$st" != 0 ] && why="$why status"
  [ "$fg" != launcher ] && why="$why front"
  [ "$cs" = 2 ] && why="$why charging"
  echo "$line" | grep -Eq "wake=(1|Awake)" || { adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP; woke=" screen_was_off:wake_key_sent"; }
  if [ -z "$why" ]; then vals+=("$skin"); else vals=(); fi
  echo "$(date -u +%FT%TZ) $line front=$fg counted_in_a_row=${#vals[@]}${why:+ not_counted:$why}${woke:-}" >> "$LOG"; woke=
  [ ${#vals[@]} -ge $N ] && break
  [ $(date +%s) -gt $t_end ] && { echo "$(date -u +%FT%TZ) no baseline within the limit" >> "$LOG"; exit 3; }
  sleep $STEP
done
printf '%s\n' "${vals[@]}" | tail -15 | sort -n | awk '{a[NR]=$1} END{m=(NR%2)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2; printf "%.2f %.1f %s %s\n", m, int((m+0.5)*10+0.5)/10, a[1], a[NR]}' > "$LOG.tmp"
read -r idle thr lo hi < "$LOG.tmp"; rm -f "$LOG.tmp"
echo "$(date -u +%FT%TZ) baseline: idle_median_last15=$idle min_last15=$lo max_last15=$hi threshold=$thr" >> "$LOG"
echo "$thr"
