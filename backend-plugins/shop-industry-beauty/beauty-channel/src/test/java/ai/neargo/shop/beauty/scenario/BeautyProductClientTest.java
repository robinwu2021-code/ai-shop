package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.beauty.port.RemoteServiceDefinitionPort;
import ai.neargo.shop.beauty.port.RemoteServiceProductFactsPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.ServiceDefinitionInternalApi;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import ai.neargo.shop.spi.product.ServiceProductFactsInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BeautyProductClientTest {
    @Test
    void productAuthorityErrorsRetainMeaningAcrossTheReverseCall() throws Exception {
        var code = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ServiceProductFactsInternalApi.PATH, exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("product-token");
            byte[] body = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"skuNo\":\"SKU\",\"goodsNo\":\"G\","
                    .concat("\"priceMinor\":9900,\"durationMin\":60}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "product-token");
            var port = new RemoteServiceProductFactsPort(http.client("PLATFORM", ServiceProductFactsInternalApi.class,
                    Duration.ofSeconds(2)));
            assertThat(port.get("E", "S", "SKU").durationMin()).isEqualTo(60);
            code.set(404);
            assertThatThrownBy(() -> port.get("E", "S", "SKU")).isInstanceOfSatisfying(BizException.class,
                    error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        } finally { server.stop(0); }
    }

    @Test
    void serviceDefinitionsCrossTheReverseCallWithAbsenceAndConflict() throws Exception {
        var code = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ServiceDefinitionInternalApi.PATH, exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("product-token");
            String path = exchange.getRequestURI().getPath();
            String definition = "{\"entityNo\":\"E\",\"storeNo\":\"S\",\"goodsNo\":\"G\",\"skuNo\":\"SKU\","
                    + "\"definitionVersion\":3,\"override\":true,\"timing\":{\"durationMin\":90,"
                    + "\"bufferBeforeMin\":0,\"bufferAfterMin\":15},\"steps\":[{\"stepCode\":\"SERVICE\","
                    + "\"sequenceNo\":0}],\"notes\":[],\"requirements\":[],\"status\":\"ACTIVE\","
                    + "\"snapshotHash\":\"h\",\"publishedAt\":\"2026-11-28T10:00:00Z\"}";
            String json = switch (path.substring(ServiceDefinitionInternalApi.PATH.length())) {
                case "/publish" -> definition;
                case "/current" -> "{\"definition\":" + definition + "}";
                case "/by-version" -> "{\"definition\":null}";
                default -> "{\"definitions\":[" + definition + "]}";
            };
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get(), code.get() == 200 ? body.length : -1);
            if (code.get() == 200) { exchange.getResponseBody().write(body); }
            exchange.close();
        });
        server.start();
        try {
            var http = new InternalHttp(service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    "product-token");
            var port = new RemoteServiceDefinitionPort(http.client("PLATFORM", ServiceDefinitionInternalApi.class,
                    Duration.ofSeconds(2)));
            var current = port.current("E", "S", "SKU");
            assertThat(current.definitionVersion()).isEqualTo(3);
            assertThat(current.timing().durationMin()).isEqualTo(90);
            assertThat(current.publishedAt()).isEqualTo(java.time.Instant.parse("2026-11-28T10:00:00Z"));
            assertThat(port.byVersion("E", "S", "SKU", 9)).as("没有这一版：空，不是 503").isNull();
            assertThat(port.list("E", "S", java.time.Instant.now())).hasSize(1);
            code.set(409);
            assertThatThrownBy(() -> port.publish(new ServiceDefinitionPort.Publish("E", "S", "SKU", false, 0,
                    new ServiceDefinitionPort.Timing(60, 0, 0), java.util.List.of(), java.util.List.of(), null, "R",
                    "staff"))).isInstanceOfSatisfying(BizException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        } finally { server.stop(0); }
    }
}
