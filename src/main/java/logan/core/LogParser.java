package logan.core;

/**
 * Stateless, allocation-free parser for access-log lines of the form
 *
 *   IP - - [dd/Mon/yyyy:HH:mm:ss +ZZZZ] "METHOD /path?query HTTP/1.1" STATUS BYTES LATENCY_MS
 *
 * (Apache "common log" format plus a trailing response-time field in milliseconds; BYTES may be '-').
 * Being stateless it is trivially thread-safe: all mutable state lives in the LogEntry argument.
 */
public final class LogParser {
    private LogParser() { }

    /** @return true and fills {@code e} if the line is well formed; false otherwise. */
    public static boolean parse(byte[] b, int from, int to, LogEntry e) {
        // 1. client address: everything up to the first space
        int ipEnd = indexOf(b, ' ', from, to);
        if (ipEnd <= from || ipEnd - from > 45) return false;

        // 2. timestamp: [dd/Mon/yyyy:HH:mm:ss +ZZZZ]   (only the hour is needed)
        int lb = indexOf(b, '[', ipEnd, to);
        if (lb < 0 || lb + 15 >= to) return false;
        if (b[lb + 3] != '/' || b[lb + 7] != '/' || b[lb + 12] != ':' || b[lb + 15] != ':') return false;
        int h1 = b[lb + 13] - '0', h2 = b[lb + 14] - '0';
        if (h1 < 0 || h1 > 2 || h2 < 0 || h2 > 9) return false;
        int hour = h1 * 10 + h2;
        if (hour > 23) return false;
        int rb = indexOf(b, ']', lb, to);
        if (rb < 0) return false;

        // 3. request line: "METHOD PATH PROTO"
        int q1 = indexOf(b, '"', rb, to);
        if (q1 < 0) return false;
        int sp1 = indexOf(b, ' ', q1 + 1, to);
        if (sp1 < 0) return false;
        int pathStart = sp1 + 1;
        int sp2 = indexOf(b, ' ', pathStart, to);
        if (sp2 < 0 || sp2 == pathStart) return false;
        int pathEnd = indexOf(b, '?', pathStart, sp2);
        if (pathEnd < 0) pathEnd = sp2;
        int q2 = indexOf(b, '"', sp2, to);
        if (q2 < 0) return false;

        // 4. STATUS (exactly 3 digits) BYTES ('-' or digits) LATENCY (digits)
        int p = q2 + 1;
        if (p + 4 > to || b[p] != ' ') return false;
        p++;
        int d1 = b[p] - '0', d2 = b[p + 1] - '0', d3 = b[p + 2] - '0';
        if (d1 < 1 || d1 > 5 || d2 < 0 || d2 > 9 || d3 < 0 || d3 > 9) return false;
        int status = d1 * 100 + d2 * 10 + d3;
        p += 3;
        if (p >= to || b[p] != ' ') return false;
        p++;

        long bytes = 0;
        if (p < to && b[p] == '-') {
            p++;
        } else {
            int s = p;
            while (p < to && b[p] >= '0' && b[p] <= '9' && p - s < 13) { bytes = bytes * 10 + (b[p] - '0'); p++; }
            if (p == s) return false;
        }
        if (p >= to || b[p] != ' ') return false;
        p++;

        int lat = 0, s = p;
        while (p < to && b[p] >= '0' && b[p] <= '9' && p - s < 9) { lat = lat * 10 + (b[p] - '0'); p++; }
        if (p == s || p != to) return false;          // no digits, or trailing garbage

        e.buf = b;
        e.ipOff = from;  e.ipLen = ipEnd - from;
        e.pathOff = pathStart;  e.pathLen = pathEnd - pathStart;
        e.status = status;  e.bytes = bytes;  e.hour = hour;  e.latencyMs = lat;
        return true;
    }

    private static int indexOf(byte[] b, char c, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] == c) return i;
        return -1;
    }
}
