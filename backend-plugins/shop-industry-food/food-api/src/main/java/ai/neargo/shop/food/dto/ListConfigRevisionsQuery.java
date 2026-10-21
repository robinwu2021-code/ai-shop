package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/** Subject and authorized store are resolved separately; filters cannot choose an owner. */
public record ListConfigRevisionsQuery(@NotBlank @Size(max=24) String configKind,
        @NotBlank @Size(max=32) String configNo, @Min(1) Integer page, @Min(1) @Max(100) Integer size) { }
