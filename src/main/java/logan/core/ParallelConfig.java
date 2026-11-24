package logan.core;

/**
 * Tunable parameters of the parallel analyzer.
 * @param threads    number of worker threads N (>= 1)
 * @param chunkBytes size of one unit of work; smaller chunks balance load better, larger chunks have less overhead
 * @param strategy   how partial results are combined
 */
public record ParallelConfig(int threads, long chunkBytes, Strategy strategy) {
    public static final long DEFAULT_CHUNK_BYTES = 1024 * 1024;     // 1 MiB
    public static final int MAX_THREADS = 256;

    public ParallelConfig {
        if (threads < 1 || threads > MAX_THREADS) throw new IllegalArgumentException("threads must be in 1.." + MAX_THREADS + ", got " + threads);
        if (chunkBytes < 1) throw new IllegalArgumentException("chunkBytes must be >= 1, got " + chunkBytes);
        if (strategy == null) throw new IllegalArgumentException("strategy must not be null");
    }

    public static ParallelConfig of(int threads) { return new ParallelConfig(threads, DEFAULT_CHUNK_BYTES, Strategy.LOCAL_MERGE); }
}
