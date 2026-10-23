package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.channel.config.FoodStoreCardClientConfiguration;
import ai.neargo.shop.spi.user.StoreCardInternalApi;
import ai.neargo.shop.spi.user.StoreCardQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoreStoreCardQueryPortTest {
    @Test void realTokenTransportKeepsAuthorityAndRejectsCoercionMissingFieldsAndDuplicates() throws Exception {
        var response = new AtomicReference<>("[{\"storeNo\":\"S\",\"storeName\":\"Store\",\"entityNo\":\"E\","
                + "\"logo\":null,\"status\":\"READONLY\",\"isDefault\":false,\"openHours\":null,\"address\":null,"
                + "\"latE6\":null,\"lngE6\":null,\"rating\":0,\"ratingCount\":0}]");
        String valid = response.get();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(StoreCardInternalApi.BATCH_PATH, e -> {
            assertThat(e.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(e.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).contains("\"storeNos\":[\"S\"]");
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodStoreCardClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" +
                                    server.getAddress().getPort()).run(c -> {
                        assertThat(c).hasNotFailed(); var port = c.getBean(StoreCardQueryPort.class);
                        assertThat(port.cards(List.of("S")).get("S").status()).isEqualTo("READONLY");
                        assertThat(port.cards(List.of("S")).get("S").openHours()).isNull();
                        for (String bad : List.of(valid.replace("\"S\"", "\"OTHER\""),
                                valid.replace("\"rating\":0", "\"rating\":\"0\""),
                                valid.replace("\"ratingCount\":0", "\"ratingCount\":0.7"),
                                valid.replace("false", "\"false\""), valid.replace("\"openHours\":null",
                                        "\"openHours\":1"),
                                valid.replace("\"rating\":0,", ""), valid.substring(0, valid.length()-1) +
                                        "," + valid.substring(1))) {
                            response.set(bad);
                            assertThatThrownBy(() -> port.cards(List.of("S"))).isInstanceOf(ServiceCallException.class);
                        }
                    });
        } finally { server.stop(0); }
    }
}
