package ai.neargo.shop.common.pricing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.common.pricing.PricingFormula.Algorithm;
import ai.neargo.shop.common.pricing.PricingFormula.Amount;
import ai.neargo.shop.common.pricing.PricingFormula.Failure;
import ai.neargo.shop.common.pricing.PricingFormula.Input;
import ai.neargo.shop.common.pricing.PricingFormula.OwnerAllocated;
import ai.neargo.shop.common.pricing.PricingFormula.Percent;
import ai.neargo.shop.common.pricing.PricingFormula.Step;
import ai.neargo.shop.common.pricing.PricingFormula.StepKey;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.PricingResult.AllocationTrace;
import ai.neargo.shop.common.pricing.PricingResult.LineResult;
import ai.neargo.shop.common.pricing.PricingResult.Status;
import ai.neargo.shop.common.pricing.PricingResult.StepResult;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Sequential minor-unit arithmetic shared by registered formulas; original legacy allocations are not reallocated. */
final class FixedPricingFormula implements PricingFormula {
    private final String formulaId;

    FixedPricingFormula(String formulaId) {
        this.formulaId = formulaId;
    }

    @Override
    public PricingResult compute(Input input) {
        String currency = input.facts().prices().currency();
        var units = input.facts().prices().units();
        var byPortion = new HashMap<PricingPortion, Integer>();
        long[] remaining = new long[units.size()];
        long[] reductions = new long[units.size()];
        long[] offsets = new long[units.size()];
        for (int i = 0; i < units.size(); i++) {
            byPortion.put(PricingPortion.from(units.get(i)), i);
            remaining[i] = units.get(i).baseAmount().minor();
        }
        var order = input.steps().stream().map(s -> new StepKey(s.asset(), s.kind())).toList();
        var benefits = new ArrayList<Benefit>();
        var activities = new ArrayList<Benefit>();
        var owners = new ArrayList<OwnerEvidence>();
        var results = new ArrayList<StepResult>();
        int previousStage = -1;
        for (int position = 0; position < input.steps().size(); position++) {
            var step = input.steps().get(position);
            int stage = stage(step.kind());
            if (stage < previousStage) {
                throw failure(Status.REJECTED, "FORMULA_STAGE_ORDER");
            }
            previousStage = stage;
            validateContract(input, step, order, position);
            var bases = new ArrayList<Long>();
            var indices = new ArrayList<Integer>();
            var seen = new HashSet<PricingPortion>();
            long base = 0;
            for (var quotedBase : step.quote().bases()) {
                Integer index = byPortion.get(quotedBase.portion());
                if (index == null || !seen.add(quotedBase.portion())) {
                    throw failure(Status.UNAVAILABLE, "OWNER_SCOPE_UNSUPPORTED");
                }
                if (!quotedBase.amount().equals(new MoneyMinor(currency, remaining[index]))) {
                    throw failure(Status.UNAVAILABLE, "OWNER_BASIS_CHANGED");
                }
                bases.add(remaining[index]);
                indices.add(index);
                base = Math.addExact(base, remaining[index]);
            }
            long total = total(step, base);
            List<Long> shares = step.operation() instanceof OwnerAllocated
                    ? step.quote().allocations().stream().map(a -> a.amount().minor()).toList()
                    : CouponCalculation.allocate(total, bases);
            var traces = new ArrayList<AllocationTrace>();
            var portions = new ArrayList<PortionAmount>();
            for (int i = 0; i < indices.size(); i++) {
                int index = indices.get(i);
                long share = shares.get(i);
                var expected = step.quote().allocations().get(i);
                if (!expected.amount().equals(new MoneyMinor(currency, share)) || share > remaining[index]) {
                    throw failure(Status.UNAVAILABLE, "OWNER_ALLOCATION_MISMATCH");
                }
                MoneyMinor rounded = step.operation() instanceof OwnerAllocated ? null
                        : new MoneyMinor(currency, rounded(total, bases.get(i), base));
                MoneyMinor correction = rounded == null ? null : new MoneyMinor(currency, share - rounded.minor());
                traces.add(new AllocationTrace(expected.portion(), new MoneyMinor(currency, remaining[index]),
                        rounded, correction, expected));
                portions.add(new PortionAmount(expected.portion(), expected.amount()));
                remaining[index] = Math.subtractExact(remaining[index], share);
                if (isOffset(step.kind())) {
                    offsets[index] = Math.addExact(offsets[index], share);
                } else {
                    reductions[index] = Math.addExact(reductions[index], share);
                }
            }
            Integer rate = step.kind() == BenefitKind.MANUAL_PERCENT && step.operation() instanceof Percent percent
                    ? percent.payableRateBp() : null;
            var benefit = new Benefit(step.asset(), step.kind(), new MoneyMinor(currency, total), portions, rate);
            if (step.kind() == BenefitKind.ACTIVITY) {
                activities.add(benefit);
            } else {
                benefits.add(benefit);
            }
            owners.add(step.quote().owner());
            results.add(new StepResult(step, new MoneyMinor(currency, base), new MoneyMinor(currency, total), traces));
        }
        if (!activities.equals(input.facts().appliedActivities())) {
            throw failure(Status.UNAVAILABLE, "ACTIVITY_FACT_MISMATCH");
        }
        var lines = new ArrayList<LineResult>();
        MoneyMinor due = new MoneyMinor(currency, 0);
        for (int i = 0; i < units.size(); i++) {
            lines.add(new LineResult(units.get(i), new MoneyMinor(currency, reductions[i]),
                    new MoneyMinor(currency, offsets[i]), new MoneyMinor(currency, remaining[i])));
            due = due.add(new MoneyMinor(currency, remaining[i]));
        }
        var candidate = new PricingRuleCandidate(benefits, owners, input.approvals(), due.requireJsonSafe());
        return new PricingResult(Status.CALCULATED, candidate, lines, results, List.of(), null);
    }

    private void validateContract(Input input, Step step, List<StepKey> order, int position) {
        var quote = step.quote();
        var config = input.config().definition();
        if (!formulaId.equals(config.formulaId()) || config.formulaVersion() != 1
                || !quote.owner().formulaId().equals(formulaId) || quote.owner().formulaVersion() != 1
                || !quote.owner().calculationBasis().equals(input.facts().context().calculationBasis())
                || !quote.owner().eligibleRemainder().currency().equals(input.facts().prices().currency())) {
            throw failure(Status.UNAVAILABLE, "OWNER_FORMULA_UNSUPPORTED");
        }
        if (!quote.basisHash().equals(PricingFormula.basisHash(input.facts(), input.config(), order,
                position, step.operation(), quote.algorithm(), quote.bases()))) {
            throw failure(Status.UNAVAILABLE, "OWNER_BASIS_CHANGED");
        }
        if (formulaId.equals("LEGACY_OWNER_COMPAT") && !BenefitSelection.isCoupon(step.kind())
                && step.kind() != BenefitKind.ACTIVITY) {
            throw failure(Status.REJECTED, "FORMULA_KIND_UNSUPPORTED");
        }
        boolean frozen = formulaId.equals("LEGACY_OWNER_COMPAT") || step.kind() == BenefitKind.ACTIVITY;
        if (frozen != (step.operation() instanceof OwnerAllocated)
                || quote.algorithm() != (frozen ? Algorithm.OWNER_FROZEN_V1 : Algorithm.SHARED_COUPON_V1)) {
            throw failure(Status.UNAVAILABLE, "OWNER_ALGORITHM_UNSUPPORTED");
        }
        if (formulaId.equals("DEDUCT_THEN_STORED_VALUE_V1") && step.kind() == BenefitKind.PERCENT_COUPON) {
            throw failure(Status.REJECTED, "FORMULA_KIND_UNSUPPORTED");
        }
        if (!frozen && (step.operation() instanceof Percent) != (step.kind() == BenefitKind.PERCENT_COUPON
                || step.kind() == BenefitKind.MANUAL_PERCENT)) {
            throw failure(Status.UNAVAILABLE, "OPERATION_KIND_MISMATCH");
        }
    }

    private static long total(Step step, long base) {
        if (step.operation() instanceof OwnerAllocated) {
            long total = 0;
            for (var allocation : step.quote().allocations()) {
                total = Math.addExact(total, allocation.amount().minor());
            }
            return total;
        }
        if (step.operation() instanceof Percent percent) {
            if (base < percent.minBaseMinor()) {
                throw failure(Status.REJECTED, "THRESHOLD_NOT_MET");
            }
            return CouponCalculation.discount("PERCENT", (long) percent.payableRateBp(), percent.capMinor(), base);
        }
        var amount = (Amount) step.operation();
        if (base < amount.minBaseMinor()) {
            throw failure(Status.REJECTED, "THRESHOLD_NOT_MET");
        }
        if (!BenefitSelection.isCoupon(step.kind()) && amount.amountMinor() > base) {
            throw failure(Status.REJECTED, "INSUFFICIENT_SCOPE_BALANCE");
        }
        return CouponCalculation.discount("CASH", amount.amountMinor(), null, base);
    }

    private static long rounded(long total, long lineBase, long base) {
        if (base == 0) {
            return 0;
        }
        var qr = BigInteger.valueOf(total).multiply(BigInteger.valueOf(lineBase))
                .divideAndRemainder(BigInteger.valueOf(base));
        return qr[0].add(qr[1].multiply(BigInteger.TWO).compareTo(BigInteger.valueOf(base)) >= 0
                ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
    }

    private static boolean isOffset(BenefitKind kind) {
        return kind == BenefitKind.STORED_VALUE || kind == BenefitKind.POINTS;
    }

    private static int stage(BenefitKind kind) {
        return switch (kind) {
            case ACTIVITY -> 0;
            case CASH_COUPON, DEDUCTION_COUPON -> 1;
            case PERCENT_COUPON -> 2;
            case MANUAL_PERCENT, MANUAL_AMOUNT -> 3;
            case STORED_VALUE -> 4;
            case POINTS -> 5;
            case MANUAL_WAIVER -> 6;
            case CASH_ROUNDING -> 7;
        };
    }

    private static Failure failure(Status status, String code) {
        return new Failure(status, code);
    }
}
