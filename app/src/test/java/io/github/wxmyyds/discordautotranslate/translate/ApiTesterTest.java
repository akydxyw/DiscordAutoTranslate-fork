package io.github.wxmyyds.discordautotranslate.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.Http;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** API-test ownership uses the real engine and transport with controlled connection I/O. */
public class ApiTesterTest {
    @Test
    public void offlineCheckReturnsOnlyTheFieldsConsumedBySettings() {
        ApiTester.Result result = ApiTester.selfCheck(Config.read(new MemoryPrefs()));
        assertTrue(result.ok);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
    }

    @Test
    public void invalidConfigFailsOfflineAndConnectionTestWithoutOpeningIo() {
        Config.Snapshot config = Config.read(new MemoryPrefs().put(Config.KEY_API_URL, "invalid"));
        for (ApiTester.Result result : new ApiTester.Result[]{ApiTester.selfCheck(config),
                ApiTester.newCall(config, ApiTester.DEFAULT_TEST_TEXT).call()}) {
            assertFalse(result.ok);
            assertNull(result.translated);
            assertTrue(result.elapsedMs >= 0);
        }
    }

    @Test
    public void blankTranslationUsesTheEnginesRejectionPath() {
        ApiTester.Result result = ApiTester.newCall(Config.read(new MemoryPrefs()), " \n\t").call();
        assertFalse(result.ok);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
    }

    @Test
    public void cancellationBeforeSubmissionPreventsIoWithoutInterruptingTheCaller() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        ApiTester.Call call = call(url -> {
            opens.incrementAndGet();
            throw new AssertionError("Cancelled queued call opened a connection");
        });
        call.cancel();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ApiTester.Result result = executor.submit(call).get(2, TimeUnit.SECONDS);
            assertFalse(result.ok);
            assertNull(result.translated);
            assertEquals(0, opens.get());
            assertFalse(Thread.currentThread().isInterrupted());
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted())
                    .get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationDisconnectsReadThatIgnoresInterruption() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                awaitIgnoringInterrupt(disconnected);
                throw new IOException("Disconnected");
            }
        }) {
            @Override public void disconnect() {
                super.disconnect();
                disconnected.countDown();
            }
        };
        AtomicInteger opens = new AtomicInteger();
        ApiTester.Call call = call(url -> {
            opens.incrementAndGet();
            return connection;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiTester.Result> result = executor.submit(call);
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            // Deliberately do not cancel the Future: owner cancellation must disconnect.
            call.cancel();
            assertFalse(result.get(2, TimeUnit.SECONDS).ok);
            assertTrue(connection.disconnected);
            assertEquals(1, opens.get());
            assertFalse(executor.submit(() -> Thread.currentThread().isInterrupted())
                    .get(2, TimeUnit.SECONDS));
        } finally {
            disconnected.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellationBeforeConnectionAttachmentCannotStartTheRequest() throws Exception {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        FakeConnection connection = successfulConnection();
        ApiTester.Call call = call(url -> {
            opening.countDown();
            awaitIgnoringInterrupt(releaseOpen);
            return connection;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiTester.Result> result = executor.submit(call);
            assertTrue(opening.await(2, TimeUnit.SECONDS));
            call.cancel();
            releaseOpen.countDown();
            assertFalse(result.get(2, TimeUnit.SECONDS).ok);
            assertTrue(connection.disconnected);
            assertEquals(0, connection.output.size());
        } finally {
            releaseOpen.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void lateCancellationCannotInterruptTheCompletedWorkersNextJob() throws Exception {
        FakeConnection connection = successfulConnection();
        ApiTester.Call call = call(url -> connection);
        CountDownLatch nextJob = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ApiTester.Result result = executor.submit(call).get(2, TimeUnit.SECONDS);
            assertTrue(result.ok);
            assertEquals("你好", result.translated);
            assertTrue(connection.disconnected);
            Future<Boolean> next = executor.submit(() -> {
                nextJob.countDown();
                try {
                    return !releaseNext.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    return true;
                }
            });
            assertTrue(nextJob.await(2, TimeUnit.SECONDS));
            call.cancel();
            call.cancel();
            releaseNext.countDown();
            assertFalse(next.get(2, TimeUnit.SECONDS));
        } finally {
            releaseNext.countDown();
            executor.shutdownNow();
        }
    }

    @Test(expected = IllegalStateException.class)
    public void callCannotBeReused() {
        ApiTester.Call call = ApiTester.newCall(Config.read(new MemoryPrefs()), "");
        call.call();
        call.call();
    }

    private interface Factory {
        HttpURLConnection open(URL url) throws Exception;
    }

    /** Same package-private transport injection used by engine tests, without widening APIs. */
    private static ApiTester.Call call(Factory factory) throws Exception {
        Class<?> type = Class.forName(Http.class.getName() + "$ConnectionFactory");
        Object connections = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> factory.open((URL) args[0]));
        Constructor<Http> constructor = Http.class.getDeclaredConstructor(int.class, type);
        constructor.setAccessible(true);
        Http http = constructor.newInstance(1000, connections);
        Config.Snapshot config = Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                .put(Config.KEY_STREAMING, false));
        return new ApiTester.Call(new JsonApiEngine(config, http), "hello");
    }

    private static FakeConnection successfulConnection() throws Exception {
        return new FakeConnection(new ByteArrayInputStream(
                "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Controlled I/O was never released");
                    }
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class FakeConnection extends HttpURLConnection {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile boolean disconnected;

        FakeConnection(InputStream input) throws Exception {
            super(new URL("https://fake.invalid/v1"));
            this.input = input;
        }

        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() { return 200; }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
    }
}
