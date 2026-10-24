package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Confirmation binds the displayed quote and original cart revision; it does not charge the customer. */
public record DiningQuoteConfirmReq(
        @NotBlank @Size(max = 32) String cartNo,
        @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class)
        @Min(0) long version,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest) {
    @JsonAnySetter public void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown dining confirmation field");
    }
}
