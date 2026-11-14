package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceLocator;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Actual transport, credentials and error preservation; this is not a live catalog authority acceptance test. */
class BeautyCatalogTransportTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private ServiceCatalogQueryService client() throws Exception {
        Class<?> api = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.spi.BeautyCatalogInternalApi"));
        Class<?> remote = assertDoesNotThrow(() ->
                Class.forName("ai.neargo.shop.beauty.client.RemoteServiceCatalogQueryService"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/beauty/catalog", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("catalog-test-key");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("S").doesNotContain("actorNo");
            byte[] body = "{\"storeNo\":\"S\",\"name\":\"store\",\"status\":\"ACTIVE\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ServiceLocator locator = service -> Optional.of("http://127.0.0.1:" + server.getAddress().getPort());
        var http = new InternalHttp(locator, "catalog-test-key");
        return (ServiceCatalogQueryService) remote.getConstructor(api)
                .newInstance(http.client("BEAUTY", api, Duration.ofSeconds(2)));
    }
    @AfterEach
    void close() { if (server != null) { server.stop(0); } }
    @Test
    void transportsPublicLookupWithoutInventingAnActor() throws Exception {
        assertThat(client().getPublicStore("S").storeNo()).isEqualTo("S");
    }
    @Test
    void preservesNotFoundInsteadOfReturningAnEmptyPublicStore() throws Exception {
        var client = client();
        status.set(404);
        assertThatThrownBy(() -> client.getPublicStore("S")).isInstanceOf(BizException.class);
    }
}
