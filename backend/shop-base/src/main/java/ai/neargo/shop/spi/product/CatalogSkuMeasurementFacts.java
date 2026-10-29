package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import ai.neargo.shop.spi.media.MediaPublicSourceFacts;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.List;

/** Original product-owner evidence for a SKU's selling unit, separate from stock quantities. */
public final class CatalogSkuMeasurementFacts {
    private CatalogSkuMeasurementFacts() {}

    public record Facts(
            @JsonDeserialize(using = MediaPublicSourceFacts.IntegerDeserializer.class) Integer schemaVersion,
            CatalogMarketContracts.SkuFacts identity,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String unit,
            Instant verifiedAt, Instant validUntil,
            @JsonDeserialize(using = MediaPublicSourceFacts.TextDeserializer.class) String receiptNo) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown SKU measurement field");
        }
    }

    public static Facts seal(Facts facts) {
        String receipt = PricingSnapshotHash.of(List.of("CATALOG_SKU_MEASUREMENT_V1",
                facts.identity().receiptNo(), facts.unit(), facts.verifiedAt().toString(),
                facts.validUntil().toString()));
        return new Facts(facts.schemaVersion(), facts.identity(), facts.unit(), facts.verifiedAt(),
                facts.validUntil(), receipt);
    }

    public static void validate(CatalogMarketContracts.SkuQuery query, Facts facts, Instant now) {
        CatalogMarketFacts.validateQuery(query);
        if (facts == null || !Integer.valueOf(1).equals(facts.schemaVersion()) || now == null || facts.identity() == null
                || !MaterialCatalogFacts.validKey(facts.unit(), 16)
                || facts.unit().chars().anyMatch(Character::isISOControl)
                || facts.verifiedAt() == null || facts.validUntil() == null
                || facts.verifiedAt().isAfter(now) || !facts.validUntil().isAfter(now)
                || !facts.validUntil().isAfter(facts.verifiedAt()))
            throw new IllegalStateException("Invalid original SKU measurement evidence");
        CatalogMarketFacts.validate(query, facts.identity(), now);
        if (facts.verifiedAt().isBefore(facts.identity().verifiedAt())
                || facts.validUntil().isAfter(facts.identity().validUntil())
                || !seal(facts).receiptNo().equals(facts.receiptNo()))
            throw new IllegalStateException("Invalid original SKU measurement evidence");
    }
}
