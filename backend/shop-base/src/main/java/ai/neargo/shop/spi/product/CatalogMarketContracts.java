package ai.neargo.shop.spi.product;

import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Market-aware management evidence grants no price, stock, checkout or publication permission. */
public final class CatalogMarketContracts {
    private CatalogMarketContracts() { }

    /** Expected market is an assertion; only the original owner selects the actual market. */
    public record GoodsQuery(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String goodsNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String expectedMarket) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    /** SKU lookup cannot select another market or fall back to a CN identity. */
    public record SkuQuery(
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String expectedMarket) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    /** Preserve original metadata types while enforcing stricter codecs only on this new transport. */
    public record GoodsFacts(
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String purpose,
            GoodsQuery query, MarketFacts marketFacts,
            @JsonDeserialize(using = CatalogMarketFacts.GoodsDeserializer.class) GoodsFact goods,
            Instant verifiedAt, Instant validUntil,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    /** Both original SKU and parent versions are retained without inventing stock or price facts. */
    public record SkuFacts(
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String purpose,
            SkuQuery query, MarketFacts marketFacts,
            @JsonDeserialize(using = CatalogMarketFacts.SkuDeserializer.class) SkuIdentityFact sku,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long goodsVersion,
            Instant verifiedAt, Instant validUntil,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String k, Object v) { throw unknown(); }
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unknown market metadata");
    }
}
