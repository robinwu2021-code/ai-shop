package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Bounds a page of the entity's shared option library; sale scope is a separate published concern. */
public record ListOptionGroupsQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) { }
