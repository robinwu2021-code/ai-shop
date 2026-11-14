package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/** 166 联调：生产 near-pay 的 /internal/pay/v1/** 按调用方鉴权，PayInternalApi 只带共享令牌时全部 401。 */
class PayCallerHeadersTest {

    static MockClientHttpRequest send(PayCallerHeaders headers) throws Exception {
        var req = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://pay/internal/pay/v1/x"));
        ClientHttpRequestExecution exec = (r, b) -> new MockClientHttpResponse(new byte[0], 200);
        headers.intercept(req, new byte[0], exec);
        return req;
    }

    @Test
    @DisplayName("★★★ 配了调用方：每个支付内部请求都带 X-Internal-Caller 与该调用方的凭据")
    void configuredCallerAddsCallerAndCredential() throws Exception {
        var h = PayCallerHeaders.of("AI_SHOP_UME", Map.of("AI_SHOP_UME", "cred-ume", "MALL", "cred-mall"));
        var req = send(h);
        assertThat(req.getHeaders().getFirst("X-Internal-Caller")).isEqualTo("AI_SHOP_UME");
        assertThat(req.getHeaders().getFirst("X-Internal-Credential")).isEqualTo("cred-ume");
    }

    @Test
    @DisplayName("★★ 没配调用方：不加头，行为与改动前一致（内嵌形态与本机不受影响）")
    void unconfiguredCallerAddsNothing() throws Exception {
        assertThat(PayCallerHeaders.of(null, Map.of())).isNull();
        assertThat(PayCallerHeaders.of(" ", Map.of())).isNull();
    }

    @Test
    @DisplayName("★★ 配了调用方却没给凭据：启动即报错，点名配置项，不发出一个注定 401 的请求")
    void callerWithoutCredentialFailsFast() {
        assertThatThrownBy(() -> PayCallerHeaders.of("AI_SHOP_UME", Map.of()))
                .hasMessageContaining("shop.pay.internal-caller-credentials.AI_SHOP_UME");
    }

    @Test
    @DisplayName("★ 调用方自己在方法上声明了调用方头（PaymentInternalApi）时不覆盖")
    void explicitCallerHeaderWins() throws Exception {
        var h = PayCallerHeaders.of("AI_SHOP_UME", Map.of("AI_SHOP_UME", "cred-ume"));
        var req = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://pay/internal/pay/v1/payments"));
        req.getHeaders().set("X-Internal-Caller", "MALL");
        req.getHeaders().set("X-Internal-Credential", "cred-mall");
        h.intercept(req, new byte[0], (r, b) -> new MockClientHttpResponse(new byte[0], 200));
        assertThat(req.getHeaders().getFirst("X-Internal-Caller")).isEqualTo("MALL");
        assertThat(req.getHeaders().getFirst("X-Internal-Credential")).isEqualTo("cred-mall");
    }

    @Test
    @DisplayName("★★★ 接线：配了调用方时注册成 PAY 的按服务拦截器 —— 收款页、运营支付页、双写等所有 PAY 客户端都带上")
    void configRegistersCallerHeadersForEveryPayClient() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("shop.pay.internal-caller", "AI_SHOP_UME")
                .withProperty("shop.pay.internal-caller-credentials.AI_SHOP_UME", "cred-ume");
        var bean = new PayClientConfig().payCallerHeaders(env);
        assertThat(bean.service()).isEqualTo(ai.neargo.shop.svc.ServiceName.PAY);
        assertThat(bean.interceptor()).isInstanceOf(PayCallerHeaders.class);
    }

    interface AnyPayApi {
        @org.springframework.web.service.annotation.GetExchange("/internal/pay/v1/biz/stores/S1/collect-status")
        String collectStatus();
    }

    @Test
    @DisplayName("★★★ 真发请求：InternalHttp 造的任意 PAY 客户端（如收款页的 PayBizInternalApi）都带调用方头")
    void anyPayClientBuiltByInternalHttpSendsCallerHeaders() throws Exception {
        var seen = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            seen.set(ex.getRequestHeaders());
            byte[] b = "\"ok\"".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            var env = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("shop.pay.internal-caller", "AI_SHOP_UME")
                    .withProperty("shop.pay.internal-caller-credentials.ai-shop-ume", "cred-ume");
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                    Map.of("payCaller", new PayClientConfig().payCallerHeaders(env)));
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var http = new ai.neargo.shop.svc.InternalHttp(svc -> java.util.Optional.of(base), "tok",
                    beans.getBeanProvider(ai.neargo.shop.svc.InternalServiceInterceptor.class));
            http.client(ai.neargo.shop.svc.ServiceName.PAY, AnyPayApi.class, java.time.Duration.ofSeconds(2)).collectStatus();
            assertThat(seen.get().getFirst("X-Internal-Caller")).isEqualTo("AI_SHOP_UME");
            assertThat(seen.get().getFirst("X-Internal-Credential")).isEqualTo("cred-ume");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("★★ 凭据键不区分大小写与分隔符：环境变量经 relaxed binding 变成 ai-shop-ume / aishopume 也找得到")
    void credentialLookupIgnoresCaseAndSeparators() throws Exception {
        for (String key : new String[] {"ai-shop-ume", "aishopume", "ai.shop.ume", "AI_SHOP_UME"}) {
            var req = send(PayCallerHeaders.of("AI_SHOP_UME", Map.of(key, "cred-ume")));
            assertThat(req.getHeaders().getFirst("X-Internal-Credential")).as(key).isEqualTo("cred-ume");
        }
    }

    @Test
    @DisplayName("★ toString 不带凭据")
    void toStringHidesCredential() {
        assertThat(PayCallerHeaders.of("AI_SHOP_UME", Map.of("AI_SHOP_UME", "cred-ume")).toString())
                .doesNotContain("cred-ume");
    }
}
