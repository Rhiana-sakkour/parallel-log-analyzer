package logan.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Streams the lines of a byte range [start, end) of a file to a {@link LineHandler}.
 *
 * OWNERSHIP RULE (the key to correct data decomposition): a line belongs to the range that contains
 * its FIRST byte. Consequently
 *   - a range that starts in the middle of a line skips that partial line (it belongs to the previous range);
 *   - a range reads past its {@code end} just far enough to finish its last line.
 * Because the ranges of a chunking partition [0,size) without overlap, every line is processed
 * exactly once, whatever the chunk size, and no synchronization between chunks is needed.
 *
 * Reads use positional FileChannel.read(buf, position), which does not touch the channel's shared
 * file position. Even so, an instance is NOT thread-safe (it owns a reusable buffer): create one
 * instance per thread.
 */
public final class LineRangeScanner implements AutoCloseable {
    public static final int DEFAULT_BUFFER_SIZE = 256 * 1024;
    public static final int MAX_LINE_BYTES = 64 * 1024 * 1024;
    private static final int OVERSHOOT_READ = 4096;      // read size once we are past `end`

    private final FileChannel channel;
    private byte[] buf;
    private final ProgressTracker tracker;               // may be null

    public LineRangeScanner(Path file, ProgressTracker tracker) throws IOException {
        this(file, tracker, DEFAULT_BUFFER_SIZE);
    }

    public LineRangeScanner(Path file, ProgressTracker tracker, int bufferSize) throws IOException {
        if (bufferSize < 1) throw new IllegalArgumentException("bufferSize must be >= 1");
        this.channel = FileChannel.open(file, StandardOpenOption.READ);
        this.buf = new byte[bufferSize];
        this.tracker = tracker;
    }

    /** Process every line whose first byte lies in [start, end). */
    public void scan(long start, long end, LineHandler handler) throws IOException {
        if (start < 0 || end < start) throw new IllegalArgumentException("bad range " + start + ".." + end);
        if (start == end) return;

        long readOffset = start > 0 ? start - 1 : 0;     // start-1 tells us whether `start` begins a line
        long bufOffset = readOffset;                      // file offset of buf[0]
        int filled = 0, lineStart = 0, scanFrom = 0;
        boolean skipping = start > 0;                     // still inside the previous range's last line
        long reported = start;                            // progress already published (file offset)

        while (true) {
            int nl;
            while ((nl = indexOfNewline(buf, scanFrom, filled)) >= 0) {
                if (skipping) {
                    skipping = false;
                } else {
                    if (bufOffset + lineStart >= end) { publish(end, reported); return; }
                    emit(handler, lineStart, nl);
                }
                lineStart = nl + 1;
                scanFrom = lineStart;
            }
            if (!skipping && bufOffset + lineStart >= end) { publish(end, reported); return; }
            scanFrom = filled;

            if (tracker != null) {
                tracker.throwIfCancelled();
                long upTo = Math.min(bufOffset + lineStart, end);
                if (upTo > reported) { tracker.addBytes(upTo - reported); reported = upTo; }
            }

            // make room: drop consumed bytes, or grow if a single line fills the whole buffer
            if (lineStart > 0) {
                System.arraycopy(buf, lineStart, buf, 0, filled - lineStart);
                filled -= lineStart; scanFrom -= lineStart; bufOffset += lineStart; lineStart = 0;
            } else if (filled == buf.length) {
                if (skipping) {                           // nothing worth keeping
                    bufOffset += filled; filled = 0; scanFrom = 0;
                } else if (buf.length >= MAX_LINE_BYTES) {
                    throw new IOException("line longer than " + MAX_LINE_BYTES + " bytes at offset " + bufOffset);
                } else {
                    buf = Arrays.copyOf(buf, (int) Math.min((long) buf.length * 2, MAX_LINE_BYTES));
                }
            }

            // read: exactly what the range still needs, then small blocks to finish the last line
            int free = buf.length - filled;
            long needed = readOffset < end ? end - readOffset : OVERSHOOT_READ;
            int want = (int) Math.min(free, Math.max(needed, 1));
            int n = channel.read(ByteBuffer.wrap(buf, filled, want), readOffset);
            if (n < 0) {                                  // end of file
                if (!skipping && filled > lineStart && bufOffset + lineStart < end) emit(handler, lineStart, filled);
                publish(end, reported);
                return;
            }
            filled += n;
            readOffset += n;
        }
    }

    private void publish(long end, long reported) {
        if (tracker != null && end > reported) tracker.addBytes(end - reported);
    }

    /** Emits [from,to) without a trailing '\r'; blank lines are ignored. */
    private void emit(LineHandler handler, int from, int to) {
        if (to > from && buf[to - 1] == '\r') to--;
        if (to > from) handler.onLine(buf, from, to);
    }

    private static int indexOfNewline(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] == '\n') return i;
        return -1;
    }

    @Override
    public void close() throws IOException { channel.close(); }
}
