package logan.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The analysis result, and also the per-thread accumulator of the lock-free strategy.
 *
 * All counters are 64-bit integers, so merging is exact, associative and commutative: the result is
 * bit-for-bit identical no matter how lines are distributed over threads or in which order the
 * partial results are merged (no floating-point rounding differences). That is what makes the
 * "sequential == parallel" correctness check a strict equality test.
 *
 * NOT thread-safe by itself (that is intentional - see the strategies in {@link Strategy}).
 */
public final class LogStats implements Accumulator {
    public static final int STATUS_SLOTS = 600;

    private long totalLines, parsedLines, malformedLines, totalBytes, totalLatencyMs, maxLatencyMs;
    private final long[] statusCounts = new long[STATUS_SLOTS];
    private final long[] hourCounts = new long[24];
    private final Map<String, long[]> pathCounts = new HashMap<>();
    private final Map<String, long[]> errorIpCounts = new HashMap<>();   // clients that received a 5xx

    public record Ranked(String key, long count) { }

    // ---------------------------------------------------------------- Accumulator

    @Override
    public void add(LogEntry e) {
        totalLines++;
        parsedLines++;
        totalBytes += e.bytes;
        totalLatencyMs += e.latencyMs;
        if (e.latencyMs > maxLatencyMs) maxLatencyMs = e.latencyMs;
        statusCounts[e.status]++;
        hourCounts[e.hour]++;
        bump(pathCounts, e.path());
        if (e.status >= 500) bump(errorIpCounts, e.ip());
    }

    @Override
    public void addMalformed() {
        totalLines++;
        malformedLines++;
    }

    private static void bump(Map<String, long[]> m, String key) {
        long[] c = m.get(key);
        if (c == null) m.put(key, new long[] {1}); else c[0]++;
    }

    // ---------------------------------------------------------------- reduction

    /** Adds all counters of {@code o} into this instance (the "reduce" step). */
    public void merge(LogStats o) {
        totalLines += o.totalLines;
        parsedLines += o.parsedLines;
        malformedLines += o.malformedLines;
        totalBytes += o.totalBytes;
        totalLatencyMs += o.totalLatencyMs;
        if (o.maxLatencyMs > maxLatencyMs) maxLatencyMs = o.maxLatencyMs;
        for (int i = 0; i < statusCounts.length; i++) statusCounts[i] += o.statusCounts[i];
        for (int i = 0; i < hourCounts.length; i++) hourCounts[i] += o.hourCounts[i];
        mergeMap(pathCounts, o.pathCounts);
        mergeMap(errorIpCounts, o.errorIpCounts);
    }

    private static void mergeMap(Map<String, long[]> into, Map<String, long[]> from) {
        for (Map.Entry<String, long[]> en : from.entrySet()) {
            long[] c = into.get(en.getKey());
            if (c == null) into.put(en.getKey(), new long[] {en.getValue()[0]}); else c[0] += en.getValue()[0];
        }
    }

    /** Used by the shared (concurrent) accumulators to publish their final counters as a LogStats. */
    static LogStats fromCounters(long total, long parsed, long malformed, long bytes, long latency, long maxLatency,
                                 long[] status, long[] hours, Map<String, Long> paths, Map<String, Long> errIps) {
        LogStats s = new LogStats();
        s.totalLines = total; s.parsedLines = parsed; s.malformedLines = malformed;
        s.totalBytes = bytes; s.totalLatencyMs = latency; s.maxLatencyMs = maxLatency;
        System.arraycopy(status, 0, s.statusCounts, 0, STATUS_SLOTS);
        System.arraycopy(hours, 0, s.hourCounts, 0, 24);
        paths.forEach((k, v) -> s.pathCounts.put(k, new long[] {v}));
        errIps.forEach((k, v) -> s.errorIpCounts.put(k, new long[] {v}));
        return s;
    }

    // ---------------------------------------------------------------- queries

    public long totalLines()      { return totalLines; }
    public long parsedLines()     { return parsedLines; }
    public long malformedLines()  { return malformedLines; }
    public long totalBytes()      { return totalBytes; }
    public long maxLatencyMs()    { return maxLatencyMs; }
    public double avgLatencyMs()  { return parsedLines == 0 ? 0 : (double) totalLatencyMs / parsedLines; }
    public long statusCount(int code) { return code >= 0 && code < STATUS_SLOTS ? statusCounts[code] : 0; }
    public long[] hourCounts()    { return hourCounts.clone(); }
    public int distinctPaths()    { return pathCounts.size(); }
    public int distinctErrorIps() { return errorIpCounts.size(); }

    /** Errors = 5xx responses. */
    public long serverErrors() {
        long n = 0;
        for (int c = 500; c < STATUS_SLOTS; c++) n += statusCounts[c];
        return n;
    }

    /** Non-zero status codes in ascending order. */
    public List<Ranked> statusTable() {
        List<Ranked> out = new ArrayList<>();
        for (int c = 0; c < STATUS_SLOTS; c++) if (statusCounts[c] > 0) out.add(new Ranked(Integer.toString(c), statusCounts[c]));
        return out;
    }

    public List<Ranked> topPaths(int k)    { return top(pathCounts, k); }
    public List<Ranked> topErrorIps(int k) { return top(errorIpCounts, k); }

    /** Deterministic ranking: count descending, then key ascending (so ties never depend on thread timing). */
    private static List<Ranked> top(Map<String, long[]> m, int k) {
        List<Ranked> all = new ArrayList<>(m.size());
        for (Map.Entry<String, long[]> e : m.entrySet()) all.add(new Ranked(e.getKey(), e.getValue()[0]));
        all.sort((a, b) -> a.count() != b.count() ? Long.compare(b.count(), a.count()) : a.key().compareTo(b.key()));
        return all.size() > k ? new ArrayList<>(all.subList(0, k)) : all;
    }

    // ---------------------------------------------------------------- equality (used by tests + benchmark)

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogStats s)) return false;
        return totalLines == s.totalLines && parsedLines == s.parsedLines && malformedLines == s.malformedLines
            && totalBytes == s.totalBytes && totalLatencyMs == s.totalLatencyMs && maxLatencyMs == s.maxLatencyMs
            && java.util.Arrays.equals(statusCounts, s.statusCounts) && java.util.Arrays.equals(hourCounts, s.hourCounts)
            && sameCounts(pathCounts, s.pathCounts) && sameCounts(errorIpCounts, s.errorIpCounts);
    }

    private static boolean sameCounts(Map<String, long[]> a, Map<String, long[]> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, long[]> e : a.entrySet()) {
            long[] o = b.get(e.getKey());
            if (o == null || o[0] != e.getValue()[0]) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(totalLines) * 31 + Long.hashCode(totalBytes) * 17 + java.util.Arrays.hashCode(statusCounts);
    }

    /** Human readable explanation of the first difference (empty string if equal). Handy in test failures. */
    public String diff(LogStats o) {
        if (totalLines != o.totalLines) return "totalLines " + totalLines + " vs " + o.totalLines;
        if (parsedLines != o.parsedLines) return "parsedLines " + parsedLines + " vs " + o.parsedLines;
        if (malformedLines != o.malformedLines) return "malformedLines " + malformedLines + " vs " + o.malformedLines;
        if (totalBytes != o.totalBytes) return "totalBytes " + totalBytes + " vs " + o.totalBytes;
        if (totalLatencyMs != o.totalLatencyMs) return "totalLatencyMs " + totalLatencyMs + " vs " + o.totalLatencyMs;
        if (!java.util.Arrays.equals(statusCounts, o.statusCounts)) return "statusCounts differ";
        if (!java.util.Arrays.equals(hourCounts, o.hourCounts)) return "hourCounts differ";
        if (!sameCounts(pathCounts, o.pathCounts)) return "pathCounts differ";
        if (!sameCounts(errorIpCounts, o.errorIpCounts)) return "errorIpCounts differ";
        return equals(o) ? "" : "maxLatency differs";
    }

    public String summary(int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Lines: %,d (parsed %,d, malformed %,d)%n", totalLines, parsedLines, malformedLines));
        sb.append(String.format("Traffic: %,d bytes | avg latency %.2f ms | max latency %,d ms%n", totalBytes, avgLatencyMs(), maxLatencyMs));
        sb.append(String.format("Server errors (5xx): %,d (%.2f%%)%n", serverErrors(), parsedLines == 0 ? 0.0 : 100.0 * serverErrors() / parsedLines));
        sb.append("Top ").append(topK).append(" paths:\n");
        for (Ranked r : topPaths(topK)) sb.append(String.format("  %,10d  %s%n", r.count(), r.key()));
        return sb.toString();
    }
}
