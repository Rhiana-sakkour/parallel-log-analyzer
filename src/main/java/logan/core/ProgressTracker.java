package logan.core;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared progress + cancellation channel between worker threads and the UI thread.
 *
 *  - bytesDone is an AtomicLong: many workers add to it concurrently (lock-free; one add per buffer
 *    refill, i.e. once per few hundred KiB of work, so contention is negligible) while the UI polls it.
 *  - cancelled is an AtomicBoolean: written by the UI thread, read by workers (visibility guaranteed).
 */
public final class ProgressTracker {
    private final AtomicLong bytesDone = new AtomicLong();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile long totalBytes;

    public void setTotalBytes(long total) { this.totalBytes = total; }
    public long totalBytes()              { return totalBytes; }
    public long bytesDone()               { return bytesDone.get(); }
    void addBytes(long n)                 { if (n > 0) bytesDone.addAndGet(n); }

    public double fraction() {
        long t = totalBytes;
        return t <= 0 ? 0.0 : Math.min(1.0, (double) bytesDone.get() / t);
    }

    public void cancel()         { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }

    void throwIfCancelled() {
        if (cancelled.get()) throw new CancellationException("analysis cancelled");
    }
}
