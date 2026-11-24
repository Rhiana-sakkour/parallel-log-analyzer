package logan.core;

/**
 * Sink for parsed log lines. Three implementations exist and are compared in the benchmark:
 *  - {@link LogStats}             : NOT thread-safe; used thread-confined (one per worker) and merged at the end.
 *  - {@link ConcurrentAccumulator}: shared, lock-free structure (ConcurrentHashMap + LongAdder / atomics).
 *  - {@link LockedAccumulator}    : one coarse ReentrantLock around a shared LogStats.
 */
public interface Accumulator {
    void add(LogEntry entry);
    void addMalformed();
}
