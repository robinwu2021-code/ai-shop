package ai.neargo.shop.promotion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** The generic App command is bound once into the executable fixed-cut input at its application boundary. */
@JsonIgnoreProperties("ruleLabel")
public record BrandOrderActivityInput(@NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="reduction") String type,
        @NotBlank @Pattern(regexp="draft") String status,
        @NotEmpty @Size(max=1000) List<@NotBlank @Size(max=64) String> storeIds,
        @NotBlank @Pattern(regexp="CNY") String currency,
        @NotNull @Min(2) @Max(2) Integer currencyScale,
        @NotNull @Positive Long amountMinor, @NotNull @PositiveOrZero Long thresholdMinor,
        @NotNull Instant startsAt, @NotNull Instant endsAt,
        @PositiveOrZero Long budgetMinor, @Positive Long quotaLimit, @Positive Integer perPerson,
        String activityId, @PositiveOrZero Long activityRevision,
        @Null Object advanced, @Null Object configuration) {
}
