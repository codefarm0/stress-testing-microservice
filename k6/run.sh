#!/usr/bin/env bash
# Runs a k6 script, pushes k6 metrics into Prometheus (visible in Grafana), saves the summary.
#
#   k6/run.sh <script> <testid> [k6 args...]
#   k6/run.sh k6/sync/ramp.js exp2-ramp
#   k6/run.sh k6/sync/overload.js exp3-overload -e RATE=900
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: $0 <script.js> <testid> [k6 args...]" >&2
  exit 1
fi

SCRIPT=$1
TESTID=$2
shift 2

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PHASE="$(basename "$(dirname "$SCRIPT")")"          # sync | async
OUT_DIR="$ROOT/results/$PHASE"
mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"

export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
export K6_PROMETHEUS_RW_TREND_STATS="p(50),p(95),p(99),avg,max"
export K6_PROMETHEUS_RW_PUSH_INTERVAL="5s"

echo ">> $TESTID  ($SCRIPT)  started $(date '+%H:%M:%S') — summary -> results/$PHASE/$TESTID-$STAMP.json"
k6 run \
  -o experimental-prometheus-rw \
  --tag testid="$TESTID" \
  --summary-export "$OUT_DIR/$TESTID-$STAMP.json" \
  "$@" "$SCRIPT" 2>&1 | tee "$OUT_DIR/$TESTID-$STAMP.log"
