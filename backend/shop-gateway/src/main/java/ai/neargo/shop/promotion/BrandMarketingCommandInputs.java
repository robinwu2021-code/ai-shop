package ai.neargo.shop.promotion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Action-specific HTTP binding keeps malformed amounts, revisions and dates out of business services. */
public final class BrandMarketingCommandInputs {
    private BrandMarketingCommandInputs() { }

    public record PublishActivity(@NotBlank String activityId, @NotNull @PositiveOrZero Long activityRevision,
                                  @NotBlank String costRuleVersionNo) { }

    public record PauseActivity(@NotBlank String activityId, @NotNull @PositiveOrZero Long activityRevision) { }

    /** Type and draft status are display metadata; funding shares are the executable rule inputs. */
    @JsonIgnoreProperties({"type", "status"})
    public record SaveRule(@NotBlank @Size(max = 100) String name,
                           @NotBlank @Pattern(regexp = "CNY") String currency,
                           @NotNull @Min(0) @Max(10000) Integer headquartersBasisPoints,
                           @NotNull @Min(0) @Max(10000) Integer storeBasisPoints) {
        @AssertTrue
        @JsonIgnore
        public boolean isBalanced() {
            return headquartersBasisPoints == null || storeBasisPoints == null
                    || (long) headquartersBasisPoints + storeBasisPoints == 10000;
        }
    }

    public record PublishRule(@NotBlank String ruleId, @NotNull @PositiveOrZero Long ruleRevision) { }

    public record GenerateStatement(@NotBlank String entityNo, @NotBlank @Pattern(regexp = "CNY") String currency,
                                    @NotNull @PastOrPresent Instant throughAt) { }

    public record StatementRevision(@NotBlank String entityNo, @NotBlank String statementId,
                                    @NotNull @PositiveOrZero Long statementRevision) { }
}
