package ai.neargo.shop.spi.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.media.MediaAssetQueryPort;
import ai.neargo.shop.spi.product.CatalogImageContracts.Expectation;
import ai.neargo.shop.spi.product.CatalogImageContracts.Facts;
import ai.neargo.shop.spi.product.CatalogImageContracts.Item;
import ai.neargo.shop.spi.product.CatalogImageContracts.Query;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CatalogImageFactsTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final Query QUERY = new Query("E", "S", List.of(new Expectation("G", "NONE", null)));

    private static Facts facts(Query q, List<Item> items) {
        return CatalogImageFacts.seal(new Facts(1, "CATALOG_IMAGE_IDENTITY", q, items, NOW, NOW.plusSeconds(30), null));
    }

    @Test void codecRoundTripsAndRejectsUnknownCoercedOrFractionalFields() {
        var json = JsonMapper.builder().build();
        var f = facts(QUERY, List.of(new Item("G", "NONE", PricingSnapshotHash.of(""), null, 3L, null, null)));
        String body = json.writeValueAsString(f);
        var read = json.readValue(body, Facts.class);
        assertThat(read).isEqualTo(f);
        CatalogImageFacts.validate(QUERY, read, NOW);
        for (String invalid : List.of(body.replace("\"goodsVersion\":3", "\"goodsVersion\":3.5"),
                body.replace("\"goodsVersion\":3", "\"goodsVersion\":\"3\""),
                body.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                body.replaceFirst("\\{", "{\"unexpected\":true,"),
                "{\"entityNo\":12,\"storeNo\":\"S\",\"expectations\":[]}")) {
            Class<?> target = invalid.contains("schemaVersion") ? Facts.class : Query.class;
            assertThatThrownBy(() -> json.readValue(invalid, target))
                    .as(invalid).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void onlyExplicitNoneOrReferenceAndBoundedUniqueGoodsAreAccepted() {
        for (Query invalid : List.of(new Query(" E", "S", QUERY.expectations()), new Query("E", "S", List.of()),
                new Query("E", "S", List.of(new Expectation("G", null, null))),
                new Query("E", "S", List.of(new Expectation("G", "NONE", ""))),
                new Query("E", "S", List.of(new Expectation("G", "REFERENCE", null))),
                new Query("E", "S", List.of(new Expectation("G", "REFERENCE", " "))),
                new Query("E", "S", List.of(QUERY.expectations().getFirst(), QUERY.expectations().getFirst())),
                new Query("E", "S", IntStream.range(0, 21).mapToObj(i -> new Expectation("G" + i, "NONE", null))
                        .toList()))) {
            assertThatThrownBy(() -> CatalogImageFacts.validateQuery(invalid)).isInstanceOf(BizException.class);
        }
    }

    @Test void scopedReceiptExpiryOrderAndNullVersionCannotBeForged() {
        var f = facts(QUERY, List.of(new Item("G", "NONE", PricingSnapshotHash.of(""), null, 3L, null, null)));
        assertThatThrownBy(() -> CatalogImageFacts.validate(QUERY, f, NOW.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CatalogImageFacts.validate(new Query("E", "X", QUERY.expectations()), f, NOW))
                .isInstanceOf(IllegalStateException.class);
        for (var item : List.of(new Item("OTHER", "NONE", PricingSnapshotHash.of(""), null, 3L, null, null),
                new Item("G", "NONE", PricingSnapshotHash.of(""), null, null, null, null),
                new Item("G", "NONE", PricingSnapshotHash.of(""), "E/S/goods/a.jpg", 3L, null, null))) {
            assertThatThrownBy(() -> CatalogImageFacts.validate(QUERY, facts(QUERY, List.of(item)), NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
        var tampered = new Facts(1, f.purpose(), QUERY, f.items(), NOW, f.validUntil(), "0".repeat(64));
        assertThatThrownBy(() -> CatalogImageFacts.validate(QUERY, tampered, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void identityDigestBindsOriginalScopeButDoesNotPretendToBeAssetContentRevision() {
        var q = new Query("E", "S", List.of(new Expectation("G", "REFERENCE", "E/S/goods/a.jpg")));
        var item = new Item("G", "REFERENCE", PricingSnapshotHash.of("E/S/goods/a.jpg"), "E/S/goods/a.jpg", 4L,
                PricingSnapshotHash.of(new MediaAssetQueryPort.AssetFact(
                        "E/S/goods/a.jpg", "E", "S", "GOODS", "ACTIVE")),
                "a".repeat(64));
        CatalogImageFacts.validate(q, facts(q, List.of(item)), NOW);
        var bad = new Item(item.goodsNo(), item.coverKind(), item.originalRefDigest(), item.assetKey(), 4L,
                "b".repeat(64), item.referenceReceiptNo());
        assertThatThrownBy(() -> CatalogImageFacts.validate(q, facts(q, List.of(bad)), NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
