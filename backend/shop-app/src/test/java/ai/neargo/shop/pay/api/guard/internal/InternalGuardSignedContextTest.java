package ai.neargo.shop.pay.api.guard.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.svc.context.SignedContextCodec;
import ai.neargo.svc.context.SignedContextInterceptor;
import ai.neargo.svc.context.SignedContextInterceptor.Caller;
import ai.neargo.svc.guard.SignedContextVerifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 支付内部入口对签名上下文<b>只比对、不拒绝</b>（TDD-网关层与统一权限 §2.7；开发计划 S）。
 * 打开拒绝是另一件要单独确认的事 —— 这里钉住的是：不管签名是什么样，内部入口的放行结论与今天相同。
 */
@DisplayName("支付内部入口 · 签名上下文只比对")
class InternalGuardSignedContextTest {

    private static final String KEY = "pay-observe-key";

    private static InternalGuard guard(String key, String mode) {
        InternalGuard guard = new InternalGuard(null, false, "");
        guard.signedContext(key, mode);
        return guard;
    }

    private static MockHttpServletRequest call() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/pay/v1/refunds");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    @DisplayName("★★★ 没带、签名是错的、签名是对的：三种情况都放行")
    void neverRejects() {
        InternalGuard guard = guard(KEY, "observe");
        assertThat(guard.authenticate(call()).principal()).as("没带签名").isNotNull();

        MockHttpServletRequest forged = call();
        forged.addHeader(SignedContextCodec.HEADER_CONTEXT, "Zm9v");
        forged.addHeader(SignedContextCodec.HEADER_SIGNATURE, "deadbeef");
        assertThat(guard.authenticate(forged).principal()).as("签名是错的也放行：这一步只比对").isNotNull();

        assertThat(guard("", "observe").authenticate(call()).principal()).as("没配密钥：不比对").isNotNull();
        assertThat(guard(KEY, "enforce").authenticate(forged).principal())
                .as("这个开关没有「拒绝」档：写成 enforce 也不会拒").isNotNull();
    }

    @Test
    @DisplayName("★★★ 出站拦截器签出来的头，支付这边用同一把密钥验得过")
    void outboundSignatureVerifiesOnThePaySide() throws Exception {
        byte[] body = "{\"payOrderNo\":\"P1\"}".getBytes(StandardCharsets.UTF_8);
        MockClientHttpRequest outbound = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("http://pay.internal/internal/pay/v1/refunds?dryRun=1"));
        new SignedContextInterceptor(() -> KEY,
                () -> new Caller("MCH-1", "MERCHANT", "ENT-1", "STORE-1", List.of("mch:pay:refund"), null))
                .intercept(outbound, body, (request, bytes) -> new MockClientHttpResponse());

        var result = new SignedContextVerifier(KEY).verify(
                outbound.getHeaders().getFirst(SignedContextCodec.HEADER_CONTEXT),
                outbound.getHeaders().getFirst(SignedContextCodec.HEADER_SIGNATURE),
                "POST", "/internal/pay/v1/refunds?dryRun=1", body, System.currentTimeMillis());
        assertThat(result.outcome()).isEqualTo(SignedContextVerifier.Outcome.OK);
        assertThat(result.context().orElseThrow().storeNo()).isEqualTo("STORE-1");
        assertThat(result.context().orElseThrow().points()).containsExactly("mch:pay:refund");

        // 没配密钥：什么头都不加
        MockClientHttpRequest plain = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://pay.internal/x"));
        new SignedContextInterceptor(() -> "", () -> new Caller(null, null, null, null, List.of(), null))
                .intercept(plain, new byte[0], (request, bytes) -> new MockClientHttpResponse());
        assertThat(plain.getHeaders().containsHeader(SignedContextCodec.HEADER_CONTEXT)).isFalse();
    }
}
