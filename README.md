# Parallel Log Analyzer

Parallel Programming - Assignment 01, **Path 1**: a parallel algorithm (data-parallel web-server log analysis) integrated into a practical desktop application, implemented as a **sequential** and a **multi-threaded** version, with tests, a real benchmark, speedup / Amdahl analysis and a technical report.

What it computes for an access log: valid/malformed line counts, HTTP status histogram, requests per hour, top-K paths, top-K clients receiving 5xx errors, total bytes, average/max latency.

```
Sequential : 1 thread scans [0, size)                         -> 1 LogStats
Parallel   : N workers self-schedule 1 MiB chunks (AtomicInteger)
             -> N private LogStats (no locks) -> merge after join
```

## 1. Requirements

| Need | Version |
|---|---|
| JDK (with `javac`) | 17 or newer (developed on 21) |
| Python 3 + `matplotlib`, `python-docx` | only for figures and the report: `pip install matplotlib python-docx` |
| Free disk | ~2 GiB for the full benchmark data (generated automatically in `data/`) |

No other libraries: the tests use a tiny built-in runner (no JUnit download), the GUI is plain Swing.

Check: `java -version` and `javac -version`.

## 2. Build, test, run (Linux / macOS / WSL)

```bash
cd parallel-log-analyzer
scripts/build.sh            # compiles main + tests into out/
scripts/run_tests.sh        # 38 tests, exit code 0 = all pass; log in results/test_report.txt
scripts/run_app.sh          # desktop GUI
```

### Windows (PowerShell / cmd, no WSL needed)

```bat
scripts\build.bat
scripts\run_tests.bat
scripts\run_app.bat
```

(Plain commands, any OS: `javac -d out $(find src/main src/test -name '*.java')` then `java -cp out logan.TestRunner` and `java -cp out logan.ui.LogAnalyzerApp`.)

> WSL: the GUI needs WSLg (Windows 11 / recent Windows 10) - otherwise run the GUI with Windows Java. For **benchmarking prefer native Windows or native Linux**, not a VM with 1-2 virtual CPUs: the number of logical processors decides what speedup is physically possible.

## 3. Using the application

1. **Generate sample log...** (or **Browse...** to open your own log in the format below).
2. Choose **Mode**: *Sequential*, *Multi-threaded* (set N, chunk size, strategy) or **Compare both** (runs each version `Runs` times, shows medians, **Speedup = T_seq / T_par**, efficiency, and checks that both results are identical).
3. **Analyze** - the window stays responsive: progress bar, a live *UI heartbeat* counter that keeps ticking, and **Cancel**.
4. Tabs show summary, status codes, top paths, top error clients, requests per hour, and per-thread work (chunks / lines / busy time).

Log line format: `IP - - [dd/Mon/yyyy:HH:mm:ss +0000] "GET /path?x=1 HTTP/1.1" 200 5123 87` (last field = latency in ms; bytes may be `-`).
Generate a file from the command line: `java -cp out logan.gen.LogGenerator 1000000 sample.log`.

## 4. Benchmark -> figures -> report

```bash
scripts/run_benchmark.sh            # ~5-15 min: 1M/4M/16M lines x N=1,2,4,8,16, 2 warm-up + 7 runs each
python3 scripts/plot_results.py results
python3 scripts/build_report.py --results results --out report/report.docx --name "Your Name" --student-id 12345
```
or everything in one go: `scripts/run_all.sh` (tests + race demo + benchmark + figures + report).
Open `report/report.docx` in Word and *Save as PDF* for `report.pdf`.

Quick pipeline check (NOT for the report): `scripts/run_benchmark.sh --quick` -> `results_quick/`.
Custom run: `java -cp out logan.bench.Benchmark --sizes 2000000,8000000 --threads 1,2,4,8,16 --warmup 2 --runs 7 --chunk-kb 1024`.

Outputs in `results/`:

| File | Content |
|---|---|
| `benchmark_results.csv` | per configuration: runs, mean, **median**, stdev, min, max, **speedup**, efficiency |
| `benchmark_raw.csv` | every single timed run |
| `phase_breakdown.csv` | plan / parallel scan / merge times, busiest-vs-average thread |
| `environment.txt` | CPU, logical processors, OS, Java, parameters (goes into the report) |
| `time_vs_threads.png`, `speedup_vs_threads.png`, `strategy_comparison.png`, `phase_breakdown.png` | figures |
| `results_tables.md` | ready-to-paste markdown tables |
| `test_report.txt`, `race_demo.txt` | test log and race/synchronization demo output |

**The report is generated from these files - numbers come only from real runs on the machine that ran the benchmark.** If that machine has few logical processors the builder prints a warning: re-run on a multi-core machine before submitting.

## 5. Project structure

```
parallel-log-analyzer/
├── src/main/java/logan/
│   ├── core/    (all algorithm + threading code, no UI dependency)
│   │   ├── SequentialAnalyzer.java    baseline: one scanner over the whole file
│   │   ├── ParallelAnalyzer.java      thread pool, chunk self-scheduling, join, merge
│   │   ├── LineRangeScanner.java      chunk-safe line reader (first-byte ownership rule)
│   │   ├── LogParser.java / LogEntry.java   allocation-free parser, reusable per-thread entry
│   │   ├── LogStats.java              mergeable result (+ strict equals for correctness checks)
│   │   ├── Accumulator.java, AccumulatingHandler.java   sink + glue shared by both versions
│   │   ├── ConcurrentAccumulator.java Strategy B: ConcurrentHashMap/LongAdder/atomics
│   │   ├── LockedAccumulator.java     Strategy C: one ReentrantLock (baseline)
│   │   ├── Strategy.java, ParallelConfig.java, AnalysisResult.java, Analyzer.java
│   │   ├── ProgressTracker.java       AtomicLong progress + AtomicBoolean cancel
│   │   └── NamedThreadFactory.java
│   ├── gen/LogGenerator.java          deterministic synthetic logs (fixed seed)
│   ├── bench/Benchmark.java           warm-up, repeated runs, median, correctness gate, CSV output
│   ├── demo/RaceConditionDemo.java    lost updates vs lock vs AtomicLong vs LongAdder
│   └── ui/LogAnalyzerApp.java         Swing GUI (SwingWorker, progress, heartbeat)
├── src/test/java/logan/               TestRunner + parser / scanner / equivalence / edge-case tests
├── scripts/                           build, run_tests, run_app, run_benchmark, run_all (.sh/.bat), analysis.py, plot_results.py, build_report.py
├── results/                           benchmark CSVs, figures, tables (generated by YOUR run)
├── sandbox_preview_1vcpu/             real but 1-core results + preview report + GUI screenshot (NOT for submission)
├── report/report.docx                 technical report (generated; export to PDF)
└── README.md
```

## 6. Design decisions in one paragraph

Lines are independent and the result is made of sums and a max, so the file is cut into chunks and each worker aggregates into a **private** `LogStats`; partial results are merged once after the join (`Future.get()` provides the happens-before edge). The only shared mutable state on the hot path is one `AtomicInteger` (next chunk). A line belongs to the chunk containing its first byte, so chunks never overlap and never split a line. Two alternative synchronization strategies (shared concurrent structures, one global lock) are implemented **only to be measured** against the default. Everything is 64-bit integer arithmetic, so "sequential == parallel" is an exact equality test.

## 7. Limitations (state them in the report)

* Benchmark data are synthetic (skewed, realistic shape, fixed seed); no real server logs.
* The file is read from the OS page cache after the first pass: CPU-bound behaviour is measured, not disk speed.
* Speedup is bounded by the machine's logical processors; N > processors only adds scheduling overhead.
* Single machine, JIT/GC noise: hence warm-up, repeated runs, median and standard deviation.
