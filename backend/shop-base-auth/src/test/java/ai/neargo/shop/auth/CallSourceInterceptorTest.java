package ai.neargo.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSources;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

/**
 * HTTP 请求的来源绑定（开发计划 M2.1；TDD-支付服务-接入守卫模块pay-guard §2.3 A）：业务服务里任何地方调支付，
 * {@code CallSources.current()} 都带着「哪个接口、哪一端、哪个终端用户 IP、谁」—— IP 只取可信代理头（{@link ClientMeta}）。
 */
class CallSourceInterceptorTest {

    private final CallSourceInterceptor interceptor = new CallSourceInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestMetaContext.clear();
    }

    private static MockHttpServletRequest request(String remote, String realIp) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/mp/orders/SO123/pay");
        r.setRemoteAddr(remote);
        r.addHeader("X-Real-IP", realIp);
        r.addHeader("X-Forwarded-For", "6.6.6.6");
        r.addHeader("User-Agent", "Mozilla/5.0 (iPhone)");
        r.addHeader("X-Request-Id", "req-12345678");
        r.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/mp/orders/{orderNo}/pay");
        return r;
    }

    @Test
    @DisplayName("★★★ 请求里调支付：来源是路由模板、端码、可信 IP、登录人、请求号；请求结束解绑")
    void bindsTheRequest() throws Exception {
        RequestMetaContext.set(new RequestMetaContext.Meta("ignored", "APP_C", null));
        LoginUser user = LoginUser.consumer("U-1001", "小王");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        MockHttpServletRequest req = request("10.0.0.2", "223.104.1.1");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        interceptor.preHandle(req, resp, new Object());
        CallSource s = CallSources.current();
        interceptor.afterCompletion(req, resp, new Object(), null);

        assertThat(s.originApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
        assertThat(s.originClient()).isEqualTo("APP_C");
        assertThat(s.originIp()).isEqualTo("223.104.1.1");
        assertThat(s.operatorNo()).isEqualTo("U-1001");
        assertThat(s.traceId()).isEqualTo("req-12345678");
        assertThat(s.originUaDigest()).hasSize(64);
        assertThat(CallSources.current().originClient()).as("请求结束解绑").isEqualTo(CallSources.UNKNOWN);
    }

    @Test
    @DisplayName("★★★ 直连应用伪造代理头：终端用户 IP 仍是 TCP 对端（与限流、白名单同一个来源）")
    void forgedHeadersDoNotChangeOriginIp() throws Exception {
        MockHttpServletRequest req = request("198.51.100.7", "1.1.1.1");

        interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
        try {
            assertThat(CallSources.current().originIp()).isEqualTo("198.51.100.7");
        } finally {
            interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
        }
    }

    @Test
    @DisplayName("★★ 匿名、没经认证链：操作人空、端码 UNKNOWN；没有路由模板就用原路径；请求号不合规就另起")
    void anonymousRequest() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        req.setRemoteAddr("10.0.0.2");
        req.addHeader("X-Request-Id", "bad id with spaces");

        interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
        CallSource s = CallSources.current();
        interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);

        assertThat(s.operatorNo()).isNull();
        assertThat(s.originClient()).isEqualTo(CallSources.UNKNOWN);
        assertThat(s.originApi()).isEqualTo("GET /actuator/health");
        assertThat(s.traceId()).matches("[0-9a-f]{32}");
    }
}
