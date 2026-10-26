package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Original catalog base prices only; checkout promotions, stock reservations and acceptance remain
 * separate owners.
 */
public interface CatalogPriceSourcePort {
    /** Absent providers must not silently replace another market with legacy CN checkout prices. */
    default Facts capture(Request request) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }

    record Request(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(contentUsing = MaterialCatalogFacts.TextDeserializer.class)
                    List<String> skuNos) {
        public Request {
            skuNos = skuNos == null ? null : List.copyOf(skuNos);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog source request field");
        }
    }

    record Market(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String market,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency,
            @JsonDeserialize(using = CatalogPriceSourceFacts.IntegerDeserializer.class)
                    Integer currencyScale) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown market fact field");
        }
    }

    record TaxFact(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String source,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String ownerNo,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long managedVersion,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long sourceVersion,
            @JsonDeserialize(using = CatalogPriceSourceFacts.IntegerDeserializer.class)
                    Integer rateBp,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String priceTaxMode,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String contentHash) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown tax fact field");
        }
    }

    record Versions(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long goods,
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long sku,
            @JsonDeserialize(using = CatalogPriceSourceFacts.OptionalVersionDeserializer.class)
                    Long storePrice,
            @JsonDeserialize(using = CatalogPriceSourceFacts.OptionalVersionDeserializer.class)
                    Long storeGoods) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown price version field");
        }
    }

    record Price(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String goodsNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String status,
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long amountMinor,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String sourceKind,
            Versions versions,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String sourceRevision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog price field");
        }
    }

    record Facts(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String purpose,
            Request request,
            Market market,
            TaxFact tax,
            List<Price> prices,
            Instant verifiedAt,
            Instant validUntil,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String receiptNo) {
        public Facts {
            prices = prices == null ? null : List.copyOf(prices);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown catalog source facts field");
        }
    }
}
