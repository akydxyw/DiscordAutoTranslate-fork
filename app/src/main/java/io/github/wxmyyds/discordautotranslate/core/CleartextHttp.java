package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 明文 HTTP 的最小传输实现，仅作为 {@link Http#postJson} 的兜底路径使用。
 *
 * <p>背景：模块注入在 Discord 进程内时，宿主的 {@code networkSecurityConfig}
 * 会禁止明文 HTTP，{@code com.android.okhttp.HttpHandler$CleartextURLFilter}
 * 在 {@link java.net.HttpURLConnection} 发起请求前直接抛 IOException。
 * {@code hooks/CleartextHooks} 用 Hook 放行，但 Hook 可能因方法内联、隐藏 API
 * 限制或不同 Android 版本的调用路径变化而装不上/不生效，且失败是静默的。
 * Android 官方对 {@code NetworkSecurityPolicy} 的说明明确指出：
 * {@link Socket} 不参与该策略判断。因此在明文被拒时改用裸 Socket 直连，
 * 让模块自己的请求不依赖 Hook 是否成功。</p>
 *
 * <p>刻意保持最小：只支持本模块会发出的 HTTP/1.1 POST、JSON 请求体、
 * {@code Connection: close}；不处理重定向、代理与连接池。HTTPS 请求以及
 * 宿主允许明文时的普通请求完全不走这里，仍由 {@link Http} 的原有路径处理。
 * 响应体复用 {@link Http#readResponse} 的解码与取消管道（含 SSE 结束帧）。</p>
 */
final class CleartextHttp {

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 16 * 1024;

    private CleartextHttp() {
    }

    /**
     * 用裸 Socket 发送一次 POST，响应经上游同一解码管道返回。
     *
     * @param request 取消跟踪：取消时由另一线程关闭 Socket
     */
    static Http.Response post(String url, String jsonBody, int timeoutMs,
                              @Nullable Http.Headers headers, Http.Request request,
                              Http.ResponseDecoder decoder)
            throws IOException, TranslationException {
        URL target = new URL(url);
        if (!"http".equalsIgnoreCase(target.getProtocol())) {
            throw new IOException("not a cleartext http URL");
        }
        String host = target.getHost();
        int port = target.getPort() > 0 ? target.getPort() : 80;
        String path = target.getFile();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);

        Socket socket = new Socket();
        request.attachSocket(socket);
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            StringBuilder requestText = new StringBuilder(512);
            requestText.append("POST ").append(path).append(" HTTP/1.1\r\n");
            requestText.append("Host: ").append(host);
            if (port != 80) {
                requestText.append(':').append(port);
            }
            requestText.append("\r\n");
            requestText.append("User-Agent: DiscordAutoTranslate\r\n");
            requestText.append("Content-Type: application/json; charset=utf-8\r\n");
            requestText.append("Accept: ").append(decoder.acceptType()).append("\r\n");
            // 明确拒绝压缩：本实现不解析 Content-Encoding 之外的编码，
            // 同时避免 gzip 与分块叠加时解码顺序出错。
            requestText.append("Accept-Encoding: identity\r\n");
            requestText.append("Connection: close\r\n");
            requestText.append("Content-Length: ").append(payload.length).append("\r\n");
            if (headers != null) {
                for (String[] pair : headers.pairs()) {
                    requestText.append(pair[0]).append(": ").append(pair[1]).append("\r\n");
                }
            }
            requestText.append("\r\n");

            OutputStream out = socket.getOutputStream();
            // 与 HttpURLConnection/OkHttp 一致用 UTF-8 写请求头，避免用户自定义头
            // 里的非 ASCII 字符被 ISO-8859-1 改写。
            out.write(requestText.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();

            InputStream raw = new BufferedInputStream(socket.getInputStream(), 4096);
            int status = readStatus(raw);
            request.responseReceived(status);
            Map<String, String> responseHeaders = readHeaders(raw);
            InputStream body = bodyStream(raw, responseHeaders);
            boolean success = status >= 200 && status < 300;
            String text = Http.readResponse(body, request,
                    success ? decoder : new Http.RawDecoder());
            return new Http.Response(status, text);
        } finally {
            request.detachSocket(socket);
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已关闭或已被取消线程关闭。
            }
        }
    }

    /** 读取最终状态行；跳过 100 Continue 之类的信息响应。 */
    static int readStatus(InputStream in) throws IOException {
        for (int i = 0; i < 5; i++) {
            String line = readLine(in);
            if (line == null) {
                throw new IOException("HTTP 响应为空");
            }
            if (line.isEmpty()) {
                continue; // 信息响应后的空行
            }
            int code = statusCodeOf(line);
            if (code >= 100 && code < 200) {
                continue;
            }
            return code;
        }
        throw new IOException("HTTP 信息响应过多");
    }

    static int statusCodeOf(String line) throws IOException {
        if (!line.regionMatches(true, 0, "HTTP/", 0, 5)) {
            throw new IOException("非法 HTTP 状态行：" + line);
        }
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            throw new IOException("非法 HTTP 状态行：" + line);
        }
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        String code = secondSpace < 0
                ? line.substring(firstSpace + 1)
                : line.substring(firstSpace + 1, secondSpace);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new IOException("非法 HTTP 状态码：" + line);
        }
    }

    /** 读取响应头直到空行；键统一小写。 */
    static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        int total = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            total += line.length() + 2;
            if (total > MAX_HEADER_BYTES) {
                throw new IOException("HTTP 响应头过大");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                        line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** 按 Content-Length / Transfer-Encoding 包一层，供 {@link Http#readResponse} 直接读取。 */
    static InputStream bodyStream(InputStream in, Map<String, String> headers)
            throws IOException {
        InputStream body;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            body = new ChunkedInputStream(in);
        } else {
            String contentLength = headers.get("content-length");
            long length = -1;
            if (contentLength != null) {
                try {
                    length = Long.parseLong(contentLength.trim());
                } catch (NumberFormatException ignored) {
                    length = -1;
                }
            }
            body = length >= 0 ? new FixedLengthInputStream(in, length) : in;
        }
        String encoding = headers.get("content-encoding");
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            return new java.util.zip.GZIPInputStream(body);
        }
        return body;
    }

    /** 逐字节读一行（ISO-8859-1），允许 CRLF 或 LF；EOF 且无数据返回 null。 */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > MAX_LINE_BYTES) {
                throw new IOException("HTTP 行过长");
            }
        }
        if (c == -1 && line.size() == 0) {
            return null;
        }
        return line.toString("ISO-8859-1");
    }

    /** 去掉 HTTP/1.1 分块帧，向上层暴露连续的响应正文。 */
    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;
        private boolean finished;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (finished) {
                return -1;
            }
            if (remaining == 0 && !nextChunk()) {
                return -1;
            }
            int wanted = (int) Math.min(length, remaining);
            int read = in.read(buffer, offset, wanted);
            if (read < 0) {
                finished = true;
                return -1;
            }
            remaining -= read;
            if (remaining == 0) {
                consumeChunkEnd();
            }
            return read;
        }

        private boolean nextChunk() throws IOException {
            String line = readLine(in);
            if (line == null) {
                finished = true;
                return false;
            }
            int semicolon = line.indexOf(';'); // 分块扩展，忽略即可
            String size = (semicolon < 0 ? line : line.substring(0, semicolon)).trim();
            long chunkSize;
            try {
                chunkSize = Long.parseLong(size, 16);
            } catch (NumberFormatException e) {
                throw new IOException("非法分块长度：" + line);
            }
            if (chunkSize == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // 跳过 trailer 头。
                }
                finished = true;
                return false;
            }
            remaining = chunkSize;
            return true;
        }

        private void consumeChunkEnd() throws IOException {
            int first = in.read();
            if (first == '\r') {
                in.read(); // 吃掉随后的 LF
            } else if (first != '\n' && first != -1) {
                throw new IOException("分块帧缺少结束换行");
            }
        }
    }

    /** 有 Content-Length 时限制读取长度，避免把后续连接数据混进响应体。 */
    static final class FixedLengthInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        FixedLengthInputStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            int wanted = (int) Math.min(length, remaining);
            int read = in.read(buffer, offset, wanted);
            if (read < 0) {
                remaining = 0;
                return -1;
            }
            remaining -= read;
            return read;
        }
    }
}
