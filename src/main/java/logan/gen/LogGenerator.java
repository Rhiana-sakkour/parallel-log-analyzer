package logan.gen;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import java.util.function.LongConsumer;

/**
 * Deterministic synthetic web-server access-log generator (same seed => byte-identical file).
 * Used for the benchmark (so every machine analyses the same data) and by the GUI's "Generate sample" button.
 * The data is synthetic - the report must say so - but its shape (skewed paths / clients, ~3% server errors,
 * ~0.1% corrupt lines, query strings) exercises every branch of the parser and the aggregation.
 *
 * Usage: java -cp out logan.gen.LogGenerator &lt;lines&gt; &lt;outputFile&gt; [seed]
 */
public final class LogGenerator {
    public static final long DEFAULT_SEED = 42L;
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};

    private LogGenerator() { }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: LogGenerator <lines> <outputFile> [seed]");
            System.exit(2);
        }
        long lines = Long.parseLong(args[0]);
        long seed = args.length > 2 ? Long.parseLong(args[2]) : DEFAULT_SEED;
        generate(Path.of(args[1]), lines, seed, null);
        System.out.printf("wrote %,d lines (%,d bytes) to %s%n", lines, Files.size(Path.of(args[1])), args[1]);
    }

    /** @param progress optional; receives the number of lines written so far (called every 100k lines). */
    public static void generate(Path out, long lines, long seed, LongConsumer progress) throws IOException {
        if (lines < 0) throw new IllegalArgumentException("lines must be >= 0");
        SplittableRandom rnd = new SplittableRandom(seed);
        byte[][] paths = buildPaths();
        byte[][] ips = buildIps(rnd, 20_000);
        byte[] http = " HTTP/1.1\" ".getBytes(StandardCharsets.US_ASCII);

        LineBuilder lb = new LineBuilder();
        long clock = LocalDateTime.of(2026, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC);
        long lastStamp = Long.MIN_VALUE;
        byte[] stamp = null;

        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            for (long i = 0; i < lines; i++) {
                if (rnd.nextInt(3) == 0) clock++;
                if (clock != lastStamp) { stamp = formatStamp(clock); lastStamp = clock; }

                lb.reset();
                double u = rnd.nextDouble();
                lb.bytes(ips[(int) (ips.length * u * u)]);
                lb.ascii(" - - [").bytes(stamp).ascii("] \"");
                int m = rnd.nextInt(100);
                lb.ascii(METHODS[m < 70 ? 0 : m < 90 ? 1 : m < 96 ? 2 : 3]).ch(' ');
                double v = rnd.nextDouble();
                lb.bytes(paths[(int) (paths.length * v * v * v)]);
                if (rnd.nextInt(5) == 0) lb.ascii("?q=").num(rnd.nextInt(100000));
                lb.bytes(http);

                int s = rnd.nextInt(1000);
                int status = s < 780 ? 200 : s < 840 ? 304 : s < 910 ? 404 : s < 940 ? 301 : s < 970 ? 500
                        : s < 980 ? 502 : s < 990 ? 503 : s < 997 ? 403 : 401;
                lb.num(status).ch(' ');
                double w = rnd.nextDouble();
                if (status == 304) lb.ch('-'); else lb.num(100 + (long) (w * w * w * 200_000));
                double x = rnd.nextDouble();
                lb.ch(' ').num(5 + (long) (x * x * x * 3000) + (status >= 500 ? 500 : 0));

                if (rnd.nextInt(1000) == 0) corrupt(lb, rnd);          // ~0.1% malformed lines
                lb.ch('\n');
                os.write(lb.array(), 0, lb.length());
                if (progress != null && (i + 1) % 100_000 == 0) progress.accept(i + 1);
            }
        }
        if (progress != null) progress.accept(lines);
    }

    private static void corrupt(LineBuilder lb, SplittableRandom rnd) {
        if (rnd.nextBoolean()) lb.truncate(1 + rnd.nextInt(Math.max(1, Math.min(lb.length(), 40))));   // cut off early
        else { lb.reset(); lb.ascii("#### corrupted record ####"); }
    }

    private static byte[] formatStamp(long epochSecond) {
        LocalDateTime t = LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC);
        String s = String.format(java.util.Locale.ROOT, "%02d/%s/%04d:%02d:%02d:%02d +0000", t.getDayOfMonth(), MONTHS[t.getMonthValue() - 1],
                t.getYear(), t.getHour(), t.getMinute(), t.getSecond());
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[][] buildPaths() {
        java.util.List<String> p = new java.util.ArrayList<>();
        String[] fixed = {"/", "/index.html", "/login", "/logout", "/search", "/cart", "/checkout", "/health", "/static/app.js", "/static/site.css"};
        for (String f : fixed) p.add(f);
        for (int i = 0; i < 1000; i++) p.add("/api/products/" + i);
        for (int i = 0; i < 200; i++) p.add("/api/users/" + i + "/orders");
        for (int i = 0; i < 100; i++) p.add("/images/img" + i + ".png");
        byte[][] out = new byte[p.size()][];
        for (int i = 0; i < out.length; i++) out[i] = p.get(i).getBytes(StandardCharsets.US_ASCII);
        return out;
    }

    private static byte[][] buildIps(SplittableRandom rnd, int count) {
        byte[][] out = new byte[count][];
        for (int i = 0; i < count; i++) {
            String ip = (1 + rnd.nextInt(223)) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256) + "." + (1 + rnd.nextInt(254));
            out[i] = ip.getBytes(StandardCharsets.US_ASCII);
        }
        return out;
    }

    /** Minimal reusable ASCII line buffer (avoids per-line String allocation while generating). */
    private static final class LineBuilder {
        private byte[] a = new byte[1024];
        private int n;
        void reset() { n = 0; }
        int length() { return n; }
        byte[] array() { return a; }
        void truncate(int len) { n = Math.min(n, len); }
        private void ensure(int extra) { if (n + extra > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + extra)); }
        LineBuilder ch(char c) { ensure(1); a[n++] = (byte) c; return this; }
        LineBuilder bytes(byte[] b) { ensure(b.length); System.arraycopy(b, 0, a, n, b.length); n += b.length; return this; }
        LineBuilder ascii(String s) { return bytes(s.getBytes(StandardCharsets.US_ASCII)); }
        LineBuilder num(long v) {
            ensure(20);
            if (v == 0) { a[n++] = '0'; return this; }
            int start = n;
            while (v > 0) { a[n++] = (byte) ('0' + v % 10); v /= 10; }
            for (int i = start, j = n - 1; i < j; i++, j--) { byte t = a[i]; a[i] = a[j]; a[j] = t; }
            return this;
        }
    }
}
