package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RequestCancellationTest {
    private static final class Connection extends HttpURLConnection {
        final AtomicInteger disconnects = new AtomicInteger();
        Connection() throws Exception { super(new URL("https://fake.invalid")); }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnects.incrementAndGet(); }
    }

    @Test
    public void cancellationBeforeAttachCannotResurrectConnection() throws Exception {
        Http.Request request = new Http.Request();
        Connection connection = new Connection();
        CountDownLatch attach = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                if (!attach.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Attach latch timed out");
                }
                request.attach(connection);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        try {
            request.cancel();
            attach.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof TranslationException);
            assertEquals(TranslationException.Kind.CANCELLED,
                    ((TranslationException) failure.get()).kind());
            assertEquals(1, connection.disconnects.get());
            assertTrue(request.isCancelled());
        } finally {
            attach.countDown();
            worker.interrupt();
            worker.join(5000);
        }
    }

    @Test
    public void attachedCancellationIsIsolatedToItsRequest() throws Exception {
        Http.Request first = new Http.Request();
        Http.Request second = new Http.Request();
        Connection firstConnection = new Connection();
        Connection secondConnection = new Connection();
        first.attach(firstConnection);
        second.attach(secondConnection);
        Thread canceller = new Thread(first::cancel);
        canceller.start();
        canceller.join(5000);
        assertFalse(canceller.isAlive());
        assertEquals(1, firstConnection.disconnects.get());
        assertEquals(0, secondConnection.disconnects.get());
        assertTrue(first.isCancelled());
        assertFalse(second.isCancelled());
        second.checkCancelled();
        first.detach(firstConnection);
        second.detach(secondConnection);
    }

    @Test
    public void detachedConnectionsAreNotTouchedAndHandlesCannotBeReused() throws Exception {
        Http.Request request = new Http.Request();
        Connection first = new Connection();
        request.attach(first);
        request.detach(first);
        Connection second = new Connection();
        try {
            request.attach(second);
            fail("Request handle must be single-use");
        } catch (IllegalStateException expected) {
            assertEquals(1, second.disconnects.get());
        }
        request.cancel();
        assertEquals(0, first.disconnects.get());
        assertTrue(request.isCancelled());
    }

    @Test
    public void interruptedAttachRejectsAndDisconnectsCandidate() throws Exception {
        Http.Request request = new Http.Request();
        Connection connection = new Connection();
        Thread.currentThread().interrupt();
        try {
            request.attach(connection);
            fail("Interrupted attachment must fail");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CANCELLED, e.kind());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, connection.disconnects.get());
        } finally {
            Thread.interrupted();
        }
    }
}
