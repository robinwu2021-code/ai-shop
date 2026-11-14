package ai.neargo.shop.common.pricing;

import ai.neargo.shop.spi.trade.PricingFeeFacts.Application;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Correction;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Entry;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Method;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Plan;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Resolved;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Rule;
import ai.neargo.shop.spi.trade.PricingFeeFacts.ScopeBasis;
import ai.neargo.shop.spi.trade.PricingFeeFacts.Source;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One shared fee arithmetic/portion plan. No arbitrary expressions or caller monetary inputs. */
public final class PricingFeeEngine {
    public Resolved calculate(
            PricingFacts prices,
            Source source,
            Rule rule,
            ScopeBasis previous,
            List<Application> applications) {
        if (source == null
                || rule == null
                || previous == null
                || !"ACTIVE".equals(rule.status())
                || !source.ruleNo().equals(rule.ruleNo())
                || !source.revisionNo().equals(rule.revisionNo())
                || source.kind() != rule.definition().kind()) bad();
        var d = rule.definition();
        if (d == null
                || d.taxClass() == null
                || d.taxClass() == TaxClass.UNKNOWN
                || d.unitAmountMinor() < 0
                || d.method() == null
                || source.quantity() == null
                || source.quantity().signum() <= 0
                || source.quantity().scale() > 6) bad();
        if (d.method() == Method.PERCENT
                && (d.rateBp() == null || d.rateBp() < 0 || d.rateBp() > 10000)) bad();
        if (!d.currency().equals(prices.currency())
                || !d.unit().equals(source.unit())
                || !source.scopeNo().equals(previous.scopeNo())
                || !rule.ruleNo().equals(previous.ruleNo())
                || !prices.currency().equals(previous.currency())) bad();
        var wanted = new HashSet<>(source.basisSourceRefs());
        if (wanted.size() != source.basisSourceRefs().size()) bad();
        var units = prices.units().stream().filter(u -> wanted.contains(u.sourceLineNo())).toList();
        if (units.isEmpty()
                || !wanted.equals(
                        new HashSet<>(units.stream().map(PricingUnit::sourceLineNo).toList())))
            bad();
        long current = 0;
        for (var unit : units) current = Math.addExact(current, unit.baseAmount().minor());
        long basis = Math.addExact(previous.goodsBasisMinor(), current);
        long target;
        if (source.waiverEvidence() != null) {
            if (source.waiverEvidence().isBlank() || source.waiverEvidence().length() > 255) bad();
            target = 0;
        } else
            target =
                    switch (d.method()) {
                        case FIXED -> d.unitAmountMinor();
                        case PER_QUANTITY ->
                                BigDecimal.valueOf(d.unitAmountMinor())
                                        .multiply(source.quantity())
                                        .setScale(0, RoundingMode.HALF_UP)
                                        .longValueExact();
                        case PERCENT ->
                                BigDecimal.valueOf(basis)
                                        .multiply(BigDecimal.valueOf(d.rateBp()))
                                        .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP)
                                        .longValueExact();
                        case MINIMUM -> Math.max(0, Math.subtractExact(d.unitAmountMinor(), basis));
                    };
        new MoneyMinor(prices.currency(), target).requireNonNegative().requireJsonSafe();
        long additional = Math.max(0, Math.subtractExact(target, previous.feeMinor()));
        var entries = new ArrayList<Entry>();
        {
            var weights = units.stream().map(u -> u.baseAmount().minor()).toList();
            if (current == 0) weights = units.stream().map(u -> 1L).toList();
            var shares = PricingFeeAllocation.allocate(additional, weights);
            for (int i = 0; i < units.size(); i++)
                entries.add(
                        new Entry(
                                PricingPortion.from(units.get(i)),
                                shares.get(i),
                                d.taxClass(),
                                "FEE:"
                                        + PricingSnapshotHash.of(
                                                        List.of(rule.ruleNo(), source.scopeNo()))
                                                .substring(0, 32),
                                rule.ruleNo(),
                                rule.revisionNo(),
                                source.scopeNo()));
        }
        long reduction = Math.max(0, Math.subtractExact(previous.feeMinor(), target));
        var corrections = new ArrayList<Correction>();
        for (var app : applications) {
            if (reduction == 0) break;
            long take = Math.min(app.adjustedFeeMinor(), reduction);
            if (take > 0) {
                long next = app.adjustedFeeMinor() - take;
                String hash =
                        PricingSnapshotHash.of(
                                List.of(
                                        "FEE_SCOPE_ADVICE_V1",
                                        app.applicationNo(),
                                        source,
                                        rule,
                                        previous,
                                        basis,
                                        next));
                corrections.add(
                        new Correction(app.applicationNo(), app.adjustedFeeMinor(), next, hash));
                reduction -= take;
            }
        }
        if (reduction != 0) bad();
        return new Resolved(
                source, rule, previous, current, target, additional, entries, corrections);
    }

    public void validatePlan(Plan plan, PricingFacts prices) {
        if (plan == null) return;
        if (!PricingSnapshotHash.of(plan.fees()).equals(plan.planHash()) || plan.fees().size() > 32)
            bad();
        var unique = new HashSet<String>();
        for (var resolved : plan.fees()) {
            if (!unique.add(resolved.source().ruleNo() + ":" + resolved.source().scopeNo())) bad();
            var apps =
                    resolved.corrections().stream()
                            .map(
                                    c ->
                                            new Application(
                                                    c.applicationNo(),
                                                    null,
                                                    null,
                                                    null,
                                                    resolved.rule().ruleNo(),
                                                    resolved.rule().revisionNo(),
                                                    resolved.source().scopeNo(),
                                                    prices.currency(),
                                                    c.currentFeeMinor(),
                                                    c.currentFeeMinor(),
                                                    null))
                            .toList();
            var rebuilt =
                    calculate(
                            prices, resolved.source(), resolved.rule(), resolved.previous(), apps);
            if (!rebuilt.equals(resolved)) bad();
        }
    }

    private static void bad() {
        throw new IllegalArgumentException("Invalid fee owner facts");
    }
}
