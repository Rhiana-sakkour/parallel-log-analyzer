#!/usr/bin/env bash
# One command from zero to final deliverables: build -> tests -> race demo -> benchmark -> figures/tables -> report.
# Needs: JDK 17+, Python 3 with `pip install matplotlib python-docx`.
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/build.sh
scripts/run_tests.sh
mkdir -p results
java -cp out logan.demo.RaceConditionDemo 8 50000000 | tee results/race_demo.txt
scripts/run_benchmark.sh
python3 scripts/plot_results.py results
python3 scripts/build_report.py --results results --out report/report.docx "$@"
echo
echo "Done. Figures/tables: results/   Report: report/report.docx (open in Word -> File > Save as PDF)"
