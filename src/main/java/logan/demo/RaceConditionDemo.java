package logan.demo;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Small, self-contained demonstration of WHY synchronization is needed (and what each primitive costs).
 * N threads each perform INCREMENTS increments of one shared counter in four different ways.
 *
 *   plain long        : counter++ is read-modify-write, NOT atomic => lost updates (a RACE CONDITION)
 *   ReentrantLock     : mutual exclusion => correct, threads wait for each other
 *   AtomicLong        : hardware compare-and-swap => correct, lock-free but contended on one cache line
 *   LongAdder         : striped cells => correct and scales best under heavy contention
 *
 * Note: a race is non-deterministic. It may lose many updates, few, or (on a machine with a single core)
 * even none in a given run; "no loss observed" never proves that unsynchronized code is safe.
 *
 * Usage: java -cp out logan.demo.RaceConditionDemo [threads] [incrementsPerThread]
 */
public final class RaceConditionDemo {
    private static long plain;                                    // deliberately unsafe
    private static long locked;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final AtomicLong ATOMIC = new AtomicLong();
    private static final LongAdder ADDER = new LongAdder();

    public static void main(String[] args) throws InterruptedException {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        long per = args.length > 1 ? Long.parseLong(args[1]) : 5_000_000L;
        long expected = threads * per;
        System.out.printf("%d threads x %,d increments => expected %,d%n%n", threads, per, expected);
        System.out.printf("%-16s %14s %14s %10s%n", "variant", "final value", "lost updates", "time ms");

        run("plain long", threads, per, expected, () -> plain, () -> plain++);
        run("ReentrantLock", threads, per, expected, () -> locked, () -> { LOCK.lock(); try { locked++; } finally { LOCK.unlock(); } });
        run("AtomicLong", threads, per, expected, ATOMIC::get, ATOMIC::incrementAndGet);
        run("LongAdder", threads, per, expected, ADDER::sum, ADDER::increment);
    }

    private static void run(String name, int threads, long per, long expected,
                            java.util.function.LongSupplier read, Runnable inc) throws InterruptedException {
        Thread[] ts = new Thread[threads];
        long t0 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> { for (long k = 0; k < per; k++) inc.run(); }, "demo-" + i);
            ts[i].start();
        }
        for (Thread t : ts) t.join();                              // join => happens-before, so read.getAsLong() is safe
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long v = read.getAsLong();
        System.out.printf("%-16s %,14d %,14d %10d%n", name, v, expected - v, ms);
    }
}
