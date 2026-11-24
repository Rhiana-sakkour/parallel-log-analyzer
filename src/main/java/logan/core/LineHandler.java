package logan.core;

/** Callback invoked by {@link LineRangeScanner} for every non-empty line. The buffer is only valid during the call. */
@FunctionalInterface
public interface LineHandler {
    void onLine(byte[] buf, int from, int to);
}
