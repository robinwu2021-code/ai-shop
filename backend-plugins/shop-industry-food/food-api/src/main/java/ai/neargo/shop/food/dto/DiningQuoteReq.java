package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.trade.PricingCheckoutPort.Selection;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Prices, buyer identity and tax settings cannot be supplied by the point-of-sale or H5 client. */
public record DiningQuoteReq(
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
        @NotBlank @Size(max = 32) String cartNo,
        @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class)
        @Min(0) long version,
        @Valid Selection selection) {
    @JsonAnySetter public void unknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown dining quote field");
    }
}
