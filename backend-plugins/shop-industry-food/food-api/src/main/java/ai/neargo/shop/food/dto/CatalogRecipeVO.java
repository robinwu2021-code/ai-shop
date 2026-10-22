package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Public recipe selection identity retains revision tracing without disclosing kitchen material definitions. */
public record CatalogRecipeVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion) { }
