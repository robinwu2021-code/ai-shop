package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** Publication identity, ordering and frozen dependencies travel with each selected restaurant menu. */
public record CatalogMenuVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long revision,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup,
        List<CatalogDependencyVO> dependencies) {
    public CatalogMenuVO { if (dependencies != null) dependencies = List.copyOf(dependencies); }
}
