package logan;

import java.util.Objects;

/** Tiny assertion helpers. */
final class Check {
    private Check() { }
    static void isTrue(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
    static void equal(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + ": expected <" + expected + "> but was <" + actual + ">");
    }
    static <T extends Throwable> void throwsA(Class<T> type, TestRunner.TestBody body, String msg) {
        try { body.run(); } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(msg + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(msg + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
