package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record CartVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cartNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String checkNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sessionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String buyerNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment,
        Instant scheduledAt,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        List<OrderLineInput> lines) {
    public CartVO { if (lines != null) lines = List.copyOf(lines); }
}
