package ai.neargo.shop.food.dto;

import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Local start-date rule; exact minute fields avoid permissive date/time coercion. */
public record WeekScheduleInput(
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer weekday,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String startTime,
        @JsonDeserialize(using=MaterialCatalogFacts.TextDeserializer.class) String endTime,
        @JsonDeserialize(using=MenuDefinitionPolicy.IntegerDeserializer.class) Integer endDayOffset) {
    @JsonAnySetter
    public void rejectUnknown(String key, Object value) {
        throw new IllegalArgumentException("Unknown schedule field");
    }
}
