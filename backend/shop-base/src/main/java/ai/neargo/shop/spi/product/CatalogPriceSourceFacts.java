package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Facts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Price;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Request;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.TaxFact;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Versions;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.Instant;
import java.util.Currency;
import java.util.HashSet;

/**
 * Consistency checks bind original owner facts; checksums never authenticate a caller or grant
 * order/payment authority.
 */
public final class CatalogPriceSourceFacts {
    public static final String PURPOSE = "CATALOG_BASE";

    private CatalogPriceSourceFacts() {}

    public static void validateMarketScope(MarketScope scope) {
        if (scope == null || !DishOfferFacts.validKey(scope.entityNo())
                || !DishOfferFacts.validKey(scope.storeNo())) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public static MarketFacts sealMarket(MarketFacts f) {
        var unsigned = new MarketFacts(f.schemaVersion(), f.purpose(), f.scope(), f.market(),
                f.verifiedAt(), f.validUntil(), null);
        return new MarketFacts(f.schemaVersion(), f.purpose(), f.scope(), f.market(),
                f.verifiedAt(), f.validUntil(), PricingSnapshotHash.of(unsigned));
    }

    /** A typed descriptor does not replace the original scope, lifetime and integrity checks. */
    public static void validateMarketFacts(MarketScope scope, MarketFacts f, Instant now) {
        validateMarketScope(scope);
        require(f != null && f.schemaVersion() == 1 && "CATALOG_MARKET".equals(f.purpose())
                && scope.equals(f.scope()) && now != null);
        validateMarket(f.market());
        require(f.verifiedAt() != null && f.validUntil() != null && !f.verifiedAt().isAfter(now)
                && f.validUntil().isAfter(now) && f.validUntil().isAfter(f.verifiedAt())
                && !f.validUntil().isAfter(f.verifiedAt().plusSeconds(30))
                && sealMarket(f).receiptNo().equals(f.receiptNo()));
    }

    public static void validateRequest(Request request) {
        if (request == null
                || !DishOfferFacts.validKey(request.entityNo())
                || !DishOfferFacts.validKey(request.storeNo())
                || request.skuNos() == null
                || request.skuNos().isEmpty()
                || request.skuNos().size() > 100
                || request.skuNos().stream().anyMatch(n -> !DishOfferFacts.validKey(n))
                || new HashSet<>(request.skuNos()).size() != request.skuNos().size())
            throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    public static void validateMarket(Market market) {
        require(
                market != null
                        && market.market() != null
                        && market.market().matches("[A-Z]{2}")
                        && market.currency() != null
                        && market.currency().matches("[A-Z]{3}")
                        && market.currencyScale() != null
                        && market.currencyScale() >= 0
                        && market.currencyScale() <= 6);
        require(
                Currency.getInstance(market.currency()).getDefaultFractionDigits()
                        == market.currencyScale());
    }

    public static MerchantTaxSnapshot taxSnapshot(Request request, TaxFact tax) {
        validateTax(tax);
        return taxDefinition(request.entityNo(), request.storeNo(), tax);
    }

    /** Scope-only readers reuse the original definition checks without fabricating a SKU or a price request. */
    public static MerchantTaxSnapshot taxSnapshotForScope(MarketScope scope, TaxFact tax) {
        validateMarketScope(scope);
        validateTax(tax);
        return taxDefinition(scope.entityNo(), scope.storeNo(), tax);
    }

    private static void validateTax(TaxFact tax) {
        require(tax != null && tax.source() != null && tax.managedVersion() != null
                && tax.sourceVersion() != null && tax.rateBp() != null && tax.priceTaxMode() != null);
    }

    private static MerchantTaxSnapshot taxDefinition(String entity, String store, TaxFact tax) {
        return new MerchantTaxSnapshot("MAIN", entity, store, tax.managedVersion(), Source.valueOf(tax.source()),
                tax.ownerNo(), tax.sourceVersion(), tax.rateBp(), PriceTaxMode.valueOf(tax.priceTaxMode()),
                tax.contentHash());
    }

    public static TaxFact taxFact(MerchantTaxSnapshot tax) {
        return new TaxFact(
                tax.source().name(),
                tax.ownerNo(),
                tax.managedVersion(),
                tax.sourceVersion(),
                tax.rateBp(),
                tax.priceTaxMode().name(),
                tax.contentHash());
    }

    /**
     * Stable per-SKU source revision excludes batch membership and short-lived transport
     * timestamps.
     */
    public static String revision(Request request, Market market, Price price) {
        record PriceIdentity(
                String entity,
                String store,
                Market market,
                String sku,
                String goods,
                String status,
                Long amount,
                String sourceKind,
                Versions versions) {}
        return PricingSnapshotHash.of(
                new PriceIdentity(
                        request.entityNo(),
                        request.storeNo(),
                        market,
                        price.skuNo(),
                        price.goodsNo(),
                        price.status(),
                        price.amountMinor(),
                        price.sourceKind(),
                        price.versions()));
    }

    public static String receipt(Facts facts) {
        return PricingSnapshotHash.of(
                new Facts(
                        facts.schemaVersion(),
                        facts.purpose(),
                        facts.request(),
                        facts.market(),
                        facts.tax(),
                        facts.prices(),
                        facts.verifiedAt(),
                        facts.validUntil(),
                        null));
    }

    /**
     * Called by remote consumers and by the original producer; no missing row or invalid scalar
     * becomes a zero price.
     */
    public static void validate(Request request, Facts facts, Instant now) {
        try {
            validateRequest(request);
            require(
                    facts != null
                            && facts.schemaVersion() == 1
                            && PURPOSE.equals(facts.purpose())
                            && request.equals(facts.request())
                            && facts.prices() != null
                            && facts.prices().size() == request.skuNos().size()
                            && facts.verifiedAt() != null
                            && facts.validUntil() != null
                            && now != null
                            && !facts.verifiedAt().isAfter(now)
                            && facts.validUntil().isAfter(now)
                            && facts.validUntil().isAfter(facts.verifiedAt())
                            && !facts.validUntil().isAfter(facts.verifiedAt().plusSeconds(30)));
            validateMarket(facts.market());
            taxSnapshot(request, facts.tax());
            var seen = new HashSet<String>();
            for (var price : facts.prices()) {
                require(
                        price != null
                                && request.skuNos().contains(price.skuNo())
                                && seen.add(price.skuNo())
                                && DishOfferFacts.validKey(price.goodsNo())
                                && price.versions() != null
                                && version(price.versions().goods())
                                && version(price.versions().sku())
                                && optionalVersion(price.versions().storePrice())
                                && optionalVersion(price.versions().storeGoods())
                                && ("ENTITY_SKU".equals(price.sourceKind())
                                        || "STORE_PRICE".equals(price.sourceKind()))
                                && (!"STORE_PRICE".equals(price.sourceKind())
                                        || price.versions().storePrice() != null));
                if ("KNOWN".equals(price.status())) {
                    require(price.amountMinor() != null);
                    new MoneyMinor(facts.market().currency(), price.amountMinor())
                            .requireNonNegative()
                            .requireJsonSafe();
                } else
                    require(
                            "UNAVAILABLE".equals(price.status())
                                    && price.amountMinor() == null
                                    && "ENTITY_SKU".equals(price.sourceKind()));
                require(revision(request, facts.market(), price).equals(price.sourceRevision()));
            }
            require(receipt(facts).equals(facts.receiptNo()));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid original catalog price facts", invalid);
        }
    }

    private static boolean version(Long value) {
        return value != null && value >= 0 && value <= MoneyMinor.MAX_JSON_SAFE_MINOR;
    }

    private static boolean optionalVersion(Long value) {
        return value == null || version(value);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid catalog price source evidence");
    }

    /**
     * Optional absent overlay versions stay null, while present versions still require integral
     * JSON tokens.
     */
    public static final class OptionalVersionDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            return new CatalogRevisionDeserializer().deserialize(parser, context);
        }
    }

    public static final class IntegerDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(
                        Integer.class, "Source scalar must be an integer token");
            return parser.getIntValue();
        }
    }
}
