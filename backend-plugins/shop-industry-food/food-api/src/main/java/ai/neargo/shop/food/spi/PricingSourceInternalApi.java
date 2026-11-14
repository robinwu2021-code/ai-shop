package ai.neargo.shop.food.spi;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningPriceFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Internal authority lookup accepts a cart version, never caller-supplied prices or calculation units. */
public interface PricingSourceInternalApi {
    String STAFF_PATH = "/internal/food/pricing-sources/cart";
    String CUSTOMER_PATH = "/internal/food/pricing-sources/customer-cart";
    @PostExchange(STAFF_PATH)
    DiningPriceFacts staff(@RequestBody StaffCommand command);
    @PostExchange(CUSTOMER_PATH)
    DiningPriceFacts customer(@RequestBody CustomerCommand command);

    record StaffCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String entityNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String storeNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class)
            @Min(0) long expectedVersion) {
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    record CustomerCommand(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
            @NotBlank @Size(max = 32) String cartNo,
            @Valid @NotNull CartAccess access,
            @JsonProperty(required = true) @JsonDeserialize(using = CatalogRevisionDeserializer.class)
            @Min(0) long expectedVersion) {
        @JsonAnySetter public void unknown(String key, Object value) { reject(); }
    }
    private static void reject() { throw new IllegalArgumentException("Unknown price source command field"); }
}
