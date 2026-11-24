#!/usr/bin/env python3
"""Builds the technical report (docx) from the project's REAL benchmark output.

The implementation chapters are fixed text describing the code in src/. Every number, table, figure and every
sentence in the Results / Speedup / Amdahl / Bottleneck sections is generated from results/*.csv, so the report
always matches the machine the benchmark was run on. Nothing is hard-coded or invented.

usage: python3 scripts/build_report.py --results results --out report/report.docx --name "Your Name" --student-id 12345
requires: pip install python-docx   (and run scripts/plot_results.py first for the figures)
"""
import argparse
import sys
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

sys.path.insert(0, str(Path(__file__).parent))
from analysis import Results, LOCAL  # noqa: E402

NAVY = RGBColor(0x1F, 0x3A, 0x5F)


# ------------------------------------------------------------------ docx helpers

def setup(doc):
    s = doc.sections[0]
    s.page_width, s.page_height = Cm(21.0), Cm(29.7)
    s.left_margin = s.right_margin = Cm(1.8)
    s.top_margin, s.bottom_margin = Cm(1.6), Cm(1.6)
    st = doc.styles["Normal"]
    st.font.name = "Calibri"; st.font.size = Pt(9.5)
    st.element.rPr.rFonts.set(qn("w:eastAsia"), "Calibri")
    st.paragraph_format.space_after = Pt(3); st.paragraph_format.space_before = Pt(0)
    st.paragraph_format.line_spacing = 1.0
    h = doc.styles["Heading 1"]
    h.font.name = "Calibri"; h.font.size = Pt(11.5); h.font.bold = True; h.font.color.rgb = NAVY
    h.paragraph_format.space_before = Pt(7); h.paragraph_format.space_after = Pt(2)
    h.paragraph_format.keep_with_next = True
    # footer with page number
    p = s.footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = p.add_run("Parallel Log Analyzer - Parallel Programming, Assignment 01 - page ")
    r.font.size = Pt(8)
    fld = OxmlElement("w:fldSimple"); fld.set(qn("w:instr"), "PAGE")
    rr = OxmlElement("w:r"); t = OxmlElement("w:t"); t.text = "1"; rr.append(t); fld.append(rr)
    p._p.append(fld)


def runs(par, text, size=None, mono=False):
    """Adds text to a paragraph; **bold** segments are supported."""
    for i, seg in enumerate(text.split("**")):
        if not seg:
            continue
        r = par.add_run(seg)
        r.bold = (i % 2 == 1)
        if size: r.font.size = Pt(size)
        if mono: r.font.name = "Consolas"
    return par


def H(doc, text): return doc.add_heading(text, level=1)


def P(doc, text, size=None, align=None):
    p = doc.add_paragraph(); runs(p, text, size)
    if align == "j": p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    return p


def B(doc, items, size=None):
    for it in items:
        p = doc.add_paragraph(style="List Bullet"); runs(p, it, size)
        p.paragraph_format.space_after = Pt(0); p.paragraph_format.left_indent = Cm(0.5)


def shade(cell, color):
    tcPr = cell._tc.get_or_add_tcPr(); shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear"); shd.set(qn("w:color"), "auto"); shd.set(qn("w:fill"), color); tcPr.append(shd)


def table(doc, header, rows, widths=None, size=8, align_right_from=1):
    t = doc.add_table(rows=1, cols=len(header)); t.style = "Table Grid"; t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(header):
        c = t.rows[0].cells[i]; c.text = ""; runs(c.paragraphs[0], f"**{h}**", size); shade(c, "D9E2F3")
    for row in rows:
        cells = t.add_row().cells
        for i, v in enumerate(row):
            cells[i].text = ""; runs(cells[i].paragraphs[0], str(v), size)
            if i >= align_right_from: cells[i].paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.CENTER
    for row in t.rows:
        for c in row.cells:
            for p in c.paragraphs: p.paragraph_format.space_after = Pt(0)
    if widths:
        t.autofit = False
        for i, w in enumerate(widths): t.columns[i].width = Cm(w)
        for row in t.rows:
            for i, w in enumerate(widths): row.cells[i].width = Cm(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(0)
    return t


def figures(doc, paths, caption, width=8.6):
    t = doc.add_table(rows=1, cols=len(paths)); t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, p in enumerate(paths):
        c = t.rows[0].cells[i]; c.text = ""
        c.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.CENTER
        if Path(p).exists(): c.paragraphs[0].add_run().add_picture(str(p), width=Cm(width))
        else: runs(c.paragraphs[0], f"[missing figure {Path(p).name} - run scripts/plot_results.py]")
    cp = doc.add_paragraph(); cp.alignment = WD_ALIGN_PARAGRAPH.CENTER
    runs(cp, f"*{caption}*".replace("*", ""), 8); cp.runs[0].italic = True


def code(doc, text):
    p = doc.add_paragraph(); p.paragraph_format.left_indent = Cm(0.5)
    r = p.add_run(text); r.font.name = "Consolas"; r.font.size = Pt(7.5)


# ------------------------------------------------------------------ data-driven narrative

def narrative(r):
    n = {}
    big = r.sizes[-1]
    n_ref = max([k for k in r.threads if k <= r.cores] or [1])
    nmax = max(r.threads)
    best = r.best()

    # ---- environment note
    if r.cores < nmax:
        n["env"] = (f"The machine exposes **{r.cores} logical processor(s)**, fewer than the largest tested N={nmax}. "
                    f"Configurations with N > {r.cores} therefore oversubscribe the CPU: the operating system time-slices the threads, "
                    f"and no additional hardware parallelism exists to be exploited.")
    else:
        n["env"] = f"The machine exposes **{r.cores} logical processors**, so every tested N can run truly in parallel (if they are physical cores or SMT siblings is not distinguished)."

    # ---- speedup
    s = []
    if r.cores == 1:
        s.append("**This run was produced on a machine with a single logical processor.** True parallel execution is impossible there: "
                 "N threads are merely time-sliced on one core, so the achievable speedup is at most 1. The numbers below therefore measure the "
                 "**overhead** of the multi-threaded engine (thread start-up, chunking, merge, context switching), not its benefit. "
                 "A submission-quality speedup analysis must use results generated on a multi-core machine.")
    s.append(f"The highest speedup of the default strategy was **{best['speedup']:.2f}x** "
             f"(N={best['threads']}, {best['lines']:,} lines), i.e. an efficiency of {100 * best['efficiency']:.0f}%.")
    if r.cores == 1 and best["speedup"] >= 1.0:
        s.append(f"On this single-processor machine such a value is within run-to-run noise (standard deviation of that configuration: {best['stdev_ms']:.1f} ms, "
                 f"sequential: {r.seq(best['lines'])['stdev_ms']:.1f} ms) and must be read as 'no speedup', not as a gain.")
    per_size = ", ".join(f"{r.par(sz, n_ref)['speedup']:.2f}x ({sz / 1e6:g}M lines)" for sz in r.sizes if r.par(sz, n_ref))
    s.append(f"At N={n_ref}" + (f" (= number of logical processors)" if n_ref == r.cores else "") + f" the speedups were {per_size}.")
    if r.cores > 1 and n_ref < nmax:
        a, b = r.par(big, n_ref)["speedup"], r.par(big, nmax)["speedup"]
        if b < a * 1.05:
            s.append(f"Going beyond the processor count did not help: for the largest input the speedup at N={nmax} ({b:.2f}x) is not better than at N={n_ref} ({a:.2f}x).")
        else:
            s.append(f"For the largest input the speedup still rose from {a:.2f}x at N={n_ref} to {b:.2f}x at N={nmax} (possible with SMT siblings or I/O waiting).")
    small, large = r.sizes[0], r.sizes[-1]
    if r.cores >= 2 and small != large and r.par(small, n_ref) and r.par(large, n_ref):
        a, b = r.par(small, n_ref)["speedup"], r.par(large, n_ref)["speedup"]
        if b > a * 1.03:
            s.append(f"Larger inputs scaled better ({a:.2f}x for {small / 1e6:g}M lines vs {b:.2f}x for {large / 1e6:g}M lines at N={n_ref}) because fixed costs (thread start-up, merge) are amortized over more work.")
        elif a > b * 1.03:
            s.append(f"The smaller input achieved the higher speedup ({a:.2f}x vs {b:.2f}x at N={n_ref}); a possible (not separately verified) reason is that the largest file is more exposed to memory and cache effects.")
        else:
            s.append(f"Speedup was practically independent of input size at N={n_ref} ({a:.2f}x vs {b:.2f}x).")
    p1 = r.par(big, 1)
    if p1:
        s.append(f"**N=1 vs sequential:** the multi-threaded engine with a single worker needed {100 * p1['median_ms'] / r.seq(big)['median_ms']:.0f}% of the sequential time "
                 f"on the largest input; the architectural difference is the thread pool, chunk-wise scanning (re-opening line boundaries) and the final merge into a fresh LogStats.")
    n["speedup"] = s

    # ---- Amdahl
    a = []
    share = r.measured_serial_share(big, n_ref)
    if share is not None:
        a.append(f"The strictly sequential phases of the parallel engine are *plan + thread start-up* and *merge*. Measured at N={n_ref} on the largest input they take "
                 f"**{100 * share:.2f}%** of the wall time (Table 3); by Amdahl's law this share alone would cap the speedup at 1/s = {1 / share:.0f}x, "
                 f"so on this data set the explicit sequential code is **not** what limits scalability.")
    fit = r.fit_serial_fraction(big)
    kfs = [(k, r.karp_flatt(r.par(big, k)["speedup"], k)) for k in r.threads if 2 <= k <= r.cores and r.par(big, k)]
    if fit is not None:
        a.append(f"Fitting S(N) = 1 / (s + (1-s)/N) to the measured speedups of the largest input for 2 <= N <= {r.cores} gives an **effective serial fraction s = {fit:.3f}** "
                 f"(parallel fraction p = {1 - fit:.3f}), i.e. a theoretical limit of {1 / fit:.1f}x" if fit > 0 else
                 "The fit gives s = 0 (no measurable serial fraction in the tested range).")
        if fit > 0 and share is not None and fit > 2 * share:
            a[-1] += (f". The fitted s is much larger than the measured sequential share ({100 * share:.2f}%): the difference is "
                      f"'hidden' serialization - shared resources that behave like a serial part (memory bandwidth, page-cache copies, allocator/GC, cache coherence).")
        else:
            a[-1] += "."
        if kfs:
            vals = [e for _, e in kfs]
            trend = ("roughly constant" if max(vals) <= 1.5 * max(min(vals), 1e-9) else ("increasing" if vals[-1] > vals[0] else "decreasing"))
            a.append("Karp-Flatt estimates e(N) = " + ", ".join(f"{e:.3f} (N={k})" for k, e in kfs) + f" - {trend} with N. "
                     + ("A constant e indicates a genuine serial fraction (Amdahl); a growing e indicates overheads that grow with N (contention, imbalance, bandwidth)."))
    else:
        a.append(f"With {r.cores} logical processor(s) a fit of Amdahl's law is not meaningful (the law assumes N independent processing units), so no effective serial fraction is reported for this machine.")
    a.append("Why speedup is not linear: (1) the sequential phases above; (2) per-thread start-up and pool shutdown; (3) tail effect - the last chunks are processed by fewer busy threads; "
             "(4) shared hardware - all threads read the same file through the page cache and share memory bandwidth and caches; (5) synchronization, quantified in Table 2; "
             "(6) N larger than the processor count only adds scheduling overhead. These are the reasons the curve flattens at large N.")
    n["amdahl"] = a

    # ---- bottlenecks
    b = []
    ph = r.phase(big, n_ref)
    if ph and r.cores >= 2 and n_ref >= 2:
        b.append(f"**Load balance:** the busiest worker was {ph['busy_max_over_mean']:.2f}x the average worker time at N={n_ref} (1.00 = perfect; worker time is the wall time between a worker's start and end); dynamic chunk self-scheduling keeps this close to 1.")
    elif ph:
        b.append("**Load balance:** not assessable on this machine - with one logical processor all workers are time-sliced and every worker's wall time spans almost the whole run.")
    if r.strategy_size is not None:
        sz = r.strategy_size
        L, C, G = (r.par(sz, n_ref, st) for st in (LOCAL, "SHARED_CONCURRENT", "GLOBAL_LOCK"))
        if L and C and G:
            def rel(x): return f"{x['median_ms'] / L['median_ms']:.2f}x the time"
            txt = (f"**Synchronization cost** ({sz:,} lines, N={n_ref}): the shared lock-free structure needed {rel(C)} of the default design, and the single global lock {rel(G)}. ")
            if r.cores >= 2 and n_ref >= 2:
                txt += ("Per-line shared updates (atomics on hot cells such as status 200, a shared hash map) cost cache-line traffic even without a lock; "
                        "thread-confined accumulation avoids it entirely.")
            else:
                txt += ("On a single logical processor there is no cache-line contention between cores, so this difference only reflects the raw per-line cost of atomic operations and lock acquisition; "
                        "contention effects (which make strategies B and C scale even worse) can only be measured on a multi-core machine.")
            b.append(txt)
    b.append("**Hypotheses not separately profiled in this project:** parsing allocates one String per line for the path (GC pressure, which grows with N); "
             "file bytes are copied from the page cache into heap buffers (memory bandwidth); the final merge of per-thread hash maps is O(distinct paths x N).")
    n["bottleneck"] = b

    # ---- data-driven conclusion about the synchronization strategies
    sc = ""
    if r.strategy_size is not None:
        sz = r.strategy_size
        names = {LOCAL: "local accumulators + merge", "SHARED_CONCURRENT": "shared concurrent structures", "GLOBAL_LOCK": "single global lock"}
        if r.cores >= 2 and n_ref >= 2:
            ps = {k: r.par(sz, n_ref, k) for k in names}
            if all(ps.values()):
                top = max(ps, key=lambda k: ps[k]["speedup"])
                sc = (f"At N={n_ref} ({sz:,} lines) the speedups were " + ", ".join(f"{ps[k]['speedup']:.2f}x ({names[k]})" for k in names) + ". ")
                if top == LOCAL:
                    sc += ("The default design - thread-confined accumulation plus one merge - was the fastest, which supports the design choice: the less shared state is touched per line, the better it scales.")
                else:
                    sc += (f"In this run the default design was NOT the fastest ({names[top]} was), so the expected advantage of thread-confined accumulation was not confirmed on this machine and data; "
                           f"the differences should be compared with the standard deviations before drawing conclusions.")
        else:
            sc = ("On a single logical processor there is no inter-core contention, so Table 2 reflects only the raw per-line cost of atomic operations / lock acquisition plus run-to-run noise; "
                  "its differences cannot be read as scalability effects. Ranking the strategies by scalability is only meaningful on a multi-core machine.")
    n["strategy"] = sc
    return n


# ------------------------------------------------------------------ report

def build(res_dir, out_path, name, sid):
    r = Results(res_dir)
    nar = narrative(r)
    big = r.sizes[-1]
    doc = Document(); setup(doc)

    t = doc.add_paragraph(); t.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = t.add_run("Parallel Log Analyzer"); run.bold = True; run.font.size = Pt(17); run.font.color.rgb = NAVY
    st = doc.add_paragraph(); st.alignment = WD_ALIGN_PARAGRAPH.CENTER
    runs(st, "Using threads to build a parallel algorithm and a practical application - sequential vs multi-threaded processing of large web-server logs", 10.5)
    who = doc.add_paragraph(); who.alignment = WD_ALIGN_PARAGRAPH.CENTER
    runs(who, f"Parallel Programming - Assignment 01 (Path 1: parallel algorithm + application)  |  Student: {name}  |  ID: {sid}", 9)

    H(doc, "1. Introduction")
    P(doc, "Web servers append one line per request to access logs that grow to gigabytes. Extracting the error rate, the most requested URLs, the clients that trigger server errors "
           "and the load per hour from such a file is a classic data-parallel workload. This report presents **Parallel Log Analyzer**, a Java desktop application with a sequential and a "
           "multi-threaded analysis engine, a test suite that proves both give identical results, and a benchmark that measures the real speedup.", align="j")

    H(doc, "2. Problem Definition")
    P(doc, "**Input:** a text log in Apache common-log format extended with the response time: "
           "`IP - - [dd/Mon/yyyy:HH:mm:ss +0000] \"METHOD /path?query HTTP/1.1\" STATUS BYTES LATENCY_MS`.".replace("`", ""), align="j")
    P(doc, "**Output:** line counts (valid / malformed), a histogram of HTTP status codes, requests per hour of the day, the top-K requested paths, the top-K clients receiving 5xx errors, "
           "total bytes, and average and maximum latency. Lines are independent of each other and the aggregation consists of sums and a maximum, so the problem decomposes cleanly. "
           "The three real difficulties are (a) chunk boundaries that fall inside a line, (b) combining results from many threads without races or needless locking, and "
           "(c) keeping the GUI responsive.", align="j")

    H(doc, "3. Objectives")
    B(doc, ["Implement the same algorithm as a single-threaded and a multi-threaded engine, sharing all per-line code.",
            "Prove correctness: sequential result == multi-threaded result for every tested size, N and chunk size.",
            "Measure execution time and speedup for N = 1, 2, 4, 8, 16 and three data sizes, with a reproducible benchmark.",
            "Explain the observed behaviour with Amdahl's law and identify the bottlenecks.",
            "Wrap the engines in a desktop application that never freezes during heavy processing."])

    H(doc, "4. System Architecture")
    P(doc, "The code is split into packages with one-way dependencies: `ui` and `bench` depend on `core`; `core` depends on nothing. All threading logic lives in `core`; the Swing UI only starts a background "
           "task and displays results.".replace("`", ""), align="j")
    table(doc, ["Thread", "Role", "Mutable shared state it touches"], [
        ["Event Dispatch Thread (EDT)", "Draws the UI; a timer polls progress and a heartbeat counter ticks", "reads ProgressTracker (atomics)"],
        ["SwingWorker thread", "Calls analyze(); the coordinator (fork, join, merge)", "creates pool; reads Futures"],
        ["log-worker-0 .. N-1 (pool)", "Pull chunks, parse lines, aggregate into private LogStats", "AtomicInteger nextChunk, ProgressTracker"],
    ], widths=[4.2, 8.0, 5.2], size=8, align_right_from=9)
    P(doc, "Main classes: `SequentialAnalyzer`, `ParallelAnalyzer`, `LineRangeScanner` (chunk-safe line reader), `LogParser` (allocation-free byte parser), `LogStats` (mergeable result), "
           "`ConcurrentAccumulator` / `LockedAccumulator` (alternative strategies), `LogGenerator`, `Benchmark`, `LogAnalyzerApp`.".replace("`", ""), size=9)

    H(doc, "5. Algorithm")
    P(doc, "One pass over the file. For each line the parser extracts IP, hour, path (query string removed), status, bytes and latency directly from the byte buffer (no intermediate String except the "
           "path, and the IP of 5xx lines) and updates: a 600-slot status array, a 24-slot hour array, a hash map path->count, a hash map IP->count (5xx only) and scalar totals. Time is O(L) for L lines; memory is "
           "O(P + C) for P distinct paths and C distinct error clients. All counters are 64-bit integers combined by addition (and max), which are associative and commutative: the result does not depend on how "
           "lines are split among threads or on the merge order, and - with no floating point - equality between the sequential and the parallel result can be checked **exactly**. "
           "Rankings break ties by key so they are deterministic.", align="j")

    H(doc, "6. Sequential Implementation")
    P(doc, "`SequentialAnalyzer` opens one `LineRangeScanner` over the whole range [0, size) and feeds every line through `AccumulatingHandler` into a single `LogStats`. It is the baseline of every speedup "
           "and uses exactly the same parsing and aggregation code as the parallel version, so the only difference between the two is how the work is distributed.".replace("`", ""), align="j")

    H(doc, "7. Multi-threaded Implementation")
    P(doc, "**Data decomposition.** The file is cut into fixed-size chunks (default 1 MiB) - the units of work. **Boundary rule:** a line belongs to the chunk that contains its first byte; "
           "a chunk starting inside a line skips that partial line and a chunk reads past its end just far enough to finish its last line. Chunks therefore partition the lines exactly, with no overlap and "
           "no communication between chunks. This rule is tested exhaustively (see section 10).", align="j")
    P(doc, "**Execution.** N identical workers run in a fixed thread pool. Each worker loops: `c = nextChunk.getAndIncrement()`; if `c` is past the last chunk it stops, otherwise it scans chunk `c` "
           "with its own scanner (own file channel, own 256 KiB buffer, own `LogEntry`) into its own `LogStats`. When all workers have finished the coordinator merges the N partial `LogStats` into the result.".replace("`", ""), align="j")
    code(doc, "plan -> pool = newFixedThreadPool(N) -> submit N workers -> take() each Future (join) -> merge partial LogStats")

    H(doc, "8. Thread Management")
    B(doc, ["**Creation:** `Executors.newFixedThreadPool(N)` with a named daemon `ThreadFactory` (log-worker-i); the pool is created per analysis so thread start-up cost is part of the measured time.".replace("`", ""),
            "**Work distribution:** dynamic self-scheduling through one atomic counter - a thread that finishes early simply takes more chunks, which balances load without a scheduler thread.",
            "**Waiting / joining:** `ExecutorCompletionService.take().get()` once per worker, in completion order, so the first failure or cancellation is noticed immediately; `shutdownNow()` in a `finally` block stops the rest.".replace("`", ""),
            "**Result aggregation:** after the join, on the coordinator thread only (section 9 explains why this is safe).",
            "**Cancellation / progress:** a `ProgressTracker` (AtomicBoolean + AtomicLong) is checked once per buffer refill, so Cancel reacts within milliseconds.".replace("`", "")], size=9)

    H(doc, "9. Synchronization")
    table(doc, ["Primitive", "Where", "Why it is needed - and why nothing stronger is used"], [
        ["AtomicInteger nextChunk", "ParallelAnalyzer", "Hands each chunk to exactly one worker; one lock-free getAndIncrement per 1 MiB of work, so contention is negligible."],
        ["Thread pool + Future.get()", "ParallelAnalyzer", "Creates/joins threads; get() gives a happens-before edge, so the coordinator sees each worker's private LogStats without any lock."],
        ["AtomicLong / AtomicBoolean", "ProgressTracker", "Many workers add progress, the UI thread reads it; the UI sets 'cancelled', workers read it. Visibility without blocking the EDT."],
        ["ConcurrentHashMap, LongAdder, AtomicLongArray", "ConcurrentAccumulator", "Strategy B (comparison only): shared lock-free aggregation - correct but every line touches shared cache lines."],
        ["ReentrantLock", "LockedAccumulator", "Strategy C (baseline only): one coarse lock around a shared LogStats - correct but serializes the hot path."],
    ], widths=[4.3, 3.3, 9.8], size=8, align_right_from=9)
    P(doc, "The **default design takes no lock on the hot path**: all per-line state is thread-confined, which is exactly why locks would only hurt (Table 2 measures this). "
           "No `Semaphore` is used: memory is already bounded by construction (at most N buffers of 256 KiB exist), so a permit counter would add cost without adding safety.".replace("`", ""), align="j")

    H(doc, "10. Race Condition / Deadlock Prevention")
    B(doc, ["**Races:** chunks are disjoint (boundary rule); the scanner buffer, `LogEntry` and `LogStats` of a worker are never shared; `LogParser` is stateless; shared data is only the atomic counter, the tracker and - in strategies B/C - structures that are themselves thread-safe. "
            "Partial results are read by the coordinator only after `Future.get()`.".replace("`", ""),
            "**Deadlocks:** the default strategy acquires no lock at all. Strategy C holds exactly one lock, never nests locks, and calls nothing that can block while holding it, so a circular wait is impossible. Workers never wait for each other.",
            "**Evidence, not just argument:** 38 automated tests including (i) 300 random files x every chunk size x tiny buffers against an independent oracle, (ii) sequential == parallel for 3 data sizes x N in {1,2,3,4,8,16} x 3 chunk sizes (default strategy) and for all 3 strategies on 50k lines x N in {1,4,16}, "
            "(iii) a stress test of 40 repeated runs per strategy with tiny chunks. During development the tests were checked against deliberate bugs: removing the lock of strategy C, shifting the chunk start offset and forgetting one field in the merge were each detected."], size=9)

    H(doc, "11. Experimental Setup")
    e = r.env
    table(doc, ["Item", "Value"], [
        ["CPU / logical processors", f"{e.get('cpu_model', '?')} / {r.cores}"],
        ["OS / Java", f"{e.get('os', '?')} / {e.get('java_version', '?')}   (max heap {e.get('max_heap_mb', '?')} MiB)"],
        ["Data", "synthetic logs from `LogGenerator` (fixed seed 42, byte-identical on every machine): ".replace("`", "")
                 + ", ".join(f"{s_:,} lines = {r.mb(s_):.0f} MiB" for s_ in r.sizes)],
        ["Threads N / chunk size", f"{', '.join(map(str, r.threads))} / {int(e.get('chunk_bytes', 1048576)) // 1024} KiB"],
        ["Repetitions", f"{e.get('warmup_runs', '?')} warm-up + {e.get('recorded_runs', '?')} recorded runs per configuration, plus a global JIT warm-up"],
    ], widths=[4.3, 13.1], size=8, align_right_from=9)
    P(doc, nar["env"], size=9, align="j")

    H(doc, "12. Benchmark Methodology")
    B(doc, ["Same deterministic input for all configurations of a size; the file is in the OS page cache after the first pass, so the benchmark measures **CPU-bound processing, not disk speed** (a stated limitation).",
            "Warm-up runs are discarded so the JIT compiler has optimized the hot loops; `System.gc()` before each recorded run; times from `System.nanoTime()` around the whole `analyze()` call (includes pool creation and merge).".replace("`", ""),
            "**Speedup = T_sequential / T_parallel** using the **median** of the recorded runs (robust to one-off OS hiccups); mean, standard deviation, min and max are in `results/benchmark_results.csv`, every single run in `benchmark_raw.csv`. Efficiency = Speedup / N.".replace("`", ""),
            "**Correctness gate:** every recorded parallel run is compared for equality with the sequential result; the benchmark aborts on any mismatch."], size=9)

    H(doc, "13. Results")
    rows = []
    for size in r.sizes:
        sq = r.seq(size)
        for k in r.threads:
            p = r.par(size, k)
            if p:
                rows.append([f"{size / 1e6:g}M ({r.mb(size):.0f} MiB)", k, f"{sq['median_ms']:.1f}", f"{p['median_ms']:.1f}", f"{p['speedup']:.2f}",
                             f"{100 * p['efficiency']:.0f}%", f"{p['stdev_ms']:.1f}"])
    P(doc, "**Table 1 - Sequential vs multi-threaded (median of recorded runs; default strategy).**", size=8.5)
    table(doc, ["Data size (lines)", "Threads N", "Sequential (ms)", "Parallel (ms)", "Speedup", "Efficiency", "Std dev (ms)"], rows,
          widths=[3.6, 2.0, 2.7, 2.5, 2.0, 2.2, 2.4], size=7.5, align_right_from=1)
    figures(doc, [Path(res_dir) / "time_vs_threads.png", Path(res_dir) / "speedup_vs_threads.png"],
            "Figure 1 (left): execution time vs N; dashed = sequential. Figure 2 (right): speedup vs N; dashed = ideal; dotted vertical = logical processors.")

    H(doc, "14. Speedup Analysis")
    for s_ in nar["speedup"]: P(doc, s_, align="j")

    H(doc, "15. Amdahl's Law")
    P(doc, "Amdahl's law bounds the speedup of a program whose fraction p is parallelizable: **S(N) = 1 / ((1 - p) + p / N)**, with limit 1 / (1 - p) for N -> infinity. "
           "Karp-Flatt turns a measured speedup into the experimentally observed serial fraction **e = (1/S - 1/N) / (1 - 1/N)**.", align="j")
    for s_ in nar["amdahl"]: P(doc, s_.replace("*plan + thread start-up*", "plan + thread start-up").replace("*merge*", "merge"), align="j")

    H(doc, "16. Bottlenecks")
    for s_ in nar["bottleneck"]: P(doc, s_, align="j")
    table_rows = []
    if r.strategy_size is not None:
        for k in r.threads:
            cells = []
            for stg in (LOCAL, "SHARED_CONCURRENT", "GLOBAL_LOCK"):
                p = r.par(r.strategy_size, k, stg)
                cells.append(f"{p['median_ms']:.0f} ms / {p['speedup']:.2f}x" if p else "-")
            table_rows.append([k] + cells)
        P(doc, f"**Table 2 - Synchronization strategies, {r.strategy_size:,} lines (time / speedup).**", size=8.5)
        table(doc, ["N", "Local accumulators + merge (default)", "Shared ConcurrentHashMap/atomics", "Single global lock"], table_rows,
              widths=[1.4, 5.6, 5.4, 5.0], size=7.5, align_right_from=0)
    figures(doc, [Path(res_dir) / "strategy_comparison.png", Path(res_dir) / "phase_breakdown.png"],
            "Figure 3 (left): speedup of the three synchronization strategies. Figure 4 (right): where wall-clock time goes (sequential phases vs parallel scan).")

    H(doc, "17. Discussion")
    B(doc, ["**Design lesson:** the three strategies give identical results (tests) but may differ in cost (Table 2). " + nar["strategy"],
            "**N = 1:** not identical to the sequential run - it still pays for the pool, chunk handling and merge (section 14).",
            "**Validity limits:** synthetic (but realistically skewed) data; a warm page cache; one machine; JIT and garbage-collector behaviour add run-to-run noise (see standard deviations). "
            "Results on a different machine will differ in absolute numbers; the methodology and the reported ratios are what carry over."], size=9)

    H(doc, "18. Conclusion")
    P(doc, f"A sequential and a multi-threaded log analyzer were implemented with the same per-line code and verified to produce identical results across sizes, thread counts, chunk sizes and strategies. "
           f"Thread-confined accumulation with a single atomic work counter avoids all locks on the hot path, rules out data races by construction and cannot deadlock. "
           f"The measured outcome on the benchmark machine ({r.cores} logical processor(s)) is summarized in Tables 1-3: best speedup {r.best()['speedup']:.2f}x at N={r.best()['threads']} on {r.best()['lines']:,} lines. "
           f"The GUI runs everything on background threads, shows progress and a live heartbeat, and lets the user compare both versions on any file.", align="j")

    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    doc.save(out_path)
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default="results")
    ap.add_argument("--out", default="report/report.docx")
    ap.add_argument("--name", default="[Student name]")
    ap.add_argument("--student-id", default="[ID]")
    a = ap.parse_args()
    r = build(a.results, a.out, a.name, a.student_id)
    print(f"report written to {a.out}  (benchmark machine: {r.cores} logical processor(s))")
    if r.cores < 4:
        print("WARNING: this benchmark ran on a machine with fewer than 4 logical processors - it cannot show meaningful "
              "speedup. Re-run scripts/run_benchmark.sh on a multi-core machine before submitting.")


if __name__ == "__main__":
    main()
