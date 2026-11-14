package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Staff can withdraw a pending original placement, never erase an accepted service debt. */
public record CancelDiningOrderReq(
        @NotNull @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
        @NotBlank @Size(max = 255)
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reason) {
    @JsonAnySetter
    public void unknown(String name, Object value) { throw new IllegalArgumentException("Unknown cancellation field"); }
}
