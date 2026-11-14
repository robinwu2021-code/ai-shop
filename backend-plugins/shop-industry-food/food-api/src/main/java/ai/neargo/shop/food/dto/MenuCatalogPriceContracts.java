package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.product.DishOfferFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Original menu composition carries its evidence so remote readers can recompute rather than trust
 * totals.
 */
public final class MenuCatalogPriceContracts {
    private MenuCatalogPriceContracts() {}

    public record Query(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String revisionNo) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown menu price query field");
        }
    }

    /**
     * Unit prices remain separate from exact per-pick quantities to preserve original checkout
     * rounding.
     */
    public record PriceLine(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String menuItemNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String mainSkuNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String groupNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String choiceNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String kind,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
            @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long unitPriceMinor,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String unitsPerPick,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String sourceRevision) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown menu price line field");
        }
    }

    public record Facts(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) long schemaVersion,
            Query query,
            MenuSourceContracts.Proof menuProof,
            CatalogPriceSourcePort.Facts productFacts,
            List<PriceLine> prices,
            Instant verifiedAt,
            Instant validUntil,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String receiptNo) {
        public Facts {
            prices = prices == null ? null : List.copyOf(prices);
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown menu price facts field");
        }
    }
}
