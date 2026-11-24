#!/usr/bin/env bash
# Runs the whole test suite (exit code 0 = all passed). Output is also saved to results/test_report.txt
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d out ] || scripts/build.sh
mkdir -p results
java ${JAVA_OPTS:-} -cp out logan.TestRunner | tee results/test_report.txt
