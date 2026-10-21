package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Recipe management contract; ownership and material authority remain server-controlled. */
public record RecipeLineVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String lineNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String itemRef, BigDecimal qty,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String unit,
        BigDecimal lossRate,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String materialDefinitionHash) { }
