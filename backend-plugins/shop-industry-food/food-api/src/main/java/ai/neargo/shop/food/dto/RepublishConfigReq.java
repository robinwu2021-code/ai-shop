package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A new publication of immutable historical content; version is the current working-head CAS. */
public record RepublishConfigReq(@NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @NotNull OffsetDateTime effectiveAt,
        @NotBlank @Size(max=255) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String reason,
        @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String approvalRef) {
    @JsonAnySetter public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown republication field");
    }
}
