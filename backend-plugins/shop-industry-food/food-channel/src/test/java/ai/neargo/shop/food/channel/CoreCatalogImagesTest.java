package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreGoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.product.CatalogImageContracts.Expectation;
import ai.neargo.shop.spi.product.CatalogImageContracts.Facts;
import ai.neargo.shop.spi.product.CatalogImageContracts.Item;
import ai.neargo.shop.spi.product.CatalogImageContracts.Query;
import ai.neargo.shop.spi.product.CatalogImageFacts;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class CoreCatalogImagesTest {
    private static final String KEY = "E/S/goods/dish.jpg";
    private static final Query QUERY = new Query("E", "S", List.of(new Expectation("G", "REFERENCE",
            "https://img.example.com/" + KEY)));

    private static Facts facts(Query q, Instant at, Instant until) {
        var item = new Item("G", "REFERENCE",
                PricingSnapshotHash.of(QUERY.expectations().getFirst().expectedCoverRef()),
                KEY, 3L, PricingSnapshotHash.of(new MediaAssetQueryPort.AssetFact(KEY, "E", "S", "GOODS", "ACTIVE")),
                "a".repeat(64));
        return CatalogImageFacts.seal(new Facts(1, "CATALOG_IMAGE_IDENTITY", q, List.of(item), at, until, null));
    }

    @Test void standaloneCapabilityUsesActualTypedHttpWithOriginalTokenAndUnchangedReferences() throws Exception {
        var json = JsonMapper.builder().build();
        var count = new AtomicInteger();
        var now = Instant.now();
        var original = facts(QUERY, now, now.plusSeconds(30));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(GoodsCatalogInternalApi.IMAGES_PATH, exchange -> {
            count.incrementAndGet();
            try {
                assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                assertThat(json.readValue(exchange.getRequestBody().readAllBytes(), Query.class)).isEqualTo(QUERY);
                byte[] bytes = json.writeValueAsBytes(original);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(c -> {
                        assertThat(c).hasNotFailed();
                        assertThat(c.getBean(GoodsCatalogQueryPort.class).resolveCatalogImages(QUERY))
                                .isEqualTo(original);
                    });
            assertThat(count.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }

    @Test void missingForeignExpiredOrAlteredEvidenceCannotBecomeSuccessfulFacts() {
        var api = mock(GoodsCatalogInternalApi.class);
        var port = new CoreGoodsCatalogQueryPortImpl(() -> api);
        var now = Instant.now();
        var good = facts(QUERY, now.minusSeconds(1), now.plusSeconds(20));
        when(api.resolveImages(QUERY)).thenReturn(good);
        assertThat(port.resolveCatalogImages(QUERY)).isEqualTo(good);
        for (var invalid : List.of(facts(new Query("E", "OTHER", QUERY.expectations()), now, now.plusSeconds(20)),
                facts(QUERY, now.minusSeconds(31), now.minusSeconds(1)),
                new Facts(1, good.purpose(), QUERY, good.items(), good.verifiedAt(), good.validUntil(), "bad"))) {
            when(api.resolveImages(QUERY)).thenReturn(invalid);
            assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(ServiceCallException.class);
        }
        when(api.resolveImages(QUERY)).thenReturn(null);
        assertThatThrownBy(() -> port.resolveCatalogImages(QUERY)).isInstanceOf(ServiceCallException.class);
    }

    @Test void originalBusinessAndTransportFailuresPropagateExactlyOnceWithoutFallback() {
        var api = mock(GoodsCatalogInternalApi.class);
        var port = new CoreGoodsCatalogQueryPortImpl(() -> api);
        for (int status : new int[] {400, 403, 404, 409, 500, 0}) {
            var count = new AtomicInteger();
            doAnswer(inv -> {
                count.incrementAndGet();
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, status, "safe");
            }).when(api).resolveImages(any());
            assertThatThrownBy(() -> port.resolveCatalogImages(QUERY))
                    .isInstanceOf(status >= 400 && status < 500 ? BizException.class : ServiceCallException.class);
            assertThat(count.get()).isEqualTo(1);
        }
    }
}
