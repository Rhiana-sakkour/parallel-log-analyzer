package logan.core;

import java.nio.charset.StandardCharsets;

/**
 * Mutable, reusable holder for ONE parsed log line.
 *
 * Thread-safety: every worker thread owns exactly one LogEntry and re-uses it for every line it
 * parses, so the instance is thread-confined and needs no synchronization. The entry points into the
 * scanner's byte buffer (zero-copy); Strings are created lazily, only for the fields that are really
 * aggregated (path, and IP for error lines). Consumers must NOT keep a reference to the entry (or to
 * its buffer) after {@link Accumulator#add(LogEntry)} returns.
 */
public final class LogEntry {
    byte[] buf;
    int ipOff, ipLen, pathOff, pathLen;
    int status;
    long bytes;
    int hour;
    int latencyMs;

    public int status()    { return status; }
    public long bytes()    { return bytes; }
    public int hour()      { return hour; }
    public int latencyMs() { return latencyMs; }

    public String ip()   { return new String(buf, ipOff, ipLen, StandardCharsets.ISO_8859_1); }
    /** Request path WITHOUT the query string. */
    public String path() { return new String(buf, pathOff, pathLen, StandardCharsets.ISO_8859_1); }
}
