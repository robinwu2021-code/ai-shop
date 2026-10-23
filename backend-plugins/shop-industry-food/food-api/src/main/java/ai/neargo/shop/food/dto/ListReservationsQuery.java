package ai.neargo.shop.food.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** Half-open overlap window; controller and service both validate the range. */
public record ListReservationsQuery(@Min(1) Integer page,@Min(1) @Max(100) Integer size,
        @NotNull Instant from,@NotNull Instant to,String status) { }
