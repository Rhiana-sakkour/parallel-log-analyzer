package logan.core;

import java.io.IOException;
import java.nio.file.Path;

/** A way of analysing a log file. Implementations: {@link SequentialAnalyzer}, {@link ParallelAnalyzer}. */
public interface Analyzer {
    String name();

    /**
     * @param tracker optional (may be null): receives progress, and cancellation is honoured.
     * @throws java.util.concurrent.CancellationException if the tracker was cancelled
     */
    AnalysisResult analyze(Path file, ProgressTracker tracker) throws IOException, InterruptedException;
}
