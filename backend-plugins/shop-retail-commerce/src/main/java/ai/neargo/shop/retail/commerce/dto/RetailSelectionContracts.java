package ai.neargo.shop.retail.commerce.dto;

import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Immutable original selection publication inputs and bounded evidence; external JSON is unchanged.
 */
public final class RetailSelectionContracts {
    private RetailSelectionContracts() {}

    public record Pick(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String goodsNo,
            @JsonDeserialize(contentUsing = CommerceTextDeserializer.class) List<String> skuNos) {
        public Pick {
            skuNos = skuNos == null ? null : List.copyOf(skuNos);
        }
    }

    public record Publish(
            String entityNo,
            String storeNo,
            String selectionNo,
            String expectedRevision,
            String connectionNo,
            String connectionRevisionNo,
            List<Pick> picks) {
        public Publish {
            picks = picks == null ? null : List.copyOf(picks);
        }
    }

    public record Prepared(RetailSelection selection, Instant validUntil) {}
}
