package ai.neargo.shop.channel.notify.port;

import ai.neargo.shop.spi.trade.WxShippingPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual local HTTP verifies full split-shipping payload and prevents false mapped-source acknowledgment. */
class WxShippingGatewayTest {
    @Test
    void fullSplitRequestContainsBothCurrentPackages() throws Exception {
        try (Endpoint e = new Endpoint(0)) {
            assertThat(e.gateway.upload(command(List.of(item("A"), item("B")), true)).success()).isTrue();
            JsonNode body = e.body();
            assertThat(body.path("delivery_mode").asInt()).isEqualTo(2);
            assertThat(body.path("is_all_delivered").asBoolean()).isTrue();
            assertThat(body.path("shipping_list").size()).isEqualTo(2);
            assertThat(body.path("shipping_list").get(0).path("tracking_no").asText()).isEqualTo("STO-A12345");
            assertThat(body.path("shipping_list").get(1).path("tracking_no").asText()).isEqualTo("STO-B12345");
            assertThat(body.toString()).doesNotContain("SOURCE-A", "SOURCE-B");
        }
    }

    @Test
    void partialStoreDispatchDoesNotClaimWholePaymentDelivered() throws Exception {
        try (Endpoint e = new Endpoint(0)) {
            e.gateway.upload(command(List.of(item("A")), false));
            assertThat(e.body().path("delivery_mode").asInt()).isEqualTo(2);
            assertThat(e.body().path("is_all_delivered").asBoolean(true)).isFalse();
        }
    }

    @Test
    void mappedAlreadyFinishedCannotProveCurrentBodyWasUploaded() throws Exception {
        try (Endpoint e = new Endpoint(10060002)) {
            WxShippingPort.Result result = e.gateway.upload(command(List.of(item("A")), true));
            assertThat(result.success()).isFalse();
            assertThat(result.retryable()).isFalse();
            assertThat(result.code()).isEqualTo(10060002);
        }
    }

    @Test
    void oldScalarRequestRetainsItsIsolatedCompatibility() throws Exception {
        try (Endpoint e = new Endpoint(10060002)) {
            assertThat(e.gateway.upload(new WxShippingPort.Command("PAY", 1, "商品", "STO-A12345", "STO", "OPEN"))
                    .success()).isTrue();
            assertThat(e.body().path("delivery_mode").asInt()).isEqualTo(1);
            assertThat(e.body().path("shipping_list").size()).isEqualTo(1);
        }
    }

    @Test
    void moreThanFifteenPackagesFailsVisiblyWithoutTruncationOrNetwork() throws Exception {
        try (Endpoint e = new Endpoint(0)) {
            List<WxShippingPort.ShippingItem> items = IntStream.range(0, 16)
                    .mapToObj(i -> item("" + i)).toList();
            assertThat(e.gateway.upload(command(items, true)).success()).isFalse();
            assertThat(e.bodies).isEmpty();
        }
    }

    private static WxShippingPort.ShippingItem item(String suffix) {
        return new WxShippingPort.ShippingItem("SUB-" + suffix, "SOURCE-" + suffix, 0L,
                "STO-" + suffix + "12345", "STO", "门店" + suffix + "商品");
    }
    private static WxShippingPort.Command command(List<WxShippingPort.ShippingItem> items, boolean all) {
        return new WxShippingPort.Command("PAY", 1, "商品", items.getFirst().trackingNo(), "STO", "OPEN",
                items, 2, all, true);
    }
    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final WxShippingGateway gateway;
        final List<String> bodies = new CopyOnWriteArrayList<>();
        Endpoint(int code) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/cgi-bin/stable_token", exchange -> {
                byte[] response = "{\"access_token\":\"LOCAL-TOKEN\",\"expires_in\":7200}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.createContext("/wxa/sec/order/upload_shipping_info", exchange -> {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = ("{\"errcode\":" + code + ",\"errmsg\":\"local response\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            });
            server.start();
            gateway = new WxShippingGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    "LOCAL-APP", "LOCAL-SECRET", "LOCAL-MCH");
        }
        JsonNode body() throws Exception { return new ObjectMapper().readTree(bodies.getFirst()); }
        @Override public void close() { server.stop(0); }
    }
}
