package logan.core;

/**
 * Glue between the scanner and an Accumulator: parse the line, then hand it to the accumulator.
 * The SAME class is used by the sequential and the parallel analyzers, so both versions execute
 * identical per-line work; the only difference is HOW the lines are distributed over threads.
 * One instance per thread (it owns a reusable LogEntry and a plain, thread-confined counter).
 */
public final class AccumulatingHandler implements LineHandler {
    private final Accumulator accumulator;
    private final LogEntry entry = new LogEntry();
    private long lines;

    public AccumulatingHandler(Accumulator accumulator) { this.accumulator = accumulator; }

    @Override
    public void onLine(byte[] buf, int from, int to) {
        lines++;
        if (LogParser.parse(buf, from, to, entry)) accumulator.add(entry);
        else accumulator.addMalformed();
    }

    public long lines() { return lines; }
}
