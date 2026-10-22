package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Current-time scope only: future preview and caller-supplied ownership are intentionally excluded. */
public record CatalogQuery(
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelBindingNo) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown catalog query field");
    }
}
