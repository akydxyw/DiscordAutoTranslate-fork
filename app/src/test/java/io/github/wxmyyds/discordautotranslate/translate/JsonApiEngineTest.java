package io.github.wxmyyds.discordautotranslate.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.Http;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class JsonApiEngineTest {
    private static class FakeConnection extends HttpURLConnection {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = 200;
        boolean disconnected;

        FakeConnection(String response) throws Exception {
            this(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        }
        FakeConnection(InputStream input) throws Exception {
            super(new URL("https://fake.invalid/v1"));
            this.input = input;
        }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
    }

    private interface Factory { HttpURLConnection open(URL url) throws Exception; }

    /** Access the package-private connection seam without widening the production API. */
    private static Http http(Factory factory) throws Exception {
        Class<?> type = Class.forName(Http.class.getName() + "$ConnectionFactory");
        Object connections = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> factory.open((URL) args[0]));
        Constructor<Http> constructor = Http.class.getDeclaredConstructor(int.class, type);
        constructor.setAccessible(true);
        return constructor.newInstance(1000, connections);
    }

    private static Config.Snapshot config(String protocol, boolean streaming) {
        return Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "https://fake.invalid/v1")
                .put(Config.KEY_API_KEY, "key")
                .put(Config.KEY_PROTOCOL, protocol)
                .put(Config.KEY_STREAMING, streaming));
    }

    @SuppressWarnings("unchecked")
    private static Map<Thread, Http.Request> active(JsonApiEngine engine) throws Exception {
        Field field = JsonApiEngine.class.getDeclaredField("active");
        field.setAccessible(true);
        return (Map<Thread, Http.Request>) field.get(engine);
    }

    @Test
    public void completeBusinessPathUsesSelectedModeAndProtocolHeaders() throws Exception {
        String[] protocols = {Config.PROTOCOL_OPENAI_COMPATIBLE,
                Config.PROTOCOL_ANTHROPIC_MESSAGES, Config.PROTOCOL_OPENAI_RESPONSES};
        for (String protocol : protocols) {
            for (boolean streaming : new boolean[]{true, false}) {
                boolean sse = streaming && !Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol);
                String body;
                if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)) {
                    body = sse ? "data: {\"choices\":[{\"delta\":{\"content\":\" 你好 \"}}]}\n\n"
                            + "data: [DONE]\n\n"
                            : "{\"choices\":[{\"message\":{\"content\":\" 你好 \"}}]}";
                } else if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
                    body = sse ? "data: {\"type\":\"content_block_delta\","
                            + "\"delta\":{\"type\":\"text_delta\",\"text\":\" 你好 \"}}\n\n"
                            + "data: {\"type\":\"message_stop\"}\n\n"
                            : "{\"content\":[{\"type\":\"text\",\"text\":\" 你好 \"}]}";
                } else {
                    body = "{\"output\":[{\"type\":\"message\",\"content\":["
                            + "{\"type\":\"output_text\",\"text\":\" 你好 \"}]}]}";
                }
                FakeConnection connection = new FakeConnection(body);
                AtomicInteger opens = new AtomicInteger();
                JsonApiEngine engine = new JsonApiEngine(config(protocol, streaming), http(url -> {
                    opens.incrementAndGet();
                    assertTrue(url.toString().startsWith("https://fake.invalid/v1/"));
                    return connection;
                }));
                assertEquals("你好", engine.translate("hello"));
                assertEquals(1, opens.get());
                assertTrue(active(engine).isEmpty());
                assertTrue(connection.disconnected);
                JSONObject request = new JSONObject(connection.output.toString("UTF-8"));
                if (!Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
                    assertEquals(sse, request.getBoolean("stream"));
                } else {
                    assertFalse(request.has("stream"));
                }
                assertEquals(sse ? "text/event-stream" : "application/json",
                        connection.getRequestProperty("Accept"));
                if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
                    assertEquals("key", connection.getRequestProperty("x-api-key"));
                    assertEquals("2023-06-01", connection.getRequestProperty("anthropic-version"));
                } else {
                    assertEquals("Bearer key", connection.getRequestProperty("Authorization"));
                }
            }
        }
    }

    @Test
    public void rawHttpErrorsAreClassifiedBeforeSuccessParsingWithoutBodyLeakage() throws Exception {
        for (int status : new int[]{302, 401, 403, 429, 500}) {
            FakeConnection connection = new FakeConnection("secret non-SSE body");
            connection.status = status;
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_ANTHROPIC_MESSAGES, true),
                    http(url -> connection));
            try {
                engine.translate("hello");
                fail("Expected HTTP error");
            } catch (TranslationException e) {
                assertEquals(status == 429 ? TranslationException.Kind.RATE_LIMIT
                        : TranslationException.Kind.HTTP, e.kind());
                assertFalse(e.userMessage().contains("secret"));
            }
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void modeMismatchAndDamagedTextHaveNoRetryOrFallback() throws Exception {
        for (String body : new String[]{"{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"<translate>你好\"}}]}\n\n"
                        + "data: [DONE]\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"XXXXXX\"}}]}\n\n"
                        + "data: [DONE]\n\n"}) {
            AtomicInteger opens = new AtomicInteger();
            FakeConnection connection = new FakeConnection(body);
            JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                    http(url -> { opens.incrementAndGet(); return connection; }));
            try {
                engine.translate("hello");
                fail("Expected strict parse rejection");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.PARSE, e.kind());
            }
            assertEquals(1, opens.get());
            assertTrue(active(engine).isEmpty());
        }
    }

    @Test
    public void httpSuccessWithPartialTranslationCanStillFailWithReadTimeout() throws Exception {
        byte[] prefix = ("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        FakeConnection connection = new FakeConnection(new InputStream() {
            private int offset;
            @Override public int read() throws IOException {
                if (offset < prefix.length) return prefix[offset++] & 0xff;
                throw new SocketTimeoutException("private response detail");
            }
        });
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> { opens.incrementAndGet(); return connection; }));
        try {
            engine.translate("hello");
            fail("Partial translation without a completed stream must not be accepted");
        } catch (TranslationException failure) {
            assertEquals(TranslationException.Kind.TIMEOUT, failure.kind());
            assertTrue(failure.getCause() instanceof SocketTimeoutException);
            assertFalse(failure.userMessage().contains("private response detail"));
        }
        assertEquals(200, connection.status);
        assertEquals(1, opens.get());
        assertTrue(connection.disconnected);
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void httpSuccessWithEofBeforeDoneRemainsParseFailureNotTimeout() throws Exception {
        FakeConnection connection = new FakeConnection(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n");
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> { opens.incrementAndGet(); return connection; }));
        try {
            engine.translate("hello");
            fail("Missing protocol end event must reject partial translation");
        } catch (TranslationException failure) {
            assertEquals(TranslationException.Kind.PARSE, failure.kind());
        }
        assertEquals(200, connection.status);
        assertEquals(1, opens.get());
        assertTrue(connection.disconnected);
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void cancelBeforeRegistrationInterruptsWorkerAndPreventsAllIo() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> { opens.incrementAndGet(); throw new IOException("Must not open"); }));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            // Scheduler cancellation can occur before Engine.translate is entered.
            engine.cancel(Thread.currentThread());
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertCancelled(failure.get());
        assertEquals(0, opens.get());
        assertTrue(active(engine).isEmpty());
    }

    @Test
    public void cancelInAttachGapMarksHandleUntilFinallyEvenIfInterruptIsCleared() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection("data: [DONE]\n\n");
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> {
                    opened.countDown();
                    awaitIgnoringInterrupts(release);
                    return connection;
                }));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            Http.Request registered = active(engine).get(worker);
            assertTrue(registered != null);
            engine.cancel(worker);
            engine.cancel(worker);
            assertTrue(registered.isCancelled());
            assertTrue(active(engine).get(worker) == registered);
            release.countDown();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertCancelled(failure.get());
            assertTrue(connection.disconnected);
            assertEquals(0, connection.output.size());
            assertTrue(active(engine).isEmpty());
        } finally {
            release.countDown();
            engine.cancel(worker);
            worker.join(5000);
        }
    }

    @Test
    public void cancellationDuringReadDisconnectsAndLeavesNoWorkerRegistration() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnectSignal = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                awaitIgnoringInterrupts(disconnectSignal);
                throw new IOException("Closed");
            }
        }) {
            @Override public void disconnect() {
                super.disconnect();
                disconnectSignal.countDown();
            }
        };
        JsonApiEngine engine = new JsonApiEngine(config(Config.PROTOCOL_OPENAI_COMPATIBLE, true),
                http(url -> connection));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { engine.translate("hello"); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            engine.cancel(worker);
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertCancelled(failure.get());
            assertTrue(connection.disconnected);
            assertTrue(active(engine).isEmpty());
        } finally {
            engine.cancel(worker);
            worker.join(5000);
        }
    }

    private static void assertCancelled(Throwable failure) {
        assertTrue(String.valueOf(failure), failure instanceof TranslationException);
        assertEquals(TranslationException.Kind.CANCELLED, ((TranslationException) failure).kind());
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) throws IOException {
        while (true) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Test latch timed out");
                }
                return;
            } catch (InterruptedException ignored) {
                // Deliberately clear interruption: the request handle must still cancel I/O.
            }
        }
    }
}
