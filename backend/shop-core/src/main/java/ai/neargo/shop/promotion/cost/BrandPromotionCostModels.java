package ai.neargo.shop.promotion.cost;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Ordinary marketing cost facts carry no payment command or settlement account. */
public final class BrandPromotionCostModels {
    private BrandPromotionCostModels() { }
    public record SaveRule(@NotBlank @Size(max=100) String title,
                           @NotBlank @Pattern(regexp="CNY") String currency,
                           @Min(0) @Max(10000) int headquartersBasisPoints,
                           @Min(0) @Max(10000) int storeBasisPoints) {
        @AssertTrue @JsonIgnore
        public boolean isBalanced() { return headquartersBasisPoints + storeBasisPoints == 10000; }
    }
    public record RuleView(String ruleVersionNo, String title, String currency, int headquartersBasisPoints,
                           int storeBasisPoints, String status, long revision, Instant createdAt, Instant publishedAt) { }
    public record GenerateStatement(@NotBlank @Pattern(regexp="CNY") String currency,
                                    @NotNull @jakarta.validation.constraints.PastOrPresent Instant throughAt) { }
    public record RevisionCommand(@Min(0) long expectedRevision) { }
    public record FactView(String factNo, String sourceType, String sourceNo, String eventNo, String eventKind,
                           String resourceVersion, String orderNo, String storeNo, String consumerEntityNo,
                           String costRuleVersionNo, long amountMinor, long headquartersMinor, long storeMinor,
                           Instant occurredAt) { }
    public record StatementView(String statementNo, String expenseEntityNo, String currency, String status,
                                long revision, long totalMinor, long headquartersMinor, long storeMinor,
                                long discrepancyMinor, Instant throughAt, Instant createdAt, Instant confirmedAt,
                                List<FactView> facts) {
        public StatementView { facts = List.copyOf(facts); }
    }
}
