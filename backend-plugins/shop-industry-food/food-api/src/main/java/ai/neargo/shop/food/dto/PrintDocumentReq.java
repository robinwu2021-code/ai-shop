package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Selects persisted restaurant facts; caller cannot submit receipt text, prices, payment state or identity. */
public record PrintDocumentReq(
        @NotBlank @Size(max=16)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String scene,
        @NotBlank @Size(max=32)
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String sourceNo,
        @Size(max=32) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String stationNo) {
    @JsonAnySetter public void rejectUnknown(String field,Object value) {
        throw new IllegalArgumentException("Unknown print document field");
    }
}
