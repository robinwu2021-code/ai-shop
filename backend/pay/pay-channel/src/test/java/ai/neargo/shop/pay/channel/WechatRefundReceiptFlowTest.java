package ai.neargo.shop.pay.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Raw HTTP refund receipts must retain currency and missing identity through the real gateway parser. */
class WechatRefundReceiptFlowTest {
    @Test
    void refundQueryPreservesActualCurrency() throws Exception {
        PayGateway.QueryResult receipt = query("{\"status\":\"SUCCESS\",\"refund_id\":\"provider-usd\","
                + "\"amount\":{\"refund\":125,\"currency\":\"USD\"}}");
        assertThat(receipt.paid()).isTrue();
        assertThat(receipt.amountMinor()).isEqualTo(125);
        assertThat(receipt.currency()).isEqualTo("USD");
        assertThat(receipt.tradeNo()).isEqualTo("provider-usd");
    }

    @Test
    void missingRefundProviderIsNotSynthesizedAsLiteralNull() throws Exception {
        PayGateway.QueryResult receipt = query("{\"status\":\"SUCCESS\","
                + "\"amount\":{\"refund\":125,\"currency\":\"CNY\"}}");
        assertThat(receipt.tradeNo()).isNull();
    }

    @Test
    void missingRefundCurrencyIsNotInventedAsCny() throws Exception {
        PayGateway.QueryResult receipt = query("{\"status\":\"SUCCESS\",\"refund_id\":\"provider\","
                + "\"amount\":{\"refund\":125}}");
        assertThat(receipt.currency()).isNull();
    }

    private PayGateway.QueryResult query(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/v3/refund/domestic/refunds/RF-ORIGINAL", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            ChannelClient client = new LocalHttpChannel(server.getAddress().getPort());
            return new WechatDirectPayGateway(client, null, null, null, "merchant", "app", null)
                    .queryRefund("RF-ORIGINAL");
        } finally {
            server.stop(0);
        }
    }

    private record LocalHttpChannel(int port) implements ChannelClient {
        @Override
        public Map<String, Object> get(String api) {
            try {
                URI uri = URI.create("http://127.0.0.1:" + port + api);
                HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
                String body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
                return new ObjectMapper().readValue(body, new TypeReference<>() { });
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public Map<String, Object> post(String api, Map<String, Object> body) {
            throw new AssertionError("query must not send another refund");
        }
    }
}
