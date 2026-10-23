package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Table configuration never accepts caller-owned identity or occupancy state. */
public record ListTablesQuery(
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer size,
        @Size(max=32) String areaNo,
        @Pattern(regexp="ACTIVE|DISABLED") String status,
        @Pattern(regexp="IDLE|IN_USE|NEEDS_CLEANING") String turnoverState,
        @Size(max=64) String keyword) {}
