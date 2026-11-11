package io.github.wxmyyds.discordautotranslate.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * 明文放行名单：只允许回环主机与配置里的 API 主机。
 *
 * <p>该名单决定 Discord 进程内哪些主机可以绕过明文 HTTP 限制，
 * 因此「不该放行的主机不能被放行」是安全边界，必须由单元测试固定。</p>
 */
public class CleartextHostsTest {

    @After
    public void resetConfiguredHost() {
        CleartextHosts.updateConfiguredUrl(null);
    }

    // ------------------------------------------------------------ 回环主机

    @Test
    public void loopbackHostsArePermitted() {
        assertTrue(CleartextHosts.isPermitted("127.0.0.1"));
        assertTrue(CleartextHosts.isPermitted("127.1.2.3"));
        assertTrue(CleartextHosts.isPermitted("localhost"));
        assertTrue(CleartextHosts.isPermitted("LOCALHOST"));
        assertTrue(CleartextHosts.isPermitted("localhost."));
        assertTrue(CleartextHosts.isPermitted("::1"));
        assertTrue(CleartextHosts.isPermitted("[::1]"));
        assertTrue(CleartextHosts.isPermitted("0:0:0:0:0:0:0:1"));
    }

    @Test
    public void nonLoopbackHostsAreNotPermittedByDefault() {
        assertFalse(CleartextHosts.isPermitted("example.com"));
        assertFalse(CleartextHosts.isPermitted("api.openai.com"));
        assertFalse(CleartextHosts.isPermitted("10.0.2.2"));
        assertFalse(CleartextHosts.isPermitted("192.168.1.10"));
        assertFalse(CleartextHosts.isPermitted("128.0.0.1"));
        assertFalse(CleartextHosts.isPermitted("127.0.0.1.example.com"));
        assertFalse(CleartextHosts.isPermitted(null));
        assertFalse(CleartextHosts.isPermitted(""));
        assertFalse(CleartextHosts.isPermitted("   "));
    }

    // ------------------------------------------------------------ 配置主机

    @Test
    public void configuredApiHostIsPermitted() {
        CleartextHosts.updateConfiguredUrl("http://192.168.1.10:8080/v1");
        assertEquals("192.168.1.10", CleartextHosts.configuredHost());
        assertTrue(CleartextHosts.isPermitted("192.168.1.10"));
        assertTrue(CleartextHosts.isPermitted("192.168.1.10"));
        // 其他主机仍然被拒绝。
        assertFalse(CleartextHosts.isPermitted("192.168.1.11"));
        assertFalse(CleartextHosts.isPermitted("example.com"));
    }

    @Test
    public void configuredApiHostIsCaseInsensitive() {
        CleartextHosts.updateConfiguredUrl("http://MyNas.local:3000/v1");
        assertTrue(CleartextHosts.isPermitted("mynas.local"));
        assertTrue(CleartextHosts.isPermitted("MyNas.Local"));
    }

    @Test
    public void configuredUrlWithPathQueryAndCredentialsIsParsed() {
        CleartextHosts.updateConfiguredUrl("http://user:pass@example.org:8000/v1?x=1#frag");
        assertEquals("example.org", CleartextHosts.configuredHost());
        CleartextHosts.updateConfiguredUrl("https://api.example.com");
        assertEquals("api.example.com", CleartextHosts.configuredHost());
    }

    @Test
    public void invalidOrEmptyConfiguredUrlClearsHost() {
        CleartextHosts.updateConfiguredUrl("http://example.org:8080/v1");
        CleartextHosts.updateConfiguredUrl("");
        assertNull(CleartextHosts.configuredHost());
        CleartextHosts.updateConfiguredUrl("http://example.org:8080/v1");
        CleartextHosts.updateConfiguredUrl(null);
        assertNull(CleartextHosts.configuredHost());
        CleartextHosts.updateConfiguredUrl("not a url");
        assertNull(CleartextHosts.configuredHost());
    }

    @Test
    public void configuredHostWithSchemeOnlyDoesNotPermitOthers() {
        CleartextHosts.updateConfiguredUrl("http://127.0.0.1:8317/v1");
        // 回环本来就允许，配置主机与回环一致时不会引入额外主机。
        assertTrue(CleartextHosts.isPermitted("127.0.0.1"));
        assertFalse(CleartextHosts.isPermitted("localhost.example.com"));
    }
}
