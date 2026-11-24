#!/usr/bin/env python3
"""Creates the figures and ready-to-paste markdown tables from results/*.csv (real measurements only).

usage: python3 scripts/plot_results.py [results_dir]
outputs (in results_dir): time_vs_threads.png, speedup_vs_threads.png, strategy_comparison.png,
                          phase_breakdown.png, results_tables.md
"""
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

sys.path.insert(0, str(Path(__file__).parent))
from analysis import Results, LOCAL  # noqa: E402

COLORS = ["#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e"]


def size_label(r, size):
    return f"{size / 1e6:g}M lines ({r.mb(size):.0f} MiB)"


def fig_time(r, out):
    fig, ax = plt.subplots(figsize=(5.2, 3.6), dpi=170)
    for i, size in enumerate(r.sizes):
        c = COLORS[i % len(COLORS)]
        ns = [n for n in r.threads if r.par(size, n)]
        ax.errorbar(ns, [r.par(size, n)["median_ms"] for n in ns], yerr=[r.par(size, n)["stdev_ms"] for n in ns],
                    marker="o", color=c, capsize=2, label=f"parallel, {size_label(r, size)}")
        ax.axhline(r.seq(size)["median_ms"], color=c, linestyle="--", linewidth=1,
                   label=f"sequential, {size / 1e6:g}M lines")
    ax.set_xscale("log", base=2); ax.set_yscale("log")
    ax.set_xticks(r.threads); ax.set_xticklabels([str(n) for n in r.threads])
    if r.cores >= 1:
        ax.axvline(r.cores, color="grey", linestyle=":", linewidth=1)
        ax.text(r.cores, ax.get_ylim()[0], f" {r.cores} logical CPU(s)", fontsize=6, color="grey", va="bottom")
    ax.set_xlabel("Number of threads N"); ax.set_ylabel("Execution time, median (ms, log scale)")
    ax.set_title("Execution time vs number of threads", fontsize=9)
    ax.grid(True, which="both", alpha=0.3)
    ax.legend(fontsize=5.5, ncol=2, loc="upper center", bbox_to_anchor=(0.5, -0.2))
    fig.tight_layout(); fig.savefig(out / "time_vs_threads.png", bbox_inches="tight"); plt.close(fig)


def fig_speedup(r, out):
    fig, ax = plt.subplots(figsize=(5.2, 3.6), dpi=170)
    nmax = max(r.threads)
    ax.plot([1, nmax], [1, nmax], color="grey", linestyle="--", linewidth=1, label="ideal (linear)")
    for i, size in enumerate(r.sizes):
        c = COLORS[i % len(COLORS)]
        ns = [n for n in r.threads if r.par(size, n)]
        ax.plot(ns, [r.par(size, n)["speedup"] for n in ns], marker="o", color=c, label=size_label(r, size))
    big = r.sizes[-1]
    s = r.fit_serial_fraction(big)
    if s is not None:
        xs = [n for n in range(1, nmax + 1)]
        ax.plot(xs, [r.amdahl(s, n) for n in xs], color="black", linestyle="-.", linewidth=1,
                label=f"Amdahl fit, largest size (s={s:.3f})")
    ax.axhline(1.0, color="red", linewidth=0.6)
    ax.axvline(r.cores, color="grey", linestyle=":", linewidth=1)
    ax.set_xscale("log", base=2); ax.set_xticks(r.threads); ax.set_xticklabels([str(n) for n in r.threads])
    ax.set_xlabel("Number of threads N"); ax.set_ylabel("Speedup = T_seq / T_par")
    ax.set_title("Speedup vs number of threads", fontsize=9)
    ax.grid(True, which="both", alpha=0.3); ax.legend(fontsize=6)
    fig.tight_layout(); fig.savefig(out / "speedup_vs_threads.png"); plt.close(fig)


def fig_strategies(r, out):
    if r.strategy_size is None:
        return
    size = r.strategy_size
    fig, ax = plt.subplots(figsize=(5.2, 3.2), dpi=170)
    names = {LOCAL: "Local accumulators + merge (default)", "SHARED_CONCURRENT": "Shared ConcurrentHashMap / atomics",
             "GLOBAL_LOCK": "Single global lock"}
    for i, st in enumerate(names):
        ns = [n for n in r.threads if r.par(size, n, st)]
        ax.plot(ns, [r.par(size, n, st)["speedup"] for n in ns], marker="o", color=COLORS[i], label=names[st])
    ax.axhline(1.0, color="red", linewidth=0.6)
    ax.set_xscale("log", base=2); ax.set_xticks(r.threads); ax.set_xticklabels([str(n) for n in r.threads])
    ax.set_xlabel("Number of threads N"); ax.set_ylabel("Speedup")
    ax.set_title(f"Synchronization strategies ({size_label(r, size)})", fontsize=9)
    ax.grid(True, which="both", alpha=0.3); ax.legend(fontsize=6)
    fig.tight_layout(); fig.savefig(out / "strategy_comparison.png"); plt.close(fig)


def fig_phases(r, out):
    size = r.sizes[-1]
    ns = [n for n in r.threads if r.phase(size, n)]
    if not ns:
        return
    fig, ax = plt.subplots(figsize=(5.2, 3.2), dpi=170)
    plan = [r.phase(size, n)["plan_ms"] for n in ns]
    scan = [r.phase(size, n)["scan_ms"] for n in ns]
    merge = [r.phase(size, n)["merge_ms"] for n in ns]
    x = range(len(ns))
    ax.bar(x, plan, label="plan + thread start-up (sequential)", color="#ff7f0e")
    ax.bar(x, scan, bottom=plan, label="parallel scan", color="#1f77b4")
    ax.bar(x, merge, bottom=[a + b for a, b in zip(plan, scan)], label="merge (sequential)", color="#d62728")
    ax.set_xticks(list(x)); ax.set_xticklabels([str(n) for n in ns])
    ax.set_xlabel("Number of threads N"); ax.set_ylabel("Median time (ms)")
    ax.set_title(f"Where the time goes ({size_label(r, size)})", fontsize=9)
    ax.legend(fontsize=6); ax.grid(True, axis="y", alpha=0.3)
    fig.tight_layout(); fig.savefig(out / "phase_breakdown.png"); plt.close(fig)


def tables_md(r):
    L = []
    L.append("### Table 1 - Sequential vs multi-threaded (default strategy: local accumulators + merge)\n")
    L.append("| Data size | Threads N | Sequential time (ms) | Parallel time (ms) | Speedup | Efficiency | Std dev (ms) |")
    L.append("|---|---|---|---|---|---|---|")
    for size in r.sizes:
        sq = r.seq(size)
        for n in r.threads:
            p = r.par(size, n)
            if p:
                L.append(f"| {size:,} lines ({r.mb(size):.0f} MiB) | {n} | {sq['median_ms']:.1f} | {p['median_ms']:.1f} | "
                         f"{p['speedup']:.2f} | {100 * p['efficiency']:.0f}% | {p['stdev_ms']:.1f} |")
    if r.strategy_size is not None:
        size = r.strategy_size
        L.append(f"\n### Table 2 - Synchronization strategies, {size:,} lines (speedup vs sequential)\n")
        L.append("| Threads N | Local + merge | Shared concurrent | Single global lock |")
        L.append("|---|---|---|---|")
        for n in r.threads:
            cells = []
            for st in (LOCAL, "SHARED_CONCURRENT", "GLOBAL_LOCK"):
                p = r.par(size, n, st)
                cells.append(f"{p['median_ms']:.1f} ms ({p['speedup']:.2f}x)" if p else "-")
            L.append(f"| {n} | " + " | ".join(cells) + " |")
    big = r.sizes[-1]
    L.append(f"\n### Table 3 - Phases of the parallel run and Amdahl/Karp-Flatt, {big:,} lines\n")
    L.append("| N | plan (ms) | scan (ms) | merge (ms) | sequential share | busiest/avg thread | measured S | Karp-Flatt e |")
    L.append("|---|---|---|---|---|---|---|---|")
    for n in r.threads:
        ph, p = r.phase(big, n), r.par(big, n)
        if ph and p:
            kf = r.karp_flatt(p["speedup"], n)
            L.append(f"| {n} | {ph['plan_ms']:.1f} | {ph['scan_ms']:.1f} | {ph['merge_ms']:.1f} | "
                     f"{100 * (ph['plan_ms'] + ph['merge_ms']) / ph['total_ms']:.1f}% | {ph['busy_max_over_mean']:.2f} | "
                     f"{p['speedup']:.2f} | {'-' if kf is None else f'{kf:.3f}'} |")
    return "\n".join(L) + "\n"


def main():
    res = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("results")
    r = Results(res)
    fig_time(r, res); fig_speedup(r, res); fig_strategies(r, res); fig_phases(r, res)
    (res / "results_tables.md").write_text(tables_md(r), encoding="utf-8")
    print(f"figures + results_tables.md written to {res.resolve()}  (logical processors: {r.cores})")


if __name__ == "__main__":
    main()
