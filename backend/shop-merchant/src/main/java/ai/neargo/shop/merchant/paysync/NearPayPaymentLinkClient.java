package ai.neargo.shop.merchant.paysync;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 收款链接（动态码）客户端：转发到 near-pay {@code /internal/pay/v2/biz/stores/{storeNo}/payment-links}。
 * 与门店同步共用内部地址、调用方与凭据；near-pay 对每次命令重新校验商家成员资格。
 */
@Component
@ConditionalOnProperty(prefix = "shop.nearpay.payment-link", name = "enabled", havingValue = "true")
public class NearPayPaymentLinkClient {

    /** 可选有效期（分钟）：15 分钟 / 1 小时 / 24 小时 / 7 天 */
    public static final Set<Integer> VALIDITY_MINUTES = Set.of(15, 60, 1440, 10080);

    public record Link(String requestNo, String payOrderNo, String status, String url, long expireAt) {
    }

    /** near-pay 拒绝（参数、成员资格、门店不符）：message 来自 near-pay，不含内部细节以外的东西 */
    public static final class Rejected extends RuntimeException {
        private final int status;
        public Rejected(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final StoreSyncProperties props;
    private final String token;
    private final HttpClient http;

    @Autowired
    public NearPayPaymentLinkClient(StoreSyncProperties props, @Value("${shop.services.internal-token:}") String token) {
        this(props, token, HttpClient.newBuilder().connectTimeout(props.timeout()).build());
    }

    NearPayPaymentLinkClient(StoreSyncProperties props, String token, HttpClient http) {
        if (props.baseUrl() == null || props.baseUrl().isBlank() || props.credential() == null || props.credential().isBlank()
                || token == null || token.isBlank()) {
            throw new IllegalStateException("shop.nearpay.payment-link requires shop.nearpay.store-sync base-url, credential and internal token");
        }
        this.props = props;
        this.token = token;
        this.http = http;
    }

    public Link create(String storeNo, String operatorNo, String requestNo, long amountMinor, String subject, int expireMinutes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operatorNo", operatorNo);
        body.put("requestNo", requestNo);
        body.put("amountMinor", amountMinor);
        body.put("subject", subject);
        body.put("expireMinutes", expireMinutes);
        return send(HttpRequest.newBuilder(uri(storeNo, null)).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
    }

    public Link status(String storeNo, String operatorNo, String requestNo) {
        return send(HttpRequest.newBuilder(uri(storeNo, "?requestNo=" + enc(requestNo) + "&operatorNo=" + enc(operatorNo))).GET());
    }

    private URI uri(String storeNo, String query) {
        return URI.create(props.baseUrl().replaceAll("/+$", "") + "/internal/pay/v2/biz/stores/" + enc(storeNo) + "/payment-links"
                + (query == null ? "" : query));
    }

    private Link send(HttpRequest.Builder b) {
        HttpRequest req = b.timeout(props.timeout())
                .header("Content-Type", "application/json")
                .header("X-Internal-Token", token)
                .header("X-Internal-Caller", props.caller())
                .header("X-Internal-Credential", props.credential())
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("near-pay payment link unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("near-pay payment link interrupted", e);
        }
        JsonNode root = resp.body() == null || resp.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(resp.body());
        if (resp.statusCode() / 100 != 2) {
            if (resp.statusCode() >= 500) throw new IllegalStateException("near-pay payment link HTTP " + resp.statusCode());
            throw new Rejected(resp.statusCode(), root.path("message").asString(""));
        }
        JsonNode d = root.path("data");
        return new Link(d.path("requestNo").asString(null), d.path("payOrderNo").asString(null), d.path("status").asString(null),
                d.path("url").isNull() ? null : d.path("url").asString(null), d.path("expireAt").asLong());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
