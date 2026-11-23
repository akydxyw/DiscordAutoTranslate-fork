package io.github.wxmyyds.discordautotranslate.core;

/**
 * 翻译流程中的可分类异常。
 *
 * <p>需求 §5.5 / §8：配置错误、网络错误、超时、限流和无效响应都必须有明确反馈。
 * 因此所有失败路径都归一到 {@link Kind} 和 {@link Reason}；宿主失败提示使用
 * {@link #userMessage()}，设置页按结构化结果解析本地化资源。</p>
 */
public class TranslationException extends Exception {

    public enum Kind {
        /** 配置缺失或不合法（URL 为空、URL 协议头不符、请求体构造失败等）。 */
        CONFIG,
        /** 连接失败、DNS 失败、TLS 失败等网络层错误。 */
        NETWORK,
        /** 读取超时或连接超时。 */
        TIMEOUT,
        /** 收到非 2xx 的 HTTP 状态码。 */
        HTTP,
        /** 429 或服务端明确提示限流。 */
        RATE_LIMIT,
        /** 响应不是合法 JSON，或按协议结构取不到译文。 */
        PARSE,
        /** 请求成功但译文为空。 */
        EMPTY,
        /** 任务在完成前被取消（关闭开关、消息被编辑、模块热重载）。 */
        CANCELLED,
        /** 其他未归类错误。 */
        UNKNOWN
    }

    /** 稳定的失败原因；由失败路径显式指定，不从异常消息推断。 */
    public enum Reason {
        CONFIG_INVALID,
        NETWORK_FAILURE,
        REQUEST_TIMEOUT,
        HTTP_ERROR,
        RATE_LIMITED,
        RESPONSE_INVALID,
        RESPONSE_INCOMPLETE,
        GENERATION_STOPPED,
        TRANSLATION_INVALID,
        EMPTY_TEXT,
        CANCELLED,
        UNKNOWN_FAILURE
    }

    private final Kind kind;
    private final Reason reason;
    private final int httpStatus;

    public TranslationException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    public TranslationException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, cause);
    }

    public TranslationException(Kind kind, String message, int httpStatus, Throwable cause) {
        this(kind, defaultReason(kind), message, httpStatus, cause);
    }

    public TranslationException(Kind kind, Reason reason, String message) {
        this(kind, reason, message, 0, null);
    }

    public TranslationException(Kind kind, Reason reason, String message,
                                int httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    private static Reason defaultReason(Kind kind) {
        switch (kind) {
            case CONFIG: return Reason.CONFIG_INVALID;
            case NETWORK: return Reason.NETWORK_FAILURE;
            case TIMEOUT: return Reason.REQUEST_TIMEOUT;
            case HTTP: return Reason.HTTP_ERROR;
            case RATE_LIMIT: return Reason.RATE_LIMITED;
            case PARSE: return Reason.RESPONSE_INVALID;
            case EMPTY: return Reason.EMPTY_TEXT;
            case CANCELLED: return Reason.CANCELLED;
            case UNKNOWN: return Reason.UNKNOWN_FAILURE;
            default: throw new AssertionError(kind);
        }
    }

    public Kind kind() {
        return kind;
    }

    public Reason reason() {
        return reason;
    }

    /** 实际 HTTP 状态码；尚未取得或未记录时为 0。 */
    public int httpStatus() {
        return httpStatus;
    }

    /** 宿主翻译失败的可读提示；设置页和项目日志使用结构化诊断字段。 */
    public String userMessage() {
        switch (kind) {
            case CONFIG:
                return "配置错误：" + getMessage();
            case NETWORK:
                return "网络连接失败，请检查网络后重试。";
            case TIMEOUT:
                return "请求超时，可在设置中增大超时时间后重试。";
            case RATE_LIMIT:
                return "API 限流（429），请稍后重试或降低并发。";
            case HTTP:
                return "服务返回错误状态码 " + httpStatus
                        + (getMessage() == null ? "" : "（" + getMessage() + "）");
            case PARSE:
                return "响应解析失败：" + getMessage();
            case EMPTY:
                return "服务返回的译文为空。";
            case CANCELLED:
                return "请求已取消。";
            default:
                return "翻译失败：" + getMessage();
        }
    }
}
