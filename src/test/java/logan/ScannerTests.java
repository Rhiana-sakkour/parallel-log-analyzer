package logan;

import logan.core.LineRangeScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The scanner's ownership rule is what makes chunked parallel processing correct, so it is tested
 * exhaustively against an independent oracle: for many inputs and EVERY chunk size, the concatenation
 * of the chunks' lines must equal the lines of the whole file (no line lost, none duplicated, none split).
 */
final class ScannerTests {
    private ScannerTests() { }

    /** Independent oracle: split on '\n', drop one trailing '\r', drop empty lines. */
    static List<String> oracle(String content) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= content.length(); i++) {
            if (i == content.length() || content.charAt(i) == '\n') {
                if (i > start || i == content.length()) {
                    String line = content.substring(start, i);
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (!line.isEmpty()) out.add(line);
                }
                start = i + 1;
            }
        }
        return out;
    }

    static List<String> scanChunks(Path file, long size, long chunk, int bufferSize) throws IOException {
        List<String> lines = new ArrayList<>();
        try (LineRangeScanner sc = new LineRangeScanner(file, null, bufferSize)) {
            for (long s = 0; s < size; s += chunk) {
                sc.scan(s, Math.min(size, s + chunk), (b, f, t) -> lines.add(new String(b, f, t - f, StandardCharsets.ISO_8859_1)));
            }
        }
        return lines;
    }

    private static void checkAllChunkSizes(String name, String content, int[] bufferSizes) throws IOException {
        Path file = TestData.text(name, content);
        List<String> expected = oracle(content);
        long size = content.getBytes(StandardCharsets.ISO_8859_1).length;
        for (int buf : bufferSizes) {
            for (long chunk = 1; chunk <= size + 2; chunk++) {
                Check.equal(expected, scanChunks(file, size, chunk, buf), name + " chunk=" + chunk + " buffer=" + buf);
            }
        }
    }

    static void register(TestRunner r) {
        r.add("scanner: chunking is lossless for every chunk size (LF, CRLF, no final newline, blank lines)", () -> {
            checkAllChunkSizes("a.txt", "alpha\nbeta\r\n\ngamma\n\n\ndelta", new int[] {1, 2, 3, 7, 64});
            checkAllChunkSizes("b.txt", "one line only, no newline", new int[] {1, 5, 64});
            checkAllChunkSizes("c.txt", "\n\n\n", new int[] {1, 4});
            checkAllChunkSizes("d.txt", "x\n", new int[] {1, 4});
        });
        r.add("scanner: 300 random small files x all chunk sizes x tiny buffers", () -> {
            Random rnd = new Random(7);
            String alphabet = "ab\n\n\r ";
            for (int i = 0; i < 300; i++) {
                StringBuilder sb = new StringBuilder();
                int len = rnd.nextInt(40);
                for (int j = 0; j < len; j++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
                checkAllChunkSizes("rnd" + i + ".txt", sb.toString(), new int[] {1, 3, 16});
            }
        });
        r.add("scanner: a line much longer than the buffer (buffer growth) is delivered intact", () -> {
            String longLine = "L".repeat(10_000);
            String content = "short\n" + longLine + "\nend\n";
            Path file = TestData.text("long.txt", content);
            long size = content.length();
            for (long chunk : new long[] {1, 7, 5000, 10_006, size}) {
                Check.equal(oracle(content), scanChunks(file, size, chunk, 16), "chunk=" + chunk);
            }
        });
        r.add("scanner: empty file and empty range produce no lines", () -> {
            Path empty = TestData.text("empty.txt", "");
            Check.equal(0, scanChunks(empty, 0, 10, 8).size(), "empty file");
            try (LineRangeScanner sc = new LineRangeScanner(empty, null)) {
                sc.scan(0, 0, (b, f, t) -> { throw new AssertionError("no lines expected"); });
            }
        });
        r.add("scanner: invalid range is rejected", () -> {
            Path f = TestData.text("range.txt", "abc\n");
            try (LineRangeScanner sc = new LineRangeScanner(f, null)) {
                Check.throwsA(IllegalArgumentException.class, () -> sc.scan(5, 2, (b, a, c) -> { }), "end < start");
                Check.throwsA(IllegalArgumentException.class, () -> sc.scan(-1, 2, (b, a, c) -> { }), "negative start");
            }
            Check.throwsA(IllegalArgumentException.class, () -> new LineRangeScanner(f, null, 0), "buffer 0");
        });
    }
}
