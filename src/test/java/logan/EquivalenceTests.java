package logan;

import logan.core.AnalysisResult;
import logan.core.LogStats;
import logan.core.ParallelAnalyzer;
import logan.core.ParallelConfig;
import logan.core.SequentialAnalyzer;
import logan.core.Strategy;

import java.nio.file.Path;

/** Core correctness proof: sequential result == parallel result, for many sizes / thread counts / chunk sizes / strategies. */
final class EquivalenceTests {
    private EquivalenceTests() { }

    private static final int[] THREADS = {1, 2, 3, 4, 8, 16};

    static void register(TestRunner r) {
        long[] sizes = {1_000, 20_000, 200_000};
        String[] labels = {"small (1k lines)", "medium (20k lines)", "large (200k lines)"};
        long[][] chunkSizes = {{777, 10_000, ParallelConfig.DEFAULT_CHUNK_BYTES},
                               {4_096, 100_000, ParallelConfig.DEFAULT_CHUNK_BYTES},
                               {65_536, 1_000_000, ParallelConfig.DEFAULT_CHUNK_BYTES}};
        for (int i = 0; i < sizes.length; i++) {
            final long lines = sizes[i];
            final long[] chunks = chunkSizes[i];
            r.add("equivalence LOCAL_MERGE: " + labels[i] + " x N{1,2,3,4,8,16} x 3 chunk sizes", () -> {
                Path f = TestData.generated(lines, 1234);
                LogStats expected = new SequentialAnalyzer().analyze(f, null).stats();
                Check.equal(lines, expected.totalLines(), "line count of sequential result");
                for (long chunk : chunks) {
                    for (int n : THREADS) {
                        AnalysisResult res = new ParallelAnalyzer(new ParallelConfig(n, chunk, Strategy.LOCAL_MERGE)).analyze(f, null);
                        Check.isTrue(expected.equals(res.stats()), "N=" + n + " chunk=" + chunk + " -> " + expected.diff(res.stats()));
                        long linesByWorkers = res.workers().stream().mapToLong(AnalysisResult.WorkerReport::lines).sum();
                        Check.equal(lines, linesByWorkers, "sum of per-worker lines (N=" + n + ")");
                    }
                }
            });
        }
        r.add("equivalence: all three strategies agree with sequential (50k lines, N{1,4,16})", () -> {
            Path f = TestData.generated(50_000, 99);
            LogStats expected = new SequentialAnalyzer().analyze(f, null).stats();
            for (Strategy s : Strategy.values()) {
                for (int n : new int[] {1, 4, 16}) {
                    LogStats got = new ParallelAnalyzer(new ParallelConfig(n, 8_192, s)).analyze(f, null).stats();
                    Check.isTrue(expected.equals(got), s + " N=" + n + " -> " + expected.diff(got));
                }
            }
        });
        r.add("equivalence: top-K rankings are identical (deterministic tie-breaking)", () -> {
            Path f = TestData.generated(50_000, 5);
            LogStats a = new SequentialAnalyzer().analyze(f, null).stats();
            LogStats b = new ParallelAnalyzer(new ParallelConfig(8, 4_096, Strategy.LOCAL_MERGE)).analyze(f, null).stats();
            Check.equal(a.topPaths(20), b.topPaths(20), "top paths");
            Check.equal(a.topErrorIps(20), b.topErrorIps(20), "top error IPs");
        });
        r.add("stress: 40 repeated parallel runs (N=8, tiny chunks) always give the same result (race detector)", () -> {
            Path f = TestData.generated(20_000, 1234);
            LogStats expected = new SequentialAnalyzer().analyze(f, null).stats();
            for (int run = 0; run < 40; run++) {
                for (Strategy s : Strategy.values()) {
                    LogStats got = new ParallelAnalyzer(new ParallelConfig(8, 2_000, s)).analyze(f, null).stats();
                    Check.isTrue(expected.equals(got), "run " + run + " " + s + " -> " + expected.diff(got));
                }
            }
        });
        r.add("generator: same seed gives byte-identical files, different seed differs", () -> {
            Path a = TestData.generated(5_000, 11), b = TestData.dir().resolve("again.log");
            logan.gen.LogGenerator.generate(b, 5_000, 11, null);
            Check.isTrue(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(a), java.nio.file.Files.readAllBytes(b)), "same seed");
            Check.isTrue(!java.util.Arrays.equals(java.nio.file.Files.readAllBytes(a),
                    java.nio.file.Files.readAllBytes(TestData.generated(5_000, 12))), "different seed");
        });
    }
}
