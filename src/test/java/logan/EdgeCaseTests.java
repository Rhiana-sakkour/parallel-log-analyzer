package logan;

import logan.core.AnalysisResult;
import logan.core.LogStats;
import logan.core.ParallelAnalyzer;
import logan.core.ParallelConfig;
import logan.core.ProgressTracker;
import logan.core.SequentialAnalyzer;
import logan.core.Strategy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

final class EdgeCaseTests {
    private EdgeCaseTests() { }

    private static void bothAgree(Path f, long expectedLines, long expectedMalformed, String what) throws Exception {
        LogStats seq = new SequentialAnalyzer().analyze(f, null).stats();
        Check.equal(expectedLines, seq.totalLines(), what + ": sequential total lines");
        Check.equal(expectedMalformed, seq.malformedLines(), what + ": sequential malformed lines");
        for (int n : new int[] {1, 4, 16}) {
            LogStats par = new ParallelAnalyzer(new ParallelConfig(n, 16, Strategy.LOCAL_MERGE)).analyze(f, null).stats();
            Check.isTrue(seq.equals(par), what + " N=" + n + " -> " + seq.diff(par));
        }
    }

    static void register(TestRunner r) {
        r.add("edge: empty file (0 bytes)", () -> bothAgree(TestData.text("e0.log", ""), 0, 0, "empty"));
        r.add("edge: only blank lines", () -> bothAgree(TestData.text("e1.log", "\n\n\r\n\n"), 0, 0, "blank lines"));
        r.add("edge: a single valid line without trailing newline", () -> bothAgree(TestData.text("e2.log", TestData.GOOD_LINE), 1, 0, "one line"));
        r.add("edge: all lines malformed", () -> bothAgree(TestData.text("e3.log", "x\ny\nz\n"), 3, 3, "all malformed"));
        r.add("edge: CRLF line endings are handled like LF", () -> {
            String crlf = (TestData.GOOD_LINE + "\r\n").repeat(50);
            bothAgree(TestData.text("e4.log", crlf), 50, 0, "crlf");
            LogStats a = new SequentialAnalyzer().analyze(TestData.text("e4b.log", crlf), null).stats();
            LogStats b = new SequentialAnalyzer().analyze(TestData.text("e4c.log", crlf.replace("\r", "")), null).stats();
            Check.isTrue(a.equals(b), "CRLF vs LF");
        });
        r.add("edge: more threads than chunks (16 threads, 1 chunk) - idle workers are harmless", () -> {
            Path f = TestData.text("e5.log", TestData.GOOD_LINE + "\n");
            AnalysisResult res = new ParallelAnalyzer(new ParallelConfig(16, 1_000_000, Strategy.LOCAL_MERGE)).analyze(f, null);
            Check.equal(1L, res.stats().totalLines(), "lines");
            Check.equal(16, res.workers().size(), "16 worker reports");
            Check.equal(1, res.workers().stream().mapToInt(w -> w.chunks()).sum(), "exactly one chunk processed in total");
        });
        r.add("edge: statistics values are correct on a hand-made file", () -> {
            String c = "1.1.1.1 - - [01/Jan/2026:01:00:00 +0000] \"GET /a?x=1 HTTP/1.1\" 200 100 10\n"
                     + "1.1.1.1 - - [01/Jan/2026:01:30:00 +0000] \"GET /a HTTP/1.1\" 500 50 30\n"
                     + "2.2.2.2 - - [01/Jan/2026:23:00:00 +0000] \"POST /b HTTP/1.1\" 404 - 20\n"
                     + "broken line\n";
            LogStats s = new ParallelAnalyzer(ParallelConfig.of(3)).analyze(TestData.text("e6.log", c), null).stats();
            Check.equal(4L, s.totalLines(), "total"); Check.equal(3L, s.parsedLines(), "parsed"); Check.equal(1L, s.malformedLines(), "malformed");
            Check.equal(150L, s.totalBytes(), "bytes"); Check.equal(30L, s.maxLatencyMs(), "max latency");
            Check.equal(1L, s.statusCount(200), "200"); Check.equal(1L, s.statusCount(500), "500"); Check.equal(1L, s.statusCount(404), "404");
            Check.equal(1L, s.serverErrors(), "5xx");
            Check.equal(2L, s.hourCounts()[1], "hour 1"); Check.equal(1L, s.hourCounts()[23], "hour 23");
            Check.equal(2L, s.topPaths(1).get(0).count(), "top path count"); Check.equal("/a", s.topPaths(1).get(0).key(), "top path");
            Check.equal("1.1.1.1", s.topErrorIps(1).get(0).key(), "error ip");
        });
        r.add("invalid input: missing file -> IOException (both analyzers)", () -> {
            Path missing = TestData.dir().resolve("does-not-exist.log");
            Check.throwsA(IOException.class, () -> new SequentialAnalyzer().analyze(missing, null), "sequential");
            Check.throwsA(IOException.class, () -> new ParallelAnalyzer(ParallelConfig.of(4)).analyze(missing, null), "parallel");
        });
        r.add("invalid input: bad configuration is rejected", () -> {
            Check.throwsA(IllegalArgumentException.class, () -> new ParallelConfig(0, 1024, Strategy.LOCAL_MERGE), "threads=0");
            Check.throwsA(IllegalArgumentException.class, () -> new ParallelConfig(-3, 1024, Strategy.LOCAL_MERGE), "threads<0");
            Check.throwsA(IllegalArgumentException.class, () -> new ParallelConfig(ParallelConfig.MAX_THREADS + 1, 1024, Strategy.LOCAL_MERGE), "threads too large");
            Check.throwsA(IllegalArgumentException.class, () -> new ParallelConfig(2, 0, Strategy.LOCAL_MERGE), "chunk=0");
            Check.throwsA(IllegalArgumentException.class, () -> new ParallelConfig(2, 1024, null), "null strategy");
        });
        r.add("cancellation: a cancelled tracker aborts both analyzers with CancellationException", () -> {
            Path f = TestData.generated(20_000, 1234);
            ProgressTracker t1 = new ProgressTracker(); t1.cancel();
            Check.throwsA(CancellationException.class, () -> new SequentialAnalyzer().analyze(f, t1), "sequential");
            ProgressTracker t2 = new ProgressTracker(); t2.cancel();
            Check.throwsA(CancellationException.class, () -> new ParallelAnalyzer(ParallelConfig.of(4)).analyze(f, t2), "parallel");
        });
        r.add("progress: tracker reaches 100% after a normal run", () -> {
            Path f = TestData.generated(20_000, 1234);
            ProgressTracker t = new ProgressTracker();
            new ParallelAnalyzer(new ParallelConfig(4, 10_000, Strategy.LOCAL_MERGE)).analyze(f, t);
            Check.equal(1.0, t.fraction(), "fraction");
            ProgressTracker s = new ProgressTracker();
            new SequentialAnalyzer().analyze(f, s);
            Check.equal(1.0, s.fraction(), "sequential fraction");
        });
    }
}
