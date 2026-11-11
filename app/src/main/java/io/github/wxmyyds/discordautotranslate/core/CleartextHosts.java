package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.net.URI;
import java.util.Locale;

/**
 * 明文 HTTP 放行名单（纯逻辑，可在 JVM 单元测试中验证）。
 *
 * <p>背景：Discord 353.x 的 Manifest 通过 {@code android:networkSecurityConfig}
 * 关闭了明文流量（{@code cleartextTrafficPermitted=false}），因此在 Discord 进程内
 * 访问 {@code http://127.0.0.1:PORT/v1} 这类回环 AI 端点时，
 * {@code com.android.okhttp.HttpHandler$CleartextURLFilter} 会抛出
 * {@code IOException: Cleartext HTTP traffic ... not permitted}，
 * 被 {@link Http} 归类为 {@link TranslationException.Kind#NETWORK}。
 *
 * <p>放行范围刻意收窄，只允许两类主机：
 * <ol>
 *   <li>本机回环主机（网络栈本身不会把这些地址发到外部）；</li>
 *   <li>当前配置的 API URL 主机（允许局域网内自建的明文端点）。</li>
 * </ol>
 * Discord 自身访问其他明文站点仍然被系统的网络安全策略拒绝。</p>
 */
public final class CleartextHosts {

    /** 当前配置的 API URL 主机（小写，无方括号）；未配置或解析失败时为 null。 */
    private static volatile String configuredHost;

    private CleartextHosts() {
    }

    /**
     * 记录配置中的 API URL，供放行判断使用。
     *
     * @param apiUrl 用户配置的 API URL；null 或无法解析时清空主机记录
     */
    public static void updateConfiguredUrl(@Nullable String apiUrl) {
        configuredHost = hostOf(apiUrl);
    }

    /** 当前配置的 API URL 主机（可能为 null）。 */
    @Nullable
    public static String configuredHost() {
        return configuredHost;
    }

    /**
     * 是否允许对该主机使用明文 HTTP。
     *
     * @param host 请求目标主机（可带方括号的 IPv6 字面量）
     */
    public static boolean isPermitted(@Nullable String host) {
        String normalized = normalize(host);
        if (normalized == null) {
            return false;
        }
        if (isLoopback(normalized)) {
            return true;
        }
        String configured = configuredHost;
        return configured != null && configured.equals(normalized);
    }

    /** 是否为回环主机（localhost / 127.0.0.0/8 / ::1）。 */
    static boolean isLoopback(@Nullable String normalizedHost) {
        String host = normalize(normalizedHost);
        if (host == null) {
            return false;
        }
        if ("localhost".equals(host) || "localhost.localdomain".equals(host)) {
            return true;
        }
        if ("::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host)) {
            return true;
        }
        // IPv4 回环：127.0.0.0/8（含 127.0.0.1 与少见但合法的 127.x.y.z）。
        if (host.startsWith("127.")) {
            int dots = 0;
            for (int i = 0; i < host.length(); i++) {
                char c = host.charAt(i);
                if (c == '.') {
                    dots++;
                } else if (c < '0' || c > '9') {
                    return false;
                }
            }
            return dots == 3;
        }
        return false;
    }

    /** 从 URL 中解析主机；解析失败返回 null。 */
    @Nullable
    static String hostOf(@Nullable String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String host = null;
        try {
            URI uri = new URI(trimmed);
            host = uri.getHost();
        } catch (Throwable ignored) {
            // 少数手写 URL 不符合 URI 规范（如含空格），回退到手工截取。
        }
        if (host == null || host.isEmpty()) {
            host = hostFromAuthority(trimmed);
        }
        return normalize(host);
    }

    /** 手工从 scheme://authority/path 中截取 host，作为 URI 解析失败时的兜底。 */
    @Nullable
    private static String hostFromAuthority(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        int start = schemeEnd + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) { // IPv6 字面量
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(0, close + 1) : null;
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.isEmpty() ? null : authority;
    }

    /** 统一为小写、去掉 IPv6 方括号与结尾的点。 */
    @Nullable
    private static String normalize(@Nullable String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.isEmpty() ? null : h;
    }
}
