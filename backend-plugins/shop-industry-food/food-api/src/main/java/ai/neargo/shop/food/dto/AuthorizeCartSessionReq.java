package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record AuthorizeCartSessionReq(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown cart field"); }
}
