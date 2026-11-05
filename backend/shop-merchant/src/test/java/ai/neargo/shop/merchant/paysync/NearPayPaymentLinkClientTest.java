package ai.neargo.shop.merchant.paysync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NearPayPaymentLinkClientTest {

    static final StoreSyncProperties PROPS = new StoreSyncProperties(true, "http://127.0.0.1:18093", null, "cred", null, null,
            null, null, Duration.ofSeconds(2));

    @SuppressWarnings("unchecked")
    static HttpClient http(int status, String body, AtomicReference<HttpRequest> seen) throws Exception {
        var client = mock(HttpClient.class);
        var resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        when(client.send(any(), any())).thenAnswer(i -> { seen.set(i.getArgument(0)); return resp; });
        return client;
    }

    @Test
    void createPostsToStoreLinksWithInternalHeadersAndParsesLink() throws Exception {
        var seen = new AtomicReference<HttpRequest>();
        var c = new NearPayPaymentLinkClient(PROPS, "tok", http(200,
                "{\"code\":0,\"data\":{\"requestNo\":\"REQ-00001\",\"payOrderNo\":\"PYO1\",\"status\":\"CREATED\","
                        + "\"url\":\"https://twpay.umeshop.ai/h5/c/abc\",\"expireAt\":1762400000000}}", seen));
        var link = c.create("ST 1", "acct-1", "REQ-00001", 35000, "訂金", 1440);
        assertThat(link.url()).isEqualTo("https://twpay.umeshop.ai/h5/c/abc");
        assertThat(link.expireAt()).isEqualTo(1762400000000L);
        assertThat(seen.get().uri().toString()).isEqualTo("http://127.0.0.1:18093/internal/pay/v2/biz/stores/ST+1/payment-links");
        assertThat(seen.get().headers().firstValue("X-Internal-Credential")).contains("cred");
        assertThat(seen.get().headers().firstValue("X-Internal-Caller")).contains("AI_SHOP_UME");
        assertThat(seen.get().method()).isEqualTo("POST");
    }

    @Test
    void statusIsGetWithoutUrlAndErrorsMap() throws Exception {
        var seen = new AtomicReference<HttpRequest>();
        var c = new NearPayPaymentLinkClient(PROPS, "tok", http(200,
                "{\"data\":{\"requestNo\":\"REQ-00001\",\"payOrderNo\":\"PYO1\",\"status\":\"PAID\",\"url\":null,\"expireAt\":1}}", seen));
        var s = c.status("ST1", "acct-1", "REQ-00001");
        assertThat(s.status()).isEqualTo("PAID");
        assertThat(s.url()).isNull();
        assertThat(seen.get().uri().getQuery()).contains("requestNo=REQ-00001").contains("operatorNo=acct-1");
        assertThatThrownBy(() -> new NearPayPaymentLinkClient(PROPS, "tok", http(403, "{\"message\":\"不是该商户的有效支付成员\"}", seen))
                .create("ST1", "x", "REQ-00002", 1, "", 15))
                .isInstanceOf(NearPayPaymentLinkClient.Rejected.class);
        assertThatThrownBy(() -> new NearPayPaymentLinkClient(PROPS, "tok", http(502, "", seen)).create("ST1", "x", "REQ-00003", 1, "", 15))
                .hasMessageContaining("HTTP 502");
        assertThatThrownBy(() -> new NearPayPaymentLinkClient(PROPS, "", http(200, "{}", seen))).hasMessageContaining("internal token");
    }
}
