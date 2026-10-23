package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/** Original page contract bounds entity-library management reads. */
public record ListBundlesQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) { }
