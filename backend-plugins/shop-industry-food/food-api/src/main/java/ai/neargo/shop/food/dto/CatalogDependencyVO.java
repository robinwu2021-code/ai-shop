package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

/** One frozen typed dependency keeps publication identity and never reads mutable working children. */
public record CatalogDependencyVO(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String revisionNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long revision,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String contentHash,
        OptionGroupConfigVO optionGroup, CatalogModifierVO modifier, BundleConfigVO bundle, CatalogRecipeVO
                recipe, List<CatalogSpecValueVO> specValues) {
    public CatalogDependencyVO { if (specValues != null) specValues = List.copyOf(specValues); }
}
