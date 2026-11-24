package logan;

import logan.core.LogEntry;
import logan.core.LogParser;

import java.nio.charset.StandardCharsets;

final class ParserTests {
    private ParserTests() { }

    private static boolean parse(String line, LogEntry e) {
        byte[] b = line.getBytes(StandardCharsets.ISO_8859_1);
        return LogParser.parse(b, 0, b.length, e);
    }

    static void register(TestRunner r) {
        r.add("parser: well-formed line -> all fields extracted", () -> {
            LogEntry e = new LogEntry();
            Check.isTrue(parse(TestData.GOOD_LINE, e), "should parse");
            Check.equal("10.0.0.1", e.ip(), "ip");
            Check.equal("/api/items/7", e.path(), "path (query string stripped)");
            Check.equal(200, e.status(), "status");
            Check.equal(512L, e.bytes(), "bytes");
            Check.equal(14, e.hour(), "hour");
            Check.equal(37, e.latencyMs(), "latency");
        });
        r.add("parser: '-' bytes means 0", () -> {
            LogEntry e = new LogEntry();
            Check.isTrue(parse("1.2.3.4 - - [05/Mar/2026:00:00:00 +0000] \"GET / HTTP/1.1\" 304 - 5", e), "should parse");
            Check.equal(0L, e.bytes(), "bytes");
            Check.equal(0, e.hour(), "hour 00");
        });
        r.add("parser: parses a sub-range of a larger buffer (from/to respected)", () -> {
            byte[] line = TestData.GOOD_LINE.getBytes(StandardCharsets.ISO_8859_1);
            byte[] big = new byte[line.length + 20];
            java.util.Arrays.fill(big, (byte) 'Z');
            System.arraycopy(line, 0, big, 10, line.length);
            LogEntry e = new LogEntry();
            Check.isTrue(LogParser.parse(big, 10, 10 + line.length, e), "should parse inside buffer");
            Check.equal(37, e.latencyMs(), "latency");
        });
        String[] bad = {
            "", "garbage", "#### corrupted record ####",
            "1.2.3.4 - - [05/Mar/2026:14:05:33 +0000] \"GET /x HTTP/1.1\" 200",                     // missing fields
            "1.2.3.4 - - [05/Mar/2026:25:05:33 +0000] \"GET /x HTTP/1.1\" 200 1 1",               // hour 25
            "1.2.3.4 - - [05-Mar-2026:14:05:33 +0000] \"GET /x HTTP/1.1\" 200 1 1",               // bad separators
            "1.2.3.4 - - [05/Mar/2026:14:05:33 +0000] \"GET /x HTTP/1.1\" 2x0 1 1",               // bad status
            "1.2.3.4 - - [05/Mar/2026:14:05:33 +0000] \"GET /x HTTP/1.1\" 200 1 1 extra",         // trailing garbage
            "1.2.3.4 - - [05/Mar/2026:14:05:33 +0000] \"GET /x HTTP/1.1\" 200 abc 1",             // bad bytes
            "1.2.3.4 - - [05/Mar/2026:14:05:33 +0000 \"GET /x HTTP/1.1\" 200 1 1",                // missing ]
            "1.2.3.4 - - [05/Mar/2026:14:05:33 +0000] \"GET /x" };                                  // truncated
        for (String line : bad) {
            r.add("parser: rejects malformed line [" + (line.length() > 30 ? line.substring(0, 30) + "..." : line) + "]", () -> {
                Check.isTrue(!parse(line, new LogEntry()), "should be rejected");
            });
        }
        r.add("parser: every proper prefix of a valid line is handled without exception", () -> {
            byte[] line = TestData.GOOD_LINE.getBytes(StandardCharsets.ISO_8859_1);
            LogEntry e = new LogEntry();
            for (int len = 0; len < line.length; len++) LogParser.parse(line, 0, len, e);   // must not throw
        });
    }
}
