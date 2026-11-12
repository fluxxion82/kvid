#!/bin/bash
# OUT=dir SERIAL=x CHECKER=path chain5.sh blocks.txt configs.txt : one session.
# Idle baseline, threshold, comparison with the first session's threshold, then the queue.
# Exit 8: this session's threshold differs from the first session's by more than 1.0 C; the session is not run.
set -u
P=$(dirname "$0"); OUT=${OUT:?}; export OUT; mkdir -p "$OUT"
thr=$(bash $P/baseline5.sh) || exit $?
[ -f "$OUT/threshold-first.txt" ] || echo "$thr" > "$OUT/threshold-first.txt"
first=$(cat "$OUT/threshold-first.txt")
echo "$(date -u +%FT%TZ) session threshold=$thr first=$first" >> "$OUT/sessions.log"
awk "BEGIN{d=$thr-$first; if(d<0)d=-d; exit !(d>1.0001)}" && { echo "$(date -u +%FT%TZ) session not run: threshold $thr against first $first" >> "$OUT/sessions.log"; exit 8; }
SKIN_MAX=$thr bash $P/queue5.sh "$1" "$2"
