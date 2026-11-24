package logan.bench;

import logan.core.AnalysisResult;
import logan.core.Analyzer;
import logan.core.LogStats;
import logan.core.ParallelAnalyzer;
import logan.core.ParallelConfig;
import logan.core.SequentialAnalyzer;
import logan.core.Strategy;
import logan.gen.LogGenerator;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Measures Sequential vs Multi-threaded execution time for several data sizes and thread counts.
 *
 * Methodology (documented in the report):
 *  - identical, deterministic input per data size (LogGenerator, fixed seed), generated once and re-used;
 *  - WARM-UP runs (not recorded) so the JIT compiler has optimised the hot loops before measuring;
 *  - R recorded runs per configuration; System.gc() before each run; times taken with System.nanoTime();
 *  - speedup = median(T_sequential) / median(T_parallel); median is robust against one-off OS hiccups;
 *    mean, standard deviation, min and max are reported as well;
 *  - EVERY parallel run is checked for equality with the sequential result (a wrong fast result is not a result);
 *  - the file is in the OS page cache after the first pass, so the benchmark measures CPU-bound processing,
 *    not disk speed (stated limitation).
 *
 * Output (CSV, in --out): benchmark_results.csv, benchmark_raw.csv, phase_breakdown.csv, environment.txt
 */
public final class Benchmark {

    private record Options(List<Long> sizes, List<Integer> threads, int warmup, int runs, long chunkBytes,
                           Path dataDir, Path outDir, int strategySizeIndex, boolean strategies) {
        static Options parse(String[] a) {
            List<Long> sizes = List.of(1_000_000L, 4_000_000L, 16_000_000L);
            List<Integer> threads = List.of(1, 2, 4, 8, 16);
            int warmup = 2, runs = 7, strategyIdx = 1;
            long chunk = ParallelConfig.DEFAULT_CHUNK_BYTES;
            Path data = Path.of("data"), out = Path.of("results");
            boolean strategies = true;
            for (int i = 0; i < a.length; i++) {
                switch (a[i]) {
                    case "--sizes" -> sizes = Arrays.stream(a[++i].split(",")).map(String::trim).map(Long::parseLong).toList();
                    case "--threads" -> threads = Arrays.stream(a[++i].split(",")).map(String::trim).map(Integer::parseInt).toList();
                    case "--warmup" -> warmup = Integer.parseInt(a[++i]);
                    case "--runs" -> runs = Integer.parseInt(a[++i]);
                    case "--chunk-kb" -> chunk = Long.parseLong(a[++i]) * 1024;
                    case "--data-dir" -> data = Path.of(a[++i]);
                    case "--out" -> out = Path.of(a[++i]);
                    case "--strategy-size-index" -> strategyIdx = Integer.parseInt(a[++i]);
                    case "--no-strategies" -> strategies = false;
                    default -> throw new IllegalArgumentException("unknown option " + a[i] +
                            "\nusage: Benchmark [--sizes 1000000,4000000] [--threads 1,2,4,8,16] [--warmup 2] [--runs 7] [--chunk-kb 1024]"
                            + " [--data-dir data] [--out results] [--strategy-size-index 1] [--no-strategies]");
                }
            }
            if (runs < 1 || warmup < 0) throw new IllegalArgumentException("runs >= 1 and warmup >= 0 required");
            return new Options(sizes, threads, warmup, runs, chunk, data, out, Math.min(strategyIdx, sizes.size() - 1), strategies);
        }
    }

    /** All recorded run times (ms) of one configuration, plus the result of the last run. */
    private static final class Series {
        final double[] ms; final AnalysisResult last; final List<AnalysisResult> results;
        Series(double[] ms, List<AnalysisResult> results) { this.ms = ms; this.results = results; this.last = results.get(results.size() - 1); }
        double mean()   { return Arrays.stream(ms).average().orElse(0); }
        double stdev()  { if (ms.length < 2) return 0; double m = mean(); return Math.sqrt(Arrays.stream(ms).map(x -> (x - m) * (x - m)).sum() / (ms.length - 1)); }
        double min()    { return Arrays.stream(ms).min().orElse(0); }
        double max()    { return Arrays.stream(ms).max().orElse(0); }
        double median() { return Benchmark.median(ms); }
    }

    static double median(double[] v) {
        double[] s = v.clone(); Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2.0;
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        Files.createDirectories(o.dataDir()); Files.createDirectories(o.outDir());
        writeEnvironment(o);

        try (PrintWriter res = open(o.outDir().resolve("benchmark_results.csv"));
             PrintWriter raw = open(o.outDir().resolve("benchmark_raw.csv"));
             PrintWriter phases = open(o.outDir().resolve("phase_breakdown.csv"))) {
            res.println("lines,file_mb,mode,strategy,threads,runs,mean_ms,median_ms,stdev_ms,min_ms,max_ms,speedup,efficiency");
            raw.println("lines,mode,strategy,threads,run,time_ms");
            phases.println("lines,threads,plan_ms,scan_ms,merge_ms,total_ms,busy_max_over_mean");

            globalWarmup(o);

            for (int si = 0; si < o.sizes().size(); si++) {
                long lines = o.sizes().get(si);
                Path data = ensureDataset(o.dataDir(), lines);
                double mb = Files.size(data) / (1024.0 * 1024.0);
                System.out.printf(Locale.ROOT, "%n=== %,d lines (%.1f MiB) ===%n", lines, mb);

                Series seq = measure(new SequentialAnalyzer(), data, o, null);
                LogStats reference = seq.last.stats();
                report(res, raw, lines, mb, "sequential", "-", 1, seq, seq.median());

                for (int n : o.threads()) {
                    Series p = measure(new ParallelAnalyzer(new ParallelConfig(n, o.chunkBytes(), Strategy.LOCAL_MERGE)), data, o, reference);
                    report(res, raw, lines, mb, "parallel", Strategy.LOCAL_MERGE.name(), n, p, seq.median());
                    writePhases(phases, lines, n, p);
                }
                if (o.strategies() && si == o.strategySizeIndex()) {
                    for (Strategy st : new Strategy[] {Strategy.SHARED_CONCURRENT, Strategy.GLOBAL_LOCK}) {
                        for (int n : o.threads()) {
                            Series p = measure(new ParallelAnalyzer(new ParallelConfig(n, o.chunkBytes(), st)), data, o, reference);
                            report(res, raw, lines, mb, "parallel", st.name(), n, p, seq.median());
                        }
                    }
                }
            }
        }
        System.out.println("\nResults written to " + o.outDir().toAbsolutePath());
    }

    /** Global JIT warm-up on the smallest data set so that the first measured configuration is not penalised. */
    private static void globalWarmup(Options o) throws Exception {
        long smallest = o.sizes().stream().min(Long::compare).orElseThrow();
        Path data = ensureDataset(o.dataDir(), smallest);
        System.out.println("global JIT warm-up ...");
        int maxN = o.threads().stream().max(Integer::compare).orElse(1);
        for (int i = 0; i < 4; i++) {
            new SequentialAnalyzer().analyze(data, null);
            new ParallelAnalyzer(new ParallelConfig(Math.min(4, maxN), o.chunkBytes(), Strategy.LOCAL_MERGE)).analyze(data, null);
        }
    }

    private static Series measure(Analyzer analyzer, Path data, Options o, LogStats mustEqual) throws Exception {
        for (int i = 0; i < o.warmup(); i++) analyzer.analyze(data, null);
        double[] ms = new double[o.runs()];
        List<AnalysisResult> results = new ArrayList<>();
        for (int i = 0; i < o.runs(); i++) {
            System.gc();
            long t0 = System.nanoTime();
            AnalysisResult r = analyzer.analyze(data, null);
            ms[i] = (System.nanoTime() - t0) / 1e6;
            if (mustEqual != null && !mustEqual.equals(r.stats()))      // correctness gate, outside the timed region
                throw new IllegalStateException("RESULT MISMATCH for " + analyzer.name() + ": " + mustEqual.diff(r.stats()));
            results.add(r);
        }
        System.out.printf(Locale.ROOT, "  %-62s median %9.1f ms  (min %.1f, max %.1f)%n", analyzer.name(), median(ms),
                Arrays.stream(ms).min().orElse(0), Arrays.stream(ms).max().orElse(0));
        return new Series(ms, results);
    }

    private static void report(PrintWriter res, PrintWriter raw, long lines, double mb, String mode, String strategy,
                               int threads, Series s, double seqMedian) {
        double speedup = seqMedian / s.median();
        res.printf(Locale.ROOT, "%d,%.2f,%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f,%.4f%n", lines, mb, mode, strategy, threads,
                s.ms.length, s.mean(), s.median(), s.stdev(), s.min(), s.max(), speedup, speedup / threads);
        for (int i = 0; i < s.ms.length; i++) raw.printf(Locale.ROOT, "%d,%s,%s,%d,%d,%.3f%n", lines, mode, strategy, threads, i + 1, s.ms[i]);
        res.flush(); raw.flush();
    }

    private static void writePhases(PrintWriter w, long lines, int threads, Series s) {
        double[] plan = s.results.stream().mapToDouble(r -> r.timing().planMs()).toArray();
        double[] scan = s.results.stream().mapToDouble(r -> r.timing().scanMs()).toArray();
        double[] merge = s.results.stream().mapToDouble(r -> r.timing().mergeMs()).toArray();
        double[] total = s.results.stream().mapToDouble(r -> r.timing().totalMs()).toArray();
        double[] imbalance = s.results.stream().mapToDouble(r -> {
            double max = r.workers().stream().mapToDouble(AnalysisResult.WorkerReport::busyMs).max().orElse(0);
            double mean = r.workers().stream().mapToDouble(AnalysisResult.WorkerReport::busyMs).average().orElse(1);
            return max / mean;
        }).toArray();
        w.printf(Locale.ROOT, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f%n", lines, threads, median(plan), median(scan), median(merge), median(total), median(imbalance));
        w.flush();
    }

    private static Path ensureDataset(Path dir, long lines) throws IOException {
        Path p = dir.resolve("access_" + lines + ".log");
        if (!Files.exists(p) || Files.size(p) == 0) {
            System.out.printf("generating %s ...%n", p);
            LogGenerator.generate(p, lines, LogGenerator.DEFAULT_SEED, null);
        }
        return p;
    }

    private static PrintWriter open(Path p) throws IOException { return new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8)); }

    private static void writeEnvironment(Options o) throws IOException {
        List<String> l = new ArrayList<>();
        l.add("java_version=" + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        l.add("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        l.add("logical_processors=" + Runtime.getRuntime().availableProcessors());
        l.add("max_heap_mb=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        l.add("cpu_model=" + cpuModel());
        l.add("warmup_runs=" + o.warmup());
        l.add("recorded_runs=" + o.runs());
        l.add("chunk_bytes=" + o.chunkBytes());
        l.add("sizes_lines=" + o.sizes());
        l.add("thread_counts=" + o.threads());
        Files.write(o.outDir().resolve("environment.txt"), l, StandardCharsets.UTF_8);
    }

    private static String cpuModel() {
        try {
            Path p = Path.of("/proc/cpuinfo");
            if (Files.exists(p)) for (String line : Files.readAllLines(p)) if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
        } catch (IOException ignored) { }
        String id = System.getenv("PROCESSOR_IDENTIFIER");          // Windows
        return id != null ? id : "unknown";
    }
}
