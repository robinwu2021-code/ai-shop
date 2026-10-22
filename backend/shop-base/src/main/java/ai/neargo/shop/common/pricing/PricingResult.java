package ai.neargo.shop.common.pricing;

import java.util.List;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingFormula.FundedAllocation;
import ai.neargo.shop.common.pricing.PricingFormula.Step;

/** Auditable business-stage result; rejected/unavailable inputs never expose usable partial monetary output. */
public record PricingResult(Status status, PricingRuleCandidate candidate, List<LineResult> lines,
        List<StepResult> steps, List<RuleViolation> violations, String code) {
    public PricingResult {
        Objects.requireNonNull(status);
        lines = List.copyOf(lines);
        steps = List.copyOf(steps);
        violations = List.copyOf(violations);
        if (status == Status.CALCULATED) {
            if (candidate == null || lines.isEmpty() || !violations.isEmpty() || code != null) {
                throw new IllegalArgumentException("A calculated result requires complete business evidence");
            }
        } else if (candidate != null || !lines.isEmpty() || !steps.isEmpty() || code == null) {
            throw new IllegalArgumentException("A failed result must not retain partial prices");
        }
    }

    public enum Status { CALCULATED, REJECTED, UNAVAILABLE }

    public CalculationPlan.Stage stage() {
        return CalculationPlan.Stage.BUSINESS_PRE_TAX;
    }

    public MoneyMinor remainingPayable() {
        requireCalculated();
        return candidate.remainingPayable();
    }

    public MoneyMinor priceReductionTotal() {
        requireCalculated();
        MoneyMinor total = new MoneyMinor(candidate.remainingPayable().currency(), 0);
        for (var line : lines) {
            total = total.add(line.priceReduction());
        }
        return total;
    }

    /** Stored value/points are business offsets here; tax treatment is resolved separately by CKO-07. */
    public MoneyMinor assetOffsetTotal() {
        requireCalculated();
        MoneyMinor total = new MoneyMinor(candidate.remainingPayable().currency(), 0);
        for (var line : lines) {
            total = total.add(line.assetOffset());
        }
        return total;
    }

    private void requireCalculated() {
        if (status != Status.CALCULATED) {
            throw new IllegalStateException("No monetary output is available for a failed calculation");
        }
    }

    public record LineResult(PricingUnit source, MoneyMinor priceReduction,
            MoneyMinor assetOffset, MoneyMinor remaining) {
        public LineResult {
            Objects.requireNonNull(source);
            Objects.requireNonNull(priceReduction).requireNonNegative().requireJsonSafe();
            Objects.requireNonNull(assetOffset).requireNonNegative().requireJsonSafe();
            Objects.requireNonNull(remaining).requireNonNegative().requireJsonSafe();
        }
    }

    public record StepResult(Step step, MoneyMinor basisTotal, MoneyMinor appliedTotal,
            List<AllocationTrace> allocations) {
        public StepResult {
            Objects.requireNonNull(step);
            Objects.requireNonNull(basisTotal).requireNonNegative().requireJsonSafe();
            Objects.requireNonNull(appliedTotal).requireNonNegative().requireJsonSafe();
            allocations = List.copyOf(allocations);
        }
    }

    /** roundedShare/correction are absent for owner-frozen amounts rather than inventing a rounding explanation. */
    public record AllocationTrace(PricingPortion portion, MoneyMinor basis, MoneyMinor roundedShare,
            MoneyMinor correction, FundedAllocation allocation) {
        public AllocationTrace {
            Objects.requireNonNull(portion);
            Objects.requireNonNull(basis).requireNonNegative().requireJsonSafe();
            Objects.requireNonNull(allocation);
            if ((roundedShare == null) != (correction == null)) {
                throw new IllegalArgumentException("Rounding share and correction must be present together");
            }
            if (roundedShare != null) {
                roundedShare.requireNonNegative().requireJsonSafe();
                correction.requireJsonSafe();
            }
        }
    }
}
