package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Original cart and displayed quote only. No caller prices, buyer identity or financial result. */
public record CreateDiningOrderReq(@NotBlank @Size(max = 32) String cartNo,
        @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class)
        @Min(0) long version, @NotBlank @Size(max = 32) String quoteNo,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest,
        @NotBlank @Pattern(regexp = "PREPAY|POSTPAY") String payMode) {
    @JsonAnySetter public void unknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown dining order field");
    }
}
