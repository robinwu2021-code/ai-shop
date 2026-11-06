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
    @DisplayName("★★★ 接线：PayClientConfig 配了调用方时，PayInternalApi 挂上调用方头；没配时不挂")
    void configWiresCallerHeadersIntoPayInternalApi() {
        var http = org.mockito.Mockito.mock(ai.neargo.shop.svc.InternalHttp.class);
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("shop.pay.internal-caller", "AI_SHOP_UME")
                .withProperty("shop.pay.internal-caller-credentials.AI_SHOP_UME", "cred-ume");
        new PayClientConfig().payInternalApi(http, env);
        org.mockito.Mockito.verify(http).client(org.mockito.ArgumentMatchers.eq("PAY"),
                org.mockito.ArgumentMatchers.eq(ai.neargo.shop.pay.client.PayInternalApi.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<org.springframework.http.client.ClientHttpRequestInterceptor>argThat(
                        i -> i instanceof PayCallerHeaders));
        var plain = org.mockito.Mockito.mock(ai.neargo.shop.svc.InternalHttp.class);
        new PayClientConfig().payInternalApi(plain, new org.springframework.mock.env.MockEnvironment());
        org.mockito.Mockito.verify(plain).client(org.mockito.ArgumentMatchers.eq("PAY"),
                org.mockito.ArgumentMatchers.eq(ai.neargo.shop.pay.client.PayInternalApi.class),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("★ toString 不带凭据")
    void toStringHidesCredential() {
        assertThat(PayCallerHeaders.of("AI_SHOP_UME", Map.of("AI_SHOP_UME", "cred-ume")).toString())
                .doesNotContain("cred-ume");
    }
}
