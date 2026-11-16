package io.github.wxmyyds.discordautotranslate.ui;

import java.util.Objects;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.TranslationException;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

/** Sole localized settings-test presentation; accepts only the content-free failure contract. */
final class ApiTestResultFormatter {
    @FunctionalInterface
    interface Strings {
        String get(int resource, Object... args);
    }

    private ApiTestResultFormatter() {}

    static String format(ApiTester.Result result, Strings strings) {
        if (result.ok) {
            switch (result.testKind) {
                case CONFIGURATION:
                    return strings.get(R.string.result_configuration_success, result.elapsedMs);
                case CONNECTION:
                    return strings.get(R.string.result_connection_success, result.elapsedMs);
                case TRANSLATION:
                    return strings.get(R.string.result_translation_success, result.elapsedMs)
                            + strings.get(R.string.result_translation,
                                    Objects.requireNonNull(result.translated));
                default:
                    throw new AssertionError(result.testKind);
            }
        }
        ApiTester.Failure failure = Objects.requireNonNull(result.failure);
        StringBuilder message = new StringBuilder(strings.get(R.string.result_test_failure,
                strings.get(testKindResource(result.testKind)), result.elapsedMs));
        message.append('\n').append(strings.get(reasonResource(failure)));
        message.append(strings.get(R.string.result_failure_category,
                strings.get(categoryResource(failure.kind)), failure.kind.name()));
        message.append(strings.get(R.string.result_failure_reason, failure.reason.name()));
        if (failure.httpStatus > 0) {
            message.append(strings.get(R.string.result_failure_http, failure.httpStatus));
        }
        if (!failure.exceptionType.isEmpty()) {
            message.append(strings.get(R.string.result_failure_exception, failure.exceptionType));
        }
        if (failure.upstreamErrorBody != null && !failure.upstreamErrorBody.isEmpty()) {
            message.append(strings.get(R.string.result_upstream_error, failure.upstreamErrorBody));
        }
        // A failed result must never expose even an accidentally supplied partial translation.
        return message.toString();
    }

    private static int testKindResource(ApiTester.TestKind testKind) {
        switch (testKind) {
            case CONFIGURATION: return R.string.result_kind_configuration;
            case CONNECTION: return R.string.result_kind_connection;
            case TRANSLATION: return R.string.result_kind_translation;
            default: throw new AssertionError(testKind);
        }
    }

    private static int categoryResource(TranslationException.Kind kind) {
        switch (kind) {
            case CONFIG: return R.string.result_category_config;
            case NETWORK: return R.string.result_category_network;
            case TIMEOUT: return R.string.result_category_timeout;
            case HTTP: return R.string.result_category_http;
            case RATE_LIMIT: return R.string.result_category_rate_limit;
            case PARSE: return R.string.result_category_parse;
            case EMPTY: return R.string.result_category_empty;
            case CANCELLED: return R.string.result_category_cancelled;
            case UNKNOWN: return R.string.result_category_unknown;
            default: throw new AssertionError(kind);
        }
    }

    private static int reasonResource(ApiTester.Failure failure) {
        switch (failure.reason) {
            case CONFIG_INVALID: return R.string.result_reason_config_invalid;
            case NETWORK_FAILURE: return R.string.result_reason_network_failure;
            case REQUEST_TIMEOUT: return R.string.result_reason_request_timeout;
            case HTTP_ERROR:
                switch (failure.httpStatus) {
                    case 400: return R.string.result_reason_http_parameters;
                    case 401: return R.string.result_reason_http_authentication;
                    case 403: return R.string.result_reason_http_permission;
                    case 404: return R.string.result_reason_http_not_found;
                    case 429: return R.string.result_reason_rate_limited;
                    default:
                        return failure.httpStatus >= 500
                                ? R.string.result_reason_http_server
                                : R.string.result_reason_http_error;
                }
            case RATE_LIMITED: return R.string.result_reason_rate_limited;
            case RESPONSE_INVALID: return R.string.result_reason_response_invalid;
            case RESPONSE_INCOMPLETE: return R.string.result_reason_response_incomplete;
            case GENERATION_STOPPED: return R.string.result_reason_generation_stopped;
            case TRANSLATION_INVALID: return R.string.result_reason_translation_invalid;
            case EMPTY_TEXT: return R.string.result_reason_empty_text;
            case CANCELLED: return R.string.result_reason_cancelled;
            case UNKNOWN_FAILURE: return R.string.result_reason_unknown_failure;
            default: throw new AssertionError(failure.reason);
        }
    }
}
