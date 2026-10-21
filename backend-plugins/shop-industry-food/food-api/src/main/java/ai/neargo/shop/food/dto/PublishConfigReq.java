package ai.neargo.shop.food.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** Only intent is accepted; frozen contents, store scope and publication identities are server controlled. */
public record PublishConfigReq(@NotNull @Min(0) @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version, @NotNull OffsetDateTime effectiveAt,
        @Size(max=64) String approvalRef) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unknown publication field");
    }
}
