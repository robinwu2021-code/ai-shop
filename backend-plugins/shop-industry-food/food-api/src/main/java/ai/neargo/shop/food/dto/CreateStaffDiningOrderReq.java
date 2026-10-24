package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Staff chooses the real customer; neither employee identity nor merchant scope can come from this body. */
public record CreateStaffDiningOrderReq(@JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
        @NotBlank @Size(max = 32) String buyerNo,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
        @NotBlank @Size(max = 32) String cartNo,
        @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class) @Min(0) long version,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
        @NotBlank @Size(max = 32) String quoteNo,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String confirmationDigest,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
        @NotBlank @Pattern(regexp = "POSTPAY") String payMode) {
    /** Reuses original cart/quote validation without converting staff identity into consumer consent. */
    public CreateDiningOrderReq order() {
        return new CreateDiningOrderReq(cartNo, version, quoteNo, confirmationDigest, payMode);
    }
    @JsonAnySetter public void unknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown staff dining order field");
    }
}
