package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 明文兜底传输的纯逻辑测试：状态行、响应头、Content-Length 与分块解码。
 *
 * <p>只用 JDK 流，不启动 Socket、不依赖 Android 类，可在 JVM 单测中直接跑。</p>
 */
public class CleartextHttpTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    @Test
    public void statusLineIsParsed() throws Exception {
        assertEquals(200, CleartextHttp.readStatus(stream("HTTP/1.1 200 OK\r\n")));
        assertEquals(404, CleartextHttp.readStatus(stream("HTTP/1.1 404 Not Found\r\n")));
        assertEquals(429, CleartextHttp.readStatus(stream("HTTP/1.0 429 Too Many Requests\r\n")));
    }

    @Test
    public void informationalStatusIsSkipped() throws Exception {
        assertEquals(200, CleartextHttp.readStatus(
                stream("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\n")));
    }

    @Test
    public void malformedStatusLineIsRejected() {
        try {
            CleartextHttp.readStatus(stream("NOT HTTP\r\n"));
            fail("非法状态行必须失败");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("状态行"));
        }
    }

    @Test
    public void headersAreLowerCasedAndStopAtBlankLine() throws Exception {
        Map<String, String> headers = CleartextHttp.readHeaders(stream(
                "Content-Type: application/json\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "\r\n"));
        assertEquals("application/json", headers.get("content-type"));
        assertEquals("chunked", headers.get("transfer-encoding"));
        assertEquals(2, headers.size());
    }

    @Test
    public void contentLengthLimitsBodyToDeclaredLength() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", "5");
        InputStream body = CleartextHttp.bodyStream(stream("helloEXTRA"), headers);
        assertEquals("hello", readAll(body));
    }

    @Test
    public void chunkedBodyIsDecodedAcrossChunks() throws Exception {
        String chunked = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");
        InputStream body = CleartextHttp.bodyStream(stream(chunked), headers);
        assertEquals("hello world", readAll(body));
    }

    @Test
    public void chunkedBodyWithExtensionIsDecoded() throws Exception {
        String chunked = "5;name=value\r\nhello\r\n0\r\n\r\n";
        Map<String, String> headers = new HashMap<>();
        headers.put("transfer-encoding", "chunked");
        InputStream body = CleartextHttp.bodyStream(stream(chunked), headers);
        assertEquals("hello", readAll(body));
    }
}
