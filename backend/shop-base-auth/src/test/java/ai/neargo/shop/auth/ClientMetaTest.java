package ai.neargo.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 可信客户端 IP（支付服务-安全规范 §4.5，G2）：只认反代覆写的 {@code X-Real-IP}，而且只在请求确实来自反代
 * （本机或私网）时才认；{@code X-Forwarded-For} 一律不认 —— 它的最左值是客户端自己写的。
 */
class ClientMetaTest {

    private static MockHttpServletRequest req(String remote, String realIp, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remote);
        if (realIp != null) {
            r.addHeader("X-Real-IP", realIp);
        }
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        return r;
    }

    @Test
    @DisplayName("★★★ 经本机 nginx：取它覆写的 X-Real-IP，客户端伪造的 XFF 最左值不算")
    void behindLocalProxyTrustsRealIpNotForgedForwardedFor() {
        assertThat(ClientMeta.clientIp(req("127.0.0.1", "203.0.113.5", "6.6.6.6, 203.0.113.5")))
                .isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("★★★ 直连应用（不经反代）：自带的 X-Real-IP 与 XFF 都不认，只认 TCP 对端")
    void directClientCannotForgeHeaders() {
        assertThat(ClientMeta.clientIp(req("198.51.100.7", "1.1.1.1", "2.2.2.2"))).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("★★ 私网反代（负载均衡在内网）同样可信")
    void privateNetworkProxyIsTrusted() {
        assertThat(ClientMeta.clientIp(req("10.0.3.4", "203.0.113.9", null))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("★★ 反代没写 X-Real-IP 时退回对端地址，不去读 XFF")
    void proxyWithoutRealIpFallsBackToRemote() {
        assertThat(ClientMeta.clientIp(req("127.0.0.1", null, "6.6.6.6"))).isEqualTo("127.0.0.1");
    }
}
