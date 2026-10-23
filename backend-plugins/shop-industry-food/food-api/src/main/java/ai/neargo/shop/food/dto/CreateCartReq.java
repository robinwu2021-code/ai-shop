package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record CreateCartReq(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment,
        Instant scheduledAt) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cart field"); }
}
