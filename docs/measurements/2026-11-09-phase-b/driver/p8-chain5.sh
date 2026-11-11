#!/bin/bash
# Pixel 8: rerun of the disturbed one-thread run, second cooling rule
# stop; the charge state is recorded in every gate line. Same cooling rule and queue as before, plus a repeat of the
# baseline 5,000-note run at the end.
P=$(cd "$(dirname "$0")" && pwd); cd "$P"
export OUT=$P/session2
SKIN_MAX=36.4 GATE_S=10800 bash ./queue.sh q-s2e.txt
