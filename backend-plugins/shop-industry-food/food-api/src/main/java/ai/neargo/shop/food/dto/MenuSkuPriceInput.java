package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.DishOfferFacts;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Restaurant menu-owned price for an original selected SKU, never a replacement product or stock
 * identity.
 */
public record MenuSkuPriceInput(
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String skuNo,
        @JsonDeserialize(using = DishOfferFacts.MinorDeserializer.class) Long priceMinor,
        @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String currency) {
    @JsonAnySetter
    public void unknown(String name, Object value) {
        throw new IllegalArgumentException(name);
    }
}
