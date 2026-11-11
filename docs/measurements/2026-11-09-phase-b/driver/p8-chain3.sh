#!/bin/bash
# Pixel 8, fourth attachment (November 10), longer gate timer: the owner cannot promise a long attachment, so no wait for charging to
# stop; the charge state is recorded in every gate line. Same cooling rule and queue as before, plus a repeat of the
# baseline 5,000-note run at the end.
P=$(cd "$(dirname "$0")" && pwd); cd "$P"
export OUT=$P/session2
SKIN_MAX=35.5 GATE_S=10800 bash ./queue.sh q-s2d.txt
