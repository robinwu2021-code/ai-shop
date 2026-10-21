package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Literal name search; pagination is strict at the typed transport boundary. */
public record ListMenusQuery(@Min(1)
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page,
        @Min(1) @Max(100)
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size,
        @Pattern(regexp="DRAFT|PUBLISHED|DISABLED")
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status,
        @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String keyword) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown menu query"); }
}
