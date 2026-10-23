package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Management filters are evaluated before pagination; they confer no product visibility. */
public record ListDishesQuery(@Min(1) Integer page, @Min(1) @Max(100) Integer size,
        @Size(max=128) String keyword, @Pattern(regexp="SINGLE|BUNDLE|ADDON") String dishKind) { }
