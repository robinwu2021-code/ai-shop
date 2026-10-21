package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** One authorized store view; no physical row identifier or assumed effective publication. */
public record MenuVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone,
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long configVersion) { }
