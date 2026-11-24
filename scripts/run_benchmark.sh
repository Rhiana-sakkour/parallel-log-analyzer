#!/usr/bin/env bash
# Full benchmark: sizes 1M / 4M / 16M lines (~89 MiB / ~355 MiB / ~1.4 GiB of generated data, needs ~2 GiB free disk),
# N = 1,2,4,8,16, 2 warm-up + 7 recorded runs per configuration. Takes roughly 5-15 minutes.
#   scripts/run_benchmark.sh           full run
#   scripts/run_benchmark.sh --quick   small sizes, fewer runs (pipeline check only - do NOT use for the report)
# Close other heavy applications first and keep a laptop plugged in; results depend on the machine.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d out ] || scripts/build.sh
mkdir -p results data
if [ "${1:-}" = "--quick" ]; then
  ARGS="--sizes 200000,1000000 --warmup 1 --runs 3 --out results_quick --data-dir data"
else
  ARGS="--out results --data-dir data"
fi
java ${JAVA_OPTS:--Xmx2g} -cp out logan.bench.Benchmark $ARGS
