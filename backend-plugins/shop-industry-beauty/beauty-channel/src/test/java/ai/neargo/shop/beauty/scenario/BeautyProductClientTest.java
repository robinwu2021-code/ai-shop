package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.beauty.port.RemoteServiceProductFactsPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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
}
