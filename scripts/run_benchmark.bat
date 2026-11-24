@echo off
REM Full benchmark (see run_benchmark.sh for details). Then: python scripts\plot_results.py results
cd /d "%~dp0.."
if not exist out call scripts\build.bat
if not exist results mkdir results
if not exist data mkdir data
java -Xmx2g -cp out logan.bench.Benchmark --out results --data-dir data
