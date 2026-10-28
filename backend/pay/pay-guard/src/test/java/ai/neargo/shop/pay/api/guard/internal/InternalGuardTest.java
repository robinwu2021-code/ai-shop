package ai.neargo.shop.pay.api.guard.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.access.entity.PayApiRequestLog;
import ai.neargo.shop.pay.api.guard.PortalFace;
import ai.neargo.shop.pay.api.guard.core.GuardFilter;
import ai.neargo.shop.pay.api.guard.core.RequestLogWriter;
import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 内部入口只识别、不拒绝（ADR-045 G9；开发计划 M2.4）：调用方码与来源头读进记录，缺失或损坏照样放行、记 UNKNOWN；
 * {@code verify-credential=true} 时恢复原来「调用方码 + 凭据」的 401。
 */
class InternalGuardTest {

    private static final CallSource SOURCE = new CallSource("shop-app", null, "api-01", "10.0.1.12", "req-abc12345",
            "POST /mp/orders/{orderNo}/pay", "APP_C", "223.104.1.1", "ab".repeat(32), "U-1", "2026-10-28T08:00:00Z");

    /** 只认 MALL / mall-secret */
    private static final InternalCallerRegistry REGISTRY = (code, credential) ->
            "MALL".equals(code) && "mall-secret".equals(credential) ? Optional.of("MALL") : Optional.empty();

    private final List<PayApiRequestLog> rows = new ArrayList<>();

    /** 不限来源 IP（只验识别与凭据开关） */
    private GuardFilter filter(boolean verify) {
        return filter(verify, "");
    }

    private GuardFilter filter(boolean verify, String allowedIps) {
        return new GuardFilter(PortalFace.INTERNAL, new InternalGuard(REGISTRY, verify, allowedIps),
                new RequestLogWriter(rows::add));
    }

    private static MockHttpServletRequest request(String caller, String credential, String source) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/internal/pay/v1/payments");
        r.setRemoteAddr("10.0.1.12");
        r.setContent("{\"bizType\":\"MALL_ORDER\"}".getBytes(StandardCharsets.UTF_8));
        if (caller != null) {
            r.addHeader(InternalHeaders.CALLER, caller);
        }
        if (credential != null) {
            r.addHeader(InternalHeaders.CREDENTIAL, credential);
        }
        if (source != null) {
            r.addHeader(InternalHeaders.CALL_SOURCE, source);
        }
        return r;
    }

    /** 记下 controller 看到的调用方码 */
    private static final class Endpoint extends MockFilterChain {
        Object caller;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            caller = request.getAttribute(InternalGuard.CALLER_ATTRIBUTE);
        }
    }

    @Test
    @DisplayName("★★★ 带来源头：放行；记录里 entry=INTERNAL_HTTP、追踪号沿用来源的、来源 8 列与操作人都落上，业务线按调用方码补")
    void sourceIsRecorded() throws Exception {
        Endpoint endpoint = new Endpoint();
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter(false).doFilter(request("MALL", null, CallSourceCodec.encode(SOURCE)), resp, endpoint);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(endpoint.caller).isEqualTo("MALL");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getEntry()).isEqualTo("INTERNAL_HTTP");
            assertThat(r.getSourceType()).isEqualTo("INTERNAL");
            assertThat(r.getAppNo()).isEqualTo("MALL");
            assertThat(r.getTraceId()).isEqualTo("req-abc12345");
            assertThat(r.getSourceService()).isEqualTo("shop-app");
            assertThat(r.getSourceModule()).isEqualTo("MALL");
            assertThat(r.getSourceInstance()).isEqualTo("api-01");
            assertThat(r.getSourceIp()).isEqualTo("10.0.1.12");
            assertThat(r.getOriginApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
            assertThat(r.getOriginClient()).isEqualTo("APP_C");
            assertThat(r.getOriginIp()).isEqualTo("223.104.1.1");
            assertThat(r.getOriginUaDigest()).isEqualTo("ab".repeat(32));
            assertThat(r.getOperatorNo()).isEqualTo("U-1");
            assertThat(r.getClientIp()).as("支付实际看到的对端").isEqualTo("10.0.1.12");
            assertThat(r.getOutcome()).isEqualTo("ACCEPTED");
        });
    }

    @Test
    @DisplayName("★★★ 没带来源头、来源头损坏、连调用方码都没有：照样放行，记 UNKNOWN —— 留痕不挡调用")
    void brokenOrMissingStillPasses() throws Exception {
        for (MockHttpServletRequest req : List.of(request("MALL", null, null), request("MALL", null, "%%%garbage"),
                request(null, null, null))) {
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter(false).doFilter(req, resp, new Endpoint());
            assertThat(resp.getStatus()).isEqualTo(200);
        }

        assertThat(rows).hasSize(3).allSatisfy(r -> {
            assertThat(r.getSourceService()).isEqualTo(CallSources.UNKNOWN);
            assertThat(r.getOutcome()).isEqualTo("ACCEPTED");
        });
        assertThat(rows.get(2).getAppNo()).isEqualTo(CallSources.UNKNOWN);
    }

    @Test
    @DisplayName("★★★ 默认不验凭据：凭据错也放行（G9）；打开 verify-credential 恢复 401，且不进端点")
    void credentialCheckIsASwitch() throws Exception {
        MockHttpServletResponse open = new MockHttpServletResponse();
        filter(false).doFilter(request("MALL", "wrong", null), open, new Endpoint());
        assertThat(open.getStatus()).isEqualTo(200);

        Endpoint endpoint = new Endpoint();
        MockHttpServletResponse closed = new MockHttpServletResponse();
        filter(true).doFilter(request("MALL", "wrong", null), closed, endpoint);
        assertThat(closed.getStatus()).isEqualTo(401);
        assertThat(endpoint.caller).isNull();

        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter(true).doFilter(request("MALL", "mall-secret", null), ok, new Endpoint());
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(rows.get(1).getOutcome()).isEqualTo("REJECTED_AUTH");
    }

    @Test
    @DisplayName("★★★ IP 白名单（M2.8）：对端在名单外 → 403、不进端点、记 REJECTED_FORBIDDEN；伪造代理头没用，只认 TCP 对端")
    void allowListUsesThePeerAddress() throws Exception {
        MockHttpServletRequest inside = request("MALL", null, null);
        inside.setRemoteAddr("10.0.1.12");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter(false, "10.0.0.0/8").doFilter(inside, ok, new Endpoint());
        assertThat(ok.getStatus()).isEqualTo(200);

        MockHttpServletRequest outside = request("MALL", null, CallSourceCodec.encode(SOURCE));
        outside.setRemoteAddr("203.0.113.9");
        outside.addHeader("X-Real-IP", "10.0.0.5");
        Endpoint endpoint = new Endpoint();
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter(false, "10.0.0.0/8").doFilter(outside, denied, endpoint);

        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(endpoint.caller).isNull();
        assertThat(rows.get(1).getOutcome()).isEqualTo("REJECTED_FORBIDDEN");
        assertThat(rows.get(1).getResponseMasked()).contains("203.0.113.9");
    }

    @Test
    @DisplayName("★★★ 默认名单只有本机回环（今天 pay-svc 与 shop-app 同机）；两种 IPv6 回环写法都认；名单配空 = 不限")
    void defaultIsLoopbackOnly() throws Exception {
        assertThat(InternalGuard.DEFAULT_ALLOWED_IPS).isEqualTo("127.0.0.1/32,::1/128");
        for (String peer : java.util.List.of("127.0.0.1", "0:0:0:0:0:0:0:1")) {
            MockHttpServletRequest r = request("MALL", null, null);
            r.setRemoteAddr(peer);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            filter(false, InternalGuard.DEFAULT_ALLOWED_IPS).doFilter(r, resp, new Endpoint());
            assertThat(resp.getStatus()).as(peer).isEqualTo(200);
        }
        MockHttpServletRequest lan = request("MALL", null, null);
        lan.setRemoteAddr("10.0.1.12");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter(false, InternalGuard.DEFAULT_ALLOWED_IPS).doFilter(lan, denied, new Endpoint());
        assertThat(denied.getStatus()).isEqualTo(403);

        MockHttpServletResponse open = new MockHttpServletResponse();
        filter(false, "").doFilter(lan, open, new Endpoint());
        assertThat(open.getStatus()).isEqualTo(200);
    }
}
