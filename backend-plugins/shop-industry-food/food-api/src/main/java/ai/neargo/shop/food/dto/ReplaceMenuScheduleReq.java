package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Explicit replacement section prevents partial schedule writes from claiming a complete menu definition. */
public record ReplaceMenuScheduleReq(@NotNull
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String section,
        @NotNull @JsonDeserialize(using=CatalogRevisionDeserializer.class) Long version,
        @NotNull @Size(max=100) List<@NotNull @Valid WeekScheduleInput> weekSchedules,
        @NotNull @Size(max=366) List<@NotNull @Valid ExceptionInput> exceptions,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class)
        String validFrom,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validTo) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown schedule field");
    }
}
