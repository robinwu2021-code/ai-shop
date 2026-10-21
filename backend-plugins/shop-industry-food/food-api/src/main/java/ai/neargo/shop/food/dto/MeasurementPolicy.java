package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;

/** Count configuration is supported first; weight requires the actual quote/inventory unit contract. */
public record MeasurementPolicy(String unit, String mode, Integer scale, BigDecimal allowedDeviation) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown unit field"); }
}
