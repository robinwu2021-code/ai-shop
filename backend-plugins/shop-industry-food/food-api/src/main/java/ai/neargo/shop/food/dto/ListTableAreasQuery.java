package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** Optional paging follows the platform defaults without exposing another store selector. */
public record ListTableAreasQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size,
        @Pattern(regexp="ACTIVE|DISABLED") String status) { }
