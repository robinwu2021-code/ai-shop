package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Only intent is accepted; frozen contents, store scope and publication identities are server controlled. */
public record PublishConfigReq(@NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version, @NotNull OffsetDateTime effectiveAt,
        @Size(max=64) @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String approvalRef) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown publication field");
    }
}
