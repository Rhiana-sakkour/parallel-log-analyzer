package logan.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Baseline: the whole file is scanned by the calling thread, front to back, into one LogStats.
 * It uses exactly the same scanner / parser / accumulator code as the parallel version.
 */
public final class SequentialAnalyzer implements Analyzer {
    @Override public String name() { return "Sequential (1 thread)"; }

    @Override
    public AnalysisResult analyze(Path file, ProgressTracker tracker) throws IOException {
        long t0 = System.nanoTime();
        long size = Files.size(file);
        if (tracker != null) tracker.setTotalBytes(size);

        LogStats stats = new LogStats();
        AccumulatingHandler handler = new AccumulatingHandler(stats);
        try (LineRangeScanner scanner = new LineRangeScanner(file, tracker)) {
            scanner.scan(0, size, handler);
        }
        long total = System.nanoTime() - t0;
        return new AnalysisResult("sequential", 1, stats,
                new AnalysisResult.Timing(total, 0, total, 0),
                List.of(new AnalysisResult.WorkerReport(0, 1, handler.lines(), total)));
    }
}
