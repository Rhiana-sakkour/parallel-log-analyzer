package logan.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-threaded version (data parallelism over file chunks).
 *
 * <pre>
 *  caller thread                       worker-0 .. worker-(N-1)  (fixed thread pool)
 *  -------------                       -----------------------------------------------
 *  1. plan: size, chunk count, pool
 *  2. submit N identical workers  -->  loop { c = nextChunk.getAndIncrement(); if c >= chunks break;
 *                                              scan bytes [c*S, (c+1)*S) into a PRIVATE LogStats }
 *  3. take() each Future (join)   <--  return private LogStats + worker report
 *  4. merge partial LogStats (single thread, after all workers are done)
 * </pre>
 *
 * Synchronization inventory (every primitive has exactly one job):
 *  - AtomicInteger nextChunk : lock-free self-scheduling; getAndIncrement hands every chunk to exactly one
 *                              worker and gives dynamic load balancing (fast threads simply take more chunks).
 *  - Thread pool + Future    : thread creation, and the join. Future.get() establishes happens-before between a
 *                              worker's writes to its private LogStats and the merge, so no lock is needed there.
 *  - ProgressTracker         : AtomicLong / AtomicBoolean for progress + cancellation (UI thread <-> workers).
 *  - (strategies B / C only) : shared accumulator, see {@link ConcurrentAccumulator} / {@link LockedAccumulator}.
 *
 * Race conditions: chunks are disjoint (ownership rule in {@link LineRangeScanner}) and, in the default
 * strategy, all mutable per-line state (scanner buffer, LogEntry, LogStats) is thread-confined.
 * Deadlocks: the default strategy never holds a lock; the baseline strategy holds exactly one lock and never
 * waits for anything else while holding it, so a lock cycle is impossible.
 */
public final class ParallelAnalyzer implements Analyzer {
    private final ParallelConfig config;

    public ParallelAnalyzer(ParallelConfig config) { this.config = config; }

    @Override public String name() { return "Parallel (" + config.threads() + " threads, " + config.strategy().label() + ")"; }

    private record WorkerOutput(AnalysisResult.WorkerReport report, LogStats local) { }

    @Override
    public AnalysisResult analyze(Path file, ProgressTracker tracker) throws IOException, InterruptedException {
        long t0 = System.nanoTime();

        // ---- 1. plan (sequential): decompose the file into chunks, create the pool
        final long size = Files.size(file);
        if (tracker != null) tracker.setTotalBytes(size);
        final long chunk = config.chunkBytes();
        final int chunkCount = Math.toIntExact((size + chunk - 1) / chunk);          // 0 for an empty file
        final AtomicInteger nextChunk = new AtomicInteger();
        final Accumulator shared = switch (config.strategy()) {
            case LOCAL_MERGE -> null;
            case SHARED_CONCURRENT -> new ConcurrentAccumulator();
            case GLOBAL_LOCK -> new LockedAccumulator();
        };
        final int n = config.threads();
        ExecutorService pool = Executors.newFixedThreadPool(n, new NamedThreadFactory("log-worker"));
        CompletionService<WorkerOutput> done = new ExecutorCompletionService<>(pool);
        long t1 = System.nanoTime();

        try {
            // ---- 2. fork: N identical workers pull chunks until none are left
            for (int w = 0; w < n; w++) {
                final int id = w;
                done.submit(() -> runWorker(id, file, size, chunk, chunkCount, nextChunk, shared, tracker));
            }

            // ---- 3. join: collect in completion order, so the first failure/cancellation is noticed at once
            List<WorkerOutput> outputs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) outputs.add(done.take().get());
            long t2 = System.nanoTime();

            // ---- 4. reduce (sequential): safe, all workers are finished
            LogStats result;
            if (shared == null) {
                result = new LogStats();
                for (WorkerOutput o : outputs) result.merge(o.local());
            } else if (shared instanceof ConcurrentAccumulator ca) {
                result = ca.snapshot();
            } else {
                result = ((LockedAccumulator) shared).snapshot();
            }
            long t3 = System.nanoTime();

            outputs.sort(Comparator.comparingInt(o -> o.report().workerId()));
            List<AnalysisResult.WorkerReport> reports = new ArrayList<>(n);
            for (WorkerOutput o : outputs) reports.add(o.report());
            return new AnalysisResult("parallel", n, result,
                    new AnalysisResult.Timing(t3 - t0, t1 - t0, t2 - t1, t3 - t2), reports);
        } catch (ExecutionException ex) {
            Throwable c = ex.getCause();
            if (c instanceof IOException io) throw io;
            if (c instanceof CancellationException ce) throw ce;
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error er) throw er;
            throw new IllegalStateException(c);
        } finally {
            pool.shutdownNow();          // idempotent; stops the remaining workers on failure / cancel / interrupt
        }
    }

    /** Body of one worker thread. Everything it mutates is private to it, except nextChunk (atomic) and `shared`. */
    private WorkerOutput runWorker(int id, Path file, long size, long chunk, int chunkCount,
                                   AtomicInteger nextChunk, Accumulator shared, ProgressTracker tracker) throws IOException {
        long begin = System.nanoTime();
        LogStats local = (shared == null) ? new LogStats() : null;
        AccumulatingHandler handler = new AccumulatingHandler(shared != null ? shared : local);
        int chunks = 0;
        try (LineRangeScanner scanner = new LineRangeScanner(file, tracker)) {
            int c;
            while ((c = nextChunk.getAndIncrement()) < chunkCount) {
                long start = (long) c * chunk;
                scanner.scan(start, Math.min(size, start + chunk), handler);
                chunks++;
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("worker interrupted");
            }
        }
        return new WorkerOutput(new AnalysisResult.WorkerReport(id, chunks, handler.lines(), System.nanoTime() - begin), local);
    }
}
