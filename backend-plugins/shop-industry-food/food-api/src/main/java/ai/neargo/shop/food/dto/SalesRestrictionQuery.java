package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Bounded current-store query with effective expiry status. */
public record SalesRestrictionQuery(
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer page,
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer size,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String status) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown sales restriction query field");
    }
}
