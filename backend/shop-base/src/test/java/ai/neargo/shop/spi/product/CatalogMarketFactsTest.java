package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CatalogMarketFactsTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final GoodsQuery GOODS = new GoodsQuery("E", "S", "G", "AE");
    private static final SkuQuery SKU = new SkuQuery("E", "S", "SKU", null);
    private static final GoodsFact METADATA = new GoodsFact("G", "E", "S", "Dish", null, false, "PENDING", 7,
            List.of(new SkuFact("SKU", null, 8)));

    private static MarketFacts market(String code, String currency, int scale) {
        return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", new MarketScope("E", "S"),
                new Market(code, currency, scale), NOW, NOW.plusSeconds(30), null));
    }

    private static GoodsFacts goods(GoodsQuery q, GoodsFact g) {
        return CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", q, market("AE", "AED", 2), g,
                NOW, NOW.plusSeconds(20), null));
    }

    @Test void strictNestedCodecsRoundTripOriginalMetadataTypesWithoutChangingLegacyRecords() {
        var json = JsonMapper.builder().build();
        var f = goods(GOODS, METADATA);
        var read = json.readValue(json.writeValueAsString(f), GoodsFacts.class);
        assertThat(read).isEqualTo(f);
        CatalogMarketFacts.validate(GOODS, read, NOW);
        var s = CatalogMarketFacts.seal(new SkuFacts(1, "CATALOG_SKU", SKU, market("AE", "AED", 2),
                new SkuIdentityFact("SKU", "G", "E", "S", 8), 7L, NOW, NOW.plusSeconds(20), null));
        var skuRead = json.readValue(json.writeValueAsString(s), SkuFacts.class);
        assertThat(skuRead).isEqualTo(s);
        CatalogMarketFacts.validate(SKU, skuRead, NOW);
        assertThat(json.readValue(json.writeValueAsString(METADATA), GoodsFact.class)).isEqualTo(METADATA);
    }

    @Test void unknownMissingFractionalOrCoercedNestedFieldsCannotInventOriginalVersions() {
        var json = JsonMapper.builder().build();
        var body = json.writeValueAsString(goods(GOODS, METADATA));
        for (String invalid : List.of(body.replace("\"version\":7", "\"version\":7.5"),
                body.replace("\"version\":8", "\"version\":\"8\""),
                body.replace(",\"version\":7", ""), body.replace("\"version\":8", "\"version\":null"),
                body.replace("\"baseOnSale\":false", "\"baseOnSale\":\"false\""),
                body.replace("\"spec\":null", "\"spec\":42"),
                body.replace("\"goods\":{", "\"goods\":{\"priceMinor\":9,"))) {
            assertThat(invalid).isNotEqualTo(body);
            assertThatThrownBy(() -> json.readValue(invalid, GoodsFacts.class)).isInstanceOf(RuntimeException.class);
        }
        var sku = CatalogMarketFacts.seal(new SkuFacts(1, "CATALOG_SKU", SKU, market("AE", "AED", 2),
                new SkuIdentityFact("SKU", "G", "E", "S", 8), 7L, NOW, NOW.plusSeconds(20), null));
        String skuBody = json.writeValueAsString(sku);
        for (String invalid : List.of(skuBody.replace(",\"version\":8", ""),
                skuBody.replace("\"sku\":{", "\"sku\":{\"market\":\"CN\","))) {
            assertThat(invalid).isNotEqualTo(skuBody);
            assertThatThrownBy(() -> json.readValue(invalid, SkuFacts.class)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void actualMarketAndCurrencyScaleComeFromOriginalMarketProof() {
        for (var m : List.of(market("AE", "AED", 2), market("TW", "TWD", 2), market("JP", "JPY", 0),
                market("KW", "KWD", 3))) {
            var q = new GoodsQuery("E", "S", "G", m.market().market());
            var f = CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", q, m, METADATA,
                    NOW, NOW.plusSeconds(20), null));
            CatalogMarketFacts.validate(q, f, NOW);
            assertThat(f.marketFacts().market().currencyScale()).isEqualTo(m.market().currencyScale());
        }
        var wrong = CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", GOODS, market("CN", "CNY", 2),
                METADATA, NOW, NOW.plusSeconds(20), null));
        assertThatThrownBy(() -> CatalogMarketFacts.validate(GOODS, wrong, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidScopeMarketAssertionAndUnknownInputFieldsAreRejected() {
        for (var q : List.of(new GoodsQuery(" E", "S", "G", null), new GoodsQuery("E", "S", "G", ""),
                new GoodsQuery("E", "S", "G", "ae"))) {
            assertThatThrownBy(() -> CatalogMarketFacts.validateQuery(q)).isInstanceOf(BizException.class);
        }
        var json = JsonMapper.builder().build();
        for (String body : List.of("{\"entityNo\":7}", "{\"market\":\"CN\"}", "{\"currency\":\"AED\"}")) {
            assertThatThrownBy(() -> json.readValue(body, GoodsQuery.class)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> json.readValue(body, SkuQuery.class)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void reorderedDuplicateForeignOrNegativeMetadataAndAlteredReceiptsFailValidation() {
        for (var g : List.of(new GoodsFact("G", "X", "S", "x", null, true, "APPROVED", 1, List.of()),
                new GoodsFact("G", "E", "S", "x", null, true, "APPROVED", -1, List.of()),
                new GoodsFact("G", "E", "S", "x", null, true, "APPROVED", 1,
                        List.of(new SkuFact("Z", null, 1), new SkuFact("A", null, 1))),
                new GoodsFact("G", "E", "S", "x", null, true, "APPROVED", 1,
                        List.of(new SkuFact("A", null, 1), new SkuFact("A", null, 1))))) {
            assertThatThrownBy(() -> CatalogMarketFacts.validate(GOODS, goods(GOODS, g), NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
        var f = goods(GOODS, METADATA);
        var altered = new GoodsFacts(1, f.purpose(), GOODS, f.marketFacts(), METADATA, NOW, f.validUntil(), "bad");
        assertThatThrownBy(() -> CatalogMarketFacts.validate(GOODS, altered, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CatalogMarketFacts.validate(GOODS, f, NOW.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void enclosingLifetimeCannotOutliveMarketProofAndForeignParentCannotBecomeSkuOwnership() {
        var f = CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", GOODS, market("AE", "AED", 2),
                METADATA, NOW, NOW.plusSeconds(31), null));
        assertThatThrownBy(() -> CatalogMarketFacts.validate(GOODS, f, NOW)).isInstanceOf(IllegalStateException.class);
        var s = CatalogMarketFacts.seal(new SkuFacts(1, "CATALOG_SKU", SKU, market("AE", "AED", 2),
                new SkuIdentityFact("SKU", "G", "E", "OTHER", 8), 7L, NOW, NOW.plusSeconds(20), null));
        assertThatThrownBy(() -> CatalogMarketFacts.validate(SKU, s, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
