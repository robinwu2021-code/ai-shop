package ai.neargo.shop.payclient;

import ai.neargo.shop.spi.pay.InternalHeaders;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 给支付域内部调用（{@code PayInternalApi}：收款页、运营支付页、商户双写）加调用方鉴权头。
 *
 * <p>独立支付服务（near-pay）的 {@code /internal/pay/v1/**} 按调用方鉴权：
 * {@code X-Internal-Caller} + {@code X-Internal-Credential}，服务端只存凭据的 SHA-256。
 * 只带共享令牌 {@code X-Internal-Token} 的请求会被 401 —— 2026-11-06 在 166 上 B 端收款接口全部 10500 就是这个。
 *
 * <p>配置：{@code shop.pay.internal-caller=<调用方码>}，凭据取 {@code shop.pay.internal-caller-credentials.<调用方码>}
 * （与 {@code RemotePaymentPort} 同一张凭据表，部署环境给，不进仓库）。没配调用方时不加头，行为与改动前一致。
 * 方法上已经显式声明了调用方头的（{@code PaymentInternalApi}）不覆盖。
 */
final class PayCallerHeaders implements ClientHttpRequestInterceptor {

    private final String caller;
    private final String credential;

    private PayCallerHeaders(String caller, String credential) {
        this.caller = caller;
        this.credential = credential;
    }

    /** 没配调用方返回 {@code null}；配了却没有凭据直接报错 */
    static PayCallerHeaders of(String caller, Map<String, String> credentials) {
        if (caller == null || caller.isBlank()) return null;
        String c = caller.trim();
        String credential = credentials == null ? null : credentials.get(c);
        if (credential == null || credential.isBlank()) {
            throw new IllegalStateException("shop.pay.internal-caller=" + c
                    + " 但没有凭据：请配置 shop.pay.internal-caller-credentials." + c);
        }
        return new PayCallerHeaders(c, credential);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsHeader(InternalHeaders.CALLER)) {
            request.getHeaders().set(InternalHeaders.CALLER, caller);
            request.getHeaders().set(InternalHeaders.CREDENTIAL, credential);
        }
        return execution.execute(request, body);
    }

    @Override
    public String toString() {
        return "PayCallerHeaders[caller=" + caller + "]";
    }
}
