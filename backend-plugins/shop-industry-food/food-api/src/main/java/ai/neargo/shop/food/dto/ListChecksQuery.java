package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/** Dining reception contract; monetary authority remains outside this resource. */
public record ListChecksQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size, String status, LocalDate businessDate) {}
