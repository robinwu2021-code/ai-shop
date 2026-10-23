package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Dining reception contract; monetary authority remains outside this resource. */
public record ListCheckActionsQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size) {}
