package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class HookRuntimeTest {
    @Before
    public void beginInactive() {
        assertTrue(HookRuntime.suspendAndAwait(0L));
    }

    @After
    public void endInactive() {
        assertTrue(HookRuntime.suspendAndAwait(0L));
    }

    private static void assertActivationRejected() {
        try {
            HookRuntime.activate();
            fail("Activation must retain outstanding calls");
        } catch (IllegalStateException expected) {
            assertFalse(HookRuntime.isActive());
        }
    }

    @Test
    public void inactiveAdmissionIsRejected() {
        assertNull(HookRuntime.enter());
        HookRuntime.activate();
        try (HookRuntime.Lease lease = HookRuntime.enter()) {
            assertNotNull(lease);
            HookRuntime.suspend();
            assertFalse(HookRuntime.isActive());
            assertNull(HookRuntime.enter());
        }
        assertTrue(HookRuntime.suspendAndAwait(0L));
    }

    @Test
    public void nestedLeasesCloseIndependentlyAndIdempotently() {
        HookRuntime.activate();
        HookRuntime.Lease outer = HookRuntime.enter();
        HookRuntime.Lease inner = HookRuntime.enter();
        assertNotNull(outer);
        assertNotNull(inner);
        try {
            HookRuntime.suspend();
            inner.close();
            inner.close();
            assertFalse(HookRuntime.suspendAndAwait(0L));
            assertActivationRejected();
            outer.close();
            outer.close();
            assertTrue(HookRuntime.suspendAndAwait(0L));
            HookRuntime.activate();
            assertTrue(HookRuntime.isActive());
        } finally {
            inner.close();
            outer.close();
        }
    }

    @Test
    public void pausedAdmittedCallMustFinishBeforeCleanup() throws Exception {
        HookRuntime.activate();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicBoolean businessFinished = new AtomicBoolean();
        AtomicBoolean cleanup = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread call = new Thread(() -> {
            try (HookRuntime.Lease lease = HookRuntime.enter()) {
                assertNotNull(lease);
                entered.countDown();
                assertTrue(resume.await(5L, TimeUnit.SECONDS));
                // Represents the late Scheduler.get().submit() that must precede teardown.
                businessFinished.set(true);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        Thread teardown = new Thread(() -> {
            try {
                HookRuntime.suspend();
                waiting.countDown();
                assertTrue(HookRuntime.suspendAndAwait(2000L));
                assertTrue(businessFinished.get());
                cleanup.set(true);
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                drained.countDown();
            }
        });
        call.start();
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS));
            teardown.start();
            assertTrue(waiting.await(5L, TimeUnit.SECONDS));
            assertNull(HookRuntime.enter());
            assertActivationRejected();
            assertFalse(drained.await(50L, TimeUnit.MILLISECONDS));
            assertFalse(cleanup.get());
            resume.countDown();
            assertTrue(drained.await(5L, TimeUnit.SECONDS));
            assertTrue(cleanup.get());
        } finally {
            resume.countDown();
            call.join(5000L);
            if (teardown.getState() != Thread.State.NEW) teardown.join(5000L);
        }
        assertNull(failure.get());
        assertFalse(call.isAlive());
        assertFalse(teardown.isAlive());
    }

    @Test
    public void ownLeaseTimesOutAndCannotBeForgottenOrReactivated() {
        HookRuntime.activate();
        try (HookRuntime.Lease lease = HookRuntime.enter()) {
            assertNotNull(lease);
            long start = System.nanoTime();
            assertFalse(HookRuntime.suspendAndAwait(20L));
            assertTrue("Drain was not bounded", System.nanoTime() - start
                    < TimeUnit.SECONDS.toNanos(2L));
            assertNull(HookRuntime.enter());
            assertActivationRejected();
            assertFalse(HookRuntime.suspendAndAwait(0L));
        }
        assertTrue(HookRuntime.suspendAndAwait(0L));
        HookRuntime.activate();
        assertTrue(HookRuntime.isActive());
    }

    @Test
    public void interruptedDrainRetainsLeaseAndInterruptStatus() {
        HookRuntime.activate();
        try (HookRuntime.Lease lease = HookRuntime.enter()) {
            assertNotNull(lease);
            Thread.currentThread().interrupt();
            try {
                assertFalse(HookRuntime.suspendAndAwait(2000L));
                assertTrue(Thread.currentThread().isInterrupted());
                assertActivationRejected();
            } finally {
                Thread.interrupted();
            }
            assertFalse(HookRuntime.suspendAndAwait(0L));
        }
        assertTrue(HookRuntime.suspendAndAwait(0L));
    }
}
