package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Subject and authorized store are resolved separately; filters cannot choose an owner. */
public record ListConfigRevisionsQuery(@NotBlank @Size(max=24)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configKind,
        @NotBlank @Size(max=32)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String configNo,
        @Min(1) @JsonDeserialize(using=ListRecipesQuery.PageDeserializer.class) Integer page,
        @Min(1) @Max(100) @JsonDeserialize(using=ListRecipesQuery.PageDeserializer.class) Integer size) { }
