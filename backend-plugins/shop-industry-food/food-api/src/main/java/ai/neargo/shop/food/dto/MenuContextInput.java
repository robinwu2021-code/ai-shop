package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Typed menu scope contract; ownership is resolved by the platform. */
public record MenuContextInput(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String orderSource,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String fulfillment,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String channelBindingNo,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String pricePolicyRef) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown menu context field");
    }
}
