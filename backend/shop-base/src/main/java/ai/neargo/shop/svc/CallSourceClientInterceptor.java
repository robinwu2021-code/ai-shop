package ai.neargo.shop.svc;

import ai.neargo.shop.spi.pay.CallSource;
import ai.neargo.shop.spi.pay.CallSourceCodec;
import ai.neargo.shop.spi.pay.CallSources;
import ai.neargo.shop.spi.pay.InternalHeaders;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 内部 HTTP 调用自动带来源（ADR-045 G9；TDD-支付服务-接入守卫模块pay-guard §2.7.4）：发起方不手填，
 * {@link InternalHttp} 造的每个客户端都挂着它。调用方已显式带了 {@link InternalHeaders#CALL_SOURCE}
 * （如 {@code RemotePaymentPort} 用命令对象里的那份）就不覆盖。
 */
public class CallSourceClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String encoded = request.getHeaders().getFirst(InternalHeaders.CALL_SOURCE);
        CallSource source;
        if (encoded == null) {
            source = CallSources.current();
            request.getHeaders().set(InternalHeaders.CALL_SOURCE, CallSourceCodec.encode(source));
        } else {
            source = CallSourceCodec.decode(encoded).orElse(null);
        }
        if (source != null && CallSources.isTraceId(source.traceId())
                && !request.getHeaders().containsHeader("X-Request-Id")) {
            request.getHeaders().set("X-Request-Id", source.traceId());
        }
        return execution.execute(request, body);
    }
}
