package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Zero-dependency HTTP transport. Protocol completion belongs to the selected decoder. */
public final class Http {
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** Success bodies are decoded translations; error bodies remain bounded raw text. */
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }

    /** One mutable decoder per request; accept receives UTF-16 characters, never raw bytes. */
    public interface ResponseDecoder {
        String acceptType();
        void accept(char value) throws TranslationException;
        boolean isComplete();
        String finish() throws TranslationException;
    }

    /** Cancellation and connection attachment share one lock, including the pre-attach gap. */
    public static final class Request {
        private boolean cancelled;
        private boolean used;
        private int responseStatus;
        private HttpURLConnection connection;
        @Nullable private Socket socket;

        public void cancel() {
            HttpURLConnection attached;
            Socket attachedSocket;
            synchronized (this) {
                cancelled = true;
                attached = connection;
                attachedSocket = socket;
            }
            if (attached != null) {
                attached.disconnect();
            }
            if (attachedSocket != null) {
                try {
                    attachedSocket.close();
                } catch (IOException ignored) {
                    // 已关闭。
                }
            }
        }

        public synchronized boolean isCancelled() {
            return cancelled;
        }

        public synchronized void checkCancelled() throws TranslationException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new TranslationException(TranslationException.Kind.CANCELLED,
                        "任务已取消", responseStatus, null);
            }
        }

        synchronized void responseReceived(int status) {
            responseStatus = status;
        }

        synchronized void attach(HttpURLConnection candidate) throws TranslationException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                candidate.disconnect();
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }
            if (used) {
                candidate.disconnect();
                throw new IllegalStateException("Request handles cannot be reused");
            }
            used = true;
            connection = candidate;
        }

        synchronized void detach(HttpURLConnection candidate) {
            if (connection == candidate) {
                connection = null;
            }
        }

        /** 同包的明文兜底传输登记 Socket，取消时一并关闭。 */
        synchronized void attachSocket(Socket candidate) throws TranslationException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                try {
                    candidate.close();
                } catch (IOException ignored) {
                    // 已关闭。
                }
                throw new TranslationException(TranslationException.Kind.CANCELLED, "任务已取消");
            }
            if (used) {
                throw new IllegalStateException("Request handles cannot be reused");
            }
            used = true;
            socket = candidate;
        }

        synchronized void detachSocket(Socket candidate) {
            if (socket == candidate) {
                socket = null;
            }
        }
    }

    public static final class Headers {
        private final java.util.List<String[]> pairs = new java.util.ArrayList<>();

        public Headers add(String name, String value) {
            if (name != null && !name.isEmpty() && value != null) {
                pairs.add(new String[]{name, value});
            }
            return this;
        }

        /** Parse configured headers, rejecting malformed lines rather than ignoring them. */
        public static Headers parse(@Nullable String multiLine) throws TranslationException {
            if (multiLine == null) {
                throw new TranslationException(TranslationException.Kind.CONFIG, "额外请求头为空");
            }
            Headers headers = new Headers();
            int lineNo = 0;
            for (String raw : multiLine.split("\r?\n")) {
                lineNo++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int idx = line.indexOf(':');
                String name = idx < 0 ? "" : line.substring(0, idx).trim();
                if (name.isEmpty()) {
                    throw new TranslationException(TranslationException.Kind.CONFIG,
                            "额外请求头格式无效（第 " + lineNo + " 行）");
                }
                if (!validHeaderName(name)) {
                    throw new TranslationException(TranslationException.Kind.CONFIG,
                            "额外请求头名称无效（第 " + lineNo + " 行）");
                }
                headers.add(name, line.substring(idx + 1).trim());
            }
            return headers;
        }

        private static boolean validHeaderName(String name) {
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (!(Character.isLetterOrDigit(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) {
                    return false;
                }
            }
            return !name.isEmpty();
        }

        @Nullable
        public String get(String name) {
            for (String[] pair : pairs) {
                if (pair[0].equalsIgnoreCase(name)) {
                    return pair[1];
                }
            }
            return null;
        }

        /** 供同包的兜底传输复用同一组请求头；只读遍历，不暴露可变集合。 */
        java.util.List<String[]> pairs() {
            return java.util.Collections.unmodifiableList(pairs);
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final int timeoutMs;
    private final ConnectionFactory connections;

    public Http(int timeoutMs) {
        this(timeoutMs, url -> (HttpURLConnection) url.openConnection());
    }

    /** JVM tests inject fake connections into the same production transport. */
    Http(int timeoutMs, ConnectionFactory connections) {
        this.timeoutMs = Math.max(1000, timeoutMs);
        this.connections = connections;
    }

    public Response postJson(String url, String jsonBody, Headers headers, Request request,
                             ResponseDecoder decoder) throws TranslationException {
        HttpURLConnection conn = null;
        int status = 0;
        try {
            request.checkCancelled();
            // 宿主 Discord 用 networkSecurityConfig 禁掉明文 HTTP。Android 明确说明
            // java.net.Socket 不受该策略约束，因此检测到明文被禁时直接走裸 Socket
            // 兜底，不依赖 CleartextHooks 是否安装成功（Hook 可能因内联或隐藏 API
            // 限制失效，且失败是静默的）。
            if (isHttpUrl(url) && !cleartextPermitted(url)) {
                return postCleartextDirect(url, jsonBody, headers, request, decoder, null);
            }
            conn = connections.open(new URL(url));
            request.attach(conn);
            request.checkCancelled();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", decoder.acceptType());
            if (headers != null) {
                for (String[] pair : headers.pairs) {
                    conn.setRequestProperty(pair[0], pair[1]);
                }
            }
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            request.checkCancelled();
            try (OutputStream out = conn.getOutputStream()) {
                request.checkCancelled();
                out.write(payload);
                request.checkCancelled();
                out.flush();
            }
            request.checkCancelled();
            status = conn.getResponseCode();
            request.responseReceived(status);
            request.checkCancelled();
            ProjectLog.info("api.http.response httpStatus=" + status);
            boolean success = status >= 200 && status < 300;
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            request.checkCancelled();
            String body = readResponse(input, request, success ? decoder : new RawDecoder());
            request.checkCancelled();
            return new Response(status, body);
        } catch (TranslationException e) {
            request.checkCancelled();
            if (status != 0 && e.httpStatus() == 0) {
                throw new TranslationException(e.kind(), e.reason(), e.getMessage(), status,
                        e.getCause());
            }
            throw e;
        } catch (SocketTimeoutException e) {
            request.checkCancelled();
            throw new TranslationException(TranslationException.Kind.TIMEOUT,
                    "请求超时（" + timeoutMs + "ms）", status, e);
        } catch (IOException e) {
            request.checkCancelled();
            // 若 Hook 让「策略查询」误判为允许、实际连接仍被明文过滤器拒绝，
            // 这里按异常特征再兜一次。
            if (isHttpUrl(url) && isCleartextRejection(e)) {
                return postCleartextDirect(url, jsonBody, headers, request, decoder, e);
            }
            throw new TranslationException(TranslationException.Kind.NETWORK,
                    "网络错误：" + e.getClass().getSimpleName() + "：" + messageOf(e), status, e);
        } catch (RuntimeException e) {
            request.checkCancelled();
            throw new TranslationException(TranslationException.Kind.UNKNOWN,
                    "请求失败：" + e.getClass().getSimpleName(), status, e);
        } finally {
            if (conn != null) {
                request.detach(conn);
                conn.disconnect();
            }
        }
    }

    /** 明文直连兜底；两次都失败时把两条错误串起来，避免界面只剩「请求失败」。 */
    private Response postCleartextDirect(String url, String jsonBody, Headers headers,
                                         Request request, ResponseDecoder decoder,
                                         @Nullable IOException primary) throws TranslationException {
        try {
            return CleartextHttp.post(url, jsonBody, timeoutMs, headers, request, decoder);
        } catch (IOException e) {
            String detail = "明文直连失败：" + e.getClass().getSimpleName() + "：" + messageOf(e);
            if (primary != null) {
                detail = "网络错误：" + primary.getClass().getSimpleName()
                        + "：" + messageOf(primary) + "；" + detail;
            }
            throw new TranslationException(TranslationException.Kind.NETWORK, detail, 0, e);
        } catch (RuntimeException e) {
            throw new TranslationException(TranslationException.Kind.UNKNOWN,
                    "明文直连失败：" + e.getClass().getSimpleName() + "：" + messageOf(e), 0, e);
        }
    }

    private static boolean isHttpUrl(String url) {
        return url != null && url.trim().toLowerCase(Locale.US).startsWith("http://");
    }

    /**
     * 当前进程是否允许访问该明文主机。用公开 SDK 的反射调用，避免纯逻辑层
     * 编译期依赖 Android；非 Android（JVM 单测）回落到原有路径，由异常兜底。
     */
    private static boolean cleartextPermitted(String url) {
        try {
            String host = new URL(url).getHost();
            Class<?> policy = Class.forName("android.security.NetworkSecurityPolicy");
            Object instance = policy.getMethod("getInstance").invoke(null);
            Object permitted = policy.getMethod("isCleartextTrafficPermitted", String.class)
                    .invoke(instance, host);
            return Boolean.TRUE.equals(permitted);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** AOSP 的明文拒绝是 IOException("Cleartext HTTP traffic to ... not permitted")。 */
    private static boolean isCleartextRejection(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < 5; i++) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.US);
                if (lower.contains("cleartext") || lower.contains("not permitted")) {
                    return true;
                }
            }
            Throwable cause = current.getCause();
            if (cause == current || cause == null) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static String messageOf(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.isEmpty() ? "无错误信息" : message;
    }

    /** No buffered read-ahead: a completed event must not consume trailing keep-alive bytes. */
    static String readResponse(@Nullable InputStream input, Request request,
                               ResponseDecoder decoder) throws IOException, TranslationException {
        request.checkCancelled();
        if (input == null) {
            return decoder.finish();
        }
        // HttpURLConnection.disconnect() owns response cleanup in postJson's finally.
        // Closing its input stream first can drain unread bytes and block on keep-alives.
        Utf8Reader reader = new Utf8Reader(input, request);
        while (!decoder.isComplete()) {
            int point = reader.read();
            if (point == -1) {
                break;
            }
            if (point <= 0xffff) {
                decoder.accept((char) point);
            } else {
                decoder.accept(Character.highSurrogate(point));
                decoder.accept(Character.lowSurrogate(point));
            }
            request.checkCancelled();
        }
        return decoder.finish();
    }

    /** Raw non-2xx bodies never pass through a successful-response protocol decoder. */
    static final class RawDecoder implements ResponseDecoder {
        private final StringBuilder body = new StringBuilder();
        public String acceptType() { return "application/json"; }
        public void accept(char value) { body.append(value); }
        public boolean isComplete() { return false; }
        public String finish() { return body.toString(); }
    }

    /** Strict UTF-8 with an explicit cumulative byte cap, checked during every read. */
    private static final class Utf8Reader {
        private final InputStream input;
        private final Request request;
        private int bytes;

        Utf8Reader(InputStream input, Request request) {
            this.input = input;
            this.request = request;
        }

        private int next() throws IOException, TranslationException {
            request.checkCancelled();
            int value = input.read();
            request.checkCancelled();
            if (value != -1 && ++bytes > MAX_RESPONSE_BYTES) {
                throw new TranslationException(TranslationException.Kind.PARSE,
                        "HTTP response exceeds 1 MiB byte limit");
            }
            return value;
        }

        int read() throws IOException, TranslationException {
            int first = next();
            if (first < 0x80) {
                return first;
            }
            int remaining;
            int point;
            int minimum;
            if (first >= 0xc2 && first <= 0xdf) {
                remaining = 1; point = first & 0x1f; minimum = 0x80;
            } else if (first >= 0xe0 && first <= 0xef) {
                remaining = 2; point = first & 0x0f; minimum = 0x800;
            } else if (first >= 0xf0 && first <= 0xf4) {
                remaining = 3; point = first & 7; minimum = 0x10000;
            } else {
                throw invalidUtf8();
            }
            for (int i = 0; i < remaining; i++) {
                int next = next();
                if (next < 0x80 || next > 0xbf) {
                    throw invalidUtf8();
                }
                point = (point << 6) | (next & 0x3f);
            }
            if (point < minimum || point > 0x10ffff || (point >= 0xd800 && point <= 0xdfff)) {
                throw invalidUtf8();
            }
            return point;
        }

        private TranslationException invalidUtf8() {
            return new TranslationException(TranslationException.Kind.PARSE,
                    "响应不是合法 UTF-8");
        }
    }
}
