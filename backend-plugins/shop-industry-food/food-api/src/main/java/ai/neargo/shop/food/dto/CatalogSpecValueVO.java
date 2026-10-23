package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Frozen choice labels omit dictionary ownership columns while keeping the versions used by the publication. */
public record CatalogSpecValueVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String valueNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dimNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String label,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String dimName,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String valueType,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String usageType,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long valueVersion,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long dimVersion) { }
