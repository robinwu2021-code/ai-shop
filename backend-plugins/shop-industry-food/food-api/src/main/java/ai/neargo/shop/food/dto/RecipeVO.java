package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Recipe management contract; ownership and material authority remain server-controlled. */
public record RecipeVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String storeNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String recipeCode,
        BigDecimal yieldQty,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String yieldUnit,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) long version,
        @JsonDeserialize(using=CatalogRevisionDeserializer.class) long configVersion,
        List<RecipeLineVO> lines) { }
