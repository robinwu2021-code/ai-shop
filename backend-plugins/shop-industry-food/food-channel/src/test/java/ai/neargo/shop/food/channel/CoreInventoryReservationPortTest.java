package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.spi.inventory.InventoryReservationInternalApi;
import ai.neargo.shop.spi.inventory.InventoryReservationPort;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Line;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.ReservedLine;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Scope;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.Snapshot;
import ai.neargo.shop.spi.inventory.InventoryReservationPort.State;
import ai.neargo.shop.svc.InternalHttp;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class CoreInventoryReservationPortTest {
    @Test void standaloneConfigurationCallsActualPlatformTransportAndRejectsForeignOrUnavailableFacts() throws Exception {
        Scope scope = new Scope("MAIN", "ENTITY", "ORDER");
        JsonMapper mapper = JsonMapper.builder().build();
        var mode = new AtomicInteger();
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(InventoryReservationInternalApi.ROOT, exchange -> {
            count.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"entityNo\":\"ENTITY\"", "\"externalRef\":\"ORDER\"")
                    .doesNotContain("ownerId", "itemId", "warehouseId");
            if (mode.get() == 2) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            Scope responseScope = mode.get() == 1 ? new Scope("MAIN", "FOREIGN", "ORDER") : scope;
            boolean reserve = exchange.getRequestURI().getPath().endsWith("/reserve");
            if (reserve) assertThat(body).contains("\"qty\":2", "\"storeNo\":\"STORE\"",
                    "\"fulfillmentRef\":\"SUB\"", "\"lifecycleVersion\":3");
            Snapshot result = reserve
                    ? new Snapshot(responseScope, "RESERVATION", State.HELD, 3, false,
                            List.of(new ReservedLine("SKU", 2, "SUB")))
                    : new Snapshot(responseScope, null, State.ABSENT, 0, false, List.of());
            byte[] response = mapper.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(InventoryReservationPort.class);
                        var port = context.getBean(InventoryReservationPort.class);
                        assertThat(port.query(scope).state()).isEqualTo(State.ABSENT);
                        Snapshot held = port.reserve(scope, List.of(new Line("SKU", "STORE", 2, "SUB")), 900, 3);
                        assertThat(held.state()).isEqualTo(State.HELD);
                        assertThat(held.originalLines()).containsExactly(new ReservedLine("SKU", 2, "SUB"));
                        mode.set(1);
                        assertThatThrownBy(() -> port.query(scope)).isInstanceOfSatisfying(BizException.class,
                                failure -> assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
                        mode.set(2);
                        assertThatThrownBy(() -> port.query(scope)).isInstanceOfSatisfying(BizException.class,
                                failure -> assertThat(failure.errorCode())
                                        .isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
                    });
            assertThat(count).hasValue(4);
        } finally {
            server.stop(0);
        }
    }
}
