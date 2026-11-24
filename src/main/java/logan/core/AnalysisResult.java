package logan.core;

import java.util.List;

/** Outcome of one analysis run: the statistics, wall-clock phase timings and a per-thread report. */
public record AnalysisResult(String mode, int threads, LogStats stats, Timing timing, List<WorkerReport> workers) {

    /**
     * Wall-clock phases in nanoseconds.
     * plan  = size lookup, chunk planning, thread-pool creation   (sequential part, thread start-up overhead)
     * scan  = all worker threads running in parallel until the last one finishes
     * merge = combining the partial results on the calling thread (sequential part)
     * total = whole analyze() call (>= plan + scan + merge).
     */
    public record Timing(long totalNanos, long planNanos, long scanNanos, long mergeNanos) {
        public double totalMs() { return totalNanos / 1e6; }
        public double planMs()  { return planNanos / 1e6; }
        public double scanMs()  { return scanNanos / 1e6; }
        public double mergeMs() { return mergeNanos / 1e6; }
    }

    /** What one worker thread did: how many chunks/lines it processed and how long it was busy. */
    public record WorkerReport(int workerId, int chunks, long lines, long busyNanos) {
        public double busyMs() { return busyNanos / 1e6; }
    }
}
