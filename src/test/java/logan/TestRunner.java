package logan;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free test runner (no JUnit download needed; runs with plain `java`).
 * Exit code 0 = all tests passed, 1 = at least one failed.
 */
public final class TestRunner {
    @FunctionalInterface public interface TestBody { void run() throws Exception; }
    private record Test(String name, TestBody body) { }

    private final List<Test> tests = new ArrayList<>();

    public void add(String name, TestBody body) { tests.add(new Test(name, body)); }

    public static void main(String[] args) throws Exception {
        TestRunner r = new TestRunner();
        ParserTests.register(r);
        ScannerTests.register(r);
        EquivalenceTests.register(r);
        EdgeCaseTests.register(r);
        System.exit(r.runAll() ? 0 : 1);
    }

    boolean runAll() {
        int passed = 0, failed = 0;
        long t0 = System.nanoTime();
        for (Test t : tests) {
            long s = System.nanoTime();
            try {
                t.body().run();
                passed++;
                System.out.printf("PASS  %-72s %6d ms%n", t.name(), (System.nanoTime() - s) / 1_000_000);
            } catch (Throwable ex) {
                failed++;
                System.out.printf("FAIL  %-72s %s%n", t.name(), ex);
            }
        }
        System.out.printf("%n%d passed, %d failed, %d total (%.1f s)%n", passed, failed, tests.size(), (System.nanoTime() - t0) / 1e9);
        return failed == 0;
    }
}
