package ai.neargo.shop.food.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Operational service allowances and intended banquet plans retain actual shared quote evidence,
 * never a paid assertion.
 */
public final class ServiceEntitlementContracts {
    private ServiceEntitlementContracts() {}

    public record QuoteRef(
            @NotBlank String cartNo,
            @Min(0) long cartVersion,
            @NotBlank String quoteNo,
            @NotBlank String confirmationDigest) {}

    public record Grant(
            @Min(0) long formVersion,
            @NotBlank String attendeeNo,
            @NotBlank String menuItemNo,
            @NotBlank String goodsNo,
            @NotBlank String skuNo,
            @NotNull BigDecimal quantity,
            @Valid @NotNull QuoteRef quote,
            @NotBlank @Size(max = 255) String reason) {}

    /**
     * Original accepted FOOD line determines quantity; callers cannot name an arbitrary consumption
     * source or amount.
     */
    public record Consume(
            @Min(0) long entitlementVersion,
            @NotBlank String batchNo,
            @NotBlank String foodLineNo,
            @NotBlank @Size(max = 255) String reason) {}

    public record Entitlement(
            String entitlementNo,
            String formNo,
            String attendeeNo,
            String goodsNo,
            String skuNo,
            BigDecimal granted,
            BigDecimal consumed,
            String unit,
            Instant validFrom,
            Instant validUntil,
            String quoteNo,
            String sourceHash,
            long version) {}

    public record Extension(
            @Min(0) long formVersion,
            @NotNull Instant validUntil,
            @NotBlank String capacitySlotNo,
            @Valid @NotNull QuoteRef quote,
            @NotBlank @Size(max = 255) String reason) {}

    public record ExtensionReceipt(
            String eventNo,
            String formNo,
            Instant beforeUntil,
            Instant validUntil,
            String capacityClaimNo,
            String quoteNo,
            long formVersion) {}

    public record Dish(
            @NotBlank String menuItemNo,
            @NotBlank String goodsNo,
            @NotBlank String skuNo,
            @NotNull BigDecimal quantity,
            @NotBlank String unit) {}

    /**
     * Budget is an intended operational ceiling; it is explicitly separate from the original
     * calculated quote.
     */
    public record Plan(
            @Min(0) long formVersion,
            @Min(1) @Max(1000) int plannedPersons,
            @Min(0) long intendedBudgetMinor,
            @NotNull @Size(min = 1, max = 100) List<@Valid Dish> dishes,
            @NotBlank @Size(max = 255) String reason) {
        public Plan {
            dishes = dishes == null ? null : List.copyOf(dishes);
        }
    }

    public record PlanRevision(
            String planNo,
            String formNo,
            long revision,
            int plannedPersons,
            String currency,
            long intendedBudgetMinor,
            List<Dish> dishes,
            String contentHash,
            long formVersion) {
        public PlanRevision {
            dishes = List.copyOf(dishes);
        }
    }

    public record ConfirmAttendance(
            @Min(0) long formVersion,
            @NotBlank String planNo,
            @Valid @NotNull QuoteRef quote,
            String depositRef,
            @NotBlank @Size(max = 255) String reason) {}

    public record AttendanceConfirmation(
            String confirmationNo,
            String formNo,
            String planNo,
            int actualPersons,
            String rosterHash,
            String quoteNo,
            String quoteHash,
            String currency,
            long calculatedReceivableMinor,
            long formVersion) {}
}
