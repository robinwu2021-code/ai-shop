package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.channel.config.FoodOfferClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreDishOfferQueryPortImpl;
import ai.neargo.shop.spi.product.DishOfferInternalApi;
import ai.neargo.shop.spi.product.DishOfferQueryPort;
import ai.neargo.shop.spi.product.DishOfferQueryPort.Offer;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoreDishOfferQueryPortTest {
    @Test void actualTokenWirePreservesUnknownStockAndRejectsRoundedMoneyOrQuantity() throws Exception {
        var response = new AtomicReference<>("[{\"skuNo\":\"SKU\",\"goodsNo\":\"G\",\"entityNo\":\"E\","
                + "\"storeNo\":\"S\",\"priceMinor\":500,\"currency\":\"CNY\",\"pricingStatus\":\"KNOWN\","
                + "\"available\":null,\"stockStatus\":\"UNKNOWN\",\"onSale\":true}]");
        String valid = response.get();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(DishOfferInternalApi.BATCH_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"skuNos\":[\"SKU\"]");
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodOfferClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(c -> {
                        assertThat(c).hasNotFailed();
                        var port = c.getBean(DishOfferQueryPort.class);
                        assertThat(port.getOffers("E", "S", List.of("SKU"))).containsExactly(
                                new Offer("SKU", "G", "E", "S", 500L, "CNY", "KNOWN", null, "UNKNOWN", true));
                        for (String invalid : List.of(valid.replace(":500", ":500.2"),
                                valid.replace(":500", ":\"500\""), valid.replace(":null", ":2.7")
                                        .replace("\"UNKNOWN\"", "\"KNOWN\""))) {
                            response.set(invalid);
                            assertThatThrownBy(() -> port.getOffers("E", "S", List.of("SKU")))
                                    .isInstanceOf(ServiceCallException.class);
                        }
                    });
        } finally { server.stop(0); }
    }

    @Test void rejectsForeignMisstatedMissingOrDuplicateAuthorityFacts() {
        for (var fact : List.of(new Offer("SKU", "G", "OTHER", "S", 500L, "CNY", "KNOWN", 1, "KNOWN", true),
                new Offer("SKU", "G", "E", "OTHER", 500L, "CNY", "KNOWN", 1, "KNOWN", true),
                new Offer("SKU", "G", "E", "S", null, "CNY", "KNOWN", 1, "KNOWN", true),
                new Offer("SKU", "G", "E", "S", 500L, "CNY", "KNOWN", 100, "UNKNOWN", true))) {
            var port = new CoreDishOfferQueryPortImpl(() -> request -> List.of(fact));
            assertThatThrownBy(() -> port.getOffers("E", "S", List.of("SKU")))
                    .isInstanceOf(ServiceCallException.class);
        }
    }
}
