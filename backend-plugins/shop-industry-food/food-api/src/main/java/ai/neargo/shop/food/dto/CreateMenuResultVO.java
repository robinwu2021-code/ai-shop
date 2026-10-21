package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Retains the established menu management response shape. */
public record CreateMenuResultVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status) { }
