package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.PublicationMarketContext;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.DishOfferFacts;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort.SpecValueFact;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One stable context policy serves historical documents without consulting current merchant configuration. */
public final class PublicationMarketPolicy {
    private PublicationMarketPolicy() { }

    /** Only a fresh original market result may seed a new frozen interpretation context. */
    public static PublicationMarketContext capture(MarketScope expected, MarketFacts facts, Instant now) {
        require(facts != null);
        CatalogPriceSourceFacts.validateMarketFacts(expected, facts, now);
        var m = facts.market();
        var identity = new Identity(facts.scope().entityNo(), facts.scope().storeNo(),
                m.market(), m.currency(), m.currencyScale());
        var result = new PublicationMarketContext(1, identity.entity(), identity.store(), identity.market(),
                identity.currency(), identity.scale(), PricingSnapshotHash.of(identity), facts.receiptNo());
        validate(result);
        return result;
    }

    /** History validates its own frozen meaning, never the TTL or current status of the old market proof. */
    public static void validate(PublicationMarketContext context) {
        require(context != null && Objects.equals(context.schemaVersion(), 1)
                && DishOfferFacts.validKey(context.entityNo()) && DishOfferFacts.validKey(context.storeNo())
                && context.marketReceiptNo() != null && context.marketReceiptNo().matches("[a-f0-9]{64}"));
        CatalogPriceSourceFacts.validateMarket(
                new Market(context.market(), context.currency(), context.currencyScale()));
        var identity = new Identity(context.entityNo(), context.storeNo(), context.market(), context.currency(),
                context.currencyScale());
        require(PricingSnapshotHash.of(identity).equals(context.contextHash()));
    }

    /** Old versions omit this field; schema six requires it and cannot borrow legacy implicit CN meaning. */
    public static void validateSchema(int schema, int legacy, PublicationMarketContext context) {
        require(schema == legacy && context == null || schema == 6 && context != null);
        if (schema == 6) validate(context);
    }

    /** Audit receipts may differ across original reads; frozen scope/market/currency/scale must be identical. */
    public static void requireSame(PublicationMarketContext parent, PublicationMarketContext child) {
        validate(parent);
        validate(child);
        require(parent.contextHash().equals(child.contextHash()));
    }

    /** Actual-market SKU identity is meaningful only together with the exact frozen owner scope. */
    public static void validateSkuFacts(PublicationMarketContext context, List<SkuIdentityFact> facts) {
        validate(context);
        require(facts != null && facts.size() <= 1000);
        var seen = new HashSet<String>();
        for (var fact : facts) {
            require(fact != null && DishOfferFacts.validKey(fact.skuNo()) && DishOfferFacts.validKey(fact.goodsNo())
                    && context.entityNo().equals(fact.entityNo()) && context.storeNo().equals(fact.storeNo())
                    && fact.version() >= 0 && seen.add(fact.skuNo()));
        }
    }

    /** Dictionary values and dimensions retain both original ownership axes, including platform-shared facts. */
    public static void validateSpecFacts(PublicationMarketContext context, List<SpecValueFact> facts) {
        validate(context);
        require(facts != null && facts.size() <= 256);
        var seen = new HashSet<String>();
        for (var fact : facts) {
            require(fact != null && MaterialCatalogFacts.validKey(fact.valueNo(), 64)
                    && MaterialCatalogFacts.validKey(fact.dimNo(), 64) && seen.add(fact.valueNo())
                    && "ENUM".equals(fact.valueType()) && fact.valueVersion() >= 0 && fact.dimVersion() >= 0
                    && visible(context.entityNo(), fact.valueScope(), fact.valueEntityNo())
                    && visible(context.entityNo(), fact.dimScope(), fact.dimEntityNo()));
        }
    }

    private static boolean visible(String entity, String scope, String owner) {
        return "PLATFORM".equals(scope) || "MERCHANT".equals(scope) && entity.equals(owner);
    }

    private record Identity(String entity, String store, String market, String currency, Integer scale) { }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Invalid frozen publication market context");
    }
}
