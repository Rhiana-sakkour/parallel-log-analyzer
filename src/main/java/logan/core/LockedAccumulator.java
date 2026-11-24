package logan.core;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Strategy C - the naive design: ONE shared LogStats protected by ONE coarse ReentrantLock that every
 * thread takes for every line. Perfectly correct (mutual exclusion => no race condition, and a single
 * lock => no deadlock), but the critical section is a large part of the per-line work, so by Amdahl's
 * law the threads spend most of their time waiting. Kept as the experimental baseline that shows WHY
 * unnecessary locking "kills" parallelism.
 */
public final class LockedAccumulator implements Accumulator {
    private final ReentrantLock lock = new ReentrantLock();
    private final LogStats stats = new LogStats();

    @Override
    public void add(LogEntry e) {
        lock.lock();
        try { stats.add(e); } finally { lock.unlock(); }
    }

    @Override
    public void addMalformed() {
        lock.lock();
        try { stats.addMalformed(); } finally { lock.unlock(); }
    }

    /** Call only after all writers have finished. */
    public LogStats snapshot() {
        lock.lock();
        try { return stats; } finally { lock.unlock(); }
    }
}
