package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.channel.config.FoodMediaClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreMediaAssetQueryPortImpl;
import ai.neargo.shop.spi.media.MediaAssetInternalApi;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.media.MediaAssetQueryPort.AssetFact;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CoreMediaAssetQueryPortTest {
    @Test void actualTransportUsesServiceTokenAndTypedBatch() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(MediaAssetInternalApi.BATCH_PATH, ex -> {
            assertThat(ex.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
            assertThat(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"assetKeys\":[\"E/S/goods/a.jpg\"]");
            byte[] bytes = ("[{\"assetKey\":\"E/S/goods/a.jpg\",\"entityNo\":\"E\",\"storeNo\":\"S\","
                    + "\"bizType\":\"GOODS\",\"status\":\"ACTIVE\"}]").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodMediaClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(c -> {
                        assertThat(c).hasNotFailed();
                        assertThat(c.getBean(MediaAssetQueryPort.class)
                                .getAssets("E", "S", List.of("E/S/goods/a.jpg")))
                                .containsExactly(new AssetFact("E/S/goods/a.jpg", "E", "S", "GOODS", "ACTIVE"));
                    });
        } finally { server.stop(0); }
    }

    @Test void rejectsMissingMalformedAndDuplicateBatchesRatherThanCallingThemMissingAssets() {
        var fact = new AssetFact("E/S/goods/a.jpg", "E", "S", "GOODS", "ACTIVE");
        for (List<AssetFact> response : Arrays.asList(null, Arrays.asList((AssetFact) null),
                List.of(fact, fact), List.of(new AssetFact("E/S/goods/extra.jpg", "E", "S", "GOODS", "ACTIVE")))) {
            var port = new CoreMediaAssetQueryPortImpl(() -> req -> response);
            assertThatThrownBy(() -> port.getAssets("E", "S", List.of("E/S/goods/a.jpg")))
                    .isInstanceOf(ServiceCallException.class);
        }
        var unavailable = new CoreMediaAssetQueryPortImpl(() -> { throw new IllegalStateException("offline"); });
        assertThatThrownBy(() -> unavailable.getAssets("E", "S", List.of("E/S/goods/a.jpg")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void rejectsMalformedForeignPrivateAndDeletedResponses() {
        for (var fact : List.of(new AssetFact("E/S/goods/a.jpg", "OTHER", "S", "GOODS", "ACTIVE"),
                new AssetFact("E/S/goods/a.jpg", "E", "OTHER", "GOODS", "ACTIVE"),
                new AssetFact("E/S/goods/a.jpg", "E", "S", "QUAL", "ACTIVE"),
                new AssetFact("E/S/goods/a.jpg", "E", "S", "GOODS", "PURGED"),
                new AssetFact("E/S/goods/a.jpg", "E", "S", "GOODS", "RECLAIMABLE"))) {
            var port = new CoreMediaAssetQueryPortImpl(() -> req -> List.of(fact));
            assertThatThrownBy(() -> port.getAssets("E", "S", List.of("E/S/goods/a.jpg")))
                    .isInstanceOf(ServiceCallException.class);
        }
    }
}
