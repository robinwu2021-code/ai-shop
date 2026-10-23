package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Null whole-object means legacy unknown; empty arrays inside a present object mean explicitly unconfigured. */
public record MenuScheduleVO(@JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validFrom,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String validTo,
        List<WeekScheduleInput> weekSchedules, List<ExceptionInput> exceptions) {
    public MenuScheduleVO {
        weekSchedules = weekSchedules == null ? null : List.copyOf(weekSchedules);
        exceptions = exceptions == null ? null : List.copyOf(exceptions);
    }
}
