package logan.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Strategy B - ONE shared structure updated by all threads WITHOUT a global lock.
 *
 *  - scalar totals       : LongAdder (striped counters, cheap under contention)
 *  - max latency         : LongAccumulator(Math::max)  (lock-free CAS loop)
 *  - status / hour arrays: AtomicLongArray (atomic increments; hot cells such as status 200 are contended)
 *  - per-path / per-IP   : ConcurrentHashMap<String, LongAdder>
 *
 * Correct, but every line performs several atomic operations on shared cache lines and hashes into a
 * shared map, so it scales worse than thread-confined accumulation ({@link Strategy#LOCAL_MERGE}).
 * It exists in the project to MEASURE that difference, not as the recommended design.
 */
public final class ConcurrentAccumulator implements Accumulator {
    private final LongAdder total = new LongAdder(), parsed = new LongAdder(), malformed = new LongAdder();
    private final LongAdder bytes = new LongAdder(), latency = new LongAdder();
    private final LongAccumulator maxLatency = new LongAccumulator(Math::max, 0);
    private final AtomicLongArray status = new AtomicLongArray(LogStats.STATUS_SLOTS);
    private final AtomicLongArray hours = new AtomicLongArray(24);
    private final ConcurrentHashMap<String, LongAdder> paths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> errIps = new ConcurrentHashMap<>();

    @Override
    public void add(LogEntry e) {
        total.increment();
        parsed.increment();
        bytes.add(e.bytes);
        latency.add(e.latencyMs);
        maxLatency.accumulate(e.latencyMs);
        status.incrementAndGet(e.status);
        hours.incrementAndGet(e.hour);
        paths.computeIfAbsent(e.path(), k -> new LongAdder()).increment();
        if (e.status >= 500) errIps.computeIfAbsent(e.ip(), k -> new LongAdder()).increment();
    }

    @Override
    public void addMalformed() {
        total.increment();
        malformed.increment();
    }

    /** Call only after all writers have finished (the executor's Future.get() gives the happens-before edge). */
    public LogStats snapshot() {
        long[] st = new long[LogStats.STATUS_SLOTS];
        for (int i = 0; i < st.length; i++) st[i] = status.get(i);
        long[] hr = new long[24];
        for (int i = 0; i < 24; i++) hr[i] = hours.get(i);
        return LogStats.fromCounters(total.sum(), parsed.sum(), malformed.sum(), bytes.sum(), latency.sum(),
                maxLatency.get(), st, hr, toMap(paths), toMap(errIps));
    }

    private static Map<String, Long> toMap(ConcurrentHashMap<String, LongAdder> m) {
        Map<String, Long> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
