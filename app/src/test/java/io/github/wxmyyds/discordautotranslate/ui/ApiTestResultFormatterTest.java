package io.github.wxmyyds.discordautotranslate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

/** Exercises the sole production formatter with observable, injectable resource resolution. */
public class ApiTestResultFormatterTest {
    private static final String PRIVATE_TEXT = "sk-secret https://secret.invalid raw-response-body";

    @Test
    public void successConceptsAndTranslationAreSelectedByRealTestKind() throws Exception {
        RecordingStrings configuration = new RecordingStrings();
        ApiTester.Result offline = ApiTester.selfCheck(Config.read(null));
        ApiTestResultFormatter.format(offline, configuration);
        configuration.assertCalls(new Call(R.string.result_configuration_success, offline.elapsedMs));

        RecordingStrings connection = new RecordingStrings();
        ApiTestResultFormatter.format(result(ApiTester.TestKind.CONNECTION, PRIVATE_TEXT, 17L, null),
                connection);
        connection.assertCalls(new Call(R.string.result_connection_success, 17L));
        assertFalse(connection.outputContains(PRIVATE_TEXT));

        RecordingStrings translation = new RecordingStrings();
        ApiTestResultFormatter.format(result(ApiTester.TestKind.TRANSLATION, "你好", 23L, null),
                translation);
        translation.assertCalls(new Call(R.string.result_translation_success, 23L),
                new Call(R.string.result_translation, "你好"));
    }

    @Test
    public void everyReasonUsesItsFixedLocalizedResourceAndCategory() throws Exception {
        assertReason(TranslationException.Kind.CONFIG, TranslationException.Reason.CONFIG_INVALID,
                R.string.result_category_config, R.string.result_reason_config_invalid);
        assertReason(TranslationException.Kind.NETWORK, TranslationException.Reason.NETWORK_FAILURE,
                R.string.result_category_network, R.string.result_reason_network_failure);
        assertReason(TranslationException.Kind.TIMEOUT, TranslationException.Reason.REQUEST_TIMEOUT,
                R.string.result_category_timeout, R.string.result_reason_request_timeout);
        assertReason(TranslationException.Kind.HTTP, TranslationException.Reason.HTTP_ERROR,
                R.string.result_category_http, R.string.result_reason_http_error);
        assertReason(TranslationException.Kind.RATE_LIMIT, TranslationException.Reason.RATE_LIMITED,
                R.string.result_category_rate_limit, R.string.result_reason_rate_limited);
        assertReason(TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INVALID,
                R.string.result_category_parse, R.string.result_reason_response_invalid);
        assertReason(TranslationException.Kind.PARSE, TranslationException.Reason.RESPONSE_INCOMPLETE,
                R.string.result_category_parse, R.string.result_reason_response_incomplete);
        assertReason(TranslationException.Kind.PARSE, TranslationException.Reason.GENERATION_STOPPED,
                R.string.result_category_parse, R.string.result_reason_generation_stopped);
        assertReason(TranslationException.Kind.PARSE, TranslationException.Reason.TRANSLATION_INVALID,
                R.string.result_category_parse, R.string.result_reason_translation_invalid);
        assertReason(TranslationException.Kind.EMPTY, TranslationException.Reason.EMPTY_TEXT,
                R.string.result_category_empty, R.string.result_reason_empty_text);
        assertReason(TranslationException.Kind.CANCELLED, TranslationException.Reason.CANCELLED,
                R.string.result_category_cancelled, R.string.result_reason_cancelled);
        assertReason(TranslationException.Kind.UNKNOWN, TranslationException.Reason.UNKNOWN_FAILURE,
                R.string.result_category_unknown, R.string.result_reason_unknown_failure);
    }

    @Test
    public void knownHttpStatusesHaveActionSpecificResourcesAndKeepTheActualStatus() throws Exception {
        int[] statuses = {400, 401, 403, 404, 429, 500, 503, 599, 418, 302};
        int[] resources = {R.string.result_reason_http_parameters,
                R.string.result_reason_http_authentication, R.string.result_reason_http_permission,
                R.string.result_reason_http_not_found, R.string.result_reason_rate_limited,
                R.string.result_reason_http_server, R.string.result_reason_http_server,
                R.string.result_reason_http_server, R.string.result_reason_http_error,
                R.string.result_reason_http_error};
        for (int index = 0; index < statuses.length; index++) {
            RecordingStrings strings = new RecordingStrings();
            ApiTestResultFormatter.format(result(ApiTester.TestKind.CONNECTION, null, 7L,
                    failure(TranslationException.Kind.HTTP, TranslationException.Reason.HTTP_ERROR,
                            statuses[index], "TranslationException")), strings);
            assertFailure(strings, R.string.result_kind_connection, 7L, resources[index],
                    R.string.result_category_http, TranslationException.Kind.HTTP,
                    TranslationException.Reason.HTTP_ERROR, statuses[index], "TranslationException");
        }
    }

    @Test
    public void parsingWithHttp200StillDisplaysItsReasonRatherThanHttpSuccess() throws Exception {
        for (TranslationException.Reason reason : new TranslationException.Reason[]{
                TranslationException.Reason.RESPONSE_INVALID,
                TranslationException.Reason.RESPONSE_INCOMPLETE,
                TranslationException.Reason.GENERATION_STOPPED,
                TranslationException.Reason.TRANSLATION_INVALID}) {
            int resource;
            switch (reason) {
                case RESPONSE_INVALID: resource = R.string.result_reason_response_invalid; break;
                case RESPONSE_INCOMPLETE: resource = R.string.result_reason_response_incomplete; break;
                case GENERATION_STOPPED: resource = R.string.result_reason_generation_stopped; break;
                case TRANSLATION_INVALID: resource = R.string.result_reason_translation_invalid; break;
                default: throw new AssertionError(reason);
            }
            RecordingStrings strings = new RecordingStrings();
            ApiTestResultFormatter.format(result(ApiTester.TestKind.TRANSLATION, PRIVATE_TEXT, 9L,
                    failure(TranslationException.Kind.PARSE, reason, 200, "JSONException")), strings);
            assertFailure(strings, R.string.result_kind_translation, 9L, resource,
                    R.string.result_category_parse, TranslationException.Kind.PARSE,
                    reason, 200, "JSONException");
            assertFalse(strings.outputContains(PRIVATE_TEXT));
        }
    }

    @Test
    public void offlineConfigurationFailureUsesRealSafeMetadataAndNoDraftContent() {
        RecordingStrings strings = new RecordingStrings();
        ApiTester.Result offline = ApiTester.selfCheck(Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, PRIVATE_TEXT).put(Config.KEY_API_KEY, PRIVATE_TEXT)
                .put(Config.KEY_EXTRA_HEADERS, PRIVATE_TEXT)));
        assertFalse(offline.ok);
        String output = ApiTestResultFormatter.format(offline, strings);
        assertFailure(strings, R.string.result_kind_configuration, offline.elapsedMs,
                R.string.result_reason_config_invalid, R.string.result_category_config,
                TranslationException.Kind.CONFIG, TranslationException.Reason.CONFIG_INVALID,
                0, "TranslationException");
        assertFalse(output.contains(PRIVATE_TEXT));
    }

    @Test
    public void realFailureConversionKeepsCauseTypeButNeverMessageOrBody() throws Exception {
        TranslationException error = new TranslationException(TranslationException.Kind.HTTP,
                TranslationException.Reason.HTTP_ERROR, PRIVATE_TEXT, 403,
                new SecurityException(PRIVATE_TEXT));
        Method from = ApiTester.Failure.class.getDeclaredMethod("from", Throwable.class);
        from.setAccessible(true);
        ApiTester.Failure safe = (ApiTester.Failure) from.invoke(null, error);
        RecordingStrings strings = new RecordingStrings();
        String output = ApiTestResultFormatter.format(
                result(ApiTester.TestKind.CONNECTION, PRIVATE_TEXT, 31L, safe), strings);
        assertFailure(strings, R.string.result_kind_connection, 31L,
                R.string.result_reason_http_permission, R.string.result_category_http,
                TranslationException.Kind.HTTP, TranslationException.Reason.HTTP_ERROR,
                403, "SecurityException");
        assertFalse(output.contains(PRIVATE_TEXT));
        assertFalse(strings.outputContains(PRIVATE_TEXT));
    }

    @Test
    public void absentHttpStatusAndExceptionTypeDoNotProduceInventedDetails() throws Exception {
        RecordingStrings strings = new RecordingStrings();
        ApiTestResultFormatter.format(result(ApiTester.TestKind.TRANSLATION, null, 0L,
                failure(TranslationException.Kind.CANCELLED, TranslationException.Reason.CANCELLED,
                        0, "")), strings);
        assertFailure(strings, R.string.result_kind_translation, 0L,
                R.string.result_reason_cancelled, R.string.result_category_cancelled,
                TranslationException.Kind.CANCELLED, TranslationException.Reason.CANCELLED, 0, "");
    }

    private static void assertReason(TranslationException.Kind kind, TranslationException.Reason reason,
                                     int category, int explanation) throws Exception {
        RecordingStrings strings = new RecordingStrings();
        ApiTestResultFormatter.format(result(ApiTester.TestKind.TRANSLATION, PRIVATE_TEXT, 13L,
                failure(kind, reason, 0, "IOException")), strings);
        assertFailure(strings, R.string.result_kind_translation, 13L, explanation, category,
                kind, reason, 0, "IOException");
        assertFalse(strings.outputContains(PRIVATE_TEXT));
    }

    private static void assertFailure(RecordingStrings strings, int testKind, long elapsed,
            int explanation, int category, TranslationException.Kind kind,
            TranslationException.Reason reason, int httpStatus, String exceptionType) {
        List<Call> expected = new ArrayList<>();
        expected.add(new Call(testKind));
        expected.add(new Call(R.string.result_test_failure, RecordingStrings.resolved(testKind), elapsed));
        expected.add(new Call(explanation));
        expected.add(new Call(category));
        expected.add(new Call(R.string.result_failure_category,
                RecordingStrings.resolved(category), kind.name()));
        expected.add(new Call(R.string.result_failure_reason, reason.name()));
        if (httpStatus > 0) expected.add(new Call(R.string.result_failure_http, httpStatus));
        if (!exceptionType.isEmpty()) {
            expected.add(new Call(R.string.result_failure_exception, exceptionType));
        }
        strings.assertCalls(expected.toArray(new Call[0]));
    }

    private static ApiTester.Failure failure(TranslationException.Kind kind,
            TranslationException.Reason reason, int status, String type) throws Exception {
        Constructor<ApiTester.Failure> constructor = ApiTester.Failure.class.getDeclaredConstructor(
                TranslationException.Kind.class, TranslationException.Reason.class,
                int.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(kind, reason, status, type);
    }

    private static ApiTester.Result result(ApiTester.TestKind kind, String translated, long elapsed,
                                          ApiTester.Failure failure) throws Exception {
        Constructor<ApiTester.Result> constructor = ApiTester.Result.class.getDeclaredConstructor(
                ApiTester.TestKind.class, String.class, long.class, ApiTester.Failure.class);
        constructor.setAccessible(true);
        return constructor.newInstance(kind, translated, elapsed, failure);
    }

    private static final class Call {
        final int resource;
        final List<Object> args;

        Call(int resource, Object... args) {
            this.resource = resource;
            this.args = Arrays.asList(args);
        }
    }

    private static final class RecordingStrings implements ApiTestResultFormatter.Strings {
        private final List<Call> calls = new ArrayList<>();

        @Override
        public String get(int resource, Object... args) {
            calls.add(new Call(resource, args));
            return "localized#" + resource + Arrays.toString(args);
        }

        static String resolved(int resource) {
            return "localized#" + resource + "[]";
        }

        void assertCalls(Call... expected) {
            assertEquals("resource call count", expected.length, calls.size());
            for (int index = 0; index < expected.length; index++) {
                assertEquals("resource at " + index, expected[index].resource, calls.get(index).resource);
                assertEquals("arguments at " + index, expected[index].args, calls.get(index).args);
            }
        }

        boolean outputContains(String value) {
            return calls.stream().anyMatch(call -> call.args.toString().contains(value));
        }
    }
}
