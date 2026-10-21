package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Mutable header only; ownership, publication and availability are server-controlled. */
public record CreateMenuReq(@NotBlank @Size(max=64)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String name,
        @NotBlank @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String menuType,
        @NotBlank @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String timezone,
        @NotNull @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer priority,
        @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String exclusiveGroup) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown menu field"); }
}
