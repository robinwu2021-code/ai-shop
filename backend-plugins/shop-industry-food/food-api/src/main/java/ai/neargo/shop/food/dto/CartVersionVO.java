package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed cart boundary keeps draft identities separate from authoritative quotes and orders. */
public record CartVersionVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String cartNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version) {
}
