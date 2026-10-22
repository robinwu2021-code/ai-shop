package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record CartAccess(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String accessToken,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cart field"); }
    @Override public String toString() { return "CartAccess[REDACTED]"; }
}
