#!/bin/bash
# Pixel 8: wait for charging to stop at its limit, then the remaining queue.
P=$(cd "$(dirname "$0")" && pwd); cd "$P"
export OUT=$P/session2
CHARGE_WAIT_S=1800 ./waitcharge.sh || exit 2
SKIN_MAX=35.5 GATE_S=2400 bash ./queue.sh q-s2c.txt
