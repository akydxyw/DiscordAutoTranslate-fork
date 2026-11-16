package io.github.wxmyyds.discordautotranslate.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.Http;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.Protocols;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLHandshakeException;

/** Issue 24: real Call -> engine -> HTTP -> strict decoder, with no live service or UI. */
public class ApiTestDiagnosticsTest {
    private static final String CHAT = Config.PROTOCOL_OPENAI_COMPATIBLE;
    private static final String RESPONSES = Config.PROTOCOL_OPENAI_RESPONSES;
    private static final String ANTHROPIC = Config.PROTOCOL_ANTHROPIC_MESSAGES;
    private static final String[] PROTOCOLS = {CHAT, RESPONSES, ANTHROPIC};
    private static final ApiTester.TestKind[] NETWORK_TESTS = {
            ApiTester.TestKind.CONNECTION, ApiTester.TestKind.TRANSLATION};
    private static final String PRIVATE_INPUT = "private DM \"quote\"\\\nnext";
    private static final String PRIVATE_BODY = "private response body 正文";
    private static final String PRIVATE_URL = "https://private.invalid/private-token/v1";
    private static final String PRIVATE_KEY = "private-api-key";
    private static final String PRIVATE_HEADERS = "X-Private-Tenant: private-header-value";
    private static final String PRIVATE_MESSAGE = "private original exception message";
    private final List<Entry> entries = new ArrayList<>();

    @Before
    public void attachCapturedOutputWithSavedLoggingDisabled() {
        ProjectLog.configure(config(CHAT, false, false));
        ProjectLog.attach((priority, tag, message) -> entries.add(new Entry(priority, tag, message)));
    }

    @After
    public void detachAndDisableLogging() {
        ProjectLog.configure(config(CHAT, false, false));
        ProjectLog.attach(null);
    }

    @Test
    public void offlineAndNetworkConfigurationFailuresUseTheSameReasonWithoutIo() throws Exception {
        Config.Snapshot[] invalid = {
                Config.invalidSnapshot(),
                Config.read(new MemoryPrefs().put(Config.KEY_PROTOCOL, "invalid-private-protocol")),
                Config.read(new MemoryPrefs().put(Config.KEY_API_URL, "")),
                Config.read(new MemoryPrefs().put(Config.KEY_API_URL, "invalid-private-url")),
                Config.read(new MemoryPrefs().put(Config.KEY_API_URL, "ftp://private.invalid")),
                Config.read(new MemoryPrefs().put(Config.KEY_EXTRA_HEADERS, "private invalid header")),
                Config.read(new MemoryPrefs().put(Config.KEY_MODEL, "")),
                Config.read(new MemoryPrefs().put(Config.KEY_SYSTEM_PROMPT, ""))
        };
        for (Config.Snapshot cfg : invalid) {
            assertFailure(ApiTester.selfCheck(cfg), ApiTester.TestKind.CONFIGURATION,
                    TranslationException.Kind.CONFIG, TranslationException.Reason.CONFIG_INVALID,
                    0, "TranslationException");
            Harness harness = new Harness(cfg, url -> {
                throw new AssertionError("Configuration failure must not open HTTP");
            });
            for (ApiTester.TestKind kind : NETWORK_TESTS) {
                assertFailure(harness.call(kind, PRIVATE_INPUT), kind,
                        TranslationException.Kind.CONFIG, TranslationException.Reason.CONFIG_INVALID,
                        0, "TranslationException");
                harness.assertReleased(0);
            }
        }
        assertTrue(entries.isEmpty());
    }

    @Test
    public void malformedHttpUrlsFailOfflineAndActualConfigurationWithoutOpeningHttp() throws Exception {
        for (String protocol : PROTOCOLS) {
            String[] invalidUrls = {"https://[bad", "https://[invalid-host/v1",
                    "https:///v1", "https://private.invalid:65536/v1",
                    "https://private.invalid:-2/v1", "https://private.invalid:abc/v1",
                    "https://private.invalid/private path/v1"};
            String[] types = {"URISyntaxException", "URISyntaxException",
                    "IllegalArgumentException", "IllegalArgumentException",
                    "IllegalArgumentException", "IllegalArgumentException", "URISyntaxException"};
            for (int i = 0; i < invalidUrls.length; i++) {
                Config.Snapshot cfg = Config.read(new MemoryPrefs()
                        .put(Config.KEY_API_URL, invalidUrls[i])
                        .put(Config.KEY_PROTOCOL, protocol).put(Config.KEY_STREAMING, false));
                assertFailure(ApiTester.selfCheck(cfg), ApiTester.TestKind.CONFIGURATION,
                        TranslationException.Kind.CONFIG, TranslationException.Reason.CONFIG_INVALID,
                        0, types[i]);
                Harness harness = new Harness(cfg, url -> {
                    throw new AssertionError("Malformed URL reached connection factory");
                });
                for (ApiTester.TestKind kind : NETWORK_TESTS) {
                    assertFailure(harness.call(kind, PRIVATE_INPUT), kind,
                            TranslationException.Kind.CONFIG, TranslationException.Reason.CONFIG_INVALID,
                            0, types[i]);
                    harness.assertReleased(0);
                }
            }
        }
    }

    @Test
    public void allHttpErrorsKeepActualStatusAndPreserveRawErrorBody() throws Exception {
        for (String protocol : PROTOCOLS) {
            for (boolean streaming : new boolean[]{false, true}) {
                for (int status : new int[]{400, 401, 403, 404, 429, 500}) {
                    for (ApiTester.TestKind kind : NETWORK_TESTS) {
                        FakeConnection connection = new FakeConnection(PRIVATE_BODY);
                        connection.status = status;
                        Harness harness = new Harness(config(protocol, streaming, false),
                                url -> connection);
                        ApiTester.Result result = harness.call(kind, PRIVATE_INPUT);
                        assertFailure(result, kind,
                                status == 429 ? TranslationException.Kind.RATE_LIMIT
                                        : TranslationException.Kind.HTTP,
                                status == 429 ? TranslationException.Reason.RATE_LIMITED
                                        : TranslationException.Reason.HTTP_ERROR,
                                status, "UpstreamHttpException");
                        assertEquals(PRIVATE_BODY, result.failure.upstreamErrorBody);
                        harness.assertReleased(1);
                        assertTrue(connection.disconnected);
                    }
                }
            }
        }
    }

    @Test
    public void dnsTlsAndConnectTimeoutHaveNoStatusAndRetainOnlyExceptionType() throws Exception {
        IOException[] errors = {new UnknownHostException(PRIVATE_MESSAGE),
                new SSLHandshakeException(PRIVATE_MESSAGE), new SocketTimeoutException(PRIVATE_MESSAGE)};
        for (IOException error : errors) {
            for (ApiTester.TestKind kind : NETWORK_TESTS) {
                Harness harness = new Harness(config(CHAT, false, false), url -> { throw error; });
                boolean timeout = error instanceof SocketTimeoutException;
                assertFailure(harness.call(kind, PRIVATE_INPUT), kind,
                        timeout ? TranslationException.Kind.TIMEOUT : TranslationException.Kind.NETWORK,
                        timeout ? TranslationException.Reason.REQUEST_TIMEOUT
                                : TranslationException.Reason.NETWORK_FAILURE,
                        0, error.getClass().getSimpleName());
                harness.assertReleased(1);
            }
        }
    }

    @Test
    public void readTimeoutAfterHttp200RejectsPartialTextWithStatusForEveryProtocol() throws Exception {
        for (String protocol : PROTOCOLS) {
            for (ApiTester.TestKind kind : NETWORK_TESTS) {
                byte[] prefix = delta(protocol, PRIVATE_BODY).getBytes(StandardCharsets.UTF_8);
                FakeConnection connection = new FakeConnection(new InputStream() {
                    private int offset;
                    @Override public int read() throws IOException {
                        if (offset < prefix.length) return prefix[offset++] & 255;
                        throw new SocketTimeoutException(PRIVATE_MESSAGE);
                    }
                });
                Harness harness = new Harness(config(protocol, true, false), url -> connection);
                assertFailure(harness.call(kind, PRIVATE_INPUT), kind,
                        TranslationException.Kind.TIMEOUT, TranslationException.Reason.REQUEST_TIMEOUT,
                        200, "SocketTimeoutException");
                harness.assertReleased(1);
                assertTrue(connection.disconnected);
            }
        }
    }

    @Test
    public void unknownRuntimeBeforeAndAfterStatusReturnsTypeNotMessageOrCause() throws Exception {
        for (ApiTester.TestKind kind : NETWORK_TESTS) {
            Harness before = new Harness(config(CHAT, false, false), url -> {
                throw new IllegalStateException(PRIVATE_MESSAGE);
            });
            assertFailure(before.call(kind, PRIVATE_INPUT), kind, TranslationException.Kind.UNKNOWN,
                    TranslationException.Reason.UNKNOWN_FAILURE, 0, "IllegalStateException");
            before.assertReleased(1);
            FakeConnection connection = new FakeConnection(new InputStream() {
                @Override public int read() { throw new IllegalArgumentException(PRIVATE_MESSAGE); }
            });
            Harness after = new Harness(config(CHAT, false, false), url -> connection);
            assertFailure(after.call(kind, PRIVATE_INPUT), kind, TranslationException.Kind.UNKNOWN,
                    TranslationException.Reason.UNKNOWN_FAILURE, 200, "IllegalArgumentException");
            after.assertReleased(1);
            assertTrue(connection.disconnected);
        }
    }

    @Test
    public void bothKindsDecodeAllThreeProtocolsInBothModesButOnlyTranslationReturnsText()
            throws Exception {
        for (String protocol : PROTOCOLS) {
            for (boolean streaming : new boolean[]{false, true}) {
                List<FakeConnection> connections = new ArrayList<>();
                Config.Snapshot cfg = config(protocol, streaming, false);
                Harness harness = new Harness(cfg, url -> {
                    assertEquals(PRIVATE_URL + endpointSuffix(protocol), url.toString());
                    FakeConnection connection = new FakeConnection(response(protocol, streaming, " 你好 "));
                    connections.add(connection);
                    return connection;
                });
                // Same engine and repeated content: every Call must still open a fresh request.
                for (String input : new String[]{PRIVATE_INPUT, null, " \n\t"}) {
                    assertSuccess(harness.call(ApiTester.TestKind.CONNECTION, input),
                            ApiTester.TestKind.CONNECTION, null);
                }
                assertSuccess(harness.call(ApiTester.TestKind.TRANSLATION, ApiTester.DEFAULT_TEST_TEXT),
                        ApiTester.TestKind.TRANSLATION, "你好");
                harness.assertReleased(4);
                JSONObject expected = new JSONObject(connections.get(0).output.toString("UTF-8"));
                assertRequest(expected, cfg, ApiTester.DEFAULT_TEST_TEXT);
                for (FakeConnection connection : connections) {
                    JSONObject actual = new JSONObject(connection.output.toString("UTF-8"));
                    assertEquals(expected.toString(), actual.toString());
                    assertRequest(actual, cfg, ApiTester.DEFAULT_TEST_TEXT);
                    assertEquals(streaming ? "text/event-stream" : "application/json",
                            connection.getRequestProperty("Accept"));
                    assertFalse(connection.getUseCaches());
                    assertFalse(connection.getInstanceFollowRedirects());
                    assertEquals("POST", connection.getRequestMethod());
                    assertEquals(1, connection.outputOpens);
                    assertTrue(connection.disconnected);
                }
            }
        }
    }

    @Test
    public void connectionAcceptsWrapperPollutionAndPlaceholderButTranslationRejectsSameResponse()
            throws Exception {
        for (String protocol : PROTOCOLS) {
            for (boolean streaming : new boolean[]{false, true}) {
                for (String text : new String[]{"<translate>你好</translate>", "前言 <translate>你好",
                        "你好</translate>", "XXXX", "<s1>你好</s1>"}) {
                    List<FakeConnection> connections = new ArrayList<>();
                    Config.Snapshot cfg = config(protocol, streaming, false);
                    Harness harness = new Harness(cfg, url -> {
                        FakeConnection connection = new FakeConnection(response(protocol, streaming, text));
                        connections.add(connection);
                        return connection;
                    });
                    assertSuccess(harness.call(ApiTester.TestKind.CONNECTION, PRIVATE_INPUT),
                            ApiTester.TestKind.CONNECTION, null);
                    assertFailure(harness.call(ApiTester.TestKind.TRANSLATION, ApiTester.DEFAULT_TEST_TEXT),
                            ApiTester.TestKind.TRANSLATION, TranslationException.Kind.PARSE,
                            TranslationException.Reason.TRANSLATION_INVALID, 200, "TranslationException");
                    harness.assertReleased(2);
                    JSONObject connectionBody = new JSONObject(connections.get(0).output.toString("UTF-8"));
                    JSONObject translationBody = new JSONObject(connections.get(1).output.toString("UTF-8"));
                    assertEquals(connectionBody.toString(), translationBody.toString());
                    assertRequest(connectionBody, cfg, ApiTester.DEFAULT_TEST_TEXT);
                    for (FakeConnection connection : connections) {
                        assertEquals(1, connection.outputOpens);
                        assertFalse(connection.getUseCaches());
                        assertFalse(connection.getInstanceFollowRedirects());
                        assertTrue(connection.disconnected);
                    }
                }
            }
        }
    }

    @Test
    public void connectionNeverBypassesJsonStructureModeOrCompletionValidation() throws Exception {
        for (String protocol : PROTOCOLS) {
            for (boolean streaming : new boolean[]{false, true}) {
                assertResponseFailure(protocol, streaming, streaming ? "data: {\n\n" : "{",
                        TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INVALID,
                        "JSONException");
                for (Object value : new Object[]{42, true, new JSONObject(), new org.json.JSONArray()}) {
                    assertResponseFailure(protocol, streaming, badTextType(protocol, streaming, value),
                            TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INVALID);
                }
                // Chat SSE null content is explicitly optional, unlike required JSON/text fields.
                if (!streaming || !CHAT.equals(protocol)) {
                    assertResponseFailure(protocol, streaming,
                            badTextType(protocol, streaming, JSONObject.NULL),
                            TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INVALID);
                }
                assertResponseFailure(protocol, streaming, response(protocol, !streaming, PRIVATE_BODY),
                        TranslationException.Kind.PARSE, streaming
                                ? TranslationException.Reason.RESPONSE_INCOMPLETE
                                : TranslationException.Reason.RESPONSE_INVALID,
                        streaming ? "TranslationException" : "JSONException");
                assertResponseFailure(protocol, streaming, stopped(protocol, streaming),
                        TranslationException.Kind.PARSE, TranslationException.Reason.GENERATION_STOPPED);
                assertResponseFailure(protocol, streaming, errorResponse(protocol, streaming),
                        TranslationException.Kind.PARSE, !streaming && RESPONSES.equals(protocol)
                                ? TranslationException.Reason.GENERATION_STOPPED
                                : TranslationException.Reason.RESPONSE_INVALID);
                assertResponseFailure(protocol, streaming, "",
                        TranslationException.Kind.PARSE, streaming
                                ? TranslationException.Reason.RESPONSE_INCOMPLETE
                                : TranslationException.Reason.RESPONSE_INVALID);
                assertResponseFailure(protocol, streaming, response(protocol, streaming, " \n\t "),
                        TranslationException.Kind.EMPTY, TranslationException.Reason.EMPTY_TEXT);
                assertResponseFailure(protocol, streaming, response(protocol, streaming, ""),
                        TranslationException.Kind.EMPTY, TranslationException.Reason.EMPTY_TEXT);
            }
            assertResponseFailure(protocol, true, delta(protocol, PRIVATE_BODY),
                    TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INCOMPLETE);
            String completed = terminal(protocol, PRIVATE_BODY);
            assertResponseFailure(protocol, true, delta(protocol, PRIVATE_BODY)
                            + completed.substring(0, completed.length() - 1),
                    TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INCOMPLETE);
        }
    }

    @Test
    public void emptyTranslationInputHasStatusZeroAndDoesNotOpenHttp() throws Exception {
        Harness harness = new Harness(config(CHAT, false, false), url -> {
            throw new AssertionError("Empty input opened HTTP");
        });
        for (String input : new String[]{null, "", " \n\t "}) {
            assertFailure(harness.call(ApiTester.TestKind.TRANSLATION, input),
                    ApiTester.TestKind.TRANSLATION, TranslationException.Kind.EMPTY,
                    TranslationException.Reason.EMPTY_TEXT, 0, "TranslationException");
            harness.assertReleased(0);
        }
    }

    @Test
    public void savedLoggingGateControlsOfflineStartHttpAndCompletionAndNeverLogsContent()
            throws Exception {
        for (boolean enabled : new boolean[]{true, false, true}) {
            Config.Snapshot cfg = config(CHAT, false, enabled);
            ProjectLog.configure(cfg);
            entries.clear();
            assertSuccess(ApiTester.selfCheck(cfg), ApiTester.TestKind.CONFIGURATION, null);
            assertEntries(enabled, "api.test.start test=CONFIGURATION protocol=" + CHAT + " streaming=false",
                    "api.test.complete test=CONFIGURATION outcome=SUCCESS");
            for (String protocol : PROTOCOLS) {
                for (boolean streaming : new boolean[]{false, true}) {
                    cfg = config(protocol, streaming, enabled);
                    // Only an explicitly saved snapshot configures the real global log gate.
                    ProjectLog.configure(cfg);
                    entries.clear();
                    List<FakeConnection> connections = new ArrayList<>();
                    Harness harness = new Harness(cfg, url -> {
                        FakeConnection connection = new FakeConnection(response(protocol, streaming, PRIVATE_BODY));
                        connections.add(connection);
                        return connection;
                    });
                    assertSuccess(harness.call(ApiTester.TestKind.TRANSLATION, PRIVATE_INPUT),
                            ApiTester.TestKind.TRANSLATION, PRIVATE_BODY);
                    FakeConnection connection = connections.get(0);
                    assertRequest(new JSONObject(connection.output.toString("UTF-8")), cfg, PRIVATE_INPUT);
                    assertEquals(ANTHROPIC.equals(protocol) ? PRIVATE_KEY : "Bearer " + PRIVATE_KEY,
                            connection.getRequestProperty(ANTHROPIC.equals(protocol) ? "x-api-key" : "Authorization"));
                    assertEquals("private-header-value", connection.getRequestProperty("X-Private-Tenant"));
                    assertEntries(enabled, start(ApiTester.TestKind.TRANSLATION, protocol, streaming),
                            "api.http.response httpStatus=200",
                            "api.test.complete test=TRANSLATION outcome=SUCCESS");
                    harness.assertReleased(1);
                    entries.clear();
                    assertSuccess(harness.call(ApiTester.TestKind.CONNECTION, PRIVATE_INPUT),
                            ApiTester.TestKind.CONNECTION, null);
                    assertEntries(enabled, start(ApiTester.TestKind.CONNECTION, protocol, streaming),
                            "api.http.response httpStatus=200",
                            "api.test.complete test=CONNECTION outcome=SUCCESS");
                    harness.assertReleased(2);
                }
            }
        }
    }

    @Test
    public void unsavedCallLoggingFlagCannotOverrideTheSavedProjectLogGate() throws Exception {
        for (boolean savedEnabled : new boolean[]{false, true}) {
            ProjectLog.configure(config(CHAT, false, savedEnabled));
            entries.clear();
            // The settings form snapshot may disagree with the saved logger configuration.
            Config.Snapshot unsaved = config(CHAT, false, !savedEnabled);
            FakeConnection connection = new FakeConnection(response(CHAT, false, PRIVATE_BODY));
            Harness harness = new Harness(unsaved, url -> connection);
            assertSuccess(harness.call(ApiTester.TestKind.CONNECTION, PRIVATE_INPUT),
                    ApiTester.TestKind.CONNECTION, null);
            assertEntries(savedEnabled, start(ApiTester.TestKind.CONNECTION, CHAT, false),
                    "api.http.response httpStatus=200", "api.test.complete test=CONNECTION outcome=SUCCESS");
            harness.assertReleased(1);
        }
    }

    @Test
    public void capturedFailureLogsIncludeExactUpstreamBodyWhenEnabled()
            throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            Config.Snapshot cfg = config(CHAT, false, enabled);
            ProjectLog.configure(cfg);
            for (ApiTester.TestKind kind : NETWORK_TESTS) {
                entries.clear();
                Harness network = new Harness(cfg, url -> { throw new UnknownHostException(PRIVATE_MESSAGE); });
                ApiTester.Result result = network.call(kind, PRIVATE_INPUT);
                assertFailure(result, kind, TranslationException.Kind.NETWORK,
                        TranslationException.Reason.NETWORK_FAILURE, 0, "UnknownHostException");
                assertEntries(enabled, start(kind, CHAT, false), completion(result));
                network.assertReleased(1);

                entries.clear();
                Harness unknown = new Harness(cfg, url -> { throw new IllegalStateException(PRIVATE_MESSAGE); });
                result = unknown.call(kind, PRIVATE_INPUT);
                assertFailure(result, kind, TranslationException.Kind.UNKNOWN,
                        TranslationException.Reason.UNKNOWN_FAILURE, 0, "IllegalStateException");
                assertEntries(enabled, start(kind, CHAT, false), completion(result));
                unknown.assertReleased(1);

                for (int status : new int[]{200, 401, 429, 500}) {
                    entries.clear();
                    FakeConnection connection = new FakeConnection(status == 200
                            ? "{\"error\":{\"message\":" + JSONObject.quote(PRIVATE_BODY) + "}}"
                            : PRIVATE_BODY);
                    connection.status = status;
                    Harness http = new Harness(cfg, url -> connection);
                    result = http.call(kind, PRIVATE_INPUT);
                    assertFailure(result, kind, status == 200 ? TranslationException.Kind.PARSE
                                    : status == 429 ? TranslationException.Kind.RATE_LIMIT : TranslationException.Kind.HTTP,
                            status == 200 ? TranslationException.Reason.RESPONSE_INVALID
                                    : status == 429 ? TranslationException.Reason.RATE_LIMITED
                                    : TranslationException.Reason.HTTP_ERROR,
                            status, status == 200 ? "TranslationException" : "UpstreamHttpException");
                    if (status == 200) {
                        assertEntries(enabled, start(kind, CHAT, false),
                                "api.http.response httpStatus=200", completion(result));
                    } else {
                        assertEntries(enabled, start(kind, CHAT, false),
                                "api.http.response httpStatus=" + status,
                                "api.upstream.error httpStatus=" + status + " body=" + PRIVATE_BODY,
                                completion(result));
                    }
                    http.assertReleased(1);
                }
            }
            entries.clear();
            ApiTester.Result offline = ApiTester.selfCheck(Config.invalidSnapshot());
            assertFailure(offline, ApiTester.TestKind.CONFIGURATION, TranslationException.Kind.CONFIG,
                    TranslationException.Reason.CONFIG_INVALID, 0, "TranslationException");
            assertEntries(enabled, "api.test.start test=CONFIGURATION protocol=" + CHAT + " streaming=true",
                    completion(offline));
        }
        // A saved invalid configuration is not allowed to leave an earlier enabled gate open.
        ProjectLog.configure(config(CHAT, false, true));
        ProjectLog.configure(Config.read(new MemoryPrefs().put(Config.KEY_LOGGING_ENABLED, true)
                .put(Config.KEY_PROTOCOL, "private invalid protocol")));
        entries.clear();
        ApiTester.selfCheck(Config.invalidSnapshot());
        assertTrue(entries.isEmpty());
    }

    @Test
    public void decoderValidationEmptyAndTimeoutFailuresKeepHttp200InCapturedSafeLogs()
            throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            for (String protocol : PROTOCOLS) {
                for (boolean streaming : new boolean[]{false, true}) {
                    Config.Snapshot cfg = config(protocol, streaming, enabled);
                    ProjectLog.configure(cfg);
                    for (String text : new String[]{"", "XXXX", "<translate>" + PRIVATE_BODY,
                            "</translate>" + PRIVATE_BODY}) {
                        for (ApiTester.TestKind kind : NETWORK_TESTS) {
                            entries.clear();
                            FakeConnection connection = new FakeConnection(response(protocol, streaming, text));
                            Harness harness = new Harness(cfg, url -> connection);
                            ApiTester.Result result = harness.call(kind, PRIVATE_INPUT);
                            if (kind == ApiTester.TestKind.CONNECTION && !text.isEmpty()) {
                                assertSuccess(result, kind, null);
                                assertEntries(enabled, start(kind, protocol, streaming),
                                        "api.http.response httpStatus=200",
                                        "api.test.complete test=CONNECTION outcome=SUCCESS");
                            } else {
                                assertFailure(result, kind, text.isEmpty() ? TranslationException.Kind.EMPTY
                                                : TranslationException.Kind.PARSE,
                                        text.isEmpty() ? TranslationException.Reason.EMPTY_TEXT
                                                : TranslationException.Reason.TRANSLATION_INVALID,
                                        200, "TranslationException");
                                assertEntries(enabled, start(kind, protocol, streaming),
                                        "api.http.response httpStatus=200", completion(result));
                            }
                            harness.assertReleased(1);
                        }
                    }
                    for (ApiTester.TestKind kind : NETWORK_TESTS) {
                        for (String body : new String[]{stopped(protocol, streaming),
                                streaming ? delta(protocol, PRIVATE_BODY) : "{"}) {
                            entries.clear();
                            FakeConnection connection = new FakeConnection(body);
                            Harness harness = new Harness(cfg, url -> connection);
                            ApiTester.Result result = harness.call(kind, PRIVATE_INPUT);
                            boolean generationStopped = body.equals(stopped(protocol, streaming));
                            assertFailure(result, kind, TranslationException.Kind.PARSE,
                                    generationStopped ? TranslationException.Reason.GENERATION_STOPPED
                                            : streaming ? TranslationException.Reason.RESPONSE_INCOMPLETE
                                            : TranslationException.Reason.RESPONSE_INVALID,
                                    200, generationStopped || streaming ? "TranslationException" : "JSONException");
                            assertEntries(enabled, start(kind, protocol, streaming),
                                    "api.http.response httpStatus=200", completion(result));
                            harness.assertReleased(1);
                        }
                        entries.clear();
                        FakeConnection connection = new FakeConnection(new InputStream() {
                            @Override public int read() throws IOException {
                                throw new SocketTimeoutException(PRIVATE_MESSAGE);
                            }
                        });
                        Harness harness = new Harness(cfg, url -> connection);
                        ApiTester.Result result = harness.call(kind, PRIVATE_INPUT);
                        assertFailure(result, kind, TranslationException.Kind.TIMEOUT,
                                TranslationException.Reason.REQUEST_TIMEOUT, 200, "SocketTimeoutException");
                        assertEntries(enabled, start(kind, protocol, streaming),
                                "api.http.response httpStatus=200", completion(result));
                        harness.assertReleased(1);
                    }
                }
            }
        }
    }

    @Test
    public void lengthRatioFailureDoesNotLeakInputResponseCountsOrHashesInResultOrLogs()
            throws Exception {
        String input = PRIVATE_INPUT.repeat(4);
        for (boolean enabled : new boolean[]{false, true}) {
            Config.Snapshot cfg = config(CHAT, false, enabled);
            ProjectLog.configure(cfg);
            entries.clear();
            FakeConnection connection = new FakeConnection(response(CHAT, false, "译"));
            Harness harness = new Harness(cfg, url -> connection);
            ApiTester.Result result = harness.call(ApiTester.TestKind.TRANSLATION, input);
            assertFailure(result, ApiTester.TestKind.TRANSLATION, TranslationException.Kind.PARSE,
                    TranslationException.Reason.TRANSLATION_INVALID, 200, "TranslationException");
            assertEntries(enabled, start(ApiTester.TestKind.TRANSLATION, CHAT, false),
                    "api.http.response httpStatus=200", completion(result));
            for (Entry entry : entries) {
                assertFalse(entry.message.contains(Integer.toString(input.length())));
                assertFalse(entry.message.contains("1/"));
                assertFalse(entry.message.contains(sha256(input)));
                assertFalse(entry.message.contains(Integer.toString(input.hashCode())));
            }
            harness.assertReleased(1);
        }
    }

    @Test
    public void queuedCancellationKeepsItsTestKindAndCannotAcquireIoOrInterruptCaller()
            throws Exception {
        ProjectLog.configure(config(CHAT, false, true));
        for (ApiTester.TestKind kind : NETWORK_TESTS) {
            entries.clear();
            Harness harness = new Harness(config(CHAT, false, true), url -> {
                throw new AssertionError("Queued cancellation opened HTTP");
            });
            ApiTester.Call call = harness.newCall(kind, PRIVATE_INPUT);
            call.cancel();
            ApiTester.Result result = call.call();
            assertFailure(result, kind, TranslationException.Kind.CANCELLED,
                    TranslationException.Reason.CANCELLED, 0, "TranslationException");
            assertEntries(true, completion(result));
            assertFalse(Thread.currentThread().isInterrupted());
            harness.assertReleased(0);
        }
    }

    @Test
    public void connectionCancellationDisconnectsActiveIoAndLateCancelCannotInterruptNextJob()
            throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch disconnectSignal = new CountDownLatch(1);
        FakeConnection connection = new FakeConnection(new InputStream() {
            @Override public int read() throws IOException {
                reading.countDown();
                while (true) {
                    try {
                        if (!disconnectSignal.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Controlled read not released");
                        }
                        throw new IOException(PRIVATE_MESSAGE);
                    } catch (InterruptedException ignored) {
                        // The real request handle, not interruption, must release this read.
                    }
                }
            }
        }) {
            @Override public void disconnect() {
                super.disconnect();
                disconnectSignal.countDown();
            }
        };
        Harness harness = new Harness(config(CHAT, true, false), url -> connection);
        ApiTester.Call call = harness.newCall(ApiTester.TestKind.CONNECTION, PRIVATE_INPUT);
        CountDownLatch nextJob = new CountDownLatch(1);
        CountDownLatch releaseNext = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiTester.Result> future = executor.submit(call);
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            assertEquals(1, harness.active().size());
            call.cancel();
            assertFailure(future.get(2, TimeUnit.SECONDS), ApiTester.TestKind.CONNECTION,
                    TranslationException.Kind.CANCELLED, TranslationException.Reason.CANCELLED,
                    200, "TranslationException");
            harness.assertReleased(1);
            assertTrue(connection.disconnected);
            Field worker = ApiTester.Call.class.getDeclaredField("worker");
            worker.setAccessible(true);
            assertNull(worker.get(call));
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
            releaseNext.countDown();
            assertFalse(next.get(2, TimeUnit.SECONDS));
        } finally {
            disconnectSignal.countDown();
            releaseNext.countDown();
            executor.shutdownNow();
        }
    }

    private static Config.Snapshot config(String protocol, boolean streaming, boolean logging) {
        return Config.read(new MemoryPrefs().put(Config.KEY_API_URL, PRIVATE_URL)
                .put(Config.KEY_API_KEY, PRIVATE_KEY).put(Config.KEY_EXTRA_HEADERS, PRIVATE_HEADERS)
                .put(Config.KEY_PROTOCOL, protocol).put(Config.KEY_STREAMING, streaming)
                .put(Config.KEY_LOGGING_ENABLED, logging));
    }

    private static void assertSuccess(ApiTester.Result result, ApiTester.TestKind kind, String text) {
        assertTrue(result.ok);
        assertEquals(kind, result.testKind);
        assertEquals(text, result.translated);
        assertNull(result.failure);
        assertTrue(result.elapsedMs >= 0);
    }

    private static void assertFailure(ApiTester.Result result, ApiTester.TestKind testKind,
                                      TranslationException.Kind kind, TranslationException.Reason reason,
                                      int status, String exceptionType) throws Exception {
        assertFalse(result.ok);
        assertEquals(testKind, result.testKind);
        assertNull(result.translated);
        assertTrue(result.elapsedMs >= 0);
        assertNotNull(result.failure);
        assertEquals(kind, result.failure.kind);
        assertEquals(reason, result.failure.reason);
        assertEquals(status, result.failure.httpStatus);
        assertEquals(exceptionType, result.failure.exceptionType);
        // Inspect retained state, not toString(): no hidden body/message/cause/Throwable fields.
        Set<String> fields = new HashSet<>();
        for (Field field : ApiTester.Failure.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            assertTrue(Modifier.isFinal(field.getModifiers()));
            field.setAccessible(true);
            Object value = field.get(result.failure);
            assertFalse(value instanceof Throwable);
            assertTrue(value == null || value instanceof Enum<?> || value instanceof Integer
                    || value instanceof String);
            if ("exceptionType".equals(field.getName())) assertEquals(exceptionType, value);
            if ("upstreamErrorBody".equals(field.getName())) {
                String expectedBody = status > 0 && (kind == TranslationException.Kind.HTTP
                        || kind == TranslationException.Kind.RATE_LIMIT) ? PRIVATE_BODY : null;
                assertEquals(expectedBody, value);
            }
            fields.add(field.getName());
        }
        assertEquals(new HashSet<>(Arrays.asList("kind", "reason", "httpStatus", "exceptionType",
                "upstreamErrorBody")), fields);
    }

    private static void assertResponseFailure(String protocol, boolean streaming, String response,
                                              TranslationException.Kind kind,
                                              TranslationException.Reason reason) throws Exception {
        assertResponseFailure(protocol, streaming, response, kind, reason, "TranslationException");
    }

    private static void assertResponseFailure(String protocol, boolean streaming, String response,
                                              TranslationException.Kind kind,
                                              TranslationException.Reason reason, String exceptionType)
            throws Exception {
        for (ApiTester.TestKind testKind : NETWORK_TESTS) {
            FakeConnection connection = new FakeConnection(response);
            Harness harness = new Harness(config(protocol, streaming, false), url -> connection);
            assertFailure(harness.call(testKind, PRIVATE_INPUT), testKind, kind, reason,
                    200, exceptionType);
            harness.assertReleased(1);
            assertTrue(connection.disconnected);
        }
    }

    private static void assertRequest(JSONObject request, Config.Snapshot cfg, String text)
            throws Exception {
        assertEquals(cfg.model, request.getString("model"));
        assertEquals(cfg.streaming, request.getBoolean("stream"));
        String wrapped = Protocols.WRAPPER_OPEN + text + Protocols.WRAPPER_CLOSE;
        String prompt = Config.defaultSystemPromptFor(cfg.targetLang) + "\n\n" + Protocols.WRAPPER_RULE;
        if (RESPONSES.equals(cfg.protocol)) {
            assertEquals(wrapped, request.getString("input"));
            assertEquals(prompt, request.getString("instructions"));
        } else {
            int user = ANTHROPIC.equals(cfg.protocol) ? 0 : 1;
            assertEquals(wrapped, request.getJSONArray("messages").getJSONObject(user).getString("content"));
            assertEquals("user", request.getJSONArray("messages").getJSONObject(user).getString("role"));
            assertEquals(prompt, user == 0 ? request.getString("system")
                    : request.getJSONArray("messages").getJSONObject(0).getString("content"));
        }
    }

    // Response fixtures describe the wire contracts; all interpretation is production code.
    private static String json(String protocol, String text) {
        String quoted = JSONObject.quote(text);
        if (CHAT.equals(protocol)) return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":" + quoted + "}}]}";
        if (ANTHROPIC.equals(protocol)) return "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":" + quoted + "}]}";
        return "{\"status\":\"completed\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":" + quoted + "}]}]}";
    }

    private static String delta(String protocol, String text) {
        String quoted = JSONObject.quote(text);
        if (CHAT.equals(protocol)) return "data: {\"choices\":[{\"delta\":{\"content\":" + quoted + "}}]}\n\n";
        if (ANTHROPIC.equals(protocol)) return "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":" + quoted + "}}\n\n";
        return "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":0,\"output_index\":0,\"content_index\":0,\"item_id\":\"msg_1\",\"delta\":" + quoted + "}\n\n";
    }

    private static String terminal(String protocol, String text) {
        if (CHAT.equals(protocol)) return "data: [DONE]\n\n";
        if (ANTHROPIC.equals(protocol)) return "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        return "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":" + json(protocol, text) + "}\n\n";
    }

    private static String response(String protocol, boolean streaming, String text) {
        return streaming ? delta(protocol, text) + terminal(protocol, text) : json(protocol, text);
    }

    private static String badTextType(String protocol, boolean streaming, Object value) throws Exception {
        if (streaming) {
            JSONObject frame = new JSONObject(delta(protocol, "text").split("data: ", 2)[1].trim());
            if (CHAT.equals(protocol)) frame.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("delta").put("content", value);
            else if (ANTHROPIC.equals(protocol)) frame.getJSONObject("delta").put("text", value);
            else frame.put("delta", value);
            return "data: " + frame + "\n\n" + terminal(protocol, "text");
        }
        JSONObject body = new JSONObject(json(protocol, "text"));
        if (CHAT.equals(protocol)) body.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").put("content", value);
        else if (ANTHROPIC.equals(protocol)) body.getJSONArray("content").getJSONObject(0).put("text", value);
        else body.getJSONArray("output").getJSONObject(0).getJSONArray("content")
                .getJSONObject(0).put("text", value);
        return body.toString();
    }

    private static String stopped(String protocol, boolean streaming) throws Exception {
        if (streaming) {
            String event = CHAT.equals(protocol) ? "data: {\"choices\":[{\"finish_reason\":\"length\"}]}\n\n"
                    : ANTHROPIC.equals(protocol) ? "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n"
                    : "data: {\"type\":\"response.incomplete\",\"sequence_number\":1,\"response\":{\"status\":\"incomplete\"}}\n\n";
            return delta(protocol, PRIVATE_BODY) + event;
        }
        JSONObject body = new JSONObject(json(protocol, PRIVATE_BODY));
        if (CHAT.equals(protocol)) body.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length");
        else body.put(ANTHROPIC.equals(protocol) ? "stop_reason" : "status",
                ANTHROPIC.equals(protocol) ? "max_tokens" : "incomplete");
        return body.toString();
    }

    private static String errorResponse(String protocol, boolean streaming) throws Exception {
        if (streaming) return delta(protocol, PRIVATE_BODY) + "event: error\ndata: "
                + JSONObject.quote(PRIVATE_BODY) + "\n\n";
        return new JSONObject(json(protocol, PRIVATE_BODY)).put("error",
                new JSONObject().put("message", PRIVATE_BODY)).toString();
    }

    private static String endpointSuffix(String protocol) {
        return CHAT.equals(protocol) ? "/chat/completions" : ANTHROPIC.equals(protocol) ? "/messages" : "/responses";
    }

    private static String start(ApiTester.TestKind kind, String protocol, boolean streaming) {
        return "api.test.start test=" + kind + " protocol=" + protocol + " streaming=" + streaming;
    }

    private static String completion(ApiTester.Result result) {
        ApiTester.Failure failure = result.failure;
        return "api.test.complete test=" + result.testKind + " outcome=FAILURE kind=" + failure.kind
                + " reason=" + failure.reason + " httpStatus=" + failure.httpStatus
                + " errorClass=" + failure.exceptionType;
    }

    private void assertEntries(boolean enabled, String... messages) throws Exception {
        assertEquals(enabled ? messages.length : 0, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            assertEquals("DiscordAutoTranslate", entry.tag);
            assertEquals(messages[i], entry.message);
            boolean upstreamBodyLine = entry.message.startsWith("api.upstream.error httpStatus=");
            assertEquals(messages[i].contains("outcome=FAILURE") || upstreamBodyLine
                    ? Log.WARN : Log.INFO, entry.priority);
            if (!upstreamBodyLine) for (String secret : new String[]{PRIVATE_INPUT, PRIVATE_BODY, PRIVATE_URL, PRIVATE_KEY,
                    PRIVATE_HEADERS, "private-header-value", PRIVATE_MESSAGE, "private DM",
                    "private response", "private.invalid", ApiTester.DEFAULT_TEST_TEXT}) {
                assertFalse(entry.message.contains(secret));
                assertFalse(entry.message.contains(sha256(secret)));
                assertFalse(entry.message.contains(Integer.toString(secret.hashCode())));
            }
            // Exact full-line assertions also rule out length ratios, counts and extra hashes.
            assertFalse(entry.message.contains("length="));
            assertFalse(entry.message.contains("hash="));
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return hex.toString();
    }

    private interface Factory { HttpURLConnection open(URL url) throws IOException; }

    /** Reflection is only for the existing core-package HTTP seam and ownership observation. */
    private static final class Harness {
        final Config.Snapshot cfg;
        final JsonApiEngine engine;
        final AtomicInteger opens = new AtomicInteger();

        Harness(Config.Snapshot cfg, Factory factory) throws Exception {
            this.cfg = cfg;
            Class<?> type = Class.forName(Http.class.getName() + "$ConnectionFactory");
            Object connections = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        opens.incrementAndGet();
                        return factory.open((URL) args[0]);
                    });
            Constructor<Http> constructor = Http.class.getDeclaredConstructor(int.class, type);
            constructor.setAccessible(true);
            engine = new JsonApiEngine(cfg, constructor.newInstance(1000, connections));
        }

        ApiTester.Call newCall(ApiTester.TestKind kind, String text) {
            return new ApiTester.Call(engine, kind, text, cfg);
        }

        ApiTester.Result call(ApiTester.TestKind kind, String text) {
            return newCall(kind, text).call();
        }

        Map<?, ?> active() throws Exception {
            Field field = JsonApiEngine.class.getDeclaredField("active");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(engine);
        }

        void assertReleased(int expectedOpens) throws Exception {
            assertEquals(expectedOpens, opens.get());
            assertTrue(active().isEmpty());
        }
    }

    private static class FakeConnection extends HttpURLConnection {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = 200;
        int outputOpens;
        volatile boolean disconnected;

        FakeConnection(String response) throws IOException {
            this(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
        }

        FakeConnection(InputStream input) throws IOException {
            super(new URL(PRIVATE_URL));
            this.input = input;
        }

        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return input; }
        @Override public OutputStream getOutputStream() { outputOpens++; return output; }
    }

    private static final class Entry {
        final int priority;
        final String tag;
        final String message;

        Entry(int priority, String tag, String message) {
            this.priority = priority;
            this.tag = tag;
            this.message = message;
        }
    }
}
