"""Shared loading + derived metrics for the plots and the report. Reads ONLY real benchmark output (results/*.csv).

Definitions
  Speedup      S(N) = median(T_sequential) / median(T_parallel(N))
  Efficiency   E(N) = S(N) / N
  Amdahl       S(N) = 1 / (s + (1 - s) / N)      s = serial fraction
  Karp-Flatt   e(N) = (1/S - 1/N) / (1 - 1/N)    experimentally determined serial fraction (N >= 2)
"""
import csv
from pathlib import Path

LOCAL = "LOCAL_MERGE"


def _num(v):
    try:
        return int(v)
    except ValueError:
        try:
            return float(v)
        except ValueError:
            return v


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return [{k: _num(v) for k, v in row.items()} for row in csv.DictReader(f)]


class Results:
    def __init__(self, res_dir="results"):
        self.dir = Path(res_dir)
        self.env = {}
        for line in (self.dir / "environment.txt").read_text(encoding="utf-8").splitlines():
            k, _, v = line.partition("=")
            self.env[k.strip()] = v.strip()
        self.cores = int(self.env.get("logical_processors", "1"))
        self.rows = read_csv(self.dir / "benchmark_results.csv")
        self.phases = read_csv(self.dir / "phase_breakdown.csv")
        self.sizes = sorted({r["lines"] for r in self.rows})
        self.threads = sorted({r["threads"] for r in self.rows if r["mode"] == "parallel"})
        self.strategy_size = next((r["lines"] for r in self.rows if r["strategy"] not in (LOCAL, "-")), None)

    # ---- accessors
    def seq(self, size):
        return next(r for r in self.rows if r["lines"] == size and r["mode"] == "sequential")

    def par(self, size, n, strategy=LOCAL):
        return next((r for r in self.rows if r["lines"] == size and r["mode"] == "parallel"
                     and r["threads"] == n and r["strategy"] == strategy), None)

    def phase(self, size, n):
        return next((p for p in self.phases if p["lines"] == size and p["threads"] == n), None)

    def mb(self, size):
        return self.seq(size)["file_mb"]

    def best(self):
        cands = [r for r in self.rows if r["mode"] == "parallel" and r["strategy"] == LOCAL]
        return max(cands, key=lambda r: r["speedup"])

    # ---- Amdahl
    @staticmethod
    def amdahl(s, n):
        return 1.0 / (s + (1.0 - s) / n)

    @staticmethod
    def karp_flatt(speedup, n):
        if n < 2 or speedup <= 0:
            return None
        return (1.0 / speedup - 1.0 / n) / (1.0 - 1.0 / n)

    def fit_serial_fraction(self, size):
        """Least-squares fit of s to the measured speedups for 2 <= N <= logical processors.
        Returns None when fewer than two usable points exist (e.g. on a 1-core machine)."""
        pts = [(n, self.par(size, n)["speedup"]) for n in self.threads if 2 <= n <= self.cores and self.par(size, n)]
        if len(pts) < 2:
            return None
        best_s, best_err = None, float("inf")
        for i in range(0, 10001):
            s = i / 10000.0
            err = sum((self.amdahl(s, n) - sp) ** 2 for n, sp in pts)
            if err < best_err:
                best_s, best_err = s, err
        return best_s

    def measured_serial_share(self, size, n):
        """Share of wall time spent in the unavoidably sequential phases (plan + merge) of the parallel run."""
        p = self.phase(size, n)
        if not p or p["total_ms"] <= 0:
            return None
        return (p["plan_ms"] + p["merge_ms"]) / p["total_ms"]
