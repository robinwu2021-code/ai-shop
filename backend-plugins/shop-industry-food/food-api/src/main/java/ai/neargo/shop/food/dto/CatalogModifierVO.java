package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Frozen customer-facing modifier metadata omits the merchant's component structure and quantities. */
public record CatalogModifierVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String kind,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String salesSkuNo,
        CatalogSkuVO salesOffer) { }
