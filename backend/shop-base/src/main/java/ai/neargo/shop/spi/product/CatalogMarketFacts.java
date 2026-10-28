package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.media.MediaReferenceFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Local and remote consumers validate the same original scope, market, versions and bounded lifetime. */
public final class CatalogMarketFacts {
    public static final int MAX_SKUS = 256;
    private CatalogMarketFacts() { }

    /** A caller may assert the current market but never choose a different one. */
    public static void validateQuery(GoodsQuery q) {
        if (q == null) bad();
        scope(q.entityNo(), q.storeNo(), q.goodsNo(), q.expectedMarket());
    }

    /** Keep SKU and goods scope validation identical across deployments. */
    public static void validateQuery(SkuQuery q) {
        if (q == null) bad();
        scope(q.entityNo(), q.storeNo(), q.skuNo(), q.expectedMarket());
    }

    private static void scope(String entity, String store, String number, String expected) {
        if (!DishOfferFacts.validKey(entity) || !DishOfferFacts.validKey(store) || !DishOfferFacts.validKey(number)
                || expected != null && !expected.matches("[A-Z]{2}")) bad();
    }

    /** Evidence digests bind original results; original service authentication remains mandatory. */
    public static GoodsFacts seal(GoodsFacts f) {
        return goodsCopy(f, PricingSnapshotHash.of(goodsCopy(f, null)));
    }

    /** SKU evidence includes the original parent version as well as the SKU identity. */
    public static SkuFacts seal(SkuFacts f) {
        return skuCopy(f, PricingSnapshotHash.of(skuCopy(f, null)));
    }

    private static GoodsFacts goodsCopy(GoodsFacts f, String receipt) {
        return new GoodsFacts(f.schemaVersion(), f.purpose(), f.query(), f.marketFacts(), f.goods(),
                f.verifiedAt(), f.validUntil(), receipt);
    }

    private static SkuFacts skuCopy(SkuFacts f, String receipt) {
        return new SkuFacts(f.schemaVersion(), f.purpose(), f.query(), f.marketFacts(), f.sku(), f.goodsVersion(),
                f.verifiedAt(), f.validUntil(), receipt);
    }

    /** Metadata does not become sellable merely because its original market was verified. */
    public static void validate(GoodsQuery q, GoodsFacts f, Instant now) {
        validateQuery(q);
        require(f != null && Objects.equals(f.schemaVersion(), 1) && "CATALOG_GOODS".equals(f.purpose())
                && q.equals(f.query()));
        market(q.entityNo(), q.storeNo(), q.expectedMarket(), f.marketFacts(), f.verifiedAt(), f.validUntil(), now);
        var g = f.goods();
        require(g != null && q.entityNo().equals(g.entityNo()) && q.storeNo().equals(g.storeNo())
                && q.goodsNo().equals(g.goodsNo()) && g.version() >= 0 && g.skus() != null
                && g.skus().size() <= MAX_SKUS);
        var seen = new HashSet<String>();
        String previous = null;
        for (var sku : g.skus()) {
            require(sku != null && DishOfferFacts.validKey(sku.skuNo()) && sku.version() >= 0
                    && seen.add(sku.skuNo()) && (previous == null || previous.compareTo(sku.skuNo()) < 0));
            previous = sku.skuNo();
        }
        require(seal(f).receiptNo().equals(f.receiptNo()));
    }

    /** Revalidate exact original SKU ownership, not just the caller-supplied SKU number. */
    public static void validate(SkuQuery q, SkuFacts f, Instant now) {
        validateQuery(q);
        require(f != null && Objects.equals(f.schemaVersion(), 1) && "CATALOG_SKU".equals(f.purpose())
                && q.equals(f.query()));
        market(q.entityNo(), q.storeNo(), q.expectedMarket(), f.marketFacts(), f.verifiedAt(), f.validUntil(), now);
        var sku = f.sku();
        require(sku != null && q.entityNo().equals(sku.entityNo()) && q.storeNo().equals(sku.storeNo())
                && q.skuNo().equals(sku.skuNo()) && DishOfferFacts.validKey(sku.goodsNo()) && sku.version() >= 0
                && f.goodsVersion() != null && f.goodsVersion() >= 0 && seal(f).receiptNo().equals(f.receiptNo()));
    }

    private static void market(String entity, String store, String expected, MarketFacts market,
            Instant at, Instant until, Instant now) {
        CatalogPriceSourceFacts.validateMarketFacts(new MarketScope(entity, store), market, now);
        require((expected == null || expected.equals(market.market().market()))
                && MediaReferenceFacts.fresh(at, until, now) && !at.isBefore(market.verifiedAt())
                && !until.isAfter(market.validUntil()));
    }

    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid original market metadata facts");
    }

    /** Property-local codec preserves legacy GoodsFact transport while rejecting coercion in new evidence. */
    public static final class GoodsDeserializer extends ValueDeserializer<GoodsFact> {
        @Override
        public GoodsFact deserialize(JsonParser p, DeserializationContext c) {
            JsonNode n = c.readTree(p);
            fields(n, Set.of("goodsNo", "entityNo", "storeNo", "title", "cover", "baseOnSale", "auditStatus",
                    "version", "skus"));
            require(n.path("baseOnSale").isBoolean() && n.path("skus").isArray()
                    && n.path("skus").size() <= MAX_SKUS);
            var skus = new ArrayList<SkuFact>();
            for (JsonNode sku : n.path("skus")) {
                fields(sku, Set.of("skuNo", "spec", "version"));
                skus.add(new SkuFact(text(sku, "skuNo", false), text(sku, "spec", true), version(sku, "version")));
            }
            return new GoodsFact(text(n, "goodsNo", false), text(n, "entityNo", false), text(n, "storeNo", false),
                    text(n, "title", true), text(n, "cover", true), n.path("baseOnSale").booleanValue(),
                    text(n, "auditStatus", true), version(n, "version"), skus);
        }
    }

    /** The new SKU wire must not interpret missing or rounded versions as a genuine original version zero. */
    public static final class SkuDeserializer extends ValueDeserializer<SkuIdentityFact> {
        @Override
        public SkuIdentityFact deserialize(JsonParser p, DeserializationContext c) {
            JsonNode n = c.readTree(p);
            fields(n, Set.of("skuNo", "goodsNo", "entityNo", "storeNo", "version"));
            return new SkuIdentityFact(text(n, "skuNo", false), text(n, "goodsNo", false),
                    text(n, "entityNo", false), text(n, "storeNo", false), version(n, "version"));
        }
    }

    private static void fields(JsonNode n, Set<String> allowed) {
        require(n != null && n.isObject() && n.properties().stream().allMatch(p -> allowed.contains(p.getKey())));
    }

    private static String text(JsonNode n, String key, boolean nullable) {
        JsonNode v = n.path(key);
        if (nullable && (v.isMissingNode() || v.isNull())) return null;
        require(v.isString());
        return v.stringValue();
    }

    private static long version(JsonNode n, String key) {
        JsonNode v = n.path(key);
        require(v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= 0);
        return v.longValue();
    }
}
