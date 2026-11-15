package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class HttpRequestTest {
    private static final String CHAT = Config.PROTOCOL_OPENAI_COMPATIBLE;
    private static final String ANTHROPIC = Config.PROTOCOL_ANTHROPIC_MESSAGES;

    private static class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InputStream input;
        boolean disconnected;
        boolean outputOpened;
        int status = 200;

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
        @Override public OutputStream getOutputStream() {
            outputOpened = true;
            return output;
        }
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Http.Response post(Http http, Http.Request request, String protocol,
                                      boolean streaming) throws TranslationException {
        return http.postJson("https://fake.invalid/v1", "{\"text\":\"你好😀\"}",
                new Http.Headers(), request, Protocols.createDecoder(protocol, streaming));
    }

    private static void assertCancelled(Action action) throws Exception {
        try {
            action.run();
            fail("Expected cancellation");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.CANCELLED, e.kind());
        }
    }

    private interface Action { void run() throws Exception; }

    @Test
    public void cancelledBeforePostNeverOpensConnection() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        Http http = new Http(1000, url -> { opens.incrementAndGet(); throw new IOException(); });
        Http.Request request = new Http.Request();
        request.cancel();
        assertCancelled(() -> post(http, request, CHAT, true));
        assertEquals(0, opens.get());
    }

    @Test
    public void cancellationInFactoryAttachGapDisconnectsWithoutWrites() throws Exception {
        Http.Request request = new Http.Request();
        FakeConnection connection = new FakeConnection(bytes("data: [DONE]\n\n"));
        Http http = new Http(1000, url -> { request.cancel(); return connection; });
        assertCancelled(() -> post(http, request, CHAT, true));
        assertTrue(connection.disconnected);
        assertFalse(connection.outputOpened);
    }

    @Test
    public void interruptedBeforePostNeverOpensConnection() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        Http http = new Http(1000, url -> { opens.incrementAndGet(); throw new IOException(); });
        Thread.currentThread().interrupt();
        try {
            assertCancelled(() -> post(http, new Http.Request(), CHAT, true));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, opens.get());
    }

    @Test
    public void cancellationAfterOpeningOutputPreventsWrite() throws Exception {
        Http.Request request = new Http.Request();
        FakeConnection connection = new FakeConnection(bytes("")) {
            @Override public OutputStream getOutputStream() {
                request.cancel();
                return super.getOutputStream();
            }
        };
        assertCancelled(() -> post(new Http(1000, url -> connection), request, CHAT, true));
        assertEquals(0, connection.output.size());
        assertTrue(connection.disconnected);
    }

    @Test
    public void bothProtocolsStopAtCompleteEventWithoutReadAhead() throws Exception {
        for (String ending : new String[]{"\n", "\r", "\r\n"}) {
            for (String protocol : new String[]{CHAT, ANTHROPIC}) {
                String content = CHAT.equals(protocol)
                        ? "data: {\"choices\":[{\"delta\":{\"content\":\"你好😀\"}}]}"
                        : "event: content_block_delta" + ending
                            + "data: {\"type\":\"content_block_delta\","
                            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"你好😀\"}}";
                String terminal = CHAT.equals(protocol) ? "data: [DONE]"
                        : "event: message_stop" + ending + "data: {\"type\":\"message_stop\"}";
                String stream = content + ending + ending + terminal + ending + ending;
                // CR completes the terminal blank line; its optional LF is not needed.
                String required = ending.equals("\r\n")
                        ? stream.substring(0, stream.length() - 1) : stream;
                byte[] prefix = required.getBytes(StandardCharsets.UTF_8);
                InputStream input = new InputStream() {
                    int at;
                    @Override public int read() {
                        if (at == prefix.length) {
                            throw new AssertionError("Read beyond terminal event");
                        }
                        return prefix[at++] & 255;
                    }
                    @Override public void close() {
                        throw new AssertionError("Stream close could drain trailing keep-alives");
                    }
                };
                FakeConnection connection = new FakeConnection(input);
                Http.Response response = post(new Http(1000, url -> connection),
                        new Http.Request(), protocol, true);
                assertEquals("你好😀", response.body);
                assertTrue(connection.disconnected);
                assertEquals("text/event-stream", connection.getRequestProperty("Accept"));
                assertFalse(connection.getInstanceFollowRedirects());
                assertEquals("{\"text\":\"你好😀\"}", connection.output.toString("UTF-8"));
            }
        }
    }

    @Test
    public void nonSuccessResponsesStayRawEvenWhenStreamingWasSelected() throws Exception {
        for (int status : new int[]{302, 401, 429, 500}) {
            FakeConnection connection = new FakeConnection(bytes("not SSE: secret error"));
            connection.status = status;
            Http.Response response = post(new Http(1000, url -> connection),
                    new Http.Request(), ANTHROPIC, true);
            assertEquals(status, response.status);
            assertEquals("not SSE: secret error", response.body);
            assertFalse(response.isSuccess());
        }
    }

    @Test
    public void disconnectUnblocksReadAndReportsCancellationNotNetwork() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnectSignal = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                awaitUninterruptibly(disconnectSignal);
                throw new IOException("Disconnected");
            }
        };
        FakeConnection connection = new FakeConnection(blocked) {
            @Override public void disconnect() {
                super.disconnect();
                disconnectSignal.countDown();
            }
        };
        Http.Request request = new Http.Request();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                post(new Http(1000, url -> connection), request, CHAT, true);
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            request.cancel();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof TranslationException);
            assertEquals(TranslationException.Kind.CANCELLED,
                    ((TranslationException) failure.get()).kind());
        } finally {
            request.cancel();
            worker.interrupt();
            worker.join(5000);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) throws IOException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Test latch timed out");
                    }
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    public void byteLimitCoversStreamingJsonAndHttpErrorsDuringRead() throws Exception {
        for (boolean streaming : new boolean[]{true, false}) {
            for (int status : new int[]{200, 500}) {
                AtomicInteger reads = new AtomicInteger();
                InputStream unbounded = new InputStream() {
                    @Override public int read() {
                        // Three-byte UTF-8 text reaches the byte limit before the char cap.
                        int at = reads.getAndIncrement() % 3;
                        return at == 0 ? 0xe4 : at == 1 ? 0xbd : 0xa0;
                    }
                };
                FakeConnection connection = new FakeConnection(unbounded);
                connection.status = status;
                try {
                    post(new Http(1000, url -> connection), new Http.Request(), CHAT, streaming);
                    fail("Expected response byte limit");
                } catch (TranslationException e) {
                    assertEquals(TranslationException.Kind.PARSE, e.kind());
                    assertTrue(e.getMessage().contains("byte limit"));
                }
                assertEquals(Http.MAX_RESPONSE_BYTES + 1, reads.get());
            }
        }
    }

    @Test
    public void asciiSingleLineHitsBoundDuringReadingAndExactLimitIsAccepted() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        FakeConnection huge = new FakeConnection(new InputStream() {
            @Override public int read() { reads.incrementAndGet(); return 'x'; }
        });
        try {
            post(new Http(1000, url -> huge), new Http.Request(), CHAT, true);
            fail("Expected bounded SSE line");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
        assertEquals(Http.MAX_RESPONSE_BYTES + 1, reads.get());
        InputStream exact = new InputStream() {
            int remaining = Http.MAX_RESPONSE_BYTES;
            @Override public int read() { return remaining-- > 0 ? 'x' : -1; }
        };
        FakeConnection error = new FakeConnection(exact);
        error.status = 500;
        Http.Response response = post(new Http(1000, url -> error), new Http.Request(), CHAT, true);
        assertEquals(Http.MAX_RESPONSE_BYTES, response.body.length());
    }

    @Test
    public void invalidUtf8NeverBecomesReplacementText() throws Exception {
        for (byte[] malformed : new byte[][]{{(byte) 0xc0, (byte) 0xaf},
                {(byte) 0xe4}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}}) {
            FakeConnection connection = new FakeConnection(new ByteArrayInputStream(malformed));
            try {
                post(new Http(1000, url -> connection), new Http.Request(), CHAT, false);
                fail("Expected invalid UTF-8");
            } catch (TranslationException e) {
                assertEquals(TranslationException.Kind.PARSE, e.kind());
                assertTrue(e.getMessage().contains("UTF-8"));
            }
        }
    }

    @Test
    public void parseAndTimeoutClassificationArePreserved() throws Exception {
        FakeConnection invalid = new FakeConnection(bytes("data: not-json\n\n"));
        try {
            post(new Http(1000, url -> invalid), new Http.Request(), CHAT, true);
            fail("Expected parse failure");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.PARSE, e.kind());
        }
        Http timeout = new Http(1000, url -> { throw new SocketTimeoutException("timeout"); });
        try {
            post(timeout, new Http.Request(), CHAT, true);
            fail("Expected timeout");
        } catch (TranslationException e) {
            assertEquals(TranslationException.Kind.TIMEOUT, e.kind());
        }
    }
}
