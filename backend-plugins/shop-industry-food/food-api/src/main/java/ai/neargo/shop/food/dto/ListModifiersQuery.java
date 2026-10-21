package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/** Bounded management pagination; visibility is constrained before count. */
public record ListModifiersQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) { }
