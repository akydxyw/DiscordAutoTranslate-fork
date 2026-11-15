package io.github.wxmyyds.discordautotranslate.hooks;

import androidx.annotation.Nullable;

import java.util.concurrent.TimeUnit;

/** Admission and drain barrier for every call that can touch the installed runtime. */
public final class HookRuntime {
    private static final Object LOCK = new Object();
    private static volatile boolean active;
    private static int admitted;
    private static int drains;

    private HookRuntime() {}

    /** Fast acceptance hint only; callers must acquire a lease before doing runtime work. */
    public static boolean isActive() { return active; }

    @Nullable
    public static Lease enter() {
        synchronized (LOCK) {
            if (!active) return null;
            admitted++;
            return new Lease();
        }
    }

    public static void activate() {
        synchronized (LOCK) {
            if (admitted != 0 || drains != 0) {
                throw new IllegalStateException("Previous runtime calls have not drained");
            }
            active = true;
        }
    }

    public static void suspend() {
        synchronized (LOCK) {
            active = false;
        }
    }

    /** A timeout or interruption retains all leases and leaves admission closed. */
    public static boolean suspendAndAwait(long timeoutMs) {
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        long start = System.nanoTime();
        synchronized (LOCK) {
            active = false;
            drains++;
            try {
                while (admitted != 0) {
                    long remaining = budget - (System.nanoTime() - start);
                    if (remaining <= 0L) return false;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(LOCK, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            } finally {
                drains--;
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private boolean closed;

        private Lease() {}

        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                admitted--;
                if (admitted == 0) LOCK.notifyAll();
            }
        }
    }
}
