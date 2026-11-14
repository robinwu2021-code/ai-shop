package ai.neargo.shop.svc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * 发起方不手填来源（开发计划 M2.3；TDD-支付服务-接入守卫模块pay-guard §2.7.4）：
 * 每一次内部 HTTP 调用都自动带 {@code X-Call-Source}，取当前上下文。
 */
class CallSourceClientInterceptorTest {

    private final CallSourceClientInterceptor interceptor = new CallSourceClientInterceptor();

    private static MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create("http://pay/internal/pay/v1/payments"));
    }

    @Test
    @DisplayName("★★★ 没带来源的内部调用：自动挂上当前上下文的来源")
    void addsCurrentSource() throws Exception {
        MockClientHttpRequest req = request();
        try (var ignored = CallSources.bind(CallSources.Origin.http("req-xyz", "POST /mp/orders/{orderNo}/pay", "APP_C",
                "223.104.1.1", null, "U-1"))) {
            interceptor.intercept(req, new byte[0], (r, b) -> new MockClientHttpResponse());
        }

        CallSource sent = CallSourceCodec.decode(req.getHeaders().getFirst(InternalHeaders.CALL_SOURCE)).orElseThrow();
        assertThat(sent.traceId()).isEqualTo("req-xyz");
        assertThat(sent.originApi()).isEqualTo("POST /mp/orders/{orderNo}/pay");
    }

    @Test
    @DisplayName("★★★ 调用方已显式带了来源（如命令对象里的那份）：不覆盖")
    void keepsExplicitSource() throws Exception {
        MockClientHttpRequest req = request();
        String explicit = CallSourceCodec.encode(CallSources.asJob("pay-order-compensation", CallSources::current));
        req.getHeaders().set(InternalHeaders.CALL_SOURCE, explicit);

        interceptor.intercept(req, new byte[0], (r, b) -> new MockClientHttpResponse());

        assertThat(req.getHeaders().get(InternalHeaders.CALL_SOURCE)).containsExactly(explicit);
    }

    @Test
    @DisplayName("★★ InternalHttp 造出来的每个客户端都挂着它 —— 不靠各个远程实现自己记得")
    void everyInternalClientHasIt() {
        assertThat(InternalHttp.DEFAULT_INTERCEPTORS).anyMatch(i -> i instanceof CallSourceClientInterceptor);
    }
}
