package logan.core;

/** How worker threads combine their results. All three give identical output; they differ in cost. */
public enum Strategy {
    /** Thread-confined partial results merged once at the end (recommended; lock-free hot path). */
    LOCAL_MERGE("Local accumulators + final merge"),
    /** Shared ConcurrentHashMap / LongAdder / atomics updated by every thread. */
    SHARED_CONCURRENT("Shared concurrent structures (lock-free)"),
    /** Shared LogStats behind one coarse ReentrantLock (baseline). */
    GLOBAL_LOCK("Single global lock (baseline)");

    private final String label;
    Strategy(String label) { this.label = label; }
    public String label() { return label; }
    @Override public String toString() { return label; }
}
