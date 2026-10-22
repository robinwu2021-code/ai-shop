package ai.neargo.shop.common.pricing;

import java.math.BigInteger;
import java.util.ArrayList;
import ai.neargo.shop.common.promotion.CouponCalculation;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import ai.neargo.shop.common.pricing.PricingFormula.Algorithm;
import ai.neargo.shop.common.pricing.PricingFormula.Percent;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.Benefit;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.PortionAmount;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;

/** Independent reconciliation of lines, applied benefits, owner allocations and funders before result admission. */
public final class PricingInvariantChecker {
    public List<String> check(PricingRuleFacts facts, PricingResult result) {
        var issues = new LinkedHashSet<String>();
        if (result.status() != PricingResult.Status.CALCULATED) {
            return List.of("RESULT_NOT_CALCULATED");
        }
        try {
            String currency = facts.prices().currency();
            var zero = new MoneyMinor(currency, 0);
            var balances = new HashMap<PricingPortion, MoneyMinor>();
            var prices = new HashMap<PricingPortion, MoneyMinor>();
            var offsets = new HashMap<PricingPortion, MoneyMinor>();
            for (var unit : facts.prices().units()) {
                balances.put(PricingPortion.from(unit), unit.baseAmount());
                prices.put(PricingPortion.from(unit), zero);
                offsets.put(PricingPortion.from(unit), zero);
            }
            var benefits = new ArrayList<Benefit>();
            var activities = new ArrayList<Benefit>();
            for (var evidence : result.steps()) {
                var step = evidence.step();
                var bases = zero;
                var applied = zero;
                var portions = new ArrayList<PortionAmount>();
                if (evidence.allocations().size() != step.quote().allocations().size()) {
                    issues.add("OWNER_TRACE_MISMATCH");
                }
                var expectedShares = step.quote().algorithm() == Algorithm.OWNER_FROZEN_V1 ? List.<Long>of()
                        : CouponCalculation.allocate(evidence.appliedTotal().minor(), evidence.allocations().stream()
                                .map(trace -> trace.basis().minor()).toList());
                for (int i = 0; i < evidence.allocations().size(); i++) {
                    var trace = evidence.allocations().get(i);
                    var allocation = trace.allocation();
                    if (!trace.portion().equals(allocation.portion())
                            || !trace.basis().equals(balances.get(trace.portion()))) {
                        issues.add("STEP_BASIS_MISMATCH");
                    }
                    if (i >= step.quote().allocations().size()
                            || !allocation.equals(step.quote().allocations().get(i))
                            || !trace.basis().equals(step.quote().bases().get(i).amount())) {
                        issues.add("OWNER_TRACE_MISMATCH");
                    }
                    if (step.quote().algorithm() == Algorithm.OWNER_FROZEN_V1) {
                        if (trace.roundedShare() != null || trace.correction() != null) {
                            issues.add("FROZEN_ROUNDING_INVENTED");
                        }
                    } else if (trace.roundedShare() == null || trace.correction() == null
                            || !trace.roundedShare().add(trace.correction()).equals(allocation.amount())) {
                        issues.add("ROUNDING_NOT_CONSERVED");
                    } else {
                        var expectedRound = new MoneyMinor(currency, rounded(evidence.appliedTotal().minor(),
                                trace.basis().minor(), evidence.basisTotal().minor()));
                        if (!expectedRound.equals(trace.roundedShare())
                                || expectedShares.get(i) != allocation.amount().minor()) {
                            issues.add("ROUNDING_EVIDENCE_MISMATCH");
                        }
                    }
                    var funded = zero;
                    for (var funder : allocation.funders()) {
                        funded = funded.add(funder.amount());
                    }
                    if (!funded.equals(allocation.amount())) {
                        issues.add("FUNDER_NOT_CONSERVED");
                    }
                    var balance = balances.get(trace.portion());
                    if (balance == null) {
                        issues.add("UNKNOWN_SOURCE_PORTION");
                        continue;
                    }
                    balances.put(trace.portion(), balance.subtract(allocation.amount()).requireNonNegative());
                    boolean offset = step.kind() == BenefitKind.STORED_VALUE || step.kind() == BenefitKind.POINTS;
                    var totals = offset ? offsets : prices;
                    totals.put(trace.portion(), totals.get(trace.portion()).add(allocation.amount()));
                    bases = bases.add(trace.basis());
                    applied = applied.add(allocation.amount());
                    portions.add(new PortionAmount(trace.portion(), allocation.amount()));
                }
                if (!bases.equals(evidence.basisTotal()) || !applied.equals(evidence.appliedTotal())) {
                    issues.add("STEP_TOTAL_MISMATCH");
                }
                Integer rate = step.kind() == BenefitKind.MANUAL_PERCENT && step.operation() instanceof Percent percent
                        ? percent.payableRateBp() : null;
                var benefit = new Benefit(step.asset(), step.kind(), applied, portions, rate);
                if (step.kind() == BenefitKind.ACTIVITY) {
                    activities.add(benefit);
                } else {
                    benefits.add(benefit);
                }
            }
            if (!benefits.equals(result.candidate().benefits()) || !activities.equals(facts.appliedActivities())
                    || !result.steps().stream().map(s -> s.step().quote().owner()).toList()
                            .equals(result.candidate().owners())) {
                issues.add("BENEFIT_STEP_MISMATCH");
            }
            if (!result.lines().stream().map(PricingResult.LineResult::source).toList()
                    .equals(facts.prices().units())) {
                issues.add("SOURCE_LINES_MISMATCH");
            }
            var due = zero;
            var allPrices = zero;
            var allOffsets = zero;
            for (var line : result.lines()) {
                var portion = PricingPortion.from(line.source());
                if (!line.source().baseAmount()
                        .equals(line.priceReduction().add(line.assetOffset()).add(line.remaining()))) {
                    issues.add("LINE_NOT_CONSERVED");
                }
                if (!line.priceReduction().equals(prices.get(portion))
                        || !line.assetOffset().equals(offsets.get(portion))
                        || !line.remaining().equals(balances.get(portion))) {
                    issues.add("LINE_STEP_TOTAL_MISMATCH");
                }
                due = due.add(line.remaining());
                allPrices = allPrices.add(line.priceReduction());
                allOffsets = allOffsets.add(line.assetOffset());
            }
            if (!due.equals(result.candidate().remainingPayable())
                    || !facts.prices().baseTotal().equals(due.add(allPrices).add(allOffsets))) {
                issues.add("ORDER_NOT_CONSERVED");
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            issues.add("AMOUNT_OR_CURRENCY_INVALID");
        }
        return List.copyOf(issues);
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

}
