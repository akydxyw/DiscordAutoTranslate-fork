package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** Structured failure metadata is independent of exception text and preserves legacy constructors. */
public class TranslationExceptionTest {

    @Test
    public void legacyConstructorsAssignOneDefaultReasonPerKind() {
        TranslationException.Kind[] kinds = {
                TranslationException.Kind.CONFIG, TranslationException.Kind.NETWORK,
                TranslationException.Kind.TIMEOUT, TranslationException.Kind.HTTP,
                TranslationException.Kind.RATE_LIMIT, TranslationException.Kind.PARSE,
                TranslationException.Kind.EMPTY, TranslationException.Kind.CANCELLED,
                TranslationException.Kind.UNKNOWN
        };
        TranslationException.Reason[] reasons = {
                TranslationException.Reason.CONFIG_INVALID, TranslationException.Reason.NETWORK_FAILURE,
                TranslationException.Reason.REQUEST_TIMEOUT, TranslationException.Reason.HTTP_ERROR,
                TranslationException.Reason.RATE_LIMITED, TranslationException.Reason.RESPONSE_INVALID,
                TranslationException.Reason.EMPTY_TEXT, TranslationException.Reason.CANCELLED,
                TranslationException.Reason.UNKNOWN_FAILURE
        };
        assertEquals(TranslationException.Kind.values().length, kinds.length);
        Throwable cause = new IllegalStateException("cause");
        for (int i = 0; i < kinds.length; i++) {
            String message = "RESPONSE_INCOMPLETE GENERATION_STOPPED 429 请求超时";
            TranslationException simple = new TranslationException(kinds[i], message);
            TranslationException withCause = new TranslationException(kinds[i], message, cause);
            TranslationException withStatus = new TranslationException(kinds[i], message, 429, cause);
            for (TranslationException failure : new TranslationException[]{simple, withCause, withStatus}) {
                assertEquals(kinds[i], failure.kind());
                assertEquals(reasons[i], failure.reason());
                assertEquals(message, failure.getMessage());
            }
            assertEquals(0, simple.httpStatus());
            assertNull(simple.getCause());
            assertEquals(0, withCause.httpStatus());
            assertSame(cause, withCause.getCause());
            assertEquals(429, withStatus.httpStatus());
            assertSame(cause, withStatus.getCause());
        }
    }

    @Test
    public void explicitReasonIsPreservedWithoutInterpretingMessage() {
        for (TranslationException.Reason reason : TranslationException.Reason.values()) {
            for (String message : new String[]{null, "", "网络错误 HTTP 429 响应不完整"}) {
                TranslationException failure = new TranslationException(
                        TranslationException.Kind.PARSE, reason, message);
                assertEquals(TranslationException.Kind.PARSE, failure.kind());
                assertEquals(reason, failure.reason());
                assertEquals(message, failure.getMessage());
                assertEquals(0, failure.httpStatus());
                assertNull(failure.getCause());
            }
        }
    }

    @Test
    public void fullConstructorPreservesProtocolFailureReasonStatusAndCause() {
        TranslationException original = new TranslationException(TranslationException.Kind.PARSE,
                TranslationException.Reason.RESPONSE_INCOMPLETE, "未收到结束事件");
        TranslationException wrapped = new TranslationException(original.kind(), original.reason(),
                original.getMessage(), 200, original);
        assertEquals(TranslationException.Kind.PARSE, wrapped.kind());
        assertEquals(TranslationException.Reason.RESPONSE_INCOMPLETE, wrapped.reason());
        assertEquals("未收到结束事件", wrapped.getMessage());
        assertEquals(200, wrapped.httpStatus());
        assertSame(original, wrapped.getCause());
    }

    @Test
    public void fullConstructorPreservesExplicitMetadataForEveryReason() {
        Throwable cause = new IllegalArgumentException("cause");
        for (TranslationException.Reason reason : TranslationException.Reason.values()) {
            TranslationException failure = new TranslationException(TranslationException.Kind.PARSE,
                    reason, "message", 201, cause);
            assertEquals(TranslationException.Kind.PARSE, failure.kind());
            assertEquals(reason, failure.reason());
            assertEquals("message", failure.getMessage());
            assertEquals(201, failure.httpStatus());
            assertSame(cause, failure.getCause());
        }
    }

    @Test
    public void httpAndRateLimitFailuresExposeRecordedStatus() {
        TranslationException http = new TranslationException(
                TranslationException.Kind.HTTP, "认证失败", 401, null);
        assertEquals(TranslationException.Reason.HTTP_ERROR, http.reason());
        assertEquals(401, http.httpStatus());
        assertEquals("服务返回错误状态码 401（认证失败）", http.userMessage());

        TranslationException rateLimit = new TranslationException(
                TranslationException.Kind.RATE_LIMIT, "限流", 429, null);
        assertEquals(TranslationException.Reason.RATE_LIMITED, rateLimit.reason());
        assertEquals(429, rateLimit.httpStatus());
    }
}
