package ai.neargo.shop.food.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.channel.config.FoodCatalogClientConfiguration;
import ai.neargo.shop.food.channel.port.CoreGoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogInternalApi;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class CoreCatalogMarketTest {
    private static final GoodsQuery GOODS = new GoodsQuery("E", "S", "G", "AE");
    private static final SkuQuery SKU = new SkuQuery("E", "S", "SKU", "AE");

    private static MarketFacts market(Instant at) {
        return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", new MarketScope("E", "S"),
                new Market("AE", "AED", 2), at, at.plusSeconds(30), null));
    }

    private static GoodsFacts goods(GoodsQuery q, Instant at) {
        return CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", q, market(at),
                new GoodsCatalogQueryPort.GoodsFact("G", "E", "S", "Dish", null, false, "PENDING", 3,
                        List.of(new GoodsCatalogQueryPort.SkuFact("SKU", null, 8))), at, at.plusSeconds(20), null));
    }

    private static SkuFacts sku(SkuQuery q, Instant at) {
        return CatalogMarketFacts.seal(new SkuFacts(1, "CATALOG_SKU", q, market(at),
                new GoodsCatalogQueryPort.SkuIdentityFact("SKU", "G", "E", "S", 8), 3L, at, at.plusSeconds(20), null));
    }

    @Test void skuMeasurementRequiresAuthenticUnmodifiedAndFreshOwnerEvidence() {
        var api = mock(GoodsCatalogInternalApi.class);
        var port = new CoreGoodsCatalogQueryPortImpl(() -> api);
        var now = Instant.now().minusSeconds(1);
        var source = sku(SKU, now);
        var facts = ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.seal(
                new ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.Facts(
                        1, source, "EA", now, source.validUntil(), null));
        when(api.lookupSkuMeasurement(SKU)).thenReturn(facts);
        assertThat(port.getCatalogSkuMeasurement(SKU).unit()).isEqualTo("EA");
        when(api.lookupSkuMeasurement(SKU)).thenReturn(
                new ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.Facts(
                        1, source, "KG", now, source.validUntil(), facts.receiptNo()));
        assertThatThrownBy(() -> port.getCatalogSkuMeasurement(SKU))
                .isInstanceOf(ServiceCallException.class);
    }

    @Test void skuMeasurementWireRejectsCoercedUnitAndSchemaTokens() {
        var json = JsonMapper.builder().build();
        var at = Instant.now().minusSeconds(1);
        var identity = sku(SKU, at);
        var value = ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.seal(
                new ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.Facts(
                        1, identity, "EA", at, identity.validUntil(), null));
        String raw = json.writeValueAsString(value);
        assertThatThrownBy(() -> json.readValue(raw.replace("\"unit\":\"EA\"", "\"unit\":123"),
                ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.Facts.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> json.readValue(raw.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.Facts.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void standaloneBothCapabilitiesUseActualTypedPlatformHttpAndOriginalToken() throws Exception {
        var json = JsonMapper.builder().build();
        var calls = new AtomicInteger();
        var now = Instant.now();
        var gf = goods(GOODS, now);
        var sf = sku(SKU, now);
        var measurement = ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.seal(
                new ai.neargo.shop.spi.product.CatalogSkuMeasurementFacts.Facts(
                        1, sf, "EA", now, sf.validUntil(), null));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/product/catalog/goods/", exchange -> {
            calls.incrementAndGet();
            try {
                assertThat(exchange.getRequestHeaders().getFirst(InternalHttp.TOKEN_HEADER)).isEqualTo("wire");
                boolean isGoods = exchange.getRequestURI().getPath().equals(GoodsCatalogInternalApi.MARKET_GOODS_PATH);
                boolean isMeasurement = exchange.getRequestURI().getPath().equals(GoodsCatalogInternalApi.SKU_MEASUREMENT_PATH);
                String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (isGoods) assertThat(json.readValue(input, GoodsQuery.class)).isEqualTo(GOODS);
                else {
                    assertThat(exchange.getRequestURI().getPath()).isEqualTo(isMeasurement
                            ? GoodsCatalogInternalApi.SKU_MEASUREMENT_PATH : GoodsCatalogInternalApi.MARKET_SKU_PATH);
                    assertThat(json.readValue(input, SkuQuery.class)).isEqualTo(SKU);
                }
                byte[] response = json.writeValueAsBytes(isGoods ? gf : isMeasurement ? measurement : sf);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(FoodCatalogClientConfiguration.class)
                    .withPropertyValues("shop.food.enabled=true", "shop.services.internal-token=wire",
                            "shop.services.targets.PLATFORM=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(c -> {
                        assertThat(c).hasNotFailed();
                        var port = c.getBean(GoodsCatalogQueryPort.class);
                        assertThat(port.getCatalogGoods(GOODS)).isEqualTo(gf);
                        assertThat(port.getCatalogSku(SKU)).isEqualTo(sf);
                        assertThat(port.getCatalogSkuMeasurement(SKU)).isEqualTo(measurement);
                    });
            assertThat(calls.get()).isEqualTo(3);
        } finally { server.stop(0); }
    }

    @Test void missingForeignExpiredAndAlteredOriginalFactsFailClosedForBothCapabilities() {
        var api = mock(GoodsCatalogInternalApi.class);
        var p = new CoreGoodsCatalogQueryPortImpl(() -> api);
        var now = Instant.now().minusSeconds(1);
        when(api.lookupMarketGoods(GOODS)).thenReturn(goods(GOODS, now));
        when(api.lookupMarketSku(SKU)).thenReturn(sku(SKU, now));
        assertThat(p.getCatalogGoods(GOODS).marketFacts().market().currency()).isEqualTo("AED");
        assertThat(p.getCatalogSku(SKU).sku().version()).isEqualTo(8);
        for (var g : List.of(goods(new GoodsQuery("X", "S", "G", "AE"), now),
                goods(GOODS, now.minusSeconds(30)))) {
            when(api.lookupMarketGoods(GOODS)).thenReturn(g);
            assertThatThrownBy(() -> p.getCatalogGoods(GOODS)).isInstanceOf(ServiceCallException.class);
        }
        for (var s : List.of(sku(new SkuQuery("E", "X", "SKU", "AE"), now), sku(SKU, now.minusSeconds(30)))) {
            when(api.lookupMarketSku(SKU)).thenReturn(s);
            assertThatThrownBy(() -> p.getCatalogSku(SKU)).isInstanceOf(ServiceCallException.class);
        }
        var good = goods(GOODS, now);
        var alteredGoods = new GoodsFacts(1, good.purpose(), GOODS, good.marketFacts(), good.goods(),
                good.verifiedAt(), good.validUntil(), "bad");
        var validSku = sku(SKU, now);
        var alteredSku = new SkuFacts(1, validSku.purpose(), SKU, validSku.marketFacts(), validSku.sku(),
                validSku.goodsVersion(), validSku.verifiedAt(), validSku.validUntil(), "bad");
        when(api.lookupMarketGoods(GOODS)).thenReturn(alteredGoods);
        when(api.lookupMarketSku(SKU)).thenReturn(alteredSku);
        assertThatThrownBy(() -> p.getCatalogGoods(GOODS)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> p.getCatalogSku(SKU)).isInstanceOf(ServiceCallException.class);
        when(api.lookupMarketGoods(GOODS)).thenReturn(null);
        when(api.lookupMarketSku(SKU)).thenReturn(null);
        assertThatThrownBy(() -> p.getCatalogGoods(GOODS)).isInstanceOf(ServiceCallException.class);
        assertThatThrownBy(() -> p.getCatalogSku(SKU)).isInstanceOf(ServiceCallException.class);
    }

    @Test void originalStatusAndTransportErrorsPropagateOnceWithoutLegacyCnFallback() {
        var api = mock(GoodsCatalogInternalApi.class);
        var p = new CoreGoodsCatalogQueryPortImpl(() -> api);
        for (int status : new int[] {400, 403, 404, 409, 503, 0}) {
            var count = new AtomicInteger();
            doAnswer(inv -> {
                count.incrementAndGet();
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, status, "safe");
            }).when(api).lookupMarketGoods(any());
            doAnswer(inv -> {
                count.incrementAndGet();
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, status, "safe");
            }).when(api).lookupMarketSku(any());
            Class<? extends RuntimeException> type = status >= 400 && status < 500
                    ? BizException.class : ServiceCallException.class;
            assertThatThrownBy(() -> p.getCatalogGoods(GOODS)).isInstanceOf(type);
            assertThatThrownBy(() -> p.getCatalogSku(SKU)).isInstanceOf(type);
            assertThat(count.get()).isEqualTo(2);
        }
    }
}
